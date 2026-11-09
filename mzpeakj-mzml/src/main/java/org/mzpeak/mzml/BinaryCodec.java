package org.mzpeak.mzml;

import ms.numpress.MSNumpress;
import org.mzpeak.io.MzPeakException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Decodes and encodes the payload of one mzML {@code binaryDataArray} (mzML design 5.7): the data types 32-bit float
 * (MS:1000521), 64-bit float (MS:1000523), 32-bit integer (MS:1000519) and 64-bit integer (MS:1000522), little
 * endian; no compression (MS:1000576), zlib (MS:1000574) and MS-Numpress linear (MS:1002312), PIC (MS:1002313) and
 * SLOF (MS:1002314), alone, followed by zlib (MS:1002746, MS:1002747, MS:1002748) or as the legacy pair of a Numpress
 * term and MS:1000574; Base64. Everything else is rejected with an {@link MzPeakException} naming the caller's
 * context, as is a decoded length that differs from the declared one. A zlib stream is inflated only up to the bytes
 * the declared length allows, so a small payload cannot claim unbounded memory.
 */
final class BinaryCodec {

    static final String FLOAT32 = "MS:1000521";
    static final String FLOAT64 = "MS:1000523";
    static final String INT32 = "MS:1000519";
    static final String INT64 = "MS:1000522";
    static final String FLOAT16 = "MS:1000520";
    static final String ASCII = "MS:1001479";
    static final String NO_COMPRESSION = "MS:1000576";
    static final String ZLIB = "MS:1000574";
    static final String LINEAR = MSNumpress.ACC_NUMPRESS_LINEAR;
    static final String PIC = MSNumpress.ACC_NUMPRESS_PIC;
    static final String SLOF = MSNumpress.ACC_NUMPRESS_SLOF;
    static final String LINEAR_ZLIB = "MS:1002746";
    static final String PIC_ZLIB = "MS:1002747";
    static final String SLOF_ZLIB = "MS:1002748";

    /** The term of each Numpress codec followed by zlib. */
    private static final Map<String, String> WITH_ZLIB = Map.of(LINEAR, LINEAR_ZLIB, PIC, PIC_ZLIB, SLOF, SLOF_ZLIB);
    /** The Numpress codec of each Numpress term, alone or followed by zlib. */
    private static final Map<String, String> CODEC = Map.of(LINEAR, LINEAR, PIC, PIC, SLOF, SLOF,
            LINEAR_ZLIB, LINEAR, PIC_ZLIB, PIC, SLOF_ZLIB, SLOF);
    private static final Map<String, String> NAMES = Map.of(LINEAR, "linear", PIC, "PIC", SLOF, "SLOF");
    /** XML whitespace, which may surround or break the Base64 text. */
    private static final Pattern WHITESPACE = Pattern.compile("[ \t\r\n]+");
    /** The largest integer up to which every integer is a double. */
    private static final double EXACT_INTEGERS = 0x1p53;
    /** The longest array the JDK's growing buffers allocate (its soft maximum array length). */
    private static final long MAX_ARRAY = Integer.MAX_VALUE - 8;

    /** What an array holds, which decides its data type under the precision rule (design 5.7). */
    enum Kind { AXIS, INTENSITY, CHARGE, NON_STANDARD, OTHER }

    /**
     * A decoded array.
     *
     * @param values       the values; null for a null-terminated ASCII array (MS:1001479)
     * @param roundedInt64 how many 64-bit integers did not survive the conversion to double (design 6.1 B-31)
     */
    record Decoded(double[] values, int roundedInt64) {
    }

    /**
     * An encoded array.
     *
     * @param base64          the {@code <binary>} text, empty for an empty array
     * @param dataTypeTerm    the binary data type accession
     * @param compressionTerm the compression accession
     * @param note            why a requested MS-Numpress codec was not applied, or null; one fixed text per reason
     */
    record Encoded(String base64, String dataTypeTerm, String compressionTerm, String note) {
    }

    private BinaryCodec() {
    }

    /**
     * Decodes one array. Whitespace in {@code base64} is ignored; an empty text decodes to an empty array under any
     * compression. A zlib stream may inflate to at most the bytes {@code declaredLength} allows: that many values of
     * the data type's width, or for an MS-Numpress payload 16 + 5 bytes per value (linear: a 16-byte header and at
     * most 4.5 bytes per further value; SLOF 8 + 2 bytes per value; PIC at most 4.5 bytes per value). MS-Numpress PIC
     * counts are read as unsigned 32-bit integers, as the C++ reference decodes them.
     *
     * @param dataType         the binary data type accession, or null when the array has none
     * @param compressionTerms the compression accessions of the array
     * @param declaredLength   {@code binaryDataArray@arrayLength} when present, else the owner's
     *                         {@code defaultArrayLength}
     * @param context          names the array in error messages, such as {@code spectrum scan=19, m/z array}
     * @throws MzPeakException for an unsupported data type or compression, invalid Base64, a corrupt zlib or
     *                         Numpress payload, a zlib stream that inflates to more bytes than {@code declaredLength}
     *                         allows (or a {@code declaredLength} that allows more than a byte array holds) or that is
     *                         followed by more bytes, a linear or SLOF fixed point of 0 or NaN in a payload with
     *                         values, or a decoded length other than {@code declaredLength}
     */
    static Decoded decode(String base64, String dataType, List<String> compressionTerms, int declaredLength,
                          String context) {
        int width = width(dataType, context);
        String term = compression(compressionTerms, context);
        if (width == 0) {
            return new Decoded(null, 0);
        }
        String compact = WHITESPACE.matcher(base64).replaceAll("");
        Decoded decoded;
        if (compact.isEmpty()) {
            decoded = new Decoded(new double[0], 0);
        } else {
            byte[] bytes = base64(compact, context);
            String codec = CODEC.get(term);
            if (term.equals(ZLIB) || WITH_ZLIB.containsValue(term)) {
                long limit = codec == null ? (long) declaredLength * width : 16 + 5L * declaredLength;
                if (limit > MAX_ARRAY) {
                    throw new MzPeakException("declared length " + declaredLength + " allows " + limit
                            + " inflated bytes, more than a byte array can hold, in " + context);
                }
                bytes = inflate(bytes, limit, declaredLength, context);
            }
            decoded = codec == null ? decodeRaw(bytes, dataType, width, context)
                    : new Decoded(decodeNumpress(bytes, codec, context), 0);
        }
        if (decoded.values().length != declaredLength) {
            throw new MzPeakException("decoded length " + decoded.values().length + " differs from the declared length "
                    + declaredLength + " in " + context);
        }
        return decoded;
    }

    /**
     * Encodes one array. The data type follows the precision rule: with {@code forcedBits} 0, 32-bit float when every
     * value survives the narrowing bit for bit, else 64-bit float; a {@link Kind#CHARGE} array as 32-bit integer when
     * every value is an int, a {@link Kind#NON_STANDARD} array as 64-bit integer when every value is an integer of
     * absolute value up to 2^53, else 64-bit float; {@code forcedBits} 32 or 64 applies to every kind except
     * {@link Kind#CHARGE}. MS-Numpress is applied first, then zlib, under the combined term and the data type
     * 64-bit float; values the codec cannot encode fall back to no Numpress with a {@link Encoded#note()}. An empty
     * array is the empty text with MS:1000576 under every option.
     *
     * @param forcedBits   0 (precision rule), 32 or 64
     * @param numpressTerm null, {@code MS:1002312}, {@code MS:1002313} or {@code MS:1002314}
     * @throws IllegalArgumentException for another {@code forcedBits} or {@code numpressTerm}
     */
    static Encoded encode(double[] values, Kind kind, int forcedBits, boolean zlib, String numpressTerm) {
        Objects.requireNonNull(kind, "kind");
        if (forcedBits != 0 && forcedBits != 32 && forcedBits != 64) {
            throw new IllegalArgumentException("forcedBits must be 0, 32 or 64, not " + forcedBits);
        }
        if (numpressTerm != null && !WITH_ZLIB.containsKey(numpressTerm)) {
            throw new IllegalArgumentException("not an MS-Numpress term: " + numpressTerm);
        }
        String dataType = dataType(values, kind, forcedBits);
        if (values.length == 0) {
            return new Encoded("", dataType, NO_COMPRESSION, null);
        }
        String note = numpressTerm == null ? null : refusal(values, numpressTerm);
        if (numpressTerm != null && note == null) {
            byte[] payload = encodeNumpress(values, numpressTerm);
            if (payload != null) {
                return new Encoded(Base64.getEncoder().encodeToString(zlib ? deflate(payload) : payload), FLOAT64,
                        zlib ? WITH_ZLIB.get(numpressTerm) : numpressTerm, null);
            }
            note = "MS-Numpress linear not applied: value too large for the fixed point";
        }
        byte[] bytes = encodeRaw(values, dataType);
        return new Encoded(Base64.getEncoder().encodeToString(zlib ? deflate(bytes) : bytes), dataType,
                zlib ? ZLIB : NO_COMPRESSION, note);
    }

    /** The byte width of a numeric data type, 0 for ASCII. */
    private static int width(String dataType, String context) {
        if (dataType == null) {
            throw new MzPeakException("no binary data type in " + context);
        }
        return switch (dataType) {
            case FLOAT32, INT32 -> 4;
            case FLOAT64, INT64 -> 8;
            case ASCII -> 0;
            case FLOAT16 -> throw new MzPeakException("16-bit float (" + FLOAT16 + ") is not supported in " + context);
            default -> throw new MzPeakException("unsupported binary data type " + dataType + " in " + context);
        };
    }

    /** The one term {@code terms} stand for: the legacy pair of a Numpress term and zlib is the combined term. */
    private static String compression(List<String> terms, String context) {
        for (String term : terms) {
            if (!term.equals(NO_COMPRESSION) && !term.equals(ZLIB) && !CODEC.containsKey(term)) {
                throw new MzPeakException("unsupported compression " + term + " in " + context);
            }
        }
        if (terms.size() == 1) {
            return terms.get(0);
        }
        if (terms.size() == 2 && terms.contains(ZLIB)) {
            String combined = WITH_ZLIB.get(terms.get(terms.get(0).equals(ZLIB) ? 1 : 0));
            if (combined != null) {
                return combined;
            }
        }
        throw new MzPeakException("unsupported compression terms " + terms + " in " + context);
    }

    private static byte[] base64(String compact, String context) {
        try {
            return Base64.getDecoder().decode(compact);
        } catch (IllegalArgumentException e) {
            throw new MzPeakException("invalid Base64 in " + context + ": " + e.getMessage(), e);
        }
    }

    /**
     * The bytes of the zlib stream {@code bytes}, at most {@code limit} of them, the bound of {@code declaredLength}:
     * inflating stops with an {@link MzPeakException} as soon as the output would exceed it. Bytes after the end of
     * the stream are rejected.
     */
    private static byte[] inflate(byte[] bytes, long limit, int declaredLength, String context) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(bytes);
            ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
            byte[] buffer = new byte[1 << 16];
            while (!inflater.finished()) {
                if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw new MzPeakException("truncated zlib stream (or one that needs a preset dictionary) in "
                            + context);
                }
                int n = inflater.inflate(buffer);
                if (out.size() + (long) n > limit) {
                    throw new MzPeakException("zlib stream inflates to at least " + (out.size() + (long) n)
                            + " bytes, more than the " + limit + " bytes the declared length " + declaredLength
                            + " allows, in " + context);
                }
                out.write(buffer, 0, n);
            }
            if (inflater.getRemaining() > 0) {
                throw new MzPeakException("bytes after the end of the zlib stream in " + context);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new MzPeakException("invalid zlib stream in " + context + ": " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }

    private static byte[] deflate(byte[] bytes) {
        Deflater deflater = new Deflater(6);
        try {
            deflater.setInput(bytes);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length / 2 + 64);
            byte[] buffer = new byte[1 << 16];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** Little-endian values of a numeric data type, counting the 64-bit integers that round to another double. */
    private static Decoded decodeRaw(byte[] bytes, String dataType, int width, String context) {
        if (bytes.length % width != 0) {
            throw new MzPeakException(bytes.length + " bytes are not a whole number of " + width + "-byte values in "
                    + context);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        double[] values = new double[bytes.length / width];
        int rounded = 0;
        for (int i = 0; i < values.length; i++) {
            switch (dataType) {
                case FLOAT64 -> values[i] = buffer.getDouble();
                case FLOAT32 -> values[i] = buffer.getFloat();
                case INT32 -> values[i] = buffer.getInt();
                default -> {
                    long v = buffer.getLong();
                    values[i] = v;
                    // 2^63, the double of Long.MAX_VALUE, is no long: (long) saturates it back to Long.MAX_VALUE
                    rounded += values[i] == 0x1p63 || (long) values[i] != v ? 1 : 0;
                }
            }
        }
        return new Decoded(values, rounded);
    }

    /**
     * The values of a Numpress payload. PIC goes through {@code decodePic} with a buffer of two values per byte, since
     * {@code MSNumpress.decode} rejects PIC payloads under 8 bytes; its counts come back sign-extended from 2^31 on
     * (the vendored {@code IntDecoder}), and since a count is never negative, 2^32 is added to every negative value.
     * A linear or SLOF payload with values must not have the fixed point (its first 8 bytes, read as
     * {@code MSNumpress.decodeFixedPoint} reads them) 0, of either sign, or NaN: the reference decoders divide by it
     * and return NaN or infinite values (SLOF at -0.0 also -1). Every other fixed point is accepted, infinite and
     * negative ones included: {@code MSNumpress.optimalLinearFixedPoint}, the algorithm of the C++ reference encoder,
     * gives +Infinity for [0.0] and [0.0, 0.0] and a negative fixed point for [-1.0] and [-2.0, -1.0], and the
     * reference decoder reads those payloads back exactly. A header without values uses none, so its fixed point is
     * not checked.
     */
    private static double[] decodeNumpress(byte[] bytes, String codec, String context) {
        double[] buffer = new double[bytes.length * 2];
        int n;
        try {
            n = codec.equals(LINEAR) ? MSNumpress.decodeLinear(bytes, bytes.length, buffer)
                    : codec.equals(SLOF) ? MSNumpress.decodeSlof(bytes, bytes.length, buffer)
                    : MSNumpress.decodePic(bytes, bytes.length, buffer);
        } catch (RuntimeException e) {
            throw new MzPeakException("corrupt MS-Numpress " + NAMES.get(codec) + " payload in " + context, e);
        }
        if (n < 0) {
            throw new MzPeakException("MS-Numpress " + NAMES.get(codec) + " payload of " + bytes.length
                    + " bytes is too short or corrupt in " + context);
        }
        if (!codec.equals(PIC) && n > 0) {
            double fixedPoint = MSNumpress.decodeFixedPoint(bytes);
            if (fixedPoint == 0 || Double.isNaN(fixedPoint)) {
                throw new MzPeakException("MS-Numpress " + NAMES.get(codec) + " fixed point " + fixedPoint
                        + " is 0 or NaN in " + context);
            }
        }
        double[] values = Arrays.copyOf(buffer, n);
        if (codec.equals(PIC)) {
            for (int i = 0; i < n; i++) {
                if (values[i] < 0) {
                    values[i] += 0x1p32;
                }
            }
        }
        return values;
    }

    /** The data type of the precision rule (see {@link #encode}). */
    private static String dataType(double[] values, Kind kind, int forcedBits) {
        if (kind == Kind.CHARGE) {
            return integral(values, Integer.MIN_VALUE, Integer.MAX_VALUE) ? INT32 : FLOAT64;
        }
        if (forcedBits != 0) {
            return forcedBits == 32 ? FLOAT32 : FLOAT64;
        }
        if (kind == Kind.NON_STANDARD) {
            return integral(values, -EXACT_INTEGERS, EXACT_INTEGERS) ? INT64 : FLOAT64;
        }
        for (double v : values) {
            if (Double.doubleToLongBits((float) v) != Double.doubleToLongBits(v)) {
                return FLOAT64;
            }
        }
        return FLOAT32;
    }

    /** Whether every value is an integer in [min, max] that the integer type stores bit for bit (not -0.0). */
    private static boolean integral(double[] values, double min, double max) {
        for (double v : values) {
            if (!(v >= min && v <= max) || Double.doubleToLongBits((long) v) != Double.doubleToLongBits(v)) {
                return false;
            }
        }
        return true;
    }

    private static byte[] encodeRaw(double[] values, String dataType) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * width(dataType, null)).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) {
            switch (dataType) {
                case FLOAT64 -> buffer.putDouble(v);
                case FLOAT32 -> buffer.putFloat((float) v);
                case INT32 -> buffer.putInt((int) v);
                default -> buffer.putLong((long) v);
            }
        }
        return buffer.array();
    }

    /**
     * Why {@code codec} cannot encode {@code values}, or null if it can (design 5.7): linear stores the first two
     * values as unsigned 32-bit integers, a single value as a payload pynumpress cannot decode, and needs a finite
     * fixed point of at least 1 (two values below about 1e-299, or both 0, give an infinite one); SLOF takes the
     * logarithm of {@code 1 + v}; PIC stores counts up to 2147483647 so that readers built on the reference Java
     * decoder, which sign-extends larger ones, read them (design Q26). {@link #encodeNumpress} checks the rest.
     */
    private static String refusal(double[] values, String codec) {
        boolean nonFinite = false;
        boolean negative = false;
        boolean aboveInt = false;
        for (double v : values) {
            if (!Double.isFinite(v)) {
                nonFinite = true;
            } else if (v < 0) {
                negative = true;
            } else if ((long) (v + 0.5) > Integer.MAX_VALUE) {
                aboveInt = true;
            }
        }
        String reason;
        if (codec.equals(LINEAR)) {
            double fixedPoint = nonFinite || negative || values.length == 1 ? 1
                    : MSNumpress.optimalLinearFixedPoint(values, values.length);
            reason = nonFinite ? "non-finite value"
                    : negative ? "negative value"
                    : values.length == 1 ? "single value"
                    : fixedPoint < 1 ? "fixed point below 1"
                    : !Double.isFinite(fixedPoint) ? "fixed point not finite" : null;
        } else {
            reason = nonFinite || negative ? "negative or non-finite value"
                    : codec.equals(PIC) && aboveInt ? "count above 2147483647" : null;
        }
        return reason == null ? null : "MS-Numpress " + NAMES.get(codec) + " not applied: " + reason;
    }

    /**
     * The Numpress payload of {@code values}, or null when a linear payload does not decode to within
     * {@code 0.5 / fixedPoint + 1e-12 |v|} of every value (the bound of design 5.7). The fixed point only bounds the
     * first two values and the residuals; {@code encodeLinear} saturates {@code (long) (v * fixedPoint + 0.5)} at
     * {@code Long.MAX_VALUE} for a larger value and writes only the low 32 bits of a residual outside the int range,
     * both silently, so the payload is decoded again to check it. A payload of this encoder decodes to exactly
     * {@code n} values.
     */
    private static byte[] encodeNumpress(double[] values, String codec) {
        int n = values.length;
        byte[] out = new byte[8 + 5 * n]; // the longest payload: linear 8 + 5n, SLOF 8 + 2n, PIC 5n bytes at most
        if (codec.equals(LINEAR)) {
            double fixedPoint = MSNumpress.optimalLinearFixedPoint(values, n);
            byte[] payload = Arrays.copyOf(out, MSNumpress.encodeLinear(values, n, out, fixedPoint));
            double[] back = new double[n];
            MSNumpress.decodeLinear(payload, payload.length, back);
            for (int i = 0; i < n; i++) {
                if (Math.abs(back[i] - values[i]) > 0.5 / fixedPoint + 1e-12 * values[i]) {
                    return null;
                }
            }
            return payload;
        }
        int length = codec.equals(SLOF)
                ? MSNumpress.encodeSlof(values, n, out, MSNumpress.optimalSlofFixedPoint(values, n))
                : MSNumpress.encodePic(values, n, out);
        return Arrays.copyOf(out, length);
    }
}
