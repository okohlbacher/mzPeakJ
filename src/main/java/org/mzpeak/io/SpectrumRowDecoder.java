package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.SignalContinuity;

import java.util.List;

/**
 * Decodes one spectrum row of the spectrum metadata into a {@link SpectrumHead}; columns are resolved by accession.
 */
final class SpectrumRowDecoder {

    // spectrum facet
    private static final String F_ID = "id";
    private static final String MS_LEVEL = "MS:1000511";
    private static final String F_TIME = "time";
    private static final String POLARITY = "MS:1000465";
    private static final String REPRESENTATION = "MS:1000525";
    private static final String N_POINTS = "MS:1003060";
    private static final String N_PEAKS = "MS:1003059";

    // m/z delta model, most current first
    private static final String POLYNOMIAL_DELTA_INTERPOLATION = "MS:1003821";
    private static final String COORDINATE_SPACING_MODEL = "MS:1003820";
    private static final String F_COORDINATE_SPACING_MODEL = "coordinate_spacing_model";
    private static final String F_MZ_DELTA_MODEL = "mz_delta_model";
    private static final String F_MEDIAN_DELTA = "median_delta";

    private SpectrumRowDecoder() {
    }

    /** Decodes the spectrum row whose (non-negative) {@code index} is {@code idx}. */
    static SpectrumHead decode(FacetRow spectrum, long idx) {
        ColumnResolver columns = spectrum.columns();
        Group g = spectrum.group();
        SpectrumHead b = new SpectrumHead();
        b.index = idx;
        b.id = columns.stringValue(g, F_ID);
        Integer level = columns.intValue(g, columns.resolve(MS_LEVEL));
        b.msLevel = level == null ? 0 : level;
        Double t = columns.doubleValue(g, F_TIME);
        b.time = t == null ? Double.NaN : t;
        b.polarity = Polarity.fromCode(columns.intValue(g, columns.resolve(POLARITY)));
        b.continuity = SignalContinuity.fromCurie(columns.stringValue(g, columns.resolve(REPRESENTATION)));
        Long nPts = columns.longValue(g, columns.resolve(N_POINTS));
        b.numberOfDataPoints = nPts == null ? 0 : nPts;
        Long nPeaks = columns.longValue(g, columns.resolve(N_PEAKS));
        b.numberOfPeaks = nPeaks == null ? 0 : nPeaks;
        b.spectrumParams = columns.params(g);
        b.mzDeltaModel = deltaModel(columns, g);
        return b;
    }

    /**
     * The row's m/z delta model coefficients: the list column of MS:1003821, else of MS:1003820 (also named
     * {@code coordinate_spacing_model} or {@code mz_delta_model}), else the scalar {@code median_delta} as a
     * one-coefficient model. Empty when the row has none.
     */
    private static double[] deltaModel(ColumnResolver columns, Group g) {
        String list = columns.resolve(POLYNOMIAL_DELTA_INTERPOLATION);
        if (list == null) {
            list = columns.resolve(COORDINATE_SPACING_MODEL, F_COORDINATE_SPACING_MODEL, F_MZ_DELTA_MODEL);
        }
        if (list != null) {
            return columns.doubleList(g, list);
        }
        Double median = columns.doubleValue(g, columns.resolveName(F_MEDIAN_DELTA));
        return median == null ? new double[0] : new double[] {median};
    }

    /**
     * Mutable assembly holder for one spectrum's core fields, plus its m/z delta model polynomial coefficients
     * (empty when the row has none).
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
