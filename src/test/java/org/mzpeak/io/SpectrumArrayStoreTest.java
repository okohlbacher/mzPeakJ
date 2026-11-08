package org.mzpeak.io;

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
import org.apache.parquet.io.SeekableInputStream;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SpectrumArrayStore} paths that {@link SpectrumArrayStoreCharacterizationTest}, {@link ExtraArraysTest} and
 * the fixture tests leave open: members that cannot be opened or read (I/O failures, injected through a wrapping
 * {@link InputFile}), row groups whose index statistics are not in index order, a point extra that is not numeric, and
 * the delta-model map {@link MzPeakReader} never passes (null, or an empty model). No fixture reaches these paths, so
 * each case writes a constructed point member with an array index footer into a {@link TempDir}.
 */
class SpectrumArrayStoreTest {

    private static final String IM = "MS:1003006";

    private static final MessageType POINT = MessageTypeParser.parseMessageType("""
            message m {
              optional group point {
                optional int64 spectrum_index (INTEGER(64,false));
                optional double mz;
                optional float intensity;
                optional double im;
                optional binary label (STRING);
              }
            }""");

    private static final String INDEX = index("point",
            entry("point.spectrum_index", "index", "MS:1003870", 0, null),
            entry("point.mz", "point", "MS:1000514", 1, null),
            entry("point.intensity", "point", "MS:1000515", null, null),
            entry("point.im", "point", IM, null, null),
            entry("point.label", "point", "MS:1000786", null, "label"));

    @TempDir
    Path tmp;

    private int written;

    // ---- I/O failures -------------------------------------------------------------------------------------------

    @Test
    void aMissingMemberFailsToOpen() {
        Path missing = tmp.resolve("spectra_data.parquet");
        assertThatThrownBy(() -> SpectrumArrayStore.load(new LocalInputFile(missing), false, Map.of()))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Failed opening spectrum signal file")
                .cause().hasMessageContaining("spectra_data.parquet");
    }

    /** The plan error is thrown after the member is closed; a failure to close it is kept as suppressed. */
    @Test
    void aPlanErrorClosesTheMemberAndKeepsTheCloseFailure() {
        String misplaced = index("point",
                entry("point.spectrum_index", "index", "MS:1003870", 0, null),
                entry("chunk.mz", "point", "MS:1000514", 1, null));
        Path file = write(misplaced, 100, row(0, 100.0, 10f, null, null));
        FaultyFile faulty = new FaultyFile(file, true);

        assertThatThrownBy(() -> SpectrumArrayStore.load(faulty, false, Map.of()))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("spectrum_array_index (FOOTER) lists chunk.mz, which is not under its prefix point")
                .satisfies(e -> assertThat(e.getSuppressed()).singleElement(InstanceOfAssertFactories.THROWABLE)
                        .isInstanceOf(IOException.class)
                        .hasMessage("injected close failure"));
        assertThat(faulty.closed).as("streams closed").isEqualTo(faulty.opened).isPositive();
    }

    /** A row group that cannot be read names the run of row groups; the store reads it again once it can. */
    @Test
    void aRowGroupReadFailureNamesTheRowGroups() {
        Path file = write(INDEX, 2,
                row(2, 100.0, 10f, null, null), row(2, 101.0, 11f, null, null), row(2, 102.0, 12f, null, null));
        FaultyFile faulty = new FaultyFile(file, false);

        try (SpectrumArrayStore store = SpectrumArrayStore.load(faulty, false, Map.of())) {
            faulty.failReads = true;
            assertThatThrownBy(() -> store.get(2))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessage("Failed reading row groups 0..1")
                    .cause().hasMessage("injected read failure");

            faulty.failReads = false;
            SpectrumArrayStore.Arrays arrays = store.get(2);
            assertThat(arrays.mz()).containsExactly(100.0, 101.0, 102.0);
            assertThat(arrays.intensity()).containsExactly(10.0, 11.0, 12.0);
        }
    }

    // ---- row-group statistics -----------------------------------------------------------------------------------

    /**
     * Row groups [4,4] [2,3] [2,2], from rows 4 4 | 2 3 | 2: a spectrum is read from the first row group whose
     * statistics cover it and the consecutive ones that do too. Spectra 4 and 3 stop before a row group of lower
     * indices, so the second run of 2 in the last row group is not decoded with them; spectrum 2 reads it and fails.
     */
    @Test
    void aSpectrumStopsAtARowGroupWhoseStatisticsLieBelowIt() {
        Path file = write(INDEX, 2,
                row(4, 400.0, 40f, null, null), row(4, 401.0, 41f, null, null),
                row(2, 200.0, 20f, null, null), row(3, 300.0, 30f, null, null),
                row(2, 201.0, 21f, null, null));

        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), false, Map.of())) {
            assertThat(store.get(4).mz()).containsExactly(400.0, 401.0);
            assertThat(store.get(4).intensity()).containsExactly(40.0, 41.0);
            assertThat(store.get(3).mz()).containsExactly(300.0);
            assertThat(store.get(3).intensity()).containsExactly(30.0);
            assertThatThrownBy(() -> store.get(2))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("signal file is not sorted by spectrum_index (index 2 appears in multiple"
                            + " non-contiguous runs)");
        }
    }

    // ---- extras -------------------------------------------------------------------------------------------------

    /**
     * A point extra whose column is not numeric is dropped from every spectrum, with one WARNING per member and
     * extra; the other extra, the m/z and the intensity are read.
     */
    @Test
    void aPointExtraThatIsNotNumericIsDroppedWithOneWarning() {
        Path file = write(INDEX, 100,
                row(0, 100.0, 10f, 0.75, "a"), row(0, 101.0, 11f, 0.875, "b"),
                row(1, 200.0, 20f, 1.25, "c"));

        List<LogRecord> records = ArrayIndexTest.captureLog(SpectrumArrayStore.class, () -> {
            try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), "spectra_data.parquet",
                    false, Map.of())) {
                assertThat(store.plan().extras().keySet()).containsExactly(IM, "label");

                SpectrumArrayStore.Arrays zero = store.get(0);
                assertThat(zero.mz()).containsExactly(100.0, 101.0);
                assertThat(zero.intensity()).containsExactly(10.0, 11.0);
                assertThat(zero.extras().keySet()).containsExactly(IM);
                assertThat(zero.extras().get(IM)).containsExactly(0.75, 0.875);

                SpectrumArrayStore.Arrays one = store.get(1);
                assertThat(one.mz()).containsExactly(200.0);
                assertThat(one.extras().keySet()).containsExactly(IM);
                assertThat(one.extras().get(IM)).containsExactly(1.25);
            }
        });
        assertThat(records).filteredOn(r -> r.getLevel() == Level.WARNING).extracting(LogRecord::getMessage)
                .containsExactly("spectra_data.parquet: the extra array label of spectrum_index 0 cannot be read as a"
                        + " number (Field label is not numeric: BINARY); it is dropped from that spectrum, whose m/z"
                        + " and intensity are kept (logged once per member and extra array)");
    }

    // ---- delta models -------------------------------------------------------------------------------------------

    @Test
    void withoutADeltaModelMapTheNullMarkedMzIsInterpolated() {
        Path file = nullMarked();
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), true, null)) {
            SpectrumArrayStore.Arrays arrays = store.get(2);
            assertThat(arrays.mz()).containsExactly(400.0, 400.5, 401.0);
            assertThat(arrays.intensity()).containsExactly(10.0, 0.0, 30.0);
        }
    }

    /** An empty delta model is no model: the null-marked m/z is interpolated, not stepped with a zero spacing. */
    @Test
    void anEmptyDeltaModelInterpolatesTheNullMarkedMz() {
        Path file = nullMarked();
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), true,
                Map.of(2L, new double[0]))) {
            assertThat(store.get(2).mz()).containsExactly(400.0, 400.5, 401.0);
            assertThat(store.get(2).intensity()).containsExactly(10.0, 0.0, 30.0);
        }
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), true,
                Map.of(2L, new double[] {0.25}))) {
            assertThat(store.get(2).mz()).as("a one-coefficient model steps").containsExactly(400.0, 400.25, 401.0);
        }
    }

    // ---- constructed members ------------------------------------------------------------------------------------

    /** Spectrum 2 with a null-marked point between m/z 400 and 401. */
    private Path nullMarked() {
        return write(INDEX, 100, row(2, 400.0, 10f, null, null), row(2, null, null, null, null),
                row(2, 401.0, 30f, null, null));
    }

    /** A row of {@link #POINT}; a null argument leaves its cell null. */
    private static Group row(long index, Double mz, Float intensity, Double im, String label) {
        Group row = new SimpleGroupFactory(POINT).newGroup();
        Group point = row.addGroup("point");
        point.append("spectrum_index", index);
        if (mz != null) {
            point.append("mz", mz);
        }
        if (intensity != null) {
            point.append("intensity", intensity);
        }
        if (im != null) {
            point.append("im", im);
        }
        if (label != null) {
            point.append("label", label);
        }
        return row;
    }

    private static String entry(String path, String format, String arrayType, Integer rank, String arrayName) {
        return "{\"path\": \"" + path + "\", \"buffer_format\": \"" + format + "\", \"array_type\": \"" + arrayType
                + "\", \"sorting_rank\": " + rank + ", \"array_name\": "
                + (arrayName == null ? "null" : "\"" + arrayName + "\"") + "}";
    }

    private static String index(String prefix, String... entries) {
        return "{\"prefix\": \"" + prefix + "\", \"entries\": [" + String.join(", ", entries) + "]}";
    }

    /** Writes {@code rows} with {@code json} as the spectrum array index, at most {@code rowGroupRows} per group. */
    private Path write(String json, int rowGroupRows, Group... rows) {
        Path file = tmp.resolve("member-" + (written++) + ".parquet");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(POINT)
                .withExtraMetaData(Map.of(SignalEntity.SPECTRUM.arrayIndexKey(), json))
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withRowGroupRowCountLimit(rowGroupRows)
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

    /**
     * A local file whose streams fail on demand: every read while {@link #failReads} is set, and every close (after
     * closing the file) when made with {@code failClose}.
     */
    private static final class FaultyFile implements InputFile {

        private final InputFile file;
        private final boolean failClose;
        volatile boolean failReads;
        int opened;
        int closed;

        FaultyFile(Path path, boolean failClose) {
            this.file = new LocalInputFile(path);
            this.failClose = failClose;
        }

        @Override
        public long getLength() throws IOException {
            return file.getLength();
        }

        @Override
        public SeekableInputStream newStream() throws IOException {
            SeekableInputStream in = file.newStream();
            opened++;
            return new SeekableInputStream() {
                @Override
                public long getPos() throws IOException {
                    return in.getPos();
                }

                @Override
                public void seek(long newPos) throws IOException {
                    in.seek(newPos);
                }

                @Override
                public int read() throws IOException {
                    check();
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    check();
                    return in.read(b, off, len);
                }

                @Override
                public void readFully(byte[] bytes) throws IOException {
                    check();
                    in.readFully(bytes);
                }

                @Override
                public void readFully(byte[] bytes, int start, int len) throws IOException {
                    check();
                    in.readFully(bytes, start, len);
                }

                @Override
                public int read(ByteBuffer buf) throws IOException {
                    check();
                    return in.read(buf);
                }

                @Override
                public void readFully(ByteBuffer buf) throws IOException {
                    check();
                    in.readFully(buf);
                }

                @Override
                public void close() throws IOException {
                    in.close();
                    closed++;
                    if (failClose) {
                        throw new IOException("injected close failure");
                    }
                }
            };
        }

        private void check() throws IOException {
            if (failReads) {
                throw new IOException("injected read failure");
            }
        }
    }
}
