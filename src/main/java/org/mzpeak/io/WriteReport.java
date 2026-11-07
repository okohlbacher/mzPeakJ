package org.mzpeak.io;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a write did that is legal but loses or changes something the caller passed. {@link WritePlanner} and the
 * encoders record it while the dataset is planned and written; {@link MzPeakWriter} logs each warning as WARNING
 * once the dataset is written. Nothing records a warning yet.
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
