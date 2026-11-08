package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ZipSource} where its storage fails or its DEFLATE members disagree with their declared sizes: an archive
 * file that is gone when it is opened, bytes that fail to close, end early or are no archive, a DEFLATE member that
 * inflates to more than its declared size or whose compressed data is cut short, and a compressed member above 2 GiB.
 * The paths the fixtures and {@link ZipSourceCharacterizationTest} reach are not repeated here.
 */
class ZipSourceTest {

    private static final Path LEGACY_ARCHIVE = Fixtures.legacy("small.mzpeak");
    private static final Path UNPACKED = Fixtures.legacy("small.unpacked.mzpeak");
    private static final String INDEX = "mzpeak_index.json";

    /** {@link MzPeakSource#open(Path)} found a regular file, which is gone by the time it is opened. */
    @Test
    void anArchiveFileThatIsGoneWhenItIsOpened(@TempDir Path tmp) {
        Path absent = tmp.resolve("absent.mzpeak");
        assertThatThrownBy(() -> new ZipSource(absent))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("Failed to open mzPeak archive " + absent)
                .hasCauseInstanceOf(NoSuchFileException.class);
    }

    @Test
    void aFailingCloseOfBytesThatAreNoArchiveIsNotReported() throws IOException {
        AtomicInteger closes = new AtomicInteger();
        RandomAccessBytes junk = closeFails(RemoteSourceTest.inMemory(Files.readAllBytes(UNPACKED.resolve(INDEX))),
                closes, new IOException("socket gone"));
        assertThatThrownBy(() -> MzPeakSource.zip("memory:junk", junk))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("not a ZIP archive (no end-of-central-directory record found)")
                .hasNoCause()
                .satisfies(e -> assertThat(e.getSuppressed()).isEmpty());
        assertThat(closes.get()).isEqualTo(1);
    }

    @Test
    void aFailingCloseIsReported() throws IOException {
        AtomicInteger closes = new AtomicInteger();
        MzPeakSource source = MzPeakSource.zip("memory:small", closeFails(
                RemoteSourceTest.inMemory(Files.readAllBytes(LEGACY_ARCHIVE)), closes, new IOException("socket gone")));
        assertThat(source.readManifestBytes()).isEqualTo(Files.readAllBytes(UNPACKED.resolve(INDEX)));
        assertThatThrownBy(source::close)
                .isInstanceOf(MzPeakException.class)
                .hasMessage("Failed to close memory:small")
                .cause().isInstanceOf(IOException.class).hasMessage("socket gone");
        assertThat(closes.get()).isEqualTo(1);
    }

    /** Bytes that end before their {@code size()} once the directory is read, as a remote object replaced meanwhile. */
    @Test
    void bytesThatEndEarlyWhileAMemberIsRead() throws IOException {
        RandomAccessBytes all = RemoteSourceTest.inMemory(Files.readAllBytes(LEGACY_ARCHIVE));
        AtomicBoolean ended = new AtomicBoolean();
        RandomAccessBytes bytes = new RandomAccessBytes() {
            @Override
            public long size() throws IOException {
                return all.size();
            }

            @Override
            public int read(ByteBuffer dst, long position) throws IOException {
                return ended.get() ? -1 : all.read(dst, position);
            }

            @Override
            public void close() throws IOException {
                all.close();
            }
        };
        try (MzPeakSource source = MzPeakSource.zip("memory:ended", bytes)) {
            ended.set(true);
            assertThatThrownBy(source::readManifestBytes)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("unexpected end of archive reading member " + INDEX + " in memory:ended");
        }
    }

    @Test
    void anIntactDeflatedManifest() throws IOException {
        byte[] manifest = Files.readAllBytes(UNPACKED.resolve(INDEX));
        try (MzPeakSource source = MzPeakSource.zip("memory:deflated",
                RemoteSourceTest.inMemory(deflatedManifest().bytes))) {
            assertThat(source.readManifestBytes()).isEqualTo(manifest);
        }
    }

    /** The data inflates to one byte more than the size the central directory declares. */
    @Test
    void aDeflatedMemberThatInflatesToMoreThanItsDeclaredSize() throws IOException {
        Deflated zip = deflatedManifest();
        int size = zip.le().getInt(zip.record + 24);
        zip.le().putInt(zip.record + 24, size - 1);
        try (MzPeakSource source = MzPeakSource.zip("memory:longer", RemoteSourceTest.inMemory(zip.bytes))) {
            assertThatThrownBy(source::readManifestBytes)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("truncated/corrupt DEFLATE member " + INDEX + " in memory:longer (inflated "
                            + (size - 1) + " of " + (size - 1) + " bytes)");
        }
    }

    /**
     * The central directory declares half the compressed size: the inflater runs out of input with the stream
     * unfinished, after what the first half inflates to, which {@link InflaterInputStream} measures here.
     */
    @Test
    void aDeflatedMemberWhoseCompressedDataIsCutShort() throws IOException {
        Deflated zip = deflatedManifest();
        int size = zip.le().getInt(zip.record + 24);
        int compressed = zip.le().getInt(zip.record + 20);
        int half = compressed / 2;
        zip.le().putInt(zip.record + 20, half);
        int inflated = inflatedBeforeTheInputEnds(Arrays.copyOfRange(zip.bytes, zip.data, zip.data + half));
        assertThat(inflated).isPositive().isLessThan(size);
        try (MzPeakSource source = MzPeakSource.zip("memory:cut", RemoteSourceTest.inMemory(zip.bytes))) {
            assertThatThrownBy(source::readManifestBytes)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("truncated/corrupt DEFLATE member " + INDEX + " in memory:cut (inflated "
                            + inflated + " of " + size + " bytes)");
        }
    }

    @Test
    void aDeflatedMemberWithoutCompressedData() throws IOException {
        Deflated zip = deflatedManifest();
        int size = zip.le().getInt(zip.record + 24);
        zip.le().putInt(zip.record + 20, 0);
        try (MzPeakSource source = MzPeakSource.zip("memory:empty", RemoteSourceTest.inMemory(zip.bytes))) {
            assertThatThrownBy(source::readManifestBytes)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("truncated/corrupt DEFLATE member " + INDEX + " in memory:empty (inflated 0 of "
                            + size + " bytes)");
        }
    }

    /**
     * A DEFLATE member of 10 bytes whose compressed data is one byte above the in-memory limit of
     * {@code Integer.MAX_VALUE - 8} bytes, in a sparse archive of a little over 2 GiB.
     */
    @Test
    void aCompressedMemberAboveTheInMemoryLimit() {
        long compressed = Integer.MAX_VALUE - 7L;
        byte[] name = INDEX.getBytes(StandardCharsets.UTF_8);
        ZipIndexTest.Le header = new ZipIndexTest.Le().i32(0x04034b50).i16(20).i16(0).i16(ZipIndex.DEFLATED).i32(0)
                .i32(0).i32((int) compressed).i32(10).i16(name.length).i16(0).put(name);
        long cdOffset = header.size() + compressed;
        ZipIndexTest.Le directory = new ZipIndexTest.Le().i32(0x02014b50).i16(20).i16(20).i16(0)
                .i16(ZipIndex.DEFLATED).i32(0).i32(0).i32((int) compressed).i32(10).i16(name.length).i16(0).i16(0)
                .i16(0).i16(0).i32(0).i32(0).put(name);
        ZipIndexTest.Le eocd = new ZipIndexTest.Le().i32(0x06054b50).i16(0).i16(0).i16(1).i16(1)
                .i32(directory.size()).i32((int) cdOffset).i16(0);
        ZipIndexTest.Sparse bytes = new ZipIndexTest.Sparse(cdOffset + directory.size() + eocd.size())
                .put(0, header.toByteArray())
                .put(cdOffset, directory.toByteArray())
                .put(cdOffset + directory.size(), eocd.toByteArray());
        try (MzPeakSource source = MzPeakSource.zip("memory:large", bytes)) {
            assertThatThrownBy(source::readManifestBytes)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("mzPeak member " + INDEX + " is too large to read into memory in memory:large");
            assertThatThrownBy(() -> source.inputFile(INDEX))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("mzPeak member " + INDEX + " is too large to read into memory in memory:large");
        }
        assertThat(bytes.closed).isTrue();
    }

    /** The bytes {@code compressed} inflate to before they run out, read through {@link InflaterInputStream}. */
    private static int inflatedBeforeTheInputEnds(byte[] compressed) throws IOException {
        int count = 0;
        try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed), new Inflater(true))) {
            while (in.read() != -1) {
                count++;
            }
            throw new AssertionError("the cut stream inflated completely");
        } catch (EOFException expected) {
            return count;
        }
    }

    /** {@code delegate}, whose close counts on {@code closes} and then throws {@code failure}. */
    private static RandomAccessBytes closeFails(RandomAccessBytes delegate, AtomicInteger closes, IOException failure) {
        return new RandomAccessBytes() {
            @Override
            public long size() throws IOException {
                return delegate.size();
            }

            @Override
            public int read(ByteBuffer dst, long position) throws IOException {
                return delegate.read(dst, position);
            }

            @Override
            public void close() throws IOException {
                closes.incrementAndGet();
                delegate.close();
                throw failure;
            }
        };
    }

    /** An archive whose only member is the legacy manifest, DEFLATED; its central record and data offset. */
    private record Deflated(byte[] bytes, int record, int data) {

        ByteBuffer le() {
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    private static Deflated deflatedManifest() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            ZipEntry e = new ZipEntry(INDEX);
            e.setMethod(ZipEntry.DEFLATED);
            zip.putNextEntry(e);
            zip.write(Files.readAllBytes(UNPACKED.resolve(INDEX)));
            zip.closeEntry();
        }
        byte[] bytes = out.toByteArray();
        ByteBuffer le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = bytes.length - 22;
        assertThat(le.getInt(eocd)).isEqualTo(0x06054b50);
        assertThat(le.getShort(eocd + 10)).isEqualTo((short) 1);
        int record = le.getInt(eocd + 16);
        assertThat(le.getInt(record)).isEqualTo(0x02014b50);
        assertThat(le.getShort(record + 10)).isEqualTo((short) ZipEntry.DEFLATED);
        int header = le.getInt(record + 42);
        int data = header + 30 + (le.getShort(header + 26) & 0xFFFF) + (le.getShort(header + 28) & 0xFFFF);
        return new Deflated(bytes, record, data);
    }
}
