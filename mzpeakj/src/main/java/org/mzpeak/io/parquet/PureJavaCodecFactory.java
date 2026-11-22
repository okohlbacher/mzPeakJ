package org.mzpeak.io.parquet;

import com.github.luben.zstd.Zstd;
import io.airlift.compress.lz4.Lz4Decompressor;
import io.airlift.compress.lz4.Lz4HadoopStreams;
import io.airlift.compress.lzo.LzoHadoopStreams;
import io.airlift.compress.snappy.SnappyDecompressor;
import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.brotli.dec.BrotliInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;

/**
 * A Hadoop-free {@link CompressionCodecFactory} for reading and writing mzPeak Parquet files.
 *
 * <p>parquet-java's default codec factory ({@code org.apache.parquet.hadoop.CodecFactory}) builds a Hadoop
 * {@code Configuration} on every {@code getCodec} call, which pulls in the shaded Woodstox/Hadoop runtime.
 * Supplying this factory (to both {@code ParquetReadOptions.builder().withCodecFactory(...)} and the writer's
 * {@code ParquetWriter.Builder.withCodecFactory(...)}) lets us read and write without touching Hadoop at all.
 *
 * <p>Decompresses every codec Parquet lists, which the mzPeak specification allows: {@code UNCOMPRESSED},
 * {@code ZSTD} (zstd-jni), {@code GZIP} ({@code java.util.zip}, concatenated members included), {@code SNAPPY},
 * {@code LZ4_RAW}, {@code LZ4} and {@code LZO} (aircompressor) and {@code BROTLI} (org.brotli:dec). All but ZSTD
 * are pure Java and load no native library. {@code LZ4} and {@code LZO} pages carry Hadoop's block framing; an
 * {@code LZ4} page that does not parse as such is decoded as one raw LZ4 block, as Arrow C++ does. A page of these
 * codecs that is corrupt or does not decompress to the size its page header declares throws an
 * {@link IOException} whose message starts with the codec name.
 *
 * <p>Compresses {@code UNCOMPRESSED} and {@code ZSTD} (what mzPeakJ writes); other codecs throw
 * {@link UnsupportedOperationException}.
 */
public class PureJavaCodecFactory implements CompressionCodecFactory {

    /** Creates the factory; it holds no state. */
    public PureJavaCodecFactory() {
    }

    @Override
    public BytesInputDecompressor getDecompressor(CompressionCodecName codecName) {
        return switch (codecName) {
            case UNCOMPRESSED -> PASSTHROUGH;
            case ZSTD -> ZSTD_DECOMPRESSOR;
            case GZIP -> GZIP_DECOMPRESSOR;
            case SNAPPY -> SNAPPY_DECOMPRESSOR;
            case LZ4_RAW -> LZ4_RAW_DECOMPRESSOR;
            case LZ4 -> LZ4_DECOMPRESSOR;
            case LZO -> LZO_DECOMPRESSOR;
            case BROTLI -> BROTLI_DECOMPRESSOR;
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

    /**
     * A decompressor of a pure-Java codec: both parquet-java entry points decode a byte array through
     * {@link #inflate}, which this class checks against the declared size and whose failures it reports as an
     * {@link IOException} naming the codec.
     */
    private abstract static class PureJavaDecompressor implements BytesInputDecompressor {

        private final CompressionCodecName codec;

        PureJavaDecompressor(CompressionCodecName codec) {
            this.codec = codec;
        }

        /**
         * Decompresses {@code compressed} into {@code out}, whose length is the declared size, and returns the
         * number of bytes the page holds, or {@link #MORE} for a stream that holds more than {@code out.length}.
         */
        abstract long inflate(byte[] compressed, byte[] out) throws IOException;

        @Override
        public BytesInput decompress(BytesInput bytes, int decompressedSize) throws IOException {
            return BytesInput.from(decode(bytes.toByteArray(), decompressedSize));
        }

        @Override
        public void decompress(ByteBuffer input, int compressedSize, ByteBuffer output, int decompressedSize)
                throws IOException {
            byte[] compressed = new byte[compressedSize];
            input.get(compressed);
            output.put(decode(compressed, decompressedSize));
        }

        @Override
        public void release() {
        }

        private byte[] decode(byte[] compressed, int decompressedSize) throws IOException {
            byte[] out = new byte[decompressedSize];
            long n;
            try {
                n = inflate(compressed, out);
            } catch (IOException | RuntimeException e) {
                throw new IOException(codec + " decompression failed: " + e, e);
            }
            if (n != decompressedSize) {
                throw new IOException(codec + " decompressed size mismatch: got "
                        + (n == MORE ? "more" : String.valueOf(n)) + ", expected " + decompressedSize);
            }
            return out;
        }
    }

    /** What {@link PureJavaDecompressor#inflate} returns for a stream longer than the declared size. */
    private static final long MORE = Long.MAX_VALUE;

    /**
     * Reads {@code in} into {@code out} and returns the bytes read, or {@link #MORE} if the stream holds more
     * (which is not read: a corrupt page could inflate without bound). Closes {@code in}.
     */
    private static long readStream(InputStream in, byte[] out) throws IOException {
        try (in) {
            int n = in.readNBytes(out, 0, out.length);
            return n < out.length || in.read() < 0 ? n : MORE;
        }
    }

    /**
     * Whether {@code page} starts like a Hadoop block of a page of {@code decompressedSize} bytes: at least the
     * two 4-byte big-endian lengths, a block length of at most the page size and a first chunk length of at most
     * the bytes that follow. Hadoop's streams allocate their buffers from these lengths, so a page that fails the
     * check never reaches them.
     */
    private static boolean hadoopFramed(byte[] page, int decompressedSize) {
        if (page.length < 8) {
            return false;
        }
        ByteBuffer header = ByteBuffer.wrap(page);
        return Integer.compareUnsigned(header.getInt(0), decompressedSize) <= 0
                && Integer.compareUnsigned(header.getInt(4), page.length - 8) <= 0;
    }

    private static final BytesInputDecompressor GZIP_DECOMPRESSOR =
            new PureJavaDecompressor(CompressionCodecName.GZIP) {
                @Override
                long inflate(byte[] compressed, byte[] out) throws IOException {
                    return readStream(new GZIPInputStream(new ByteArrayInputStream(compressed)), out);
                }
            };

    private static final BytesInputDecompressor SNAPPY_DECOMPRESSOR =
            new PureJavaDecompressor(CompressionCodecName.SNAPPY) {
                @Override
                long inflate(byte[] compressed, byte[] out) {
                    int declared = SnappyDecompressor.getUncompressedLength(compressed, 0);
                    return declared != out.length ? declared
                            : new SnappyDecompressor().decompress(compressed, 0, compressed.length, out, 0, out.length);
                }
            };

    private static final BytesInputDecompressor LZ4_RAW_DECOMPRESSOR =
            new PureJavaDecompressor(CompressionCodecName.LZ4_RAW) {
                @Override
                long inflate(byte[] compressed, byte[] out) {
                    return new Lz4Decompressor().decompress(compressed, 0, compressed.length, out, 0, out.length);
                }
            };

    private static final BytesInputDecompressor LZ4_DECOMPRESSOR =
            new PureJavaDecompressor(CompressionCodecName.LZ4) {
                @Override
                long inflate(byte[] compressed, byte[] out) {
                    if (hadoopFramed(compressed, out.length)) {
                        try {
                            long n = readStream(new Lz4HadoopStreams(Math.max(out.length, 1))
                                    .createInputStream(new ByteArrayInputStream(compressed)), out);
                            if (n == out.length) {
                                return n;
                            }
                        } catch (IOException | RuntimeException notHadoopFramed) {
                            // decoded as a raw block below
                        }
                    }
                    return new Lz4Decompressor().decompress(compressed, 0, compressed.length, out, 0, out.length);
                }
            };

    private static final BytesInputDecompressor LZO_DECOMPRESSOR =
            new PureJavaDecompressor(CompressionCodecName.LZO) {
                @Override
                long inflate(byte[] compressed, byte[] out) throws IOException {
                    if (!hadoopFramed(compressed, out.length)) {
                        throw new IOException("the page does not start with a Hadoop block header");
                    }
                    return readStream(new LzoHadoopStreams(Math.max(out.length, 1))
                            .createInputStream(new ByteArrayInputStream(compressed)), out);
                }
            };

    private static final BytesInputDecompressor BROTLI_DECOMPRESSOR =
            new PureJavaDecompressor(CompressionCodecName.BROTLI) {
                @Override
                long inflate(byte[] compressed, byte[] out) throws IOException {
                    return readStream(new BrotliInputStream(new ByteArrayInputStream(compressed)), out);
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

    /** {@code Zstd.decompressByteArray} throws {@code ZstdException} on a decoding error; a short frame remains. */
    private static void checkSize(long actual, int expected) throws IOException {
        if (actual != expected) {
            throw new IOException("ZSTD decompressed size mismatch: got " + actual + ", expected " + expected);
        }
    }
}
