package org.mzpeak.io;

import static org.mzpeak.io.NumpressSignalWriter.NUMPRESS_LINEAR_FIXED_POINT;

/**
 * The values the Numpress codecs of {@link NumpressSignalWriter} can encode (design §5.7, MZ-23, and §4.1, D-11): the
 * one check both {@link WritePlanner#requireNumpressEncodable}, before any I/O, and
 * {@link NumpressSignalWriter#appendNumpressChunks}, before it encodes a spectrum, make.
 */
final class NumpressRange {

    /** The largest count Numpress PIC is written with: 2^31 - 1 (design Q26, see {@link #check}). */
    private static final long MAX_PIC_COUNT = Integer.MAX_VALUE;
    /**
     * The largest fixed-point m/z Numpress linear is written with: 2^32 - 1, the largest of the unsigned 32-bit
     * integers the codec stores the first two m/z of a chunk as (see {@link #check}).
     */
    private static final long MAX_LINEAR_VALUE = 0xFFFFFFFFL;
    /** The codec and the values it takes, as the error for a value it cannot encode names them. */
    private static final String LINEAR_RANGE = "Numpress linear, which takes only finite m/z >= 0 that round to at"
            + " most " + MAX_LINEAR_VALUE + " at its fixed point " + (long) NUMPRESS_LINEAR_FIXED_POINT + " (m/z "
            + MAX_LINEAR_VALUE / NUMPRESS_LINEAR_FIXED_POINT + ")";
    /** The same for an m/z too far from its extrapolation; the error appends the two m/z it is extrapolated from. */
    private static final String LINEAR_STEP = "Numpress linear, which takes an m/z after the first two of a chunk only"
            + " within about 21474.84 of its extrapolation from the two before it, 2 * ";
    private static final String SLOF_RANGE = "Numpress SLOF, which takes only finite intensities >= 0";
    private static final String PIC_RANGE = "Numpress PIC, which takes only finite intensities >= 0 that round to at"
            + " most " + MAX_PIC_COUNT;
    private static final String POINT = "SignalEncoding.POINT";

    private NumpressRange() {
    }

    /**
     * Throws {@link MzPeakException} for the first point of spectrum {@code index} in {@code member} that cannot be
     * encoded, going through the points {@code mz}/{@code intensity} in the chunks
     * {@link NumpressSignalWriter#chunkEnd} splits them into: an m/z or intensity that is not finite or is below 0, an
     * m/z whose fixed-point value {@code (long) (mz * 1e5 + 0.5)} exceeds 4294967295, an m/z after the first two of a
     * chunk whose fixed-point difference from its extrapolation (2 * previous - the one before) is outside the signed
     * 32-bit range, or with Numpress PIC ({@code pic}) an intensity whose rounded count {@code (long) (v + 0.5)}
     * exceeds 2147483647. The message names the spectrum's index, the array, the value, the member and the
     * alternative.
     */
    static void check(long index, double[] mz, double[] intensity, String member, boolean pic) {
        int pos = 0;
        while (pos < mz.length) {
            int end = NumpressSignalWriter.chunkEnd(pos, mz.length);
            for (int i = pos; i < end; i++) {
                long fixed = linearFixedPoint(mz[i]);
                if (!Double.isFinite(mz[i]) || mz[i] < 0 || fixed > MAX_LINEAR_VALUE) {
                    throw unencodable(index, "m/z", mz[i], member, LINEAR_RANGE, POINT);
                }
                // From the third m/z of a chunk on, the codec stores the difference as a signed 32-bit integer.
                if (i - pos >= 2) {
                    long step = fixed - 2 * linearFixedPoint(mz[i - 1]) + linearFixedPoint(mz[i - 2]);
                    if ((int) step != step) {
                        throw unencodable(index, "m/z", mz[i], member, LINEAR_STEP + mz[i - 1] + " - " + mz[i - 2],
                                POINT);
                    }
                }
                checkIntensity(index, intensity[i], member, pic);
            }
            pos = end;
        }
    }

    /** The fixed-point value NumpressSignalWriter's Numpress linear stores {@code mz} as. */
    private static long linearFixedPoint(double mz) {
        return (long) (mz * NUMPRESS_LINEAR_FIXED_POINT + 0.5);
    }

    /** Throws for one intensity of spectrum {@code index} in {@code member}, as {@link #check}. */
    private static void checkIntensity(long index, double intensity, String member, boolean pic) {
        if (!Double.isFinite(intensity) || intensity < 0) {
            throw unencodable(index, "intensity", intensity, member, pic ? PIC_RANGE : SLOF_RANGE, POINT);
        }
        if (pic && (long) (intensity + 0.5) > MAX_PIC_COUNT) {
            throw unencodable(index, "intensity", intensity, member, PIC_RANGE,
                    "NumpressIntensityEncoding.SLOF or " + POINT);
        }
    }

    /** The error for {@code value} of {@code array}, which {@code codec} cannot encode, and its alternative. */
    private static MzPeakException unencodable(long index, String array, double value, String member, String codec,
                                               String alternative) {
        return new MzPeakException("spectrum " + index + ": " + array + " " + value + " in " + member
                + " cannot be encoded with " + codec + "; use " + alternative);
    }
}
