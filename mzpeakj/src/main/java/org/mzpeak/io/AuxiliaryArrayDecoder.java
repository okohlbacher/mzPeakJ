package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.AuxiliaryArray;
import org.mzpeak.model.Param;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the {@code auxiliary_arrays} column of a metadata row (docs/layouts/auxiliary-arrays.md):
 * {@code list<struct<data, name, data_type, compression, items, unit, parameters, data_processing_id>>}, an Arrow
 * {@code list} or {@code large_list}, both of which Parquet stores as a LIST group. The bytes of {@code data} are kept
 * as stored, compressed or not; {@link AuxiliaryArray#doubles()} decodes them.
 */
final class AuxiliaryArrayDecoder {

    static final String F_AUXILIARY_ARRAYS = "auxiliary_arrays";

    private AuxiliaryArrayDecoder() {
    }

    /**
     * The auxiliary arrays of {@code row}, in list order; empty when the row has no such column or a null or empty
     * list. Null list items are skipped.
     *
     * @param member the name of the member in errors
     * @param entity the row's entity and index in errors, such as {@code spectrum 0}
     * @throws MzPeakException when an item lacks a field the spec requires ({@code name} with its {@code name},
     *                         {@code data}, {@code data_type}, {@code compression}, {@code items}), or its
     *                         {@code items} is out of range; the message names the member, the entity, the item and
     *                         the field
     */
    static List<AuxiliaryArray> decode(Group row, String member, String entity) {
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
            Long count = require(ParquetGroups.optIndexKey(item, "items", where), where, "items");
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

    private static <T> T require(T value, String where, String field) {
        if (value == null) {
            throw new MzPeakException(where + " has no " + field + ", which the spec requires");
        }
        return value;
    }
}
