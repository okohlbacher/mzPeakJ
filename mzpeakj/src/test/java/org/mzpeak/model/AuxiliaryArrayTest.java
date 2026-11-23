package org.mzpeak.model;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AuxiliaryArray} (R1, X-31): the bytes are kept as given, {@link AuxiliaryArray#doubles()} decompresses
 * (none, zlib) and decodes the four numeric data types little-endian, and refuses what it cannot decode with a message
 * naming it; the entities that carry auxiliary arrays.
 */
class AuxiliaryArrayTest {

    private static final Param NAME = new Param("signal to noise array", "MS:1000517", null, null);
    private static final String NONE = "MS:1000576";
    private static final String ZLIB = "MS:1000574";

    // ---- doubles() ----------------------------------------------------------------------------------------------

    @Test
    void eachNumericDataTypeDecodesLittleEndian() {
        ByteBuffer f32 = buffer(3 * Float.BYTES);
        f32.putFloat(1.5f).putFloat(-2.25f).putFloat(3.0f);
        ByteBuffer f64 = buffer(2 * Double.BYTES);
        f64.putDouble(0.1).putDouble(-1e300);
        ByteBuffer i32 = buffer(2 * Integer.BYTES);
        i32.putInt(-7).putInt(Integer.MAX_VALUE);
        ByteBuffer i64 = buffer(2 * Long.BYTES);
        i64.putLong(-3L).putLong(1L << 40);

        assertThat(array(f32.array(), "MS:1000521", NONE, 3).doubles()).containsExactly(1.5, -2.25, 3.0);
        assertThat(array(f64.array(), "MS:1000523", NONE, 2).doubles()).containsExactly(0.1, -1e300);
        assertThat(array(i32.array(), "MS:1000519", NONE, 2).doubles()).containsExactly(-7.0, Integer.MAX_VALUE);
        assertThat(array(i64.array(), "MS:1000522", NONE, 2).doubles()).containsExactly(-3.0, 0x1p40);
        assertThat(array(new byte[0], "MS:1000523", NONE, 0).doubles()).isEmpty();
    }

    @Test
    void zlibIsDecompressedOnDemandAndTheStoredBytesAreKept() {
        ByteBuffer f64 = buffer(3 * Double.BYTES);
        f64.putDouble(1.0).putDouble(2.0).putDouble(3.0);
        byte[] stored = zlib(f64.array());
        AuxiliaryArray a = array(stored, "MS:1000523", ZLIB, 3);

        assertThat(a.data()).isSameAs(stored);
        assertThat(a.doubles()).containsExactly(1.0, 2.0, 3.0);
        assertThat(a.doubles()).isNotSameAs(a.doubles());
        assertThat(a.data()).isEqualTo(zlib(f64.array()));
    }

    @Test
    void anUnknownCompressionOrDataTypeIsRefusedNamingIt() {
        byte[] stored = {1, 2, 3, 4};
        AuxiliaryArray numpress = array(stored, "MS:1000523", "MS:1002312", 1);
        assertThat(numpress.data()).isSameAs(stored);
        assertThatThrownBy(numpress::doubles).isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("auxiliary array signal to noise array: the compression MS:1002312 cannot be decoded;"
                        + " supported are MS:1000576 (no compression) and MS:1000574 (zlib compression)");
        assertThatThrownBy(array(stored, "MS:1001479", NONE, 4)::doubles)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("auxiliary array signal to noise array: the data type MS:1001479 cannot be decoded;"
                        + " supported are MS:1000521 (32-bit float), MS:1000523 (64-bit float), MS:1000519 (32-bit"
                        + " integer) and MS:1000522 (64-bit integer)");
    }

    @Test
    void bytesThatDoNotHoldTheItemsOrCorruptZlibAreRefused() {
        assertThatThrownBy(array(new byte[12], "MS:1000523", NONE, 2)::doubles)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("auxiliary array signal to noise array: 12 bytes do not hold 2 values of MS:1000523 (8"
                        + " bytes each)");
        // item counts whose byte size overflows a long are refused, not wrapped around
        assertThatThrownBy(array(new byte[0], "MS:1000523", NONE, 1L << 61)::doubles)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("auxiliary array signal to noise array: 0 bytes do not hold 2305843009213693952 values of"
                        + " MS:1000523 (8 bytes each)");
        assertThatThrownBy(array(new byte[8], "MS:1000523", NONE, (1L << 61) + 1)::doubles)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("auxiliary array signal to noise array: 8 bytes do not hold 2305843009213693953 values of"
                        + " MS:1000523 (8 bytes each)");
        assertThatThrownBy(array(new byte[0], "MS:1000521", NONE, 1L << 62)::doubles)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("auxiliary array signal to noise array: 0 bytes do not hold 4611686018427387904 values of"
                        + " MS:1000521 (4 bytes each)");
        assertThatThrownBy(array(new byte[] {1, 2, 3}, "MS:1000523", ZLIB, 1)::doubles)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("auxiliary array signal to noise array: the zlib data cannot be decompressed:");
        byte[] zlib = zlib(new byte[8]);
        assertThatThrownBy(array(Arrays.copyOf(zlib, zlib.length - 3), "MS:1000523", ZLIB, 1)::doubles)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("auxiliary array signal to noise array: the zlib data cannot be decompressed:");
    }

    // ---- construction -------------------------------------------------------------------------------------------

    @Test
    void theRequiredComponentsAreCheckedAndTheParamsCopied() {
        List<Param> params = new ArrayList<>(List.of(new Param("comment", null, "x", null)));
        AuxiliaryArray a = new AuxiliaryArray(NAME, new byte[0], "MS:1000523", NONE, 0, "UO:0000186", params, "dp");
        params.clear();
        assertThat(a.parameters()).containsExactly(new Param("comment", null, "x", null));
        assertThatThrownBy(() -> a.parameters().add(NAME)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(a.unit()).isEqualTo("UO:0000186");
        assertThat(a.dataProcessingId()).isEqualTo("dp");
        assertThat(new AuxiliaryArray(NAME, new byte[0], "MS:1000523", NONE, 0, null, null, null).parameters())
                .isEmpty();

        assertThatThrownBy(() -> new AuxiliaryArray(null, new byte[0], "MS:1000523", NONE, 0, null, null, null))
                .isInstanceOf(NullPointerException.class).hasMessage("name");
        assertThatThrownBy(() -> new AuxiliaryArray(NAME, null, "MS:1000523", NONE, 0, null, null, null))
                .isInstanceOf(NullPointerException.class).hasMessage("data");
        assertThatThrownBy(() -> new AuxiliaryArray(NAME, new byte[0], null, NONE, 0, null, null, null))
                .isInstanceOf(NullPointerException.class).hasMessage("dataType");
        assertThatThrownBy(() -> new AuxiliaryArray(NAME, new byte[0], "MS:1000523", null, 0, null, null, null))
                .isInstanceOf(NullPointerException.class).hasMessage("compression");
        assertThatThrownBy(() -> new AuxiliaryArray(NAME, new byte[0], "MS:1000523", NONE, -1, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("auxiliary array signal to noise array: items is negative: -1");
    }

    // ---- the entities -------------------------------------------------------------------------------------------

    @Test
    void theEntitiesCarryTheirAuxiliaryArrays() {
        AuxiliaryArray a = array(new byte[8], "MS:1000523", NONE, 1);
        List<AuxiliaryArray> given = new ArrayList<>(List.of(a));

        Spectrum spectrum = new Spectrum(null, new double[] {1.0, 2.0}, new double[] {3.0, 4.0}, null, null, null,
                null, null, given);
        WavelengthSpectrum wavelength = new WavelengthSpectrum(0, "w", 1.0, null, null, null, null, given);
        Chromatogram chromatogram = new Chromatogram(0, "TIC", "MS:1000235", new double[] {1.0},
                new double[] {2.0}, null, Map.of(), null, null, null, null, "UO:0000010", null, given);
        given.clear();

        assertThat(spectrum.auxiliaryArrays()).containsExactly(a);
        assertThat(wavelength.auxiliaryArrays()).containsExactly(a);
        assertThat(chromatogram.auxiliaryArrays()).containsExactly(a);
        assertThatThrownBy(() -> spectrum.auxiliaryArrays().add(a)).isInstanceOf(UnsupportedOperationException.class);
        // the times are converted, the auxiliary arrays kept
        Chromatogram minutes = chromatogram.withTimeUnit("UO:0000031");
        assertThat(minutes.time()).containsExactly(1.0 / 60);
        assertThat(minutes.auxiliaryArrays()).containsExactly(a);

        // null is none, as are the constructors without them
        assertThat(new Spectrum(null, null, null, null, null, null, null, null, null).auxiliaryArrays()).isEmpty();
        assertThat(new Spectrum(null, null, null, null).auxiliaryArrays()).isEmpty();
        assertThat(new WavelengthSpectrum(0, "w", 1.0, null, null, null, null, null).auxiliaryArrays()).isEmpty();
        assertThat(new WavelengthSpectrum(0, "w", 1.0, null, null, null).auxiliaryArrays()).isEmpty();
        assertThat(new Chromatogram(0, "TIC", null, null, null, null, null, null, null, null, null, null, null, null)
                .auxiliaryArrays()).isEmpty();
        assertThat(new Chromatogram(0, "TIC", null, null, null).auxiliaryArrays()).isEmpty();

        List<AuxiliaryArray> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> new Spectrum(null, null, null, null, null, null, null, null, withNull))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static AuxiliaryArray array(byte[] data, String dataType, String compression, long items) {
        return new AuxiliaryArray(NAME, data, dataType, compression, items, null, List.of(), null);
    }

    private static ByteBuffer buffer(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] zlib(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
            deflater.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
