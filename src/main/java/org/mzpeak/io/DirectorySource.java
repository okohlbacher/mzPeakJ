package org.mzpeak.io;

import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.LocalInputFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@link MzPeakSource} over an unpacked {@code *.mzpeak/} directory. */
final class DirectorySource implements MzPeakSource {

    private final Path directory;

    DirectorySource(Path directory) {
        this.directory = directory;
    }

    @Override
    public byte[] readManifestBytes() {
        Path manifest = directory.resolve("mzpeak_index.json");
        if (!Files.isRegularFile(manifest)) {
            throw new MzPeakException("Not an unpacked mzPeak directory (no mzpeak_index.json): " + directory);
        }
        try {
            return Files.readAllBytes(manifest);
        } catch (IOException e) {
            throw new MzPeakException("Failed to read " + manifest, e);
        }
    }

    @Override
    public boolean has(String name) {
        return Files.isRegularFile(directory.resolve(name));
    }

    @Override
    public InputFile inputFile(String name) {
        return new LocalInputFile(directory.resolve(name));
    }

    @Override
    public String describe() {
        return directory.toString();
    }

    @Override
    public void close() {
        // nothing to release
    }
}
