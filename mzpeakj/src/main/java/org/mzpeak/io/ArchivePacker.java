package org.mzpeak.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs a dataset directory into a single-file STORED {@code .mzpeak} ZIP for the archives of {@link MzPeakWriter}
 * (its archive sessions, on which the archive overloads run): the members in name order, then the manifest as the last
 * entry. The members are written into a temp directory next to the archive ({@link #stagingDirectory}), packed into a
 * temp file next to it and moved into place ({@link #pack}).
 *
 * <p>Each member is read twice as a stream, once through a CRC-32 for the entry header, which a STORED entry needs
 * before its data, and once into the archive. So no member is held in memory, and a member may exceed the 2 GiB of an
 * array; {@link ZipOutputStream} adds zip64 extra fields where a size or an offset exceeds 4 GiB.
 *
 * <p>Every entry gets the time {@link #ENTRY_TIME} and, but for zip64, no extra field, so the ZIP layer does not
 * depend on when or in which time zone the archive is written. The Parquet members it packs can still differ between
 * JVM runs, in their footers only: parquet-java 1.17.1 collects the encodings of each column chunk in a
 * {@code HashSet} of enum constants ({@code ParquetFileWriter.startColumn}) and writes them in its iteration order
 * ({@code ParquetMetadataConverter.toFormatEncodings}). Enum constants hash by identity, so that order can change
 * from one JVM run to the next, and with it the member's bytes, its CRC and its checksum in the manifest; within one
 * JVM it does not change. The content of the members (schema, footer key-values, row groups and records) is the same
 * in every run.
 */
final class ArchivePacker {

    /**
     * The time of every entry. Not 1980-01-01T00:00, the earliest MS-DOS time: {@link ZipEntry} takes that value as
     * its marker for a time before 1980 and then keeps the time as an instant in the default time zone, which
     * {@link ZipOutputStream} writes as an extended-timestamp (0x5455) extra field, so the bytes would depend on the
     * zone. {@link ZipEntry#setTime(long)} is not used either, since it converts through the default time zone.
     */
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);

    /** The options of the final move: replacing a file at the target, or not. */
    private static final CopyOption[] REPLACE = {StandardCopyOption.REPLACE_EXISTING};
    private static final CopyOption[] NO_REPLACE = {};

    /** Name order, except that the manifest sorts after every member. */
    private static final Comparator<Path> INDEX_LAST = Comparator
            .comparing((Path p) -> p.getFileName().toString().equals(ManifestWriter.INDEX))
            .thenComparing(Comparator.naturalOrder());

    private ArchivePacker() {
    }

    /**
     * The steps of an archive write in one call, which a {@link MzPeakWriter.Session} takes one at a time: write to a
     * temp directory, pack as STORED ZIP, and move atomically into place, replacing a file at {@code archive}:
     * {@link #stagingDirectory}, {@code writer}, {@link #pack}. Temp files are cleaned up in all cases.
     */
    static void packArchive(Path archive, Consumer<Path> writer) {
        Path tmpDir = stagingDirectory(archive);
        try {
            writer.accept(tmpDir);
            pack(tmpDir, archive, true);
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    /**
     * A new temp directory next to {@code archive}, in which the members of the archive are written before
     * {@link #pack} packs them; the caller deletes it ({@link #deleteRecursively}).
     *
     * @throws MzPeakException when the directory cannot be created
     */
    static Path stagingDirectory(Path archive) {
        try {
            return Files.createTempDirectory(parent(archive), ".mzpeak-write-");
        } catch (IOException e) {
            throw failure(archive, e);
        }
    }

    /**
     * Packs the members of {@code directory} as a STORED ZIP into a temp file next to {@code archive} and moves it to
     * {@code archive} by renaming it, which replaces a file there only when {@code replace} is set. The temp file is
     * deleted in all cases.
     *
     * @throws MzPeakException when a member cannot be read or the archive cannot be written or moved into place, among
     *                         others when {@code archive} exists and {@code replace} is not set
     */
    static void pack(Path directory, Path archive, boolean replace) {
        Path tmpZip = null;
        try {
            tmpZip = Files.createTempFile(parent(archive), ".mzpeak-write-", ".zip");
            packStored(directory, tmpZip);
            Files.move(tmpZip, archive, replace ? REPLACE : NO_REPLACE);
            tmpZip = null;
        } catch (IOException e) {
            throw failure(archive, e);
        } finally {
            deleteRecursively(tmpZip);
        }
    }

    private static Path parent(Path archive) {
        return archive.toAbsolutePath().getParent();
    }

    private static MzPeakException failure(Path archive, IOException e) {
        return new MzPeakException("Failed to write mzPeak archive " + archive, e);
    }

    private static void packStored(Path dir, Path zipFile) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zip.setMethod(ZipOutputStream.STORED);
            try (var members = Files.list(dir).sorted(INDEX_LAST)) {
                for (Path member : (Iterable<Path>) members::iterator) {
                    CRC32 crc = new CRC32();
                    long size;
                    try (InputStream in = new CheckedInputStream(Files.newInputStream(member), crc)) {
                        size = in.transferTo(OutputStream.nullOutputStream());
                    }
                    ZipEntry entry = new ZipEntry(member.getFileName().toString());
                    entry.setTimeLocal(ENTRY_TIME);
                    entry.setSize(size);
                    entry.setCompressedSize(size);
                    entry.setCrc(crc.getValue());
                    zip.putNextEntry(entry);
                    try (InputStream in = Files.newInputStream(member)) {
                        in.transferTo(zip);
                    }
                    zip.closeEntry();
                }
            }
        }
    }

    /** Deletes {@code path}, a file or a directory with everything in it, if it exists; best effort. */
    static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}
