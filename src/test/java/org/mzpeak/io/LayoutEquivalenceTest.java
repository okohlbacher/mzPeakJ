package org.mzpeak.io;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the spectrum read path by value across storage layouts and containers. small.unpacked (point layout),
 * small.mzpeak (the same point layout in a STORED ZIP) and small.chunked (delta-chunk layout) hold the same 48
 * spectra, so every decoded m/z and intensity array must be bit-identical between them, with and without
 * profile reconstruction. This guards the chunk delta decode (null-aware MS:1003089, chunk_start-as-a-point)
 * for the 14 null-marked profile spectra, which the spot checks in {@link MzPeakContainersTest} do not reach.
 *
 * <p>Peaks: small.chunked additionally stores secondary centroid peaks for its 14 profile spectra (spectrum 0:
 * 1612 peaks), which small.unpacked does not have. Unpacked and chunked peak lists are therefore compared only
 * where the point layout has peaks (the 34 centroid spectra); the chunked-only lists are pinned through the
 * declared MS:1003059 count in {@link #declaredCountsHold(String, int, int)}.
 */
class LayoutEquivalenceTest {

    private static final String BASE = "src/test/resources/mzpeak/";
    private static final Path UNPACKED = Path.of(BASE + "small.unpacked.mzpeak");
    private static final Path PACKED = Path.of(BASE + "small.mzpeak");
    private static final Path CHUNKED = Path.of(BASE + "small.chunked.mzpeak");

    @ParameterizedTest(name = "reconstructProfile={0}")
    @ValueSource(booleans = {true, false})
    void chunkedAndZipEqualPointLayoutForAllSpectra(boolean reconstructProfile) {
        try (MzPeakReader point = MzPeakReader.open(UNPACKED, reconstructProfile);
             MzPeakReader packed = MzPeakReader.open(PACKED, reconstructProfile);
             MzPeakReader chunked = MzPeakReader.open(CHUNKED, reconstructProfile)) {
            assertThat(point.size()).isEqualTo(48);
            assertThat(packed.size()).isEqualTo(48);
            assertThat(chunked.size()).isEqualTo(48);

            int centroidPeakListsCompared = 0;
            for (int ordinal = 0; ordinal < point.size(); ordinal++) {
                Spectrum a = point.getSpectrumAt(ordinal);
                long idx = a.index();

                // single-file ZIP, same point layout: full equality, peaks included
                Spectrum z = packed.getSpectrum(idx).orElseThrow();
                assertIdentical("small.mzpeak spectrum " + idx + " m/z", a.mz(), z.mz());
                assertIdentical("small.mzpeak spectrum " + idx + " intensity", a.intensity(), z.intensity());
                assertThat(z.peaks()).as("small.mzpeak spectrum %d peaks", idx).isEqualTo(a.peaks());

                // delta-chunk layout: arrays for all 48, peaks only where the point layout has them
                Spectrum c = chunked.getSpectrum(idx).orElseThrow();
                assertIdentical("small.chunked spectrum " + idx + " m/z", a.mz(), c.mz());
                assertIdentical("small.chunked spectrum " + idx + " intensity", a.intensity(), c.intensity());
                if (!a.peaks().isEmpty()) {
                    assertThat(c.peaks()).as("small.chunked spectrum %d peaks", idx).isEqualTo(a.peaks());
                    centroidPeakListsCompared++;
                }
            }
            assertThat(centroidPeakListsCompared).as("centroid spectra with comparable peak lists").isEqualTo(34);
        }
    }

    /**
     * Every declared MS:1003060 (number of data points) and MS:1003059 (number of peaks) holds after decoding
     * with reconstruction. The number of spectra declaring each count is pinned too, so the invariant cannot
     * pass vacuously because a count stopped being decoded. small.chunked declares peaks for all 48 spectra,
     * which pins its 14 secondary centroid lists.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "small.unpacked.mzpeak, 14, 34",
            "small.mzpeak,          14, 34",
            "small.chunked.mzpeak,  14, 48",
            "small.numpress.mzpeak, 14, 34",
            "has_uv.mzpeak,          0, 212"
    })
    void declaredCountsHold(String fixture, int spectraDeclaringPoints, int spectraDeclaringPeaks) {
        try (MzPeakReader r = MzPeakReader.open(Path.of(BASE + fixture), true)) {
            int pointChecks = 0;
            int peakChecks = 0;
            for (Spectrum s : r) {
                SpectrumDescription d = s.description();
                if (d.numberOfDataPoints() > 0) {
                    assertThat((long) s.mz().length)
                            .as("%s spectrum %d: m/z length vs MS:1003060", fixture, s.index())
                            .isEqualTo(d.numberOfDataPoints());
                    pointChecks++;
                }
                if (d.numberOfPeaks() > 0) {
                    assertThat((long) s.peaks().size())
                            .as("%s spectrum %d: peak count vs MS:1003059", fixture, s.index())
                            .isEqualTo(d.numberOfPeaks());
                    peakChecks++;
                }
            }
            assertThat(pointChecks).as("%s: spectra declaring MS:1003060", fixture)
                    .isEqualTo(spectraDeclaringPoints);
            assertThat(peakChecks).as("%s: spectra declaring MS:1003059", fixture)
                    .isEqualTo(spectraDeclaringPeaks);
        }
    }

    /** {@link Arrays#equals(double[], double[])}, reporting the first differing position on failure. */
    static void assertIdentical(String what, double[] expected, double[] actual) {
        assertThat(Arrays.equals(expected, actual))
                .as(() -> {
                    int k = Arrays.mismatch(expected, actual);
                    return what + ": lengths " + expected.length + " vs " + actual.length + ", first mismatch at "
                            + k + (k < Math.min(expected.length, actual.length)
                                    ? " (" + expected[k] + " vs " + actual[k] + ")" : "");
                })
                .isTrue();
    }
}
