package org.mzpeak.io;

import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Spectrum;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * RD-20: grid-encoded chunks (chunk_encoding MS:1003826, design §8.6) decode instead of failing with RD-12's error.
 *
 * <ul>
 *   <li>diaPASEF.grid stores the peaks of diaPASEF.ref with the m/z as a timsTOF m/z grid (MS:9999002, delta-encoded
 *       indices) and the ion mobility as a timsTOF 1/K0 grid (MS:9999001, absolute indices). Decoded, its 9 spectra
 *       equal diaPASEF.ref bit for bit: m/z, intensity and ion mobility, so they pass diaPASEF.ref's golden exactly.
 *   <li>diaPASEF.grid's own golden comes from the HEAD Python reader, which computes the time of flight without the
 *       fused multiply-add of the reference's {@code convert_f64}; its m/z are up to 6.8e-13 away from diaPASEF.ref.
 *       Its S, P and X lines pass with the strict rules; in its full arrays and digest the m/z are compared within
 *       relative 1e-12 (design §2.4), and everything else exactly.
 *   <li>Constructed members, written with parquet-java into a {@link TempDir} from row 0 of diaPASEF.grid's peaks
 *       member or from the spec's worked example, cover the unsigned index read, the cumulative main axis next to the
 *       absolute secondary grid, grid and delta rows in one spectrum, and the errors: an unknown main-axis grid_type
 *       fails naming the known ones, while an unknown ion mobility grid only drops that extra array; a grid column that
 *       cannot be read fails naming the column on the main axis and only drops that extra array as an ion mobility
 *       grid, also when the row's chunk_encoding is not the grid encoding.
 * </ul>
 */
class GridDecodeTest {

    private static final String IM = "MS:1003006";
    private static final String DIA_GRID = "diaPASEF.grid.mzpeak";
    private static final String DIA_REF = "diaPASEF.ref.mzpeak";
    private static final String UNKNOWN = "MS:9999999";
    private static final String MEMBER = "spectra_peaks.parquet";

    @TempDir
    Path tmp;

    private int written;

    // ---- (a) diaPASEF.grid decodes to diaPASEF.ref ------------------------------------------------------------------

    @ParameterizedTest(name = "reconstructProfile={0}")
    @ValueSource(booleans = {true, false})
    void diaPasefGridDecodesToDiaPasefRefBitForBit(boolean reconstructProfile) {
        long points = 0;
        try (MzPeakReader grid = MzPeakReader.open(Fixtures.current(DIA_GRID), reconstructProfile);
             MzPeakReader ref = MzPeakReader.open(Fixtures.current(DIA_REF), reconstructProfile)) {
            assertThat(grid.size()).isEqualTo(ref.size()).isEqualTo(9);
            for (int i = 0; i < ref.size(); i++) {
                Spectrum g = grid.getSpectrumAt(i);
                Spectrum r = ref.getSpectrumAt(i);
                String what = "spectrum " + r.index();
                assertThat(g.index()).isEqualTo(r.index());
                assertThat(g.peaks()).as("%s peaks", what).hasSameSizeAs(r.peaks());
                // stored order: the m/z come from the peaks member, as in diaPASEF.ref
                assertExact(what + " m/z", r.mz(), g.mz());
                assertExact(what + " intensity", r.intensity(), g.intensity());
                assertThat(g.peakArrays().keySet()).as("%s peak arrays", what).containsExactly(IM);
                assertExact(what + " ion mobility", r.peakArrays().get(IM), g.peakArrays().get(IM));
                assertThat(g.arrays()).isEqualTo(g.peakArrays());
                assertThat(g.ionMobility()).containsSame(g.peakArrays().get(IM));
                for (int k = 0; k < r.peaks().size(); k++) {
                    CentroidPeak gp = g.peaks().get(k);
                    CentroidPeak rp = r.peaks().get(k);
                    assertThat(gp.index()).isEqualTo(rp.index());
                    assertThat(Double.compare(gp.mz(), rp.mz())).isZero();
                }
                points += g.mz().length;
            }
            assertThat(grid.getSpectrum(0).orElseThrow().mz()).hasSize(205_921);
        }
        assertThat(points).isEqualTo(291_453);
    }

    // ---- (b) the goldens --------------------------------------------------------------------------------------------

    @Test
    void diaPasefGridPassesDiaPasefRefsGoldenExactly() {
        List<String> mismatches = checkGolden(Golden.load(DIA_REF), Golden.Tolerance.DEFAULT, false);
        assertThat(mismatches).isEmpty();
    }

    /**
     * The S, P and X lines of diaPASEF.grid's own golden pass with the strict rules. Its full arrays and digest record
     * m/z that the Python reader computed without {@code convert_f64}'s fused multiply-add (see
     * {@link GridModelsTest#theFusedTimeOfFlightGivesDiaPasefRefAndTheUnfusedOneThePythonGolden}); there the m/z are
     * compared within relative 1e-12 (design §2.4) and the m/z digest is diaPASEF.ref's, while the intensity and ion
     * mobility are compared exactly.
     */
    @Test
    void diaPasefGridPassesItsOwnGoldenWithTheArraysMzWithinRelative1e12() {
        List<String> mismatches = checkGolden(Golden.load(DIA_GRID), Golden.Tolerance.DEFAULT, true);
        assertThat(mismatches).isEmpty();
    }

    /**
     * The S, P and X lines, full arrays and the digest of every diaPASEF.grid spectrum against {@code golden}, with the
     * strict rules; with {@code arraysMzWithin1e12}, the expected m/z of the full arrays are replaced by the decoded
     * ones where they are within relative 1e-12, and the m/z digest is diaPASEF.ref's (the decoded m/z are its stored
     * ones, checked bit for bit by {@link #diaPasefGridDecodesToDiaPasefRefBitForBit}).
     */
    private static List<String> checkGolden(Golden golden, Golden.Tolerance t, boolean arraysMzWithin1e12) {
        List<String> out = new ArrayList<>();
        int lines = 0;
        int full = 0;
        int digests = 0;
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(DIA_GRID))) {
            for (Spectrum s : reader) {
                String key = Long.toString(s.index());
                double[] mobility = s.arrays().get(IM);
                double[] peakMz = new double[s.peaks().size()];
                double[] peakIntensity = new double[peakMz.length];
                double[] peakMobility = new double[peakMz.length];
                for (int i = 0; i < peakMz.length; i++) {
                    CentroidPeak p = s.peaks().get(i);
                    peakMz[i] = p.mz();
                    peakIntensity[i] = p.intensity();
                    peakMobility[i] = s.peakArrays().get(IM)[p.index()];
                }
                List<Object[]> checks = List.of(
                        new Object[] {golden.line(Golden.Kind.S, key).orElseThrow(), s.mz(), s.intensity()},
                        new Object[] {golden.line(Golden.Kind.P, key).orElseThrow(), peakMz, peakIntensity},
                        new Object[] {golden.line(Golden.Kind.X, "S:" + key, IM).orElseThrow(), s.mz(), mobility},
                        new Object[] {golden.line(Golden.Kind.X, "P:" + key, IM).orElseThrow(), peakMz, peakMobility});
                for (Object[] c : checks) {
                    out.addAll(Golden.Comparator.compare((Golden.Line) c[0], (double[]) c[1], (double[]) c[2], t));
                    lines++;
                }
                Optional<Golden.FullArray> fullArray = golden.full(Golden.Kind.S, key);
                if (fullArray.isPresent()) {
                    Golden.FullArray f = fullArray.get();
                    double[] x = arraysMzWithin1e12 ? mzWithin(f.x(), s.mz()) : f.x();
                    out.addAll(Golden.Comparator.compareFull(new Golden.FullArray(f.kind(), f.key(), x, f.y(),
                            f.filled()), s.mz(), s.intensity(), t));
                    full++;
                }
                Optional<Golden.Digest> digest = golden.digest(Golden.Kind.S, key);
                if (digest.isPresent()) {
                    Golden.Digest d = digest.get();
                    String sha256X = arraysMzWithin1e12
                            ? Golden.load(DIA_REF).digest(Golden.Kind.S, key).orElseThrow().sha256X() : d.sha256X();
                    out.addAll(Golden.Comparator.compareDigest(new Golden.Digest(d.kind(), d.key(), d.n(),
                            d.nFilled(), sha256X, d.sha256Y(), d.sha256Extras()), s.mz(), s.intensity(), s.arrays()));
                    digests++;
                }
            }
        }
        assertThat(lines).isEqualTo(36);
        assertThat(full).isEqualTo(2);
        assertThat(digests).isEqualTo(1);
        return out;
    }

    /** {@code expected} with each value replaced by the decoded one at its position when within relative 1e-12. */
    private static double[] mzWithin(double[] expected, double[] actual) {
        double[] out = expected.clone();
        for (int i = 0; i < Math.min(expected.length, actual.length); i++) {
            if (Math.abs(actual[i] - expected[i]) <= 1e-12 * Math.abs(expected[i])) {
                out[i] = actual[i];
            }
        }
        return out;
    }

    // ---- (c) the open models on a constructed row: the spec's worked example, read as uint32 ------------------------

    @Test
    void aLinearGridIndexAboveTwoToThe31IsReadUnsignedAndSummedOnTheMainAxis() {
        GridRow row = new GridRow();
        row.intensity = new double[] {10, 20, 30};
        row.mzType = "MS:1003824";
        row.mzParameters = new double[] {95.0, 3.75e-7};
        // 2190583200 is above 2^31: read signed it would be -2104384096 and give a negative m/z
        row.mzIndices = new long[] {2_190_583_200L, 1_000L, 2_000L};
        row.imType = "MS:1003824";
        row.imParameters = new double[] {0.5, 1e-3, 2.0};
        row.imIndices = new long[] {1_500L, 1_400L, 1_300L};

        SpectrumArrayStore.Arrays arrays = read(row);

        assertThat(arrays.mz()).hasSize(3);
        assertThat(arrays.mz()[0]).isCloseTo(916.4687, within(5e-5));
        double[] cumulative = {2_190_583_200.0, 2_190_584_200.0, 2_190_586_200.0};
        double[] mz = new double[3];
        for (int i = 0; i < 3; i++) {
            mz[i] = (cumulative[i] * 3.75e-7 + 95.0) / 1.0;
        }
        assertExact("m/z", mz, arrays.mz());
        assertExact("intensity", row.intensity, arrays.intensity());
        // the secondary grid uses its indices as they are: (0.5 + 1e-3 * i) / 2
        assertThat(arrays.extras().keySet()).containsExactly(IM);
        assertExact("ion mobility", new double[] {(1_500 * 1e-3 + 0.5) / 2.0, (1_400 * 1e-3 + 0.5) / 2.0,
                (1_300 * 1e-3 + 0.5) / 2.0}, arrays.extras().get(IM));
    }

    @Test
    void aCumulativeMainAxisIndexBeyondUint32Fails() {
        GridRow row = Row0.INSTANCE.copy();
        row.mzIndices = row.mzIndices.clone();
        row.mzIndices[0] = 0xFFFF_FFFFL;
        assertThatThrownBy(() -> read(row))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("mz_grid")
                .hasMessageContaining("4294967295")
                .hasMessageContaining("spectrum_index 0");
    }

    // ---- diaPASEF.grid row 0 as stored and rewritten ----------------------------------------------------------------

    @Test
    void row0DecodesToTheFirstPeaksOfDiaPasefRefSpectrum0() {
        GridRow row = Row0.INSTANCE.copy();
        SpectrumArrayStore.Arrays arrays = read(row);
        int n = row.mzIndices.length;
        try (MzPeakReader ref = MzPeakReader.open(Fixtures.current(DIA_REF))) {
            Spectrum zero = ref.getSpectrum(0).orElseThrow();
            // no chunk start point: one m/z per grid index
            assertExact("m/z", Arrays.copyOf(zero.mz(), n), arrays.mz());
            assertExact("intensity", Arrays.copyOf(zero.intensity(), n), arrays.intensity());
            assertExact("ion mobility", Arrays.copyOf(zero.peakArrays().get(IM), n), arrays.extras().get(IM));
        }
    }

    @Test
    void anUnknownMainAxisGridTypeFailsNamingTheKnownOnes() {
        GridRow row = Row0.INSTANCE.copy();
        row.mzType = UNKNOWN;
        assertThatThrownBy(() -> read(row))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("grid_type " + UNKNOWN + " of mz_grid is not one of MS:1003824, MS:1003825, MS:9999002,"
                        + " MS:9999001 for spectrum_index 0");
    }

    @Test
    void aGridRowWithoutItsGridStructFails() {
        GridRow row = Row0.INSTANCE.copy();
        row.mzType = null;
        assertThatThrownBy(() -> read(row))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("chunk_encoding MS:1003826 for spectrum_index 0, but the row has no grid in mz_grid");
    }

    @Test
    void aGridEncodingWithoutAGridColumnInThePlanFails() {
        assertThat(ChunkCodecs.undecodableGrid(null, false, null, 4))
                .hasMessage("chunk_encoding MS:1003826 for spectrum_index 4, but the signal plan has no grid column");
    }

    @Test
    void aGridColumnThatIsNotAStructWithoutTheGridEncodingFailsNamingTheColumn() {
        // before FN-3 the error re-read the column as a struct and failed with a ClassCastException
        GridRow row = Row0.INSTANCE.copy();
        row.encoding = "MS:1003089";
        Path file = write(schema(malformed(MZ_GRID, NOT_A_STRUCT), grid(IM_GRID, UINT32)), row);
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), MEMBER, false, Map.of())) {
            assertThatThrownBy(() -> store.get(0))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("mz_grid carries a grid but chunk_encoding is MS:1003089 for spectrum_index 0");
        }
    }

    @Test
    void anUnknownIonMobilityGridTypeDropsOnlyThatExtraWithOneWarning() {
        SpectrumArrayStore.Arrays stored = read(Row0.INSTANCE.copy());
        GridRow row = Row0.INSTANCE.copy();
        row.imType = UNKNOWN;
        GridRow second = Row0.INSTANCE.copy();
        second.imType = UNKNOWN;
        Path file = write(row, second);

        SpectrumArrayStore.Arrays[] arrays = new SpectrumArrayStore.Arrays[1];
        List<LogRecord> records = ArrayIndexTest.captureLog(SpectrumArrayStore.class, () -> {
            try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), MEMBER, false,
                    Map.of())) {
                arrays[0] = store.get(0);
            }
        });

        assertThat(arrays[0].extras()).isEmpty();
        double[] twice = concat(stored.mz(), stored.mz());
        assertExact("m/z", twice, arrays[0].mz());
        assertExact("intensity", concat(stored.intensity(), stored.intensity()), arrays[0].intensity());
        List<LogRecord> warnings = records.stream().filter(r -> r.getLevel() == Level.WARNING).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage())
                .contains(MEMBER, "extra array " + IM + " ", "spectrum_index 0", UNKNOWN,
                        "mean_inverse_reduced_ion_mobility_grid", "m/z and intensity are kept");
    }

    /** An ion mobility grid column that cannot be read (design §1 principle 4) drops that extra, as an unknown one. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {TEXT_INDICES, NOT_A_STRUCT})
    void anIonMobilityGridThatCannotBeReadDropsOnlyThatExtraWithOneWarning(String malformation) {
        SpectrumArrayStore.Arrays stored = read(Row0.INSTANCE.copy());
        Path file = write(schema(grid(MZ_GRID, UINT32), malformed(IM_GRID, malformation)), Row0.INSTANCE.copy(),
                Row0.INSTANCE.copy());

        SpectrumArrayStore.Arrays[] arrays = new SpectrumArrayStore.Arrays[1];
        List<LogRecord> records = ArrayIndexTest.captureLog(SpectrumArrayStore.class, () -> {
            try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), MEMBER, false,
                    Map.of())) {
                arrays[0] = store.get(0);
            }
        });

        assertThat(arrays[0].extras()).isEmpty();
        assertExact("m/z", concat(stored.mz(), stored.mz()), arrays[0].mz());
        assertExact("intensity", concat(stored.intensity(), stored.intensity()), arrays[0].intensity());
        List<LogRecord> warnings = records.stream().filter(r -> r.getLevel() == Level.WARNING).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage())
                .contains(MEMBER, "extra array " + IM + " ", "spectrum_index 0", "cannot be decoded from its grid",
                        "grid " + IM_GRID + " cannot be read", "m/z and intensity are kept");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {TEXT_INDICES, NOT_A_STRUCT})
    void aMainAxisGridThatCannotBeReadFailsNamingItsColumn(String malformation) {
        Path file = write(schema(malformed(MZ_GRID, malformation), grid(IM_GRID, UINT32)), Row0.INSTANCE.copy());
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), MEMBER, false, Map.of())) {
            assertThatThrownBy(() -> store.get(0))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("grid " + MZ_GRID + " cannot be read as {grid_type, parameters, indices}")
                    .hasMessageEndingWith(" for spectrum_index 0")
                    .cause().isInstanceOf(malformation.equals(NOT_A_STRUCT)
                            ? ClassCastException.class : IllegalStateException.class);
        }
    }

    @Test
    void aMainAxisGridWithTheWrongNumberOfParametersFails() {
        GridRow row = Row0.INSTANCE.copy();
        row.mzParameters = Arrays.copyOf(row.mzParameters, 6);
        assertThatThrownBy(() -> read(row))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("MS:9999002")
                .hasMessageContaining("7 parameters")
                .hasMessageContaining("6")
                .hasMessageContaining("spectrum_index 0");
    }

    @Test
    void anIonMobilityGridWithTheWrongNumberOfParametersIsDropped() {
        GridRow row = Row0.INSTANCE.copy();
        row.imParameters = Arrays.copyOf(row.imParameters, 5);
        SpectrumArrayStore.Arrays arrays = read(row);
        assertThat(arrays.extras()).isEmpty();
        assertThat(arrays.mz()).hasSize(row.mzIndices.length);
    }

    @Test
    void aGridWhoseIntensitiesDoNotMatchItsIndicesFails() {
        GridRow row = Row0.INSTANCE.copy();
        row.intensity = Arrays.copyOf(row.intensity, row.intensity.length - 1);
        assertThatThrownBy(() -> read(row))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining(Integer.toString(row.intensity.length))
                .hasMessageContaining(Integer.toString(row.mzIndices.length))
                .hasMessageContaining("spectrum_index 0");
    }

    @Test
    void anIonMobilityGridOfAnotherLengthIsDropped() {
        GridRow row = Row0.INSTANCE.copy();
        row.imIndices = Arrays.copyOf(row.imIndices, row.imIndices.length - 1);
        SpectrumArrayStore.Arrays arrays = read(row);
        assertThat(arrays.extras()).isEmpty();
        assertThat(arrays.mz()).hasSize(row.mzIndices.length);
    }

    @Test
    void gridAndDeltaRowsMixWithinOneSpectrum() {
        SpectrumArrayStore.Arrays stored = read(Row0.INSTANCE.copy());
        int n = stored.mz().length;
        double last = stored.mz()[n - 1];
        GridRow delta = new GridRow();
        delta.encoding = "MS:1003089";
        delta.start = last + 1.0;
        delta.values = new double[] {0.25, 0.5};
        delta.intensity = new double[] {7, 8, 9};

        SpectrumArrayStore.Arrays arrays = read(Row0.INSTANCE.copy(), delta);

        assertExact("m/z", concat(stored.mz(), new double[] {last + 1.0, last + 1.0 + 0.25, last + 1.0 + 0.25 + 0.5}),
                arrays.mz());
        assertExact("intensity", concat(stored.intensity(), delta.intensity), arrays.intensity());
        // the delta row has no ion mobility grid: its cells read as NaN
        assertExact("ion mobility", concat(stored.extras().get(IM), new double[] {Double.NaN, Double.NaN, Double.NaN}),
                arrays.extras().get(IM));
    }

    // ---- fixtures and constructed members ---------------------------------------------------------------------------

    /** Row 0 of the current diaPASEF.grid peaks member, read with parquet-java. */
    private static final class Row0 {
        static final GridRow INSTANCE = load();

        private static GridRow load() {
            try (MzPeakSource source = MzPeakSource.open(Fixtures.current(DIA_GRID))) {
                String peaks = MzPeakManifest.fromSource(source).find(EntityType.SPECTRUM, DataKind.PEAKS)
                        .orElseThrow().name();
                Group[] first = new Group[1];
                ParquetGroups.forEach(source.inputFile(peaks), record -> {
                    if (first[0] == null) {
                        first[0] = record.getGroup("chunk", 0);
                    }
                });
                Group chunk = first[0];
                Group mzGrid = ParquetGroups.optGroup(chunk, "mz_grid");
                Group imGrid = ParquetGroups.optGroup(chunk, "mean_inverse_reduced_ion_mobility_grid");
                GridRow row = new GridRow();
                row.start = ParquetGroups.optDouble(chunk, "mz_chunk_start");
                row.end = ParquetGroups.optDouble(chunk, "mz_chunk_end");
                row.encoding = ParquetGroups.optString(chunk, "chunk_encoding");
                row.intensity = ParquetGroups.doubleListNullable(chunk, "intensity");
                row.mzType = ParquetGroups.optString(mzGrid, "grid_type");
                row.mzParameters = ParquetGroups.doubleListNullable(mzGrid, "parameters");
                row.mzIndices = longs(ParquetGroups.doubleListNullable(mzGrid, "indices"));
                row.imType = ParquetGroups.optString(imGrid, "grid_type");
                row.imParameters = ParquetGroups.doubleListNullable(imGrid, "parameters");
                row.imIndices = longs(ParquetGroups.doubleListNullable(imGrid, "indices"));
                assertThat(ParquetGroups.optLong(chunk, "spectrum_index")).isZero();
                assertThat(ParquetGroups.doubleListNullable(chunk, "mz_chunk_values")).isEmpty();
                assertThat(row.encoding).isEqualTo("MS:1003826");
                assertThat(row.mzType).isEqualTo("MS:9999002");
                assertThat(row.mzParameters).hasSize(7);
                assertThat(row.imType).isEqualTo("MS:9999001");
                assertThat(row.imParameters).hasSize(4);
                assertThat(row.intensity).hasSize(552).hasSameSizeAs(row.mzIndices).hasSameSizeAs(row.imIndices);
                return row;
            }
        }

        private static long[] longs(double[] values) {
            return Arrays.stream(values).mapToLong(v -> (long) v).toArray();
        }
    }

    /** One constructed chunk row of spectrum 0; a null field is written as a null cell. */
    private static final class GridRow {
        Double start;
        Double end;
        String encoding = "MS:1003826";
        double[] values;
        double[] intensity;
        String mzType;
        double[] mzParameters;
        long[] mzIndices;
        String imType;
        double[] imParameters;
        long[] imIndices;

        GridRow copy() {
            GridRow c = new GridRow();
            c.start = start;
            c.end = end;
            c.encoding = encoding;
            c.values = values;
            c.intensity = intensity;
            c.mzType = mzType;
            c.mzParameters = mzParameters;
            c.mzIndices = mzIndices;
            c.imType = imType;
            c.imParameters = imParameters;
            c.imIndices = imIndices;
            return c;
        }
    }

    private static final String MZ_GRID = "mz_grid";
    private static final String IM_GRID = "mean_inverse_reduced_ion_mobility_grid";
    /** The grid indices element of diaPASEF.grid: an unsigned 32-bit integer. */
    private static final String UINT32 = "required int32 element (INTEGER(32,false));";
    /** A malformed grid column: a struct whose indices are text. */
    private static final String TEXT_INDICES = "text indices";
    /** A malformed grid column: a double instead of the struct. */
    private static final String NOT_A_STRUCT = "not a struct";

    private static final MessageType SCHEMA = schema(grid(MZ_GRID, UINT32), grid(IM_GRID, UINT32));

    /** The member schema with the declarations {@code mzGrid} and {@code imGrid} of the two grid columns. */
    private static MessageType schema(String mzGrid, String imGrid) {
        return MessageTypeParser.parseMessageType("""
                message m {
                  optional group chunk {
                    optional int64 spectrum_index (INTEGER(64,false));
                    optional double mz_chunk_start;
                    optional double mz_chunk_end;
                    optional group mz_chunk_values (LIST) { repeated group list { optional double element; } }
                    optional binary chunk_encoding (STRING);
                    optional group intensity (LIST) { repeated group list { optional float element; } }
                    %s
                    %s
                  }
                }""".formatted(mzGrid, imGrid));
    }

    /** The declaration of grid column {@code field} whose indices list holds {@code indexElement}. */
    private static String grid(String field, String indexElement) {
        return "optional group " + field + " {"
                + " required binary grid_type (STRING);"
                + " optional group parameters (LIST) { repeated group list { required double element; } }"
                + " optional group indices (LIST) { repeated group list { " + indexElement + " } } }";
    }

    /** The declaration of grid column {@code field} with {@link #TEXT_INDICES} or {@link #NOT_A_STRUCT}. */
    private static String malformed(String field, String malformation) {
        return malformation.equals(NOT_A_STRUCT) ? "optional double " + field + ";"
                : grid(field, "required binary element (STRING);");
    }

    /** The entries of diaPASEF.grid's spectrum_array_index footer. */
    private static final String INDEX = "{\"prefix\": \"chunk\", \"entries\": ["
            + String.join(", ",
            entry("chunk.spectrum_index", "index", "MS:1003870", 0, null),
            entry("chunk.mz_chunk_start", "chunk_start", "MS:1000514", 1, null),
            entry("chunk.mz_chunk_end", "chunk_end", "MS:1000514", 1, null),
            entry("chunk.mz_chunk_values", "chunk_values", "MS:1000514", 1, null),
            entry("chunk.chunk_encoding", "chunk_encoding", "MS:1000514", 1, null),
            entry("chunk.intensity", "chunk_secondary", "MS:1000515", null, null),
            entry("chunk.mz_grid", "chunk_transform", "MS:1000514", 1, "MS:1003826"),
            entry("chunk.mean_inverse_reduced_ion_mobility_grid", "chunk_transform", IM, null, "MS:1003826"))
            + "]}";

    private static String entry(String path, String format, String arrayType, Integer rank, String transform) {
        return "{\"path\": \"" + path + "\", \"buffer_format\": \"" + format + "\", \"array_type\": \"" + arrayType
                + "\", \"sorting_rank\": " + rank + ", \"transform\": "
                + (transform == null ? "null" : "\"" + transform + "\"")
                + ", \"buffer_priority\": \"primary\", \"context\": \"spectrum\"}";
    }

    /** Reads spectrum 0 of a constructed member holding {@code rows}. */
    private SpectrumArrayStore.Arrays read(GridRow... rows) {
        Path file = write(rows);
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), MEMBER, false, Map.of())) {
            SpectrumArrayStore.Arrays arrays = store.get(0);
            assertThat(arrays).as("arrays of spectrum 0").isNotNull();
            return arrays;
        }
    }

    /** Writes {@code rows} with diaPASEF.grid's array index as the footer, in one row group. */
    private Path write(GridRow... rows) {
        return write(SCHEMA, rows);
    }

    /** Writes {@code rows} in {@code schema} with diaPASEF.grid's array index as the footer, in one row group. */
    private Path write(MessageType schema, GridRow... rows) {
        Path file = tmp.resolve("member-" + (written++) + ".parquet");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withExtraMetaData(Map.of(SignalEntity.SPECTRUM.arrayIndexKey(), INDEX))
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (GridRow row : rows) {
                writer.write(toGroup(schema, row));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    private static Group toGroup(MessageType schema, GridRow row) {
        Group record = new SimpleGroupFactory(schema).newGroup();
        Group chunk = record.addGroup("chunk");
        chunk.add("spectrum_index", 0L);
        if (row.start != null) {
            chunk.add("mz_chunk_start", row.start);
        }
        if (row.end != null) {
            chunk.add("mz_chunk_end", row.end);
        }
        Group values = chunk.addGroup("mz_chunk_values");
        if (row.values != null) {
            for (double v : row.values) {
                values.addGroup("list").add("element", v);
            }
        }
        if (row.encoding != null) {
            chunk.add("chunk_encoding", row.encoding);
        }
        Group intensity = chunk.addGroup("intensity");
        for (double v : row.intensity) {
            intensity.addGroup("list").add("element", (float) v);
        }
        addGrid(chunk, MZ_GRID, row.mzType, row.mzParameters, row.mzIndices);
        addGrid(chunk, IM_GRID, row.imType, row.imParameters, row.imIndices);
        return record;
    }

    /**
     * A grid struct; each index is stored as the INT32 with its unsigned 32-bit pattern, or as the text
     * {@code "index <i>"} when the schema declares text indices. A column declared as a double gets the first
     * parameter.
     */
    private static void addGrid(Group chunk, String field, String type, double[] parameters, long[] indices) {
        if (type == null) {
            return;
        }
        if (chunk.getType().getType(field).isPrimitive()) {
            chunk.add(field, parameters[0]);
            return;
        }
        Group grid = chunk.addGroup(field);
        grid.add("grid_type", type);
        Group p = grid.addGroup("parameters");
        for (double v : parameters) {
            p.addGroup("list").add("element", v);
        }
        Group i = grid.addGroup("indices");
        boolean text = i.getType().getType("list").asGroupType().getType("element").asPrimitiveType()
                .getPrimitiveTypeName() == PrimitiveTypeName.BINARY;
        for (long v : indices) {
            if (text) {
                i.addGroup("list").add("element", "index " + v);
            } else {
                i.addGroup("list").add("element", (int) v);
            }
        }
    }

    private static double[] concat(double[] a, double[] b) {
        double[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static void assertExact(String what, double[] expected, double[] actual) {
        assertThat(actual).as("%s", what).isNotNull();
        assertThat(actual).as("%s length", what).hasSize(expected.length);
        for (int k = 0; k < expected.length; k++) {
            if (Double.compare(actual[k], expected[k]) != 0) {
                assertThat(actual[k]).as("%s at %d", what, k).isEqualTo(expected[k]);
            }
        }
    }
}
