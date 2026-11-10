package org.mzpeak.mzml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.io.MzPeakWriter;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JZ-11, opt-in: direction A on a large mzPeak file (mzML design 7, 12.6, Q59). Runs only with
 * {@code -Dmzpeak.large.mzpeak=<LFQ_Astral_DIA_15min_50ng_Human_01.mzpeak>}; without it the test is skipped. In two
 * child JVMs: {@link MzMLConversion#toMzML} (zlib) with {@code -Xmx} from {@code -Dmzpeak.large.xmx} (default
 * {@code 20g}; design 7 estimated 18g for 3,300 points per spectrum), then {@link MzMLConversion#toMzPeak} (defaults)
 * with {@code -Xmx2g}. The mzML passes {@link MzMLAssert#valid}; both children convert as many spectra as the input
 * holds (295,811 for LFQ_Astral_DIA_15min_50ng_Human_01); a stratified sample of spectra and every chromatogram come
 * back equal ({@link #sampledEqual}). Each child's peak heap is reported on stdout: the highest used heap of a
 * {@code MemoryMXBean} poll every 50 ms, and the highest heap before a collection in its GC log. The children do not
 * get the build's coverage agent: opt-in tests do not count for coverage.
 */
class LargeFileTest {

    private static final String PROPERTY = "mzpeak.large.mzpeak";
    private static final String XMX = "mzpeak.large.xmx";
    private static final String LFQ_01 = "LFQ_Astral_DIA_15min_50ng_Human_01.mzpeak";
    private static final int LFQ_01_SPECTRA = 295_811;
    private static final long TIMEOUT_HOURS = 6;
    private static final int SAMPLES = 1000;

    @TempDir
    Path dir;

    @Test
    void largeFileGoesThroughDirectionAInTwoChildJvms() throws IOException, InterruptedException {
        String property = System.getProperty(PROPERTY, "");
        assumeTrue(!property.isBlank(), "-D" + PROPERTY + "=<LFQ_Astral...mzpeak> not set");
        Path input = Path.of(property);
        assertThat(input).as("-D%s", PROPERTY).exists();
        String xmx = System.getProperty(XMX, "20g");
        Path mzml = dir.resolve("large.mzML");
        Path back = dir.resolve("large.mzpeak");

        Report toMzML = child(dir, "toMzML", List.of("-Xmx" + xmx), "toMzML", input.toString(), mzml.toString());
        Report toMzPeak = child(dir, "toMzPeak", List.of("-Xmx2g"), "toMzPeak", mzml.toString(), back.toString());
        System.out.println("LargeFileTest " + input.getFileName() + ": mzML " + Files.size(mzml) + " bytes");
        System.out.println("  toMzML   -Xmx" + xmx + ": " + toMzML);
        System.out.println("  toMzPeak -Xmx2g: " + toMzPeak);

        int size;
        try (MzPeakReader reader = MzPeakReader.open(input)) {
            size = reader.size();
        }
        if (input.getFileName().toString().equals(LFQ_01)) {
            assertThat(size).isEqualTo(LFQ_01_SPECTRA);
        }
        assertThat(toMzML.spectra()).isEqualTo(size);
        assertThat(toMzPeak.spectra()).isEqualTo(size);
        sampledEqual(input, back, SAMPLES);
    }

    // ---- shared with MzMLCorpusTest ------------------------------------------------------------------------------

    /** What a child reports: its counts, its seconds, its peak heaps (poll and GC log, MB) and its warnings. */
    record Report(long spectra, long wavelengthSpectra, long chromatograms, double seconds, long polledPeakMb,
                  long gcPeakMb, List<String> warnings) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%d spectra, %d wavelength spectra, %d chromatograms in %.1f s;"
                    + " peak heap %d MB (MemoryMXBean poll), %d MB (GC log)", spectra, wavelengthSpectra,
                    chromatograms, seconds, polledPeakMb, gcPeakMb);
        }
    }

    private static final Pattern RESULT = Pattern.compile(
            "(?m)^result (\\d+) (\\d+) (\\d+) ([0-9.]+) (\\d+)$");
    private static final Pattern GC_BEFORE = Pattern.compile("(\\d+)M->\\d+M\\(\\d+M\\)");

    /**
     * Runs {@link Child} with {@code args} in a child JVM with this JVM's class path, {@code --enable-native-access}
     * and {@code jvmOptions}, writing its GC log, stdout and stderr to {@code dir}; fails unless it exits with 0.
     */
    static Report child(Path dir, String name, List<String> jvmOptions, String... args)
            throws IOException, InterruptedException {
        Path gc = dir.resolve(name + "-gc.log");
        Path stdout = dir.resolve(name + "-stdout.txt");
        Path stderr = dir.resolve(name + "-stderr.txt");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String option : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (option.startsWith("--enable-native-access=")) {
                command.add(option);
            }
        }
        command.addAll(jvmOptions);
        command.add("-Xlog:gc:file=" + gc);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Child.class.getName());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile())
                .start();
        if (!process.waitFor(TIMEOUT_HOURS, TimeUnit.HOURS)) {
            process.destroyForcibly();
            throw new IllegalStateException("timed out after " + TIMEOUT_HOURS + " h: " + command);
        }
        String out = Files.readString(stdout, StandardCharsets.UTF_8);
        String err = Files.readString(stderr, StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as("%s%n%s%n%s", command, out, err).isZero();
        Matcher m = RESULT.matcher(out);
        assertThat(m.find()).as(out).isTrue();
        long gcPeak = 0;
        Matcher g = GC_BEFORE.matcher(Files.readString(gc, StandardCharsets.UTF_8));
        while (g.find()) {
            gcPeak = Math.max(gcPeak, Long.parseLong(g.group(1)));
        }
        List<String> warnings = out.lines().filter(l -> l.startsWith("warning ")).map(l -> l.substring(8)).toList();
        return new Report(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3)),
                Double.parseDouble(m.group(4)), Long.parseLong(m.group(5)), gcPeak, warnings);
    }

    /**
     * {@code expected} and {@code actual} hold as many spectra, wavelength spectra and chromatograms; the spectra at
     * {@code samples} ordinals spread evenly over the file (all when there are fewer, always the first and the last;
     * read in ascending order, so that samples in one row-group run share its decoded run) agree in id, MS level,
     * polarity and continuity, and bit-exactly in m/z, intensity and every extra array; the retention time of
     * {@code actual} is that of {@code expected} or, A-9 (design 6.2: mzML holds no retention time besides the scan
     * start time), {@code expected}'s first scan start time; every chromatogram and wavelength spectrum agrees in id
     * and bit-exactly in its arrays. Ids agree by {@link #sameId}.
     */
    static void sampledEqual(Path expected, Path actual, int samples) {
        try (MzPeakReader e = MzPeakReader.open(expected); MzPeakReader a = MzPeakReader.open(actual)) {
            assertThat(a.size()).as("spectra").isEqualTo(e.size());
            TreeSet<Integer> ordinals = new TreeSet<>();
            int n = e.size();
            for (int i = 0; i < Math.min(samples, n); i++) {
                ordinals.add(n <= samples ? i : (int) ((long) i * (n - 1) / (samples - 1)));
            }
            for (int i : ordinals) {
                Spectrum x = e.getSpectrumAt(i);
                Spectrum y = a.getSpectrumAt(i);
                SpectrumDescription d = x.description();
                SpectrumDescription f = y.description();
                String what = "spectrum " + i + " (" + d.id() + ")";
                sameId(what, d.id(), f.id());
                assertThat(Arrays.asList(f.msLevel(), f.polarity(), f.signalContinuity())).as(what)
                        .isEqualTo(Arrays.asList(d.msLevel(), d.polarity(), d.signalContinuity()));
                Set<Long> times = new HashSet<>(List.of(Double.doubleToLongBits(d.retentionTime())));
                if (!d.scans().isEmpty()) {
                    times.add(Double.doubleToLongBits(d.scans().get(0).startTime()));
                }
                assertThat(times).as(what + " retention time").contains(Double.doubleToLongBits(f.retentionTime()));
                assertThat(bits(y.mz())).as(what + " m/z").isEqualTo(bits(x.mz()));
                assertThat(bits(y.intensity())).as(what + " intensity").isEqualTo(bits(x.intensity()));
                assertThat(y.arrays().keySet()).as(what + " arrays").isEqualTo(x.arrays().keySet());
                for (Map.Entry<String, double[]> extra : x.arrays().entrySet()) {
                    assertThat(bits(y.arrays().get(extra.getKey()))).as(what + " " + extra.getKey())
                            .isEqualTo(bits(extra.getValue()));
                }
            }
            List<Chromatogram> ec = e.chromatograms();
            List<Chromatogram> ac = a.chromatograms();
            assertThat(ac).as("chromatograms").hasSameSizeAs(ec);
            for (int i = 0; i < ec.size(); i++) {
                String what = "chromatogram " + i + " (" + ec.get(i).id() + ")";
                sameId(what, ec.get(i).id(), ac.get(i).id());
                assertThat(bits(ac.get(i).time())).as(what + " time").isEqualTo(bits(ec.get(i).time()));
                assertThat(bits(ac.get(i).intensity())).as(what + " intensity").isEqualTo(bits(ec.get(i).intensity()));
            }
            List<WavelengthSpectrum> ew = e.wavelengthSpectra();
            List<WavelengthSpectrum> aw = a.wavelengthSpectra();
            assertThat(aw).as("wavelength spectra").hasSameSizeAs(ew);
            for (int i = 0; i < ew.size(); i++) {
                String what = "wavelength spectrum " + i + " (" + ew.get(i).id() + ")";
                sameId(what, ew.get(i).id(), aw.get(i).id());
                assertThat(bits(aw.get(i).wavelength())).as(what).isEqualTo(bits(ew.get(i).wavelength()));
                assertThat(bits(aw.get(i).intensity())).as(what).isEqualTo(bits(ew.get(i).intensity()));
            }
        }
    }

    /**
     * {@code actual} is {@code expected}, or, A-3 (design 6.2), {@code index=<i>} for an {@code expected} id that is
     * null or empty: CK1.prescan's chromatogram, whose id is empty.
     */
    private static void sameId(String what, String expected, String actual) {
        if (expected == null || expected.isEmpty()) {
            assertThat(actual).as(what + " id (A-3)").matches("index=\\d+");
        } else {
            assertThat(actual).as(what + " id").isEqualTo(expected);
        }
    }

    private static long[] bits(double[] values) {
        long[] bits = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            bits[i] = Double.doubleToRawLongBits(values[i]);
        }
        return bits;
    }

    /**
     * The child JVM. {@code toMzML <mzPeak> <mzML>}: {@link MzMLConversion#toMzML} with zlib, then
     * {@link MzMLAssert#valid}; {@code toMzPeak <mzML> <mzPeak>}: {@link MzMLConversion#toMzPeak} with the defaults
     * to an archive. Prints each warning as {@code warning <line>}, then
     * {@code result <spectra> <wavelength spectra> <chromatograms> <seconds> <peak MB>}, the peak being the highest
     * used heap a {@code MemoryMXBean} poll every 50 ms saw during the conversion.
     */
    static final class Child {
        public static void main(String[] args) throws InterruptedException {
            AtomicLong peak = new AtomicLong();
            Thread poll = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                            Math::max);
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            poll.setDaemon(true);
            poll.start();
            long start = System.nanoTime();
            Path in = Path.of(args[1]);
            Path out = Path.of(args[2]);
            MzMLConversion.Result result = args[0].equals("toMzML") ? MzMLConversion.toMzML(in, out, true)
                    : MzMLConversion.toMzPeak(in, out, false, MzPeakWriter.Options.defaults());
            double seconds = (System.nanoTime() - start) / 1e9;
            poll.interrupt();
            poll.join();
            if (args[0].equals("toMzML")) {
                MzMLAssert.valid(out);
            }
            result.warnings().forEach(w -> System.out.println("warning " + w));
            System.out.printf(Locale.ROOT, "result %d %d %d %.1f %d%n", result.spectra(), result.wavelengthSpectra(),
                    result.chromatograms(), seconds, peak.get() >> 20);
        }
    }
}
