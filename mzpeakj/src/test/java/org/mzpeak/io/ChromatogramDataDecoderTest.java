package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChromatogramDataDecoder#decode} on chunk members that no fixture has and {@link ChromatogramArraysTest} does
 * not construct: a time axis stored in a chunk transform, plans without the chunk start or without the chunk values
 * of the time axis, and a chunk extra list that is not numeric. Each member is constructed with an array index
 * footer; the plan is built from it as {@link ChromatogramStore} builds it.
 */
class ChromatogramDataDecoderTest {

    private static final String TIME = "MS:1000595";
    private static final String INTENSITY = "MS:1000515";
    private static final String NON_STANDARD = "MS:1000786";
    private static final String MEMBER = "chromatograms_data.parquet";

    private static final MessageType CHUNK = MessageTypeParser.parseMessageType("""
            message m {
              optional group chunk {
                optional int64 chromatogram_index (INTEGER(64,false));
                optional double time_chunk_start;
                optional group time_chunk_values (LIST) { repeated group list { optional double item; } }
                optional group time_numpress_linear_bytes (LIST) {
                  repeated group list { required int32 item (INTEGER(8,false)); }
                }
                optional group intensity (LIST) { repeated group list { optional float item; } }
                optional group ms_level (LIST) { repeated group list { optional int64 item; } }
                optional group label (LIST) { repeated group list { optional binary item (STRING); } }
              }
            }""");

    private static final String INDEX_ENTRY = entry("chunk.chromatogram_index", "index", "MS:1003870", 0, null, null);
    private static final String START = entry("chunk.time_chunk_start", "chunk_start", TIME, 1, null, null);
    private static final String VALUES = entry("chunk.time_chunk_values", "chunk_values", TIME, 1, null, null);
    private static final String LIST = entry("chunk.intensity", "chunk_secondary", INTENSITY, null, null, null);

    @TempDir
    Path tmp;

    private int written;

    /**
     * A chunk whose time axis transform column (Numpress linear time) carries a value fails, naming the index, the
     * transform and the column; chunks whose transform column is null decode before it.
     */
    @Test
    void aTimeAxisChunkTransformThatCarriesAValueFails() {
        String index = index(INDEX_ENTRY, START, VALUES, LIST,
                entry("chunk.time_numpress_linear_bytes", "chunk_transform", TIME, null,
                        MSNumpress.ACC_NUMPRESS_LINEAR, null));
        InputFile data = write(index,
                row(0, 0.0, List.of(1.0), null, List.of(1f, 2f), null, null),
                row(1, 0.0, null, numpressLinear(0.0, 1.0), List.of(3f, 4f), null, null));
        SignalPlan plan = footerPlan(data);
        assertThat(plan.axis().transformFields())
                .containsExactly(Map.entry(MSNumpress.ACC_NUMPRESS_LINEAR, "time_numpress_linear_bytes"));

        assertThatThrownBy(() -> ChromatogramDataDecoder.decode(data, MEMBER, plan))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("chromatogram_index 1 stores its time axis with the chunk transform MS:1002312 (column"
                        + " time_numpress_linear_bytes), which is not decoded for chromatograms");
    }

    /**
     * A plan without a chunk start column skips every chunk, as a chunk without a start is skipped; the same rows
     * decode with the plan inferred from the column names, which has the start.
     */
    @Test
    void aPlanWithoutTheChunkStartSkipsEveryChunk() {
        InputFile data = write(index(INDEX_ENTRY, VALUES, LIST),
                row(0, 10.0, List.of(1.0, 1.0), null, List.of(1f, 2f, 3f), null, null),
                row(1, 5.0, List.of(0.5), null, List.of(4f, 5f), null, null));
        SignalPlan plan = footerPlan(data);
        assertThat(plan.layout()).isEqualTo(SignalPlan.Layout.CHUNK);
        assertThat(plan.axis().start()).isNull();

        assertThat(ChromatogramDataDecoder.decode(data, MEMBER, plan)).isEmpty();

        Map<Long, ChromatogramDataDecoder.Decoded> inferred =
                ChromatogramDataDecoder.decode(data, MEMBER, inferredPlan(data));
        assertThat(inferred.keySet()).containsExactly(0L, 1L);
        assertThat(inferred.get(0L).time()).containsExactly(10.0, 11.0, 12.0);
        assertThat(inferred.get(0L).intensity()).containsExactly(1.0, 2.0, 3.0);
        assertThat(inferred.get(1L).time()).containsExactly(5.0, 5.5);
    }

    /**
     * A plan without a chunk values column of the time axis reads each chunk as its start alone, paired with the
     * first intensity; the surplus intensities are dropped, as for every chunk.
     */
    @Test
    void aPlanWithoutTheChunkValuesReadsOnePointPerChunk() {
        InputFile data = write(index(INDEX_ENTRY, START, LIST),
                row(0, 10.0, List.of(1.0, 1.0), null, List.of(1f, 2f, 3f), null, null),
                row(0, 20.0, null, null, List.of(4f), null, null),
                row(1, 5.0, null, null, List.of(7f, 8f), null, null));
        SignalPlan plan = footerPlan(data);
        assertThat(plan.axis().values()).isNull();

        Map<Long, ChromatogramDataDecoder.Decoded> decoded = ChromatogramDataDecoder.decode(data, MEMBER, plan);

        assertThat(decoded.keySet()).containsExactly(0L, 1L);
        assertThat(decoded.get(0L).time()).containsExactly(10.0, 20.0);
        assertThat(decoded.get(0L).intensity()).containsExactly(1.0, 4.0);
        assertThat(decoded.get(1L).time()).containsExactly(5.0);
        assertThat(decoded.get(1L).intensity()).containsExactly(7.0);
    }

    /**
     * A chunk extra list whose items are not numbers is dropped from the member with one WARNING, and not read again
     * in later chunks; the numeric extra, the time and the intensity are read.
     */
    @Test
    void aChunkExtraThatIsNotNumericIsDroppedFromTheMember() {
        String index = index(INDEX_ENTRY, START, VALUES, LIST,
                entry("chunk.ms_level", "chunk_secondary", NON_STANDARD, null, null, "ms level"),
                entry("chunk.label", "chunk_secondary", NON_STANDARD, null, null, "label"));
        InputFile data = write(index,
                row(0, 0.0, List.of(1.0), null, List.of(1f, 2f), List.of(1L, 2L), List.of("a", "b")),
                row(1, 3.0, List.of(1.0), null, List.of(3f, 4f), List.of(2L, 2L), List.of("c", "d")));
        SignalPlan plan = footerPlan(data);
        assertThat(plan.extras().keySet()).containsExactly("ms level", "label");

        Map<Long, ChromatogramDataDecoder.Decoded> decoded = new LinkedHashMap<>();
        List<LogRecord> records = ArrayIndexTest.captureLog(ChromatogramDataDecoder.class,
                () -> decoded.putAll(ChromatogramDataDecoder.decode(data, MEMBER, plan)));

        assertThat(decoded.keySet()).containsExactly(0L, 1L);
        assertThat(decoded.get(0L).time()).containsExactly(0.0, 1.0);
        assertThat(decoded.get(0L).intensity()).containsExactly(1.0, 2.0);
        assertThat(decoded.get(0L).arrays().keySet()).containsExactly("ms level");
        assertThat(decoded.get(0L).arrays().get("ms level")).containsExactly(1.0, 2.0);
        assertThat(decoded.get(1L).time()).containsExactly(3.0, 4.0);
        assertThat(decoded.get(1L).intensity()).containsExactly(3.0, 4.0);
        assertThat(decoded.get(1L).arrays().keySet()).containsExactly("ms level");
        assertThat(decoded.get(1L).arrays().get("ms level")).containsExactly(2.0, 2.0);
        assertThat(records).filteredOn(r -> r.getLevel() == Level.WARNING).extracting(LogRecord::getMessage)
                .containsExactly("chromatogram extra array 'label' (chunk.label) cannot be read as numbers (List"
                        + " element item is not numeric: BINARY); the array is dropped");
    }

    // ---- constructed members ------------------------------------------------------------------------------------

    /** The plan {@link ChromatogramStore} builds for {@code data}: from its array index footer. */
    private static SignalPlan footerPlan(InputFile data) {
        ParquetFooter footer = ParquetFooter.read(data);
        SignalPlan plan = SignalPlan.of(ArrayIndex.of(footer.keyValue(), footer.schema(), SignalEntity.CHROMATOGRAM),
                footer.schema(), SignalEntity.CHROMATOGRAM);
        assertThat(plan.source()).isEqualTo(ArrayIndex.Source.FOOTER);
        return plan;
    }

    /** The plan of {@code data} inferred from its column names, as for a member without an array index. */
    private static SignalPlan inferredPlan(InputFile data) {
        ParquetFooter footer = ParquetFooter.read(data);
        return SignalPlan.of(ArrayIndex.of(Map.of(), footer.schema(), SignalEntity.CHROMATOGRAM), footer.schema(),
                SignalEntity.CHROMATOGRAM);
    }

    /** A chunk row of {@link #CHUNK}; a null argument leaves its column null. */
    private static Group row(long index, Double start, List<Double> deltas, byte[] linearTime, List<Float> intensity,
                             List<Long> msLevel, List<String> label) {
        Group row = new SimpleGroupFactory(CHUNK).newGroup();
        Group chunk = row.addGroup("chunk");
        chunk.append("chromatogram_index", index);
        if (start != null) {
            chunk.append("time_chunk_start", start);
        }
        if (deltas != null) {
            Group list = chunk.addGroup("time_chunk_values");
            deltas.forEach(v -> list.addGroup("list").append("item", v));
        }
        if (linearTime != null) {
            Group list = chunk.addGroup("time_numpress_linear_bytes");
            for (byte b : linearTime) {
                list.addGroup("list").append("item", b & 0xff);
            }
        }
        if (intensity != null) {
            Group list = chunk.addGroup("intensity");
            intensity.forEach(v -> list.addGroup("list").append("item", v));
        }
        if (msLevel != null) {
            Group list = chunk.addGroup("ms_level");
            msLevel.forEach(v -> list.addGroup("list").append("item", v));
        }
        if (label != null) {
            Group list = chunk.addGroup("label");
            label.forEach(v -> list.addGroup("list").append("item", v));
        }
        return row;
    }

    /** MS-Numpress linear bytes of {@code values}. */
    private static byte[] numpressLinear(double... values) {
        byte[] buffer = new byte[8 + values.length * 5];
        int n = MSNumpress.encodeLinear(values, values.length, buffer,
                MSNumpress.optimalLinearFixedPoint(values, values.length));
        return Arrays.copyOf(buffer, n);
    }

    private static String entry(String path, String format, String arrayType, Integer rank, String transform,
                                String arrayName) {
        return "{\"path\": \"" + path + "\", \"buffer_format\": \"" + format + "\", \"array_type\": \"" + arrayType
                + "\", \"sorting_rank\": " + rank + ", \"transform\": "
                + (transform == null ? "null" : "\"" + transform + "\"") + ", \"array_name\": "
                + (arrayName == null ? "null" : "\"" + arrayName + "\"") + "}";
    }

    private static String index(String... entries) {
        return "{\"prefix\": \"chunk\", \"entries\": [" + String.join(", ", entries) + "]}";
    }

    /** Writes {@code rows} with {@code json} as the chromatogram array index footer. */
    private InputFile write(String json, Group... rows) {
        Path file = tmp.resolve("chromatograms_data-" + (written++) + ".parquet");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(CHUNK)
                .withExtraMetaData(Map.of(SignalEntity.CHROMATOGRAM.arrayIndexKey(), json))
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (Group row : rows) {
                writer.write(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new LocalInputFile(file);
    }
}
