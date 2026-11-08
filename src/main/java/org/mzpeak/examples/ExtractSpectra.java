package org.mzpeak.examples;

import org.mzpeak.io.MzPeakReader;
import org.mzpeak.io.MzPeakWriter;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Example: extract a filtered subset of spectra into a new mzPeak file — demonstrates reading AND writing.
 *
 * <pre>{@code
 *   ExtractSpectra <in.mzpeak> <out.mzpeak> [--ms-level 2] [--rt-min 5] [--rt-max 30]
 * }</pre>
 *
 * If {@code <out>} ends in {@code .mzpeak} a single-file ZIP is written, otherwise an unpacked directory.
 */
public final class ExtractSpectra {

    private ExtractSpectra() {
    }

    /** @return number of spectra written. */
    public static int run(Path input, Path output, SpectrumFilter filter) {
        List<Spectrum> kept = new ArrayList<>();
        List<Chromatogram> chromatograms;
        try (MzPeakReader reader = MzPeakReader.open(input)) {
            for (Spectrum s : reader) {
                if (filter.accept(s.description())) {
                    kept.add(s);
                }
            }
            chromatograms = new ArrayList<>(reader.chromatograms());
        }
        if (output.getFileName().toString().endsWith(".mzpeak")) {
            MzPeakWriter.writeArchive(output, kept, chromatograms);
        } else {
            MzPeakWriter.writeDirectory(output, kept, chromatograms);
        }
        return kept.size();
    }

    public static void main(String[] args) {
        ExampleArgs.exit(run(args, System.out, System.err), System::exit);
    }

    /** Writes the filtered copy, prints a summary to {@code out} and returns 0, or the usage to {@code err} and 2. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        ExampleArgs a = new ExampleArgs(args);
        if (a.positional.size() < 2) {
            err.println("Usage: ExtractSpectra <in.mzpeak> <out.mzpeak> "
                    + "[--ms-level 1,2] [--rt-min M] [--rt-max M]");
            return 2;
        }
        SpectrumFilter filter = SpectrumFilter.from(a);
        int n = run(Path.of(a.positional.get(0)), Path.of(a.positional.get(1)), filter);
        out.printf("Wrote %d spectra (%s) to %s%n", n, filter.describe(), a.positional.get(1));
        return 0;
    }
}
