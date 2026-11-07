package org.mzpeak.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ms.numpress.MSNumpress;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The array index of one signal member: the {@code <entity>_array_index} footer JSON, one {@link Entry} per Parquet
 * column that holds (part of) a signal array. Files without the footer, such as those mzPeakJ writes, get an
 * {@link Source#INFERRED INFERRED} index built from today's column-name rules, so they decode as before.
 *
 * @param entity  the entity the member stores arrays for
 * @param prefix  the top-level struct the entries live under, {@code point} or {@code chunk}
 * @param entries the entries in index order
 * @param source  where the entries come from
 */
record ArrayIndex(SignalEntity entity, String prefix, List<Entry> entries, Source source) {

    private static final System.Logger LOG = System.getLogger(ArrayIndex.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String POINT_PREFIX = "point";
    static final String CHUNK_PREFIX = "chunk";

    /** MS:1003870, the array type of the {@code index} entry. */
    static final String INDEX_ARRAY = "MS:1003870";
    /** MS:1000515, intensity array. */
    static final String INTENSITY_ARRAY = "MS:1000515";
    /** MS:1003826, the coordinate-grid chunk transform. */
    static final String GRID_TRANSFORM = "MS:1003826";

    ArrayIndex {
        entries = List.copyOf(entries);
    }

    /** Where an index comes from. */
    enum Source {
        /** Parsed from the member's {@code <entity>_array_index} footer. */
        FOOTER,
        /** Built from the column names, because the footer has no index or a malformed one. */
        INFERRED
    }

    /** How an entry's column stores its array ({@code buffer_format}). */
    enum BufferFormat {
        POINT, CHUNK_VALUES, CHUNK_SECONDARY, CHUNK_START, CHUNK_END, CHUNK_ENCODING, CHUNK_TRANSFORM, INDEX, UNKNOWN;

        /**
         * The format named by {@code s}, compared in lower case; {@code secondary_chunk} is an alias of
         * {@code chunk_secondary}, as in the Rust reference. A null or unknown name gives {@link #UNKNOWN}.
         */
        static BufferFormat parse(String s) {
            if (s == null) {
                return UNKNOWN;
            }
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "point" -> POINT;
                case "chunk_values" -> CHUNK_VALUES;
                case "chunk_secondary", "secondary_chunk" -> CHUNK_SECONDARY;
                case "chunk_start" -> CHUNK_START;
                case "chunk_end" -> CHUNK_END;
                case "chunk_encoding" -> CHUNK_ENCODING;
                case "chunk_transform" -> CHUNK_TRANSFORM;
                case "index" -> INDEX;
                default -> UNKNOWN;
            };
        }

        /** True for the formats whose column holds the array's values: point, chunk values, chunk secondary. */
        boolean valueBearing() {
            return this == POINT || this == CHUNK_VALUES || this == CHUNK_SECONDARY;
        }
    }

    /**
     * One index entry. String components are null when the JSON value is absent or null.
     *
     * @param path        the dotted column path from the schema root, such as {@code point.mz}
     * @param field       the last path segment, the column's name inside the prefix struct
     * @param sortingRank the sort rank, or null when the array is not sorted
     */
    record Entry(String path, String field, String arrayType, String dataType, String unit, BufferFormat format,
                 String transform, Integer sortingRank, String bufferPriority, String arrayName, String context,
                 String dataProcessingId) {
    }

    /**
     * Parses an array index footer value.
     *
     * @throws MzPeakException when {@code json} is not a JSON object with a {@code prefix} string and an
     *                         {@code entries} array of objects that each have a {@code path} string, or when a
     *                         string or integer component has another JSON type
     */
    static ArrayIndex parse(String json, SignalEntity entity) {
        Objects.requireNonNull(json, "json");
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw malformed(entity, "not valid JSON (" + e.getOriginalMessage() + ")", e);
        }
        if (root == null || !root.isObject()) {
            throw malformed(entity, "not a JSON object", null);
        }
        JsonNode prefix = root.get("prefix");
        if (prefix == null || !prefix.isTextual()) {
            throw malformed(entity, "no 'prefix' string", null);
        }
        JsonNode entries = root.get("entries");
        if (entries == null || !entries.isArray()) {
            throw malformed(entity, "no 'entries' array", null);
        }
        List<Entry> parsed = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            parsed.add(entry(entries.get(i), i, entity));
        }
        return new ArrayIndex(entity, prefix.asText(), parsed, Source.FOOTER);
    }

    private static Entry entry(JsonNode n, int i, SignalEntity entity) {
        if (!n.isObject()) {
            throw malformed(entity, "entry " + i + " is not an object", null);
        }
        String path = text(n, "path", i, entity);
        if (path == null || path.isEmpty()) {
            throw malformed(entity, "entry " + i + " has no 'path'", null);
        }
        return new Entry(path, path.substring(path.lastIndexOf('.') + 1),
                text(n, "array_type", i, entity), text(n, "data_type", i, entity), text(n, "unit", i, entity),
                BufferFormat.parse(text(n, "buffer_format", i, entity)), text(n, "transform", i, entity),
                sortingRank(n, i, entity), text(n, "buffer_priority", i, entity), text(n, "array_name", i, entity),
                text(n, "context", i, entity), text(n, "data_processing_id", i, entity));
    }

    private static String text(JsonNode n, String key, int i, SignalEntity entity) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw malformed(entity, "entry " + i + " has a non-string '" + key + "'", null);
        }
        return v.asText();
    }

    private static Integer sortingRank(JsonNode n, int i, SignalEntity entity) {
        JsonNode v = n.get("sorting_rank");
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isIntegralNumber() || !v.canConvertToInt()) {
            throw malformed(entity, "entry " + i + " has a non-integer 'sorting_rank'", null);
        }
        return v.intValue();
    }

    private static MzPeakException malformed(SignalEntity entity, String reason, Throwable cause) {
        return new MzPeakException("malformed " + entity.arrayIndexKey() + " footer: " + reason, cause);
    }

    /**
     * The index of a member from its footer key-values: {@link Source#FOOTER FOOTER} when
     * {@code <entity>_array_index} parses, {@link Source#INFERRED INFERRED} when it is absent, and INFERRED plus a
     * WARNING when it is malformed.
     */
    static ArrayIndex of(Map<String, String> keyValue, MessageType schema, SignalEntity entity) {
        String json = keyValue == null ? null : keyValue.get(entity.arrayIndexKey());
        if (json == null) {
            return infer(schema, entity);
        }
        try {
            return parse(json, entity);
        } catch (MzPeakException e) {
            LOG.log(Level.WARNING, () -> e.getMessage() + "; resolving the signal columns by name instead");
            return infer(schema, entity);
        }
    }

    /**
     * An {@link Source#INFERRED INFERRED} index for a member without one. It lists, in schema order except as noted
     * for chromatograms, the columns today's decoders read by name, so a plan built from it resolves exactly those
     * columns, with the same intensity priority. The prefix is
     * {@code point} when the schema has that struct, else {@code chunk} when it has that one (spectra and
     * chromatograms only; wavelength spectra are read from {@code point} rows only).
     * <ul>
     *   <li>Every entity: {@code <entity>_index} is the index (rank 0).
     *   <li>Spectra, as read by {@link SpectrumArrayStore}: in {@code point}, {@code mz} (rank 1) and
     *       {@code intensity}; in {@code chunk}, the {@code mz_chunk_start}, {@code mz_chunk_end},
     *       {@code mz_chunk_values} and {@code chunk_encoding} columns of the m/z axis (rank 1), the Numpress
     *       linear and grid transforms {@code mz_numpress_linear_bytes} and {@code mz_grid} of the axis, the
     *       {@code intensity} list (chunk secondary) and the Numpress intensity transforms
     *       {@code intensity_numpress_slof_bytes} and {@code intensity_numpress_pic_bytes}.
     *   <li>Chromatograms, as read by {@link ChromatogramDataDecoder}: in {@code point}, {@code time} (rank 1),
     *       {@code intensity} and every non-repeated FLOAT, DOUBLE, INT32 or INT64 column named
     *       {@code intensity_*}, the latter listed after all other columns (in schema order among themselves) because
     *       they are read only when {@code intensity} is null; in {@code chunk}, the {@code time_chunk_*} and
     *       {@code chunk_encoding} columns of the time axis (rank 1), the {@code intensity} list (chunk secondary) and
     *       {@code intensity_numpress_slof_bytes}. PIC intensity is not read for chromatograms, so it is not
     *       listed.
     *   <li>Wavelength spectra, as read by {@link PointArrays}: {@code wavelength} (rank 1) and {@code intensity}.
     * </ul>
     * The axis bounds and {@code chunk_encoding} are listed whenever present so that the index matches what a
     * footer would list, although the chromatogram decoder reads neither. Units and data types are not inferred.
     */
    static ArrayIndex infer(MessageType schema, SignalEntity entity) {
        String prefix = POINT_PREFIX;
        if (!hasGroup(schema, POINT_PREFIX) && hasGroup(schema, CHUNK_PREFIX)
                && entity != SignalEntity.WAVELENGTH_SPECTRUM) {
            prefix = CHUNK_PREFIX;
        }
        List<Entry> entries = new ArrayList<>();
        // a chromatogram point's intensity_* columns are read only after the literal intensity (Q-U5)
        List<Entry> intensityFallbacks = new ArrayList<>();
        if (hasGroup(schema, prefix)) {
            for (Type field : schema.getType(prefix).asGroupType().getFields()) {
                Entry e = inferEntry(entity, prefix, field);
                if (e == null) {
                    continue;
                }
                if (entity == SignalEntity.CHROMATOGRAM && prefix.equals(POINT_PREFIX)
                        && isNumericIntensityFallback(field)) {
                    intensityFallbacks.add(e);
                } else {
                    entries.add(e);
                }
            }
        }
        entries.addAll(intensityFallbacks);
        return new ArrayIndex(entity, prefix, entries, Source.INFERRED);
    }

    private static boolean hasGroup(MessageType schema, String name) {
        return schema.containsField(name) && !schema.getType(name).isPrimitive();
    }

    private static Entry inferEntry(SignalEntity entity, String prefix, Type field) {
        String name = field.getName();
        String axis = entity.defaultAxis();
        String axisName = switch (entity) {
            case SPECTRUM -> "mz";
            case CHROMATOGRAM -> "time";
            case WAVELENGTH_SPECTRUM -> "wavelength";
        };
        if (name.equals(entity.indexField())) {
            return inferred(entity, prefix, name, INDEX_ARRAY, BufferFormat.INDEX, null, 0);
        }
        if (prefix.equals(POINT_PREFIX)) {
            if (name.equals(axisName)) {
                return inferred(entity, prefix, name, axis, BufferFormat.POINT, null, 1);
            }
            if (name.equals("intensity")
                    || (entity == SignalEntity.CHROMATOGRAM && isNumericIntensityFallback(field))) {
                return inferred(entity, prefix, name, INTENSITY_ARRAY, BufferFormat.POINT, null, null);
            }
            return null;
        }
        if (name.equals(axisName + "_chunk_start")) {
            return inferred(entity, prefix, name, axis, BufferFormat.CHUNK_START, null, 1);
        }
        if (name.equals(axisName + "_chunk_end")) {
            return inferred(entity, prefix, name, axis, BufferFormat.CHUNK_END, null, 1);
        }
        if (name.equals(axisName + "_chunk_values")) {
            return inferred(entity, prefix, name, axis, BufferFormat.CHUNK_VALUES, null, 1);
        }
        if (name.equals("chunk_encoding")) {
            return inferred(entity, prefix, name, axis, BufferFormat.CHUNK_ENCODING, null, 1);
        }
        if (name.equals("intensity")) {
            return inferred(entity, prefix, name, INTENSITY_ARRAY, BufferFormat.CHUNK_SECONDARY, null, null);
        }
        if (name.equals("intensity_numpress_slof_bytes")) {
            return inferred(entity, prefix, name, INTENSITY_ARRAY, BufferFormat.CHUNK_TRANSFORM,
                    MSNumpress.ACC_NUMPRESS_SLOF, null);
        }
        if (entity == SignalEntity.SPECTRUM) {
            if (name.equals("mz_numpress_linear_bytes")) {
                return inferred(entity, prefix, name, axis, BufferFormat.CHUNK_TRANSFORM,
                        MSNumpress.ACC_NUMPRESS_LINEAR, 1);
            }
            if (name.equals("mz_grid")) {
                return inferred(entity, prefix, name, axis, BufferFormat.CHUNK_TRANSFORM, GRID_TRANSFORM, 1);
            }
            if (name.equals("intensity_numpress_pic_bytes")) {
                return inferred(entity, prefix, name, INTENSITY_ARRAY, BufferFormat.CHUNK_TRANSFORM,
                        MSNumpress.ACC_NUMPRESS_PIC, null);
            }
        }
        return null;
    }

    /** The {@code intensity_*} rule of {@link ChromatogramDataDecoder}'s point-row fallback. */
    private static boolean isNumericIntensityFallback(Type field) {
        if (!field.getName().startsWith("intensity_")
                || !field.isPrimitive() || field.isRepetition(Type.Repetition.REPEATED)) {
            return false;
        }
        return switch (field.asPrimitiveType().getPrimitiveTypeName()) {
            case FLOAT, DOUBLE, INT32, INT64 -> true;
            default -> false;
        };
    }

    private static Entry inferred(SignalEntity entity, String prefix, String name, String arrayType,
                                  BufferFormat format, String transform, Integer sortingRank) {
        return new Entry(prefix + "." + name, name, arrayType, null, null, format, transform, sortingRank, null,
                null, entity.key(), null);
    }
}
