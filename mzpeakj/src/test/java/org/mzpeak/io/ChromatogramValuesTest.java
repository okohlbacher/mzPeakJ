package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Chromatogram;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Chromatogram time and intensity values, checked against the vendored fixtures of both sets: the legacy packed
 * layout and the current split layout carry the same chromatograms. The point-layout
 * {@code small.unpacked.mzpeak} TIC is the reference for the other {@code small.*} containers; the exact
 * pre-existing output of the arrays that must not change is pinned by a bit-exact SHA-256 checksum.
 */
class ChromatogramValuesTest {

    // Bit-exact checksums of the reader output, captured on the code before chromatogram intensity fallbacks
    // (SLOF chunk bytes, intensity_* point columns) were added.
    private static final String SMALL_TIC_TIME_SHA256 =
            "b92f8411d9337bd1e31b6ca189313d44e10525208c2ac57381293faab7206dd2";
    private static final String SMALL_TIC_INTENSITY_SHA256 =
            "01b09b6cb68b8e11f7010555d63719a90810427385c93b19f104b4caf5f034e6";
    private static final String UV_TIC_TIME_SHA256 =
            "f2a31176cc10587cd1204800964d5bd44fcccda1117d3db66641c53229c9b1ab";
    private static final String UV_TIC_INTENSITY_SHA256 =
            "048a988b1785de9bb8b61eaf7c3742b250cef99f7e30dd355dd66fe292bf8acf";
    private static final String UV_DAD1_TIME_SHA256 =
            "7fdfc101e43534357a5fc5db61a2da40771d8846ce8619a70f2845f0cfd2735d";

    /** The two vendored fixture sets. */
    enum FixtureSet {
        LEGACY(Fixtures::legacy),
        CURRENT(Fixtures::current);

        private final Function<String, Path> resolve;

        FixtureSet(Function<String, Path> resolve) {
            this.resolve = resolve;
        }

        Path path(String fixture) {
            return resolve.apply(fixture);
        }
    }

    private static Chromatogram tic(FixtureSet set, String fixture) {
        try (MzPeakReader r = MzPeakReader.open(set.path(fixture))) {
            return r.getChromatogram(0).orElseThrow();
        }
    }

    private static List<Chromatogram> chromatograms(FixtureSet set, String fixture) {
        try (MzPeakReader r = MzPeakReader.open(set.path(fixture))) {
            return r.chromatograms();
        }
    }

    /** Every fixture set paired with each of {@code fixtures}. */
    private static Stream<Arguments> inBothSets(String... fixtures) {
        return Stream.of(FixtureSet.values())
                .flatMap(set -> Stream.of(fixtures).map(fixture -> Arguments.of(set, fixture)));
    }

    static Stream<Arguments> layoutVariants() {
        return inBothSets("small.mzpeak", "small.chunked.mzpeak");
    }

    static Stream<Arguments> smallFixtures() {
        return inBothSets("small.unpacked.mzpeak", "small.mzpeak", "small.chunked.mzpeak", "small.numpress.mzpeak");
    }

    /** SHA-256 over the raw IEEE-754 bits of {@code values}, big-endian, so any bit change is detected. */
    static String bitsSha256(double[] values) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        ByteBuffer buf = ByteBuffer.allocate(Long.BYTES);
        for (double v : values) {
            buf.clear();
            buf.putLong(Double.doubleToRawLongBits(v));
            md.update(buf.array());
        }
        return HexFormat.of().formatHex(md.digest());
    }

    // ---- unchanged behaviour (characterization) --------------------------------------------------

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("layoutVariants")
    void layoutVariantTicEqualsUnpackedTic(FixtureSet set, String fixture) {
        Chromatogram expected = tic(set, "small.unpacked.mzpeak");
        Chromatogram actual = tic(set, fixture);
        assertThat(actual.time()).isEqualTo(expected.time());
        assertThat(actual.intensity()).isEqualTo(expected.intensity());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixtureSet.class)
    void numpressTicTimeEqualsUnpackedTime(FixtureSet set) {
        assertThat(tic(set, "small.numpress.mzpeak").time()).isEqualTo(tic(set, "small.unpacked.mzpeak").time());
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("smallFixtures")
    void smallFixturesHaveOneTicWith48Points(FixtureSet set, String fixture) {
        List<Chromatogram> all = chromatograms(set, fixture);
        assertThat(all).hasSize(1);
        Chromatogram tic = all.get(0);
        assertThat(tic.index()).isZero();
        assertThat(tic.id()).isEqualTo("TIC");
        assertThat(tic.typeCurie()).isEqualTo("MS:1000235");
        assertThat(tic.size()).isEqualTo(48);
        assertThat(bitsSha256(tic.time())).isEqualTo(SMALL_TIC_TIME_SHA256);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixtureSet.class)
    void unpackedTicIsBitIdenticalToPreChangeOutput(FixtureSet set) {
        Chromatogram tic = tic(set, "small.unpacked.mzpeak");
        assertThat(tic.intensity()[0]).isEqualTo(1.5245068E7);
        assertThat(bitsSha256(tic.intensity())).isEqualTo(SMALL_TIC_INTENSITY_SHA256);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixtureSet.class)
    void hasUvChromatogramIdentityAndTimeAxesUnchanged(FixtureSet set) {
        List<Chromatogram> all = chromatograms(set, "has_uv.mzpeak");
        assertThat(all).hasSize(2);

        Chromatogram tic = all.get(0);
        assertThat(tic.index()).isZero();
        assertThat(tic.id()).isEqualTo("TIC");
        assertThat(tic.typeCurie()).isEqualTo("MS:1000235");
        assertThat(tic.size()).isEqualTo(212);
        assertThat(bitsSha256(tic.time())).isEqualTo(UV_TIC_TIME_SHA256);

        Chromatogram dad = all.get(1);
        assertThat(dad.index()).isEqualTo(1L);
        assertThat(dad.id()).isEqualTo("DAD1 A: Sig=272,16 Ref=360,100");
        assertThat(dad.typeCurie()).isEqualTo("MS:1000812");
        assertThat(dad.size()).isEqualTo(526);
        assertThat(bitsSha256(dad.time())).isEqualTo(UV_DAD1_TIME_SHA256);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixtureSet.class)
    void hasUvTicIntensityIsBitIdenticalToPreChangeOutput(FixtureSet set) {
        Chromatogram tic = tic(set, "has_uv.mzpeak");
        assertThat(tic.intensity()[0]).isEqualTo(168514.375);
        assertThat(bitsSha256(tic.intensity())).isEqualTo(UV_TIC_INTENSITY_SHA256);
    }

    // ---- intensities stored outside the literal 'intensity' column -------------------------------

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixtureSet.class)
    void numpressTicDecodesSlofIntensities(FixtureSet set) {
        // small.numpress stores the TIC intensity only as chunk.intensity_numpress_slof_bytes (lossy SLOF;
        // the hand-decoded values are within 1.25e-4 relative error of the point-layout TIC).
        Chromatogram expected = tic(set, "small.unpacked.mzpeak");
        Chromatogram actual = tic(set, "small.numpress.mzpeak");
        assertThat(actual.time()).isEqualTo(expected.time());
        assertThat(actual.intensity()).hasSize(48);
        for (int i = 0; i < expected.size(); i++) {
            double want = expected.intensity()[i];
            assertThat(actual.intensity()[i]).as("TIC intensity[%d]", i)
                    .isCloseTo(want, within(1e-3 * Math.abs(want)));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixtureSet.class)
    void hasUvDad1ReadsAbsorbanceColumn(FixtureSet set) {
        // has_uv stores the DAD1 absorbance in point.intensity_f32_au; point.intensity is null on all 526 rows.
        Chromatogram dad = chromatograms(set, "has_uv.mzpeak").get(1);
        assertThat(dad.size()).isEqualTo(526);
        assertThat(dad.intensity()[0]).isCloseTo(-0.0248, within(1e-3));
        assertThat(dad.intensity()[525]).isCloseTo(-0.021457672119140625, within(1e-9));
        assertThat(Arrays.stream(dad.intensity()).anyMatch(v -> v != 0.0)).as("not all zeros").isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(FixtureSet.class)
    void hasUvDad1IntensityEqualsStoredAbsorbanceColumn(FixtureSet set) {
        // Independent oracle: the DAD1 rows' intensity_f32_au values, read straight from the data file in row
        // order, widened from FLOAT to double.
        Path fixture = set.path("has_uv.mzpeak");
        DoubleArrayBuilder stored = new DoubleArrayBuilder();
        try (MzPeakSource src = MzPeakSource.open(fixture)) {
            String dataName = MzPeakManifest.fromSource(src).find("chromatogram", "data arrays").orElseThrow().name();
            ParquetGroups.forEach(src.inputFile(dataName), row -> {
                Group point = ParquetGroups.optGroup(row, "point");
                if (point != null && Long.valueOf(1L).equals(ParquetGroups.optLong(point, "chromatogram_index"))) {
                    stored.add(ParquetGroups.optDouble(point, "intensity_f32_au"));
                }
            });
        }
        assertThat(stored.size()).isEqualTo(526);
        assertThat(chromatograms(set, "has_uv.mzpeak").get(1).intensity()).isEqualTo(stored.toArray());
    }
}
