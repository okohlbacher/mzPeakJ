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
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.Chromatogram;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AP-9: the per-point extra arrays and the intensity unit of chromatograms.
 *
 * <p>Characterization, pinned on the code before AP-9 (agent-team/upstream-p1 17e639f): the 5-argument
 * {@link Chromatogram} constructor, and the time and intensity arrays of constructed members whose array index lists
 * extra columns, several intensity units, unreadable and transform-only extras. The fixtures are pinned by
 * {@link ChromatogramValuesTest}, {@link SignalPlanWiringTest} (current set) and {@link SignalSnapshotTest} (legacy
 * set).
 */
class ChromatogramArraysTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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
