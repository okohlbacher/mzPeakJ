package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakReader.Options;
import org.mzpeak.io.MzPeakReader.SignalMode;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Spectrum;

import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AP-2: the signal mode of {@link MzPeakReader.Options}, which selects the signal files a reader decodes (design
 * section 10).
 *
 * <p>Characterization (step 1, pinned on the reader before AP-2): the default options read every fixture of both sets
 * like {@link MzPeakReader#open(Path)}, and the spectra they decode match a SHA-256 over their raw bits. The digest
 * covers what {@code MzPeakReader.materialize} chooses, which AP-2 changes: per spectrum its index, m/z, intensity,
 * peaks (index, m/z and intensity, in {@link Spectrum#peaks()} order), {@link Spectrum#arrays()} and
 * {@link Spectrum#peakArrays()}. It leaves out descriptions, chromatograms and file metadata, which other units change
 * on purpose, and diaPASEF.grid, whose grid decoding is in flux (RD-20), like {@link SignalSnapshotTest}.
 *
 * <p>The modes: BOTH decodes the pinned signal. PROFILE and CENTROID are checked against the BOTH read of the same
 * fixture on every fixture, and on the cases of the unit's acceptance (small.chunked, small.unpacked, diaPASEF.ref,
 * has_uv), with spectrum 0 of small.chunked (13589 profile points, 1612 centroids) and spectrum 5 (650 centroids
 * only) as in the HEAD Python golden ({@code golden/small.chunked.tsv}). A {@link TestSources.Recording} source shows
 * that each mode leaves the other member unopened.
 */
class SignalModeTest {

    /**
     * Signal digests ({@link #signalDigest}) of the default read, recorded on the reader before AP-2. The current and
     * the legacy example of a kind decode alike, and so do small and small.unpacked.
     */
    private static final String SMALL = "48 spectra, 243054 points, 25344 peaks, "
            + "d72d003d9b327a498100a8827f883ed330db332f7455f080b63b9c89c68a4de2";
    private static final String CHUNKED = "48 spectra, 243054 points, 50247 peaks, "
            + "8e97a663d7c26cae59f84bdfee88aa2bfb62edcf5e4d1e234b23cd9ec26a39bd";
    private static final String NUMPRESS = "48 spectra, 243054 points, 25344 peaks, "
            + "3271922ef97bced47ca825a745463e282ddd9949ff25d0770d7d9794d2f675db";
    private static final String HAS_UV = "212 spectra, 24310 points, 24310 peaks, "
            + "6d82374f7b65a136b4e3eef88cb20b8fdc58b266441bcf5f181cafe7ec37c990";

    static Stream<Path> allFixtures() {
        return Stream.concat(Fixtures.CURRENT_NAMES.stream().map(Fixtures::current),
                Fixtures.LEGACY_NAMES.stream().map(Fixtures::legacy));
    }

    /** Each fixture but diaPASEF.grid, with the signal digest of its default read. */
    static Stream<Arguments> pinnedSignals() {
        return Stream.of(
                Arguments.of(Fixtures.current("small.mzpeak"), SMALL),
                Arguments.of(Fixtures.current("small.chunked.mzpeak"), CHUNKED),
                Arguments.of(Fixtures.current("small.numpress.mzpeak"), NUMPRESS),
                Arguments.of(Fixtures.current("small.unpacked.mzpeak"), SMALL),
                Arguments.of(Fixtures.current("has_uv.mzpeak"), HAS_UV),
                Arguments.of(Fixtures.current("diaPASEF.ref.mzpeak"), "9 spectra, 291453 points, 291453 peaks, "
                        + "9e238d5251dd0ce5025d6fb91938b28cce01f6a1467bbdb0b7a22488b532ea7b"),
                Arguments.of(Fixtures.current("Example_Processed.img.mzpeak"), "9 spectra, 36856 points, 0 peaks, "
                        + "b252bf67e9b20d28d7dcc749bf0a692b0dc4a2a385eb01f190ec0f19c7faa632"),
                Arguments.of(Fixtures.legacy("small.mzpeak"), SMALL),
                Arguments.of(Fixtures.legacy("small.chunked.mzpeak"), CHUNKED),
                Arguments.of(Fixtures.legacy("small.numpress.mzpeak"), NUMPRESS),
                Arguments.of(Fixtures.legacy("small.unpacked.mzpeak"), SMALL),
                Arguments.of(Fixtures.legacy("has_uv.mzpeak"), HAS_UV));
    }

    @ParameterizedTest
    @MethodSource("allFixtures")
    void defaultOptionsReadLikeThePathOverload(Path fixture) {
        try (MzPeakReader expected = MzPeakReader.open(fixture);
             MzPeakReader actual = MzPeakReader.open(fixture, Options.defaults())) {
            assertThat(TestSources.content(actual)).containsExactlyElementsOf(TestSources.content(expected));
        }
    }

    @ParameterizedTest
    @MethodSource("pinnedSignals")
    void defaultOptionsDecodeThePinnedSignal(Path fixture, String digest) {
        try (MzPeakReader reader = MzPeakReader.open(fixture, Options.defaults())) {
            assertThat(signalDigest(reader)).isEqualTo(digest);
        }
    }

    // ---- BOTH ---------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("pinnedSignals")
    void bothIsTheDefaultAndDecodesThePinnedSignal(Path fixture, String digest) {
        try (MzPeakReader reader = MzPeakReader.open(fixture, Options.defaults().withSignalMode(SignalMode.BOTH));
             MzPeakReader byPath = MzPeakReader.open(fixture)) {
            assertThat(signalDigest(reader)).isEqualTo(digest);
            assertThat(reader.options()).isEqualTo(Options.defaults());
            assertThat(byPath.options().signalMode()).isEqualTo(SignalMode.BOTH);
        }
    }

    // ---- the modes on every fixture -----------------------------------------------------------------------------

    /**
     * Oracle: the BOTH read of the same fixture. PROFILE keeps the data-arrays side of each spectrum and CENTROID the
     * peaks side, and BOTH is the PROFILE spectrum when that has points, else the CENTROID one.
     */
    @ParameterizedTest
    @MethodSource("allFixtures")
    void eachModeKeepsItsSideOfTheBothRead(Path fixture) {
        try (MzPeakReader both = MzPeakReader.open(fixture);
             MzPeakReader profile = MzPeakReader.open(fixture, Options.defaults().withSignalMode(SignalMode.PROFILE));
             MzPeakReader centroid = MzPeakReader.open(fixture,
                     Options.defaults().withSignalMode(SignalMode.CENTROID))) {
            assertThat(profile.size()).isEqualTo(both.size());
            assertThat(centroid.size()).isEqualTo(both.size());
            for (int i = 0; i < both.size(); i++) {
                Spectrum b = both.getSpectrumAt(i);
                Spectrum p = profile.getSpectrumAt(i);
                Spectrum c = centroid.getSpectrumAt(i);
                String at = fixture + " ordinal " + i;

                assertThat(p.description()).as(at).isEqualTo(b.description());
                assertThat(p.peaks()).as(at).isEmpty();
                assertThat(p.peakArrays()).as(at).isEmpty();

                assertThat(c.description()).as(at).isEqualTo(b.description());
                assertThat(c.peaks()).as(at).isEqualTo(b.peaks());
                assertSameArrays(c.peakArrays(), b.peakArrays(), at);
                assertStoredPeakOrder(c, at);
                assertSameArrays(c.arrays(), c.peakArrays(), at);

                Spectrum expected = p.mz().length > 0 ? p : c;
                assertThat(b.mz()).as(at).isEqualTo(expected.mz());
                assertThat(b.intensity()).as(at).isEqualTo(expected.intensity());
                assertSameArrays(b.arrays(), expected.arrays(), at);
            }
        }
    }

    // ---- PROFILE --------------------------------------------------------------------------------------------------

    @Test
    void profileReadsOnlyTheDataArraysOfSmallChunked() {
        Path fixture = Fixtures.current("small.chunked.mzpeak");
        TestSources.Recording source = TestSources.recording(MzPeakSource.open(fixture));
        try (MzPeakReader both = MzPeakReader.open(fixture);
             MzPeakReader profile = MzPeakReader.open(source,
                     Options.defaults().withSignalMode(SignalMode.PROFILE))) {
            Spectrum b0 = both.getSpectrum(0).orElseThrow();
            Spectrum p0 = profile.getSpectrum(0).orElseThrow();
            assertThat(b0.peaks()).hasSize(1612);
            assertThat(p0.mz()).hasSize(13589).isEqualTo(b0.mz());
            assertThat(p0.intensity()).isEqualTo(b0.intensity());
            assertSameArrays(p0.arrays(), b0.arrays(), "spectrum 0");
            assertThat(p0.peaks()).isEmpty();
            assertThat(p0.peakArrays()).isEmpty();

            // spectrum 5 is stored only as centroids
            assertThat(both.getSpectrum(5).orElseThrow().peaks()).hasSize(650);
            assertEmpty(profile.getSpectrum(5).orElseThrow(), "spectrum 5");

            for (Spectrum ignored : profile) {
                // read every spectrum
            }
            assertThat(source.opened())
                    .contains(member(profile, DataKind.DATA_ARRAYS))
                    .doesNotContain(member(profile, DataKind.PEAKS));
        }
        assertThat(source.closeCount()).isEqualTo(1);
    }

    @Test
    void profileOfACentroidOnlyDatasetIsEmpty() {
        try (MzPeakReader dia = MzPeakReader.open(Fixtures.current("diaPASEF.ref.mzpeak"),
                Options.defaults().withSignalMode(SignalMode.PROFILE));
             MzPeakReader uv = MzPeakReader.open(Fixtures.current("has_uv.mzpeak"),
                     Options.defaults().withSignalMode(SignalMode.PROFILE))) {
            assertThat(dia.size()).isEqualTo(9);
            for (Spectrum s : dia) {
                assertEmpty(s, "diaPASEF.ref spectrum " + s.index());
            }
            assertThat(uv.size()).isEqualTo(212);
            for (Spectrum s : uv) {
                assertThat(s.mz()).as("has_uv spectrum %d", s.index()).isEmpty();
            }
        }
    }

    @Test
    void profileKeepsTheReconstructionOption() {
        Path fixture = Fixtures.legacy("small.unpacked.mzpeak");
        Options stored = Options.defaults().withReconstructProfile(false);
        try (MzPeakReader both = MzPeakReader.open(fixture, stored);
             MzPeakReader profile = MzPeakReader.open(fixture, stored.withSignalMode(SignalMode.PROFILE))) {
            double[] mz = profile.getSpectrumAt(0).mz();
            assertThat(mz).hasSizeLessThan(13589).isEqualTo(both.getSpectrumAt(0).mz());
        }
    }

    // ---- CENTROID -------------------------------------------------------------------------------------------------

    @Test
    void centroidReadsOnlyThePeaksOfSmallChunked() {
        Path fixture = Fixtures.current("small.chunked.mzpeak");
        TestSources.Recording source = TestSources.recording(MzPeakSource.open(fixture));
        try (MzPeakReader both = MzPeakReader.open(fixture);
             MzPeakReader centroid = MzPeakReader.open(source,
                     Options.defaults().withSignalMode(SignalMode.CENTROID))) {
            Spectrum b0 = both.getSpectrum(0).orElseThrow();
            Spectrum c0 = centroid.getSpectrum(0).orElseThrow();
            assertThat(b0.mz()).hasSize(13589);
            assertThat(c0.peaks()).hasSize(1612).containsExactlyElementsOf(b0.peaks());
            // m/z and intensity are the peaks in stored order, the order CentroidPeak.index() counts
            assertThat(c0.mz()).hasSize(1612);
            assertStoredPeakOrder(c0, "spectrum 0");
            List<CentroidPeak> stored = new ArrayList<>(b0.peaks());
            stored.sort(Comparator.comparingInt(CentroidPeak::index));
            assertThat(c0.mz()).isEqualTo(stored.stream().mapToDouble(CentroidPeak::mz).toArray());
            assertThat(c0.intensity()).isEqualTo(stored.stream().mapToDouble(CentroidPeak::intensity).toArray());

            // spectrum 5 is stored only as centroids, so BOTH reads it the same way
            assertSameSignal(centroid.getSpectrum(5).orElseThrow(), both.getSpectrum(5).orElseThrow(),
                    "spectrum 5");

            for (Spectrum ignored : centroid) {
                // read every spectrum
            }
            assertThat(source.opened())
                    .contains(member(centroid, DataKind.PEAKS))
                    .doesNotContain(member(centroid, DataKind.DATA_ARRAYS));
        }
        assertThat(source.closeCount()).isEqualTo(1);
    }

    @Test
    void centroidOfAProfileOnlySpectrumIsEmpty() {
        for (Path fixture : List.of(Fixtures.current("small.unpacked.mzpeak"),
                Fixtures.legacy("small.unpacked.mzpeak"))) {
            try (MzPeakReader both = MzPeakReader.open(fixture);
                 MzPeakReader centroid = MzPeakReader.open(fixture,
                         Options.defaults().withSignalMode(SignalMode.CENTROID))) {
                assertThat(both.getSpectrum(0).orElseThrow().mz()).as("%s", fixture).hasSize(13589);
                assertEmpty(centroid.getSpectrum(0).orElseThrow(), fixture + " spectrum 0");
            }
        }
    }

    @Test
    void centroidReadsDiaPasefRefLikeBoth() {
        Path fixture = Fixtures.current("diaPASEF.ref.mzpeak");
        try (MzPeakReader both = MzPeakReader.open(fixture);
             MzPeakReader centroid = MzPeakReader.open(fixture,
                     Options.defaults().withSignalMode(SignalMode.CENTROID))) {
            assertThat(TestSources.content(centroid)).containsExactlyElementsOf(TestSources.content(both));
            // the comparison covers the extras
            Spectrum s0 = centroid.getSpectrumAt(0);
            assertThat(s0.peakArrays()).containsOnlyKeys("MS:1003006");
            assertThat(s0.ionMobility()).isPresent();
        }
    }

    // ---- Options --------------------------------------------------------------------------------------------------

    @Test
    void withSignalModeReturnsANewInstanceAndKeepsTheOtherOptions() {
        Options defaults = Options.defaults();
        Options profile = defaults.withSignalMode(SignalMode.PROFILE);
        Options stored = defaults.withReconstructProfile(false).withSignalMode(SignalMode.CENTROID);

        assertThat(defaults.signalMode()).isEqualTo(SignalMode.BOTH);
        assertThat(profile).isNotSameAs(defaults);
        assertThat(profile.signalMode()).isEqualTo(SignalMode.PROFILE);
        assertThat(profile.reconstructProfile()).isTrue();
        assertThat(defaults.withSignalMode(SignalMode.BOTH)).isNotSameAs(defaults).isEqualTo(defaults);
        assertThat(stored.signalMode()).isEqualTo(SignalMode.CENTROID);
        assertThat(stored.reconstructProfile()).isFalse();
        assertThat(stored.withReconstructProfile(true).signalMode()).isEqualTo(SignalMode.CENTROID);
    }

    @Test
    void withSignalModeRejectsNull() {
        assertThatThrownBy(() -> Options.defaults().withSignalMode(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("signalMode");
    }

    @Test
    void equalityCoversTheSignalMode() {
        Options a = Options.defaults().withSignalMode(SignalMode.CENTROID);
        Options b = Options.defaults().withSignalMode(SignalMode.PROFILE).withSignalMode(SignalMode.CENTROID);

        assertThat(a).isEqualTo(b).isNotSameAs(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(Options.defaults());
        assertThat(a).isNotEqualTo(Options.defaults().withSignalMode(SignalMode.PROFILE));
        assertThat(a).isNotEqualTo(a.withReconstructProfile(false));
        assertThat(a.toString()).contains("signalMode=CENTROID").contains("reconstructProfile=true");
        assertThat(Options.defaults().toString()).contains("signalMode=BOTH");
    }

    @Test
    void openReportsTheSignalModeItWasGiven() {
        Options centroid = Options.defaults().withSignalMode(SignalMode.CENTROID);
        try (MzPeakReader reader = MzPeakReader.open(TestSources.memory(Fixtures.legacy("small.unpacked.mzpeak")),
                centroid)) {
            assertThat(reader.options()).isEqualTo(centroid);
            assertThat(reader.options().signalMode()).isEqualTo(SignalMode.CENTROID);
        }
    }

    @Test
    void signalModeIsAPublicNestedEnum() {
        assertThat(SignalMode.class.isEnum()).isTrue();
        assertThat(Modifier.isPublic(SignalMode.class.getModifiers())).isTrue();
        assertThat(SignalMode.class.getEnclosingClass()).isEqualTo(MzPeakReader.class);
        assertThat(SignalMode.values()).containsExactly(SignalMode.BOTH, SignalMode.PROFILE, SignalMode.CENTROID);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** The name of the spectrum member of {@code kind}, found by its manifest role. */
    private static String member(MzPeakReader reader, DataKind kind) {
        return reader.manifest().find(EntityType.SPECTRUM, kind).orElseThrow().name();
    }

    private static void assertEmpty(Spectrum s, String at) {
        assertThat(s.mz()).as(at).isEmpty();
        assertThat(s.intensity()).as(at).isEmpty();
        assertThat(s.peaks()).as(at).isEmpty();
        assertThat(s.arrays()).as(at).isEmpty();
        assertThat(s.peakArrays()).as(at).isEmpty();
    }

    /** {@code s.mz()} and {@code s.intensity()} hold every peak at its {@link CentroidPeak#index()}. */
    private static void assertStoredPeakOrder(Spectrum s, String at) {
        assertThat(s.mz().length).as(at).isGreaterThanOrEqualTo(s.peaks().size());
        List<CentroidPeak> misplaced = new ArrayList<>();
        for (CentroidPeak peak : s.peaks()) {
            if (Double.compare(s.mz()[peak.index()], peak.mz()) != 0
                    || Double.compare(s.intensity()[peak.index()], peak.intensity()) != 0) {
                misplaced.add(peak);
            }
        }
        assertThat(misplaced).as(at + ": peaks not at their index in mz() and intensity()").isEmpty();
    }

    private static void assertSameSignal(Spectrum actual, Spectrum expected, String at) {
        assertThat(actual.description()).as(at).isEqualTo(expected.description());
        assertThat(actual.mz()).as(at).isEqualTo(expected.mz());
        assertThat(actual.intensity()).as(at).isEqualTo(expected.intensity());
        assertThat(actual.peaks()).as(at).containsExactlyElementsOf(expected.peaks());
        assertSameArrays(actual.arrays(), expected.arrays(), at);
        assertSameArrays(actual.peakArrays(), expected.peakArrays(), at);
    }

    /** Same keys in the same order, and arrays with the same values (compared by bits, so NaN equals NaN). */
    private static void assertSameArrays(Map<String, double[]> actual, Map<String, double[]> expected, String at) {
        assertThat(actual.keySet()).as(at).containsExactlyElementsOf(expected.keySet());
        expected.forEach((key, values) -> assertThat(actual.get(key)).as(at + " " + key).isEqualTo(values));
    }

    /**
     * A SHA-256 over every spectrum {@code reader} decodes, in ordinal order, prefixed by the spectrum count and the
     * total number of m/z values and peaks, so that a mismatch shows whether the shape changed. Per spectrum: its
     * index; the m/z and intensity arrays; the peaks as index, m/z and intensity; then {@link Spectrum#arrays()} and
     * {@link Spectrum#peakArrays()} as key and array, in iteration order. An array is its length and the raw bits of
     * its values.
     */
    static String signalDigest(MzPeakReader reader) {
        MessageDigest sha = sha256();
        long points = 0;
        long peaks = 0;
        for (int i = 0; i < reader.size(); i++) {
            Spectrum s = reader.getSpectrumAt(i);
            update(sha, s.description().index());
            update(sha, s.mz());
            update(sha, s.intensity());
            update(sha, s.peaks().size());
            for (CentroidPeak p : s.peaks()) {
                update(sha, p.index());
                update(sha, Double.doubleToRawLongBits(p.mz()));
                update(sha, Double.doubleToRawLongBits(p.intensity()));
            }
            update(sha, s.arrays());
            update(sha, s.peakArrays());
            points += s.mz().length;
            peaks += s.peaks().size();
        }
        return reader.size() + " spectra, " + points + " points, " + peaks + " peaks, "
                + HexFormat.of().formatHex(sha.digest());
    }

    private static void update(MessageDigest sha, Map<String, double[]> arrays) {
        update(sha, arrays.size());
        arrays.forEach((key, values) -> {
            byte[] name = key.getBytes(StandardCharsets.UTF_8);
            update(sha, name.length);
            sha.update(name);
            update(sha, values);
        });
    }

    private static void update(MessageDigest sha, double[] values) {
        ByteBuffer bits = ByteBuffer.allocate(Long.BYTES * (values.length + 1));
        bits.putLong(values.length);
        for (double v : values) {
            bits.putLong(Double.doubleToRawLongBits(v));
        }
        sha.update(bits.array());
    }

    private static void update(MessageDigest sha, long value) {
        sha.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
