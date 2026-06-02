package org.mzpeak.io;

import org.apache.parquet.io.InputFile;
import org.mzpeak.io.parquet.ByteArrayInputFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * {@link MzPeakSource} over a single-file {@code .mzpeak} ZIP archive. The mzPeak spec requires members to be
 * STORED (uncompressed); members are read fully into memory and wrapped as {@link ByteArrayInputFile}s.
 */
final class ZipSource implements MzPeakSource {

    /** Guard against pathological archives reading unbounded heap (byte[] is also capped at ~2 GiB). */
    private static final long MAX_MEMBER_BYTES = Integer.MAX_VALUE - 8;

    private final Path path;
    private final ZipFile zip;

    ZipSource(Path path) {
        this.path = path;
        try {
            this.zip = new ZipFile(path.toFile());
        } catch (IOException e) {
            throw new MzPeakException("Failed to open mzPeak archive " + path, e);
        }
    }

    @Override
    public byte[] readManifestBytes() {
        return readEntry("mzpeak_index.json");
    }

    @Override
    public InputFile inputFile(String name) {
        return new ByteArrayInputFile(readEntry(name));
    }

    private byte[] readEntry(String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new MzPeakException("mzPeak archive " + path + " has no member " + name);
        }
        long size = entry.getSize();
        if (size > MAX_MEMBER_BYTES) {
            throw new MzPeakException("mzPeak member " + name + " is too large to read into memory ("
                    + size + " bytes) in " + path);
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new MzPeakException("Failed to read member " + name + " from " + path, e);
        }
    }

    @Override
    public String describe() {
        return path.toString();
    }

    @Override
    public void close() {
        try {
            zip.close();
        } catch (IOException e) {
            throw new MzPeakException("Failed to close " + path, e);
        }
    }
}
