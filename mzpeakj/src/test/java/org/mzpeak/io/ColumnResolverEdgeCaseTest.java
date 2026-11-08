package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Param;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ColumnResolver} on constructed schemas, for what {@link ColumnResolverTest} and the fixtures do not reach:
 * list layouts other than the 3-level one, column_mapping items without a path or an accession, null and dotted
 * names, typed reads of columns of another type, and the leftovers of cells that no Param value holds.
 */
class ColumnResolverEdgeCaseTest {

    private static final String MEMBER = "constructed.parquet";

    // ---- child scopes ----------------------------------------------------------------------------------------

    /**
     * A 2-level list, with or without the LIST annotation, a 2-level list whose element struct has one field, and a
     * struct of one field are scopes, whose fields resolve and read below the field's name. A term whose path lies
     * below another field does not resolve in such a scope.
     */
    @Test
    void twoLevelListsAndSingleFieldStructsAreScopes() {
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional int64 source_index (INTEGER(64,false));\n"
                + "  optional group scan_windows {\n"
                + "    repeated group array {\n"
                + "      optional double scan_window_lower_limit;\n"
                + "      optional double scan_window_upper_limit;\n"
                + "    }\n"
                + "  }\n"
                + "  optional group lower_limits (LIST) {\n"
                + "    repeated group array {\n"
                + "      optional double scan_window_lower_limit;\n"
                + "    }\n"
                + "  }\n"
                + "  optional group window {\n"
                + "    optional double scan_window_lower_limit;\n"
                + "  }\n"
                + "}");
        ColumnResolver columns = ColumnResolver.split(schema, new Entry(MEMBER, "spectrum", "scans"),
                Facet.SPECTRUM_SCAN);
        ColumnResolver windows = columns.child("scan_windows");
        assertThat(windows.resolve("MS:1000501")).isEqualTo("scan_window_lower_limit");
        assertThat(windows.resolve("MS:1000500")).isEqualTo("scan_window_upper_limit");
        assertThat(windows.path("scan_window_upper_limit")).isEqualTo("scan_windows.scan_window_upper_limit");
        ColumnResolver lowerLimits = columns.child("lower_limits");
        assertThat(lowerLimits.resolveName("scan_window_lower_limit")).isEqualTo("scan_window_lower_limit");
        assertThat(lowerLimits.resolve("MS:1000501")).as("its path lies below scan_windows").isNull();
        ColumnResolver window = columns.child("window");
        assertThat(window.resolveName("scan_window_lower_limit")).isEqualTo("scan_window_lower_limit");
        assertThat(window.resolve("MS:1000501")).isNull();

        Group row = new SimpleGroupFactory(schema).newGroup().append("source_index", 0L);
        Group list = row.addGroup("scan_windows");
        list.addGroup("array").append("scan_window_lower_limit", 100.0).append("scan_window_upper_limit", 900.0);
        list.addGroup("array").append("scan_window_lower_limit", 200.0).append("scan_window_upper_limit", 800.0);
        row.addGroup("lower_limits").addGroup("array").append("scan_window_lower_limit", 150.0);
        row.addGroup("window").append("scan_window_lower_limit", 50.0);
        FacetRow scan = new FacetRow(Facet.SPECTRUM_SCAN, row, columns);
        assertThat(scan.children("scan_windows"))
                .extracting(w -> w.columns().doubleValue(w.group(), w.columns().resolve("MS:1000500")))
                .containsExactly(900.0, 800.0);
        assertThat(scan.children("lower_limits"))
                .extracting(w -> w.columns().doubleValue(w.group(), "scan_window_lower_limit"))
                .containsExactly(150.0);
        FacetRow single = scan.child("window");
        assertThat(single.columns().doubleValue(single.group(), "scan_window_lower_limit")).isEqualTo(50.0);
    }

    /** A LIST group without fields is a scope without fields. */
    @Test
    void aListWithoutFieldsIsAnEmptyScope() {
        MessageType schema = Types.buildMessage()
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("source_index")
                .addField(Types.optionalGroup().as(LogicalTypeAnnotation.listType()).named("scan_windows"))
                .named("arrow_schema");
        ColumnResolver windows = ColumnResolver.split(schema, new Entry(MEMBER, "spectrum", "scans"),
                Facet.SPECTRUM_SCAN).child("scan_windows");
        assertThat(windows.resolve("MS:1000501")).isNull();
        assertThat(windows.resolveName("element", "array", "list")).isNull();
        assertThat(windows.path("scan_window_lower_limit")).isEqualTo("scan_windows.scan_window_lower_limit");
    }

    /** A 2-level list of scalars is no scope, and neither is a null field name. */
    @Test
    void aTwoLevelListOfScalarsAndANullNameAreNoScope() {
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional int64 index (INTEGER(64,false));\n"
                + "  optional group mz_delta_model (LIST) {\n"
                + "    repeated double element;\n"
                + "  }\n"
                + "}");
        ColumnResolver columns = ColumnResolver.split(schema, new Entry(MEMBER, "spectrum", "metadata"),
                Facet.SPECTRUM);
        assertThatThrownBy(() -> columns.child("mz_delta_model"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(MEMBER + ": mz_delta_model is not a struct or a list of structs in arrow_schema");
        assertThatThrownBy(() -> columns.child(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(MEMBER + ": null is not a struct or a list of structs in arrow_schema");
    }

    // ---- resolution ------------------------------------------------------------------------------------------

    /**
     * A column_mapping item without a path names no column, and one without an accession claims none for another
     * term: the {@link ColumnTable} paths still resolve. A term-marker flag that the item omits comes from the
     * {@link ColumnTable} column of the field's path, and is false for a path the table does not know.
     */
    @Test
    void mappingItemsWithoutAPathOrAnAccession() {
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional int64 index (INTEGER(64,false));\n"
                + "  optional int32 ms_level;\n"
                + "  optional binary spectrum_type (STRING);\n"
                + "  optional binary custom (STRING);\n"
                + "}");
        Entry entry = new Entry(MEMBER, "spectrum", "metadata", List.of(
                new ColumnMapping("ms level", null, "MS:1000511", null, null),
                new ColumnMapping("spectrum type", "spectrum_type", null, null, null),
                new ColumnMapping("custom", "custom", null, null, null)), List.of(), null);
        ColumnResolver columns = ColumnResolver.split(schema, entry, Facet.SPECTRUM);
        assertThat(columns.resolve("MS:1000511")).isEqualTo("ms_level");
        assertThat(columns.resolve("MS:1000559")).isEqualTo("spectrum_type");
        assertThat(columns.mapping("spectrum_type")).contains(entry.columnMapping().get(1));
        assertThat(columns.termMarker("spectrum_type")).isTrue();
        assertThat(columns.termMarker("custom")).isFalse();

        Group row = new SimpleGroupFactory(schema).newGroup().append("index", 0L).append("ms_level", 2)
                .append("spectrum_type", "MS:1000580").append("custom", "x");
        assertThat(columns.leftovers(row, Set.of("ms_level"))).containsExactly(
                new Param(null, "MS:1000580", null, null),
                new Param("custom", null, "x", null));
    }

    /** A fallback name that the column_mapping assigns to another term is not taken for the column table's term. */
    @Test
    void aFallbackMappedToAnotherTermIsNotTaken() {
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional int64 index (INTEGER(64,false));\n"
                + "  optional int32 MS_1000465_scan_polarity;\n"
                + "}");
        Entry entry = new Entry(MEMBER, "spectrum", "metadata", List.of(
                new ColumnMapping("vendor polarity", "MS_1000465_scan_polarity", "MS:9999001", null, null)),
                List.of(), null);
        ColumnResolver columns = ColumnResolver.split(schema, entry, Facet.SPECTRUM);
        assertThat(columns.resolve("MS:1000465")).isNull();
        assertThat(columns.resolve("MS:9999001")).isEqualTo("MS_1000465_scan_polarity");
        assertThat(ColumnResolver.split(schema, new Entry(MEMBER, "spectrum", "metadata"), Facet.SPECTRUM)
                .resolve("MS:1000465")).as("unmapped").isEqualTo("MS_1000465_scan_polarity");
    }

    /**
     * Null names are skipped among the extra names of {@link ColumnResolver#resolve} and the names of
     * {@link ColumnResolver#resolveName}. A field whose name holds a dot is not found by its column_mapping path, which
     * reads as a nested one, but as an extra name: when the column_mapping assigns it to the term looked up, not to
     * another term.
     */
    @Test
    void nullNamesAreSkippedAndADottedNameIsFoundAsAnExtraName() {
        MessageType schema = Types.buildMessage()
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("index")
                .optional(PrimitiveTypeName.INT32).named("level")
                .optional(PrimitiveTypeName.INT32).named("vendor.level")
                .named("arrow_schema");
        Entry entry = new Entry(MEMBER, "spectrum", "metadata", List.of(
                new ColumnMapping("vendor level", "vendor.level", "MS:9999002", null, null)), List.of(), null);
        ColumnResolver columns = ColumnResolver.split(schema, entry, Facet.SPECTRUM);

        assertThat(columns.resolve("MS:9999003", null, "level")).isEqualTo("level");
        assertThat(columns.resolveName(null, "level")).isEqualTo("level");

        assertThat(columns.resolve("MS:9999002")).isNull();
        assertThat(columns.resolve("MS:9999002", "vendor.level")).isEqualTo("vendor.level");
        assertThat(columns.resolve("MS:9999003", "vendor.level")).as("mapped to another term").isNull();
        Group row = new SimpleGroupFactory(schema).newGroup().append("index", 0L).append("vendor.level", 3);
        assertThat(columns.longValue(row, "vendor.level")).isEqualTo(3L);
    }

    // ---- typed reads -----------------------------------------------------------------------------------------

    /** A boolean cell comes from a BOOLEAN column, or from an INT32 one as non-zero; other columns throw. */
    @Test
    void booleanCellsReadFromBooleanAndInt32ColumnsOnly() {
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional int64 index (INTEGER(64,false));\n"
                + "  optional boolean flag;\n"
                + "  optional int32 count;\n"
                + "  optional double mass;\n"
                + "}");
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);
        Group yes = factory.newGroup().append("index", 0L).append("flag", true).append("count", 2).append("mass", 1.5);
        Group no = factory.newGroup().append("index", 1L).append("flag", false).append("count", 0);
        ColumnResolver columns = ColumnResolver.split(schema, new Entry(MEMBER, "spectrum", "metadata"),
                Facet.SPECTRUM);

        assertThat(columns.booleanValue(yes, "flag")).isTrue();
        assertThat(columns.booleanValue(no, "flag")).isFalse();
        assertThat(columns.booleanValue(yes, "count")).isTrue();
        assertThat(columns.booleanValue(no, "count")).isFalse();
        assertThat(columns.booleanValue(no, "mass")).as("null in this row").isNull();
        assertThat(columns.booleanValue(yes, "no_such_column")).isNull();
        assertThat(columns.booleanValue(yes, null)).isNull();
        assertThatThrownBy(() -> columns.booleanValue(yes, "mass"))
                .isInstanceOf(MzPeakException.class)
                .hasMessage(MEMBER + ": mass is not a boolean: DOUBLE");
    }

    /** An integer cell below the int range does not fit an int, as one above it does not. */
    @Test
    void anIntCellBelowTheIntRangeThrows() {
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional int64 index (INTEGER(64,false));\n"
                + "  optional int64 count;\n"
                + "}");
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);
        ColumnResolver columns = ColumnResolver.split(schema, new Entry(MEMBER, "spectrum", "metadata"),
                Facet.SPECTRUM);
        assertThat(columns.intValue(factory.newGroup().append("count", (long) Integer.MIN_VALUE), "count"))
                .isEqualTo(Integer.MIN_VALUE);
        assertThatThrownBy(() -> columns.intValue(factory.newGroup().append("count", -3_000_000_000L), "count"))
                .isInstanceOf(MzPeakException.class)
                .hasMessage(MEMBER + ": count holds -3000000000, beyond the int range");
    }

    /**
     * A struct read as a scalar and a scalar read as a list throw, naming the member and the field's path; a list read
     * by a {@code null} name is empty.
     */
    @Test
    void aStructReadAsAScalarAndAScalarReadAsAListThrow() {
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional int64 source_index (INTEGER(64,false));\n"
                + "  optional double mass;\n"
                + "  optional group activation {\n"
                + "    optional double collision_energy;\n"
                + "    optional group settings {\n"
                + "      optional double energy;\n"
                + "    }\n"
                + "  }\n"
                + "}");
        Group row = new SimpleGroupFactory(schema).newGroup().append("source_index", 0L).append("mass", 1.5);
        row.addGroup("activation").append("collision_energy", 30.0).addGroup("settings").append("energy", 2.0);
        ColumnResolver columns = ColumnResolver.split(schema, new Entry(MEMBER, "spectrum", "precursors"),
                Facet.SPECTRUM_PRECURSOR);
        String struct = MEMBER + ": activation is a struct or list column, expected a scalar";
        assertThatThrownBy(() -> columns.longValue(row, "activation")).isInstanceOf(MzPeakException.class)
                .hasMessage(struct);
        assertThatThrownBy(() -> columns.doubleValue(row, "activation")).isInstanceOf(MzPeakException.class)
                .hasMessage(struct);
        assertThatThrownBy(() -> columns.stringValue(row, "activation")).isInstanceOf(MzPeakException.class)
                .hasMessage(struct);
        assertThatThrownBy(() -> columns.booleanValue(row, "activation")).isInstanceOf(MzPeakException.class)
                .hasMessage(struct);
        ColumnResolver activation = columns.child("activation");
        Group cells = row.getGroup("activation", 0);
        assertThatThrownBy(() -> activation.doubleValue(cells, "settings")).isInstanceOf(MzPeakException.class)
                .hasMessage(MEMBER + ": activation.settings is a struct or list column, expected a scalar");

        assertThatThrownBy(() -> columns.doubleList(row, "mass")).isInstanceOf(MzPeakException.class)
                .hasMessage(MEMBER + ": mass is a scalar column, expected a list");
        assertThat(columns.doubleList(row, null)).isEmpty();
    }

    /**
     * An integer annotation on a column that is neither INT32 nor INT64, which parquet-java writes into no file but an
     * in-memory schema can carry, does not make the column an unsigned integer: a BINARY column stays no integer.
     */
    @Test
    void anUnsignedAnnotationOnABinaryColumnIsNoInteger() {
        PrimitiveType text = new PrimitiveType(Type.Repetition.OPTIONAL, PrimitiveTypeName.BINARY, "count")
                .withLogicalTypeAnnotation(LogicalTypeAnnotation.intType(32, false));
        MessageType schema = new MessageType("arrow_schema", Types.optional(PrimitiveTypeName.INT64)
                .as(LogicalTypeAnnotation.intType(64, false)).named("index"), text);
        Group row = new SimpleGroupFactory(schema).newGroup().append("index", 0L).append("count", "12");
        ColumnResolver columns = ColumnResolver.split(schema, new Entry(MEMBER, "spectrum", "metadata"),
                Facet.SPECTRUM);
        assertThatThrownBy(() -> columns.longValue(row, "count")).isInstanceOf(MzPeakException.class)
                .hasMessage(MEMBER + ": count is not an integer: BINARY");
        assertThatThrownBy(() -> columns.doubleValue(row, "count")).isInstanceOf(MzPeakException.class)
                .hasMessage(MEMBER + ": count is not an integer: BINARY");
    }

    // ---- leftovers -------------------------------------------------------------------------------------------

    /**
     * Cells of a type no Param value holds (FIXED_LEN_BYTE_ARRAY, INT96) are no leftovers, and a term-marker column
     * that holds a number gives a Param with its value, as an ordinary mapped column does.
     */
    @Test
    void leftoversSkipCellsNoParamHoldsAndKeepTheNumberOfATermMarker() {
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional int64 index (INTEGER(64,false));\n"
                + "  optional fixed_len_byte_array(4) digest;\n"
                + "  optional int96 acquired;\n"
                + "  optional int32 charge_flag;\n"
                + "}");
        Entry entry = new Entry(MEMBER, "spectrum", "metadata", List.of(
                new ColumnMapping("charge flag", "charge_flag", "MS:9999004", "UO:0000189", true)), List.of(), null);
        Group row = new SimpleGroupFactory(schema).newGroup().append("index", 0L)
                .append("digest", Binary.fromConstantByteArray(new byte[] {1, 2, 3, 4}))
                .append("acquired", Binary.fromConstantByteArray(new byte[12]))
                .append("charge_flag", 3);
        ColumnResolver columns = ColumnResolver.split(schema, entry, Facet.SPECTRUM);
        assertThat(ParquetGroups.has(row, "digest")).isTrue();
        assertThat(ParquetGroups.has(row, "acquired")).isTrue();
        assertThat(columns.termMarker("charge_flag")).isTrue();
        assertThat(columns.leftovers(row, Set.of()))
                .containsExactly(new Param("charge flag", "MS:9999004", 3L, "UO:0000189"));
    }
}
