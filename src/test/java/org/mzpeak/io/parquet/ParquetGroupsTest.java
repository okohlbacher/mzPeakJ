package org.mzpeak.io.parquet;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;
import org.mzpeak.model.Param;

import java.io.FileNotFoundException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The paths of {@link ParquetGroups} that no vendored fixture takes: a member that cannot be opened, integers stored
 * as DOUBLE or non-finite FLOAT, non-numeric leaves, and list layouts other than the 3-level LIST the fixtures use
 * (2-level repeated primitives and groups, list wrappers without fields, null list elements, INT32 and group
 * {@code boolean} arms of a param value). The records are built in memory against the schema each case needs.
 */
class ParquetGroupsTest {

    private static final Path MISSING = Fixtures.current("small.unpacked.mzpeak").resolve("no_such_member.parquet");

    /** An empty record of {@code schema}, a Parquet message type in text form. */
    private static Group record(String schema) {
        return new SimpleGroup(MessageTypeParser.parseMessageType(schema));
    }

    @Test
    void aMemberThatCannotBeOpenedFailsWithTheIoCause() {
        List<Group> rows = new ArrayList<>();
        assertThatThrownBy(() -> ParquetGroups.forEach(MISSING, rows::add))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Failed reading Parquet input")
                .cause().isInstanceOf(FileNotFoundException.class).hasMessageContaining("no_such_member.parquet");
        assertThat(rows).isEmpty();
        assertThatThrownBy(() -> ParquetGroups.footerKeyValue(new LocalInputFile(MISSING)))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Failed reading Parquet footer")
                .cause().isInstanceOf(FileNotFoundException.class).hasMessageContaining("no_such_member.parquet");
    }

    @Test
    void optLongCoercesFiniteFloatingPointAndNullsTheRest() {
        String schema = "message m { optional float f; optional double d; }";
        Group nanFloat = record(schema);
        nanFloat.add("f", Float.NaN);
        Group infFloat = record(schema);
        infFloat.add("f", Float.POSITIVE_INFINITY);
        Group fraction = record(schema);
        fraction.add("d", 42.9);
        Group negative = record(schema);
        negative.add("d", -7.5);
        Group nanDouble = record(schema);
        nanDouble.add("d", Double.NaN);
        Group infDouble = record(schema);
        infDouble.add("d", Double.NEGATIVE_INFINITY);

        assertThat(ParquetGroups.optLong(nanFloat, "f")).isNull();
        assertThat(ParquetGroups.optLong(infFloat, "f")).isNull();
        assertThat(ParquetGroups.optLong(fraction, "d")).isEqualTo(42L);
        assertThat(ParquetGroups.optLong(negative, "d")).isEqualTo(-7L);
        assertThat(ParquetGroups.optLong(nanDouble, "d")).isNull();
        assertThat(ParquetGroups.optLong(infDouble, "d")).isNull();
        assertThat(ParquetGroups.optInt(fraction, "d")).isEqualTo(42);
        assertThat(ParquetGroups.optInt(nanDouble, "d")).isNull();
        assertThat(ParquetGroups.optLong(fraction, "f")).isNull();
    }

    @Test
    void optLongRefusesNonNumericLeaves() {
        Group g = record("message m { optional binary s (STRING); optional boolean b; }");
        g.add("s", "12");
        g.add("b", true);

        assertThatThrownBy(() -> ParquetGroups.optLong(g, "s"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Field s is not an integer: BINARY");
        assertThatThrownBy(() -> ParquetGroups.optInt(g, "b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Field b is not an integer: BOOLEAN");
    }

    @Test
    void groupListSkipsNullElementsOfA3LevelList() {
        Group g = record("message m { optional group items (LIST) { repeated group list {"
                + " optional group element { optional int32 x; } } } }");
        Group wrapper = g.addGroup("items");
        wrapper.addGroup("list").addGroup("element").append("x", 1);
        wrapper.addGroup("list");
        wrapper.addGroup("list").addGroup("element").append("x", 3);

        List<Group> items = ParquetGroups.groupList(g, "items");

        assertThat(items).extracting(item -> item.getInteger("x", 0)).containsExactly(1, 3);
    }

    @Test
    void groupListReturnsTheSlotsOfA2LevelList() {
        Group pairs = record("message m { optional group items (LIST) { repeated group item {"
                + " optional int32 x; optional int32 y; } } }");
        Group pairWrapper = pairs.addGroup("items");
        pairWrapper.addGroup("item").append("x", 1).append("y", 2);
        pairWrapper.addGroup("item").append("x", 3);
        Group singles = record("message m { optional group items (LIST) { repeated group item {"
                + " optional double x; } } }");
        Group singleWrapper = singles.addGroup("items");
        singleWrapper.addGroup("item").append("x", 1.5);
        singleWrapper.addGroup("item").append("x", 2.5);

        List<Group> pairItems = ParquetGroups.groupList(pairs, "items");
        List<Group> singleItems = ParquetGroups.groupList(singles, "items");

        assertThat(pairItems).hasSize(2);
        assertThat(ParquetGroups.optInt(pairItems.get(0), "x")).isEqualTo(1);
        assertThat(ParquetGroups.optInt(pairItems.get(0), "y")).isEqualTo(2);
        assertThat(ParquetGroups.optInt(pairItems.get(1), "x")).isEqualTo(3);
        assertThat(ParquetGroups.optInt(pairItems.get(1), "y")).isNull();
        assertThat(singleItems).extracting(item -> item.getDouble("x", 0)).containsExactly(1.5, 2.5);
    }

    @Test
    void groupListOfPrimitivesOrOfAWrapperWithoutFieldsIsEmpty() {
        Group primitives = record("message m { optional group items (LIST) { repeated int32 item; } }");
        primitives.addGroup("items").append("item", 1).append("item", 2).append("item", 3);
        Group fieldless = record("message m { optional group items { } }");
        fieldless.addGroup("items");

        assertThat(primitives.getGroup("items", 0).getFieldRepetitionCount("item")).isEqualTo(3);
        assertThat(ParquetGroups.groupList(primitives, "items")).isEmpty();
        assertThat(ParquetGroups.groupList(fieldless, "items")).isEmpty();
    }

    @Test
    void readParamsDecodesAnInt32BooleanArmAndKeepsParamsWithoutAValue() {
        Group g = record("message m { optional group parameters (LIST) { repeated group list {"
                + " optional group item {"
                + "   optional group value { optional int64 integer; optional double float;"
                + "     optional binary string (STRING); optional int32 boolean; }"
                + "   optional binary accession (STRING); optional binary name (STRING);"
                + "   optional binary unit (STRING); } } } }");
        Group wrapper = g.addGroup("parameters");
        Group yes = wrapper.addGroup("list").addGroup("item");
        yes.addGroup("value").append("boolean", 1);
        yes.append("accession", "MS:0000001").append("name", "flag on");
        Group no = wrapper.addGroup("list").addGroup("item");
        no.addGroup("value").append("boolean", 0);
        no.append("name", "flag off");
        Group bare = wrapper.addGroup("list").addGroup("item");
        bare.append("accession", "MS:0000002").append("name", "no value").append("unit", "UO:0000010");

        assertThat(ParquetGroups.readParams(g, "parameters")).containsExactly(
                new Param("flag on", "MS:0000001", Boolean.TRUE, null),
                new Param("flag off", null, Boolean.FALSE, null),
                new Param("no value", "MS:0000002", null, "UO:0000010"));
    }

    @Test
    void readParamsIgnoresABooleanArmThatIsAGroup() {
        Group g = record("message m { optional group parameters (LIST) { repeated group list {"
                + " optional group item {"
                + "   optional group value { optional int64 integer; optional group boolean { optional int32 v; } }"
                + "   optional binary name (STRING); } } } }");
        Group item = g.addGroup("parameters").addGroup("list").addGroup("item");
        item.addGroup("value").addGroup("boolean").append("v", 1);
        item.append("name", "odd boolean");

        assertThat(ParquetGroups.readParams(g, "parameters"))
                .containsExactly(new Param("odd boolean", null, null, null));
    }

    @Test
    void doubleListNullableReadsA2LevelRepeatedPrimitive() {
        Group g = record("message m { optional group values (LIST) { repeated float item; } }");
        g.addGroup("values").append("item", 1.5f).append("item", -2.25f).append("item", 0.1f);

        assertThat(ParquetGroups.doubleListNullable(g, "values")).containsExactly(1.5, -2.25, (double) 0.1f);
    }

    @Test
    void doubleListNullableOfAWrapperWithoutFieldsIsEmpty() {
        Group g = record("message m { optional group values { } }");
        g.addGroup("values");

        assertThat(ParquetGroups.doubleListNullable(g, "values")).isEmpty();
    }

    @Test
    void byteListReadsA2LevelRepeatedPrimitiveAsSignedBytes() {
        Group g = record("message m { optional group bytes (LIST) { repeated int32 item; } }");
        g.addGroup("bytes").append("item", 0).append("item", 127).append("item", 200).append("item", 255);

        assertThat(ParquetGroups.byteList(g, "bytes")).containsExactly(0, 127, -56, -1);
    }

    @Test
    void byteListOfAMissingFieldOrAWrapperWithoutFieldsIsEmpty() {
        Group absent = record("message m { optional group bytes (LIST) { repeated int32 item; } }");
        Group fieldless = record("message m { optional group bytes { } }");
        fieldless.addGroup("bytes");

        assertThat(ParquetGroups.byteList(absent, "bytes")).isEmpty();
        assertThat(ParquetGroups.byteList(absent, "no_such_field")).isEmpty();
        assertThat(ParquetGroups.byteList(fieldless, "bytes")).isEmpty();
    }
}
