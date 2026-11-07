package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterizes the chunk codecs of the spectrum signal reader through {@link SpectrumArrayStore#load}: the decoding
 * of {@code mz_chunk_values} (null-aware delta from {@code chunk_start}, or absolute) and of Numpress payloads (an
 * empty payload decodes to no values, a corrupt one fails naming what was decoded). The rejections RD-12 added are
 * pinned by {@link UnsupportedEncodingTest}.
 *
 * <p>Each case writes one constructed chunk row of spectrum 0 and reads it back. The delta rows are prefixes of rows
 * 0 and 1 of the current small.chunked data member (spectrum 0, chunk_encoding {@code MS:1003089}): row 0 carries one
 * intensity more than values and a null pair followed by an absolute re-anchor, row 1 pairs values and intensities
 * one to one and starts with a null. The Numpress rows use the payloads of row 0 of the current small.numpress data
 * member. Members are found by manifest role. The expected values are the exact doubles the reader produces
 * (Double.toString round-trips them) and are compared bit for bit with {@link Double#compare}.
 */
class ChunkCodecsCharacterizationTest {

    private static final String DELTA = "MS:1003089";

    /** small.chunked row 0: chunk_start, values 0..11 and intensities 0..12 (one more intensity than values). */
    private static final double START_POINT_START = 202.60657495520474;
    private static final double[] START_POINT_VALUES = {
            2.485275090009509E-4, 2.485281187318833E-4, 2.485287285196591E-4, 2.485293381653264E-4,
            2.485299478962588E-4, 2.4853055768403465E-4, Double.NaN, Double.NaN,
            204.75958873936264, 2.538375690619432E-4, 2.5383819846069855E-4, 2.5383882780261047E-4};
    private static final double[] START_POINT_INTENSITY = {
            0.0, 1938.117431640625, 2572.8388671875, 3392.107421875, 3729.5908203125, 2819.127197265625,
            993.3759765625, Double.NaN, Double.NaN, 1422.173583984375, 3215.4931640625, 3887.35595703125,
            2843.16552734375};

    /** small.chunked row 1: chunk_start, values 0..9 and intensities 0..9 (one to one). */
    private static final double ONE_TO_ONE_START = 252.96545361882283;
    private static final double[] ONE_TO_ONE_VALUES = {
            Double.NaN, 252.96545361882283, 3.8742706865946275E-4, 3.8742825540794E-4, 3.8742944209957386E-4,
            3.8743062887647284E-4, 3.87431815539685E-4, Double.NaN, Double.NaN, 252.983663973986};
    private static final double[] ONE_TO_ONE_INTENSITY = {
            Double.NaN, 1328.245849609375, 3272.78173828125, 4725.3662109375, 4891.2607421875, 3677.263671875,
            1680.248779296875, Double.NaN, Double.NaN, 2385.547119140625};

    /** The start-point row decoded with reconstructProfile=false: the null pair is dropped. */
    private static final double[] START_POINT_MZ = {
            202.60657495520474, 202.60682348271374, 202.60707201083247, 202.607320539561, 202.60756906889915,
            202.60781759884705, 202.60806612940473, 204.75958873936264, 204.7598425769317, 204.76009641513016,
            204.76035025395797};
    private static final double[] START_POINT_MZ_INTENSITY = {
            0.0, 1938.117431640625, 2572.8388671875, 3392.107421875, 3729.5908203125, 2819.127197265625,
            993.3759765625, 1422.173583984375, 3215.4931640625, 3887.35595703125, 2843.16552734375};

    /** The start-point row decoded with reconstructProfile=true and no delta model: the null pair is interpolated. */
    private static final double[] START_POINT_FILLED_MZ = {
            202.60657495520474, 202.60682348271374, 202.60707201083247, 202.607320539561, 202.60756906889915,
            202.60781759884705, 202.60806612940473, 203.32524033272404, 204.04241453604334, 204.75958873936264,
            204.7598425769317, 204.76009641513016, 204.76035025395797};
    private static final double[] START_POINT_FILLED_INTENSITY = {
            0.0, 1938.117431640625, 2572.8388671875, 3392.107421875, 3729.5908203125, 2819.127197265625,
            993.3759765625, 0.0, 0.0, 1422.173583984375, 3215.4931640625, 3887.35595703125, 2843.16552734375};

    @TempDir
    Path tmp;

    private int written;

    @Test
    void fixtureInputIsPinned() {
        List<Group> rows = chunks("small.chunked.mzpeak", 2);
        assertStoredRow("small.chunked row 0", rows.get(0),
                START_POINT_START, START_POINT_VALUES, START_POINT_INTENSITY, 775, 776);
        assertStoredRow("small.chunked row 1", rows.get(1),
                ONE_TO_ONE_START, ONE_TO_ONE_VALUES, ONE_TO_ONE_INTENSITY, 756, 756);

        Numpress numpress = Numpress.row0();
        assertThat(numpress.mzLinear).hasSize(838);
        assertThat(numpress.slof).hasSize(1560);
        assertThat(MSNumpress.decode(MSNumpress.ACC_NUMPRESS_SLOF, numpress.slof, numpress.slof.length))
                .hasSize(776);
    }

    // ---- mz_chunk_values ----------------------------------------------------------------------------------------

    @Test
    void deltaValuesStepFromChunkStartAndReanchorAbsolutelyAfterANull() {
        // chunk_start is emitted as a point with intensity[0]; the value after the null pair is absolute.
        SpectrumArrayStore.Arrays arrays = read(deltaRow(START_POINT_START, START_POINT_VALUES, START_POINT_INTENSITY),
                false);
        assertExact("m/z", START_POINT_MZ, arrays.mz());
        assertExact("intensity", START_POINT_MZ_INTENSITY, arrays.intensity());
    }

    @Test
    void deltaValuesWithReconstructionFillTheNullPair() {
        SpectrumArrayStore.Arrays arrays = read(deltaRow(START_POINT_START, START_POINT_VALUES, START_POINT_INTENSITY),
                true);
        assertExact("m/z", START_POINT_FILLED_MZ, arrays.mz());
        assertExact("intensity", START_POINT_FILLED_INTENSITY, arrays.intensity());
    }

    @Test
    void deltaValuesPairedOneToOneReanchorAfterALeadingNull() {
        // No start point: the leading null breaks the chain at once, so the next value (equal to chunk_start) is
        // absolute.
        ChunkRow row = deltaRow(ONE_TO_ONE_START, ONE_TO_ONE_VALUES, ONE_TO_ONE_INTENSITY);
        SpectrumArrayStore.Arrays dropped = read(row, false);
        assertExact("m/z", new double[]{
                252.96545361882283, 252.9658410458915, 252.9662284741469, 252.966615903589, 252.96700333421788,
                252.96739076603342, 252.983663973986}, dropped.mz());
        assertExact("intensity", new double[]{
                1328.245849609375, 3272.78173828125, 4725.3662109375, 4891.2607421875, 3677.263671875,
                1680.248779296875, 2385.547119140625}, dropped.intensity());

        SpectrumArrayStore.Arrays filled = read(row, true);
        assertExact("filled m/z", new double[]{
                252.96545361882283, 252.96545361882283, 252.9658410458915, 252.9662284741469, 252.966615903589,
                252.96700333421788, 252.96739076603342, 252.9728151686843, 252.97823957133514, 252.983663973986},
                filled.mz());
        assertExact("filled intensity", new double[]{
                0.0, 1328.245849609375, 3272.78173828125, 4725.3662109375, 4891.2607421875, 3677.263671875,
                1680.248779296875, 0.0, 0.0, 2385.547119140625}, filled.intensity());
    }

    @Test
    void absoluteValuesAreKeptAndTheirNullsStayGaps() {
        // The decoded start-point row written back as absolute values, the null pair still in place: both absolute
        // encodings reproduce the delta decoding exactly.
        double[] values = START_POINT_FILLED_MZ.clone();
        values[7] = Double.NaN;
        values[8] = Double.NaN;
        for (String encoding : new String[] {"MS:1000576", "MS:1003088"}) {
            ChunkRow row = new ChunkRow();
            row.start = START_POINT_START;
            row.encoding = encoding;
            row.values = values;
            row.intensity = START_POINT_INTENSITY;
            SpectrumArrayStore.Arrays dropped = read(row, false);
            assertExact(encoding + " m/z", START_POINT_MZ, dropped.mz());
            assertExact(encoding + " intensity", START_POINT_MZ_INTENSITY, dropped.intensity());
            SpectrumArrayStore.Arrays filled = read(row, true);
            assertExact(encoding + " filled m/z", START_POINT_FILLED_MZ, filled.mz());
            assertExact(encoding + " filled intensity", START_POINT_FILLED_INTENSITY, filled.intensity());
        }
    }

    // ---- Numpress payloads --------------------------------------------------------------------------------------

    @Test
    void emptyNumpressPayloadsDecodeToAnEmptySpectrum() {
        ChunkRow row = Numpress.row0().asStored();
        row.mzLinear = new byte[0];
        row.slof = new byte[0];
        SpectrumArrayStore.Arrays arrays = read(row, false);
        assertThat(arrays.mz()).isEmpty();
        assertThat(arrays.intensity()).isEmpty();
    }

    @Test
    void anEmptyNumpressMzPayloadIsNotHandedToTheDecoder() {
        // The empty payload decodes to no values instead of failing the 8-byte header check of the Numpress decoder.
        ChunkRow row = Numpress.row0().asStored();
        row.mzLinear = new byte[0];
        assertThatThrownBy(() -> read(row, false))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("Numpress chunk m/z (0) and intensity (776) lengths differ for spectrum_index 0");
    }

    @Test
    void aTruncatedNumpressMzPayloadFailsNamingMz() {
        ChunkRow row = Numpress.row0().asStored();
        row.mzLinear = Arrays.copyOf(row.mzLinear, 4);
        assertThatThrownBy(() -> read(row, false))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("Failed to Numpress-decode m/z for spectrum_index 0")
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot decode numLin data, need at least 8 initial bytes for fixed point.");
    }

    @Test
    void aTruncatedSlofPayloadFailsNamingIntensity() {
        ChunkRow row = Numpress.row0().asStored();
        row.slof = Arrays.copyOf(row.slof, 4);
        assertThatThrownBy(() -> read(row, false))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("Failed to Numpress-decode intensity for spectrum_index 0")
                .cause()
                .isInstanceOf(NegativeArraySizeException.class);
    }

    @Test
    void aTruncatedSlofPayloadWithoutNumpressMzFailsInTheDecoderFirst() {
        ChunkRow row = Numpress.row0().asStored();
        row.mzLinear = null;
        row.slof = Arrays.copyOf(row.slof, 4);
        assertThatThrownBy(() -> read(row, false))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("Failed to Numpress-decode intensity for spectrum_index 0");
    }

    // ---- fixtures and constructed rows --------------------------------------------------------------------------

    private static ChunkRow deltaRow(double start, double[] values, double[] intensity) {
        ChunkRow row = new ChunkRow();
        row.start = start;
        row.encoding = DELTA;
        row.values = values;
        row.intensity = intensity;
        return row;
    }

    private static void assertStoredRow(String what, Group chunk, double start, double[] values, double[] intensity,
                                        int valueCount, int intensityCount) {
        assertThat(ParquetGroups.optLong(chunk, "spectrum_index")).as("%s spectrum_index", what).isZero();
        assertThat(ParquetGroups.optString(chunk, "chunk_encoding")).as("%s chunk_encoding", what).isEqualTo(DELTA);
        assertExact(what + " chunk_start", new double[]{start},
                new double[]{ParquetGroups.optDouble(chunk, "mz_chunk_start")});
        double[] storedValues = ParquetGroups.doubleListNullable(chunk, "mz_chunk_values");
        double[] storedIntensity = ParquetGroups.doubleListNullable(chunk, "intensity");
        assertThat(storedValues).as("%s values", what).hasSize(valueCount);
        assertThat(storedIntensity).as("%s intensities", what).hasSize(intensityCount);
        assertExact(what + " values", values, Arrays.copyOf(storedValues, values.length));
        assertExact(what + " intensities", intensity, Arrays.copyOf(storedIntensity, intensity.length));
    }

    /** The Numpress payloads of row 0 of the current small.numpress data member (spectrum 0). */
    private static final class Numpress {
        final byte[] mzLinear;
        final byte[] slof;

        private Numpress(Group chunk) {
            mzLinear = ParquetGroups.byteList(chunk, "mz_numpress_linear_bytes");
            slof = ParquetGroups.byteList(chunk, "intensity_numpress_slof_bytes");
            assertThat(ParquetGroups.optLong(chunk, "spectrum_index")).isZero();
            assertThat(ParquetGroups.optString(chunk, "chunk_encoding")).isEqualTo("MS:1002312");
        }

        static Numpress row0() {
            return new Numpress(chunks("small.numpress.mzpeak", 1).get(0));
        }

        ChunkRow asStored() {
            ChunkRow row = new ChunkRow();
            row.encoding = "MS:1002312";
            row.mzLinear = mzLinear;
            row.slof = slof;
            return row;
        }
    }

    /** The first {@code count} chunk rows of the spectrum data member of the current dataset {@code name}. */
    private static List<Group> chunks(String name, int count) {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current(name))) {
            String data = MzPeakManifest.fromSource(source).find("spectrum", "data_arrays").orElseThrow().name();
            List<Group> rows = new ArrayList<>();
            ParquetGroups.forEach(source.inputFile(data), record -> {
                if (rows.size() < count) {
                    rows.add(record.getGroup("chunk", 0));
                }
            });
            assertThat(rows).as("first rows of %s", name).hasSize(count);
            return rows;
        }
    }

    /** One constructed chunk row of spectrum 0; a null field is written as a null cell. */
    private static final class ChunkRow {
        Double start;
        String encoding;
        double[] values;
        double[] intensity;
        byte[] mzLinear;
        byte[] slof;
    }

    /** The chunk columns of the current small.chunked and small.numpress data members. */
    private static final MessageType SCHEMA = Types.buildMessage()
            .addField(Types.optionalGroup()
                    .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false))
                    .named("spectrum_index")
                    .optional(PrimitiveTypeName.DOUBLE).named("mz_chunk_start")
                    .addField(doubleList("mz_chunk_values"))
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("chunk_encoding")
                    .addField(doubleList("intensity"))
                    .addField(byteList("mz_numpress_linear_bytes"))
                    .addField(byteList("intensity_numpress_slof_bytes"))
                    .named("chunk"))
            .named("arrow_schema");

    private static Type doubleList(String name) {
        return Types.optionalList().optionalElement(PrimitiveTypeName.DOUBLE).named(name);
    }

    private static Type byteList(String name) {
        return Types.optionalList().requiredElement(PrimitiveTypeName.INT32)
                .as(LogicalTypeAnnotation.intType(8, false)).named(name);
    }

    /** Writes {@code row} as a one-row spectrum signal file and returns what the store decodes for spectrum 0. */
    private SpectrumArrayStore.Arrays read(ChunkRow row, boolean reconstructProfile) {
        Path file = tmp.resolve("chunk-" + (written++) + ".parquet");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(SCHEMA)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            writer.write(toGroup(row));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (SpectrumArrayStore store =
                     SpectrumArrayStore.load(new LocalInputFile(file), reconstructProfile, Map.of())) {
            SpectrumArrayStore.Arrays arrays = store.get(0);
            assertThat(arrays).as("arrays of spectrum 0").isNotNull();
            return arrays;
        }
    }

    private static Group toGroup(ChunkRow row) {
        Group record = new SimpleGroupFactory(SCHEMA).newGroup();
        Group chunk = record.addGroup("chunk");
        chunk.add("spectrum_index", 0L);
        if (row.start != null) {
            chunk.add("mz_chunk_start", row.start);
        }
        addDoubles(chunk, "mz_chunk_values", row.values);
        if (row.encoding != null) {
            chunk.add("chunk_encoding", row.encoding);
        }
        addDoubles(chunk, "intensity", row.intensity);
        addBytes(chunk, "mz_numpress_linear_bytes", row.mzLinear);
        addBytes(chunk, "intensity_numpress_slof_bytes", row.slof);
        return record;
    }

    /** A LIST of nullable doubles; NaN is written as a null element, as the fixtures store their gaps. */
    private static void addDoubles(Group parent, String field, double[] values) {
        if (values != null) {
            Group list = parent.addGroup(field);
            for (double v : values) {
                Group element = list.addGroup("list");
                if (!Double.isNaN(v)) {
                    element.add("element", v);
                }
            }
        }
    }

    private static void addBytes(Group parent, String field, byte[] bytes) {
        if (bytes != null) {
            Group list = parent.addGroup(field);
            for (byte b : bytes) {
                list.addGroup("list").add("element", b & 0xFF);
            }
        }
    }

    private static void assertExact(String what, double[] expected, double[] actual) {
        assertThat(actual).as("%s length", what).hasSize(expected.length);
        for (int k = 0; k < expected.length; k++) {
            assertThat(Double.compare(actual[k], expected[k]))
                    .as("%s at %d: expected %s but was %s", what, k, expected[k], actual[k])
                    .isZero();
        }
    }
}
