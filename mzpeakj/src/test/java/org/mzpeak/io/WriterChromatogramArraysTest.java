package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.ArrayIndex.BufferFormat;
import org.mzpeak.io.ArrayIndex.Entry;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.model.Chromatogram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

/**
 * MZ-2 (upstream WR-11, chromatogram part; design §11.7 of the upstream design, §5.8 and §6.1 B-36 of the mzML
 * design): the writer keeps the extra arrays of chromatograms, {@link Chromatogram#arrays()}, as extra columns of
 * chromatograms_data, and writes their intensities to one column per distinct {@link Chromatogram#intensityUnit()},
 * each column with an array-index entry, so that a chromatogram reads back with its arrays and its unit; the cv-list
 * prefix scan covers the array keys and the units.
 *
 * <p>Oracle: the vendored HUPO-PSI fixtures, read by {@link MzPeakReader}: current small.unpacked, whose TIC carries an
 * {@code ms level} array of 48 values (sum 82) and intensities in MS:1000131; current has_uv, whose TIC (MS:1000131,
 * 212 points, {@code ms level}) and DAD absorbance chromatogram (UO:0000269, 526 points) are stored in two intensity
 * columns; current diaPASEF.ref, whose TIC and BPC declare no intensity unit; changed where a test says so.
 */
class WriterChromatogramArraysTest {

    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final Path HAS_UV = Fixtures.current("has_uv.mzpeak");
    private static final Path DIA_REF = Fixtures.current("diaPASEF.ref.mzpeak");
    private static final String DAD = "DAD1 A: Sig=272,16 Ref=360,100";
    private static final String MS_LEVEL = "ms level";
    private static final String COUNTS = "MS:1000131";
    private static final String ABSORBANCE = "UO:0000269";
    private static final String DIMENSIONLESS = "UO:0000186";
    private static final String MINUTE = "UO:0000031";
    private static final String INDEX_ARRAY = "MS:1003870";
    private static final String TIME = "MS:1000595";
    private static final String INTENSITY = "MS:1000515";
    private static final String NON_STANDARD = "MS:1000786";
    private static final String CHARGE = "MS:1000516";
    private static final String SIGNAL_TO_NOISE = "MS:1000517";
    private static final String INT64 = "MS:1000522";
    private static final String FLOAT64 = "MS:1000523";
    private static final String PRIMARY = "primary";
    private static final String TIC_TYPE = "MS:1000235";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- (a) current small.unpacked ----------------------------------------------------------------------------

    /**
     * Current small.unpacked read, written in either metadata layout and read again: its TIC keeps its
     * {@code ms level} array, value for value, and its intensity unit; chromatograms_data lists the array as column
     * {@code ms_level}, a non-standard data array named {@code ms level} in UO:0000186, after the one intensity
     * column.
     */
    @ParameterizedTest
    @EnumSource(MetadataLayout.class)
    void theTicOfSmallKeepsItsMsLevelArray(MetadataLayout layout, @TempDir Path tmp) {
        Source source = ForwardBackwardCorpusTest.readSource(SMALL);
        Chromatogram tic = source.chromatograms.get(0);
        assertThat(tic.arrays().get(MS_LEVEL)).hasSize(48);
        Path out = tmp.resolve("small.mzpeak");

        MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, Options.defaults().withMetadataLayout(layout));

        List<Chromatogram> back = ForwardBackwardCorpusTest.readSource(out).chromatograms;
        assertThat(back).hasSize(1);
        Chromatogram b = back.get(0);
        assertThat(b.arrays()).containsOnlyKeys(MS_LEVEL);
        assertThat(b.arrays().get(MS_LEVEL)).hasSize(48).isEqualTo(tic.arrays().get(MS_LEVEL));
        assertThat(sum(b.arrays().get(MS_LEVEL))).isEqualTo(82.0);
        assertThat(b.intensityUnit()).isEqualTo(COUNTS);
        assertThat(b.time()).isEqualTo(tic.time());
        assertThat(b.intensity()).isEqualTo(tic.intensity());
        assertThat(chromatogramIndex(out).entries())
                .extracting(Entry::field, Entry::arrayType, Entry::arrayName, Entry::unit, Entry::dataType,
                        Entry::format, Entry::bufferPriority)
                .containsExactly(
                        tuple("chromatogram_index", INDEX_ARRAY, "index array", null, INT64, BufferFormat.INDEX,
                                PRIMARY),
                        tuple("time", TIME, "time array", MINUTE, FLOAT64, BufferFormat.POINT, PRIMARY),
                        tuple("intensity", INTENSITY, "intensity array", COUNTS, FLOAT64, BufferFormat.POINT,
                                PRIMARY),
                        tuple("ms_level", NON_STANDARD, MS_LEVEL, DIMENSIONLESS, FLOAT64, BufferFormat.POINT,
                                PRIMARY));
    }

    // ---- (b) has_uv ----------------------------------------------------------------------------------------------

    /**
     * Current has_uv read, written as an archive in either metadata layout and read again: the TIC keeps its
     * intensities in MS:1000131 and its {@code ms level} array, the DAD chromatogram its absorbances in UO:0000269 and
     * no {@code ms level}. The MS:1000131 column is {@code intensity}, with buffer priority primary; the absorbance
     * column is {@code intensity_UO_0000269}, without buffer priority, as the reference writes has_uv's.
     */
    @ParameterizedTest
    @EnumSource(MetadataLayout.class)
    void hasUvKeepsTheIntensityUnitOfEachChromatogramAndTheTicMsLevel(MetadataLayout layout, @TempDir Path tmp) {
        Source source = ForwardBackwardCorpusTest.readSource(HAS_UV);
        Path out = tmp.resolve("has_uv.mzpeak");

        MzPeakWriter.writeArchive(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, Options.defaults().withMetadataLayout(layout));

        List<Chromatogram> back = ForwardBackwardCorpusTest.readSource(out).chromatograms;
        assertThat(back).extracting(Chromatogram::id).containsExactly("TIC", DAD);
        Chromatogram tic = back.get(0);
        assertThat(tic.intensityUnit()).isEqualTo(COUNTS);
        assertThat(tic.intensity()).hasSize(212).isEqualTo(source.chromatograms.get(0).intensity());
        assertThat(tic.time()).isEqualTo(source.chromatograms.get(0).time());
        assertThat(tic.arrays()).containsOnlyKeys(MS_LEVEL);
        assertThat(tic.arrays().get(MS_LEVEL)).hasSize(212).isEqualTo(source.chromatograms.get(0).arrays()
                .get(MS_LEVEL));
        Chromatogram dad = back.get(1);
        assertThat(dad.intensityUnit()).isEqualTo(ABSORBANCE);
        assertThat(dad.intensity()).hasSize(526).isEqualTo(source.chromatograms.get(1).intensity());
        assertThat(sum(dad.intensity())).isCloseTo(57.358741760253906, within(1e-9));
        assertThat(dad.time()).isEqualTo(source.chromatograms.get(1).time());
        assertThat(dad.arrays()).isEmpty();
        assertThat(chromatogramIndex(out).entries())
                .extracting(Entry::field, Entry::arrayType, Entry::unit, Entry::bufferPriority)
                .containsExactly(
                        tuple("chromatogram_index", INDEX_ARRAY, null, PRIMARY),
                        tuple("time", TIME, MINUTE, PRIMARY),
                        tuple("intensity", INTENSITY, COUNTS, PRIMARY),
                        tuple("intensity_UO_0000269", INTENSITY, ABSORBANCE, null),
                        tuple("ms_level", NON_STANDARD, DIMENSIONLESS, PRIMARY));
    }

    // ---- (c) diaPASEF.ref ------------------------------------------------------------------------------------------

    /**
     * Current diaPASEF.ref, whose TIC and BPC declare no intensity unit: they read back without one, from the one
     * intensity column {@code intensity}, whose entry has no unit.
     */
    @Test
    void chromatogramsWithoutIntensityUnitKeepNone(@TempDir Path tmp) {
        Source source = ForwardBackwardCorpusTest.readSource(DIA_REF);
        assertThat(source.chromatograms).extracting(Chromatogram::intensityUnit).containsExactly(null, null);
        Path out = tmp.resolve("dia.mzpeak");

        MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata);

        List<Chromatogram> back = ForwardBackwardCorpusTest.readSource(out).chromatograms;
        assertThat(back).extracting(Chromatogram::id).containsExactly("TIC", "BPC");
        for (int i = 0; i < 2; i++) {
            assertThat(back.get(i).intensityUnit()).as("chromatogram %d unit", i).isNull();
            assertThat(back.get(i).intensity()).as("chromatogram %d intensity", i)
                    .isEqualTo(source.chromatograms.get(i).intensity());
            assertThat(back.get(i).arrays()).as("chromatogram %d arrays", i).isEmpty();
        }
        assertThat(chromatogramIndex(out).entries()).filteredOn(e -> INTENSITY.equals(e.arrayType()))
                .extracting(Entry::field, Entry::unit, Entry::bufferPriority)
                .containsExactly(tuple("intensity", null, PRIMARY));
    }

    // ---- (d) no warning about chromatograms ------------------------------------------------------------------------

    static Stream<Path> fixturesWithChromatogramArraysOrUnits() {
        return Stream.of(Fixtures.legacy("small.unpacked.mzpeak"), Fixtures.legacy("has_uv.mzpeak"), SMALL, HAS_UV);
    }

    /**
     * The fixtures whose chromatograms carry an extra array and an intensity unit are written in either metadata
     * layout without a warning: the writer no longer drops either.
     */
    @ParameterizedTest
    @MethodSource("fixturesWithChromatogramArraysOrUnits")
    void writingChromatogramArraysAndUnitsLogsNoWarning(Path fixture, @TempDir Path tmp) {
        Source source = ForwardBackwardCorpusTest.readSource(fixture);
        assertThat(source.chromatograms).anySatisfy(c -> assertThat(c.arrays()).isNotEmpty());
        assertThat(source.chromatograms).anySatisfy(c -> assertThat(c.intensityUnit()).isNotNull());

        for (MetadataLayout layout : MetadataLayout.values()) {
            Path out = tmp.resolve(layout + ".mzpeak");
            List<String> warnings = warnings(() -> MzPeakWriter.writeDirectory(out, source.spectra,
                    source.chromatograms, source.wavelengthSpectra, source.scanSettings, source.fileMetadata,
                    Options.defaults().withMetadataLayout(layout)));
            assertThat(warnings).as("%s", layout).noneMatch(w -> w.contains("chromatogram"));
        }
    }

    // ---- (f) the CVs of a unit and an array key ----------------------------------------------------------------------

    /**
     * A chromatogram in NCIT:C25613 with an array BTO:0000142, two known CVs the data use nowhere else:
     * metadata.cv_list declares both, the write warns about nothing, and the chromatogram reads back with its unit and
     * its array, written as column {@code bto_0000142} without a unit.
     */
    @Test
    void theCvsOfAnIntensityUnitAndAnArrayKeyAreDeclared(@TempDir Path tmp) throws IOException {
        Chromatogram c = new Chromatogram(0, "C", TIC_TYPE, new double[] {1.0, 2.0}, new double[] {10.0, 20.0},
                "NCIT:C25613", Map.of("BTO:0000142", new double[] {3.0, 4.0}));
        Path out = tmp.resolve("cv.mzpeak");

        List<String> warnings = warnings(() -> MzPeakWriter.writeDirectory(out, List.of(), List.of(c)));

        assertThat(warnings).isEmpty();
        assertThat(cvIds(out)).containsExactly("MS", "UO", "NCIT", "BTO");
        Chromatogram back = ForwardBackwardCorpusTest.readSource(out).chromatograms.get(0);
        assertThat(back.intensityUnit()).isEqualTo("NCIT:C25613");
        assertThat(back.arrays()).containsOnlyKeys("BTO:0000142");
        assertThat(back.arrays().get("BTO:0000142")).containsExactly(3.0, 4.0);
        assertThat(chromatogramIndex(out).entries()).filteredOn(e -> e.format() == BufferFormat.POINT)
                .extracting(Entry::field, Entry::arrayType, Entry::arrayName, Entry::unit)
                .containsExactly(
                        tuple("time", TIME, "time array", MINUTE),
                        tuple("intensity", INTENSITY, "intensity array", "NCIT:C25613"),
                        tuple("bto_0000142", "BTO:0000142", "BTO:0000142", null));
    }

    // ---- (g) a chromatogram without points ----------------------------------------------------------------------

    /**
     * has_uv's chromatograms and a third one without points in UO:0000269, written in the split layout: the empty
     * chromatogram reads back without points and without intensity unit, since the reader takes the unit from the
     * column that supplied its values (design §6.1 B-36); the TIC and the DAD chromatogram read back unchanged.
     */
    @Test
    void aChromatogramWithoutPointsReadsBackWithoutIntensityUnit(@TempDir Path tmp) {
        Source source = ForwardBackwardCorpusTest.readSource(HAS_UV);
        List<Chromatogram> chromatograms = new ArrayList<>(source.chromatograms);
        chromatograms.add(new Chromatogram(2, "empty", "MS:1000812", new double[0], new double[0], ABSORBANCE,
                Map.of()));
        Path out = tmp.resolve("empty.mzpeak");

        MzPeakWriter.writeDirectory(out, source.spectra, chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata,
                Options.defaults().withMetadataLayout(MetadataLayout.SPLIT));

        List<Chromatogram> back = ForwardBackwardCorpusTest.readSource(out).chromatograms;
        assertThat(back).extracting(Chromatogram::id).containsExactly("TIC", DAD, "empty");
        Chromatogram empty = back.get(2);
        assertThat(empty.size()).isZero();
        assertThat(empty.intensityUnit()).isNull();
        assertThat(empty.arrays()).isEmpty();
        for (int i = 0; i < 2; i++) {
            assertSameChromatogram(source.chromatograms.get(i), back.get(i));
        }
    }

    // ---- intensity columns -------------------------------------------------------------------------------------------

    /**
     * Seven chromatograms in six distinct units, null included: the MS:1000131 column is {@code intensity}, every
     * other unit's column {@code intensity_} and the unit with each character other than an ASCII letter, digit or
     * underscore as {@code _}, the null unit's {@code intensity_unitless}, made unique with {@code _2}; the columns
     * follow the primary one in the order the units first occur. Each chromatogram reads back with its own unit and
     * values.
     */
    @Test
    void eachDistinctUnitGetsAnIntensityColumnOfItsOwn(@TempDir Path tmp) {
        List<String> units = Arrays.asList(ABSORBANCE, null, COUNTS, "UO_0000269", "unitless", null, "a b.c");
        List<Chromatogram> chromatograms = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            chromatograms.add(new Chromatogram(i, "C" + i, TIC_TYPE, new double[] {1.0, 2.0},
                    new double[] {i, i + 0.5}, units.get(i), Map.of()));
        }
        Path out = tmp.resolve("units.mzpeak");

        MzPeakWriter.writeDirectory(out, List.of(), chromatograms);

        assertThat(chromatogramIndex(out).entries()).filteredOn(e -> INTENSITY.equals(e.arrayType()))
                .extracting(Entry::field, Entry::unit, Entry::bufferPriority, Entry::dataType, Entry::format)
                .containsExactly(
                        tuple("intensity", COUNTS, PRIMARY, FLOAT64, BufferFormat.POINT),
                        tuple("intensity_UO_0000269", ABSORBANCE, null, FLOAT64, BufferFormat.POINT),
                        tuple("intensity_unitless", null, null, FLOAT64, BufferFormat.POINT),
                        tuple("intensity_UO_0000269_2", "UO_0000269", null, FLOAT64, BufferFormat.POINT),
                        tuple("intensity_unitless_2", "unitless", null, FLOAT64, BufferFormat.POINT),
                        tuple("intensity_a_b_c", "a b.c", null, FLOAT64, BufferFormat.POINT));
        List<Chromatogram> back = ForwardBackwardCorpusTest.readSource(out).chromatograms;
        assertThat(back).extracting(Chromatogram::intensityUnit).containsExactlyElementsOf(units);
        for (int i = 0; i < units.size(); i++) {
            assertThat(back.get(i).intensity()).as("chromatogram %d", i).containsExactly(i, i + 0.5);
        }
    }

    /**
     * Without MS:1000131, the unit of the first chromatogram in index order gets the column {@code intensity}, whatever
     * the order of the caller's list.
     */
    @Test
    void withoutCountsTheFirstUnitInIndexOrderIsTheIntensityColumn(@TempDir Path tmp) {
        Chromatogram unitless = new Chromatogram(1, "unitless", TIC_TYPE, new double[] {1.0}, new double[] {2.0},
                null, Map.of());
        Chromatogram absorbance = new Chromatogram(0, "absorbance", "MS:1000812", new double[] {1.0},
                new double[] {0.5}, ABSORBANCE, Map.of());
        Path out = tmp.resolve("first.mzpeak");

        MzPeakWriter.writeDirectory(out, List.of(), List.of(unitless, absorbance));

        assertThat(chromatogramIndex(out).entries()).filteredOn(e -> INTENSITY.equals(e.arrayType()))
                .extracting(Entry::field, Entry::unit, Entry::bufferPriority)
                .containsExactly(tuple("intensity", ABSORBANCE, PRIMARY), tuple("intensity_unitless", null, null));
        List<Chromatogram> back = ForwardBackwardCorpusTest.readSource(out).chromatograms;
        assertThat(back).extracting(Chromatogram::id, Chromatogram::intensityUnit)
                .containsExactly(tuple("absorbance", ABSORBANCE), tuple("unitless", null));
        assertThat(back.get(0).intensity()).containsExactly(0.5);
        assertThat(back.get(1).intensity()).containsExactly(2.0);
    }

    // ---- extra columns -----------------------------------------------------------------------------------------------

    /**
     * Two chromatograms with different keys: chromatograms_data gets one column per key of either, in first-seen order
     * (index order, then map order), named as the extra columns of spectra and made unique among the time, index and
     * intensity columns; the unit is the {@code CvTerms.DEFAULT_ARRAY_UNITS} one or none. Each chromatogram reads back
     * with exactly its keys, a NaN value as NaN, and writes null cells for the keys it lacks.
     */
    @Test
    void everyKeyOfEveryChromatogramGetsAColumn(@TempDir Path tmp) {
        Map<String, double[]> first = new LinkedHashMap<>();
        first.put(MS_LEVEL, new double[] {1.0, 2.0});
        first.put(SIGNAL_TO_NOISE, new double[] {Double.NaN, 7.5});
        Map<String, double[]> second = new LinkedHashMap<>();
        second.put(CHARGE, new double[] {2.0, 3.0});
        second.put("intensity", new double[] {4.0, 5.0});
        second.put("time", new double[] {6.0, 7.0});
        second.put("intensity unitless", new double[] {8.0, 9.0});
        second.put("chromatogram index", new double[] {10.0, 11.0});
        second.put(MS_LEVEL, new double[] {2.0, 2.0});
        Chromatogram c0 = new Chromatogram(0, "A", TIC_TYPE, new double[] {1.0, 2.0}, new double[] {1.0, 1.0}, null,
                first);
        Chromatogram c1 = new Chromatogram(1, "B", TIC_TYPE, new double[] {1.0, 2.0}, new double[] {2.0, 2.0}, COUNTS,
                second);
        Path out = tmp.resolve("extras.mzpeak");

        MzPeakWriter.writeDirectory(out, List.of(), List.of(c0, c1));

        assertThat(chromatogramIndex(out).entries()).filteredOn(e -> !INTENSITY.equals(e.arrayType()))
                .extracting(Entry::field, Entry::arrayType, Entry::arrayName, Entry::unit, Entry::dataType)
                .containsExactly(
                        tuple("chromatogram_index", INDEX_ARRAY, "index array", null, INT64),
                        tuple("time", TIME, "time array", MINUTE, FLOAT64),
                        tuple("ms_level", NON_STANDARD, MS_LEVEL, DIMENSIONLESS, FLOAT64),
                        tuple("signal_to_noise", SIGNAL_TO_NOISE, "signal to noise array", null, FLOAT64),
                        tuple("charge", CHARGE, "charge array", null, FLOAT64),
                        tuple("intensity_2", NON_STANDARD, "intensity", null, FLOAT64),
                        tuple("time_2", NON_STANDARD, "time", null, FLOAT64),
                        tuple("intensity_unitless_2", NON_STANDARD, "intensity unitless", null, FLOAT64),
                        tuple("chromatogram_index_2", NON_STANDARD, "chromatogram index", null, FLOAT64));
        List<Chromatogram> back = ForwardBackwardCorpusTest.readSource(out).chromatograms;
        assertSameArrays("A", first, back.get(0).arrays());
        assertSameArrays("B", second, back.get(1).arrays());
        assertThat(back).extracting(Chromatogram::intensityUnit).containsExactly(null, COUNTS);
    }

    /**
     * A chromatogram whose time is not ascending is written re-sorted with one warning, its intensity and its extra
     * arrays permuted alike; the caller's arrays are not modified.
     */
    @Test
    void aReSortedChromatogramPermutesItsExtraArraysAlike(@TempDir Path tmp) {
        double[] time = {3.0, 1.0, 2.0};
        double[] intensity = {30.0, 10.0, 20.0};
        double[] msLevel = {2.0, 1.0, 1.0};
        Chromatogram c = new Chromatogram(5, "TIC", TIC_TYPE, time, intensity, COUNTS, Map.of(MS_LEVEL, msLevel));
        Path out = tmp.resolve("sorted.mzpeak");

        List<String> warnings = warnings(() -> MzPeakWriter.writeDirectory(out, List.of(), List.of(c)));

        assertThat(warnings).containsExactly("chromatogram 5: main axis not ascending; re-sorted 3 rows");
        Chromatogram back = ForwardBackwardCorpusTest.readSource(out).chromatograms.get(0);
        assertThat(back.time()).containsExactly(1.0, 2.0, 3.0);
        assertThat(back.intensity()).containsExactly(10.0, 20.0, 30.0);
        assertThat(back.arrays().get(MS_LEVEL)).containsExactly(1.0, 1.0, 2.0);
        assertThat(msLevel).as("the caller's array").containsExactly(2.0, 1.0, 1.0);
    }

    /**
     * The time and intensity array types cannot be extra keys of a chromatogram, which holds those arrays in
     * {@code time()} and {@code intensity()}: a reader would take such a column for the main axis or an intensity. The
     * write is refused before any I/O.
     */
    @ParameterizedTest
    @ValueSource(strings = {TIME, INTENSITY})
    void aMainArrayTypeAsAChromatogramArrayKeyIsRefused(String key, @TempDir Path tmp) {
        Chromatogram c = new Chromatogram(3, "TIC", TIC_TYPE, new double[] {1.0}, new double[] {2.0}, COUNTS,
                Map.of(key, new double[] {4.0}));
        Path out = tmp.resolve("main.mzpeak");

        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(out, List.of(), List.of(c)))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("chromatogram 3: the extra array " + key + " ("
                        + (key.equals(TIME) ? "time array" : "intensity array") + ") is a main array of a"
                        + " chromatogram, which time() and intensity() hold; it cannot be written as an extra column");
        assertThat(out).doesNotExist();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** {@code b} has the time, intensity, intensity unit and extra arrays of {@code a}. */
    private static void assertSameChromatogram(Chromatogram a, Chromatogram b) {
        assertThat(b.id()).isEqualTo(a.id());
        assertThat(b.time()).as("%s time", a.id()).isEqualTo(a.time());
        assertThat(b.intensity()).as("%s intensity", a.id()).isEqualTo(a.intensity());
        assertThat(b.intensityUnit()).as("%s unit", a.id()).isEqualTo(a.intensityUnit());
        assertSameArrays(a.id(), a.arrays(), b.arrays());
    }

    /** {@code actual} has the keys of {@code expected}, each with an equal array ({@link Double#compare}). */
    private static void assertSameArrays(String what, Map<String, double[]> expected, Map<String, double[]> actual) {
        assertThat(actual.keySet()).as("%s keys", what).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((key, values) -> assertThat(actual.get(key)).as("%s %s", what, key).isEqualTo(values));
    }

    private static double sum(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum;
    }

    /** The WARNING messages {@link MzPeakWriter} logs while {@code write} runs. */
    private static List<String> warnings(Runnable write) {
        return ArrayIndexTest.captureLog(MzPeakWriter.class, write).stream()
                .filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage).toList();
    }

    /** The ids of the dataset's metadata.cv_list, in order. */
    private static List<String> cvIds(Path dataset) throws IOException {
        JsonNode index = MAPPER.readTree(Files.readAllBytes(dataset.resolve(ManifestWriter.INDEX)));
        List<String> ids = new ArrayList<>();
        for (JsonNode cv : index.path("metadata").path("cv_list")) {
            ids.add(cv.path("id").asText());
        }
        return ids;
    }

    /** The array index in the footer of the chromatograms_data member of {@code dataset}, a directory or archive. */
    static ArrayIndex chromatogramIndex(Path dataset) {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            String name = manifest.find(EntityType.CHROMATOGRAM, DataKind.DATA_ARRAYS).orElseThrow().name();
            ParquetFooter footer = ParquetFooter.read(source.inputFile(name));
            return ArrayIndex.parse(footer.keyValue().get(SignalEntity.CHROMATOGRAM.arrayIndexKey()),
                    SignalEntity.CHROMATOGRAM);
        }
    }
}
