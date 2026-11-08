package org.mzpeak.io;

import org.apache.parquet.io.InputFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Abstracts where an mzPeak dataset's member files live: an unpacked directory or a single-file
 * (STORED) {@code .mzpeak} ZIP archive. Members are addressed by their manifest name.
 *
 * <p>{@link #open(Path)} opens a local directory or archive. For other storage, such as an object store, a caller
 * supplies the bytes and the reader does the rest: {@link #zip(String, RandomAccessBytes)} reads a single-file archive
 * through one {@link RandomAccessBytes}, and {@link #unpacked(String, MemberOpener)} reads an unpacked dataset whose
 * members a {@link MemberOpener} opens one by one.
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

    /**
     * A single-file {@code .mzpeak} ZIP archive read from {@code bytes}, as {@link #open(Path)} reads an archive file:
     * the central directory is read now, STORED members are read in place and only the ranges a reader asks for.
     *
     * <p>The source takes ownership of {@code bytes} and closes them on {@link #close()}, or before throwing when the
     * archive cannot be read (or {@code description} is null).
     *
     * @param description names the archive in messages and {@link #describe()}, e.g. its URL
     * @throws MzPeakException if {@code bytes} are not a readable ZIP archive
     */
    static MzPeakSource zip(String description, RandomAccessBytes bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (description == null) {
            ZipSource.closeQuietly(bytes);
            throw new NullPointerException("description");
        }
        return new ZipSource(description, bytes);
    }

    /**
     * An unpacked dataset whose members {@code opener} opens, as {@link #open(Path)} reads a directory. Members are
     * opened lazily, when a reader first asks for them, and not again once they opened successfully; a member name
     * that is absolute or contains a {@code ..} segment is rejected before {@code opener} is called. A name
     * counts as absolute if it starts with {@code /} or {@code \}, or if its first segment contains {@code :} (a drive
     * letter such as {@code C:/x}, or a URI scheme such as {@code https://host/x} or {@code file:///x}).
     * {@link #close()} closes every member opened.
     *
     * @param description names the dataset in messages and {@link #describe()}, e.g. its base URL
     */
    static MzPeakSource unpacked(String description, MemberOpener opener) {
        return new UnpackedSource(Objects.requireNonNull(description, "description"),
                Objects.requireNonNull(opener, "opener"));
    }

    /** Opens the members of an unpacked dataset for {@link #unpacked(String, MemberOpener)}. */
    @FunctionalInterface
    interface MemberOpener {

        /**
         * Opens the member {@code name}, a {@code /}-separated path relative to the dataset root as the manifest
         * lists it ({@code mzpeak_index.json} for the manifest). The source calls this at most once per name that
         * opened successfully; a failed open (an exception, a {@code null} or failing {@code size()}) is retried on
         * the next request for that name. Lookups of other members wait while an open runs. Decoding the name, such
         * as percent-encoding it for a URL, is the opener's job. The source closes the returned bytes when it is
         * closed, or right away when their {@code size()} fails.
         *
         * @return the member's bytes, or {@code null} if the dataset has no such member
         * @throws IOException if the member cannot be opened
         */
        RandomAccessBytes open(String name) throws IOException;
    }
}
