package org.mzpeak.io;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a write did that is legal but loses or changes something the caller passed. {@link WritePlanner} and the
 * encoders record it while the dataset is planned and written; {@link MzPeakWriter} logs each warning as WARNING
 * once the dataset is written. The planner records one warning per entity whose main axis it re-sorts, and, in the
 * packed metadata layout, one for the scans, precursors and selected ions that layout does not write, one for the
 * Params of wavelength spectra and one for the polarities, data processing references, Params and precursors of
 * chromatograms; in the split layout, one for the scan settings it gives an id. {@link ManifestWriter} records a CV
 * prefix that neither {@link KnownCvs} nor the caller's cv list knows.
 */
final class WriteReport {

    private final List<String> warnings = new ArrayList<>();

    /** Record one warning. */
    void warn(String message) {
        warnings.add(message);
    }

    /** The recorded warnings in the order they were recorded (unmodifiable view). */
    List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }
}
