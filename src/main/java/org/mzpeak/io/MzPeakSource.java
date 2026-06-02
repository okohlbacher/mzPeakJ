package org.mzpeak.io;

import org.apache.parquet.io.InputFile;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Abstracts where an mzPeak dataset's member files live: an unpacked directory or a single-file
 * (STORED) {@code .mzpeak} ZIP archive. Members are addressed by their manifest name.
 */
public interface MzPeakSource extends AutoCloseable {

    /** Raw bytes of {@code mzpeak_index.json}. */
    byte[] readManifestBytes();

    /** True if a member with this name exists. */
    boolean has(String name);

    /** A parquet {@link InputFile} for a member. */
    InputFile inputFile(String name);

    /** Human-readable source description (path) for error messages. */
    String describe();

    @Override
    void close();

    /** Open a directory or single-file {@code .mzpeak} as a source. */
    static MzPeakSource open(Path path) {
        if (Files.isDirectory(path)) {
            return new DirectorySource(path);
        }
        if (Files.isRegularFile(path)) {
            return new ZipSource(path);
        }
        throw new MzPeakException("No such mzPeak dataset: " + path);
    }
}
