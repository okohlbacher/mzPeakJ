package org.mzpeak.io;

import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes {@link NullFill#polynomialFill} for every shape of null run. Every null run in the vendored
 * fixtures is an interior pair, so the other shapes are built from fixture data: eight consecutive stored m/z values
 * of small.unpacked spectrum 0 (stored ordinals 50..57, which sit on consecutive grid points) and that spectrum's
 * stored {@code mz_delta_model}, with NaN injected where a null run should be.
 *
 * <p>The expected values are the exact doubles the fill produces (Double.toString round-trips them) and are
 * compared bit for bit with {@link Double#compare}. The last null of an interior run of two or more is the left
 * flank of the next stored cluster; it is pinned separately in {@link #lastNullOfInteriorRun()}.
 */
class PolynomialFillCharacterizationTest {

    private static final Path UNPACKED = Fixtures.legacy("small.unpacked.mzpeak");
    private static final int FROM = 50;
    private static final int LENGTH = 8;

    /** small.unpacked spectrum 0, stored (reconstructProfile=false) m/z ordinals 50..57. */
    private static final double[] STORED = {
            204.8207817999514, 204.82103578926362, 204.8212897792057, 204.82154376977772,
            204.8217977609797, 204.82205175281157, 204.8223057452734, 204.8225597383652};
    /** small.unpacked spectrum 0, mz_delta_model. */
    private static final double[] MODEL = {-2.2113927547804747E-8, 9.697546039425898E-11, 6.054228626999033E-9};

    private static double[] stored;
    private static double[] beta;

    @BeforeAll
    static void loadFixture() {
        beta = SpectrumMetadataDecoder.decode(new LocalInputFile(UNPACKED.resolve("spectra_metadata.parquet")))
                .deltaModels().get(0L);
        try (MzPeakReader reader = MzPeakReader.open(UNPACKED, false)) {
            stored = Arrays.copyOfRange(reader.getSpectrum(0).orElseThrow().mz(), FROM, FROM + LENGTH);
        }
    }

    @Test
    void fixtureInputIsPinned() {
        assertExact("stored m/z", STORED, stored);
        assertExact("mz_delta_model", MODEL, beta);
    }

    @Test
    void leadingRunOfTwoStepsBackwards() {
        assertExact("leading run of 2", new double[]{
                204.82078181323647, 204.8210357959061, 204.8212897792057, 204.82154376977772,
                204.8217977609797, 204.82205175281157, 204.8223057452734, 204.8225597383652}, fill(0, 1));
    }

    @Test
    void trailingRunOfTwoStepsForwards() {
        assertExact("trailing run of 2", new double[]{
                204.8207817999514, 204.82103578926362, 204.8212897792057, 204.82154376977772,
                204.8217977609797, 204.82205175281157, 204.82230573800098, 204.82255972382032}, fill(6, 7));
    }

    @Test
    void interiorSingletonStepsForwardsFromLeftAnchor() {
        assertExact("interior singleton", new double[]{
                204.8207817999514, 204.82103578926362, 204.8212897792057, 204.82154376250529,
                204.8217977609797, 204.82205175281157, 204.8223057452734, 204.8225597383652}, fill(3));
    }

    @Test
    void interiorRunOfThreeStepsForwardsExceptItsLastNull() {
        double[] out = fill(2, 3, 4);
        assertExactAt("interior run of 3", new double[]{
                204.8207817999514, 204.82103578926362, 204.82128977193327, 204.82154375523282,
                Double.NaN, 204.82205175281157, 204.8223057452734, 204.8225597383652}, out, 0, 1, 2, 3, 5, 6, 7);
    }

    @Test
    void interiorRunOfTwoStepsForwardsExceptItsLastNull() {
        double[] out = fill(3, 4);
        assertExactAt("interior run of 2", new double[]{
                204.8207817999514, 204.82103578926362, 204.8212897792057, 204.82154376250529,
                Double.NaN, 204.82205175281157, 204.8223057452734, 204.8225597383652}, out, 0, 1, 2, 3, 5, 6, 7);
    }

    /**
     * The last null of an interior run of two or more, index 4 in both inputs. It steps backwards from the right
     * anchor: stored[5] - delta(stored[5]).
     */
    @Test
    void lastNullOfInteriorRun() {
        assertExactValue("interior run of 3, last null", 204.82179776762217, fill(2, 3, 4)[4]);
        assertExactValue("interior run of 2, last null", 204.82179776762217, fill(3, 4)[4]);
    }

    @Test
    void allNullInputIsLeftUnfilled() {
        double[] allNaN = new double[LENGTH];
        Arrays.fill(allNaN, Double.NaN);
        assertExact("all null", allNaN, fill(0, 1, 2, 3, 4, 5, 6, 7));
    }

    @Test
    void inputWithoutNullsIsUnchanged() {
        assertExact("no null", STORED, fill());
    }

    /** Copy the stored fixture values, set {@code nullPositions} to NaN, and run the fill on the copy. */
    private static double[] fill(int... nullPositions) {
        double[] m = stored.clone();
        for (int k : nullPositions) {
            m[k] = Double.NaN;
        }
        NullFill.polynomialFill(m, beta);
        return m;
    }

    private static void assertExact(String what, double[] expected, double[] actual) {
        assertThat(actual).as("%s length", what).hasSize(expected.length);
        int[] all = new int[expected.length];
        Arrays.setAll(all, i -> i);
        assertExactAt(what, expected, actual, all);
    }

    private static void assertExactAt(String what, double[] expected, double[] actual, int... positions) {
        for (int k : positions) {
            assertExactValue(what + " at " + k, expected[k], actual[k]);
        }
    }

    private static void assertExactValue(String what, double expected, double actual) {
        assertThat(Double.compare(actual, expected))
                .as("%s: expected %s but was %s", what, expected, actual)
                .isZero();
    }
}
