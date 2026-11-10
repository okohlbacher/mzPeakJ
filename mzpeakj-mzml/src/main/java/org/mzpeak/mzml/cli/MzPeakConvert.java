package org.mzpeak.mzml.cli;

import org.mzpeak.io.MzPeakException;
import org.mzpeak.io.MzPeakWriter;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.mzml.MzMLConversion;
import org.mzpeak.mzml.MzMLReader;
import org.mzpeak.mzml.MzMLWriter;

import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Converts mzML to mzPeak and mzPeak to mzML from the command line (mzML design 8.2, 9), through
 * {@link MzMLConversion}:
 *
 * <pre>{@code
 *   java [-Xmx...] -cp mzpeakj-mzml-<version>-all.jar org.mzpeak.mzml.cli.MzPeakConvert \
 *       [--help] [options] <input> <output>
 * }</pre>
 *
 * The direction comes from the names, case-insensitive: an input ending in {@code .mzML} is written to an output
 * ending in {@code .mzpeak}, a ZIP archive or with {@code --directory} an unpacked directory, with the
 * {@link MzPeakWriter.Options} that {@code --layout split|packed}, {@code --numpress-slof} or {@code --numpress-pic}
 * and {@code --row-group-size <bytes>} set; an input ending in {@code .mzpeak}, or a directory, is written to an
 * output ending in {@code .mzML}, its arrays zlib-compressed unless {@code --no-zlib}. {@code --help} prints the
 * options, the limit lines {@link MzMLReader#LIMITS} and {@link MzMLWriter#LIMITS} and the memory rule of design 7.
 *
 * <p>Exit codes. 0: the output was written; stdout gets {@code wrote <output>: N spectra, M wavelength spectra,
 * K chromatograms}, stderr a {@code warning: } line for each warning of the conversion (its limit lines first).
 * 1: the conversion failed ({@link MzPeakException}, {@link UncheckedIOException}); stderr gets {@code error: } and
 * the message, and the conversion leaves no output. 2: a usage error; stderr gets {@code error: } with the problem and
 * the usage line, and nothing is written: an unknown option, an option given twice or without its value, a missing or
 * extra argument, an option that does not apply to the direction, both Numpress options, a layout other than
 * {@code split} or {@code packed}, a row-group size that is no positive number, an input ending in {@code .mzML.gz}
 * (jmzML 1.7.11 cannot read gzip), an input of another type, an output with the wrong extension, an existing output.
 *
 * <p>{@link #main} prints the library's own warnings (a re-sorted axis, for one) as one {@code warning: } line each:
 * it sets {@code java.util.logging.SimpleFormatter.format} unless it is set. jmzML's writer sets the JVM-wide system
 * property {@code javax.xml.stream.XMLOutputFactory} (design 5.11), which ends with the CLI's JVM.
 */
public final class MzPeakConvert {

    /** The usage line, the first of the help and the second of every usage error. */
    static final String USAGE = "usage: java [-Xmx...] -cp mzpeakj-mzml-<version>-all.jar"
            + " org.mzpeak.mzml.cli.MzPeakConvert [--help] [options] <input> <output>";
    /** The memory rule of design 7 for mzML output, which holds the whole document in memory (L-9). */
    private static final String MEMORY = "-Xmx >= expected mzML size + 13 KB per spectrum + 1 GB";
    private static final String GZIP = "gunzip it first: jmzML 1.7.11 cannot read gzip-compressed mzML";

    private static final String FORMAT = "java.util.logging.SimpleFormatter.format";
    private static final String NO_ZLIB = "--no-zlib";
    private static final String DIRECTORY = "--directory";
    private static final String LAYOUT = "--layout";
    private static final String SLOF = "--numpress-slof";
    private static final String PIC = "--numpress-pic";
    private static final String ROW_GROUP_SIZE = "--row-group-size";
    private static final List<String> FLAGS = List.of(NO_ZLIB, DIRECTORY, SLOF, PIC);
    private static final List<String> VALUED = List.of(LAYOUT, ROW_GROUP_SIZE);
    private static final String HELP = """
            Converts mzML to mzPeak and mzPeak to mzML; the direction comes from the names (case-insensitive):
              <input>.mzML                         -> <output>.mzpeak   ZIP archive (--directory: unpacked directory)
              <input>.mzpeak (ZIP) or a directory  -> <output>.mzML     plain mzML, 64-bit float arrays
            An input ending in .mzML.gz is not read: gunzip it first. The output must not exist.

            mzML output:
              --no-zlib                   no zlib compression of the arrays (default: zlib)
            mzPeak output:
              --directory                 write an unpacked directory instead of a ZIP archive
              --layout split|packed       metadata layout (default: split)
              --numpress-slof             spectra in Numpress chunks: linear m/z, SLOF intensity
              --numpress-pic              spectra in Numpress chunks: linear m/z, PIC intensity
              --row-group-size <bytes>    Parquet row-group size
              --help                      this text

            Exit codes: 0 written (warnings on stderr), 1 conversion failed (no output left),
            2 usage error (nothing written).

            Limits of jmzML 1.7.11 as is, printed as warnings on every conversion in their direction.
            mzML input:
            """;

    private MzPeakConvert() {
    }

    /** Runs {@link #run} on the standard streams and exits with its code unless that is 0. */
    public static void main(String[] args) {
        if (System.getProperty(FORMAT) == null) {
            System.setProperty(FORMAT, "warning: %5$s%n");
        }
        exit(run(args, System.out, System.err), System::exit);
    }

    /** Hands a non-zero {@code code} to {@code exiter} (in {@link #main}, {@link System#exit}). */
    static void exit(int code, IntConsumer exiter) {
        if (code != 0) {
            exiter.accept(code);
        }
    }

    /** Converts as {@code args} ask, prints to {@code out} and {@code err} as the class comment says; the exit code. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (Arrays.asList(args).contains("--help")) {
            out.println(USAGE);
            out.println();
            HELP.lines().forEach(out::println);
            MzMLReader.LIMITS.forEach(limit -> out.println("  " + limit));
            out.println("mzML output:");
            MzMLWriter.LIMITS.forEach(limit -> out.println("  " + limit));
            out.println();
            out.println("Memory: mzML output is held in memory until it is written; run with");
            out.println("  " + MEMORY);
            return 0;
        }
        Command command;
        try {
            command = Command.parse(args);
        } catch (UsageError e) {
            err.println("error: " + e.getMessage());
            err.println(USAGE);
            return 2;
        }
        MzMLConversion.Result result;
        try {
            result = command.toMzML()
                    ? MzMLConversion.toMzML(command.input(), command.output(), command.zlib())
                    : MzMLConversion.toMzPeak(command.input(), command.output(), command.directory(),
                    command.options());
        } catch (MzPeakException | UncheckedIOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
        result.warnings().forEach(warning -> err.println("warning: " + warning));
        out.println("wrote " + command.output() + ": " + result.spectra() + " spectra, " + result.wavelengthSpectra()
                + " wavelength spectra, " + result.chromatograms() + " chromatograms");
        return 0;
    }

    /** A usage error; its message says what is wrong. */
    private static final class UsageError extends Exception {
        private static final long serialVersionUID = 1L;

        UsageError(String message) {
            super(message);
        }
    }

    /** What the arguments ask for; {@code options} is null for mzML output. */
    private record Command(Path input, Path output, boolean toMzML, boolean zlib, boolean directory,
                           MzPeakWriter.Options options) {

        static Command parse(String[] args) throws UsageError {
            Map<String, String> given = new LinkedHashMap<>();
            List<String> names = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("-")) {
                    names.add(arg);
                    continue;
                }
                if (!FLAGS.contains(arg) && !VALUED.contains(arg)) {
                    throw new UsageError("unknown option " + arg);
                }
                if (VALUED.contains(arg) && i + 1 == args.length) {
                    throw new UsageError(arg + " needs a value");
                }
                if (given.put(arg, VALUED.contains(arg) ? args[++i] : "") != null) {
                    throw new UsageError(arg + " is given twice");
                }
            }
            if (names.size() != 2) {
                throw new UsageError(names.size() > 2 ? "extra argument " + names.get(2)
                        : names.isEmpty() ? "missing <input> and <output>" : "missing <output>");
            }
            Path input = Path.of(names.get(0));
            Path output = Path.of(names.get(1));
            String in = input.toString().toLowerCase(Locale.ROOT);
            if (in.endsWith(".mzml.gz")) {
                throw new UsageError(input + ": " + GZIP);
            }
            boolean toMzML = !in.endsWith(".mzml");
            if (toMzML && !in.endsWith(".mzpeak") && !Files.isDirectory(input)) {
                throw new UsageError(input + ": unknown input type: expected a name ending in .mzML or .mzpeak,"
                        + " or a directory");
            }
            for (String option : given.keySet()) {
                if (option.equals(NO_ZLIB) != toMzML) {
                    throw new UsageError(option + " does not apply to " + (toMzML ? "mzML" : "mzPeak") + " output");
                }
            }
            if (!output.toString().toLowerCase(Locale.ROOT).endsWith(toMzML ? ".mzml" : ".mzpeak")) {
                throw new UsageError(output + ": the output of " + (toMzML ? "an mzPeak input must end in .mzML"
                        : "an mzML input must end in .mzpeak"));
            }
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                throw new UsageError(output + " exists: remove it first");
            }
            return new Command(input, output, toMzML, !given.containsKey(NO_ZLIB), given.containsKey(DIRECTORY),
                    toMzML ? null : options(given));
        }

        /** The mzPeak writer options of {@code given}. */
        private static MzPeakWriter.Options options(Map<String, String> given) throws UsageError {
            if (given.containsKey(SLOF) && given.containsKey(PIC)) {
                throw new UsageError(SLOF + " and " + PIC + " exclude each other");
            }
            MzPeakWriter.Options options = MzPeakWriter.Options.defaults();
            String layout = given.getOrDefault(LAYOUT, "split");
            if (layout.equals("packed")) {
                options = options.withMetadataLayout(MetadataLayout.PACKED);
            } else if (!layout.equals("split")) {
                throw new UsageError(LAYOUT + " takes split or packed, not " + layout);
            }
            if (given.containsKey(SLOF) || given.containsKey(PIC)) {
                options = options.withSignalEncoding(SignalEncoding.NUMPRESS).withNumpressIntensity(
                        given.containsKey(PIC) ? NumpressIntensityEncoding.PIC : NumpressIntensityEncoding.SLOF);
            }
            String size = given.get(ROW_GROUP_SIZE);
            if (size != null) {
                long bytes;
                try {
                    bytes = Long.parseLong(size);
                } catch (NumberFormatException e) {
                    bytes = 0;
                }
                if (bytes <= 0) {
                    throw new UsageError(ROW_GROUP_SIZE + " takes a positive number of bytes, not " + size);
                }
                options = options.withRowGroupSize(bytes);
            }
            return options;
        }
    }
}
