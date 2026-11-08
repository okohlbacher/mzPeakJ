package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Numpress PIC decode of {@link ChunkCodecs#decodeNumpress} (MZ-23, design §5.7). A PIC payload has no
 * fixed-point header, so it may be shorter than the 8 bytes the vendored {@code MSNumpress.decode} asks for: one count
 * takes one to five bytes. A PIC count is the low 32 bits of a rounded non-negative intensity, which the C++
 * reference (pynumpress) decodes unsigned, 0 to 4294967294, and the vendored decoder sign-extends; the reader decodes
 * it unsigned, as the reference does. The four payloads of {@link #payloadsUnderEightBytesDecodeToUnsignedCounts} are
 * those of the design (revision 3), which decode with pynumpress to the same values.
 */
class ChunkCodecsPicTest {

    private static final String FIELD = "intensity_numpress_pic_bytes";

    /** A chunk struct with the PIC column as {@link NumpressSignalWriter} writes it: a {@code list<uint8>}. */
    private static final MessageType SCHEMA = Types.buildMessage()
            .addField(Types.optionalGroup().as(LogicalTypeAnnotation.listType())
                    .repeatedGroup()
                    .required(PrimitiveTypeName.INT32).as(LogicalTypeAnnotation.intType(8, false)).named("item")
                    .named("list")
                    .named(FIELD))
            .named("chunk");

    @Test
    void payloadsUnderEightBytesDecodeToUnsignedCounts() {
        assertThat(decode(0x00, 0x0e, 0x50, 0xd2, 0xb0)).containsExactly(3e9);
        assertThat(decode(0x00, 0x00, 0x00, 0x00, 0x80)).containsExactly(2147483648.0);
        assertThat(decode(0x0e, 0xff, 0xff, 0xff, 0xf0)).containsExactly(4294967294.0);
        assertThat(decode(0x0f, 0xff, 0xff, 0xff, 0x70)).containsExactly(2147483647.0);
    }

    /** The shortest payloads: one byte holds the count 0, two bytes the counts 0 and 23. */
    @Test
    void oneAndTwoBytePayloadsDecode() {
        assertThat(decode(0x80)).containsExactly(0.0);
        assertThat(decode(0x86, 0x71)).containsExactly(0.0, 23.0);
    }

    /**
     * A payload of 8 bytes or more, which {@code MSNumpress.decode} accepts, also reads its counts at and above 2^31
     * unsigned; the counts below 2^31 are unchanged.
     */
    @Test
    void countsAtAndAbove2To31AreUnsignedInLongPayloadsToo() {
        double[] counts = {3e9, 0.0, 4294967294.0, 23.0, 2147483648.0, 2147483647.0, 1.0};
        byte[] encoded = new byte[5 * counts.length];
        int length = MSNumpress.encodePic(counts, counts.length, encoded);
        assertThat(length).isGreaterThanOrEqualTo(8);

        assertThat(decode(Arrays.copyOf(encoded, length))).containsExactly(counts);
    }

    @Test
    void anEmptyPayloadDecodesToNoCounts() {
        assertThat(decode(new byte[0])).isEmpty();
    }

    /** A payload whose last count is cut off fails, naming what was decoded and the spectrum. */
    @Test
    void aTruncatedPayloadFailsNamingWhatAndTheSpectrum() {
        assertThatThrownBy(() -> ChunkCodecs.decodeNumpress(chunk(new byte[] {0x00}), FIELD,
                MSNumpress.ACC_NUMPRESS_PIC, 7, "intensity"))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessage("Failed to Numpress-decode intensity for spectrum_index 7")
                .cause()
                .isInstanceOf(ArrayIndexOutOfBoundsException.class);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static double[] decode(int... payload) {
        byte[] bytes = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) {
            bytes[i] = (byte) payload[i];
        }
        return decode(bytes);
    }

    private static double[] decode(byte[] payload) {
        return ChunkCodecs.decodeNumpress(chunk(payload), FIELD, MSNumpress.ACC_NUMPRESS_PIC, 0, "intensity");
    }

    /** A chunk struct whose PIC column holds {@code payload}. */
    private static Group chunk(byte[] payload) {
        Group chunk = new SimpleGroupFactory(SCHEMA).newGroup();
        Group list = chunk.addGroup(FIELD);
        for (byte b : payload) {
            list.addGroup("list").add("item", b & 0xFF);
        }
        return chunk;
    }
}
