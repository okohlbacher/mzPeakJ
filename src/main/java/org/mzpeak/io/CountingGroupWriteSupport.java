package org.mzpeak.io;

import org.apache.parquet.hadoop.example.GroupWriteSupport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@link GroupWriteSupport} of the writers {@link ParquetOutput} opens, which adds footer key-values when the
 * writer closes (design §7, MZ-10): the counts set with {@link #setCount}, through {@link #finalizeWrite()}, so that a
 * signal file gets {@code <entity>_count} and {@code <entity>_data_point_count} once its last row is written.
 * parquet-java merges them with the key-values given when the writer is built. The schema is read from the
 * configuration, as {@link GroupWriteSupport} reads it.
 */
final class CountingGroupWriteSupport extends GroupWriteSupport {

    private final Map<String, String> counts = new LinkedHashMap<>();

    /** A write support without counts, whose schema is the configuration's {@code parquet.example.schema}. */
    public CountingGroupWriteSupport() {
    }

    /** Sets the footer key-value {@code key} to {@code count}, replacing a count set before for that key. */
    void setCount(String key, long count) {
        counts.put(key, Long.toString(count));
    }

    /** The counts set so far, as the footer key-values added when the writer closes. */
    @Override
    public FinalizedWriteContext finalizeWrite() {
        return new FinalizedWriteContext(new LinkedHashMap<>(counts));
    }
}
