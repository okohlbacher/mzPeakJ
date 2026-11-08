package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ArchivePacker#packArchive}: the layout of the archive it packs, and its behaviour when its temporary
 * directory is gone or cannot be cleaned up. The cleanup is best effort, so a pack that succeeded stays a success and
 * a failed one reports its own failure, leaving behind what could not be deleted. The temporary directory is made
 * undeletable or unlistable through its POSIX permissions, which the file system does not enforce against root; then
 * the cleanup succeeds, and the tests expect nothing to be left.
 */
class ArchivePackerTest {

    private static final String INDEX = "mzpeak_index.json";
    /** The time of every entry: the same on every machine, in every time zone and at every time. */
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);
    /** The members {@link MzPeakWriter} writes for current small.unpacked, in name order with the manifest last. */
    private static final List<String> SMALL_MEMBERS = List.of(
            "chromatograms_data.parquet",
            "chromatograms_metadata.parquet",
            "chromatograms_metadata_precursors.parquet",
            "chromatograms_metadata_selected_ions.parquet",
            "spectra_data.parquet",
            "spectra_metadata.parquet",
            "spectra_metadata_precursors.parquet",
            "spectra_metadata_scans.parquet",
            "spectra_metadata_selected_ions.parquet",
            "spectra_peaks.parquet",
            INDEX);

    /**
     * An archive holds exactly the members a directory write of the same input holds (in one JVM both are byte for
     * byte the same), each as a STORED entry whose sizes and CRC are those of the member, in name order with the
     * manifest last; the local headers and the central directory agree on the order.
     */
    @Test
    void anArchiveHoldsTheMembersStoredInNameOrderWithTheManifestLast(@TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(Fixtures.current("small.unpacked.mzpeak"));
        Path dir = tmp.resolve("dir.mzpeak");
        MzPeakWriter.writeDirectory(dir, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata);
        Path archive = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeArchive(archive, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata);

        assertThat(list(dir)).map(f -> f.getFileName().toString())
                .containsExactlyInAnyOrderElementsOf(SMALL_MEMBERS);
        List<String> central = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                central.add(entry.getName());
                byte[] member = Files.readAllBytes(dir.resolve(entry.getName()));
                assertThat(entry.getMethod()).as("method of %s", entry.getName()).isEqualTo(ZipEntry.STORED);
                assertThat(entry.getSize()).as("size of %s", entry.getName()).isEqualTo(member.length);
                assertThat(entry.getCompressedSize()).as("compressed size of %s", entry.getName())
                        .isEqualTo(member.length);
                assertThat(entry.getCrc()).as("CRC of %s", entry.getName()).isEqualTo(crc(member));
                try (InputStream in = zip.getInputStream(entry)) {
                    assertThat(in.readAllBytes()).as("content of %s", entry.getName()).isEqualTo(member);
                }
            }
        }
        List<String> local = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(archive))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                local.add(entry.getName());
                assertThat(in.readAllBytes()).as("local content of %s", entry.getName())
                        .isEqualTo(Files.readAllBytes(dir.resolve(entry.getName())));
            }
        }
        assertThat(central).containsExactlyElementsOf(SMALL_MEMBERS);
        assertThat(local).containsExactlyElementsOf(SMALL_MEMBERS);
    }

    /**
     * Every entry carries the fixed time 2000-01-01T00:00 (MS-DOS date 0x2821, time 0) and no extra field, neither
     * in its local header nor in the central directory, so two writes of the same input in one JVM are byte for byte
     * the same, whenever they run.
     */
    @Test
    void everyEntryHasTheFixedTimeAndNoExtraFieldSoTwoWritesAreIdentical(@TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(Fixtures.current("small.unpacked.mzpeak"));
        Path first = tmp.resolve("first.mzpeak");
        Path second = tmp.resolve("second.mzpeak");
        MzPeakWriter.writeArchive(first, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata);
        MzPeakWriter.writeArchive(second, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata);

        List<ArchiveLayout.Entry> entries = ArchiveLayout.entries(first);
        assertThat(entries).extracting(ArchiveLayout.Entry::name).containsExactlyElementsOf(SMALL_MEMBERS);
        for (ArchiveLayout.Entry entry : entries) {
            assertThat(entry.timeLocal()).as("time of %s", entry.name()).isEqualTo(ENTRY_TIME);
            assertThat(entry.dosDate()).as("MS-DOS date of %s", entry.name()).isEqualTo(0x2821);
            assertThat(entry.dosTime()).as("MS-DOS time of %s", entry.name()).isZero();
            assertThat(entry.localExtraLength()).as("local extra field length of %s", entry.name()).isZero();
            assertThat(entry.centralExtraLength()).as("central extra field length of %s", entry.name()).isZero();
        }
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(first))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                assertThat(entry.getTimeLocal()).as("local time of %s", entry.getName()).isEqualTo(ENTRY_TIME);
                assertThat(entry.getExtra()).as("local extra field of %s", entry.getName()).isNull();
            }
        }
        assertThat(Files.mismatch(first, second)).as("first byte at which the two archives differ").isEqualTo(-1L);
    }

    /**
     * A child JVM with a 64 MiB heap packs a 200 MiB member, so the packer streams each member instead of holding it
     * in memory (which would also fail for a member over 2 GiB); the entry's sizes and CRC are those of the member.
     */
    @Test
    void aChildJvmWithA64MegabyteHeapPacksA200MegabyteMember(@TempDir Path tmp) throws Exception {
        long length = 200L << 20;
        Path out = Files.createDirectory(tmp.resolve("out"));
        Path archive = out.resolve("large.mzpeak");
        ChildJvm.Result result = ChildJvm.run(tmp, List.of("-Xmx64m"), ArchiveChildMain.class,
                "pack-sparse", Long.toString(length), archive.toString());
        assertThat(result.exitStatus()).as("exit status of the child; stderr:%n%s", result.stderr()).isZero();

        CRC32 zeros = new CRC32();
        byte[] buffer = new byte[1 << 16];
        for (long left = length; left > 0; left -= buffer.length) {
            zeros.update(buffer, 0, (int) Math.min(buffer.length, left));
        }
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName)).containsExactly("big.bin", INDEX);
            ZipEntry big = zip.getEntry("big.bin");
            assertThat(big.getMethod()).isEqualTo(ZipEntry.STORED);
            assertThat(big.getSize()).isEqualTo(length);
            assertThat(big.getCompressedSize()).isEqualTo(length);
            assertThat(big.getCrc()).isEqualTo(zeros.getValue());
            CRC32 read = new CRC32();
            long count = 0;
            try (InputStream in = zip.getInputStream(big)) {
                for (int n = in.read(buffer); n != -1; n = in.read(buffer)) {
                    read.update(buffer, 0, n);
                    count += n;
                }
            }
            assertThat(count).isEqualTo(length);
            assertThat(read.getValue()).isEqualTo(zeros.getValue());
        }
        assertThat(list(out)).as("the archive's directory after the pack").containsExactly(archive);
    }

    @Test
    void aWriterThatRemovesTheDirectoryFailsThePackAndLeavesNothing(@TempDir Path tmp) throws IOException {
        Path archive = tmp.resolve("out.mzpeak");
        AtomicReference<Path> directory = new AtomicReference<>();
        assertThatThrownBy(() -> ArchivePacker.packArchive(archive, dir -> {
            directory.set(dir);
            delete(dir);
        }))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("Failed to write mzPeak archive " + archive)
                .cause().isInstanceOf(NoSuchFileException.class).hasMessage(directory.get().toString());
        assertThat(list(tmp)).isEmpty();
    }

    @Test
    void membersThatCannotBeDeletedAreLeftBehindAndThePackSucceeds(@TempDir Path tmp) throws IOException {
        Path archive = tmp.resolve("out.mzpeak");
        AtomicReference<Path> directory = new AtomicReference<>();
        AtomicBoolean enforced = new AtomicBoolean();
        try {
            ArchivePacker.packArchive(archive, dir -> {
                directory.set(dir);
                write(dir.resolve("b.parquet"), "b");
                write(dir.resolve(INDEX), "{}");
                write(dir.resolve("a.parquet"), "a");
                permissions(dir, "r-x------");
                enforced.set(!Files.isWritable(dir));
            });

            assertThat(entries(archive)).containsExactly(
                    Map.entry("a.parquet", "a"), Map.entry("b.parquet", "b"), Map.entry(INDEX, "{}"));
            Path dir = directory.get();
            if (enforced.get()) {
                assertThat(list(tmp)).containsExactlyInAnyOrder(archive, dir);
                assertThat(list(dir)).containsExactlyInAnyOrder(
                        dir.resolve("a.parquet"), dir.resolve("b.parquet"), dir.resolve(INDEX));
            } else {
                assertThat(list(tmp)).containsExactly(archive);
            }
        } finally {
            restore(directory.get());
        }
    }

    @Test
    void aDirectoryThatCannotBeListedIsLeftBehindAndThePackFails(@TempDir Path tmp) throws IOException {
        Path archive = tmp.resolve("out.mzpeak");
        AtomicReference<Path> directory = new AtomicReference<>();
        AtomicBoolean enforced = new AtomicBoolean();
        try {
            // Unlistable, the directory fails the pack; for root, which lists it, the subdirectory fails it.
            assertThatThrownBy(() -> ArchivePacker.packArchive(archive, dir -> {
                directory.set(dir);
                write(dir.resolve("a.parquet"), "a");
                try {
                    Files.createDirectory(dir.resolve("sub"));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                permissions(dir, "-wx------");
                enforced.set(!Files.isReadable(dir));
            }))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("Failed to write mzPeak archive " + archive)
                    .hasCauseInstanceOf(IOException.class);

            assertThat(archive).doesNotExist();
            Path dir = directory.get();
            if (enforced.get()) {
                assertThat(list(tmp)).containsExactly(dir);
                restore(dir);
                assertThat(list(dir)).containsExactlyInAnyOrder(dir.resolve("a.parquet"), dir.resolve("sub"));
            } else {
                assertThat(list(tmp)).isEmpty();
            }
        } finally {
            restore(directory.get());
        }
    }

    /** The entries of {@code archive} and their content, in archive order, read with {@link ZipFile}. */
    private static Map<String, String> entries(Path archive) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                assertThat(entry.getMethod()).as("method of %s", entry.getName()).isEqualTo(ZipEntry.STORED);
                try (InputStream in = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return entries;
    }

    private static long crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    private static List<Path> list(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.collect(Collectors.toCollection(ArrayList::new));
        }
    }

    /** Gives {@code dir}, if it is left, back to its owner, so that {@code @TempDir} can delete it. */
    private static void restore(Path dir) throws IOException {
        if (dir != null && Files.isDirectory(dir)) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private static void permissions(Path dir, String permissions) {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(permissions));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
