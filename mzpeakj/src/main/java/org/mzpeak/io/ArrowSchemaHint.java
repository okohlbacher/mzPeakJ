package org.mzpeak.io;

import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * The {@code ARROW:schema} footer key-value of a split metadata member: the Arrow schema an Arrow reader of the
 * member takes its column types from, as an Arrow IPC Schema message, encapsulated (continuation marker
 * {@code 0xFFFFFFFF}, the message length, the message padded to 8 bytes) and base64-encoded, as Arrow writers store it.
 *
 * <p>The Rust reference reader casts the columns of {@code auxiliary_arrays} to 64-bit-offset lists and strings
 * ({@code data}, {@code parameters} and {@code data_processing_id}) and panics on every wavelength spectrum when they
 * are not: without this key arrow-rs reads a Parquet list as a 32-bit-offset list and a string as {@code Utf8}. The
 * hint therefore gives {@code auxiliary_arrays} exactly the types of the reference's has_uv; every other field gets the
 * type arrow-rs infers from the Parquet schema without a hint, so no other column is read differently. arrow-rs takes a
 * list or struct hint only when it matches the Parquet field's shape and child names, and a primitive hint only where
 * it can change the inferred type, so a hint mirroring the schema is safe.
 *
 * <p>Inference covers the types the split writer uses: BOOLEAN, INT32 and INT64 without annotation or with an integer
 * annotation, DOUBLE, BINARY as string, structs, and standard three-level lists.
 */
final class ArrowSchemaHint {

    /** The footer key Arrow readers look the schema up under. */
    static final String KEY = "ARROW:schema";

    /** The top-level field whose subtree takes the reference's types. */
    private static final String AUXILIARY_ARRAYS = "auxiliary_arrays";

    // Arrow IPC (Schema.fbs, Message.fbs): the Type union's ids, FloatingPoint precision, MetadataVersion V5 and the
    // MessageHeader id of Schema.
    private static final int TYPE_INT = 2;
    private static final int TYPE_FLOATING_POINT = 3;
    private static final int TYPE_UTF8 = 5;
    private static final int TYPE_BOOL = 6;
    private static final int TYPE_LIST = 12;
    private static final int TYPE_STRUCT = 13;
    private static final int TYPE_LARGE_UTF8 = 20;
    private static final int TYPE_LARGE_LIST = 21;
    private static final int PRECISION_DOUBLE = 2;
    private static final short METADATA_V5 = 4;
    private static final int HEADER_SCHEMA = 1;

    /** An Arrow data type: its Type union id and, for Int and FloatingPoint, the fields of its table. */
    record ArrowType(int id, int bitWidth, boolean signed, int precision) {

        static ArrowType of(int id) {
            return new ArrowType(id, 0, false, 0);
        }

        static ArrowType integer(int bitWidth, boolean signed) {
            return new ArrowType(TYPE_INT, bitWidth, signed, 0);
        }
    }

    /** An Arrow field: name, nullability, type and child fields (the element of a list, the fields of a struct). */
    record ArrowField(String name, boolean nullable, ArrowType type, List<ArrowField> children) {
    }

    private static final ArrowType BOOL = ArrowType.of(TYPE_BOOL);
    private static final ArrowType DOUBLE = new ArrowType(TYPE_FLOATING_POINT, 0, false, PRECISION_DOUBLE);
    private static final ArrowType UTF8 = ArrowType.of(TYPE_UTF8);
    private static final ArrowType LARGE_UTF8 = ArrowType.of(TYPE_LARGE_UTF8);
    private static final ArrowType LIST = ArrowType.of(TYPE_LIST);
    private static final ArrowType LARGE_LIST = ArrowType.of(TYPE_LARGE_LIST);
    private static final ArrowType STRUCT = ArrowType.of(TYPE_STRUCT);

    private ArrowSchemaHint() {
    }

    /**
     * The {@code ARROW:schema} value for a member written with {@code schema}: its fields as arrow-rs infers them,
     * {@code auxiliary_arrays} as the reference types it.
     *
     * @throws IllegalArgumentException if a primitive field has a type outside those listed in the class comment
     */
    static String encode(MessageType schema) {
        return Base64.getEncoder().encodeToString(encapsulate(message(fields(schema))));
    }

    /** The Arrow fields of {@code schema}, as {@link #encode} hints them. */
    static List<ArrowField> fields(MessageType schema) {
        List<ArrowField> out = new ArrayList<>();
        for (Type t : schema.getFields()) {
            out.add(AUXILIARY_ARRAYS.equals(t.getName()) ? auxiliaryArrays() : inferred(t));
        }
        return out;
    }

    // ---- types ----------------------------------------------------------------------------------------------------

    /** The field arrow-rs infers for the Parquet field {@code t} without a hint. */
    private static ArrowField inferred(Type t) {
        boolean nullable = t.getRepetition() != Type.Repetition.REQUIRED;
        if (t.isPrimitive()) {
            return new ArrowField(t.getName(), nullable, primitive(t.asPrimitiveType()), List.of());
        }
        GroupType g = t.asGroupType();
        if (g.getLogicalTypeAnnotation() instanceof LogicalTypeAnnotation.ListLogicalTypeAnnotation) {
            // standard three-level list: optional group <name> (LIST) { repeated group list { <element> } }
            Type element = g.getType(0).asGroupType().getType(0);
            return new ArrowField(t.getName(), nullable, LIST, List.of(inferred(element)));
        }
        List<ArrowField> children = new ArrayList<>();
        for (Type child : g.getFields()) {
            children.add(inferred(child));
        }
        return new ArrowField(t.getName(), nullable, STRUCT, children);
    }

    private static ArrowType primitive(PrimitiveType p) {
        LogicalTypeAnnotation a = p.getLogicalTypeAnnotation();
        switch (p.getPrimitiveTypeName()) {
            case BOOLEAN:
                return BOOL;
            case DOUBLE:
                return DOUBLE;
            case INT32:
            case INT64:
                if (a == null) {
                    return ArrowType.integer(p.getPrimitiveTypeName() == PrimitiveTypeName.INT32 ? 32 : 64, true);
                }
                if (a instanceof LogicalTypeAnnotation.IntLogicalTypeAnnotation i) {
                    return ArrowType.integer(i.getBitWidth(), i.isSigned());
                }
                break;
            case BINARY:
                if (a instanceof LogicalTypeAnnotation.StringLogicalTypeAnnotation) {
                    return UTF8;
                }
                break;
            default:
                break;
        }
        throw new IllegalArgumentException("no Arrow type hint for " + p);
    }

    /**
     * {@code auxiliary_arrays} as the reference's has_uv types it: {@code large_list<item: struct<data:
     * large_list<item: uint8 not null>, name: param, data_type: string, compression: string, items: uint64, unit:
     * string, parameters: large_list<item: param>, data_processing_id: large_string>>}, every field nullable but the
     * bytes of {@code data}.
     */
    private static ArrowField auxiliaryArrays() {
        ArrowField data = new ArrowField("data", true, LARGE_LIST,
                List.of(new ArrowField("item", false, ArrowType.integer(8, false), List.of())));
        ArrowField item = new ArrowField("item", true, STRUCT, List.of(
                data,
                param("name"),
                leaf("data_type", UTF8),
                leaf("compression", UTF8),
                leaf("items", ArrowType.integer(64, false)),
                leaf("unit", UTF8),
                new ArrowField("parameters", true, LARGE_LIST, List.of(param("item"))),
                leaf("data_processing_id", LARGE_UTF8)));
        return new ArrowField(AUXILIARY_ARRAYS, true, LARGE_LIST, List.of(item));
    }

    /** The reference's param struct: {@code value: struct<integer, float, string, boolean>, accession, name, unit}. */
    private static ArrowField param(String name) {
        ArrowField value = new ArrowField("value", true, STRUCT, List.of(
                leaf("integer", ArrowType.integer(64, true)),
                leaf("float", DOUBLE),
                leaf("string", LARGE_UTF8),
                leaf("boolean", BOOL)));
        return new ArrowField(name, true, STRUCT, List.of(value, leaf("accession", UTF8),
                leaf("name", LARGE_UTF8), leaf("unit", UTF8)));
    }

    private static ArrowField leaf(String name, ArrowType type) {
        return new ArrowField(name, true, type, List.of());
    }

    // ---- IPC encoding ---------------------------------------------------------------------------------------------

    /** The encapsulated message: continuation marker, metadata length, the message padded to a multiple of 8. */
    private static byte[] encapsulate(byte[] message) {
        int padded = (message.length + 7) & ~7;
        ByteBuffer out = ByteBuffer.allocate(8 + padded).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0xFFFFFFFF).putInt(padded).put(message);
        return out.array();
    }

    /** The flatbuffer of {@code Message { version: V5, header: Schema { fields } }}. */
    private static byte[] message(List<ArrowField> fields) {
        Flatbuffer fb = new Flatbuffer();
        int rootSlot = fb.reserveOffset();
        fb.patch(rootSlot, fb.table(List.of(
                Slot.int16(METADATA_V5),
                Slot.uint8(HEADER_SCHEMA),
                Slot.child(b -> b.table(List.of(Slot.absent(), Slot.child(b2 -> b2.tables(fields,
                        ArrowSchemaHint::field))))))));
        return fb.bytes();
    }

    /** {@code Field { name, nullable, type_type, type, dictionary: absent, children }}. */
    private static int field(Flatbuffer fb, ArrowField f) {
        return fb.table(List.of(
                Slot.child(b -> b.string(f.name())),
                Slot.uint8(f.nullable() ? 1 : 0),
                Slot.uint8(f.type().id()),
                Slot.child(b -> type(b, f.type())),
                Slot.absent(),
                Slot.child(b -> b.tables(f.children(), ArrowSchemaHint::field))));
    }

    /** The type's table: {@code Int { bitWidth, is_signed }}, {@code FloatingPoint { precision }}, else empty. */
    private static int type(Flatbuffer fb, ArrowType t) {
        if (t.id() == TYPE_INT) {
            return fb.table(List.of(Slot.int32(t.bitWidth()), Slot.uint8(t.signed() ? 1 : 0)));
        }
        if (t.id() == TYPE_FLOATING_POINT) {
            return fb.table(List.of(Slot.int16(t.precision())));
        }
        return fb.table(List.of());
    }

    /** A table field: absent, a little-endian scalar of {@code size} bytes, or an offset to a {@code child}. */
    private record Slot(int size, long value, ChildWriter child) {

        static Slot absent() {
            return new Slot(0, 0, null);
        }

        static Slot uint8(int v) {
            return new Slot(1, v, null);
        }

        static Slot int16(int v) {
            return new Slot(2, v, null);
        }

        static Slot int32(int v) {
            return new Slot(4, v, null);
        }

        static Slot child(ChildWriter w) {
            return new Slot(4, 0, w);
        }
    }

    /** Writes a child object (table, string or vector) and returns its position. */
    @FunctionalInterface
    private interface ChildWriter {
        int write(Flatbuffer fb);
    }

    /** Writes an element of a vector of tables and returns its position. */
    @FunctionalInterface
    private interface ElementWriter<T> {
        int write(Flatbuffer fb, T element);
    }

    /**
     * A flatbuffer laid out front to back: every object is written after the offsets that refer to it, so each
     * uoffset is positive, and each vtable directly before its table. Tables, vectors and strings start 4-aligned;
     * scalars are aligned to their size.
     */
    private static final class Flatbuffer {

        private byte[] buf = new byte[1024];
        private int pos;

        /** A 4-aligned uoffset slot to {@link #patch} later. */
        int reserveOffset() {
            align(4);
            int at = pos;
            pos += 4;
            ensure(0);
            return at;
        }

        /** Points the uoffset at {@code slot} to {@code target}, which lies after it. */
        void patch(int slot, int target) {
            putInt(slot, target - slot);
        }

        /** A table of {@code slots}, the vtable right before it, then its children; returns the table's position. */
        int table(List<Slot> slots) {
            int vtableSize = 4 + 2 * slots.size();
            // the vtable is 2-aligned and ends where the 4-aligned table starts
            align(4);
            if (vtableSize % 4 != 0) {
                pos += 2;
            }
            int vtable = pos;
            int table = vtable + vtableSize;
            pos = table + 4;
            ensure(0);
            int[] fieldOffsets = new int[slots.size()];
            for (int i = 0; i < slots.size(); i++) {
                Slot s = slots.get(i);
                if (s.size() > 0) {
                    align(s.size());
                    fieldOffsets[i] = pos - table;
                    ensure(s.size());
                    for (int b = 0; b < s.size(); b++) {
                        buf[pos++] = (byte) (s.value() >>> (8 * b));
                    }
                }
            }
            int tableSize = pos - table;
            putShort(vtable, vtableSize);
            putShort(vtable + 2, tableSize);
            for (int i = 0; i < slots.size(); i++) {
                putShort(vtable + 4 + 2 * i, fieldOffsets[i]);
            }
            putInt(table, table - vtable);
            for (int i = 0; i < slots.size(); i++) {
                Slot s = slots.get(i);
                if (s.child() != null) {
                    patch(table + fieldOffsets[i], s.child().write(this));
                }
            }
            return table;
        }

        /** A vector of tables, one per element, written after it; returns the vector's position. */
        <T> int tables(List<T> elements, ElementWriter<T> writer) {
            align(4);
            int vector = pos;
            pos += 4;
            ensure(0);
            putInt(vector, elements.size());
            int[] slots = new int[elements.size()];
            for (int i = 0; i < slots.length; i++) {
                slots[i] = reserveOffset();
            }
            for (int i = 0; i < slots.length; i++) {
                patch(slots[i], writer.write(this, elements.get(i)));
            }
            return vector;
        }

        /** A null-terminated UTF-8 string; returns its position. */
        int string(String s) {
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            align(4);
            int at = pos;
            ensure(4 + utf8.length + 1);
            putInt(at, utf8.length);
            System.arraycopy(utf8, 0, buf, at + 4, utf8.length);
            pos = at + 4 + utf8.length + 1; // the terminator is the zero the buffer holds
            return at;
        }

        byte[] bytes() {
            return Arrays.copyOf(buf, pos);
        }

        private void align(int n) {
            pos = (pos + n - 1) / n * n;
            ensure(0);
        }

        /** Grows the buffer to hold {@code n} bytes past {@link #pos}. */
        private void ensure(int n) {
            if (pos + n > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, pos + n));
            }
        }

        private void putShort(int at, int v) {
            buf[at] = (byte) v;
            buf[at + 1] = (byte) (v >>> 8);
        }

        private void putInt(int at, int v) {
            for (int b = 0; b < 4; b++) {
                buf[at + b] = (byte) (v >>> (8 * b));
            }
        }
    }
}
