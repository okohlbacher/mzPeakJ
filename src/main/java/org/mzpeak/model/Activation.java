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
     * Params that describe dissociation methods: those whose accession is in {@link CvTerms#DISSOCIATION_METHODS}
     * (the descendants of MS:1000044), in list order. Energy and other attribute params are excluded, and so are
     * MS:1000044 itself and MS:1002631 (EThcD, a combined dissociation method attribute in PSI-MS, which stays in
     * {@link #parameters()}).
     */
    public List<Param> methods() {
        return parameters.stream()
                .filter(p -> p.accession() != null && CvTerms.DISSOCIATION_METHODS.containsKey(p.accession()))
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
