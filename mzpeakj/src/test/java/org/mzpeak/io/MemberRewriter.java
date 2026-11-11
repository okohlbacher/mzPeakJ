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
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test helper that rewrites one signal member with parquet-java's Group API ({@link #rewrite}): the values of the
 * named columns changed, such as scaled into another unit, and their array-index entries changed with them; every
 * other column and footer key kept. Moved out of {@link ArrayUnitConversionTest} for the chromatogram time unit tests
 * (V-21) unchanged.
 */
final class MemberRewriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MemberRewriter() {
    }

    /**
     * How {@link #rewrite} changes one column: its array-index entry gets {@code unit} (null for none) and, unless
     * null, {@code arrayType}; its values go through {@code values}, unless null.
     */
    record Edit(String unit, String arrayType, DoubleUnaryOperator values) {

        Edit(String unit, DoubleUnaryOperator values) {
            this(unit, null, values);
        }
    }

    /** {@link #rewrite} of the member file {@code member} in place. */
    static void rewriteInPlace(Path member, SignalEntity entity, Map<String, Edit> edits) {
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
    static void rewrite(InputFile in, Path out, SignalEntity entity, Map<String, Edit> edits) {
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
