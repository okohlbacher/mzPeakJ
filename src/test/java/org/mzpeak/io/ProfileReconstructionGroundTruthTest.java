package org.mzpeak.io;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.model.Spectrum;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Checks profile reconstruction against ground truth. small.unpacked and small.chunked store the zero-intensity
 * flanks of their 14 profile spectra as null m/z, while small.numpress stores the same spectra without null marking,
 * so its m/z arrays hold every point. Filling the nulls from the stored {@code mz_delta_model} must land on the
 * Numpress m/z at every position. MS-Numpress linear is itself lossy by up to about 5e-7 Da on these spectra.
 */
class ProfileReconstructionGroundTruthTest {

    private static final String BASE = "src/test/resources/mzpeak/";
    private static final Path UNPACKED = Path.of(BASE + "small.unpacked.mzpeak");
    private static final Path NUMPRESS = Path.of(BASE + "small.numpress.mzpeak");

    /** Spectrum index to number of null-marked points, from small.unpacked with and without reconstruction. */
    private static final Map<Long, Integer> NULL_MARKED = new TreeMap<>();

    @BeforeAll
    static void findNullMarkedSpectra() {
        try (MzPeakReader full = MzPeakReader.open(UNPACKED, true);
             MzPeakReader stored = MzPeakReader.open(UNPACKED, false)) {
            for (int ordinal = 0; ordinal < full.size(); ordinal++) {
                Spectrum s = full.getSpectrumAt(ordinal);
                int missing = s.mz().length - stored.getSpectrum(s.index()).orElseThrow().mz().length;
                if (missing > 0) {
                    NULL_MARKED.put(s.index(), missing);
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"small.unpacked.mzpeak", "small.chunked.mzpeak"})
    void reconstructedMzMatchesNumpressGroundTruth(String fixture) {
        assertThat(NULL_MARKED).as("null-marked profile spectra").hasSize(14);
        assertThat(NULL_MARKED.values().stream().mapToInt(Integer::intValue).sum())
                .as("null-marked points").isEqualTo(39968);

        int offBy1e3 = 0;
        double maxDeviation = 0;
        String firstOff = null;
        try (MzPeakReader reader = MzPeakReader.open(Path.of(BASE + fixture), true);
             MzPeakReader truth = MzPeakReader.open(NUMPRESS)) {
            for (long index : NULL_MARKED.keySet()) {
                double[] mz = reader.getSpectrum(index).orElseThrow().mz();
                double[] expected = truth.getSpectrum(index).orElseThrow().mz();
                assertThat(mz).as("%s spectrum %d length", fixture, index).hasSameSizeAs(expected);
                for (int k = 0; k < mz.length; k++) {
                    double deviation = Math.abs(mz[k] - expected[k]);
                    maxDeviation = Math.max(maxDeviation, deviation);
                    if (!(deviation <= 1e-3)) {
                        offBy1e3++;
                        if (firstOff == null) {
                            firstOff = "spectrum " + index + " position " + k + ": " + mz[k] + " vs " + expected[k];
                        }
                    }
                }
            }
            assertThat(offBy1e3)
                    .as("%s positions off by more than 1e-3 Da (max %s; first: %s)", fixture, maxDeviation, firstOff)
                    .isZero();
            assertThat(maxDeviation).as("%s max |m/z - ground truth|", fixture).isLessThanOrEqualTo(1e-6);

            // left flank of a stored cluster, the case that stepped forwards from the previous cluster
            assertThat(reader.getSpectrum(0).orElseThrow().mz()[13409]).isCloseTo(1950.9901, within(1e-3));
        }
    }
}
