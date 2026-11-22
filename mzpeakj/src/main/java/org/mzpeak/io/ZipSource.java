package org.mzpeak.io;

import org.apache.parquet.io.InputFile;
import org.mzpeak.io.parquet.FileSliceInputFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * {@link MzPeakSource} over a single-file {@code .mzpeak} ZIP archive, read through {@link RandomAccessBytes}: a
 * local file, or any storage a caller plugs in through {@link MzPeakSource#zip(String, RandomAccessBytes)}.
 *
 * <p>mzPeak archives store their Parquet members STORED (uncompressed). Such members are read <em>in place</em>
 * via a seekable {@link FileSliceInputFile} over the archive's bytes — no copy into memory — so reads from inside
 * the archive are streaming and memory-bounded, exactly like an unpacked directory.
 *
 * <p>The spec requires STORED members and forbids ZIP encryption (R9): reading a member that is compressed or has the
 * encryption flag throws an {@link MzPeakException} naming it. {@link MzPeakReader#open(MzPeakSource)} checks every
 * member the manifest lists; a member it does not list is never read and never fails the open.
 */
final class ZipSource implements MzPeakSource {

    /** Guard the in-memory read of the manifest against unbounded heap. */
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
        ZipIndex.Entry e = readable(name);
        return new FileSliceInputFile(bytes, e.dataOffset(), e.size()); // in place, seekable
    }

    /** Whether the archive's central directory lists the member {@code name}. */
    boolean contains(String name) {
        return index.containsKey(name);
    }

    /**
     * Why the member {@code name} is not read (R9): it is encrypted or not STORED. {@code null} if it can be read or
     * the archive has no such member.
     */
    String rejection(String name) {
        ZipIndex.Entry e = index.get(name);
        return e == null ? null : rejection(e);
    }

    private String rejection(ZipIndex.Entry e) {
        if (e.encrypted()) {
            return "mzPeak archive " + description + ": member " + e.name()
                    + " is encrypted; mzPeak forbids ZIP encryption";
        }
        if (e.method() != ZipIndex.STORED) {
            return "mzPeak archive " + description + ": member " + e.name() + " is stored with ZIP compression method "
                    + e.method() + "; mzPeak requires STORED members";
        }
        return null;
    }

    private ZipIndex.Entry entry(String name) {
        ZipIndex.Entry e = index.get(name);
        if (e == null) {
            throw new MzPeakException("mzPeak archive " + description + " has no member " + name);
        }
        return e;
    }

    /** The entry of the member {@code name}, which must exist and be STORED and unencrypted. */
    private ZipIndex.Entry readable(String name) {
        ZipIndex.Entry e = entry(name);
        String reason = rejection(e);
        if (reason != null) {
            throw new MzPeakException(reason);
        }
        return e;
    }

    private byte[] readBytes(String name) {
        ZipIndex.Entry e = readable(name);
        if (e.size() > MAX_MEMBER_BYTES) {
            throw new MzPeakException("mzPeak member " + name + " is too large to read into memory in "
                    + description);
        }
        return readSlice(e.dataOffset(), (int) e.size(), name);
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
