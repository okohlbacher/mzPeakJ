package org.mzpeak.io;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes the null fill without a delta model: {@link NullFill#interpolateGaps} and
 * {@link NullFill#finalizeArrays} for both {@code reconstructProfile} values. The fill with a delta model,
 * {@code polynomialFill}, is pinned by {@link PolynomialFillCharacterizationTest}.
 *
 * <p>The input is the window that test uses: eight consecutive stored m/z values of small.unpacked spectrum 0
 * (stored ordinals 50..57) and their intensities, with NaN injected where a null run should be. The expected values
 * are the exact doubles the fill produces (Double.toString round-trips them) and are compared bit for bit with
 * {@link Double#compare}.
 */
class NullFillCharacterizationTest {

    private static final Path UNPACKED = Fixtures.legacy("small.unpacked.mzpeak");
    private static final int FROM = 50;
    private static final int LENGTH = 8;

    /** small.unpacked spectrum 0, stored (reconstructProfile=false) m/z ordinals 50..57. */
    private static final double[] STORED_MZ = {
            204.8207817999514, 204.82103578926362, 204.8212897792057, 204.82154376977772,
            204.8217977609797, 204.82205175281157, 204.8223057452734, 204.8225597383652};
    /** small.unpacked spectrum 0, stored intensity ordinals 50..57. */
    private static final double[] STORED_INTENSITY = {
            932.3289794921875, 2340.11572265625, 3257.099609375, 3419.59228515625,
            2641.063720703125, 1139.264892578125, 0.0, 796.740966796875};

    private static double[] storedMz;
    private static double[] storedIntensity;

    @BeforeAll
    static void loadFixture() {
        try (MzPeakReader reader = MzPeakReader.open(UNPACKED, false)) {
            var spectrum = reader.getSpectrum(0).orElseThrow();
            storedMz = Arrays.copyOfRange(spectrum.mz(), FROM, FROM + LENGTH);
            storedIntensity = Arrays.copyOfRange(spectrum.intensity(), FROM, FROM + LENGTH);
        }
    }

    @Test
    void fixtureInputIsPinned() {
        assertExact("stored m/z", STORED_MZ, storedMz);
        assertExact("stored intensity", STORED_INTENSITY, storedIntensity);
    }

    // ---- interpolateGaps --------------------------------------------------------------------------------------

    @Test
    void leadingRunIsFlattenedToTheFirstStoredValue() {
        assertExact("leading run of 2", new double[]{
                204.8212897792057, 204.8212897792057, 204.8212897792057, 204.82154376977772,
                204.8217977609797, 204.82205175281157, 204.8223057452734, 204.8225597383652}, interpolate(0, 1));
    }

    @Test
    void trailingRunIsFlattenedToTheLastStoredValue() {
        assertExact("trailing run of 2", new double[]{
                204.8207817999514, 204.82103578926362, 204.8212897792057, 204.82154376977772,
                204.8217977609797, 204.82205175281157, 204.82205175281157, 204.82205175281157}, interpolate(6, 7));
    }

    @Test
    void interiorSingletonIsInterpolatedLinearly() {
        assertExact("interior singleton", new double[]{
                204.8207817999514, 204.82103578926362, 204.8212897792057, 204.82154377009272,
                204.8217977609797, 204.82205175281157, 204.8223057452734, 204.8225597383652}, interpolate(3));
    }

    @Test
    void interiorRunOfThreeIsInterpolatedLinearly() {
        assertExact("interior run of 3", new double[]{
                204.8207817999514, 204.82103578926362, 204.8212897801506, 204.8215437710376,
                204.82179776192459, 204.82205175281157, 204.8223057452734, 204.8225597383652}, interpolate(2, 3, 4));
    }

    @Test
    void leadingInteriorAndTrailingRunsTogether() {
        assertExact("runs at 0, 3..4 and 7", new double[]{
                204.82103578926362, 204.82103578926362, 204.8212897792057, 204.82154377040766,
                204.82179776160962, 204.82205175281157, 204.8223057452734, 204.8223057452734}, interpolate(0, 3, 4, 7));
    }

    @Test
    void allNullInputIsLeftUnfilled() {
        double[] allNaN = new double[LENGTH];
        Arrays.fill(allNaN, Double.NaN);
        assertExact("all null", allNaN, interpolate(0, 1, 2, 3, 4, 5, 6, 7));
    }

    @Test
    void inputWithoutNullsIsUnchanged() {
        assertExact("no null", STORED_MZ, interpolate());
    }

    // ---- finalizeArrays ---------------------------------------------------------------------------------------

    @Test
    void withoutReconstructionNullMzIsDroppedWithItsIntensity() {
        SpectrumArrayStore.Arrays interior = finalizeWithNulls(false, 2, 3, 4);
        assertExact("m/z without 2..4", new double[]{
                204.8207817999514, 204.82103578926362, 204.82205175281157, 204.8223057452734, 204.8225597383652},
                interior.mz());
        assertExact("intensity without 2..4", new double[]{
                932.3289794921875, 2340.11572265625, 1139.264892578125, 0.0, 796.740966796875}, interior.intensity());

        SpectrumArrayStore.Arrays mixed = finalizeWithNulls(false, 0, 3, 4, 7);
        assertExact("m/z without 0, 3, 4, 7", new double[]{
                204.82103578926362, 204.8212897792057, 204.82205175281157, 204.8223057452734}, mixed.mz());
        assertExact("intensity without 0, 3, 4, 7", new double[]{
                2340.11572265625, 3257.099609375, 1139.264892578125, 0.0}, mixed.intensity());
    }

    @Test
    void withoutReconstructionAnAllNullInputBecomesEmpty() {
        SpectrumArrayStore.Arrays arrays = finalizeWithNulls(false, 0, 1, 2, 3, 4, 5, 6, 7);
        assertThat(arrays.mz()).isEmpty();
        assertThat(arrays.intensity()).isEmpty();
    }

    @Test
    void withoutReconstructionAndWithoutNullsTheInputArraysAreReturned() {
        double[] mz = storedMz.clone();
        double[] intensity = storedIntensity.clone();
        SpectrumArrayStore.Arrays arrays = NullFill.finalizeArrays(mz, intensity, false);
        assertThat(arrays.mz()).isSameAs(mz);
        assertThat(arrays.intensity()).isSameAs(intensity);
        assertExact("m/z", STORED_MZ, arrays.mz());
        assertExact("intensity", STORED_INTENSITY, arrays.intensity());
    }

    @Test
    void withReconstructionNullMzIsInterpolatedInPlace() {
        double[] mz = withNulls(storedMz, 0, 3, 4, 7);
        double[] intensity = storedIntensity.clone();
        SpectrumArrayStore.Arrays arrays = NullFill.finalizeArrays(mz, intensity, true);
        assertThat(arrays.mz()).isSameAs(mz);
        assertThat(arrays.intensity()).isSameAs(intensity);
        assertExact("m/z", interpolate(0, 3, 4, 7), arrays.mz());
        assertExact("intensity", STORED_INTENSITY, arrays.intensity());
    }

    /** Copy the stored m/z, set {@code nullPositions} to NaN, and run the interpolation on the copy. */
    private static double[] interpolate(int... nullPositions) {
        double[] m = withNulls(storedMz, nullPositions);
        NullFill.interpolateGaps(m);
        return m;
    }

    /** {@link NullFill#finalizeArrays} on copies of the stored window, NaN m/z at {@code nullPositions}. */
    private static SpectrumArrayStore.Arrays finalizeWithNulls(boolean reconstructProfile, int... nullPositions) {
        return NullFill.finalizeArrays(
                withNulls(storedMz, nullPositions), storedIntensity.clone(), reconstructProfile);
    }

    private static double[] withNulls(double[] values, int... nullPositions) {
        double[] m = values.clone();
        for (int k : nullPositions) {
            m[k] = Double.NaN;
        }
        return m;
    }

    private static void assertExact(String what, double[] expected, double[] actual) {
        assertThat(actual).as("%s length", what).hasSize(expected.length);
        for (int k = 0; k < expected.length; k++) {
            assertThat(Double.compare(actual[k], expected[k]))
                    .as("%s at %d: expected %s but was %s", what, k, expected[k], actual[k])
                    .isZero();
        }
    }
}
