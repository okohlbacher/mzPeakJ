package org.mzpeak.io;

import org.apache.parquet.io.InputFile;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.CvTerms;
import org.mzpeak.model.DoubleRange;
import org.mzpeak.model.SignalSlice;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Reads an mzPeak dataset — either an unpacked {@code *.mzpeak/} directory or a single-file (STORED)
 * {@code .mzpeak} ZIP. Surface: open, count, metadata access, spectrum (m/z + intensity + peaks + extra arrays
 * such as ion mobility) materialization, lookup by index / scan number / native id / retention time, iteration, range
 * queries ({@link #extractSignal(SignalQuery)}, {@link #queryPeaks(SignalQuery)}), metadata column statistics
 * ({@link #columnStatistics}, {@link #observedMzRange()}), and chromatograms, including a TIC and a BPC derived from
 * the spectrum metadata ({@link #encodedTic()}, {@link #encodedBpc()}).
 *
 * <p>Metadata is loaded eagerly; signal files are loaded lazily and cached. Profile null-marked points are
 * reconstructed by default (so the point count matches the declared {@code number_of_data_points}); open with
 * {@link Options#withReconstructProfile(boolean) Options.defaults().withReconstructProfile(false)} to get only the
 * stored non-null points. Spectra combine the data arrays member and the peaks member by default; a
 * {@link SignalMode} set with {@link Options#withSignalMode(SignalMode)} reads only one of them. Not thread-safe.
 */
public final class MzPeakReader implements Iterable<Spectrum>, AutoCloseable {

    /**
     * Which of a dataset's two spectrum signal members a reader decodes: the data arrays member (typically
     * {@code spectra_data.parquet}, profile or other point arrays) and the peaks member (typically
     * {@code spectra_peaks.parquet}, centroids). A spectrum may be stored in either or both.
     *
     * <p>{@link #PROFILE} and {@link #CENTROID} read one member and leave the other unopened. A spectrum read in one
     * of these modes is a partial view of what the dataset stores and must not be passed to {@link MzPeakWriter},
     * which would write only that part: read with {@link #BOTH} before writing.
     */
    public enum SignalMode {
        /**
         * Both members, the default. {@link Spectrum#mz()}, {@link Spectrum#intensity()} and
         * {@link Spectrum#arrays()} hold the data arrays of the spectrum, or its centroids in stored order when the
         * data arrays member has none; {@link Spectrum#peaks()} and {@link Spectrum#peakArrays()} hold its centroids.
         */
        BOTH,
        /**
         * The data arrays member only. {@link Spectrum#mz()}, {@link Spectrum#intensity()} and
         * {@link Spectrum#arrays()} hold the data arrays of the spectrum, and are empty when that member has none;
         * {@link Spectrum#peaks()} and {@link Spectrum#peakArrays()} are always empty.
         */
        PROFILE,
        /**
         * The peaks member only. {@link Spectrum#mz()}, {@link Spectrum#intensity()} and {@link Spectrum#arrays()}
         * hold the centroids of the spectrum in stored order, and are empty when that member has none;
         * {@link Spectrum#peaks()} and {@link Spectrum#peakArrays()} hold the same centroids, as in {@link #BOTH}.
         */
        CENTROID
    }

    /**
     * How a reader decodes a dataset. Immutable: start from {@link #defaults()} and derive variants with the
     * {@code with...} methods, each of which returns a new instance. Two instances are equal when all their options
     * are equal.
     */
    public static final class Options {

        private final boolean reconstructProfile;
        private final SignalMode signalMode;

        private Options(boolean reconstructProfile, SignalMode signalMode) {
            this.reconstructProfile = reconstructProfile;
            this.signalMode = signalMode;
        }

        /**
         * The default options: null-marked profile points are reconstructed, and spectra are read from both signal
         * members ({@link SignalMode#BOTH}).
         */
        public static Options defaults() {
            return new Options(true, SignalMode.BOTH);
        }

        /**
         * Whether null-marked profile points are reconstructed, so that the point count matches the declared
         * {@code number_of_data_points}; {@code false} returns only the stored (non-null) points. Default
         * {@code true}.
         */
        public boolean reconstructProfile() {
            return reconstructProfile;
        }

        /** These options with {@link #reconstructProfile()} set to {@code reconstructProfile}, as a new instance. */
        public Options withReconstructProfile(boolean reconstructProfile) {
            return new Options(reconstructProfile, signalMode);
        }

        /**
         * Which signal members spectra are read from. Default {@link SignalMode#BOTH}; the other modes give partial
         * views of a spectrum, see {@link SignalMode}.
         */
        public SignalMode signalMode() {
            return signalMode;
        }

        /**
         * These options with {@link #signalMode()} set to {@code signalMode}, as a new instance.
         *
         * @throws NullPointerException if {@code signalMode} is {@code null}
         */
        public Options withSignalMode(SignalMode signalMode) {
            return new Options(reconstructProfile, Objects.requireNonNull(signalMode, "signalMode"));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Options other && reconstructProfile == other.reconstructProfile
                    && signalMode == other.signalMode;
        }

        @Override
        public int hashCode() {
            return 31 * Boolean.hashCode(reconstructProfile) + signalMode.hashCode();
        }

        @Override
        public String toString() {
            return "Options[reconstructProfile=" + reconstructProfile + ", signalMode=" + signalMode + "]";
        }
    }

    /** The lowest and highest observed m/z terms of the spectrum metadata. */
    private static final String LOWEST_OBSERVED_MZ = "MS:1000528";
    private static final String HIGHEST_OBSERVED_MZ = "MS:1000527";
    /** The total ion current and base peak intensity terms of the spectrum metadata. */
    private static final String TOTAL_ION_CURRENT = "MS:1000285";
    private static final String BASE_PEAK_INTENSITY = "MS:1000505";
    /** The chromatogram types total ion current chromatogram and basepeak chromatogram. */
    private static final String TIC_CHROMATOGRAM = "MS:1000235";
    private static final String BPC_CHROMATOGRAM = "MS:1000628";

    private final MzPeakSource source;
    private final MzPeakManifest manifest;
    private final Options options;
    private final List<SpectrumDescription> descriptions;
    private final Map<Long, double[]> deltaModels;
    private final Map<Long, Integer> indexToOrdinal;
    private final Map<Integer, Long> scanNumberToIndex;
    private final Map<String, Long> idToIndex;
    private final double[] sortedTimes;
    private final long[] timeIndexOrder;

    private final String metadataFileName;
    private final String dataFileName;   // nullable
    private final String peaksFileName;  // nullable
    private boolean fileMetadataLoaded;
    private org.mzpeak.model.meta.FileMetadata fileMetadata;
    private boolean scanSettingsLoaded;
    private List<org.mzpeak.model.meta.ScanSettings> scanSettings;
    private Map<String, String> footerKeyValue;

    private boolean dataLoaded;
    private SpectrumArrayStore dataStore;
    private boolean peaksLoaded;
    private SpectrumArrayStore peakStore;
    private boolean chromatogramsLoaded;
    private ChromatogramStore chromatogramStore;
    private boolean wavelengthLoaded;
    private WavelengthSpectrumStore wavelengthStore;
    private boolean closed;

    private MzPeakReader(MzPeakSource source, MzPeakManifest manifest, Options options,
                         SpectrumMetadataDecoder.Decoded decoded, String metadataFileName,
                         String dataFileName, String peaksFileName) {
        this.source = source;
        this.manifest = manifest;
        this.options = options;
        this.descriptions = decoded.descriptions();
        this.deltaModels = decoded.deltaModels();
        this.metadataFileName = metadataFileName;
        this.dataFileName = dataFileName;
        this.peaksFileName = peaksFileName;

        this.indexToOrdinal = new HashMap<>();
        this.scanNumberToIndex = new HashMap<>();
        this.idToIndex = new HashMap<>();
        for (int i = 0; i < descriptions.size(); i++) {
            SpectrumDescription d = descriptions.get(i);
            indexToOrdinal.put(d.index(), i);
            if (d.id() != null) {
                idToIndex.put(d.id(), d.index());
                org.mzpeak.model.NativeId.scanNumber(d.id())
                        .ifPresent(scan -> scanNumberToIndex.putIfAbsent(scan, d.index()));
            }
        }
        // RT lookup: (time, index) sorted by time, excluding spectra with non-finite retention times.
        List<Integer> finite = new ArrayList<>();
        for (int i = 0; i < descriptions.size(); i++) {
            if (Double.isFinite(descriptions.get(i).retentionTime())) {
                finite.add(i);
            }
        }
        finite.sort((a, b) ->
                Double.compare(descriptions.get(a).retentionTime(), descriptions.get(b).retentionTime()));
        this.sortedTimes = new double[finite.size()];
        this.timeIndexOrder = new long[finite.size()];
        for (int i = 0; i < finite.size(); i++) {
            sortedTimes[i] = descriptions.get(finite.get(i)).retentionTime();
            timeIndexOrder[i] = descriptions.get(finite.get(i)).index();
        }
    }

    /** Open an mzPeak dataset (directory or ZIP), reconstructing null-marked profile points. */
    public static MzPeakReader open(Path path) {
        return open(path, Options.defaults());
    }

    /** Open an mzPeak dataset; {@code reconstructProfile=false} returns only stored (non-null) profile points. */
    public static MzPeakReader open(Path path, boolean reconstructProfile) {
        return open(path, Options.defaults().withReconstructProfile(reconstructProfile));
    }

    /**
     * Open an mzPeak dataset (directory or ZIP) with {@code options}.
     *
     * @throws NullPointerException if {@code path} or {@code options} is {@code null}
     */
    public static MzPeakReader open(Path path, Options options) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return open(MzPeakSource.open(path), options);
    }

    /**
     * Open the mzPeak dataset that {@code source} provides, with the {@linkplain Options#defaults() default
     * options}. The reader takes ownership of {@code source}; see {@link #open(MzPeakSource, Options)}.
     *
     * @throws NullPointerException if {@code source} is {@code null}
     */
    public static MzPeakReader open(MzPeakSource source) {
        return open(source, Options.defaults());
    }

    /**
     * Open the mzPeak dataset that {@code source} provides, with {@code options}.
     *
     * <p>The reader takes ownership of {@code source} and closes it exactly once: when this method throws (also for
     * a {@code null} {@code options}, and for an {@link Error}), or on the first {@link #close()} of the returned
     * reader. A failure to close it then is added to the thrown exception as suppressed, unless it is that same
     * exception.
     *
     * @throws NullPointerException if {@code source} or {@code options} is {@code null}
     */
    public static MzPeakReader open(MzPeakSource source, Options options) {
        Objects.requireNonNull(source, "source");
        try {
            Objects.requireNonNull(options, "options");
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            SpectrumMetadataDecoder.Decoded decoded = SpectrumMetadataDecoder.decode(source, manifest);
            // present: decode() throws when the manifest lists no spectrum metadata member
            String metadataFile = member(manifest, EntityType.SPECTRUM, DataKind.METADATA);
            String dataFile = member(manifest, EntityType.SPECTRUM, DataKind.DATA_ARRAYS);
            String peaksFile = member(manifest, EntityType.SPECTRUM, DataKind.PEAKS);
            return new MzPeakReader(source, manifest, options, decoded,
                    metadataFile, dataFile, peaksFile);
        } catch (RuntimeException | Error e) {
            try {
                source.close();
            } catch (RuntimeException | Error closing) {
                if (closing != e) {
                    e.addSuppressed(closing);
                }
            }
            throw e;
        }
    }

    /** The options this reader was opened with. */
    public Options options() {
        return options;
    }

    /** The name of the member with role {@code (entity, kind)}, or {@code null} if the manifest lists none. */
    private static String member(MzPeakManifest manifest, EntityType entity, DataKind kind) {
        return manifest.find(entity, kind).map(MzPeakManifest.Entry::name).orElse(null);
    }

    public MzPeakManifest manifest() {
        return manifest;
    }

    /**
     * Scan settings from the manifest's {@code metadata.scan_settings_list}, else from the spectrum metadata
     * footer — non-empty for imaging (MSI / imzML) datasets, empty for conventional LC-MS files. Read on first use and
     * cached.
     *
     * @throws IllegalStateException if this reader is closed and the scan settings are not cached yet and must be read
     *                               from the footer
     */
    public synchronized java.util.List<org.mzpeak.model.meta.ScanSettings> scanSettings() {
        if (!scanSettingsLoaded) {
            scanSettings = FooterMetadataReader.scanSettings(manifest.metadataJson(), this::footerKeyValue);
            scanSettingsLoaded = true;
        }
        return scanSettings;
    }

    /**
     * {@code true} if this is a mass-spectrometry imaging dataset (manifest {@code metadata.imaging.is_imaging}
     * flag set or {@link #scanSettings()} is non-empty).
     *
     * @throws IllegalStateException as {@link #scanSettings()} does, when the manifest flag is not set
     */
    public boolean isImaging() {
        return manifest.isImaging() || !scanSettings().isEmpty();
    }

    public int size() {
        return descriptions.size();
    }

    public List<SpectrumDescription> metadata() {
        return Collections.unmodifiableList(descriptions);
    }

    public Optional<SpectrumDescription> getMetadata(long index) {
        Integer ordinal = indexToOrdinal.get(index);
        return ordinal == null ? Optional.empty() : Optional.of(descriptions.get(ordinal));
    }

    public SpectrumDescription metadataAt(int ordinal) {
        return descriptions.get(ordinal);
    }

    /**
     * The spectrum with {@code index}, or empty if there is none.
     *
     * @throws IllegalStateException if this reader is closed and the spectrum exists
     */
    public Optional<Spectrum> getSpectrum(long index) {
        Integer ordinal = indexToOrdinal.get(index);
        return ordinal == null ? Optional.empty() : Optional.of(materialize(descriptions.get(ordinal)));
    }

    /**
     * The spectrum at {@code ordinal} in metadata order.
     *
     * @throws IllegalStateException if this reader is closed
     */
    public Spectrum getSpectrumAt(int ordinal) {
        return materialize(descriptions.get(ordinal));
    }

    /** Look up by native id string (e.g. {@code "controllerType=0 controllerNumber=1 scan=3"}). */
    public Optional<Spectrum> getSpectrumById(String id) {
        Long index = idToIndex.get(id);
        return index == null ? Optional.empty() : getSpectrum(index);
    }

    /** Look up by vendor scan number parsed from the native id. */
    public Optional<Spectrum> getSpectrumByScanNumber(int scanNumber) {
        Long index = scanNumberToIndex.get(scanNumber);
        return index == null ? Optional.empty() : getSpectrum(index);
    }

    /** The spectrum whose retention time is nearest {@code time}; empty only if there are no spectra. */
    public Optional<Spectrum> getSpectrumByTime(double time) {
        if (sortedTimes.length == 0 || !Double.isFinite(time)) {
            return Optional.empty();
        }
        int pos = java.util.Arrays.binarySearch(sortedTimes, time);
        if (pos < 0) {
            int ins = -pos - 1;
            if (ins == 0) {
                pos = 0;
            } else if (ins >= sortedTimes.length) {
                pos = sortedTimes.length - 1;
            } else {
                pos = (time - sortedTimes[ins - 1] <= sortedTimes[ins] - time) ? ins - 1 : ins;
            }
        }
        return getSpectrum(timeIndexOrder[pos]);
    }

    private Spectrum materialize(SpectrumDescription description) {
        requireOpen();
        long index = description.index();
        SignalMode mode = options.signalMode();
        // PROFILE never loads the peaks member, CENTROID never the data member
        SpectrumArrayStore.Arrays profile =
                mode == SignalMode.CENTROID || data() == null ? null : data().get(index);
        SpectrumArrayStore.Arrays centroids =
                mode == SignalMode.PROFILE || peaks() == null ? null : peaks().get(index);

        List<CentroidPeak> peakList = List.of();
        if (centroids != null) {
            double[] cmz = centroids.mz();
            double[] cin = centroids.intensity();
            List<CentroidPeak> built = new ArrayList<>(cmz.length);
            for (int i = 0; i < cmz.length; i++) {
                built.add(new CentroidPeak(cmz[i], cin[i], i));
            }
            peakList = built;
        }

        double[] mz;
        double[] intensity;
        Map<String, double[]> arrays;
        if (profile != null) {
            mz = profile.mz();
            intensity = profile.intensity();
            arrays = profile.extras();
        } else if (centroids != null) {
            mz = centroids.mz();
            intensity = centroids.intensity();
            arrays = centroids.extras();
        } else {
            mz = new double[0];
            intensity = new double[0];
            arrays = Map.of();
        }
        return new Spectrum(description, mz, intensity, peakList, arrays,
                centroids == null ? Map.of() : centroids.extras());
    }

    // ---- range queries ----------------------------------------------------------------------------

    /**
     * The spectra {@code query} selects by its time or index range and its MS levels, in index order. Works on the
     * metadata held in memory and reads no signal; the m/z and ion-mobility filters of {@code query} play no part.
     *
     * @return an unmodifiable list, empty when no spectrum matches
     * @throws NullPointerException if {@code query} is {@code null}
     */
    public List<SpectrumDescription> select(SignalQuery query) {
        Objects.requireNonNull(query, "query");
        List<SpectrumDescription> selected = new ArrayList<>();
        for (SpectrumDescription d : descriptions) {
            if (query.selects(d)) {
                selected.add(d);
            }
        }
        selected.sort(Comparator.comparingLong(SpectrumDescription::index));
        return Collections.unmodifiableList(selected);
    }

    /**
     * The data arrays (typically {@code spectra_data.parquet}: profile or other point arrays) of the spectra
     * {@link #select(SignalQuery) selected} by {@code query}, one slice per spectrum in index order, keeping the
     * points that pass the query's m/z and ion-mobility filters. The points are those of {@link Spectrum#mz()} in
     * {@link SignalMode#PROFILE} mode, so the {@linkplain Options#reconstructProfile() reconstructProfile} option
     * applies; the {@link SignalMode} of this reader does not. A spectrum without data arrays gives an empty slice.
     *
     * <p>Only the row groups that hold a selected spectrum are read. The stream is lazy and sequential: it decodes a
     * spectrum when its slice is requested, and throws {@link IllegalStateException} when that happens after
     * {@link #close()}. A slice that the filters leave whole shares its arrays with the reader's cache, like
     * {@link Spectrum#mz()}; treat them as immutable.
     *
     * @throws NullPointerException  if {@code query} is {@code null}
     * @throws IllegalStateException if this reader is closed
     */
    public Stream<SignalSlice> extractSignal(SignalQuery query) {
        return slices(query, false);
    }

    /**
     * The centroid peaks (typically {@code spectra_peaks.parquet}) of the spectra {@link #select(SignalQuery)
     * selected} by {@code query}, one slice per spectrum in index order, keeping in stored order the peaks with a
     * finite m/z that pass the query's m/z and ion-mobility filters. The peaks are those of {@link Spectrum#mz()} in
     * {@link SignalMode#CENTROID} mode; the {@link SignalMode} of this reader does not apply. A spectrum without
     * peaks gives an empty slice. Reads and streams like {@link #extractSignal(SignalQuery)}.
     *
     * @throws NullPointerException  if {@code query} is {@code null}
     * @throws IllegalStateException if this reader is closed
     */
    public Stream<SignalSlice> queryPeaks(SignalQuery query) {
        return slices(query, true);
    }

    private Stream<SignalSlice> slices(SignalQuery query, boolean fromPeaks) {
        List<SpectrumDescription> selected = select(query);
        requireOpen();
        return selected.stream().map(d -> slice(d, query, fromPeaks));
    }

    /**
     * The slice of spectrum {@code d}: its points in the peaks member when {@code fromPeaks}, else in the data
     * arrays member, filtered by {@code query}. Peaks with a non-finite m/z are always dropped.
     */
    private SignalSlice slice(SpectrumDescription d, SignalQuery query, boolean fromPeaks) {
        requireOpen();
        SpectrumArrayStore store = fromPeaks ? peaks() : data();
        SpectrumArrayStore.Arrays arrays = store == null ? null : store.get(d.index());
        if (arrays == null) {
            return new SignalSlice(d.index(), d.retentionTime(), d.msLevel(), null, null, null);
        }
        double[] mz = arrays.mz();
        double[] intensity = arrays.intensity();
        double[] im = null;
        for (Map.Entry<String, double[]> extra : arrays.extras().entrySet()) {
            if (CvTerms.ION_MOBILITY_ARRAYS.contains(extra.getKey())) {
                im = extra.getValue();
                break;
            }
        }
        DoubleRange mzRange = query.mz().orElse(null);
        // the ion-mobility filter applies only to a spectrum that has ion mobility
        DoubleRange imRange = im == null ? null : query.ionMobility().orElse(null);
        int kept = 0;
        for (int i = 0; i < mz.length; i++) {
            if (keeps(i, mz, im, mzRange, imRange, fromPeaks)) {
                kept++;
            }
        }
        if (kept == mz.length) {
            return new SignalSlice(d.index(), d.retentionTime(), d.msLevel(), mz, intensity, im);
        }
        double[] keptMz = new double[kept];
        double[] keptIntensity = new double[kept];
        double[] keptIm = im == null ? null : new double[kept];
        int k = 0;
        for (int i = 0; i < mz.length; i++) {
            if (keeps(i, mz, im, mzRange, imRange, fromPeaks)) {
                keptMz[k] = mz[i];
                keptIntensity[k] = intensity[i];
                if (keptIm != null) {
                    keptIm[k] = im[i];
                }
                k++;
            }
        }
        return new SignalSlice(d.index(), d.retentionTime(), d.msLevel(), keptMz, keptIntensity, keptIm);
    }

    /** Whether point {@code i} passes the filters; {@code finiteMz} also drops a non-finite m/z. */
    private static boolean keeps(int i, double[] mz, double[] im, DoubleRange mzRange, DoubleRange imRange,
                                 boolean finiteMz) {
        return (!finiteMz || Double.isFinite(mz[i]))
                && (mzRange == null || mzRange.contains(mz[i]))
                && (imRange == null || imRange.contains(im[i]));
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("the reader is closed");
        }
    }

    // ---- column statistics ------------------------------------------------------------------------

    /**
     * The Parquet row-group statistics of the metadata column that holds the term {@code accession} for the role
     * {@code (entity, kind)}, such as ({@code SPECTRUM}, {@code SCANS}, {@code "MS:1000016"}) for the scan start
     * times. Reads only the footer of the member that holds the column, no row.
     *
     * <p>The column is looked up in the facet's own member, else, in the packed layout, in the entity's metadata
     * member; by the member's column mapping and the known column names, and in the packed layout also by its CURIE
     * prefix ({@code MS_1000016_}) when exactly one field has it. Only columns directly in the facet's table or
     * packed struct are found, not ones in a nested struct or list.
     *
     * <p>The statistics are of the values as stored, in the column's own unit: they are not converted as the reader
     * converts what it decodes, such as scan start times to minutes.
     *
     * @return empty for the data arrays and peaks roles, for a role that the dataset has no metadata member for,
     *         when no column holds the term, and when the column is not of the Parquet type INT32, INT64, FLOAT or
     *         DOUBLE
     * @throws NullPointerException  if an argument is {@code null}
     * @throws IllegalStateException if this reader is closed
     * @throws MzPeakException       if the member has rows but neither metadata layout
     */
    public Optional<ColumnStatistics> columnStatistics(EntityType entity, DataKind kind, String accession) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(accession, "accession");
        requireOpen();
        return MetadataColumns.statistics(manifest, source, entity, kind, accession);
    }

    /**
     * The m/z range the spectra cover: from the smallest lowest observed m/z (MS:1000528) to the largest highest
     * observed m/z (MS:1000527) of the spectrum metadata, taken from the Parquet row-group statistics of the two
     * columns ({@link #columnStatistics}).
     *
     * @return empty unless both columns are found and their statistics are {@linkplain ColumnStatistics#complete()
     *         complete} with a minimum and a maximum
     * @throws IllegalStateException if this reader is closed
     * @throws MzPeakException       if the smallest lowest observed m/z exceeds the largest highest observed m/z
     */
    public Optional<DoubleRange> observedMzRange() {
        Optional<ColumnStatistics> lowest =
                columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, LOWEST_OBSERVED_MZ);
        Optional<ColumnStatistics> highest =
                columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, HIGHEST_OBSERVED_MZ);
        if (lowest.isEmpty() || highest.isEmpty() || !lowest.get().complete() || !highest.get().complete()
                || lowest.get().min().isEmpty() || highest.get().max().isEmpty()) {
            return Optional.empty();
        }
        double low = lowest.get().min().getAsDouble();
        double high = highest.get().max().getAsDouble();
        if (low > high) {
            throw new MzPeakException(lowest.get().member() + ": the smallest lowest observed m/z " + low
                    + " exceeds the largest highest observed m/z " + high);
        }
        return Optional.of(new DoubleRange(low, high));
    }

    /**
     * The retention-time range the spectra cover: from the smallest to the largest finite retention time of the
     * spectrum metadata held in memory, in the file's time unit (typically minutes). Reads nothing.
     *
     * @return empty when no spectrum has a finite retention time
     */
    public Optional<DoubleRange> observedTimeRange() {
        if (sortedTimes.length == 0) {
            return Optional.empty();
        }
        return Optional.of(new DoubleRange(sortedTimes[0], sortedTimes[sortedTimes.length - 1]));
    }

    // ---- chromatograms ----------------------------------------------------------------------------

    /**
     * All chromatograms (TIC, BPC, ...) ordered by index. Their times are as stored, in the
     * {@link org.mzpeak.model.Chromatogram#timeUnit() timeUnit} the file declares for them; convert them with
     * {@link org.mzpeak.model.Chromatogram#withTimeUnit}.
     *
     * @throws IllegalStateException if this reader is closed, as do {@link #getChromatogram(long)} and
     *                               {@link #getChromatogramById(String)}
     */
    public List<org.mzpeak.model.Chromatogram> chromatograms() {
        return chromatograms0() == null ? List.of() : chromatograms0().all();
    }

    public Optional<org.mzpeak.model.Chromatogram> getChromatogram(long index) {
        return chromatograms0() == null ? Optional.empty() : chromatograms0().byIndex(index);
    }

    public Optional<org.mzpeak.model.Chromatogram> getChromatogramById(String id) {
        return chromatograms0() == null ? Optional.empty() : chromatograms0().byId(id);
    }

    /**
     * The total ion current chromatogram: the first of {@link #chromatograms()} of the type MS:1000235 (total ion
     * current chromatogram), else the one derived from the spectrum metadata, {@link #encodedTic()}.
     *
     * @return empty when the dataset stores no such chromatogram and its spectrum metadata has no total ion current
     *         column
     * @throws IllegalStateException if this reader is closed
     */
    public Optional<org.mzpeak.model.Chromatogram> tic() {
        return storedOrEncoded(TIC_CHROMATOGRAM, this::encodedTic);
    }

    /**
     * The base peak chromatogram: the first of {@link #chromatograms()} of the type MS:1000628 (basepeak
     * chromatogram), else the one derived from the spectrum metadata, {@link #encodedBpc()}.
     *
     * @return empty when the dataset stores no such chromatogram and its spectrum metadata has no base peak
     *         intensity column
     * @throws IllegalStateException if this reader is closed
     */
    public Optional<org.mzpeak.model.Chromatogram> bpc() {
        return storedOrEncoded(BPC_CHROMATOGRAM, this::encodedBpc);
    }

    /**
     * The total ion current chromatogram derived from the spectrum metadata: one point per spectrum, in index order,
     * that has a total ion current (MS:1000285) and a finite retention time, which give the point's intensity and
     * time. It has the index 0, the id {@code "TIC"} and the type MS:1000235 (total ion current chromatogram), the
     * time unit minute (UO:0000031), no intensity unit and no further arrays. It is not one of
     * {@link #chromatograms()}, and it may differ from a TIC the dataset stores, which {@link #tic()} prefers. Reads
     * the index and total ion current columns of the spectrum metadata member.
     *
     * @return empty when the spectrum metadata has no numeric total ion current column
     * @throws IllegalStateException if this reader is closed
     */
    public Optional<org.mzpeak.model.Chromatogram> encodedTic() {
        return encoded(TOTAL_ION_CURRENT, 0, "TIC", TIC_CHROMATOGRAM);
    }

    /**
     * The base peak chromatogram derived from the spectrum metadata: one point per spectrum, in index order, that
     * has a base peak intensity (MS:1000505) and a finite retention time, which give the point's intensity and time.
     * It has the index 1, the id {@code "BPC"} and the type MS:1000628 (basepeak chromatogram), the time unit minute
     * (UO:0000031), no intensity unit and no further arrays. It is not one of {@link #chromatograms()}, and it may
     * differ from a BPC the dataset stores, which {@link #bpc()} prefers. Reads the index and base peak intensity
     * columns of the spectrum metadata member.
     *
     * @return empty when the spectrum metadata has no numeric base peak intensity column
     * @throws IllegalStateException if this reader is closed
     */
    public Optional<org.mzpeak.model.Chromatogram> encodedBpc() {
        return encoded(BASE_PEAK_INTENSITY, 1, "BPC", BPC_CHROMATOGRAM);
    }

    private Optional<org.mzpeak.model.Chromatogram> storedOrEncoded(
            String type, Supplier<Optional<org.mzpeak.model.Chromatogram>> encoded) {
        requireOpen();
        for (org.mzpeak.model.Chromatogram c : chromatograms()) {
            if (type.equals(c.typeCurie())) {
                return Optional.of(c);
            }
        }
        return encoded.get();
    }

    /**
     * The chromatogram {@code (index, id, type)} that pairs the retention time of each spectrum with the value of
     * the term {@code accession} in its metadata row.
     */
    private Optional<org.mzpeak.model.Chromatogram> encoded(String accession, long index, String id, String type) {
        requireOpen();
        Optional<Map<Long, Double>> values =
                MetadataColumns.valuesByIndex(manifest, source, EntityType.SPECTRUM, accession);
        if (values.isEmpty()) {
            return Optional.empty();
        }
        DoubleArrayBuilder time = new DoubleArrayBuilder();
        DoubleArrayBuilder intensity = new DoubleArrayBuilder();
        for (SpectrumDescription d : descriptions) {  // sorted by index
            Double value = values.get().get(d.index());
            if (value != null && Double.isFinite(d.retentionTime())) {
                time.add(d.retentionTime());
                intensity.add(value);
            }
        }
        return Optional.of(new org.mzpeak.model.Chromatogram(index, id, type, time.toArray(), intensity.toArray()));
    }

    // ---- wavelength (UV/DAD) spectra --------------------------------------------------------------

    /**
     * All wavelength (UV/DAD) spectra ordered by index; empty if the dataset has none.
     *
     * @throws IllegalStateException if this reader is closed, as does {@link #getWavelengthSpectrum(long)}
     */
    public List<org.mzpeak.model.WavelengthSpectrum> wavelengthSpectra() {
        return wavelength0() == null ? List.of() : wavelength0().all();
    }

    public Optional<org.mzpeak.model.WavelengthSpectrum> getWavelengthSpectrum(long index) {
        return wavelength0() == null ? Optional.empty() : wavelength0().byIndex(index);
    }

    // ---- file/run metadata ------------------------------------------------------------------------

    /**
     * File/run-level metadata (instrument, software, run, file description, ...) from the manifest's
     * {@code metadata}; a document the manifest lacks is read from the spectrum metadata footer. Read on first use and
     * cached.
     *
     * @throws IllegalStateException if this reader is closed and the metadata is not cached yet and needs the footer
     */
    public synchronized org.mzpeak.model.meta.FileMetadata fileMetadata() {
        if (!fileMetadataLoaded) {
            fileMetadata = FooterMetadataReader.resolve(manifest.metadataJson(), this::footerKeyValue);
            fileMetadataLoaded = true;
        }
        return fileMetadata;
    }

    /** The key-value metadata of the spectrum metadata member's Parquet footer, read on first use. */
    private synchronized Map<String, String> footerKeyValue() {
        requireOpen();
        if (footerKeyValue == null) {
            footerKeyValue = ParquetGroups.footerKeyValue(source.inputFile(metadataFileName));
        }
        return footerKeyValue;
    }

    // ---- lazy stores ------------------------------------------------------------------------------

    private synchronized SpectrumArrayStore data() {
        if (!dataLoaded) {
            dataStore = dataFileName == null ? null : SpectrumArrayStore.load(source.inputFile(dataFileName),
                    dataFileName, options.reconstructProfile(), deltaModels);
            dataLoaded = true;
        }
        return dataStore;
    }

    private synchronized SpectrumArrayStore peaks() {
        if (!peaksLoaded) {
            peakStore = peaksFileName == null ? null : SpectrumArrayStore.load(source.inputFile(peaksFileName),
                    peaksFileName, options.reconstructProfile(), deltaModels);
            peaksLoaded = true;
        }
        return peakStore;
    }

    private synchronized ChromatogramStore chromatograms0() {
        requireOpen();
        if (!chromatogramsLoaded) {
            chromatogramStore = ChromatogramStore.load(source, manifest);
            chromatogramsLoaded = true;
        }
        return chromatogramStore;
    }

    private synchronized WavelengthSpectrumStore wavelength0() {
        requireOpen();
        if (!wavelengthLoaded) {
            wavelengthStore = WavelengthSpectrumStore.load(source, manifest);
            wavelengthLoaded = true;
        }
        return wavelengthStore;
    }

    /** The spectra in metadata order; {@code next()} throws {@link IllegalStateException} after {@link #close()}. */
    @Override
    public Iterator<Spectrum> iterator() {
        return new Iterator<>() {
            private int pos = 0;

            @Override
            public boolean hasNext() {
                return pos < descriptions.size();
            }

            @Override
            public Spectrum next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return getSpectrumAt(pos++);
            }
        };
    }

    /**
     * Release the signal readers and close the source. Only the first call has an effect. Each of the two spectrum
     * signal readers and the source is closed even when closing another fails, also with an {@link Error}; the first
     * failure is thrown, with any later ones that are not that same exception added as suppressed.
     *
     * <p>After {@code close()}, the methods that read the dataset throw {@link IllegalStateException}: spectrum
     * retrieval (also through the iterator), chromatograms, wavelength spectra, range and column-statistics queries,
     * and file metadata or scan settings that are not cached yet and need the footer. What the reader holds in memory
     * stays available: the manifest, the spectrum descriptions, {@link #select(SignalQuery)},
     * {@link #observedTimeRange()}, and file metadata and scan settings read before.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Throwable failure = null;
        try {
            if (dataStore != null) {
                dataStore.close();
            }
        } catch (RuntimeException | Error e) {
            failure = e;
        } finally {
            dataStore = null;
        }
        try {
            if (peakStore != null) {
                peakStore.close();
            }
        } catch (RuntimeException | Error e) {
            failure = addFailure(failure, e);
        } finally {
            peakStore = null;
            chromatogramStore = null;
            wavelengthStore = null;
        }
        try {
            source.close();
        } catch (RuntimeException | Error e) {
            failure = addFailure(failure, e);
        }
        if (failure instanceof RuntimeException e) {
            throw e;
        }
        if (failure instanceof Error e) {
            throw e;
        }
    }

    /**
     * {@code e} if there is no {@code failure} yet, else {@code failure} with {@code e} added as suppressed unless it is
     * the same instance.
     */
    private static Throwable addFailure(Throwable failure, Throwable e) {
        if (failure == null) {
            return e;
        }
        if (failure != e) {
            failure.addSuppressed(e);
        }
        return failure;
    }
}
