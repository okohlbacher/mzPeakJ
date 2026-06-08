package org.mzpeak.io.parquet;

import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
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

    /** Hadoop-free read options with the custom ZSTD codec factory (no Hadoop Configuration is built). */
    public static ParquetReadOptions readOptions() {
        return ParquetReadOptions.builder(new PlainParquetConfiguration())
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .build();
    }

    /** Stream every record in {@code file} to {@code handler}. */
    public static void forEach(Path file, Consumer<Group> handler) {
        forEach(new LocalInputFile(file), handler);
    }

    /** Stream every record in {@code input} to {@code handler}. */
    public static void forEach(InputFile input, Consumer<Group> handler) {
        try (ParquetFileReader reader = ParquetFileReader.open(input, readOptions())) {
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
            throw new UncheckedIOException("Failed reading Parquet input", e);
        }
    }

    /** The Parquet footer key-value metadata map (file/run-level JSON documents live here). */
    public static java.util.Map<String, String> footerKeyValue(InputFile input) {
        try (ParquetFileReader reader = ParquetFileReader.open(input, readOptions())) {
            return reader.getFooter().getFileMetaData().getKeyValueMetaData();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading Parquet footer", e);
        }
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

    /** Read an integer leaf as long (INT64, INT32, or numeric FLOAT/DOUBLE coerced to long). */
    public static Long optLong(Group g, String field) {
        if (!has(g, field)) {
            return null;
        }
        PrimitiveTypeName p = g.getType().getType(field).asPrimitiveType().getPrimitiveTypeName();
        return switch (p) {
            case INT64 -> g.getLong(field, 0);
            case INT32 -> (long) g.getInteger(field, 0);
            // Some vendor writers store integer fields as FLOAT/DOUBLE — coerce safely.
            case FLOAT -> {
                float f = g.getFloat(field, 0);
                yield Float.isFinite(f) ? (long) f : null;
            }
            case DOUBLE -> {
                double d = g.getDouble(field, 0);
                yield Double.isFinite(d) ? (long) d : null;
            }
            default -> throw new IllegalStateException("Field " + field + " is not an integer: " + p);
        };
    }

    /**
     * Read an integer leaf as int. NaN/Infinite float/double values coerce to {@code null}
     * rather than propagating — matches upstream fix for charge state fields.
     */
    public static Integer optInt(Group g, String field) {
        Long v = optLong(g, field);
        return v == null ? null : v.intValue();
    }

    /**
     * Read a (large_)list-of-structs column as a Java {@code List<Group>}. Handles both the standard
     * 3-level Parquet LIST encoding ({@code list → element}) and a 2-level layout, navigating by field position
     * so wrapper element names ({@code list}/{@code item}/{@code element}) are irrelevant.
     */
    public static java.util.List<Group> groupList(Group g, String field) {
        if (!has(g, field)) {
            return java.util.List.of();
        }
        Group wrapper = g.getGroup(field, 0);
        if (wrapper.getType().getFieldCount() == 0) {
            return java.util.List.of();
        }
        String repName = wrapper.getType().getFields().get(0).getName();
        int n = wrapper.getFieldRepetitionCount(repName);
        java.util.List<Group> out = new java.util.ArrayList<>(n);
        boolean repIsPrimitive = wrapper.getType().getType(0).isPrimitive();
        for (int i = 0; i < n; i++) {
            if (repIsPrimitive) {
                continue; // not a group — skip
            }
            Group slot = wrapper.getGroup(repName, i);
            if (slot.getType().getFieldCount() == 1 && !slot.getType().getType(0).isPrimitive()) {
                // 3-level list: slot wraps one element group
                String elemName = slot.getType().getFields().get(0).getName();
                if (slot.getFieldRepetitionCount(elemName) > 0) {
                    out.add(slot.getGroup(elemName, 0));
                }
            } else {
                out.add(slot); // 2-level list: slot is already the element
            }
        }
        return out;
    }

    /**
     * Read a {@code parameters: large_list<struct{value{integer,float,string,boolean}, accession, name, unit}>}
     * column into a list of {@link org.mzpeak.model.Param}. The value union has four optional slots; the first
     * non-null wins.
     */
    public static java.util.List<org.mzpeak.model.Param> readParams(Group g, String field) {
        java.util.List<Group> items = groupList(g, field);
        java.util.List<org.mzpeak.model.Param> out = new java.util.ArrayList<>(items.size());
        for (Group item : items) {
            String name = optString(item, "name");
            String accession = optString(item, "accession");
            String unit = optString(item, "unit");
            Object value = null;
            Group valueGroup = optGroup(item, "value");
            if (valueGroup != null) {
                // The value union has four optional arms; pick the first non-null one.
                // boolean is stored as INT32 (0/1) in the Parquet Group encoding.
                Long iVal = optLong(valueGroup, "integer");
                Double fVal = iVal == null ? optDouble(valueGroup, "float") : null;
                String sVal = (iVal == null && fVal == null) ? optString(valueGroup, "string") : null;
                Boolean bVal = null;
                if (iVal == null && fVal == null && sVal == null
                        && has(valueGroup, "boolean") && valueGroup.getType().getType("boolean").isPrimitive()) {
                    bVal = valueGroup.getInteger("boolean", 0) != 0;
                }
                value = iVal != null ? iVal : fVal != null ? fVal : sVal != null ? sVal : bVal;
            }
            out.add(new org.mzpeak.model.Param(name, accession, value, unit));
        }
        return out;
    }

    private static final double[] EMPTY_DOUBLE = new double[0];

    /**
     * Read a (large_)list numeric column as {@code double[]}, widening FLOAT to double and preserving nulls as
     * {@code NaN} (chunk {@code mz_chunk_values} use null markers for dropped flanking points). Handles both
     * the 3-level LIST encoding ({@code list -> element}) and a 2-level repeated primitive, navigating by field
     * position so element names ({@code element}/{@code item}) don't matter.
     */
    public static double[] doubleListNullable(Group g, String field) {
        if (!has(g, field)) {
            return EMPTY_DOUBLE;
        }
        Group wrapper = g.getGroup(field, 0);
        if (wrapper.getType().getFieldCount() == 0) {
            return EMPTY_DOUBLE;
        }
        String repName = wrapper.getType().getFields().get(0).getName();
        boolean primitiveElements = wrapper.getType().getType(0).isPrimitive();
        int n = wrapper.getFieldRepetitionCount(repName);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            if (primitiveElements) {
                out[i] = primitiveAsDouble(wrapper, repName, i);
            } else {
                Group element = wrapper.getGroup(repName, i);
                String valName = element.getType().getFields().get(0).getName();
                out[i] = element.getFieldRepetitionCount(valName) > 0
                        ? primitiveAsDouble(element, valName, 0) : Double.NaN;
            }
        }
        return out;
    }

    private static final byte[] EMPTY_BYTE = new byte[0];

    /** Read a (large_)list of bytes (uint8, stored as INT32) as {@code byte[]} — used for Numpress buffers. */
    public static byte[] byteList(Group g, String field) {
        if (!has(g, field)) {
            return EMPTY_BYTE;
        }
        Group wrapper = g.getGroup(field, 0);
        if (wrapper.getType().getFieldCount() == 0) {
            return EMPTY_BYTE;
        }
        String repName = wrapper.getType().getFields().get(0).getName();
        boolean primitiveElements = wrapper.getType().getType(0).isPrimitive();
        int n = wrapper.getFieldRepetitionCount(repName);
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            if (primitiveElements) {
                out[i] = (byte) wrapper.getInteger(repName, i);
            } else {
                Group element = wrapper.getGroup(repName, i);
                String valName = element.getType().getFields().get(0).getName();
                out[i] = (byte) element.getInteger(valName, 0);
            }
        }
        return out;
    }

    private static double primitiveAsDouble(Group g, String field, int index) {
        PrimitiveTypeName p = g.getType().getType(field).asPrimitiveType().getPrimitiveTypeName();
        return switch (p) {
            case DOUBLE -> g.getDouble(field, index);
            case FLOAT -> (double) g.getFloat(field, index);
            case INT32 -> (double) g.getInteger(field, index);
            case INT64 -> (double) g.getLong(field, index);
            default -> throw new IllegalStateException("List element " + field + " is not numeric: " + p);
        };
    }
}
