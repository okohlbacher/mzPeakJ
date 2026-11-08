package org.mzpeak.io;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * The Parquet row-group statistics of one numeric metadata column, as {@link MzPeakReader#columnStatistics} reads
 * them from a member's footer: the smallest and largest value of each row group, without reading any row.
 *
 * @param member    the dataset member that holds the column, such as {@code spectra_metadata.parquet}
 * @param column    the column's dotted path in the member: the column name in the split layout (such as
 *                  {@code lowest_observed_mz}), the packed struct and the field in the packed layout (such as
 *                  {@code spectrum.MS_1000528_lowest_observed_mz_unit_MS_1000040})
 * @param accession the CURIE the column was looked up by
 * @param rowGroups the statistics of each row group of the member, in file order; empty when the member has none
 */
public record ColumnStatistics(String member, String column, String accession, List<RowGroup> rowGroups) {

    /**
     * @throws NullPointerException if an argument or an element of {@code rowGroups} is {@code null}
     */
    public ColumnStatistics {
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(accession, "accession");
        rowGroups = List.copyOf(rowGroups);
    }

    /**
     * The statistics of the column in one row group. Integer values are converted to double, which rounds those
     * beyond ±2<sup>53</sup>.
     *
     * @param rowCount  the number of rows in the row group
     * @param nullCount the number of null cells of the column in the row group, or {@code null} when the footer does
     *                  not record it
     * @param min       the smallest non-null value of the column in the row group, or {@code null} when the footer
     *                  does not record it (also when the row group holds only nulls, or a NaN)
     * @param max       the largest non-null value, or {@code null} under the same conditions as {@code min}
     */
    public record RowGroup(long rowCount, Long nullCount, Double min, Double max) {

        /**
         * @throws IllegalArgumentException when {@code rowCount} or {@code nullCount} is negative, when only one of
         *                                  {@code min} and {@code max} is given, when either is NaN, or when
         *                                  {@code min > max}
         */
        public RowGroup {
            if (rowCount < 0 || nullCount != null && nullCount < 0) {
                throw new IllegalArgumentException("negative count: rowCount " + rowCount + ", nullCount "
                        + nullCount);
            }
            if ((min == null) != (max == null)) {
                throw new IllegalArgumentException("min and max must both be given or both be null: [" + min + ", "
                        + max + "]");
            }
            if (min != null && (min.isNaN() || max.isNaN() || min > max)) {
                throw new IllegalArgumentException("invalid bounds: [" + min + ", " + max + "]");
            }
        }
    }

    /**
     * {@code true} when {@link #min()} and {@link #max()} bound every non-null value of the column: each row group
     * records its minimum and maximum, or has no rows, or records that it holds only nulls. Also {@code true} when
     * there is no row group.
     */
    public boolean complete() {
        for (RowGroup g : rowGroups) {
            boolean bounded = g.min() != null || g.rowCount() == 0
                    || g.nullCount() != null && g.nullCount() == g.rowCount();
            if (!bounded) {
                return false;
            }
        }
        return true;
    }

    /**
     * The smallest row-group minimum; empty when no row group records one. It is the smallest value of the column
     * only when the statistics are {@linkplain #complete() complete}.
     */
    public OptionalDouble min() {
        OptionalDouble min = OptionalDouble.empty();
        for (RowGroup g : rowGroups) {
            if (g.min() != null && (min.isEmpty() || g.min() < min.getAsDouble())) {
                min = OptionalDouble.of(g.min());
            }
        }
        return min;
    }

    /**
     * The largest row-group maximum; empty when no row group records one. It is the largest value of the column only
     * when the statistics are {@linkplain #complete() complete}.
     */
    public OptionalDouble max() {
        OptionalDouble max = OptionalDouble.empty();
        for (RowGroup g : rowGroups) {
            if (g.max() != null && (max.isEmpty() || g.max() > max.getAsDouble())) {
                max = OptionalDouble.of(g.max());
            }
        }
        return max;
    }
}
