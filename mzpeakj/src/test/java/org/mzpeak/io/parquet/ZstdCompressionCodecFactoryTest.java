package org.mzpeak.io.parquet;

import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.Fixtures;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deprecated {@link ZstdCompressionCodecFactory} (X-1) is a {@link PureJavaCodecFactory}: it reads a SNAPPY
 * member, which it refused before 0.13.0, and still writes only UNCOMPRESSED and ZSTD.
 */
@SuppressWarnings("deprecation")
class ZstdCompressionCodecFactoryTest {

    private static final Path MEMBER = Fixtures.current("small.unpacked.mzpeak").resolve("spectra_metadata.parquet");

    @Test
    void readsTheSnappyCopyOfAMember(@TempDir Path dir) throws IOException {
        ZstdCompressionCodecFactory factory = new ZstdCompressionCodecFactory();
        Path snappy = PureJavaCodecFactoryTest.rewrite(dir, TestCodecs.snappy(), CompressionCodecName.SNAPPY);
        List<String> expected = new ArrayList<>();
        ParquetGroups.forEach(MEMBER, row -> expected.add(row.toString()));

        assertThat(PureJavaCodecFactoryTest.codecs(new LocalInputFile(snappy)))
                .containsExactly(CompressionCodecName.SNAPPY);
        ParquetReadOptions options = ParquetReadOptions.builder(new PlainParquetConfiguration())
                .withCodecFactory(factory)
                .build();
        assertThat(PureJavaCodecFactoryTest.read(new LocalInputFile(snappy), options))
                .hasSize(48)
                .isEqualTo(expected);
    }

    @Test
    void writesTheCodecsItWroteBefore() {
        ZstdCompressionCodecFactory factory = new ZstdCompressionCodecFactory();

        assertThat(factory).isInstanceOf(PureJavaCodecFactory.class);
        assertThat(factory.getCompressor(CompressionCodecName.ZSTD).getCodecName())
                .isEqualTo(CompressionCodecName.ZSTD);
        assertThat(factory.getCompressor(CompressionCodecName.UNCOMPRESSED).getCodecName())
                .isEqualTo(CompressionCodecName.UNCOMPRESSED);
        assertThatThrownBy(() -> factory.getCompressor(CompressionCodecName.SNAPPY))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("mzPeakJ does not support writing Parquet codec SNAPPY");
    }
}
