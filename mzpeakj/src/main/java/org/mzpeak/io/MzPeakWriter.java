package org.mzpeak.io;

import org.mzpeak.io.MzPeakManifest.CvEntry;
import org.mzpeak.io.WritePlan.ExtraColumn;
import org.mzpeak.io.WritePlan.SpectrumSignal;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Writes mzPeak datasets in either {@code point} layout (the default) or MS-Numpress {@code chunk} layout.
 *
 * <p>Point layout: spectra (profile + centroid), chromatograms, wavelength (UV/DAD) spectra, and file/run
 * metadata; round-trips through {@link MzPeakReader}. Numpress layout additionally encodes m/z with Numpress
 * linear (MS:1002312) and intensity with Numpress SLOF (MS:1002314) per chunk.
 *
 * <p>The metadata members are written in the split layout of the current mzPeak format unless the {@link Options}
 * say otherwise ({@link MetadataLayout#SPLIT}): one table per facet, every scan, precursor and selected ion of a
 * spectrum, and a manifest in the current dialect, which the HEAD reference Python reader reads.
 * {@link MetadataLayout#PACKED} writes the pin-era packed layout that mzPeakJ 0.10.3 and earlier wrote, with only the
 * first scan, the primary precursor and its first selected ion of a spectrum; a write that drops more logs one
 * WARNING.
 *
 * <p>Per-spectrum/scan/selected-ion CV/user {@code parameters} lists round-trip through both writer and reader.
 * The writer also emits the reference schema's richer metadata: derived spectrum columns (observed m/z range,
 * base peak, total ion current, spectrum type), scan windows, precursor activation, typed ion-mobility and
 * imaging-position columns, scan settings, and wavelength facets. The extra signal arrays of spectra,
 * {@link Spectrum#arrays()} and {@link Spectrum#peakArrays()} (such as the ion mobility of a diaPASEF spectrum), are
 * written as extra columns of spectra_data and spectra_peaks, in both signal encodings, and those of chromatograms,
 * {@link Chromatogram#arrays()} (such as the {@code ms level} array of a TIC), as extra columns of chromatograms_data,
 * which holds the intensities of the chromatograms of each {@link Chromatogram#intensityUnit()} in a column of their
 * own. A read &rarr; write cycle keeps everything {@link MzPeakReader} exposes, except the intensity unit of a
 * chromatogram without points: the reader takes a chromatogram's unit from the column that holds its values, so such
 * a chromatogram reads back without one. Chromatogram times are written as given and declared in their
 * {@link Chromatogram#timeUnit()}, so they read back in that unit. chromatograms_data declares one time unit:
 * chromatograms of different time units are refused with an {@link MzPeakException} before anything is written;
 * {@link Chromatogram#withTimeUnit} converts one beforehand.
 *
 * <p>The two full forms, {@link #writeDirectory(Path, List, List, List, List, FileMetadata, Options)} and
 * {@link #writeArchive(Path, List, List, List, List, FileMetadata, Options)}, take every setting as {@link Options}.
 * Each other overload is a shorthand for one of them; its documentation names the call it stands for. These batch
 * overloads take the whole dataset as lists. {@link #openDirectory(Path, FileMetadata, List, Set, Options)} and
 * {@link #openArchive(Path, FileMetadata, List, Set, Options)} open a {@link Session} instead, which writes the same
 * dataset one spectrum at a time and holds no spectrum's signal once it is added, so that a dataset of any size can
 * be written; unlike the batch overloads, they refuse an existing archive and a directory that is not empty.
 *
 * <p>An archive holds the members in name order and the manifest last, each STORED with the time 2000-01-01T00:00
 * and no extra field but the zip64 one that sizes and offsets over 4 GiB need, so its ZIP layer does not depend on
 * when or in which time zone it is written. The members are streamed into it, so they may exceed 2 GiB.
 * Within one JVM, the same input and options give the same bytes. Across JVM runs, only the footers of the Parquet
 * members can differ: parquet-java writes the encodings of each column chunk in the iteration order of a
 * {@code HashSet} of enum constants, which hash by identity, so that order can change from one run to the next, and
 * with it a member's bytes, its SHA-512 checksum in the manifest and its CRC in an archive. What the members contain
 * (schema, footer key-values, row groups and records) is the same in every run.
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
     * <p>Spectra, chromatograms and wavelength spectra may come in any order. Each kind is written in the order of
     * its indices and numbered 0..n-1 in that order, as the mzPeak spec requires: a subset, such as the MS2 spectra
     * of a run, is written as 0..n-1, and input already numbered 0..n-1 keeps its indices. Each precursor_index is
     * written as the new index of the spectrum it names, or as null when that spectrum is not written; the
     * precursor_id is kept. The caller's lists and objects are not modified.
     * A spectrum without scans is written, in both metadata layouts, with one scan that holds only its retention
     * time, and reads back with that scan. In the split metadata layout, a scan setting without an id is written with
     * the id {@code scan_settings_<position>}, made unique, and a WARNING is logged, since the current manifest needs
     * one; the packed layout leaves the id out.
     * An m/z, chromatogram time or wavelength array whose finite values are not ascending is written re-sorted, its
     * intensities and extra arrays with it, and a WARNING is logged for that entity (the caller's arrays are not
     * modified). The sort orders the rows by {@link Double#compare}, so rows with a NaN move to the end.
     * A CENTROID spectrum whose arrays are its peak list, peak arrays included, is written to spectra_peaks only,
     * unless one of its m/z is not finite: then its arrays go to spectra_data and its peak list to spectra_peaks, since
     * spectra_peaks would read such a row back as an invented peak.
     * Every key of the spectra's {@link Spectrum#arrays()} and {@link Spectrum#peakArrays()} becomes one extra column
     * of both spectra_data and spectra_peaks, as 64-bit floats in the unit
     * {@link org.mzpeak.model.CvTerms#DEFAULT_ARRAY_UNITS} gives the key, or without a unit. A row holds the value of
     * {@code arrays()} where {@code mz()}/{@code intensity()} are written, and of {@code peakArrays()} at the peak's
     * {@link org.mzpeak.model.CentroidPeak#index()} where {@code peaks()} are; a spectrum without the key writes null
     * cells, which read back as no array. The column is named by the spec's short name for the array type
     * ({@code mean_inverse_reduced_ion_mobility}), else after the {@link org.mzpeak.model.CvTerms#ARRAY_TYPES} name,
     * the CURIE or the array name, made unique; a key that is not a CURIE is written as a non-standard data array
     * (MS:1000786) with the key as its name. A key MS:1000514 or MS:1000515 is rejected, since a spectrum holds those
     * arrays in {@code mz()} and {@code intensity()}, and so is a peak whose {@code index()} is not a position of its
     * spectrum's peak arrays when those are written.
     * Every key of the chromatograms' {@link Chromatogram#arrays()} becomes one extra column of chromatograms_data in
     * the same way, a chromatogram without the key writing null cells; a key MS:1000595 or MS:1000515 is rejected,
     * since a chromatogram holds those arrays in {@code time()} and {@code intensity()}. chromatograms_data has one
     * intensity column for each distinct {@link Chromatogram#intensityUnit()}, null included, declared in that unit:
     * that of MS:1000131, else of the unit of the first chromatogram in index order, is named {@code intensity}, every
     * other one {@code intensity_} followed by the unit with each character other than an ASCII letter or digit as
     * {@code _} ({@code intensity_UO_0000269}), or {@code intensity_unitless} for chromatograms without a unit, made
     * unique. A chromatogram's row holds its intensity in the column of its unit and null in the others.
     * chromatograms_data declares its time column in the {@link Chromatogram#timeUnit()} of the chromatograms, which
     * must all have the same one, and holds their times as given.
     * Spectrum, chromatogram and wavelength-spectrum indices must be unique and non-negative, and so must every
     * precursor_index that is written (the packed metadata layout writes only the primary precursor's); several
     * spectra may share one. In the split metadata layout, which writes every precursor, precursors of one spectrum
     * that would be written with the same precursor_index (null equal to null) are allowed only when none after the
     * first of them carries selected ions, since {@link MzPeakReader} attaches each ion to the first precursor with
     * its key, whatever the row order. The typical violation is a spectrum with several precursors written without
     * its parent spectra; writing the parents as well avoids it. The selected ions of a spectrum are written in the
     * order of their precursor's precursor_index, null first, which the Rust reference reader also needs.
     * With {@link SignalEncoding#NUMPRESS}, every m/z and intensity written must be in the range that encoding
     * documents.
     * Input that breaks these rules is rejected with an {@link MzPeakException} before anything is written.
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

    // ---- Streaming session API --------------------------------------------------------------------

    /**
     * Opens a {@link Session} that writes an unpacked {@code *.mzpeak/} directory at {@code target} one spectrum at a
     * time. {@code target} must not exist, and is then created with any missing parent directory, or be an empty
     * directory; this is checked before anything is written. The session writes the members into it as it goes, and
     * {@link Session#close()} without {@link Session#finish()} deletes them, and the directory if the session created
     * it.
     *
     * @param target         the dataset directory
     * @param fileMetadata   the file and run metadata, {@link FileMetadata#EMPTY} for none
     * @param scanSettings   the scan settings, written as {@link #writeDirectory(Path, List, List, List, List,
     *                       FileMetadata, Options) writeDirectory} writes them
     * @param spectrumArrays the keys of every {@link Spectrum#arrays()} and {@link Spectrum#peakArrays()} the spectra
     *                       may carry, each written as one extra column of spectra_data and spectra_peaks in the set's
     *                       iteration order, whether a spectrum has it or not, and named as {@code writeDirectory}
     *                       names the keys it meets in that order. The column order follows the set's iteration
     *                       order, so for reproducible output pass a {@link java.util.LinkedHashSet} or a
     *                       {@link java.util.SortedSet}: the order of {@link Set#of} can differ between JVM runs
     * @param options        the settings of the write
     * @return the open session
     * @throws MzPeakException      when {@code target} exists and is not an empty directory, when it cannot be read,
     *                              created or written, or when a key of {@code spectrumArrays} is MS:1000514 or
     *                              MS:1000515, which a spectrum holds in {@code mz()} and {@code intensity()}
     * @throws NullPointerException when an argument, a scan setting or a key is {@code null}
     */
    public static Session openDirectory(Path target, FileMetadata fileMetadata, List<ScanSettings> scanSettings,
                                        Set<String> spectrumArrays, Options options) {
        return Session.open(target, false, fileMetadata, scanSettings, spectrumArrays, options);
    }

    /**
     * Opens a {@link Session} that writes a single-file STORED {@code .mzpeak} ZIP at {@code target} one spectrum at
     * a time. {@code target} must not exist; this is checked before anything is written. The session writes the
     * members into a temp directory next to {@code target}, and {@link Session#finish()} packs them into a temp file
     * next to it, which it moves to {@code target} by renaming it; {@link Session#close()} without a finish deletes
     * both, leaving {@code target} absent.
     *
     * @param target         the archive file
     * @param fileMetadata   as for {@link #openDirectory(Path, FileMetadata, List, Set, Options) openDirectory}
     * @param scanSettings   as for {@code openDirectory}
     * @param spectrumArrays as for {@code openDirectory}: the extra columns follow the set's iteration order, so for
     *                       reproducible output pass a {@link java.util.LinkedHashSet} or a {@link java.util.SortedSet}
     * @param options        the settings of the write
     * @return the open session
     * @throws MzPeakException      when {@code target} exists, when the temp directory cannot be created or written,
     *                              or when a key of {@code spectrumArrays} is MS:1000514 or MS:1000515
     * @throws NullPointerException when an argument, a scan setting or a key is {@code null}
     */
    public static Session openArchive(Path target, FileMetadata fileMetadata, List<ScanSettings> scanSettings,
                                      Set<String> spectrumArrays, Options options) {
        return Session.open(target, true, fileMetadata, scanSettings, spectrumArrays, options);
    }

    /**
     * Intensity encoding for Numpress chunks. {@link #SLOF} is the standard choice for floating-point
     * intensities; {@link #PIC} is appropriate for integer ion-count arrays.
     *
     * <p>SLOF ({@code MS:1002314}) takes finite intensities of at least 0. PIC ({@code MS:1002313}) rounds each
     * intensity to the count {@code (long) (v + 0.5)} and takes finite intensities of at least 0 whose count is at most
     * 2147483647: the codec stores 32 bits, but readers built on the reference Java MS-Numpress decoder read a count
     * from 2^31 on as negative. {@link MzPeakReader} reads the PIC counts of spectra as unsigned 32-bit integers, as
     * the C++ reference does, so spectra of other writers with counts up to 4294967294 read correctly, and so do PIC
     * payloads under 8 bytes. A write with another value throws {@link MzPeakException} before anything is written.
     */
    public enum NumpressIntensityEncoding { SLOF, PIC }

    /** How the metadata members are laid out; {@link #SPLIT} unless the {@link Options} say otherwise. */
    public enum MetadataLayout {
        /**
         * The current layout (HUPO-PSI/mzPeak 0a28759 and later), the default: one flat table per metadata facet,
         * listed in the current manifest dialect with underscore roles and a column mapping. Spectra get
         * spectra_metadata, spectra_metadata_scans, spectra_metadata_precursors and spectra_metadata_selected_ions,
         * with every scan, precursor and selected ion; chromatograms get chromatograms_metadata and empty precursor
         * and selected-ion tables; wavelength spectra get wavelength_spectra_metadata and its scans table. The HEAD
         * reference Python reader reads this layout.
         */
        SPLIT,
        /**
         * The pin-era packed layout (HUPO-PSI/mzPeak 29e59b2), kept for readers of that era, mzPeakJ 0.10.3 and
         * earlier among them: one struct per facet in one member per entity, the pin-era role spellings and no column
         * mapping. Only the first scan and the primary precursor and selected ion of a spectrum are written. The HEAD
         * reference Python reader rejects this layout.
         */
        PACKED
    }

    /** How spectra_data and spectra_peaks are encoded; chromatograms and wavelength spectra are always points. */
    public enum SignalEncoding {
        /** One row per point: index, m/z and intensity. */
        POINT,
        /**
         * MS-Numpress chunks: Numpress linear m/z and Numpress SLOF or PIC intensity, as chosen by
         * {@link Options#numpressIntensity()}, in chunks of up to 500 points per spectrum. The chunks have no
         * unencoded fallback, so every m/z and intensity written to spectra_data and spectra_peaks must be one the
         * codecs encode. Numpress linear, at the fixed point 100000, takes finite m/z of at least 0 whose fixed-point
         * value {@code (long) (mz * 100000 + 0.5)} is at most 4294967295 (m/z 42949.67295), and stores every m/z
         * after the first two of a chunk as the difference of its fixed-point value from the extrapolation
         * 2 * previous - the one before, which must fit a signed 32-bit integer (within about 21474.84 m/z); sorted
         * m/z up to 21474.83 always do. Intensities must be in the range of the {@link NumpressIntensityEncoding}
         * (finite and at least 0; with PIC, rounding to at most 2147483647). A write with another value throws
         * {@link MzPeakException} naming the spectrum index, the array and the value before anything is written;
         * {@link #POINT} writes such values (a NaN m/z reads back interpolated, as a null in the point layout does).
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
                OptionalLong.empty(), MetadataLayout.SPLIT, List.of());

        private final SignalEncoding signalEncoding;
        private final NumpressIntensityEncoding numpressIntensity;
        private final OptionalLong rowGroupSize;
        private final MetadataLayout metadataLayout;
        private final List<CvEntry> cvList;

        private Options(SignalEncoding signalEncoding, NumpressIntensityEncoding numpressIntensity,
                        OptionalLong rowGroupSize, MetadataLayout metadataLayout, List<CvEntry> cvList) {
            this.signalEncoding = signalEncoding;
            this.numpressIntensity = numpressIntensity;
            this.rowGroupSize = rowGroupSize;
            this.metadataLayout = metadataLayout;
            this.cvList = cvList;
        }

        /**
         * Point layout, SLOF Numpress intensity, no row-group size, the split metadata layout and no cv list of the
         * caller's: what the plain overloads write.
         */
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

        /** How the metadata members are laid out; {@link MetadataLayout#SPLIT} by default. */
        public MetadataLayout metadataLayout() {
            return metadataLayout;
        }

        /**
         * The caller's controlled vocabularies, whose entries {@code metadata.cv_list} declares for the CVs in use in
         * place of the writer's own ({@link #withCvList}); empty by default. Unmodifiable.
         */
        public List<CvEntry> cvList() {
            return cvList;
        }

        /**
         * These options with {@code encoding} for spectra_data and spectra_peaks.
         *
         * @throws NullPointerException if {@code encoding} is {@code null}
         */
        public Options withSignalEncoding(SignalEncoding encoding) {
            return new Options(Objects.requireNonNull(encoding, "encoding"), numpressIntensity, rowGroupSize,
                    metadataLayout, cvList);
        }

        /**
         * These options with {@code encoding} for Numpress intensities; it takes effect only with
         * {@link SignalEncoding#NUMPRESS}.
         *
         * @throws NullPointerException if {@code encoding} is {@code null}
         */
        public Options withNumpressIntensity(NumpressIntensityEncoding encoding) {
            return new Options(signalEncoding, Objects.requireNonNull(encoding, "encoding"), rowGroupSize,
                    metadataLayout, cvList);
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
            return new Options(signalEncoding, numpressIntensity, OptionalLong.of(bytes), metadataLayout, cvList);
        }

        /**
         * These options with {@code layout} for the metadata members; {@link MetadataLayout#PACKED} writes the pin-era
         * layout of mzPeakJ 0.10.3 and earlier.
         *
         * @throws NullPointerException if {@code layout} is {@code null}
         */
        public Options withMetadataLayout(MetadataLayout layout) {
            return new Options(signalEncoding, numpressIntensity, rowGroupSize,
                    Objects.requireNonNull(layout, "layout"), cvList);
        }

        /**
         * These options with the caller's controlled vocabularies, such as the cv list of an mzML source, so that
         * {@code metadata.cv_list} keeps their versions and URIs. The writer still declares only MS, UO and the CVs
         * the dataset uses, in the order of {@link CvEntry#known()}, but a CV the list holds is declared with the
         * list's entry, the first one when the list holds its id more than once, its missing fields filled: each
         * {@code null} full name, URI or version takes the value of {@link CvEntry#known()}, with a warning naming
         * the fields. A CV in use that {@link CvEntry#known()} lacks and the list holds is declared after the known
         * ones, in the list's order, without the warning that such a CV otherwise gets, if the list's entry has all
         * fields; else it is not declared, and the warning names the missing fields. An entry without an id is not
         * declared, with a warning; nor is an entry for a CV the dataset does not use. So every
         * {@code metadata.cv_list} entry has an id, full name, URI and version. The list is copied.
         *
         * @throws NullPointerException if {@code cvList} or one of its entries is {@code null}
         */
        public Options withCvList(List<CvEntry> cvList) {
            return new Options(signalEncoding, numpressIntensity, rowGroupSize, metadataLayout,
                    List.copyOf(Objects.requireNonNull(cvList, "cvList")));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            return o instanceof Options other && signalEncoding == other.signalEncoding
                    && numpressIntensity == other.numpressIntensity && rowGroupSize.equals(other.rowGroupSize)
                    && metadataLayout == other.metadataLayout && cvList.equals(other.cvList);
        }

        @Override
        public int hashCode() {
            return Objects.hash(signalEncoding, numpressIntensity, rowGroupSize, metadataLayout, cvList);
        }

        @Override
        public String toString() {
            return "Options[signalEncoding=" + signalEncoding + ", numpressIntensity=" + numpressIntensity
                    + ", rowGroupSize=" + rowGroupSize + ", metadataLayout=" + metadataLayout + ", cvList=" + cvList
                    + "]";
        }
    }

    /**
     * The write of one mzPeak dataset one spectrum at a time (design §7, MZ-11), opened by
     * {@link MzPeakWriter#openDirectory openDirectory} or {@link MzPeakWriter#openArchive openArchive}: add the
     * spectra in index order with {@link #addSpectrum}, the chromatograms and wavelength spectra with
     * {@link #addChromatogram} and {@link #addWavelengthSpectrum} at any point before the finish, then call
     * {@link #finish()}. Open it in try-with-resources, so that a session that does not finish is closed:
     *
     * <pre>{@code
     * try (MzPeakWriter.Session session = MzPeakWriter.openArchive(target, FileMetadata.EMPTY, List.of(),
     *         Set.of(), MzPeakWriter.Options.defaults())) {
     *     for (Spectrum spectrum : spectra) {
     *         session.addSpectrum(spectrum);
     *     }
     *     session.finish();
     * }
     * }</pre>
     *
     * <p><b>Contract.</b> The spectra are added in index order, numbered 0, 1, 2, ...: a spectrum whose
     * {@link Spectrum#index()} is not the number of spectra added so far is refused. Each spectrum is written as given,
     * with its index and every precursor_index, which must name a spectrum of the dataset: when the session finishes,
     * each precursor_index the metadata layout writes must be less than the number of spectra (the parent may come
     * after the spectrum that names it). Every key of a spectrum's {@link Spectrum#arrays()} and
     * {@link Spectrum#peakArrays()} must be one of the spectrum arrays the session was opened with. The other rules are
     * those of {@link MzPeakWriter#writeDirectory(Path, List, List, List, List, FileMetadata, Options) writeDirectory}:
     * each precursor_index the layout writes is non-negative; in the split layout, precursors of a spectrum that share
     * their precursor_index carry no selected ions after the first of them; a peak's index is a position of the peak
     * arrays; with {@link SignalEncoding#NUMPRESS}, every value written can be encoded. A spectrum that breaks one is
     * refused with an {@link MzPeakException}, nothing of it is written, and the session goes on with the next
     * spectrum. A main axis that is not ascending is written re-sorted, with a WARNING. Chromatograms and wavelength
     * spectra are held until the finish and written then as {@code writeDirectory} writes them: in the order of their
     * indices, which must be unique and non-negative, numbered 0..n-1, each precursor_index of a chromatogram as the
     * new index of the chromatogram it names, or null; a violation fails the finish.
     *
     * <p><b>Targets.</b> {@link MzPeakWriter#openDirectory openDirectory} writes the members into the directory as the
     * session goes; the directory must not exist (it is then created) or be empty. {@link MzPeakWriter#openArchive
     * openArchive} writes them into a temp directory next to the archive, which must not exist, and {@link #finish()}
     * packs them into a temp file next to it and renames that to the archive. The batch overloads
     * ({@code writeDirectory}, {@code writeArchive} and their Numpress forms) run on this class in a replace mode
     * instead: an archive replaces an existing file at the final move, and a directory is created or reused, each
     * member replacing a file of its name and every other file left alone.
     *
     * <p><b>Ending.</b> {@link #finish()} closes spectra_data and spectra_peaks, writes the metadata members,
     * chromatograms_data, wavelength_spectra_data and the manifest, packs and moves an archive, and logs the warnings
     * of the write. {@link #close()} without a finish abandons the write: an archive session deletes its temp directory
     * and temp file, so the target stays absent, and a directory session deletes the members it wrote, and the
     * directory if it created it (its missing parents stay). In replace mode an existing archive stays untouched and a
     * directory stays as it is. A finish that throws, and an I/O failure while a spectrum is added, leave what
     * {@link #close()} leaves and close the session. After {@link #finish()} or {@link #close()}, adding or finishing
     * throws {@link IllegalStateException}, and closing does nothing. A session is not safe for use by several threads.
     *
     * <p><b>Memory.</b> A session holds about 2 KB per spectrum added (its description with its precursors, five
     * summary values and its row counts), the chromatograms and wavelength spectra added, and the open row groups of
     * spectra_data and spectra_peaks, up to the row-group size each (parquet-java's default of 128 MiB, which
     * {@link Options#withRowGroupSize} lowers); never the signal of a spectrum once {@link #addSpectrum} has returned.
     * Packing an archive needs disk space for about twice its size.
     */
    public static final class Session implements AutoCloseable {

        private enum State { OPEN, FINISHED, CLOSED }

        private final Path target;
        private final boolean archive;
        /** Replace mode, the batch overloads': an archive replaces a file, and a directory is left as it is. */
        private final boolean replace;
        /** Whether a strict directory session created its directory, which closing it then deletes. */
        private final boolean createdDirectory;
        /** The directory the members are written into: the target, or the temp directory of an archive. */
        private final Path directory;
        private final Options options;
        private final Set<String> spectrumArrays;
        private final List<ExtraColumn> extras;
        private final List<ScanSettings> scanSettings;
        private final Map<String, String> footer;
        private final WriteReport report;
        private final DatasetWriter dataset;
        private final List<Chromatogram> chromatograms = new ArrayList<>();
        private final List<WavelengthSpectrum> wavelengthSpectra = new ArrayList<>();
        /** What the packed layout does not write of the spectra added: later scans, precursors, selected ions. */
        private final int[] packedDrops = new int[3];
        private State state = State.OPEN;

        /**
         * Opens the session: creates the temp directory of an archive and opens spectra_data in the directory of the
         * members, creating that directory if need be. A failure there leaves what {@link #close()} leaves.
         */
        private Session(Path target, boolean archive, boolean replace, boolean createdDirectory, Options options,
                        List<ExtraColumn> extras, boolean writesSpectraData, List<ScanSettings> scanSettings,
                        Map<String, String> footer, WriteReport report) {
            this.target = target;
            this.archive = archive;
            this.replace = replace;
            this.createdDirectory = createdDirectory;
            this.options = options;
            this.spectrumArrays = new LinkedHashSet<>();
            for (ExtraColumn extra : extras) {
                spectrumArrays.add(extra.key());
            }
            this.extras = extras;
            this.scanSettings = scanSettings;
            this.footer = footer;
            this.report = report;
            this.directory = archive ? ArchivePacker.stagingDirectory(target) : target;
            this.dataset = new DatasetWriter(directory, options, extras, writesSpectraData);
            try (Step step = new Step()) {
                dataset.open();
                step.done();
            }
        }

        /** The session of the public factories, whose target is checked strictly. */
        private static Session open(Path target, boolean archive, FileMetadata fileMetadata,
                                    List<ScanSettings> scanSettings, Set<String> spectrumArrays, Options options) {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(fileMetadata, "fileMetadata");
            List<ScanSettings> settings = List.copyOf(Objects.requireNonNull(scanSettings, "scanSettings"));
            Set<String> keys = new LinkedHashSet<>();
            for (String key : Objects.requireNonNull(spectrumArrays, "spectrumArrays")) {
                keys.add(Objects.requireNonNull(key, "spectrumArrays key"));
            }
            Objects.requireNonNull(options, "options");
            List<ExtraColumn> extras = WritePlanner.spectrumExtras(keys);
            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (exists && (archive || !isEmptyDirectory(target))) {
                throw new MzPeakException(archive
                        ? "mzPeak session target " + target + " already exists; openArchive writes only a new archive"
                        : "mzPeak session target " + target + " exists and is not an empty directory; openDirectory"
                        + " writes only into a new or empty directory");
            }
            return new Session(target, archive, false, !archive && !exists, options, extras, true, settings,
                    FooterMetadataWriter.serialize(fileMetadata), new WriteReport());
        }

        /**
         * The session the batch overloads write {@code plan} on, in replace mode: an archive at {@code target} is
         * replaced by the final move, and a directory at {@code target} is created or reused, its members written over
         * files of their names and other files left alone; closing the session without a finish deletes only the temp
         * files of an archive. Its spectrum signal files get the plan's extra columns, the union of the keys of the
         * planned spectra in first-seen order. The plan's spectrum signals are added with {@link #append} and the
         * session finishes with {@link #finish(WritePlan)}.
         */
        static Session replacing(Path target, boolean archive, WritePlan plan) {
            return new Session(target, archive, true, false, plan.options(), plan.spectrumExtras(),
                    plan.writesSpectraData(), plan.scanSettings(), plan.footer(), plan.report());
        }

        /** Whether {@code target}, which exists, is a directory without an entry. */
        private static boolean isEmptyDirectory(Path target) {
            if (!Files.isDirectory(target)) {
                return false;
            }
            try (Stream<Path> entries = Files.list(target)) {
                return entries.findAny().isEmpty();
            } catch (IOException e) {
                throw new MzPeakException("Cannot list mzPeak session target " + target, e);
            }
        }

        /**
         * Writes the signal of {@code spectrum}, the next spectrum, and keeps its metadata for the finish.
         *
         * @throws MzPeakException       when {@code spectrum} breaks the session's contract, before anything of it is
         *                               written, or when its write fails, which closes the session
         * @throws IllegalStateException after {@link #finish()} or {@link #close()}
         * @throws NullPointerException  if {@code spectrum} is {@code null}
         */
        public void addSpectrum(Spectrum spectrum) {
            requireOpen();
            Objects.requireNonNull(spectrum, "spectrum");
            long expected = dataset.entries().size();
            if (spectrum.index() != expected) {
                throw new MzPeakException("expected spectrum index " + expected + ", got " + spectrum.index());
            }
            WriteReport warnings = new WriteReport();
            append(WritePlanner.sessionSignal(spectrum, spectrumArrays, options, warnings, packedDrops));
            for (String warning : warnings.warnings()) {
                report.warn(warning);
            }
        }

        /**
         * Adds {@code chromatogram}, which is written at the finish.
         *
         * @throws IllegalStateException after {@link #finish()} or {@link #close()}
         * @throws NullPointerException  if {@code chromatogram} is {@code null}
         * @throws MzPeakException       if its {@link Chromatogram#timeUnit()} differs from that of the first
         *                               chromatogram added; it is not added
         */
        public void addChromatogram(Chromatogram chromatogram) {
            requireOpen();
            Objects.requireNonNull(chromatogram, "chromatogram");
            if (!chromatograms.isEmpty()) {
                WritePlanner.requireSameTimeUnit(chromatograms.get(0), chromatogram);
            }
            chromatograms.add(chromatogram);
        }

        /**
         * Adds {@code spectrum}, which is written at the finish.
         *
         * @throws IllegalStateException after {@link #finish()} or {@link #close()}
         * @throws NullPointerException  if {@code spectrum} is {@code null}
         */
        public void addWavelengthSpectrum(WavelengthSpectrum spectrum) {
            requireOpen();
            wavelengthSpectra.add(Objects.requireNonNull(spectrum, "spectrum"));
        }

        /**
         * Completes the dataset: closes spectra_data and spectra_peaks, writes the metadata members, the chromatograms,
         * the wavelength spectra and the manifest, and packs an archive and moves it into place; then logs the
         * warnings of the write. A failure leaves what {@link #close()} leaves.
         *
         * @throws MzPeakException       when a precursor_index names no spectrum of the dataset, when the
         *                               chromatograms or wavelength spectra break the contract, or when a write fails
         * @throws IllegalStateException after {@link #finish()} or {@link #close()}
         */
        public void finish() {
            finish(() -> {
                WritePlanner.warnPackedDrops(packedDrops, report);
                return WritePlanner.planFinish(options, dataset.entries(), extras, chromatograms, wavelengthSpectra,
                        scanSettings, footer, report);
            });
        }

        /**
         * Abandons the write unless it has finished: deletes what the session wrote, as the class documentation
         * says; does nothing after {@link #finish()} or {@link #close()}.
         *
         * @throws MzPeakException when the open signal files cannot be closed; what the session wrote is deleted all
         *                         the same
         */
        @Override
        public void close() {
            if (state == State.OPEN) {
                abort();
            }
        }

        /** Appends {@code signal}, the next spectrum's; a failure closes the session. */
        void append(SpectrumSignal signal) {
            try (Step step = new Step()) {
                dataset.append(signal);
                step.done();
            }
        }

        /** Finishes with {@code plan}, a batch plan whose spectrum signals were appended. */
        void finish(WritePlan plan) {
            finish(() -> plan);
        }

        /**
         * Closes the signal files, checks the precursor indices, writes the metadata of the plan {@code planner}
         * makes and packs an archive; then deletes the temp directory and logs the plan's warnings.
         */
        private void finish(Supplier<WritePlan> planner) {
            requireOpen();
            WritePlan plan;
            try (Step step = new Step()) {
                dataset.closeSignals();
                WritePlanner.requireWrittenParents(dataset.entries(), options.metadataLayout());
                plan = planner.get();
                dataset.writeMetadata(plan);
                if (archive) {
                    ArchivePacker.pack(directory, target, replace);
                }
                step.done();
            }
            state = State.FINISHED;
            if (archive) {
                ArchivePacker.deleteRecursively(directory);
            }
            for (String warning : plan.report().warnings()) {
                LOG.log(Level.WARNING, warning);
            }
        }

        private void requireOpen() {
            if (state != State.OPEN) {
                throw new IllegalStateException("the mzPeak session for " + target + " is "
                        + (state == State.FINISHED ? "finished" : "closed"));
            }
        }

        /**
         * Closes the session without a finish: closes the open signal files and deletes the temp directory of an
         * archive, or in strict directory mode the files the session wrote and the directory it created.
         */
        private void abort() {
            state = State.CLOSED;
            try {
                dataset.closeSignals();
            } finally {
                if (archive) {
                    ArchivePacker.deleteRecursively(directory);
                } else if (!replace) {
                    dataset.deleteFiles(createdDirectory);
                }
            }
        }

        /**
         * A step of the session that writes, run in try-with-resources: closed before {@link #done()}, that is when
         * the step throws, it closes the session as {@link Session#close()} does, and an exception of that is added
         * to the step's as suppressed.
         */
        private final class Step implements AutoCloseable {

            private boolean done;

            void done() {
                done = true;
            }

            @Override
            public void close() {
                if (!done) {
                    abort();
                }
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /**
     * The one path behind every batch overload: plan without any I/O (a contract violation throws here, before
     * any file or temp directory exists), then write the plan into {@code target}, as a directory or, for
     * {@code archive}, as a STORED ZIP built in a temp directory and moved into place.
     */
    private static void write(Path target, boolean archive, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                              List<WavelengthSpectrum> wavelengthSpectra, List<ScanSettings> scanSettings,
                              FileMetadata fileMetadata, Options options) {
        write(target, archive, WritePlanner.plan(spectra, chromatograms, wavelengthSpectra, scanSettings,
                fileMetadata, options));
    }

    /**
     * Writes {@code plan} into {@code target} on a session in replace mode ({@link Session#replacing}): its spectrum
     * signals appended in order, then its finish with the plan's chromatograms, wavelength spectra and warnings, which
     * are logged once the dataset is written.
     */
    static void write(Path target, boolean archive, WritePlan plan) {
        try (Session session = Session.replacing(target, archive, plan)) {
            for (SpectrumSignal signal : plan.spectrumSignals()) {
                session.append(signal);
            }
            session.finish(plan);
        }
    }

    /** The options of a legacy Numpress overload; a {@code null} intensity encoding has always meant SLOF. */
    private static Options numpress(NumpressIntensityEncoding intensityEncoding) {
        return Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS).withNumpressIntensity(
                intensityEncoding == null ? NumpressIntensityEncoding.SLOF : intensityEncoding);
    }
}
