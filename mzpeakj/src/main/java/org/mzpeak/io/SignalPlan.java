package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;
import org.mzpeak.io.ArrayIndex.BufferFormat;
import org.mzpeak.io.ArrayIndex.Entry;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Which columns of a signal member hold the main axis, the intensities and the extra arrays, derived from its
 * {@link ArrayIndex}. Arrays are told apart by array type and buffer format; the {@code buffer_priority} role flags
 * never pick an array (small.chunked's intensity is chunk secondary, diaPASEF.ref's ion mobility is primary).
 *
 * @param entity      the entity the member stores arrays for
 * @param source      where the index came from
 * @param layout      point or chunk rows
 * @param prefix      the top-level struct holding the columns
 * @param indexField  the entity index column inside the prefix struct
 * @param axis        the main axis columns
 * @param intensities every intensity (MS:1000515) column, in index order
 * @param extras      every other array, keyed as described in {@link #of}, in index order
 */
record SignalPlan(SignalEntity entity, ArrayIndex.Source source, Layout layout, String prefix, String indexField,
                  Axis axis, List<Column> intensities, Map<String, Extra> extras) {

    private static final System.Logger LOG = System.getLogger(SignalPlan.class.getName());

    /** MS:1000786, non-standard data array: keyed by its array name. */
    static final String NON_STANDARD_ARRAY = "MS:1000786";

    SignalPlan {
        intensities = List.copyOf(intensities);
        extras = Collections.unmodifiableMap(new LinkedHashMap<>(extras));
    }

    /** Row layout of a signal member. */
    enum Layout { POINT, CHUNK }

    /** One column of the plan, projected from its index entry. */
    record Column(String path, String field, String arrayType, String dataType, String unit, BufferFormat format,
                  String transform) {

        static Column of(Entry e) {
            return new Column(e.path(), e.field(), e.arrayType(), e.dataType(), e.unit(), e.format(), e.transform());
        }
    }

    /**
     * The main axis.
     *
     * @param arrayType       m/z, time or wavelength
     * @param values          the point or chunk values column, or null when the index lists none
     * @param start           the chunk start column, or null
     * @param end             the chunk end column, or null
     * @param encoding        the chunk encoding column, or null
     * @param transformFields chunk transform CURIE to column field, such as MS:1002312 to
     *                        {@code mz_numpress_linear_bytes} or MS:1003826 to {@code mz_grid}
     */
    record Axis(String arrayType, Column values, Column start, Column end, Column encoding,
                Map<String, String> transformFields) {

        Axis {
            transformFields = Collections.unmodifiableMap(new LinkedHashMap<>(transformFields));
        }
    }

    /**
     * One extra array.
     *
     * @param key             the array type, the array name for MS:1000786, or {@code <key>@<field>} for each
     *                        further column of a key
     * @param values          the value-bearing column, or null when only a transform column stores the array
     * @param transformFields chunk transform CURIE to column field, such as MS:1003826 to
     *                        {@code mean_inverse_reduced_ion_mobility_grid}
     */
    record Extra(String key, String arrayType, String arrayName, String unit, Column values,
                 Map<String, String> transformFields) {

        Extra {
            transformFields = Collections.unmodifiableMap(new LinkedHashMap<>(transformFields));
        }
    }

    /** The first intensity column that holds plain values (point, chunk values or chunk secondary). */
    Optional<Column> plainIntensity() {
        return intensities.stream().filter(c -> c.format().valueBearing()).findFirst();
    }

    /**
     * The intensity column stored as an MS-Numpress chunk transform: SLOF (MS:1002314) when the index lists one,
     * else PIC (MS:1002313), matching the order in which the spectrum decoder tries them.
     */
    Optional<Column> numpressIntensity() {
        Optional<Column> slof = numpressIntensity(MSNumpress.ACC_NUMPRESS_SLOF);
        return slof.isPresent() ? slof : numpressIntensity(MSNumpress.ACC_NUMPRESS_PIC);
    }

    private Optional<Column> numpressIntensity(String transform) {
        return intensities.stream()
                .filter(c -> c.format() == BufferFormat.CHUNK_TRANSFORM && transform.equals(c.transform()))
                .findFirst();
    }

    /**
     * Derives the plan of a member from its index and schema (design §8.1).
     * <ol>
     *   <li>Every entry's path must lie under the prefix ({@code <prefix>.}). An entry whose path names no field of
     *       {@code schema} is skipped with a WARNING, unless it holds the entity's default axis or MS:1000515
     *       (intensity), in any buffer format: then the plan cannot be built. Only a footer can list such an entry;
     *       an INFERRED index is built from the schema.
     *   <li>The first {@code index} entry names the index field; without one it is
     *       {@link SignalEntity#indexField()}.
     *   <li>The main axis is the array type with the lowest {@code sorting_rank} among the other entries (rank 1
     *       in current files, rank 0 in pin-era ones; the entity's default axis when no entry is ranked). It must be
     *       the entity's default axis. Its value-bearing entry is the values column, its chunk start, end and
     *       encoding entries the chunk columns, and its chunk transforms go into {@link Axis#transformFields()}.
     *   <li>Every MS:1000515 entry is an intensity, in index order.
     *   <li>Every other entry is an extra, keyed by its array type, or by its array name for MS:1000786
     *       (for example {@code ms level}, with a space). Several value-bearing entries may share a key (arrays of one
     *       type may differ in unit or data type): the one with {@code buffer_priority} primary, else the first,
     *       gets the plain key, every other one {@code <key>@<field>}. A chunk transform joins the array of its key
     *       with the same data type, unit and data processing, else the plain-key array.
     * </ol>
     * The layout is CHUNK when an entry has a chunk format, or when there is none and the prefix is {@code chunk}.
     * The prefix {@code point} admits no chunk format, the prefix {@code chunk} no {@code point} format.
     *
     * @throws MzPeakException when an entry's path is not under the prefix; when a main-axis or intensity entry names
     *                         no column of the member; when the buffer formats disagree with the prefix; when the main
     *                         axis is not the entity's default axis; or when two value-bearing entries hold the main
     *                         axis (the message names both columns); never for a missing extra column
     */
    static SignalPlan of(ArrayIndex index, MessageType schema, SignalEntity entity) {
        Objects.requireNonNull(schema, "schema");
        if (index.entity() != entity) {
            throw new IllegalArgumentException("array index of " + index.entity() + " used for " + entity);
        }
        String what = entity.arrayIndexKey() + " (" + index.source() + ")";
        List<Entry> present = new ArrayList<>();
        for (Entry e : index.entries()) {
            if (!e.path().startsWith(index.prefix() + ".")) {
                throw new MzPeakException(what + " lists " + e.path() + ", which is not under its prefix "
                        + index.prefix());
            }
            if (hasPath(schema, e.path())) {
                present.add(e);
            } else if (entity.defaultAxis().equals(e.arrayType())
                    || ArrayIndex.INTENSITY_ARRAY.equals(e.arrayType())) {
                throw new MzPeakException(what + " lists " + e.path() + " (" + e.arrayType() + ", "
                        + formatName(e.format()) + "), which is not a column of the member");
            } else {
                LOG.log(Level.WARNING, () -> what + " lists " + e.path() + " (" + e.arrayType()
                        + "), which is not a column of the member; the entry is ignored");
            }
        }

        String indexField = null;
        List<Entry> arrays = new ArrayList<>();
        Entry chunkEntry = null;
        for (Entry e : present) {
            if (e.format() == BufferFormat.INDEX) {
                if (indexField == null) {
                    indexField = e.field();
                }
            } else {
                arrays.add(e);
                if (chunkEntry == null && e.format() != BufferFormat.POINT && e.format() != BufferFormat.UNKNOWN) {
                    chunkEntry = e;
                }
                if (e.format() == BufferFormat.POINT && ArrayIndex.CHUNK_PREFIX.equals(index.prefix())) {
                    throw new MzPeakException(what + " lists " + e.path() + " with the point buffer format under"
                            + " the prefix " + index.prefix());
                }
            }
        }
        if (indexField == null) {
            indexField = entity.indexField();
        }
        if (chunkEntry != null && ArrayIndex.POINT_PREFIX.equals(index.prefix())) {
            throw new MzPeakException(what + " lists " + chunkEntry.path() + " with the chunk buffer format "
                    + formatName(chunkEntry.format()) + " under the prefix " + index.prefix());
        }
        Layout layout = chunkEntry != null || (arrays.isEmpty() && ArrayIndex.CHUNK_PREFIX.equals(index.prefix()))
                ? Layout.CHUNK : Layout.POINT;

        String axisType = mainAxisType(arrays, entity, what);
        Column values = null;
        Column start = null;
        Column end = null;
        Column encoding = null;
        Map<String, String> axisTransforms = new LinkedHashMap<>();
        List<Column> intensities = new ArrayList<>();
        List<Entry> extraEntries = new ArrayList<>();
        for (Entry e : arrays) {
            if (e.arrayType() == null) {
                LOG.log(Level.WARNING, () -> what + " entry " + e.path() + " has no array_type; it is ignored");
            } else if (e.arrayType().equals(axisType)) {
                switch (e.format()) {
                    case POINT, CHUNK_VALUES, CHUNK_SECONDARY -> {
                        if (values != null) {
                            throw new MzPeakException(what + " lists two value-bearing entries for the main axis "
                                    + axisType + ": " + values.path() + " and " + e.path());
                        }
                        values = Column.of(e);
                    }
                    case CHUNK_START -> start = start == null ? Column.of(e) : start;
                    case CHUNK_END -> end = end == null ? Column.of(e) : end;
                    case CHUNK_ENCODING -> encoding = encoding == null ? Column.of(e) : encoding;
                    case CHUNK_TRANSFORM -> addTransform(axisTransforms, e, what);
                    default -> LOG.log(Level.DEBUG, () -> what + " entry " + e.path() + " of the main axis has"
                            + " buffer format " + e.format() + "; it is ignored");
                }
            } else if (e.arrayType().equals(ArrayIndex.INTENSITY_ARRAY)) {
                intensities.add(Column.of(e));
            } else {
                extraEntries.add(e);
            }
        }
        Axis axis = new Axis(axisType, values, start, end, encoding, axisTransforms);
        return new SignalPlan(entity, index.source(), layout, index.prefix(), indexField, axis, intensities,
                extras(extraEntries, what));
    }

    /** The array type with the lowest sorting rank, which must be the entity's default axis. */
    private static String mainAxisType(List<Entry> arrays, SignalEntity entity, String what) {
        Entry lowest = null;
        for (Entry e : arrays) {
            if (e.sortingRank() != null && e.arrayType() != null
                    && (lowest == null || e.sortingRank() < lowest.sortingRank())) {
                lowest = e;
            }
        }
        if (lowest == null) {
            return entity.defaultAxis();
        }
        if (!lowest.arrayType().equals(entity.defaultAxis())) {
            throw new MzPeakException(what + ": the main axis " + lowest.arrayType() + " (" + lowest.path()
                    + ", sorting_rank " + lowest.sortingRank() + ") is not the " + entity.key() + " axis "
                    + entity.defaultAxis());
        }
        return lowest.arrayType();
    }

    private static void addTransform(Map<String, String> transforms, Entry e, String what) {
        if (e.transform() == null) {
            LOG.log(Level.WARNING, () -> what + " chunk transform " + e.path() + " names no transform; it is ignored");
        } else if (transforms.putIfAbsent(e.transform(), e.field()) != null) {
            LOG.log(Level.WARNING, () -> what + " lists a second " + e.transform() + " column " + e.path()
                    + " for " + e.arrayType() + "; it is ignored");
        }
    }

    /** Groups the extra entries into keyed arrays; see {@link #of}. Nothing is thrown. */
    private static Map<String, Extra> extras(List<Entry> entries, String what) {
        // Which value-bearing entry of each key gets the plain key: the first primary one, else the first one.
        Map<String, Entry> plain = new HashMap<>();
        for (Entry e : entries) {
            if (e.format().valueBearing()) {
                Entry chosen = plain.get(key(e));
                if (chosen == null || (!isPrimary(chosen) && isPrimary(e))) {
                    plain.put(key(e), e);
                }
            }
        }
        Map<String, ExtraBuilder> builders = new LinkedHashMap<>();
        for (Entry e : entries) {
            String key = key(e);
            if (e.format().valueBearing()) {
                String k = plain.get(key) == e ? key : key + "@" + e.field();
                ExtraBuilder b = builders.computeIfAbsent(k, x -> new ExtraBuilder(x, e));
                if (b.values != null) {
                    LOG.log(Level.WARNING, () -> what + " lists the extra array " + k + " twice (" + b.values.path()
                            + " and " + e.path() + "); " + e.path() + " is ignored");
                } else {
                    b.values = Column.of(e);
                }
            } else if (e.format() == BufferFormat.CHUNK_TRANSFORM) {
                if (e.transform() == null) {
                    LOG.log(Level.WARNING, () -> what + " chunk transform " + e.path() + " of " + key
                            + " names no transform; it is ignored");
                    continue;
                }
                ExtraBuilder b = transformTarget(builders, plain, entries, e, key);
                if (b.transformFields.containsKey(e.transform())) {
                    LOG.log(Level.WARNING, () -> what + " chunk transform " + e.path() + " of " + key
                            + " cannot be placed; it is ignored");
                } else {
                    b.transformFields.put(e.transform(), e.field());
                }
            } else {
                LOG.log(Level.DEBUG, () -> what + " extra entry " + e.path() + " has buffer format " + e.format()
                        + "; it is ignored");
            }
        }
        Map<String, Extra> out = new LinkedHashMap<>();
        builders.forEach((k, b) -> out.put(k, b.build()));
        return out;
    }

    /**
     * The array a chunk transform of {@code key} joins: the first value-bearing array of the key with the same
     * data type, unit and data processing, else the plain-key array (created if no value column precedes it).
     */
    private static ExtraBuilder transformTarget(Map<String, ExtraBuilder> builders, Map<String, Entry> plain,
                                                List<Entry> entries, Entry transform, String key) {
        for (Entry e : entries) {
            if (e.format().valueBearing() && key(e).equals(key)
                    && Objects.equals(e.dataType(), transform.dataType())
                    && Objects.equals(e.unit(), transform.unit())
                    && Objects.equals(e.dataProcessingId(), transform.dataProcessingId())) {
                String k = plain.get(key) == e ? key : key + "@" + e.field();
                return builders.computeIfAbsent(k, x -> new ExtraBuilder(x, e));
            }
        }
        return builders.computeIfAbsent(key, x -> new ExtraBuilder(x, plain.getOrDefault(key, transform)));
    }

    /** The {@code buffer_format} name of {@code format}, such as {@code chunk_encoding}. */
    private static String formatName(BufferFormat format) {
        return format.name().toLowerCase(Locale.ROOT);
    }

    /** The extra key of an entry: its array name for MS:1000786 (array type when it has none), else its type. */
    private static String key(Entry e) {
        if (NON_STANDARD_ARRAY.equals(e.arrayType()) && e.arrayName() != null) {
            return e.arrayName();
        }
        return e.arrayType();
    }

    private static boolean isPrimary(Entry e) {
        return "primary".equalsIgnoreCase(e.bufferPriority());
    }

    /** True when every segment of the dotted {@code path} names a field, a group for all but the last. */
    private static boolean hasPath(MessageType schema, String path) {
        GroupType group = schema;
        String[] parts = path.split("\\.", -1);
        for (int i = 0; i < parts.length; i++) {
            if (!group.containsField(parts[i])) {
                return false;
            }
            Type t = group.getType(parts[i]);
            if (i < parts.length - 1) {
                if (t.isPrimitive()) {
                    return false;
                }
                group = t.asGroupType();
            }
        }
        return true;
    }

    private static final class ExtraBuilder {
        private final String key;
        private final Entry first;
        private Column values;
        private final Map<String, String> transformFields = new LinkedHashMap<>();

        ExtraBuilder(String key, Entry first) {
            this.key = key;
            this.first = first;
        }

        Extra build() {
            return new Extra(key, first.arrayType(), first.arrayName(), first.unit(), values, transformFields);
        }
    }
}
