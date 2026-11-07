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
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * RD-22: chromatogram and wavelength-spectrum signal columns, resolved through the {@link SignalPlan} of the data
 * member.
 *
 * <p>Characterization, pinned on the code before RD-22 (agent-team/upstream-p1 5736e6d):
 * <ul>
 *   <li>the bits of every chromatogram and wavelength spectrum of the current set, read through
 *       {@link MzPeakReader} (CH-2 pins the legacy set);
 *   <li>mzPeakJ output reads back the arrays it was written from;
 *   <li>constructed members without an array index footer pin the decoding rules (Q-U5): a point row takes the
 *       literal {@code intensity}, else the first non-null numeric {@code intensity_*} column in schema order, else
 *       0, and a null time reads as NaN; a chunk row adds its time deltas to {@code time_chunk_start} whatever its
 *       {@code chunk_encoding} (a null delta adds 0), takes the plain {@code intensity} list, else the SLOF bytes,
 *       pads missing intensities with 0 and cuts surplus ones; rows without an index, and chunk rows without a
 *       start, are skipped; PIC bytes are not read; wavelength spectra read {@code wavelength} and the literal
 *       {@code intensity} only.
 * </ul>
 */
class SignalPlanWiringTest {

    /** Fixture and entity to the summary of its arrays, recorded on the base code (agent-team/upstream-p1 5736e6d). */
    private static final Map<String, String> CURRENT = Map.ofEntries(
            current("small.mzpeak", "chromatograms", "entries=1 absent=none values=96",
                    "316eab9cb4e2028177069ca8bade8660ed0b66fba3ccde716c924eab8baf98ee"),
            current("small.chunked.mzpeak", "chromatograms", "entries=1 absent=none values=96",
                    "316eab9cb4e2028177069ca8bade8660ed0b66fba3ccde716c924eab8baf98ee"),
            current("small.numpress.mzpeak", "chromatograms", "entries=1 absent=none values=96",
                    "20209b8732cb1e6a40e5fa49a44dd1e71c4fd273a5c33ccfe576b0fd5c4454e7"),
            current("small.unpacked.mzpeak", "chromatograms", "entries=1 absent=none values=96",
                    "316eab9cb4e2028177069ca8bade8660ed0b66fba3ccde716c924eab8baf98ee"),
            current("has_uv.mzpeak", "chromatograms", "entries=2 absent=none values=1476",
                    "7b80a16b9a131321b46839e48286b01ac0db3bff257228b20164d58629505bda"),
            current("has_uv.mzpeak", "wavelength_spectra", "entries=520 absent=none values=99840",
                    "9dc0c387ba84510932cc6426bb666b2f28b61b2a18cdd2e878902611f6471795"),
            current("diaPASEF.ref.mzpeak", "chromatograms", "entries=2 absent=none values=36",
                    "8a30e18791761bbc9e29e112b5578ac2c6fc2c63ff4d2da0810d8f68a3f3e075"),
            current("diaPASEF.grid.mzpeak", "chromatograms", "entries=2 absent=none values=36",
                    "8a30e18791761bbc9e29e112b5578ac2c6fc2c63ff4d2da0810d8f68a3f3e075"));

    /** The summary of a fixture without such entities. */
    private static final String NONE = "entries=0 absent=none values=0"
            + " sha256=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final MessageType CHROMATOGRAM_METADATA = MessageTypeParser.parseMessageType("""
            message m {
              optional int64 index (INTEGER(64,false));
              optional binary id (STRING);
            }""");

    private static final MessageType WAVELENGTH_METADATA = MessageTypeParser.parseMessageType("""
            message m {
              optional int64 index (INTEGER(64,false));
              optional binary id (STRING);
              optional double time;
            }""");

    @TempDir
    Path tmp;

    private static Map.Entry<String, String> current(String fixture, String entity, String summary, String sha256) {
        return Map.entry(fixture + " " + entity, summary + " sha256=" + sha256);
    }

    static Stream<String> currentFixtures() {
        return Fixtures.CURRENT_NAMES.stream();
    }

    // ---- characterization: fixtures and mzPeakJ output ---------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void currentChromatogramAndWavelengthArraysAreUnchanged(String fixture) {
        SignalSnapshotTest.SignalDigest chromatograms = new SignalSnapshotTest.SignalDigest("");
        SignalSnapshotTest.SignalDigest wavelength = new SignalSnapshotTest.SignalDigest("");
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            for (Chromatogram c : reader.chromatograms()) {
                chromatograms.entry(c.index(), c.time(), c.intensity());
            }
            for (WavelengthSpectrum w : reader.wavelengthSpectra()) {
                wavelength.entry(w.index(), w.wavelength(), w.intensity());
            }
        }
        assertThat(chromatograms.summary()).as("%s chromatograms", fixture)
                .isEqualTo(CURRENT.getOrDefault(fixture + " chromatograms", NONE));
        assertThat(wavelength.summary()).as("%s wavelength spectra", fixture)
                .isEqualTo(CURRENT.getOrDefault(fixture + " wavelength_spectra", NONE));
    }

    @Test
    void mzPeakJOutputReadsBackTheWrittenArrays() {
        List<Spectrum> spectra = new ArrayList<>();
        List<Chromatogram> chromatograms;
        List<WavelengthSpectrum> wavelength;
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.legacy("has_uv.mzpeak"))) {
            reader.iterator().forEachRemaining(spectra::add);
            chromatograms = reader.chromatograms();
            wavelength = reader.wavelengthSpectra();
        }
        Path written = tmp.resolve("written.mzpeak");
        MzPeakWriter.writeDirectory(written, spectra, chromatograms, wavelength, List.of(), FileMetadata.EMPTY);

        try (MzPeakReader reader = MzPeakReader.open(written)) {
            List<Chromatogram> readBack = reader.chromatograms();
            assertThat(readBack).hasSameSizeAs(chromatograms).hasSize(2);
            for (int i = 0; i < chromatograms.size(); i++) {
                assertThat(readBack.get(i).index()).isEqualTo(chromatograms.get(i).index());
                assertThat(readBack.get(i).time()).as("time %d", i).isEqualTo(chromatograms.get(i).time());
                assertThat(readBack.get(i).intensity()).as("intensity %d", i)
                        .isEqualTo(chromatograms.get(i).intensity());
            }
            List<WavelengthSpectrum> wavelengthBack = reader.wavelengthSpectra();
            assertThat(wavelengthBack).hasSameSizeAs(wavelength).hasSize(520);
            for (int i = 0; i < wavelength.size(); i++) {
                assertThat(wavelengthBack.get(i).index()).isEqualTo(wavelength.get(i).index());
                assertThat(wavelengthBack.get(i).wavelength()).as("wavelength %d", i)
                        .isEqualTo(wavelength.get(i).wavelength());
                assertThat(wavelengthBack.get(i).intensity()).as("intensity %d", i)
                        .isEqualTo(wavelength.get(i).intensity());
            }
        }
    }

    // ---- characterization: constructed chromatogram members without an array index ----------------------------

    /**
     * Point rows: the literal intensity (last in schema order) wins; without it the first non-null numeric
     * {@code intensity_*} column in schema order, skipping the string and the repeated one; else 0. A null time is
     * NaN, a row without an index is skipped, a chromatogram without rows is empty and rows of an index without a
     * header are not returned.
     */
    @Test
    void pointRowsWithoutAnArrayIndex() {
        MessageType schema = MessageTypeParser.parseMessageType("""
                message m {
                  optional group point {
                    optional int64 chromatogram_index (INTEGER(64,false));
                    optional binary intensity_label (STRING);
                    optional double time;
                    optional int32 intensity_counts;
                    repeated double intensity_repeated;
                    optional double intensity_au;
                    optional float intensity;
                  }
                }""");
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        Path data = write("chromatograms_data.parquet", schema,
                point(rows).append("chromatogram_index", 1L).append("intensity_label", "x").append("time", 1.0)
                        .append("intensity_counts", 99).append("intensity_au", 98.0).append("intensity", 10f).parent,
                point(rows).append("chromatogram_index", 1L).append("intensity_label", "7")
                        .append("intensity_repeated", 3.0).append("intensity_au", 2.5).parent,
                point(rows).append("chromatogram_index", 0L).append("time", 0.5).append("intensity_counts", 4)
                        .append("intensity_au", 9.0).parent,
                point(rows).append("time", 9.0).append("intensity", 9f).parent,
                point(rows).append("chromatogram_index", 0L).append("intensity_label", "5").append("time", 0.75)
                        .append("intensity_repeated", 6.0).parent,
                point(rows).append("chromatogram_index", 1L).append("time", 3.0).append("intensity", 30f).parent,
                point(rows).append("chromatogram_index", 5L).append("time", 1.0).append("intensity", 1f).parent,
                rows.newGroup());

        List<Chromatogram> all = chromatograms(data, 0, 1, 2);

        assertThat(all).extracting(Chromatogram::id).containsExactly("c0", "c1", "c2");
        assertArrays(all.get(0), new double[] {0.5, 0.75}, new double[] {4.0, 0.0});
        assertArrays(all.get(1), new double[] {1.0, Double.NaN, 3.0}, new double[] {10.0, 2.5, 30.0});
        assertArrays(all.get(2), new double[0], new double[0]);
    }

    /**
     * Chunk rows: the time axis is {@code time_chunk_start} plus the running sum of the deltas whatever the
     * chunk_encoding, a null delta adds 0; intensities come from the plain list, else the SLOF bytes, are padded
     * with 0 and cut to the time axis; PIC bytes are not read. Rows without an index or a start are skipped; chunks
     * of one index are appended in row order.
     */
    @Test
    void chunkRowsWithoutAnArrayIndex() {
        MessageType schema = chunkSchema();
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        byte[] slof = slof(100.0, 200.0);
        Path data = write("chromatograms_data.parquet", schema,
                chunk(rows, 0L, 10.0, "MS:1003089").list("time_chunk_values", 1.0, null, 2.0)
                        .list("intensity", 1.0, null, 3.0).parent,
                chunk(rows, 1L, 5.0, null).list("time_chunk_values", 0.5)
                        .bytes("intensity_numpress_slof_bytes", slof).parent,
                chunk(rows, 0L, 20.0, null).list("time_chunk_values").list("intensity").parent,
                chunk(rows, 2L, null, null).list("time_chunk_values", 1.0).list("intensity", 1.0, 2.0).parent,
                chunk(rows, null, 1.0, null).list("time_chunk_values", 1.0).list("intensity", 1.0, 2.0).parent,
                chunk(rows, 3L, 1.0, "MS:1000576").list("time_chunk_values", 1.0, 1.0)
                        .list("intensity", 7.0, 8.0, 9.0, 10.0).parent,
                chunk(rows, 4L, 0.0, null).list("time_chunk_values", 2.0)
                        .bytes("intensity_numpress_slof_bytes", new byte[0]).parent,
                chunk(rows, 5L, 0.0, null).list("time_chunk_values", 1.0).list("intensity", 4.0, 5.0)
                        .bytes("intensity_numpress_slof_bytes", slof).parent,
                chunk(rows, 6L, 0.0, null).list("time_chunk_values", 1.0)
                        .bytes("intensity_numpress_pic_bytes", new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}).parent,
                chunk(rows, 7L, 0.0, null).list("intensity", 6.0).parent);

        List<Chromatogram> all = chromatograms(data, 0, 1, 2, 3, 4, 5, 6, 7);

        double[] decoded = MSNumpress.decode(MSNumpress.ACC_NUMPRESS_SLOF, slof, slof.length);
        assertThat(decoded).hasSize(2);
        assertThat(decoded[0]).isCloseTo(100.0, within(0.1));
        assertThat(decoded[1]).isCloseTo(200.0, within(0.1));
        assertArrays(all.get(0), new double[] {10.0, 11.0, 11.0, 13.0, 20.0}, new double[] {1.0, 0.0, 3.0, 0.0, 0.0});
        assertArrays(all.get(1), new double[] {5.0, 5.5}, decoded);
        assertArrays(all.get(2), new double[0], new double[0]);
        assertArrays(all.get(3), new double[] {1.0, 2.0, 3.0}, new double[] {7.0, 8.0, 9.0});
        assertArrays(all.get(4), new double[] {0.0, 2.0}, new double[] {0.0, 0.0});
        assertArrays(all.get(5), new double[] {0.0, 1.0}, new double[] {4.0, 5.0});
        assertArrays(all.get(6), new double[] {0.0, 1.0}, new double[] {0.0, 0.0});
        assertArrays(all.get(7), new double[] {0.0}, new double[] {6.0});
    }

    /** SLOF bytes that do not decode throw, naming the chromatogram index. */
    @Test
    void undecodableSlofBytesThrowNamingTheChromatogram() {
        MessageType schema = chunkSchema();
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        Path data = write("chromatograms_data.parquet", schema,
                chunk(rows, 3L, 0.0, null).list("time_chunk_values", 1.0)
                        .bytes("intensity_numpress_slof_bytes", new byte[] {1, 2, 3}).parent);

        assertThatThrownBy(() -> chromatograms(data, 3))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("Failed to Numpress-decode intensity for chromatogram_index 3")
                .hasCauseInstanceOf(NegativeArraySizeException.class);
    }

    // ---- characterization: constructed wavelength members without an array index ------------------------------

    /**
     * Point rows: {@code wavelength} and the literal {@code intensity}, without an {@code intensity_*} fallback; a
     * null wavelength is NaN, a null intensity 0; rows without an index are skipped.
     */
    @Test
    void wavelengthPointRowsWithoutAnArrayIndex() {
        MessageType schema = MessageTypeParser.parseMessageType("""
                message m {
                  optional group point {
                    optional int64 wavelength_spectrum_index (INTEGER(64,false));
                    optional float wavelength;
                    optional float intensity;
                    optional double intensity_au;
                  }
                }""");
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        Path data = write("wavelength_spectra_data.parquet", schema,
                point(rows).append("wavelength_spectrum_index", 0L).append("wavelength", 200f)
                        .append("intensity", 1f).parent,
                point(rows).append("wavelength_spectrum_index", 0L).append("intensity_au", 5.0).parent,
                point(rows).append("wavelength", 205f).append("intensity", 9f).parent,
                point(rows).append("wavelength_spectrum_index", 1L).append("wavelength", 210f)
                        .append("intensity", 2f).append("intensity_au", 7.0).parent,
                point(rows).append("wavelength_spectrum_index", 0L).append("wavelength", 220f)
                        .append("intensity", 3f).parent,
                point(rows).append("wavelength_spectrum_index", 9L).append("wavelength", 1f)
                        .append("intensity", 1f).parent);

        List<WavelengthSpectrum> all = wavelengthSpectra(data, 0, 1, 2);

        assertThat(all).extracting(WavelengthSpectrum::id).containsExactly("w0", "w1", "w2");
        assertThat(all.get(0).wavelength()).containsExactly(200.0, Double.NaN, 220.0);
        assertThat(all.get(0).intensity()).containsExactly(1.0, 0.0, 3.0);
        assertThat(all.get(1).wavelength()).containsExactly(210.0);
        assertThat(all.get(1).intensity()).containsExactly(2.0);
        assertThat(all.get(2).wavelength()).isEmpty();
        assertThat(all.get(2).intensity()).isEmpty();
    }

    /** Wavelength spectra are read from point rows only: a chunk member gives empty arrays. */
    @Test
    void aChunkWavelengthMemberGivesEmptyArrays() {
        MessageType schema = MessageTypeParser.parseMessageType("""
                message m {
                  optional group chunk {
                    optional int64 wavelength_spectrum_index (INTEGER(64,false));
                    optional double wavelength_chunk_start;
                    optional group wavelength_chunk_values (LIST) { repeated group list { optional double item; } }
                    optional group intensity (LIST) { repeated group list { optional float item; } }
                  }
                }""");
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        Group row = rows.newGroup();
        new Row(row, row.addGroup("chunk")).append("wavelength_spectrum_index", 0L)
                .append("wavelength_chunk_start", 200.0).list("wavelength_chunk_values", 1.0)
                .list("intensity", 1.0, 2.0);
        Path data = write("wavelength_spectra_data.parquet", schema, row);

        List<WavelengthSpectrum> all = wavelengthSpectra(data, 0);

        assertThat(all).hasSize(1);
        assertThat(all.get(0).wavelength()).isEmpty();
        assertThat(all.get(0).intensity()).isEmpty();
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    /** The chromatograms of {@code data}, with one header {@code c<index>} per index, in the given order. */
    private List<Chromatogram> chromatograms(Path data, long... indices) {
        SimpleGroupFactory rows = new SimpleGroupFactory(CHROMATOGRAM_METADATA);
        Path metadata = write("chromatograms_metadata.parquet", CHROMATOGRAM_METADATA, Arrays.stream(indices)
                .mapToObj(i -> rows.newGroup().append("index", i).append("id", "c" + i)).toArray(Group[]::new));
        return ChromatogramStore.load(new LocalInputFile(metadata), new LocalInputFile(data)).all();
    }

    /** The wavelength spectra of {@code data}, with one header {@code w<index>} per index, in the given order. */
    private List<WavelengthSpectrum> wavelengthSpectra(Path data, long... indices) {
        SimpleGroupFactory rows = new SimpleGroupFactory(WAVELENGTH_METADATA);
        Path metadata = write("wavelength_spectra_metadata.parquet", WAVELENGTH_METADATA, Arrays.stream(indices)
                .mapToObj(i -> rows.newGroup().append("index", i).append("id", "w" + i).append("time", 0.1 * i))
                .toArray(Group[]::new));
        return WavelengthSpectrumStore.load(new LocalInputFile(metadata), new LocalInputFile(data)).all();
    }

    private static void assertArrays(Chromatogram c, double[] time, double[] intensity) {
        assertThat(c.time()).as("%s time", c.id()).containsExactly(time);
        assertThat(c.intensity()).as("%s intensity", c.id()).containsExactly(intensity);
    }

    /** A chromatogram chunk schema with the time axis, the plain intensity list and both Numpress byte lists. */
    private static MessageType chunkSchema() {
        return MessageTypeParser.parseMessageType("""
                message m {
                  optional group chunk {
                    optional int64 chromatogram_index (INTEGER(64,false));
                    optional double time_chunk_start;
                    optional double time_chunk_end;
                    optional group time_chunk_values (LIST) { repeated group list { optional double item; } }
                    optional binary chunk_encoding (STRING);
                    optional group intensity (LIST) { repeated group list { optional float item; } }
                    optional group intensity_numpress_slof_bytes (LIST) {
                      repeated group list { required int32 item (INTEGER(8,false)); }
                    }
                    optional group intensity_numpress_pic_bytes (LIST) {
                      repeated group list { required int32 item (INTEGER(8,false)); }
                    }
                  }
                }""");
    }

    /** A new row with its {@code point} struct. */
    private static Row point(SimpleGroupFactory rows) {
        Group row = rows.newGroup();
        return new Row(row, row.addGroup("point"));
    }

    /** A new row with its {@code chunk} struct; null arguments leave their columns null. */
    private static Row chunk(SimpleGroupFactory rows, Long index, Double start, String encoding) {
        Group row = rows.newGroup();
        Row chunk = new Row(row, row.addGroup("chunk"));
        if (index != null) {
            chunk.append("chromatogram_index", index);
        }
        if (start != null) {
            chunk.append("time_chunk_start", start);
        }
        if (encoding != null) {
            chunk.append("chunk_encoding", encoding);
        }
        return chunk;
    }

    /** The prefix struct of a row under construction, and the row itself as {@link #parent}. */
    private static final class Row {
        final Group parent;
        final Group struct;

        Row(Group parent, Group struct) {
            this.parent = parent;
            this.struct = struct;
        }

        Row append(String field, long value) {
            struct.append(field, value);
            return this;
        }

        Row append(String field, int value) {
            struct.append(field, value);
            return this;
        }

        Row append(String field, double value) {
            struct.append(field, value);
            return this;
        }

        Row append(String field, float value) {
            struct.append(field, value);
            return this;
        }

        Row append(String field, String value) {
            struct.append(field, value);
            return this;
        }

        /** A list column of FLOAT or DOUBLE items; a null value is a null item. */
        Row list(String field, Double... values) {
            Group list = struct.addGroup(field);
            for (Double v : values) {
                Group element = list.addGroup("list");
                if (v == null) {
                    continue;
                }
                if (element.getType().getType("item").asPrimitiveType().getPrimitiveTypeName()
                        == PrimitiveTypeName.FLOAT) {
                    element.append("item", v.floatValue());
                } else {
                    element.append("item", v);
                }
            }
            return this;
        }

        /** A list column of uint8 items. */
        Row bytes(String field, byte[] values) {
            Group list = struct.addGroup(field);
            for (byte b : values) {
                list.addGroup("list").append("item", b & 0xff);
            }
            return this;
        }
    }

    /** MS-Numpress SLOF bytes of {@code values}. */
    private static byte[] slof(double... values) {
        byte[] buffer = new byte[8 + 2 * values.length];
        int n = MSNumpress.encodeSlof(values, values.length, buffer,
                MSNumpress.optimalSlofFixedPoint(values, values.length));
        return Arrays.copyOf(buffer, n);
    }

    /** Writes {@code rows} as the Parquet file {@code name} of the temporary directory. */
    private Path write(String name, MessageType schema, Group... rows) {
        Path file = tmp.resolve(name);
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
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
        return file;
    }
}
