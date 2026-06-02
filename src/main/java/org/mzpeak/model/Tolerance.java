package org.mzpeak.model;

/**
 * A mass tolerance, in either parts-per-million or daltons. Mirrors {@code mzpeaks::Tolerance}.
 *
 * @param value magnitude
 * @param unit  PPM or DA
 */
public record Tolerance(double value, Unit unit) {

    public enum Unit { PPM, DA }

    public static Tolerance ppm(double value) {
        return new Tolerance(value, Unit.PPM);
    }

    public static Tolerance da(double value) {
        return new Tolerance(value, Unit.DA);
    }

    /** Absolute tolerance in daltons around {@code mz}. */
    public double absoluteAt(double mz) {
        return unit == Unit.PPM ? Math.abs(mz) * value / 1.0e6 : value;
    }

    /** Inclusive [lower, upper] bounds around {@code mz}. */
    public double[] bounds(double mz) {
        double d = absoluteAt(mz);
        return new double[] {mz - d, mz + d};
    }

    /** True if {@code query} is within tolerance of {@code reference}. */
    public boolean test(double query, double reference) {
        return Math.abs(query - reference) <= absoluteAt(reference);
    }
}
