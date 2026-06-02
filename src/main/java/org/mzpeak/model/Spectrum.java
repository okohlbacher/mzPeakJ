package org.mzpeak.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A spectrum: its {@link SpectrumDescription} plus the signal payload.
 *
 * <p>Profile points and centroid peaks are kept <em>decoupled</em>: an mzPeak spectrum may carry profile
 * arrays (from {@code spectra_data.parquet}), centroid peaks (from {@code spectra_peaks.parquet}), or both.
 * {@link #mz()}/{@link #intensity()} hold the profile/raw point arrays; {@link #peaks()} holds centroids.
 * Arrays are parallel ({@code mz.length == intensity.length}) and are considered immutable after construction.
 */
public final class Spectrum {

    private final SpectrumDescription description;
    private final double[] mz;
    private final double[] intensity;
    private final List<CentroidPeak> peaks;

    public Spectrum(SpectrumDescription description, double[] mz, double[] intensity, List<CentroidPeak> peaks) {
        this.description = description;
        this.mz = mz == null ? EMPTY : mz;
        this.intensity = intensity == null ? EMPTY : intensity;
        if (this.mz.length != this.intensity.length) {
            throw new IllegalArgumentException(
                    "mz/intensity length mismatch: " + this.mz.length + " vs " + this.intensity.length);
        }
        // Sort peaks by m/z to guarantee binary search correctness regardless of Parquet row order.
        if (peaks == null || peaks.isEmpty()) {
            this.peaks = List.of();
        } else {
            List<CentroidPeak> sorted = new ArrayList<>(peaks);
            sorted.sort(java.util.Comparator.comparingDouble(CentroidPeak::mz));
            this.peaks = Collections.unmodifiableList(sorted);
        }
    }

    private static final double[] EMPTY = new double[0];

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
