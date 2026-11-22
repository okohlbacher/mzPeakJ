package org.mzpeak.io;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.model.Spectrum;

import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes how the vendored fixtures read with null-marked profile points reconstructed (the default) against
 * the read without reconstruction: which spectra and how many points are filled, that the stored points keep their
 * values and order, and the values of a few filled points. small.mzpeak, small.unpacked and Example_Processed.img
 * store their null marks in the point layout, small.chunked in delta-encoded chunk rows; the other fixtures have
 * none.
 */
class NullFillFixtureCharacterizationTest {

    static Stream<Arguments> fixtures() {
        return Stream.of(
                Arguments.of(Fixtures.current("small.mzpeak"), 14, 39968),
                Arguments.of(Fixtures.current("small.chunked.mzpeak"), 14, 39968),
                Arguments.of(Fixtures.current("small.numpress.mzpeak"), 0, 0),
                Arguments.of(Fixtures.current("small.unpacked.mzpeak"), 14, 39968),
                Arguments.of(Fixtures.current("has_uv.mzpeak"), 0, 0),
                Arguments.of(Fixtures.current("diaPASEF.ref.mzpeak"), 0, 0),
                Arguments.of(Fixtures.current("diaPASEF.grid.mzpeak"), 0, 0),
                Arguments.of(Fixtures.current("Example_Processed.img.mzpeak"), 9, 13286),
                Arguments.of(Fixtures.legacy("small.mzpeak"), 14, 39968),
                Arguments.of(Fixtures.legacy("small.chunked.mzpeak"), 14, 39968),
                Arguments.of(Fixtures.legacy("small.numpress.mzpeak"), 0, 0),
                Arguments.of(Fixtures.legacy("small.unpacked.mzpeak"), 14, 39968),
                Arguments.of(Fixtures.legacy("has_uv.mzpeak"), 0, 0));
    }

    /**
     * Every spectrum reads; a reconstructed spectrum has its stored points in stored order with the filled points
     * between them, every filled m/z a number with intensity 0, and the m/z strictly ascending.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void filledPointsSitBetweenTheStoredPoints(Path fixture, int spectra, int points) {
        int filledSpectra = 0;
        long filledPoints = 0;
        try (MzPeakReader filled = MzPeakReader.open(fixture, true);
             MzPeakReader stored = MzPeakReader.open(fixture, false)) {
            for (int ordinal = 0; ordinal < filled.size(); ordinal++) {
                Spectrum f = filled.getSpectrumAt(ordinal);
                Spectrum s = stored.getSpectrumAt(ordinal);
                int missing = f.mz().length - s.mz().length;
                assertThat(missing).as("spectrum %d", f.index()).isNotNegative();
                if (missing == 0) {
                    assertThat(f.mz()).as("spectrum %d m/z", f.index()).isEqualTo(s.mz());
                    continue;
                }
                filledSpectra++;
                filledPoints += missing;
                int j = 0;
                for (int k = 0; k < f.mz().length; k++) {
                    double mz = f.mz()[k];
                    assertThat(Double.isNaN(mz)).as("spectrum %d point %d is NaN", f.index(), k).isFalse();
                    if (k > 0) {
                        assertThat(mz).as("spectrum %d point %d ascending", f.index(), k)
                                .isGreaterThan(f.mz()[k - 1]);
                    }
                    if (j < s.mz().length && Double.compare(mz, s.mz()[j]) == 0) {
                        assertThat(f.intensity()[k]).as("spectrum %d stored point %d", f.index(), k)
                                .isEqualTo(s.intensity()[j]);
                        j++;
                    } else {
                        assertThat(f.intensity()[k]).as("spectrum %d filled point %d", f.index(), k).isZero();
                    }
                }
                assertThat(j).as("spectrum %d stored points found in order", f.index()).isEqualTo(s.mz().length);
            }
        }
        assertThat(filledSpectra).as("spectra with filled points").isEqualTo(spectra);
        assertThat(filledPoints).as("filled points").isEqualTo(points);
    }

    /**
     * The spectra the HEAD Python reader's golden output stores in full match the default read: stored points
     * exactly, filled points within the golden tolerance ({@link Golden.Tolerance#DEFAULT}).
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"small.mzpeak", "small.chunked.mzpeak", "small.unpacked.mzpeak", "Example_Processed.img.mzpeak"})
    void fullGoldenSpectraMatchWithinTheTolerance(String fixture) {
        Golden golden = Golden.load(fixture);
        int compared = 0;
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            for (Golden.FullArray full : golden.fullArrays()) {
                if (full.kind() != Golden.Kind.S) {
                    continue;
                }
                Spectrum s = reader.getSpectrum(Long.parseLong(full.key())).orElseThrow();
                assertThat(Golden.Comparator.compareFull(full, s.mz(), s.intensity(), Golden.Tolerance.DEFAULT))
                        .as("spectrum %s", full.key()).isEmpty();
                compared++;
            }
        }
        assertThat(compared).isPositive();
    }

    /**
     * The values of some filled points of spectrum 0:an interior pair (7, 8) in the point and the chunk layout, and
     * in small.chunked the last point of its first chunk row and the first of its second (775, 776), which are the two
     * nulls of one pair split across the rows.
     */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "small.mzpeak,                 7,   202.60831465319626",
            "small.mzpeak,                 8,   204.75933490906888",
            "small.mzpeak,                 775, 252.42923215778364",
            "small.mzpeak,                 776, 252.96506619710865",
            "small.chunked.mzpeak,         7,   202.60831465319626",
            "small.chunked.mzpeak,         8,   204.75933490906888",
            "small.chunked.mzpeak,         775, 252.42923215778364",
            "small.chunked.mzpeak,         776, 252.96506619710865",
            "Example_Processed.img.mzpeak,  11,  108.91666869665156",
            "Example_Processed.img.mzpeak,  12,  109.16666717408039"
    })
    void filledValuesOfSpectrumZero(String fixture, int position, double expected) {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            double actual = reader.getSpectrum(0).orElseThrow().mz()[position];
            assertThat(Double.compare(actual, expected)).as("expected %s but was %s", expected, actual).isZero();
        }
    }
}
