package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.mzpeak.model.Param;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.List;

/**
 * The {@link Param#value()} types the writer writes (W2, design D13-16): {@code null}; {@code Long}, {@code Integer},
 * {@code Short} and {@code Byte}, the integer slot; {@code Double} and {@code Float}, the float slot; {@code String};
 * {@code Boolean}; and a {@code java.util.Collection} or Java array of these, nested ones included, which is its
 * compact JSON text in a Parquet {@code parameters} list (metadata-tables.md: list values MUST be JSON strings) and a
 * JSON array in the JSON metadata (schema/param.json). Any other type is refused with {@link #require} before
 * anything is written.
 */
final class ParamValues {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private ParamValues() {
    }

    /**
     * Throws {@link MzPeakException} when a param of {@code params} has a value of a type the writer cannot write,
     * naming {@code owner}, the param (its quoted name, else its accession) and the type.
     */
    static void require(String owner, List<Param> params) {
        for (Param p : params) {
            Class<?> type = unsupportedType(p.value());
            if (type != null) {
                throw new MzPeakException(owner + ": the value of the parameter "
                        + (p.name() != null ? "\"" + p.name() + "\"" : p.accession())
                        + (type == p.value().getClass() ? " is a " : " holds a ") + type.getName()
                        + ", which mzPeak cannot write: a parameter value is an integer, a float, a string, a boolean,"
                        + " or a collection or array of these");
            }
        }
    }

    /** True for a value of the integer slot: {@code Long}, {@code Integer}, {@code Short} or {@code Byte}. */
    static boolean isInteger(Object value) {
        return value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte;
    }

    /**
     * {@code value}, which {@link #require} accepted, as JSON: an integer as an integral number, a {@code Float} with
     * the digits of the float, a collection or array as an array of its elements, {@code null} as null.
     */
    static JsonNode json(Object value) {
        if (value instanceof Collection<?> c) {
            ArrayNode array = JSON.arrayNode();
            c.forEach(e -> array.add(json(e)));
            return array;
        }
        if (value != null && value.getClass().isArray()) {
            ArrayNode array = JSON.arrayNode();
            for (int i = 0; i < Array.getLength(value); i++) {
                array.add(json(Array.get(value, i)));
            }
            return array;
        }
        if (value instanceof Boolean b) {
            return JSON.booleanNode(b);
        }
        if (value instanceof String s) {
            return JSON.textNode(s);
        }
        if (value instanceof Double d) {
            return JSON.numberNode(d);
        }
        if (value instanceof Float f) {
            return JSON.numberNode(f);
        }
        if (value instanceof Number n) { // an integer type: require refused every other
            return JSON.numberNode(n.longValue());
        }
        return JSON.nullNode();
    }

    /** The type of {@code value}, or of the first element it holds, that cannot be written, or null if none. */
    private static Class<?> unsupportedType(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Double
                || value instanceof Float || isInteger(value)) {
            return null;
        }
        if (value instanceof Collection<?> c) {
            for (Object e : c) {
                Class<?> type = unsupportedType(e);
                if (type != null) {
                    return type;
                }
            }
            return null;
        }
        if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                Class<?> type = unsupportedType(Array.get(value, i));
                if (type != null) {
                    return type;
                }
            }
            return null;
        }
        return value.getClass();
    }
}
