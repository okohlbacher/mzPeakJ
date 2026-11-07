package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.mzpeak.io.ArrayIndex.BufferFormat;
import org.mzpeak.io.SignalPlan.Column;
import org.mzpeak.io.parquet.ParquetGroups;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes a chromatogram data member into time and intensity arrays per chromatogram index, reading only the
 * columns its {@link SignalPlan} names: the prefix struct, the index field, the time axis and the intensity columns
 * (design §8.4). The rows are read in the plan's layout:
 * <ul>
 *   <li><b>point</b>, one row per time point: the time value, and the first non-null value among the plan's
 *       value-bearing intensity columns in index order, such as has_uv's {@code intensity_f32_au} absorbance where
 *       {@code intensity} is null. A null time reads as NaN, an intensity without any value as 0.
 *   <li><b>chunk</b>, one row per chunk: the time axis is the chunk start followed by the running sum of the chunk
 *       values as deltas, whatever the chunk encoding; a null delta adds 0. The intensities are the first non-null
 *       value-bearing intensity list, else the MS-Numpress SLOF bytes, else none; a null intensity reads as 0,
 *       missing ones are padded with 0 and surplus ones dropped. A chunk without a start is skipped.
 * </ul>
 * Rows without the prefix struct or an index are skipped; the chunks and points of one index are appended in row
 * order. These are Q-U5's semantics; sharing the spectrum chunk decoder would change them (deferred).
 */
final class ChromatogramDataDecoder {

    private final String prefix;
    private final String indexField;
    private final boolean chunked;
    private final String timeField;
    private final String startField;
    private final List<String> intensityFields;
    private final String slofField;

    private ChromatogramDataDecoder(SignalPlan plan) {
        this.prefix = plan.prefix();
        this.indexField = plan.indexField();
        this.chunked = plan.layout() == SignalPlan.Layout.CHUNK;
        this.timeField = field(plan.axis().values());
        this.startField = field(plan.axis().start());
        this.intensityFields = plan.intensities().stream()
                .filter(c -> c.format().valueBearing())
                .map(Column::field)
                .toList();
        this.slofField = plan.intensities().stream()
                .filter(c -> c.format() == BufferFormat.CHUNK_TRANSFORM
                        && MSNumpress.ACC_NUMPRESS_SLOF.equals(c.transform()))
                .map(Column::field)
                .findFirst().orElse(null);
    }

    /**
     * Groups the rows of {@code dataFile} by chromatogram index, in the order the indices first occur.
     *
     * @throws MzPeakException when SLOF bytes do not decode; the message names the chromatogram index
     */
    static Map<Long, PointArrays.XY> decode(InputFile dataFile, SignalPlan plan) {
        ChromatogramDataDecoder decoder = new ChromatogramDataDecoder(plan);
        Map<Long, DoubleArrayBuilder[]> acc = new LinkedHashMap<>();
        ParquetGroups.forEach(dataFile, row -> decoder.accept(row, acc));

        Map<Long, PointArrays.XY> out = new LinkedHashMap<>();
        acc.forEach((idx, b) -> out.put(idx, new PointArrays.XY(b[0].toArray(), b[1].toArray())));
        return out;
    }

    private static String field(Column column) {
        return column == null ? null : column.field();
    }

    private void accept(Group row, Map<Long, DoubleArrayBuilder[]> acc) {
        Group group = ParquetGroups.optGroup(row, prefix);
        if (group == null) {
            return;
        }
        Long idx = ParquetGroups.optLong(group, indexField);
        if (idx == null) {
            return;
        }
        if (chunked) {
            acceptChunk(group, idx, acc);
        } else {
            acceptPoint(group, idx, acc);
        }
    }

    private void acceptPoint(Group point, long idx, Map<Long, DoubleArrayBuilder[]> acc) {
        Double t = timeField == null ? null : ParquetGroups.optDouble(point, timeField);
        Double in = pointIntensity(point);
        DoubleArrayBuilder[] b = builders(acc, idx);
        b[0].add(t  == null ? Double.NaN : t);
        b[1].add(in == null ? 0.0 : in);
    }

    /** The first non-null value among the plan's value-bearing intensity columns, or {@code null} when none has one. */
    private Double pointIntensity(Group point) {
        for (String field : intensityFields) {
            Double v = ParquetGroups.optDouble(point, field);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private void acceptChunk(Group chunk, long idx, Map<Long, DoubleArrayBuilder[]> acc) {
        // Time axis: chunk start + cumulative sum of the chunk values
        Double start = startField == null ? null : ParquetGroups.optDouble(chunk, startField);
        if (start == null) {
            return;
        }
        double[] deltas    = timeField == null ? new double[0] : ParquetGroups.doubleListNullable(chunk, timeField);
        double[] intensity = chunkIntensity(chunk, idx);

        // Reconstruct time axis: time[0] = start; time[i+1] = time[i] + deltas[i]
        // intensity.length == deltas.length + 1 (intensity[0] is at the chunk start)
        int n = deltas.length + 1;
        double[] time = new double[n];
        time[0] = start;
        for (int i = 0; i < deltas.length; i++) {
            double d = deltas[i];
            time[i + 1] = time[i] + (Double.isNaN(d) ? 0.0 : d);
        }

        // Pair with intensity; guard against length mismatch (shouldn't happen in well-formed files)
        DoubleArrayBuilder[] b = builders(acc, idx);
        for (int i = 0; i < n; i++) {
            b[0].add(time[i]);
            double in = (i < intensity.length) ? intensity[i] : 0.0;
            b[1].add(Double.isNaN(in) ? 0.0 : in);
        }
    }

    /**
     * Intensity values of a chunk row: the first non-null value-bearing intensity list of the plan, otherwise the
     * plan's MS-Numpress SLOF bytes. Returns an empty array when neither is present.
     */
    private double[] chunkIntensity(Group chunk, long chromatogramIndex) {
        for (String field : intensityFields) {
            if (ParquetGroups.has(chunk, field)) {
                return ParquetGroups.doubleListNullable(chunk, field);
            }
        }
        if (slofField != null && ParquetGroups.has(chunk, slofField)) {
            byte[] bytes = ParquetGroups.byteList(chunk, slofField);
            if (bytes.length == 0) {
                return new double[0];
            }
            try {
                return MSNumpress.decode(MSNumpress.ACC_NUMPRESS_SLOF, bytes, bytes.length);
            } catch (RuntimeException e) {
                throw new MzPeakException("Failed to Numpress-decode intensity for " + indexField + " "
                        + chromatogramIndex, e);
            }
        }
        // A chunk with only PIC bytes is not decoded yet and reads as zero intensities
        // (deferred: PIC chromatograms, via MSNumpress.decodePic, since decode() rejects PIC payloads < 8 bytes).
        return new double[0];
    }

    private static DoubleArrayBuilder[] builders(Map<Long, DoubleArrayBuilder[]> acc, long idx) {
        return acc.computeIfAbsent(idx,
                k -> new DoubleArrayBuilder[] {new DoubleArrayBuilder(), new DoubleArrayBuilder()});
    }
}
