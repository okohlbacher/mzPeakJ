package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.Golden.Comparator;
import org.mzpeak.io.Golden.Digest;
import org.mzpeak.io.Golden.FullArray;
import org.mzpeak.io.Golden.Kind;
import org.mzpeak.io.Golden.Line;
import org.mzpeak.io.parquet.ParquetGroups;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The golden files of the HEAD Python reader ({@link Golden}) and their comparator. The files must hold the
 * values the unit FX-1b names and stay internally consistent; the comparator must catch arrays that keep
 * their per-array sums but lose their pairing (a rotated intensity, a reversed ion mobility), a single
 * flipped bit under a digest, an infinite value under any tolerance and a stored-point shift beyond the
 * §2.4 sum tolerance. Raw cells are read with parquet-java from the members the manifest names, so
 * the Python output is also checked against an independent Java read where the fixture stores plain points.
 */
class GoldenComparatorTest {

    private static final Golden.Tolerance T = Golden.Tolerance.DEFAULT;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ION_MOBILITY = "MS:1003006";

    static Stream<String> currentFixtures() {
        return Fixtures.CURRENT_NAMES.stream();
    }

    // ---- the golden files ------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void headerNamesTheVendoredReaderCommitAndPinnedVersions(String fixture) throws IOException {
        Golden g = Golden.load(fixture);
        String commit = Files.readAllLines(Fixtures.CURRENT.resolve("UPSTREAM_COMMIT.txt")).get(0).trim();
        Set<String> pins = requirementPins();

        assertThat(g.header())
                .containsEntry("reader", "HUPO-PSI/mzPeak python/mzpeak @ " + commit)
                .containsEntry("python", "CPython " + readmePythonVersion())
                .containsEntry("digest-threshold", "20000")
                .containsEntry("command", "generate_golden.py " + fixture + " --out golden --reader-commit " + commit);
        List<String> packages = List.of(g.header().get("packages").split(" "));
        assertThat(packages).map(p -> p.substring(0, p.indexOf("==")))
                .containsExactly("pyarrow", "numpy", "pandas", "scipy", "psims", "pynumpress");
        assertThat(pins).containsAll(packages);
    }

    private static final Pattern NAME_AND_VERSION = Pattern.compile("([A-Za-z0-9_.-]+)==([^\\s]+)");

    /** The {@code name==version} lines of {@code uv-requirements.txt}. */
    private static Set<String> requirementPins() throws IOException {
        return Files.readAllLines(Golden.DIR.resolve("uv-requirements.txt")).stream()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .collect(Collectors.toSet());
    }

    @Test
    void readmeListsExactlyThePinnedVersions() throws IOException {
        Map<String, String> pinned = new TreeMap<>();
        for (String pin : requirementPins()) {
            Matcher m = NAME_AND_VERSION.matcher(pin);
            assertThat(m.matches()).as("pin %s", pin).isTrue();
            pinned.put(m.group(1), m.group(2));
        }
        Map<String, String> listed = new TreeMap<>();
        Matcher row = Pattern.compile("(?m)^\\| ([a-z0-9-]+) \\| ([0-9][^ |]*) \\|$")
                .matcher(Files.readString(Golden.DIR.resolve("README.md")));
        while (row.find()) {
            listed.put(row.group(1), row.group(2));
        }
        assertThat(pinned).hasSize(12).containsKeys("pyarrow", "numpy", "pandas", "scipy", "psims", "pynumpress");
        assertThat(listed).isEqualTo(pinned);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void everyFullArrayMatchesItsSummaryLine(String fixture) {
        Golden g = Golden.load(fixture);
        assertThat(g.fullArrays()).isNotEmpty();
        for (FullArray f : g.fullArrays()) {
            Line line = g.line(f.kind(), f.key()).orElseThrow();
            assertThat(line.nFilled()).as("%s %s filled flags", f.kind(), f.key()).isEqualTo(countTrue(f.filled()));
            assertThat(Comparator.compare(line, f.x(), f.y(), T)).as("%s %s", f.kind(), f.key()).isEmpty();
            assertThat(Comparator.compareFull(f, f.x(), f.y(), T)).as("%s %s", f.kind(), f.key()).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void arraysCoverTheSelectedSpectraAndEveryChromatogram(String fixture) {
        Golden g = Golden.load(fixture);
        List<Line> spectra = g.lines(Kind.S);
        assertThat(spectra).isNotEmpty();
        for (Line s : List.of(spectra.get(0), spectra.get(spectra.size() - 1))) {
            assertStoredOnce(g, s);
        }
        g.lines(Kind.C).forEach(c -> assertStoredOnce(g, c));
        for (Digest d : g.digests()) {
            assertThat(d.n()).as("digest %s %s", d.kind(), d.key()).isGreaterThan(20_000);
        }
        for (FullArray f : g.fullArrays()) {
            assertThat(f.x().length).as("full %s %s", f.kind(), f.key()).isLessThanOrEqualTo(20_000);
        }
        if (g.digests().stream().anyMatch(d -> d.kind() == Kind.S)) {
            Line smallest = spectra.stream().min(java.util.Comparator.comparingLong(Line::n)).orElseThrow();
            assertThat(g.full(Kind.S, smallest.key())).as("smallest spectrum %s", smallest.key()).isPresent();
        }
    }

    private static void assertStoredOnce(Golden g, Line line) {
        boolean full = g.full(line.kind(), line.key()).isPresent();
        boolean digest = g.digest(line.kind(), line.key()).isPresent();
        assertThat(full ^ digest).as("%s %s stored in full xor as a digest", line.kind(), line.key()).isTrue();
    }

    @Test
    void smallHoldsTheExpectedLines() {
        Golden g = Golden.load("small.mzpeak");
        assertThat(g.lines(Kind.S)).hasSize(48);
        assertThat(g.line(Kind.S, "0").orElseThrow().n()).isEqualTo(13589);
        assertThat(g.lines(Kind.P)).hasSize(34);
        Line tic = g.line(Kind.C, "TIC").orElseThrow();
        assertThat(tic.n()).isEqualTo(48);
        assertThat(tic.sumY()).isCloseTo(196618504.523, within(1e-3));
        assertThat(g.line(Kind.X, "C:TIC", "ms level").orElseThrow().n()).isEqualTo(48);
        assertThat(g.line(Kind.R, "0").orElseThrow().n()).isEqualTo(11213);
    }

    @Test
    void smallChunkedStoresPeaksForTheProfileSpectra() {
        assertThat(Golden.load("small.chunked.mzpeak").line(Kind.P, "0").orElseThrow().n()).isEqualTo(1612);
    }

    @Test
    void hasUvHoldsSpectraChromatogramsAndWavelengthSpectra() {
        Golden g = Golden.load("has_uv.mzpeak");
        assertThat(g.lines(Kind.S)).hasSize(212);
        assertThat(g.lines(Kind.P)).hasSize(212);
        assertThat(g.lines(Kind.C)).extracting(Line::key).containsExactly("TIC", "DAD1 A: Sig=272,16 Ref=360,100");
        Line dad = g.line(Kind.C, "DAD1 A: Sig=272,16 Ref=360,100").orElseThrow();
        assertThat(dad.n()).isEqualTo(526);
        assertThat(dad.sumY()).isCloseTo(57.3587, within(1e-4));
        assertThat(g.lines(Kind.W)).hasSize(520);
        assertThat(g.lines(Kind.W).stream().mapToLong(Line::n).sum()).isEqualTo(49920);
    }

    @Test
    void diaPasefSpectrumZeroIsADigestWithItsIonMobility() {
        for (String fixture : List.of("diaPASEF.ref.mzpeak", "diaPASEF.grid.mzpeak")) {
            Golden g = Golden.load(fixture);
            assertThat(g.line(Kind.S, "0").orElseThrow().n()).as(fixture).isEqualTo(205921);
            assertThat(g.lines(Kind.X)).as(fixture).isNotEmpty().allMatch(x -> ION_MOBILITY.equals(x.arrayType()));
            assertThat(g.line(Kind.X, "S:0", ION_MOBILITY)).as(fixture).isPresent();
            Digest d = g.digest(Kind.S, "0").orElseThrow();
            assertThat(d.n()).isEqualTo(205921);
            assertThat(d.sha256Extras()).as(fixture).containsOnlyKeys(ION_MOBILITY);
            assertThat(g.full(Kind.S, "0")).as(fixture).isEmpty();
        }
    }

    @Test
    void exampleProcessedImagingSpectrumZero() {
        assertThat(Golden.load("Example_Processed.img.mzpeak").line(Kind.S, "0").orElseThrow().n()).isEqualTo(2837);
    }

    @Test
    void diaPasefGridSumsMatchTheReference() {
        Golden ref = Golden.load("diaPASEF.ref.mzpeak");
        Golden grid = Golden.load("diaPASEF.grid.mzpeak");
        List<Line> refLines = new ArrayList<>();
        for (Kind k : Kind.values()) {
            assertThat(grid.lines(k)).as("%s lines", k).hasSameSizeAs(ref.lines(k));
            refLines.addAll(ref.lines(k));
        }
        assertThat(refLines).hasSize(9 + 9 + 18 + 2);
        for (Line r : refLines) {
            Line g = grid.line(r.kind(), r.key(), r.arrayType()).orElseThrow();
            assertThat(g.n()).isEqualTo(r.n());
            assertThat(g.sumX()).as("%s %s sum_x", r.kind(), r.key()).isCloseTo(r.sumX(), within(1e-12 * r.sumX()));
            assertThat(g.sumY()).as("%s %s sum_y", r.kind(), r.key()).isCloseTo(r.sumY(), within(1e-12 * r.sumY()));
        }
    }

    @Test
    void goldenFilesStayUnderTheSizeCap() throws IOException {
        long total;
        try (Stream<Path> files = Files.list(Golden.DIR)) {
            total = files.filter(p -> p.getFileName().toString().endsWith(".tsv")).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).sum();
        }
        assertThat(total).isPositive().isLessThan(8_000_000L);
    }

    // ---- the comparator --------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void intensityRotatedByOneIsReportedAlthoughItsSumIsUnchanged(String fixture) {
        Golden g = Golden.load(fixture);
        int checked = 0;
        for (FullArray f : g.fullArrays()) {
            if (f.y().length < 2 || allEqual(f.y())) {
                continue;
            }
            Line line = g.line(f.kind(), f.key()).orElseThrow();
            double[] rotated = rotateByOne(f.y());

            List<String> mismatches = Comparator.compare(line, f.x(), rotated, T);
            assertThat(mismatches).as("%s %s", f.kind(), f.key())
                    .anyMatch(m -> m.contains(": sum_iy "))
                    .noneMatch(m -> m.contains(": sum_y "));
            assertThat(Comparator.compareFull(f, f.x(), rotated, T)).isNotEmpty();
            checked++;
        }
        assertThat(checked).isPositive();
    }

    @Test
    void reversedIonMobilityIsReportedAlthoughItsSumIsUnchanged() {
        Golden g = Golden.load("diaPASEF.ref.mzpeak");
        Map<String, double[]> stored = storedPoints("diaPASEF.ref.mzpeak", "peaks").get(2L);
        double[] mz = stored.get("MS:1000514");
        double[] mobility = stored.get(ION_MOBILITY);
        Line line = g.line(Kind.X, "S:2", ION_MOBILITY).orElseThrow();
        assertThat(mz).hasSize(960);

        assertThat(Comparator.compare(line, mz, mobility, T)).isEmpty();

        List<String> mismatches = Comparator.compare(line, mz, reversed(mobility), T);
        assertThat(mismatches)
                .anyMatch(m -> m.contains(": sum_xy "))
                .anyMatch(m -> m.contains(": sum_iy "))
                .noneMatch(m -> m.contains(": sum_y "));
    }

    @ParameterizedTest(name = "{0} Da")
    @ValueSource(doubles = {0.02, 0.05, 0.1, -0.05})
    void storedPointShiftInAFilledSpectrumIsReportedBySumX(double shift) {
        // §2.4: relative 1e-9 on the sums once filled points are involved, about 0.012 Da on this sum_x
        Golden g = Golden.load("small.mzpeak");
        Line line = g.line(Kind.S, "0").orElseThrow();
        FullArray full = g.full(Kind.S, "0").orElseThrow();
        int point = 10;
        assertThat(line.nFilled()).isEqualTo(2376);
        assertThat(full.filled()[point]).isFalse();
        assertThat(Comparator.compare(line, full.x(), full.y(), T)).isEmpty();

        double[] shifted = full.x().clone();
        shifted[point] += shift;
        assertThat(Comparator.compare(line, shifted, full.y(), T)).anyMatch(m -> m.contains(": sum_x "));
        assertThat(Comparator.compareFull(full, shifted, full.y(), T))
                .singleElement().asString().contains("point " + point);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(doubles = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void infiniteMzIsReported(double infinity) {
        Golden g = Golden.load("small.mzpeak");
        Line line = g.line(Kind.S, "2").orElseThrow();
        FullArray full = g.full(Kind.S, "2").orElseThrow();
        int point = full.x().length / 2;
        assertThat(line.nFilled()).isZero();

        double[] x = full.x().clone();
        x[point] = infinity;
        assertThat(Comparator.compare(line, x, full.y(), T))
                .anyMatch(m -> m.contains(": sum_x "))
                .anyMatch(m -> m.contains(": sum_xy "))
                .anyMatch(m -> m.contains(": sum_ix "));
        assertThat(Comparator.compareFull(full, x, full.y(), T)).singleElement().asString().contains("point " + point);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(doubles = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void infiniteIntensityIsReportedUnderANumpressTolerance(double infinity) {
        Golden.Tolerance numpress = new Golden.Tolerance(T.filledAbsDa(), T.relExact(), T.relFilled(), 1e-3);
        Golden g = Golden.load("small.mzpeak");
        Line line = g.line(Kind.S, "2").orElseThrow();
        FullArray full = g.full(Kind.S, "2").orElseThrow();
        int point = full.y().length / 2;
        assertThat(full.y()[point]).isPositive();

        // the Numpress tolerance is in effect: a relative error of 1e-4 passes it and fails the exact rule
        double[] close = full.y().clone();
        close[point] *= 1 + 1e-4;
        assertThat(Comparator.compare(line, full.x(), close, numpress)).isEmpty();
        assertThat(Comparator.compareFull(full, full.x(), close, numpress)).isEmpty();
        assertThat(Comparator.compareFull(full, full.x(), close, T)).isNotEmpty();

        double[] y = full.y().clone();
        y[point] = infinity;
        assertThat(Comparator.compare(line, full.x(), y, numpress))
                .anyMatch(m -> m.contains(": sum_y "))
                .anyMatch(m -> m.contains(": sum_xy "))
                .anyMatch(m -> m.contains(": sum_iy "));
        assertThat(Comparator.compareFull(full, full.x(), y, numpress))
                .singleElement().asString().contains("point " + point);

        double[] head = full.y().clone();
        head[0] = infinity;
        assertThat(Comparator.compare(line, full.x(), head, numpress)).anyMatch(m -> m.contains(": head pair 0 "));
    }

    @Test
    void storedPointsOfTheSmallestDiaPasefSpectrumMatchTheFullArray() {
        Golden g = Golden.load("diaPASEF.ref.mzpeak");
        Map<String, double[]> stored = storedPoints("diaPASEF.ref.mzpeak", "peaks").get(2L);
        FullArray full = g.full(Kind.S, "2").orElseThrow();
        assertThat(Comparator.compareFull(full, stored.get("MS:1000514"), stored.get("MS:1000515"), T)).isEmpty();
    }

    @Test
    void digestMatchesTheStoredPointsAndCatchesOneFlippedBit() {
        Golden g = Golden.load("diaPASEF.ref.mzpeak");
        Digest d = g.digest(Kind.S, "0").orElseThrow();
        Map<String, double[]> stored = storedPoints("diaPASEF.ref.mzpeak", "peaks").get(0L);
        double[] mz = stored.get("MS:1000514");
        double[] intensity = stored.get("MS:1000515");
        Map<String, double[]> extras = Map.of(ION_MOBILITY, stored.get(ION_MOBILITY));

        assertThat(Comparator.compareDigest(d, mz, intensity, extras)).isEmpty();

        double[] flipped = mz.clone();
        flipped[mz.length / 2] = Double.longBitsToDouble(Double.doubleToRawLongBits(flipped[mz.length / 2]) ^ 1L);
        assertThat(Comparator.compareDigest(d, flipped, intensity, extras))
                .singleElement().asString().contains("SHA-256 of x");

        double[] mobility = extras.get(ION_MOBILITY).clone();
        mobility[0] = Double.longBitsToDouble(Double.doubleToRawLongBits(mobility[0]) ^ 1L);
        assertThat(Comparator.compareDigest(d, mz, intensity, Map.of(ION_MOBILITY, mobility)))
                .singleElement().asString().contains("extra array " + ION_MOBILITY);
        assertThat(Comparator.compareDigest(d, mz, intensity, Map.of()))
                .singleElement().asString().contains("missing");
    }

    @Test
    void rawStoredCellsMatchTheRLines() {
        Golden g = Golden.load("small.mzpeak");
        Map<Long, Map<String, double[]>> stored = storedPoints("small.mzpeak", "data_arrays");
        assertThat(stored.keySet()).map(String::valueOf)
                .containsExactlyElementsOf(g.lines(Kind.R).stream().map(Line::key).toList());
        for (Line r : g.lines(Kind.R)) {
            Map<String, double[]> cells = stored.get(Long.parseLong(r.key()));
            assertThat(Comparator.compare(r, cells.get("MS:1000514"), cells.get("MS:1000515"), T))
                    .as("R %s", r.key()).isEmpty();
        }
        Map<String, double[]> zero = stored.get(0L);
        double[] shifted = rotateByOne(zero.get("MS:1000514"));
        assertThat(Comparator.compare(g.line(Kind.R, "0").orElseThrow(), shifted, zero.get("MS:1000515"), T))
                .isNotEmpty();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /**
     * The point-layout cells of a spectrum member found by its manifest role, grouped by spectrum index and
     * keyed by array_type; field names come from the member's footer array index, null cells read as NaN.
     */
    private static Map<Long, Map<String, double[]>> storedPoints(String fixture, String dataKind) {
        try (MzPeakSource src = MzPeakSource.open(Fixtures.current(fixture))) {
            String member = MzPeakManifest.fromSource(src).find("spectrum", dataKind).orElseThrow().name();
            InputFile input = src.inputFile(member);
            JsonNode entries = MAPPER.readTree(ParquetGroups.footerKeyValue(input).get("spectrum_array_index"))
                    .path("entries");
            String indexField = null;
            Map<String, String> fields = new LinkedHashMap<>();
            for (JsonNode e : entries) {
                String path = e.path("path").asText();
                assertThat(path).startsWith("point.");
                String leaf = path.substring("point.".length());
                if (e.path("buffer_format").asText().equals("index")) {
                    indexField = leaf;
                } else {
                    fields.putIfAbsent(e.path("array_type").asText(), leaf);
                }
            }
            String index = indexField;
            Map<Long, Map<String, DoubleArrayBuilder>> acc = new TreeMap<>();
            ParquetGroups.forEach(input, row -> {
                Group p = ParquetGroups.optGroup(row, "point");
                Long idx = p == null ? null : ParquetGroups.optLong(p, index);
                if (idx == null) {
                    return;
                }
                Map<String, DoubleArrayBuilder> columns = acc.computeIfAbsent(idx, k -> new HashMap<>());
                fields.forEach((type, leaf) -> {
                    Double v = ParquetGroups.optDouble(p, leaf);
                    columns.computeIfAbsent(type, k -> new DoubleArrayBuilder()).add(v == null ? Double.NaN : v);
                });
            });
            Map<Long, Map<String, double[]>> out = new TreeMap<>();
            acc.forEach((idx, columns) -> {
                Map<String, double[]> arrays = new HashMap<>();
                columns.forEach((type, b) -> arrays.put(type, b.toArray()));
                out.put(idx, arrays);
            });
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readmePythonVersion() throws IOException {
        Matcher m = Pattern.compile("CPython \\*\\*([0-9.]+)\\*\\*")
                .matcher(Files.readString(Golden.DIR.resolve("README.md")));
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static long countTrue(boolean[] flags) {
        long n = 0;
        for (boolean f : flags) {
            if (f) {
                n++;
            }
        }
        return n;
    }

    private static boolean allEqual(double[] values) {
        for (double v : values) {
            if (Double.compare(v, values[0]) != 0) {
                return false;
            }
        }
        return true;
    }

    private static double[] rotateByOne(double[] values) {
        double[] out = new double[values.length];
        System.arraycopy(values, 1, out, 0, values.length - 1);
        out[values.length - 1] = values[0];
        return out;
    }

    private static double[] reversed(double[] values) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[values.length - 1 - i];
        }
        return out;
    }
}
