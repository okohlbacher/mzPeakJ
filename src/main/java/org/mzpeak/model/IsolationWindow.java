package org.mzpeak.model;

import java.util.List;

/**
 * Precursor isolation window.
 *
 * @param targetMz    isolation window target m/z (MS:1000827); may be {@code null}
 * @param lowerOffset lower offset (MS:1000828); may be {@code null}
 * @param upperOffset upper offset (MS:1000829); may be {@code null}
 * @param parameters  the window's other CV and user params ({@code isolation_window.parameters}, as in mzdata
 *                    0.68.0), such as the user param 'ms level'; never {@code null}, may be empty
 */
public record IsolationWindow(Double targetMz, Double lowerOffset, Double upperOffset, List<Param> parameters) {

    public IsolationWindow {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /** A window without parameters. */
    public IsolationWindow(Double targetMz, Double lowerOffset, Double upperOffset) {
        this(targetMz, lowerOffset, upperOffset, List.of());
    }

    public Double lowerBound() {
        return (targetMz == null || lowerOffset == null) ? null : targetMz - lowerOffset;
    }

    public Double upperBound() {
        return (targetMz == null || upperOffset == null) ? null : targetMz + upperOffset;
    }
}
