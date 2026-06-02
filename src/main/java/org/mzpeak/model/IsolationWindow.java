package org.mzpeak.model;

/**
 * Precursor isolation window.
 *
 * @param targetMz    isolation window target m/z (MS:1000827); may be {@code null}
 * @param lowerOffset lower offset (MS:1000828); may be {@code null}
 * @param upperOffset upper offset (MS:1000829); may be {@code null}
 */
public record IsolationWindow(Double targetMz, Double lowerOffset, Double upperOffset) {

    public Double lowerBound() {
        return (targetMz == null || lowerOffset == null) ? null : targetMz - lowerOffset;
    }

    public Double upperBound() {
        return (targetMz == null || upperOffset == null) ? null : targetMz + upperOffset;
    }
}
