package org.mzpeak.io.parquet;

import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Hadoop-free Parquet record reading via {@link LocalInputFile} + {@link PlainParquetConfiguration} +
 * the parquet-column {@link Group} API, with a {@link ZstdCompressionCodecFactory} so the Hadoop codec
 * machinery (and its {@code Configuration} dependency) is never touched.
 *
 * <p>Also provides null- and type-variance-aware leaf accessors: mzPeak writers may emit a numeric column
 * as FLOAT or DOUBLE (and integer widths vary), so callers must read via these helpers rather than assuming
 * a physical type.
 */
public final class ParquetGroups {

    private ParquetGroups() {
    }

    private static ParquetReadOptions readOptions() {
        return ParquetReadOptions.builder(new PlainParquetConfiguration())
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .build();
    }

    /** Stream every record in {@code file} to {@code handler}. */
    public static void forEach(Path file, Consumer<Group> handler) {
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(file), readOptions())) {
            MessageType schema = reader.getFooter().getFileMetaData().getSchema();
            ColumnIOFactory factory = new ColumnIOFactory();
            PageReadStore pages;
            while ((pages = reader.readNextRowGroup()) != null) {
                MessageColumnIO columnIO = factory.getColumnIO(schema);
                RecordReader<Group> recordReader = columnIO.getRecordReader(pages, new GroupRecordConverter(schema));
                long rows = pages.getRowCount();
                for (long i = 0; i < rows; i++) {
                    handler.accept(recordReader.read());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading Parquet file " + file, e);
        }
    }

    /** Read all records into a list. Use only for small tables (e.g. metadata). */
    public static List<Group> readAll(Path file) {
        List<Group> out = new ArrayList<>();
        forEach(file, out::add);
        return out;
    }

    // ---- null / type-variance-aware leaf accessors -------------------------------------------------

    /** True if {@code field} exists in the group's schema and is present (non-null) in this record. */
    public static boolean has(Group g, String field) {
        GroupType type = g.getType();
        return type.containsField(field) && g.getFieldRepetitionCount(field) > 0;
    }

    public static Group optGroup(Group g, String field) {
        return has(g, field) ? g.getGroup(field, 0) : null;
    }

    public static String optString(Group g, String field) {
        return has(g, field) ? g.getString(field, 0) : null;
    }

    /** Read a numeric leaf as double regardless of whether it is stored as FLOAT/DOUBLE/INT32/INT64. */
    public static Double optDouble(Group g, String field) {
        if (!has(g, field)) {
            return null;
        }
        PrimitiveTypeName p = g.getType().getType(field).asPrimitiveType().getPrimitiveTypeName();
        return switch (p) {
            case DOUBLE -> g.getDouble(field, 0);
            case FLOAT -> (double) g.getFloat(field, 0);
            case INT32 -> (double) g.getInteger(field, 0);
            case INT64 -> (double) g.getLong(field, 0);
            default -> throw new IllegalStateException("Field " + field + " is not numeric: " + p);
        };
    }

    /** Read an integer leaf as long (INT64 or INT32). */
    public static Long optLong(Group g, String field) {
        if (!has(g, field)) {
            return null;
        }
        PrimitiveTypeName p = g.getType().getType(field).asPrimitiveType().getPrimitiveTypeName();
        return switch (p) {
            case INT64 -> g.getLong(field, 0);
            case INT32 -> (long) g.getInteger(field, 0);
            default -> throw new IllegalStateException("Field " + field + " is not an integer: " + p);
        };
    }

    /** Read an integer leaf as int (INT32 or INT64). */
    public static Integer optInt(Group g, String field) {
        Long v = optLong(g, field);
        return v == null ? null : Math.toIntExact(v);
    }

    /** Number of repeated elements for a list/repeated field (0 if absent). */
    public static int repetitionCount(Group g, String field) {
        return g.getType().containsField(field) ? g.getFieldRepetitionCount(field) : 0;
    }
}
