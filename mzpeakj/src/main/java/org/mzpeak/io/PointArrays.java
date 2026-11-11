package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.mzpeak.io.SignalPlan.Column;
import org.mzpeak.io.parquet.ParquetGroups;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared grouping of a simple {@code point}-layout data file (chromatograms, wavelength spectra). */
final class PointArrays {

    private PointArrays() {
    }

    /** Parallel x + intensity arrays for one entity. */
    record XY(double[] x, double[] intensity) {
    }

    /**
     * Read the point rows of a data file and group them by the plan's index field into parallel arrays of the
     * main-axis values column and the first non-null value among the plan's value-bearing intensity columns, in index
     * order, as spectrum and chromatogram point rows are read. Null x is kept as {@code NaN}; an intensity without
     * any value as 0. A plan in chunk layout has no point rows and gives an empty map.
     *
     * @param member the name of the data member in errors
     * @throws MzPeakException when an index is beyond the supported range, as {@link ParquetGroups#optIndexKey} reads
     *                         it
     */
    static Map<Long, XY> groupByIndex(InputFile dataFile, SignalPlan plan, String member) {
        Map<Long, XY> out = new LinkedHashMap<>();
        if (plan.layout() != SignalPlan.Layout.POINT) {
            return out;
        }
        String prefix = plan.prefix();
        String indexField = plan.indexField();
        String xField = plan.axis().values() == null ? null : plan.axis().values().field();
        List<String> intensityFields = plan.intensities().stream()
                .filter(c -> c.format().valueBearing())
                .map(Column::field)
                .toList();
        Map<Long, DoubleArrayBuilder[]> acc = new LinkedHashMap<>();
        ParquetGroups.forEach(dataFile, row -> {
            Group p = ParquetGroups.optGroup(row, prefix);
            if (p == null) {
                return;
            }
            Long idx = ParquetGroups.optIndexKey(p, indexField, member);
            if (idx == null) {
                return;
            }
            Double x = xField == null ? null : ParquetGroups.optDouble(p, xField);
            Double intensity = firstValue(p, intensityFields);
            DoubleArrayBuilder[] b = acc.computeIfAbsent(idx,
                    k -> new DoubleArrayBuilder[] {new DoubleArrayBuilder(), new DoubleArrayBuilder()});
            b[0].add(x == null ? Double.NaN : x);
            b[1].add(intensity == null ? 0.0 : intensity);
        });
        acc.forEach((idx, b) -> out.put(idx, new XY(b[0].toArray(), b[1].toArray())));
        return out;
    }

    /** The first non-null value among {@code fields} in {@code point}, or null. */
    private static Double firstValue(Group point, List<String> fields) {
        for (String field : fields) {
            Double v = ParquetGroups.optDouble(point, field);
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
