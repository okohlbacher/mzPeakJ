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
 *
 * <p>The two full forms, {@link #writeDirectory(Path, List, List, List, List, FileMetadata, Options)} and
 * {@link #writeArchive(Path, List, List, List, List, FileMetadata, Options)}, take every setting as {@link Options}.
 * Each other overload is a shorthand for one of them; its documentation names the call it stands for.
 */
public final class MzPeakWriter {

    private static final System.Logger LOG = System.getLogger(MzPeakWriter.class.getName());

    private MzPeakWriter() {
    }

    /**
     * Same as {@link #writeDirectory(Path, List, List, List, List, FileMetadata, Options) writeDirectory(directory,
     * spectra, List.of(), List.of(), List.of(), FileMetadata.EMPTY, Options.defaults())}.
     */
    public static void writeDirectory(Path directory, List<Spectrum> spectra) {
        writeDirectory(directory, spectra, List.of(), List.of(), List.of(), FileMetadata.EMPTY, Options.defaults());
    }

    /**
     * Same as {@link #writeDirectory(Path, List, List, List, List, FileMetadata, Options) writeDirectory(directory,
     * spectra, chromatograms, List.of(), List.of(), FileMetadata.EMPTY, Options.defaults())}.
     */
    public static void writeDirectory(Path directory, List<Spectrum> spectra, List<Chromatogram> chromatograms) {
        writeDirectory(directory, spectra, chromatograms, List.of(), List.of(), FileMetadata.EMPTY, Options.defaults());
    }

    /**
     * Same as {@link #writeDirectory(Path, List, List, List, List, FileMetadata, Options) writeDirectory(directory,
     * spectra, chromatograms, List.of(), List.of(), fileMetadata, Options.defaults())}.
     */
    public static void writeDirectory(Path directory, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                      FileMetadata fileMetadata) {
        writeDirectory(directory, spectra, chromatograms, List.of(), List.of(), fileMetadata, Options.defaults());
    }

    /**
     * Write an unpacked {@code *.mzpeak/} directory in point layout, including wavelength spectra, scan settings,
     * and file/run metadata. Same as {@link #writeDirectory(Path, List, List, List, List, FileMetadata, Options)
     * writeDirectory(directory, spectra, chromatograms, wavelengthSpectra, scanSettings, fileMetadata,
     * Options.defaults())}.
     */
    public static void writeDirectory(Path directory, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                      List<WavelengthSpectrum> wavelengthSpectra, List<ScanSettings> scanSettings,
                                      FileMetadata fileMetadata) {
        writeDirectory(directory, spectra, chromatograms, wavelengthSpectra, scanSettings, fileMetadata,
                Options.defaults());
    }

    /**
     * Write an unpacked {@code *.mzpeak/} directory, including wavelength spectra, scan settings, and file/run
     * metadata, with the given {@link Options}. This is the full form behind every other {@code writeDirectory} and
     * {@code writeDirectoryNumpress} overload.
     *
     * <p>Spectra may come in any order; they are written in index order (the caller's list is not modified).
     * An m/z, chromatogram time or wavelength array whose finite values are not ascending is written re-sorted, its
     * intensities with it, and a WARNING is logged for that entity (the caller's arrays are not modified).
     * Spectrum, chromatogram and wavelength-spectrum indices must be unique and non-negative. The primary
     * precursor's precursor_index, the only one written, must be non-negative; several spectra may share it.
     * Otherwise an {@link MzPeakException} is thrown before anything is written.
     *
     * @throws NullPointerException if {@code options} is {@code null}
     */
    public static void writeDirectory(Path directory, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                      List<WavelengthSpectrum> wavelengthSpectra, List<ScanSettings> scanSettings,
                                      FileMetadata fileMetadata, Options options) {
        write(directory, false, spectra, chromatograms, wavelengthSpectra, scanSettings, fileMetadata,
                Objects.requireNonNull(options, "options"));
    }

    /**
     * Same as {@link #writeArchive(Path, List, List, List, List, FileMetadata, Options) writeArchive(archive, spectra,
     * chromatograms, List.of(), List.of(), FileMetadata.EMPTY, Options.defaults())}.
     */
    public static void writeArchive(Path archive, List<Spectrum> spectra, List<Chromatogram> chromatograms) {
        writeArchive(archive, spectra, chromatograms, List.of(), List.of(), FileMetadata.EMPTY, Options.defaults());
    }

    /**
     * Same as {@link #writeArchive(Path, List, List, List, List, FileMetadata, Options) writeArchive(archive, spectra,
     * chromatograms, List.of(), List.of(), fileMetadata, Options.defaults())}.
     */
    public static void writeArchive(Path archive, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                    FileMetadata fileMetadata) {
        writeArchive(archive, spectra, chromatograms, List.of(), List.of(), fileMetadata, Options.defaults());
    }

    /**
     * Write a single-file STORED {@code .mzpeak} ZIP in point layout (atomically: build in temp, then move into
     * place). Same as {@link #writeArchive(Path, List, List, List, List, FileMetadata, Options) writeArchive(archive,
     * spectra, chromatograms, wavelengthSpectra, scanSettings, fileMetadata, Options.defaults())}.
     */
    public static void writeArchive(Path archive, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                    List<WavelengthSpectrum> wavelengthSpectra, List<ScanSettings> scanSettings,
                                    FileMetadata fileMetadata) {
        writeArchive(archive, spectra, chromatograms, wavelengthSpectra, scanSettings, fileMetadata,
                Options.defaults());
    }

    /**
     * Write a single-file STORED {@code .mzpeak} ZIP (atomically: build in temp, then move into place) with the
     * given {@link Options}. This is the full form behind every other {@code writeArchive} and
     * {@code writeArchiveNumpress} overload. The input contract is that of
     * {@link #writeDirectory(Path, List, List, List, List, FileMetadata, Options)}; a violation is reported before
     * the temp directory is created.
     *
     * @throws NullPointerException if {@code options} is {@code null}
     */
    public static void writeArchive(Path archive, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                    List<WavelengthSpectrum> wavelengthSpectra, List<ScanSettings> scanSettings,
                                    FileMetadata fileMetadata, Options options) {
        write(archive, true, spectra, chromatograms, wavelengthSpectra, scanSettings, fileMetadata,
                Objects.requireNonNull(options, "options"));
    }

    // ---- Numpress-encoded chunk layout API --------------------------------------------------------

    /**
     * Write an unpacked {@code *.mzpeak/} directory using MS-Numpress chunk encoding.
     * m/z is encoded with Numpress linear ({@code MS:1002312}); intensity with Numpress SLOF
     * ({@code MS:1002314}). Chromatograms are written in standard point layout. Round-trips through
     * {@link MzPeakReader} on any container and layout variant.
     *
     * <p>Same as {@link #writeDirectory(Path, List, List, List, List, FileMetadata, Options) writeDirectory(directory,
     * spectra, chromatograms, List.of(), List.of(), FileMetadata.EMPTY,
     * Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS))}.
     */
    public static void writeDirectoryNumpress(Path directory, List<Spectrum> spectra,
                                             List<Chromatogram> chromatograms) {
        writeDirectory(directory, spectra, chromatograms, List.of(), List.of(), FileMetadata.EMPTY,
                Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS));
    }

    /**
     * Same as {@link #writeDirectory(Path, List, List, List, List, FileMetadata, Options) writeDirectory(directory,
     * spectra, chromatograms, List.of(), List.of(), fileMetadata,
     * Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS))}.
     */
    public static void writeDirectoryNumpress(Path directory, List<Spectrum> spectra,
                                             List<Chromatogram> chromatograms,
                                             FileMetadata fileMetadata) {
        writeDirectory(directory, spectra, chromatograms, List.of(), List.of(), fileMetadata,
                Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS));
    }

    /**
     * Write an unpacked directory with Numpress encoding and a caller-chosen intensity encoding.
     * Use {@link NumpressIntensityEncoding#SLOF} (default) for floating-point intensities and
     * {@link NumpressIntensityEncoding#PIC} for non-negative integer ion-count arrays.
     *
     * <p>Same as {@link #writeDirectory(Path, List, List, List, List, FileMetadata, Options) writeDirectory(directory,
     * spectra, chromatograms, List.of(), List.of(), fileMetadata,
     * Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS).withNumpressIntensity(intensityEncoding))},
     * where a {@code null} {@code intensityEncoding} means SLOF.
     */
    public static void writeDirectoryNumpress(Path directory, List<Spectrum> spectra,
                                             List<Chromatogram> chromatograms,
                                             FileMetadata fileMetadata,
                                             NumpressIntensityEncoding intensityEncoding) {
        writeDirectory(directory, spectra, chromatograms, List.of(), List.of(), fileMetadata,
                numpress(intensityEncoding));
    }

    /**
     * Full Numpress directory form including wavelength spectra and scan settings. Same as
     * {@link #writeDirectory(Path, List, List, List, List, FileMetadata, Options) writeDirectory(directory, spectra,
     * chromatograms, wavelengthSpectra, scanSettings, fileMetadata,
     * Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS).withNumpressIntensity(intensityEncoding))},
     * where a {@code null} {@code intensityEncoding} means SLOF.
     */
    public static void writeDirectoryNumpress(Path directory, List<Spectrum> spectra,
                                             List<Chromatogram> chromatograms,
                                             List<WavelengthSpectrum> wavelengthSpectra,
                                             List<ScanSettings> scanSettings,
                                             FileMetadata fileMetadata,
                                             NumpressIntensityEncoding intensityEncoding) {
        writeDirectory(directory, spectra, chromatograms, wavelengthSpectra, scanSettings, fileMetadata,
                numpress(intensityEncoding));
    }

    /**
     * Write a single-file STORED {@code .mzpeak} ZIP with Numpress encoding. Same as
     * {@link #writeArchive(Path, List, List, List, List, FileMetadata, Options) writeArchive(archive, spectra,
     * chromatograms, List.of(), List.of(), FileMetadata.EMPTY,
     * Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS))}.
     */
    public static void writeArchiveNumpress(Path archive, List<Spectrum> spectra,
                                           List<Chromatogram> chromatograms) {
        writeArchive(archive, spectra, chromatograms, List.of(), List.of(), FileMetadata.EMPTY,
                Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS));
    }

    /**
     * Same as {@link #writeArchive(Path, List, List, List, List, FileMetadata, Options) writeArchive(archive, spectra,
     * chromatograms, List.of(), List.of(), fileMetadata,
     * Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS))}.
     */
    public static void writeArchiveNumpress(Path archive, List<Spectrum> spectra,
                                           List<Chromatogram> chromatograms,
                                           FileMetadata fileMetadata) {
        writeArchive(archive, spectra, chromatograms, List.of(), List.of(), fileMetadata,
                Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS));
    }

    /**
     * Full Numpress archive form including wavelength spectra and scan settings, with SLOF intensities. Same as
     * {@link #writeArchive(Path, List, List, List, List, FileMetadata, Options) writeArchive(archive, spectra,
     * chromatograms, wavelengthSpectra, scanSettings, fileMetadata,
     * Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS))}; that form also takes PIC intensities.
     */
    public static void writeArchiveNumpress(Path archive, List<Spectrum> spectra,
                                           List<Chromatogram> chromatograms,
                                           List<WavelengthSpectrum> wavelengthSpectra,
                                           List<ScanSettings> scanSettings,
                                           FileMetadata fileMetadata) {
        writeArchive(archive, spectra, chromatograms, wavelengthSpectra, scanSettings, fileMetadata,
                Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS));
    }

    /**
     * Intensity encoding for Numpress chunks. {@link #SLOF} is the standard choice for floating-point
     * intensities; {@link #PIC} is appropriate for integer ion-count arrays.
     */
    public enum NumpressIntensityEncoding { SLOF, PIC }

    /**
     * How the metadata members are laid out (design §11.4). Package-private until the split writer is complete
     * (WR-7); until then the public overloads always write {@link #PACKED}.
     */
    enum MetadataLayout {
        /**
         * The current layout (HUPO-PSI/mzPeak 0a28759 and later): one flat table per metadata facet, listed in the
         * current manifest dialect with underscore roles and a column mapping. So far it writes spectra_metadata and
         * spectra_metadata_scans; the chromatogram and wavelength-spectrum metadata are still packed.
         */
        SPLIT,
        /** The pin-era packed layout (HUPO-PSI/mzPeak 29e59b2): one struct per facet in one member per entity. */
        PACKED
    }

    /** How spectra_data and spectra_peaks are encoded; chromatograms and wavelength spectra are always points. */
    public enum SignalEncoding {
        /** One row per point: index, m/z and intensity. */
        POINT,
        /**
         * MS-Numpress chunks: Numpress linear m/z and Numpress SLOF or PIC intensity, as chosen by
         * {@link Options#numpressIntensity()}.
         */
        NUMPRESS
    }

    /**
     * The settings of one write: immutable and compared by value. Start from {@link #defaults()} and change a
     * setting with its with-method, which returns a new instance and leaves the receiver unchanged:
     *
     * <pre>{@code
     * MzPeakWriter.Options options = MzPeakWriter.Options.defaults()
     *         .withSignalEncoding(MzPeakWriter.SignalEncoding.NUMPRESS)
     *         .withNumpressIntensity(MzPeakWriter.NumpressIntensityEncoding.PIC);
     * }</pre>
     *
     * <p>Settings are only ever added as new with-methods, never as constructor or factory parameters (there is no
     * public constructor), so code written against this class keeps compiling when a setting is added.
     */
    public static final class Options {

        private static final Options DEFAULTS = new Options(SignalEncoding.POINT, NumpressIntensityEncoding.SLOF,
                OptionalLong.empty(), MetadataLayout.PACKED);

        private final SignalEncoding signalEncoding;
        private final NumpressIntensityEncoding numpressIntensity;
        private final OptionalLong rowGroupSize;
        private final MetadataLayout metadataLayout;

        private Options(SignalEncoding signalEncoding, NumpressIntensityEncoding numpressIntensity,
                        OptionalLong rowGroupSize, MetadataLayout metadataLayout) {
            this.signalEncoding = signalEncoding;
            this.numpressIntensity = numpressIntensity;
            this.rowGroupSize = rowGroupSize;
            this.metadataLayout = metadataLayout;
        }

        /** Point layout, SLOF Numpress intensity, no row-group size: what the plain overloads write. */
        public static Options defaults() {
            return DEFAULTS;
        }

        /** How spectra_data and spectra_peaks are encoded; {@link SignalEncoding#POINT} by default. */
        public SignalEncoding signalEncoding() {
            return signalEncoding;
        }

        /**
         * The Numpress intensity encoding, {@link NumpressIntensityEncoding#SLOF} by default; ignored unless
         * {@link #signalEncoding()} is {@link SignalEncoding#NUMPRESS}.
         */
        public NumpressIntensityEncoding numpressIntensity() {
            return numpressIntensity;
        }

        /**
         * The target row-group size in bytes of every Parquet member, empty by default. When it is empty, the
         * {@code mzpeak.writer.rowGroupSize} system property applies if it is set, and otherwise the parquet-java
         * default.
         */
        public OptionalLong rowGroupSize() {
            return rowGroupSize;
        }

        /** How the metadata members are laid out; {@link MetadataLayout#PACKED} by default. */
        MetadataLayout metadataLayout() {
            return metadataLayout;
        }

        /**
         * These options with {@code encoding} for spectra_data and spectra_peaks.
         *
         * @throws NullPointerException if {@code encoding} is {@code null}
         */
        public Options withSignalEncoding(SignalEncoding encoding) {
            return new Options(Objects.requireNonNull(encoding, "encoding"), numpressIntensity, rowGroupSize,
                    metadataLayout);
        }

        /**
         * These options with {@code encoding} for Numpress intensities; it takes effect only with
         * {@link SignalEncoding#NUMPRESS}.
         *
         * @throws NullPointerException if {@code encoding} is {@code null}
         */
        public Options withNumpressIntensity(NumpressIntensityEncoding encoding) {
            return new Options(signalEncoding, Objects.requireNonNull(encoding, "encoding"), rowGroupSize,
                    metadataLayout);
        }

        /**
         * These options with a target row-group size of {@code bytes} for every Parquet member; it takes precedence
         * over the {@code mzpeak.writer.rowGroupSize} system property. parquet-java checks the size only every so
         * many rows, so a small member stays in one row group.
         *
         * @throws IllegalArgumentException if {@code bytes} is not positive
         */
        public Options withRowGroupSize(long bytes) {
            if (bytes <= 0) {
                throw new IllegalArgumentException("row-group size must be positive: " + bytes);
            }
            return new Options(signalEncoding, numpressIntensity, OptionalLong.of(bytes), metadataLayout);
        }

        /**
         * These options with {@code layout} for the metadata members.
         *
         * @throws NullPointerException if {@code layout} is {@code null}
         */
        Options withMetadataLayout(MetadataLayout layout) {
            return new Options(signalEncoding, numpressIntensity, rowGroupSize,
                    Objects.requireNonNull(layout, "layout"));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            return o instanceof Options other && signalEncoding == other.signalEncoding
                    && numpressIntensity == other.numpressIntensity && rowGroupSize.equals(other.rowGroupSize)
                    && metadataLayout == other.metadataLayout;
        }

        @Override
        public int hashCode() {
            return Objects.hash(signalEncoding, numpressIntensity, rowGroupSize, metadataLayout);
        }

        @Override
        public String toString() {
            // The metadata layout is named only when it is not the default: while it is package-private (until
            // WR-7), every Options the public API can build prints as it always has.
            return "Options[signalEncoding=" + signalEncoding + ", numpressIntensity=" + numpressIntensity
                    + ", rowGroupSize=" + rowGroupSize
                    + (metadataLayout == MetadataLayout.PACKED ? "" : ", metadataLayout=" + metadataLayout) + "]";
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
