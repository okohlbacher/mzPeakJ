package org.mzpeak.model;

import java.util.List;

/**
 * A selected (precursor) ion.
 *
 * @param mz         selected ion m/z (MS:1000744)
 * @param charge     charge state (MS:1000041); may be {@code null}
 * @param intensity  selected ion intensity (MS:1000042); may be {@code null}
 * @param parameters additional CV/user params; never {@code null}, may be empty
 */
public record SelectedIon(double mz, Integer charge, Double intensity, List<Param> parameters) {

    public SelectedIon {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /** Backwards-compatible constructor without parameters (defaults to empty list). */
    public SelectedIon(double mz, Integer charge, Double intensity) {
        this(mz, charge, intensity, List.of());
    }
}
