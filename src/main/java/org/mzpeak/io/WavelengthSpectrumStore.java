package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.WavelengthSpectrum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads {@code wavelength_spectra_metadata.parquet} + {@code wavelength_spectra_data.parquet} into
 * {@link WavelengthSpectrum}s. UV/DAD data is dense {@code point} layout (no null-marking, no precursors).
 */
final class WavelengthSpectrumStore {

    private static final String F_INDEX = "index";
    private static final String F_ID = "id";
    private static final String F_TIME = "time";
    private static final String F_LAMBDA_MAX = "MS_1003812_lambda_max_unit_UO:0000018";
    private static final String F_WL_INDEX = "wavelength_spectrum_index";
    private static final String F_WAVELENGTH = "wavelength";

    private final List<WavelengthSpectrum> all;
    private final Map<Long, WavelengthSpectrum> byIndex;

    private WavelengthSpectrumStore(List<WavelengthSpectrum> all) {
        this.all = List.copyOf(all);
        this.byIndex = new LinkedHashMap<>();
        for (WavelengthSpectrum w : all) {
            byIndex.put(w.index(), w);
        }
    }

    List<WavelengthSpectrum> all() {
        return all;
    }

    Optional<WavelengthSpectrum> byIndex(long index) {
        return Optional.ofNullable(byIndex.get(index));
    }

    static WavelengthSpectrumStore load(InputFile metadataFile, InputFile dataFile) {
        // metadata: index -> (id, time, lambdaMax), in encounter order
        Map<Long, Object[]> meta = new LinkedHashMap<>();
        List<Long> order = new ArrayList<>();
        ParquetGroups.forEach(metadataFile, row -> {
            Group s = ParquetGroups.optGroup(row, "spectrum");
            if (s == null) {
                return;
            }
            Long idx = ParquetGroups.optLong(s, F_INDEX);
            if (idx == null) {
                return;
            }
            if (!meta.containsKey(idx)) {
                order.add(idx);
            }
            Double time = ParquetGroups.optDouble(s, F_TIME);
            meta.put(idx, new Object[] {
                    ParquetGroups.optString(s, F_ID),
                    time == null ? Double.NaN : time,
                    ParquetGroups.optDouble(s, F_LAMBDA_MAX)});
        });

        Map<Long, PointArrays.XY> data = PointArrays.groupByIndex(dataFile, F_WL_INDEX, F_WAVELENGTH);

        List<WavelengthSpectrum> all = new ArrayList<>(order.size());
        for (Long idx : order) {
            Object[] m = meta.get(idx);
            PointArrays.XY xy = data.get(idx);
            double[] wavelength = xy == null ? new double[0] : xy.x();
            double[] intensity = xy == null ? new double[0] : xy.intensity();
            all.add(new WavelengthSpectrum(idx, (String) m[0], (Double) m[1], (Double) m[2], wavelength, intensity));
        }
        return new WavelengthSpectrumStore(all);
    }
}
