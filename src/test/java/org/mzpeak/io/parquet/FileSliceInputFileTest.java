package org.mzpeak.io.parquet;

import org.apache.parquet.io.SeekableInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterizes the streams of a {@link FileSliceInputFile} built from a {@link FileChannel}, before AP-6 moves the
 * class onto {@code RandomAccessBytes} and keeps this constructor as a delegate. The slice is
 * {@code [BASE, BASE + LENGTH)} of a member of the legacy {@code small.unpacked.mzpeak}; the oracle is the file's
 * bytes read with {@link Files#readAllBytes}.
 */
class FileSliceInputFileTest {

    private static final Path MEMBER = Fixtures.legacy("small.unpacked.mzpeak").resolve("spectra_peaks.parquet");
    private static final int BASE = 100;
    private static final int LENGTH = 1000;

    private static byte[] file;

    @BeforeAll
    static void readMember() throws IOException {
        file = Files.readAllBytes(MEMBER);
        assertThat(file.length).isGreaterThan(BASE + LENGTH + 64);
    }

    private static FileChannel channel() throws IOException {
        return FileChannel.open(MEMBER, StandardOpenOption.READ);
    }

    private static byte[] expected(int from, int to) {
        return Arrays.copyOfRange(file, BASE + from, BASE + to);
    }

    @Test
    void lengthIsTheSliceLength() throws IOException {
        try (FileChannel ch = channel()) {
            assertThat(new FileSliceInputFile(ch, BASE, LENGTH).getLength()).isEqualTo(LENGTH);
        }
    }

    @Test
    void singleByteReadsWalkTheSliceAndEndWithMinusOne() throws IOException {
        try (FileChannel ch = channel();
             SeekableInputStream in = new FileSliceInputFile(ch, BASE, LENGTH).newStream()) {
            assertThat(in.getPos()).isZero();
            assertThat(in.read()).isEqualTo(file[BASE] & 0xFF);
            assertThat(in.getPos()).isEqualTo(1);
            in.seek(LENGTH - 2);
            assertThat(in.read()).isEqualTo(file[BASE + LENGTH - 2] & 0xFF);
            assertThat(in.read()).isEqualTo(file[BASE + LENGTH - 1] & 0xFF);
            assertThat(in.getPos()).isEqualTo(LENGTH);
            assertThat(in.read()).isEqualTo(-1);
            assertThat(in.getPos()).isEqualTo(LENGTH);
        }
    }

    @Test
    void arrayReadsAreClampedToTheSlice() throws IOException {
        try (FileChannel ch = channel();
             SeekableInputStream in = new FileSliceInputFile(ch, BASE, LENGTH).newStream()) {
            byte[] buf = new byte[64];
            assertThat(in.read(buf, 4, 20)).isEqualTo(20);
            assertThat(Arrays.copyOfRange(buf, 4, 24)).isEqualTo(expected(0, 20));
            assertThat(in.getPos()).isEqualTo(20);

            in.seek(LENGTH - 10);
            Arrays.fill(buf, (byte) 0);
            assertThat(in.read(buf, 4, 60)).isEqualTo(10);
            assertThat(Arrays.copyOfRange(buf, 4, 14)).isEqualTo(expected(LENGTH - 10, LENGTH));
            assertThat(buf[14]).isZero();
            assertThat(in.getPos()).isEqualTo(LENGTH);
            assertThat(in.read(buf, 0, 8)).isEqualTo(-1);
            assertThat(in.read(buf, 0, 0)).isZero();
            assertThat(in.getPos()).isEqualTo(LENGTH);
        }
    }

    @Test
    void byteBufferReadsAreClampedAndKeepTheLimit() throws IOException {
        try (FileChannel ch = channel();
             SeekableInputStream in = new FileSliceInputFile(ch, BASE, LENGTH).newStream()) {
            ByteBuffer buf = ByteBuffer.allocate(64);
            buf.position(3);
            in.seek(LENGTH - 10);
            assertThat(in.read(buf)).isEqualTo(10);
            assertThat(buf.position()).isEqualTo(13);
            assertThat(buf.limit()).isEqualTo(64);
            assertThat(Arrays.copyOfRange(buf.array(), 3, 13)).isEqualTo(expected(LENGTH - 10, LENGTH));
            assertThat(in.getPos()).isEqualTo(LENGTH);
            assertThat(in.read(buf)).isEqualTo(-1);
            assertThat(in.read(ByteBuffer.allocate(0))).isZero();

            in.seek(0);
            ByteBuffer direct = ByteBuffer.allocateDirect(16);
            assertThat(in.read(direct)).isEqualTo(16);
            byte[] copy = new byte[16];
            direct.flip().get(copy);
            assertThat(copy).isEqualTo(expected(0, 16));
        }
    }

    @Test
    void readFullyReadsExactlyAndRefusesToCrossTheEnd() throws IOException {
        try (FileChannel ch = channel();
             SeekableInputStream in = new FileSliceInputFile(ch, BASE, LENGTH).newStream()) {
            byte[] twenty = new byte[20];
            in.readFully(twenty);
            assertThat(twenty).isEqualTo(expected(0, 20));
            byte[] window = new byte[30];
            in.readFully(window, 5, 10);
            assertThat(Arrays.copyOfRange(window, 5, 15)).isEqualTo(expected(20, 30));
            ByteBuffer buf = ByteBuffer.allocate(40);
            in.readFully(buf);
            assertThat(buf.position()).isEqualTo(40);
            assertThat(buf.array()).isEqualTo(expected(30, 70));
            assertThat(in.getPos()).isEqualTo(70);

            in.seek(LENGTH - 5);
            in.readFully(new byte[5]);
            assertThat(in.getPos()).isEqualTo(LENGTH);
            in.seek(LENGTH - 5);
            assertThatThrownBy(() -> in.readFully(new byte[6]))
                    .isInstanceOf(EOFException.class)
                    .hasMessage("readFully past end of slice (length " + LENGTH + ")");
            assertThatThrownBy(() -> in.readFully(new byte[8], 1, 6))
                    .isInstanceOf(EOFException.class)
                    .hasMessage("readFully past end of slice (length " + LENGTH + ")");
            assertThatThrownBy(() -> in.readFully(ByteBuffer.allocate(6)))
                    .isInstanceOf(EOFException.class)
                    .hasMessage("readFully past end of slice (length " + LENGTH + ")");
            assertThat(in.getPos()).isEqualTo(LENGTH - 5);
        }
    }

    @Test
    void seekIsBoundedByTheSlice() throws IOException {
        try (FileChannel ch = channel();
             SeekableInputStream in = new FileSliceInputFile(ch, BASE, LENGTH).newStream()) {
            in.seek(LENGTH);
            assertThat(in.getPos()).isEqualTo(LENGTH);
            assertThatThrownBy(() -> in.seek(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("seek out of bounds: -1 (length " + LENGTH + ")");
            assertThatThrownBy(() -> in.seek(LENGTH + 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("seek out of bounds: " + (LENGTH + 1) + " (length " + LENGTH + ")");
            assertThat(in.getPos()).isEqualTo(LENGTH);
        }
    }

    @Test
    void streamsKeepTheirOwnPositionAndLeaveTheChannelAlone() throws IOException {
        try (FileChannel ch = channel()) {
            FileSliceInputFile slice = new FileSliceInputFile(ch, BASE, LENGTH);
            try (SeekableInputStream a = slice.newStream(); SeekableInputStream b = slice.newStream()) {
                byte[] fromA = new byte[50];
                a.readFully(fromA);
                byte[] fromB = new byte[50];
                b.readFully(fromB);
                assertThat(fromA).isEqualTo(expected(0, 50)).isEqualTo(fromB);
                assertThat(a.read()).isEqualTo(file[BASE + 50] & 0xFF);
                assertThat(a.getPos()).isEqualTo(51);
                assertThat(b.getPos()).isEqualTo(50);
            }
            assertThat(ch.position()).isZero();
        }
    }

    @Test
    void concurrentStreamsReadTheSameBytes() throws Exception {
        int whole = file.length - BASE;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try (FileChannel ch = channel()) {
            FileSliceInputFile slice = new FileSliceInputFile(ch, BASE, whole);
            List<Future<byte[]>> reads = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                reads.add(pool.submit(() -> {
                    byte[] out = new byte[whole];
                    try (SeekableInputStream in = slice.newStream()) {
                        int chunk = 4096;
                        for (int at = 0; at < whole; at += chunk) {
                            in.readFully(out, at, Math.min(chunk, whole - at));
                        }
                    }
                    return out;
                }));
            }
            for (Future<byte[]> read : reads) {
                assertThat(read.get()).isEqualTo(Arrays.copyOfRange(file, BASE, file.length));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sliceReachingPastTheEndOfTheFile() throws IOException {
        int base = file.length - 4;
        try (FileChannel ch = channel();
             SeekableInputStream in = new FileSliceInputFile(ch, base, 10).newStream()) {
            byte[] buf = new byte[10];
            assertThat(in.read(buf, 0, 10)).isEqualTo(4);
            assertThat(Arrays.copyOfRange(buf, 0, 4)).isEqualTo(Arrays.copyOfRange(file, base, file.length));
            assertThat(in.getPos()).isEqualTo(4);
            assertThat(in.read()).isEqualTo(-1);
            assertThat(in.read(buf, 0, 10)).isEqualTo(-1);
            assertThat(in.read(ByteBuffer.allocate(4))).isEqualTo(-1);
            assertThat(in.getPos()).isEqualTo(4);
            assertThatThrownBy(() -> in.readFully(new byte[2]))
                    .isInstanceOf(EOFException.class)
                    .hasMessage("unexpected end of file in archive slice");
        }
    }

    @Test
    void readsAfterTheChannelIsClosedThrow() throws IOException {
        FileChannel ch = channel();
        FileSliceInputFile slice = new FileSliceInputFile(ch, BASE, LENGTH);
        try (SeekableInputStream in = slice.newStream()) {
            ch.close();
            assertThat(slice.getLength()).isEqualTo(LENGTH);
            assertThatThrownBy(in::read).isInstanceOf(ClosedChannelException.class);
            assertThatThrownBy(() -> in.read(new byte[4], 0, 4)).isInstanceOf(ClosedChannelException.class);
            assertThatThrownBy(() -> in.readFully(new byte[4])).isInstanceOf(ClosedChannelException.class);
            assertThat(in.getPos()).isZero();
        }
    }
}
