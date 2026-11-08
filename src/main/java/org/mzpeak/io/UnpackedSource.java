package org.mzpeak.io;

import org.apache.parquet.io.InputFile;
import org.mzpeak.io.parquet.FileSliceInputFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link MzPeakSource} over an unpacked dataset whose members a {@link MzPeakSource.MemberOpener} opens as
 * {@link RandomAccessBytes}, e.g. the objects under one prefix of an object store. Each member is opened on first
 * use, at most once, and served as a {@link FileSliceInputFile} over its whole length; {@link #close()} closes the
 * members opened.
 */
final class UnpackedSource implements MzPeakSource {

    private static final String MANIFEST = "mzpeak_index.json";

    /** Guard the manifest read against unbounded heap. */
    private static final long MAX_MANIFEST_BYTES = Integer.MAX_VALUE - 8;

    /** An opened member and its size, read once when it is opened. */
    private record Member(RandomAccessBytes bytes, long size) {
    }

    private final String description;
    private final MzPeakSource.MemberOpener opener;
    private final Map<String, Member> members = new LinkedHashMap<>();
    private boolean closed;

    UnpackedSource(String description, MzPeakSource.MemberOpener opener) {
        this.description = description;
        this.opener = opener;
    }

    @Override
    public byte[] readManifestBytes() {
        Member m = member(MANIFEST);
        if (m.size() > MAX_MANIFEST_BYTES) {
            throw new MzPeakException("mzPeak member " + MANIFEST + " is too large to read into memory in "
                    + description);
        }
        ByteBuffer buf = ByteBuffer.allocate((int) m.size());
        try {
            long pos = 0;
            while (buf.hasRemaining()) {
                int n = m.bytes().read(buf, pos);
                if (n < 0) {
                    throw new MzPeakException("unexpected end of member " + MANIFEST + " in " + description);
                }
                pos += n;
            }
        } catch (IOException e) {
            throw new MzPeakException("Failed to read member " + MANIFEST + " from " + description, e);
        }
        return buf.array();
    }

    @Override
    public InputFile inputFile(String name) {
        Member m = member(name);
        return new FileSliceInputFile(m.bytes(), 0, m.size());
    }

    /** The member {@code name}, opened on first use. */
    private synchronized Member member(String name) {
        if (closed) {
            throw new IllegalStateException(description + " is closed");
        }
        checkName(name);
        Member m = members.get(name);
        if (m == null) {
            m = open(name);
            members.put(name, m);
        }
        return m;
    }

    /**
     * Rejects a name that is absolute or has a {@code ..} segment, so an opener never sees one. A name is absolute if
     * it starts with {@code /} or {@code \}, or if its first segment contains {@code :}, which covers drive letters
     * ({@code C:/x}) and URI schemes ({@code https://host/x}, {@code file:///x}, {@code s3:x}).
     */
    private void checkName(String name) {
        String[] segments = name.split("[/\\\\]", -1);
        boolean absolute = name.startsWith("/") || name.startsWith("\\") || segments[0].contains(":");
        boolean climbs = false;
        for (String segment : segments) {
            climbs |= segment.equals("..");
        }
        if (absolute || climbs) {
            throw new MzPeakException("manifest member escapes dataset directory: " + name + " in " + description);
        }
    }

    private Member open(String name) {
        RandomAccessBytes bytes;
        try {
            bytes = opener.open(name);
        } catch (IOException e) {
            throw new MzPeakException("Failed to open member " + name + " of " + description, e);
        }
        if (bytes == null) {
            throw new MzPeakException(description + " has no member " + name);
        }
        try {
            long size = bytes.size();
            if (size < 0) {
                throw new MzPeakException("member " + name + " of " + description + " has a negative size " + size);
            }
            return new Member(bytes, size);
        } catch (IOException | RuntimeException e) {
            ZipSource.closeQuietly(bytes);
            throw (e instanceof MzPeakException me) ? me
                    : new MzPeakException("Failed to read the size of member " + name + " of " + description, e);
        }
    }

    @Override
    public String describe() {
        return description;
    }

    /** Closes every member opened, once; later calls do nothing. */
    @Override
    public void close() {
        List<Member> opened;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            opened = new ArrayList<>(members.values());
            members.clear();
        }
        MzPeakException failure = null;
        for (Member m : opened) {
            try {
                m.bytes().close();
            } catch (IOException | RuntimeException e) {
                if (failure == null) {
                    failure = new MzPeakException("Failed to close " + description, e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
