package org.mzpeak.model;

import java.util.List;

/**
 * A single acquisition (scan) event within a spectrum.
 *
 * @param startTime     scan start time (MS:1000016), in the file's time unit (typically minutes)
 * @param injectionTime ion injection time (MS:1000927); may be {@code null}
 * @param filterString  vendor filter string (MS:1000512); may be {@code null}
 * @param scanWindows   acquisition windows; never {@code null}, may be empty
 */
public record ScanEvent(double startTime,
                        Double injectionTime,
                        String filterString,
                        List<ScanWindow> scanWindows) {

    public ScanEvent {
        scanWindows = scanWindows == null ? List.of() : List.copyOf(scanWindows);
    }
}
