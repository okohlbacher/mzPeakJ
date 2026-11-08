package org.mzpeak.cli;

import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SpectrumDescription;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.TreeMap;
import java.util.function.IntConsumer;

/**
 * Tiny demo CLI: prints a summary of an mzPeak dataset.
 *
 * <pre>{@code
 *   java -cp mzpeakj/target/classes:<deps> org.mzpeak.cli.MzPeakInfo path/to/run.mzpeak
 * }</pre>
 */
public final class MzPeakInfo {

    private MzPeakInfo() {
    }

    public static void main(String[] args) {
        exit(run(args, System.out, System.err), System::exit);
    }

    /** Prints the summary to {@code out} and returns 0, or prints the usage to {@code err} and returns 2. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 1) {
            err.println("Usage: MzPeakInfo <path-to-.mzpeak-dir-or-zip> [maxSpectra]");
            return 2;
        }
        Path path = Path.of(args[0]);
        int max = args.length > 1 ? Integer.parseInt(args[1]) : 5;

        try (MzPeakReader reader = MzPeakReader.open(path)) {
            out.println("Dataset: " + path);
            out.println("Spectra: " + reader.size());

            TreeMap<Integer, Long> byLevel = new TreeMap<>();
            for (SpectrumDescription d : reader.metadata()) {
                byLevel.merge(d.msLevel(), 1L, Long::sum);
            }
            out.println("MS levels: " + byLevel);

            var chroms = reader.chromatograms();
            out.println("Chromatograms: " + chroms.size());
            for (Chromatogram c : chroms) {
                out.printf("  - %s (%s) %d points%n", c.id(), c.typeCurie(), c.size());
            }

            int wl = reader.wavelengthSpectra().size();
            if (wl > 0) {
                out.println("Wavelength (UV/DAD) spectra: " + wl);
            }

            out.println("First " + Math.min(max, reader.size()) + " spectra:");
            for (int i = 0; i < Math.min(max, reader.size()); i++) {
                var s = reader.getSpectrumAt(i);
                SpectrumDescription d = s.description();
                StringBuilder line = new StringBuilder(String.format(Locale.US,
                        "  #%d  MS%d  rt=%.4f  points=%d  peaks=%d  %s",
                        d.index(), d.msLevel(), d.retentionTime(), s.pointCount(), s.peaks().size(),
                        d.signalContinuity()));
                Precursor p = d.primaryPrecursor();
                if (p != null && p.primaryIon() != null) {
                    line.append(String.format(Locale.US, "  precursor=%.4f", p.primaryIon().mz()));
                    if (p.primaryIon().charge() != null) {
                        line.append(" z=").append(p.primaryIon().charge());
                    }
                }
                out.println(line);
            }
        }
        return 0;
    }

    /** Hands a non-zero {@code status} to {@code exiter} (in {@link #main}, {@link System#exit}). */
    static void exit(int status, IntConsumer exiter) {
        if (status != 0) {
            exiter.accept(status);
        }
    }
}
