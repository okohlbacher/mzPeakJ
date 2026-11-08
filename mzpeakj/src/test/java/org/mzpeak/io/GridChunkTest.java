package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GridChunk#read} on in-memory grid cells for the edge cases no fixture or written member reaches: a grid
 * without indices, a negative index in a list that is not INT32 (where only INT32 holds unsigned bit patterns), and an
 * indices column whose struct has no fields. The grid read of diaPASEF.grid and its errors are covered by
 * {@link GridDecodeTest}.
 */
class GridChunkTest {

    private static final String MZ_GRID = "mz_grid";

    @Test
    void aGridWithoutIndicesHasNone() {
        GridChunk grid = GridChunk.read(gridCell("required int32 element (INTEGER(32,false));", null), MZ_GRID, 0);

        assertThat(grid.field()).isEqualTo(MZ_GRID);
        assertThat(grid.gridType()).isEqualTo(GridModels.LINEAR);
        assertThat(grid.parameters()).containsExactly(95.0, 3.75e-7);
        assertThat(grid.indices()).isEmpty();
        assertThat(grid.cumulativeIndices(0)).isEmpty();
    }

    /** Only an INT32 list stores unsigned bit patterns; a negative INT64 or DOUBLE index is outside the range. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"required int64 element;", "required double element;"})
    void aNegativeIndexOfAListThatIsNotInt32IsOutsideTheRange(String element) {
        Group chunk = gridCell(element, indices -> {
            Group five = indices.addGroup("list");
            Group minusOne = indices.addGroup("list");
            if (element.contains("int64")) {
                five.add("element", 5L);
                minusOne.add("element", -1L);
            } else {
                five.add("element", 5.0);
                minusOne.add("element", -1.0);
            }
        });

        assertThatThrownBy(() -> GridChunk.read(chunk, MZ_GRID, 3))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("grid " + MZ_GRID + " has the index -1 at position 1, outside 0..4294967295,"
                        + " for spectrum_index 3");
    }

    /** An indices column whose struct has no fields has no element type and holds no indices; it is not an error. */
    @Test
    void anIndicesStructWithoutFieldsHoldsNoIndices() {
        GroupType grid = new GroupType(Type.Repetition.OPTIONAL, MZ_GRID,
                Types.required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("grid_type"),
                new GroupType(Type.Repetition.OPTIONAL, "indices", List.of()));
        MessageType schema = new MessageType("m", new GroupType(Type.Repetition.OPTIONAL, "chunk", grid));
        Group record = new SimpleGroupFactory(schema).newGroup();
        Group cell = record.addGroup("chunk").addGroup(MZ_GRID);
        cell.add("grid_type", GridModels.LINEAR);
        cell.addGroup("indices");

        GridChunk read = GridChunk.read(record.getGroup("chunk", 0), MZ_GRID, 0);

        assertThat(read.gridType()).isEqualTo(GridModels.LINEAR);
        assertThat(read.parameters()).isEmpty();
        assertThat(read.indices()).isEmpty();
    }

    /**
     * The chunk struct of a row whose {@code mz_grid} has the grid_type MS:1003824, the parameters {@code [95,
     * 3.75e-7]} and an indices list of {@code element}s added by {@code fill}, or no indices when it is null.
     */
    private static Group gridCell(String element, Consumer<Group> fill) {
        MessageType schema = MessageTypeParser.parseMessageType("message m { optional group chunk {"
                + " optional group " + MZ_GRID + " { required binary grid_type (STRING);"
                + " optional group parameters (LIST) { repeated group list { required double element; } }"
                + " optional group indices (LIST) { repeated group list { " + element + " } } } } }");
        Group record = new SimpleGroupFactory(schema).newGroup();
        Group grid = record.addGroup("chunk").addGroup(MZ_GRID);
        grid.add("grid_type", GridModels.LINEAR);
        Group parameters = grid.addGroup("parameters");
        parameters.addGroup("list").add("element", 95.0);
        parameters.addGroup("list").add("element", 3.75e-7);
        if (fill != null) {
            fill.accept(grid.addGroup("indices"));
        }
        return record.getGroup("chunk", 0);
    }
}
