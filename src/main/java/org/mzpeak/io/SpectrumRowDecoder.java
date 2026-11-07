package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.SignalContinuity;

import java.util.List;

/** Decodes one {@code spectrum} struct of the packed spectrum metadata layout into a {@link SpectrumHead}. */
final class SpectrumRowDecoder {

    // spectrum facet
    private static final String F_ID = "id";
    private static final String F_MS_LEVEL = "MS_1000511_ms_level";
    private static final String F_TIME = "time";
    private static final String F_POLARITY = "MS_1000465_scan_polarity";
    private static final String F_REPRESENTATION = "MS_1000525_spectrum_representation";
    private static final String F_N_POINTS = "MS_1003060_number_of_data_points";
    private static final String F_N_PEAKS = "MS_1003059_number_of_peaks";
    private static final String F_MZ_DELTA_MODEL = "mz_delta_model";

    private SpectrumRowDecoder() {
    }

    /** Decodes the {@code spectrum} struct whose (non-negative) {@code index} is {@code idx}. */
    static SpectrumHead decode(Group spectrum, long idx) {
        SpectrumHead b = new SpectrumHead();
        b.index = idx;
        b.id = ParquetGroups.optString(spectrum, F_ID);
        Integer level = ParquetGroups.optInt(spectrum, F_MS_LEVEL);
        b.msLevel = level == null ? 0 : level;
        Double t = ParquetGroups.optDouble(spectrum, F_TIME);
        b.time = t == null ? Double.NaN : t;
        b.polarity = Polarity.fromCode(ParquetGroups.optInt(spectrum, F_POLARITY));
        b.continuity = SignalContinuity.fromCurie(ParquetGroups.optString(spectrum, F_REPRESENTATION));
        Long nPts = ParquetGroups.optLong(spectrum, F_N_POINTS);
        b.numberOfDataPoints = nPts == null ? 0 : nPts;
        Long nPeaks = ParquetGroups.optLong(spectrum, F_N_PEAKS);
        b.numberOfPeaks = nPeaks == null ? 0 : nPeaks;
        b.spectrumParams = ParquetGroups.readParams(spectrum, "parameters");
        b.mzDeltaModel = ParquetGroups.doubleListNullable(spectrum, F_MZ_DELTA_MODEL);
        return b;
    }

    /**
     * Mutable assembly holder for one spectrum's core fields, plus its {@code mz_delta_model} polynomial
     * coefficients (empty when the struct has none).
     */
    static final class SpectrumHead {
        long index;
        String id;
        int msLevel;
        double time;
        Polarity polarity;
        SignalContinuity continuity;
        long numberOfDataPoints;
        long numberOfPeaks;
        List<Param> spectrumParams = List.of();
        double[] mzDeltaModel;
    }
}
