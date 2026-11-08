package org.mzpeak.model;

import java.util.List;
import java.util.OptionalDouble;

/**
 * A single acquisition (scan) event within a spectrum.
 *
 * @param startTime                  scan start time (MS:1000016) in minutes, converted from its column's unit
 * @param injectionTime              ion injection time (MS:1000927); may be {@code null}
 * @param filterString               vendor filter string (MS:1000512); may be {@code null}
 * @param presetScanConfiguration    preset scan configuration index (MS:1000616); may be {@code null}
 * @param instrumentConfigurationRef index of the instrument configuration used for this scan; may be {@code null}
 * @param spectrumReference          reference to another spectrum (used in some MS imaging workflows); may be {@code null}
 * @param scanWindows                acquisition m/z windows; never {@code null}, may be empty
 * @param parameters                 additional CV/user params from the scan facet; never {@code null}, may be empty
 */
public record ScanEvent(double startTime,
                        Double injectionTime,
                        String filterString,
                        Integer presetScanConfiguration,
                        Integer instrumentConfigurationRef,
                        String spectrumReference,
                        List<ScanWindow> scanWindows,
                        List<Param> parameters) {

    public ScanEvent {
        scanWindows = scanWindows == null ? List.of() : List.copyOf(scanWindows);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    // ---- imaging position ------------------------------------------------------------------

    /**
     * Pixel coordinate (x, y) for mass-spectrometry imaging scans, derived from CV params
     * {@code IMS:1000050} (position x) and {@code IMS:1000051} (position y); returns {@code null}
     * if either coordinate is absent.
     *
     * <p>Current mzPeak imaging files store pixel coordinates directly in the scan {@code parameters}
     * list. Newer files may also promote them to dedicated Parquet columns; the reader normalises
     * both forms into the {@code parameters} list, so this method covers both cases.
     */
    public ImagingPosition imagingPosition() {
        Integer x = null, y = null;
        for (Param p : parameters) {
            if ("IMS:1000050".equals(p.accession()) && p.value() instanceof Number n) {
                x = n.intValue();
            } else if ("IMS:1000051".equals(p.accession()) && p.value() instanceof Number n) {
                y = n.intValue();
            }
            if (x != null && y != null) break;
        }
        return (x != null && y != null) ? new ImagingPosition(x, y) : null;
    }

    // ---- ion mobility ----------------------------------------------------------------------

    /**
     * Scalar ion mobility value from the scan parameters (first param whose accession is one of
     * {@link CvTerms#ION_MOBILITY_TYPES}, taken in that table's order, and whose value is a finite
     * number), or {@link OptionalDouble#empty()} if absent or NaN.
     *
     * <p>The ion mobility type (which physical quantity this is: drift time, 1/K0, FAIMS CV, …)
     * is given by {@link #ionMobilityType()}.
     */
    public OptionalDouble ionMobilityValue() {
        for (String acc : CvTerms.ION_MOBILITY_TYPES) {
            for (Param p : parameters) {
                if (acc.equals(p.accession()) && p.value() instanceof Number n) {
                    double v = n.doubleValue();
                    if (Double.isFinite(v)) {
                        return OptionalDouble.of(v);
                    }
                }
            }
        }
        return OptionalDouble.empty();
    }

    /**
     * CV accession of the ion mobility type (the first of {@link CvTerms#ION_MOBILITY_TYPES} that a
     * param carries), or {@code null} if no ion mobility param is present.
     */
    public String ionMobilityType() {
        for (String acc : CvTerms.ION_MOBILITY_TYPES) {
            for (Param p : parameters) {
                if (acc.equals(p.accession())) {
                    return acc;
                }
            }
        }
        return null;
    }
}
