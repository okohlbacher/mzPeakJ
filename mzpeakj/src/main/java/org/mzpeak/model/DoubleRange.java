package org.mzpeak.model;

/**
 * A closed interval {@code [low, high]} of doubles: both bounds belong to it. A bound may be infinite, which leaves
 * that side open-ended; a range never contains NaN.
 *
 * @param low  the smallest value in the range
 * @param high the largest value in the range
 */
public record DoubleRange(double low, double high) {

    /**
     * @throws IllegalArgumentException when a bound is NaN, or when {@code low > high}
     */
    public DoubleRange {
        if (Double.isNaN(low) || Double.isNaN(high)) {
            throw new IllegalArgumentException("range bound is NaN: [" + low + ", " + high + "]");
        }
        if (low > high) {
            throw new IllegalArgumentException("range low > high: [" + low + ", " + high + "]");
        }
    }

    /** {@code true} when {@code low <= value <= high}; never for NaN. */
    public boolean contains(double value) {
        return value >= low && value <= high;
    }
}
