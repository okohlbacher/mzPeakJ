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
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a minimal mzPeak dataset: ZSTD-compressed Parquet ({@code point} layout) + {@code mzpeak_index.json},
 * to an unpacked directory or a single-file (STORED) {@code .mzpeak} ZIP. Round-trips through {@link MzPeakReader}.
 *
 * <p>Scope (mirrors the reader's): spectra (MS1 + MSn, scalar metadata + precursor/selected-ion) and
 * chromatograms, point layout only. Profile spectra go to {@code spectra_data.parquet}, centroided spectra to
 * {@code spectra_peaks.parquet}. Parameter/auxiliary-array lists, chunk/Numpress encoding, and wavelength
 * spectra are not written.
 */
public final class MzPeakWriter {

    private static final String SCHEMA_PROPERTY = "parquet.example.schema";

    private MzPeakWriter() {
    }

    public static void writeDirectory(Path directory, List<Spectrum> spectra) {
        writeDirectory(directory, spectra, List.of());
    }

    /** Write an unpacked {@code *.mzpeak/} directory. */
    public static void writeDirectory(Path directory, List<Spectrum> spectra, List<Chromatogram> chromatograms) {
        validate(spectra, chromatograms);
        boolean hasProfile = spectra.stream().anyMatch(s -> !isCentroid(s));
        boolean hasPeaks = spectra.stream().anyMatch(MzPeakWriter::isCentroid);
        boolean hasChromatograms = !chromatograms.isEmpty();
        try {
            Files.createDirectories(directory);
            writeSpectrumMetadata(directory.resolve("spectra_metadata.parquet"), spectra);
            if (hasProfile) {
                writeSpectrumPoints(directory.resolve("spectra_data.parquet"), spectra, false);
            }
            if (hasPeaks) {
                writeSpectrumPoints(directory.resolve("spectra_peaks.parquet"), spectra, true);
            }
            if (hasChromatograms) {
                writeChromatogramMetadata(directory.resolve("chromatograms_metadata.parquet"), chromatograms);
                writeChromatogramData(directory.resolve("chromatograms_data.parquet"), chromatograms);
            }
            writeManifest(directory.resolve("mzpeak_index.json"), hasProfile, hasPeaks, hasChromatograms);
        } catch (IOException e) {
            throw new MzPeakException("Failed to write mzPeak dataset to " + directory, e);
        }
    }

    /** Write a single-file STORED {@code .mzpeak} ZIP (atomically: build in temp, then move into place). */
    public static void writeArchive(Path archive, List<Spectrum> spectra, List<Chromatogram> chromatograms) {
        Path parent = archive.toAbsolutePath().getParent();
        Path tmpDir = null;
        Path tmpZip = null;
        try {
            tmpDir = Files.createTempDirectory(parent, ".mzpeak-write-");
            tmpZip = Files.createTempFile(parent, ".mzpeak-write-", ".zip");
            writeDirectory(tmpDir, spectra, chromatograms);
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

    private static final MessageType METADATA_SCHEMA = Types.buildMessage()
            .addField(Types.optionalGroup()
                    .required(PrimitiveTypeName.INT64).named("index")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("id")
                    .optional(PrimitiveTypeName.INT32).named("MS_1000511_ms_level")
                    .optional(PrimitiveTypeName.DOUBLE).named("time")
                    .optional(PrimitiveTypeName.INT32).named("MS_1000465_scan_polarity")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                    .named("MS_1000525_spectrum_representation")
                    .optional(PrimitiveTypeName.INT64).named("MS_1003060_number_of_data_points")
                    .optional(PrimitiveTypeName.INT64).named("MS_1003059_number_of_peaks")
                    .named("spectrum"))
            .addField(Types.optionalGroup()
                    .required(PrimitiveTypeName.INT64).named("source_index")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000016_scan_start_time_unit_UO_0000031")
                    .named("scan"))
            .addField(Types.optionalGroup()
                    .required(PrimitiveTypeName.INT64).named("source_index")
                    .optional(PrimitiveTypeName.INT64).named("precursor_index")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("precursor_id")
                    .optionalGroup()
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000827_isolation_window_target_mz")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000828_isolation_window_lower_offset")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000829_isolation_window_upper_offset")
                    .named("isolation_window")
                    .named("precursor"))
            .addField(Types.optionalGroup()
                    .required(PrimitiveTypeName.INT64).named("source_index")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000744_selected_ion_mz_unit_MS_1000040")
                    .optional(PrimitiveTypeName.INT32).named("MS_1000041_charge_state")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000042_intensity_unit_MS_1000131")
                    .named("selected_ion"))
            .named("spectrum_metadata");

    private static final MessageType SPECTRUM_DATA_SCHEMA = pointSchema("spectrum_index", "mz");
    private static final MessageType CHROM_DATA_SCHEMA = pointSchema("chromatogram_index", "time");

    private static MessageType pointSchema(String indexField, String xField) {
        return Types.buildMessage()
                .addField(Types.requiredGroup()
                        .required(PrimitiveTypeName.INT64).named(indexField)
                        .optional(PrimitiveTypeName.DOUBLE).named(xField)
                        .optional(PrimitiveTypeName.DOUBLE).named("intensity")
                        .named("point"))
                .named("data");
    }

    private static final MessageType CHROM_META_SCHEMA = Types.buildMessage()
            .addField(Types.optionalGroup()
                    .required(PrimitiveTypeName.INT64).named("index")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("id")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                    .named("MS_1000626_chromatogram_type")
                    .optional(PrimitiveTypeName.INT64).named("MS_1003060_number_of_data_points")
                    .named("chromatogram"))
            .named("chromatogram_metadata");

    // ---- writers ----------------------------------------------------------------------------------

    private static void writeSpectrumMetadata(Path file, List<Spectrum> spectra) throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(METADATA_SCHEMA);
        try (ParquetWriter<Group> writer = openWriter(file, METADATA_SCHEMA)) {
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
                spectrum.add(isCentroid(s) ? "MS_1003059_number_of_peaks" : "MS_1003060_number_of_data_points",
                        (long) payloadSize(s));

                Group scan = row.addGroup("scan");
                scan.add("source_index", d.index());
                if (Double.isFinite(d.retentionTime())) {
                    scan.add("MS_1000016_scan_start_time_unit_UO_0000031", d.retentionTime());
                }

                writePrecursor(row, d);
                writer.write(row);
            }
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
        SelectedIon ion = precursor.primaryIon();
        if (ion != null) {
            Group si = row.addGroup("selected_ion");
            si.add("source_index", d.index());
            si.add("MS_1000744_selected_ion_mz_unit_MS_1000040", ion.mz());
            if (ion.charge() != null) {
                si.add("MS_1000041_charge_state", ion.charge());
            }
            addIfPresent(si, "MS_1000042_intensity_unit_MS_1000131", ion.intensity());
        }
    }

    private static void writeSpectrumPoints(Path file, List<Spectrum> spectra, boolean centroidFile)
            throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(SPECTRUM_DATA_SCHEMA);
        try (ParquetWriter<Group> writer = openWriter(file, SPECTRUM_DATA_SCHEMA)) {
            for (Spectrum s : spectra) {
                if (isCentroid(s) != centroidFile) {
                    continue;
                }
                if (centroidFile && !s.peaks().isEmpty()) {
                    for (CentroidPeak peak : s.peaks()) {
                        writePoint(writer, factory, "spectrum_index", s.index(), "mz", peak.mz(), peak.intensity());
                    }
                } else {
                    double[] mz = s.mz();
                    double[] intensity = s.intensity();
                    for (int i = 0; i < mz.length; i++) {
                        writePoint(writer, factory, "spectrum_index", s.index(), "mz", mz[i], intensity[i]);
                    }
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

    private static void writeManifest(Path file, boolean hasProfile, boolean hasPeaks, boolean hasChromatograms)
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
        root.putObject("metadata");
        Files.write(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static ParquetWriter<Group> openWriter(Path file, MessageType schema) throws IOException {
        PlainParquetConfiguration conf = new PlainParquetConfiguration();
        conf.set(SCHEMA_PROPERTY, schema.toString());
        return ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(conf)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build();
    }

    private static void validate(List<Spectrum> spectra, List<Chromatogram> chromatograms) {
        for (Spectrum s : spectra) {
            requireNonNegative(s.index(), "spectrum index");
            Precursor p = s.description().primaryPrecursor();
            if (p != null && p.precursorIndex() != null) {
                requireNonNegative(p.precursorIndex(), "precursor_index");
            }
        }
        for (Chromatogram c : chromatograms) {
            requireNonNegative(c.index(), "chromatogram index");
        }
    }

    private static void requireNonNegative(long value, String what) {
        if (value < 0) {
            throw new MzPeakException(what + " must be non-negative (mzPeak indices are uint64): " + value);
        }
    }

    private static boolean isCentroid(Spectrum s) {
        return s.description().signalContinuity() == SignalContinuity.CENTROID;
    }

    /** Number of points/peaks that will actually be written for this spectrum. */
    private static int payloadSize(Spectrum s) {
        return isCentroid(s) && !s.peaks().isEmpty() ? s.peaks().size() : s.mz().length;
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

    private static void addManifestEntry(ArrayNode files, String name, String entityType, String dataKind) {
        ObjectNode e = files.addObject();
        e.put("name", name);
        e.put("entity_type", entityType);
        e.put("data_kind", dataKind);
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
