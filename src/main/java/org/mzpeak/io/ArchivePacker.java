package org.mzpeak.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs a dataset directory into a single-file STORED {@code .mzpeak} ZIP for the {@link MzPeakWriter} archive
 * overloads, in member-name order.
 */
final class ArchivePacker {

    private ArchivePacker() {
    }

    /**
     * Shared archive helper: write to a temp directory, pack as STORED ZIP, and move atomically into place.
     * Temp files are cleaned up in all cases.
     */
    static void packArchive(Path archive, Consumer<Path> writer) {
        Path parent = archive.toAbsolutePath().getParent();
        Path tmpDir = null;
        Path tmpZip = null;
        try {
            tmpDir = Files.createTempDirectory(parent, ".mzpeak-write-");
            tmpZip = Files.createTempFile(parent, ".mzpeak-write-", ".zip");
            writer.accept(tmpDir);
            packStored(tmpDir, tmpZip);
            Files.move(tmpZip, archive, StandardCopyOption.REPLACE_EXISTING);
            tmpZip = null;
        } catch (IOException e) {
            throw new MzPeakException("Failed to write mzPeak archive " + archive, e);
        } finally {
            deleteRecursively(tmpDir);
            deleteRecursively(tmpZip);
        }
    }

    private static void packStored(Path dir, Path zipFile) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zip.setMethod(ZipOutputStream.STORED);
            try (var members = Files.list(dir).sorted()) {
                for (Path member : (Iterable<Path>) members::iterator) {
                    byte[] bytes = Files.readAllBytes(member);
                    CRC32 crc = new CRC32();
                    crc.update(bytes);
                    ZipEntry entry = new ZipEntry(member.getFileName().toString());
                    entry.setSize(bytes.length);
                    entry.setCompressedSize(bytes.length);
                    entry.setCrc(crc.getValue());
                    zip.putNextEntry(entry);
                    zip.write(bytes);
                    zip.closeEntry();
                }
            }
        }
    }

    private static void deleteRecursively(Path path) {
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
