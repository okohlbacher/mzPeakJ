package org.mzpeak.io.parquet;

import io.airlift.compress.Compressor;
import io.airlift.compress.hadoop.HadoopOutputStream;
import io.airlift.compress.lz4.Lz4Compressor;
import io.airlift.compress.lz4.Lz4HadoopStreams;
import io.airlift.compress.lzo.LzoHadoopStreams;
import io.airlift.compress.snappy.SnappyCompressor;
import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.UnaryOperator;
import java.util.zip.GZIPOutputStream;

/**
 * Test-scope compressors for the codecs {@link PureJavaCodecFactory} reads but does not write, as a write-only codec
 * factory for parquet-java's writer: each variant names the codec written into the column chunks and the function
 * that compresses one page. aircompressor and {@code java.util.zip} compress, independently of the decompressors under
 * test; the Hadoop block framing of {@link #lz4HadoopChunks} is put together here because no Java writer makes a
 * block of several chunks. BROTLI has no Java compressor (see {@code mzpeak/crafted/README.md}).
 */
public record TestCodecs(String name, CompressionCodecName codec, UnaryOperator<byte[]> compress)
        implements CompressionCodecFactory {

    /** SNAPPY: one raw Snappy block per page, as parquet-java and Arrow write it. */
    public static TestCodecs snappy() {
        return new TestCodecs("snappy", CompressionCodecName.SNAPPY, page -> raw(new SnappyCompressor(), page));
    }

    /** LZ4_RAW: one raw LZ4 block per page. */
    public static TestCodecs lz4Raw() {
        return new TestCodecs("lz4-raw", CompressionCodecName.LZ4_RAW, page -> raw(new Lz4Compressor(), page));
    }

    /**
     * LZ4 with Hadoop's framing through aircompressor's stream with a {@code bufferSize}-byte buffer: a page longer
     * than the buffer becomes several blocks.
     */
    public static TestCodecs lz4Hadoop(int bufferSize) {
        return new TestCodecs("lz4-hadoop-" + bufferSize, CompressionCodecName.LZ4,
                page -> stream(new Lz4HadoopStreams(bufferSize)::createOutputStream, page));
    }

    /**
     * LZ4 with Hadoop's framing, one block per page that holds several compressed chunks of at most
     * {@code chunkSize} input bytes each, as Hadoop's {@code BlockCompressorStream} writes a block whose input
     * exceeds the compressor's buffer: the block's uncompressed length, then per chunk its compressed length and
     * the chunk (each length a 4-byte big-endian integer).
     */
    public static TestCodecs lz4HadoopChunks(int chunkSize) {
        return new TestCodecs("lz4-hadoop-chunks-" + chunkSize, CompressionCodecName.LZ4, page -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeInt(out, page.length);
            for (int from = 0; from < page.length; from += chunkSize) {
                byte[] chunk = raw(new Lz4Compressor(), Arrays.copyOfRange(page, from,
                        Math.min(page.length, from + chunkSize)));
                writeInt(out, chunk.length);
                out.writeBytes(chunk);
            }
            return out.toByteArray();
        });
    }

    /** LZ4 as Arrow C++ wrote it before it adopted Hadoop's framing: one raw LZ4 block per page. */
    public static TestCodecs lz4Unframed() {
        return new TestCodecs("lz4-unframed", CompressionCodecName.LZ4, page -> raw(new Lz4Compressor(), page));
    }

    /** LZO with Hadoop's framing, through aircompressor's stream. */
    public static TestCodecs lzo() {
        return new TestCodecs("lzo", CompressionCodecName.LZO,
                page -> stream(new LzoHadoopStreams()::createOutputStream, page));
    }

    /** GZIP: one gzip member per page. */
    public static TestCodecs gzip() {
        return new TestCodecs("gzip", CompressionCodecName.GZIP, TestCodecs::gzip);
    }

    /** GZIP with every page split into two concatenated gzip members (RFC 1952 allows several). */
    public static TestCodecs gzipMembers() {
        return new TestCodecs("gzip-members", CompressionCodecName.GZIP, page -> {
            int half = page.length / 2;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(gzip(Arrays.copyOfRange(page, 0, half)));
            out.writeBytes(gzip(Arrays.copyOfRange(page, half, page.length)));
            return out.toByteArray();
        });
    }

    /** {@code page} gzip-compressed. */
    public static byte[] gzip(byte[] page) {
        return stream(GZIPOutputStream::new, page);
    }

    /** {@code page} compressed as one raw block of {@code compressor}. */
    public static byte[] raw(Compressor compressor, byte[] page) {
        byte[] out = new byte[compressor.maxCompressedLength(page.length)];
        int n = compressor.compress(page, 0, page.length, out, 0, out.length);
        return Arrays.copyOf(out, n);
    }

    /** {@code page} written through the compressing stream that {@code streams} wraps around the output. */
    public static byte[] stream(StreamFactory streams, byte[] page) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream compressing = streams.wrap(out)) {
            compressing.write(page);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** A compressing stream around an output stream ({@link HadoopOutputStream} or {@link GZIPOutputStream}). */
    @FunctionalInterface
    public interface StreamFactory {
        OutputStream wrap(OutputStream out) throws IOException;
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.writeBytes(ByteBuffer.allocate(4).putInt(value).array());
    }

    @Override
    public BytesInputCompressor getCompressor(CompressionCodecName codecName) {
        if (codecName != codec) {
            throw new IllegalArgumentException(name + " writes " + codec + ", not " + codecName);
        }
        return new BytesInputCompressor() {
            @Override
            public BytesInput compress(BytesInput bytes) throws IOException {
                return BytesInput.from(compress.apply(bytes.toByteArray()));
            }

            @Override
            public CompressionCodecName getCodecName() {
                return codec;
            }

            @Override
            public void release() {
            }
        };
    }

    @Override
    public BytesInputDecompressor getDecompressor(CompressionCodecName codecName) {
        throw new UnsupportedOperationException(name + " only writes");
    }

    @Override
    public void release() {
    }

    @Override
    public String toString() {
        return name;
    }
}
