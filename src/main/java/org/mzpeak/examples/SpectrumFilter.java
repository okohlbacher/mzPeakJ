package org.mzpeak.examples;

import org.mzpeak.model.SpectrumDescription;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Shared spectrum filter used by all example tools: by MS level ({@code --ms-level 1,2} or repeated) and/or
 * retention-time window ({@code --rt-min}, {@code --rt-max}, in the file's time unit).
 */
record SpectrumFilter(Set<Integer> msLevels, double rtMin, double rtMax) {

    static SpectrumFilter from(ExampleArgs args) {
        Set<Integer> levels = new LinkedHashSet<>();
        String ml = args.options.get("ms-level");
        if (ml != null) {
            for (String part : ml.split(",")) {
                if (!part.isBlank()) {
                    levels.add(Integer.parseInt(part.trim()));
                }
            }
        }
        return new SpectrumFilter(levels,
                args.optDouble("rt-min", Double.NEGATIVE_INFINITY),
                args.optDouble("rt-max", Double.POSITIVE_INFINITY));
    }

    boolean accept(SpectrumDescription d) {
        if (!msLevels.isEmpty() && !msLevels.contains(d.msLevel())) {
            return false;
        }
        double rt = d.retentionTime();
        return Double.isNaN(rt) || (rt >= rtMin && rt <= rtMax);
    }

    String describe() {
        return "ms-level=" + (msLevels.isEmpty() ? "any" : msLevels)
                + " rt=[" + rtMin + "," + rtMax + "]";
    }
}
