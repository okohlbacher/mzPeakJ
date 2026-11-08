package ms.numpress;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Spectrum;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the vendored {@link MSNumpress} codec (byte-identical to upstream MSNumpress.java) through its public and
 * package API: the round trips of Numpress linear, PIC and SLOF, the fixed-point helpers, the half-byte integer
 * coding of {@link MSNumpress#encodeInt}, the edge sizes (no value, one value, two values), magnitudes that overflow
 * the fixed-point representation, and the error returns and exceptions.
 *
 * <p>The realistic arrays are the m/z and intensity of all 48 spectra of the current small.mzpeak (MS1 profile and
 * MS2 centroid); each is compared element by element with the value the codec's arithmetic defines (the fixed-point
 * rounding of linear and PIC, the uint16 log of SLOF). The small layouts, the edge sizes and the overflowing
 * magnitudes are constructed, since no fixture holds an empty, single-point or out-of-range array.
 *
 * <p>Unreachable, so left uncovered: in {@link MSNumpress#decode}, the PIC branch's {@code if (nbrOfDoubles < 0)}
 * (MSNumpress.java line 73) is never true and its {@code throw new IllegalArgumentException("Corrupt numPic data!")}
 * (line 74) never runs. {@link MSNumpress#decodePic} starts its count at 0 and only increments it, so it returns a
 * count of at least 0 or throws (ArrayIndexOutOfBoundsException on a truncated payload); unlike decodeLinear and
 * decodeSlof it has no -1 return. The count cannot wrap to a negative int either: it indexes the result double[],
 * which fails at its bound long before. That is 1 line and 1 branch; every other line and branch is reached here.
 */
class MSNumpressCoverageTest {

    /** m/z and intensity of each spectrum of the current small.mzpeak, in file order. */
    private static final List<double[][]> SPECTRA = new ArrayList<>();

    @BeforeAll
    static void readFixture() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            for (int i = 0; i < reader.size(); i++) {
                Spectrum spectrum = reader.getSpectrumAt(i);
                SPECTRA.add(new double[][] {spectrum.mz(), spectrum.intensity()});
            }
        }
        assertThat(SPECTRA).hasSize(48);
    }

    // ---------------------------------------------------------------- accessions, constructor

    @Test
    void accessionsAreThePsiMsNumpressTerms() {
        assertThat(MSNumpress.ACC_NUMPRESS_LINEAR).isEqualTo("MS:1002312");
        assertThat(MSNumpress.ACC_NUMPRESS_PIC).isEqualTo("MS:1002313");
        assertThat(MSNumpress.ACC_NUMPRESS_SLOF).isEqualTo("MS:1002314");
    }

    @Test
    void implicitPublicConstructorIsUsable() {
        // The class only has static members; its implicit constructor is public API of the vendored class.
        assertThat(new MSNumpress()).isInstanceOf(MSNumpress.class);
    }

    // ---------------------------------------------------------------- encodeInt

    @Test
    void encodeIntWritesTheDocumentedHalfBytes() {
        // The Javadoc examples: 0 => 0x8, -1 => 0xf 0xf, 23 => 0x6 0x7 0x1.
        assertThat(encodeInt(0)).containsExactly(8);
        assertThat(encodeInt(-1)).containsExactly(0xf, 0xf);
        assertThat(encodeInt(23)).containsExactly(6, 7, 1);
    }

    @Test
    void encodeIntCountsLeadingZeroAndOneHalfBytes() {
        // seven leading 0xf half bytes, the last one 0xe
        assertThat(encodeInt(-2)).containsExactly(0xf, 0xe);
        // one leading 0xf half byte (count 1 | 8), then seven half bytes, low first
        assertThat(encodeInt(-0x10000000L)).containsExactly(9, 0, 0, 0, 0, 0, 0, 0);
        // one leading 0x0 half byte
        assertThat(encodeInt(0x01234567L)).containsExactly(1, 7, 6, 5, 4, 3, 2, 1);
    }

    @Test
    void encodeIntStoresAllEightHalfBytesWhenNoneLeads() {
        assertThat(encodeInt(0x12345678L)).containsExactly(0, 8, 7, 6, 5, 4, 3, 2, 1);
        assertThat(encodeInt(0x7FFFFFFFL)).containsExactly(0, 0xf, 0xf, 0xf, 0xf, 0xf, 0xf, 0xf, 7);
        // beyond 32 bits only the low 32 bits are written
        assertThat(encodeInt(0x1_0000_0001L)).containsExactly(0, 1, 0, 0, 0, 0, 0, 0, 0);
        assertThat(encodeInt(0xF0000000L)).containsExactly(0, 0, 0, 0, 0, 0, 0, 0, 0xf);
    }

    @Test
    void encodeIntWritesAtTheOffset() {
        byte[] res = new byte[8];
        Arrays.fill(res, (byte) -1);

        assertThat(MSNumpress.encodeInt(23, res, 3)).isEqualTo(3);
        assertThat(res).containsExactly(-1, -1, -1, 6, 7, 1, -1, -1);
    }

    // ---------------------------------------------------------------- fixed point

    @Test
    void fixedPointIsTheBigEndianIeee754Double() {
        byte[] result = new byte[12];
        Arrays.fill(result, (byte) 0x55);

        MSNumpress.encodeFixedPoint(1e5, result);

        assertThat(Arrays.copyOf(result, 8)).containsExactly(ByteBuffer.allocate(8).putDouble(1e5).array());
        assertThat(Arrays.copyOfRange(result, 8, 12)).containsExactly(0x55, 0x55, 0x55, 0x55);
        assertThat(MSNumpress.decodeFixedPoint(result)).isEqualTo(1e5);
    }

    @Test
    void fixedPointRoundTripsSpecialDoublesBitForBit() {
        double[] values = {0.0, -0.0, Double.MIN_VALUE, Double.MAX_VALUE, Double.NEGATIVE_INFINITY, Double.NaN,
                -123.456};
        for (double value : values) {
            byte[] bytes = new byte[8];
            MSNumpress.encodeFixedPoint(value, bytes);
            assertThat(Double.doubleToRawLongBits(MSNumpress.decodeFixedPoint(bytes)))
                    .as("fixed point %s", value)
                    .isEqualTo(Double.doubleToLongBits(value));
        }
    }

    // ---------------------------------------------------------------- linear fixed-point choice

    @Test
    void optimalLinearFixedPointOfNoOneAndTwoValues() {
        assertThat(MSNumpress.optimalLinearFixedPoint(new double[0], 0)).isEqualTo(0.0);
        // one value: the unsigned 32-bit range over that value
        assertThat(MSNumpress.optimalLinearFixedPoint(new double[] {1000.0}, 1)).isEqualTo(4294967.0);
        // two values: the signed 32-bit range over the larger; the values past dataSize are ignored
        assertThat(MSNumpress.optimalLinearFixedPoint(new double[] {100.0, 200.0, 1e9}, 2)).isEqualTo(10737418.0);
    }

    @Test
    void optimalLinearFixedPointBoundsTheLinearPredictionResidual() {
        // residual of the third value is 30000, so floor(0x7FFFFFFF / 30001)
        assertThat(MSNumpress.optimalLinearFixedPoint(new double[] {0.0, 0.0, 30000.0}, 3)).isEqualTo(71580.0);
    }

    @Test
    void optimalLinearFixedPointMassReturnsTheAccuracyFixedPointOrMinusOne() {
        double[] mz = SPECTRA.get(0)[0];
        int n = mz.length;

        assertThat(MSNumpress.optimalLinearFixedPointMass(new double[] {100.0, 200.0}, 2, 1e-3)).isEqualTo(0.0);
        assertThat(MSNumpress.optimalLinearFixedPointMass(mz, n, 1e-4)).isEqualTo(0.5 / 1e-4);
        // 0.5 / 1e-9 is past the overflow-safe fixed point of these m/z values
        assertThat(MSNumpress.optimalLinearFixedPoint(mz, n)).isLessThan(0.5 / 1e-9);
        assertThat(MSNumpress.optimalLinearFixedPointMass(mz, n, 1e-9)).isEqualTo(-1.0);
    }

    // ---------------------------------------------------------------- linear

    @Test
    void linearRoundTripOfFixtureMzIsExactInTheFixedPoint() {
        for (double[][] spectrum : SPECTRA) {
            double[] mz = spectrum[0];
            int n = mz.length;
            double fixedPoint = MSNumpress.optimalLinearFixedPoint(mz, n);
            byte[] encoded = new byte[8 + 5 * n];

            int length = MSNumpress.encodeLinear(mz, n, encoded, fixedPoint);
            double[] decoded = new double[(length - 8) * 2];
            int count = MSNumpress.decodeLinear(encoded, length, decoded);

            double[] expected = new double[n];
            double maxError = 0;
            for (int i = 0; i < n; i++) {
                expected[i] = (long) (mz[i] * fixedPoint + 0.5) / fixedPoint;
                maxError = Math.max(maxError, Math.abs(decoded[i] - mz[i]));
            }
            assertThat(MSNumpress.decodeFixedPoint(encoded)).isEqualTo(fixedPoint);
            assertThat(count).isEqualTo(n);
            assertThat(Arrays.copyOf(decoded, count)).containsExactly(expected);
            assertThat(maxError).isLessThanOrEqualTo(0.5 / fixedPoint + 1e-12);
            assertThat(MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, Arrays.copyOf(encoded, length), length))
                    .containsExactly(expected);
        }
    }

    @Test
    void linearOfNoOneAndTwoValuesIsHeaderOnly() {
        byte[] none = new byte[8];
        assertThat(MSNumpress.encodeLinear(new double[0], 0, none, 1e5)).isEqualTo(8);
        assertThat(MSNumpress.decodeFixedPoint(none)).isEqualTo(1e5);
        assertThat(MSNumpress.decodeLinear(none, 8, new double[0])).isEqualTo(0);
        assertThat(MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, none, 8)).isEmpty();

        // the first two values are stored as little-endian 32-bit fixed-point ints
        byte[] one = new byte[13];
        assertThat(MSNumpress.encodeLinear(new double[] {1.5}, 1, one, 10)).isEqualTo(12);
        assertThat(Arrays.copyOfRange(one, 8, 12)).containsExactly(15, 0, 0, 0);
        double[] decodedOne = new double[2];
        assertThat(MSNumpress.decodeLinear(one, 12, decodedOne)).isEqualTo(1);
        assertThat(decodedOne[0]).isEqualTo(1.5);

        byte[] two = new byte[18];
        assertThat(MSNumpress.encodeLinear(new double[] {1.5, 2.5}, 2, two, 10)).isEqualTo(16);
        assertThat(Arrays.copyOfRange(two, 12, 16)).containsExactly(25, 0, 0, 0);
        assertThat(MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, Arrays.copyOf(two, 16), 16))
                .containsExactly(1.5, 2.5);
    }

    @Test
    void linearPacksTheResidualHalfBytesAndPadsAnOddCount() {
        // residual 0 is the single half byte 0x8; an odd count is padded with a 0x0 half byte
        byte[] three = new byte[23];
        assertThat(MSNumpress.encodeLinear(new double[] {1.0, 2.0, 3.0}, 3, three, 10)).isEqualTo(17);
        assertThat(three[16]).isEqualTo((byte) 0x80);
        assertThat(MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, Arrays.copyOf(three, 17), 17))
                .containsExactly(1.0, 2.0, 3.0);

        // an even count fills the last byte; a trailing 0x8 half byte is a value, not padding
        byte[] four = new byte[28];
        assertThat(MSNumpress.encodeLinear(new double[] {1.0, 2.0, 3.0, 4.0}, 4, four, 10)).isEqualTo(17);
        assertThat(four[16]).isEqualTo((byte) 0x88);
        assertThat(MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, Arrays.copyOf(four, 17), 17))
                .containsExactly(1.0, 2.0, 3.0, 4.0);

        // residual 10 is 0x7 0xa after 0x8, so three half bytes and a padded last byte
        byte[] jump = new byte[28];
        assertThat(MSNumpress.encodeLinear(new double[] {1.0, 2.0, 3.0, 5.0}, 4, jump, 10)).isEqualTo(18);
        assertThat(Arrays.copyOfRange(jump, 16, 18)).containsExactly(0x87, 0xa0);
        assertThat(MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, Arrays.copyOf(jump, 18), 18))
                .containsExactly(1.0, 2.0, 3.0, 5.0);
    }

    @Test
    void linearWrapsValuesPastTheFixedPointRange() {
        // 50000 * 1e5 = 5e9 does not fit the stored unsigned 32-bit int; it wraps to 5e9 - 2^32
        byte[] first = new byte[13];
        MSNumpress.encodeLinear(new double[] {50000.0}, 1, first, 1e5);
        double[] decodedFirst = new double[2];
        assertThat(MSNumpress.decodeLinear(first, 12, decodedFirst)).isEqualTo(1);
        assertThat(decodedFirst[0]).isEqualTo(705032704 / 1e5);

        // a residual of 3e9 is cut to 32 bits and read back sign-extended, as 3e9 - 2^32
        double[] data = {0.0, 0.0, 30000.0};
        byte[] residual = new byte[23];
        int length = MSNumpress.encodeLinear(data, 3, residual, 1e5);
        assertThat(length).isEqualTo(21);
        assertThat(MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, Arrays.copyOf(residual, length), length))
                .containsExactly(0.0, 0.0, -1294967296 / 1e5);

        // the overflow-safe fixed point keeps the same data exact
        double safe = MSNumpress.optimalLinearFixedPoint(data, 3);
        length = MSNumpress.encodeLinear(data, 3, residual, safe);
        assertThat(MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, Arrays.copyOf(residual, length), length))
                .containsExactly(0.0, 0.0, 30000.0);
    }

    @Test
    void decodeLinearReturnsMinusOneForTruncatedHeaders() {
        byte[] two = new byte[18];
        MSNumpress.encodeLinear(new double[] {1.5, 2.5}, 2, two, 10);

        double[] decoded = new double[4];
        assertThat(MSNumpress.decodeLinear(new byte[4], 4, decoded)).isEqualTo(-1);
        assertThat(MSNumpress.decodeLinear(two, 8, decoded)).isEqualTo(0);
        assertThat(MSNumpress.decodeLinear(two, 9, decoded)).isEqualTo(-1);
        assertThat(MSNumpress.decodeLinear(two, 11, decoded)).isEqualTo(-1);
        assertThat(decoded).containsOnly(0.0);
        // with 12 to 15 bytes the first value is written before the -1
        assertThat(MSNumpress.decodeLinear(two, 14, decoded)).isEqualTo(-1);
        assertThat(decoded).containsExactly(1.5, 0.0, 0.0, 0.0);
    }

    @Test
    void decodeLinearRejectsShortAndCorruptPayloads() {
        byte[] two = new byte[18];
        MSNumpress.encodeLinear(new double[] {1.5, 2.5}, 2, two, 10);

        assertThatThrownBy(() -> MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, two, 4))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot decode numLin data, need at least 8 initial bytes for fixed point.");
        assertThatThrownBy(() -> MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, new byte[4], 8))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot decode numLin data, need at least 8 initial bytes for fixed point.");
        assertThatThrownBy(() -> MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, Arrays.copyOf(two, 10), 10))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Corrupt numLin data!");
    }

    // ---------------------------------------------------------------- PIC

    @Test
    void picRoundTripOfFixtureIntensityRoundsToCounts() {
        for (double[][] spectrum : SPECTRA) {
            double[] intensity = spectrum[1];
            int n = intensity.length;
            byte[] encoded = new byte[5 * n];

            int length = MSNumpress.encodePic(intensity, n, encoded);
            double[] decoded = new double[length * 2];
            int count = MSNumpress.decodePic(encoded, length, decoded);

            double[] expected = new double[n];
            for (int i = 0; i < n; i++) {
                expected[i] = (long) (intensity[i] + 0.5);
            }
            assertThat(count).isEqualTo(n);
            assertThat(Arrays.copyOf(decoded, count)).containsExactly(expected);
            assertThat(MSNumpress.decode(MSNumpress.ACC_NUMPRESS_PIC, Arrays.copyOf(encoded, length), length))
                    .containsExactly(expected);
        }
    }

    @Test
    void picPacksHalfBytesWithoutHeader() {
        byte[] encoded = new byte[10];
        assertThat(MSNumpress.encodePic(new double[0], 0, encoded)).isEqualTo(0);
        assertThat(MSNumpress.decodePic(encoded, 0, new double[0])).isEqualTo(0);

        // 0 is 0x8, padded with 0x0
        assertThat(MSNumpress.encodePic(new double[] {0.0}, 1, encoded)).isEqualTo(1);
        assertThat(encoded[0]).isEqualTo((byte) 0x80);
        double[] decoded = new double[2];
        assertThat(MSNumpress.decodePic(encoded, 1, decoded)).isEqualTo(1);
        assertThat(decoded[0]).isEqualTo(0.0);

        // 0 and 23 are 0x8 then 0x6 0x7 0x1: four half bytes in two bytes
        assertThat(MSNumpress.encodePic(new double[] {0.0, 23.0}, 2, encoded)).isEqualTo(2);
        assertThat(Arrays.copyOf(encoded, 2)).containsExactly(0x86, 0x71);
        decoded = new double[4];
        assertThat(MSNumpress.decodePic(encoded, 2, decoded)).isEqualTo(2);
        assertThat(decoded).startsWith(0.0, 23.0);

        // 22.5 rounds half up to 23: 0x6 0x7 0x1 and a 0x0 pad
        assertThat(MSNumpress.encodePic(new double[] {22.5}, 1, encoded)).isEqualTo(2);
        assertThat(Arrays.copyOf(encoded, 2)).containsExactly(0x67, 0x10);
        assertThat(MSNumpress.decodePic(encoded, 2, decoded)).isEqualTo(1);
        assertThat(decoded[0]).isEqualTo(23.0);
    }

    @Test
    void decodePicRejectsPayloadsShorterThanEightBytes() {
        // a valid PIC payload has no fixed-point header, but decode() still asks for 8 bytes
        byte[] encoded = new byte[10];
        int length = MSNumpress.encodePic(new double[] {0.0, 23.0}, 2, encoded);

        assertThatThrownBy(() -> MSNumpress.decode(MSNumpress.ACC_NUMPRESS_PIC, encoded, length))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot decode numPic data, need at least 8 initial bytes for fixed point.");
        assertThatThrownBy(() -> MSNumpress.decode(MSNumpress.ACC_NUMPRESS_PIC, new byte[4], 8))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot decode numPic data, need at least 8 initial bytes for fixed point.");
    }

    @Test
    void picKeepsOnlyTheLow32BitsOfACountReadAsSigned() {
        assertThat(picRoundTrip(2147483647.0)).isEqualTo(2147483647.0);
        // 3e9 is past the signed int range: read back as 3e9 - 2^32
        assertThat(picRoundTrip(3e9)).isEqualTo(-1294967296.0);
        // 5e9 loses its bits above 32: 5e9 - 2^32
        assertThat(picRoundTrip(5e9)).isEqualTo(705032704.0);
        // a negative value is truncated toward zero after adding 0.5
        assertThat(picRoundTrip(-4.0)).isEqualTo(-3.0);
    }

    // ---------------------------------------------------------------- SLOF

    @Test
    void slofRoundTripOfFixtureIntensityIsTheUint16Log() {
        for (double[][] spectrum : SPECTRA) {
            double[] intensity = spectrum[1];
            int n = intensity.length;
            double fixedPoint = MSNumpress.optimalSlofFixedPoint(intensity, n);
            byte[] encoded = new byte[8 + 2 * n];

            int length = MSNumpress.encodeSlof(intensity, n, encoded, fixedPoint);
            double[] decoded = new double[n];
            int count = MSNumpress.decodeSlof(encoded, length, decoded);

            double[] expected = new double[n];
            double maxRelativeError = 0;
            for (int i = 0; i < n; i++) {
                int stored = (int) (Math.log(intensity[i] + 1) * fixedPoint + 0.5);
                expected[i] = Math.exp(((double) (0xffff & stored)) / fixedPoint) - 1;
                maxRelativeError = Math.max(maxRelativeError,
                        Math.abs(decoded[i] - intensity[i]) / (intensity[i] + 1));
            }
            assertThat(length).isEqualTo(8 + 2 * n);
            assertThat(MSNumpress.decodeFixedPoint(encoded)).isEqualTo(fixedPoint);
            assertThat(count).isEqualTo(n);
            assertThat(decoded).containsExactly(expected);
            assertThat(maxRelativeError).isLessThanOrEqualTo(Math.expm1(0.5 / fixedPoint) + 1e-12);
            assertThat(MSNumpress.decode(MSNumpress.ACC_NUMPRESS_SLOF, encoded, length)).containsExactly(expected);
        }
    }

    @Test
    void optimalSlofFixedPointFitsTheLargestLogIntoUint16() {
        assertThat(MSNumpress.optimalSlofFixedPoint(new double[0], 0)).isEqualTo(0.0);
        // log(x + 1) below 1 keeps the divisor at 1
        assertThat(MSNumpress.optimalSlofFixedPoint(new double[] {0.5, 1.0}, 2)).isEqualTo(65535.0);
        assertThat(MSNumpress.optimalSlofFixedPoint(new double[] {10.0, 1e6}, 2))
                .isEqualTo(Math.floor(0xFFFF / Math.log(1e6 + 1)))
                .isEqualTo(4743.0);
    }

    @Test
    void slofStoresLittleEndianUint16AfterTheFixedPoint() {
        byte[] none = new byte[8];
        assertThat(MSNumpress.encodeSlof(new double[0], 0, none, 1000)).isEqualTo(8);
        assertThat(MSNumpress.decodeSlof(none, 8, new double[0])).isEqualTo(0);
        assertThat(MSNumpress.decode(MSNumpress.ACC_NUMPRESS_SLOF, none, 8)).isEmpty();

        // log(1) * 1000 = 0 and log(2) * 1000 = 693.1 -> 693 = 0x02b5
        byte[] encoded = new byte[12];
        assertThat(MSNumpress.encodeSlof(new double[] {0.0, 1.0}, 2, encoded, 1000)).isEqualTo(12);
        assertThat(MSNumpress.decodeFixedPoint(encoded)).isEqualTo(1000.0);
        assertThat(Arrays.copyOfRange(encoded, 8, 12)).containsExactly(0, 0, 0xb5, 0x02);
        assertThat(MSNumpress.decode(MSNumpress.ACC_NUMPRESS_SLOF, encoded, 12))
                .containsExactly(0.0, Math.exp(693 / 1000.0) - 1);
    }

    @Test
    void slofWrapsALogPastUint16() {
        // log(1e6 + 1) * 10000 = 138155 does not fit 16 bits; 138155 - 2^17 = 7083 = 0x1bab is stored
        byte[] encoded = new byte[10];
        MSNumpress.encodeSlof(new double[] {1e6}, 1, encoded, 10000);

        assertThat(Arrays.copyOfRange(encoded, 8, 10)).containsExactly(0xab, 0x1b);
        assertThat(MSNumpress.decode(MSNumpress.ACC_NUMPRESS_SLOF, encoded, 10))
                .containsExactly(Math.exp(7083 / 10000.0) - 1);
    }

    @Test
    void decodeSlofReturnsMinusOneForShortOrOddPayloads() {
        byte[] encoded = new byte[12];
        MSNumpress.encodeSlof(new double[] {0.0, 1.0}, 2, encoded, 1000);

        double[] decoded = {7.0, 7.0};
        assertThat(MSNumpress.decodeSlof(new byte[4], 4, decoded)).isEqualTo(-1);
        assertThat(MSNumpress.decodeSlof(encoded, 11, decoded)).isEqualTo(-1);
        assertThat(decoded).containsExactly(7.0, 7.0);
        // decode() sizes its result from the payload and ignores the -1: one value, left at 0.0
        assertThat(MSNumpress.decode(MSNumpress.ACC_NUMPRESS_SLOF, Arrays.copyOf(encoded, 11), 11))
                .containsExactly(0.0);
    }

    // ---------------------------------------------------------------- decode dispatch

    @Test
    void decodeRejectsAnAccessionThatIsNoNumpressTerm() {
        assertThatThrownBy(() -> MSNumpress.decode("MS:1000576", new byte[8], 8))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("'MS:1000576' is not a numpress compression term");
    }

    // ---------------------------------------------------------------- helpers

    /** The half bytes {@link MSNumpress#encodeInt} writes for {@code x}, checked against its returned count. */
    private static int[] encodeInt(long x) {
        byte[] res = new byte[10];
        Arrays.fill(res, (byte) -1);
        int count = MSNumpress.encodeInt(x, res, 0);
        assertThat(res[count]).as("byte past the half bytes of %s", x).isEqualTo((byte) -1);
        int[] halfBytes = new int[count];
        for (int i = 0; i < count; i++) {
            halfBytes[i] = res[i];
        }
        return halfBytes;
    }

    /** {@code value} encoded and decoded with PIC alone. */
    private static double picRoundTrip(double value) {
        byte[] encoded = new byte[5];
        int length = MSNumpress.encodePic(new double[] {value}, 1, encoded);
        double[] decoded = new double[length * 2];
        assertThat(MSNumpress.decodePic(encoded, length, decoded)).isEqualTo(1);
        return decoded[0];
    }
}
