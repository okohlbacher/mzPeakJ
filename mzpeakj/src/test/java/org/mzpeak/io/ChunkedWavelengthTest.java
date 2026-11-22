package org.mzpeak.io;

import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.parquet.PureJavaCodecFactory;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Chunked wavelength spectra (C5, X-11; wavelength-spectra.md: point or chunked layout), decoded by
 * {@link ChunkRowDecoder}. Each case replaces has_uv's {@code wavelength_spectra_data.parquet}, 520 spectra in point
 * layout, by a member under the {@code chunk} prefix written with parquet-java into a {@link TempDir}: one chunk row
 * per spectrum, built from the arrays the point member reads as, with a {@code wavelength_spectrum_array_index} of
 * {@code chunk_*} formats ({@code wavelength_chunk_start}, {@code _end}, {@code _values}, {@code chunk_encoding} and an
 * {@code intensity} list):
 * <ul>
 *   <li>delta encoding (MS:1003089): the chunk start is the first point, the values the differences to the next ones;
 *   <li>basic encoding (MS:1000576): the values are the absolute wavelengths, the chunk start not a separate point;
 *   <li>a chunked member without rows: every spectrum of the metadata reads with empty arrays.
 * </ul>
 */
class ChunkedWavelengthTest {

    private static final String HAS_UV = "has_uv.mzpeak";
    private static final String DATA = "wavelength_spectra_data.parquet";
    private static final String DELTA = "MS:1003089";
    private static final String BASIC = "MS:1000576";

    private static final MessageType SCHEMA = MessageTypeParser.parseMessageType("""
            message m {
              optional group chunk {
                optional int64 wavelength_spectrum_index (INTEGER(64,false));
                optional double wavelength_chunk_start;
                optional double wavelength_chunk_end;
                optional group wavelength_chunk_values (LIST) { repeated group list { optional double item; } }
                optional binary chunk_encoding (STRING);
                optional group intensity (LIST) { repeated group list { optional float item; } }
              }
            }""");

    private static final String ARRAY_INDEX = "{\"prefix\": \"chunk\", \"entries\": ["
            + entry("chunk.wavelength_spectrum_index", "index", "MS:1003870", "0", null) + ", "
            + entry("chunk.wavelength_chunk_start", "chunk_start", "MS:1000617", "1", "UO:0000018") + ", "
            + entry("chunk.wavelength_chunk_end", "chunk_end", "MS:1000617", "1", "UO:0000018") + ", "
            + entry("chunk.wavelength_chunk_values", "chunk_values", "MS:1000617", "1", "UO:0000018") + ", "
            + entry("chunk.chunk_encoding", "chunk_encoding", "MS:1000617", "1", null) + ", "
            + entry("chunk.intensity", "chunk_secondary", "MS:1000515", "null", "MS:1000131") + "]}";

    @TempDir
    Path tmp;

    @Test
    void deltaEncodedChunksReadAsThePointMember() throws IOException {
        assertReadsAsPoints(DELTA);
    }

    @Test
    void basicEncodedChunksReadAsThePointMember() throws IOException {
        assertReadsAsPoints(BASIC);
    }

    /** A chunked member without rows gives every spectrum of the metadata, with empty arrays. */
    @Test
    void anEmptyChunkedMemberGivesEmptyArrays() throws IOException {
        List<WavelengthSpectrum> read = read(List.of());

        assertThat(read).hasSize(520);
        for (WavelengthSpectrum w : read) {
            assertThat(w.wavelength()).as("%s wavelength", w.id()).isEmpty();
            assertThat(w.intensity()).as("%s intensity", w.id()).isEmpty();
        }
    }

    /** has_uv rewritten to one chunk row per spectrum in {@code encoding} reads every array as the point member. */
    private void assertReadsAsPoints(String encoding) throws IOException {
        List<WavelengthSpectrum> points = pointSpectra();
        assertThat(points).hasSize(520);
        List<Group> rows = new ArrayList<>();
        for (WavelengthSpectrum w : points) {
            rows.add(chunkRow(w, encoding));
        }

        List<WavelengthSpectrum> chunked = read(rows);

        assertThat(chunked).hasSize(520);
        WavelengthSpectrum first = chunked.get(0);
        assertThat(first.wavelength()).hasSize(96);
        assertThat(first.intensity()).hasSize(96);
        assertThat(first.wavelength()[0]).isEqualTo(210.0);
        assertThat(first.intensity()[0]).isCloseTo(-0.109196, within(1e-6));
        for (int s = 0; s < points.size(); s++) {
            WavelengthSpectrum p = points.get(s);
            WavelengthSpectrum c = chunked.get(s);
            assertThat(c.index()).isEqualTo(p.index());
            assertThat(c.wavelength()).as("%s wavelength", p.id()).hasSameSizeAs(p.wavelength());
            assertThat(c.intensity()).as("%s intensity", p.id()).hasSameSizeAs(p.intensity());
            for (int i = 0; i < p.wavelength().length; i++) {
                assertThat(c.wavelength()[i]).as("%s wavelength[%d]", p.id(), i)
                        .isCloseTo(p.wavelength()[i], within(1e-9));
                assertThat(c.intensity()[i]).as("%s intensity[%d]", p.id(), i)
                        .isCloseTo(p.intensity()[i], within(1e-9));
            }
        }
    }

    /** The wavelength spectra of has_uv as stored, in point layout. */
    private static List<WavelengthSpectrum> pointSpectra() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(HAS_UV))) {
            return reader.wavelengthSpectra();
        }
    }

    /**
     * The chunk row of {@code w}: delta encoding stores the first wavelength as the chunk start and the differences
     * to the following ones as values (intensities one longer); basic encoding stores every wavelength as a value.
     */
    private static Group chunkRow(WavelengthSpectrum w, String encoding) {
        double[] wavelength = w.wavelength();
        Group row = new SimpleGroup(SCHEMA);
        Group chunk = row.addGroup("chunk");
        chunk.add("wavelength_spectrum_index", w.index());
        chunk.add("wavelength_chunk_start", wavelength[0]);
        chunk.add("wavelength_chunk_end", wavelength[wavelength.length - 1]);
        Group values = chunk.addGroup("wavelength_chunk_values");
        if (DELTA.equals(encoding)) {
            for (int i = 1; i < wavelength.length; i++) {
                values.addGroup("list").add("item", wavelength[i] - wavelength[i - 1]);
            }
        } else {
            for (double v : wavelength) {
                values.addGroup("list").add("item", v);
            }
        }
        chunk.add("chunk_encoding", encoding);
        Group intensity = chunk.addGroup("intensity");
        for (double v : w.intensity()) {
            intensity.addGroup("list").add("item", (float) v);
        }
        return row;
    }

    /** has_uv with {@code rows} as its chunked wavelength spectrum data member, read through {@link MzPeakReader}. */
    private List<WavelengthSpectrum> read(List<Group> rows) throws IOException {
        Path file = tmp.resolve(DATA);
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(SCHEMA)
                .withExtraMetaData(Map.of(SignalEntity.WAVELENGTH_SPECTRUM.arrayIndexKey(), ARRAY_INDEX))
                .withCodecFactory(new PureJavaCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (Group row : rows) {
                writer.write(row);
            }
        }
        MzPeakSource source = MzPeakReaderEdgeCasesTest.edited(Fixtures.current(HAS_UV), index -> { },
                Map.of(DATA, Files.readAllBytes(file)));
        try (MzPeakReader reader = MzPeakReader.open(source)) {
            assertThat(reader.wavelengthSpectra()).isNotEmpty();
            return reader.wavelengthSpectra();
        }
    }

    private static String entry(String path, String format, String arrayType, String rank, String unit) {
        return "{\"context\": \"wavelength_spectrum\", \"path\": \"" + path + "\", \"buffer_format\": \"" + format
                + "\", \"array_type\": \"" + arrayType + "\", \"sorting_rank\": " + rank + ", \"transform\": null,"
                + " \"unit\": " + (unit == null ? "null" : "\"" + unit + "\"") + "}";
    }
}
