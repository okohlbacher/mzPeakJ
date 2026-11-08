package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.mzpeak.io.SignalPlan.Column;
import org.mzpeak.io.parquet.ParquetGroups;

import java.util.LinkedHashMap;
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
     * main-axis values column and the plan's plain intensity column ({@link SignalPlan#plainIntensity()}). Null x is
     * kept as {@code NaN}; null intensity as 0. A plan in chunk layout has no point rows and gives an empty map.
     *
     * @throws MzPeakException when an index is negative: an unsigned value of 2^31 or more in an INT32 column, or of
     *                         2^63 or more, which would miss its header
     */
    static Map<Long, XY> groupByIndex(InputFile dataFile, SignalPlan plan) {
        Map<Long, XY> out = new LinkedHashMap<>();
        if (plan.layout() != SignalPlan.Layout.POINT) {
            return out;
        }
        String prefix = plan.prefix();
        String indexField = plan.indexField();
        String xField = plan.axis().values() == null ? null : plan.axis().values().field();
        String intensityField = plan.plainIntensity().map(Column::field).orElse(null);
        Map<Long, DoubleArrayBuilder[]> acc = new LinkedHashMap<>();
        ParquetGroups.forEach(dataFile, row -> {
            Group p = ParquetGroups.optGroup(row, prefix);
            if (p == null) {
                return;
            }
            Long idx = ParquetGroups.optLong(p, indexField);
            if (idx == null) {
                return;
            }
            if (idx < 0) {
                throw new MzPeakException(indexField + " " + idx + " is out of range (negative when read as a signed"
                        + " integer: an unsigned value of 2^31 or more in an INT32 column, or of 2^63 or more)");
            }
            Double x = xField == null ? null : ParquetGroups.optDouble(p, xField);
            Double intensity = intensityField == null ? null : ParquetGroups.optDouble(p, intensityField);
            DoubleArrayBuilder[] b = acc.computeIfAbsent(idx,
                    k -> new DoubleArrayBuilder[] {new DoubleArrayBuilder(), new DoubleArrayBuilder()});
            b[0].add(x == null ? Double.NaN : x);
            b[1].add(intensity == null ? 0.0 : intensity);
        });
        acc.forEach((idx, b) -> out.put(idx, new XY(b[0].toArray(), b[1].toArray())));
        return out;
    }
}
