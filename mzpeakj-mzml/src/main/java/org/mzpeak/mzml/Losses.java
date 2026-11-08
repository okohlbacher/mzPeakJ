package org.mzpeak.mzml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Counted warnings of the converter (mzML design 6, 10): every loss or addition is noted under its kind, and
 * {@link #lines()} gives one line per kind with its count, in the order the kinds were first noted.
 */
final class Losses {

    private final Map<String, Long> counts = new LinkedHashMap<>();

    /** Notes one occurrence of {@code kind}. */
    void note(String kind) {
        note(kind, 1);
    }

    /**
     * Notes {@code n} occurrences of {@code kind}. A count of 0 notes nothing, so a kind without occurrences never
     * gives a line.
     *
     * @throws IllegalArgumentException if {@code n} is negative
     */
    void note(String kind, long n) {
        Objects.requireNonNull(kind, "kind");
        if (n < 0) {
            throw new IllegalArgumentException("negative count " + n + " for " + kind);
        }
        if (n > 0) {
            counts.merge(kind, n, Long::sum);
        }
    }

    /** One line {@code <kind> (<n>)} per noted kind, in first-seen order; an unmodifiable snapshot. */
    List<String> lines() {
        List<String> lines = new ArrayList<>(counts.size());
        counts.forEach((kind, n) -> lines.add(kind + " (" + n + ")"));
        return Collections.unmodifiableList(lines);
    }
}
