package org.mzpeak.io.parquet;

import com.github.luben.zstd.Zstd;
import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A Hadoop-free {@link CompressionCodecFactory} for reading mzPeak Parquet files.
 *
 * <p>parquet-java's default codec factory ({@code org.apache.parquet.hadoop.CodecFactory}) builds a Hadoop
 * {@code Configuration} on every {@code getCodec} call, which pulls in the shaded Woodstox/Hadoop runtime.
 * mzPeak columns are ZSTD-compressed, so supplying this factory (to both
 * {@code ParquetReadOptions.builder().withCodecFactory(...)} and the writer's
 * {@code ParquetWriter.Builder.withCodecFactory(...)}) lets us read and write without touching Hadoop at all.
 *
 * <p>Handles {@code UNCOMPRESSED} and {@code ZSTD} for both decompression (read) and compression (write).
 * Other codecs throw — add them here if a future mzPeak file uses SNAPPY/GZIP/etc.
 */
public final class ZstdCompressionCodecFactory implements CompressionCodecFactory {

    @Override
    public BytesInputDecompressor getDecompressor(CompressionCodecName codecName) {
        return switch (codecName) {
            case UNCOMPRESSED -> PASSTHROUGH;
            case ZSTD -> ZSTD_DECOMPRESSOR;
            default -> throw new UnsupportedOperationException(
                    "mzPeakJ reader does not support Parquet codec " + codecName + " yet");
        };
    }

    @Override
    public BytesInputCompressor getCompressor(CompressionCodecName codecName) {
        return switch (codecName) {
            case UNCOMPRESSED -> PASSTHROUGH_COMPRESSOR;
            case ZSTD -> ZSTD_COMPRESSOR;
            default -> throw new UnsupportedOperationException(
                    "mzPeakJ does not support writing Parquet codec " + codecName);
        };
    }

    @Override
    public void release() {
        // no pooled resources
    }

    private static final BytesInputDecompressor PASSTHROUGH = new BytesInputDecompressor() {
        @Override
        public BytesInput decompress(BytesInput bytes, int decompressedSize) {
            return bytes;
        }

        @Override
        public void decompress(ByteBuffer input, int compressedSize, ByteBuffer output, int decompressedSize) {
            int originalLimit = input.limit();
            try {
                input.limit(input.position() + compressedSize);
                output.put(input);
            } finally {
                input.limit(originalLimit);
            }
        }

        @Override
        public void release() {
        }
    };

    private static final BytesInputDecompressor ZSTD_DECOMPRESSOR = new BytesInputDecompressor() {
        @Override
        public BytesInput decompress(BytesInput bytes, int decompressedSize) throws IOException {
            byte[] compressed = bytes.toByteArray();
            byte[] out = new byte[decompressedSize];
            long n = Zstd.decompressByteArray(out, 0, decompressedSize, compressed, 0, compressed.length);
            checkSize(n, decompressedSize);
            return BytesInput.from(out);
        }

        @Override
        public void decompress(ByteBuffer input, int compressedSize, ByteBuffer output, int decompressedSize)
                throws IOException {
            byte[] compressed = new byte[compressedSize];
            input.get(compressed);
            byte[] out = new byte[decompressedSize];
            long n = Zstd.decompressByteArray(out, 0, decompressedSize, compressed, 0, compressed.length);
            checkSize(n, decompressedSize);
            output.put(out, 0, decompressedSize);
        }

        @Override
        public void release() {
        }
    };

    /** Parquet's default ZSTD level. */
    private static final int ZSTD_LEVEL = 3;

    private static final BytesInputCompressor PASSTHROUGH_COMPRESSOR = new BytesInputCompressor() {
        @Override
        public BytesInput compress(BytesInput bytes) {
            return bytes;
        }

        @Override
        public CompressionCodecName getCodecName() {
            return CompressionCodecName.UNCOMPRESSED;
        }

        @Override
        public void release() {
        }
    };

    private static final BytesInputCompressor ZSTD_COMPRESSOR = new BytesInputCompressor() {
        @Override
        public BytesInput compress(BytesInput bytes) throws IOException {
            return BytesInput.from(Zstd.compress(bytes.toByteArray(), ZSTD_LEVEL));
        }

        @Override
        public CompressionCodecName getCodecName() {
            return CompressionCodecName.ZSTD;
        }

        @Override
        public void release() {
        }
    };

    private static void checkSize(long actual, int expected) throws IOException {
        if (Zstd.isError(actual)) {
            throw new IOException("ZSTD decompression failed: " + Zstd.getErrorName(actual));
        }
        if (actual != expected) {
            throw new IOException("ZSTD decompressed size mismatch: got " + actual + ", expected " + expected);
        }
    }
}

