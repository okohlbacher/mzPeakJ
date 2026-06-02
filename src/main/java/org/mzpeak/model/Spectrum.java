package org.mzpeak.model;

import java.util.List;

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
        this.peaks = peaks == null ? List.of() : List.copyOf(peaks);
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

    @Override
    public String toString() {
        return "Spectrum{index=" + description.index()
                + ", id=" + description.id()
                + ", msLevel=" + description.msLevel()
                + ", points=" + mz.length
                + ", peaks=" + peaks.size() + '}';
    }
}
