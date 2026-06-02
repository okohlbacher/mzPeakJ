package org.mzpeak.model;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a scan-like number out of a PSI-MS spectrum native id string. Native id formats are vendor-specific;
 * this extracts the first matching {@code key=integer} token in priority order so lookup works beyond Thermo's
 * {@code scan=}. "Scan number" is inherently approximate across vendors (e.g. SCIEX has cycle×experiment), so
 * this is a best-effort convenience for {@code getSpectrumByScanNumber}.
 */
public final class NativeId {

    private NativeId() {
    }

    // Priority-ordered: a genuine scan/frame number first, then index-like fallbacks.
    private static final Pattern[] PATTERNS = {
            Pattern.compile("(?:^|\\s)scan=(\\d+)"),        // Thermo, Waters, generic mzML
            Pattern.compile("(?:^|\\s)scanId=(\\d+)"),      // Agilent
            Pattern.compile("(?:^|\\s)frame=(\\d+)"),       // Bruker TDF
            Pattern.compile("(?:^|\\s)cycle=(\\d+)"),       // SCIEX WIFF (cycle as the scan-like index)
            Pattern.compile("(?:^|\\s)scan_number=(\\d+)"),
            Pattern.compile("(?:^|\\s)index=(\\d+)"),        // generic index-based ids
            Pattern.compile("(?:^|\\s)spectrum=(\\d+)"),
    };

    /** Extract a scan number from {@code nativeId}, or empty if none / out of int range. */
    public static OptionalInt scanNumber(String nativeId) {
        if (nativeId == null) {
            return OptionalInt.empty();
        }
        for (Pattern p : PATTERNS) {
            Matcher m = p.matcher(nativeId);
            if (m.find()) {
                try {
                    return OptionalInt.of(Integer.parseInt(m.group(1)));
                } catch (NumberFormatException e) {
                    return OptionalInt.empty(); // does not fit in an int
                }
            }
        }
        return OptionalInt.empty();
    }
}
