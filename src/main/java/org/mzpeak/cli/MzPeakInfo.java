package org.mzpeak.cli;

import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Path;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Tiny demo CLI: prints a summary of an mzPeak dataset.
 *
 * <pre>{@code
 *   java -cp target/classes:<deps> org.mzpeak.cli.MzPeakInfo path/to/run.mzpeak
 * }</pre>
 */
public final class MzPeakInfo {

    private MzPeakInfo() {
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: MzPeakInfo <path-to-.mzpeak-dir-or-zip> [maxSpectra]");
            System.exit(2);
        }
        Path path = Path.of(args[0]);
        int max = args.length > 1 ? Integer.parseInt(args[1]) : 5;

        try (MzPeakReader reader = MzPeakReader.open(path)) {
            System.out.println("Dataset: " + path);
            System.out.println("Spectra: " + reader.size());

            TreeMap<Integer, Long> byLevel = new TreeMap<>();
            for (SpectrumDescription d : reader.metadata()) {
                byLevel.merge(d.msLevel(), 1L, Long::sum);
            }
            System.out.println("MS levels: " + byLevel);

            var chroms = reader.chromatograms();
            System.out.println("Chromatograms: " + chroms.size());
            for (Chromatogram c : chroms) {
                System.out.printf("  - %s (%s) %d points%n", c.id(), c.typeCurie(), c.size());
            }

            System.out.println("First " + Math.min(max, reader.size()) + " spectra:");
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
                System.out.println(line);
            }
        }
    }
}
