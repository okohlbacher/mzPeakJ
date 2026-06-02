package org.mzpeak.model;

import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Precursor activation description: the list of CV/user params from the mzPeak {@code activation.parameters}
 * column, which carries the dissociation method(s) (e.g. CID, HCD, ETD) and energy attributes.
 *
 * @param parameters all activation params; never {@code null}, may be empty
 */
public record Activation(List<Param> parameters) {

    /** Canonical empty activation (no params). */
    public static final Activation EMPTY = new Activation(List.of());

    public Activation {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /** True when there are no activation params (e.g. MS1 spectra). */
    public boolean isEmpty() {
        return parameters.isEmpty();
    }

    /**
     * Known PSI-MS dissociation method accessions. Activation parameter lists can include non-method entries
     * (e.g. collision energy), so we whitelist rather than blacklist.
     */
    private static final java.util.Set<String> KNOWN_METHODS = java.util.Set.of(
            CvTerms.CID,   // MS:1000133 CID
            CvTerms.HCD,   // MS:1000422 HCD
            CvTerms.ETD,   // MS:1000598 ETD
            CvTerms.ECD,   // MS:1000250 ECD
            CvTerms.IRMPD, // MS:1000262 IRMPD
            "MS:1000435",  // multiphoton dissociation (MPD)
            "MS:1000044",  // dissociation method (parent term)
            "MS:1002631",  // supplemental beam-type CID
            "MS:1002678",  // EThcD
            "MS:1003181"   // UVPD
    );

    /**
     * Params that describe dissociation methods (whitelisted CV method accessions, so energy/config params
     * are excluded even if other non-energy, non-method params appear).
     */
    public List<Param> methods() {
        return parameters.stream()
                .filter(p -> p.accession() != null && KNOWN_METHODS.contains(p.accession()))
                .toList();
    }

    /**
     * Collision energy in eV ({@code MS:1000045}), if present and finite.
     * The value is stored as a float in the fixture (35.0 eV for CID spectra).
     */
    public OptionalDouble collisionEnergy() {
        return Param.find(parameters, CvTerms.COLLISION_ENERGY)
                .filter(p -> p.value() instanceof Number)
                .map(p -> {
                    double d = p.doubleValue(Double.NaN);
                    return Double.isFinite(d) ? OptionalDouble.of(d) : OptionalDouble.empty();
                })
                .orElse(OptionalDouble.empty());
    }

    /** Display string: method names (+ collision energy if present), e.g. {@code "CID @ 35.0 eV"}. */
    public String summary() {
        List<Param> m = methods();
        if (m.isEmpty()) {
            return isEmpty() ? "(none)" : "custom";
        }
        String methods = m.stream()
                .map(p -> p.name() != null ? p.name() : p.accession())
                .reduce((a, b) -> a + " + " + b)
                .orElse("?");
        OptionalDouble energy = collisionEnergy();
        return energy.isPresent()
                ? String.format(Locale.US, "%s @ %.1f eV", methods, energy.getAsDouble())
                : methods;
    }
}
