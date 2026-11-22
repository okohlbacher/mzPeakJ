package org.mzpeak.io;

import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.PureJavaCodecFactory;
import org.mzpeak.model.Chromatogram;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Chunked chromatograms decoded by {@link ChunkRowDecoder} (C4, X-10), on small.chunked, whose
 * {@code chromatograms_data} is one delta-encoded chunk row (chunked-layout.md §Basic encoding, §Delta encoding). Each
 * case writes a {@code chromatograms_data.parquet} with small.chunked's schema and footer (its array index) into a copy
 * of small.unpacked, whose chromatogram metadata names the same TIC, and reads it through {@link MzPeakReader}:
 * <ul>
 *   <li>(a) the row rewritten to basic encoding (MS:1000576: absolute times, the chunk start excluded) reads as the
 *       delta row;
 *   <li>(b) a crafted delta row with null-marked times is null-aware decoded and filled per chunk row with
 *       reconstructProfile, and the null points dropped without;
 *   <li>(c) an intensity list that is neither as long as the times nor one longer throws;
 *   <li>(d) small.numpress (delta time, SLOF intensity) reads its times as small.chunked; that it reads exactly as on
 *       0.12.0 is pinned by {@link ChromatogramFixtureCharacterizationTest}.
 * </ul>
 */
class ChunkedChromatogramTest {

    private static final String DATA = "chromatograms_data.parquet";
    private static final String DELTA = "MS:1003089";
    private static final String BASIC = "MS:1000576";

    @TempDir
    Path tmp;

    /** (a) The TIC rewritten to basic encoding reads 48 points from 0.004935 to 0.487237 min, as the delta row. */
    @Test
    void aBasicEncodedChunkReadsAsTheDeltaChunk() {
        Chromatogram delta = tic(Fixtures.current("small.chunked.mzpeak"), true);
        List<Group> rows = new ArrayList<>();
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current("small.chunked.mzpeak"))) {
            InputFile data = source.inputFile(DATA);
            ParquetGroups.forEach(data, rows::add);
            assertThat(rows).hasSize(1);
            Group chunk = rows.get(0).getGroup("chunk", 0);
            assertThat(chunk.getString("chunk_encoding", 0)).isEqualTo(DELTA);
            double start = chunk.getDouble("time_chunk_start", 0);
            double[] absolute = ParquetGroups.doubleListNullable(chunk, "time_chunk_values");
            double last = start;
            for (int i = 0; i < absolute.length; i++) {
                last += absolute[i];
                absolute[i] = last;
            }
            Path dataset = dataset(data, row(data, 0, start, chunk.getDouble("time_chunk_end", 0), boxed(absolute),
                    BASIC, boxed(ParquetGroups.doubleListNullable(chunk, "intensity")),
                    boxed(ParquetGroups.doubleListNullable(chunk, "ms_level"))));

            Chromatogram basic = tic(dataset, true);
            assertThat(basic.time()).hasSize(48);
            assertThat(basic.time()[0]).isEqualTo(0.004935);
            assertThat(basic.time()[47]).isCloseTo(0.487237, within(1e-6));
            assertThat(basic.time()).isEqualTo(delta.time());
            assertThat(basic.intensity()).isEqualTo(delta.intensity());
            assertThat(basic.arrays().get("ms level")).isEqualTo(delta.arrays().get("ms level"));
        }
    }

    /**
     * (b) Start 1.0 and the delta values [0.1, null, null, 5.0, 0.1] decode null-aware to [1.0, 1.1, null, null,
     * 5.0, 5.1] (the value after a null is absolute); the null pair is filled from the spacing of the segments on
     * either side with reconstructProfile, and dropped with its zero intensities without.
     */
    @Test
    void nullMarkedTimesAreFilledPerChunkRow() {
        Path dataset = crafted(Arrays.asList(0.1, null, null, 5.0, 0.1), List.of(10.0, 11.0, 0.0, 0.0, 12.0, 13.0));

        Chromatogram filled = tic(dataset, true);
        double[] expected = {1.0, 1.1, 1.2, 4.9, 5.0, 5.1};
        assertThat(filled.time()).hasSize(6);
        for (int i = 0; i < expected.length; i++) {
            assertThat(filled.time()[i]).as("time[%d]", i).isCloseTo(expected[i], within(1e-12));
        }
        assertThat(filled.intensity()).containsExactly(10.0, 11.0, 0.0, 0.0, 12.0, 13.0);

        Chromatogram stored = tic(dataset, false);
        assertThat(stored.time()).hasSize(4);
        double[] kept = {1.0, 1.1, 5.0, 5.1};
        for (int i = 0; i < kept.length; i++) {
            assertThat(stored.time()[i]).as("time[%d]", i).isCloseTo(kept[i], within(1e-12));
        }
        assertThat(stored.intensity()).containsExactly(10.0, 11.0, 12.0, 13.0);
    }

    /** (c) Five intensities for three time values throw, naming the chromatogram. */
    @Test
    void anIntensityListOfAnotherLengthThrows() {
        Path dataset = crafted(List.of(0.1, 0.1, 0.1), List.of(1.0, 2.0, 3.0, 4.0, 5.0));

        try (MzPeakReader r = MzPeakReader.open(dataset)) {
            assertThatThrownBy(r::chromatograms)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageContaining("chromatogram_index 0")
                    .hasMessageContaining("(5)")
                    .hasMessageContaining("(3)");
        }
    }

    /**
     * (d) small.numpress's TIC (delta time, SLOF intensity) has small.chunked's times and ms levels, and its
     * intensities within the SLOF precision.
     */
    @Test
    void smallNumpressReadsItsSlofTic() {
        Chromatogram chunked = tic(Fixtures.current("small.chunked.mzpeak"), true);
        Chromatogram numpress = tic(Fixtures.current("small.numpress.mzpeak"), true);
        assertThat(numpress.time()).isEqualTo(chunked.time());
        assertThat(numpress.arrays().get("ms level")).isEqualTo(chunked.arrays().get("ms level"));
        assertThat(numpress.intensity()).hasSize(48);
        for (int i = 0; i < 48; i++) {
            assertThat(numpress.intensity()[i]).as("intensity[%d]", i)
                    .isCloseTo(chunked.intensity()[i], within(1e-3 * chunked.intensity()[i] + 1));
        }
        assertThat(numpress.intensityUnit()).isEqualTo(chunked.intensityUnit());
    }

    // ---- crafted members ------------------------------------------------------------------------------------------

    private static Chromatogram tic(Path dataset, boolean reconstructProfile) {
        try (MzPeakReader r = MzPeakReader.open(dataset,
                MzPeakReader.Options.defaults().withReconstructProfile(reconstructProfile))) {
            assertThat(r.chromatograms()).hasSize(1);
            return r.chromatograms().get(0);
        }
    }

    /** small.unpacked with one chunk row of chromatogram 0: start 1.0, delta {@code values}, {@code intensity}. */
    private Path crafted(List<Double> values, List<Double> intensity) {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current("small.chunked.mzpeak"))) {
            InputFile data = source.inputFile(DATA);
            return dataset(data, row(data, 0, 1.0, null, values, DELTA, intensity, null));
        }
    }

    private static List<Double> boxed(double[] values) {
        return Arrays.stream(values).boxed().toList();
    }

    /** A chunk row with the schema of {@code template}; a null argument leaves its column null. */
    private static Group row(InputFile template, long index, double start, Double end, List<Double> values,
                             String encoding, List<Double> intensity, List<Double> msLevel) {
        MessageType schema = ParquetFooter.read(template).schema();
        Group row = new SimpleGroup(schema);
        Group chunk = row.addGroup("chunk");
        chunk.add("chromatogram_index", index);
        chunk.add("time_chunk_start", start);
        if (end != null) {
            chunk.add("time_chunk_end", end);
        }
        list(chunk, "time_chunk_values", values);
        chunk.add("chunk_encoding", encoding);
        list(chunk, "intensity", intensity);
        list(chunk, "ms_level", msLevel);
        return row;
    }

    /** Appends the 3-level LIST {@code field} with {@code values} (null elements left null); null leaves it null. */
    private static void list(Group parent, String field, List<Double> values) {
        if (values == null) {
            return;
        }
        Group list = parent.addGroup(field);
        GroupType repeated = list.getType().getType(0).asGroupType();
        String element = repeated.getType(0).getName();
        PrimitiveTypeName type = repeated.getType(0).asPrimitiveType().getPrimitiveTypeName();
        for (Double v : values) {
            Group item = list.addGroup(repeated.getName());
            if (v == null) {
                continue;
            }
            switch (type) {
                case DOUBLE -> item.add(element, v);
                case FLOAT -> item.add(element, v.floatValue());
                case INT64 -> item.add(element, v.longValue());
                default -> throw new AssertionError("unexpected " + type);
            }
        }
    }

    /**
     * A copy of small.unpacked whose {@code chromatograms_data.parquet} is {@code rows}, written with the schema and
     * footer key-value metadata of {@code template}.
     */
    private Path dataset(InputFile template, Group... rows) {
        Path copy = tmp.resolve("dataset-" + System.nanoTime() + ".mzpeak");
        try {
            Files.createDirectories(copy);
            Path unpacked = Fixtures.current("small.unpacked.mzpeak");
            try (Stream<Path> files = Files.list(unpacked)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    Files.copy(f, copy.resolve(f.getFileName().toString()));
                }
            }
            Files.delete(copy.resolve(DATA));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ParquetFooter footer = ParquetFooter.read(template);
        Map<String, String> keyValue = new LinkedHashMap<>(footer.keyValue());
        keyValue.remove("writer.model.name");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(copy.resolve(DATA)))
                .withConf(new PlainParquetConfiguration())
                .withType(footer.schema())
                .withExtraMetaData(keyValue)
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
        return copy;
    }
}
