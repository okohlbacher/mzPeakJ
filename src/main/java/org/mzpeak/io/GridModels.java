package org.mzpeak.io;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The coordinate grid models of the grid chunk encoding (MS:1003826, design §8.6): each maps an unsigned 32-bit grid
 * index to a coordinate, parameterized by the {@code parameters} list stored with the indices. {@link #KINDS} is the
 * only place that names a model, so renumbering a provisional accession is a one-line change.
 *
 * <p>The arithmetic follows the reference's scalar decoders (mzPeak {@code grid.rs}, mzdata
 * {@code calibration.rs}), fused multiply-adds included; with them diaPASEF.grid decodes to the stored values of
 * diaPASEF.ref bit for bit.
 */
final class GridModels {

    /** A grid model with its parameters bound. */
    @FunctionalInterface
    interface Model {
        /** The coordinate of grid index {@code index}, in 0..2^32-1. */
        double fromIndex(long index);
    }

    /**
     * One kind of grid model.
     *
     * @param accession     the {@code grid_type} CURIE
     * @param name          the term name
     * @param provisional   whether the accession is a placeholder that is not in PSI-MS yet
     * @param minParameters the fewest parameters the model takes
     * @param maxParameters the most parameters the model takes
     * @param factory       binds a parameter list of an accepted length
     */
    record Kind(String accession, String name, boolean provisional, int minParameters, int maxParameters,
                Function<double[], Model> factory) {

        /**
         * The model with {@code parameters} bound.
         *
         * @throws IllegalArgumentException when the number of parameters does not fit the model
         */
        Model model(double[] parameters) {
            int n = parameters.length;
            if (n < minParameters || n > maxParameters) {
                String expected = minParameters == maxParameters ? Integer.toString(minParameters)
                        : minParameters + " to " + maxParameters;
                throw new IllegalArgumentException("grid_type " + accession + " (" + name + ") takes " + expected
                        + " parameters, found " + n);
            }
            return factory.apply(parameters.clone());
        }
    }

    /** MS:1003824: {@code (a + b·i) / s} with parameters {@code [a, b]} or {@code [a, b, s]}, s defaulting to 1. */
    static final String LINEAR = "MS:1003824";
    /** MS:1003825: {@code (a + b·i)² / s} with parameters {@code [a, b]} or {@code [a, b, s]}, s defaulting to 1. */
    static final String SQUARE_ROOT = "MS:1003825";
    /** Provisional: the rustims approximation of the Bruker timsTOF m/z model, mzdata's {@code convert_f64}. */
    static final String TIMS_TOF_MZ = "MS:9999002";
    /** Provisional: the Bruker timsTOF 1/K0 model 2. */
    static final String TIMS_TOF_MOBILITY = "MS:9999001";

    /** Every known model, keyed by accession. */
    static final Map<String, Kind> KINDS = table(
            new Kind(LINEAR, "linear grid interpolation", false, 2, 3, GridModels::linear),
            new Kind(SQUARE_ROOT, "square root grid interpolation", false, 2, 3, GridModels::squareRoot),
            new Kind(TIMS_TOF_MZ, "timsTOF m/z grid", true, 7, 7, GridModels::timsTofMz),
            new Kind(TIMS_TOF_MOBILITY, "timsTOF 1/K0 grid", true, 4, 4, GridModels::timsTofMobility));

    private GridModels() {
    }

    private static Map<String, Kind> table(Kind... kinds) {
        Map<String, Kind> out = new LinkedHashMap<>();
        for (Kind k : kinds) {
            out.put(k.accession(), k);
        }
        return Collections.unmodifiableMap(out);
    }

    /** The kind named by {@code gridType}, or null when it is unknown (or null). */
    static Kind kind(String gridType) {
        return gridType == null ? null : KINDS.get(gridType);
    }

    /** The coordinates of {@code indices}. */
    static double[] decode(Model model, long[] indices) {
        double[] out = new double[indices.length];
        for (int i = 0; i < indices.length; i++) {
            out[i] = model.fromIndex(indices[i]);
        }
        return out;
    }

    private static double scale(double[] p) {
        return p.length > 2 ? p[2] : 1.0;
    }

    /** {@code LinearGrid::from_index}: {@code (i·slope + intercept) / scale}. */
    private static Model linear(double[] p) {
        double intercept = p[0];
        double slope = p[1];
        double scale = scale(p);
        return i -> ((double) i * slope + intercept) / scale;
    }

    /** {@code SquareRootLinearGrid::from_index}: {@code (i·slope + intercept)² / scale}. */
    private static Model squareRoot(double[] p) {
        double intercept = p[0];
        double slope = p[1];
        double scale = scale(p);
        return i -> {
            double v = (double) i * slope + intercept;
            return v * v / scale;
        };
    }

    /**
     * {@code MzCalibrationModel2::convert_f64} (mzdata 0.68), parameters
     * {@code [c0, beta, c2, c3, c4, digitizer_timebase, digitizer_delay]}: the time of flight
     * {@code i·timebase + delay} (one fused multiply-add), then the root {@code s} of
     * {@code c0 + beta·s + c2·s² + c3·s³ = tof} (Newton, at most 8 steps, when c3 ≠ 0; the quadratic formula when
     * only c2 ≠ 0; linear otherwise), and m/z = {@code s² − c4}.
     */
    private static Model timsTofMz(double[] p) {
        double c0 = p[0];
        double beta = p[1];
        double c2 = p[2];
        double c3 = p[3];
        double c4 = p[4];
        double timebase = p[5];
        double delay = p[6];
        return i -> {
            double tof = Math.fma((double) i, timebase, delay);
            double inner = tof - c0;
            double s0 = inner / beta;
            double refined;
            if (c3 != 0.0) {
                double s = s0;
                double c2x2 = c2 * 2.0;
                double c3x3 = c3 * 3.0;
                for (int step = 0; step < 8; step++) {
                    double s2 = s * s;
                    double f1 = Math.fma(s, beta, c0);
                    double f2 = s2 * c2;
                    double f3 = Math.fma(s2 * s, c3, -tof);
                    double f = f1 + f2 + f3;
                    double df1 = Math.fma(s, c2x2, beta);
                    double df2 = s2 * c3x3;
                    double derivative = df1 + df2;
                    if (derivative == 0.0) {
                        break;
                    }
                    double delta = f / derivative;
                    s -= delta;
                    if (Math.abs(delta) < 1e-12) {
                        break;
                    }
                }
                refined = s;
            } else if (c2 != 0.0) {
                double d = beta * beta - 4.0 * c2 * (c0 - tof);
                if (d < 0.0) {
                    refined = s0;
                } else {
                    double q = -0.5 * (beta + Math.sqrt(d));
                    refined = (c0 - tof) / q;
                }
            } else {
                refined = s0;
            }
            return refined * refined - c4;
        };
    }

    /**
     * {@code TimsCalibrationModel2::convert} (mzdata 0.68), parameters {@code [c6, c7, offset, slope]}:
     * {@code 1 / (c6 + c7 / (offset + slope·i))}.
     */
    private static Model timsTofMobility(double[] p) {
        double c6 = p[0];
        double c7 = p[1];
        double offset = p[2];
        double slope = p[3];
        return i -> 1.0 / (c6 + c7 / (offset + slope * (double) i));
    }
}
