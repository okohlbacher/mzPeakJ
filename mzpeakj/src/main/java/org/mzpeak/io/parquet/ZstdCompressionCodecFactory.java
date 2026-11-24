package org.mzpeak.io.parquet;

/**
 * The Hadoop-free codec factory under its name before 0.13.0, when it read and wrote only {@code UNCOMPRESSED} and
 * {@code ZSTD}. It is now {@link PureJavaCodecFactory}, with the same behaviour: every Parquet codec is read,
 * {@code UNCOMPRESSED} and {@code ZSTD} are written.
 *
 * @deprecated use {@link PureJavaCodecFactory}
 */
@Deprecated(since = "0.13.0")
public final class ZstdCompressionCodecFactory extends PureJavaCodecFactory {

    /** Creates the factory; it holds no state. */
    public ZstdCompressionCodecFactory() {
    }
}
