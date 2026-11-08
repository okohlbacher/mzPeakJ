package org.mzpeak.mzml;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The typed canonical rendering of the reader's model that the characterization files hold (MJ-3, mzML design 12.8).
 * A record renders as {@code Name(component=value, ...)} in declaration order, recursively; a {@link List} as
 * {@code [a, b]}; a {@link Map} as {@code {key=value, ...}} in iteration order; {@code null} as {@code null}; an enum
 * by its name; a {@code double[]} as {@code double[a, b]}, element by element; a String, Double, Float, Long, Integer,
 * Short, Byte or Boolean as {@code <SimpleClassName>:<text>}, a Double or Float by {@link Double#toString(double)}, a
 * String by {@link #escape(String)}. So {@code Long:7} and {@code String:7}, {@code Boolean:true} and
 * {@code String:true}, {@code Double:1.0} and {@code Long:1}, {@code null} and {@code String:null} differ. Any other
 * type fails the rendering: nothing is rendered by an identity {@code toString}.
 */
final class Canonical {

    /** The types rendered as {@code <SimpleClassName>:<text>}. */
    private static final Set<Class<?>> SCALARS = Set.of(String.class, Double.class, Float.class, Long.class,
            Integer.class, Short.class, Byte.class, Boolean.class);

    private Canonical() {
    }

    /**
     * The canonical rendering of {@code value}.
     *
     * @throws IllegalArgumentException if {@code value} is, or holds, a value of a type without a rule
     * @throws IllegalStateException    if a record accessor fails
     */
    static String render(Object value) {
        StringBuilder out = new StringBuilder();
        render(value, out);
        return out.toString();
    }

    /**
     * The canonical rendering of {@code record} without the components {@code omitted}, for example a wavelength
     * spectrum without its arrays.
     *
     * @throws IllegalArgumentException if a name in {@code omitted} is no component of the record, or as
     *                                  {@link #render(Object)}
     */
    static String renderWithout(Record record, String... omitted) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            names.add(component.getName());
        }
        List<String> unknown = new ArrayList<>(Arrays.asList(omitted));
        unknown.removeAll(names);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(record.getClass().getSimpleName() + " has no component " + unknown
                    + "; its components: " + names);
        }
        StringBuilder out = new StringBuilder();
        renderRecord(record, Arrays.asList(omitted), out);
        return out.toString();
    }

    /**
     * {@code text} with {@code \}, {@code |} and every character outside U+0020-U+007E (tab, LF and CR included)
     * written as {@code \}{@code u} and four lowercase hex digits; so the result is printable ASCII without a field
     * separator.
     */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7e || c == '\\' || c == '|') {
                out.append("\\u").append(HexFormat.of().toHexDigits(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The lowercase hex SHA-256 of the little-endian IEEE-754 bytes of {@code values}. */
    static String sha256(double[] values) {
        ByteBuffer bytes = ByteBuffer.allocate(Double.BYTES * values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) {
            bytes.putDouble(v);
        }
        return sha256(bytes.array());
    }

    /** The lowercase hex SHA-256 of the UTF-8 bytes of {@code text}. */
    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void render(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Record record) {
            renderRecord(record, List.of(), out);
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                out.append(i == 0 ? "" : ", ");
                render(list.get(i), out);
            }
            out.append(']');
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            String separator = "";
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.append(separator);
                render(e.getKey(), out);
                out.append('=');
                render(e.getValue(), out);
                separator = ", ";
            }
            out.append('}');
        } else if (value instanceof Enum<?> e) {
            out.append(e.name());
        } else if (value instanceof double[] values) {
            out.append("double[");
            for (int i = 0; i < values.length; i++) {
                out.append(i == 0 ? "" : ", ").append(Double.toString(values[i]));
            }
            out.append(']');
        } else if (SCALARS.contains(value.getClass())) {
            out.append(value.getClass().getSimpleName()).append(':').append(scalar(value));
        } else {
            throw new IllegalArgumentException("no canonical rendering for " + value.getClass().getName());
        }
    }

    private static String scalar(Object value) {
        if (value instanceof String s) {
            return escape(s);
        }
        if (value instanceof Double || value instanceof Float) {
            return Double.toString(((Number) value).doubleValue());
        }
        return value.toString();
    }

    /**
     * The components of {@code record}, name to value, in declaration order, read through their accessors.
     *
     * @throws IllegalStateException if an accessor fails
     */
    static Map<String, Object> components(Record record) {
        Map<String, Object> components = new LinkedHashMap<>();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            try {
                components.put(component.getName(), component.getAccessor().invoke(record));
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("cannot read " + record.getClass().getSimpleName() + "."
                        + component.getName(), e);
            }
        }
        return components;
    }

    private static void renderRecord(Record record, List<String> omitted, StringBuilder out) {
        out.append(record.getClass().getSimpleName()).append('(');
        String separator = "";
        for (Map.Entry<String, Object> component : components(record).entrySet()) {
            if (omitted.contains(component.getKey())) {
                continue;
            }
            out.append(separator).append(component.getKey()).append('=');
            render(component.getValue(), out);
            separator = ", ";
        }
        out.append(')');
    }
}
