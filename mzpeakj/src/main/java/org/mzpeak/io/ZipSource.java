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
 * {@link MzPeakSource} over a single-file {@code .mzpeak} ZIP archive, read through {@link RandomAccessBytes}: a
 * local file, or any storage a caller plugs in through {@link MzPeakSource#zip(String, RandomAccessBytes)}.
 *
 * <p>mzPeak archives store their Parquet members STORED (uncompressed). Such members are read <em>in place</em>
 * via a seekable {@link FileSliceInputFile} over the archive's bytes — no copy into memory — so reads from inside
 * the archive are streaming and memory-bounded, exactly like an unpacked directory.
 * (Compressed members, which the spec doesn't use, are inflated into memory as a fallback.)
 */
final class ZipSource implements MzPeakSource {

    /** Guard the in-memory fallback (manifest read + compressed members) against unbounded heap. */
    private static final long MAX_MEMBER_BYTES = Integer.MAX_VALUE - 8;

    private final String description;
    private final RandomAccessBytes bytes;
    private final Map<String, ZipIndex.Entry> index;

    /** The archive file at {@code path}, described by the path. */
    ZipSource(Path path) {
        this(path.toString(), openFile(path));
    }

    /**
     * The archive {@code bytes}, described by {@code description} in messages. Takes ownership of {@code bytes}: they
     * are closed when the archive cannot be read, or by {@link #close()}.
     */
    ZipSource(String description, RandomAccessBytes bytes) {
        this.description = description;
        this.bytes = bytes;
        try {
            this.index = ZipIndex.read(description, bytes);
        } catch (IOException | RuntimeException e) {
            closeQuietly(bytes);
            throw (e instanceof MzPeakException me) ? me
                    : new MzPeakException("Failed to open mzPeak archive " + description, e);
        }
    }

    private static RandomAccessBytes openFile(Path path) {
        try {
            return RandomAccessBytes.of(FileChannel.open(path, StandardOpenOption.READ));
        } catch (IOException | RuntimeException e) {
            throw new MzPeakException("Failed to open mzPeak archive " + path, e);
        }
    }

    static void closeQuietly(RandomAccessBytes bytes) {
        try {
            bytes.close();
        } catch (IOException | RuntimeException ignored) {
            // best effort on the failure path
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
            return new FileSliceInputFile(bytes, e.dataOffset(), e.size()); // in place, seekable
        }
        return new ByteArrayInputFile(readBytes(name)); // compressed -> inflate into memory
    }

    /** Whether the archive's central directory lists the member {@code name}. */
    boolean contains(String name) {
        return index.containsKey(name);
    }

    private ZipIndex.Entry entry(String name) {
        ZipIndex.Entry e = index.get(name);
        if (e == null) {
            throw new MzPeakException("mzPeak archive " + description + " has no member " + name);
        }
        return e;
    }

    private byte[] readBytes(String name) {
        ZipIndex.Entry e = entry(name);
        if (e.size() > MAX_MEMBER_BYTES || e.compressedSize() > MAX_MEMBER_BYTES) {
            throw new MzPeakException("mzPeak member " + name + " is too large to read into memory in "
                    + description);
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
                    break; // no further progress possible: the stream finished or its input ran out
                }
                total += n;
            }
            if (!inflater.finished() || total != out.length) {
                throw new MzPeakException("truncated/corrupt DEFLATE member " + name + " in " + description
                        + " (inflated " + total + " of " + out.length + " bytes)");
            }
            return out;
        } catch (DataFormatException ex) {
            throw new MzPeakException("Failed to inflate member " + name + " in " + description, ex);
        } finally {
            inflater.end();
        }
    }

    private byte[] readSlice(long offset, int length, String name) {
        try {
            ByteBuffer buf = ByteBuffer.allocate(length);
            long pos = offset;
            while (buf.hasRemaining()) {
                int n = bytes.read(buf, pos);
                if (n < 0) {
                    throw new MzPeakException("unexpected end of archive reading member " + name + " in "
                            + description);
                }
                if (n == 0) {
                    throw new MzPeakException("zero-length read of member " + name + " in " + description
                            + " at position " + pos + "; a read must return at least one byte or -1");
                }
                pos += n;
            }
            return buf.array();
        } catch (IOException e) {
            throw new MzPeakException("Failed to read member " + name + " from " + description, e);
        }
    }

    @Override
    public String describe() {
        return description;
    }

    @Override
    public void close() {
        try {
            bytes.close();
        } catch (IOException e) {
            throw new MzPeakException("Failed to close " + description, e);
        }
    }
}
