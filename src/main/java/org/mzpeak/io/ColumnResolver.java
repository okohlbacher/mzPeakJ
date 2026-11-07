package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation.IntLogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.ListLogicalTypeAnnotation;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.mzpeak.io.ColumnTable.Column;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Param;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the columns of one scope of a metadata facet by accession and reads their cells. A scope is the facet's
 * split table or packed struct, or a struct or list element below it ({@link #child}). Column identity comes from
 * the member's {@code column_mapping} (split layout only), then from {@link ColumnTable}, so legacy names are data,
 * not code. Every read goes through the {@link ParquetGroups} accessors, made aware of unsigned integers.
 */
final class ColumnResolver {

    private static final String PARAMETERS = "parameters";
    /** The physical types {@link ParquetGroups#optLong} and {@link ParquetGroups#optDouble} read. */
    private static final Set<PrimitiveTypeName> NUMERIC = EnumSet.of(
            PrimitiveTypeName.INT32, PrimitiveTypeName.INT64, PrimitiveTypeName.FLOAT, PrimitiveTypeName.DOUBLE);

    private final GroupType scope;
    /** The member's manifest entry in the split layout; {@code null} in the packed layout. */
    private final Entry entry;
    private final Facet facet;
    /** The dotted path of this scope below the facet's table or packed struct; empty at the top. */
    private final String prefix;
    private final String member;

    private ColumnResolver(GroupType scope, Entry entry, Facet facet, String prefix, String member) {
        this.scope = scope;
        this.entry = entry;
        this.facet = facet;
        this.prefix = prefix;
        this.member = member;
    }

    /** The resolver of a split facet table, whose columns {@code entry}'s column_mapping maps. */
    static ColumnResolver split(GroupType table, Entry entry, Facet facet) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(facet, "facet");
        return new ColumnResolver(table, entry, facet, "", String.valueOf(entry.name()));
    }

    /** The resolver of a pin-era packed struct, such as {@code spectrum} or {@code scan}; it has no mapping. */
    static ColumnResolver packed(GroupType struct, Facet facet) {
        Objects.requireNonNull(struct, "struct");
        Objects.requireNonNull(facet, "facet");
        return new ColumnResolver(struct, null, facet, "", "packed struct '" + struct.getName() + "'");
    }

    /** {@link #packed(GroupType, Facet)} of the member named {@code member}, which error messages name too. */
    static ColumnResolver packed(GroupType struct, Facet facet, String member) {
        Objects.requireNonNull(struct, "struct");
        Objects.requireNonNull(facet, "facet");
        Objects.requireNonNull(member, "member");
        return new ColumnResolver(struct, null, facet, "", member + ", packed struct '" + struct.getName() + "'");
    }

    /**
     * The resolver of the struct {@code field}, or of the element struct of the list {@code field}, of this scope.
     * Mapping paths omit list and element levels, so both continue the dotted path with {@code field}.
     *
     * @throws IllegalArgumentException if {@code field} is absent, a scalar or a list of scalars
     */
    ColumnResolver child(String field) {
        Type type = field == null || !scope.containsField(field) ? null : scope.getType(field);
        GroupType element = type == null || type.isPrimitive() ? null : element(type.asGroupType());
        if (element == null) {
            throw new IllegalArgumentException(member + ": " + path(field)
                    + " is not a struct or a list of structs in " + scope.getName());
        }
        return new ColumnResolver(element, entry, facet, path(field), member);
    }

    /** Names the member, or the packed struct, in error messages. */
    String member() {
        return member;
    }

    // ---- resolution -------------------------------------------------------------------------------------------

    /**
     * The field of this scope that holds the term {@code accession}: the first column_mapping path with that
     * accession, then the {@link ColumnTable} path, then its fallbacks, then {@code extraNames}; each only when it
     * names a field directly in this scope. A field that the column_mapping assigns to another accession is never
     * taken from the {@link ColumnTable} path, its fallbacks or {@code extraNames}.
     *
     * @return the field name, or {@code null} when none is present
     */
    String resolve(String accession, String... extraNames) {
        Objects.requireNonNull(accession, "accession");
        if (entry != null) {
            for (ColumnMapping m : entry.columnMapping()) {
                String field = accession.equals(m.accession()) ? field(m.path()) : null;
                if (field != null) {
                    return field;
                }
            }
        }
        Optional<Column> column = ColumnTable.byAccession(facet, accession);
        if (column.isPresent()) {
            String field = field(column.get().path());
            if (field != null && !mappedToAnother(field, accession)) {
                return field;
            }
            for (String fallback : column.get().fallbacks()) {
                field = field(fallback);
                if (field != null && !mappedToAnother(field, accession)) {
                    return field;
                }
            }
        }
        for (String name : extraNames) {
            if (name != null && scope.containsField(name) && !mappedToAnother(name, accession)) {
                return name;
            }
        }
        return null;
    }

    /**
     * Whether the member's column_mapping (split layout only) assigns {@code field} of this scope to a term other
     * than {@code accession}.
     */
    private boolean mappedToAnother(String field, String accession) {
        if (entry == null) {
            return false;
        }
        Optional<ColumnMapping> mapped = entry.columnAtPath(path(field));
        return mapped.isPresent() && mapped.get().accession() != null && !accession.equals(mapped.get().accession());
    }

    /** The first of {@code names} that is a field directly in this scope, or {@code null}. */
    String resolveName(String... names) {
        for (String name : names) {
            if (name != null && scope.containsField(name)) {
                return name;
            }
        }
        return null;
    }

    /**
     * The mapping of {@code field}: the member's column_mapping entry at its path, else the {@link ColumnTable}
     * column whose path or fallback it is, given as a mapping at the field's path.
     */
    Optional<ColumnMapping> mapping(String field) {
        if (field == null) {
            return Optional.empty();
        }
        String path = path(field);
        if (entry != null) {
            Optional<ColumnMapping> mapped = entry.columnAtPath(path);
            if (mapped.isPresent()) {
                return mapped;
            }
        }
        return ColumnTable.byPath(facet, path)
                .map(c -> new ColumnMapping(c.name(), path, c.accession(), c.unit(), c.termMarker()));
    }

    /**
     * Whether {@code field} holds term markers: the mapping's {@code term_marker}, or, where the manifest omits the
     * key, the {@link ColumnTable} column of the mapped accession (of the path when the mapping has none).
     */
    boolean termMarker(String field) {
        Optional<ColumnMapping> mapped = mapping(field);
        if (mapped.isEmpty()) {
            return false;
        }
        ColumnMapping m = mapped.get();
        if (m.termMarker() != null) {
            return m.termMarker();
        }
        Optional<Column> column = m.accession() != null
                ? ColumnTable.byAccession(facet, m.accession())
                : ColumnTable.byPath(facet, path(field));
        return column.map(Column::termMarker).orElse(false);
    }

    // ---- typed reads ------------------------------------------------------------------------------------------

    /**
     * An integer cell as long. Unsigned 8-, 16- and 32-bit integers are not sign-extended; signed ones and the
     * rest read as {@link ParquetGroups#optLong} reads them. {@code null} when the field is {@code null}, absent or
     * null in this row.
     *
     * @throws MzPeakException if an unsigned 64-bit cell has the high bit set, or the column is not numeric; the
     *                         message names the member and the field
     */
    Long longValue(Group row, String field) {
        if (field == null || !ParquetGroups.has(row, field)) {
            return null;
        }
        PrimitiveType type = scalar(row, field);
        if (unsigned(type)) {
            if (type.getPrimitiveTypeName() == PrimitiveTypeName.INT32) {
                return Integer.toUnsignedLong(row.getInteger(field, 0));
            }
            if (type.getPrimitiveTypeName() == PrimitiveTypeName.INT64) {
                long value = row.getLong(field, 0);
                if (value < 0) {
                    throw new MzPeakException(member + ": uint64 column " + path(field) + " holds "
                            + Long.toUnsignedString(value) + ", beyond the supported range (high bit set)");
                }
                return value;
            }
        }
        requireType(field, type, NUMERIC, "an integer");
        return ParquetGroups.optLong(row, field);
    }

    /**
     * An integer cell as int, read as {@link #longValue}.
     *
     * @throws MzPeakException if the value does not fit an int
     */
    Integer intValue(Group row, String field) {
        Long value = longValue(row, field);
        if (value == null) {
            return null;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new MzPeakException(member + ": " + path(field) + " holds " + value + ", beyond the int range");
        }
        return value.intValue();
    }

    /**
     * A numeric cell as double; unsigned integers read as {@link #longValue}, the rest as
     * {@link ParquetGroups#optDouble}.
     *
     * @throws MzPeakException if the column is not numeric; the message names the member and the field
     */
    Double doubleValue(Group row, String field) {
        if (field == null || !ParquetGroups.has(row, field)) {
            return null;
        }
        PrimitiveType type = scalar(row, field);
        if (unsigned(type)) {
            return longValue(row, field).doubleValue();
        }
        requireType(field, type, NUMERIC, "numeric");
        return ParquetGroups.optDouble(row, field);
    }

    /**
     * A string cell, or {@code null}.
     *
     * @throws MzPeakException if the column is not BINARY (or FIXED_LEN_BYTE_ARRAY); the message names the member
     *                         and the field
     */
    String stringValue(Group row, String field) {
        if (field == null || !ParquetGroups.has(row, field)) {
            return null;
        }
        requireType(field, scalar(row, field),
                EnumSet.of(PrimitiveTypeName.BINARY, PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY), "a string");
        return ParquetGroups.optString(row, field);
    }

    /**
     * A boolean cell, or {@code null}; an INT32 cell is true when non-zero, as for the boolean arm of a parameter.
     *
     * @throws MzPeakException if the column is neither BOOLEAN nor INT32
     */
    Boolean booleanValue(Group row, String field) {
        if (field == null || !ParquetGroups.has(row, field)) {
            return null;
        }
        PrimitiveTypeName p = scalar(row, field).getPrimitiveTypeName();
        return switch (p) {
            case BOOLEAN -> row.getBoolean(field, 0);
            case INT32 -> row.getInteger(field, 0) != 0;
            default -> throw new MzPeakException(member + ": " + path(field) + " is not a boolean: " + p);
        };
    }

    /**
     * A numeric list cell, as {@link ParquetGroups#doubleListNullable} reads it; empty when absent or null.
     *
     * @throws MzPeakException if the column is a scalar or its elements are not numeric; the message names the
     *                         member and the field
     */
    double[] doubleList(Group row, String field) {
        if (field == null) {
            return new double[0];
        }
        if (ParquetGroups.has(row, field) && row.getType().getType(field).isPrimitive()) {
            throw new MzPeakException(member + ": " + path(field) + " is a scalar column, expected a list");
        }
        try {
            return ParquetGroups.doubleListNullable(row, field);
        } catch (IllegalStateException | ClassCastException e) {
            throw new MzPeakException(member + ": " + path(field) + " is not a numeric list: " + e.getMessage(), e);
        }
    }

    /** The scope's {@code parameters} list, as {@link ParquetGroups#readParams} reads it, in both layouts. */
    List<Param> params(Group row) {
        return ParquetGroups.readParams(row, PARAMETERS);
    }

    // ---- leftovers --------------------------------------------------------------------------------------------

    /**
     * The scope's unconsumed scalar cells as Params, in schema order (split layout only; empty for packed). Only
     * primitive, non-repeated fields directly in the scope are visited, never groups or lists. Structural fields
     * ({@link ColumnTable#structural}), {@code consumed} fields, null cells and cells of a type no Param value
     * holds (INT96, FIXED_LEN_BYTE_ARRAY) are skipped. The others become:
     * <ul>
     *   <li>a term-marker string: {@code Param(null, cell)}, the cell as accession;
     *   <li>a term-marker boolean: {@code Param(mapping name, mapping accession)}, only when TRUE;
     *   <li>a mapped cell: {@code Param(mapping name, mapping accession, value, mapping unit)};
     *   <li>an unmapped cell: {@code Param(field, null, value)}.
     * </ul>
     * Values are Long for integers, Double for floats, String or Boolean.
     */
    List<Param> leftovers(Group row, Set<String> consumed) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(consumed, "consumed");
        if (entry == null) {
            return List.of();
        }
        Set<String> structural = ColumnTable.structural(facet);
        List<Param> out = new ArrayList<>();
        for (Type type : row.getType().getFields()) {
            String field = type.getName();
            if (!type.isPrimitive() || type.isRepetition(Type.Repetition.REPEATED) || structural.contains(field)
                    || consumed.contains(field) || !ParquetGroups.has(row, field)) {
                continue;
            }
            Param p = leftover(row, type.asPrimitiveType());
            if (p != null) {
                out.add(p);
            }
        }
        return List.copyOf(out);
    }

    private Param leftover(Group row, PrimitiveType type) {
        String field = type.getName();
        Object value = switch (type.getPrimitiveTypeName()) {
            case BOOLEAN -> booleanValue(row, field);
            case INT32, INT64 -> longValue(row, field);
            case FLOAT, DOUBLE -> doubleValue(row, field);
            case BINARY -> stringValue(row, field);
            default -> null;
        };
        if (value == null) {
            return null;
        }
        Optional<ColumnMapping> mapped = mapping(field);
        if (mapped.isEmpty()) {
            return new Param(field, null, value, null);
        }
        ColumnMapping m = mapped.get();
        if (termMarker(field)) {
            if (value instanceof String curie) {
                return new Param(null, curie, null, null);
            }
            if (value instanceof Boolean present) {
                return present ? new Param(m.name(), m.accession(), null, null) : null;
            }
        }
        return new Param(m.name(), m.accession(), value, m.unit());
    }

    // ---- paths ------------------------------------------------------------------------------------------------

    /** The dotted path of {@code field} below the facet's table or packed struct. */
    String path(String field) {
        return prefix.isEmpty() ? field : prefix + "." + field;
    }

    /**
     * The primitive type of {@code field} of {@code row}.
     *
     * @throws MzPeakException if the field is a struct or a list
     */
    private PrimitiveType scalar(Group row, String field) {
        Type type = row.getType().getType(field);
        if (!type.isPrimitive()) {
            throw new MzPeakException(member + ": " + path(field) + " is a struct or list column, expected a scalar");
        }
        return type.asPrimitiveType();
    }

    /** Throws unless {@code type} is one of {@code accepted}; the message names the member and the field. */
    private void requireType(String field, PrimitiveType type, Set<PrimitiveTypeName> accepted, String expected) {
        if (!accepted.contains(type.getPrimitiveTypeName())) {
            throw new MzPeakException(member + ": " + path(field) + " is not " + expected + ": "
                    + type.getPrimitiveTypeName());
        }
    }

    /** The field directly in this scope that the facet-relative {@code dottedPath} names, or {@code null}. */
    private String field(String dottedPath) {
        if (dottedPath == null) {
            return null;
        }
        String relative;
        if (prefix.isEmpty()) {
            relative = dottedPath;
        } else if (dottedPath.startsWith(prefix + ".")) {
            relative = dottedPath.substring(prefix.length() + 1);
        } else {
            return null;
        }
        return relative.indexOf('.') < 0 && scope.containsField(relative) ? relative : null;
    }

    private static boolean unsigned(PrimitiveType type) {
        return type.getLogicalTypeAnnotation() instanceof IntLogicalTypeAnnotation i && !i.isSigned();
    }

    /**
     * A struct itself, or the element struct of a list as {@link ParquetGroups#groupList} reads it (3-level
     * {@code list -> element} or 2-level); {@code null} for a list of scalars.
     */
    private static GroupType element(GroupType type) {
        boolean list = type.getLogicalTypeAnnotation() instanceof ListLogicalTypeAnnotation
                || type.getFieldCount() == 1 && type.getType(0).isRepetition(Type.Repetition.REPEATED);
        if (!list || type.getFieldCount() == 0) {
            return type;
        }
        Type slot = type.getType(0);
        if (slot.isPrimitive()) {
            return null;
        }
        GroupType repeated = slot.asGroupType();
        if (repeated.getFieldCount() == 1 && !repeated.getType(0).isPrimitive()) {
            return repeated.getType(0).asGroupType();
        }
        return repeated;
    }
}
