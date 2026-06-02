package org.mzpeak.io;

import org.apache.parquet.io.InputFile;
import org.mzpeak.io.parquet.ByteArrayInputFile;
import org.mzpeak.io.parquet.FileSliceInputFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * {@link MzPeakSource} over a single-file {@code .mzpeak} ZIP archive.
 *
 * <p>mzPeak archives store their Parquet members STORED (uncompressed). Such members are read <em>in place</em>
 * via a seekable {@link FileSliceInputFile} over the archive's {@link FileChannel} — no copy into memory — so
 * reads from inside the archive are streaming and memory-bounded, exactly like an unpacked directory.
 * (Compressed members, which the spec doesn't use, are inflated into memory as a fallback.)
 */
final class ZipSource implements MzPeakSource {

    /** Guard the in-memory fallback (manifest read + compressed members) against unbounded heap. */
    private static final long MAX_MEMBER_BYTES = Integer.MAX_VALUE - 8;

    private final Path path;
    private final FileChannel channel;
    private final Map<String, ZipIndex.Entry> index;

    ZipSource(Path path) {
        this.path = path;
        FileChannel ch = null;
        try {
            ch = FileChannel.open(path, StandardOpenOption.READ);
            this.index = ZipIndex.read(ch);
            this.channel = ch;
        } catch (IOException | RuntimeException e) {
            closeQuietly(ch);
            throw (e instanceof MzPeakException me) ? me
                    : new MzPeakException("Failed to open mzPeak archive " + path, e);
        }
    }

    private static void closeQuietly(FileChannel ch) {
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException ignored) {
                // best effort on the failure path
            }
        }
    }

    @Override
    public byte[] readManifestBytes() {
        return readBytes("mzpeak_index.json");
    }

    @Override
    public InputFile inputFile(String name) {
        ZipIndex.Entry e = entry(name);
        if (e.method() == ZipIndex.STORED) {
            return new FileSliceInputFile(channel, e.dataOffset(), e.size()); // in place, seekable
        }
        return new ByteArrayInputFile(readBytes(name)); // compressed -> inflate into memory
    }

    private ZipIndex.Entry entry(String name) {
        ZipIndex.Entry e = index.get(name);
        if (e == null) {
            throw new MzPeakException("mzPeak archive " + path + " has no member " + name);
        }
        return e;
    }

    private byte[] readBytes(String name) {
        ZipIndex.Entry e = entry(name);
        if (e.size() > MAX_MEMBER_BYTES || e.compressedSize() > MAX_MEMBER_BYTES) {
            throw new MzPeakException("mzPeak member " + name + " is too large to read into memory in " + path);
        }
        byte[] raw = readSlice(e.dataOffset(), (int) e.compressedSize(), name);
        if (e.method() == ZipIndex.STORED) {
            return raw;
        }
        if (e.method() != ZipIndex.DEFLATED) {
            throw new MzPeakException("unsupported ZIP compression method " + e.method() + " for member " + name);
        }
        byte[] out = new byte[(int) e.size()];
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(raw);
            int total = 0;
            while (total < out.length) {
                int n = inflater.inflate(out, total, out.length - total);
                if (n == 0) {
                    if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) {
                        break; // no further progress possible
                    }
                }
                total += n;
            }
            if (!inflater.finished() || total != out.length) {
                throw new MzPeakException("truncated/corrupt DEFLATE member " + name + " in " + path
                        + " (inflated " + total + " of " + out.length + " bytes)");
            }
            return out;
        } catch (DataFormatException ex) {
            throw new MzPeakException("Failed to inflate member " + name + " in " + path, ex);
        } finally {
            inflater.end();
        }
    }

    private byte[] readSlice(long offset, int length, String name) {
        try {
            ByteBuffer buf = ByteBuffer.allocate(length);
            long pos = offset;
            while (buf.hasRemaining()) {
                int n = channel.read(buf, pos);
                if (n < 0) {
                    throw new MzPeakException("unexpected end of archive reading member " + name + " in " + path);
                }
                pos += n;
            }
            return buf.array();
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
            channel.close();
        } catch (IOException e) {
            throw new MzPeakException("Failed to close " + path, e);
        }
    }
}
