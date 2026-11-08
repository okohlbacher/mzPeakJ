package org.mzpeak.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A spectrum: its {@link SpectrumDescription} plus the signal payload.
 *
 * <p>Profile points and centroid peaks are kept <em>decoupled</em>: an mzPeak spectrum may carry profile
 * arrays (from {@code spectra_data.parquet}), centroid peaks (from {@code spectra_peaks.parquet}), or both.
 * {@link #mz()}/{@link #intensity()} hold the profile/raw point arrays; {@link #peaks()} holds centroids.
 * Arrays are parallel ({@code mz.length == intensity.length}) and are considered immutable after construction.
 *
 * <p>Additional signal arrays, such as the per-peak ion mobility of a diaPASEF spectrum, are in {@link #arrays()},
 * parallel to {@link #mz()}, and in {@link #peakArrays()}, parallel to the stored peak list. They are keyed by the
 * array type CURIE (for example {@code MS:1003006}), by the array name for a non-standard data array (MS:1000786,
 * for example {@code ms level}), and by {@code <key>@<column>} for every further column of a key. A drift-time ion
 * mobility array (MS:1002477, MS:1002816, MS:1003007, MS:1003153, MS:1003154 or MS:1003156, also under a
 * {@code <key>@<column>} key) is in milliseconds, its unit in {@link CvTerms#DEFAULT_ARRAY_UNITS}: the reader converts
 * one stored in seconds or minutes. The reader keeps every other array as stored.
 */
public final class Spectrum {

    private final SpectrumDescription description;
    private final double[] mz;
    private final double[] intensity;
    private final List<CentroidPeak> peaks;
    private final Map<String, double[]> arrays;
    private final Map<String, double[]> peakArrays;
    /** The key of {@link #ionMobility()}, or null. */
    private final String ionMobilityKey;

    public Spectrum(SpectrumDescription description, double[] mz, double[] intensity, List<CentroidPeak> peaks) {
        this(description, mz, intensity, peaks, Map.of(), Map.of());
    }

    /**
     * A spectrum with additional signal arrays.
     *
     * @param arrays     additional arrays parallel to {@code mz}; null is the same as an empty map
     * @param peakArrays additional arrays parallel to {@code peaks} in the order given here (the position a reader
     *                   stores as {@link CentroidPeak#index()}); null is the same as an empty map
     * @throws IllegalArgumentException when {@code mz} and {@code intensity} differ in length, when a map has a
     *                                  null key or value, or when an array of {@code arrays} differs in length from
     *                                  {@code mz}, or one of {@code peakArrays} from {@code peaks}
     */
    public Spectrum(SpectrumDescription description, double[] mz, double[] intensity, List<CentroidPeak> peaks,
                    Map<String, double[]> arrays, Map<String, double[]> peakArrays) {
        this.description = description;
        this.mz = mz == null ? EMPTY : mz;
        this.intensity = intensity == null ? EMPTY : intensity;
        if (this.mz.length != this.intensity.length) {
            throw new IllegalArgumentException(
                    "mz/intensity length mismatch: " + this.mz.length + " vs " + this.intensity.length);
        }
        this.arrays = copy(arrays, "arrays", this.mz.length, "mz");
        this.peakArrays = copy(peakArrays, "peakArrays", peaks == null ? 0 : peaks.size(), "the peak list");
        String imKey = null;
        for (String key : this.arrays.keySet()) {
            if (CvTerms.ION_MOBILITY_ARRAYS.contains(key)) {
                imKey = key;
                break;
            }
        }
        this.ionMobilityKey = imKey;
        // Sort peaks by m/z; filter non-finite m/z entries (e.g. SRM spectra where
        // the converter writes NaN for transitions without a valid product-ion m/z).
        if (peaks == null || peaks.isEmpty()) {
            this.peaks = List.of();
        } else {
            List<CentroidPeak> sorted = new ArrayList<>(peaks.size());
            for (CentroidPeak p : peaks) {
                if (Double.isFinite(p.mz())) sorted.add(p);
            }
            sorted.sort(java.util.Comparator.comparingDouble(CentroidPeak::mz));
            this.peaks = Collections.unmodifiableList(sorted);
        }
    }

    private static final double[] EMPTY = new double[0];

    /**
     * An unmodifiable insertion-ordered copy of {@code map}, whose arrays must all have {@code length} values (the
     * length of {@code against}); the arrays themselves are not copied.
     */
    private static Map<String, double[]> copy(Map<String, double[]> map, String what, int length,
                                              String against) {
        if (map == null || map.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, double[]> out = new LinkedHashMap<>(map);
        for (Map.Entry<String, double[]> e : out.entrySet()) {
            if (e.getKey() == null) {
                throw new IllegalArgumentException(what + " has a null key");
            }
            if (e.getValue() == null) {
                throw new IllegalArgumentException(what + " has a null array for " + e.getKey());
            }
            if (e.getValue().length != length) {
                throw new IllegalArgumentException(what + ": " + e.getKey() + " has " + e.getValue().length
                        + " values but " + against + " has " + length);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    public SpectrumDescription description() {
        return description;
    }

    /** Raw/profile m/z array (empty if this spectrum has no point-array payload). */
    public double[] mz() {
        return mz;
    }

    /** Raw/profile intensity array, parallel to {@link #mz()}. */
    public double[] intensity() {
        return intensity;
    }

    /** Centroid peaks (empty if none). */
    public List<CentroidPeak> peaks() {
        return peaks;
    }

    /**
     * Additional signal arrays parallel to {@link #mz()}, keyed as described in the class comment, in the order of
     * the member's array index (empty if none). When {@link #mz()} holds the peaks because the spectrum has no
     * point arrays, these are the peak arrays. The map is unmodifiable; the arrays are shared, not copied, and are
     * considered immutable like {@link #mz()}.
     */
    public Map<String, double[]> arrays() {
        return arrays;
    }

    /**
     * Additional signal arrays parallel to the stored peak list (empty if none): the value of peak {@code p} is at
     * {@link CentroidPeak#index() p.index()}, since {@link #peaks()} is sorted by m/z and holds only the peaks with a
     * finite m/z. Unmodifiable and shared like {@link #arrays()}.
     */
    public Map<String, double[]> peakArrays() {
        return peakArrays;
    }

    /** The array of {@link #arrays()} with {@code key}, if present. */
    public Optional<double[]> array(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(arrays.get(key));
    }

    /**
     * The ion mobility array parallel to {@link #mz()}: the first array of {@link #arrays()} whose key is an ion
     * mobility array type ({@link CvTerms#ION_MOBILITY_ARRAYS}), if any.
     */
    public Optional<double[]> ionMobility() {
        return ionMobilityKey == null ? Optional.empty() : Optional.of(arrays.get(ionMobilityKey));
    }

    /** The array type of {@link #ionMobility()}, such as {@code MS:1003006}, if present. */
    public Optional<String> ionMobilityArrayType() {
        return Optional.ofNullable(ionMobilityKey);
    }

    public long index() {
        return description.index();
    }

    public int msLevel() {
        return description.msLevel();
    }

    /** Number of raw/profile points. */
    public int pointCount() {
        return mz.length;
    }

    public boolean hasPeaks() {
        return !peaks.isEmpty();
    }

    // ---- peak search (centroid spectra) -------------------------------------------------------

    /**
     * Find the centroid peak closest to {@code targetMz} within {@code tolerance}. Uses binary search on the
     * m/z-sorted {@link #peaks()} list; returns empty for profile-only spectra or when no peak falls within
     * tolerance.
     */
    public Optional<CentroidPeak> findPeak(double targetMz, Tolerance tolerance) {
        if (peaks.isEmpty()) {
            return Optional.empty();
        }
        double[] bounds = tolerance.bounds(targetMz);
        int lo = lowerBound(bounds[0]);
        int hi = upperBound(bounds[1]);
        if (lo > hi) {
            return Optional.empty();
        }
        // pick the peak with minimum |mz - targetMz| in [lo, hi]
        CentroidPeak best = null;
        double bestDelta = Double.POSITIVE_INFINITY;
        for (int i = lo; i <= hi; i++) {
            double d = Math.abs(peaks.get(i).mz() - targetMz);
            if (d < bestDelta) {
                bestDelta = d;
                best = peaks.get(i);
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * All centroid peaks whose m/z is within {@code tolerance} of {@code targetMz}, sorted by m/z.
     * Returns an empty list for profile-only spectra.
     */
    public List<CentroidPeak> findPeaks(double targetMz, Tolerance tolerance) {
        if (peaks.isEmpty()) {
            return List.of();
        }
        double[] bounds = tolerance.bounds(targetMz);
        int lo = lowerBound(bounds[0]);
        int hi = upperBound(bounds[1]);
        return lo > hi ? List.of() : peaks.subList(lo, hi + 1);
    }

    /**
     * All centroid peaks in the m/z range {@code [mzLow, mzHigh]} (inclusive), sorted by m/z.
     * Returns an empty list for profile-only spectra.
     */
    public List<CentroidPeak> findPeaksBetween(double mzLow, double mzHigh) {
        if (peaks.isEmpty() || mzLow > mzHigh) {
            return List.of();
        }
        int lo = lowerBound(mzLow);
        int hi = upperBound(mzHigh);
        return lo > hi ? List.of() : peaks.subList(lo, hi + 1);
    }

    /** Index of the first peak with mz ≥ key, or peaks.size() if none. */
    private int lowerBound(double key) {
        int lo = 0, hi = peaks.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (peaks.get(mid).mz() < key) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Index of the last peak with mz ≤ key, or -1 if none. */
    private int upperBound(double key) {
        int lo = 0, hi = peaks.size() - 1, result = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (peaks.get(mid).mz() <= key) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return "Spectrum{index=" + description.index()
                + ", id=" + description.id()
                + ", msLevel=" + description.msLevel()
                + ", points=" + mz.length
                + ", peaks=" + peaks.size() + '}';
    }
}
