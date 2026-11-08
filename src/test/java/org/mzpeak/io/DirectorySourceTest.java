package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DirectorySource} over a directory whose manifest is a regular file that cannot be read. A directory of the
 * JDK's ZIP file system serves as one: its manifest is a DEFLATE entry with corrupt data, which the file system reports
 * as a {@link ZipException} once the entry is read. {@link ContainerNegativePathTest} covers a missing manifest and a
 * member escaping the directory.
 */
class DirectorySourceTest {

    private static final Path UNPACKED = Fixtures.legacy("small.unpacked.mzpeak");
    private static final String INDEX = "mzpeak_index.json";

    @Test
    void aManifestThatCannotBeRead(@TempDir Path tmp) throws IOException {
        Path zip = tmp.resolve("dataset.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            ZipEntry e = new ZipEntry(INDEX);
            e.setMethod(ZipEntry.DEFLATED);
            out.putNextEntry(e);
            out.write(Files.readAllBytes(UNPACKED.resolve(INDEX)));
            out.closeEntry();
        }
        byte[] bytes = Files.readAllBytes(zip);
        ByteBuffer le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int header = le.getInt(le.getInt(bytes.length - 22 + 16) + 42);
        int data = header + 30 + (le.getShort(header + 26) & 0xFFFF) + (le.getShort(header + 28) & 0xFFFF);
        bytes[data] = (byte) 0xFF; // a final block of the reserved type 3
        Files.write(zip, bytes);

        try (FileSystem fs = FileSystems.newFileSystem(zip)) {
            Path root = fs.getPath("/");
            Path manifest = root.resolve(INDEX);
            assertThat(manifest).isRegularFile();
            try (MzPeakSource source = MzPeakSource.open(root)) {
                assertThat(source.describe()).isEqualTo("/");
                assertThatThrownBy(source::readManifestBytes)
                        .isInstanceOf(MzPeakException.class)
                        .hasMessage("Failed to read " + manifest)
                        .hasCauseInstanceOf(ZipException.class);
            }
            assertThatThrownBy(() -> MzPeakReader.open(root))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("Failed to read /" + INDEX)
                    .hasCauseInstanceOf(ZipException.class);
        }
    }
}
