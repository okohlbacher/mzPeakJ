package org.mzpeak.io;

import org.apache.parquet.schema.MessageType;

/**
 * Layout of a metadata member, detected from the member's own schema. It never comes from {@code metadata.version},
 * which is 0.9.0 on both sides of the layout change, nor from which facet members the manifest lists. (Named so as
 * not to clash with the writer's {@code MzPeakWriter.MetadataLayout}.)
 */
enum MetadataShape {

    /** Pin-era packed parallel tables: a top-level struct per facet, such as {@code spectrum} or {@code scan}. */
    PACKED,

    /** One table per facet with top-level columns, keyed by a primitive {@code index} or {@code source_index}. */
    SPLIT,

    /** Neither shape, and no rows: a legal empty member that decodes to nothing. */
    EMPTY;

    /**
     * The shape of a metadata member: {@link #PACKED} when the schema has a top-level group {@code packedStruct},
     * else {@link #SPLIT} when it has a top-level primitive {@code keyColumn}, else {@link #EMPTY} when the member
     * has no rows.
     *
     * @param member names the member in the error message
     * @throws MzPeakException when the member has rows but neither shape; the message names the member, the
     *                         expected struct and the top-level columns found
     */
    static MetadataShape detect(ParquetFooter footer, String packedStruct, String keyColumn, String member) {
        MessageType schema = footer.schema();
        if (schema.containsField(packedStruct) && !schema.getType(packedStruct).isPrimitive()) {
            return PACKED;
        }
        if (schema.containsField(keyColumn) && schema.getType(keyColumn).isPrimitive()) {
            return SPLIT;
        }
        if (footer.rowCount() == 0) {
            return EMPTY;
        }
        throw new MzPeakException(member + " has " + footer.rowCount() + " rows but neither the packed shape"
                + " (top-level struct '" + packedStruct + "') nor the split shape (top-level column '" + keyColumn
                + "'); columns found: " + footer.topLevelFields());
    }
}
