package org.mzpeak.io.parquet;

import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A parquet {@link InputFile} backed by an in-memory byte array. Used to read Parquet members extracted
 * from a (STORED) single-file {@code .mzpeak} ZIP without spilling to temp files.
 */
public final class ByteArrayInputFile implements InputFile {

    private final byte[] data;

    public ByteArrayInputFile(byte[] data) {
        this.data = data;
    }

    @Override
    public long getLength() {
        return data.length;
    }

    @Override
    public SeekableInputStream newStream() {
        return new Stream(data);
    }

    private static final class Stream extends SeekableInputStream {
        private final byte[] data;
        private int pos;

        Stream(byte[] data) {
            this.data = data;
        }

        @Override
        public long getPos() {
            return pos;
        }

        @Override
        public void seek(long newPos) {
            if (newPos < 0 || newPos > data.length) {
                throw new IllegalArgumentException("seek out of bounds: " + newPos);
            }
            pos = (int) newPos;
        }

        @Override
        public int read() {
            return pos < data.length ? (data[pos++] & 0xFF) : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            if (pos >= data.length) {
                return -1;
            }
            int n = Math.min(len, data.length - pos);
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override
        public void readFully(byte[] bytes) throws IOException {
            readFully(bytes, 0, bytes.length);
        }

        @Override
        public void readFully(byte[] bytes, int start, int len) throws IOException {
            if (pos + len > data.length) {
                throw new EOFException("readFully past end of buffer");
            }
            System.arraycopy(data, pos, bytes, start, len);
            pos += len;
        }

        @Override
        public int read(ByteBuffer buf) {
            if (!buf.hasRemaining()) {
                return 0;
            }
            if (pos >= data.length) {
                return -1;
            }
            int n = Math.min(buf.remaining(), data.length - pos);
            buf.put(data, pos, n);
            pos += n;
            return n;
        }

        @Override
        public void readFully(ByteBuffer buf) throws IOException {
            int n = buf.remaining();
            if (pos + n > data.length) {
                throw new EOFException("readFully(ByteBuffer) past end of buffer");
            }
            buf.put(data, pos, n);
            pos += n;
        }
    }
}
