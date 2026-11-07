package org.mzpeak.io;

import org.apache.parquet.io.InputFile;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Abstracts where an mzPeak dataset's member files live: an unpacked directory or a single-file
 * (STORED) {@code .mzpeak} ZIP archive. Members are addressed by their manifest name.
 *
 * <p>Implementations other than the built-in ones can be passed to {@link MzPeakReader#open(MzPeakSource)} and
 * {@link MzPeakReader#open(MzPeakSource, MzPeakReader.Options)}. What a reader expects of a source:
 * <ul>
 *   <li>Members are addressed by the {@code name} the manifest lists, a {@code /}-separated path relative to the
 *       dataset root (e.g. {@code spectra_data.parquet}).
 *   <li>{@link #inputFile(String)} may be called several times for the same member, and the streams of the
 *       returned files may be open at the same time; each stream keeps its own position. A reader keeps the streams
 *       of its signal members open until it is closed.
 *   <li>Failures are reported unchecked, preferably as {@link MzPeakException} naming the member and
 *       {@link #describe()}; {@link InputFile} methods may throw {@link java.io.IOException} as declared.
 *   <li>A reader opened on a source owns it and closes it exactly once: when opening fails, or on the reader's
 *       first {@link MzPeakReader#close()}.
 * </ul>
 */
public interface MzPeakSource extends AutoCloseable {

    /** Raw bytes of {@code mzpeak_index.json}; read once, while a reader opens. */
    byte[] readManifestBytes();

    /** A parquet {@link InputFile} for a member, addressed by its manifest name. */
    InputFile inputFile(String name);

    /** Human-readable source description (path) for error messages. */
    String describe();

    /** Release the resources of this source; failures are reported unchecked. */
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
