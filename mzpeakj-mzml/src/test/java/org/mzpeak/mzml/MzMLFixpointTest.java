package org.mzpeak.mzml;

import org.apache.parquet.hadoop.ParquetFileReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ContentDigest;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakManifest;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.io.MzPeakSource;
import org.mzpeak.io.MzPeakWriter;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Param;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.mzml.ModelAssert.Exclusion;
import org.mzpeak.mzml.ModelAssert.Model;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mzpeak.mzml.ModelAssert.Mode.STRICT;

/**
 * JZ-9: the fixpoints of mzML design 12.5, under the default time zone UTC, restored after each test.
 * <ul>
 *   <li><b>mzPeak fixtures</b> (all 13, zlib): m1 = toMzML(F), p2 = toMzPeak(m1), m2 = toMzML(p2), p3 = toMzPeak(m2),
 *       m3 = toMzML(p3). m2 and m3 are byte-identical and p2, p3 have equal {@link ContentDigest}s per member, for
 *       every F; the 8 current fixtures are normal already (m1 = m2 byte for byte); the 5 legacy ones gain exactly the
 *       five summary params per spectrum in p2 (A-16), the only difference between the models {@link MzMLReader} reads
 *       from m1 and m2.
 *   <li><b>mzML fixtures</b> (the 4 vendored ones): q1 = toMzPeak(X), m1 = toMzML(q1), q2 = toMzPeak(m1),
 *       m2 = toMzML(q2): m1 and m2 are byte-identical and q1, q2 have equal content digests; diaPASEF's m1 keeps
 *       exactly MS:1000505 and MS:1000285 of the five summary params, with the source values; tiny's run start time is
 *       2007-06-27T15:23:45Z in m1 (A-26) and in m2. tiny is the one exception to equal digests: q1 holds the source's
 *       start time 2007-06-27T15:23:45.00035 and q2 the one m1 holds (A-26), in the footer key-values of the four
 *       spectrum metadata members and in the manifest, so the test asserts that q1 and q2 hold the same schemas,
 *       records, key-values and manifest once that one string is replaced, and that their models differ only by A-26.
 * </ul>
 */
class MzMLFixpointTest {

    /** The five summary params the mzPeak writer derives when a spectrum carries none of them (design 6.2). */
    static final Set<String> SUMMARIES = Set.of("MS:1000528", "MS:1000527", "MS:1000504", "MS:1000505", "MS:1000285");

    /** A member checksum in the manifest with its value. */
    private static final Pattern CHECKSUM = Pattern.compile("\"checksum\" : \"[0-9a-f]+\"");

    private static final Pattern SPECTRUM_PARAM =Pattern.compile("spectrum\\[\\d+]\\.parameters\\[(.+)]");

    /**
     * A-16 narrowed to the summary params: a difference A-16 hides whose path names one of the five, so that a gained
     * spectrum type (which m1 already carries) is not hidden.
     */
    private static final Exclusion A_16_SUMMARIES = new Exclusion("A_16_SUMMARIES",
            "A-16 for the five summary params only: legacy small* 48 x 5, legacy has_uv 212 x 5",
            d -> summaryPath(d.path()) && ModelAssert.A_16.hides().test(d));

    /** tiny's run start time, without zone, and as jmzML writes it under UTC (A-26). */
    private static final String TINY_START = "2007-06-27T15:23:45.00035";
    private static final String TINY_START_UTC = "2007-06-27T15:23:45Z";

    @TempDir
    Path dir;

    private TimeZone zone;

    @BeforeEach
    void utc() {
        zone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(zone);
    }

    /** The 13 mzPeak fixtures: display name, path, whether legacy, MS spectra. */
    static Stream<Arguments> mzPeakFixtures() {
        List<Arguments> cases = new ArrayList<>();
        Fixtures.CURRENT_NAMES.forEach(n -> cases.add(arguments("current " + n, Fixtures.current(n), false)));
        Fixtures.LEGACY_NAMES.forEach(n -> cases.add(arguments("legacy " + n, Fixtures.legacy(n), true)));
        return cases.stream();
    }

    @Test
    void thereAreThirteenMzPeakFixtures() {
        assertThat(mzPeakFixtures()).hasSize(13);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mzPeakFixtures")
    void mzPeakFixturesReachTheirFixpointAfterOnePass(String name, Path fixture, boolean legacy) throws IOException {
        Path m1 = dir.resolve("m1.mzML");
        Path p2 = dir.resolve("p2.mzpeak");
        Path m2 = dir.resolve("m2.mzML");
        Path p3 = dir.resolve("p3.mzpeak");
        Path m3 = dir.resolve("m3.mzML");
        MzMLConversion.toMzML(fixture, m1, true);
        MzMLConversion.toMzPeak(m1, p2, false, MzPeakWriter.Options.defaults());
        MzMLConversion.toMzML(p2, m2, true);
        MzMLConversion.toMzPeak(m2, p3, false, MzPeakWriter.Options.defaults());
        MzMLConversion.toMzML(p3, m3, true);
        for (Path mzml : List.of(m1, m2, m3)) {
            MzMLAssert.valid(mzml);
        }

        assertThat(Files.readAllBytes(m3)).as(name + ": m3 = m2").isEqualTo(Files.readAllBytes(m2));
        assertThat(digests(p3)).as(name + ": p3 = p2").isEqualTo(digests(p2));
        if (!legacy) {
            assertThat(Files.readAllBytes(m2)).as(name + ": m2 = m1").isEqualTo(Files.readAllBytes(m1));
            return;
        }
        assertThat(Files.readAllBytes(m2)).as(name + ": m2 differs from m1").isNotEqualTo(Files.readAllBytes(m1));
        Model first = mzmlModel(m1);
        int spectra = first.spectrumList().size();
        assertThat(spectra).isIn(48, 212);
        assertThat(first.spectrumList()).allSatisfy(s -> assertThat(s.description().parameters())
                .extracting(Param::accession).doesNotContainAnyElementsOf(SUMMARIES));
        ModelAssert.Result result = ModelAssert.assertEqual(name + ": m1 vs m2", first, mzmlModel(m2), STRICT,
                A_16_SUMMARIES);
        assertThat(result.hidden()).isEqualTo(Map.of("A_16_SUMMARIES", 5 * spectra));
    }

    /** The 4 vendored mzML fixtures. */
    static Stream<String> mzMLFixtures() {
        return MzMLFixtures.NAMES.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mzMLFixtures")
    void mzMLFixturesAreNormalAlready(String name) throws IOException {
        Path source = MzMLFixtures.extract(name, Files.createDirectories(dir.resolve("source")));
        Path q1 = dir.resolve("q1.mzpeak");
        Path m1 = dir.resolve("m1.mzML");
        Path q2 = dir.resolve("q2.mzpeak");
        Path m2 = dir.resolve("m2.mzML");
        MzMLConversion.toMzPeak(source, q1, false, MzPeakWriter.Options.defaults());
        MzMLConversion.toMzML(q1, m1, true);
        MzMLConversion.toMzPeak(m1, q2, false, MzPeakWriter.Options.defaults());
        MzMLConversion.toMzML(q2, m2, true);
        MzMLAssert.valid(m1);
        MzMLAssert.valid(m2);

        assertThat(Files.readAllBytes(m2)).as(name + ": m2 = m1").isEqualTo(Files.readAllBytes(m1));
        if (name.equals(MzMLFixtures.TINY)) {
            // A-26 rewrites the run start time in m1, so q2 holds the rewritten one where q1 holds the source's
            assertThat(digests(q2)).as(name + ": q2 differs from q1").isNotEqualTo(digests(q1));
            assertThat(contents(q2, TINY_START_UTC, TINY_START_UTC)).as(name + ": q2 = q1 up to the start time")
                    .isEqualTo(contents(q1, TINY_START, TINY_START_UTC));
        } else {
            assertThat(digests(q2)).as(name + ": q2 = q1").isEqualTo(digests(q1));
        }

        if (name.equals(MzMLFixtures.DIAPASEF)) {
            List<Spectrum> given = mzmlModel(source).spectrumList();
            List<Spectrum> written = mzmlModel(m1).spectrumList();
            assertThat(written).hasSize(9);
            for (int i = 0; i < 9; i++) {
                List<Param> summaries = summaries(written.get(i));
                assertThat(summaries).extracting(Param::accession).as("spectrum %d", i)
                        .containsExactly("MS:1000505", "MS:1000285");
                assertThat(summaries).as("spectrum %d", i).isEqualTo(summaries(given.get(i)));
            }
        }
        if (name.equals(MzMLFixtures.TINY)) {
            assertThat(mzmlModel(source).fileMetadata().run().startTime()).isEqualTo(TINY_START);
            assertThat(mzmlModel(m1).fileMetadata().run().startTime()).isEqualTo(TINY_START_UTC);
            assertThat(mzmlModel(m2).fileMetadata().run().startTime()).isEqualTo(TINY_START_UTC);
            ModelAssert.Result result = ModelAssert.assertEqual(name + ": q1 vs q2", Model.load(q1), Model.load(q2),
                    STRICT, ModelAssert.A_26);
            assertThat(result.hidden()).isEqualTo(Map.of("A_26", 1));
        }
    }

    // ---- helpers (also used by MzMLDeterminismTest) ---------------------------------------------------------------

    /** The params of {@code s} that are summary params, in order. */
    private static List<Param> summaries(Spectrum s) {
        return s.description().parameters().stream().filter(p -> p.accession() != null && SUMMARIES.contains(p.accession())).toList();
    }

    /** Whether {@code path} names spectrum params of one of the five summary accessions. */
    private static boolean summaryPath(String path) {
        Matcher m = SPECTRUM_PARAM.matcher(path);
        return m.matches() && SUMMARIES.contains(m.group(1));
    }

    /**
     * The content digest of every member of the dataset at {@code path}, by name: each file its manifest lists, and
     * the manifest itself under {@code mzpeak_index.json} (SHA-256 of its bytes).
     */
    static Map<String, String> digests(Path path) {
        Map<String, String> digests = new TreeMap<>();
        try (MzPeakReader reader = MzPeakReader.open(path); MzPeakSource source = MzPeakSource.open(path)) {
            for (MzPeakManifest.Entry member : reader.manifest().files()) {
                digests.put(member.name(), ContentDigest.of(source, member.name()).sha256());
            }
            digests.put("mzpeak_index.json", sha256(source.readManifestBytes()));
        }
        assertThat(digests).hasSizeGreaterThan(1);
        return digests;
    }

    /**
     * What each member of the dataset at {@code path} holds, with every occurrence of {@code from} in the footer
     * key-values and the manifest replaced by {@code to}: per Parquet member the {@link ContentDigest} detail without
     * its key-value part (row groups, rows, schema and records digests) and the footer key-values sorted by key; the
     * manifest as text without the values of its member checksums (byte checksums, which change with the footers).
     * {@code from} must occur in at least one member.
     */
    private static Map<String, String> contents(Path path, String from, String to) {
        Map<String, String> contents = new TreeMap<>();
        int holding = 0;
        try (MzPeakReader reader = MzPeakReader.open(path); MzPeakSource source = MzPeakSource.open(path)) {
            for (MzPeakManifest.Entry member : reader.manifest().files()) {
                String detail = ContentDigest.of(source, member.name()).detail().replaceAll(" key_values=\\S+", "");
                Map<String, String> keyValues = new TreeMap<>();
                try (ParquetFileReader parquet = ParquetFileReader.open(source.inputFile(member.name()),
                        ParquetGroups.readOptions())) {
                    for (Map.Entry<String, String> kv : parquet.getFooter().getFileMetaData().getKeyValueMetaData()
                            .entrySet()) {
                        holding += kv.getValue().contains(from) ? 1 : 0;
                        keyValues.put(kv.getKey(), kv.getValue().replace(from, to));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                contents.put(member.name(), detail + " " + keyValues);
            }
            String manifest = new String(source.readManifestBytes(), StandardCharsets.UTF_8);
            holding += manifest.contains(from) ? 1 : 0;
            contents.put("mzpeak_index.json", CHECKSUM.matcher(manifest.replace(from, to)).replaceAll("\"checksum\""));
        }
        assertThat(holding).as("members holding " + from).isPositive();
        return contents;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The model {@link MzMLReader} reads from {@code file}. */
    static Model mzmlModel(Path file) {
        MzMLReader reader = new MzMLReader(file);
        List<Spectrum> spectra = new ArrayList<>();
        List<WavelengthSpectrum> wavelengthSpectra = new ArrayList<>();
        List<Chromatogram> chromatograms = new ArrayList<>();
        reader.read(new MzMLReader.Visitor() {
            @Override
            public void spectrum(Spectrum spectrum) {
                spectra.add(spectrum);
            }

            @Override
            public void wavelengthSpectrum(WavelengthSpectrum spectrum) {
                wavelengthSpectra.add(spectrum);
            }

            @Override
            public void chromatogram(Chromatogram chromatogram) {
                chromatograms.add(chromatogram);
            }
        });
        MzMLReader.Header header = reader.header();
        return new Model(spectra, chromatograms, wavelengthSpectra, header.fileMetadata(), header.scanSettings(),
                header.cvList());
    }
}
