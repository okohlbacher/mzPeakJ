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
 * mzPeak columns are ZSTD-compressed, so supplying this factory via
 * {@code ParquetReadOptions.builder().withCodecFactory(...)} lets us read without touching Hadoop at all.
 *
 * <p>Reader-only: handles {@code UNCOMPRESSED} and {@code ZSTD}. Other codecs throw — add them here if a
 * future mzPeak writer emits SNAPPY/GZIP/etc.
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
        throw new UnsupportedOperationException("mzPeakJ is read-only; compression is not supported");
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
            output.put(input);
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
            if (Zstd.isError(n)) {
                throw new IOException("ZSTD decompression failed: " + Zstd.getErrorName(n));
            }
            return BytesInput.from(out);
        }

        @Override
        public void decompress(ByteBuffer input, int compressedSize, ByteBuffer output, int decompressedSize)
                throws IOException {
            byte[] compressed = new byte[compressedSize];
            input.get(compressed);
            byte[] out = new byte[decompressedSize];
            long n = Zstd.decompressByteArray(out, 0, decompressedSize, compressed, 0, compressed.length);
            if (Zstd.isError(n)) {
                throw new IOException("ZSTD decompression failed: " + Zstd.getErrorName(n));
            }
            output.put(out, 0, decompressedSize);
        }

        @Override
        public void release() {
        }
    };
}
