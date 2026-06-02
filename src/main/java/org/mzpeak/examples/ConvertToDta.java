package org.mzpeak.examples;

import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Example: convert MSn spectra to classic Sequest {@code .dta} files (one per spectrum) — demonstrates reading.
 *
 * <pre>{@code
 *   ConvertToDta <in.mzpeak> <outDir> [--ms-level 2] [--rt-min M] [--rt-max M] [--default-charge 2]
 * }</pre>
 *
 * Each {@code .dta} file's first line is {@code <MH+> <charge>} (the singly-protonated precursor mass and
 * charge); subsequent lines are {@code <m/z> <intensity>}. When a precursor charge is missing, the value of
 * {@code --default-charge} is used. Files are named {@code <base>.<scan>.<scan>.<charge>.dta}.
 */
public final class ConvertToDta {

    private static final double PROTON = 1.007276466812;
    private static final Pattern SCAN = Pattern.compile("scan=(\\d+)");

    private ConvertToDta() {
    }

    /** @return number of .dta files written. */
    public static int run(Path input, Path outputDir, SpectrumFilter filter, int defaultCharge) {
        String base = stripExtension(input.getFileName().toString());
        int written = 0;
        try {
            Files.createDirectories(outputDir);
            try (MzPeakReader reader = MzPeakReader.open(input)) {
                for (Spectrum s : reader) {
                    SpectrumDescription d = s.description();
                    if (d.msLevel() < 2 || !filter.accept(d)) {
                        continue;
                    }
                    Precursor precursor = d.primaryPrecursor();
                    SelectedIon ion = precursor == null ? null : precursor.primaryIon();
                    if (ion == null) {
                        continue;
                    }
                    int charge = ion.charge() != null ? ion.charge() : defaultCharge;
                    double mhPlus = ion.mz() * charge - (charge - 1) * PROTON;
                    int scan = scanNumber(d);
                    Path file = outputDir.resolve(base + "." + scan + "." + scan + "." + charge + ".dta");
                    writeDta(file, mhPlus, charge, s);
                    written++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing DTA files to " + outputDir, e);
        }
        return written;
    }

    private static void writeDta(Path file, double mhPlus, int charge, Spectrum s) throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(String.format(Locale.US, "%.5f %d%n", mhPlus, charge));
            if (!s.peaks().isEmpty()) {
                for (CentroidPeak p : s.peaks()) {
                    w.write(String.format(Locale.US, "%.5f %.4f%n", p.mz(), p.intensity()));
                }
            } else {
                double[] mz = s.mz();
                double[] intensity = s.intensity();
                for (int i = 0; i < mz.length; i++) {
                    w.write(String.format(Locale.US, "%.5f %.4f%n", mz[i], intensity[i]));
                }
            }
        }
    }

    private static int scanNumber(SpectrumDescription d) {
        if (d.id() != null) {
            Matcher m = SCAN.matcher(d.id());
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                    // fall through to index
                }
            }
        }
        return (int) d.index();
    }

    private static String stripExtension(String name) {
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    public static void main(String[] args) {
        ExampleArgs a = new ExampleArgs(args);
        if (a.positional.size() < 2) {
            System.err.println("Usage: ConvertToDta <in.mzpeak> <outDir> "
                    + "[--ms-level 2] [--rt-min M] [--rt-max M] [--default-charge 2]");
            System.exit(2);
        }
        int n = run(Path.of(a.positional.get(0)), Path.of(a.positional.get(1)),
                SpectrumFilter.from(a), a.optInt("default-charge", 2));
        System.out.printf("Wrote %d .dta files to %s%n", n, a.positional.get(1));
    }
}
