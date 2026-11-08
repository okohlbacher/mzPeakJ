package org.mzpeak.model;

import java.util.List;
import java.util.Optional;

/**
 * A controlled-vocabulary or user parameter: a {@link #name}, an optional {@link #accession} CURIE
 * (e.g. {@code MS:1000045}), a typed {@link #value} (Long/Double/String/Boolean/null), and an optional
 * {@link #unit} CURIE (e.g. {@code UO:0000266}).
 *
 * <p>Null {@code accession} means a user param. Shared by the spectrum-level inline param lists
 * (per-spectrum/scan/precursor/selected-ion {@code parameters} columns) and the file-level metadata
 * ({@link org.mzpeak.model.meta.FileMetadata}).
 */
public record Param(String name, String accession, Object value, String unit) {

    /** True if this param's accession equals {@code acc}. */
    public boolean hasAccession(String acc) {
        return acc != null && acc.equals(accession);
    }

    /** Numeric value as a double, or {@code def} when absent or non-numeric. */
    public double doubleValue(double def) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return def;
    }

    /** First param in {@code list} whose accession matches {@code acc}. */
    public static Optional<Param> find(List<Param> list, String acc) {
        if (list != null) {
            for (Param p : list) {
                if (p.hasAccession(acc)) {
                    return Optional.of(p);
                }
            }
        }
        return Optional.empty();
    }

    /** True if {@code list} contains a param with the given accession. */
    public static boolean contains(List<Param> list, String acc) {
        return find(list, acc).isPresent();
    }
}
