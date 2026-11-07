package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.io.parquet.ParquetGroups;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Iterates the rows of one metadata facet in either layout, detected from the member's schema
 * ({@link MetadataShape}). In the split layout the member is the facet's own table; in the packed layout it is the
 * entity's metadata member, and the facet is its top-level struct {@link Facet#packedStruct()}.
 */
final class MetadataTable {

    private MetadataTable() {
    }

    /**
     * Passes every row of {@code facet} in {@code entry}'s member to {@code handler}, in file order.
     * <ul>
     *   <li>packed: the non-null structs whose row key is not null; placeholder rows are skipped;
     *   <li>split: every top-level row;
     *   <li>empty (neither shape, no rows): nothing.
     * </ul>
     *
     * @throws MzPeakException if the member has rows but neither shape (a split facet without its key column), if
     *                         a split row has a null key, or if a key has the high bit set
     */
    static void forEachRow(MzPeakSource source, Entry entry, Facet facet, Consumer<FacetRow> handler) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(facet, "facet");
        Objects.requireNonNull(handler, "handler");
        String member = Objects.requireNonNull(Objects.requireNonNull(entry, "entry").name(), "entry name");
        InputFile file = source.inputFile(member);
        ParquetFooter footer = ParquetFooter.read(file);
        MessageType schema = footer.schema();
        String key = FacetRow.keyColumn(schema, facet);
        switch (MetadataShape.detect(footer, facet.packedStruct(), key, member)) {
            case PACKED -> packed(file, schema.getType(facet.packedStruct()).asGroupType(), facet, handler);
            case SPLIT -> split(file, ColumnResolver.split(schema, entry, facet), facet, member, key, handler);
            case EMPTY -> {
            }
        }
    }

    private static void packed(InputFile file, GroupType struct, Facet facet, Consumer<FacetRow> handler) {
        ColumnResolver columns = ColumnResolver.packed(struct, facet);
        String key = FacetRow.keyColumn(struct, facet);
        ParquetGroups.forEach(file, row -> {
            Group g = ParquetGroups.optGroup(row, struct.getName());
            if (g == null) {
                return;
            }
            FacetRow facetRow = new FacetRow(facet, g, columns);
            if (facetRow.optKey(key) != null) {
                handler.accept(facetRow);
            }
        });
    }

    private static void split(InputFile file, ColumnResolver columns, Facet facet, String member, String key,
                              Consumer<FacetRow> handler) {
        long[] ordinal = {0};
        ParquetGroups.forEach(file, row -> {
            FacetRow facetRow = new FacetRow(facet, row, columns);
            if (facetRow.optKey(key) == null) {
                throw new MzPeakException(member + " row " + ordinal[0] + ": " + key
                        + " is null; a split metadata key must not be null");
            }
            ordinal[0]++;
            handler.accept(facetRow);
        });
    }
}
