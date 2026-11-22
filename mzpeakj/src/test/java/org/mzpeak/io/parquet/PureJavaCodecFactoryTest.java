package org.mzpeak.io.parquet;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdException;
import io.airlift.compress.lz4.Lz4Compressor;
import io.airlift.compress.lzo.LzoCompressor;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.bytes.DirectByteBufferAllocator;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.compression.CompressionCodecFactory.BytesInputCompressor;
import org.apache.parquet.compression.CompressionCodecFactory.BytesInputDecompressor;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.schema.MessageType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.Fixtures;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The codecs of {@link PureJavaCodecFactory} through parquet-java on a member of the current
 * {@code small.unpacked.mzpeak} (ZSTD as stored, every other codec as rewritten, BROTLI from the committed crafted
 * member), read through heap and through direct buffers, which take the two {@code decompress} methods; and the
 * codecs on their own for what parquet-java never asks of them (short and long pages, corrupt pages, the Hadoop
 * framing checks, unsupported compressors). The test-scope compressors are {@link TestCodecs}.
 */
class PureJavaCodecFactoryTest {

    private static final Path MEMBER = Fixtures.current("small.unpacked.mzpeak").resolve("spectra_metadata.parquet");

    private static final Path BROTLI_MEMBER = Fixtures.CURRENT.resolve("crafted")
            .resolve("small.spectra_metadata.brotli.parquet");

    private static final PureJavaCodecFactory FACTORY = new PureJavaCodecFactory();

    /** The uncompressed length of the pages of the codec tests. */
    private static final int PAGE = 4096;

    private static byte[] file;
    private static List<String> records;

    @BeforeAll
    static void readMember() throws IOException {
        file = Files.readAllBytes(MEMBER);
        records = new ArrayList<>();
        ParquetGroups.forEach(MEMBER, row -> records.add(row.toString()));
        assertThat(records).isNotEmpty();
        assertThat(codecs(new LocalInputFile(MEMBER))).containsExactly(CompressionCodecName.ZSTD);
    }

    static Stream<TestCodecs> pureJavaCodecs() {
        return Stream.of(
                TestCodecs.snappy(),
                TestCodecs.lz4Raw(),
                TestCodecs.lz4Hadoop(1024),
                TestCodecs.lz4HadoopChunks(1000),
                TestCodecs.lz4Unframed(),
                TestCodecs.lzo(),
                TestCodecs.gzip(),
                TestCodecs.gzipMembers());
    }

    /** The codecs of every column chunk of {@code input}. */
    static List<CompressionCodecName> codecs(InputFile input) throws IOException {
        try (ParquetFileReader reader = ParquetFileReader.open(input, ParquetGroups.readOptions())) {
            return reader.getFooter().getBlocks().stream()
                    .map(BlockMetaData::getColumns)
                    .flatMap(List::stream)
                    .map(ColumnChunkMetaData::getCodec)
                    .distinct()
                    .toList();
        }
    }

    /**
     * Parquet read options with {@code factory} that decompress into direct buffers, which parquet-java hands to the
     * ByteBuffer method. The input must fill direct buffers, which {@link LocalInputFile} does not, so these reads
     * go through {@link ByteArrayInputFile}.
     */
    static ParquetReadOptions directOptions(CompressionCodecFactory factory) {
        return ParquetReadOptions.builder(new PlainParquetConfiguration())
                .withCodecFactory(factory)
                .withAllocator(new DirectByteBufferAllocator())
                .useOffHeapDecryptBuffer(true)
                .build();
    }

    /** Every record of {@code input} read with {@code options}, rendered as text. */
    static List<String> read(InputFile input, ParquetReadOptions options) throws IOException {
        List<String> out = new ArrayList<>();
        try (ParquetFileReader reader = ParquetFileReader.open(input, options)) {
            MessageType schema = reader.getFooter().getFileMetaData().getSchema();
            PageReadStore pages;
            while ((pages = reader.readNextRowGroup()) != null) {
                RecordReader<Group> rows = new ColumnIOFactory().getColumnIO(schema)
                        .getRecordReader(pages, new GroupRecordConverter(schema));
                for (long i = 0; i < pages.getRowCount(); i++) {
                    out.add(rows.read().toString());
                }
            }
        }
        return out;
    }

    /** The member's records rewritten with {@code codec} by {@code factory}, in small pages. */
    static Path rewrite(Path dir, CompressionCodecFactory factory, CompressionCodecName codec) throws IOException {
        MessageType schema;
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(MEMBER),
                ParquetGroups.readOptions())) {
            schema = reader.getFooter().getFileMetaData().getSchema();
        }
        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(MEMBER, rows::add);
        Path out = dir.resolve(factory + "." + codec + ".parquet");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(out))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withCodecFactory(factory)
                .withCompressionCodec(codec)
                .withPageSize(PAGE)
                .build()) {
            for (Group row : rows) {
                writer.write(row);
            }
        }
        return out;
    }

    private static byte[] bytes(int from, int to) {
        return Arrays.copyOfRange(file, from, to);
    }

    private static BytesInputDecompressor decompressor(TestCodecs codecs) {
        return FACTORY.getDecompressor(codecs.codec());
    }

    @Test
    void zstdMemberReadsTheSameThroughDirectBuffers() throws IOException {
        assertThat(read(new ByteArrayInputFile(file), directOptions(FACTORY))).isEqualTo(records);
    }

    @Test
    void uncompressedRewriteReadsBackTheSameRecords(@TempDir Path dir) throws IOException {
        Path rewritten = rewrite(dir, FACTORY, CompressionCodecName.UNCOMPRESSED);
        LocalInputFile input = new LocalInputFile(rewritten);

        assertThat(codecs(input)).containsExactly(CompressionCodecName.UNCOMPRESSED);
        List<String> heap = new ArrayList<>();
        ParquetGroups.forEach(rewritten, row -> heap.add(row.toString()));
        assertThat(heap).isEqualTo(records);
        assertThat(read(new ByteArrayInputFile(Files.readAllBytes(rewritten)), directOptions(FACTORY)))
                .isEqualTo(records);
    }

    @Test
    void zstdRewriteReadsBackTheSameRecords(@TempDir Path dir) throws IOException {
        Path rewritten = rewrite(dir, FACTORY, CompressionCodecName.ZSTD);
        LocalInputFile input = new LocalInputFile(rewritten);

        assertThat(codecs(input)).containsExactly(CompressionCodecName.ZSTD);
        assertThat(read(input, ParquetGroups.readOptions())).isEqualTo(records);
        assertThat(read(new ByteArrayInputFile(Files.readAllBytes(rewritten)), directOptions(FACTORY)))
                .isEqualTo(records);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pureJavaCodecs")
    void pureJavaRewriteReadsBackTheSameRecords(TestCodecs codecs, @TempDir Path dir) throws IOException {
        Path rewritten = rewrite(dir, codecs, codecs.codec());
        LocalInputFile input = new LocalInputFile(rewritten);

        assertThat(codecs(input)).containsExactly(codecs.codec());
        assertThat(read(input, ParquetGroups.readOptions())).isEqualTo(records);
        assertThat(read(new ByteArrayInputFile(Files.readAllBytes(rewritten)), directOptions(FACTORY)))
                .isEqualTo(records);
    }

    @Test
    void brotliMemberReadsTheSameRecordsBothWays() throws IOException {
        LocalInputFile input = new LocalInputFile(BROTLI_MEMBER);

        assertThat(codecs(input)).containsExactly(CompressionCodecName.BROTLI);
        assertThat(read(input, ParquetGroups.readOptions())).isEqualTo(records);
        assertThat(read(new ByteArrayInputFile(Files.readAllBytes(BROTLI_MEMBER)), directOptions(FACTORY)))
                .isEqualTo(records);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pureJavaCodecs")
    void pureJavaCodecsDecompressBothWays(TestCodecs codecs) throws IOException {
        BytesInputDecompressor decompressor = decompressor(codecs);
        byte[] compressed = codecs.compress().apply(bytes(0, PAGE));

        assertThat(decompressor.decompress(BytesInput.from(compressed), PAGE).toByteArray()).isEqualTo(bytes(0, PAGE));

        ByteBuffer input = ByteBuffer.allocate(compressed.length + 16);
        input.put(new byte[8]).put(compressed).put(new byte[8]).flip().position(8);
        ByteBuffer output = ByteBuffer.allocate(PAGE + 16);
        decompressor.decompress(input, compressed.length, output, PAGE);
        assertThat(input.position()).isEqualTo(8 + compressed.length);
        assertThat(output.position()).isEqualTo(PAGE);
        assertThat(Arrays.copyOfRange(output.array(), 0, PAGE)).isEqualTo(bytes(0, PAGE));
    }

    /** A page that holds fewer bytes than declared fails naming the codec, both ways. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("pureJavaCodecs")
    void aShortPageFailsNamingTheCodec(TestCodecs codecs) {
        BytesInputDecompressor decompressor = decompressor(codecs);
        byte[] compressed = codecs.compress().apply(bytes(0, PAGE));
        // an LZ4 page that does not fill the declared size through Hadoop's framing is retried as a raw block
        String message = codecs.name().startsWith("lz4-hadoop")
                ? "LZ4 decompression failed: io.airlift.compress.MalformedInputException"
                : codecs.codec() + " decompressed size mismatch: got " + PAGE + ", expected " + (PAGE + 1);

        assertThatThrownBy(() -> decompressor.decompress(BytesInput.from(compressed), PAGE + 1))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith(message);
        assertThatThrownBy(() -> decompressor.decompress(ByteBuffer.wrap(compressed), compressed.length,
                ByteBuffer.allocate(PAGE + 1), PAGE + 1))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith(message);
    }

    /** A page that holds more bytes than declared fails naming the codec. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("pureJavaCodecs")
    void aLongPageFailsNamingTheCodec(TestCodecs codecs) {
        BytesInputDecompressor decompressor = decompressor(codecs);
        byte[] compressed = codecs.compress().apply(bytes(0, PAGE));
        String message = switch (codecs.codec()) {
            // streams stop reading one byte past the declared size
            case GZIP -> "GZIP decompressed size mismatch: got more, expected " + (PAGE - 1);
            // the length the page declares
            case SNAPPY -> "SNAPPY decompressed size mismatch: got " + PAGE + ", expected " + (PAGE - 1);
            // the block length exceeds the declared size
            case LZO -> "LZO decompression failed: java.io.IOException: Hadoop block length " + PAGE
                    + " exceeds the " + (PAGE - 1) + " bytes left of the declared size";
            // raw blocks and Hadoop chunks do not fit the output buffer
            default -> codecs.codec() + " decompression failed: io.airlift.compress.MalformedInputException";
        };

        assertThatThrownBy(() -> decompressor.decompress(BytesInput.from(compressed), PAGE - 1))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith(message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pureJavaCodecs")
    void aCorruptPageFailsNamingTheCodec(TestCodecs codecs) {
        BytesInputDecompressor decompressor = decompressor(codecs);
        byte[] corrupt = codecs.compress().apply(bytes(0, PAGE));
        // keeps the GZIP header, Snappy's length and Hadoop's block and chunk lengths
        Arrays.fill(corrupt, 10, corrupt.length, (byte) 0xFF);

        assertThatThrownBy(() -> decompressor.decompress(BytesInput.from(corrupt), PAGE))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith(codecs.codec() + " decompression failed: ")
                .hasCauseInstanceOf(Exception.class);
        assertThatThrownBy(() -> decompressor.decompress(ByteBuffer.wrap(corrupt), corrupt.length,
                ByteBuffer.allocate(PAGE), PAGE))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith(codecs.codec() + " decompression failed: ");
    }

    @Test
    void brotliFailsNamingTheCodec() throws IOException {
        BytesInputDecompressor brotli = FACTORY.getDecompressor(CompressionCodecName.BROTLI);
        // the empty Brotli stream: one empty last meta-block
        byte[] empty = {0x06};

        assertThat(brotli.decompress(BytesInput.from(empty), 0).toByteArray()).isEmpty();
        assertThatThrownBy(() -> brotli.decompress(BytesInput.from(empty), 1))
                .isInstanceOf(IOException.class)
                .hasMessage("BROTLI decompressed size mismatch: got 0, expected 1");
        byte[] corrupt = new byte[64];
        Arrays.fill(corrupt, (byte) 0xFF);
        assertThatThrownBy(() -> brotli.decompress(BytesInput.from(corrupt), 64))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("BROTLI decompression failed: ");
    }

    /** An LZ4 page shorter than Hadoop's two lengths is a raw block. */
    @Test
    void aTinyLz4PageIsARawBlock() throws IOException {
        byte[] page = {'a', 'b', 'c'};
        byte[] compressed = TestCodecs.raw(new Lz4Compressor(), page);
        assertThat(compressed).hasSizeLessThan(8);

        assertThat(FACTORY.getDecompressor(CompressionCodecName.LZ4).decompress(BytesInput.from(compressed), 3)
                .toByteArray()).isEqualTo(page);
    }

    /**
     * An LZ4 page whose block length fits but whose first chunk length runs past the page is not Hadoop-framed;
     * as a raw block it is corrupt. An LZO page needs the framing.
     */
    @Test
    void framingHeadersOutsideThePageAreNotHadoopFramed() {
        // block length 4, chunk length 5 (4 bytes follow)
        byte[] page = {0, 0, 0, 4, 0, 0, 0, 5, 0, 0, 0, 0};
        byte[] tooShort = {0, 0, 0, 4, 0, 0, 0};
        // two empty blocks, then 3 bytes of the next block length
        byte[] cutLength = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] rawBlock = TestCodecs.raw(new Lz4Compressor(), bytes(0, PAGE));

        assertThatThrownBy(() -> FACTORY.getDecompressor(CompressionCodecName.LZ4)
                .decompress(BytesInput.from(page), 4))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("LZ4 decompression failed: io.airlift.compress.MalformedInputException");
        String lzo = "LZO decompression failed: java.io.IOException: ";
        assertLzoFails(page, 4, lzo + "Hadoop chunk length 5 exceeds the 4 bytes left of the page");
        assertLzoFails(tooShort, 4, lzo + "the page does not start with a Hadoop block header");
        assertLzoFails(cutLength, 4, lzo + "the page ends inside a Hadoop block length");
        assertLzoFails(rawBlock, 4, lzo + "Hadoop block length "
                + Integer.toUnsignedLong(ByteBuffer.wrap(rawBlock).getInt()) + " exceeds the 4 bytes left of the "
                + "declared size");
    }

    /**
     * A Hadoop-framed page whose first chunk is valid but whose second chunk length runs far past the page fails
     * naming the codec, both ways, without allocating from that length (aircompressor's Hadoop streams allocate a
     * buffer of the chunk length before they read the chunk). LZ4 retries the page as a raw block, which is corrupt.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = CompressionCodecName.class, names = {"LZ4", "LZO"})
    void anOversizedLaterChunkLengthFailsNamingTheCodec(CompressionCodecName codec) {
        BytesInputDecompressor decompressor = FACTORY.getDecompressor(codec);
        byte[] first = TestCodecs.raw(codec == CompressionCodecName.LZ4 ? new Lz4Compressor() : new LzoCompressor(),
                bytes(0, 1000));
        for (int length : new int[] {0x7FFFFFF0, 0xFFFFFFF0}) {
            byte[] page = ByteBuffer.allocate(16 + first.length)
                    .putInt(PAGE).putInt(first.length).put(first).putInt(length).putInt(0).array();
            String message = codec == CompressionCodecName.LZ4
                    ? "LZ4 decompression failed: io.airlift.compress.MalformedInputException"
                    : "LZO decompression failed: java.io.IOException: Hadoop chunk length "
                            + Integer.toUnsignedLong(length) + " exceeds the 4 bytes left of the page";

            assertThatThrownBy(() -> decompressor.decompress(BytesInput.from(page), PAGE))
                    .isInstanceOf(IOException.class)
                    .hasMessageStartingWith(message);
            assertThatThrownBy(() -> decompressor.decompress(ByteBuffer.wrap(page), page.length,
                    ByteBuffer.allocate(PAGE), PAGE))
                    .isInstanceOf(IOException.class)
                    .hasMessageStartingWith(message);
        }
    }

    private static void assertLzoFails(byte[] page, int decompressedSize, String message) {
        assertThatThrownBy(() -> FACTORY.getDecompressor(CompressionCodecName.LZO)
                .decompress(BytesInput.from(page), decompressedSize))
                .isInstanceOf(IOException.class)
                .hasMessage(message);
    }

    /** A Hadoop-framed LZ4 page whose chunk does not decode is retried as a raw block, which fails too. */
    @Test
    void aCorruptHadoopChunkFallsBackToARawBlock() {
        byte[] page = {0, 0, 0, 4, 0, 0, 0, 4, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

        assertThatThrownBy(() -> FACTORY.getDecompressor(CompressionCodecName.LZ4)
                .decompress(BytesInput.from(page), 4))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("LZ4 decompression failed: io.airlift.compress.MalformedInputException");
    }

    @Test
    void compressorsNameTheirCodec() throws IOException {
        BytesInputCompressor none = FACTORY.getCompressor(CompressionCodecName.UNCOMPRESSED);
        BytesInputCompressor zstd = FACTORY.getCompressor(CompressionCodecName.ZSTD);

        assertThat(none.getCodecName()).isEqualTo(CompressionCodecName.UNCOMPRESSED);
        assertThat(zstd.getCodecName()).isEqualTo(CompressionCodecName.ZSTD);
        BytesInput payload = BytesInput.from(bytes(0, 4096));
        assertThat(none.compress(payload)).isSameAs(payload);
        byte[] compressed = zstd.compress(BytesInput.from(bytes(0, 4096))).toByteArray();
        assertThat(Zstd.decompress(compressed, 4096)).isEqualTo(bytes(0, 4096));
    }

    @Test
    void passthroughReturnsTheInputAndCopiesExactlyTheCompressedSize() throws IOException {
        BytesInputDecompressor none = FACTORY.getDecompressor(CompressionCodecName.UNCOMPRESSED);
        BytesInput payload = BytesInput.from(bytes(0, 100));
        assertThat(none.decompress(payload, 100)).isSameAs(payload);

        ByteBuffer input = ByteBuffer.wrap(file, 0, 200).position(10);
        ByteBuffer output = ByteBuffer.allocate(64);
        none.decompress(input, 50, output, 50);
        assertThat(input.position()).isEqualTo(60);
        assertThat(input.limit()).isEqualTo(200);
        assertThat(output.position()).isEqualTo(50);
        assertThat(Arrays.copyOfRange(output.array(), 0, 50)).isEqualTo(bytes(10, 60));
        assertThat(output.array()[50]).isZero();
    }

    @Test
    void passthroughRestoresTheInputLimitWhenTheOutputOverflows() {
        BytesInputDecompressor none = FACTORY.getDecompressor(CompressionCodecName.UNCOMPRESSED);
        ByteBuffer input = ByteBuffer.wrap(file, 0, 200);
        ByteBuffer output = ByteBuffer.allocate(10);

        assertThatThrownBy(() -> none.decompress(input, 50, output, 50))
                .isInstanceOf(BufferOverflowException.class);
        assertThat(input.position()).isZero();
        assertThat(input.limit()).isEqualTo(200);
    }

    @Test
    void zstdDecompressesBothWays() throws IOException {
        BytesInputDecompressor zstd = FACTORY.getDecompressor(CompressionCodecName.ZSTD);
        byte[] compressed = Zstd.compress(bytes(0, 4096), 3);

        assertThat(zstd.decompress(BytesInput.from(compressed), 4096).toByteArray()).isEqualTo(bytes(0, 4096));

        ByteBuffer input = ByteBuffer.allocate(compressed.length + 8);
        input.put(compressed).put(new byte[8]).flip();
        ByteBuffer output = ByteBuffer.allocate(4096 + 16);
        zstd.decompress(input, compressed.length, output, 4096);
        assertThat(input.position()).isEqualTo(compressed.length);
        assertThat(output.position()).isEqualTo(4096);
        assertThat(Arrays.copyOfRange(output.array(), 0, 4096)).isEqualTo(bytes(0, 4096));
    }

    @Test
    void zstdRejectsAFrameShorterThanTheDeclaredSize() {
        BytesInputDecompressor zstd = FACTORY.getDecompressor(CompressionCodecName.ZSTD);
        byte[] compressed = Zstd.compress(bytes(0, 1000), 3);

        assertThatThrownBy(() -> zstd.decompress(BytesInput.from(compressed), 1001))
                .isInstanceOf(IOException.class)
                .hasMessage("ZSTD decompressed size mismatch: got 1000, expected 1001");
        assertThatThrownBy(() -> zstd.decompress(ByteBuffer.wrap(compressed), compressed.length,
                ByteBuffer.allocate(1001), 1001))
                .isInstanceOf(IOException.class)
                .hasMessage("ZSTD decompressed size mismatch: got 1000, expected 1001");
    }

    @Test
    void zstdFailsLoudlyOnACorruptFrame() {
        BytesInputDecompressor zstd = FACTORY.getDecompressor(CompressionCodecName.ZSTD);
        byte[] corrupt = Zstd.compress(bytes(0, 1000), 3);
        Arrays.fill(corrupt, 4, corrupt.length, (byte) 0x5A);

        assertThatThrownBy(() -> zstd.decompress(BytesInput.from(corrupt), 1000))
                .isInstanceOf(ZstdException.class);
        assertThatThrownBy(() -> zstd.decompress(ByteBuffer.wrap(corrupt), corrupt.length,
                ByteBuffer.allocate(1000), 1000))
                .isInstanceOf(ZstdException.class);
        // a frame that inflates past the declared size
        byte[] compressed = Zstd.compress(bytes(0, 1000), 3);
        assertThatThrownBy(() -> zstd.decompress(BytesInput.from(compressed), 999))
                .isInstanceOf(ZstdException.class);
    }

    @Test
    void releaseLeavesTheSharedCodecsUsable() throws IOException {
        List<BytesInputDecompressor> decompressors = Arrays.stream(CompressionCodecName.values())
                .map(FACTORY::getDecompressor).toList();
        BytesInputDecompressor none = FACTORY.getDecompressor(CompressionCodecName.UNCOMPRESSED);
        BytesInputDecompressor zstdIn = FACTORY.getDecompressor(CompressionCodecName.ZSTD);
        BytesInputCompressor noneOut = FACTORY.getCompressor(CompressionCodecName.UNCOMPRESSED);
        BytesInputCompressor zstdOut = FACTORY.getCompressor(CompressionCodecName.ZSTD);
        decompressors.forEach(BytesInputDecompressor::release);
        noneOut.release();
        zstdOut.release();
        FACTORY.release();

        BytesInput payload = BytesInput.from(bytes(0, 512));
        byte[] compressed = zstdOut.compress(payload).toByteArray();
        assertThat(zstdIn.decompress(BytesInput.from(compressed), 512).toByteArray()).isEqualTo(bytes(0, 512));
        assertThat(none.decompress(noneOut.compress(payload), 512)).isSameAs(payload);
        byte[] gzip = TestCodecs.gzip(bytes(0, 512));
        assertThat(FACTORY.getDecompressor(CompressionCodecName.GZIP).decompress(BytesInput.from(gzip), 512)
                .toByteArray()).isEqualTo(bytes(0, 512));
        assertThat(read(new LocalInputFile(MEMBER), ParquetGroups.readOptions())).isEqualTo(records);
    }

    @ParameterizedTest
    @EnumSource(value = CompressionCodecName.class, names = {"UNCOMPRESSED", "ZSTD"},
            mode = EnumSource.Mode.EXCLUDE)
    void otherCompressorsAreRefused(CompressionCodecName codec) {
        assertThat(FACTORY.getDecompressor(codec)).isNotNull();
        assertThatThrownBy(() -> FACTORY.getCompressor(codec))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("mzPeakJ does not support writing Parquet codec " + codec);
    }
}
