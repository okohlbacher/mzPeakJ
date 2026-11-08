package org.mzpeak.io;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a write did that is legal but loses or changes something the caller passed. {@link WritePlanner} and the
 * encoders record it while the dataset is planned and written; {@link MzPeakWriter} logs each warning as WARNING
 * once the dataset is written. The planner records one warning per entity whose main axis it re-sorts and one for the
 * extra signal arrays of spectra it drops, and {@link ManifestWriter} records a CV prefix that {@link KnownCvs} does
 * not know.
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
