package org.mzpeak.io;

import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.FileMetaData;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;
import org.mzpeak.io.parquet.ParquetGroups;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The footer of one Parquet member: its schema, total row count and key-value metadata, read with a single open.
 */
record ParquetFooter(MessageType schema, long rowCount, Map<String, String> keyValue) {

    ParquetFooter {
        keyValue = Collections.unmodifiableMap(new LinkedHashMap<>(keyValue));
    }

    /** Read the footer of {@code file} (Hadoop-free, through {@link ParquetGroups#readOptions()}). */
    static ParquetFooter read(InputFile file) {
        try (ParquetFileReader reader = ParquetFileReader.open(file, ParquetGroups.readOptions())) {
            FileMetaData meta = reader.getFooter().getFileMetaData();
            return new ParquetFooter(meta.getSchema(), reader.getRecordCount(), meta.getKeyValueMetaData());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading Parquet footer", e);
        }
    }

    /** Names of the schema's top-level fields, in schema order. */
    List<String> topLevelFields() {
        return schema.getFields().stream().map(Type::getName).toList();
    }
}
