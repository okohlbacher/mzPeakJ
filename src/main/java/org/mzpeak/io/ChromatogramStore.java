package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.schema.Type;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
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
 * Intensities come from the literal {@code intensity} column; a point row falls back to a numeric
 * {@code intensity_*} column (e.g. UV absorbance), a chunk row to MS-Numpress SLOF bytes.
 */
final class ChromatogramStore {

    /** Names a member given directly as an {@link InputFile} in error messages, by its manifest role. */
    private static final String MEMBER = "chromatogram metadata member";

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

    /**
     * Loads the members that {@code manifest} lists under the roles (chromatogram, metadata) and (chromatogram, data
     * arrays); error messages name the metadata member by its file name.
     *
     * @return the store, or {@code null} when the manifest lacks either member or lists it without a name
     */
    static ChromatogramStore load(MzPeakSource source, MzPeakManifest manifest) {
        String metadataName = manifest.find(EntityType.CHROMATOGRAM, DataKind.METADATA)
                .map(MzPeakManifest.Entry::name).orElse(null);
        String dataName = manifest.find(EntityType.CHROMATOGRAM, DataKind.DATA_ARRAYS)
                .map(MzPeakManifest.Entry::name).orElse(null);
        if (metadataName == null || dataName == null) {
            return null;
        }
        return load(source.inputFile(metadataName), metadataName, source.inputFile(dataName));
    }

    /** Loads a metadata and a data member given directly; error messages name the metadata member by its role. */
    static ChromatogramStore load(InputFile metadataFile, InputFile dataFile) {
        return load(metadataFile, MEMBER, dataFile);
    }

    private static ChromatogramStore load(InputFile metadataFile, String member, InputFile dataFile) {
        MetadataShape shape = MetadataShape.detect(ParquetFooter.read(metadataFile), "chromatogram", "index", member);
        if (shape == MetadataShape.SPLIT) {
            throw new MzPeakException(member + ": split metadata layout not supported yet");
        }
        if (shape == MetadataShape.EMPTY) {
            return new ChromatogramStore(List.of());
        }
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
                if (in == null) {
                    in = fallbackPointIntensity(point);
                }
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
            double[] intensity = chunkIntensity(chunk, idx);

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

    /**
     * Intensity of a point row whose literal {@code intensity} is null: the first non-null value among the
     * point's primitive numeric (FLOAT, DOUBLE, INT32, INT64) fields named {@code intensity_*}, in schema order —
     * e.g. the {@code intensity_f32_au} absorbance column of a UV/DAD chromatogram. List, group, repeated and
     * BINARY fields are skipped without being read. Returns {@code null} when there is no such value.
     */
    private static Double fallbackPointIntensity(Group point) {
        for (Type field : point.getType().getFields()) {
            if (!field.getName().startsWith("intensity_")
                    || !field.isPrimitive() || field.isRepetition(Type.Repetition.REPEATED)) {
                continue;
            }
            switch (field.asPrimitiveType().getPrimitiveTypeName()) {
                case FLOAT, DOUBLE, INT32, INT64 -> {
                    Double v = ParquetGroups.optDouble(point, field.getName());
                    if (v != null) {
                        return v;
                    }
                }
                default -> { }
            }
        }
        return null;
    }

    /**
     * Intensity values of a chunk row: the plain {@code intensity} list when present, otherwise the MS-Numpress
     * SLOF bytes in {@code intensity_numpress_slof_bytes}. Returns an empty array when neither is present.
     */
    private static double[] chunkIntensity(Group chunk, long chromatogramIndex) {
        if (ParquetGroups.has(chunk, "intensity")) {
            return ParquetGroups.doubleListNullable(chunk, "intensity");
        }
        if (ParquetGroups.has(chunk, "intensity_numpress_slof_bytes")) {
            byte[] bytes = ParquetGroups.byteList(chunk, "intensity_numpress_slof_bytes");
            if (bytes.length == 0) {
                return new double[0];
            }
            try {
                return MSNumpress.decode(MSNumpress.ACC_NUMPRESS_SLOF, bytes, bytes.length);
            } catch (RuntimeException e) {
                throw new MzPeakException("Failed to Numpress-decode intensity for chromatogram_index "
                        + chromatogramIndex, e);
            }
        }
        // A chunk with only intensity_numpress_pic_bytes is not decoded yet and reads as zero intensities
        // (deferred: PIC chromatograms, via MSNumpress.decodePic, since decode() rejects PIC payloads < 8 bytes).
        return new double[0];
    }
}
