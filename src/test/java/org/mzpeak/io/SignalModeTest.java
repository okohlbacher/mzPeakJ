package org.mzpeak.io;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.MzPeakReader.Options;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Spectrum;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

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
