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
import org.apache.parquet.io.api.Binary;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * RD-12: a spectrum chunk the reader cannot decode fails with a precise {@link MzPeakException} instead of being
 * dropped or misdecoded. Covered: grid chunks ({@code MS:1003826}) of a grid type RD-20 does not decode, or without
 * the grid encoding, unknown {@code chunk_encoding} CURIEs, Numpress intensity without a Numpress m/z source, Numpress
 * m/z without an intensity source, and unknown {@code *numpress*} columns (list or scalar) that carry a value.
 *
 * <p>The cases write constructed chunk rows whose values come from row 0 of the current small.numpress data member
 * (spectrum 0, chunk_encoding {@code MS:1002312}, Numpress linear m/z and SLOF intensity) or from row 0 of the
 * diaPASEF.grid peaks member, and read them back through {@link SpectrumArrayStore#load}. Members are found by
 * manifest role.
 */
class UnsupportedEncodingTest {


    @TempDir
    Path tmp;

    private int written;

    // ---- grid chunks ------------------------------------------------------------------------------------------

    @Test
    void aGridChunkOfAnUnknownGridTypeFailsWithThePreciseGridError() {
        // RD-20 decodes the grid types of GridModels.KINDS, diaPASEF.grid's MS:9999002 among them (GridDecodeTest)
        ChunkRow row = gridRow0();
        assertThat(read(row).mz()).hasSameSizeAs(row.gridIndices);
        row.gridType = "MS:9999999";
        assertThatThrownBy(() -> read(row))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("spectrum_index 0")
                .hasMessageNotContaining("must equal")
                .hasMessage("grid_type MS:9999999 of mz_grid is not one of MS:1003824, MS:1003825, MS:9999002,"
                        + " MS:9999001 for spectrum_index 0");
    }

    @Test
    void aGridStructWithoutTheGridEncodingFailsWithTheGridError() {
        // diaPASEF.grid row 0 with its chunk_encoding removed: the mz_grid struct, empty chunk values and the
        // intensities alone identify a grid chunk.
        ChunkRow row = gridRow0();
        row.encoding = null;
        assertThatThrownBy(() -> read(row)).isInstanceOf(MzPeakException.class)
                .hasMessage("mz_grid carries a grid but chunk_encoding is (none) for spectrum_index 0");
    }

    // ---- Numpress intensity without a Numpress m/z source -----------------------------------------------------

    @Test
    void storedRow0DecodesAsNumpress() {
        Row0 row0 = Row0.INSTANCE;
        SpectrumArrayStore.Arrays arrays = read(row0.asStored());
        assertThat(arrays.mz()).isEqualTo(row0.mz).hasSizeGreaterThan(100);
        assertThat(arrays.intensity()).isEqualTo(row0.intensity);
    }

    @Test
    void slofIntensityWithoutNumpressMzFails() {
        ChunkRow row = Row0.INSTANCE.asStored();
        row.mzLinear = null;
        assertThatThrownBy(() -> read(row))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("SLOF")
                .hasMessageContaining("MS:1002312")
                .hasMessageContaining("spectrum_index 0");
    }

    @Test
    void picIntensityWithoutNumpressMzFails() {
        double[] intensity = Row0.INSTANCE.intensity;
        byte[] buffer = new byte[8 + intensity.length * 5];
        int length = MSNumpress.encodePic(intensity, intensity.length, buffer);
        ChunkRow row = Row0.INSTANCE.asStored();
        row.mzLinear = null;
        row.slof = null;
        row.pic = java.util.Arrays.copyOf(buffer, length);
        assertThatThrownBy(() -> read(row))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("PIC")
                .hasMessageNotContaining("SLOF")
                .hasMessageContaining("MS:1002312")
                .hasMessageContaining("spectrum_index 0");
    }

    @Test
    void anEmptySlofPayloadWithoutNumpressMzIsSkipped() {
        ChunkRow row = Row0.INSTANCE.asStored();
        row.mzLinear = null;
        row.slof = new byte[0];
        SpectrumArrayStore.Arrays arrays = read(row);
        assertThat(arrays.mz()).isEmpty();
        assertThat(arrays.intensity()).isEmpty();
    }

    // ---- Numpress m/z without an intensity source -------------------------------------------------------------

    @Test
    void numpressMzWithoutAnyIntensityColumnFails() {
        // On 1e400ed the decoded m/z was dropped and spectrum 0 read as empty.
        ChunkRow row = Row0.INSTANCE.asStored();
        row.slof = null;
        assertThatThrownBy(() -> read(row))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("unsupported chunk: Numpress linear m/z without an intensity column (SLOF, PIC or"
                        + " intensity) for spectrum_index 0");
    }

    @Test
    void anEmptyNumpressMzPayloadWithoutAnIntensityColumnIsSkipped() {
        ChunkRow row = Row0.INSTANCE.asStored();
        row.mzLinear = new byte[0];
        row.slof = null;
        SpectrumArrayStore.Arrays arrays = read(row);
        assertThat(arrays.mz()).isEmpty();
        assertThat(arrays.intensity()).isEmpty();
    }

    // ---- unknown Numpress columns -----------------------------------------------------------------------------

    @Test
    void anExtraNumpressColumnWithAValueFailsNamingTheColumn() {
        ChunkRow row = Row0.INSTANCE.asStored();
        row.extraSlof = Row0.INSTANCE.slof;
        assertThatThrownBy(() -> read(row))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("intensity_f32_dc_numpress_slof_bytes")
                .hasMessageContaining("spectrum_index 0");
    }

    @Test
    void anUnknownNumpressColumnInPlaceOfTheKnownOneFails() {
        // Without the check, the chunk has Numpress m/z but no known intensity column and is dropped silently.
        ChunkRow row = Row0.INSTANCE.asStored();
        row.slof = null;
        row.extraSlof = Row0.INSTANCE.slof;
        assertThatThrownBy(() -> read(row))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("intensity_f32_dc_numpress_slof_bytes")
                .hasMessageContaining("spectrum_index 0");
    }

    @Test
    void aScalarNumpressColumnWithAValueFailsNamingTheColumn() {
        // A primitive BINARY column has no list level: any non-null cell carries a value.
        ChunkRow row = Row0.INSTANCE.asStored();
        row.scalarSlof = Row0.INSTANCE.slof;
        assertThatThrownBy(() -> read(row))
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("unsupported Numpress column intensity_numpress_slof_binary for"
                        + " spectrum_index 0");
    }

    @Test
    void anUnknownNumpressColumnWithoutAValueIsIgnored() {
        // The schema always declares the column; storedRow0DecodesAsNumpress covers the null cell.
        ChunkRow row = Row0.INSTANCE.asStored();
        row.extraSlof = new byte[0];
        SpectrumArrayStore.Arrays arrays = read(row);
        assertThat(arrays.mz()).isEqualTo(Row0.INSTANCE.mz);
        assertThat(arrays.intensity()).isEqualTo(Row0.INSTANCE.intensity);
    }

    // ---- chunk_encoding dispatch ------------------------------------------------------------------------------

    @Test
    void anUnknownChunkEncodingFailsNamingTheCurie() {
        // Before RD-12 an unknown encoding was decoded as delta: these absolute values became running sums.
        ChunkRow row = Row0.INSTANCE.absolute("MS:9999999");
        assertThatThrownBy(() -> read(row))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("unsupported chunk encoding MS:9999999 for spectrum_index 0");
    }

    @Test
    void absoluteEncodingsKeepTheStoredValues() {
        Row0 row0 = Row0.INSTANCE;
        for (String encoding : new String[] {"MS:1000576", "MS:1003088"}) {
            SpectrumArrayStore.Arrays arrays = read(row0.absolute(encoding));
            assertThat(arrays.mz()).as(encoding).isEqualTo(row0.mz);
            assertThat(arrays.intensity()).as(encoding).isEqualTo(row0.intensity);
        }
    }

    @Test
    void nullAndDeltaEncodingsDecodeAsDelta() {
        Row0 row0 = Row0.INSTANCE;
        SpectrumArrayStore.Arrays delta = read(row0.delta("MS:1003089"));
        SpectrumArrayStore.Arrays unset = read(row0.delta(null));
        assertThat(unset.mz()).isEqualTo(delta.mz());
        assertThat(unset.intensity()).isEqualTo(delta.intensity()).isEqualTo(row0.intensity);
        assertThat(delta.mz()).hasSameSizeAs(row0.mz);
        for (int i = 0; i < row0.mz.length; i++) {
            assertThat(delta.mz()[i]).isCloseTo(row0.mz[i], within(1e-9));
        }
    }

    @Test
    void theNumpressEncodingWithoutNumpressColumnsDecodesAsDeltaAsBefore() {
        // MS:1002312 is a known encoding, but only the Numpress columns select the Numpress decoder (design §8.2).
        Row0 row0 = Row0.INSTANCE;
        SpectrumArrayStore.Arrays numpress = read(row0.delta("MS:1002312"));
        SpectrumArrayStore.Arrays delta = read(row0.delta("MS:1003089"));
        assertThat(numpress.mz()).isEqualTo(delta.mz());
        assertThat(numpress.intensity()).isEqualTo(delta.intensity());
    }

    @Test
    void anUnknownChunkEncodingOnTheNumpressRowFailsNamingTheCurie() {
        // Before RD-12 the Numpress columns alone chose the decoder and the unknown CURIE went unnoticed.
        ChunkRow row = Row0.INSTANCE.asStored();
        row.encoding = "MS:9999999";
        assertThatThrownBy(() -> read(row))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("unsupported chunk encoding MS:9999999 for spectrum_index 0");
    }

    @Test
    void aGridEncodingOnTheNumpressRowFailsWithTheGridError() {
        ChunkRow row = Row0.INSTANCE.asStored();
        row.encoding = "MS:1003826";
        assertThatThrownBy(() -> read(row))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("chunk_encoding MS:1003826 for spectrum_index 0, but the row has no grid in mz_grid");
    }

    @Test
    void numpressColumnsWinOverAKnownChunkEncoding() {
        // Among the known encodings, column presence selects the Numpress decoder (design §8.2).
        for (String encoding : new String[] {null, "MS:1003089", "MS:1000576", "MS:1003088"}) {
            ChunkRow row = Row0.INSTANCE.asStored();
            row.encoding = encoding;
            SpectrumArrayStore.Arrays arrays = read(row);
            assertThat(arrays.mz()).as(String.valueOf(encoding)).isEqualTo(Row0.INSTANCE.mz);
            assertThat(arrays.intensity()).as(String.valueOf(encoding)).isEqualTo(Row0.INSTANCE.intensity);
        }
    }

    // ---- fixtures and constructed rows ------------------------------------------------------------------------

    /** Row 0 of the current small.numpress spectrum data member, with its Numpress payloads decoded. */
    private static final class Row0 {
        static final Row0 INSTANCE = load();

        final long spectrumIndex;
        final double start;
        final double end;
        final String encoding;
        final byte[] mzLinear;
        final byte[] slof;
        final double[] mz;
        final double[] intensity;

        private Row0(Group chunk) {
            spectrumIndex = ParquetGroups.optLong(chunk, "spectrum_index");
            start = ParquetGroups.optDouble(chunk, "mz_chunk_start");
            end = ParquetGroups.optDouble(chunk, "mz_chunk_end");
            encoding = ParquetGroups.optString(chunk, "chunk_encoding");
            mzLinear = ParquetGroups.byteList(chunk, "mz_numpress_linear_bytes");
            slof = ParquetGroups.byteList(chunk, "intensity_numpress_slof_bytes");
            mz = MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, mzLinear, mzLinear.length);
            intensity = MSNumpress.decode(MSNumpress.ACC_NUMPRESS_SLOF, slof, slof.length);
            assertThat(spectrumIndex).isZero();
            assertThat(encoding).isEqualTo("MS:1002312");
            assertThat(mz).hasSameSizeAs(intensity);
        }

        private static Row0 load() {
            try (MzPeakSource source = MzPeakSource.open(Fixtures.current("small.numpress.mzpeak"))) {
                String data = MzPeakManifest.fromSource(source).find("spectrum", "data_arrays").orElseThrow().name();
                return new Row0(firstChunk(source, data));
            }
        }

        /** The row as stored: Numpress linear m/z and SLOF intensity. */
        ChunkRow asStored() {
            ChunkRow row = header(encoding);
            row.mzLinear = mzLinear;
            row.slof = slof;
            return row;
        }

        /** The decoded values as an absolute chunk: values and intensities pair one to one. */
        ChunkRow absolute(String chunkEncoding) {
            ChunkRow row = header(chunkEncoding);
            row.values = mz;
            row.intensity = intensity;
            return row;
        }

        /** The decoded values as a delta chunk from chunk_start: one intensity more than values. */
        ChunkRow delta(String chunkEncoding) {
            ChunkRow row = header(chunkEncoding);
            row.start = mz[0];
            row.values = new double[mz.length - 1];
            for (int i = 1; i < mz.length; i++) {
                row.values[i - 1] = mz[i] - mz[i - 1];
            }
            row.intensity = intensity;
            return row;
        }

        private ChunkRow header(String chunkEncoding) {
            ChunkRow row = new ChunkRow();
            row.spectrumIndex = spectrumIndex;
            row.start = start;
            row.end = end;
            row.encoding = chunkEncoding;
            return row;
        }
    }

    /** Row 0 of the current diaPASEF.grid peaks member: a grid chunk with grid_type MS:9999002. */
    private static ChunkRow gridRow0() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current("diaPASEF.grid.mzpeak"))) {
            String peaks = MzPeakManifest.fromSource(source).find("spectrum", "peaks").orElseThrow().name();
            Group chunk = firstChunk(source, peaks);
            Group grid = ParquetGroups.optGroup(chunk, "mz_grid");
            ChunkRow row = new ChunkRow();
            row.spectrumIndex = ParquetGroups.optLong(chunk, "spectrum_index");
            row.start = ParquetGroups.optDouble(chunk, "mz_chunk_start");
            row.end = ParquetGroups.optDouble(chunk, "mz_chunk_end");
            row.encoding = ParquetGroups.optString(chunk, "chunk_encoding");
            row.intensity = ParquetGroups.doubleListNullable(chunk, "intensity");
            row.gridType = ParquetGroups.optString(grid, "grid_type");
            row.gridParameters = ParquetGroups.doubleListNullable(grid, "parameters");
            row.gridIndices = ParquetGroups.doubleListNullable(grid, "indices");
            assertThat(row.spectrumIndex).isZero();
            assertThat(row.encoding).isEqualTo("MS:1003826");
            assertThat(row.gridType).isEqualTo("MS:9999002");
            assertThat(ParquetGroups.doubleListNullable(chunk, "mz_chunk_values")).isEmpty();
            assertThat(row.intensity).isNotEmpty().hasSameSizeAs(row.gridIndices);
            return row;
        }
    }

    private static Group firstChunk(MzPeakSource source, String member) {
        Group[] first = new Group[1];
        ParquetGroups.forEach(source.inputFile(member), record -> {
            if (first[0] == null) {
                first[0] = record;
            }
        });
        assertThat(first[0]).as("first row of %s", member).isNotNull();
        return first[0].getGroup("chunk", 0);
    }

    /** One constructed chunk row; a null field is written as a null cell. */
    private static final class ChunkRow {
        Long spectrumIndex;
        Double start;
        Double end;
        String encoding;
        double[] values;
        double[] intensity;
        byte[] mzLinear;
        byte[] slof;
        byte[] pic;
        byte[] extraSlof;
        byte[] scalarSlof;
        String gridType;
        double[] gridParameters;
        double[] gridIndices;
    }

    /**
     * The chunk columns of the current fixtures, plus PIC, a dtype-suffixed SLOF column, a scalar BINARY Numpress
     * column and an m/z grid.
     */
    private static final MessageType SCHEMA = Types.buildMessage()
            .addField(Types.optionalGroup()
                    .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false))
                    .named("spectrum_index")
                    .optional(PrimitiveTypeName.DOUBLE).named("mz_chunk_start")
                    .optional(PrimitiveTypeName.DOUBLE).named("mz_chunk_end")
                    .addField(doubleList("mz_chunk_values"))
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("chunk_encoding")
                    .addField(doubleList("intensity"))
                    .addField(byteList("mz_numpress_linear_bytes"))
                    .addField(byteList("intensity_numpress_slof_bytes"))
                    .addField(byteList("intensity_numpress_pic_bytes"))
                    .addField(byteList("intensity_f32_dc_numpress_slof_bytes"))
                    .optional(PrimitiveTypeName.BINARY).named("intensity_numpress_slof_binary")
                    .addField(Types.optionalGroup()
                            .required(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                            .named("grid_type")
                            .addField(doubleList("parameters"))
                            .addField(Types.optionalList().requiredElement(PrimitiveTypeName.INT32)
                                    .as(LogicalTypeAnnotation.intType(32, false)).named("indices"))
                            .named("mz_grid"))
                    .named("chunk"))
            .named("arrow_schema");

    private static Type doubleList(String name) {
        return Types.optionalList().optionalElement(PrimitiveTypeName.DOUBLE).named(name);
    }

    private static Type byteList(String name) {
        return Types.optionalList().requiredElement(PrimitiveTypeName.INT32)
                .as(LogicalTypeAnnotation.intType(8, false)).named(name);
    }

    /** Writes {@code row} as a one-row spectrum signal file and returns what the store decodes for its index. */
    private SpectrumArrayStore.Arrays read(ChunkRow row) {
        Path file = tmp.resolve("chunk-" + (written++) + ".parquet");
        PlainParquetConfiguration conf = new PlainParquetConfiguration();
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(conf)
                .withType(SCHEMA)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            writer.write(toGroup(row));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), false, Map.of())) {
            SpectrumArrayStore.Arrays arrays = store.get(row.spectrumIndex);
            assertThat(arrays).as("arrays of spectrum %s", row.spectrumIndex).isNotNull();
            return arrays;
        }
    }

    private static Group toGroup(ChunkRow row) {
        Group record = new SimpleGroupFactory(SCHEMA).newGroup();
        Group chunk = record.addGroup("chunk");
        if (row.spectrumIndex != null) {
            chunk.add("spectrum_index", row.spectrumIndex);
        }
        if (row.start != null) {
            chunk.add("mz_chunk_start", row.start);
        }
        if (row.end != null) {
            chunk.add("mz_chunk_end", row.end);
        }
        addDoubles(chunk, "mz_chunk_values", row.values);
        if (row.encoding != null) {
            chunk.add("chunk_encoding", row.encoding);
        }
        addDoubles(chunk, "intensity", row.intensity);
        addBytes(chunk, "mz_numpress_linear_bytes", row.mzLinear);
        addBytes(chunk, "intensity_numpress_slof_bytes", row.slof);
        addBytes(chunk, "intensity_numpress_pic_bytes", row.pic);
        addBytes(chunk, "intensity_f32_dc_numpress_slof_bytes", row.extraSlof);
        if (row.scalarSlof != null) {
            chunk.add("intensity_numpress_slof_binary", Binary.fromConstantByteArray(row.scalarSlof));
        }
        if (row.gridType != null) {
            Group grid = chunk.addGroup("mz_grid");
            grid.add("grid_type", row.gridType);
            addDoubles(grid, "parameters", row.gridParameters);
            if (row.gridIndices != null) {
                Group list = grid.addGroup("indices");
                for (double index : row.gridIndices) {
                    list.addGroup("list").add("element", (int) (long) index);
                }
            }
        }
        return record;
    }

    private static void addDoubles(Group parent, String field, double[] values) {
        if (values != null) {
            Group list = parent.addGroup(field);
            for (double v : values) {
                list.addGroup("list").add("element", v);
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
}
