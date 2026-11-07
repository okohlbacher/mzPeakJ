package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterizes how {@link SpectrumArrayStore} resolves the rows of a spectrum signal member, for the paths that
 * {@link SignalSnapshotTest}, {@link ChunkCodecsCharacterizationTest} and {@link UnsupportedEncodingTest} leave open:
 * the row groups the {@code spectrum_index} statistics select (including {@link SpectrumArrayStore#contains}, a
 * spectrum spanning row groups, and members without usable statistics), the placeholders of null point cells, the
 * Numpress intensity choice per chunk row, two chunk-row edge cases, and the messages of the structural errors.
 *
 * <p>Each case writes constructed rows with parquet-java into a {@link TempDir} and reads them back through
 * {@link SpectrumArrayStore#load} with an empty delta-model map. Point values are the first peaks of spectra 2, 3, 4
 * and 6 of the current small.mzpeak peaks member; chunk values come from row 0 of the current small.numpress data
 * member (spectrum 0: Numpress linear m/z and SLOF intensity, decoded here for the plain-list cases). Members are found
 * by manifest role. The constructed files carry no array index footer.
 */
class SpectrumArrayStoreCharacterizationTest {

    @TempDir
    Path tmp;

    private int written;

    // ---- row-group statistics -----------------------------------------------------------------------------------

    /** Spectra 2, 3, 4 and 6 with 3, 3, 1 and 2 points: two rows per row group gives [2,2] [2,3] [3,3] [4,6] [6,6]. */
    private static final Map<Long, Integer> SPANNING = orderedCounts(2, 3, 3, 3, 4, 1, 6, 2);

    @Test
    void theIndexStatisticsSelectEveryRowGroupOfASpectrum() {
        Path file = writePoints(POINT_SCHEMA, Peaks.rows(SPANNING), 2, true);
        assertThat(rowGroups(file)).isEqualTo(5);
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), false, Map.of())) {
            assertThat(store.contains(1)).isFalse();
            assertThat(store.contains(2)).isTrue();
            assertThat(store.contains(5)).as("inside the [4,6] row group").isTrue();
            assertThat(store.contains(7)).isFalse();

            // spectrum 4 alone decodes the [4,6] row group, which holds the first point of spectrum 6 only
            assertPeaks(store.get(4), 4);
            // spectrum 6 then reads [4,6] and [6,6]
            assertPeaks(store.get(6), 6);
            // spectrum 3 spans [2,3] and [3,3], spectrum 2 spans [2,2] and [2,3]
            assertPeaks(store.get(3), 3);
            assertPeaks(store.get(2), 2);
            assertThat(store.get(5)).as("no rows inside a covered range").isNull();
            assertThat(store.get(1)).isNull();
            assertThat(store.get(7)).isNull();
        }
    }

    @Test
    void withoutStatisticsEveryRowGroupIsDecoded() {
        Path file = writePoints(POINT_SCHEMA, Peaks.rows(SPANNING), 2, false);
        assertThat(rowGroups(file)).isEqualTo(5);
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), false, Map.of())) {
            assertThat(store.contains(1)).isTrue();
            assertThat(store.contains(99)).isTrue();
            assertPeaks(store.get(3), 3);
            assertPeaks(store.get(6), 6);
            assertThat(store.get(99)).isNull();
        }
    }

    @Test
    void anInt32IndexColumnHasNoUsableStatistics() {
        // Only INT64 (Long) statistics bound a row group; INT32 ones leave every row group in range.
        Path file = writePoints(INT32_INDEX_SCHEMA, Peaks.rows(SPANNING), 2, true);
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), false, Map.of())) {
            assertThat(store.contains(99)).isTrue();
            assertPeaks(store.get(3), 3);
            assertPeaks(store.get(6), 6);
            assertThat(store.get(5)).isNull();
        }
    }

    // ---- point rows ---------------------------------------------------------------------------------------------

    @Test
    void nullPointCellsBecomeNanMzAndZeroIntensity() {
        double[][] p = Peaks.points(2);
        List<Group> rows = List.of(
                point(POINT_SCHEMA, 2L, p[0][0], p[0][1]),
                point(POINT_SCHEMA, 2L, null, null),
                point(POINT_SCHEMA, 2L, p[2][0], null),
                point(POINT_SCHEMA, 2L, p[3][0], p[3][1]));
        Path file = writePoints(POINT_SCHEMA, rows, 100, true);

        SpectrumArrayStore.Arrays dropped = read(file, 2, false);
        assertExact("dropped m/z", new double[] {p[0][0], p[2][0], p[3][0]}, dropped.mz());
        assertExact("dropped intensity", new double[] {p[0][1], 0.0, p[3][1]}, dropped.intensity());

        SpectrumArrayStore.Arrays filled = read(file, 2, true);
        double between = p[0][0] + (p[2][0] - p[0][0]) * 1 / 2; // linear interpolation of the single gap
        assertExact("filled m/z", new double[] {p[0][0], between, p[2][0], p[3][0]}, filled.mz());
        assertExact("filled intensity", new double[] {p[0][1], 0.0, 0.0, p[3][1]}, filled.intensity());
    }

    @Test
    void aPointMemberWithoutAnIntensityColumnReadsZeroIntensities() {
        double[][] p = Peaks.points(2);
        List<Group> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Group record = new SimpleGroupFactory(MZ_ONLY_SCHEMA).newGroup();
            Group point = record.addGroup("point");
            point.add("spectrum_index", 2L);
            point.add("mz", p[i][0]);
            rows.add(record);
        }
        SpectrumArrayStore.Arrays arrays = read(writePoints(MZ_ONLY_SCHEMA, rows, 100, true), 2, false);
        assertExact("m/z", new double[] {p[0][0], p[1][0], p[2][0]}, arrays.mz());
        assertExact("intensity", new double[] {0.0, 0.0, 0.0}, arrays.intensity());
    }

    // ---- structural errors --------------------------------------------------------------------------------------

    @Test
    void aRowWithoutTheIndexFails() {
        double[][] p = Peaks.points(2);
        Path file = writePoints(POINT_SCHEMA, List.of(point(POINT_SCHEMA, null, p[0][0], p[0][1])), 100, true);
        assertThatThrownBy(() -> read(file, 0, false))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("signal row missing spectrum_index");
    }

    @Test
    void anIndexWithTheUint64HighBitSetFails() {
        double[][] p = Peaks.points(2);
        Path file = writePoints(POINT_SCHEMA, List.of(point(POINT_SCHEMA, -1L, p[0][0], p[0][1])), 100, true);
        assertThatThrownBy(() -> read(file, -1, false))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("spectrum_index exceeds supported range (uint64 high bit set): -1");
    }

    @Test
    void aRowWithoutThePointStructFails() {
        Path file = writePoints(POINT_SCHEMA, List.of(new SimpleGroupFactory(POINT_SCHEMA).newGroup()), 100, true);
        assertThatThrownBy(() -> read(file, 0, false))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("spectrum signal row has neither 'point' nor 'chunk' struct");
    }

    @Test
    void aSpectrumInTwoNonContiguousRunsFails() {
        double[][] p = Peaks.points(2);
        List<Group> rows = List.of(
                point(POINT_SCHEMA, 2L, p[0][0], p[0][1]),
                point(POINT_SCHEMA, 3L, p[1][0], p[1][1]),
                point(POINT_SCHEMA, 2L, p[2][0], p[2][1]));
        Path file = writePoints(POINT_SCHEMA, rows, 100, true);
        assertThatThrownBy(() -> read(file, 2, false))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("signal file is not sorted by spectrum_index (index 2 appears in multiple non-contiguous"
                        + " runs)");
    }

    // ---- chunk rows ---------------------------------------------------------------------------------------------

    @Test
    void aDeltaChunkWithoutChunkStartStepsFromZero() {
        Row0 r = Row0.INSTANCE;
        ChunkRow row = new ChunkRow();
        row.encoding = "MS:1003089";
        row.values = new double[] {r.mz[1] - r.mz[0], r.mz[2] - r.mz[1]};
        row.intensity = new double[] {r.intensity[0], r.intensity[1], r.intensity[2]};
        SpectrumArrayStore.Arrays arrays = read(writeChunk(row), 0, false);
        double first = 0.0 + row.values[0];
        assertExact("m/z", new double[] {0.0, first, first + row.values[1]}, arrays.mz());
        assertExact("intensity", row.intensity, arrays.intensity());
    }

    @Test
    void aDeltaChunkWithMismatchedLengthsFails() {
        Row0 r = Row0.INSTANCE;
        ChunkRow row = new ChunkRow();
        row.start = r.mz[0];
        row.encoding = "MS:1003089";
        row.values = new double[] {r.mz[1] - r.mz[0], r.mz[2] - r.mz[1], r.mz[3] - r.mz[2]};
        row.intensity = java.util.Arrays.copyOf(r.intensity, 5);
        Path file = writeChunk(row);
        assertThatThrownBy(() -> read(file, 0, false))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("chunk intensity length (5) must equal m/z values (3) or values+1, for spectrum_index 0");
    }

    @Test
    void numpressMzPairsWithAPlainIntensityListKeepingItsNulls() {
        // Mixed format: the plain list is taken as stored, so a null element stays NaN (not 0 as in delta chunks).
        Row0 r = Row0.INSTANCE;
        ChunkRow row = r.numpressMz();
        row.intensity = r.intensity.clone();
        row.intensity[3] = Double.NaN;
        SpectrumArrayStore.Arrays arrays = read(writeChunk(row), 0, false);
        assertExact("m/z", r.mz, arrays.mz());
        assertExact("intensity", row.intensity, arrays.intensity());
        assertThat(arrays.intensity()[3]).isNaN();
    }

    @Test
    void slofIntensityWinsOverPicAndPlainIntensity() {
        Row0 r = Row0.INSTANCE;
        ChunkRow row = r.numpressMz();
        row.slof = r.slof;
        row.pic = r.pic();
        row.intensity = new double[r.mz.length];
        SpectrumArrayStore.Arrays arrays = read(writeChunk(row), 0, false);
        assertExact("m/z", r.mz, arrays.mz());
        assertExact("intensity", r.intensity, arrays.intensity());
    }

    @Test
    void picIntensityWinsOverPlainIntensity() {
        Row0 r = Row0.INSTANCE;
        ChunkRow row = r.numpressMz();
        row.pic = r.pic();
        row.intensity = new double[r.mz.length];
        SpectrumArrayStore.Arrays arrays = read(writeChunk(row), 0, false);
        double[] pic = MSNumpress.decode(MSNumpress.ACC_NUMPRESS_PIC, row.pic, row.pic.length);
        assertThat(pic).isNotEqualTo(r.intensity);
        assertExact("m/z", r.mz, arrays.mz());
        assertExact("intensity", pic, arrays.intensity());
    }

    @Test
    void numpressMzWithoutAnyIntensityGivesAnEmptySpectrum() {
        SpectrumArrayStore.Arrays arrays = read(writeChunk(Row0.INSTANCE.numpressMz()), 0, false);
        assertThat(arrays.mz()).isEmpty();
        assertThat(arrays.intensity()).isEmpty();
    }

    @Test
    void anUnknownNumpressColumnNamesTheDecodedColumns() {
        ChunkRow row = Row0.INSTANCE.numpressMz();
        row.slof = Row0.INSTANCE.slof;
        row.extraSlof = Row0.INSTANCE.slof;
        Path file = writeChunk(row);
        assertThatThrownBy(() -> read(file, 0, false))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("unsupported Numpress column intensity_f32_dc_numpress_slof_bytes for spectrum_index 0;"
                        + " only mz_numpress_linear_bytes, intensity_numpress_slof_bytes and"
                        + " intensity_numpress_pic_bytes are decoded");
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    /** The first points of the current small.mzpeak peaks member, per spectrum index. */
    private static final class Peaks {
        static final Map<Long, List<double[]>> BY_INDEX = load();

        private static Map<Long, List<double[]>> load() {
            Map<Long, List<double[]>> out = new LinkedHashMap<>();
            try (MzPeakSource source = MzPeakSource.open(Fixtures.current("small.mzpeak"))) {
                String peaks = MzPeakManifest.fromSource(source).find("spectrum", "peaks").orElseThrow().name();
                ParquetGroups.forEach(source.inputFile(peaks), record -> {
                    Group point = record.getGroup("point", 0);
                    List<double[]> points = out.computeIfAbsent(ParquetGroups.optLong(point, "spectrum_index"),
                            k -> new ArrayList<>());
                    if (points.size() < 4) {
                        points.add(new double[] {ParquetGroups.optDouble(point, "mz"),
                                ParquetGroups.optDouble(point, "intensity")});
                    }
                });
            }
            for (long index : new long[] {2, 3, 4, 6}) {
                assertThat(out.get(index)).as("peaks of spectrum %s", index).hasSize(4);
            }
            return out;
        }

        /** The first 4 points of {@code index} as {m/z, intensity} pairs. */
        static double[][] points(long index) {
            return BY_INDEX.get(index).toArray(double[][]::new);
        }

        /** The first {@code counts.get(i)} points of each spectrum {@code i}, as point rows in key order. */
        static List<Group> rows(Map<Long, Integer> counts) {
            List<Group> rows = new ArrayList<>();
            counts.forEach((index, n) -> {
                double[][] p = points(index);
                for (int i = 0; i < n; i++) {
                    rows.add(point(POINT_SCHEMA, index, p[i][0], p[i][1]));
                }
            });
            return rows;
        }
    }

    /** Asserts that {@code arrays} are the points {@link #SPANNING} writes for spectrum {@code index}. */
    private static void assertPeaks(SpectrumArrayStore.Arrays arrays, long index) {
        assertThat(arrays).as("arrays of spectrum %s", index).isNotNull();
        double[][] p = Peaks.points(index);
        int n = SPANNING.get(index);
        double[] mz = new double[n];
        double[] intensity = new double[n];
        for (int i = 0; i < n; i++) {
            mz[i] = p[i][0];
            intensity[i] = p[i][1];
        }
        assertExact("m/z of spectrum " + index, mz, arrays.mz());
        assertExact("intensity of spectrum " + index, intensity, arrays.intensity());
    }

    /** Row 0 of the current small.numpress spectrum data member, with its Numpress payloads decoded. */
    private static final class Row0 {
        static final Row0 INSTANCE = load();

        final byte[] mzLinear;
        final byte[] slof;
        final double[] mz;
        final double[] intensity;

        private Row0(Group chunk) {
            assertThat(ParquetGroups.optLong(chunk, "spectrum_index")).isZero();
            assertThat(ParquetGroups.optString(chunk, "chunk_encoding")).isEqualTo("MS:1002312");
            mzLinear = ParquetGroups.byteList(chunk, "mz_numpress_linear_bytes");
            slof = ParquetGroups.byteList(chunk, "intensity_numpress_slof_bytes");
            mz = MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, mzLinear, mzLinear.length);
            intensity = MSNumpress.decode(MSNumpress.ACC_NUMPRESS_SLOF, slof, slof.length);
            assertThat(mz).hasSameSizeAs(intensity).hasSizeGreaterThan(100);
        }

        private static Row0 load() {
            try (MzPeakSource source = MzPeakSource.open(Fixtures.current("small.numpress.mzpeak"))) {
                String data = MzPeakManifest.fromSource(source).find("spectrum", "data_arrays").orElseThrow().name();
                Group[] first = new Group[1];
                ParquetGroups.forEach(source.inputFile(data), record -> {
                    if (first[0] == null) {
                        first[0] = record;
                    }
                });
                return new Row0(first[0].getGroup("chunk", 0));
            }
        }

        /** A row with the Numpress linear m/z only. */
        ChunkRow numpressMz() {
            ChunkRow row = new ChunkRow();
            row.start = mz[0];
            row.encoding = "MS:1002312";
            row.mzLinear = mzLinear;
            return row;
        }

        /** The decoded intensities encoded as Numpress PIC. */
        byte[] pic() {
            byte[] buffer = new byte[8 + intensity.length * 5];
            int length = MSNumpress.encodePic(intensity, intensity.length, buffer);
            return java.util.Arrays.copyOf(buffer, length);
        }
    }

    // ---- constructed members ------------------------------------------------------------------------------------

    private static final MessageType POINT_SCHEMA = Types.buildMessage()
            .addField(Types.optionalGroup()
                    .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false))
                    .named("spectrum_index")
                    .optional(PrimitiveTypeName.DOUBLE).named("mz")
                    .optional(PrimitiveTypeName.FLOAT).named("intensity")
                    .named("point"))
            .named("arrow_schema");

    private static final MessageType INT32_INDEX_SCHEMA = Types.buildMessage()
            .addField(Types.optionalGroup()
                    .optional(PrimitiveTypeName.INT32).as(LogicalTypeAnnotation.intType(32, false))
                    .named("spectrum_index")
                    .optional(PrimitiveTypeName.DOUBLE).named("mz")
                    .optional(PrimitiveTypeName.FLOAT).named("intensity")
                    .named("point"))
            .named("arrow_schema");

    private static final MessageType MZ_ONLY_SCHEMA = Types.buildMessage()
            .addField(Types.optionalGroup()
                    .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false))
                    .named("spectrum_index")
                    .optional(PrimitiveTypeName.DOUBLE).named("mz")
                    .named("point"))
            .named("arrow_schema");

    /** The chunk columns of the current fixtures, plus PIC and a dtype-suffixed SLOF column. */
    private static final MessageType CHUNK_SCHEMA = Types.buildMessage()
            .addField(Types.optionalGroup()
                    .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false))
                    .named("spectrum_index")
                    .optional(PrimitiveTypeName.DOUBLE).named("mz_chunk_start")
                    .addField(doubleList("mz_chunk_values"))
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("chunk_encoding")
                    .addField(doubleList("intensity"))
                    .addField(byteList("mz_numpress_linear_bytes"))
                    .addField(byteList("intensity_numpress_slof_bytes"))
                    .addField(byteList("intensity_numpress_pic_bytes"))
                    .addField(byteList("intensity_f32_dc_numpress_slof_bytes"))
                    .named("chunk"))
            .named("arrow_schema");

    private static Type doubleList(String name) {
        return Types.optionalList().optionalElement(PrimitiveTypeName.DOUBLE).named(name);
    }

    private static Type byteList(String name) {
        return Types.optionalList().requiredElement(PrimitiveTypeName.INT32)
                .as(LogicalTypeAnnotation.intType(8, false)).named(name);
    }

    /** One point row; a null argument is written as a null cell. */
    private static Group point(MessageType schema, Long index, Double mz, Double intensity) {
        Group record = new SimpleGroupFactory(schema).newGroup();
        Group point = record.addGroup("point");
        if (index != null) {
            if (schema.getType("point").asGroupType().getType("spectrum_index").asPrimitiveType()
                    .getPrimitiveTypeName() == PrimitiveTypeName.INT32) {
                point.add("spectrum_index", (int) (long) index);
            } else {
                point.add("spectrum_index", index);
            }
        }
        if (mz != null) {
            point.add("mz", mz);
        }
        if (intensity != null) {
            point.add("intensity", (float) (double) intensity);
        }
        return record;
    }

    /** One constructed chunk row of spectrum 0; a null field is written as a null cell, NaN as a null element. */
    private static final class ChunkRow {
        Double start;
        String encoding;
        double[] values;
        double[] intensity;
        byte[] mzLinear;
        byte[] slof;
        byte[] pic;
        byte[] extraSlof;
    }

    private Path writeChunk(ChunkRow row) {
        Group record = new SimpleGroupFactory(CHUNK_SCHEMA).newGroup();
        Group chunk = record.addGroup("chunk");
        chunk.add("spectrum_index", 0L);
        if (row.start != null) {
            chunk.add("mz_chunk_start", row.start);
        }
        addDoubles(chunk, "mz_chunk_values", row.values);
        if (row.encoding != null) {
            chunk.add("chunk_encoding", row.encoding);
        }
        addDoubles(chunk, "intensity", row.intensity);
        addBytes(chunk, "mz_numpress_linear_bytes", row.mzLinear);
        addBytes(chunk, "intensity_numpress_slof_bytes", row.slof);
        addBytes(chunk, "intensity_numpress_pic_bytes", row.pic);
        addBytes(chunk, "intensity_f32_dc_numpress_slof_bytes", row.extraSlof);
        return write(CHUNK_SCHEMA, List.of(record), 100, true);
    }

    private static void addDoubles(Group parent, String field, double[] values) {
        if (values != null) {
            Group list = parent.addGroup(field);
            for (double v : values) {
                Group element = list.addGroup("list");
                if (!Double.isNaN(v)) {
                    element.add("element", v);
                }
            }
        }
    }

    private static void addBytes(Group parent, String field, byte[] bytes) {
        if (bytes != null) {
            Group list = parent.addGroup(field);
            for (byte b : bytes) {
                list.addGroup("list").add("element", b & 0xFF);
            }
        }
    }

    private Path writePoints(MessageType schema, List<Group> rows, int rowGroupRows, boolean statistics) {
        List<Group> converted = new ArrayList<>();
        for (Group row : rows) {
            if (row.getType().equals(schema)) {
                converted.add(row);
            } else {
                Group point = row.getGroup("point", 0);
                converted.add(point(schema, ParquetGroups.optLong(point, "spectrum_index"),
                        ParquetGroups.optDouble(point, "mz"), ParquetGroups.optDouble(point, "intensity")));
            }
        }
        return write(schema, converted, rowGroupRows, statistics);
    }

    /** Writes {@code rows} with at most {@code rowGroupRows} rows per row group. */
    private Path write(MessageType schema, List<Group> rows, int rowGroupRows, boolean statistics) {
        Path file = tmp.resolve("member-" + (written++) + ".parquet");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withRowGroupRowCountLimit(rowGroupRows)
                .withStatisticsEnabled(statistics)
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

    private static int rowGroups(Path file) {
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(file), ParquetGroups.readOptions())) {
            return reader.getRowGroups().size();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What the store decodes for {@code index}, checked to be present. */
    private static SpectrumArrayStore.Arrays read(Path file, long index, boolean reconstructProfile) {
        try (SpectrumArrayStore store =
                     SpectrumArrayStore.load(new LocalInputFile(file), reconstructProfile, Map.of())) {
            SpectrumArrayStore.Arrays arrays = store.get(index);
            assertThat(arrays).as("arrays of spectrum %s", index).isNotNull();
            return arrays;
        }
    }

    private static Map<Long, Integer> orderedCounts(long... indexAndCount) {
        Map<Long, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < indexAndCount.length; i += 2) {
            out.put(indexAndCount[i], (int) indexAndCount[i + 1]);
        }
        return out;
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
