package org.mzpeak.model;

/** Scan polarity. */
public enum Polarity {
    POSITIVE,
    NEGATIVE,
    UNKNOWN;

    /** From the mzPeak {@code MS_1000465_scan_polarity} int8 code (+1 / -1), null/0 → UNKNOWN. */
    public static Polarity fromCode(Integer code) {
        if (code == null) {
            return UNKNOWN;
        }
        if (code > 0) {
            return POSITIVE;
        }
        if (code < 0) {
            return NEGATIVE;
        }
        return UNKNOWN;
    }
}
