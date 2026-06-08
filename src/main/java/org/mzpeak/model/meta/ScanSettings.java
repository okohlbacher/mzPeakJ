package org.mzpeak.model.meta;

import org.mzpeak.model.Param;

import java.util.List;
import java.util.Optional;

/**
 * Imaging scan settings from {@code mzpeak_index.json} {@code metadata.scan_settings_list}.
 *
 * <p>Mirrors the imzML {@code <scanSettings>} concept: pixel grid dimensions, pixel size, and scan
 * direction. All measured values are surfaced as a {@link Param} list (accession + value + unit);
 * the most common ones have typed convenience accessors.
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
public record ScanSettings(String id, List<Param> parameters) {

    public ScanSettings {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
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
