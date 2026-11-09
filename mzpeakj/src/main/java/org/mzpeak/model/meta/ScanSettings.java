package org.mzpeak.model.meta;

import org.mzpeak.model.Param;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Imaging scan settings from {@code mzpeak_index.json} {@code metadata.scan_settings_list}.
 *
 * <p>Mirrors the imzML {@code <scanSettings>} concept: pixel grid dimensions, pixel size, and scan
 * direction. All measured values are surfaced as a {@link Param} list (accession + value + unit);
 * the most common ones have typed convenience accessors. Like the mzML {@code <scanSettings>}, an entry also names
 * the source files holding the acquisition settings ({@code source_file_refs}) and lists the targets of an inclusion
 * list ({@code targets}), each target a param list.
 *
 * <p>The params are stored as JSON, which has no non-finite numbers: a {@code Double} that is infinite or NaN reads
 * back as the {@code String} {@code Infinity}, {@code -Infinity} or {@code NaN}.
 *
 * <h3>Common accessions</h3>
 * <ul>
 *   <li>{@code IMS:1000042} — max count of pixel x
 *   <li>{@code IMS:1000043} — max count of pixel y
 *   <li>{@code IMS:1000044} — max dimension x (typically µm)
 *   <li>{@code IMS:1000045} — max dimension y (typically µm)
 *   <li>{@code IMS:1000046} — pixel size x (typically µm)
 *   <li>{@code IMS:1000047} — pixel size y (typically µm)
 * </ul>
 */
public record ScanSettings(String id, List<Param> parameters, List<String> sourceFileRefs,
                           List<List<Param>> targets) {

    /**
     * Every list is unmodifiable, a copy of the given list, {@code targets} with each of its param lists; a
     * {@code null} list, also a {@code null} target, becomes an empty one.
     *
     * @throws NullPointerException when {@code parameters}, {@code sourceFileRefs} or a target holds a {@code null}
     *                              element
     */
    public ScanSettings {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        sourceFileRefs = sourceFileRefs == null ? List.of() : List.copyOf(sourceFileRefs);
        List<List<Param>> copies = new ArrayList<>();
        if (targets != null) {
            for (List<Param> target : targets) {
                copies.add(target == null ? List.of() : List.copyOf(target));
            }
        }
        targets = List.copyOf(copies);
    }

    /** Scan settings without source file references or targets. */
    public ScanSettings(String id, List<Param> parameters) {
        this(id, parameters, List.of(), List.of());
    }

    /** Max pixel count in x (IMS:1000042), or empty if not recorded. */
    public Optional<Integer> maxPixelX() {
        return intParam("IMS:1000042");
    }

    /** Max pixel count in y (IMS:1000043), or empty if not recorded. */
    public Optional<Integer> maxPixelY() {
        return intParam("IMS:1000043");
    }

    /** Pixel size in x in the recorded unit (IMS:1000046), or empty if not recorded. */
    public Optional<Double> pixelSizeX() {
        return doubleParam("IMS:1000046");
    }

    /** Pixel size in y in the recorded unit (IMS:1000047), or empty if not recorded. */
    public Optional<Double> pixelSizeY() {
        return doubleParam("IMS:1000047");
    }

    private Optional<Integer> intParam(String accession) {
        for (Param p : parameters) {
            if (accession.equals(p.accession()) && p.value() != null) {
                try {
                    if (p.value() instanceof Number n) return Optional.of(n.intValue());
                    return Optional.of(Integer.parseInt(p.value().toString()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Double> doubleParam(String accession) {
        for (Param p : parameters) {
            if (accession.equals(p.accession()) && p.value() != null) {
                try {
                    if (p.value() instanceof Number n) return Optional.of(n.doubleValue());
                    return Optional.of(Double.parseDouble(p.value().toString()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return Optional.empty();
    }
}
