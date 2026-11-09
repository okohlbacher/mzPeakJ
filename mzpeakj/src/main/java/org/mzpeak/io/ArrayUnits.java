package org.mzpeak.io;

import java.util.Set;
import java.util.function.DoubleUnaryOperator;

/**
 * Converts the time-like signal arrays into the unit the model documents for them,
 * {@link org.mzpeak.model.CvTerms#DEFAULT_ARRAY_UNITS}, from the unit of their array-index entry (mzML design §5.7,
 * §6.2 A-13): a chromatogram time axis to minutes, a drift-time ion mobility array of a spectrum or chromatogram to
 * milliseconds. An entry without a unit, as in an {@link ArrayIndex.Source#INFERRED INFERRED} index, is taken to be
 * in the model's unit. Every other array keeps its stored values, and column statistics are not converted.
 */
final class ArrayUnits {

    /** The drift-time ion mobility array types, whose model unit is UO:0000028 millisecond. */
    static final Set<String> DRIFT_TIME_ARRAYS =
            Set.of("MS:1002477", "MS:1002816", "MS:1003007", "MS:1003153", "MS:1003154", "MS:1003156");

    private ArrayUnits() {
    }

    /**
     * The conversion of chromatogram times stored in {@code unit} to minutes, as scan start times are converted:
     * minutes or no unit as is, seconds divided by 60, milliseconds by 60000, hours multiplied by 60.
     *
     * @param member the member, which the error names
     * @param column the column path, which the error names
     * @throws MzPeakException if {@code unit} is any other
     */
    static DoubleUnaryOperator toMinutes(String unit, String member, String column) {
        if (unit == null) {
            return DoubleUnaryOperator.identity();
        }
        return switch (unit) {
            case "UO:0000031" -> DoubleUnaryOperator.identity(); // minute
            case "UO:0000010" -> v -> v / 60; // second
            case "UO:0000028" -> v -> v / 60_000; // millisecond
            case "UO:0000032" -> v -> v * 60; // hour
            default -> throw new MzPeakException(member + ": chromatogram time column " + column + " has the unit "
                    + unit + "; supported are minute (UO:0000031), second (UO:0000010), millisecond (UO:0000028)"
                    + " and hour (UO:0000032)");
        };
    }

    /**
     * The conversion of a drift-time array of {@code arrayType} stored in {@code unit} to milliseconds: milliseconds
     * or no unit as is, seconds multiplied by 1000, minutes by 60000.
     *
     * @param member the member, which the error names
     * @param column the column path, which the error names
     * @throws MzPeakException if {@code unit} is any other
     */
    static DoubleUnaryOperator toMilliseconds(String unit, String arrayType, String member, String column) {
        if (unit == null) {
            return DoubleUnaryOperator.identity();
        }
        return switch (unit) {
            case "UO:0000028" -> DoubleUnaryOperator.identity(); // millisecond
            case "UO:0000010" -> v -> v * 1000; // second
            case "UO:0000031" -> v -> v * 60_000; // minute
            default -> throw new MzPeakException(member + ": drift time array column " + column + " (" + arrayType
                    + ") has the unit " + unit + "; supported are millisecond (UO:0000028), second (UO:0000010) and"
                    + " minute (UO:0000031)");
        };
    }
}
