package org.mzpeak.io;

import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.ScanSettings;

import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

import static org.mzpeak.io.ArchivePacker.packArchive;

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

    private static final System.Logger LOG = System.getLogger(MzPeakWriter.class.getName());

    private MzPeakWriter() {
    }

    public static void writeDirectory(Path directory, List<Spectrum> spectra) {
        writeDirectory(directory, spectra, List.of());
    }

    public static void writeDirectory(Path directory, List<Spectrum> spectra, List<Chromatogram> chromatograms) {
        writeDirectory(directory, spectra, chromatograms, FileMetadata.EMPTY);
    }

    public static void writeDirectory(Path directory, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                      FileMetadata fileMetadata) {
        writeDirectory(directory, spectra, chromatograms, List.of(), List.of(), fileMetadata);
    }

    /**
     * Write an unpacked {@code *.mzpeak/} directory, including wavelength spectra, scan settings, and
     * file/run metadata. This is the full round-trip form consumed by the forward-backward test.
     *
     * <p>Spectra may come in any order; they are written in index order (the caller's list is not modified).
     * Spectrum, chromatogram and wavelength-spectrum indices must be unique and non-negative. The primary
     * precursor's precursor_index, the only one written, must be non-negative; several spectra may share it.
     * Otherwise an {@link MzPeakException} is thrown before anything is written.
     */
    public static void writeDirectory(Path directory, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                      List<WavelengthSpectrum> wavelengthSpectra, List<ScanSettings> scanSettings,
                                      FileMetadata fileMetadata) {
        write(directory, false, spectra, chromatograms, wavelengthSpectra, scanSettings, fileMetadata,
                Options.defaults());
    }

    public static void writeArchive(Path archive, List<Spectrum> spectra, List<Chromatogram> chromatograms) {
        writeArchive(archive, spectra, chromatograms, FileMetadata.EMPTY);
    }

    public static void writeArchive(Path archive, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                    FileMetadata fileMetadata) {
        writeArchive(archive, spectra, chromatograms, List.of(), List.of(), fileMetadata);
    }

    /** Write a single-file STORED {@code .mzpeak} ZIP (atomically: build in temp, then move into place). */
    public static void writeArchive(Path archive, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                    List<WavelengthSpectrum> wavelengthSpectra, List<ScanSettings> scanSettings,
                                    FileMetadata fileMetadata) {
        write(archive, true, spectra, chromatograms, wavelengthSpectra, scanSettings, fileMetadata,
                Options.defaults());
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
                FileMetadata.EMPTY, NumpressIntensityEncoding.SLOF);
    }

    public static void writeDirectoryNumpress(Path directory, List<Spectrum> spectra,
                                             List<Chromatogram> chromatograms,
                                             FileMetadata fileMetadata) {
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
                                             FileMetadata fileMetadata,
                                             NumpressIntensityEncoding intensityEncoding) {
        writeDirectoryNumpress(directory, spectra, chromatograms, List.of(), List.of(), fileMetadata, intensityEncoding);
    }

    /**
     * Full Numpress directory form including wavelength spectra and scan settings.
     *
     * <p>Spectra may come in any order; they are written in index order (the caller's list is not modified).
     * Spectrum, chromatogram and wavelength-spectrum indices must be unique and non-negative. The primary
     * precursor's precursor_index, the only one written, must be non-negative; several spectra may share it.
     * Otherwise an {@link MzPeakException} is thrown before anything is written.
     */
    public static void writeDirectoryNumpress(Path directory, List<Spectrum> spectra,
                                             List<Chromatogram> chromatograms,
                                             List<WavelengthSpectrum> wavelengthSpectra,
                                             List<ScanSettings> scanSettings,
                                             FileMetadata fileMetadata,
                                             NumpressIntensityEncoding intensityEncoding) {
        write(directory, false, spectra, chromatograms, wavelengthSpectra, scanSettings, fileMetadata,
                numpress(intensityEncoding));
    }

    /** Write a single-file STORED {@code .mzpeak} ZIP with Numpress encoding. */
    public static void writeArchiveNumpress(Path archive, List<Spectrum> spectra,
                                           List<Chromatogram> chromatograms) {
        writeArchiveNumpress(archive, spectra, chromatograms, FileMetadata.EMPTY);
    }

    public static void writeArchiveNumpress(Path archive, List<Spectrum> spectra,
                                           List<Chromatogram> chromatograms,
                                           FileMetadata fileMetadata) {
        writeArchiveNumpress(archive, spectra, chromatograms, List.of(), List.of(), fileMetadata);
    }

    /** Full Numpress archive form including wavelength spectra and scan settings. */
    public static void writeArchiveNumpress(Path archive, List<Spectrum> spectra,
                                           List<Chromatogram> chromatograms,
                                           List<WavelengthSpectrum> wavelengthSpectra,
                                           List<ScanSettings> scanSettings,
                                           FileMetadata fileMetadata) {
        write(archive, true, spectra, chromatograms, wavelengthSpectra, scanSettings, fileMetadata,
                numpress(NumpressIntensityEncoding.SLOF));
    }

    /**
     * Intensity encoding for Numpress chunks. {@link #SLOF} is the standard choice for floating-point
     * intensities; {@link #PIC} is appropriate for integer ion-count arrays.
     */
    public enum NumpressIntensityEncoding { SLOF, PIC }

    /** How spectra_data and spectra_peaks are encoded; chromatograms and wavelength spectra are always points. */
    enum SignalEncoding {
        /** One row per point: index, m/z and intensity. */
        POINT,
        /** MS-Numpress chunks: Numpress linear m/z and Numpress SLOF or PIC intensity. */
        NUMPRESS
    }

    /**
     * The settings of one write, immutable: {@link #defaults()} plus with-methods. The plain overloads use the
     * defaults (point layout), the Numpress overloads {@link SignalEncoding#NUMPRESS} with their intensity encoding.
     *
     * <p>{@link #rowGroupSize()} is not applied yet: every member still takes its row-group size from the
     * {@code mzpeak.writer.rowGroupSize} system property, or else the Parquet default.
     */
    static final class Options {

        private static final Options DEFAULTS =
                new Options(SignalEncoding.POINT, NumpressIntensityEncoding.SLOF, OptionalLong.empty());

        private final SignalEncoding signalEncoding;
        private final NumpressIntensityEncoding numpressIntensity;
        private final OptionalLong rowGroupSize;

        private Options(SignalEncoding signalEncoding, NumpressIntensityEncoding numpressIntensity,
                        OptionalLong rowGroupSize) {
            this.signalEncoding = signalEncoding;
            this.numpressIntensity = numpressIntensity;
            this.rowGroupSize = rowGroupSize;
        }

        /** Point layout, SLOF Numpress intensity, no row-group size. */
        static Options defaults() {
            return DEFAULTS;
        }

        SignalEncoding signalEncoding() {
            return signalEncoding;
        }

        /** The Numpress intensity encoding; ignored unless {@link #signalEncoding()} is NUMPRESS. */
        NumpressIntensityEncoding numpressIntensity() {
            return numpressIntensity;
        }

        OptionalLong rowGroupSize() {
            return rowGroupSize;
        }

        Options withSignalEncoding(SignalEncoding encoding) {
            return new Options(Objects.requireNonNull(encoding, "encoding"), numpressIntensity, rowGroupSize);
        }

        Options withNumpressIntensity(NumpressIntensityEncoding encoding) {
            return new Options(signalEncoding, Objects.requireNonNull(encoding, "encoding"), rowGroupSize);
        }

        /** @throws IllegalArgumentException if {@code bytes} is not positive */
        Options withRowGroupSize(long bytes) {
            if (bytes <= 0) {
                throw new IllegalArgumentException("row-group size must be positive: " + bytes);
            }
            return new Options(signalEncoding, numpressIntensity, OptionalLong.of(bytes));
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /**
     * The one path behind every public overload: plan without any I/O (a contract violation throws here, before
     * any file or temp directory exists), then write the plan into {@code target}, as a directory or, for
     * {@code archive}, as a STORED ZIP built in a temp directory and moved into place. The report's warnings are
     * logged once the dataset is written.
     */
    private static void write(Path target, boolean archive, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                              List<WavelengthSpectrum> wavelengthSpectra, List<ScanSettings> scanSettings,
                              FileMetadata fileMetadata, Options options) {
        WritePlan plan = WritePlanner.plan(spectra, chromatograms, wavelengthSpectra, scanSettings, fileMetadata,
                options);
        if (archive) {
            packArchive(target, dir -> DatasetWriter.write(dir, plan));
        } else {
            DatasetWriter.write(target, plan);
        }
        for (String warning : plan.report().warnings()) {
            LOG.log(Level.WARNING, warning);
        }
    }

    /** The options of a legacy Numpress overload; a {@code null} intensity encoding has always meant SLOF. */
    private static Options numpress(NumpressIntensityEncoding intensityEncoding) {
        return Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS).withNumpressIntensity(
                intensityEncoding == null ? NumpressIntensityEncoding.SLOF : intensityEncoding);
    }
}
