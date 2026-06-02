package org.mzpeak.io.parquet;

import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * A parquet {@link InputFile} over a contiguous slice {@code [base, base+length)} of a {@link FileChannel}.
 * Used to read a STORED (uncompressed) Parquet member of a single-file {@code .mzpeak} ZIP <em>in place</em>,
 * without copying it into memory — so reads from inside the archive are seekable and memory-bounded, just
 * like reading an unpacked directory.
 *
 * <p>The channel is shared and read positionally ({@link FileChannel#read(ByteBuffer, long)} does not move the
 * channel position), so multiple slice streams over the same archive coexist safely.
 */
public final class FileSliceInputFile implements InputFile {

    private final FileChannel channel;
    private final long base;
    private final long length;

    public FileSliceInputFile(FileChannel channel, long base, long length) {
        this.channel = channel;
        this.base = base;
        this.length = length;
    }

    @Override
    public long getLength() {
        return length;
    }

    @Override
    public SeekableInputStream newStream() {
        return new Stream();
    }

    private final class Stream extends SeekableInputStream {
        private long pos;

        @Override
        public long getPos() {
            return pos;
        }

        @Override
        public void seek(long newPos) {
            if (newPos < 0 || newPos > length) {
                throw new IllegalArgumentException("seek out of bounds: " + newPos + " (length " + length + ")");
            }
            pos = newPos;
        }

        @Override
        public int read() throws IOException {
            if (pos >= length) {
                return -1;
            }
            ByteBuffer one = ByteBuffer.allocate(1);
            int n = channel.read(one, base + pos);
            if (n <= 0) {
                return -1;
            }
            pos++;
            return one.get(0) & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (pos >= length) {
                return -1;
            }
            int toRead = (int) Math.min(len, length - pos);
            int n = channel.read(ByteBuffer.wrap(b, off, toRead), base + pos);
            if (n <= 0) {
                return -1;
            }
            pos += n;
            return n;
        }

        @Override
        public void readFully(byte[] bytes) throws IOException {
            readFully(bytes, 0, bytes.length);
        }

        @Override
        public void readFully(byte[] bytes, int start, int len) throws IOException {
            readFully(ByteBuffer.wrap(bytes, start, len));
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (pos >= length) {
                return -1;
            }
            int want = (int) Math.min(dst.remaining(), length - pos);
            if (want == 0) {
                return 0;
            }
            int savedLimit = dst.limit();
            dst.limit(dst.position() + want);
            int n = channel.read(dst, base + pos);
            dst.limit(savedLimit);
            if (n <= 0) {
                return -1;
            }
            pos += n;
            return n;
        }

        @Override
        public void readFully(ByteBuffer dst) throws IOException {
            int need = dst.remaining();
            if (pos + need > length) {
                throw new EOFException("readFully past end of slice (length " + length + ")");
            }
            long filePos = base + pos;
            while (dst.hasRemaining()) {
                int n = channel.read(dst, filePos);
                if (n < 0) {
                    throw new EOFException("unexpected end of file in archive slice");
                }
                filePos += n;
            }
            pos += need;
        }
    }
}
