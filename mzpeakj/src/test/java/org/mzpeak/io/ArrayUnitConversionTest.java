package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ArrayIndex.BufferFormat;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.CvTerms;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MZ-3 (mzML design §5.7 array units, §6.1 B-15, §6.2 A-13): the reader returns chromatogram times in minutes and
 * the drift-time ion mobility arrays of spectra in milliseconds, the units {@code CvTerms.DEFAULT_ARRAY_UNITS}
 * documents, converting from the unit of the column's array-index entry; a unit it cannot convert is rejected.
 *
 * <p>Each case rewrites one signal member with parquet-java's Group API ({@link #rewrite}): the values of the named
 * columns scaled into another unit, the unit of their array-index entries set to it, every other column and footer
 * key kept. The members are those of the vendored fixtures (current small.unpacked, small.chunked and
 * diaPASEF.grid) or of a dataset {@link MzPeakWriter} wrote; the oracle is the same member read unchanged. Before
 * MZ-3 the reader passed the stored values through, so every case with another unit than the model's fails there.
 */
class ArrayUnitConversionTest {

    private static final Path SMALL_UNPACKED = Fixtures.current("small.unpacked.mzpeak");
    private static final Path SMALL_CHUNKED = Fixtures.current("small.chunked.mzpeak");
    private static final Path DIA_GRID = Fixtures.current("diaPASEF.grid.mzpeak");
    private static final String CHROMATOGRAMS_DATA = "chromatograms_data.parquet";
    private static final String CHROMATOGRAMS_METADATA = "chromatograms_metadata.parquet";
    private static final String SPECTRA_PEAKS = "spectra_peaks.parquet";
    private static final String TIME_ARRAY = "MS:1000595";
    private static final String TIC = "MS:1000235";
    private static final String MEAN_DRIFT_TIME = "MS:1002477";
    private static final String MEAN_INVERSE_REDUCED_ION_MOBILITY = "MS:1003006";
    private static final String MS_LEVEL = "ms level";
    private static final double RELATIVE = 1e-15;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    // ---- chromatogram time -------------------------------------------------------------------------------------

    /** Time units and how a time in minutes is stored in each. */
    static Stream<Arguments> timeUnits() {
        return Stream.of(
                Arguments.of(null, (DoubleUnaryOperator) v -> v),
                Arguments.of("UO:0000031", (DoubleUnaryOperator) v -> v),
                Arguments.of("UO:0000010", (DoubleUnaryOperator) v -> v * 60),
                Arguments.of("UO:0000028", (DoubleUnaryOperator) v -> v * 60_000),
                Arguments.of("UO:0000032", (DoubleUnaryOperator) v -> v / 60));
    }

    /**
     * (a), (b) Current small.unpacked with its point-layout time column stored in {@code unit}: every chromatogram,
     * the TIC among them, has the time of the unchanged member within a relative 1e-15, and the same intensity and
     * ms level arrays. No unit and minutes leave the values as stored.
     */
    @ParameterizedTest(name = "unit {0}")
    @MethodSource("timeUnits")
    void aChromatogramTimeInAnotherUnitIsReadInMinutes(String unit, DoubleUnaryOperator fromMinutes)
            throws IOException {
        Path dir = copySmallUnpacked();
        Path data = dir.resolve(CHROMATOGRAMS_DATA);
        rewriteInPlace(data, SignalEntity.CHROMATOGRAM, Map.of("point.time", new Edit(unit, fromMinutes)));
        assertThat(plan(new LocalInputFile(data), SignalEntity.CHROMATOGRAM).axis().values().unit()).isEqualTo(unit);

        try (MzPeakReader original = MzPeakReader.open(SMALL_UNPACKED); MzPeakReader edited = MzPeakReader.open(dir)) {
            List<Chromatogram> expected = original.chromatograms();
            List<Chromatogram> actual = edited.chromatograms();
            assertThat(actual).hasSameSizeAs(expected);
            assertThat(actual).extracting(Chromatogram::typeCurie).contains(TIC);
            for (int c = 0; c < expected.size(); c++) {
                Chromatogram a = expected.get(c);
                Chromatogram b = actual.get(c);
                assertThat(b.id()).isEqualTo(a.id());
                assertClose(b.time(), a.time(), RELATIVE, "time of " + a.id());
                assertThat(b.intensity()).as("intensity of %s", a.id()).isEqualTo(a.intensity());
                assertThat(b.arrays().keySet()).as("arrays of %s", a.id()).isEqualTo(a.arrays().keySet());
                assertThat(b.arrays().get(MS_LEVEL)).as("ms level of %s", a.id()).isEqualTo(a.arrays().get(MS_LEVEL));
            }
            assertClose(edited.tic().orElseThrow().time(), original.tic().orElseThrow().time(), RELATIVE, "TIC");
        }
    }

    /** (c) A time column in a unit that is no time unit is rejected, naming the member, the column and the unit. */
    @Test
    void aChromatogramTimeInAnUnknownUnitIsRejected() throws IOException {
        Path dir = copySmallUnpacked();
        rewriteInPlace(dir.resolve(CHROMATOGRAMS_DATA), SignalEntity.CHROMATOGRAM,
                Map.of("point.time", new Edit("UO:0000033", v -> v)));

        try (MzPeakReader reader = MzPeakReader.open(dir)) {
            assertThatThrownBy(reader::chromatograms)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(CHROMATOGRAMS_DATA + ": chromatogram time column point.time has the unit UO:0000033;"
                            + " supported are minute (UO:0000031), second (UO:0000010), millisecond (UO:0000028)"
                            + " and hour (UO:0000032)");
        }
    }

    /**
     * (f) Column statistics are of the stored values: the data arrays role has none
     * ({@link MzPeakReader#columnStatistics} documents it), so the time column rewritten in seconds is neither
     * converted nor reported there.
     */
    @Test
    void columnStatisticsOfTheDataArraysStayEmpty() throws IOException {
        Path dir = copySmallUnpacked();
        rewriteInPlace(dir.resolve(CHROMATOGRAMS_DATA), SignalEntity.CHROMATOGRAM,
                Map.of("point.time", new Edit("UO:0000010", v -> v * 60)));

        try (MzPeakReader reader = MzPeakReader.open(dir)) {
            assertThat(reader.columnStatistics(EntityType.CHROMATOGRAM, DataKind.DATA_ARRAYS, TIME_ARRAY)).isEmpty();
        }
    }

    /**
     * Current small.chunked, whose chromatograms are in the chunk layout: the chunk start in milliseconds, the chunk
     * end and the deltas in seconds. Each column is converted by its own unit, so the times are those of the
     * unchanged member within a relative 1e-15.
     */
    @Test
    void aChunkedChromatogramTimeIsConvertedColumnByColumn() {
        try (MzPeakSource source = MzPeakSource.open(SMALL_CHUNKED)) {
            InputFile metadata = source.inputFile(CHROMATOGRAMS_METADATA);
            InputFile data = source.inputFile(CHROMATOGRAMS_DATA);
            assertThat(plan(data, SignalEntity.CHROMATOGRAM).layout()).isEqualTo(SignalPlan.Layout.CHUNK);
            Path rewritten = tmp.resolve(CHROMATOGRAMS_DATA);
            Map<String, Edit> edits = new HashMap<>();
            edits.put("chunk.time_chunk_start", new Edit("UO:0000028", v -> v * 60_000));
            edits.put("chunk.time_chunk_end", new Edit("UO:0000010", v -> v * 60));
            edits.put("chunk.time_chunk_values", new Edit("UO:0000010", v -> v * 60));
            rewrite(data, rewritten, SignalEntity.CHROMATOGRAM, edits);

            List<Chromatogram> expected = ChromatogramStore.load(metadata, data).all();
            List<Chromatogram> actual = ChromatogramStore.load(metadata, new LocalInputFile(rewritten)).all();
            assertThat(actual).hasSameSizeAs(expected);
            int points = 0;
            for (int c = 0; c < expected.size(); c++) {
                assertClose(actual.get(c).time(), expected.get(c).time(), RELATIVE, "time of " + expected.get(c).id());
                assertThat(actual.get(c).intensity()).isEqualTo(expected.get(c).intensity());
                points += expected.get(c).size();
            }
            assertThat(points).isPositive();
        }
    }

    /** A chunk start in a unit that is no time unit is rejected, naming the column. */
    @Test
    void aChunkStartInAnUnknownUnitIsRejected() {
        try (MzPeakSource source = MzPeakSource.open(SMALL_CHUNKED)) {
            InputFile metadata = source.inputFile(CHROMATOGRAMS_METADATA);
            Path rewritten = tmp.resolve(CHROMATOGRAMS_DATA);
            rewrite(source.inputFile(CHROMATOGRAMS_DATA), rewritten, SignalEntity.CHROMATOGRAM,
                    Map.of("chunk.time_chunk_start", new Edit("UO:0000187", v -> v)));

            assertThatThrownBy(() -> ChromatogramStore.load(metadata, new LocalInputFile(rewritten)))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("chromatogram data member: chromatogram time column chunk.time_chunk_start has the"
                            + " unit UO:0000187; supported are minute (UO:0000031), second (UO:0000010), millisecond"
                            + " (UO:0000028) and hour (UO:0000032)");
        }
    }

    // ---- drift-time arrays -------------------------------------------------------------------------------------

    /** Drift-time units and how a drift time in milliseconds is stored in each. */
    static Stream<Arguments> driftTimeUnits() {
        return Stream.of(
                Arguments.of(null, (DoubleUnaryOperator) v -> v),
                Arguments.of("UO:0000028", (DoubleUnaryOperator) v -> v),
                Arguments.of("UO:0000010", (DoubleUnaryOperator) v -> v / 1000),
                Arguments.of("UO:0000031", (DoubleUnaryOperator) v -> v / 60_000));
    }

    /**
     * (d) One centroid spectrum written with the drift-time array {1.5, 2.5} (MS:1002477), its spectra_peaks column
     * then stored in {@code unit}, such as {0.0015, 0.0025} in seconds: the reader returns {1.5, 2.5} within a
     * relative 1e-15, in {@link Spectrum#arrays()} and {@link Spectrum#peakArrays()}.
     */
    @ParameterizedTest(name = "unit {0}")
    @MethodSource("driftTimeUnits")
    void aDriftTimeArrayInAnotherUnitIsReadInMilliseconds(String unit, DoubleUnaryOperator fromMilliseconds) {
        Path dir = tmp.resolve("drift.mzpeak");
        MzPeakWriter.writeDirectory(dir, List.of(driftTimeSpectrum()));
        Path peaks = dir.resolve(SPECTRA_PEAKS);
        rewriteInPlace(peaks, SignalEntity.SPECTRUM,
                Map.of("point.mean_drift_time", new Edit(unit, fromMilliseconds)));
        if ("UO:0000010".equals(unit)) {
            assertThat(cells(peaks, "point", "mean_drift_time")).containsExactly(0.0015, 0.0025);
        }

        try (MzPeakReader reader = MzPeakReader.open(dir)) {
            Spectrum s = reader.getSpectrum(0).orElseThrow();
            assertThat(s.arrays().keySet()).containsExactly(MEAN_DRIFT_TIME);
            assertClose(s.arrays().get(MEAN_DRIFT_TIME), new double[] {1.5, 2.5}, RELATIVE, "arrays");
            assertClose(s.peakArrays().get(MEAN_DRIFT_TIME), new double[] {1.5, 2.5}, RELATIVE, "peak arrays");
            assertThat(s.mz()).containsExactly(100.0, 200.0);
            assertThat(s.intensity()).containsExactly(10.0, 20.0);
        }
    }

    /** (d) A drift-time array in a unit that is no time unit is rejected, naming the member, column and unit. */
    @Test
    void aDriftTimeArrayInAnUnknownUnitIsRejected() {
        Path dir = tmp.resolve("drift.mzpeak");
        MzPeakWriter.writeDirectory(dir, List.of(driftTimeSpectrum()));
        rewriteInPlace(dir.resolve(SPECTRA_PEAKS), SignalEntity.SPECTRUM,
                Map.of("point.mean_drift_time", new Edit("UO:0000033", v -> v)));

        try (MzPeakReader reader = MzPeakReader.open(dir)) {
            assertThatThrownBy(() -> reader.getSpectrum(0))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(SPECTRA_PEAKS + ": drift time array column point.mean_drift_time (MS:1002477) has"
                            + " the unit UO:0000033; supported are millisecond (UO:0000028), second (UO:0000010)"
                            + " and minute (UO:0000031)");
        }
    }

    /**
     * (e) The chunk layout: the spectrum written in the Numpress layout keeps its drift time as a chunk secondary
     * list; stored in seconds, the decoded chunk row reads in milliseconds through {@link SpectrumArrayStore}.
     */
    @Test
    void aDriftTimeListOfAChunkRowIsConverted() {
        Path dir = tmp.resolve("drift-numpress.mzpeak");
        MzPeakWriter.writeDirectory(dir, List.of(driftTimeSpectrum()), List.of(), List.of(), List.of(),
                FileMetadata.EMPTY, Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS));
        Path peaks = dir.resolve(SPECTRA_PEAKS);
        rewriteInPlace(peaks, SignalEntity.SPECTRUM,
                Map.of("chunk.mean_drift_time", new Edit("UO:0000010", v -> v / 1000)));

        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(peaks), SPECTRA_PEAKS, false,
                null)) {
            assertThat(store.plan().layout()).isEqualTo(SignalPlan.Layout.CHUNK);
            assertThat(store.plan().extras().get(MEAN_DRIFT_TIME).values().format())
                    .isEqualTo(BufferFormat.CHUNK_SECONDARY);
            assertClose(store.get(0).extras().get(MEAN_DRIFT_TIME), new double[] {1.5, 2.5}, RELATIVE, "chunk row");
        }
    }

    /**
     * (e) A drift-time array stored only as a coordinate grid: diaPASEF.grid's peaks, whose ion mobility grid is
     * relabelled as a drift-time array in seconds, read 1000 times the grid values that the unchanged member reads
     * as mean inverse reduced ion mobility.
     */
    @Test
    void aDriftTimeGridIsConverted() {
        try (MzPeakSource source = MzPeakSource.open(DIA_GRID)) {
            InputFile original = source.inputFile(SPECTRA_PEAKS);
            Path rewritten = tmp.resolve(SPECTRA_PEAKS);
            rewrite(original, rewritten, SignalEntity.SPECTRUM, Map.of("chunk.mean_inverse_reduced_ion_mobility_grid",
                    new Edit("UO:0000010", MEAN_DRIFT_TIME, null)));

            try (SpectrumArrayStore a = SpectrumArrayStore.load(original, SPECTRA_PEAKS, false, null);
                 SpectrumArrayStore b = SpectrumArrayStore.load(new LocalInputFile(rewritten), SPECTRA_PEAKS, false,
                         null)) {
                assertThat(b.plan().extras().get(MEAN_DRIFT_TIME).values()).as("a grid only").isNull();
                double[] im = a.get(0).extras().get(MEAN_INVERSE_REDUCED_ION_MOBILITY);
                double[] drift = b.get(0).extras().get(MEAN_DRIFT_TIME);
                assertThat(im).hasSizeGreaterThan(100);
                assertThat(drift).hasSameSizeAs(im);
                for (int i = 0; i < im.length; i++) {
                    assertThat(drift[i]).as("point %d", i).isEqualTo(im[i] * 1000);
                }
            }
        }
    }

    /** A grid-only drift-time array in a unit that is no time unit is rejected, naming the grid column. */
    @Test
    void aDriftTimeGridInAnUnknownUnitIsRejected() {
        try (MzPeakSource source = MzPeakSource.open(DIA_GRID)) {
            Path rewritten = tmp.resolve(SPECTRA_PEAKS);
            rewrite(source.inputFile(SPECTRA_PEAKS), rewritten, SignalEntity.SPECTRUM,
                    Map.of("chunk.mean_inverse_reduced_ion_mobility_grid",
                            new Edit("UO:0000032", MEAN_DRIFT_TIME, null)));

            assertThatThrownBy(() -> SpectrumArrayStore.load(new LocalInputFile(rewritten), SPECTRA_PEAKS, false,
                    null))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(SPECTRA_PEAKS + ": drift time array column"
                            + " chunk.mean_inverse_reduced_ion_mobility_grid (MS:1002477) has the unit UO:0000032;"
                            + " supported are millisecond (UO:0000028), second (UO:0000010) and minute (UO:0000031)");
        }
    }

    /**
     * MZ-3 note 2: a second MS:1002477 value column, keyed {@code MS:1002477@<column>}, is converted by its own unit
     * (seconds here) while the first stays in milliseconds; a conversion keyed by array type would mix them up.
     */
    @Test
    void aSecondDriftTimeColumnOfASpectrumIsConvertedByItsOwnUnit() {
        Path dir = tmp.resolve("two-drift.mzpeak");
        Map<String, double[]> arrays = new LinkedHashMap<>();
        arrays.put(MEAN_DRIFT_TIME, new double[] {1.5, 2.5});
        arrays.put("second drift", new double[] {3.5, 4.5});
        Spectrum s = new Spectrum(centroid(), new double[] {100.0, 200.0}, new double[] {10.0, 20.0}, List.of(),
                arrays, Map.of());
        MzPeakWriter.writeDirectory(dir, List.of(s));
        rewriteInPlace(dir.resolve(SPECTRA_PEAKS), SignalEntity.SPECTRUM,
                Map.of("point.second_drift", new Edit("UO:0000010", MEAN_DRIFT_TIME, v -> v / 1000)));

        try (MzPeakReader reader = MzPeakReader.open(dir)) {
            Spectrum read = reader.getSpectrum(0).orElseThrow();
            assertThat(read.arrays().keySet()).containsExactly(MEAN_DRIFT_TIME, MEAN_DRIFT_TIME + "@second_drift");
            assertClose(read.arrays().get(MEAN_DRIFT_TIME), new double[] {1.5, 2.5}, RELATIVE, "first column");
            assertClose(read.arrays().get(MEAN_DRIFT_TIME + "@second_drift"), new double[] {3.5, 4.5}, RELATIVE,
                    "second column");
        }
    }

    /**
     * MZ-3 note 3: {@link ArrayUnits#DRIFT_TIME_ARRAYS} are exactly the keys {@link CvTerms#DEFAULT_ARRAY_UNITS} maps
     * to UO:0000028 millisecond.
     */
    @Test
    void theDriftTimeArraysAreTheKeysWhoseDefaultUnitIsMillisecond() {
        Set<String> milliseconds = new HashSet<>();
        CvTerms.DEFAULT_ARRAY_UNITS.forEach((key, unit) -> {
            if (unit.equals("UO:0000028")) {
                milliseconds.add(key);
            }
        });

        assertThat(ArrayUnits.DRIFT_TIME_ARRAYS).isEqualTo(milliseconds).hasSize(6);
    }

    // ---- drift-time arrays of chromatograms ------------------------------------------------------------------

    /**
     * A chromatogram written with the drift-time array {1.5, 2.5} (MS:1002477), its chromatograms_data column then
     * stored in {@code unit}: the reader returns {1.5, 2.5} within a relative 1e-15, as for spectra. Before, the
     * stored values were returned under the millisecond label of {@code CvTerms.DEFAULT_ARRAY_UNITS}.
     */
    @ParameterizedTest(name = "unit {0}")
    @MethodSource("driftTimeUnits")
    void aChromatogramDriftTimeArrayInAnotherUnitIsReadInMilliseconds(String unit,
                                                                     DoubleUnaryOperator fromMilliseconds) {
        Path dir = tmp.resolve("drift-chromatogram.mzpeak");
        MzPeakWriter.writeDirectory(dir, List.of(), List.of(driftTimeChromatogram()));
        Path data = dir.resolve(CHROMATOGRAMS_DATA);
        SignalPlan written = plan(new LocalInputFile(data), SignalEntity.CHROMATOGRAM);
        assertThat(written.layout()).isEqualTo(SignalPlan.Layout.POINT);
        String column = written.extras().get(MEAN_DRIFT_TIME).values().path();
        rewriteInPlace(data, SignalEntity.CHROMATOGRAM, Map.of(column, new Edit(unit, fromMilliseconds)));
        if ("UO:0000010".equals(unit)) {
            assertThat(cells(data, "point", column.substring("point.".length()))).containsExactly(0.0015, 0.0025);
        }

        try (MzPeakReader reader = MzPeakReader.open(dir)) {
            Chromatogram c = reader.chromatograms().get(0);
            assertThat(c.arrays().keySet()).containsExactly(MEAN_DRIFT_TIME);
            assertClose(c.arrays().get(MEAN_DRIFT_TIME), new double[] {1.5, 2.5}, RELATIVE, "drift time");
            assertThat(c.time()).containsExactly(1.0, 2.0);
            assertThat(c.intensity()).containsExactly(10.0, 20.0);
        }
    }

    /**
     * The chunk layout: current small.chunked's chromatogram {@code ms level} list relabelled as a drift-time array
     * in seconds reads 1000 times the values the unchanged member reads as {@code ms level}, each chunk cell
     * converted.
     */
    @Test
    void aChunkedChromatogramDriftTimeListIsConverted() {
        try (MzPeakSource source = MzPeakSource.open(SMALL_CHUNKED)) {
            InputFile metadata = source.inputFile(CHROMATOGRAMS_METADATA);
            InputFile data = source.inputFile(CHROMATOGRAMS_DATA);
            Path rewritten = tmp.resolve(CHROMATOGRAMS_DATA);
            rewrite(data, rewritten, SignalEntity.CHROMATOGRAM,
                    Map.of("chunk.ms_level", new Edit("UO:0000010", MEAN_DRIFT_TIME, null)));
            assertThat(plan(new LocalInputFile(rewritten), SignalEntity.CHROMATOGRAM).extras().get(MEAN_DRIFT_TIME)
                    .values().format()).isEqualTo(BufferFormat.CHUNK_SECONDARY);

            List<Chromatogram> expected = ChromatogramStore.load(metadata, data).all();
            List<Chromatogram> actual = ChromatogramStore.load(metadata, new LocalInputFile(rewritten)).all();
            assertThat(actual).hasSameSizeAs(expected);
            int cells = 0;
            for (int c = 0; c < expected.size(); c++) {
                double[] msLevel = expected.get(c).arrays().get(MS_LEVEL);
                assertThat(actual.get(c).arrays().keySet()).as("arrays of %s", expected.get(c).id())
                        .isEqualTo(msLevel == null ? Set.of() : Set.of(MEAN_DRIFT_TIME));
                if (msLevel != null) {
                    double[] scaled = new double[msLevel.length];
                    for (int i = 0; i < msLevel.length; i++) {
                        scaled[i] = msLevel[i] * 1000;
                    }
                    assertThat(actual.get(c).arrays().get(MEAN_DRIFT_TIME)).containsExactly(scaled);
                    cells += msLevel.length;
                }
                assertThat(actual.get(c).time()).isEqualTo(expected.get(c).time());
            }
            assertThat(cells).isPositive();
        }
    }

    /**
     * A chromatogram drift-time array in a unit that is no time unit is dropped from the member with the WARNING of
     * an unreadable extra, naming the member, the column and the unit; extras never throw, so time and intensity are
     * read.
     */
    @Test
    void aChromatogramDriftTimeArrayInAnUnknownUnitIsDroppedWithAWarning() {
        Path dir = tmp.resolve("drift-chromatogram.mzpeak");
        MzPeakWriter.writeDirectory(dir, List.of(), List.of(driftTimeChromatogram()));
        Path data = dir.resolve(CHROMATOGRAMS_DATA);
        String column = plan(new LocalInputFile(data), SignalEntity.CHROMATOGRAM).extras().get(MEAN_DRIFT_TIME)
                .values().path();
        rewriteInPlace(data, SignalEntity.CHROMATOGRAM, Map.of(column, new Edit("UO:0000033", v -> v)));

        try (MzPeakReader reader = MzPeakReader.open(dir)) {
            List<Chromatogram> read = new ArrayList<>();
            List<LogRecord> records = ArrayIndexTest.captureLog(ChromatogramDataDecoder.class,
                    () -> read.addAll(reader.chromatograms()));

            assertThat(read).hasSize(1);
            assertThat(read.get(0).arrays()).isEmpty();
            assertThat(read.get(0).time()).containsExactly(1.0, 2.0);
            assertThat(read.get(0).intensity()).containsExactly(10.0, 20.0);
            assertThat(records).filteredOn(r -> r.getLevel() == Level.WARNING).extracting(LogRecord::getMessage)
                    .containsExactly("chromatogram extra array '" + MEAN_DRIFT_TIME + "' (" + column + ") cannot be"
                            + " read as numbers (" + CHROMATOGRAMS_DATA + ": drift time array column " + column
                            + " (" + MEAN_DRIFT_TIME + ") has the unit UO:0000033; supported are millisecond"
                            + " (UO:0000028), second (UO:0000010) and minute (UO:0000031)); the array is dropped");
        }
    }

    /** An array that is not a drift-time array keeps its stored values whatever its unit. */
    @Test
    void anotherArrayKeepsItsStoredValues() {
        Path dir = tmp.resolve("other.mzpeak");
        Spectrum s = new Spectrum(centroid(), new double[] {100.0, 200.0}, new double[] {10.0, 20.0}, List.of(),
                Map.of("MS:1002893", new double[] {0.5, 0.75}), Map.of());
        MzPeakWriter.writeDirectory(dir, List.of(s));
        rewriteInPlace(dir.resolve(SPECTRA_PEAKS), SignalEntity.SPECTRUM,
                Map.of("point.ion_mobility", new Edit("UO:0000010", v -> v)));

        try (MzPeakReader reader = MzPeakReader.open(dir)) {
            assertThat(reader.getSpectrum(0).orElseThrow().arrays().get("MS:1002893")).containsExactly(0.5, 0.75);
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /**
     * How {@link #rewrite} changes one column: its array-index entry gets {@code unit} (null for none) and, unless
     * null, {@code arrayType}; its values go through {@code values}, unless null.
     */
    private record Edit(String unit, String arrayType, DoubleUnaryOperator values) {

        Edit(String unit, DoubleUnaryOperator values) {
            this(unit, null, values);
        }
    }

    /** A CENTROID MS1 description of index 0 without scans or precursors. */
    private static SpectrumDescription centroid() {
        return new SpectrumDescription(0, "scan=1", 1, 1.0, null, SignalContinuity.CENTROID, 0, 0, List.of(),
                List.of(), List.of());
    }

    /** A CENTROID spectrum of two points with the drift-time array {1.5, 2.5}. */
    private static Spectrum driftTimeSpectrum() {
        return new Spectrum(centroid(), new double[] {100.0, 200.0}, new double[] {10.0, 20.0}, List.of(),
                Map.of(MEAN_DRIFT_TIME, new double[] {1.5, 2.5}), Map.of());
    }

    /** A chromatogram of two points, times 1 and 2 minutes, with the drift-time array {1.5, 2.5}. */
    private static Chromatogram driftTimeChromatogram() {
        return new Chromatogram(0, "TIC", TIC, new double[] {1.0, 2.0}, new double[] {10.0, 20.0}, "MS:1000131",
                Map.of(MEAN_DRIFT_TIME, new double[] {1.5, 2.5}));
    }

    /** Copies current small.unpacked into the temporary directory. */
    private Path copySmallUnpacked() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("small.unpacked.mzpeak"));
        try (Stream<Path> files = Files.list(SMALL_UNPACKED)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Files.copy(f, dir.resolve(f.getFileName().toString()));
            }
        }
        return dir;
    }

    /** The signal plan of {@code member}, built as the reader builds it. */
    private static SignalPlan plan(InputFile member, SignalEntity entity) {
        ParquetFooter footer = ParquetFooter.read(member);
        return SignalPlan.of(ArrayIndex.of(footer.keyValue(), footer.schema(), entity), footer.schema(), entity);
    }

    /** The cells of the column {@code field} in the struct {@code prefix} of every row of {@code member}. */
    private static List<Double> cells(Path member, String prefix, String field) {
        List<Double> out = new ArrayList<>();
        ParquetGroups.forEach(member, row -> out.add(ParquetGroups.optDouble(row.getGroup(prefix, 0), field)));
        return out;
    }

    /** Asserts that {@code actual[i]} differs from {@code expected[i]} by at most {@code relative * |expected[i]|}. */
    private static void assertClose(double[] actual, double[] expected, double relative, String what) {
        assertThat(actual).as(what).isNotNull().hasSameSizeAs(expected);
        assertThat(expected).as(what).isNotEmpty();
        for (int i = 0; i < expected.length; i++) {
            assertThat(Math.abs(actual[i] - expected[i])).as("%s[%d]: %s vs %s", what, i, actual[i], expected[i])
                    .isLessThanOrEqualTo(relative * Math.abs(expected[i]));
        }
    }

    /** {@link #rewrite} of the member file {@code member} in place. */
    private static void rewriteInPlace(Path member, SignalEntity entity, Map<String, Edit> edits) {
        Path rewritten = member.resolveSibling(member.getFileName() + ".rewritten");
        rewrite(new LocalInputFile(member), rewritten, entity, edits);
        try {
            Files.move(rewritten, member, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes {@code in} to {@code out} with parquet-java's Group API and the same schema: every cell of an edited
     * column (a column path such as {@code point.time}; the items of a list or the fields of a struct below it
     * included) through its {@link Edit#values()}, and the entry of each edited path in the
     * {@code <entity>_array_index} footer changed as its {@link Edit} says. Every other column and footer key is
     * kept (parquet-java writes its own {@code writer.model.name}).
     */
    private static void rewrite(InputFile in, Path out, SignalEntity entity, Map<String, Edit> edits) {
        ParquetFooter footer = ParquetFooter.read(in);
        MessageType schema = footer.schema();
        Map<String, String> keyValue = new LinkedHashMap<>(footer.keyValue());
        keyValue.remove("writer.model.name");
        keyValue.put(entity.arrayIndexKey(), editIndex(keyValue.get(entity.arrayIndexKey()), edits));
        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(in, rows::add);
        int[] edited = {0};
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(out))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withExtraMetaData(keyValue)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (Group row : rows) {
                Group copy = new SimpleGroup(schema);
                copy(row, copy, "", edits, null, edited);
                writer.write(copy);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        boolean editsValues = edits.values().stream().anyMatch(e -> e.values() != null);
        assertThat(edited[0] > 0).as("edited cells of %s", edits.keySet()).isEqualTo(editsValues);
    }

    /** The array index JSON {@code json} with the entries of the edited paths changed. */
    private static String editIndex(String json, Map<String, Edit> edits) {
        try {
            ObjectNode index = (ObjectNode) MAPPER.readTree(json);
            int changed = 0;
            for (JsonNode entry : index.path("entries")) {
                Edit edit = edits.get(entry.path("path").asText());
                if (edit != null) {
                    ((ObjectNode) entry).put("unit", edit.unit());
                    if (edit.arrayType() != null) {
                        ((ObjectNode) entry).put("array_type", edit.arrayType());
                    }
                    changed++;
                }
            }
            assertThat(changed).as("array index entries of %s", edits.keySet()).isEqualTo(edits.size());
            return MAPPER.writeValueAsString(index);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Copies every value of {@code src} into {@code dst}, recursively; a DOUBLE cell at or below an edited path goes
     * through its edit (or {@code edit}, the one of an enclosing path), counted in {@code edited[0]}.
     */
    private static void copy(Group src, Group dst, String path, Map<String, Edit> edits, DoubleUnaryOperator edit,
                             int[] edited) {
        GroupType type = src.getType();
        for (int f = 0; f < type.getFieldCount(); f++) {
            Type field = type.getType(f);
            String name = field.getName();
            String fieldPath = path.isEmpty() ? name : path + "." + name;
            Edit own = edits.get(fieldPath);
            DoubleUnaryOperator fieldEdit = own != null ? own.values() : edit;
            for (int i = 0; i < src.getFieldRepetitionCount(f); i++) {
                if (!field.isPrimitive()) {
                    copy(src.getGroup(f, i), dst.addGroup(name), fieldPath, edits, fieldEdit, edited);
                    continue;
                }
                PrimitiveTypeName primitive = field.asPrimitiveType().getPrimitiveTypeName();
                switch (primitive) {
                    case INT32 -> dst.add(name, src.getInteger(f, i));
                    case INT64 -> dst.add(name, src.getLong(f, i));
                    case FLOAT -> dst.add(name, src.getFloat(f, i));
                    case DOUBLE -> {
                        double v = src.getDouble(f, i);
                        if (fieldEdit != null) {
                            v = fieldEdit.applyAsDouble(v);
                            edited[0]++;
                        }
                        dst.add(name, v);
                    }
                    case BOOLEAN -> dst.add(name, src.getBoolean(f, i));
                    case BINARY, FIXED_LEN_BYTE_ARRAY -> dst.add(name, src.getBinary(f, i));
                    default -> throw new AssertionError("unexpected " + primitive + " field " + fieldPath);
                }
            }
        }
    }
}
