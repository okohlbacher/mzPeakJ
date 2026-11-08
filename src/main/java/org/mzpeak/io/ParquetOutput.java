package org.mzpeak.io;

import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.Param;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Parquet output shared by the {@link MzPeakWriter} components: opening a ZSTD-compressed writer with optional
 * footer key-values and the row-group size of the write, the footer key-values of a signal file, the UINT64
 * annotation, and the {@code parameters} and {@code scan_windows} list fields with the {@code parameters} encoder.
 */
final class ParquetOutput {

    private static final String SCHEMA_PROPERTY = "parquet.example.schema";
    private static final String ROW_GROUP_SIZE_PROPERTY = "mzpeak.writer.rowGroupSize";

    /** uint64 logical type annotation (unsigned 64-bit int, matching mzPeak spec for index/count fields). */
    static final LogicalTypeAnnotation UINT64 = LogicalTypeAnnotation.intType(64, false);

    private ParquetOutput() {
    }

    /** A reusable {@code parameters: large_list<item: struct<value-union, accession, name, unit>>} field schema. */
    static Type paramListField() {
        GroupType itemType = Types.buildGroup(Type.Repetition.OPTIONAL)
                .addField(Types.optionalGroup()
                        .optional(PrimitiveTypeName.INT64).named("integer")
                        .optional(PrimitiveTypeName.DOUBLE).named("float")
                        .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("string")
                        .optional(PrimitiveTypeName.BOOLEAN).named("boolean")
                        .named("value"))
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("accession")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("name")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("unit")
                .named("item");
        return Types.optionalGroup().as(LogicalTypeAnnotation.listType())
                .addField(Types.repeatedGroup().addField(itemType).named("list"))
                .named("parameters");
    }

    /** {@code scan_windows: large_list<item: struct<lower, upper>>} field schema (matches the reference fixtures). */
    static Type scanWindowListField() {
        GroupType itemType = Types.buildGroup(Type.Repetition.OPTIONAL)
                .optional(PrimitiveTypeName.DOUBLE).named("MS_1000501_scan_window_lower_limit_unit_MS_1000040")
                .optional(PrimitiveTypeName.DOUBLE).named("MS_1000500_scan_window_upper_limit_unit_MS_1000040")
                .named("item");
        return Types.optionalGroup().as(LogicalTypeAnnotation.listType())
                .addField(Types.repeatedGroup().addField(itemType).named("list"))
                .named("scan_windows");
    }

    static ParquetWriter<Group> openWriter(Path file, MessageType schema, Options options) throws IOException {
        return openWriter(file, schema, Map.of(), options);
    }

    /**
     * A ZSTD writer of {@code file} with {@code footerMetadata} as its footer key-values, which parquet-java fixes when
     * the writer is built. Every column chunk gets statistics (the parquet-java default, set explicitly because readers
     * prune row groups by the index column's min/max) and a page index, the column and offset index parquet-java
     * writes for every column chunk.
     */
    static ParquetWriter<Group> openWriter(Path file, MessageType schema,
                                           Map<String, String> footerMetadata, Options options) throws IOException {
        PlainParquetConfiguration conf = new PlainParquetConfiguration();
        conf.set(SCHEMA_PROPERTY, schema.toString());
        var builder = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(conf)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withStatisticsEnabled(true)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE);
        if (!footerMetadata.isEmpty()) {
            builder = builder.withExtraMetaData(footerMetadata);
        }
        OptionalLong rowGroupSize = rowGroupSize(options);
        if (rowGroupSize.isPresent()) {
            builder = builder.withRowGroupSize(rowGroupSize.getAsLong());
        }
        return builder.build();
    }

    /**
     * The footer key-values of a signal file (design §11.7, WR-2), computed from the plan before its writer opens:
     * {@code <entity>_array_index}, the file's {@code index} as JSON; {@code <entity>_count}, the {@code entityCount}
     * entities of that type in the archive; and {@code <entity>_data_point_count}, the {@code dataPointCount} points
     * the file holds. A file without rows gets them too.
     */
    static Map<String, String> signalFooter(ArrayIndex index, long entityCount, long dataPointCount) {
        Map<String, String> footer = new LinkedHashMap<>();
        footer.put(index.entity().arrayIndexKey(), index.toJson());
        footer.put(countKey(index.entity()), Long.toString(entityCount));
        footer.put(index.entity().key() + "_data_point_count", Long.toString(dataPointCount));
        return footer;
    }

    /** The footer key of the entity count, {@code <entity>_count}. */
    static String countKey(SignalEntity entity) {
        return entity.key() + "_count";
    }

    /**
     * The row-group size of every member: {@link Options#rowGroupSize()} if set, else the
     * {@code mzpeak.writer.rowGroupSize} system property if set (which lets tests force small row groups with
     * {@code -Dmzpeak.writer.rowGroupSize=N}), else empty for the parquet-java default.
     */
    static OptionalLong rowGroupSize(Options options) {
        if (options.rowGroupSize().isPresent()) {
            return options.rowGroupSize();
        }
        String property = System.getProperty(ROW_GROUP_SIZE_PROPERTY);
        return property == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(property));
    }

    /**
     * Write a {@code List<Param>} into a {@code parameters: large_list<item: struct<value-union,...>>} group.
     * Symmetric to {@link org.mzpeak.io.parquet.ParquetGroups#readParams}.
     */
    static void writeParams(Group owner, List<Param> params) {
        if (params.isEmpty()) {
            return;
        }
        Group wrapper = owner.addGroup("parameters");
        for (Param p : params) {
            Group slot = wrapper.addGroup("list");
            Group item = slot.addGroup("item");
            // value union
            Group valueGroup = item.addGroup("value");
            Object v = p.value();
            if (v instanceof Long l)    { valueGroup.add("integer", l); }
            else if (v instanceof Double d) { valueGroup.add("float", d); }
            else if (v instanceof String s) { valueGroup.add("string", s); }
            else if (v instanceof Boolean b) { valueGroup.add("boolean", b); }
            if (p.accession() != null) { item.add("accession", p.accession()); }
            if (p.name() != null)      { item.add("name", p.name()); }
            if (p.unit() != null)      { item.add("unit", p.unit()); }
        }
    }
}
