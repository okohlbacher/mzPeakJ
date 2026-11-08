package org.mzpeak.model;

/**
 * A single m/z–intensity centroid. Mirrors {@code mzpeaks::CentroidPeak} (intensity widened to
 * {@code double} for easy interop with downstream MS toolkits).
 *
 * @param mz        mass-to-charge
 * @param intensity signal magnitude
 * @param index     position within the owning spectrum's peak list (0-based), or -1 if unset
 */
public record CentroidPeak(double mz, double intensity, int index)
        implements CoordinateLike, IntensityMeasurement, Indexed {

    @Override
    public double coordinate() {
        return mz;
    }
}
