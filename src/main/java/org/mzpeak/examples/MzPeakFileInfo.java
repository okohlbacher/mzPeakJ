package org.mzpeak.examples;

import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Example: a mzPeak-only port of the OpenMS {@code FileInfo} tool. Prints the same kind of summary OpenMS
 * reports for a peak map — data ranges (overall and per MS level), spectrum/peak counts, peak type, precursor
 * charge distribution, and chromatogram breakdown — restricted to what an mzPeak file carries.
 *
 * <pre>{@code
 *   MzPeakFileInfo <in.mzpeak> [-s]      # -s adds intensity statistics (five-number summary)
 * }</pre>
 *
 * <p>Note: like OpenMS {@code FileInfo}, this scans the whole file; mzPeakJ loads the signal arrays to do so.
 * Instrument/activation metadata (kept in the Parquet footer) is not parsed by mzPeakJ and is reported as such.
 */
public final class MzPeakFileInfo {

    private MzPeakFileInfo() {
    }

    public static void run(Path input, boolean statistics, PrintStream out) {
        try (MzPeakReader reader = MzPeakReader.open(input)) {
            out.println("File name: " + input);
            out.println("File type: mzPeak (" + (Files.isDirectory(input) ? "unpacked directory" : "single-file archive") + ")");
            out.println();

            Range rtAll = new Range();
            Range mzAll = new Range();
            Range intAll = new Range();
            Map<Integer, long[]> perLevelCount = new TreeMap<>();
            Map<Integer, Range[]> perLevelRanges = new TreeMap<>();          // [rt, mz, intensity]
            Map<Integer, SignalContinuity> perLevelContinuity = new TreeMap<>();
            Map<String, Long> chargeDist = new TreeMap<>();
            List<Double> ms1Intensities = statistics ? new ArrayList<>() : null;
            long spectrumPeaks = 0;

            for (Spectrum s : reader) {
                SpectrumDescription d = s.description();
                int level = d.msLevel();
                perLevelCount.computeIfAbsent(level, k -> new long[1])[0]++;
                Range[] pr = perLevelRanges.computeIfAbsent(level, k -> new Range[] {new Range(), new Range(), new Range()});

                double rt = d.retentionTime();
                if (Double.isFinite(rt)) {
                    rtAll.add(rt);
                    pr[0].add(rt);
                }
                perLevelContinuity.merge(level, d.signalContinuity(),
                        (a, b) -> a == b ? a : SignalContinuity.UNKNOWN);

                double[] mz = s.mz();
                double[] intensity = s.intensity();
                spectrumPeaks += mz.length;
                for (int i = 0; i < mz.length; i++) {
                    mzAll.add(mz[i]);
                    intAll.add(intensity[i]);
                    pr[1].add(mz[i]);
                    pr[2].add(intensity[i]);
                    if (ms1Intensities != null && level == 1) {
                        ms1Intensities.add(intensity[i]);
                    }
                }

                if (level > 1) {
                    Precursor p = d.primaryPrecursor();
                    SelectedIon ion = p == null ? null : p.primaryIon();
                    String key = (ion != null && ion.charge() != null) ? ("charge " + ion.charge()) : "unknown";
                    chargeDist.merge(key, 1L, Long::sum);
                }
            }

            // ---- general ----
            out.println("Number of spectra: " + reader.size());
            out.println("MS levels: " + perLevelCount.keySet());
            out.println("Number of spectra per MS level:");
            perLevelCount.forEach((level, c) -> out.println("  level " + level + ": " + c[0]));
            out.println("Number of peaks (spectra): " + spectrumPeaks);
            out.println();

            // ---- ranges ----
            out.println("Ranges:");
            out.println("  retention time: " + rtAll.format());
            out.println("  mass-to-charge: " + mzAll.format());
            out.println("  intensity:      " + intAll.format());
            out.println();
            out.println("Per MS level ranges:");
            for (var e : perLevelRanges.entrySet()) {
                out.println("  MS level " + e.getKey() + ":");
                out.println("    retention time: " + e.getValue()[0].format());
                out.println("    mass-to-charge: " + e.getValue()[1].format());
                out.println("    intensity:      " + e.getValue()[2].format());
            }
            out.println();

            // ---- peak type ----
            out.println("Peak type per MS level:");
            perLevelContinuity.forEach((level, c) -> out.println("  level " + level + ": " + peakType(c)));
            out.println();

            // ---- precursor charges ----
            if (!chargeDist.isEmpty()) {
                out.println("Precursor charge distribution:");
                chargeDist.forEach((k, v) -> out.println("  " + k + ": " + v));
                out.println();
            }

            // ---- chromatograms ----
            List<Chromatogram> chromatograms = reader.chromatograms();
            long chromPeaks = chromatograms.stream().mapToLong(Chromatogram::size).sum();
            out.println("Number of chromatograms: " + chromatograms.size());
            out.println("Number of chromatographic peaks: " + chromPeaks);
            if (!chromatograms.isEmpty()) {
                Map<String, Long> perType = new TreeMap<>();
                for (Chromatogram c : chromatograms) {
                    perType.merge(c.typeCurie() == null ? "unknown" : c.typeCurie(), 1L, Long::sum);
                }
                out.println("Number of chromatograms per type:");
                perType.forEach((t, v) -> out.println("  " + t + " (" + chromatogramTypeName(t) + "): " + v));
            }

            // ---- wavelength (mzPeak-specific) ----
            List<WavelengthSpectrum> wavelength = reader.wavelengthSpectra();
            if (!wavelength.isEmpty()) {
                out.println("Number of wavelength (UV/DAD) spectra: " + wavelength.size());
            }

            out.println();
            out.println("Instrument / activation metadata: not parsed by mzPeakJ (stored in the Parquet footer)");

            // ---- statistics ----
            if (statistics) {
                out.println();
                out.println("-- Statistics --");
                out.println("MS1 peak intensity distribution:");
                printFiveNumberSummary(out, ms1Intensities);
            }
        }
    }

    private static String peakType(SignalContinuity c) {
        return switch (c) {
            case PROFILE -> "profile";
            case CENTROID -> "centroid";
            case UNKNOWN -> "unknown/mixed";
        };
    }

    private static String chromatogramTypeName(String curie) {
        return switch (curie) {
            case "MS:1000235" -> "total ion current";
            case "MS:1000628" -> "basepeak chromatogram";
            case "MS:1000812" -> "absorption chromatogram";
            case "MS:1000810" -> "ion current chromatogram";
            default -> "?";
        };
    }

    private static void printFiveNumberSummary(PrintStream out, List<Double> values) {
        if (values.isEmpty()) {
            out.println("  (no values)");
            return;
        }
        double[] v = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double mean = 0;
        for (double x : v) {
            mean += x;
        }
        mean /= v.length;
        out.printf(Locale.US, "  min:            %.4f%n", v[0]);
        out.printf(Locale.US, "  lower quartile: %.4f%n", quantile(v, 0.25));
        out.printf(Locale.US, "  median:         %.4f%n", quantile(v, 0.50));
        out.printf(Locale.US, "  upper quartile: %.4f%n", quantile(v, 0.75));
        out.printf(Locale.US, "  max:            %.4f%n", v[v.length - 1]);
        out.printf(Locale.US, "  mean:           %.4f%n", mean);
        out.println("  count:          " + v.length);
    }

    private static double quantile(double[] sorted, double q) {
        if (sorted.length == 1) {
            return sorted[0];
        }
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
    }

    /** Tracks a min/max range and formats it. */
    private static final class Range {
        private double min = Double.POSITIVE_INFINITY;
        private double max = Double.NEGATIVE_INFINITY;

        void add(double v) {
            if (v < min) {
                min = v;
            }
            if (v > max) {
                max = v;
            }
        }

        String format() {
            if (min > max) {
                return "(none)";
            }
            return String.format(Locale.US, "%.4f .. %.4f", min, max);
        }
    }

    public static void main(String[] args) {
        ExampleArgs a = new ExampleArgs(args);
        boolean statistics = a.positional.contains("-s") || a.options.containsKey("statistics");
        List<String> inputs = a.positional.stream().filter(p -> !p.equals("-s")).toList();
        if (inputs.isEmpty()) {
            System.err.println("Usage: MzPeakFileInfo <in.mzpeak> [-s]   (-s adds intensity statistics)");
            System.exit(2);
        }
        run(Path.of(inputs.get(0)), statistics, System.out);
    }
}
