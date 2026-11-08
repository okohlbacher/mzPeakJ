package org.mzpeak.io;

import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.conf.ParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.api.WriteSupport;
import org.apache.parquet.io.OutputFile;

/**
 * The builder of the {@link Group} writers {@link ParquetOutput} opens (design §7, MZ-10): a writer built by it writes
 * through the caller's {@link CountingGroupWriteSupport}, so that the caller can set footer counts until the writer
 * closes. parquet-java's ExampleParquetWriter.Builder, whose constructors are private, cannot be given one.
 */
final class GroupWriterBuilder extends ParquetWriter.Builder<Group, GroupWriterBuilder> {

    private final CountingGroupWriteSupport writeSupport;

    /** A builder of a writer of {@code file} through {@code writeSupport}. */
    GroupWriterBuilder(OutputFile file, CountingGroupWriteSupport writeSupport) {
        super(file);
        this.writeSupport = writeSupport;
    }

    @Override
    protected GroupWriterBuilder self() {
        return this;
    }

    /** The caller's write support: {@link #build()} asks for it with the builder's {@link ParquetConfiguration}. */
    @Override
    protected WriteSupport<Group> getWriteSupport(ParquetConfiguration conf) {
        return writeSupport;
    }

    /**
     * Never called: {@link #build()} passes a {@link ParquetConfiguration}, and mzPeakJ writes without a Hadoop
     * {@link Configuration}.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    protected WriteSupport<Group> getWriteSupport(Configuration conf) {
        throw new UnsupportedOperationException("mzPeakJ writes Parquet without a Hadoop Configuration");
    }
}
