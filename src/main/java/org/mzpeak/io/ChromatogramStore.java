package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Chromatogram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads {@code chromatograms_metadata.parquet} + {@code chromatograms_data.parquet} into {@link Chromatogram}s.
 * Handles both {@code point} layout (one row per time point) and {@code chunk} layout (delta-encoded time
 * values with one chunk row per chromatogram, as used in the chunked mzPeak variant).
 */
final class ChromatogramStore {

    private final List<Chromatogram> all;
    private final Map<Long, Chromatogram> byIndex;
    private final Map<String, Chromatogram> byId;

    private ChromatogramStore(List<Chromatogram> all) {
        this.all = List.copyOf(all);
        this.byIndex = new LinkedHashMap<>();
        this.byId = new LinkedHashMap<>();
        for (Chromatogram c : all) {
            byIndex.put(c.index(), c);
            if (c.id() != null) {
                byId.putIfAbsent(c.id(), c);
            }
        }
    }

    List<Chromatogram> all()                    { return all; }
    Optional<Chromatogram> byIndex(long index)  { return Optional.ofNullable(byIndex.get(index)); }
    Optional<Chromatogram> byId(String id)      { return Optional.ofNullable(byId.get(id)); }

    static ChromatogramStore load(InputFile metadataFile, InputFile dataFile) {
        // ---- metadata ----------------------------------------------------------------
        Map<Long, String[]> meta = new LinkedHashMap<>(); // {id, typeCurie}
        List<Long> order = new ArrayList<>();
        ParquetGroups.forEach(metadataFile, row -> {
            Group c = ParquetGroups.optGroup(row, "chromatogram");
            if (c == null) return;
            Long idx = ParquetGroups.optLong(c, "index");
            if (idx == null) return;
            if (!meta.containsKey(idx)) order.add(idx);
            meta.put(idx, new String[] {
                    ParquetGroups.optString(c, "id"),
                    ParquetGroups.optString(c, "MS_1000626_chromatogram_type")});
        });

        // ---- data: point or chunk layout -------------------------------------------
        Map<Long, PointArrays.XY> data = loadData(dataFile);

        // ---- assemble --------------------------------------------------------------
        List<Chromatogram> all = new ArrayList<>(order.size());
        for (Long idx : order) {
            String[] m = meta.get(idx);
            PointArrays.XY xy = data.get(idx);
            double[] time      = xy == null ? new double[0] : xy.x();
            double[] intensity = xy == null ? new double[0] : xy.intensity();
            all.add(new Chromatogram(idx, m[0], m[1], time, intensity));
        }
        return new ChromatogramStore(all);
    }

    /**
     * Route to the correct data decoder by inspecting the top-level struct name of the first row.
     * {@code point} rows use the standard PointArrays grouping; {@code chunk} rows use delta decoding.
     */
    private static Map<Long, PointArrays.XY> loadData(InputFile dataFile) {
        // Peek at the schema to decide layout (point vs chunk).
        // PointArrays.groupByIndex handles point; we implement chunk inline.
        Map<Long, PointArrays.XY>[] result = new Map[] {null};
        Map<Long, DoubleArrayBuilder[]> chunkAcc = new LinkedHashMap<>();

        ParquetGroups.forEach(dataFile, row -> {
            Group point = ParquetGroups.optGroup(row, "point");
            if (point != null) {
                // Point layout: delegate collection inline
                Long idx = ParquetGroups.optLong(point, "chromatogram_index");
                if (idx == null) return;
                Double t  = ParquetGroups.optDouble(point, "time");
                Double in = ParquetGroups.optDouble(point, "intensity");
                DoubleArrayBuilder[] b = chunkAcc.computeIfAbsent(idx,
                        k -> new DoubleArrayBuilder[] {new DoubleArrayBuilder(), new DoubleArrayBuilder()});
                b[0].add(t  == null ? Double.NaN : t);
                b[1].add(in == null ? 0.0 : in);
                return;
            }
            Group chunk = ParquetGroups.optGroup(row, "chunk");
            if (chunk == null) return;

            // Chunk layout: time_chunk_start + cumulative-sum of time_chunk_values → time axis
            Long idx = ParquetGroups.optLong(chunk, "chromatogram_index");
            if (idx == null) return;
            Double start = ParquetGroups.optDouble(chunk, "time_chunk_start");
            if (start == null) return;

            double[] deltas    = ParquetGroups.doubleListNullable(chunk, "time_chunk_values");
            double[] intensity = ParquetGroups.doubleListNullable(chunk, "intensity");

            // Reconstruct time axis: time[0] = start; time[i+1] = time[i] + deltas[i]
            // intensity.length == deltas.length + 1 (intensity[0] is at time_chunk_start)
            int n = deltas.length + 1;
            double[] time = new double[n];
            time[0] = start;
            for (int i = 0; i < deltas.length; i++) {
                double d = deltas[i];
                time[i + 1] = time[i] + (Double.isNaN(d) ? 0.0 : d);
            }

            // Pair with intensity; guard against length mismatch (shouldn't happen in well-formed files)
            DoubleArrayBuilder[] b = chunkAcc.computeIfAbsent(idx,
                    k -> new DoubleArrayBuilder[] {new DoubleArrayBuilder(), new DoubleArrayBuilder()});
            for (int i = 0; i < n; i++) {
                b[0].add(time[i]);
                double in = (i < intensity.length) ? intensity[i] : 0.0;
                b[1].add(Double.isNaN(in) ? 0.0 : in);
            }
        });

        Map<Long, PointArrays.XY> out = new LinkedHashMap<>();
        chunkAcc.forEach((idx, b) -> out.put(idx, new PointArrays.XY(b[0].toArray(), b[1].toArray())));
        return out;
    }
}
