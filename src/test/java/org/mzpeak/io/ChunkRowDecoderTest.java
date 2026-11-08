package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.junit.jupiter.api.Test;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.SignalPlan.Layout;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/**
 * {@link ChunkRowDecoder} on constructed chunk rows of spectrum signal members, for the plans and rows that the
 * vendored fixtures do not contain: a plan without a chunk_encoding, axis values or chunk start column, a grid column
 * next to delta values, Numpress columns the plan lists twice or does not decode, an empty PIC payload, an extra
 * that is not a list of numbers, several plain intensity columns and an intensity chunk transform that names no
 * transform. Each row is an in-memory parquet-java record of the schema its plan is derived from, decoded directly.
 */
class ChunkRowDecoderTest {

    private static final String DELTA = "MS:1003089";
    private static final String NUMPRESS_LINEAR = "MS:1002312";

    private static final String START = "optional double mz_chunk_start;";
    private static final String VALUES = list("mz_chunk_values", "optional double element;");
    private static final String ENCODING = "optional binary chunk_encoding (STRING);";
    private static final String INTENSITY = list("intensity", "optional float element;");
    private static final String MZ_GRID = "optional group mz_grid { optional binary grid_type (STRING);"
            + " optional group parameters (LIST) { repeated group list { required double element; } }"
            + " optional group indices (LIST) { repeated group list {"
            + " required int32 element (INTEGER(32,false)); } } }";
    private static final String LINEAR = bytes("mz_numpress_linear_bytes");

    private static final String RANK_1 = ", \"sorting_rank\": 1";
    private static final String INDEX_ENTRY = indexEntry("spectrum_index", "index", "MS:1003870",
            ", \"sorting_rank\": 0");
    private static final String START_ENTRY = indexEntry("mz_chunk_start", "chunk_start", "MS:1000514", RANK_1);
    private static final String VALUES_ENTRY = indexEntry("mz_chunk_values", "chunk_values", "MS:1000514", RANK_1);
    private static final String ENCODING_ENTRY = indexEntry("chunk_encoding", "chunk_encoding", "MS:1000514", RANK_1);

    // ---- the plan --------------------------------------------------------------------------------------------------

    @Test
    void aChromatogramPlanIsNotDecodedHere() {
        ParquetFooter f = ArrayIndexTest.footer(Fixtures.current("small.chunked.mzpeak"), EntityType.CHROMATOGRAM,
                DataKind.DATA_ARRAYS);
        SignalPlan plan = SignalPlan.of(ArrayIndex.of(f.keyValue(), f.schema(), SignalEntity.CHROMATOGRAM),
                f.schema(), SignalEntity.CHROMATOGRAM);
        assertThat(plan.layout()).isEqualTo(Layout.CHUNK);

        assertThatThrownBy(() -> new ChunkRowDecoder(plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("chunk rows of CHROMATOGRAM are not decoded here");
    }

    // ---- axis values ----------------------------------------------------------------------------------------------

    @Test
    void aPlanWithoutAChunkEncodingColumnDecodesDeltasFromTheChunkStart() {
        MessageType schema = schema(START, VALUES, INTENSITY);
        SignalPlan plan = inferred(schema);
        assertThat(plan.axis().encoding()).isNull();
        Group chunk = row(schema);
        chunk.add("mz_chunk_start", 100.0);
        addList(chunk, "mz_chunk_values", 0.5, 0.25);
        addList(chunk, "intensity", 1, 2, 3);

        ChunkRowDecoder.Row row = new ChunkRowDecoder(plan).decode(chunk, 0);

        assertThat(row.axis()).containsExactly(100.0, 100.5, 100.75);
        assertThat(row.intensity()).containsExactly(1.0, 2.0, 3.0);
    }

    @Test
    void withoutAnAxisValuesColumnTheChunkStartIsTheOnlyPoint() {
        MessageType schema = schema(START, ENCODING, INTENSITY);
        SignalPlan plan = inferred(schema);
        assertThat(plan.axis().values()).isNull();
        Group chunk = row(schema);
        chunk.add("mz_chunk_start", 250.5);
        chunk.add("chunk_encoding", DELTA);
        addList(chunk, "intensity", 7);

        ChunkRowDecoder.Row row = new ChunkRowDecoder(plan).decode(chunk, 0);

        assertThat(row.axis()).containsExactly(250.5);
        assertThat(row.intensity()).containsExactly(7.0);
    }

    @Test
    void withoutAxisValuesAndChunkStartOnlyARowWithoutIntensitiesDecodes() {
        MessageType schema = schema(ENCODING, INTENSITY);
        SignalPlan plan = inferred(schema);
        assertThat(plan.axis().values()).isNull();
        assertThat(plan.axis().start()).isNull();
        ChunkRowDecoder decoder = new ChunkRowDecoder(plan);
        Group empty = row(schema);
        empty.add("chunk_encoding", DELTA);
        addList(empty, "intensity");
        Group one = row(schema);
        one.add("chunk_encoding", DELTA);
        addList(one, "intensity", 1);

        ChunkRowDecoder.Row row = decoder.decode(empty, 2);

        assertThat(row.axis()).isEmpty();
        assertThat(row.intensity()).isEmpty();
        assertThatThrownBy(() -> decoder.decode(one, 2))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("chunk row of spectrum_index 2 carries 1 intensities, but the signal plan names neither"
                        + " an m/z values nor an m/z chunk start column");
    }

    // ---- grids ----------------------------------------------------------------------------------------------------

    @Test
    void aGridNextToDeltaValuesIsNotRead() {
        MessageType schema = schema(START, VALUES, ENCODING, INTENSITY, MZ_GRID);
        SignalPlan plan = inferred(schema);
        assertThat(plan.axis().transformFields()).containsExactly(entry(ArrayIndex.GRID_TRANSFORM, "mz_grid"));
        Group chunk = row(schema);
        chunk.add("mz_chunk_start", 100.0);
        addList(chunk, "mz_chunk_values", 1.0);
        chunk.add("chunk_encoding", DELTA);
        addList(chunk, "intensity", 5, 6);
        // read as a grid, the m/z would be 7 and 15
        addGrid(chunk, GridModels.LINEAR, 7, 8);

        ChunkRowDecoder.Row row = new ChunkRowDecoder(plan).decode(chunk, 0);

        assertThat(row.axis()).containsExactly(100.0, 101.0);
        assertThat(row.intensity()).containsExactly(5.0, 6.0);
    }

    @Test
    void aGridWithoutValuesFailsWithoutTheGridEncodingOnlyWhenTheRowHasIntensities() {
        MessageType schema = schema(START, VALUES, ENCODING, INTENSITY, MZ_GRID);
        ChunkRowDecoder decoder = new ChunkRowDecoder(inferred(schema));
        Group empty = gridRowWithoutValues(schema);
        addList(empty, "intensity");
        Group one = gridRowWithoutValues(schema);
        addList(one, "intensity", 5);

        ChunkRowDecoder.Row row = decoder.decode(empty, 0);

        assertThat(row.axis()).isEmpty();
        assertThat(row.intensity()).isEmpty();
        assertThatThrownBy(() -> decoder.decode(one, 0))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("mz_grid carries a grid but chunk_encoding is " + DELTA + " for spectrum_index 0");
    }

    /** A delta row of {@code schema} with a chunk start, empty axis values and a linear grid of one index. */
    private static Group gridRowWithoutValues(MessageType schema) {
        Group chunk = row(schema);
        chunk.add("mz_chunk_start", 100.0);
        addList(chunk, "mz_chunk_values");
        chunk.add("chunk_encoding", DELTA);
        addGrid(chunk, GridModels.LINEAR, 7);
        return chunk;
    }

    @Test
    void theGridEncodingFailsWhenThePlanHasNoGridColumn() {
        MessageType schema = schema(START, VALUES, ENCODING, INTENSITY);
        Group chunk = row(schema);
        chunk.add("chunk_encoding", ChunkCodecs.GRID_ENCODING);
        addList(chunk, "intensity", 1);

        assertThatThrownBy(() -> new ChunkRowDecoder(inferred(schema)).decode(chunk, 5))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("chunk_encoding MS:1003826 for spectrum_index 5, but the signal plan has no grid column");
    }

    @Test
    void aGridWithoutAGridTypeFailsNamingNone() {
        MessageType schema = schema(START, VALUES, ENCODING, INTENSITY, MZ_GRID);
        Group chunk = row(schema);
        chunk.add("chunk_encoding", ChunkCodecs.GRID_ENCODING);
        addList(chunk, "intensity", 1, 2);
        addGrid(chunk, null, 1, 1);

        assertThatThrownBy(() -> new ChunkRowDecoder(inferred(schema)).decode(chunk, 0))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("grid_type (none) of mz_grid is not one of MS:1003824, MS:1003825, MS:9999002,"
                        + " MS:9999001 for spectrum_index 0");
    }

    // ---- Numpress columns -----------------------------------------------------------------------------------------

    @Test
    void withoutDecodedNumpressColumnsTheErrorSaysNoneIsDecoded() {
        MessageType schema = schema(START, VALUES, ENCODING, INTENSITY, bytes("mz_numpress_delta_bytes"));
        Group chunk = row(schema);
        addBytes(chunk, "mz_numpress_delta_bytes", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> new ChunkRowDecoder(inferred(schema)).decode(chunk, 0))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("unsupported Numpress column mz_numpress_delta_bytes for spectrum_index 0;"
                        + " no Numpress column is decoded");
    }

    @Test
    void withOneDecodedNumpressColumnTheErrorNamesIt() {
        MessageType schema = schema(ENCODING, LINEAR, INTENSITY, bytes("intensity_numpress_delta_bytes"));
        Group chunk = row(schema);
        addBytes(chunk, "intensity_numpress_delta_bytes", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> new ChunkRowDecoder(inferred(schema)).decode(chunk, 0))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("unsupported Numpress column intensity_numpress_delta_bytes for spectrum_index 0;"
                        + " only mz_numpress_linear_bytes is decoded");
    }

    @Test
    void aNumpressColumnTheIndexListsTwiceIsNamedOnce() {
        MessageType schema = schema(ENCODING, LINEAR, bytes("intensity_numpress_slof_bytes"),
                bytes("intensity_numpress_delta_bytes"));
        String slof = indexEntry("intensity_numpress_slof_bytes", "chunk_transform", ArrayIndex.INTENSITY_ARRAY,
                ", \"transform\": \"" + MSNumpress.ACC_NUMPRESS_SLOF + "\"");
        SignalPlan plan = footer(schema, INDEX_ENTRY, ENCODING_ENTRY,
                indexEntry("mz_numpress_linear_bytes", "chunk_transform", "MS:1000514",
                        RANK_1 + ", \"transform\": \"" + NUMPRESS_LINEAR + "\""), slof, slof);
        assertThat(plan.intensities()).hasSize(2);
        Group chunk = row(schema);
        addBytes(chunk, "intensity_numpress_delta_bytes", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> new ChunkRowDecoder(plan).decode(chunk, 0))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("unsupported Numpress column intensity_numpress_delta_bytes for spectrum_index 0;"
                        + " only mz_numpress_linear_bytes and intensity_numpress_slof_bytes are decoded");
    }

    @Test
    void numpressMzWithAnEmptyPicPayloadHasNoIntensities() {
        MessageType schema = schema(ENCODING, LINEAR, bytes("intensity_numpress_pic_bytes"));
        ChunkRowDecoder decoder = new ChunkRowDecoder(inferred(schema));
        Group emptyPic = numpressRow(schema, 100.0, 200.0, 300.0);
        addBytes(emptyPic, "intensity_numpress_pic_bytes", new byte[0]);
        Group noPic = numpressRow(schema, 100.0, 200.0, 300.0);

        // the null-but-present PIC cell makes the intensities empty rather than missing
        assertThatThrownBy(() -> decoder.decode(emptyPic, 0))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("Numpress chunk m/z (3) and intensity (0) lengths differ for spectrum_index 0");
        assertThatThrownBy(() -> decoder.decode(noPic, 0))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("unsupported chunk: Numpress linear m/z without an intensity column (SLOF, PIC or"
                        + " intensity) for spectrum_index 0");
    }

    /** A row of {@code schema} whose m/z {@code mz} are stored as Numpress linear. */
    private static Group numpressRow(MessageType schema, double... mz) {
        Group chunk = row(schema);
        chunk.add("chunk_encoding", NUMPRESS_LINEAR);
        byte[] encoded = new byte[8 + mz.length * 5];
        int n = MSNumpress.encodeLinear(mz, mz.length, encoded, MSNumpress.optimalLinearFixedPoint(mz, mz.length));
        addBytes(chunk, "mz_numpress_linear_bytes", Arrays.copyOf(encoded, n));
        return chunk;
    }

    // ---- intensities ----------------------------------------------------------------------------------------------

    @Test
    void theFirstIntensityColumnCarryingValuesWinsOverEarlierEmptyOnes() {
        MessageType schema = schema(START, VALUES, list("intensity_a", "optional float element;"),
                list("intensity_b", "optional float element;"), list("intensity_c", "optional float element;"));
        SignalPlan plan = footer(schema, INDEX_ENTRY, START_ENTRY, VALUES_ENTRY,
                indexEntry("intensity_a", "chunk_secondary", ArrayIndex.INTENSITY_ARRAY, ""),
                indexEntry("intensity_b", "chunk_secondary", ArrayIndex.INTENSITY_ARRAY, ""),
                indexEntry("intensity_c", "chunk_secondary", ArrayIndex.INTENSITY_ARRAY, ""));
        Group chunk = row(schema);
        chunk.add("mz_chunk_start", 100.0);
        addList(chunk, "mz_chunk_values", 1.0);
        addList(chunk, "intensity_a");
        addList(chunk, "intensity_b");
        addList(chunk, "intensity_c", 5, 6);

        ChunkRowDecoder.Row row = new ChunkRowDecoder(plan).decode(chunk, 0);

        assertThat(row.axis()).containsExactly(100.0, 101.0);
        assertThat(row.intensity()).containsExactly(5.0, 6.0);
    }

    @Test
    void anIntensityTransformNamingNoTransformFailsWhenItHoldsTheOnlyIntensities() {
        MessageType schema = schema(START, VALUES, INTENSITY, list("intensity_packed", "optional double element;"));
        SignalPlan plan = footer(schema, INDEX_ENTRY, START_ENTRY, VALUES_ENTRY,
                indexEntry("intensity", "chunk_secondary", ArrayIndex.INTENSITY_ARRAY, ""),
                indexEntry("intensity_packed", "chunk_transform", ArrayIndex.INTENSITY_ARRAY, ""));
        Group chunk = row(schema);
        chunk.add("mz_chunk_start", 100.0);
        addList(chunk, "mz_chunk_values", 1.0);
        addList(chunk, "intensity_packed", 1, 2);

        assertThatThrownBy(() -> new ChunkRowDecoder(plan).decode(chunk, 0))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("spectrum_index 0 stores its intensities with an unnamed chunk transform (column"
                        + " intensity_packed), which is not decoded");
    }

    // ---- extras ---------------------------------------------------------------------------------------------------

    @Test
    void anExtraThatIsNotAListOfNumbersIsDroppedAndTheSignalKept() {
        MessageType schema = schema(START, VALUES, ENCODING, INTENSITY,
                list("label", "optional binary element (STRING);"));
        SignalPlan plan = footer(schema, INDEX_ENTRY, START_ENTRY, VALUES_ENTRY, ENCODING_ENTRY,
                indexEntry("intensity", "chunk_secondary", ArrayIndex.INTENSITY_ARRAY, ""),
                indexEntry("label", "chunk_secondary", SignalPlan.NON_STANDARD_ARRAY, ", \"array_name\": \"label\""));
        assertThat(plan.extras()).containsOnlyKeys("label");
        Group chunk = row(schema);
        chunk.add("mz_chunk_start", 100.0);
        addList(chunk, "mz_chunk_values", 1.0);
        chunk.add("chunk_encoding", DELTA);
        addList(chunk, "intensity", 5, 6);
        Group label = chunk.addGroup("label");
        label.addGroup("list").add("element", "a");
        label.addGroup("list").add("element", "b");

        ChunkRowDecoder.Row row = new ChunkRowDecoder(plan).decode(chunk, 0);

        assertThat(row.axis()).containsExactly(100.0, 101.0);
        assertThat(row.intensity()).containsExactly(5.0, 6.0);
        assertThat(row.extras()).isEmpty();
        assertThat(row.dropped()).containsExactly(entry("label",
                "cannot be read as a list of numbers (List element element is not numeric: BINARY)"));
    }

    // ---- constructed members ---------------------------------------------------------------------------------------

    /** A spectrum chunk member schema with {@code spectrum_index} and the declarations {@code columns}. */
    private static MessageType schema(String... columns) {
        return MessageTypeParser.parseMessageType("message m { optional group chunk {"
                + " optional int64 spectrum_index; " + String.join(" ", columns) + " } }");
    }

    private static String list(String field, String element) {
        return "optional group " + field + " (LIST) { repeated group list { " + element + " } }";
    }

    /** A Numpress byte list column. */
    private static String bytes(String field) {
        return list(field, "required int32 element (INTEGER(8,false));");
    }

    /** An index entry of the column {@code chunk.<field>} with the JSON members {@code more}. */
    private static String indexEntry(String field, String format, String arrayType, String more) {
        return "{\"path\": \"chunk." + field + "\", \"buffer_format\": \"" + format + "\", \"array_type\": \""
                + arrayType + "\"" + more + "}";
    }

    /** The plan of {@code schema} without an array index footer. */
    private static SignalPlan inferred(MessageType schema) {
        return SignalPlan.of(ArrayIndex.infer(schema, SignalEntity.SPECTRUM), schema, SignalEntity.SPECTRUM);
    }

    /** The plan of {@code schema} with a footer listing {@code entries} under the prefix chunk. */
    private static SignalPlan footer(MessageType schema, String... entries) {
        String json = "{\"prefix\": \"chunk\", \"entries\": [" + String.join(", ", entries) + "]}";
        return SignalPlan.of(ArrayIndex.parse(json, SignalEntity.SPECTRUM), schema, SignalEntity.SPECTRUM);
    }

    /** A new row of {@code schema}: its chunk struct. */
    private static Group row(MessageType schema) {
        return new SimpleGroupFactory(schema).newGroup().addGroup("chunk");
    }

    /** Adds the list {@code field} holding {@code values} as its declared FLOAT or DOUBLE elements. */
    private static void addList(Group chunk, String field, double... values) {
        Group list = chunk.addGroup(field);
        boolean isFloat = list.getType().getType("list").asGroupType().getType("element").asPrimitiveType()
                .getPrimitiveTypeName() == PrimitiveTypeName.FLOAT;
        for (double v : values) {
            if (isFloat) {
                list.addGroup("list").add("element", (float) v);
            } else {
                list.addGroup("list").add("element", v);
            }
        }
    }

    private static void addBytes(Group chunk, String field, byte[] bytes) {
        Group list = chunk.addGroup(field);
        for (byte b : bytes) {
            list.addGroup("list").add("element", b & 0xff);
        }
    }

    /** Adds {@code mz_grid} with {@code gridType} (none when null), parameters {@code [0, 1]} and {@code indices}. */
    private static void addGrid(Group chunk, String gridType, int... indices) {
        Group grid = chunk.addGroup("mz_grid");
        if (gridType != null) {
            grid.add("grid_type", gridType);
        }
        Group parameters = grid.addGroup("parameters");
        parameters.addGroup("list").add("element", 0.0);
        parameters.addGroup("list").add("element", 1.0);
        Group list = grid.addGroup("indices");
        for (int i : indices) {
            list.addGroup("list").add("element", i);
        }
    }
}
