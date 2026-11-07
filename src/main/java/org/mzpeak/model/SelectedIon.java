package org.mzpeak.model;

import java.util.List;
import java.util.OptionalDouble;

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

    /**
     * Ion-mobility value carried by this selected ion: the first param, in list order, whose accession
     * is one of {@link CvTerms#ION_MOBILITY_TYPES} (e.g. 1/K0 from MS:1002815 or drift time from
     * MS:1002476) and whose value is a finite number, or {@link OptionalDouble#empty()} if absent or NaN.
     */
    public OptionalDouble ionMobilityValue() {
        for (Param p : parameters) {
            if (CvTerms.ION_MOBILITY_TYPES.contains(p.accession()) && p.value() instanceof Number n) {
                double v = n.doubleValue();
                if (Double.isFinite(v)) return OptionalDouble.of(v);
            }
        }
        return OptionalDouble.empty();
    }
}
