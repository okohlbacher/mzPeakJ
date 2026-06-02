package org.mzpeak.model;

/**
 * A selected (precursor) ion.
 *
 * @param mz        selected ion m/z (MS:1000744)
 * @param charge    charge state (MS:1000041); may be {@code null}
 * @param intensity selected ion intensity (MS:1000042); may be {@code null}
 */
public record SelectedIon(double mz, Integer charge, Double intensity) {
}
