package org.mzpeak.io;

import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
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

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Parquet output shared by the {@link MzPeakWriter} components: opening a ZSTD-compressed writer with optional
 * footer key-values and the row-group size of the write, a signal file with its footer key-values, the UINT64
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
        return openWriter(file, schema, footerMetadata, new CountingGroupWriteSupport(), options);
    }

    /**
     * {@link #openWriter(Path, MessageType, Map, Options)} writing through {@code writeSupport}, whose counts are added
     * to the footer key-values when the writer closes.
     */
    private static ParquetWriter<Group> openWriter(Path file, MessageType schema, Map<String, String> footerMetadata,
                                                   CountingGroupWriteSupport writeSupport, Options options)
            throws IOException {
        PlainParquetConfiguration conf = new PlainParquetConfiguration();
        conf.set(SCHEMA_PROPERTY, schema.toString());
        var builder = new GroupWriterBuilder(new LocalOutputFile(file), writeSupport)
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
     * A signal file being written (design §11.7, WR-2; design §7, MZ-10): its rows, and its footer key-values,
     * {@code <entity>_array_index}, the file's array index as JSON, given when the writer opens, and the counts given
     * when it closes, once its last row is written: {@code <entity>_count}, the entities of that type in the archive
     * ({@link #setEntityCount}), and {@code <entity>_data_point_count}, the points the file holds ({@link #addPoints}).
     * A file without rows gets them too.
     */
    static final class SignalFile implements Closeable {

        private final SignalEntity entity;
        private final CountingGroupWriteSupport counts = new CountingGroupWriteSupport();
        private final SimpleGroupFactory rows;
        private final ParquetWriter<Group> writer;
        private long entityCount;
        private long points;

        /** Opens {@code file} with {@code schema}, whose array index is {@code index}. */
        SignalFile(Path file, MessageType schema, ArrayIndex index, Options options) throws IOException {
            entity = index.entity();
            rows = new SimpleGroupFactory(schema);
            writer = openWriter(file, schema, Map.of(entity.arrayIndexKey(), index.toJson()), counts, options);
        }

        /** A new, empty row of the file's schema. */
        Group newRow() {
            return rows.newGroup();
        }

        /** Writes {@code row}. */
        void write(Group row) throws IOException {
            writer.write(row);
        }

        /** Adds {@code n} to the points the file holds. */
        void addPoints(long n) {
            points += n;
        }

        /** Sets the entities of the file's type in the archive. */
        void setEntityCount(long n) {
            entityCount = n;
        }

        /** Closes the file, with its counts as footer key-values. */
        @Override
        public void close() throws IOException {
            counts.setCount(countKey(entity), entityCount);
            counts.setCount(entity.key() + "_data_point_count", points);
            writer.close();
        }
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
