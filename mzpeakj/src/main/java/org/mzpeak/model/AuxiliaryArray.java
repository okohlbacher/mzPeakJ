package org.mzpeak.model;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;
import java.util.zip.InflaterInputStream;

/**
 * An auxiliary data array of a spectrum, chromatogram or wavelength spectrum: an array stored in the entity's metadata
 * row ({@code auxiliary_arrays}) rather than in the signal data, such as one whose length differs from the entity's
 * axis (docs/layouts/auxiliary-arrays.md).
 *
 * <p>{@link #data()} holds the bytes as stored, compressed or not, and {@link #compression()} describes them;
 * {@link #doubles()} decompresses and decodes them on demand. A reader keeps the bytes of a compression it cannot
 * decode, so they are written again unchanged.
 *
 * <p>Record semantics: {@code equals}, {@code hashCode} and {@code toString} treat {@link #data()} by reference, as
 * {@link Chromatogram} does its arrays; the bytes are not copied and are considered immutable.
 *
 * @param name             the CV term naming the array, a child of MS:1000513 (data array), such as
 *                         {@code MS:1000517} (signal to noise array) or MS:1000786 (non-standard data array) with the
 *                         array's name as value; never {@code null}
 * @param data             the array's bytes as stored: little-endian values of {@link #dataType}, compressed as
 *                         {@link #compression} says; never {@code null}
 * @param dataType         the CURIE of the binary data type of the values, a child of MS:1000518 (binary data type),
 *                         such as {@code MS:1000523} (64-bit float); never {@code null}
 * @param compression      the CURIE of the compression of {@link #data}, a child of MS:1000572 (binary data
 *                         compression type), such as {@code MS:1000576} (no compression) or {@code MS:1000574} (zlib
 *                         compression); never {@code null}
 * @param items            the number of values of {@link #dataType} in the array; not negative
 * @param unit             the unit CURIE of the values, or {@code null} when none is declared
 * @param parameters       further CV and user params describing the array; never {@code null}, may be empty.
 *                         Unmodifiable, a copy of the given list
 * @param dataProcessingId the id of the data processing that created the array, or {@code null}
 */
public record AuxiliaryArray(Param name, byte[] data, String dataType, String compression, long items, String unit,
                             List<Param> parameters, String dataProcessingId) {

    /** MS:1000576, no compression. */
    private static final String NO_COMPRESSION = "MS:1000576";
    /** MS:1000574, zlib compression. */
    private static final String ZLIB_COMPRESSION = "MS:1000574";
    /** MS:1000521, 32-bit float. */
    private static final String FLOAT32 = "MS:1000521";
    /** MS:1000523, 64-bit float. */
    private static final String FLOAT64 = "MS:1000523";
    /** MS:1000519, 32-bit integer. */
    private static final String INT32 = "MS:1000519";
    /** MS:1000522, 64-bit integer. */
    private static final String INT64 = "MS:1000522";

    /**
     * @throws NullPointerException     when {@code name}, {@code data}, {@code dataType} or {@code compression} is
     *                                  {@code null}, or {@code parameters} holds a {@code null} element
     * @throws IllegalArgumentException when {@code items} is negative
     */
    public AuxiliaryArray {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(dataType, "dataType");
        Objects.requireNonNull(compression, "compression");
        if (items < 0) {
            throw new IllegalArgumentException("auxiliary array " + name.name() + ": items is negative: " + items);
        }
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /**
     * The values of the array: {@link #data()} decompressed (MS:1000576 none, MS:1000574 zlib) and decoded as
     * little-endian {@link #dataType()} values (MS:1000521 32-bit float, MS:1000523 64-bit float, MS:1000519 32-bit
     * integer, MS:1000522 64-bit integer), widened to double. A new array on every call.
     *
     * @throws UnsupportedOperationException when the compression or the data type is not one of these; the message
     *                                       names it
     * @throws IllegalStateException         when the zlib data is corrupt or truncated, or the decompressed bytes do
     *                                       not hold {@link #items()} values of the data type
     */
    public double[] doubles() {
        int width = switch (dataType) {
            case FLOAT32, INT32 -> Integer.BYTES;
            case FLOAT64, INT64 -> Long.BYTES;
            default -> throw new UnsupportedOperationException("auxiliary array " + name.name()
                    + ": the data type " + dataType + " cannot be decoded; supported are MS:1000521 (32-bit float),"
                    + " MS:1000523 (64-bit float), MS:1000519 (32-bit integer) and MS:1000522 (64-bit integer)");
        };
        byte[] bytes = decompressed();
        if (bytes.length != items * width) {
            throw new IllegalStateException("auxiliary array " + name.name() + ": " + bytes.length
                    + " bytes do not hold " + items + " values of " + dataType + " (" + width + " bytes each)");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        double[] out = new double[(int) items];
        for (int i = 0; i < out.length; i++) {
            out[i] = switch (dataType) {
                case FLOAT32 -> buffer.getFloat();
                case FLOAT64 -> buffer.getDouble();
                case INT32 -> buffer.getInt();
                default -> buffer.getLong();
            };
        }
        return out;
    }

    /** {@link #data()} decompressed as {@link #compression()} says. */
    private byte[] decompressed() {
        switch (compression) {
            case NO_COMPRESSION -> {
                return data;
            }
            case ZLIB_COMPRESSION -> {
                try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(data))) {
                    return in.readAllBytes();
                } catch (IOException e) {
                    throw new IllegalStateException("auxiliary array " + name.name() + ": the zlib data cannot be"
                            + " decompressed: " + e.getMessage(), e);
                }
            }
            default -> throw new UnsupportedOperationException("auxiliary array " + name.name()
                    + ": the compression " + compression + " cannot be decoded; supported are MS:1000576 (no"
                    + " compression) and MS:1000574 (zlib compression)");
        }
    }
}
