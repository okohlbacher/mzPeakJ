package org.mzpeak.mzml;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.io.MzPeakWriter;
import org.mzpeak.model.Param;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.mzml.ModelAssert.Exclusion;
import org.mzpeak.mzml.ModelAssert.Model;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mzpeak.mzml.ModelAssert.Mode.STRICT;

/**
 * JZ-11, opt-in: the converter on the local mzPeak and mzML corpus (mzML design 12.6). Runs only with
 * {@code -Dmzpeak.mzml.corpus.dir=<dir>}, its own property: the weekly corpus workflow sets {@code mzpeak.corpus.dir}
 * to a corpus that holds none of these files, and the default build sets it to a directory that does not exist.
 * Each file is looked up by name anywhere below the directory (symbolic links followed); a missing one skips its own
 * case.
 * <ul>
 *   <li>A on the 7 corpus mzPeak files (LFQ_Astral DIA, CK1 prescan, default and delta, repro-centroid, repro-mixed,
 *       twin_mzml_lane), each in a child JVM ({@link LargeFileTest#child}) whose {@code -Xmx} follows design 7:
 *       the expected mzML size (the Base64 text of every array as 64-bit floats, the size without zlib, an upper
 *       bound) + 13 KB per spectrum + 1 GB. A file needing more than three quarters of this machine's memory is
 *       skipped. The child runs {@link MzMLConversion#toMzML} (zlib), {@link MzMLAssert#valid} and
 *       {@link MzMLConversion#toMzPeak} (defaults); a sample of 500 spectra (all of the smaller files) and every
 *       chromatogram then come back equal ({@link LargeFileTest#sampledEqual}).
 *   <li>B on three mzML whose mzPeak another converter made: tiny (vendored) vs repro-mixed.mzpeak,
 *       swath.api-sample-centroid.mzML vs repro-centroid.mzpeak, timsTOF_autoMSMS_Urine_50s_neg.mzML vs
 *       twin_mzml_lane.mzpeak. {@link MzMLReader}'s model M1 goes through {@link MzMLConversion#toMzPeak} and back
 *       through {@link MzMLConversion#toMzML} (zlib and without, each {@link MzMLAssert#valid}) to M3, which equals
 *       M1 strictly up to the exclusions of the case, each hiding a pinned count (tiny: A_26, its run start time
 *       without a zone, under UTC; timsTOF: {@link #A_29}). Our intermediate mzPeak is then compared with the other
 *       converter's, the oracle: ids, MS levels, polarity, continuity and retention times equal (a spectrum without
 *       scan start time has NaN in ours and 0.0 in the oracle: tiny's scan=21); profile spectra after the zero-run
 *       stripping of upstream {@code find_where_not_zero_run} ({@link MzMLRustOracleTest#notInZeroRun}), which the
 *       oracle applies; intensities exact; m/z within 1e-9 relative (measured: 2.3e-10, swath and timsTOF) and within
 *       5e-5 at the zero flanks the oracle stores without m/z (measured: 3.0e-7, timsTOF). Chromatograms are not
 *       compared: the oracle derives its TIC and BPC from the spectra (repro-mixed: 3 points each, tiny's TIC has
 *       15).
 * </ul>
 * The warnings of every conversion are printed, not pinned: the corpus files carry losses the fixtures do not (LFQ:
 * A-21, A-22, A-26, A-27, A-29; CK1: A-3, A-18, A-22).
 */
class MzMLCorpusTest {

    private static final String PROPERTY = "mzpeak.mzml.corpus.dir";
    private static final long GB = 1L << 30;
    private static final int SAMPLES = 500;

    /** The 7 corpus mzPeak files of direction A. */
    private static final List<String> MZPEAK = List.of("repro-mixed.mzpeak", "repro-centroid.mzpeak",
            "twin_mzml_lane.mzpeak", "CK1.prescan.mzpeak", "CK1.default.mzpeak", "CK1.delta.mzpeak",
            "LFQ_Astral_DIA_15min_50ng_Human_01.mzpeak");
    private static final String SWATH = "swath.api-sample-centroid.mzML";
    private static final String TIMS_TOF = "timsTOF_autoMSMS_Urine_50s_neg.mzML";

    private static Map<String, Path> found;

    @TempDir
    Path dir;

    private TimeZone zone;

    /**
     * Sets the default time zone UTC, before anything can skip the case, so that {@link #restoreTimeZone} always
     * restores the one it replaced; then skips without the property or its directory and looks the files up once.
     */
    @BeforeEach
    void utcAndCorpusIsGiven() throws IOException {
        zone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        String property = System.getProperty(PROPERTY, "");
        assumeTrue(!property.isBlank(), "-D" + PROPERTY + "=<dir> not set");
        Path root = Path.of(property);
        assumeTrue(Files.isDirectory(root), root + " is not a directory");
        if (found != null) {
            return;
        }
        Set<String> names = new HashSet<>(MZPEAK);
        names.addAll(List.of(SWATH, TIMS_TOF));
        found = new LinkedHashMap<>();
        Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), 12, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attributes) {
                Path name = d.getFileName();
                if (name != null && names.contains(name.toString())) {
                    found.putIfAbsent(name.toString(), d);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attributes) {
                if (names.contains(f.getFileName().toString())) {
                    found.putIfAbsent(f.getFileName().toString(), f);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) {
                return FileVisitResult.CONTINUE; // unreadable entries and link cycles
            }
        });
        System.out.println("MzMLCorpusTest: found " + found);
    }

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(zone);
    }

    /** The corpus file {@code name}; skips the case when the corpus does not hold it. */
    private static Path corpus(String name) {
        Path p = found.get(name);
        assumeTrue(p != null, name + " not in the corpus");
        return p;
    }

    // ---- A -------------------------------------------------------------------------------------------------------

    static List<String> mzPeakFiles() {
        return MZPEAK;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mzPeakFiles")
    void directionAOnACorpusFile(String name) throws IOException, InterruptedException {
        Path input = corpus(name);
        long xmx = heap(input);
        long memory = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                .getTotalMemorySize();
        assumeTrue(xmx <= memory / 4 * 3, name + " needs -Xmx" + xmx / GB + "g; this machine has "
                + memory / GB + " GB");
        Path mzml = dir.resolve("a.mzML");
        Path back = dir.resolve("a.mzpeak");

        LargeFileTest.Report toMzML = LargeFileTest.child(dir, "toMzML", List.of("-Xmx" + xmx / GB + "g"),
                "toMzML", input.toString(), mzml.toString());
        long mzmlBytes = Files.size(mzml);
        LargeFileTest.Report toMzPeak = LargeFileTest.child(dir, "toMzPeak", List.of("-Xmx" + xmx / GB + "g"),
                "toMzPeak", mzml.toString(), back.toString());
        System.out.println("MzMLCorpusTest A " + name + ": mzML " + mzmlBytes + " bytes, -Xmx" + xmx / GB + "g");
        System.out.println("  toMzML:   " + toMzML);
        toMzML.warnings().stream().filter(w -> !w.startsWith("L-")).forEach(w -> System.out.println("    " + w));
        System.out.println("  toMzPeak: " + toMzPeak);
        toMzPeak.warnings().stream().filter(w -> !w.startsWith("L-")).forEach(w -> System.out.println("    " + w));

        try (MzPeakReader reader = MzPeakReader.open(input)) {
            assertThat(List.of(toMzML.spectra(), toMzML.wavelengthSpectra(), toMzML.chromatograms()))
                    .isEqualTo(List.of((long) reader.size(), (long) reader.wavelengthSpectra().size(),
                            (long) reader.chromatograms().size()));
        }
        assertThat(List.of(toMzPeak.spectra(), toMzPeak.wavelengthSpectra(), toMzPeak.chromatograms()))
                .isEqualTo(List.of(toMzML.spectra(), toMzML.wavelengthSpectra(), toMzML.chromatograms()));
        LargeFileTest.sampledEqual(input, back, SAMPLES);
    }

    /**
     * The {@code -Xmx} design 7 gives {@code mzpeak} for direction A, in whole GB: the Base64 text of every array as
     * 64-bit floats (points from the metadata, arrays per spectrum from the first spectrum, an upper bound of the
     * mzML's array text) + 13 KB per spectrum + 1 GB.
     */
    private static long heap(Path mzpeak) {
        try (MzPeakReader reader = MzPeakReader.open(mzpeak)) {
            long points = 0;
            for (SpectrumDescription d : reader.metadata()) {
                points += Math.max(d.numberOfDataPoints(), d.numberOfPeaks());
            }
            int arrays = reader.size() == 0 ? 2 : 2 + reader.getSpectrumAt(0).arrays().size();
            long base64 = points * arrays * 8 / 3 * 4;
            long bytes = base64 + 13L * 1024 * reader.size() + GB;
            return (bytes + GB - 1) / GB * GB;
        }
    }

    // ---- B -------------------------------------------------------------------------------------------------------

    /**
     * A-29 (design 6.2): a String, or a param's name or String value, with characters outside ASCII in expected that
     * comes back with one U+FFFD per UTF-8 byte of each such character, because the mzML has no encoding declaration
     * and jmzML reads it as US-ASCII (L-5): timsTOF_autoMSMS_Urine_50s_neg.mzML, 7 chromatogram ids and params
     * ('BPC,\u00b1MS', 'Flow - [\u00b5l/min]', 'Tray temperature - [\u00b0C]', ...).
     */
    static final Exclusion A_29 = new Exclusion("A_29", "A-29: non-ASCII text read back as U+FFFD per UTF-8 byte",
            d -> readBackWithoutEncoding(d.expected(), d.actual()));

    private static boolean readBackWithoutEncoding(Object expected, Object actual) {
        if (expected instanceof String e && actual instanceof String a) {
            return !e.equals(a) && asciiReadBack(e).equals(a);
        }
        if (expected instanceof List<?> e && actual instanceof List<?> a && e.size() == a.size() && !e.equals(a)) {
            for (int i = 0; i < e.size(); i++) {
                if (!(e.get(i) instanceof Param p && a.get(i) instanceof Param q)
                        || !Objects.equals(p.accession(), q.accession()) || !Objects.equals(p.unit(), q.unit())
                        || !Objects.equals(asciiReadBack(p.name()), q.name())
                        || !(p.value() instanceof String v ? asciiReadBack(v).equals(q.value())
                        : Objects.equals(p.value(), q.value()))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** {@code s} with each character outside ASCII replaced by one U+FFFD per byte of its UTF-8 encoding. */
    private static String asciiReadBack(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        s.codePoints().forEach(c -> {
            if (c < 0x80) {
                b.append((char) c);
            } else {
                b.append("\ufffd".repeat(new String(Character.toChars(c)).getBytes(StandardCharsets.UTF_8).length));
            }
        });
        return b.toString();
    }

    /** Each B case: the mzML (a corpus name, or tiny), its oracle and the exclusions M1 vs M3 needs, with counts. */
    static Stream<Arguments> directionB() {
        return Stream.of(arguments(MzMLFixtures.TINY, "repro-mixed.mzpeak", Map.of(ModelAssert.A_26, 1)),
                arguments(SWATH, "repro-centroid.mzpeak", Map.of()),
                arguments(TIMS_TOF, "twin_mzml_lane.mzpeak", Map.of(A_29, 7)));
    }

    /**
     * B, then the oracle. Retention time: a spectrum whose scan gives no start time has NaN in the model (design 5.4)
     * and 0.0 in the oracle (tiny's scan=21); every other one is bit-equal.
     */
    @ParameterizedTest(name = "{0} vs {1}")
    @MethodSource("directionB")
    void directionBAgreesWithTheOtherConverter(String mzmlName, String oracleName, Map<Exclusion, Integer> hidden)
            throws IOException {
        Path oracle = corpus(oracleName);
        Path source = mzmlName.equals(MzMLFixtures.TINY)
                ? MzMLFixtures.extract(MzMLFixtures.TINY, Files.createDirectories(dir.resolve("source")))
                : corpus(mzmlName);
        Model m1 = MzMLFixpointTest.mzmlModel(source);
        Path ours = dir.resolve("b.mzpeak");
        MzMLConversion.Result toMzPeak = MzMLConversion.toMzPeak(source, ours, false, MzPeakWriter.Options.defaults());
        System.out.println("MzMLCorpusTest B " + mzmlName + ": " + toMzPeak.spectra() + " spectra");
        toMzPeak.warnings().stream().filter(w -> !w.startsWith("L-")).forEach(w -> System.out.println("    " + w));
        Map<String, Integer> counts = new LinkedHashMap<>();
        hidden.forEach((exclusion, n) -> counts.put(exclusion.id(), n));
        for (boolean zlib : List.of(true, false)) {
            Path mzml = dir.resolve("b-" + zlib + ".mzML");
            MzMLConversion.Result toMzML = MzMLConversion.toMzML(ours, mzml, zlib);
            toMzML.warnings().stream().filter(w -> !w.startsWith("L-")).forEach(w -> System.out.println("    " + w));
            MzMLAssert.valid(mzml);
            ModelAssert.Result result = ModelAssert.assertEqual(mzmlName + " B, zlib " + zlib, m1,
                    MzMLFixpointTest.mzmlModel(mzml), STRICT, hidden.keySet().toArray(Exclusion[]::new));
            assertThat(result.hidden()).isEqualTo(counts);
        }

        List<Spectrum> written = spectra(ours);
        List<Spectrum> reference = spectra(oracle);
        assertThat(written).hasSameSizeAs(reference).isNotEmpty();
        for (int i = 0; i < written.size(); i++) {
            SpectrumDescription d = written.get(i).description();
            SpectrumDescription e = reference.get(i).description();
            String what = "spectrum " + i + " (" + d.id() + ")";
            assertThat(Arrays.asList(d.id(), d.msLevel(), d.polarity(), d.signalContinuity())).as(what)
                    .isEqualTo(Arrays.asList(e.id(), e.msLevel(), e.polarity(), e.signalContinuity()));
            assertThat(Double.doubleToLongBits(e.retentionTime())).as(what + " retention time")
                    .isEqualTo(Double.doubleToLongBits(Double.isNaN(d.retentionTime()) ? 0.0 : d.retentionTime()));
            strippedAlike(what, written.get(i), reference.get(i));
        }
    }

    /**
     * {@code stored} holds the points of {@code full} that {@link MzMLRustOracleTest#notInZeroRun} keeps:
     * intensities exact, m/z within 1e-9 relative where the intensity is not zero and within 5e-5 where it is.
     */
    private static void strippedAlike(String what, Spectrum full, Spectrum stored) {
        int[] kept = MzMLRustOracleTest.notInZeroRun(full);
        assertThat(stored.mz()).as(what + " points").hasSize(kept.length);
        assertThat(stored.intensity()).as(what + " points").hasSize(kept.length);
        for (int k = 0; k < kept.length; k++) {
            int j = kept[k];
            assertThat(stored.intensity()[k]).as(what + " intensity " + k).isEqualTo(full.intensity()[j]);
            double difference = Math.abs(stored.mz()[k] - full.mz()[j]);
            if (full.intensity()[j] != 0) {
                assertThat(difference).as(what + " m/z " + k).isLessThanOrEqualTo(1e-9 * Math.abs(full.mz()[j]));
            } else {
                assertThat(difference).as(what + " m/z " + k + " (zero flank)").isLessThanOrEqualTo(5e-5);
            }
        }
    }

    private static List<Spectrum> spectra(Path mzpeak) {
        List<Spectrum> spectra = new ArrayList<>();
        try (MzPeakReader reader = MzPeakReader.open(mzpeak)) {
            reader.forEach(spectra::add);
        }
        return spectra;
    }
}
