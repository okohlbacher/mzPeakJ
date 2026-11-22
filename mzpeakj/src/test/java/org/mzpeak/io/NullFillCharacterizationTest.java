package org.mzpeak.io;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterizes the null fill without a delta model ({@link NullFill#fill} with no model) and the drop of null-marked
 * points without reconstruction ({@link NullFill#dropNulls}). The fill with a delta model is pinned by
 * {@link PolynomialFillCharacterizationTest}.
 *
 * <p>R2 (X-8) replaced the 0.12.0 fill without a model, which interpolated interior runs linearly and flattened
 * leading and trailing runs to the nearest stored value ({@code interpolateGaps}), with the spec's rule: a flank steps
 * from its stored segment by the segment's second median spacing; next to a single stored value it steps by the
 * second median spacing of all of the spectrum's stored values (mzPeakJ's fallback, as there is no model); an
 * interior single null throws. Without a model the values agree with the model fill wherever no segment holds a single
 * stored value.
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

    // ---- fill without a model -----------------------------------------------------------------------------------

    /** R2: a leading run steps backwards by the local spacing (0.12.0: flattened to the first stored value). */
    @Test
    void leadingRunStepsBackwardsByTheLocalSpacing() {
        assertExact("leading run of 2", new double[]{
                204.82078179680173, 204.8210357880037, 204.8212897792057, 204.82154376977772,
                204.8217977609797, 204.82205175281157, 204.8223057452734, 204.8225597383652}, fill(0, 1));
    }

    /** R2: a trailing run steps forwards by the local spacing (0.12.0: flattened to the last stored value). */
    @Test
    void trailingRunStepsForwardsByTheLocalSpacing() {
        assertExact("trailing run of 2", new double[]{
                204.8207817999514, 204.82103578926362, 204.8212897792057, 204.82154376977772,
                204.8217977609797, 204.82205175281157, 204.82230574275366, 204.82255973269574}, fill(6, 7));
    }

    /** R2: an interior single null throws (0.12.0: interpolated linearly). */
    @Test
    void interiorSingletonThrows() {
        assertThatThrownBy(() -> fill(3)).isInstanceOf(MzPeakException.class)
                .hasMessageContaining("unpaired null m/z at position 3");
    }

    /** R2: an interior run of three is filled from both segments and halfway between (0.12.0: linearly). */
    @Test
    void interiorRunOfThreeIsFilledFromBothSegments() {
        assertExact("interior run of 3", new double[]{
                204.8207817999514, 204.82103578926362, 204.82128977857585, 204.82154376946278,
                204.82179776034974, 204.82205175281157, 204.8223057452734, 204.8225597383652}, fill(2, 3, 4));
    }

    /** R2: single nulls at both ends and an interior pair, the shape null marking produces. */
    @Test
    void leadingInteriorAndTrailingRunsTogether() {
        assertExact("runs at 0, 3..4 and 7", new double[]{
                204.82078179932154, 204.82103578926362, 204.8212897792057, 204.82154376914778,
                204.82179776034974, 204.82205175281157, 204.8223057452734, 204.82255973773525}, fill(0, 3, 4, 7));
    }

    /** R2: next to a single stored value the spectrum's second median spacing is used. */
    @Test
    void aSingleStoredValueStepsByTheSpectrumsSpacing() {
        assertExact("single stored value at 1", new double[]{
                204.82078179711675, 204.82103578926362, 204.82128978141048, 204.82154376883284,
                204.8217977609797, 204.82205175281157, 204.8223057452734, 204.8225597383652}, fill(0, 2, 3));
        assertExact("single stored values at 0 and 3", new double[]{
                204.8207817999514, 204.82103579304317, 204.82128977668594, 204.82154376977772,
                204.8217977628695, 204.82205175218164, 204.8223057452734, 204.8225597383652}, fill(1, 2, 4, 5));
    }

    @Test
    void allNullInputIsLeftUnfilled() {
        double[] allNaN = new double[LENGTH];
        Arrays.fill(allNaN, Double.NaN);
        assertExact("all null", allNaN, fill(0, 1, 2, 3, 4, 5, 6, 7));
    }

    @Test
    void inputWithoutNullsIsUnchanged() {
        assertExact("no null", STORED_MZ, fill());
    }

    // ---- dropNulls ----------------------------------------------------------------------------------------------

    @Test
    void withoutReconstructionNullMzIsDroppedWithItsIntensity() {
        SpectrumArrayStore.Arrays interior = dropWithNulls(2, 3, 4);
        assertExact("m/z without 2..4", new double[]{
                204.8207817999514, 204.82103578926362, 204.82205175281157, 204.8223057452734, 204.8225597383652},
                interior.mz());
        assertExact("intensity without 2..4", new double[]{
                932.3289794921875, 2340.11572265625, 1139.264892578125, 0.0, 796.740966796875}, interior.intensity());

        SpectrumArrayStore.Arrays mixed = dropWithNulls(0, 3, 4, 7);
        assertExact("m/z without 0, 3, 4, 7", new double[]{
                204.82103578926362, 204.8212897792057, 204.82205175281157, 204.8223057452734}, mixed.mz());
        assertExact("intensity without 0, 3, 4, 7", new double[]{
                2340.11572265625, 3257.099609375, 1139.264892578125, 0.0}, mixed.intensity());
    }

    @Test
    void withoutReconstructionAnAllNullInputBecomesEmpty() {
        SpectrumArrayStore.Arrays arrays = dropWithNulls(0, 1, 2, 3, 4, 5, 6, 7);
        assertThat(arrays.mz()).isEmpty();
        assertThat(arrays.intensity()).isEmpty();
    }

    @Test
    void withoutReconstructionAndWithoutNullsTheInputArraysAreReturned() {
        double[] mz = storedMz.clone();
        double[] intensity = storedIntensity.clone();
        SpectrumArrayStore.Arrays arrays = NullFill.dropNulls(mz, intensity, Map.of());
        assertThat(arrays.mz()).isSameAs(mz);
        assertThat(arrays.intensity()).isSameAs(intensity);
        assertExact("m/z", STORED_MZ, arrays.mz());
        assertExact("intensity", STORED_INTENSITY, arrays.intensity());
    }

    /** The fill of a single array (the point layout) works in place and returns that array. */
    @Test
    void withReconstructionNullMzIsFilledInPlace() {
        double[] mz = withNulls(storedMz, 0, 3, 4, 7);
        assertThat(NullFill.fill(List.of(mz), false, null, "spectra_data.parquet", SignalEntity.SPECTRUM.ref(0)))
                .isSameAs(mz);
        assertExact("m/z", fill(0, 3, 4, 7), mz);
    }

    /** Copy the stored m/z, set {@code nullPositions} to NaN, and run the fill without a model on the copy. */
    private static double[] fill(int... nullPositions) {
        double[] m = withNulls(storedMz, nullPositions);
        NullFill.fill(List.of(m), false, null, "spectra_data.parquet", SignalEntity.SPECTRUM.ref(0));
        return m;
    }

    /** {@link NullFill#dropNulls} on copies of the stored window, NaN m/z at {@code nullPositions}. */
    private static SpectrumArrayStore.Arrays dropWithNulls(int... nullPositions) {
        return NullFill.dropNulls(withNulls(storedMz, nullPositions), storedIntensity.clone(), Map.of());
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
