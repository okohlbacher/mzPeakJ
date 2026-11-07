package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.schema.GroupType;
import org.mzpeak.io.parquet.ParquetGroups;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One row of a metadata facet (a split table row or a packed struct), or a struct or list element inside it,
 * together with the {@link ColumnResolver} of its scope.
 *
 * @param facet   the facet the row belongs to
 * @param group   the row's cells
 * @param columns the resolver of the row's scope
 */
record FacetRow(Facet facet, Group group, ColumnResolver columns) {

    private static final String SOURCE_INDEX = "source_index";
    /** Accepted in place of {@code source_index} when a facet scope lacks it. */
    private static final String SPECTRUM_INDEX = "spectrum_index";

    FacetRow {
        Objects.requireNonNull(facet, "facet");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(columns, "columns");
    }

    /**
     * The row key that ties the row to its entity: {@code index} in a primary facet, else {@code source_index}
     * (or its alias {@code spectrum_index}).
     *
     * @throws MzPeakException if the key is absent, null or has the high bit set
     */
    long key() {
        String field = keyColumn(group.getType(), facet);
        Long key = optKey(field);
        if (key == null) {
            throw new MzPeakException(columns.member() + ": " + facet + " row has no " + field);
        }
        return key;
    }

    /**
     * The key or index column {@code field} (such as {@code precursor_index}), or {@code null} when it is absent or
     * null in this row.
     *
     * @throws MzPeakException if the value has the high bit set
     */
    Long optKey(String field) {
        Long value = columns.longValue(group, field);
        if (value != null && value < 0) {
            throw new MzPeakException(columns.member() + ": " + facet + " key " + field + " is " + value
                    + ", beyond the supported range (high bit set)");
        }
        return value;
    }

    /** The struct {@code struct} of this row, or {@code null} when it is absent or null. */
    FacetRow child(String struct) {
        Group g = ParquetGroups.optGroup(group, struct);
        return g == null ? null : new FacetRow(facet, g, columns.child(struct));
    }

    /** The element structs of the list {@code listField} of this row, in list order; empty when absent or null. */
    List<FacetRow> children(String listField) {
        List<Group> elements = ParquetGroups.groupList(group, listField);
        if (elements.isEmpty()) {
            return List.of();
        }
        ColumnResolver elementColumns = columns.child(listField);
        List<FacetRow> out = new ArrayList<>(elements.size());
        for (Group element : elements) {
            out.add(new FacetRow(facet, element, elementColumns));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * The name of {@code facet}'s row key in {@code scope}: its first {@link Facet#keyColumns() key column}, with
     * {@code spectrum_index} in place of a missing {@code source_index}.
     */
    static String keyColumn(GroupType scope, Facet facet) {
        String key = facet.keyColumns().get(0);
        if (SOURCE_INDEX.equals(key) && !scope.containsField(SOURCE_INDEX) && scope.containsField(SPECTRUM_INDEX)) {
            return SPECTRUM_INDEX;
        }
        return key;
    }
}
