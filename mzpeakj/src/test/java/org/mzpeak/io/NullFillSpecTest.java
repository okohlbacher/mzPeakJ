package org.mzpeak.io;

import org.apache.parquet.column.ParquetProperties;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.parquet.PureJavaCodecFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The null fill follows the spec's rule (signal-data.md, Decoding null pairs; X-8, R2): the flank of a stored segment
 * of two or more values steps by the second median of the segment's spacing, the flank of a single stored value by
 * the stored {@code mz_delta_model}; an unpaired null is valid only as the first or last value of the array (of each
 * chunk row in the chunk layout, which is filled row by row), any other throws; a run of three or more nulls is filled
 * with one DEBUG message per spectrum. Members are written in the test; small.chunked is compared against the spec's
 * own Python code ({@code src/test/resources/mzpeak/specfill}) and the fixtures against the HEAD Python reader's
 * golden output.
 */
class NullFillSpecTest {

    private static final double N = Double.NaN;
    private static final String MEMBER = "spectra_data.parquet";
    private static final String INDEX_KEY = SignalEntity.SPECTRUM.arrayIndexKey();
    private static final Path SPECFILL = Fixtures.CURRENT.resolve("specfill").resolve("small.chunked.specfill.tsv");

    private static final MessageType POINT = MessageTypeParser.parseMessageType("""
            message m {
              optional group point {
                optional int64 spectrum_index (INTEGER(64,false));
                optional double mz;
                optional float intensity;
              }
            }""");

    private static final String POINT_INDEX = index("point",
            entry("point.spectrum_index", "index", "MS:1003870", 0),
            entry("point.mz", "point", "MS:1000514", 1),
            entry("point.intensity", "point", "MS:1000515", null));

    private static final MessageType CHUNK = MessageTypeParser.parseMessageType("""
            message m {
              optional group chunk {
                optional int64 spectrum_index (INTEGER(64,false));
                optional double mz_chunk_start;
                optional group mz_chunk_values (LIST) { repeated group list { optional double element; } }
                optional binary chunk_encoding (STRING);
                optional group intensity (LIST) { repeated group list { optional float element; } }
              }
            }""");

    private static final String CHUNK_INDEX = index("chunk",
            entry("chunk.spectrum_index", "index", "MS:1003870", 0),
            entry("chunk.mz_chunk_start", "chunk_start", "MS:1000514", 1),
            entry("chunk.mz_chunk_values", "chunk_values", "MS:1000514", 1),
            entry("chunk.chunk_encoding", "chunk_encoding", "MS:1000514", 1),
            entry("chunk.intensity", "chunk_secondary", "MS:1000515", null));

    @TempDir
    Path tmp;

    private int written;

    // ---- the design's cases ---------------------------------------------------------------------------------------

    /** With local spacing 1 and the model 2.0, the pair between two runs of stored values steps by the spacing. */
    @Test
    void anInteriorPairStepsByTheLocalSecondMedianNotByTheModel() {
        Path file = writePoint(0, 100, 101, 102, N, N, 105, 106, 107);
        assertThat(read(file, Map.of(0L, new double[] {2.0}), 0))
                .containsExactly(100, 101, 102, 103, 104, 105, 106, 107);
    }

    @Test
    void anInteriorUnpairedNullThrowsNamingTheSpectrumAndThePosition() {
        Path file = writePoint(3, 100, N, 102);
        assertThatThrownBy(() -> read(file, Map.of(3L, new double[] {2.0}), 3))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining(MEMBER)
                .hasMessageContaining("spectrum_index 3")
                .hasMessageContaining("unpaired null m/z at position 1")
                .hasMessageNotContaining("chunk");
    }

    /** A pair split by a chunk row boundary is an unpaired null at the end of one row and the start of the next. */
    @Test
    void aPairSplitAcrossChunkRowsIsFilledInEachRow() {
        Path file = writeChunks(
                chunkRow(0, 100, new double[] {1, 1, N}, 4),        // 100, 101, 102, null
                chunkRow(0, 104.5, new double[] {N, 105, 1, 1}, 4)); // null, 105, 106, 107
        assertThat(read(file, Map.of(0L, new double[] {2.0}), 0))
                .containsExactly(100, 101, 102, 103, 104, 105, 106, 107);
    }

    @Test
    void anInteriorUnpairedNullOfAChunkRowThrowsNamingTheChunk() {
        Path file = writeChunks(
                chunkRow(5, 100, new double[] {1, 1}, 3),
                chunkRow(5, 200, new double[] {1, N, 203, 1}, 5));   // 200, 201, null, 203, 204
        assertThatThrownBy(() -> read(file, Map.of(), 5))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining(MEMBER)
                .hasMessageContaining("spectrum_index 5")
                .hasMessageContaining("chunk 1")
                .hasMessageContaining("unpaired null m/z at position 2");
    }

    @Test
    void aRunOfThreeNullsIsFilledWithOneDebugMessagePerSpectrum() {
        Path file = writePoint(0, 100, 101, N, N, N, 105, 106, N, N, N, N, 111, 112);
        List<double[]> read = new ArrayList<>();
        List<LogRecord> records = ArrayIndexTest.captureLog(NullFill.class,
                () -> read.add(read(file, Map.of(0L, new double[] {2.0}), 0)));
        assertThat(read.get(0)).containsExactly(100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112);
        List<LogRecord> debug = records.stream().filter(r -> r.getLevel() == Level.FINE).toList();
        assertThat(debug).singleElement().extracting(LogRecord::getMessage).asString()
                .contains(MEMBER, "spectrum_index 0", "2 runs of null m/z", "position 2");
    }

    // ---- the rule's other cases -----------------------------------------------------------------------------------

    /** A single stored value between nulls steps by the model on both sides, as do a leading and a trailing one. */
    @Test
    void aSingleStoredValueStepsByTheModel() {
        Path file = writePoint(0, N, 100, N, N, 110, N, N, 120, 121, 122, N);
        // delta(mz) = 0.5 + mz / 128: 1.28125 at 100, 1.359375 at 110
        double[] mz = read(file, Map.of(0L, new double[] {0.5, 0.0078125}), 0);
        assertThat(mz).containsExactly(98.71875, 100, 101.28125, 108.640625, 110, 111.359375, 119, 120, 121, 122, 123);
    }

    /** Without a model a single stored value steps by the second median of the spectrum's stored spacing. */
    @Test
    void withoutAModelASingleStoredValueStepsByTheSpectrumsSecondMedian() {
        // stored spacing of the spectrum: 1, 3 (row 0) and 2, 2, 7 (row 1): median 2, below it 1, 2, 2 -> 2
        Path file = writeChunks(
                chunkRow(0, 100, new double[] {1, 3, N, N, 300, N}, 7),   // 100, 101, 104, null, null, 300, null
                chunkRow(0, 400, new double[] {2, 2, 7}, 4));              // 400, 402, 404, 411
        assertThat(read(file, Map.of(), 0)).containsExactly(100, 101, 104, 105, 298, 300, 302, 400, 402, 404, 411);
    }

    @Test
    void withoutAModelOrAnySpacingASingleStoredValueThrows() {
        Path file = writePoint(4, N, 100, N);
        assertThatThrownBy(() -> read(file, Map.of(4L, new double[0]), 4))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining(MEMBER)
                .hasMessageContaining("spectrum_index 4")
                .hasMessageContaining("single stored m/z at position 1")
                .hasMessageContaining("no mz_delta_model");
    }

    /** A leading or trailing run of more than one null steps on outwards, an irregular run like a run of three. */
    @Test
    void longerRunsAtTheEndsStepOutwards() {
        Path file = writePoint(0, N, N, 100, 102, 104, N, N, N);
        List<double[]> read = new ArrayList<>();
        List<LogRecord> records = ArrayIndexTest.captureLog(NullFill.class,
                () -> read.add(read(file, Map.of(), 0)));
        assertThat(read.get(0)).containsExactly(96, 98, 100, 102, 104, 106, 108, 110);
        assertThat(records.stream().filter(r -> r.getLevel() == Level.FINE)).singleElement()
                .extracting(LogRecord::getMessage).asString().contains("2 runs of null m/z", "position 0");
    }

    @Test
    void theDebugMessageOfAChunkedSpectrumNamesTheChunk() {
        Path file = writeChunks(
                chunkRow(1, 100, new double[] {1}, 2),
                chunkRow(1, 200, new double[] {1, N, N, N, 205, 1}, 7));   // 200, 201, null x3, 205, 206
        List<double[]> read = new ArrayList<>();
        List<LogRecord> records = ArrayIndexTest.captureLog(NullFill.class,
                () -> read.add(read(file, Map.of(), 1)));
        assertThat(read.get(0)).containsExactly(100, 101, 200, 201, 202, 203, 204, 205, 206);
        assertThat(records.stream().filter(r -> r.getLevel() == Level.FINE)).singleElement()
                .extracting(LogRecord::getMessage).asString()
                .contains("spectrum_index 1", "1 run of null m/z", "position 2 of chunk 1");
    }

    /** An array without a stored value is left as it is, as is one without a null. */
    @Test
    void anArrayWithoutStoredValuesIsLeftUnfilled() {
        Path file = writeChunks(
                chunkRow(0, 100, new double[] {1}, 2),
                chunkRow(0, 0, new double[] {N, N}, 2));
        assertThat(read(file, Map.of(), 0)).containsExactly(100, 101, N, N);
        assertThat(read(writePoint(1, N, N), Map.of(), 1)).containsExactly(N, N);
        assertThat(read(writePoint(2, 5, 6), Map.of(), 2)).containsExactly(5, 6);
    }

    /** Without reconstruction the nulls are dropped, whatever their shape. */
    @Test
    void withoutReconstructionNullsAreDroppedWithoutAnError() {
        Path file = writePoint(3, 100, N, 102);
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), MEMBER, false, Map.of())) {
            assertThat(store.get(3).mz()).containsExactly(100, 102);
        }
    }

    /**
     * A spectrum whose rows continue into the next row group is decoded only in part with the spectra before it:
     * spectrum 1 starts as [null, 200] at the end of row group 0, which alone cannot be filled without a model. That
     * must not fail spectrum 0, and spectrum 1 read with both row groups is filled. A spectrum that cannot be filled
     * still fails when it is requested, and only then: spectrum 2 is decoded with spectrum 1.
     */
    @Test
    void aSpectrumContinuingIntoTheNextRowGroupDoesNotFailItsNeighbour() {
        Path file = write(POINT, POINT_INDEX, 5,
                pointRow(0, 100), pointRow(0, 101), pointRow(0, 102), pointRow(1, N), pointRow(1, 200),
                pointRow(1, 201), pointRow(1, 202), pointRow(2, N), pointRow(2, 300));
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), MEMBER, true, Map.of())) {
            assertThat(store.get(0).mz()).containsExactly(100, 101, 102);
            assertThat(store.get(1).mz()).containsExactly(199, 200, 201, 202);
            assertThatThrownBy(() -> store.get(2))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageContaining("spectrum_index 2")
                    .hasMessageContaining("single stored m/z at position 1");
            assertThat(store.get(0).mz()).containsExactly(100, 101, 102);
        }
    }

    /** A spectrum that cannot be filled in one run of rows and appears in another is an unsorted member. */
    @Test
    void anUnfillableSpectrumInTwoRunsIsUnsorted() {
        Path file = write(POINT, POINT_INDEX, 100,
                pointRow(0, 100), pointRow(0, N), pointRow(0, 102), pointRow(1, 5), pointRow(0, 7));
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), MEMBER, true, Map.of())) {
            assertThatThrownBy(() -> store.get(1))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageContaining("not sorted by spectrum_index (index 0");
        }
    }

    // ---- against the Python references ----------------------------------------------------------------------------

    /**
     * small.chunked spectra 0 and 41 against the spec's own {@code null_delta_decode} and {@code fill_nulls}, applied
     * per chunk row ({@code specfill/README.md}).
     */
    @ParameterizedTest(name = "spectrum {0}")
    @ValueSource(longs = {0, 41})
    void smallChunkedMatchesTheSpecsPythonFill(long index) throws IOException {
        Map<Integer, Double> expected = new TreeMap<>();
        for (String line : Files.readAllLines(SPECFILL, StandardCharsets.UTF_8)) {
            String[] cells = line.split("\t");
            if (Long.parseLong(cells[0]) == index) {
                expected.put(Integer.parseInt(cells[1]), Double.parseDouble(cells[2]));
            }
        }
        Path fixture = Fixtures.current("small.chunked.mzpeak");
        double max = 0;
        try (MzPeakReader filled = MzPeakReader.open(fixture, true);
             MzPeakReader stored = MzPeakReader.open(fixture, false)) {
            double[] mz = filled.getSpectrum(index).orElseThrow().mz();
            assertThat(expected).hasSize(mz.length - stored.getSpectrum(index).orElseThrow().mz().length);
            for (Map.Entry<Integer, Double> e : expected.entrySet()) {
                max = Math.max(max, Math.abs(mz[e.getKey()] - e.getValue()));
            }
        }
        assertThat(expected).hasSizeGreaterThan(2000);
        assertThat(max).as("max |m/z - spec fill_nulls|").isLessThan(1e-9);
    }

    /**
     * The spectra the HEAD Python reader's golden output stores in full: within the golden tolerance, and every
     * filled m/z within 1e-9 of the golden one.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"small.mzpeak", "small.chunked.mzpeak", "small.unpacked.mzpeak",
            "Example_Processed.img.mzpeak"})
    void filledPointsMatchTheGolden(String fixture) {
        Golden golden = Golden.load(fixture);
        int filledPoints = 0;
        double max = 0;
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            for (Golden.FullArray full : golden.fullArrays()) {
                if (full.kind() != Golden.Kind.S) {
                    continue;
                }
                double[] mz = reader.getSpectrum(Long.parseLong(full.key())).orElseThrow().mz();
                assertThat(Golden.Comparator.compareFull(full, mz, full.y(), Golden.Tolerance.DEFAULT))
                        .as("spectrum %s", full.key()).isEmpty();
                for (int k = 0; k < mz.length; k++) {
                    if (full.filled()[k]) {
                        filledPoints++;
                        max = Math.max(max, Math.abs(mz[k] - full.x()[k]));
                    }
                }
            }
        }
        assertThat(filledPoints).isPositive();
        assertThat(max).as("max |filled m/z - golden|").isLessThan(1e-9);
    }

    // ---- members --------------------------------------------------------------------------------------------------

    /** The m/z of {@code index} read from {@code file} with reconstruction and {@code models}. */
    private static double[] read(Path file, Map<Long, double[]> models, long index) {
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), MEMBER, true, models)) {
            return store.get(index).mz();
        }
    }

    /** A point member of spectrum {@code index}: NaN m/z as a null cell with a null intensity, others intensity 1. */
    private Path writePoint(long index, double... mz) {
        Group[] rows = new Group[mz.length];
        for (int i = 0; i < mz.length; i++) {
            rows[i] = pointRow(index, mz[i]);
        }
        return write(POINT, POINT_INDEX, rows);
    }

    /** A point row of spectrum {@code index}: a NaN m/z as a null cell with a null intensity, else intensity 1. */
    private static Group pointRow(long index, double mz) {
        Group record = new SimpleGroupFactory(POINT).newGroup();
        Group point = record.addGroup("point");
        point.add("spectrum_index", index);
        if (!Double.isNaN(mz)) {
            point.add("mz", mz);
            point.add("intensity", 1f);
        }
        return record;
    }

    private Path writeChunks(Group... rows) {
        return write(CHUNK, CHUNK_INDEX, rows);
    }

    /**
     * A delta chunk row: {@code values} with NaN as a null element, and {@code intensities} intensities (one more
     * than the values when the chunk start is a point).
     */
    private static Group chunkRow(long index, double start, double[] values, int intensities) {
        Group record = new SimpleGroupFactory(CHUNK).newGroup();
        Group chunk = record.addGroup("chunk");
        chunk.add("spectrum_index", index);
        chunk.add("mz_chunk_start", start);
        Group list = chunk.addGroup("mz_chunk_values");
        for (double v : values) {
            Group element = list.addGroup("list");
            if (!Double.isNaN(v)) {
                element.add("element", v);
            }
        }
        chunk.add("chunk_encoding", "MS:1003089");
        Group intensity = chunk.addGroup("intensity");
        for (int i = 0; i < intensities; i++) {
            intensity.addGroup("list").add("element", 1f);
        }
        return record;
    }

    private static String entry(String path, String format, String arrayType, Integer rank) {
        return "{\"path\": \"" + path + "\", \"buffer_format\": \"" + format + "\", \"array_type\": \"" + arrayType
                + "\", \"sorting_rank\": " + rank + ", \"transform\": null, \"buffer_priority\": null,"
                + " \"context\": \"spectrum\"}";
    }

    private static String index(String prefix, String... entries) {
        return "{\"prefix\": \"" + prefix + "\", \"entries\": [" + String.join(", ", entries) + "]}";
    }

    private Path write(MessageType schema, String json, Group... rows) {
        return write(schema, json, ParquetProperties.DEFAULT_ROW_GROUP_ROW_COUNT_LIMIT, rows);
    }

    /** Writes {@code rows} with {@code json} as the spectrum array index, at most {@code rowGroupRows} per group. */
    private Path write(MessageType schema, String json, int rowGroupRows, Group... rows) {
        Path file = tmp.resolve("member-" + (written++) + ".parquet");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withRowGroupRowCountLimit(rowGroupRows)
                .withExtraMetaData(Map.of(INDEX_KEY, json))
                .withCodecFactory(new PureJavaCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
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
