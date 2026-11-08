package org.mzpeak.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ms.numpress.MSNumpress;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AP-9: the per-point extra arrays ({@link Chromatogram#arrays()}) and the intensity unit
 * ({@link Chromatogram#intensityUnit()}) of chromatograms, decoded from the plan of the chromatogram data member
 * (design sections 8.1, 8.4 and 10).
 *
 * <p>Oracles: the pyarrow cells of the fixtures' {@code point.ms_level} column (pyarrow 26.0.1), and the same cells
 * read straight from the member; small.chunked and the other small* TICs against current small.unpacked; the units
 * of the footers' intensity entries; constructed members for the decoding rules.
 *
 * <p>Characterization, pinned on the code before AP-9 (agent-team/upstream-p1 17e639f): the 5-argument
 * {@link Chromatogram} constructor, and the time and intensity arrays of constructed members whose array index lists
 * extra columns, several intensity units, unreadable and transform-only extras. The fixtures are pinned by
 * {@link ChromatogramValuesTest}, {@link SignalPlanWiringTest} (current set) and {@link SignalSnapshotTest} (legacy
 * set).
 */
class ChromatogramArraysTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The 48 'ms level' cells of the small* TIC: pyarrow's point.ms_level of current small.unpacked. */
    private static final double[] SMALL_TIC_MS_LEVEL = {
            1, 1, 2, 2, 2, 2, 2, 1, 1, 2, 2, 2, 2, 2, 1, 1, 2, 2, 2, 2, 2, 1, 1, 2,
            2, 2, 2, 2, 1, 1, 2, 2, 2, 2, 1, 1, 2, 2, 2, 2, 2, 1, 1, 2, 2, 2, 2, 2};

    private static final MessageType CHROMATOGRAM_METADATA = MessageTypeParser.parseMessageType("""
            message m {
              optional int64 index (INTEGER(64,false));
              optional binary id (STRING);
            }""");

    /** A point member with two intensity columns of different units and several extra columns. */
    private static final MessageType POINT_SCHEMA = MessageTypeParser.parseMessageType("""
            message m {
              optional group point {
                optional int64 chromatogram_index (INTEGER(64,false));
                optional double time;
                optional float intensity;
                optional float intensity_f32_au;
                optional int64 ms_level;
                optional double ms_level_f64;
                optional float charge;
                optional binary label (STRING);
              }
            }""");

    /** A chunk member with an intensity list, SLOF intensity bytes, an extra list and a transform-only extra. */
    private static final MessageType CHUNK_SCHEMA = MessageTypeParser.parseMessageType("""
            message m {
              optional group chunk {
                optional int64 chromatogram_index (INTEGER(64,false));
                optional double time_chunk_start;
                optional group time_chunk_values (LIST) { repeated group list { optional double item; } }
                optional group intensity (LIST) { repeated group list { optional float item; } }
                optional group intensity_numpress_slof_bytes (LIST) {
                  repeated group list { required int32 item (INTEGER(8,false)); }
                }
                optional group ms_level (LIST) { repeated group list { optional int64 item; } }
                optional group ms_level_slof (LIST) {
                  repeated group list { required int32 item (INTEGER(8,false)); }
                }
              }
            }""");

    @TempDir
    Path tmp;

    // ---- characterization: the 5-argument constructor ----------------------------------------------------------

    @Test
    void fiveArgumentConstructorKeepsItsContract() {
        double[] time = {1.0, 2.0};
        double[] intensity = {3.0, 4.0};
        Chromatogram c = new Chromatogram(3, "TIC", "MS:1000235", time, intensity);

        assertThat(c.index()).isEqualTo(3L);
        assertThat(c.id()).isEqualTo("TIC");
        assertThat(c.typeCurie()).isEqualTo("MS:1000235");
        assertThat(c.time()).isSameAs(time);
        assertThat(c.intensity()).isSameAs(intensity);
        assertThat(c.size()).isEqualTo(2);

        Chromatogram empty = new Chromatogram(0, null, null, null, null);
        assertThat(empty.time()).isEmpty();
        assertThat(empty.intensity()).isEmpty();
        assertThat(empty.size()).isZero();

        assertThatThrownBy(() -> new Chromatogram(0, "x", null, new double[2], new double[1]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("time/intensity length mismatch: 2 vs 1");
        assertThatThrownBy(() -> new Chromatogram(0, "x", null, null, new double[1]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("time/intensity length mismatch: 0 vs 1");

        // arrays compare by reference, as for every record component of array type
        Chromatogram same = new Chromatogram(3, "TIC", "MS:1000235", time, intensity);
        assertThat(same).isEqualTo(c).hasSameHashCodeAs(c);
        assertThat(new Chromatogram(3, "TIC", "MS:1000235", time.clone(), intensity)).isNotEqualTo(c);
        assertThat(new Chromatogram(4, "TIC", "MS:1000235", time, intensity)).isNotEqualTo(c);
    }

    // ---- characterization: time and intensity of members with extra columns -----------------------------------

    @Test
    void pointMemberWithExtraColumnsDecodesTimeAndIntensity() {
        List<Chromatogram> all = pointMemberChromatograms(true);

        assertThat(all).extracting(Chromatogram::id).containsExactly("c0", "c1", "c2", "c3", "c4");
        assertArrays(all.get(0), new double[] {1, 2, 3}, new double[] {10, 20, 30});
        assertArrays(all.get(1), new double[] {0.5, 0.75}, new double[] {0.25, 0.125});
        assertArrays(all.get(2), new double[] {5, 6}, new double[] {5, 0.5});
        assertArrays(all.get(3), new double[] {7}, new double[] {0});
        assertArrays(all.get(4), new double[0], new double[0]);
    }

    @Test
    void pointMemberWithoutAnArrayIndexDecodesTimeAndIntensity() {
        List<Chromatogram> all = pointMemberChromatograms(false);

        // the INFERRED plan reads the literal intensity, else the intensity_* fallback (Q-U5)
        assertArrays(all.get(0), new double[] {1, 2, 3}, new double[] {10, 20, 30});
        assertArrays(all.get(1), new double[] {0.5, 0.75}, new double[] {0.25, 0.125});
        assertArrays(all.get(2), new double[] {5, 6}, new double[] {5, 0.5});
        assertArrays(all.get(3), new double[] {7}, new double[] {0});
        assertArrays(all.get(4), new double[0], new double[0]);
    }

    @Test
    void chunkMemberWithExtraColumnsDecodesTimeAndIntensity() {
        List<Chromatogram> all = chunkMemberChromatograms();

        double[] slof = slofDecoded();
        assertThat(all).extracting(Chromatogram::id).containsExactly("c0", "c1", "c2", "c3");
        assertArrays(all.get(0), new double[] {10, 11, 12, 20, 21}, new double[] {1, 2, 3, 4, 5});
        assertArrays(all.get(1), new double[] {0, 1, 2, 5, 6}, new double[] {7, 8, 9, 1, 1});
        assertArrays(all.get(2), new double[] {0, 0.5}, slof);
        assertArrays(all.get(3), new double[] {0, 1, 2, 3}, new double[] {1, 2, slof[0], slof[1]});
    }

    // ---- (a), (b): fixture chromatograms -------------------------------------------------------------------------

    @Test
    void currentSmallUnpackedTicCarriesTheMsLevelCells() {
        Path dataset = Fixtures.current("small.unpacked.mzpeak");
        Chromatogram tic = chromatogramsOf(dataset).get(0);

        assertThat(tic.arrays().keySet()).containsExactly("ms level");
        assertThat(tic.arrays().get("ms level")).hasSize(48).startsWith(1, 1, 2, 2, 2, 2)
                .containsExactly(SMALL_TIC_MS_LEVEL)
                .containsExactly(cells(storedCells(dataset, 0, "ms_level")));
        assertThat(tic.intensityUnit()).isEqualTo("MS:1000131");
    }

    @Test
    void legacySmallUnpackedTicCarriesItsOwnMsLevelCells() {
        Path dataset = Fixtures.legacy("small.unpacked.mzpeak");
        Chromatogram tic = chromatogramsOf(dataset).get(0);

        assertThat(tic.arrays().keySet()).containsExactly("ms level");
        assertThat(tic.arrays().get("ms level")).containsExactly(cells(storedCells(dataset, 0, "ms_level")))
                .containsExactly(SMALL_TIC_MS_LEVEL);
        assertThat(tic.intensityUnit()).isEqualTo("MS:1000131");
    }

    static Stream<Arguments> smallFixtures() {
        Stream<Arguments> current = Stream.of("small.mzpeak", "small.chunked.mzpeak", "small.numpress.mzpeak")
                .map(name -> Arguments.of("current " + name, Fixtures.current(name)));
        Stream<Arguments> legacy = Stream.of("small.unpacked.mzpeak", "small.mzpeak", "small.chunked.mzpeak",
                        "small.numpress.mzpeak")
                .map(name -> Arguments.of("legacy " + name, Fixtures.legacy(name)));
        return Stream.concat(current, legacy);
    }

    /**
     * Every other small* TIC, point or chunk (chunk secondary 'ms level' list, plain or SLOF intensity), carries the
     * same 'ms level' array as current small.unpacked, element by element, and the counts unit.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void smallTicArraysEqualTheUnpackedTic(String label, Path dataset) {
        Chromatogram expected = chromatogramsOf(Fixtures.current("small.unpacked.mzpeak")).get(0);
        Chromatogram tic = chromatogramsOf(dataset).get(0);

        assertThat(tic.arrays().keySet()).containsExactly("ms level");
        assertThat(tic.arrays().get("ms level")).isEqualTo(expected.arrays().get("ms level"));
        assertThat(tic.intensityUnit()).isEqualTo("MS:1000131");
    }

    static Stream<Path> hasUvInBothSets() {
        return Stream.of(Fixtures.current("has_uv.mzpeak"), Fixtures.legacy("has_uv.mzpeak"));
    }

    /**
     * has_uv: the TIC has 212 'ms level' cells (all 1) in counts; 'DAD1 A' is in absorbance, from the
     * intensity_f32_au entry, and has no 'ms level' entry because all 526 of its cells are null.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("hasUvInBothSets")
    void hasUvTicHasMsLevelsAndDad1IsInAbsorbance(Path dataset) {
        List<Chromatogram> all = chromatogramsOf(dataset);

        Chromatogram tic = all.get(0);
        List<Double> ticCells = storedCells(dataset, 0, "ms_level");
        assertThat(ticCells).hasSize(212).containsOnly(1.0);
        assertThat(tic.arrays().keySet()).containsExactly("ms level");
        assertThat(tic.arrays().get("ms level")).containsExactly(cells(ticCells));
        assertThat(tic.intensityUnit()).isEqualTo("MS:1000131");

        Chromatogram dad = all.get(1);
        assertThat(dad.id()).startsWith("DAD1 A");
        assertThat(storedCells(dataset, 1, "ms_level")).hasSize(526).containsOnlyNulls();
        assertThat(dad.arrays()).isEmpty();
        assertThat(dad.intensityUnit()).isEqualTo("UO:0000269");
    }

    /** The diaPASEF intensity entries name no unit, and their members have no extra column. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"diaPASEF.ref.mzpeak", "diaPASEF.grid.mzpeak"})
    void diaPasefChromatogramsHaveNoUnitAndNoArrays(String fixture) {
        List<Chromatogram> all = chromatogramsOf(Fixtures.current(fixture));

        assertThat(all).hasSize(2).allSatisfy(c -> {
            assertThat(c.size()).isEqualTo(9);
            assertThat(c.intensityUnit()).isNull();
            assertThat(c.arrays()).isEmpty();
        });
    }

    // ---- (c): mzPeakJ output ----------------------------------------------------------------------------------

    /**
     * An archive mzPeakJ writes declares the intensity unit of each intensity column in its array index and stores the
     * extras (MZ-2), so its FOOTER plan gives each chromatogram its unit and its extras.
     */
    @Test
    void mzPeakJArchiveGivesTheUnitsAndArraysItWasWrittenWith() {
        List<Spectrum> spectra = new ArrayList<>();
        List<Chromatogram> chromatograms;
        List<WavelengthSpectrum> wavelength;
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.legacy("has_uv.mzpeak"))) {
            reader.iterator().forEachRemaining(spectra::add);
            chromatograms = reader.chromatograms();
            wavelength = reader.wavelengthSpectra();
        }
        assertThat(chromatograms.get(0).arrays()).containsOnlyKeys("ms level");
        Path archive = tmp.resolve("written.mzpeak");
        MzPeakWriter.writeArchive(archive, spectra, chromatograms, wavelength, List.of(), FileMetadata.EMPTY);

        try (MzPeakSource source = MzPeakSource.open(archive)) {
            assertThat(ChromatogramStore.load(source, MzPeakManifest.fromSource(source)).plan().source())
                    .isEqualTo(ArrayIndex.Source.FOOTER);
        }
        List<Chromatogram> readBack = chromatogramsOf(archive);
        assertThat(readBack).hasSize(2);
        for (int i = 0; i < readBack.size(); i++) {
            Chromatogram c = readBack.get(i);
            assertThat(c.time()).isEqualTo(chromatograms.get(i).time());
            assertThat(c.intensity()).isEqualTo(chromatograms.get(i).intensity());
            assertThat(c.intensityUnit()).as("%s unit", c.id()).isEqualTo(chromatograms.get(i).intensityUnit());
            assertThat(c.arrays().keySet()).as("%s arrays", c.id())
                    .containsExactlyElementsOf(chromatograms.get(i).arrays().keySet());
        }
        assertThat(readBack).extracting(Chromatogram::intensityUnit).containsExactly("MS:1000131", "UO:0000269");
        assertThat(readBack.get(0).arrays().get("ms level")).isEqualTo(chromatograms.get(0).arrays().get("ms level"));
    }

    // ---- (e): the record ---------------------------------------------------------------------------------------

    @Test
    void sevenArgumentConstructorKeepsTheArraysInOrderWithoutCopyingThem() {
        double[] time = {1.0, 2.0};
        double[] intensity = {3.0, 4.0};
        double[] z = {5.0, 6.0};
        double[] a = {7.0, 8.0};
        double[] msLevel = {1.0, 2.0};
        Map<String, double[]> in = new LinkedHashMap<>();
        in.put("z", z);
        in.put("a", a);
        in.put("ms level", msLevel);

        Chromatogram c = new Chromatogram(0, "TIC", "MS:1000235", time, intensity, "MS:1000131", in);

        assertThat(c.intensityUnit()).isEqualTo("MS:1000131");
        assertThat(c.arrays().keySet()).containsExactly("z", "a", "ms level");
        assertThat(c.arrays().get("z")).isSameAs(z);
        assertThat(c.arrays().get("a")).isSameAs(a);
        assertThat(c.arrays().get("ms level")).isSameAs(msLevel);
        assertThat(c.time()).isSameAs(time);
        assertThat(c.intensity()).isSameAs(intensity);

        // a copy of the map: later changes to the argument do not show
        in.remove("a");
        in.put("late", new double[2]);
        assertThat(c.arrays().keySet()).containsExactly("z", "a", "ms level");

        // unmodifiable
        assertThatThrownBy(() -> c.arrays().put("x", new double[2]))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> c.arrays().remove("z")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> c.arrays().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> c.arrays().entrySet().iterator().next().setValue(new double[2]))
                .isInstanceOf(UnsupportedOperationException.class);

        // a null map is an empty one
        Chromatogram none = new Chromatogram(0, "TIC", null, time, intensity, null, null);
        assertThat(none.arrays()).isEmpty();
        assertThat(none.intensityUnit()).isNull();
    }

    @Test
    void sevenArgumentConstructorRejectsNullKeysNullValuesAndLengthMismatches() {
        double[] time = {1.0, 2.0};
        double[] intensity = {3.0, 4.0};
        Map<String, double[]> nullKey = new HashMap<>();
        nullKey.put(null, new double[2]);
        Map<String, double[]> nullValue = new HashMap<>();
        nullValue.put("ms level", null);

        assertThatThrownBy(() -> new Chromatogram(0, "x", null, time, intensity, null, nullKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("arrays has a null key");
        assertThatThrownBy(() -> new Chromatogram(0, "x", null, time, intensity, null, nullValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("arrays[ms level] is null");
        assertThatThrownBy(() -> new Chromatogram(0, "x", null, time, intensity, null,
                Map.of("ms level", new double[3])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("arrays[ms level]/time length mismatch: 3 vs 2");
        assertThatThrownBy(() -> new Chromatogram(0, "x", null, null, null, null, Map.of("ms level", new double[1])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("arrays[ms level]/time length mismatch: 1 vs 0");
        // the time/intensity check comes first
        assertThatThrownBy(() -> new Chromatogram(0, "x", null, time, new double[1], null,
                Map.of("ms level", new double[3])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("time/intensity length mismatch: 2 vs 1");
    }

    @Test
    void fiveArgumentConstructorSetsNoUnitAndNoArrays() {
        double[] time = {1.0, 2.0};
        double[] intensity = {3.0, 4.0};
        Chromatogram five = new Chromatogram(0, "x", "MS:1000235", time, intensity);

        assertThat(five.intensityUnit()).isNull();
        assertThat(five.arrays()).isEmpty();
        assertThat(five).isEqualTo(new Chromatogram(0, "x", "MS:1000235", time, intensity, null, Map.of()))
                .hasSameHashCodeAs(new Chromatogram(0, "x", "MS:1000235", time, intensity, null, null));
        // equals and hashCode include the new components
        assertThat(new Chromatogram(0, "x", "MS:1000235", time, intensity, "MS:1000131", Map.of()))
                .isNotEqualTo(five);
        assertThat(new Chromatogram(0, "x", "MS:1000235", time, intensity, null, Map.of("ms level", time)))
                .isNotEqualTo(five);
    }

    // ---- decoding rules on constructed members ------------------------------------------------------------------

    /**
     * Point rows: extras are grouped per chromatogram index and keyed as the plan keys them (array name for
     * MS:1000786, {@code <key>@<field>} for a further column of a key, else the array type), null cells read as
     * NaN, and a key whose cells are all null in a chromatogram has no entry there. The unit is that of the entry
     * that supplied the values; mixed units give null with one WARNING, and a chromatogram without any value has no
     * unit. The string column cannot be read as numbers: it is dropped with one WARNING, the rest is read.
     */
    @Test
    void pointMemberExtrasAndUnits() {
        List<List<Chromatogram>> out = new ArrayList<>();
        List<LogRecord> records = ArrayIndexTest.captureLog(ChromatogramDataDecoder.class,
                () -> out.add(pointMemberChromatograms(true)));
        List<Chromatogram> all = out.get(0);

        Chromatogram c0 = all.get(0);
        assertThat(c0.intensityUnit()).isEqualTo("MS:1000131");
        assertThat(c0.arrays().keySet()).containsExactly("ms level", "ms level@ms_level_f64", "MS:1000516");
        assertThat(c0.arrays().get("ms level")).containsExactly(1, Double.NaN, 2);
        assertThat(c0.arrays().get("ms level@ms_level_f64")).containsExactly(Double.NaN, 2.5, Double.NaN);
        assertThat(c0.arrays().get("MS:1000516")).containsExactly(2, Double.NaN, Double.NaN);

        assertThat(all.get(1).intensityUnit()).isEqualTo("UO:0000269");
        assertThat(all.get(1).arrays()).isEmpty();

        assertThat(all.get(2).intensityUnit()).isNull();
        assertThat(all.get(2).arrays().keySet()).containsExactly("ms level");
        assertThat(all.get(2).arrays().get("ms level")).containsExactly(3, 3);

        assertThat(all.get(3).intensityUnit()).isNull();
        assertThat(all.get(3).arrays().keySet()).containsExactly("ms level");
        assertThat(all.get(3).arrays().get("ms level")).containsExactly(1);

        assertThat(all.get(4).intensityUnit()).isNull();
        assertThat(all.get(4).arrays()).isEmpty();

        assertThat(warnings(records)).satisfiesExactlyInAnyOrder(
                m -> assertThat(m).contains("label", "point.label"),
                m -> assertThat(m).contains("chromatogram_index 2", "MS:1000131", "UO:0000269"));
    }

    /** Without an array index the plan is INFERRED: no unit, no extras and no WARNING, though two units mix. */
    @Test
    void inferredPlanGivesNoUnitAndNoArrays() {
        List<List<Chromatogram>> out = new ArrayList<>();
        List<LogRecord> records = ArrayIndexTest.captureLog(ChromatogramDataDecoder.class,
                () -> out.add(pointMemberChromatograms(false)));

        assertThat(out.get(0)).hasSize(5).allSatisfy(c -> {
            assertThat(c.intensityUnit()).isNull();
            assertThat(c.arrays()).isEmpty();
        });
        assertThat(warnings(records)).isEmpty();
    }

    /**
     * Chunk rows: an extra list aligns 1:1 with the time values its chunk decodes to, a null list reads as NaN
     * cells, and the chunks of one index are appended in row order; a skipped chunk (no start) adds no cells. A list
     * of another length drops that key for that chromatogram with one WARNING. The unit is that of the intensity
     * list or the SLOF entry that supplied the chunk; mixed units give null with one WARNING. An extra stored only
     * as a chunk transform is not decoded: it is dropped with one WARNING.
     */
    @Test
    void chunkMemberExtrasAndUnits() {
        List<List<Chromatogram>> out = new ArrayList<>();
        List<LogRecord> records = ArrayIndexTest.captureLog(ChromatogramDataDecoder.class,
                () -> out.add(chunkMemberChromatograms()));
        List<Chromatogram> all = out.get(0);

        assertThat(all.get(0).intensityUnit()).isEqualTo("MS:1000131");
        assertThat(all.get(0).arrays().keySet()).containsExactly("ms level");
        assertThat(all.get(0).arrays().get("ms level")).containsExactly(1, Double.NaN, 2, Double.NaN, Double.NaN);

        assertThat(all.get(1).intensityUnit()).isEqualTo("MS:1000131");
        assertThat(all.get(1).arrays()).isEmpty();

        assertThat(all.get(2).intensityUnit()).isEqualTo("UO:0000269");
        assertThat(all.get(2).arrays()).isEmpty();

        assertThat(all.get(3).intensityUnit()).isNull();
        assertThat(all.get(3).arrays().keySet()).containsExactly("ms level");
        assertThat(all.get(3).arrays().get("ms level")).containsExactly(5, 5, 6, 6);

        assertThat(warnings(records)).satisfiesExactlyInAnyOrder(
                m -> assertThat(m).contains("compressed level", "chunk.ms_level_slof"),
                m -> assertThat(m).contains("chromatogram_index 1", "ms level", "2", "3"),
                m -> assertThat(m).contains("chromatogram_index 3", "MS:1000131", "UO:0000269"));
    }

    /** A chunk extra list one cell longer than its chunk drops that key for that chromatogram, with a WARNING (CD3). */
    @Test
    void aChunkExtraListOneCellLongerIsDroppedForThatChromatogram() {
        SimpleGroupFactory rows = new SimpleGroupFactory(CHUNK_SCHEMA);
        String index = arrayIndex("chunk",
                entry("chunk.chromatogram_index", "index", "MS:1003870").put("sorting_rank", 0),
                entry("chunk.time_chunk_start", "chunk_start", "MS:1000595").put("sorting_rank", 1),
                entry("chunk.time_chunk_values", "chunk_values", "MS:1000595").put("sorting_rank", 1),
                entry("chunk.intensity", "chunk_secondary", "MS:1000515"),
                entry("chunk.ms_level", "chunk_secondary", "MS:1000786").put("array_name", "ms level"));
        Path data = write("chromatograms_data.parquet", CHUNK_SCHEMA,
                Map.of(SignalEntity.CHROMATOGRAM.arrayIndexKey(), index),
                row(rows, "chunk", "chromatogram_index", 0L, "time_chunk_start", 0, "time_chunk_values",
                        List.of(1, 1), "intensity", List.of(1, 2, 3), "ms_level", List.of(1, 1, 1, 1)),
                row(rows, "chunk", "chromatogram_index", 1L, "time_chunk_start", 0, "time_chunk_values",
                        List.of(1), "intensity", List.of(4, 5), "ms_level", List.of(2, 2)));

        List<List<Chromatogram>> out = new ArrayList<>();
        List<LogRecord> records = ArrayIndexTest.captureLog(ChromatogramDataDecoder.class,
                () -> out.add(chromatograms(data, 0, 1)));

        assertArrays(out.get(0).get(0), new double[] {0, 1, 2}, new double[] {1, 2, 3});
        assertThat(out.get(0).get(0).arrays()).isEmpty();
        assertThat(out.get(0).get(1).arrays().get("ms level")).containsExactly(2, 2);
        assertThat(warnings(records)).singleElement()
                .satisfies(m -> assertThat(m).contains("chromatogram_index 0", "ms level", "has 4 values",
                        "a chunk of 3 time points"));
    }

    /** An extra with a value column and a joined chunk transform is not decoded: dropped with a WARNING (CD5). */
    @Test
    void anExtraWithAValueColumnAndAJoinedTransformIsDropped() {
        SimpleGroupFactory rows = new SimpleGroupFactory(CHUNK_SCHEMA);
        String index = arrayIndex("chunk",
                entry("chunk.chromatogram_index", "index", "MS:1003870").put("sorting_rank", 0),
                entry("chunk.time_chunk_start", "chunk_start", "MS:1000595").put("sorting_rank", 1),
                entry("chunk.time_chunk_values", "chunk_values", "MS:1000595").put("sorting_rank", 1),
                entry("chunk.intensity", "chunk_secondary", "MS:1000515"),
                entry("chunk.ms_level", "chunk_secondary", "MS:1000786").put("array_name", "ms level")
                        .put("unit", "UO:0000186"),
                entry("chunk.ms_level_slof", "chunk_transform", "MS:1000786").put("array_name", "ms level")
                        .put("unit", "UO:0000186").put("transform", MSNumpress.ACC_NUMPRESS_SLOF));
        Path data = write("chromatograms_data.parquet", CHUNK_SCHEMA,
                Map.of(SignalEntity.CHROMATOGRAM.arrayIndexKey(), index),
                row(rows, "chunk", "chromatogram_index", 0L, "time_chunk_start", 0, "time_chunk_values",
                        List.of(1), "intensity", List.of(1, 2), "ms_level", List.of(1, 2)));

        List<List<Chromatogram>> out = new ArrayList<>();
        List<LogRecord> records = ArrayIndexTest.captureLog(ChromatogramDataDecoder.class,
                () -> out.add(chromatograms(data, 0)));

        assertArrays(out.get(0).get(0), new double[] {0, 1}, new double[] {1, 2});
        assertThat(out.get(0).get(0).arrays()).isEmpty();
        assertThat(warnings(records)).singleElement()
                .satisfies(m -> assertThat(m).contains("'ms level'", "chunk.ms_level_slof (MS:1002314)",
                        "the array is dropped"));
    }

    // ---- constructed members ----------------------------------------------------------------------------------

    /**
     * The chromatograms 0 to 4 of the point member: rows of 0 and 2 interleave, 1 has only absorbance, 2 mixes
     * counts and absorbance, 3 has no intensity at all, 4 has no rows; a row without an index is skipped. The array
     * index (when {@code withIndex}) lists two 'ms level' columns, a charge array and a string 'label' column.
     */
    private List<Chromatogram> pointMemberChromatograms(boolean withIndex) {
        SimpleGroupFactory rows = new SimpleGroupFactory(POINT_SCHEMA);
        String index = arrayIndex("point",
                entry("point.chromatogram_index", "index", "MS:1003870").put("sorting_rank", 0),
                entry("point.time", "point", "MS:1000595").put("sorting_rank", 1).put("unit", "UO:0000031"),
                entry("point.intensity", "point", "MS:1000515").put("unit", "MS:1000131")
                        .put("buffer_priority", "primary"),
                entry("point.intensity_f32_au", "point", "MS:1000515").put("unit", "UO:0000269"),
                entry("point.ms_level", "point", "MS:1000786").put("array_name", "ms level")
                        .put("unit", "UO:0000186").put("buffer_priority", "primary"),
                entry("point.ms_level_f64", "point", "MS:1000786").put("array_name", "ms level"),
                entry("point.charge", "point", "MS:1000516").put("array_name", "charge array"),
                entry("point.label", "point", "MS:1000786").put("array_name", "label"));
        Path data = write("chromatograms_data.parquet", POINT_SCHEMA,
                withIndex ? Map.of(SignalEntity.CHROMATOGRAM.arrayIndexKey(), index) : Map.of(),
                row(rows, "point", "chromatogram_index", 0L, "time", 1, "intensity", 10, "ms_level", 1,
                        "charge", 2, "label", "a"),
                row(rows, "point", "chromatogram_index", 2L, "time", 5, "intensity", 5, "ms_level", 3),
                row(rows, "point", "chromatogram_index", 0L, "time", 2, "intensity", 20, "ms_level_f64", 2.5),
                row(rows, "point", "time", 9, "intensity", 9, "ms_level", 9),
                row(rows, "point", "chromatogram_index", 1L, "time", 0.5, "intensity_f32_au", 0.25),
                row(rows, "point", "chromatogram_index", 2L, "time", 6, "intensity_f32_au", 0.5, "ms_level", 3),
                row(rows, "point", "chromatogram_index", 0L, "time", 3, "intensity", 30, "ms_level", 2,
                        "label", "b"),
                row(rows, "point", "chromatogram_index", 1L, "time", 0.75, "intensity_f32_au", 0.125),
                row(rows, "point", "chromatogram_index", 3L, "time", 7, "ms_level", 1));
        return chromatograms(data, 0, 1, 2, 3, 4);
    }

    /**
     * The chromatograms 0 to 3 of the chunk member: 0 has two chunks of the intensity list (the second without
     * 'ms level' cells) and a chunk without a start; 1 has an 'ms level' list one short in its first chunk; 2 has
     * SLOF intensity and null 'ms level' cells; 3 has one list chunk and one SLOF chunk. The SLOF intensity entry
     * carries another unit than the list.
     */
    private List<Chromatogram> chunkMemberChromatograms() {
        SimpleGroupFactory rows = new SimpleGroupFactory(CHUNK_SCHEMA);
        String index = arrayIndex("chunk",
                entry("chunk.chromatogram_index", "index", "MS:1003870").put("sorting_rank", 0),
                entry("chunk.time_chunk_start", "chunk_start", "MS:1000595").put("sorting_rank", 1),
                entry("chunk.time_chunk_values", "chunk_values", "MS:1000595").put("sorting_rank", 1),
                entry("chunk.intensity", "chunk_secondary", "MS:1000515").put("unit", "MS:1000131"),
                entry("chunk.intensity_numpress_slof_bytes", "chunk_transform", "MS:1000515")
                        .put("transform", MSNumpress.ACC_NUMPRESS_SLOF).put("unit", "UO:0000269"),
                entry("chunk.ms_level", "chunk_secondary", "MS:1000786").put("array_name", "ms level")
                        .put("unit", "UO:0000186"),
                entry("chunk.ms_level_slof", "chunk_transform", "MS:1000786").put("array_name", "compressed level")
                        .put("transform", MSNumpress.ACC_NUMPRESS_SLOF));
        byte[] slof = slof();
        Path data = write("chromatograms_data.parquet", CHUNK_SCHEMA,
                Map.of(SignalEntity.CHROMATOGRAM.arrayIndexKey(), index),
                row(rows, "chunk", "chromatogram_index", 0L, "time_chunk_start", 10, "time_chunk_values",
                        List.of(1, 1), "intensity", List.of(1, 2, 3), "ms_level", Arrays.asList(1, null, 2)),
                row(rows, "chunk", "chromatogram_index", 1L, "time_chunk_start", 0, "time_chunk_values",
                        List.of(1, 1), "intensity", List.of(7, 8, 9), "ms_level", List.of(1, 2)),
                row(rows, "chunk", "chromatogram_index", 0L, "time_chunk_start", 20, "time_chunk_values",
                        List.of(1), "intensity", List.of(4, 5)),
                row(rows, "chunk", "chromatogram_index", 1L, "time_chunk_start", 5, "time_chunk_values",
                        List.of(1), "intensity", List.of(1, 1), "ms_level", List.of(2, 2)),
                row(rows, "chunk", "chromatogram_index", 0L, "time_chunk_values", List.of(), "intensity",
                        List.of(9), "ms_level", List.of(9)),
                row(rows, "chunk", "chromatogram_index", 2L, "time_chunk_start", 0, "time_chunk_values",
                        List.of(0.5), "intensity_numpress_slof_bytes", slof, "ms_level", Arrays.asList(null, null),
                        "ms_level_slof", slof),
                row(rows, "chunk", "chromatogram_index", 3L, "time_chunk_start", 0, "time_chunk_values",
                        List.of(1), "intensity", List.of(1, 2), "ms_level", List.of(5, 5)),
                row(rows, "chunk", "chromatogram_index", 3L, "time_chunk_start", 2, "time_chunk_values",
                        List.of(1), "intensity_numpress_slof_bytes", slof, "ms_level", List.of(6, 6)));
        return chromatograms(data, 0, 1, 2, 3);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** The chromatograms of a dataset, read through {@link MzPeakReader}. */
    private static List<Chromatogram> chromatogramsOf(Path dataset) {
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            return reader.chromatograms();
        }
    }

    /**
     * The cells of {@code point.<field>} in the rows of chromatogram {@code index}, in row order, read straight from
     * the chromatogram data member (found by its manifest role); null cells stay null.
     */
    private static List<Double> storedCells(Path dataset, long index, String field) {
        List<Double> cells = new ArrayList<>();
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            String data = MzPeakManifest.fromSource(source).find(EntityType.CHROMATOGRAM, DataKind.DATA_ARRAYS)
                    .orElseThrow().name();
            ParquetGroups.forEach(source.inputFile(data), row -> {
                Group point = ParquetGroups.optGroup(row, "point");
                if (point != null && Long.valueOf(index).equals(ParquetGroups.optLong(point, "chromatogram_index"))) {
                    cells.add(ParquetGroups.optDouble(point, field));
                }
            });
        }
        return cells;
    }

    /** The cells as a {@code double[]}, null as NaN. */
    private static double[] cells(List<Double> cells) {
        return cells.stream().mapToDouble(v -> v == null ? Double.NaN : v).toArray();
    }

    /** The messages of the WARNING records. */
    private static List<String> warnings(List<LogRecord> records) {
        return records.stream().filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage).toList();
    }

    /** The chromatograms of {@code data}, with one header {@code c<index>} per index, in the given order. */
    private List<Chromatogram> chromatograms(Path data, long... indices) {
        SimpleGroupFactory rows = new SimpleGroupFactory(CHROMATOGRAM_METADATA);
        Path metadata = write("chromatograms_metadata.parquet", CHROMATOGRAM_METADATA, Map.of(),
                Arrays.stream(indices).mapToObj(i -> rows.newGroup().append("index", i).append("id", "c" + i))
                        .toArray(Group[]::new));
        return ChromatogramStore.load(new LocalInputFile(metadata), new LocalInputFile(data)).all();
    }

    private static void assertArrays(Chromatogram c, double[] time, double[] intensity) {
        assertThat(c.time()).as("%s time", c.id()).containsExactly(time);
        assertThat(c.intensity()).as("%s intensity", c.id()).containsExactly(intensity);
    }

    /** MS-Numpress SLOF bytes of the intensities 100 and 200. */
    private static byte[] slof() {
        double[] values = {100.0, 200.0};
        byte[] buffer = new byte[8 + 2 * values.length];
        int n = MSNumpress.encodeSlof(values, values.length, buffer,
                MSNumpress.optimalSlofFixedPoint(values, values.length));
        return Arrays.copyOf(buffer, n);
    }

    /** The decoded {@link #slof()} intensities. */
    private static double[] slofDecoded() {
        byte[] bytes = slof();
        return MSNumpress.decode(MSNumpress.ACC_NUMPRESS_SLOF, bytes, bytes.length);
    }

    /** One array index entry. */
    private static ObjectNode entry(String path, String format, String arrayType) {
        return JSON.createObjectNode().put("path", path).put("buffer_format", format).put("array_type", arrayType);
    }

    /** An array index footer value with {@code prefix} and {@code entries}. */
    private static String arrayIndex(String prefix, ObjectNode... entries) {
        ObjectNode index = JSON.createObjectNode().put("prefix", prefix);
        ArrayNode list = index.putArray("entries");
        for (ObjectNode e : entries) {
            list.add(e);
        }
        return index.toString();
    }

    /**
     * A row whose {@code prefix} struct holds the given field and value pairs. A null value leaves its column null;
     * a number is stored as the column's primitive type, a {@link List} as a LIST column (null items stay null) and a
     * {@code byte[]} as a list of uint8 items.
     */
    private static Group row(SimpleGroupFactory rows, String prefix, Object... fieldsAndValues) {
        Group row = rows.newGroup();
        Group struct = row.addGroup(prefix);
        for (int i = 0; i < fieldsAndValues.length; i += 2) {
            put(struct, (String) fieldsAndValues[i], fieldsAndValues[i + 1]);
        }
        return row;
    }

    private static void put(Group g, String field, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof byte[] bytes) {
            Group list = g.addGroup(field);
            for (byte b : bytes) {
                list.addGroup("list").append("item", b & 0xff);
            }
        } else if (value instanceof List<?> items) {
            Group list = g.addGroup(field);
            for (Object item : items) {
                put(list.addGroup("list"), "item", item);
            }
        } else if (value instanceof String s) {
            g.append(field, s);
        } else {
            Number n = (Number) value;
            switch (g.getType().getType(field).asPrimitiveType().getPrimitiveTypeName()) {
                case FLOAT -> g.append(field, n.floatValue());
                case DOUBLE -> g.append(field, n.doubleValue());
                case INT32 -> g.append(field, n.intValue());
                case INT64 -> g.append(field, n.longValue());
                default -> throw new IllegalArgumentException("not a numeric column: " + field);
            }
        }
    }

    /** Writes {@code rows} as the Parquet file {@code name} of the temporary directory, with footer key-values. */
    private Path write(String name, MessageType schema, Map<String, String> footer, Group... rows) {
        Path file = tmp.resolve(name);
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .withExtraMetaData(footer)
                .build()) {
            for (Group row : rows) {
                writer.write(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
