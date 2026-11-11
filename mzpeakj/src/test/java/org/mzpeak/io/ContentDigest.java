package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.FileMetaData;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;
import org.mzpeak.io.parquet.ParquetGroups;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A digest of what a dataset member contains, independent of how its bytes are compressed or packed. Read
 * through {@link MzPeakSource#inputFile(String)}, so a member of an unpacked directory and the same member inside
 * a ZIP digest alike.
 *
 * <ul>
 *   <li><b>Parquet members</b> (both ends carry the {@code PAR1} magic): a SHA-256 over the schema
 *       ({@link #schema}, the text of {@code MessageType.toString()} in any default locale), the footer key-values sorted by key, the row-group count and every record
 *       in file order. ZSTD page bytes, page layout and the {@code created_by} string do not enter it.
 *   <li><b>Other members</b> (the manifest): a SHA-256 over the raw bytes.
 * </ul>
 *
 * <p>Each record is rendered like {@code Group.toString()} (one line per present value, its field name, nested
 * groups indented by two spaces, absent values omitted), except for the leaves: FLOAT and DOUBLE are written as
 * their raw IEEE-754 bits, BINARY, FIXED_LEN_BYTE_ARRAY and INT96 as hex bytes. {@code Group.toString()} formats
 * doubles with {@link Double#toString(double)}, whose output changed in JDK 19 (JDK-4511638), and CI builds on
 * JDK 17 and 21; raw bits keep the digest the same on every JDK and also tell -0.0 from 0.0 and NaN payloads
 * apart. Every string fed to the digest is prefixed with its UTF-8 length, so section and record boundaries are
 * unambiguous.
 *
 * @param member the member name in the dataset
 * @param kind   {@code parquet} or {@code bytes}
 * @param detail human-readable facts for a mismatch report: row groups, rows and short digests of the schema,
 *               the key-values and the records (Parquet), or the size (bytes)
 * @param sha256 the content digest, lowercase hex
 */
public record ContentDigest(String member, String kind, String detail, String sha256) {

    private static final byte[] PARQUET_MAGIC = "PAR1".getBytes(StandardCharsets.US_ASCII);
    private static final HexFormat HEX = HexFormat.of();
    /** Length of the part digests shown in {@link #detail}. */
    private static final int SHORT = 16;

    /** The digest of {@code member}, read from {@code source}. */
    public static ContentDigest of(MzPeakSource source, String member) {
        InputFile input = source.inputFile(member);
        try {
            return isParquet(input) ? parquet(member, input) : bytes(member, input);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to digest " + member + " in " + source.describe(), e);
        }
    }

    /** One TSV line: member, kind, detail, sha256. */
    public String tsvLine() {
        return member + '\t' + kind + '\t' + detail + '\t' + sha256;
    }

    private static boolean isParquet(InputFile input) throws IOException {
        long length = input.getLength();
        if (length < 2L * PARQUET_MAGIC.length) {
            return false;
        }
        byte[] head = new byte[PARQUET_MAGIC.length];
        byte[] tail = new byte[PARQUET_MAGIC.length];
        try (SeekableInputStream in = input.newStream()) {
            in.readFully(head);
            in.seek(length - PARQUET_MAGIC.length);
            in.readFully(tail);
        }
        return Arrays.equals(head, PARQUET_MAGIC) && Arrays.equals(tail, PARQUET_MAGIC);
    }

    private static ContentDigest bytes(String member, InputFile input) throws IOException {
        MessageDigest all = newSha256();
        byte[] buf = new byte[1 << 16];
        long size = 0;
        try (SeekableInputStream in = input.newStream()) {
            for (int n = in.read(buf); n != -1; n = in.read(buf)) {
                all.update(buf, 0, n);
                size += n;
            }
        }
        return new ContentDigest(member, "bytes", "size=" + size, HEX.formatHex(all.digest()));
    }

    private static ContentDigest parquet(String member, InputFile input) throws IOException {
        MessageDigest all = newSha256();
        MessageDigest schemaPart = newSha256();
        MessageDigest keyValuePart = newSha256();
        MessageDigest recordPart = newSha256();

        FileMetaData footer;
        int rowGroups;
        long rows;
        try (ParquetFileReader reader = ParquetFileReader.open(input, ParquetGroups.readOptions())) {
            footer = reader.getFooter().getFileMetaData();
            rowGroups = reader.getRowGroups().size();
            rows = reader.getRecordCount();
        }
        Map<String, String> keyValues = new TreeMap<>(footer.getKeyValueMetaData());

        put("schema", all);
        put(schema(footer.getSchema()), all, schemaPart);
        put("key_values", all);
        putLong(keyValues.size(), all, keyValuePart);
        for (Map.Entry<String, String> kv : keyValues.entrySet()) {
            put(kv.getKey(), all, keyValuePart);
            put(kv.getValue(), all, keyValuePart);
        }
        put("row_groups", all);
        putLong(rowGroups, all);
        put("records", all);
        ParquetGroups.forEach(input, record -> {
            StringBuilder text = new StringBuilder();
            render(record, "", text);
            put(text.toString(), all, recordPart);
        });

        String detail = "row_groups=" + rowGroups + " rows=" + rows
                + " schema=" + shortHex(schemaPart)
                + " key_values=" + keyValues.size() + ":" + shortHex(keyValuePart)
                + " records=" + shortHex(recordPart);
        return new ContentDigest(member, "parquet", detail, HEX.formatHex(all.digest()));
    }

    /**
     * {@code schema} as {@code MessageType.toString()} renders it in the root locale. parquet-java 1.17.1 lowercases
     * a primitive type name with the default locale ({@code PrimitiveType.writeToStringBuilder}), so under
     * {@code tr_TR} {@code INT64} becomes {@code ınt64}; this walker lowercases with {@link Locale#ROOT} and is
     * otherwise the same, so the digest does not depend on the locale and the pinned digests stay.
     */
    static String schema(MessageType schema) {
        StringBuilder out = new StringBuilder("message ").append(schema.getName());
        appendAnnotation(schema, out);
        out.append(" {\n");
        appendMembers(schema, "  ", out);
        return out.append("}\n").toString();
    }

    /** {@code GroupType.membersDisplayString}: each field, a primitive one followed by {@code ;}, then a newline. */
    private static void appendMembers(GroupType group, String indent, StringBuilder out) {
        for (Type field : group.getFields()) {
            out.append(indent).append(field.getRepetition().name().toLowerCase(Locale.ROOT));
            if (field.isPrimitive()) {
                PrimitiveType primitive = field.asPrimitiveType();
                out.append(' ').append(primitive.getPrimitiveTypeName().name().toLowerCase(Locale.ROOT));
                if (primitive.getPrimitiveTypeName() == PrimitiveType.PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY) {
                    out.append('(').append(primitive.getTypeLength()).append(')');
                }
                out.append(' ').append(field.getName());
                appendAnnotation(field, out);
                appendId(field, out);
                out.append(';');
            } else {
                out.append(" group ").append(field.getName());
                appendAnnotation(field, out);
                appendId(field, out);
                out.append(" {\n");
                appendMembers(field.asGroupType(), indent + "  ", out);
                out.append(indent).append('}');
            }
            out.append('\n');
        }
    }

    private static void appendAnnotation(Type type, StringBuilder out) {
        if (type.getLogicalTypeAnnotation() != null) {
            out.append(" (").append(type.getLogicalTypeAnnotation()).append(')');
        }
    }

    private static void appendId(Type type, StringBuilder out) {
        if (type.getId() != null) {
            out.append(" = ").append(type.getId());
        }
    }

    /** Appends {@code group} in the {@code Group.toString()} layout, with exact leaf values (class comment). */
    static void render(Group group, String indent, StringBuilder out) {
        GroupType type = group.getType();
        for (int field = 0; field < type.getFieldCount(); field++) {
            Type fieldType = type.getType(field);
            int count = group.getFieldRepetitionCount(field);
            for (int i = 0; i < count; i++) {
                out.append(indent).append(fieldType.getName());
                if (fieldType.isPrimitive()) {
                    out.append(": ").append(leaf(group, field, i, fieldType)).append('\n');
                } else {
                    out.append('\n');
                    render(group.getGroup(field, i), indent + "  ", out);
                }
            }
        }
    }

    private static String leaf(Group group, int field, int i, Type fieldType) {
        switch (fieldType.asPrimitiveType().getPrimitiveTypeName()) {
            case DOUBLE:
                return "d:" + HEX.toHexDigits(Double.doubleToRawLongBits(group.getDouble(field, i)));
            case FLOAT:
                return "f:" + HEX.toHexDigits(Float.floatToRawIntBits(group.getFloat(field, i)));
            case INT32:
                return Integer.toString(group.getInteger(field, i));
            case INT64:
                return Long.toString(group.getLong(field, i));
            case BOOLEAN:
                return Boolean.toString(group.getBoolean(field, i));
            case INT96:
                return "x:" + hex(group.getInt96(field, i));
            case BINARY:
            case FIXED_LEN_BYTE_ARRAY:
                return "x:" + hex(group.getBinary(field, i));
            default:
                throw new IllegalStateException("unknown primitive type of " + fieldType);
        }
    }

    private static String hex(Binary value) {
        return HEX.formatHex(value.getBytes());
    }

    private static void put(String value, MessageDigest... digests) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        putLong(bytes.length, digests);
        for (MessageDigest d : digests) {
            d.update(bytes);
        }
    }

    private static void putLong(long value, MessageDigest... digests) {
        byte[] bytes = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
        for (MessageDigest d : digests) {
            d.update(bytes);
        }
    }

    private static String shortHex(MessageDigest digest) {
        return HEX.formatHex(digest.digest()).substring(0, SHORT);
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
