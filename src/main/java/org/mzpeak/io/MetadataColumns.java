package org.mzpeak.io;

import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.IntLogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;
import org.mzpeak.io.ColumnStatistics.RowGroup;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.io.parquet.ParquetGroups;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Finds the metadata column that holds a term, by the role of its facet and its accession, in either layout, and
 * reads its Parquet row-group statistics from the member's footer.
 *
 * <p>The member is the facet's own one when the manifest lists it, else the entity's metadata member, which holds
 * every facet in the packed layout. The member's own schema decides the layout ({@link MetadataShape}), and so the
 * scope searched: the top level of a split table, or the facet's struct in a packed member. The column comes from
 * {@link ColumnResolver#resolve}; in the packed layout only, a column that it does not know is also found by its
 * CURIE prefix ({@code MS_1000528_} for {@code MS:1000528}) when exactly one field of the scope has that prefix,
 * which covers name variants of packed writers. Only fields directly in the scope are found, never ones in a nested
 * struct or list.
 */
final class MetadataColumns {

    /**
     * A resolved column.
     *
     * @param member the member that holds it
     * @param column its dotted path in the member: the field in the split layout, struct and field in the packed one
     */
    record Resolved(String member, String column) {
    }

    /** A resolved column with what reading its statistics needs. */
    private record Located(String member, String[] path, PrimitiveType type, ParquetMetadata footer) {

        String column() {
            return String.join(".", path);
        }
    }

    private MetadataColumns() {
    }

    /**
     * The column that holds the term {@code accession} in the facet {@code (entity, kind)}; reads one footer.
     *
     * @return empty for the data arrays and peaks members, for a role without a metadata facet, when the manifest
     *         lists neither the facet's member nor the entity's metadata member, when the member does not hold the
     *         facet, and when no scalar field of the facet holds the term
     * @throws MzPeakException if the member has rows but neither metadata shape
     */
    static Optional<Resolved> resolve(MzPeakManifest manifest, MzPeakSource source, EntityType entity,
                                      DataKind kind, String accession) {
        return locate(manifest, source, entity, kind, accession)
                .map(c -> new Resolved(c.member(), c.column()));
    }

    /**
     * The row-group statistics of the column {@link #resolve} finds, read from the member's footer alone.
     *
     * @return empty when {@link #resolve} is, and when the column is not an INT32, INT64, FLOAT or DOUBLE column (an
     *         integer column annotated as anything but an integer counts as neither)
     * @throws MzPeakException if the member has rows but neither metadata shape, or an unsigned 64-bit statistic has
     *                         the high bit set
     */
    static Optional<ColumnStatistics> statistics(MzPeakManifest manifest, MzPeakSource source, EntityType entity,
                                                 DataKind kind, String accession) {
        Optional<Located> located = locate(manifest, source, entity, kind, accession);
        if (located.isEmpty() || !numeric(located.get().type())) {
            return Optional.empty();
        }
        Located c = located.get();
        List<RowGroup> rowGroups = new ArrayList<>();
        for (BlockMetaData block : c.footer().getBlocks()) {
            rowGroups.add(rowGroup(block, c));
        }
        return Optional.of(new ColumnStatistics(c.member(), c.column(), accession, rowGroups));
    }

    // ---- resolution -------------------------------------------------------------------------------------------

    private static Optional<Located> locate(MzPeakManifest manifest, MzPeakSource source, EntityType entity,
                                            DataKind kind, String accession) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(accession, "accession");
        Facet facet = facet(entity, kind);
        Facet primary = facet(entity, DataKind.METADATA);
        if (kind == DataKind.DATA_ARRAYS || kind == DataKind.PEAKS || facet == null || primary == null) {
            return Optional.empty();
        }
        Optional<Entry> own = named(manifest.find(entity, kind));
        Optional<Entry> entry = own.isPresent() ? own : named(manifest.find(entity, DataKind.METADATA));
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        String member = entry.get().name();
        ParquetMetadata footer = footer(source, member);
        MessageType schema = footer.getFileMetaData().getSchema();
        long rows = 0;
        for (BlockMetaData block : footer.getBlocks()) {
            rows += block.getRowCount();
        }
        // the entity's metadata member standing in for a facet member has the shape of the entity's own table
        Facet shapeOf = own.isPresent() ? facet : primary;
        MetadataShape shape = MetadataShape.detect(
                new ParquetFooter(schema, rows, footer.getFileMetaData().getKeyValueMetaData()),
                shapeOf.packedStruct(), FacetRow.keyColumn(schema, shapeOf), member);
        GroupType scope;
        String field;
        String[] path;
        switch (shape) {
            case PACKED -> {
                String struct = facet.packedStruct();
                if (!schema.containsField(struct) || schema.getType(struct).isPrimitive()) {
                    return Optional.empty();
                }
                scope = schema.getType(struct).asGroupType();
                field = ColumnResolver.packed(scope, facet).resolve(accession);
                if (field == null) {
                    field = uniqueWithPrefix(scope, accession.replace(':', '_') + "_");
                }
                path = new String[] {struct, field};
            }
            case SPLIT -> {
                if (own.isEmpty()) {
                    // a split dataset whose manifest does not list the facet's member: the facet has no column
                    return Optional.empty();
                }
                scope = schema;
                field = ColumnResolver.split(schema, entry.get(), facet).resolve(accession);
                path = new String[] {field};
            }
            default -> {
                return Optional.empty();
            }
        }
        if (field == null) {
            return Optional.empty();
        }
        Type type = scope.getType(field);
        if (!type.isPrimitive() || type.isRepetition(Type.Repetition.REPEATED)) {
            return Optional.empty();
        }
        return Optional.of(new Located(member, path, type.asPrimitiveType(), footer));
    }

    /** The metadata facet with the role {@code (entity, kind)}, or {@code null}. */
    private static Facet facet(EntityType entity, DataKind kind) {
        for (Facet f : Facet.values()) {
            if (f.entityType().equals(entity.canonical()) && f.dataKind().equals(kind.canonical())) {
                return f;
            }
        }
        return null;
    }

    private static Optional<Entry> named(Optional<Entry> entry) {
        return entry.filter(e -> e.name() != null);
    }

    /** The only primitive field of {@code scope} whose name starts with {@code prefix}, or {@code null}. */
    private static String uniqueWithPrefix(GroupType scope, String prefix) {
        String found = null;
        for (Type t : scope.getFields()) {
            if (t.isPrimitive() && t.getName().startsWith(prefix)) {
                if (found != null) {
                    return null;
                }
                found = t.getName();
            }
        }
        return found;
    }

    private static ParquetMetadata footer(MzPeakSource source, String member) {
        try (ParquetFileReader reader = ParquetFileReader.open(source.inputFile(member), ParquetGroups.readOptions())) {
            return reader.getFooter();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading Parquet footer of " + member, e);
        }
    }

    // ---- statistics -------------------------------------------------------------------------------------------

    /** INT32, INT64 (plain or annotated as an integer), FLOAT or DOUBLE. */
    private static boolean numeric(PrimitiveType type) {
        LogicalTypeAnnotation annotation = type.getLogicalTypeAnnotation();
        return switch (type.getPrimitiveTypeName()) {
            case INT32, INT64 -> annotation == null || annotation instanceof IntLogicalTypeAnnotation;
            case FLOAT, DOUBLE -> true;
            default -> false;
        };
    }

    private static RowGroup rowGroup(BlockMetaData block, Located c) {
        Statistics<?> stats = null;
        for (ColumnChunkMetaData chunk : block.getColumns()) {
            if (Arrays.equals(chunk.getPath().toArray(), c.path())) {
                stats = chunk.getStatistics();
                break;
            }
        }
        if (stats == null) {
            return new RowGroup(block.getRowCount(), null, null, null);
        }
        Long nulls = stats.isNumNullsSet() ? stats.getNumNulls() : null;
        if (!stats.hasNonNullValue()) {
            return new RowGroup(block.getRowCount(), nulls, null, null);
        }
        double min = value(stats.genericGetMin(), c, "minimum");
        double max = value(stats.genericGetMax(), c, "maximum");
        if (Double.isNaN(min) || Double.isNaN(max)) {
            // parquet-java already drops NaN bounds, whose order is undefined
            return new RowGroup(block.getRowCount(), nulls, null, null);
        }
        return new RowGroup(block.getRowCount(), nulls, min, max);
    }

    /** A statistic as double: floats widened, unsigned integers not sign-extended. */
    private static double value(Object statistic, Located c, String which) {
        boolean unsigned = c.type().getLogicalTypeAnnotation() instanceof IntLogicalTypeAnnotation i && !i.isSigned();
        if (statistic instanceof Integer i) {
            return unsigned ? Integer.toUnsignedLong(i) : i;
        }
        if (statistic instanceof Long l) {
            if (unsigned && l < 0) {
                throw new MzPeakException(c.member() + ": uint64 column " + c.column() + " has the " + which + " "
                        + Long.toUnsignedString(l) + ", beyond the supported range (high bit set)");
            }
            return l;
        }
        if (statistic instanceof Float f) {
            return f;
        }
        if (statistic instanceof Double d) {
            return d;
        }
        throw new MzPeakException(c.member() + ": column " + c.column() + " has a " + which + " statistic of type "
                + statistic.getClass().getSimpleName() + ", not a number");
    }
}
