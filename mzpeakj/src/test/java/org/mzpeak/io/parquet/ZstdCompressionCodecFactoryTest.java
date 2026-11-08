package org.mzpeak.io.parquet;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdException;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.bytes.DirectByteBufferAllocator;
import org.apache.parquet.column.page.PageReadStore;
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
import org.mzpeak.io.Fixtures;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The codecs of {@link ZstdCompressionCodecFactory}: ZSTD and UNCOMPRESSED through parquet-java on a member of the
 * current {@code small.unpacked.mzpeak} (ZSTD as stored, UNCOMPRESSED as rewritten), read through heap and through
 * direct buffers, which take the two {@code decompress} methods; and the codecs on their own for what parquet-java
 * never asks of them (short frames, corrupt frames, unsupported codecs).
 */
class ZstdCompressionCodecFactoryTest {

    private static final Path MEMBER = Fixtures.current("small.unpacked.mzpeak").resolve("spectra_metadata.parquet");

    private static final ZstdCompressionCodecFactory FACTORY = new ZstdCompressionCodecFactory();

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

    /** The codecs of every column chunk of {@code input}. */
    private static List<CompressionCodecName> codecs(InputFile input) throws IOException {
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
     * Parquet read options that decompress into direct buffers, which parquet-java hands to the ByteBuffer method. The
     * input must fill direct buffers, which {@link LocalInputFile} does not, so these reads go through
     * {@link ByteArrayInputFile}.
     */
    private static ParquetReadOptions directOptions() {
        return ParquetReadOptions.builder(new PlainParquetConfiguration())
                .withCodecFactory(FACTORY)
                .withAllocator(new DirectByteBufferAllocator())
                .useOffHeapDecryptBuffer(true)
                .build();
    }

    /** Every record of {@code input} read with {@code options}, rendered as text. */
    private static List<String> read(InputFile input, ParquetReadOptions options) throws IOException {
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

    /** The member's records rewritten through the factory with {@code codec}. */
    private static Path rewrite(Path dir, CompressionCodecName codec) throws IOException {
        MessageType schema;
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(MEMBER),
                ParquetGroups.readOptions())) {
            schema = reader.getFooter().getFileMetaData().getSchema();
        }
        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(MEMBER, rows::add);
        Path out = dir.resolve(codec + ".parquet");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(out))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withCodecFactory(FACTORY)
                .withCompressionCodec(codec)
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

    @Test
    void zstdMemberReadsTheSameThroughDirectBuffers() throws IOException {
        assertThat(read(new ByteArrayInputFile(file), directOptions())).isEqualTo(records);
    }

    @Test
    void uncompressedRewriteReadsBackTheSameRecords(@TempDir Path dir) throws IOException {
        Path rewritten = rewrite(dir, CompressionCodecName.UNCOMPRESSED);
        LocalInputFile input = new LocalInputFile(rewritten);

        assertThat(codecs(input)).containsExactly(CompressionCodecName.UNCOMPRESSED);
        List<String> heap = new ArrayList<>();
        ParquetGroups.forEach(rewritten, row -> heap.add(row.toString()));
        assertThat(heap).isEqualTo(records);
        assertThat(read(new ByteArrayInputFile(Files.readAllBytes(rewritten)), directOptions())).isEqualTo(records);
    }

    @Test
    void zstdRewriteReadsBackTheSameRecords(@TempDir Path dir) throws IOException {
        Path rewritten = rewrite(dir, CompressionCodecName.ZSTD);
        LocalInputFile input = new LocalInputFile(rewritten);

        assertThat(codecs(input)).containsExactly(CompressionCodecName.ZSTD);
        assertThat(read(input, ParquetGroups.readOptions())).isEqualTo(records);
        assertThat(read(new ByteArrayInputFile(Files.readAllBytes(rewritten)), directOptions())).isEqualTo(records);
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
        BytesInputDecompressor none = FACTORY.getDecompressor(CompressionCodecName.UNCOMPRESSED);
        BytesInputDecompressor zstdIn = FACTORY.getDecompressor(CompressionCodecName.ZSTD);
        BytesInputCompressor noneOut = FACTORY.getCompressor(CompressionCodecName.UNCOMPRESSED);
        BytesInputCompressor zstdOut = FACTORY.getCompressor(CompressionCodecName.ZSTD);
        none.release();
        zstdIn.release();
        noneOut.release();
        zstdOut.release();
        FACTORY.release();

        BytesInput payload = BytesInput.from(bytes(0, 512));
        byte[] compressed = zstdOut.compress(payload).toByteArray();
        assertThat(zstdIn.decompress(BytesInput.from(compressed), 512).toByteArray()).isEqualTo(bytes(0, 512));
        assertThat(none.decompress(noneOut.compress(payload), 512)).isSameAs(payload);
        assertThat(read(new LocalInputFile(MEMBER), ParquetGroups.readOptions())).isEqualTo(records);
    }

    @ParameterizedTest
    @EnumSource(value = CompressionCodecName.class, names = {"UNCOMPRESSED", "ZSTD"},
            mode = EnumSource.Mode.EXCLUDE)
    void otherCodecsAreRefused(CompressionCodecName codec) {
        assertThatThrownBy(() -> FACTORY.getDecompressor(codec))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("mzPeakJ reader does not support Parquet codec " + codec + " yet");
        assertThatThrownBy(() -> FACTORY.getCompressor(codec))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("mzPeakJ does not support writing Parquet codec " + codec);
    }
}
