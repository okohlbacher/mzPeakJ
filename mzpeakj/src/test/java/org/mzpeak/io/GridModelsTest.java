package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.junit.jupiter.api.Test;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.parquet.ParquetGroups;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * RD-20: the grid models of {@link GridModels#KINDS}. The open models are checked against the spec's worked example
 * and hand-computed values; the timsTOF models against diaPASEF.ref, diaPASEF.grid's golden and the upstream Python
 * test. The Newton branch of the timsTOF m/z model has no fixture.
 */
class GridModelsTest {

    /** diaPASEF.grid's m/z grid parameters (every row has them): c0, beta, c2, c3 = 0, c4, timebase, delay. */
    private static final double[] TIMS_MZ = {320.480481, 2515.8383621118483, 0.00211500318015718, 0.0, -0.068565,
            0.2, 24833.0};
    /** diaPASEF.grid's 1/K0 grid parameters: c6, c7, offset, slope. */
    private static final double[] TIMS_MOBILITY = {0.020932469715718494, 131.22279563838268, 222.46486824959476,
            -0.1539079919581615};

    private static GridModels.Model model(String gridType, double... parameters) {
        return GridModels.kind(gridType).model(parameters);
    }

    @Test
    void theTableHoldsTheTwoOpenAndTheTwoProvisionalModels() {
        assertThat(GridModels.KINDS.keySet()).containsExactly("MS:1003824", "MS:1003825", "MS:9999002", "MS:9999001");
        assertThat(GridModels.KINDS.values()).extracting(GridModels.Kind::provisional)
                .containsExactly(false, false, true, true);
        assertThat(GridModels.KINDS.values()).extracting(k -> k.minParameters() + ".." + k.maxParameters())
                .containsExactly("2..3", "2..3", "7..7", "4..4");
        assertThat(GridModels.kind("MS:1003824")).isSameAs(GridModels.KINDS.get(GridModels.LINEAR));
        assertThat(GridModels.kind("MS:9999999")).isNull();
        assertThat(GridModels.kind(null)).isNull();
        assertThatThrownBy(() -> GridModels.KINDS.remove("MS:1003824"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- (c) the open models ----------------------------------------------------------------------------------------

    @Test
    void theLinearModelGivesTheSpecsWorkedExample() {
        // chunked-layout.md: a = 95.0, b = 3.75e-7, s = 1.0 map index 2190583200 (above 2^31) to 916.4687
        GridModels.Model twoParameters = model("MS:1003824", 95.0, 3.75e-7);
        GridModels.Model withScale = model("MS:1003824", 95.0, 3.75e-7, 1.0);
        assertThat(twoParameters.fromIndex(2_190_583_200L)).isCloseTo(916.4687, within(1e-9));
        assertThat(withScale.fromIndex(2_190_583_200L)).isEqualTo(twoParameters.fromIndex(2_190_583_200L));
        assertThat(twoParameters.fromIndex(0)).isEqualTo(95.0);
        assertThat(model("MS:1003824", 95.0, 3.75e-7, 2.0).fromIndex(2_190_583_200L))
                .isCloseTo(916.4687 / 2, within(1e-9));
        assertThat(twoParameters.fromIndex(GridChunk.MAX_INDEX)).isCloseTo(95.0 + 4294967295.0 * 3.75e-7,
                within(1e-9));
    }

    @Test
    void theSquareRootModelSquaresTheLinearValue() {
        // (a + b·i)² / s, chosen so that every step is exact: (10 + 0.5·6)² / 4 = 13² / 4 = 42.25
        assertThat(model("MS:1003825", 10.0, 0.5, 4.0).fromIndex(6)).isEqualTo(42.25);
        // (2 + 0.25·4)² with the default scale 1 = 9
        assertThat(model("MS:1003825", 2.0, 0.25).fromIndex(4)).isEqualTo(9.0);
        // an index above 2^31: (1 + 2^-20·3·2^30)² = (1 + 3072)² = 9443329
        assertThat(model("MS:1003825", 1.0, Math.scalb(1.0, -20)).fromIndex(3L << 30)).isEqualTo(9_443_329.0);
    }

    // ---- the timsTOF models -----------------------------------------------------------------------------------------

    @Test
    void theTimsTofMobilityModelMatchesTheUpstreamTestAndDiaPasefRef() {
        // mzPeak python/test/test_reader.py test_linear_bruker_grid, compared there with numpy.allclose
        GridModels.Model m = model("MS:9999001", TIMS_MOBILITY);
        long[] indices = {533, 486, 481, 514, 517};
        double[] expected = {1.04673088, 1.09940738, 1.10500427, 1.06803995, 1.06467665};
        double[] decoded = GridModels.decode(m, indices);
        for (int i = 0; i < indices.length; i++) {
            assertThat(decoded[i]).as("index %d", indices[i]).isCloseTo(expected[i], within(1e-8));
        }
        // the first peaks of diaPASEF.ref spectrum 0, exactly (its golden X line head)
        assertThat(decoded[0]).isEqualTo(1.0467308802733821);
        assertThat(decoded[1]).isEqualTo(1.0994073807086113);
        assertThat(decoded[2]).isEqualTo(1.1050042749543254);
    }

    @Test
    void theTimsTofMzModelGivesTheFirstPeaksOfDiaPasefRef() {
        // diaPASEF.grid row 0 starts with the delta-encoded indices 60, 69, 8 (quadratic branch: c3 = 0, c2 != 0)
        GridModels.Model m = model("MS:9999002", TIMS_MZ);
        assertThat(m.fromIndex(60)).isEqualTo(95.091497034932047);
        assertThat(m.fromIndex(129)).isEqualTo(95.19846546245752);
        assertThat(m.fromIndex(137)).isEqualTo(95.210871491708531);
    }

    @Test
    void theTimsTofMzModelSolvesTheLinearAndQuadraticCasesByHand() {
        // c0 + beta·s + c2·s² = tof with tof = i·timebase + delay, and m/z = s² - c4
        // linear (c2 = c3 = 0): tof = 10, s = 10 / 2 = 5, m/z = 25 - 1
        assertThat(model("MS:9999002", 0, 2, 0, 0, 1, 1, 0).fromIndex(10)).isEqualTo(24.0);
        // quadratic: s + s² = 6 has the root s = 2 (discriminant 25), m/z = 4 - 0.5
        assertThat(model("MS:9999002", 0, 1, 1, 0, 0.5, 2, -4).fromIndex(5)).isEqualTo(3.5);
        // a negative discriminant (1 - 4·tof with c2 = -1, tof = 1) keeps the linear root s = 1
        assertThat(model("MS:9999002", 0, 1, -1, 0, 0, 1, 0).fromIndex(1)).isEqualTo(1.0);
    }

    /**
     * The Newton branch (c3 != 0) has no fixture. UNVERIFIED against the reference: the expected values come from the
     * scratch Python port of mzdata's {@code convert_f64} (scratchpad upstream/skeptic-grid-b/verify2.py,
     * {@code mz_model}), which follows its control flow but not its fused multiply-adds, hence the tolerance.
     */
    @Test
    void theTimsTofMzNewtonBranchMatchesTheScratchPortUnverified() {
        double[] p = TIMS_MZ.clone();
        p[3] = 2.5e-6;
        GridModels.Model newton = model("MS:9999002", p);
        assertThat(newton.fromIndex(60)).isCloseTo(95.09147909020479, within(1e-9));
        assertThat(newton.fromIndex(100_000)).isCloseTo(313.09837299770874, within(1e-9));
        assertThat(newton.fromIndex(396_857)).isCloseTo(1704.971124580392, within(1e-9));
        p[3] = -1.0e-5;
        assertThat(model("MS:9999002", p).fromIndex(100_000)).isCloseTo(313.0993466809677, within(1e-9));
        // the cubic term matters: the quadratic solution is 1.9e-4 away at index 100000
        assertThat(model("MS:9999002", TIMS_MZ).fromIndex(100_000)).isCloseTo(313.0985677335124, within(1e-9));
    }

    /**
     * A cubic term large enough that Newton needs several steps from {@code s0 = (tof - c0) / beta}: at index 396857
     * the first step lands 112 m/z away, the fourth still 2.7e-9 away, the fifth on the root (kills GM4 and GM5, which
     * stop after the first step). UNVERIFIED against the reference: the expected values are the roots of
     * {@code c0 + beta·s + c2·s² + c3·s³ = tof} found by bisection in 60-digit decimal arithmetic (scratch script,
     * not mzdata), then {@code s² - c4}.
     */
    @Test
    void theTimsTofMzNewtonBranchTakesSeveralStepsUnverified() {
        double[] p = TIMS_MZ.clone();
        p[3] = 1.0;
        GridModels.Model newton = model("MS:9999002", p);
        assertThat(newton.fromIndex(100_000)).isCloseTo(257.65397279895666, within(1e-9));
        assertThat(newton.fromIndex(396_857)).isCloseTo(916.2491700535076, within(1e-9));
        assertThat(newton.fromIndex(60)).isCloseTo(88.73209515330551, within(1e-9));
        // one Newton step is far from the root: a model that stopped there would be off by more than 1 m/z
        double tof = Math.fma(396_857.0, p[5], p[6]);
        double s = (tof - p[0]) / p[1];
        s -= (p[0] + p[1] * s + p[2] * s * s + p[3] * s * s * s - tof) / (p[1] + 2 * p[2] * s + 3 * p[3] * s * s);
        assertThat(Math.abs(s * s - p[4] - 916.2491700535076)).isGreaterThan(1.0);
    }

    /**
     * Newton stops where the derivative {@code beta + 2·c2·s + 3·c3·s²} vanishes, keeping its current root instead of
     * dividing by zero: with c0 = 3, beta = -3, c3 = 1 and tof = 0, s0 = 1 is where the derivative is -3 + 3 = 0, so
     * m/z = 1² - c4. Another step would give an infinite delta and a NaN m/z.
     */
    @Test
    void theTimsTofMzNewtonBranchStopsWhereTheDerivativeVanishes() {
        assertThat(model("MS:9999002", 3, -3, 0, 1, 0.5, 1, 0).fromIndex(0)).isEqualTo(0.5);
    }

    /**
     * Newton takes at most 8 steps: s + s³ = 10⁶ (c0 = c2 = c4 = 0, beta = c3 = 1, tof = index) from s0 = 10⁶ shrinks
     * s by about a third per step, so after 8 steps s is still 39018.44244207348, not the root 99.99666..., and m/z is
     * its square (the 8th Newton iterate, computed in 60-digit decimal arithmetic). 7 or 9 steps give 3.4e9 or 6.8e8.
     */
    @Test
    void theTimsTofMzNewtonBranchStopsAfterEightSteps() {
        assertThat(model("MS:9999002", 0, 1, 0, 1, 0, 1, 0).fromIndex(1_000_000))
                .isCloseTo(1_522_438_850.6054008, within(1e-3));
    }

    // ---- parameters -------------------------------------------------------------------------------------------------

    @Test
    void aWrongNumberOfParametersIsRejected() {
        assertThatThrownBy(() -> model("MS:1003824", 95.0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("grid_type MS:1003824 (linear grid interpolation) takes 2 to 3 parameters, found 1");
        assertThatThrownBy(() -> model("MS:1003825", 1, 2, 3, 4)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("takes 2 to 3 parameters, found 4");
        assertThatThrownBy(() -> model("MS:9999002", 1, 2, 3, 4, 5, 6)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("grid_type MS:9999002 (timsTOF m/z grid) takes 7 parameters, found 6");
        assertThatThrownBy(() -> model("MS:9999001", 1, 2, 3, 4, 5)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("takes 4 parameters, found 5");
    }

    @Test
    void theModelKeepsItsOwnCopyOfTheParameters() {
        double[] p = {95.0, 3.75e-7};
        GridModels.Model m = model("MS:1003824", p);
        p[0] = 0;
        assertThat(m.fromIndex(0)).isEqualTo(95.0);
    }

    // ---- why diaPASEF.grid's own golden arrays differ in the last bits ----------------------------------------------

    /**
     * diaPASEF.grid's spectrum 2, all of its rows: {@code convert_f64}'s fused multiply-add for the time of flight
     * gives the m/z that diaPASEF.ref stores, bit for bit, as recorded in diaPASEF.ref's golden. The HEAD Python reader
     * ({@code grid.py}, {@code BrukerTimsTOFMzGrid2.from_index}) multiplies and adds separately; its m/z, recorded in
     * diaPASEF.grid's golden, are reproduced here by that arithmetic and differ from the stored ones in the last bits.
     */
    @Test
    void theFusedTimeOfFlightGivesDiaPasefRefAndTheUnfusedOneThePythonGolden() {
        List<Long> indices = new ArrayList<>();
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current("diaPASEF.grid.mzpeak"))) {
            String peaks = MzPeakManifest.fromSource(source).find(EntityType.SPECTRUM, DataKind.PEAKS)
                    .orElseThrow().name();
            ParquetGroups.forEach(source.inputFile(peaks), record -> {
                Group chunk = record.getGroup("chunk", 0);
                if (ParquetGroups.optLong(chunk, "spectrum_index") == 2) {
                    Group grid = ParquetGroups.optGroup(chunk, "mz_grid");
                    assertThat(ParquetGroups.doubleListNullable(grid, "parameters")).containsExactly(TIMS_MZ);
                    long sum = 0;
                    for (double d : ParquetGroups.doubleListNullable(grid, "indices")) {
                        sum += (long) d;
                        indices.add(sum);
                    }
                }
            });
        }
        double[] fused = GridModels.decode(model("MS:9999002", TIMS_MZ),
                indices.stream().mapToLong(Long::longValue).toArray());
        double[] unfused = indices.stream().mapToDouble(i -> pythonFromIndex(TIMS_MZ, i)).toArray();

        double[] ref = Golden.load("diaPASEF.ref.mzpeak").full(Golden.Kind.S, "2").orElseThrow().x();
        double[] python = Golden.load("diaPASEF.grid.mzpeak").full(Golden.Kind.S, "2").orElseThrow().x();
        assertThat(fused).hasSize(960).containsExactly(ref);
        assertThat(unfused).containsExactly(python);
        int differ = 0;
        for (int i = 0; i < fused.length; i++) {
            assertThat(unfused[i]).isCloseTo(fused[i], within(1e-12 * fused[i]));
            differ += fused[i] != unfused[i] ? 1 : 0;
        }
        assertThat(differ).isPositive();
    }

    /** grid.py's {@code BrukerTimsTOFMzGrid2.from_index} for c3 = 0, c2 != 0, as numpy evaluates it. */
    private static double pythonFromIndex(double[] p, long index) {
        double c0 = p[0];
        double beta = p[1];
        double c2 = p[2];
        double c4 = p[4];
        double tof = index * p[5] + p[6];
        double s0 = (tof - c0) / beta;
        double disc = beta * beta - 4.0 * c2 * (c0 - tof);
        double s = disc < 0 ? s0 : (c0 - tof) / (-0.5 * (beta + Math.sqrt(disc)));
        return s * s - c4;
    }
}
