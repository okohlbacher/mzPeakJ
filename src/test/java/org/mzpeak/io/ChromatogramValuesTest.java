package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Chromatogram;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Chromatogram time and intensity values, checked against the vendored fixtures. The point-layout
 * {@code small.unpacked.mzpeak} TIC is the reference for the other {@code small.*} containers; the exact
 * pre-existing output of the arrays that must not change is pinned by a bit-exact SHA-256 checksum.
 */
class ChromatogramValuesTest {

    private static final String BASE = "src/test/resources/mzpeak/";

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

    private static Chromatogram tic(String fixture) {
        try (MzPeakReader r = MzPeakReader.open(Path.of(BASE + fixture))) {
            return r.getChromatogram(0).orElseThrow();
        }
    }

    private static List<Chromatogram> chromatograms(String fixture) {
        try (MzPeakReader r = MzPeakReader.open(Path.of(BASE + fixture))) {
            return r.chromatograms();
        }
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

    @ParameterizedTest
    @ValueSource(strings = {"small.mzpeak", "small.chunked.mzpeak"})
    void layoutVariantTicEqualsUnpackedTic(String fixture) {
        Chromatogram expected = tic("small.unpacked.mzpeak");
        Chromatogram actual = tic(fixture);
        assertThat(actual.time()).isEqualTo(expected.time());
        assertThat(actual.intensity()).isEqualTo(expected.intensity());
    }

    @Test
    void numpressTicTimeEqualsUnpackedTime() {
        assertThat(tic("small.numpress.mzpeak").time()).isEqualTo(tic("small.unpacked.mzpeak").time());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "small.unpacked.mzpeak", "small.mzpeak", "small.chunked.mzpeak", "small.numpress.mzpeak"})
    void smallFixturesHaveOneTicWith48Points(String fixture) {
        List<Chromatogram> all = chromatograms(fixture);
        assertThat(all).hasSize(1);
        Chromatogram tic = all.get(0);
        assertThat(tic.index()).isZero();
        assertThat(tic.id()).isEqualTo("TIC");
        assertThat(tic.typeCurie()).isEqualTo("MS:1000235");
        assertThat(tic.size()).isEqualTo(48);
        assertThat(bitsSha256(tic.time())).isEqualTo(SMALL_TIC_TIME_SHA256);
    }

    @Test
    void unpackedTicIsBitIdenticalToPreChangeOutput() {
        Chromatogram tic = tic("small.unpacked.mzpeak");
        assertThat(tic.intensity()[0]).isEqualTo(1.5245068E7);
        assertThat(bitsSha256(tic.intensity())).isEqualTo(SMALL_TIC_INTENSITY_SHA256);
    }

    @Test
    void hasUvChromatogramIdentityAndTimeAxesUnchanged() {
        List<Chromatogram> all = chromatograms("has_uv.mzpeak");
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

    @Test
    void hasUvTicIntensityIsBitIdenticalToPreChangeOutput() {
        Chromatogram tic = tic("has_uv.mzpeak");
        assertThat(tic.intensity()[0]).isEqualTo(168514.375);
        assertThat(bitsSha256(tic.intensity())).isEqualTo(UV_TIC_INTENSITY_SHA256);
    }

    // ---- intensities stored outside the literal 'intensity' column -------------------------------

    @Test
    void numpressTicDecodesSlofIntensities() {
        // small.numpress stores the TIC intensity only as chunk.intensity_numpress_slof_bytes (lossy SLOF;
        // the hand-decoded values are within 1.25e-4 relative error of the point-layout TIC).
        Chromatogram expected = tic("small.unpacked.mzpeak");
        Chromatogram actual = tic("small.numpress.mzpeak");
        assertThat(actual.time()).isEqualTo(expected.time());
        assertThat(actual.intensity()).hasSize(48);
        for (int i = 0; i < expected.size(); i++) {
            double want = expected.intensity()[i];
            assertThat(actual.intensity()[i]).as("TIC intensity[%d]", i)
                    .isCloseTo(want, within(1e-3 * Math.abs(want)));
        }
    }

    @Test
    void hasUvDad1ReadsAbsorbanceColumn() {
        // has_uv stores the DAD1 absorbance in point.intensity_f32_au; point.intensity is null on all 526 rows.
        Chromatogram dad = chromatograms("has_uv.mzpeak").get(1);
        assertThat(dad.size()).isEqualTo(526);
        assertThat(dad.intensity()[0]).isCloseTo(-0.0248, within(1e-3));
        assertThat(dad.intensity()[525]).isCloseTo(-0.021457672119140625, within(1e-9));
        assertThat(Arrays.stream(dad.intensity()).anyMatch(v -> v != 0.0)).as("not all zeros").isTrue();
    }

    @Test
    void hasUvDad1IntensityEqualsStoredAbsorbanceColumn() {
        // Independent oracle: the DAD1 rows' intensity_f32_au values, read straight from the data file in row
        // order, widened from FLOAT to double.
        Path fixture = Path.of(BASE + "has_uv.mzpeak");
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
        assertThat(chromatograms("has_uv.mzpeak").get(1).intensity()).isEqualTo(stored.toArray());
    }
}
