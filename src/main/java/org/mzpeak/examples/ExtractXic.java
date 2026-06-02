package org.mzpeak.examples;

import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Example: extract an XIC (extracted-ion chromatogram) for a target m/z — demonstrates reading + processing.
 *
 * <pre>{@code
 *   ExtractXic <in.mzpeak> <out.csv> --mz 445.12 [--tol-ppm 20 | --tol-da 0.01]
 *                                    [--ms-level 1] [--rt-min M] [--rt-max M]
 * }</pre>
 *
 * For each matching spectrum (MS1 by default), sums the intensity of all peaks within the m/z tolerance and
 * writes {@code retention_time,intensity} rows to the CSV.
 */
public final class ExtractXic {

    private ExtractXic() {
    }

    record XicPoint(double retentionTime, double intensity) {
    }

    /** @return the XIC points, one per matching spectrum, in acquisition order. */
    public static java.util.List<XicPoint> compute(Path input, double targetMz, double tolDa,
                                                   SpectrumFilter filter) {
        java.util.List<XicPoint> points = new java.util.ArrayList<>();
        double lo = targetMz - tolDa;
        double hi = targetMz + tolDa;
        try (MzPeakReader reader = MzPeakReader.open(input)) {
            for (Spectrum s : reader) {
                SpectrumDescription d = s.description();
                if (!filter.accept(d)) {
                    continue;
                }
                double[] mz = s.mz();
                double[] intensity = s.intensity();
                double sum = 0;
                for (int i = 0; i < mz.length; i++) {
                    if (mz[i] >= lo && mz[i] <= hi) {
                        sum += intensity[i];
                    } else if (mz[i] > hi) {
                        break; // m/z is ascending
                    }
                }
                points.add(new XicPoint(d.retentionTime(), sum));
            }
        }
        return points;
    }

    public static void main(String[] args) {
        ExampleArgs a = new ExampleArgs(args);
        if (a.positional.size() < 2 || a.options.get("mz") == null) {
            System.err.println("Usage: ExtractXic <in.mzpeak> <out.csv> --mz M "
                    + "[--tol-ppm 20 | --tol-da 0.01] [--ms-level 1] [--rt-min M] [--rt-max M]");
            System.exit(2);
        }
        double mz = a.optDouble("mz", 0);
        double tolDa = a.options.containsKey("tol-da")
                ? a.optDouble("tol-da", 0.01)
                : mz * a.optDouble("tol-ppm", 20) / 1.0e6;
        // default to MS1 if no level requested
        if (a.options.get("ms-level") == null) {
            a.options.put("ms-level", "1");
        }
        var points = compute(Path.of(a.positional.get(0)), mz, tolDa, SpectrumFilter.from(a));
        Path out = Path.of(a.positional.get(1));
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("retention_time,intensity\n");
            for (XicPoint p : points) {
                w.write(String.format(Locale.US, "%.6f,%.4f%n", p.retentionTime(), p.intensity()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing XIC to " + out, e);
        }
        System.out.printf(Locale.US, "Wrote XIC with %d points (m/z %.4f +/- %.4f Da) to %s%n",
                points.size(), mz, tolDa, a.positional.get(1));
    }
}
