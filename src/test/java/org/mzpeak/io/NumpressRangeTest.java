package org.mzpeak.io;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.SignalSnapshotTest.SignalDigest;
import org.mzpeak.io.WriterSnapshotTest.Overload;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Spectrum;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The value ranges of the mzPeak Numpress chunk layout (MZ-23, design §5.7).
 *
 * <p>Characterization, recorded on the base code (da74f60) before MZ-23 changed the PIC decode: the arrays
 * {@link MzPeakReader} reads back from the PIC outputs of legacy {@code small.unpacked} that
 * {@link WriterSnapshotTest} pins ({@link Overload#WRITE_DIRECTORY_NUMPRESS_PIC} and
 * {@link Overload#WRITE_DIRECTORY_NUMPRESS_PIC_PACKED}), every spectrum's m/z, intensity, centroid m/z and centroid
 * intensity digested like {@link SignalSnapshotTest} (raw IEEE-754 bits), in both reconstructProfile modes. Every
 * PIC payload of these outputs has 8 bytes or more (the base code decodes them) and every count is below 2^31, so the
 * PIC decode of MZ-23 must read them back unchanged.
 */
class NumpressRangeTest {

    private static final Path SMALL = Fixtures.legacy("small.unpacked.mzpeak");

    /**
     * The read-back digest recorded on the base code (da74f60), the same for both outputs and both modes: the metadata
     * layout does not change the signal, and Numpress chunks carry no null markers.
     */
    private static final String PIC_READ_BACK = "entries=48 absent=none values=536796"
            + " sha256=d48b2d8b74bde14d859dcee4b43fcda6ffc8155cd7820ff9ff96578f7ed18e5f";

    private static Source source;

    static Stream<Arguments> picOutputs() {
        return Stream.of(Overload.WRITE_DIRECTORY_NUMPRESS_PIC, Overload.WRITE_DIRECTORY_NUMPRESS_PIC_PACKED)
                .flatMap(o -> Stream.of(Arguments.of(o, false), Arguments.of(o, true)));
    }

    @ParameterizedTest(name = "{0}, reconstructProfile={1}")
    @MethodSource("picOutputs")
    void picOutputsOfLegacySmallReadBackAsOnTheBaseCode(Overload overload, boolean reconstructProfile,
                                                         @TempDir Path tmp) {
        Path out = tmp.resolve("out.mzpeak");
        overload.write.accept(out, source());
        SignalDigest digest = new SignalDigest("");
        try (MzPeakReader reader = MzPeakReader.open(out, reconstructProfile)) {
            for (Spectrum s : reader) {
                digest.entry(s.index(), s.mz(), s.intensity(), peakMz(s), peakIntensity(s));
            }
        }
        assertThat(digest.summary()).isEqualTo(PIC_READ_BACK);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static synchronized Source source() {
        if (source == null) {
            source = ForwardBackwardCorpusTest.readSource(SMALL);
        }
        return source;
    }

    private static double[] peakMz(Spectrum s) {
        List<CentroidPeak> peaks = s.peaks();
        double[] out = new double[peaks.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = peaks.get(i).mz();
        }
        return out;
    }

    private static double[] peakIntensity(Spectrum s) {
        List<CentroidPeak> peaks = s.peaks();
        double[] out = new double[peaks.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = peaks.get(i).intensity();
        }
        return out;
    }
}
