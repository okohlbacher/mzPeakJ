package org.mzpeak.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The raw metadata cells of one current fixture ({@link Fixtures#CURRENT}), written by
 * {@code generate_golden.py --meta} to {@code <name>.meta.jsonl} next to the {@link Golden} files; the README
 * describes the format. There is one {@link Row} per row of every member whose manifest data_kind is metadata,
 * scans, precursors or selected_ions, in manifest and row order, holding the cells as pyarrow reads them
 * (without the auxiliary_arrays column). The cells come from pyarrow alone, so they do not depend on how either
 * reader interprets them, and they compare exactly: a float32 cell is the double that widening the float gives.
 *
 * <p>A value is {@code null}, a {@link Boolean}, a {@link String}, a {@link Long} (every integral JSON number), a
 * {@link Double} (every other number, NaN and the infinities included), a {@code List<Object>} or a
 * {@code Map<String, Object>} in column order. All collections are unmodifiable.
 */
final class GoldenMeta {

    /** The columns of a meta line, in file order. */
    private static final List<String> COLUMNS = List.of("member", "entity_type", "data_kind", "row", "values");

    private static final Pattern HEADER = Pattern.compile("# ([a-z0-9-]+): (.*)");

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * One row of a metadata member. {@code row} is the 0-based row ordinal within {@code member}; {@code values}
     * maps each column to its cell.
     */
    record Row(String member, String entityType, String dataKind, int row, Map<String, Object> values) {

        /**
         * The value at a dotted path: map entries by field name, list elements by decimal index (for example
         * {@code activation.dissociation_method} or {@code parameters.0.accession}). A null on the way yields
         * null.
         *
         * @throws IllegalArgumentException if a field on the path does not exist, an index is not one of the
         *                                  list's, or the path continues below a scalar
         */
        Object get(String dottedPath) {
            Object node = values;
            for (String segment : segments(dottedPath)) {
                if (node == null) {
                    return null;
                }
                if (node instanceof Map<?, ?> map) {
                    node = field(map, segment, dottedPath);
                } else if (node instanceof List<?> list) {
                    node = list.get(index(list, segment, dottedPath));
                } else {
                    throw new IllegalArgumentException(where(dottedPath) + ": " + segment + " is below a scalar");
                }
            }
            return node;
        }

        /**
         * The values at a dotted path, with every list met on the way or at its end expanded element by element,
         * as the manifest's column_mapping paths read: {@code scan_windows.scan_window_lower_limit} gives the
         * lower limit of each scan window, {@code parameters} each parameter. A null met on the way contributes no
         * value; a null at the end is kept.
         *
         * @throws IllegalArgumentException if a field on the path does not exist or the path continues below a
         *                                  scalar
         */
        List<Object> list(String dottedPath) {
            List<Object> out = new ArrayList<>();
            collect(values, segments(dottedPath), 0, dottedPath, out);
            return Collections.unmodifiableList(out);
        }

        private void collect(Object node, String[] segments, int i, String dottedPath, List<Object> out) {
            if (node instanceof List<?> list) {
                for (Object element : list) {
                    collect(element, segments, i, dottedPath, out);
                }
            } else if (i == segments.length) {
                out.add(node);
            } else if (node instanceof Map<?, ?> map) {
                collect(field(map, segments[i], dottedPath), segments, i + 1, dottedPath, out);
            } else if (node != null) {
                throw new IllegalArgumentException(where(dottedPath) + ": " + segments[i] + " is below a scalar");
            }
        }

        private Object field(Map<?, ?> map, String name, String dottedPath) {
            if (!map.containsKey(name)) {
                throw new IllegalArgumentException(where(dottedPath) + ": no field " + name + " among " + map.keySet());
            }
            return map.get(name);
        }

        private int index(List<?> list, String segment, String dottedPath) {
            if (!segment.matches("[0-9]+") || segment.length() > 9 || Integer.parseInt(segment) >= list.size()) {
                throw new IllegalArgumentException(where(dottedPath) + ": " + segment + " is not an index of a list of "
                        + list.size());
            }
            return Integer.parseInt(segment);
        }

        private String where(String dottedPath) {
            return member + " row " + row + " path " + dottedPath;
        }

        private static String[] segments(String dottedPath) {
            String[] segments = dottedPath.split("\\.", -1);
            for (String s : segments) {
                if (s.isEmpty()) {
                    throw new IllegalArgumentException("malformed dotted path: '" + dottedPath + "'");
                }
            }
            return segments;
        }
    }

    private final String name;
    private final Map<String, String> header;
    private final List<Row> rows;

    private GoldenMeta(String name, Map<String, String> header, List<Row> rows) {
        this.name = name;
        this.header = Collections.unmodifiableMap(header);
        this.rows = List.copyOf(rows);
    }

    /** Load the meta file of {@code fixture}, given as {@code small.mzpeak} or {@code small}. */
    static GoldenMeta load(String fixture) {
        String name = fixture.endsWith(".mzpeak") ? fixture.substring(0, fixture.length() - ".mzpeak".length())
                : fixture;
        Path file = Golden.DIR.resolve(name + ".meta.jsonl");
        Map<String, String> header = new LinkedHashMap<>();
        List<Row> rows = new ArrayList<>();
        Map<String, Integer> nextRow = new HashMap<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (line.startsWith("#")) {
                    check(rows.isEmpty(), "header line after the first row", line);
                    Matcher m = HEADER.matcher(line);
                    if (m.matches()) {
                        header.put(m.group(1), m.group(2));
                    }
                } else if (!line.isEmpty()) {
                    Row row = parseRow(line);
                    int expected = nextRow.merge(row.member(), 1, Integer::sum) - 1;
                    check(row.row() == expected, "row " + row.row() + " where row " + expected + " was due", line);
                    rows.add(row);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read golden meta file " + file, e);
        }
        return new GoldenMeta(name, header, rows);
    }

    /** The dataset name without {@code .mzpeak}. */
    String name() {
        return name;
    }

    /** The {@code # key: value} header lines, such as {@code python}, {@code packages} and {@code command}. */
    Map<String, String> header() {
        return header;
    }

    /** Every row, in file order. */
    List<Row> rows() {
        return rows;
    }

    /** The rows of {@code member} in row order; empty for a member without rows or not in the file. */
    List<Row> rows(String member) {
        return rows.stream().filter(r -> r.member().equals(member)).toList();
    }

    /**
     * One meta line as a {@link Row}.
     *
     * @throws IllegalStateException if the line is not a JSON object with exactly the generator's fields, or
     *                               holds an integral number outside the {@code long} range
     */
    static Row parseRow(String line) {
        JsonNode node;
        try {
            node = MAPPER.readTree(line);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("malformed golden meta line: " + line, e);
        }
        check(node.isObject(), "not an object", line);
        List<String> fields = new ArrayList<>();
        node.properties().forEach(e -> fields.add(e.getKey()));
        check(fields.equals(COLUMNS), "fields " + fields, line);
        check(node.get("member").isTextual() && node.get("entity_type").isTextual()
                && node.get("data_kind").isTextual(), "member, entity_type and data_kind must be strings", line);
        check(node.get("row").canConvertToExactIntegral() && node.get("row").canConvertToInt()
                && node.get("row").intValue() >= 0, "row must be a non-negative int", line);
        check(node.get("values").isObject(), "values must be an object", line);
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) value(node.get("values"), line);
        return new Row(node.get("member").textValue(), node.get("entity_type").textValue(),
                node.get("data_kind").textValue(), node.get("row").intValue(), values);
    }

    /** A JSON value as the class comment describes it. */
    private static Object value(JsonNode node, String line) {
        return switch (node.getNodeType()) {
            case NULL -> null;
            case BOOLEAN -> node.booleanValue();
            case STRING -> node.textValue();
            case NUMBER -> number(node, line);
            case ARRAY -> {
                List<Object> out = new ArrayList<>(node.size());
                for (JsonNode element : node) {
                    out.add(value(element, line));
                }
                yield Collections.unmodifiableList(out);
            }
            case OBJECT -> {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> e : node.properties()) {
                    out.put(e.getKey(), value(e.getValue(), line));
                }
                yield Collections.unmodifiableMap(out);
            }
            default -> throw new IllegalStateException("unexpected JSON " + node.getNodeType()
                    + " in golden meta line: " + line);
        };
    }

    /** An integral JSON number as {@link Long}, any other (NaN and the infinities included) as {@link Double}. */
    private static Object number(JsonNode node, String line) {
        if (node.isIntegralNumber()) {
            check(node.canConvertToLong(), "integral number " + node + " outside the long range", line);
            return node.longValue();
        }
        check(node.isDouble(), "number " + node + " not read as a double", line);
        return node.doubleValue();
    }

    private static void check(boolean ok, String problem, String line) {
        if (!ok) {
            throw new IllegalStateException("malformed golden meta line (" + problem + "): " + line);
        }
    }
}
