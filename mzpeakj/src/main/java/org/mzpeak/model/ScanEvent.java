package org.mzpeak.model;

import java.util.ArrayList;
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

    /**
     * The order in which {@link #ionMobilityValue()} and {@link #ionMobilityType()} look for an ion mobility type:
     * mzdata's {@code ION_MOBILITY_SCAN_TERMS} (drift time MS:1002476, inverse reduced ion mobility MS:1002815, FAIMS
     * compensation voltage MS:1001581, SelexION compensation voltage MS:1003371), then the other
     * {@link CvTerms#ION_MOBILITY_TYPES} in that table's order.
     */
    private static final List<String> ION_MOBILITY_PRIORITY = ionMobilityPriority();

    public ScanEvent {
        scanWindows = scanWindows == null ? List.of() : List.copyOf(scanWindows);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    private static List<String> ionMobilityPriority() {
        List<String> order = new ArrayList<>(List.of("MS:1002476", "MS:1002815", "MS:1001581", "MS:1003371"));
        for (String type : CvTerms.ION_MOBILITY_TYPES) {
            if (!order.contains(type)) {
                order.add(type);
            }
        }
        return List.copyOf(order);
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
     * Scalar ion mobility value from the scan parameters, or {@link OptionalDouble#empty()} if absent or NaN: the
     * value of the first param whose value is a finite number and whose accession is one of
     * {@link CvTerms#ION_MOBILITY_TYPES}, the types taken in mzdata's priority order: ion mobility drift time
     * (MS:1002476), inverse reduced ion mobility (MS:1002815), FAIMS compensation voltage (MS:1001581), SelexION
     * compensation voltage (MS:1003371), then the other types in the table's order. Among params of one type, the
     * first in list order wins.
     *
     * <p>The ion mobility type (which physical quantity this is: drift time, 1/K0, FAIMS CV, …)
     * is given by {@link #ionMobilityType()}.
     */
    public OptionalDouble ionMobilityValue() {
        Param p = ionMobilityParam();
        return p == null ? OptionalDouble.empty() : OptionalDouble.of(((Number) p.value()).doubleValue());
    }

    /**
     * CV accession of the ion mobility type: the accession of the param whose value {@link #ionMobilityValue()}
     * returns; when no ion mobility param has a finite value, the first type a param carries, in the same priority
     * order. {@code null} if no ion mobility param is present.
     */
    public String ionMobilityType() {
        Param p = ionMobilityParam();
        if (p != null) {
            return p.accession();
        }
        for (String acc : ION_MOBILITY_PRIORITY) {
            for (Param q : parameters) {
                if (acc.equals(q.accession())) {
                    return acc;
                }
            }
        }
        return null;
    }

    /** The param {@link #ionMobilityValue()} reads, or null when no ion mobility param has a finite value. */
    private Param ionMobilityParam() {
        for (String acc : ION_MOBILITY_PRIORITY) {
            for (Param p : parameters) {
                if (acc.equals(p.accession()) && p.value() instanceof Number n && Double.isFinite(n.doubleValue())) {
                    return p;
                }
            }
        }
        return null;
    }
}
