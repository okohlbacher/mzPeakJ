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

/** Reads {@code chromatograms_metadata.parquet} + {@code chromatograms_data.parquet} into {@link Chromatogram}s. */
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

    List<Chromatogram> all() {
        return all;
    }

    Optional<Chromatogram> byIndex(long index) {
        return Optional.ofNullable(byIndex.get(index));
    }

    Optional<Chromatogram> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    static ChromatogramStore load(InputFile metadataFile, InputFile dataFile) {
        // metadata: index -> (id, type)
        Map<Long, String[]> meta = new LinkedHashMap<>(); // value = {id, typeCurie}
        List<Long> order = new ArrayList<>();
        ParquetGroups.forEach(metadataFile, row -> {
            Group c = ParquetGroups.optGroup(row, "chromatogram");
            if (c == null) {
                return;
            }
            Long idx = ParquetGroups.optLong(c, "index");
            if (idx == null) {
                return;
            }
            if (!meta.containsKey(idx)) {
                order.add(idx);
            }
            meta.put(idx, new String[] {
                    ParquetGroups.optString(c, "id"),
                    ParquetGroups.optString(c, "MS_1000626_chromatogram_type")});
        });

        Map<Long, PointArrays.XY> data = PointArrays.groupByIndex(dataFile, "chromatogram_index", "time");

        List<Chromatogram> all = new ArrayList<>(order.size());
        for (Long idx : order) {
            String[] m = meta.get(idx);
            PointArrays.XY xy = data.get(idx);
            double[] time = xy == null ? new double[0] : xy.x();
            double[] intensity = xy == null ? new double[0] : xy.intensity();
            all.add(new Chromatogram(idx, m[0], m[1], time, intensity));
        }
        return new ChromatogramStore(all);
    }
}
