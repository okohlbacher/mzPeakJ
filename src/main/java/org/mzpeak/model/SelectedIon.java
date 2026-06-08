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
     * Ion-mobility value carried by this selected ion (e.g. 1/K0 from MS:1002476 or drift time
     * from MS:1002477), or {@link OptionalDouble#empty()} if absent or NaN.
     */
    public OptionalDouble ionMobilityValue() {
        for (Param p : parameters) {
            if (p.accession() != null && p.accession().startsWith("MS:100") && p.value() instanceof Number n) {
                // Quick filter: only look at accessions in the MS:10024xx / MS:10028xx / MS:10031xx range
                // that correspond to ion mobility child terms of MS:1002892
                String acc = p.accession();
                if (acc.equals("MS:1002476") || acc.equals("MS:1002477") || acc.equals("MS:1002478") ||
                    acc.equals("MS:1002814") || acc.equals("MS:1002816") ||
                    acc.equals("MS:1003006") || acc.equals("MS:1003007") ||
                    acc.equals("MS:1003153") || acc.equals("MS:1003155") || acc.equals("MS:1003156")) {
                    double v = n.doubleValue();
                    if (Double.isFinite(v)) return OptionalDouble.of(v);
                }
            }
        }
        return OptionalDouble.empty();
    }
}
