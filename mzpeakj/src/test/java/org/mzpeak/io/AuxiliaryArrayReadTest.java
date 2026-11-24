package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.model.Activation;
import org.mzpeak.model.AuxiliaryArray;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Auxiliary arrays (R1, X-31; docs/layouts/auxiliary-arrays.md) read from the {@code auxiliary_arrays} column of a
 * metadata row. Each case is a vendored fixture whose metadata member is rewritten with parquet-java
 * ({@link MemberRewrite}): one row of small.unpacked's {@code spectra_metadata.parquet} or
 * {@code chromatograms_metadata.parquet}, or of has_uv's {@code wavelength_spectra_metadata.parquet}, given an
 * auxiliary array in the column the fixture already declares (empty in every row). The array is MS:1000517 (signal to
 * noise array) of the 64-bit floats 1, 2 and 3, stored as is (MS:1000576), zlib-compressed (MS:1000574) or under a
 * compression the model cannot decode.
 */
class AuxiliaryArrayReadTest {

    private static final String SPECTRA = "spectra_metadata.parquet";
    private static final String CHROMATOGRAMS = "chromatograms_metadata.parquet";
    private static final String WAVELENGTHS = "wavelength_spectra_metadata.parquet";
    private static final Param SIGNAL_TO_NOISE = new Param("signal to noise array", "MS:1000517", null, null);
    private static final String FLOAT64 = "MS:1000523";
    private static final String NONE = "MS:1000576";
    private static final String ZLIB = "MS:1000574";
    /** MS:1002312, MS-Numpress linear prediction compression: a compression {@link AuxiliaryArray} cannot decode. */
    private static final String NUMPRESS_LINEAR = "MS:1002312";

    @TempDir
    Path tmp;

    // ---- spectra ------------------------------------------------------------------------------------------------

    /** small.unpacked, spectrum 0 given one uncompressed float64 array of 3 items without unit. */
    @Test
    void anUncompressedFloat64ArrayOfASpectrumReadsItsValues() {
        byte[] stored = float64(1.0, 2.0, 3.0);
        Spectrum s0;
        Spectrum s1;
        try (MzPeakReader reader = MzPeakReader.open(edited("small.unpacked.mzpeak", SPECTRA, 0,
                item -> fill(item, stored, NONE)))) {
            s0 = reader.getSpectrum(0).orElseThrow();
            s1 = reader.getSpectrum(1).orElseThrow();
        }

        assertThat(s0.auxiliaryArrays()).hasSize(1);
        AuxiliaryArray a = s0.auxiliaryArrays().get(0);
        assertThat(a.name()).isEqualTo(SIGNAL_TO_NOISE);
        assertThat(a.dataType()).isEqualTo(FLOAT64);
        assertThat(a.compression()).isEqualTo(NONE);
        assertThat(a.items()).isEqualTo(3);
        assertThat(a.unit()).isNull();
        assertThat(a.parameters()).isEmpty();
        assertThat(a.dataProcessingId()).isNull();
        assertThat(a.data()).isEqualTo(stored);
        assertThat(a.doubles()).containsExactly(1.0, 2.0, 3.0);
        // the signal arrays are the fixture's, and a spectrum without auxiliary arrays has none
        assertThat(s0.mz()).isNotEmpty();
        assertThat(s1.auxiliaryArrays()).isEmpty();
    }

    /** A zlib-compressed copy keeps the stored zlib bytes in data() and decodes them in doubles(). */
    @Test
    void aZlibArrayKeepsItsStoredBytesAndDecodes() {
        byte[] stored = zlib(float64(1.0, 2.0, 3.0));
        AuxiliaryArray a = firstSpectrumArray(item -> fill(item, stored, ZLIB));

        assertThat(a.compression()).isEqualTo(ZLIB);
        assertThat(a.data()).isEqualTo(stored);
        assertThat(a.doubles()).containsExactly(1.0, 2.0, 3.0);
    }

    /** A compression the model cannot decode keeps its bytes; doubles() throws naming it. */
    @Test
    void anUnknownCompressionKeepsItsBytesAndDoublesThrowsNamingIt() {
        byte[] stored = {7, 8, 9, 10};
        AuxiliaryArray a = firstSpectrumArray(item -> fill(item, stored, NUMPRESS_LINEAR));

        assertThat(a.compression()).isEqualTo(NUMPRESS_LINEAR);
        assertThat(a.data()).isEqualTo(stored);
        assertThatThrownBy(a::doubles).isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining(NUMPRESS_LINEAR).hasMessageContaining("signal to noise array");
    }

    /** The optional fields: unit, parameters and data processing id; and a second array after the first. */
    @Test
    void theOptionalFieldsAndASecondArrayAreRead() {
        List<AuxiliaryArray> arrays;
        try (MzPeakReader reader = MzPeakReader.open(edited("small.unpacked.mzpeak", SPECTRA, 0, item -> {
            fill(item, float64(1.0, 2.0, 3.0), NONE);
            item.append("unit", "UO:0000186");
            ParquetOutput.writeParams(item, List.of(new Param("comment", null, "note", null)));
            item.append("data_processing_id", "dp-aux");
        }, item -> fill(item, float64(4.0), NONE, 1)))) {
            arrays = reader.getSpectrum(0).orElseThrow().auxiliaryArrays();
        }

        assertThat(arrays).hasSize(2);
        assertThat(arrays.get(0).unit()).isEqualTo("UO:0000186");
        assertThat(arrays.get(0).parameters()).containsExactly(new Param("comment", null, "note", null));
        assertThat(arrays.get(0).dataProcessingId()).isEqualTo("dp-aux");
        assertThat(arrays.get(1).doubles()).containsExactly(4.0);
    }

    // ---- chromatograms and wavelength spectra -------------------------------------------------------------------

    /**
     * small.unpacked, chromatogram 0 given the array; it survives withTimeUnit and the writer's copy with renumbered
     * precursors.
     */
    @Test
    void aChromatogramReadsItsAuxiliaryArray() {
        byte[] stored = float64(1.0, 2.0, 3.0);
        List<Chromatogram> chromatograms;
        try (MzPeakReader reader = MzPeakReader.open(edited("small.unpacked.mzpeak", CHROMATOGRAMS, 0,
                item -> fill(item, stored, NONE)))) {
            chromatograms = reader.chromatograms();
        }

        Chromatogram c0 = chromatograms.get(0);
        assertThat(c0.index()).isZero();
        assertThat(c0.auxiliaryArrays()).hasSize(1);
        assertThat(c0.auxiliaryArrays().get(0).name()).isEqualTo(SIGNAL_TO_NOISE);
        assertThat(c0.auxiliaryArrays().get(0).data()).isEqualTo(stored);
        assertThat(c0.auxiliaryArrays().get(0).doubles()).containsExactly(1.0, 2.0, 3.0);
        assertThat(c0.time()).isNotEmpty();
        assertThat(c0.withTimeUnit("UO:0000010").auxiliaryArrays()).isEqualTo(c0.auxiliaryArrays());
        Chromatogram withPrecursor = new Chromatogram(c0.index(), c0.id(), c0.typeCurie(), c0.time(),
                c0.intensity(), c0.intensityUnit(), c0.arrays(), c0.polarity(), c0.dataProcessingRef(),
                c0.parameters(), List.of(new Precursor(7L, null, null, List.of(), Activation.EMPTY, 2)),
                c0.timeUnit(), c0.products(), c0.auxiliaryArrays());
        Chromatogram written = WritePlanner.withWrittenPrecursors(withPrecursor, 0, Map.of(7L, 1L),
                MetadataLayout.SPLIT);
        assertThat(written).isNotSameAs(withPrecursor);
        assertThat(written.auxiliaryArrays()).isEqualTo(c0.auxiliaryArrays());
        assertThat(chromatograms.subList(1, chromatograms.size()))
                .allSatisfy(c -> assertThat(c.auxiliaryArrays()).isEmpty());
    }

    /** has_uv, wavelength spectrum 0 given the zlib array. */
    @Test
    void aWavelengthSpectrumReadsItsAuxiliaryArray() {
        byte[] stored = zlib(float64(1.0, 2.0, 3.0));
        List<WavelengthSpectrum> spectra;
        try (MzPeakReader reader = MzPeakReader.open(edited("has_uv.mzpeak", WAVELENGTHS, 0,
                item -> fill(item, stored, ZLIB)))) {
            spectra = reader.wavelengthSpectra();
        }

        WavelengthSpectrum w0 = spectra.get(0);
        assertThat(w0.index()).isZero();
        assertThat(w0.wavelength()).hasSize(96);
        assertThat(w0.auxiliaryArrays()).hasSize(1);
        assertThat(w0.auxiliaryArrays().get(0).data()).isEqualTo(stored);
        assertThat(w0.auxiliaryArrays().get(0).doubles()).containsExactly(1.0, 2.0, 3.0);
        assertThat(spectra.get(1).auxiliaryArrays()).isEmpty();
        // the canonical wavelength line shows them only where there are any
        assertThat(Canonical.describe(w0)).endsWith("|aux=[(" + Canonical.param(SIGNAL_TO_NOISE) + "|" + FLOAT64
                + "|" + ZLIB + "|3|" + Canonical.ABSENT + "|" + HexFormat.of().formatHex(stored) + "|[]|"
                + Canonical.ABSENT + ")]");
        assertThat(Canonical.describe(spectra.get(1))).doesNotContain("|aux=");
    }

    /** The unchanged fixtures read no auxiliary arrays. */
    @Test
    void theFixturesHaveNoAuxiliaryArrays() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("has_uv.mzpeak"))) {
            assertThat(reader.getSpectrum(0).orElseThrow().auxiliaryArrays()).isEmpty();
            assertThat(reader.chromatograms()).allSatisfy(c -> assertThat(c.auxiliaryArrays()).isEmpty());
            assertThat(reader.wavelengthSpectra()).allSatisfy(w -> assertThat(w.auxiliaryArrays()).isEmpty());
        }
    }

    // ---- required fields ----------------------------------------------------------------------------------------

    /**
     * Each field the spec requires, missing, is rejected with a message naming the member, entity and field; but
     * {@code items}, which is derived from the data.
     */
    @Test
    void aMissingRequiredFieldIsRejectedNamingIt() {
        byte[] stored = float64(1.0);
        assertRejected(item -> item.append("data_type", FLOAT64).append("compression", NONE).append("items", 1L)
                .addGroup("data"), "has no name,");
        assertRejected(item -> {
            item.addGroup("name").append("accession", "MS:1000517");
            item.append("data_type", FLOAT64).append("compression", NONE).append("items", 1L).addGroup("data");
        }, "has no name.name,");
        assertRejected(item -> {
            name(item);
            item.append("data_type", FLOAT64).append("compression", NONE).append("items", 1L);
        }, "has no data,");
        assertRejected(item -> {
            name(item);
            data(item, stored);
            item.append("compression", NONE).append("items", 1L);
        }, "has no data_type,");
        assertRejected(item -> {
            name(item);
            data(item, stored);
            item.append("data_type", FLOAT64).append("items", 1L);
        }, "has no compression,");
        // items is derived when absent (U1, AuxiliaryArrayDecoderTest)
        assertThat(firstSpectrumArray(item -> {
            name(item);
            data(item, stored);
            item.append("data_type", FLOAT64).append("compression", NONE);
        }).items()).isEqualTo(1);
    }

    // ---- the decoder on crafted rows ----------------------------------------------------------------------------

    /** The value of the name param: the first non-null arm of its value union, or none. */
    @Test
    void theNameParamTakesTheFirstValueArm() {
        assertThat(nameValue(v -> v.append("integer", 5L).append("float", 1.5))).isEqualTo(5L);
        assertThat(nameValue(v -> v.append("float", 1.5).append("string", "s"))).isEqualTo(1.5);
        assertThat(nameValue(v -> v.append("string", "frobnication factor array").append("boolean", true)))
                .isEqualTo("frobnication factor array");
        assertThat(nameValue(v -> v.append("boolean", true))).isEqualTo(Boolean.TRUE);
        assertThat(nameValue(v -> { })).isNull();
    }

    /** A row without the column, or with a null list, has no auxiliary arrays. */
    @Test
    void aRowWithoutTheColumnOrWithANullListHasNone() {
        MessageType bare = Types.buildMessage().optional(PrimitiveTypeName.INT64).named("index").named("m");
        assertThat(AuxiliaryArrayDecoder.decode(new SimpleGroupFactory(bare).newGroup(), "member", "spectrum 0"))
                .isEmpty();
        assertThat(AuxiliaryArrayDecoder.decode(new SimpleGroupFactory(ROW).newGroup(), "member", "spectrum 0"))
                .isEmpty();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** A metadata row of just {@code auxiliary_arrays}, as mzPeakJ declares it. */
    private static final MessageType ROW = Types.buildMessage().addField(ParquetOutput.auxiliaryArrayListField())
            .named("m");

    /** The value of the name param of an array whose name's value group {@code value} fills. */
    private static Object nameValue(Consumer<Group> value) {
        Group row = new SimpleGroupFactory(ROW).newGroup();
        Group item = row.addGroup("auxiliary_arrays").addGroup("list").addGroup("item");
        Group name = item.addGroup("name");
        value.accept(name.addGroup("value"));
        name.append("accession", "MS:1000786").append("name", "non-standard data array");
        data(item, float64(1.0));
        item.append("data_type", FLOAT64).append("compression", NONE).append("items", 1L);
        return AuxiliaryArrayDecoder.decode(row, "member", "spectrum 0").get(0).name().value();
    }

    private void assertRejected(Consumer<Group> item, String field) {
        MzPeakSource source = edited("small.unpacked.mzpeak", SPECTRA, 0, item);
        assertThatThrownBy(() -> MzPeakReader.open(source).close()).isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith(SPECTRA + ": auxiliary array 0 of spectrum 0 " + field);
    }

    /** The only auxiliary array of spectrum 0 of small.unpacked given the array {@code item} fills. */
    private AuxiliaryArray firstSpectrumArray(Consumer<Group> item) {
        try (MzPeakReader reader = MzPeakReader.open(edited("small.unpacked.mzpeak", SPECTRA, 0, item))) {
            List<AuxiliaryArray> arrays = reader.getSpectrum(0).orElseThrow().auxiliaryArrays();
            assertThat(arrays).hasSize(1);
            return arrays.get(0);
        }
    }

    /**
     * The fixture {@code fixture} with its member {@code member} rewritten: the row whose {@code index} is
     * {@code index} gets one auxiliary array per {@code items}, each filled by its consumer.
     */
    @SafeVarargs
    private MzPeakSource edited(String fixture, String member, long index, Consumer<Group>... items) {
        try {
            Path file = tmp.resolve(fixture + "." + member + "." + System.nanoTime());
            Files.write(file, TestSources.memory(Fixtures.current(fixture)).member(member));
            MemberRewrite.Member m = MemberRewrite.read(file);
            boolean found = false;
            for (Group row : m.rows) {
                if (row.getLong("index", 0) != index) {
                    continue;
                }
                found = true;
                Group list = row.getFieldRepetitionCount("auxiliary_arrays") == 0
                        ? row.addGroup("auxiliary_arrays") : row.getGroup("auxiliary_arrays", 0);
                assertThat(list.getFieldRepetitionCount("list")).as("arrays of the fixture row").isZero();
                for (Consumer<Group> item : items) {
                    item.accept(list.addGroup("list").addGroup("item"));
                }
            }
            assertThat(found).as("%s has a row with index %d", member, index).isTrue();
            MemberRewrite.write(m, file);
            return MzPeakReaderEdgeCasesTest.edited(Fixtures.current(fixture), manifest -> { },
                    Map.of(member, Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Fills {@code item} as MS:1000517 of 3 float64 values, stored as {@code stored} under {@code compression}. */
    private static void fill(Group item, byte[] stored, String compression) {
        fill(item, stored, compression, 3);
    }

    private static void fill(Group item, byte[] stored, String compression, long items) {
        name(item);
        data(item, stored);
        item.append("data_type", FLOAT64).append("compression", compression).append("items", items);
    }

    private static void name(Group item) {
        item.addGroup("name").append("accession", SIGNAL_TO_NOISE.accession()).append("name", SIGNAL_TO_NOISE.name());
    }

    private static void data(Group item, byte[] bytes) {
        Group data = item.addGroup("data");
        for (byte b : bytes) {
            data.addGroup("list").append("item", b & 0xff);
        }
    }

    private static byte[] float64(double... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Double.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) {
            buffer.putDouble(v);
        }
        return buffer.array();
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
