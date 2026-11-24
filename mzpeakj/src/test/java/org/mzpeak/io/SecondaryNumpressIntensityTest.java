package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.parquet.PureJavaCodecFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * X-9 (R3): a SLOF or PIC intensity column pairs with the decoded m/z of any chunk encoding, not only with Numpress
 * linear m/z (chunked-layout.md, Opaque array transforms for other dimensions). Its length is that of the decoded m/z
 * values, or one more with the chunk start as the first point, as for a plain intensity list; any other length throws
 * naming the spectrum and the chunk. Up to 0.12.0 every such row threw "unsupported chunk: Numpress SLOF intensity
 * ... without Numpress linear m/z".
 *
 * <p>The member is small.chunked's {@code spectra_data.parquet} schema and array index with two columns added,
 * {@code intensity_numpress_slof_bytes} and {@code intensity_numpress_pic_bytes} (chunk_transform MS:1002314 and
 * MS:1002313, copies of the intensity entry otherwise), holding crafted rows; it is written into a {@link TempDir}.
 */
class SecondaryNumpressIntensityTest {

    private static final String MEMBER = "spectra_data.parquet";
    private static final String INDEX_KEY = SignalEntity.SPECTRUM.arrayIndexKey();
    private static final String SLOF = "intensity_numpress_slof_bytes";
    private static final String PIC = "intensity_numpress_pic_bytes";
    private static final String BASIC = "MS:1000576";
    private static final String DELTA = "MS:1003089";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    private int written;

    @Test
    void aSlofIntensityPairsWithBasicMzAndTheChunkStart() {
        SpectrumArrayStore.Arrays s = read(row(0, BASIC, 100.0, new double[] {101}, SLOF, slof(10, 20)), 0);

        assertThat(s.mz()).containsExactly(100.0, 101.0);
        assertThat(s.intensity()).hasSize(2);
        assertThat(s.intensity()[0]).isCloseTo(10.0, within(10.0 * 1e-3));
        assertThat(s.intensity()[1]).isCloseTo(20.0, within(20.0 * 1e-3));
    }

    @Test
    void aPicIntensityPairsWithBasicMzExactly() {
        SpectrumArrayStore.Arrays s = read(row(0, BASIC, 100.0, new double[] {101}, PIC, pic(10, 20)), 0);

        assertThat(s.mz()).containsExactly(100.0, 101.0);
        assertThat(s.intensity()).containsExactly(10.0, 20.0);
    }

    /** A delta row pairs the same way, here one-to-one without the chunk start as a separate point. */
    @Test
    void aPicIntensityPairsWithDeltaMz() {
        SpectrumArrayStore.Arrays s = read(row(0, DELTA, 100.0, new double[] {0.5, 0.25}, PIC, pic(7, 8)), 0);

        assertThat(s.mz()).containsExactly(100.5, 100.75);
        assertThat(s.intensity()).containsExactly(7.0, 8.0);
    }

    @Test
    void aSlofIntensityOfAnotherLengthThrowsNamingTheSpectrumAndTheChunk() {
        Path file = write(row(0, BASIC, 100.0, new double[] {101}, SLOF, slof(10, 20, 30)));

        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), MEMBER, false, Map.of())) {
            assertThatThrownBy(() -> store.get(0))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("chunk intensity length (3) must equal m/z values (1) or values+1, for spectrum_index"
                            + " 0 (SLOF intensity column " + SLOF + ", chunk start 100.0)");
        }
    }

    // ---- the crafted member ---------------------------------------------------------------------------------------

    /** Spectrum {@code index} of a member holding {@code row}. */
    private SpectrumArrayStore.Arrays read(Group row, long index) {
        Path file = write(row);
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), MEMBER, false, Map.of())) {
            return store.get(index);
        }
    }

    /** A row of spectrum {@code index} with m/z {@code start} and {@code values}, and {@code payload} in {@code field}. */
    private static Group row(long index, String encoding, double start, double[] values, String field,
                             byte[] payload) {
        Group record = new SimpleGroupFactory(schema()).newGroup();
        Group chunk = record.addGroup("chunk");
        chunk.add("spectrum_index", index);
        chunk.add("mz_chunk_start", start);
        chunk.add("mz_chunk_end", values[values.length - 1]);
        Group list = chunk.addGroup("mz_chunk_values");
        String element = elementName(list);
        for (double v : values) {
            list.addGroup("list").add(element, v);
        }
        chunk.add("chunk_encoding", encoding);
        Group bytes = chunk.addGroup(field);
        for (byte b : payload) {
            bytes.addGroup("list").add("element", b & 0xff);
        }
        return record;
    }

    /** The name of the element field of the LIST {@code list}. */
    private static String elementName(Group list) {
        return list.getType().getType("list").asGroupType().getType(0).getName();
    }

    private static ParquetFooter footer() {
        return ArrayIndexTest.footer(Fixtures.current("small.chunked.mzpeak"), EntityType.SPECTRUM,
                DataKind.DATA_ARRAYS);
    }

    /** small.chunked's spectrum data schema with the SLOF and PIC byte list columns added to the chunk struct. */
    private static MessageType schema() {
        MessageType base = footer().schema();
        GroupType chunk = base.getType("chunk").asGroupType();
        List<Type> fields = new ArrayList<>(chunk.getFields());
        fields.add(byteList(SLOF));
        fields.add(byteList(PIC));
        return new MessageType(base.getName(), chunk.withNewFields(fields));
    }

    private static Type byteList(String name) {
        return Types.optionalList()
                .requiredElement(PrimitiveTypeName.INT32).as(LogicalTypeAnnotation.intType(8, false))
                .named(name);
    }

    /** small.chunked's spectrum array index with entries for the SLOF and PIC columns, copied from the intensity. */
    private static String index() {
        try {
            ObjectNode index = (ObjectNode) JSON.readTree(footer().keyValue().get(INDEX_KEY));
            ArrayNode entries = (ArrayNode) index.get("entries");
            ObjectNode intensity = null;
            for (JsonNode entry : entries) {
                if (entry.get("path").asText().equals("chunk.intensity")) {
                    intensity = (ObjectNode) entry;
                }
            }
            assertThat(intensity).isNotNull();
            entries.add(transformEntry(intensity, SLOF, MSNumpress.ACC_NUMPRESS_SLOF));
            entries.add(transformEntry(intensity, PIC, MSNumpress.ACC_NUMPRESS_PIC));
            return JSON.writeValueAsString(index);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ObjectNode transformEntry(ObjectNode intensity, String field, String transform) {
        ObjectNode entry = intensity.deepCopy();
        entry.put("path", "chunk." + field);
        entry.put("buffer_format", "chunk_transform");
        entry.put("transform", transform);
        entry.putNull("buffer_priority");
        return entry;
    }

    private Path write(Group... rows) {
        Path file = tmp.resolve("member-" + (written++) + ".parquet");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(schema())
                .withExtraMetaData(Map.of(INDEX_KEY, index()))
                .withCodecFactory(new PureJavaCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (Group row : rows) {
                writer.write(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    private static byte[] slof(double... values) {
        byte[] buffer = new byte[8 + values.length * 2];
        int length = MSNumpress.encodeSlof(values, values.length, buffer,
                MSNumpress.optimalSlofFixedPoint(values, values.length));
        return Arrays.copyOf(buffer, length);
    }

    private static byte[] pic(double... values) {
        byte[] buffer = new byte[values.length * 5];
        int length = MSNumpress.encodePic(values, values.length, buffer);
        return Arrays.copyOf(buffer, length);
    }
}
