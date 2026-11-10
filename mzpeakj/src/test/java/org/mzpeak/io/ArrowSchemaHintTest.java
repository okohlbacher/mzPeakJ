package org.mzpeak.io;

import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.ArrowSchemaHint.ArrowField;
import org.mzpeak.io.ArrowSchemaHint.ArrowType;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ArrowSchemaHint}: the {@code ARROW:schema} footer value is an encapsulated Arrow IPC Schema message, which a
 * flatbuffer reader written here, independently of the encoder, decodes to the intended fields: every field typed as
 * arrow-rs infers it from the Parquet schema, and {@code auxiliary_arrays} exactly as in the reference's has_uv, whose
 * own {@code ARROW:schema} the same reader decodes. pyarrow and the Rust reference reader (rdump) decoded the written
 * hint too (RustReaderAcceptanceTest).
 */
class ArrowSchemaHintTest {

    private static final String HAS_UV = "has_uv.mzpeak";
    private static final List<String> HINTED = List.of("spectra_metadata.parquet",
            "wavelength_spectra_metadata.parquet");

    private static final ArrowType UINT64 = new ArrowType(2, 64, false, 0);
    private static final ArrowType INT64 = new ArrowType(2, 64, true, 0);
    private static final ArrowType INT32 = new ArrowType(2, 32, true, 0);
    private static final ArrowType UINT32 = new ArrowType(2, 32, false, 0);
    private static final ArrowType DOUBLE = new ArrowType(3, 0, false, 2);
    private static final ArrowType UTF8 = ArrowType.of(5);
    private static final ArrowType BOOL = ArrowType.of(6);
    private static final ArrowType LIST = ArrowType.of(12);
    private static final ArrowType STRUCT = ArrowType.of(13);
    private static final ArrowType LARGE_UTF8 = ArrowType.of(20);
    private static final ArrowType LARGE_LIST = ArrowType.of(21);

    @TempDir
    Path tmp;

    /**
     * Both hinted members of a split write of has_uv carry the hint of their own schema, which decodes to the fields
     * {@link ArrowSchemaHint#fields} gives, and whose {@code auxiliary_arrays} equals the reference's.
     */
    @Test
    void theWrittenHintDecodesToItsFieldsAndAuxiliaryArraysAsTheReference() {
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current(HAS_UV));
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, Options.defaults().withMetadataLayout(MetadataLayout.SPLIT));
        for (String member : HINTED) {
            ParquetFooter written = footer(out, member);
            List<ArrowField> decoded = decode(written.keyValue().get(ArrowSchemaHint.KEY));
            assertThat(decoded).as(member).isEqualTo(ArrowSchemaHint.fields(written.schema()));
            assertThat(decoded).as(member).extracting(ArrowField::name).isEqualTo(written.topLevelFields());

            ArrowField reference = field(decode(footer(Fixtures.current(HAS_UV), member).keyValue()
                    .get(ArrowSchemaHint.KEY)), "auxiliary_arrays");
            assertThat(field(decoded, "auxiliary_arrays")).as(member).isEqualTo(reference);
            assertThat(reference.type()).isEqualTo(LARGE_LIST);
        }
    }

    /**
     * Every field but {@code auxiliary_arrays} is typed as arrow-rs infers it without a hint: unsigned and signed
     * integers by their annotation, DOUBLE, BOOLEAN, strings as Utf8, lists as 32-bit-offset lists named after their
     * Parquet element, structs; nullable unless required.
     */
    @Test
    void otherFieldsAreTypedAsInferred() {
        MessageType schema = Types.buildMessage()
                .required(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("index")
                .optional(PrimitiveTypeName.INT32).named("ms_level")
                .optional(PrimitiveTypeName.INT32).as(LogicalTypeAnnotation.intType(32, false)).named("count")
                .optional(PrimitiveTypeName.DOUBLE).named("time")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("id")
                .addField(ParquetOutput.paramListField())
                .named("m");
        ArrowField value = new ArrowField("value", true, STRUCT, List.of(leaf("integer", INT64),
                leaf("float", DOUBLE), leaf("string", UTF8), leaf("boolean", BOOL)));
        ArrowField param = new ArrowField("item", true, STRUCT, List.of(value, leaf("accession", UTF8),
                leaf("name", UTF8), leaf("unit", UTF8)));
        List<ArrowField> expected = List.of(
                new ArrowField("index", false, UINT64, List.of()),
                leaf("ms_level", INT32),
                leaf("count", UINT32),
                leaf("time", DOUBLE),
                leaf("id", UTF8),
                new ArrowField("parameters", true, LIST, List.of(param)));
        assertThat(ArrowSchemaHint.fields(schema)).isEqualTo(expected);
        assertThat(decode(ArrowSchemaHint.encode(schema))).isEqualTo(expected);
    }

    /** {@code auxiliary_arrays} takes the reference's Large types whatever the Parquet field holds. */
    @Test
    void auxiliaryArraysTakeTheReferenceTypes() {
        MessageType schema = Types.buildMessage().addField(ParquetOutput.auxiliaryArrayListField()).named("m");
        ArrowField aux = ArrowSchemaHint.fields(schema).get(0);
        assertThat(aux.type()).isEqualTo(LARGE_LIST);
        ArrowField item = aux.children().get(0);
        assertThat(item.children()).extracting(ArrowField::name).containsExactly("data", "name", "data_type",
                "compression", "items", "unit", "parameters", "data_processing_id");
        assertThat(item.children()).extracting(f -> f.type()).containsExactly(LARGE_LIST, STRUCT, UTF8, UTF8,
                UINT64, UTF8, LARGE_LIST, LARGE_UTF8);
        assertThat(item.children().get(0).children()).containsExactly(
                new ArrowField("item", false, new ArrowType(2, 8, false, 0), List.of()));
        assertThat(field(item.children().get(1).children(), "name").type()).isEqualTo(LARGE_UTF8);
    }

    /** The value is base64 of the continuation marker, the padded length, and the message padded to 8 bytes. */
    @Test
    void theMessageIsEncapsulated() {
        MessageType schema = Types.buildMessage().optional(PrimitiveTypeName.DOUBLE).named("x").named("m");
        byte[] bytes = Base64.getDecoder().decode(ArrowSchemaHint.encode(schema));
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(b.getInt(0)).isEqualTo(0xFFFFFFFF);
        int length = b.getInt(4);
        assertThat(length % 8).isZero();
        assertThat(bytes).hasSize(8 + length);
    }

    /** A primitive type the split writer does not use has no hint. */
    @ParameterizedTest
    @ValueSource(strings = {"float", "date", "binary"})
    void anUnsupportedPrimitiveTypeIsRefused(String kind) {
        MessageType schema = switch (kind) {
            case "float" -> Types.buildMessage().optional(PrimitiveTypeName.FLOAT).named("f").named("m");
            case "date" -> Types.buildMessage().optional(PrimitiveTypeName.INT32).as(LogicalTypeAnnotation.dateType())
                    .named("d").named("m");
            default -> Types.buildMessage().optional(PrimitiveTypeName.BINARY).named("b").named("m");
        };
        assertThatThrownBy(() -> ArrowSchemaHint.encode(schema)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("no Arrow type hint for ");
    }

    // ---- a flatbuffer reader of the Schema message ----------------------------------------------------------------

    private static ArrowField leaf(String name, ArrowType type) {
        return new ArrowField(name, true, type, List.of());
    }

    private static ArrowField field(List<ArrowField> fields, String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    private static ParquetFooter footer(Path dataset, String member) {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            return ParquetFooter.read(source.inputFile(member));
        }
    }

    /** The fields of the Schema message in the encapsulated, base64-encoded {@code value}. */
    static List<ArrowField> decode(String value) {
        byte[] bytes = Base64.getDecoder().decode(value);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(b.getInt(0)).isEqualTo(0xFFFFFFFF);
        ByteBuffer fb = ByteBuffer.wrap(bytes, 8, bytes.length - 8).slice().order(ByteOrder.LITTLE_ENDIAN);
        int message = fb.getInt(0);
        assertThat(fb.getShort(slot(fb, message, 0) + message)).as("MetadataVersion V5").isEqualTo((short) 4);
        assertThat(fb.get(slot(fb, message, 1) + message)).as("header type Schema").isEqualTo((byte) 1);
        int schema = indirect(fb, message, 2);
        return fields(fb, indirect(fb, schema, 1));
    }

    private static List<ArrowField> fields(ByteBuffer fb, int vector) {
        List<ArrowField> out = new ArrayList<>();
        int n = fb.getInt(vector);
        for (int i = 0; i < n; i++) {
            int at = vector + 4 + 4 * i;
            out.add(fieldAt(fb, at + fb.getInt(at)));
        }
        return out;
    }

    private static ArrowField fieldAt(ByteBuffer fb, int table) {
        int nameAt = indirect(fb, table, 0);
        byte[] name = new byte[fb.getInt(nameAt)];
        fb.get(nameAt + 4, name);
        boolean nullable = slot(fb, table, 1) != 0 && fb.get(table + slot(fb, table, 1)) != 0;
        int typeId = fb.get(table + slot(fb, table, 2));
        int type = indirect(fb, table, 3);
        ArrowType t = switch (typeId) {
            case 2 -> new ArrowType(2, fb.getInt(type + slot(fb, type, 0)),
                    slot(fb, type, 1) != 0 && fb.get(type + slot(fb, type, 1)) != 0, 0);
            case 3 -> new ArrowType(3, 0, false, fb.getShort(type + slot(fb, type, 0)));
            default -> ArrowType.of(typeId);
        };
        int children = slot(fb, table, 5);
        return new ArrowField(new String(name, StandardCharsets.UTF_8), nullable, t,
                children == 0 ? List.of() : fields(fb, indirect(fb, table, 5)));
    }

    /** The offset in {@code table} of field {@code index}, 0 when absent. */
    private static int slot(ByteBuffer fb, int table, int index) {
        int vtable = table - fb.getInt(table);
        int vtableSize = fb.getShort(vtable);
        return 4 + 2 * index < vtableSize ? fb.getShort(vtable + 4 + 2 * index) : 0;
    }

    /** The position the uoffset field {@code index} of {@code table} points to. */
    private static int indirect(ByteBuffer fb, int table, int index) {
        int at = table + slot(fb, table, index);
        return at + fb.getInt(at);
    }
}
