package org.mzpeak.io;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mzpeak.model.Spectrum;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Checks profile reconstruction against ground truth, in both fixture sets. small.unpacked and small.chunked store
 * the zero-intensity flanks of their 14 profile spectra as null m/z, while small.numpress stores the same spectra
 * without null marking, so its m/z arrays hold every point. Filling the nulls from the stored m/z delta model
 * ({@code mz_delta_model} in the legacy set, MS:1003821 {@code polynomial_delta_coordinate_interpolation} in the
 * current set) must land on the Numpress m/z at every position. MS-Numpress linear is itself lossy by up to about
 * 5e-7 Da on these spectra.
 */
class ProfileReconstructionGroundTruthTest {

    private static final String UNPACKED = "small.unpacked.mzpeak";
    private static final String NUMPRESS = "small.numpress.mzpeak";

    /**
     * Per fixture set, spectrum index to number of null-marked points, from small.unpacked with and without
     * reconstruction.
     */
    private static final Map<String, Map<Long, Integer>> NULL_MARKED = new HashMap<>();

    @BeforeAll
    static void findNullMarkedSpectra() {
        for (String set : List.of("legacy", "current")) {
            Map<Long, Integer> marked = new TreeMap<>();
            try (MzPeakReader full = MzPeakReader.open(fixture(set, UNPACKED), true);
                 MzPeakReader stored = MzPeakReader.open(fixture(set, UNPACKED), false)) {
                for (int ordinal = 0; ordinal < full.size(); ordinal++) {
                    Spectrum s = full.getSpectrumAt(ordinal);
                    int missing = s.mz().length - stored.getSpectrum(s.index()).orElseThrow().mz().length;
                    if (missing > 0) {
                        marked.put(s.index(), missing);
                    }
                }
            }
            NULL_MARKED.put(set, marked);
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "legacy,  small.unpacked.mzpeak",
            "legacy,  small.chunked.mzpeak",
            "current, small.unpacked.mzpeak",
            "current, small.chunked.mzpeak"
    })
    void reconstructedMzMatchesNumpressGroundTruth(String set, String fixture) {
        Map<Long, Integer> nullMarked = NULL_MARKED.get(set);
        assertThat(nullMarked).as("%s null-marked profile spectra", set).hasSize(14);
        assertThat(nullMarked.values().stream().mapToInt(Integer::intValue).sum())
                .as("%s null-marked points", set).isEqualTo(39968);

        int offBy1e3 = 0;
        double maxDeviation = 0;
        String firstOff = null;
        try (MzPeakReader reader = MzPeakReader.open(fixture(set, fixture), true);
             MzPeakReader truth = MzPeakReader.open(fixture(set, NUMPRESS))) {
            for (long index : nullMarked.keySet()) {
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

    /** The dataset {@code name} of the fixture set {@code set}, {@code legacy} or {@code current}. */
    private static Path fixture(String set, String name) {
        return switch (set) {
            case "legacy" -> Fixtures.legacy(name);
            case "current" -> Fixtures.current(name);
            default -> throw new IllegalArgumentException("no fixture set " + set);
        };
    }
}
