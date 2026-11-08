package org.mzpeak.io;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link UnpackedSource} reading a manifest whose bytes fail: one too large to read into memory, one that ends before
 * its size, and one whose read throws. {@link RemoteSourceTest} covers the members a {@code MemberOpener} opens from
 * the fixtures.
 */
class UnpackedSourceTest {

    private static final String INDEX = "mzpeak_index.json";

    @Test
    void aManifestAboveTheInMemoryLimitIsNotRead() {
        AtomicInteger closes = new AtomicInteger();
        // one byte above Integer.MAX_VALUE - 8; reading it would fail the test
        MzPeakSource source = MzPeakSource.unpacked("files",
                name -> bytes(Integer.MAX_VALUE - 7L, (dst, position) -> {
                    throw new AssertionError("read of " + name);
                }, closes));
        assertThatThrownBy(source::readManifestBytes)
                .isInstanceOf(MzPeakException.class)
                .hasMessage("mzPeak member " + INDEX + " is too large to read into memory in files");
        source.close();
        assertThat(closes.get()).as("the manifest opened is closed with the source").isEqualTo(1);
    }

    @Test
    void aManifestThatEndsBeforeItsSize() {
        byte[] data = new byte[40];
        AtomicInteger closes = new AtomicInteger();
        try (MzPeakSource source = MzPeakSource.unpacked("files", name -> bytes(100, (dst, position) -> {
            if (position >= data.length) {
                return -1;
            }
            int n = Math.min(dst.remaining(), data.length - (int) position);
            dst.put(data, (int) position, n);
            return n;
        }, closes))) {
            assertThatThrownBy(source::readManifestBytes)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("unexpected end of member " + INDEX + " in files");
        }
        assertThat(closes.get()).isEqualTo(1);
    }

    @Test
    void aManifestWhoseReadFails() {
        AtomicInteger closes = new AtomicInteger();
        try (MzPeakSource source = MzPeakSource.unpacked("files", name -> bytes(100, (dst, position) -> {
            throw new IOException("connection reset at " + position);
        }, closes))) {
            assertThatThrownBy(source::readManifestBytes)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("Failed to read member " + INDEX + " from files")
                    .cause().isInstanceOf(IOException.class).hasMessage("connection reset at 0");
        }
        assertThat(closes.get()).isEqualTo(1);
    }

    /** A read, for {@link #bytes}. */
    @FunctionalInterface
    private interface Read {
        int read(ByteBuffer dst, long position) throws IOException;
    }

    /** Bytes of {@code size} that {@code read} reads, counting the closes on {@code closes}. */
    private static RandomAccessBytes bytes(long size, Read read, AtomicInteger closes) {
        return new RandomAccessBytes() {
            @Override
            public long size() {
                return size;
            }

            @Override
            public int read(ByteBuffer dst, long position) throws IOException {
                return read.read(dst, position);
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
    }
}
