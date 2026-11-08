package org.mzpeak.io;

import org.mzpeak.io.ForwardBackwardCorpusTest.Source;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * The entry point of the child JVMs ({@link ChildJvm}) of {@link DeterminismProcessTest} and
 * {@link ArchivePackerTest}. Commands:
 *
 * <ul>
 *   <li>{@code write <dataset> <archive>}: reads the dataset with {@link ForwardBackwardCorpusTest#readSource} and
 *       writes it with {@link MzPeakWriter#writeArchive(Path, java.util.List, java.util.List, java.util.List,
 *       java.util.List, org.mzpeak.model.meta.FileMetadata)};
 *   <li>{@code pack <directory> <archive>}: packs a copy of every file of the directory with
 *       {@link ArchivePacker#packArchive};
 *   <li>{@code pack-sparse <length> <archive>}: packs a sparse member {@code big.bin} of {@code length} zero bytes,
 *       made with {@link RandomAccessFile#setLength}, and a manifest {@code {}}.
 * </ul>
 *
 * A failure ends the child with its stack trace on stderr and a non-zero exit status.
 */
final class ArchiveChildMain {

    private ArchiveChildMain() {
    }

    public static void main(String[] args) throws IOException {
        Path archive = Path.of(args[2]);
        switch (args[0]) {
            case "write" -> {
                Source s = ForwardBackwardCorpusTest.readSource(Path.of(args[1]));
                MzPeakWriter.writeArchive(archive, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                        s.fileMetadata);
            }
            case "pack" -> {
                Path members = Path.of(args[1]);
                ArchivePacker.packArchive(archive, dir -> copyFiles(members, dir));
            }
            case "pack-sparse" -> {
                long length = Long.parseLong(args[1]);
                ArchivePacker.packArchive(archive, dir -> sparse(dir, length));
            }
            default -> throw new IllegalArgumentException("unknown command " + args[0]);
        }
    }

    private static void copyFiles(Path from, Path to) {
        try (Stream<Path> files = Files.list(from)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Files.copy(file, to.resolve(file.getFileName().toString()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sparse(Path dir, long length) {
        try (RandomAccessFile big = new RandomAccessFile(dir.resolve("big.bin").toFile(), "rw")) {
            big.setLength(length);
            Files.writeString(dir.resolve(ManifestWriter.INDEX), "{}", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
