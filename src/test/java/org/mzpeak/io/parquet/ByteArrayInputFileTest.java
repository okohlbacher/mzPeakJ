package org.mzpeak.io.parquet;

import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.SeekableInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The streams of a {@link ByteArrayInputFile} over the bytes of a member of the current {@code small.unpacked.mzpeak};
 * the oracle is the same file read from disk.
 */
class ByteArrayInputFileTest {

    private static final Path MEMBER = Fixtures.current("small.unpacked.mzpeak").resolve("spectra_metadata.parquet");

    private static byte[] file;

    @BeforeAll
    static void readMember() throws IOException {
        file = Files.readAllBytes(MEMBER);
        assertThat(file.length).isGreaterThan(1024);
    }

    private static byte[] expected(int from, int to) {
        return Arrays.copyOfRange(file, from, to);
    }

    @Test
    void parquetReadsTheSameRecordsAndFooterAsFromDisk() {
        List<String> fromDisk = new ArrayList<>();
        ParquetGroups.forEach(MEMBER, row -> fromDisk.add(row.toString()));
        List<String> fromMemory = new ArrayList<>();
        ByteArrayInputFile input = new ByteArrayInputFile(file);
        ParquetGroups.forEach(input, row -> fromMemory.add(row.toString()));

        assertThat(fromDisk).isNotEmpty();
        assertThat(fromMemory).isEqualTo(fromDisk);
        assertThat(ParquetGroups.footerKeyValue(input))
                .isNotEmpty()
                .isEqualTo(ParquetGroups.footerKeyValue(new LocalInputFile(MEMBER)));
    }

    @Test
    void lengthIsTheArrayLength() {
        assertThat(new ByteArrayInputFile(file).getLength()).isEqualTo(file.length);
        assertThat(new ByteArrayInputFile(new byte[0]).getLength()).isZero();
    }

    @Test
    void singleByteReadsWalkTheArrayAndEndWithMinusOne() throws IOException {
        try (SeekableInputStream in = new ByteArrayInputFile(file).newStream()) {
            assertThat(in.getPos()).isZero();
            assertThat(in.read()).isEqualTo(file[0] & 0xFF);
            assertThat(in.getPos()).isEqualTo(1);
            in.seek(file.length - 2);
            assertThat(in.getPos()).isEqualTo(file.length - 2);
            assertThat(in.read()).isEqualTo(file[file.length - 2] & 0xFF);
            assertThat(in.read()).isEqualTo(file[file.length - 1] & 0xFF);
            assertThat(in.getPos()).isEqualTo(file.length);
            assertThat(in.read()).isEqualTo(-1);
            assertThat(in.getPos()).isEqualTo(file.length);
        }
    }

    @Test
    void arrayReadsAreClampedToTheEnd() throws IOException {
        try (SeekableInputStream in = new ByteArrayInputFile(file).newStream()) {
            byte[] buf = new byte[64];
            assertThat(in.read(buf, 4, 20)).isEqualTo(20);
            assertThat(Arrays.copyOfRange(buf, 4, 24)).isEqualTo(expected(0, 20));
            assertThat(in.getPos()).isEqualTo(20);
            assertThat(in.read(buf, 0, 0)).isZero();
            assertThat(in.getPos()).isEqualTo(20);

            in.seek(file.length - 10);
            Arrays.fill(buf, (byte) 0);
            assertThat(in.read(buf, 4, 60)).isEqualTo(10);
            assertThat(Arrays.copyOfRange(buf, 4, 14)).isEqualTo(expected(file.length - 10, file.length));
            assertThat(buf[14]).isZero();
            assertThat(in.getPos()).isEqualTo(file.length);
            assertThat(in.read(buf, 0, 8)).isEqualTo(-1);
            assertThat(in.read(buf, 0, 0)).isZero();
            assertThat(in.getPos()).isEqualTo(file.length);
        }
    }

    @Test
    void byteBufferReadsAreClampedToTheEnd() throws IOException {
        try (SeekableInputStream in = new ByteArrayInputFile(file).newStream()) {
            ByteBuffer buf = ByteBuffer.allocate(64);
            buf.position(3);
            assertThat(in.read(buf)).isEqualTo(61);
            assertThat(buf.position()).isEqualTo(64);
            assertThat(Arrays.copyOfRange(buf.array(), 3, 64)).isEqualTo(expected(0, 61));
            assertThat(in.read(buf)).isZero();
            assertThat(in.getPos()).isEqualTo(61);

            in.seek(file.length - 10);
            buf.clear().position(3);
            assertThat(in.read(buf)).isEqualTo(10);
            assertThat(buf.position()).isEqualTo(13);
            assertThat(buf.limit()).isEqualTo(64);
            assertThat(Arrays.copyOfRange(buf.array(), 3, 13)).isEqualTo(expected(file.length - 10, file.length));
            assertThat(in.getPos()).isEqualTo(file.length);
            assertThat(in.read(buf)).isEqualTo(-1);
            assertThat(buf.position()).isEqualTo(13);
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
        try (SeekableInputStream in = new ByteArrayInputFile(file).newStream()) {
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

            in.seek(file.length - 5);
            in.readFully(new byte[5]);
            assertThat(in.getPos()).isEqualTo(file.length);
            in.seek(file.length - 5);
            assertThatThrownBy(() -> in.readFully(new byte[6]))
                    .isInstanceOf(EOFException.class)
                    .hasMessage("readFully past end of buffer");
            assertThatThrownBy(() -> in.readFully(new byte[8], 1, 6))
                    .isInstanceOf(EOFException.class)
                    .hasMessage("readFully past end of buffer");
            ByteBuffer six = ByteBuffer.allocate(6);
            assertThatThrownBy(() -> in.readFully(six))
                    .isInstanceOf(EOFException.class)
                    .hasMessage("readFully(ByteBuffer) past end of buffer");
            assertThat(six.position()).isZero();
            assertThat(in.getPos()).isEqualTo(file.length - 5);
        }
    }

    @Test
    void seekIsBoundedByTheArray() throws IOException {
        try (SeekableInputStream in = new ByteArrayInputFile(file).newStream()) {
            in.seek(file.length);
            assertThat(in.getPos()).isEqualTo(file.length);
            assertThatThrownBy(() -> in.seek(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("seek out of bounds: -1");
            assertThatThrownBy(() -> in.seek(file.length + 1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("seek out of bounds: " + (file.length + 1L));
            assertThat(in.getPos()).isEqualTo(file.length);
            in.seek(0);
            assertThat(in.getPos()).isZero();
        }
    }

    @Test
    void streamsKeepTheirOwnPosition() throws IOException {
        ByteArrayInputFile input = new ByteArrayInputFile(file);
        try (SeekableInputStream a = input.newStream(); SeekableInputStream b = input.newStream()) {
            byte[] fromA = new byte[50];
            a.readFully(fromA);
            byte[] fromB = new byte[50];
            b.readFully(fromB);
            assertThat(fromA).isEqualTo(expected(0, 50)).isEqualTo(fromB);
            assertThat(a.read()).isEqualTo(file[50] & 0xFF);
            assertThat(a.getPos()).isEqualTo(51);
            assertThat(b.getPos()).isEqualTo(50);
        }
    }
}
