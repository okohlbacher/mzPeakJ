package org.mzpeak.io;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GroupWriterBuilder} and {@link CountingGroupWriteSupport} (design §7, MZ-10): a writer built by the builder
 * writes through the caller's write support, which reads the schema from the configuration and adds the counts set
 * before the writer closes to the footer key-values given when it is built. The builder's Hadoop-typed
 * {@code getWriteSupport}, which the Hadoop-free build never calls, refuses.
 */
class GroupWriterBuilderTest {

    private static final MessageType SCHEMA = MessageTypeParser.parseMessageType(
            "message data { required int64 spectrum_index; optional double mz; }");

    @Test
    void theHadoopWriteSupportIsRefused() {
        GroupWriterBuilder builder = new GroupWriterBuilder(new LocalOutputFile(Path.of("unused.parquet")),
                new CountingGroupWriteSupport());
        assertThatThrownBy(() -> builder.getWriteSupport((Configuration) null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("mzPeakJ writes Parquet without a Hadoop Configuration");
    }

    @Test
    void theParquetConfigurationGetsTheCallersWriteSupport() {
        CountingGroupWriteSupport support = new CountingGroupWriteSupport();
        GroupWriterBuilder builder = new GroupWriterBuilder(new LocalOutputFile(Path.of("unused.parquet")), support);
        assertThat(builder.getWriteSupport(new PlainParquetConfiguration())).isSameAs(support);
        assertThat(builder.self()).isSameAs(builder);
    }

    @Test
    void finalizeWriteReturnsExactlyTheCountsSet() {
        CountingGroupWriteSupport support = new CountingGroupWriteSupport();
        assertThat(support.finalizeWrite().getExtraMetaData()).isEmpty();
        support.setCount("spectrum_count", 3);
        support.setCount("spectrum_data_point_count", 7);
        support.setCount("spectrum_count", 4);
        assertThat(support.finalizeWrite().getExtraMetaData())
                .containsExactly(Map.entry("spectrum_count", "4"), Map.entry("spectrum_data_point_count", "7"));
        assertThat(support.getName()).isEqualTo("example");
    }

    @Test
    void theFooterCarriesTheBuildTimeAndTheFinalizeTimeKeys(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("data.parquet");
        CountingGroupWriteSupport support = new CountingGroupWriteSupport();
        PlainParquetConfiguration conf = new PlainParquetConfiguration();
        conf.set("parquet.example.schema", SCHEMA.toString());
        SimpleGroupFactory rows = new SimpleGroupFactory(SCHEMA);
        try (ParquetWriter<Group> writer = new GroupWriterBuilder(new LocalOutputFile(file), support)
                .withConf(conf)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withExtraMetaData(Map.of("spectrum_array_index", "{}"))
                .build()) {
            writer.write(rows.newGroup().append("spectrum_index", 0L).append("mz", 100.5));
            writer.write(rows.newGroup().append("spectrum_index", 1L));
            // Set after the last row, before close: the key-values are only fixed when the writer closes.
            support.setCount("spectrum_count", 2);
            support.setCount("spectrum_data_point_count", 2);
        }

        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(file),
                ParquetGroups.readOptions())) {
            assertThat(reader.getFooter().getFileMetaData().getSchema()).isEqualTo(SCHEMA);
            assertThat(reader.getFooter().getFileMetaData().getKeyValueMetaData()).isEqualTo(Map.of(
                    "spectrum_array_index", "{}",
                    "spectrum_count", "2",
                    "spectrum_data_point_count", "2",
                    "writer.model.name", "example"));
            assertThat(reader.getRecordCount()).isEqualTo(2);
        }
        List<String> records = new ArrayList<>();
        ParquetGroups.forEach(new LocalInputFile(file), g -> records.add(g.getLong("spectrum_index", 0) + ":"
                + g.getFieldRepetitionCount("mz")));
        assertThat(records).containsExactly("0:1", "1:0");
    }
}
