package org.mzpeak.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;
import ms.numpress.MSNumpress;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes mzPeak datasets in either {@code point} layout (the default) or MS-Numpress {@code chunk} layout.
 *
 * <p>Point layout: spectra (profile + centroid), chromatograms, wavelength (UV/DAD) spectra, and file/run
 * metadata; round-trips through {@link MzPeakReader}. Numpress layout additionally encodes m/z with Numpress
 * linear (MS:1002312) and intensity with Numpress SLOF (MS:1002314) per chunk.
 *
 * <p>Per-spectrum/scan/selected-ion CV/user {@code parameters} lists round-trip through both writer and reader.
 * The writer also emits the reference schema's richer metadata: derived spectrum columns (observed m/z range,
 * base peak, total ion current, spectrum type), scan windows, precursor activation, typed ion-mobility and
 * imaging-position columns, scan settings, and wavelength facets — so a read &rarr; write cycle preserves
 * everything {@link MzPeakReader} surfaces.
 */
public final class MzPeakWriter {

    private static final String SCHEMA_PROPERTY = "parquet.example.schema";

    private MzPeakWriter() {
    }

    public static void writeDirectory(Path directory, List<Spectrum> spectra) {
        writeDirectory(directory, spectra, List.of());
    }

    public static void writeDirectory(Path directory, List<Spectrum> spectra, List<Chromatogram> chromatograms) {
        writeDirectory(directory, spectra, chromatograms, org.mzpeak.model.meta.FileMetadata.EMPTY);
    }

    public static void writeDirectory(Path directory, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                      org.mzpeak.model.meta.FileMetadata fileMetadata) {
        writeDirectory(directory, spectra, chromatograms, List.of(), List.of(), fileMetadata);
    }

    /**
     * Write an unpacked {@code *.mzpeak/} directory, including wavelength spectra, scan settings, and
     * file/run metadata. This is the full round-trip form consumed by the forward-backward test.
     *
     * <p>Spectra may come in any order; they are written in index order (the caller's list is not modified).
     * Spectrum, chromatogram and wavelength-spectrum indices must be unique and non-negative, as must every
     * precursor_index; otherwise an {@link MzPeakException} is thrown before anything is written.
     */
    public static void writeDirectory(Path directory, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                      List<WavelengthSpectrum> wavelengthSpectra, List<ScanSettings> scanSettings,
                                      org.mzpeak.model.meta.FileMetadata fileMetadata) {
        List<Spectrum> ordered = validateAndOrder(spectra, chromatograms, wavelengthSpectra);
        boolean hasProfile = ordered.stream().anyMatch(s -> profileRows(s) > 0);
        boolean hasPeaks = ordered.stream().anyMatch(s -> peakRows(s) > 0);
        boolean hasChromatograms = !chromatograms.isEmpty();
        boolean hasWavelength = !wavelengthSpectra.isEmpty();
        java.util.Map<String, String> footer = FooterMetadataWriter.serialize(fileMetadata);
        try {
            Files.createDirectories(directory);
            writeSpectrumMetadata(directory.resolve("spectra_metadata.parquet"), ordered, footer);
            if (hasProfile) {
                writeSpectrumPoints(directory.resolve("spectra_data.parquet"), ordered, false);
            }
            if (hasPeaks) {
                writeSpectrumPoints(directory.resolve("spectra_peaks.parquet"), ordered, true);
            }
            if (hasChromatograms) {
                writeChromatogramMetadata(directory.resolve("chromatograms_metadata.parquet"), chromatograms);
                writeChromatogramData(directory.resolve("chromatograms_data.parquet"), chromatograms);
            }
            if (hasWavelength) {
                writeWavelengthMetadata(directory.resolve("wavelength_spectra_metadata.parquet"), wavelengthSpectra);
                writeWavelengthData(directory.resolve("wavelength_spectra_data.parquet"), wavelengthSpectra);
            }
            writeManifest(directory.resolve("mzpeak_index.json"), hasProfile, hasPeaks, hasChromatograms,
                    hasWavelength, scanSettings, fileMetadata);
        } catch (IOException e) {
            throw new MzPeakException("Failed to write mzPeak dataset to " + directory, e);
        }
    }

    public static void writeArchive(Path archive, List<Spectrum> spectra, List<Chromatogram> chromatograms) {
        writeArchive(archive, spectra, chromatograms, org.mzpeak.model.meta.FileMetadata.EMPTY);
    }

    public static void writeArchive(Path archive, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                    org.mzpeak.model.meta.FileMetadata fileMetadata) {
        writeArchive(archive, spectra, chromatograms, List.of(), List.of(), fileMetadata);
    }

    /** Write a single-file STORED {@code .mzpeak} ZIP (atomically: build in temp, then move into place). */
    public static void writeArchive(Path archive, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                    List<WavelengthSpectrum> wavelengthSpectra, List<ScanSettings> scanSettings,
                                    org.mzpeak.model.meta.FileMetadata fileMetadata) {
        packArchive(archive, dir ->
                writeDirectory(dir, spectra, chromatograms, wavelengthSpectra, scanSettings, fileMetadata));
    }

    // ---- Numpress-encoded chunk layout API --------------------------------------------------------

    /**
     * Write an unpacked {@code *.mzpeak/} directory using MS-Numpress chunk encoding.
     * m/z is encoded with Numpress linear ({@code MS:1002312}); intensity with Numpress SLOF
     * ({@code MS:1002314}). Chromatograms are written in standard point layout. Round-trips through
     * {@link MzPeakReader} on any container and layout variant.
     */
    public static void writeDirectoryNumpress(Path directory, List<Spectrum> spectra,
                                             List<Chromatogram> chromatograms) {
        writeDirectoryNumpress(directory, spectra, chromatograms,
                org.mzpeak.model.meta.FileMetadata.EMPTY, NumpressIntensityEncoding.SLOF);
    }

    public static void writeDirectoryNumpress(Path directory, List<Spectrum> spectra,
                                             List<Chromatogram> chromatograms,
                                             org.mzpeak.model.meta.FileMetadata fileMetadata) {
        writeDirectoryNumpress(directory, spectra, chromatograms, List.of(), List.of(), fileMetadata,
                NumpressIntensityEncoding.SLOF);
    }

    /**
     * Write an unpacked directory with Numpress encoding and a caller-chosen intensity encoding.
     * Use {@link NumpressIntensityEncoding#SLOF} (default) for floating-point intensities and
     * {@link NumpressIntensityEncoding#PIC} for non-negative integer ion-count arrays.
     */
    public static void writeDirectoryNumpress(Path directory, List<Spectrum> spectra,
                                             List<Chromatogram> chromatograms,
                                             org.mzpeak.model.meta.FileMetadata fileMetadata,
                                             NumpressIntensityEncoding intensityEncoding) {
        writeDirectoryNumpress(directory, spectra, chromatograms, List.of(), List.of(), fileMetadata, intensityEncoding);
    }

    /**
     * Full Numpress directory form including wavelength spectra and scan settings.
     *
     * <p>Spectra may come in any order; they are written in index order (the caller's list is not modified).
     * Spectrum, chromatogram and wavelength-spectrum indices must be unique and non-negative, as must every
     * precursor_index; otherwise an {@link MzPeakException} is thrown before anything is written.
     */
    public static void writeDirectoryNumpress(Path directory, List<Spectrum> spectra,
                                             List<Chromatogram> chromatograms,
                                             List<WavelengthSpectrum> wavelengthSpectra,
                                             List<ScanSettings> scanSettings,
                                             org.mzpeak.model.meta.FileMetadata fileMetadata,
                                             NumpressIntensityEncoding intensityEncoding) {
        List<Spectrum> ordered = validateAndOrder(spectra, chromatograms, wavelengthSpectra);
        boolean hasPeaks = ordered.stream().anyMatch(s -> peakRows(s) > 0);
        boolean hasChromatograms = !chromatograms.isEmpty();
        boolean hasWavelength = !wavelengthSpectra.isEmpty();
        java.util.Map<String, String> footer = FooterMetadataWriter.serialize(fileMetadata);
        try {
            Files.createDirectories(directory);
            writeSpectrumMetadata(directory.resolve("spectra_metadata.parquet"), ordered, footer);
            writeNumpressChunks(directory.resolve("spectra_data.parquet"), ordered, false, intensityEncoding);
            if (hasPeaks) {
                writeNumpressChunks(directory.resolve("spectra_peaks.parquet"), ordered, true, intensityEncoding);
            }
            if (hasChromatograms) {
                writeChromatogramMetadata(directory.resolve("chromatograms_metadata.parquet"), chromatograms);
                writeChromatogramData(directory.resolve("chromatograms_data.parquet"), chromatograms);
            }
            if (hasWavelength) {
                writeWavelengthMetadata(directory.resolve("wavelength_spectra_metadata.parquet"), wavelengthSpectra);
                writeWavelengthData(directory.resolve("wavelength_spectra_data.parquet"), wavelengthSpectra);
            }
            writeManifest(directory.resolve("mzpeak_index.json"), true, hasPeaks, hasChromatograms,
                    hasWavelength, scanSettings, fileMetadata);
        } catch (IOException e) {
            throw new MzPeakException("Failed to write Numpress mzPeak dataset to " + directory, e);
        }
    }

    /** Write a single-file STORED {@code .mzpeak} ZIP with Numpress encoding. */
    public static void writeArchiveNumpress(Path archive, List<Spectrum> spectra,
                                           List<Chromatogram> chromatograms) {
        writeArchiveNumpress(archive, spectra, chromatograms, org.mzpeak.model.meta.FileMetadata.EMPTY);
    }

    public static void writeArchiveNumpress(Path archive, List<Spectrum> spectra,
                                           List<Chromatogram> chromatograms,
                                           org.mzpeak.model.meta.FileMetadata fileMetadata) {
        writeArchiveNumpress(archive, spectra, chromatograms, List.of(), List.of(), fileMetadata);
    }

    /** Full Numpress archive form including wavelength spectra and scan settings. */
    public static void writeArchiveNumpress(Path archive, List<Spectrum> spectra,
                                           List<Chromatogram> chromatograms,
                                           List<WavelengthSpectrum> wavelengthSpectra,
                                           List<ScanSettings> scanSettings,
                                           org.mzpeak.model.meta.FileMetadata fileMetadata) {
        packArchive(archive, dir ->
                writeDirectoryNumpress(dir, spectra, chromatograms, wavelengthSpectra, scanSettings, fileMetadata,
                        NumpressIntensityEncoding.SLOF));
    }

    /**
     * Shared archive helper: write to a temp directory, pack as STORED ZIP, and move atomically into place.
     * Temp files are cleaned up in all cases.
     */
    private static void packArchive(Path archive, Consumer<Path> writer) {
        Path parent = archive.toAbsolutePath().getParent();
        Path tmpDir = null;
        Path tmpZip = null;
        try {
            tmpDir = Files.createTempDirectory(parent, ".mzpeak-write-");
            tmpZip = Files.createTempFile(parent, ".mzpeak-write-", ".zip");
            writer.accept(tmpDir);
            packStored(tmpDir, tmpZip);
            Files.move(tmpZip, archive, StandardCopyOption.REPLACE_EXISTING);
            tmpZip = null;
        } catch (IOException e) {
            throw new MzPeakException("Failed to write mzPeak archive " + archive, e);
        } finally {
            deleteRecursively(tmpDir);
            deleteRecursively(tmpZip);
        }
    }

    private static void packStored(Path dir, Path zipFile) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zip.setMethod(ZipOutputStream.STORED);
            try (var members = Files.list(dir).sorted()) {
                for (Path member : (Iterable<Path>) members::iterator) {
                    byte[] bytes = Files.readAllBytes(member);
                    CRC32 crc = new CRC32();
                    crc.update(bytes);
                    ZipEntry entry = new ZipEntry(member.getFileName().toString());
                    entry.setSize(bytes.length);
                    entry.setCompressedSize(bytes.length);
                    entry.setCrc(crc.getValue());
                    zip.putNextEntry(entry);
                    zip.write(bytes);
                    zip.closeEntry();
                }
            }
        }
    }

    // ---- schemas ----------------------------------------------------------------------------------

    /** A reusable {@code parameters: large_list<item: struct<value-union, accession, name, unit>>} field schema. */
    private static org.apache.parquet.schema.Type paramListField() {
        org.apache.parquet.schema.GroupType itemType = Types.buildGroup(
                        org.apache.parquet.schema.Type.Repetition.OPTIONAL)
                .addField(Types.optionalGroup()
                        .optional(PrimitiveTypeName.INT64).named("integer")
                        .optional(PrimitiveTypeName.DOUBLE).named("float")
                        .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("string")
                        .optional(PrimitiveTypeName.BOOLEAN).named("boolean")
                        .named("value"))
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("accession")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("name")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("unit")
                .named("item");
        return Types.optionalGroup().as(LogicalTypeAnnotation.listType())
                .addField(Types.repeatedGroup().addField(itemType).named("list"))
                .named("parameters");
    }

    /** {@code scan_windows: large_list<item: struct<lower, upper>>} field schema (matches the reference fixtures). */
    private static org.apache.parquet.schema.Type scanWindowListField() {
        org.apache.parquet.schema.GroupType itemType = Types.buildGroup(
                        org.apache.parquet.schema.Type.Repetition.OPTIONAL)
                .optional(PrimitiveTypeName.DOUBLE).named("MS_1000501_scan_window_lower_limit_unit_MS_1000040")
                .optional(PrimitiveTypeName.DOUBLE).named("MS_1000500_scan_window_upper_limit_unit_MS_1000040")
                .named("item");
        return Types.optionalGroup().as(LogicalTypeAnnotation.listType())
                .addField(Types.repeatedGroup().addField(itemType).named("list"))
                .named("scan_windows");
    }

    /** uint64 logical type annotation (unsigned 64-bit int, matching mzPeak spec for index/count fields). */
    private static final LogicalTypeAnnotation UINT64 = LogicalTypeAnnotation.intType(64, false);

    private static final MessageType METADATA_SCHEMA = Types.buildMessage()
            .addField(Types.optionalGroup()
                    .required(PrimitiveTypeName.INT64).as(UINT64).named("index")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("id")
                    .optional(PrimitiveTypeName.INT32).named("MS_1000511_ms_level")
                    .optional(PrimitiveTypeName.DOUBLE).named("time")
                    .optional(PrimitiveTypeName.INT32).named("MS_1000465_scan_polarity")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                    .named("MS_1000525_spectrum_representation")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                    .named("MS_1000559_spectrum_type")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000528_lowest_observed_mz_unit_MS_1000040")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000527_highest_observed_mz_unit_MS_1000040")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000504_base_peak_mz_unit_MS_1000040")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000505_base_peak_intensity_unit_MS_1000131")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000285_total_ion_current_unit_MS_1000131")
                    .optional(PrimitiveTypeName.INT64).as(UINT64).named("MS_1003060_number_of_data_points")
                    .optional(PrimitiveTypeName.INT64).as(UINT64).named("MS_1003059_number_of_peaks")
                    .addField(paramListField())
                    .named("spectrum"))
            .addField(Types.optionalGroup()
                    .required(PrimitiveTypeName.INT64).as(UINT64).named("source_index")
                    .optional(PrimitiveTypeName.INT64).as(UINT64).named("scan_index")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000016_scan_start_time_unit_UO_0000031")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000927_ion_injection_time_unit_UO_0000028")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("MS_1000512_filter_string")
                    .optional(PrimitiveTypeName.INT32).named("MS_1000616_preset_scan_configuration")
                    .optional(PrimitiveTypeName.INT32).named("instrument_configuration_ref")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("spectrum_reference")
                    .optional(PrimitiveTypeName.DOUBLE).named("ion_mobility_value")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("ion_mobility_type")
                    .optional(PrimitiveTypeName.INT32).named("IMS_1000050_position_x")
                    .optional(PrimitiveTypeName.INT32).named("IMS_1000051_position_y")
                    .addField(scanWindowListField())
                    .addField(paramListField())
                    .named("scan"))
            .addField(Types.optionalGroup()
                    .required(PrimitiveTypeName.INT64).as(UINT64).named("source_index")
                    .optional(PrimitiveTypeName.INT64).as(UINT64).named("precursor_index")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("precursor_id")
                    .optionalGroup()
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000827_isolation_window_target_mz")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000828_isolation_window_lower_offset")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000829_isolation_window_upper_offset")
                    .named("isolation_window")
                    .addField(Types.optionalGroup().addField(paramListField()).named("activation"))
                    .named("precursor"))
            .addField(Types.optionalGroup()
                    .required(PrimitiveTypeName.INT64).as(UINT64).named("source_index")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000744_selected_ion_mz_unit_MS_1000040")
                    .optional(PrimitiveTypeName.INT32).named("MS_1000041_charge_state")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000042_intensity_unit_MS_1000131")
                    .addField(paramListField())
                    .named("selected_ion"))
            .named("spectrum_metadata");

    private static final MessageType SPECTRUM_DATA_SCHEMA = pointSchema("spectrum_index", "mz");
    private static final MessageType CHROM_DATA_SCHEMA = pointSchema("chromatogram_index", "time");
    private static final MessageType WAVELENGTH_DATA_SCHEMA = pointSchema("wavelength_spectrum_index", "wavelength");

    private static MessageType pointSchema(String indexField, String xField) {
        return Types.buildMessage()
                .addField(Types.requiredGroup()
                        .required(PrimitiveTypeName.INT64).as(UINT64).named(indexField)
                        .optional(PrimitiveTypeName.DOUBLE).named(xField)
                        .optional(PrimitiveTypeName.DOUBLE).named("intensity")
                        .named("point"))
                .named("data");
    }

    /** Wavelength (UV/DAD) metadata schema — minimal spectrum facet read by {@link WavelengthSpectrumStore}. */
    private static final MessageType WAVELENGTH_META_SCHEMA = Types.buildMessage()
            .addField(Types.optionalGroup()
                    .required(PrimitiveTypeName.INT64).as(UINT64).named("index")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("id")
                    .optional(PrimitiveTypeName.DOUBLE).named("time")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1003812_lambda_max_unit_UO:0000018")
                    .optional(PrimitiveTypeName.INT64).as(UINT64).named("MS_1003060_number_of_data_points")
                    .named("spectrum"))
            .named("wavelength_spectra_metadata");

    /** Schema for Numpress-encoded chunk layout (compatible with SpectrumArrayStore.acceptNumpressChunk). */
    private static final MessageType NUMPRESS_CHUNK_SCHEMA = Types.buildMessage()
            .addField(Types.requiredGroup()
                    .required(PrimitiveTypeName.INT64).as(UINT64).named("spectrum_index")
                    .optional(PrimitiveTypeName.DOUBLE).named("mz_chunk_start")
                    .optional(PrimitiveTypeName.DOUBLE).named("mz_chunk_end")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("chunk_encoding")
                    // large_list<uint8> columns — stored as repeated INT32 inside a LIST group
                    .addField(numpressByteListField("mz_numpress_linear_bytes"))
                    .addField(numpressByteListField("intensity_numpress_slof_bytes"))
                    .addField(numpressByteListField("intensity_numpress_pic_bytes"))
                    .named("chunk"))
            .named("chunk_data");

    private static org.apache.parquet.schema.Type numpressByteListField(String name) {
        return Types.optionalGroup().as(LogicalTypeAnnotation.listType())
                .repeatedGroup()
                .required(PrimitiveTypeName.INT32).named("item")
                .named("list")
                .named(name);
    }

    /** Default number of m/z points per numpress chunk. */
    private static final int NUMPRESS_CHUNK_SIZE = 500;
    /** Numpress linear fixed-point scale (near-lossless for MS m/z up to ~6000 Da at 5 sig figs). */
    private static final double NUMPRESS_LINEAR_FIXED_POINT = 100_000.0;
    /**
     * Intensity encoding for Numpress chunks. {@link #SLOF} is the standard choice for floating-point
     * intensities; {@link #PIC} is appropriate for integer ion-count arrays.
     */
    public enum NumpressIntensityEncoding { SLOF, PIC }

    /** Max value for a uint16 SLOF symbol — SLOF encodes {@code log(x+1)*fixedPoint} into 2 bytes.
     * The fixedPoint is chosen dynamically so the maximum log value maps to ≤65535.
     */
    private static final int SLOF_MAX_SYMBOL = 65535;

    private static final MessageType CHROM_META_SCHEMA = Types.buildMessage()
            .addField(Types.optionalGroup()
                    .required(PrimitiveTypeName.INT64).as(UINT64).named("index")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("id")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                    .named("MS_1000626_chromatogram_type")
                    .optional(PrimitiveTypeName.INT64).as(UINT64).named("MS_1003060_number_of_data_points")
                    .named("chromatogram"))
            .named("chromatogram_metadata");

    // ---- writers ----------------------------------------------------------------------------------

    private static void writeSpectrumMetadata(Path file, List<Spectrum> spectra,
                                              java.util.Map<String, String> footer) throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(METADATA_SCHEMA);
        long scanIndexCounter = 0;
        try (ParquetWriter<Group> writer = openWriter(file, METADATA_SCHEMA, footer)) {
            for (Spectrum s : spectra) {
                SpectrumDescription d = s.description();
                Group row = factory.newGroup();

                Group spectrum = row.addGroup("spectrum");
                spectrum.add("index", d.index());
                if (d.id() != null) {
                    spectrum.add("id", d.id());
                }
                spectrum.add("MS_1000511_ms_level", d.msLevel());
                if (Double.isFinite(d.retentionTime())) {
                    spectrum.add("time", d.retentionTime());
                }
                Integer polarity = polarityCode(d);
                if (polarity != null) {
                    spectrum.add("MS_1000465_scan_polarity", polarity);
                }
                spectrum.add("MS_1000525_spectrum_representation", continuityCurie(d));
                spectrum.add("MS_1000559_spectrum_type", d.msLevel() > 1 ? "MS:1000580" : "MS:1000579");
                writeDerivedColumns(spectrum, s);
                // Declare each facet's count independently; a spectrum may carry profile points AND a
                // centroid peak list (both facets), so both counts can be present.
                int profileRows = profileRows(s);
                int peakRows = peakRows(s);
                if (profileRows > 0) {
                    spectrum.add("MS_1003060_number_of_data_points", (long) profileRows);
                }
                if (peakRows > 0) {
                    spectrum.add("MS_1003059_number_of_peaks", (long) peakRows);
                }
                writeParams(spectrum, d.parameters());

                ScanEvent firstScan = d.scans().isEmpty() ? null : d.scans().get(0);
                populateScan(row.addGroup("scan"), d.index(), scanIndexCounter++, d.retentionTime(), firstScan);

                writePrecursor(row, d);
                writer.write(row);
                // NOTE: multiple scans / selected ions per spectrum (e.g. Bruker PASEF combineIMS frames carry
                // ~25 mobility scans) are NOT yet written as additional packed-facet rows — only the primary
                // scan and selected ion are emitted. The m/z + intensity payload is preserved in full; the
                // forward-backward test surfaces the dropped secondary records via its scans/precursors/ion
                // count checks. Packed multi-facet writing is a deferred enhancement.
            }
        }
    }

    /** Populate a {@code scan} facet group. {@code se} may be {@code null} for a spectrum with no scan events. */
    private static void populateScan(Group scan, long sourceIndex, long scanIndex, double rtFallback, ScanEvent se) {
        scan.add("source_index", sourceIndex);
        scan.add("scan_index", scanIndex);
        double rt = (se != null && Double.isFinite(se.startTime())) ? se.startTime() : rtFallback;
        if (Double.isFinite(rt)) {
            scan.add("MS_1000016_scan_start_time_unit_UO_0000031", rt);
        }
        if (se == null) {
            return;
        }
        if (se.injectionTime() != null) {
            scan.add("MS_1000927_ion_injection_time_unit_UO_0000028", se.injectionTime());
        }
        if (se.filterString() != null) {
            scan.add("MS_1000512_filter_string", se.filterString());
        }
        if (se.presetScanConfiguration() != null) {
            scan.add("MS_1000616_preset_scan_configuration", se.presetScanConfiguration());
        }
        if (se.instrumentConfigurationRef() != null) {
            scan.add("instrument_configuration_ref", se.instrumentConfigurationRef());
        }
        if (se.spectrumReference() != null) {
            scan.add("spectrum_reference", se.spectrumReference());
        }
        // Typed ion-mobility columns (also kept in params; the reader prefers params on read, so there is
        // no duplication — the typed columns satisfy reference/validator parity).
        OptionalDouble im = se.ionMobilityValue();
        if (im.isPresent()) {
            scan.add("ion_mobility_value", im.getAsDouble());
            String imType = se.ionMobilityType();
            if (imType != null) {
                scan.add("ion_mobility_type", imType);
            }
        }
        // Typed imaging-position columns (required by the validator for imaging archives, which also checks
        // they are 1-based). Value preserved in params for round-trip.
        var pos = se.imagingPosition();
        if (pos != null) {
            scan.add("IMS_1000050_position_x", pos.x());
            scan.add("IMS_1000051_position_y", pos.y());
        }
        writeScanWindows(scan, se.scanWindows());
        writeParams(scan, se.parameters());
    }

    /** Append the derived spectrum columns (observed m/z range, base peak, TIC) when the spectrum has payload. */
    private static void writeDerivedColumns(Group spectrum, Spectrum s) {
        boolean centroid = isCentroid(s) && !s.peaks().isEmpty();
        int n = centroid ? s.peaks().size() : s.mz().length;
        if (n == 0) {
            return; // empty spectrum: omit (null), do not fabricate zeros
        }
        double loMz = Double.POSITIVE_INFINITY, hiMz = Double.NEGATIVE_INFINITY;
        double basePeakMz = Double.NaN, basePeakIntensity = Double.NEGATIVE_INFINITY;
        double tic = 0.0;
        if (centroid) {
            for (CentroidPeak p : s.peaks()) {
                double mz = p.mz(), in = p.intensity();
                if (mz < loMz) loMz = mz;
                if (mz > hiMz) hiMz = mz;
                tic += in;
                if (in > basePeakIntensity) { basePeakIntensity = in; basePeakMz = mz; }
            }
        } else {
            double[] mz = s.mz();
            double[] in = s.intensity();
            for (int i = 0; i < mz.length; i++) {
                if (mz[i] < loMz) loMz = mz[i];
                if (mz[i] > hiMz) hiMz = mz[i];
                tic += in[i];
                if (in[i] > basePeakIntensity) { basePeakIntensity = in[i]; basePeakMz = mz[i]; }
            }
        }
        if (Double.isFinite(loMz)) spectrum.add("MS_1000528_lowest_observed_mz_unit_MS_1000040", loMz);
        if (Double.isFinite(hiMz)) spectrum.add("MS_1000527_highest_observed_mz_unit_MS_1000040", hiMz);
        if (Double.isFinite(basePeakMz)) spectrum.add("MS_1000504_base_peak_mz_unit_MS_1000040", basePeakMz);
        if (Double.isFinite(basePeakIntensity)) {
            spectrum.add("MS_1000505_base_peak_intensity_unit_MS_1000131", basePeakIntensity);
        }
        spectrum.add("MS_1000285_total_ion_current_unit_MS_1000131", tic);
    }

    private static void writeScanWindows(Group scan, List<ScanWindow> windows) {
        if (windows == null || windows.isEmpty()) {
            return;
        }
        Group wrapper = scan.addGroup("scan_windows");
        for (ScanWindow w : windows) {
            Group item = wrapper.addGroup("list").addGroup("item");
            item.add("MS_1000501_scan_window_lower_limit_unit_MS_1000040", w.lowerLimit());
            item.add("MS_1000500_scan_window_upper_limit_unit_MS_1000040", w.upperLimit());
        }
    }

    private static void writePrecursor(Group row, SpectrumDescription d) {
        Precursor precursor = d.primaryPrecursor();
        if (precursor == null) {
            return;
        }
        Group p = row.addGroup("precursor");
        p.add("source_index", d.index());
        if (precursor.precursorIndex() != null) {
            p.add("precursor_index", precursor.precursorIndex());
        }
        if (precursor.precursorId() != null) {
            p.add("precursor_id", precursor.precursorId());
        }
        if (precursor.isolationWindow() != null && hasAnyWindowValue(precursor)) {
            Group iso = p.addGroup("isolation_window");
            addIfPresent(iso, "MS_1000827_isolation_window_target_mz", precursor.isolationWindow().targetMz());
            addIfPresent(iso, "MS_1000828_isolation_window_lower_offset", precursor.isolationWindow().lowerOffset());
            addIfPresent(iso, "MS_1000829_isolation_window_upper_offset", precursor.isolationWindow().upperOffset());
        }
        // Precursor activation (dissociation method + collision energy). Round-trips via the reader's
        // Precursor.activation(); previously dropped entirely on write.
        if (precursor.activation() != null && !precursor.activation().isEmpty()) {
            Group activation = p.addGroup("activation");
            writeParams(activation, precursor.activation().parameters());
        }
        SelectedIon ion = precursor.primaryIon();
        if (ion != null) {
            writeSelectedIon(row.addGroup("selected_ion"), d.index(), ion);
        }
    }

    /** Populate a {@code selected_ion} facet group. */
    private static void writeSelectedIon(Group si, long sourceIndex, SelectedIon ion) {
        si.add("source_index", sourceIndex);
        si.add("MS_1000744_selected_ion_mz_unit_MS_1000040", ion.mz());
        if (ion.charge() != null) {
            si.add("MS_1000041_charge_state", ion.charge());
        }
        addIfPresent(si, "MS_1000042_intensity_unit_MS_1000131", ion.intensity());
        writeParams(si, ion.parameters());
    }

    private static void writeSpectrumPoints(Path file, List<Spectrum> spectra, boolean centroidFile)
            throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(SPECTRUM_DATA_SCHEMA);
        try (ParquetWriter<Group> writer = openWriter(file, SPECTRUM_DATA_SCHEMA)) {
            for (Spectrum s : spectra) {
                if (centroidFile) {
                    if (peakRows(s) == 0) {
                        continue;
                    }
                    if (!s.peaks().isEmpty()) {
                        for (CentroidPeak peak : s.peaks()) {
                            writePoint(writer, factory, "spectrum_index", s.index(), "mz", peak.mz(), peak.intensity());
                        }
                    } else {
                        // centroid spectrum whose centroids are carried in the mz/intensity arrays
                        double[] mz = s.mz();
                        double[] intensity = s.intensity();
                        for (int i = 0; i < mz.length; i++) {
                            writePoint(writer, factory, "spectrum_index", s.index(), "mz", mz[i], intensity[i]);
                        }
                    }
                } else {
                    if (profileRows(s) == 0) {
                        continue;
                    }
                    double[] mz = s.mz();
                    double[] intensity = s.intensity();
                    for (int i = 0; i < mz.length; i++) {
                        writePoint(writer, factory, "spectrum_index", s.index(), "mz", mz[i], intensity[i]);
                    }
                }
            }
        }
    }

    /**
     * Write spectra as MS-Numpress chunk rows. Each spectrum is split into fixed-size chunks; m/z is encoded
     * with Numpress linear and intensity with Numpress SLOF. Output is compatible with
     * {@code SpectrumArrayStore.acceptNumpressChunk}.
     */
    private static void writeNumpressChunks(Path file, List<Spectrum> spectra, boolean centroidFile,
                                            NumpressIntensityEncoding intensityEncoding)
            throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(NUMPRESS_CHUNK_SCHEMA);
        try (ParquetWriter<Group> writer = openWriter(file, NUMPRESS_CHUNK_SCHEMA)) {
            for (Spectrum s : spectra) {
                double[] mz;
                double[] intensity;
                if (centroidFile) {
                    if (peakRows(s) == 0) {
                        continue;
                    }
                    if (!s.peaks().isEmpty()) {
                        mz = new double[s.peaks().size()];
                        intensity = new double[s.peaks().size()];
                        for (int i = 0; i < mz.length; i++) {
                            mz[i] = s.peaks().get(i).mz();
                            intensity[i] = s.peaks().get(i).intensity();
                        }
                    } else {
                        mz = s.mz();
                        intensity = s.intensity();
                    }
                } else {
                    if (profileRows(s) == 0) {
                        continue;
                    }
                    mz = s.mz();
                    intensity = s.intensity();
                }
                int n = mz.length;
                if (n == 0) {
                    continue;
                }
                int pos = 0;
                while (pos < n) {
                    int end = Math.min(pos + NUMPRESS_CHUNK_SIZE, n);
                    int chunkLen = end - pos;
                    double[] chunkMz = java.util.Arrays.copyOfRange(mz, pos, end);
                    double[] chunkIn = java.util.Arrays.copyOfRange(intensity, pos, end);

                    // Encode m/z with Numpress linear; max output = 8 (header) + 5 bytes/value
                    byte[] mzBuf = new byte[8 + chunkLen * 5];
                    int mzLen = MSNumpress.encodeLinear(chunkMz, chunkLen, mzBuf, NUMPRESS_LINEAR_FIXED_POINT);

                    // Encode intensity with SLOF or PIC depending on the caller's choice.
                    final byte[] inBuf;
                    final int inLen;
                    final String inColumn;
                    if (intensityEncoding == NumpressIntensityEncoding.PIC) {
                        // PIC: 8-byte header + variable bytes; max = 8 + 5*n (worst case)
                        inBuf = new byte[8 + chunkLen * 5];
                        inLen = MSNumpress.encodePic(chunkIn, chunkLen, inBuf);
                        inColumn = "intensity_numpress_pic_bytes";
                    } else {
                        // SLOF: dynamic fixedPoint so log(max_in+1)*fp fits in uint16 (max 65535).
                        double maxIn = 0;
                        for (double v : chunkIn) { if (v > maxIn) maxIn = v; }
                        double slofFixed = maxIn <= 0 ? 1.0 : Math.floor(SLOF_MAX_SYMBOL / Math.log(maxIn + 1));
                        inBuf = new byte[8 + chunkLen * 2];
                        inLen = MSNumpress.encodeSlof(chunkIn, chunkLen, inBuf, slofFixed);
                        inColumn = "intensity_numpress_slof_bytes";
                    }

                    Group row = factory.newGroup();
                    Group chunk = row.addGroup("chunk");
                    chunk.add("spectrum_index", s.index());
                    chunk.add("mz_chunk_start", chunkMz[0]);
                    chunk.add("mz_chunk_end", chunkMz[chunkLen - 1]);
                    chunk.add("chunk_encoding", MSNumpress.ACC_NUMPRESS_LINEAR);

                    // Write mz_numpress_linear_bytes as repeated-group list (one "list" Group per byte)
                    Group mzBytesWrapper = chunk.addGroup("mz_numpress_linear_bytes");
                    for (int i = 0; i < mzLen; i++) {
                        mzBytesWrapper.addGroup("list").add("item", mzBuf[i] & 0xFF);
                    }

                    // Write the chosen intensity column (SLOF or PIC)
                    Group inBytesWrapper = chunk.addGroup(inColumn);
                    for (int i = 0; i < inLen; i++) {
                        inBytesWrapper.addGroup("list").add("item", inBuf[i] & 0xFF);
                    }

                    writer.write(row);
                    pos = end;
                }
            }
        }
    }

    private static void writeChromatogramMetadata(Path file, List<Chromatogram> chromatograms) throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(CHROM_META_SCHEMA);
        try (ParquetWriter<Group> writer = openWriter(file, CHROM_META_SCHEMA)) {
            for (Chromatogram c : chromatograms) {
                Group row = factory.newGroup();
                Group chrom = row.addGroup("chromatogram");
                chrom.add("index", c.index());
                if (c.id() != null) {
                    chrom.add("id", c.id());
                }
                if (c.typeCurie() != null) {
                    chrom.add("MS_1000626_chromatogram_type", c.typeCurie());
                }
                chrom.add("MS_1003060_number_of_data_points", (long) c.size());
                writer.write(row);
            }
        }
    }

    private static void writeChromatogramData(Path file, List<Chromatogram> chromatograms) throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(CHROM_DATA_SCHEMA);
        try (ParquetWriter<Group> writer = openWriter(file, CHROM_DATA_SCHEMA)) {
            for (Chromatogram c : chromatograms) {
                double[] time = c.time();
                double[] intensity = c.intensity();
                for (int i = 0; i < time.length; i++) {
                    writePoint(writer, factory, "chromatogram_index", c.index(), "time", time[i], intensity[i]);
                }
            }
        }
    }

    private static void writeWavelengthMetadata(Path file, List<WavelengthSpectrum> spectra) throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(WAVELENGTH_META_SCHEMA);
        try (ParquetWriter<Group> writer = openWriter(file, WAVELENGTH_META_SCHEMA)) {
            for (WavelengthSpectrum w : spectra) {
                Group row = factory.newGroup();
                Group s = row.addGroup("spectrum");
                s.add("index", w.index());
                if (w.id() != null) {
                    s.add("id", w.id());
                }
                if (Double.isFinite(w.time())) {
                    s.add("time", w.time());
                }
                if (w.lambdaMax() != null) {
                    s.add("MS_1003812_lambda_max_unit_UO:0000018", w.lambdaMax());
                }
                s.add("MS_1003060_number_of_data_points", (long) w.size());
                writer.write(row);
            }
        }
    }

    private static void writeWavelengthData(Path file, List<WavelengthSpectrum> spectra) throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(WAVELENGTH_DATA_SCHEMA);
        try (ParquetWriter<Group> writer = openWriter(file, WAVELENGTH_DATA_SCHEMA)) {
            for (WavelengthSpectrum w : spectra) {
                double[] wl = w.wavelength();
                double[] intensity = w.intensity();
                for (int i = 0; i < wl.length; i++) {
                    writePoint(writer, factory, "wavelength_spectrum_index", w.index(), "wavelength",
                            wl[i], intensity[i]);
                }
            }
        }
    }

    private static void writePoint(ParquetWriter<Group> writer, SimpleGroupFactory factory,
                                   String indexField, long index, String xField, double x, double intensity)
            throws IOException {
        Group row = factory.newGroup();
        Group point = row.addGroup("point");
        point.add(indexField, index);
        point.add(xField, x);
        point.add("intensity", intensity);
        writer.write(row);
    }

    private static void writeManifest(Path file, boolean hasProfile, boolean hasPeaks, boolean hasChromatograms,
                                       boolean hasWavelength, List<ScanSettings> scanSettings,
                                       org.mzpeak.model.meta.FileMetadata fileMetadata)
            throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ArrayNode files = root.putArray("files");
        addManifestEntry(files, "spectra_metadata.parquet", "spectrum", "metadata");
        if (hasProfile) {
            addManifestEntry(files, "spectra_data.parquet", "spectrum", "data arrays");
        }
        if (hasPeaks) {
            addManifestEntry(files, "spectra_peaks.parquet", "spectrum", "peaks");
        }
        if (hasChromatograms) {
            addManifestEntry(files, "chromatograms_metadata.parquet", "chromatogram", "metadata");
            addManifestEntry(files, "chromatograms_data.parquet", "chromatogram", "data arrays");
        }
        if (hasWavelength) {
            addManifestEntry(files, "wavelength_spectra_metadata.parquet", "wavelength spectrum", "metadata");
            addManifestEntry(files, "wavelength_spectra_data.parquet", "wavelength spectrum", "data arrays");
        }
        ObjectNode meta = root.putObject("metadata");
        // Match the reference writer: the spec example declares version 0.9.0.
        meta.put("version", "0.9.0");
        // Always declare the standard CV ontologies; every mzPeak file uses at minimum MS + UO.
        // Including IMS is harmless for non-imaging files (extra declarations are not errors).
        ArrayNode cvList = meta.putArray("cv_list");
        addCvEntry(cvList, "MS", "PSI-MS controlled vocabulary",
                "https://raw.githubusercontent.com/HUPO-PSI/psi-ms-CV/master/psi-ms.obo", "4.1.x");
        addCvEntry(cvList, "IMS", "Mass Spectrometry Imaging controlled vocabulary",
                "https://raw.githubusercontent.com/imzML/imzML/master/imagingMS.obo", "1.1.x");
        addCvEntry(cvList, "UO", "Unit Ontology",
                "https://raw.githubusercontent.com/bio-ontology-research-group/unit-ontology/master/unit.obo",
                "2024-01-01");
        writeScanSettingsList(meta.putArray("scan_settings_list"), scanSettings);
        for (var kv : FooterMetadataWriter.serialize(fileMetadata).entrySet()) {
            meta.set(kv.getKey(), mapper.readTree(kv.getValue()));
        }
        Files.write(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
    }

    /** Serialize imaging scan settings into the manifest, matching {@code MzPeakManifest.parseParamArray}. */
    private static void writeScanSettingsList(ArrayNode arr, List<ScanSettings> scanSettings) {
        if (scanSettings == null) {
            return;
        }
        for (ScanSettings ss : scanSettings) {
            ObjectNode n = arr.addObject();
            if (ss.id() != null) {
                n.put("id", ss.id());
            }
            ArrayNode params = n.putArray("parameters");
            for (org.mzpeak.model.Param p : ss.parameters()) {
                ObjectNode pn = params.addObject();
                if (p.name() != null) pn.put("name", p.name());
                if (p.accession() != null) pn.put("accession", p.accession());
                Object v = p.value();
                if (v instanceof Long l) pn.put("value", l);
                else if (v instanceof Integer i) pn.put("value", i.longValue());
                else if (v instanceof Double dd) pn.put("value", dd);
                else if (v instanceof Boolean b) pn.put("value", b);
                else if (v != null) pn.put("value", v.toString());
                if (p.unit() != null) pn.put("unit_accession", p.unit());
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static ParquetWriter<Group> openWriter(Path file, MessageType schema) throws IOException {
        return openWriter(file, schema, java.util.Map.of());
    }

    private static ParquetWriter<Group> openWriter(Path file, MessageType schema,
                                                   java.util.Map<String, String> footerMetadata) throws IOException {
        PlainParquetConfiguration conf = new PlainParquetConfiguration();
        conf.set(SCHEMA_PROPERTY, schema.toString());
        var builder = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(conf)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE);
        if (footerMetadata != null && !footerMetadata.isEmpty()) {
            builder = builder.withExtraMetaData(footerMetadata);
        }
        // Allow tests to force a small row-group size via -Dmzpeak.writer.rowGroupSize=N.
        String rowGroupSize = System.getProperty("mzpeak.writer.rowGroupSize");
        if (rowGroupSize != null) {
            builder = builder.withRowGroupSize(Long.parseLong(rowGroupSize));
        }
        return builder.build();
    }

    /**
     * Enforce the reader's index contract before anything is written. Spectrum, precursor_index, chromatogram
     * and wavelength-spectrum indices must be non-negative (they are stored as uint64), and spectrum, chromatogram
     * and wavelength-spectrum indices must be unique. The reader finds a spectrum's data rows through the index
     * range of each row group, so spectra are written in index order: this returns the caller's list when it is
     * already in index order, and otherwise a stable-sorted copy. The caller's list is never modified.
     */
    private static List<Spectrum> validateAndOrder(List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                                   List<WavelengthSpectrum> wavelengthSpectra) {
        boolean inIndexOrder = true;
        long previous = -1;
        for (Spectrum s : spectra) {
            requireNonNegative(s.index(), "spectrum index");
            Precursor p = s.description().primaryPrecursor();
            if (p != null && p.precursorIndex() != null) {
                requireNonNegative(p.precursorIndex(), "precursor_index");
            }
            inIndexOrder &= s.index() >= previous;
            previous = s.index();
        }
        Set<Long> seen = new HashSet<>();
        for (Chromatogram c : chromatograms) {
            requireNonNegative(c.index(), "chromatogram index");
            requireUnique(seen, c.index(), "chromatogram index");
        }
        seen.clear();
        for (WavelengthSpectrum w : wavelengthSpectra) {
            requireNonNegative(w.index(), "wavelength spectrum index");
            requireUnique(seen, w.index(), "wavelength spectrum index");
        }
        List<Spectrum> ordered = spectra;
        if (!inIndexOrder) {
            ordered = new ArrayList<>(spectra);
            ordered.sort(Comparator.comparingLong(Spectrum::index)); // List.sort is stable
        }
        previous = -1;
        for (Spectrum s : ordered) {
            if (s.index() == previous) {
                throw new MzPeakException("duplicate spectrum index: " + s.index() + " (mzPeak indices are unique)");
            }
            previous = s.index();
        }
        return ordered;
    }

    private static void requireNonNegative(long value, String what) {
        if (value < 0) {
            throw new MzPeakException(what + " must be non-negative (mzPeak indices are uint64): " + value);
        }
    }

    private static void requireUnique(Set<Long> seen, long value, String what) {
        if (!seen.add(value)) {
            throw new MzPeakException("duplicate " + what + ": " + value + " (mzPeak indices are unique)");
        }
    }

    private static boolean isCentroid(Spectrum s) {
        return s.description().signalContinuity() == SignalContinuity.CENTROID;
    }

    // Facet routing is by *container*, not by signal continuity: the mz/intensity point arrays go to
    // spectra_data and the explicit centroid peak list goes to spectra_peaks. This preserves the source
    // layout exactly — including centroid spectra whose centroids are stored in spectra_data (e.g. SRM/MRM
    // transitions, which the reference also stores there), which a continuity-based rule would wrongly
    // flip into spectra_peaks on write.

    /** Rows this spectrum contributes to {@code spectra_data} (the point/profile arrays). */
    private static int profileRows(Spectrum s) {
        return s.mz().length;
    }

    /** Rows this spectrum contributes to {@code spectra_peaks} (the explicit centroid peak list). */
    private static int peakRows(Spectrum s) {
        return s.peaks().size();
    }

    private static boolean hasAnyWindowValue(Precursor p) {
        return p.isolationWindow().targetMz() != null
                || p.isolationWindow().lowerOffset() != null
                || p.isolationWindow().upperOffset() != null;
    }

    private static void addIfPresent(Group group, String field, Double value) {
        if (value != null) {
            group.add(field, value);
        }
    }

    /**
     * Write a {@code List<Param>} into a {@code parameters: large_list<item: struct<value-union,...>>} group.
     * Symmetric to {@link org.mzpeak.io.parquet.ParquetGroups#readParams}.
     */
    private static void writeParams(Group owner, List<org.mzpeak.model.Param> params) {
        if (params == null || params.isEmpty()) {
            return;
        }
        Group wrapper = owner.addGroup("parameters");
        for (org.mzpeak.model.Param p : params) {
            Group slot = wrapper.addGroup("list");
            Group item = slot.addGroup("item");
            // value union
            Group valueGroup = item.addGroup("value");
            Object v = p.value();
            if (v instanceof Long l)    { valueGroup.add("integer", l); }
            else if (v instanceof Double d) { valueGroup.add("float", d); }
            else if (v instanceof String s) { valueGroup.add("string", s); }
            else if (v instanceof Boolean b) { valueGroup.add("boolean", b); }
            if (p.accession() != null) { item.add("accession", p.accession()); }
            if (p.name() != null)      { item.add("name", p.name()); }
            if (p.unit() != null)      { item.add("unit", p.unit()); }
        }
    }

    private static void addManifestEntry(ArrayNode files, String name, String entityType, String dataKind) {
        ObjectNode e = files.addObject();
        e.put("name", name);
        e.put("entity_type", entityType);
        e.put("data_kind", dataKind);
    }

    private static void addCvEntry(ArrayNode cvList, String id, String fullName, String uri, String version) {
        ObjectNode cv = cvList.addObject();
        cv.put("id", id);
        cv.put("full_name", fullName);
        cv.put("uri", uri);
        cv.put("version", version);
    }

    private static Integer polarityCode(SpectrumDescription d) {
        if (d.polarity() == null) {
            return null;
        }
        return switch (d.polarity()) {
            case POSITIVE -> 1;
            case NEGATIVE -> -1;
            case UNKNOWN -> null;
        };
    }

    private static String continuityCurie(SpectrumDescription d) {
        return d.signalContinuity() == SignalContinuity.CENTROID ? "MS:1000127" : "MS:1000128";
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}
