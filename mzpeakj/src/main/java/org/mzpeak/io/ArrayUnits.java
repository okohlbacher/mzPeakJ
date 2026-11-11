package org.mzpeak.io;

import java.util.Set;
import java.util.function.DoubleUnaryOperator;

/**
 * Converts the time-like signal arrays (mzML design §5.7, §6.2 A-13): a drift-time ion mobility array of a spectrum
 * or chromatogram into the unit the model documents for it, {@link org.mzpeak.model.CvTerms#DEFAULT_ARRAY_UNITS},
 * milliseconds, from the unit of its array-index entry; and the chunk start column of a chromatogram time axis into
 * the unit of its time values column (D-12), the chromatogram's time unit. An entry without a unit, as in an
 * {@link ArrayIndex.Source#INFERRED INFERRED} index, is taken to be in the model's unit. Every other array keeps its
 * stored values, and column statistics are not converted.
 */
final class ArrayUnits {

    /** The drift-time ion mobility array types, whose model unit is UO:0000028 millisecond. */
    static final Set<String> DRIFT_TIME_ARRAYS =
            Set.of("MS:1002477", "MS:1002816", "MS:1003007", "MS:1003153", "MS:1003154", "MS:1003156");

    private ArrayUnits() {
    }

    /**
     * The conversion of chromatogram times stored in {@code from}, the unit of the column {@code fromColumn}, to
     * {@code to}, the unit of the column {@code toColumn}: the same unit as is; else minutes (UO:0000031), seconds
     * (UO:0000010), milliseconds (UO:0000028) and hours (UO:0000032) multiplied by the whole ratio of the two units
     * when {@code to} is the smaller unit, else divided by it (seconds to minutes: divided by 60), as
     * {@link org.mzpeak.model.Chromatogram#withTimeUnit} converts.
     *
     * @param member the member, which the error names
     * @throws MzPeakException if the units differ and {@code from} or {@code to} is any other unit; the message
     *                         names the member, the column and its unit
     */
    static DoubleUnaryOperator timeConversion(String from, String fromColumn, String to, String toColumn,
                                              String member) {
        if (from.equals(to)) {
            return DoubleUnaryOperator.identity();
        }
        long fromMs = timeUnitMilliseconds(from, member, fromColumn);
        long toMs = timeUnitMilliseconds(to, member, toColumn);
        if (fromMs > toMs) {
            double factor = fromMs / toMs;
            return v -> v * factor;
        }
        double divisor = toMs / fromMs;
        return v -> v / divisor;
    }

    /** The length of the chromatogram time unit {@code unit} in milliseconds. */
    private static long timeUnitMilliseconds(String unit, String member, String column) {
        return switch (unit) {
            case "UO:0000031" -> 60_000; // minute
            case "UO:0000010" -> 1_000; // second
            case "UO:0000028" -> 1; // millisecond
            case "UO:0000032" -> 3_600_000; // hour
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
