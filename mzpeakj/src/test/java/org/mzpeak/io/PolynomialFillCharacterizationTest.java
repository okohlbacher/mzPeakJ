package org.mzpeak.io;

import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterizes {@link NullFill#fill} with a stored delta model for every shape of null run. Every null run in the
 * vendored fixtures is an interior pair or, in chunk rows, a single null at a row's end, so the other shapes are built
 * from fixture data: eight consecutive stored m/z values of small.unpacked spectrum 0 (stored ordinals 50..57, which
 * sit on consecutive grid points) and that spectrum's stored {@code mz_delta_model}, with NaN injected where a null
 * run should be.
 *
 * <p>R2 (X-8) replaced the 0.12.0 fill, which stepped every flank by the model ({@code polynomialFill}), with the
 * spec's rule: the flank of a segment of two or more stored values steps by the segment's second median spacing, so
 * the model only fills next to a single stored value ({@link #aSingleStoredValueStepsByTheModel()}); an interior
 * single null throws; a run of three or more is filled between its flanks. The expected values are the exact doubles
 * the fill produces (Double.toString round-trips them) and are compared bit for bit with {@link Double#compare}.
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

    /** R2: a leading run of two steps backwards by the local spacing, twice (0.12.0: by the model). */
    @Test
    void leadingRunOfTwoStepsBackwards() {
        assertExact("leading run of 2", new double[]{
                204.82078179680173, 204.8210357880037, 204.8212897792057, 204.82154376977772,
                204.8217977609797, 204.82205175281157, 204.8223057452734, 204.8225597383652}, fill(0, 1));
    }

    /** R2: a trailing run of two steps forwards by the local spacing, twice (0.12.0: by the model). */
    @Test
    void trailingRunOfTwoStepsForwards() {
        assertExact("trailing run of 2", new double[]{
                204.8207817999514, 204.82103578926362, 204.8212897792057, 204.82154376977772,
                204.8217977609797, 204.82205175281157, 204.82230574275366, 204.82255973269574}, fill(6, 7));
    }

    /** R2: an interior single null is an unrecoverable error (0.12.0: stepped forwards from the left anchor). */
    @Test
    void interiorSingletonThrows() {
        assertThatThrownBy(() -> fill(3)).isInstanceOf(MzPeakException.class)
                .hasMessageContaining("unpaired null m/z at position 3");
    }

    /**
     * R2: an interior run of three: its first null steps forwards and its last backwards by the local spacing of
     * their segments, the middle one lies halfway between them (0.12.0: every null but the last stepped forwards).
     */
    @Test
    void interiorRunOfThreeIsFilledFromBothSegments() {
        assertExact("interior run of 3", new double[]{
                204.8207817999514, 204.82103578926362, 204.82128977857585, 204.82154376946278,
                204.82179776034974, 204.82205175281157, 204.8223057452734, 204.8225597383652}, fill(2, 3, 4));
    }

    /** R2: an interior pair: each null steps from its own segment by that segment's local spacing. */
    @Test
    void interiorRunOfTwoStepsFromBothSegments() {
        assertExact("interior run of 2", new double[]{
                204.8207817999514, 204.82103578926362, 204.8212897792057, 204.82154376851793,
                204.82179776034974, 204.82205175281157, 204.8223057452734, 204.8225597383652}, fill(3, 4));
    }

    /** R2: the model fills only next to a single stored value, on both sides of it. */
    @Test
    void aSingleStoredValueStepsByTheModel() {
        assertExact("single stored value at 1", new double[]{
                204.82078180659397, 204.82103578926362, 204.82128977193327, 204.82154376883284,
                204.8217977609797, 204.82205175281157, 204.8223057452734, 204.8225597383652}, fill(0, 2, 3));
        assertExact("single stored values at 0 and 3", new double[]{
                204.8207817999514, 204.8210357819911, 204.8212897858482, 204.82154376977772,
                204.82179775370724, 204.82205175218164, 204.8223057452734, 204.8225597383652}, fill(1, 2, 4, 5));
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
        double[] out = NullFill.fill(List.of(m), false, beta, "spectra_data.parquet", SignalEntity.SPECTRUM.ref(0));
        assertThat(out).isSameAs(m);
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
