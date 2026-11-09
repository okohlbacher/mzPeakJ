package org.mzpeak.mzml;

import ms.numpress.MSNumpress;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakException;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.mzml.BinaryCodec.Decoded;
import org.mzpeak.mzml.BinaryCodec.Encoded;
import org.mzpeak.mzml.BinaryCodec.Kind;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.data.Offset.offset;
import static org.mzpeak.mzml.BinaryCodec.ASCII;
import static org.mzpeak.mzml.BinaryCodec.FLOAT16;
import static org.mzpeak.mzml.BinaryCodec.FLOAT32;
import static org.mzpeak.mzml.BinaryCodec.FLOAT64;
import static org.mzpeak.mzml.BinaryCodec.INT32;
import static org.mzpeak.mzml.BinaryCodec.INT64;
import static org.mzpeak.mzml.BinaryCodec.LINEAR;
import static org.mzpeak.mzml.BinaryCodec.LINEAR_ZLIB;
import static org.mzpeak.mzml.BinaryCodec.NO_COMPRESSION;
import static org.mzpeak.mzml.BinaryCodec.PIC;
import static org.mzpeak.mzml.BinaryCodec.PIC_ZLIB;
import static org.mzpeak.mzml.BinaryCodec.SLOF;
import static org.mzpeak.mzml.BinaryCodec.SLOF_ZLIB;
import static org.mzpeak.mzml.BinaryCodec.ZLIB;

/**
 * MZ-14: {@link BinaryCodec} decodes every binaryDataArray of the vendored mzML fixtures the converter tests rely on
 * (checked against has_uv.mzpeak where the two hold the same data), round-trips them under every precision and zlib
 * option, applies MS-Numpress within the codec's error bounds or falls back with a note (mzML design 5.7), decodes
 * golden Numpress payloads independently of its own encoder, and rejects what it cannot decode with an
 * {@link MzPeakException} naming the array.
 */
class BinaryCodecTest {

    private static final String CTX = "spectrum scan=19, m/z array";
    private static final String MZ = "MS:1000514";
    private static final String INTENSITY = "MS:1000515";
    private static final String WAVELENGTH = "MS:1000617";
    private static final String[] NUMPRESS = {LINEAR, SLOF, PIC};

    /** tiny: every spectrum and chromatogram array (8 + 4). */
    private static List<Array> tiny;
    /** small: spectrum 0 and the TIC (time, intensity, 64-bit integer ms level). */
    private static List<Array> small;
    /** TOFsulfas: spectrum 0 (MS, zlib) and spectrum 212 (the first UV spectrum, arrayLength 96). */
    private static List<Array> tofsulfas;
    /** diaPASEF: spectrum 0 (m/z, intensity, MS:1003006, zlib). */
    private static List<Array> diaPasef;

    @BeforeAll
    static void readFixtures() throws Exception {
        tiny = arrays(MzMLFixtures.TINY, Set.of(0, 1, 2, 3), true);
        small = arrays(MzMLFixtures.SMALL, Set.of(0), true);
        tofsulfas = arrays(MzMLFixtures.TOFSULFAS, Set.of(0, 212), false);
        diaPasef = arrays(MzMLFixtures.DIAPASEF, Set.of(0), false);
    }

    /** One binaryDataArray of a fixture as written: its owner, declared length, terms and Base64 text. */
    record Array(String fixture, String owner, int ownerIndex, String ownerId, int declaredLength,
                 Integer arrayLength, String arrayType, String dataType, List<String> compression, String text) {

        String context() {
            return fixture + " " + owner + " " + ownerId + ", " + arrayType;
        }

        Kind kind() {
            return switch (arrayType) {
                case MZ, "MS:1000595", WAVELENGTH -> Kind.AXIS;
                case INTENSITY -> Kind.INTENSITY;
                case "MS:1000516" -> Kind.CHARGE;
                case "MS:1000786" -> Kind.NON_STANDARD;
                default -> Kind.OTHER;
            };
        }

        @Override
        public String toString() {
            return context();
        }
    }

    static Stream<Array> fixtureArrays() {
        return Stream.of(tiny, small, tofsulfas, diaPasef).flatMap(List::stream);
    }

    // ---- (a) decoding the fixtures -------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureArrays")
    void everyFixtureArrayDecodesToItsDeclaredLength(Array a) {
        Decoded decoded = decode(a);

        assertThat(decoded.values()).hasSize(a.declaredLength());
        assertThat(decoded.roundedInt64()).isZero();
    }

    @Test
    void tinyHasEightSpectrumAndFourChromatogramArraysWithTheirValues() {
        assertThat(tiny).extracting(Array::owner).containsExactly("spectrum", "spectrum", "spectrum", "spectrum",
                "spectrum", "spectrum", "spectrum", "spectrum", "chromatogram", "chromatogram", "chromatogram",
                "chromatogram");
        assertThat(tiny).extracting(Array::declaredLength).containsExactly(15, 15, 10, 10, 0, 0, 15, 15, 15, 15,
                10, 10);

        assertThat(decode(tiny.get(0)).values()).containsExactly(range(0, 15, 1));
        assertThat(decode(tiny.get(1)).values()).containsExactly(range(15, 0, -1));
        assertThat(decode(tiny.get(2)).values()).containsExactly(range(0, 20, 2));
        assertThat(decode(tiny.get(3)).values()).containsExactly(range(20, 0, -2));
        // scan=21 has two empty arrays, no compression
        assertThat(tiny.get(4).ownerId()).isEqualTo("scan=21");
        assertThat(tiny.get(4).text()).isEmpty();
        assertThat(decode(tiny.get(4)).values()).isEmpty();
        assertThat(decode(tiny.get(5)).values()).isEmpty();
        // the sic chromatogram: time 0..9 s, intensity 10..1
        assertThat(decode(tiny.get(10)).values()).containsExactly(range(0, 10, 1));
        assertThat(decode(tiny.get(11)).values()).containsExactly(range(10, 0, -1));
    }

    @Test
    void smallSpectrum0IsUncompressedFloat64MzAndFloat32Intensity() {
        Array mz = find(small, "spectrum", 0, MZ);
        Array intensity = find(small, "spectrum", 0, INTENSITY);

        assertThat(mz.dataType()).isEqualTo(FLOAT64);
        assertThat(intensity.dataType()).isEqualTo(FLOAT32);
        assertThat(mz.compression()).containsExactly(NO_COMPRESSION);
        assertThat(intensity.compression()).containsExactly(NO_COMPRESSION);
        assertThat(decode(mz).values()).hasSize(19_913).isSorted();
        assertThat(decode(intensity).values()).hasSize(19_913);
    }

    @Test
    void smallTicMsLevelIsA64BitIntegerArrayThatAutoWritesAsSuch() {
        Array msLevel = find(small, "chromatogram", 0, "MS:1000786");

        Decoded decoded = decode(msLevel);

        assertThat(msLevel.dataType()).isEqualTo(INT64);
        assertThat(msLevel.arrayLength()).isEqualTo(48);
        assertThat(decoded.roundedInt64()).isZero();
        assertThat(Arrays.stream(decoded.values()).sum()).isEqualTo(82.0);
        assertThat(BinaryCodec.encode(decoded.values(), Kind.NON_STANDARD, 0, true, null).dataTypeTerm())
                .isEqualTo(INT64);
    }

    @Test
    void tofsulfasSpectrum0AndWavelengthSpectrum0AreBitEqualToHasUv() {
        Array mz = find(tofsulfas, "spectrum", 0, MZ);
        Array intensity = find(tofsulfas, "spectrum", 0, INTENSITY);
        Array wavelength = find(tofsulfas, "spectrum", 212, WAVELENGTH);
        Array absorbance = find(tofsulfas, "spectrum", 212, INTENSITY);
        assertThat(mz.compression()).containsExactly(ZLIB);
        assertThat(wavelength.arrayLength()).isEqualTo(96);
        assertThat(absorbance.arrayLength()).isNull();

        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("has_uv.mzpeak"))) {
            Spectrum spectrum = reader.getSpectrum(0).orElseThrow();
            WavelengthSpectrum uv = reader.getWavelengthSpectrum(0).orElseThrow();
            assertThat(uv.id()).isEqualTo(wavelength.ownerId());

            assertBitEqual(decode(mz).values(), spectrum.mz());
            assertBitEqual(decode(intensity).values(), spectrum.intensity());
            assertBitEqual(decode(wavelength).values(), uv.wavelength());
            assertBitEqual(decode(absorbance).values(), uv.intensity());
        }
        assertThat(decode(wavelength).values()).hasSize(96);
    }

    @Test
    void diaPasefSpectrum0HasThreeZlibArraysOf205921Values() {
        assertThat(diaPasef).extracting(Array::arrayType).containsExactly(MZ, INTENSITY, "MS:1003006");
        for (Array a : diaPasef) {
            assertThat(a.compression()).containsExactly(ZLIB);
            assertThat(decode(a).values()).hasSize(205_921);
        }
        assertThat(diaPasef.get(2).arrayLength()).isEqualTo(205_921);
    }

    // ---- (b) round trips under every precision and zlib ----------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureArrays")
    void everyFixtureArrayRoundTripsUnderEveryPrecisionWithAndWithoutZlib(Array a) {
        double[] values = decode(a).values();
        String auto = BinaryCodec.encode(values, a.kind(), 0, false, null).dataTypeTerm();

        for (int bits : new int[] {0, 32, 64}) {
            for (boolean zlib : new boolean[] {false, true}) {
                Encoded encoded = BinaryCodec.encode(values, a.kind(), bits, zlib, null);

                assertThat(encoded.note()).isNull();
                assertThat(encoded.compressionTerm()).isEqualTo(zlib && values.length > 0 ? ZLIB : NO_COMPRESSION);
                if (bits != 0 && a.kind() != Kind.CHARGE) {
                    assertThat(encoded.dataTypeTerm()).isEqualTo(bits == 32 ? FLOAT32 : FLOAT64);
                } else {
                    assertThat(encoded.dataTypeTerm()).isEqualTo(auto);
                }
                double[] back = BinaryCodec.decode(encoded.base64(), encoded.dataTypeTerm(),
                        List.of(encoded.compressionTerm()), values.length, a.context()).values();
                if (bits == 32 && !auto.equals(FLOAT32)) {
                    assertBitEqual(back, narrowed(values));
                } else {
                    assertBitEqual(back, values);
                }
            }
        }
    }

    // ---- (c) Numpress on fixture arrays --------------------------------------------------------------------------

    static Stream<Arguments> numpressFixtureArrays() {
        Array smallMz = find(small, "spectrum", 0, MZ);
        Array smallIntensity = find(small, "spectrum", 0, INTENSITY);
        Array tinyMz = find(tiny, "spectrum", 0, MZ);
        Array tinyIntensity = find(tiny, "spectrum", 0, INTENSITY);
        return Stream.of(
                Arguments.of(smallMz, LINEAR), Arguments.of(smallIntensity, SLOF), Arguments.of(smallIntensity, PIC),
                Arguments.of(tinyMz, LINEAR), Arguments.of(tinyIntensity, SLOF), Arguments.of(tinyIntensity, PIC));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("numpressFixtureArrays")
    void numpressOnFixtureArraysStaysWithinTheCodecBounds(Array a, String codec) {
        double[] values = decode(a).values();
        for (boolean zlib : new boolean[] {false, true}) {
            Encoded encoded = BinaryCodec.encode(values, a.kind(), 0, zlib, codec);

            assertThat(encoded.note()).isNull();
            assertThat(encoded.dataTypeTerm()).isEqualTo(FLOAT64);
            assertThat(encoded.compressionTerm()).isEqualTo(zlib ? combined(codec) : codec);
            double[] back = decodeEncoded(encoded, values.length);
            assertWithinBounds(codec, back, values, payload(encoded));
        }
    }

    @Test
    void numpressCombinedTermsAreLinearSlofPicFollowedByZlib() {
        double[] values = {100.0, 200.0, 300.0};
        assertThat(BinaryCodec.encode(values, Kind.AXIS, 0, true, LINEAR).compressionTerm()).isEqualTo("MS:1002746");
        assertThat(BinaryCodec.encode(values, Kind.INTENSITY, 0, true, SLOF).compressionTerm()).isEqualTo("MS:1002748");
        assertThat(BinaryCodec.encode(values, Kind.INTENSITY, 0, true, PIC).compressionTerm()).isEqualTo("MS:1002747");
        assertThat(BinaryCodec.encode(values, Kind.AXIS, 0, false, LINEAR).compressionTerm()).isEqualTo("MS:1002312");
        assertThat(BinaryCodec.encode(values, Kind.INTENSITY, 0, false, SLOF).compressionTerm())
                .isEqualTo("MS:1002314");
        assertThat(BinaryCodec.encode(values, Kind.INTENSITY, 0, false, PIC).compressionTerm()).isEqualTo("MS:1002313");
    }

    @Test
    void slofOfSmallSpectrum0IsInsideTheLogBoundButOutsideARelativeOne() {
        double[] values = decode(find(small, "spectrum", 0, INTENSITY)).values();
        int i = 0;
        while (values[i] != 0.426025390625) {
            i++;
        }
        Encoded encoded = BinaryCodec.encode(values, Kind.INTENSITY, 0, false, SLOF);
        double fixedPoint = MSNumpress.decodeFixedPoint(payload(encoded));
        double back = decodeEncoded(encoded, values.length)[i];

        assertThat(fixedPoint).isEqualTo(4614.0);
        assertThat(back).isCloseTo(0.42588086, offset(1e-8));
        assertThat(Math.abs(Math.log(1 + back) - Math.log(1 + values[i])))
                .isLessThanOrEqualTo(0.5 / fixedPoint + 1e-12);
        assertThat(Math.abs(back - values[i]) / values[i]).isGreaterThan(3e-4);
    }

    @Test
    void slofKeepsValuesAboveTheIntRangeThatPicRefuses() {
        double[] values = {3.0e9, 1.0};
        Encoded encoded = BinaryCodec.encode(values, Kind.INTENSITY, 0, false, SLOF);

        assertThat(encoded.note()).isNull();
        assertThat(encoded.compressionTerm()).isEqualTo(SLOF);
        assertWithinBounds(SLOF, decodeEncoded(encoded, 2), values, payload(encoded));
    }

    // ---- (d) fallbacks -------------------------------------------------------------------------------------------

    static Stream<Arguments> fallbacks() {
        return Stream.of(
                Arguments.of(new double[] {1.0, Double.NaN}, LINEAR,
                        "MS-Numpress linear not applied: non-finite value"),
                Arguments.of(new double[] {-0.038, 0.1, 0.2}, LINEAR, "MS-Numpress linear not applied: negative value"),
                Arguments.of(new double[] {100.0}, LINEAR, "MS-Numpress linear not applied: single value"),
                Arguments.of(new double[] {0.0, 1.0e10}, LINEAR, "MS-Numpress linear not applied: fixed point below 1"),
                // (2^31 - 1) / max(v0, v1) overflows to +Infinity, which would encode both values as 0
                Arguments.of(new double[] {1.0e-300, 1.0e-300}, LINEAR,
                        "MS-Numpress linear not applied: fixed point not finite"),
                Arguments.of(new double[] {0.0, 0.0}, LINEAR, "MS-Numpress linear not applied: fixed point not finite"),
                // fixed point 715827882 from the first two values and the residuals; from i = 113512 on,
                // i * i * fixedPoint is 2^63 or more and encodeLinear saturates it at Long.MAX_VALUE
                Arguments.of(Named.of("i * i for i < 120000", squares(120000)), LINEAR,
                        "MS-Numpress linear not applied: value too large for the fixed point"),
                Arguments.of(new double[] {1.0, -2.0}, SLOF,
                        "MS-Numpress SLOF not applied: negative or non-finite value"),
                Arguments.of(new double[] {1.0, Double.POSITIVE_INFINITY}, SLOF,
                        "MS-Numpress SLOF not applied: negative or non-finite value"),
                Arguments.of(new double[] {1.0, -2.0}, PIC,
                        "MS-Numpress PIC not applied: negative or non-finite value"),
                Arguments.of(new double[] {1.0, Double.POSITIVE_INFINITY}, PIC,
                        "MS-Numpress PIC not applied: negative or non-finite value"),
                Arguments.of(new double[] {1.0, 2147483648.0}, PIC,
                        "MS-Numpress PIC not applied: count above 2147483647"),
                Arguments.of(new double[] {3.0e9}, PIC, "MS-Numpress PIC not applied: count above 2147483647"),
                Arguments.of(new double[] {4294967294.0}, PIC, "MS-Numpress PIC not applied: count above 2147483647"),
                Arguments.of(new double[] {4294967295.0}, PIC, "MS-Numpress PIC not applied: count above 2147483647"));
    }

    @ParameterizedTest(name = "{1} {0}")
    @MethodSource("fallbacks")
    void valuesTheCodecCannotEncodeFallBackToNoNumpressWithANote(double[] values, String codec, String note) {
        Kind kind = codec.equals(LINEAR) ? Kind.AXIS : Kind.INTENSITY;
        String auto = BinaryCodec.encode(values, kind, 0, false, null).dataTypeTerm();
        for (boolean zlib : new boolean[] {false, true}) {
            Encoded encoded = BinaryCodec.encode(values, kind, 0, zlib, codec);

            assertThat(encoded.note()).isEqualTo(note);
            assertThat(encoded.compressionTerm()).isEqualTo(zlib ? ZLIB : NO_COMPRESSION);
            assertThat(encoded.dataTypeTerm()).isEqualTo(auto);
            assertBitEqual(decodeEncoded(encoded, values.length), values);
        }
    }

    @Test
    void linearKeepsValuesUpToTheLastOneItsFixedPointEncodes() {
        double[] values = squares(113512); // i = 113511: i * i * 715827882 is still below 2^63
        for (boolean zlib : new boolean[] {false, true}) {
            Encoded encoded = BinaryCodec.encode(values, Kind.AXIS, 0, zlib, LINEAR);

            assertThat(encoded.note()).isNull();
            assertThat(encoded.compressionTerm()).isEqualTo(zlib ? LINEAR_ZLIB : LINEAR);
            assertThat(MSNumpress.decodeFixedPoint(payload(encoded))).isEqualTo(715827882.0);
            assertWithinBounds(LINEAR, decodeEncoded(encoded, values.length), values, payload(encoded));
        }
    }

    @Test
    void picKeepsCountsUpTo2147483647AndDecodesThemExactly() {
        for (boolean zlib : new boolean[] {false, true}) {
            Encoded pair = BinaryCodec.encode(new double[] {0.0, 2147483647.0}, Kind.INTENSITY, 0, zlib, PIC);
            Encoded single = BinaryCodec.encode(new double[] {2147483647.4}, Kind.INTENSITY, 0, zlib, PIC);

            assertThat(pair.note()).isNull();
            assertThat(single.note()).isNull();
            assertThat(pair.compressionTerm()).isEqualTo(zlib ? PIC_ZLIB : PIC);
            assertThat(decodeEncoded(pair, 2)).containsExactly(0.0, 2147483647.0);
            assertThat(decodeEncoded(single, 1)).containsExactly(2147483647.0);
        }
    }

    @Test
    void anMzArrayWithANanGetsNoNumpress() {
        Encoded encoded = BinaryCodec.encode(new double[] {100.5, Double.NaN, 300.25}, Kind.AXIS, 0, true, LINEAR);

        assertThat(encoded.note()).isEqualTo("MS-Numpress linear not applied: non-finite value");
        assertThat(encoded.compressionTerm()).isEqualTo(ZLIB);
        assertThat(encoded.dataTypeTerm()).isEqualTo(FLOAT32);
    }

    // ---- (e) short inputs ----------------------------------------------------------------------------------------

    @Test
    void numpressOfNoOneAndTwoValues() {
        for (boolean zlib : new boolean[] {false, true}) {
            for (String codec : NUMPRESS) {
                Kind kind = codec.equals(LINEAR) ? Kind.AXIS : Kind.INTENSITY;
                Encoded none = BinaryCodec.encode(new double[0], kind, 0, zlib, codec);
                assertThat(none).isEqualTo(new Encoded("", FLOAT32, NO_COMPRESSION, null));

                double[] two = {100.0, 200.75};
                Encoded encodedTwo = BinaryCodec.encode(two, kind, 0, zlib, codec);
                assertThat(encodedTwo.compressionTerm()).isEqualTo(zlib ? combined(codec) : codec);
                assertWithinBounds(codec, decodeEncoded(encodedTwo, 2), two, payload(encodedTwo));

                double[] one = {42.25};
                Encoded encodedOne = BinaryCodec.encode(one, kind, 0, zlib, codec);
                if (codec.equals(LINEAR)) {
                    assertThat(encodedOne.note()).isEqualTo("MS-Numpress linear not applied: single value");
                    assertThat(encodedOne.compressionTerm()).isEqualTo(zlib ? ZLIB : NO_COMPRESSION);
                    assertThat(decodeEncoded(encodedOne, 1)).containsExactly(42.25);
                } else {
                    assertThat(encodedOne.note()).isNull();
                    assertThat(encodedOne.compressionTerm()).isEqualTo(zlib ? combined(codec) : codec);
                    assertWithinBounds(codec, decodeEncoded(encodedOne, 1), one, payload(encodedOne));
                }
            }
        }
    }

    @Test
    void picPayloadsOfOneAndTwoBytesDecode() {
        byte[] one = {(byte) 0x80};
        byte[] two = {(byte) 0x86, 0x71};
        assertThatThrownBy(() -> MSNumpress.decode(PIC, one, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MSNumpress.decode(PIC, two, 2)).isInstanceOf(IllegalArgumentException.class);

        assertThat(BinaryCodec.decode(base64(one), FLOAT64, List.of(PIC), 1, CTX).values()).containsExactly(0.0);
        assertThat(BinaryCodec.decode(base64(two), FLOAT64, List.of(PIC), 2, CTX).values()).containsExactly(0.0, 23.0);
        assertThat(BinaryCodec.decode(base64(deflate(two)), FLOAT64, List.of(PIC_ZLIB), 2, CTX).values())
                .containsExactly(0.0, 23.0);
    }

    // ---- (f) empty arrays ----------------------------------------------------------------------------------------

    @Test
    void anEmptyTextDecodesToAnEmptyArrayUnderEveryCompression() {
        List<List<String>> compressions = new ArrayList<>(List.of(List.of(NO_COMPRESSION), List.of(ZLIB),
                List.of(LINEAR_ZLIB), List.of(PIC_ZLIB), List.of(SLOF_ZLIB)));
        for (String codec : NUMPRESS) {
            compressions.add(List.of(codec));
            compressions.add(List.of(codec, ZLIB));
            compressions.add(List.of(ZLIB, codec));
        }
        for (String dataType : new String[] {FLOAT32, FLOAT64, INT32, INT64}) {
            for (List<String> compression : compressions) {
                for (String text : new String[] {"", " \n\t\r "}) {
                    Decoded decoded = BinaryCodec.decode(text, dataType, compression, 0, CTX);
                    assertThat(decoded.values()).as(dataType + " " + compression).isEmpty();
                    assertThat(decoded.roundedInt64()).isZero();
                }
            }
        }
    }

    @Test
    void anEmptyArrayEncodesAsEmptyTextWithoutCompressionUnderEveryOption() {
        for (Kind kind : Kind.values()) {
            for (int bits : new int[] {0, 32, 64}) {
                for (boolean zlib : new boolean[] {false, true}) {
                    for (String codec : new String[] {null, LINEAR, SLOF, PIC}) {
                        Encoded encoded = BinaryCodec.encode(new double[0], kind, bits, zlib, codec);

                        String dataType = kind == Kind.CHARGE ? INT32
                                : bits == 64 ? FLOAT64
                                : bits == 32 ? FLOAT32
                                : kind == Kind.NON_STANDARD ? INT64 : FLOAT32;
                        assertThat(encoded).isEqualTo(new Encoded("", dataType, NO_COMPRESSION, null));
                    }
                }
            }
        }
    }

    @Test
    void anAsciiArrayDecodesToNoValues() {
        Decoded decoded = BinaryCodec.decode("aGVsbG8A", ASCII, List.of(NO_COMPRESSION), 6, CTX);

        assertThat(decoded.values()).isNull();
        assertThat(decoded.roundedInt64()).isZero();
    }

    // ---- (g) independent checks ----------------------------------------------------------------------------------

    @Test
    void goldenLinearVectorOfTheReferenceTestsDecodesThroughTheCodec() {
        double[] values = {100, 200, 300.00005, 400.0001};
        byte[] encoded = new byte[40];
        int length = MSNumpress.encodeLinear(values, 4, encoded, 100000.0);

        assertThat(length).isEqualTo(18);
        assertThat(Arrays.copyOfRange(encoded, 8, 12)).containsExactly(0x80, 0x96, 0x98, 0x00);
        assertThat(Arrays.copyOfRange(encoded, 16, 18)).containsExactly(0x75, 0x80);
        double[] back = BinaryCodec.decode(base64(deflate(Arrays.copyOf(encoded, 18))), FLOAT64,
                List.of(LINEAR_ZLIB), 4, CTX).values();
        assertThat(back).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat(Math.abs(back[i] - values[i])).isLessThanOrEqualTo(0.5 / 100000.0);
        }
    }

    @Test
    void linearZlibOutputDecodesWithInflaterAndMsNumpressDirectly() {
        double[] values = decode(find(small, "spectrum", 0, MZ)).values();
        Encoded encoded = BinaryCodec.encode(values, Kind.AXIS, 0, true, LINEAR);

        byte[] payload = inflate(Base64.getDecoder().decode(encoded.base64()));
        double[] buffer = new double[payload.length * 2];
        int n = MSNumpress.decodeLinear(payload, payload.length, buffer);

        assertThat(n).isEqualTo(values.length);
        assertBitEqual(Arrays.copyOf(buffer, n), decodeEncoded(encoded, values.length));
        // zlib after Numpress, with the zlib wrapper at level 6
        assertThat(encoded.base64()).isEqualTo(base64(deflate(payload)));
    }

    @Test
    void zlibOutputIsTheLevel6ZlibStreamOfTheLittleEndianValues() {
        double[] values = {1.5, -2.25, 1.0e300};
        ByteBuffer expected = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) {
            expected.putDouble(v);
        }

        Encoded zlib = BinaryCodec.encode(values, Kind.OTHER, 0, true, null);
        Encoded plain = BinaryCodec.encode(values, Kind.OTHER, 0, false, null);

        assertThat(zlib.base64()).isEqualTo(base64(deflate(expected.array())));
        assertThat(plain.base64()).isEqualTo(base64(expected.array()));
    }

    static Stream<Arguments> goldenPicPayloads() {
        return Stream.of(
                Arguments.of("00 0e 50 d2 b0", 3e9),
                Arguments.of("00 00 00 00 80", 2147483648.0),
                Arguments.of("0e ff ff ff f0", 4294967294.0),
                Arguments.of("0f ff ff ff 70", 2147483647.0));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("goldenPicPayloads")
    void goldenPicPayloadsDecodeAsUnsignedCounts(String hex, double count) {
        byte[] payload = HexFormat.ofDelimiter(" ").parseHex(hex);

        assertThat(BinaryCodec.decode(base64(payload), FLOAT64, List.of(PIC), 1, CTX).values()).containsExactly(count);
        assertThat(BinaryCodec.decode(base64(deflate(payload)), FLOAT64, List.of(PIC_ZLIB), 1, CTX).values())
                .containsExactly(count);
    }

    @Test
    void theLegacyPairOfNumpressAndZlibDecodesInEitherOrder() {
        double[] values = {10.0, 20.5, 31.25, 40.0};
        for (String codec : NUMPRESS) {
            Encoded encoded = BinaryCodec.encode(values, Kind.INTENSITY, 0, true, codec);
            for (List<String> pair : List.of(List.of(codec, ZLIB), List.of(ZLIB, codec))) {
                double[] back = BinaryCodec.decode(encoded.base64(), FLOAT64, pair, 4, CTX).values();
                assertBitEqual(back, decodeEncoded(encoded, 4));
                assertWithinBounds(codec, back, values, payload(encoded));
            }
        }
    }

    // ---- (h) precision AUTO --------------------------------------------------------------------------------------

    @Test
    void autoPicksTheNarrowestLosslessType() {
        double[] mz = decode(find(small, "spectrum", 0, MZ)).values();
        double[] intensity = decode(find(small, "spectrum", 0, INTENSITY)).values();

        assertThat(BinaryCodec.encode(mz, Kind.AXIS, 0, true, null).dataTypeTerm()).isEqualTo(FLOAT64);
        assertThat(BinaryCodec.encode(intensity, Kind.INTENSITY, 0, true, null).dataTypeTerm()).isEqualTo(FLOAT32);
        assertThat(BinaryCodec.encode(new double[] {1, 2, 3}, Kind.CHARGE, 0, true, null).dataTypeTerm())
                .isEqualTo(INT32);
        assertThat(BinaryCodec.encode(new double[] {1, 2}, Kind.NON_STANDARD, 0, true, null).dataTypeTerm())
                .isEqualTo(INT64);
        assertThat(BinaryCodec.encode(new double[] {1.5, Double.NaN}, Kind.OTHER, 0, true, null).dataTypeTerm())
                .isEqualTo(FLOAT32);
    }

    static Stream<Arguments> integerKinds() {
        double beyond = 0x1p53 + 2;
        return Stream.of(
                Arguments.of(Kind.CHARGE, new double[] {1, 2, 3}, INT32),
                Arguments.of(Kind.CHARGE, new double[] {Integer.MIN_VALUE, Integer.MAX_VALUE}, INT32),
                Arguments.of(Kind.CHARGE, new double[] {1, 2.5}, FLOAT64),
                Arguments.of(Kind.CHARGE, new double[] {1, -3.0e9}, FLOAT64),
                Arguments.of(Kind.CHARGE, new double[] {1, 3.0e9}, FLOAT64),
                Arguments.of(Kind.CHARGE, new double[] {-0.0}, FLOAT64),
                Arguments.of(Kind.CHARGE, new double[] {Double.NaN}, FLOAT64),
                Arguments.of(Kind.NON_STANDARD, new double[] {-0x1p53, 0x1p53}, INT64),
                Arguments.of(Kind.NON_STANDARD, new double[] {1, beyond}, FLOAT64),
                Arguments.of(Kind.NON_STANDARD, new double[] {1, -beyond}, FLOAT64),
                Arguments.of(Kind.NON_STANDARD, new double[] {1, 2.5}, FLOAT64),
                Arguments.of(Kind.NON_STANDARD, new double[] {Double.POSITIVE_INFINITY}, FLOAT64));
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource("integerKinds")
    void autoWritesChargeAsInt32AndNonStandardAsInt64WhenEveryValueIsAnExactInteger(Kind kind, double[] values,
                                                                                     String dataType) {
        for (boolean zlib : new boolean[] {false, true}) {
            Encoded encoded = BinaryCodec.encode(values, kind, 0, zlib, null);

            assertThat(encoded.dataTypeTerm()).isEqualTo(dataType);
            assertBitEqual(decodeEncoded(encoded, values.length), values);
        }
    }

    @Test
    void forcedPrecisionAppliesToEveryKindButCharge() {
        double[] values = {1, 2, 3};
        assertThat(BinaryCodec.encode(values, Kind.CHARGE, 32, false, null).dataTypeTerm()).isEqualTo(INT32);
        assertThat(BinaryCodec.encode(values, Kind.CHARGE, 64, false, null).dataTypeTerm()).isEqualTo(INT32);
        assertThat(BinaryCodec.encode(values, Kind.NON_STANDARD, 32, false, null).dataTypeTerm()).isEqualTo(FLOAT32);
        assertThat(BinaryCodec.encode(values, Kind.NON_STANDARD, 64, false, null).dataTypeTerm()).isEqualTo(FLOAT64);
        assertThat(BinaryCodec.encode(values, Kind.AXIS, 64, false, null).dataTypeTerm()).isEqualTo(FLOAT64);
        // a value that float32 cannot hold is narrowed under 32
        Encoded narrowed = BinaryCodec.encode(new double[] {0.1}, Kind.OTHER, 32, false, null);
        assertThat(narrowed.dataTypeTerm()).isEqualTo(FLOAT32);
        assertThat(decodeEncoded(narrowed, 1)).containsExactly((double) 0.1f);
    }

    @Test
    void numpressWithForcedPrecisionStillWrites64BitFloatAndItsFallbackTheForcedType() {
        assertThat(BinaryCodec.encode(new double[] {1.5, 2.5}, Kind.INTENSITY, 32, false, PIC).dataTypeTerm())
                .isEqualTo(FLOAT64);
        assertThat(BinaryCodec.encode(new double[] {1.5, -2.5}, Kind.INTENSITY, 32, false, PIC).dataTypeTerm())
                .isEqualTo(FLOAT32);
    }

    // ---- (i) int64 -----------------------------------------------------------------------------------------------

    @Test
    void int64ValuesBeyond2To53RoundToTheNearestDoubleAndAreCounted() {
        long[] longs = {9007199254740993L, Long.MAX_VALUE, -9007199254740993L};
        Decoded decoded = BinaryCodec.decode(base64(int64(longs)), INT64, List.of(NO_COMPRESSION), 3, CTX);

        assertThat(decoded.values()).containsExactly(9007199254740992.0, 9.223372036854775807E18,
                -9007199254740992.0);
        assertThat(decoded.roundedInt64()).isEqualTo(3);
        Decoded small = BinaryCodec.decode(base64(int64(new long[] {1, 2})), INT64, List.of(NO_COMPRESSION), 2, CTX);
        assertThat(small.values()).containsExactly(1.0, 2.0);
        assertThat(small.roundedInt64()).isZero();
    }

    @Test
    void int64ValuesBeyond2To53ThatADoubleHoldsAreNotCounted() {
        // -2^63, 2^54 and 2^53 + 2 are doubles; 2^63 (the double of Long.MAX_VALUE) is no long
        long[] longs = {Long.MIN_VALUE, 1L << 54, (1L << 53) + 2, Long.MAX_VALUE - 1};
        Decoded decoded = BinaryCodec.decode(base64(int64(longs)), INT64, List.of(NO_COMPRESSION), 4, CTX);

        assertThat(decoded.values()).containsExactly(-0x1p63, 0x1p54, 0x1p53 + 2, 0x1p63);
        assertThat(decoded.roundedInt64()).isEqualTo(1);
    }

    @Test
    void int64AndInt32ArraysRoundTripThroughZlib() {
        Decoded int64 = BinaryCodec.decode(base64(deflate(int64(new long[] {1, 2}))), INT64, List.of(ZLIB), 2, CTX);
        assertThat(int64.values()).containsExactly(1.0, 2.0);
        assertThat(int64.roundedInt64()).isZero();

        byte[] int32 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(-7).putInt(3).array();
        assertThat(BinaryCodec.decode(base64(int32), INT32, List.of(NO_COMPRESSION), 2, CTX).values())
                .containsExactly(-7.0, 3.0);
    }

    // ---- (j) errors ----------------------------------------------------------------------------------------------

    @Test
    void sixteenBitFloatIsRejected() {
        assertThatThrownBy(() -> BinaryCodec.decode("AAAA", FLOAT16, List.of(NO_COMPRESSION), 1, CTX))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessage("16-bit float (MS:1000520) is not supported in " + CTX);
    }

    @Test
    void anUnsupportedOrMissingDataTypeIsRejected() {
        assertThatThrownBy(() -> BinaryCodec.decode("", "MS:1000999", List.of(NO_COMPRESSION), 0, CTX))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessage("unsupported binary data type MS:1000999 in " + CTX);
        assertThatThrownBy(() -> BinaryCodec.decode("", null, List.of(NO_COMPRESSION), 0, CTX))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessage("no binary data type in " + CTX);
    }

    @Test
    void unsupportedCompressionsAreRejected() {
        for (String term : new String[] {"MS:1003089", "MS:1003088", "MS:1003090", "MS:1003780", "MS:1003783",
                "MS:1003826"}) {
            assertThatThrownBy(() -> BinaryCodec.decode("", FLOAT64, List.of(term), 0, CTX))
                    .isExactlyInstanceOf(MzPeakException.class)
                    .hasMessage("unsupported compression " + term + " in " + CTX);
        }
        assertThatThrownBy(() -> BinaryCodec.decode("", FLOAT64, List.of(ZLIB, "MS:1003089"), 0, CTX))
                .hasMessage("unsupported compression MS:1003089 in " + CTX);
    }

    @Test
    void compressionTermsThatAreNotOneTermOrTheLegacyPairAreRejected() {
        List<List<String>> invalid = List.of(List.of(), List.of(NO_COMPRESSION, ZLIB), List.of(ZLIB, ZLIB),
                List.of(LINEAR, SLOF), List.of(NO_COMPRESSION, LINEAR), List.of(LINEAR_ZLIB, ZLIB),
                List.of(LINEAR, ZLIB, NO_COMPRESSION));
        for (List<String> terms : invalid) {
            assertThatThrownBy(() -> BinaryCodec.decode("", FLOAT64, terms, 0, CTX))
                    .isExactlyInstanceOf(MzPeakException.class)
                    .hasMessage("unsupported compression terms " + terms + " in " + CTX);
        }
    }

    @Test
    void invalidBase64IsRejected() {
        assertThatThrownBy(() -> BinaryCodec.decode("AAAA$AAA", FLOAT64, List.of(NO_COMPRESSION), 1, CTX))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("invalid Base64 in " + CTX + ": ")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whitespaceInTheBase64TextIsIgnored() {
        Array a = tiny.get(0);
        String broken = " " + a.text().substring(0, 40) + "\n  \t" + a.text().substring(40) + "\r\n";

        assertThat(BinaryCodec.decode(broken, FLOAT64, a.compression(), 15, CTX).values())
                .containsExactly(range(0, 15, 1));
    }

    @Test
    void aTruncatedCorruptOrDictionaryZlibStreamIsRejected() {
        byte[] stream = deflate(int64(new long[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}));
        String truncated = base64(Arrays.copyOf(stream, stream.length / 2));
        assertThatThrownBy(() -> BinaryCodec.decode(truncated, INT64, List.of(ZLIB), 10, CTX))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessage("truncated zlib stream (or one that needs a preset dictionary) in " + CTX);

        Deflater deflater = new Deflater(6);
        deflater.setDictionary(new byte[] {1, 2, 3, 4});
        byte[] withDictionary = deflate(deflater, int64(new long[] {1, 2}));
        assertThatThrownBy(() -> BinaryCodec.decode(base64(withDictionary), INT64, List.of(ZLIB), 2, CTX))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessage("truncated zlib stream (or one that needs a preset dictionary) in " + CTX);

        assertThatThrownBy(() -> BinaryCodec.decode(base64(new byte[] {0, 1, 2, 3}), INT64, List.of(ZLIB), 0, CTX))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("invalid zlib stream in " + CTX + ": ");
    }

    @Test
    void aPayloadThatIsNoWholeNumberOfValuesIsRejected() {
        assertThatThrownBy(() -> BinaryCodec.decode(base64(new byte[12]), FLOAT64, List.of(NO_COMPRESSION), 1, CTX))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessage("12 bytes are not a whole number of 8-byte values in " + CTX);
    }

    @Test
    void shortOrCorruptNumpressPayloadsAreRejected() {
        assertThatThrownBy(() -> BinaryCodec.decode(base64(new byte[4]), FLOAT64, List.of(SLOF), 0, CTX))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessage("MS-Numpress SLOF payload of 4 bytes is too short or corrupt in " + CTX);
        assertThatThrownBy(() -> BinaryCodec.decode(base64(new byte[9]), FLOAT64, List.of(SLOF), 0, CTX))
                .hasMessage("MS-Numpress SLOF payload of 9 bytes is too short or corrupt in " + CTX);
        assertThatThrownBy(() -> BinaryCodec.decode(base64(new byte[14]), FLOAT64, List.of(LINEAR), 1, CTX))
                .hasMessage("MS-Numpress linear payload of 14 bytes is too short or corrupt in " + CTX);
        assertThatThrownBy(() -> BinaryCodec.decode(base64(deflate(new byte[0])), FLOAT64, List.of(LINEAR_ZLIB), 0,
                CTX)).hasMessage("MS-Numpress linear payload of 0 bytes is too short or corrupt in " + CTX);
        // a count that announces more half bytes than the payload has
        assertThatThrownBy(() -> BinaryCodec.decode(base64(new byte[] {0x01}), FLOAT64, List.of(PIC), 1, CTX))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessage("corrupt MS-Numpress PIC payload in " + CTX)
                .hasCauseInstanceOf(ArrayIndexOutOfBoundsException.class);
        assertThatThrownBy(() -> BinaryCodec.decode(base64(new byte[17]), FLOAT64, List.of(LINEAR), 3, CTX))
                .hasMessage("corrupt MS-Numpress linear payload in " + CTX);
    }

    /**
     * A zlib stream is inflated only up to the bytes its declared length allows: the length times the width for a
     * data type, 16 + 5 bytes per value for an MS-Numpress payload. A few KB of zlib zeros stop after the first
     * buffer, not after 8 MiB (or with an OutOfMemoryError for a larger stream).
     */
    @Test
    void aZlibStreamThatInflatesBeyondWhatTheDeclaredLengthAllowsIsRejectedEarly() {
        int zeros = 8 << 20;
        String bomb = base64(deflate(new byte[zeros]));
        assertThat(bomb.length()).isLessThan(16 << 10);

        Throwable raw = catchThrowable(() -> BinaryCodec.decode(bomb, FLOAT64, List.of(ZLIB), 1, CTX));
        assertThat(raw).isExactlyInstanceOf(MzPeakException.class)
                .hasMessageMatching("zlib stream inflates to at least \\d+ bytes, more than the 8 bytes the declared"
                        + " length 1 allows, in " + Pattern.quote(CTX));
        assertThat(inflatedSoFar(raw)).isGreaterThan(8).isLessThan(zeros);

        for (List<String> terms : List.of(List.of(LINEAR_ZLIB), List.of(SLOF, ZLIB), List.of(PIC_ZLIB))) {
            Throwable numpress = catchThrowable(() -> BinaryCodec.decode(bomb, FLOAT64, terms, 1, CTX));
            assertThat(numpress).as("%s", terms).isExactlyInstanceOf(MzPeakException.class)
                    .hasMessageMatching("zlib stream inflates to at least \\d+ bytes, more than the 21 bytes the"
                            + " declared length 1 allows, in " + Pattern.quote(CTX));
            assertThat(inflatedSoFar(numpress)).isGreaterThan(21).isLessThan(zeros);
        }
    }

    @Test
    void aDeclaredLengthWhoseInflatedBytesNoArrayCanHoldIsRejectedBeforeInflating() {
        String stream = base64(deflate(new byte[8]));
        assertThatThrownBy(() -> BinaryCodec.decode(stream, INT32, List.of(ZLIB), 536_870_910, CTX))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessage("declared length 536870910 allows 2147483640 inflated bytes, more than a byte array can"
                        + " hold, in " + CTX);
        assertThatThrownBy(() -> BinaryCodec.decode(stream, INT32, List.of(ZLIB), 536_870_909, CTX))
                .hasMessage("decoded length 2 differs from the declared length 536870909 in " + CTX);
        assertThatThrownBy(() -> BinaryCodec.decode(stream, FLOAT64, List.of(LINEAR_ZLIB), 429_496_726, CTX))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessage("declared length 429496726 allows 2147483646 inflated bytes, more than a byte array can"
                        + " hold, in " + CTX);
    }

    @Test
    void bytesAfterTheEndOfTheZlibStreamAreRejected() {
        byte[] stream = deflate(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(1.0).array());
        byte[] trailing = Arrays.copyOf(stream, stream.length + 5);
        System.arraycopy(new byte[] {1, 2, 3, 4, 5}, 0, trailing, stream.length, 5);
        assertThat(BinaryCodec.decode(base64(stream), FLOAT64, List.of(ZLIB), 1, CTX).values()).containsExactly(1.0);

        assertThatThrownBy(() -> BinaryCodec.decode(base64(trailing), FLOAT64, List.of(ZLIB), 1, CTX))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessage("bytes after the end of the zlib stream in " + CTX);
    }

    /**
     * A linear or SLOF fixed point of 0, of either sign, or NaN is rejected (the reference decoders divide by it and
     * return NaN or infinite values), once the payload holds a value; a header without values decodes to none. Every
     * other fixed point, infinite and negative ones included, decodes to what the reference decoders return.
     */
    @Test
    void aLinearOrSlofFixedPointOf0OrNaNIsRejected() {
        assertThatThrownBy(() -> BinaryCodec.decode(base64(new byte[16]), FLOAT64, List.of(LINEAR), 2, CTX))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessage("MS-Numpress linear fixed point 0.0 is 0 or NaN in " + CTX);
        assertThatThrownBy(() -> BinaryCodec.decode(base64(new byte[10]), FLOAT64, List.of(SLOF), 1, CTX))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessage("MS-Numpress SLOF fixed point 0.0 is 0 or NaN in " + CTX);
        for (double fixedPoint : new double[] {-0.0, Double.NaN}) {
            byte[] linear = linearWithFixedPoint(fixedPoint);
            assertThatThrownBy(() -> BinaryCodec.decode(base64(deflate(linear)), FLOAT64, List.of(LINEAR_ZLIB), 2,
                    CTX)).isExactlyInstanceOf(MzPeakException.class)
                    .hasMessage("MS-Numpress linear fixed point " + fixedPoint + " is 0 or NaN in " + CTX);
            byte[] slof = Arrays.copyOf(linear, 10);
            assertThatThrownBy(() -> BinaryCodec.decode(base64(slof), FLOAT64, List.of(SLOF), 1, CTX))
                    .isExactlyInstanceOf(MzPeakException.class)
                    .hasMessage("MS-Numpress SLOF fixed point " + fixedPoint + " is 0 or NaN in " + CTX);
        }
        for (double fixedPoint : new double[] {-100.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            byte[] linear = linearWithFixedPoint(fixedPoint);
            double[] reference = new double[32];
            int n = MSNumpress.decodeLinear(linear, linear.length, reference);
            assertThat(BinaryCodec.decode(base64(deflate(linear)), FLOAT64, List.of(LINEAR_ZLIB), 2, CTX).values())
                    .as("linear %s", fixedPoint).containsExactly(Arrays.copyOf(reference, n));
            byte[] slof = Arrays.copyOf(linear, 10);
            n = MSNumpress.decodeSlof(slof, slof.length, reference);
            assertThat(BinaryCodec.decode(base64(slof), FLOAT64, List.of(SLOF), 1, CTX).values())
                    .as("SLOF %s", fixedPoint).containsExactly(Arrays.copyOf(reference, n));
        }
        assertThat(BinaryCodec.decode(base64(new byte[8]), FLOAT64, List.of(LINEAR), 0, CTX).values()).isEmpty();
        assertThat(BinaryCodec.decode(base64(new byte[8]), FLOAT64, List.of(SLOF), 0, CTX).values()).isEmpty();
    }

    /**
     * {@code MSNumpress.optimalLinearFixedPoint}, the algorithm of the C++ reference encoder, gives +Infinity for
     * [0.0] (a one-point chromatogram at RT 0.0) and [0.0, 0.0] and a negative fixed point for [-1.0] and
     * [-2.0, -1.0]; the reference decoder reads these payloads back exactly, and so does decode, alone and after zlib.
     */
    @Test
    void referenceLinearPayloadsWithAnInfiniteOrNegativeFixedPointDecodeExactly() {
        double[][] cases = {{0.0}, {-1.0}, {0.0, 0.0}, {-2.0, -1.0}};
        double[] fixedPoints = {Double.POSITIVE_INFINITY, -4294967295.0, Double.POSITIVE_INFINITY, -2147483647.0};
        for (int c = 0; c < cases.length; c++) {
            double[] values = cases[c];
            double fixedPoint = MSNumpress.optimalLinearFixedPoint(values, values.length);
            assertThat(fixedPoint).as("fixed point of %s", Arrays.toString(values)).isEqualTo(fixedPoints[c]);
            byte[] out = new byte[8 + 5 * values.length];
            byte[] payload = Arrays.copyOf(out, MSNumpress.encodeLinear(values, values.length, out, fixedPoint));
            double[] reference = new double[2 * payload.length];
            assertThat(Arrays.copyOf(reference, MSNumpress.decodeLinear(payload, payload.length, reference)))
                    .as("reference decoding of %s", Arrays.toString(values)).containsExactly(values);

            assertThat(BinaryCodec.decode(base64(payload), FLOAT64, List.of(LINEAR), values.length, CTX).values())
                    .as("%s", Arrays.toString(values)).containsExactly(values);
            for (List<String> terms : List.of(List.of(LINEAR_ZLIB), List.of(ZLIB, LINEAR))) {
                assertThat(BinaryCodec.decode(base64(deflate(payload)), FLOAT64, terms, values.length, CTX).values())
                        .as("%s with %s", Arrays.toString(values), terms).containsExactly(values);
            }
        }
    }

    @Test
    void aDecodedLengthOtherThanTheDeclaredOneIsRejected() {
        Array a = tiny.get(0);
        assertThatThrownBy(() -> BinaryCodec.decode(a.text(), a.dataType(), a.compression(), 14, CTX))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessage("decoded length 15 differs from the declared length 14 in " + CTX);
        assertThatThrownBy(() -> BinaryCodec.decode("", FLOAT64, List.of(ZLIB), 3, CTX))
                .hasMessage("decoded length 0 differs from the declared length 3 in " + CTX);
        assertThatThrownBy(() -> BinaryCodec.decode(base64(new byte[] {(byte) 0x86, 0x71}), FLOAT64, List.of(PIC), 3,
                CTX)).hasMessage("decoded length 2 differs from the declared length 3 in " + CTX);
    }

    @Test
    void invalidEncodeArgumentsAreRejected() {
        double[] values = {1.0};
        assertThatThrownBy(() -> BinaryCodec.encode(values, Kind.AXIS, 16, false, null))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("forcedBits must be 0, 32 or 64, not 16");
        assertThatThrownBy(() -> BinaryCodec.encode(values, Kind.AXIS, 0, false, LINEAR_ZLIB))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("not an MS-Numpress term: " + LINEAR_ZLIB);
        assertThatThrownBy(() -> BinaryCodec.encode(values, null, 0, false, null))
                .isExactlyInstanceOf(NullPointerException.class);
    }

    // ---- (k) determinism -----------------------------------------------------------------------------------------

    @Test
    void encodingTwiceGivesEqualStrings() {
        double[] mz = decode(find(small, "spectrum", 0, MZ)).values();
        double[] intensity = decode(find(small, "spectrum", 0, INTENSITY)).values();
        for (boolean zlib : new boolean[] {false, true}) {
            assertThat(BinaryCodec.encode(mz, Kind.AXIS, 0, zlib, null))
                    .isEqualTo(BinaryCodec.encode(mz, Kind.AXIS, 0, zlib, null));
            assertThat(BinaryCodec.encode(mz, Kind.AXIS, 0, zlib, LINEAR))
                    .isEqualTo(BinaryCodec.encode(mz, Kind.AXIS, 0, zlib, LINEAR));
            assertThat(BinaryCodec.encode(intensity, Kind.INTENSITY, 0, zlib, SLOF))
                    .isEqualTo(BinaryCodec.encode(intensity, Kind.INTENSITY, 0, zlib, SLOF));
            assertThat(BinaryCodec.encode(intensity, Kind.INTENSITY, 0, zlib, PIC))
                    .isEqualTo(BinaryCodec.encode(intensity, Kind.INTENSITY, 0, zlib, PIC));
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static Decoded decode(Array a) {
        return BinaryCodec.decode(a.text(), a.dataType(), a.compression(), a.declaredLength(), a.context());
    }

    private static double[] decodeEncoded(Encoded encoded, int length) {
        return BinaryCodec.decode(encoded.base64(), encoded.dataTypeTerm(), List.of(encoded.compressionTerm()),
                length, CTX).values();
    }

    /** The Numpress payload of an encoded array, inflated by the test when the term includes zlib. */
    private static byte[] payload(Encoded encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded.base64());
        return List.of(LINEAR_ZLIB, PIC_ZLIB, SLOF_ZLIB).contains(encoded.compressionTerm()) ? inflate(bytes) : bytes;
    }

    /** The inflated byte count the message of a rejected zlib stream gives. */
    private static long inflatedSoFar(Throwable e) {
        Matcher m = Pattern.compile("zlib stream inflates to at least (\\d+) bytes, .*").matcher(e.getMessage());
        assertThat(m.matches()).as(e.getMessage()).isTrue();
        return Long.parseLong(m.group(1));
    }

    private static String combined(String codec) {
        return codec.equals(LINEAR) ? LINEAR_ZLIB : codec.equals(SLOF) ? SLOF_ZLIB : PIC_ZLIB;
    }

    /** The error bounds of design 5.7: linear and PIC absolute, SLOF in log space. */
    private static void assertWithinBounds(String codec, double[] back, double[] values, byte[] payload) {
        assertThat(back).hasSameSizeAs(values);
        for (int i = 0; i < values.length; i++) {
            double v = values[i];
            double w = back[i];
            if (codec.equals(LINEAR)) {
                double fixedPoint = MSNumpress.decodeFixedPoint(payload);
                assertThat(Math.abs(w - v)).as("linear %d", i)
                        .isLessThanOrEqualTo(0.5 / fixedPoint + 1e-12 * Math.abs(v));
            } else if (codec.equals(SLOF)) {
                double fixedPoint = MSNumpress.decodeFixedPoint(payload);
                assertThat(Math.abs(Math.log(1 + w) - Math.log(1 + v))).as("SLOF %d", i)
                        .isLessThanOrEqualTo(0.5 / fixedPoint + 1e-12);
            } else {
                assertThat(Math.abs(w - v)).as("PIC %d", i).isLessThanOrEqualTo(0.5);
            }
        }
    }

    private static void assertBitEqual(double[] actual, double[] expected) {
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.length; i++) {
            if (Double.doubleToRawLongBits(actual[i]) != Double.doubleToRawLongBits(expected[i])) {
                fail("value " + i + ": " + actual[i] + " is not bit-equal to " + expected[i]);
            }
        }
    }

    private static double[] narrowed(double[] values) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (float) values[i];
        }
        return out;
    }

    /** i * i for i from 0 below {@code n}. */
    private static double[] squares(int n) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = (double) i * i;
        }
        return out;
    }

    private static double[] range(int from, int to, int step) {
        double[] out = new double[Math.abs(to - from) / Math.abs(step)];
        for (int i = 0; i < out.length; i++) {
            out[i] = from + i * step;
        }
        return out;
    }

    private static byte[] int64(long[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (long v : values) {
            buffer.putLong(v);
        }
        return buffer.array();
    }

    /** A 16-byte linear payload of two values, 10 and 0 before the division by {@code fixedPoint}. */
    private static byte[] linearWithFixedPoint(double fixedPoint) {
        byte[] linear = new byte[16];
        MSNumpress.encodeFixedPoint(fixedPoint, linear);
        linear[8] = 10;
        return linear;
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] deflate(byte[] bytes) {
        return deflate(new Deflater(6), bytes);
    }

    private static byte[] deflate(Deflater deflater, byte[] bytes) {
        deflater.setInput(bytes);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] bytes) {
        Inflater inflater = new Inflater();
        inflater.setInput(bytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try {
            while (!inflater.finished()) {
                out.write(buffer, 0, inflater.inflate(buffer));
            }
        } catch (DataFormatException e) {
            throw new AssertionError(e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    private static Array find(List<Array> arrays, String owner, int ownerIndex, String arrayType) {
        return arrays.stream()
                .filter(a -> a.owner().equals(owner) && a.ownerIndex() == ownerIndex && a.arrayType().equals(arrayType))
                .findFirst().orElseThrow();
    }

    /**
     * The binaryDataArrays of the spectra with an index in {@code spectra} and, if {@code chromatograms}, of every
     * chromatogram of a vendored fixture, in document order. The declared length is {@code arrayLength} when present,
     * else the owner's {@code defaultArrayLength} (design 5.7).
     */
    private static List<Array> arrays(String fixture, Set<Integer> spectra, boolean chromatograms) throws Exception {
        List<Array> out = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        boolean raw = fixture.equals(MzMLFixtures.TINY);
        try (InputStream in = raw ? MzMLFixtures.open(fixture)
                : new GZIPInputStream(MzMLFixtures.open(fixture + ".gz"))) {
            XMLStreamReader r = factory.createXMLStreamReader(in);
            String owner = null;
            String ownerId = null;
            int ownerIndex = -1;
            int defaultLength = -1;
            Integer arrayLength = null;
            String arrayType = null;
            String dataType = null;
            List<String> compression = null;
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                switch (r.getLocalName()) {
                    case "spectrum", "chromatogram" -> {
                        owner = r.getLocalName();
                        ownerId = r.getAttributeValue(null, "id");
                        ownerIndex = Integer.parseInt(r.getAttributeValue(null, "index"));
                        defaultLength = Integer.parseInt(r.getAttributeValue(null, "defaultArrayLength"));
                    }
                    case "binaryDataArray" -> {
                        String length = r.getAttributeValue(null, "arrayLength");
                        arrayLength = length == null ? null : Integer.valueOf(length);
                        arrayType = null;
                        dataType = null;
                        compression = new ArrayList<>();
                    }
                    case "cvParam" -> {
                        String accession = r.getAttributeValue(null, "accession");
                        if (compression == null) {
                            continue; // a param outside a binaryDataArray
                        }
                        if (MzMLMappingCheck.DATA_TYPES.contains(accession)) {
                            dataType = accession;
                        } else if (MzMLMappingCheck.COMPRESSIONS.contains(accession)) {
                            compression.add(accession);
                        } else if (MzMLMappingCheck.ARRAY_TYPES.contains(accession)) {
                            arrayType = accession;
                        }
                    }
                    case "referenceableParamGroupRef" -> assertThat(compression).as("group ref in an array").isNull();
                    case "binary" -> {
                        String text = r.getElementText();
                        boolean wanted = owner.equals("spectrum") ? spectra.contains(ownerIndex) : chromatograms;
                        if (wanted) {
                            out.add(new Array(fixture, owner, ownerIndex, ownerId,
                                    arrayLength == null ? defaultLength : arrayLength, arrayLength, arrayType,
                                    dataType, List.copyOf(compression), text));
                        }
                        compression = null;
                    }
                    default -> {
                    }
                }
            }
            r.close();
        }
        return out;
    }
}
