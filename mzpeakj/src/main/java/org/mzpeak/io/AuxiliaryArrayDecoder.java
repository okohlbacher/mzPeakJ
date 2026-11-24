package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.AuxiliaryArray;
import org.mzpeak.model.Param;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.InflaterInputStream;

/**
 * Decodes the {@code auxiliary_arrays} column of a metadata row (docs/layouts/auxiliary-arrays.md):
 * {@code list<struct<data, name, data_type, compression, items, unit, parameters, data_processing_id>>}, an Arrow
 * {@code list} or {@code large_list}, both of which Parquet stores as a LIST group. The bytes of {@code data} are kept
 * as stored, compressed or not; {@link AuxiliaryArray#doubles()} decodes them.
 *
 * <p>An item without {@code items}, as mzpeak-convert 0.17.2 writes them (its struct has no such field), gets the
 * count derived from its data: the stored length, or for zlib the inflated length, over the width of its fixed-width
 * data type. An item whose count cannot be derived is skipped with a WARNING.
 */
final class AuxiliaryArrayDecoder {

    static final String F_AUXILIARY_ARRAYS = "auxiliary_arrays";

    /**
     * The most bytes a derived count may cover: {@link AuxiliaryArray#doubles()} inflates {@code items * width} bytes
     * and one more into a Java array, so {@code items * width} must stay below {@link Integer#MAX_VALUE}.
     */
    static final int MAX_DERIVED_BYTES = Integer.MAX_VALUE - 1;

    private static final System.Logger LOG = System.getLogger(AuxiliaryArrayDecoder.class.getName());

    private AuxiliaryArrayDecoder() {
    }

    /**
     * The auxiliary arrays of {@code row}, in list order; empty when the row has no such column or a null or empty
     * list. Null list items are skipped. An item without {@code items} gets the count derived from its data (see the
     * class comment), or is skipped with a WARNING when the count cannot be derived.
     *
     * @param member the name of the member in errors
     * @param entity the row's entity and index in errors, such as {@code spectrum 0}
     * @throws MzPeakException when an item lacks a field the spec requires ({@code name} with its {@code name},
     *                         {@code data}, {@code data_type}, {@code compression}), its {@code items} is out of
     *                         range, or it has no {@code items} and its data are not a whole number of values of its
     *                         data type; the message names the member, the entity, the item and the field
     */
    static List<AuxiliaryArray> decode(Group row, String member, String entity) {
        return decode(row, member, entity, MAX_DERIVED_BYTES);
    }

    /** {@link #decode(Group, String, String)}, deriving counts that cover at most {@code maxDerivedBytes} bytes. */
    static List<AuxiliaryArray> decode(Group row, String member, String entity, int maxDerivedBytes) {
        List<Group> items = ParquetGroups.groupList(row, F_AUXILIARY_ARRAYS);
        List<AuxiliaryArray> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            Group item = items.get(i);
            String where = member + ": auxiliary array " + i + " of " + entity;
            Param name = param(require(ParquetGroups.optGroup(item, "name"), where, "name"));
            require(name.name(), where, "name.name");
            byte[] data = require(ParquetGroups.has(item, "data") ? ParquetGroups.byteList(item, "data") : null,
                    where, "data");
            String dataType = require(ParquetGroups.optString(item, "data_type"), where, "data_type");
            String compression = require(ParquetGroups.optString(item, "compression"), where, "compression");
            Long count = ParquetGroups.optIndexKey(item, "items", where);
            if (count == null) {
                count = derivedItems(data, dataType, compression, where, maxDerivedBytes);
                if (count == null) {
                    continue;
                }
            }
            out.add(new AuxiliaryArray(name, data, dataType, compression, count,
                    ParquetGroups.optString(item, "unit"), ParquetGroups.readParams(item, "parameters"),
                    ParquetGroups.optString(item, "data_processing_id")));
        }
        return List.copyOf(out);
    }

    /**
     * The param struct {@code g} ({@code value} union, {@code accession}, {@code name}, {@code unit}), as
     * {@link ParquetGroups#readParams} reads each item of a {@code parameters} list: the first non-null arm of the
     * value union is the value.
     */
    private static Param param(Group g) {
        Object value = null;
        Group v = ParquetGroups.optGroup(g, "value");
        if (v != null) {
            Long integer = ParquetGroups.optLong(v, "integer");
            Double real = ParquetGroups.optDouble(v, "float");
            String string = ParquetGroups.optString(v, "string");
            Boolean bool = ParquetGroups.has(v, "boolean") ? v.getBoolean("boolean", 0) : null;
            value = integer != null ? integer : real != null ? real : string != null ? string : bool;
        }
        return new Param(ParquetGroups.optString(g, "name"), ParquetGroups.optString(g, "accession"), value,
                ParquetGroups.optString(g, "unit"));
    }

    /**
     * The number of values in {@code data} of an item without {@code items}: the stored length (MS:1000576, no
     * compression) or the inflated length (MS:1000574, zlib) over the width of the fixed-width {@code dataType}.
     * {@code null}, after a WARNING naming the item, when it cannot be derived: a data type without a fixed width, a
     * compression other than these two, zlib data that cannot be inflated, or more than {@code maxBytes} bytes. zlib
     * data are inflated through a fixed buffer, at most {@code maxBytes} bytes and one more, and not kept.
     *
     * @throws MzPeakException when the length is not a multiple of the width
     */
    private static Long derivedItems(byte[] data, String dataType, String compression, String where, int maxBytes) {
        int width;
        switch (dataType) {
            case "MS:1000521", "MS:1000519" -> width = Integer.BYTES;
            case "MS:1000523", "MS:1000522" -> width = Long.BYTES;
            default -> {
                return skipped(where, "its data type " + dataType + " has no fixed width");
            }
        }
        long length;
        switch (compression) {
            case "MS:1000576" -> length = data.length;
            case "MS:1000574" -> {
                try {
                    length = inflatedLength(data, maxBytes);
                } catch (IOException e) {
                    return skipped(where, "its zlib data cannot be inflated: " + e.getMessage());
                }
            }
            default -> {
                return skipped(where, "its compression " + compression + " cannot be decoded");
            }
        }
        if (length > maxBytes) {
            return skipped(where, "its data are more than " + maxBytes + " bytes");
        }
        if (length % width != 0) {
            throw new MzPeakException(where + " has no items, and its " + length + " bytes of data are not a whole"
                    + " number of values of " + dataType + " (" + width + " bytes each)");
        }
        return length / width;
    }

    /** The inflated length of the zlib {@code data}, counted up to {@code maxBytes} and one more. */
    private static long inflatedLength(byte[] data, int maxBytes) throws IOException {
        long length = 0;
        byte[] buffer = new byte[8192];
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(data))) {
            int n;
            while (length <= maxBytes && (n = in.read(buffer)) != -1) {
                length += n;
            }
        }
        return length;
    }

    private static Long skipped(String where, String reason) {
        LOG.log(Level.WARNING, () -> where + " has no items, and its count cannot be derived (" + reason
                + "); the array is skipped");
        return null;
    }

    private static <T> T require(T value, String where, String field) {
        if (value == null) {
            throw new MzPeakException(where + " has no " + field + ", which the spec requires");
        }
        return value;
    }
}
