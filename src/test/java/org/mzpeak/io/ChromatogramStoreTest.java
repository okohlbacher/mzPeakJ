package org.mzpeak.io;

import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How {@link ChromatogramStore} names the metadata member it reads: by its file name when the manifest lists it, by
 * its role when it is given directly as an {@code InputFile}. The members are has_uv's, with the metadata member
 * replaced by one written with parquet-java into a {@link TempDir} that has rows but neither metadata shape, since no
 * fixture has such a member.
 */
class ChromatogramStoreTest {

    private static final String HAS_UV = "has_uv.mzpeak";
    private static final String METADATA = "chromatograms_metadata.parquet";
    private static final String DATA = "chromatograms_data.parquet";
    private static final String NEITHER_SHAPE = " has 1 rows but neither the packed shape (top-level struct"
            + " 'chromatogram') nor the split shape (top-level column 'index'); columns found: [note]";

    @TempDir
    Path tmp;

    @Test
    void aMetadataMemberListedByTheManifestIsNamedByItsFileName() throws IOException {
        MzPeakSource source = MzPeakReaderEdgeCasesTest.edited(Fixtures.current(HAS_UV), index -> { },
                Map.of(METADATA, Files.readAllBytes(neitherShape())));
        try (MzPeakReader reader = MzPeakReader.open(source)) {
            assertThatThrownBy(reader::chromatograms)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(METADATA + NEITHER_SHAPE);
        }
    }

    @Test
    void aMetadataMemberGivenDirectlyIsNamedByItsRole() throws IOException {
        Path metadata = neitherShape();
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current(HAS_UV))) {
            assertThatThrownBy(() -> ChromatogramStore.load(new LocalInputFile(metadata), source.inputFile(DATA)))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("chromatogram metadata member" + NEITHER_SHAPE);
        }
    }

    /** A member with one row and only the column {@code note}: neither the packed nor the split shape. */
    private Path neitherShape() throws IOException {
        MessageType schema = MessageTypeParser.parseMessageType("message m { optional int64 note; }");
        Path file = tmp.resolve(METADATA);
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            writer.write(new SimpleGroupFactory(schema).newGroup().append("note", 1L));
        }
        return file;
    }
}
