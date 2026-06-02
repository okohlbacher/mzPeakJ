package org.mzpeak.model;

/** Whether a spectrum is stored as a continuous profile or as discrete centroids. */
public enum SignalContinuity {
    PROFILE,
    CENTROID,
    UNKNOWN;

    /** PSI-MS {@code MS:1000128} = profile, {@code MS:1000127} = centroid. */
    public static SignalContinuity fromCurie(String curie) {
        if (curie == null) {
            return UNKNOWN;
        }
        return switch (curie.trim()) {
            case "MS:1000128" -> PROFILE;
            case "MS:1000127" -> CENTROID;
            default -> UNKNOWN;
        };
    }
}
