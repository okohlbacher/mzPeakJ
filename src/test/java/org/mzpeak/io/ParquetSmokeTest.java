package org.mzpeak.io;

import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.io.RecordReader;
import org.apache.parquet.schema.MessageType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * De-risking smoke test: proves we can open an mzPeak point-data Parquet file Hadoop-free
 * (LocalInputFile + ParquetReadOptions + the parquet-column Group API) and that the ZSTD
 * codec decodes. Golden values were extracted independently with pyarrow.
 */
class ParquetSmokeTest {

    private static final Path DATA =
            Path.of("src/test/resources/mzpeak/small.unpacked.mzpeak/spectra_data.parquet");

    @Test
    void readsPointDataHadoopFree() throws Exception {
        long total = 0;
        long spec0Count = 0;
        double firstMz = Double.NaN;
        double firstIntensity = Double.NaN;

        try (ParquetFileReader reader =
                     ParquetFileReader.open(new LocalInputFile(DATA),
                             ParquetReadOptions.builder(new PlainParquetConfiguration())
                                     .withCodecFactory(new org.mzpeak.io.parquet.ZstdCompressionCodecFactory())
                                     .build())) {
            MessageType schema = reader.getFooter().getFileMetaData().getSchema();
            ColumnIOFactory factory = new ColumnIOFactory();
            PageReadStore pages;
            while ((pages = reader.readNextRowGroup()) != null) {
                MessageColumnIO columnIO = factory.getColumnIO(schema);
                RecordReader<Group> rr = columnIO.getRecordReader(pages, new GroupRecordConverter(schema));
                long rows = pages.getRowCount();
                for (long i = 0; i < rows; i++) {
                    Group point = rr.read().getGroup("point", 0);
                    long idx = point.getLong("spectrum_index", 0);
                    if (idx == 0) {
                        if (spec0Count == 0) {
                            firstMz = point.getDouble("mz", 0);
                            firstIntensity = point.getFloat("intensity", 0);
                        }
                        spec0Count++;
                    }
                    total++;
                }
            }
        }

        assertThat(total).isEqualTo(217710L);
        assertThat(spec0Count).isEqualTo(13589L);
        assertThat(firstMz).isCloseTo(202.606575, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(firstIntensity).isEqualTo(0.0); // first point of spectrum 0 is a zero-intensity flank
    }
}
