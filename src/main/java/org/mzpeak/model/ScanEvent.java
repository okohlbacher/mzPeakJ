package org.mzpeak.model;

import java.util.List;
import java.util.OptionalDouble;

/**
 * A single acquisition (scan) event within a spectrum.
 *
 * @param startTime                  scan start time (MS:1000016), in the file's time unit (typically minutes)
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

    // ---- known ion-mobility CV accessions (children of MS:1002892) -------------------------
    // These are the accessions used to store scalar ion mobility measurements.
    private static final String[] ION_MOBILITY_ACCESSIONS = {
        "MS:1002476",   // mean inverse reduced ion mobility (1/K0, FAIMS)
        "MS:1002477",   // mean ion mobility drift time
        "MS:1002478",   // scan start drift time (Bruker)
        "MS:1002814",   // FAIMS compensation voltage
        "MS:1002816",   // mean ion mobility
        "MS:1003006",   // mean inverse reduced ion mobility (1/K0, timsTOF)
        "MS:1003007",   // raw inverse reduced ion mobility
        "MS:1003153",   // raw ion mobility drift time
        "MS:1003155",   // deconvoluted inverse reduced ion mobility
        "MS:1003156",   // deconvoluted ion mobility drift time
    };

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
     * Scalar ion mobility value from the scan parameters (first recognised ion-mobility CV param
     * whose value is a finite number), or {@link OptionalDouble#empty()} if absent or NaN.
     *
     * <p>The ion mobility type (which physical quantity this is: drift time, 1/K0, FAIMS CV, …)
     * is given by {@link #ionMobilityType()}.
     */
    public OptionalDouble ionMobilityValue() {
        for (String acc : ION_MOBILITY_ACCESSIONS) {
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
     * CV accession of the ion mobility type (the accession of the recognised ion-mobility param),
     * or {@code null} if no ion mobility param is present.
     */
    public String ionMobilityType() {
        for (String acc : ION_MOBILITY_ACCESSIONS) {
            for (Param p : parameters) {
                if (acc.equals(p.accession())) {
                    return acc;
                }
            }
        }
        return null;
    }
}
