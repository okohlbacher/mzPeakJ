package org.mzpeak.model;

/**
 * A charge-deconvoluted peak located on the neutral-mass axis. Mirrors {@code mzpeaks::DeconvolutedPeak}.
 *
 * @param neutralMass neutral (uncharged) mass
 * @param intensity   signal magnitude
 * @param charge      charge state
 * @param index       position within the owning collection (0-based), or -1 if unset
 */
public record DeconvolutedPeak(double neutralMass, double intensity, int charge, int index)
        implements CoordinateLike, IntensityMeasurement, Indexed, KnownCharge {

    @Override
    public double coordinate() {
        return neutralMass;
    }
}
