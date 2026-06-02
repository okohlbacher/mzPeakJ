package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
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
     * Read a {@code point}-layout file and group its rows by {@code indexField} into parallel
     * {@code xField} + {@code intensity} arrays. Null x is kept as {@code NaN}; null intensity as 0.
     */
    static Map<Long, XY> groupByIndex(InputFile dataFile, String indexField, String xField) {
        Map<Long, DoubleArrayBuilder[]> acc = new LinkedHashMap<>();
        ParquetGroups.forEach(dataFile, row -> {
            Group p = ParquetGroups.optGroup(row, "point");
            if (p == null) {
                return;
            }
            Long idx = ParquetGroups.optLong(p, indexField);
            if (idx == null) {
                return;
            }
            Double x = ParquetGroups.optDouble(p, xField);
            Double intensity = ParquetGroups.optDouble(p, "intensity");
            DoubleArrayBuilder[] b = acc.computeIfAbsent(idx,
                    k -> new DoubleArrayBuilder[] {new DoubleArrayBuilder(), new DoubleArrayBuilder()});
            b[0].add(x == null ? Double.NaN : x);
            b[1].add(intensity == null ? 0.0 : intensity);
        });
        Map<Long, XY> out = new LinkedHashMap<>();
        acc.forEach((idx, b) -> out.put(idx, new XY(b[0].toArray(), b[1].toArray())));
        return out;
    }
}
