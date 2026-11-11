package org.mzpeak.io;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.model.Chromatogram;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-12a (V-21): the chromatogram time unit. Every vendored fixture stores its chromatogram times in minutes, so its
 * chromatograms read the same values before and after the reader stopped converting to minutes: the digests below
 * were taken on the code before V-21 (b6aa051 lineage).
 */
class ChromatogramTimeUnitTest {

    // The digests of the chromatograms of each fixture, taken on the code before V-21.
    private static final String SMALL = "b0c18d39bc9630fe3de04717d552c57fc9a55b3b81769e38b61805b6c4a71857";
    private static final String NUMPRESS = "95149d8f18461f72ec4776c06de5017803cece5f7dce0df345568dd46cafa1bd";
    private static final String HAS_UV = "0683e1579beb11c7920f004dd1d02b0b81d7cbd352d24ab4d382f63de1b1d7a6";
    private static final String DIA_PASEF = "da9176a14d8c67605f9321d928d9da3620b74e00c2f71f60f7662157c91e341e";
    /** No chromatograms: the SHA-256 of nothing. */
    private static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /**
     * Each fixture with chromatograms, the number it has and the SHA-256 of their index, id, time and intensity
     * values ({@link #digest}).
     */
    static Stream<Arguments> fixtures() {
        return Stream.of(
                Arguments.of(Fixtures.current("small.mzpeak"), 1, SMALL),
                Arguments.of(Fixtures.current("small.chunked.mzpeak"), 1, SMALL),
                Arguments.of(Fixtures.current("small.numpress.mzpeak"), 1, NUMPRESS),
                Arguments.of(Fixtures.current("small.unpacked.mzpeak"), 1, SMALL),
                Arguments.of(Fixtures.current("has_uv.mzpeak"), 2, HAS_UV),
                Arguments.of(Fixtures.current("diaPASEF.ref.mzpeak"), 2, DIA_PASEF),
                Arguments.of(Fixtures.current("diaPASEF.grid.mzpeak"), 2, DIA_PASEF),
                Arguments.of(Fixtures.current("Example_Processed.img.mzpeak"), 0, EMPTY),
                Arguments.of(Fixtures.legacy("small.mzpeak"), 1, SMALL),
                Arguments.of(Fixtures.legacy("small.chunked.mzpeak"), 1, SMALL),
                Arguments.of(Fixtures.legacy("small.numpress.mzpeak"), 1, NUMPRESS),
                Arguments.of(Fixtures.legacy("small.unpacked.mzpeak"), 1, SMALL),
                Arguments.of(Fixtures.legacy("has_uv.mzpeak"), 2, HAS_UV));
    }

    /** (a) Every fixture's chromatograms read the values pinned before V-21. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void everyFixtureReadsItsChromatogramsUnchanged(Path fixture, int count, String sha256) {
        try (MzPeakReader reader = MzPeakReader.open(fixture)) {
            List<Chromatogram> chromatograms = reader.chromatograms();
            assertThat(chromatograms).hasSize(count);
            assertThat(digest(chromatograms)).isEqualTo(sha256);
        }
    }

    /** The SHA-256 of the index, id, time and intensity bits of each of {@code chromatograms}, in order. */
    static String digest(List<Chromatogram> chromatograms) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        for (Chromatogram c : chromatograms) {
            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES * (2 + 2 * c.size()));
            buffer.putLong(c.index()).putLong(c.size());
            for (double t : c.time()) {
                buffer.putLong(Double.doubleToRawLongBits(t));
            }
            for (double v : c.intensity()) {
                buffer.putLong(Double.doubleToRawLongBits(v));
            }
            sha.update(buffer.array());
            sha.update(String.valueOf(c.id()).getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(sha.digest());
    }
}
