package org.mzpeak.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A minimal ZIP central-directory reader: maps each member name to its compression method, size, and the byte
 * offset of its data within the archive. This lets STORED (uncompressed) members be read in place via
 * {@link org.mzpeak.io.parquet.FileSliceInputFile} rather than copied into memory. The archive is read through
 * {@link RandomAccessBytes}, with positional reads only.
 *
 * <p>Members, offsets and the archive may exceed 4 GiB: per-entry zip64 extra fields and the zip64
 * end-of-central-directory record are read. The central directory itself must fit in 2 GiB.
 */
final class ZipIndex {

    static final int STORED = 0;
    static final int DEFLATED = 8;

    /** One central-directory entry. */
    record Entry(String name, int method, long size, long compressedSize, long dataOffset) {
    }

    /** Where the central directory lies and how many records it holds. */
    private record Directory(long offset, long size, long entries) {
    }

    private static final int EOCD_SIG = 0x06054b50;
    private static final int ZIP64_EOCD_SIG = 0x06064b50;
    private static final int ZIP64_LOCATOR_SIG = 0x07064b50;
    private static final int CDH_SIG = 0x02014b50;
    private static final int LFH_SIG = 0x04034b50;
    private static final long U32_MAX = 0xFFFFFFFFL;
    /** The tail searched for the end-of-central-directory record before the longest possible tail is read. */
    private static final int EOCD_PROBE = 1024;

    private ZipIndex() {
    }

    /** {@link #read(RandomAccessBytes)} over {@code channel}. Does not close the channel. */
    static Map<String, Entry> read(FileChannel channel) throws IOException {
        return read(RandomAccessBytes.of(channel));
    }

    /** The entries of the archive {@code bytes}, in central-directory order. Does not close {@code bytes}. */
    static Map<String, Entry> read(RandomAccessBytes bytes) throws IOException {
        long fileSize = bytes.size();
        Directory directory = directory(bytes, fileSize, locateEocd(bytes, fileSize));

        ByteBuffer cd = readAt(bytes, directory.offset(), (int) directory.size());
        Map<String, Entry> entries = new LinkedHashMap<>();
        int p = 0;
        int limit = cd.limit();
        for (long i = 0; i < directory.entries(); i++) {
            if (p < 0 || p > limit - 46 || cd.getInt(p) != CDH_SIG) {
                throw new MzPeakException("corrupt zip central directory at offset " + p);
            }
            int method = cd.getShort(p + 10) & 0xFFFF;
            long compressedSize = cd.getInt(p + 20) & U32_MAX;
            long size = cd.getInt(p + 24) & U32_MAX;
            int nameLen = cd.getShort(p + 28) & 0xFFFF;
            int extraLen = cd.getShort(p + 30) & 0xFFFF;
            int commentLen = cd.getShort(p + 32) & 0xFFFF;
            long localHeaderOffset = cd.getInt(p + 42) & U32_MAX;
            long recordEnd = (long) p + 46 + nameLen + extraLen + commentLen;
            if (recordEnd > limit) {
                throw new MzPeakException("corrupt zip central directory: record overruns directory");
            }
            byte[] nameBytes = new byte[nameLen];
            cd.position(p + 46);
            cd.get(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);

            // Sizes/offset may be 0xFFFFFFFF with the real values in a per-entry zip64 extra field (header
            // id 0x0001), present in the example archives even when the EOCD itself is not zip64.
            if (size == U32_MAX || compressedSize == U32_MAX || localHeaderOffset == U32_MAX) {
                long[] z64 = zip64Extra(cd, p + 46 + nameLen, extraLen,
                        size == U32_MAX, compressedSize == U32_MAX, localHeaderOffset == U32_MAX);
                if (size == U32_MAX) {
                    size = z64[0];
                }
                if (compressedSize == U32_MAX) {
                    compressedSize = z64[1];
                }
                if (localHeaderOffset == U32_MAX) {
                    localHeaderOffset = z64[2];
                }
            }

            if (size < 0 || compressedSize < 0 || localHeaderOffset < 0 || localHeaderOffset > fileSize) {
                throw new MzPeakException("corrupt zip entry '" + name + "': size/offset out of range");
            }
            if (method == STORED && size != compressedSize) {
                throw new MzPeakException("corrupt zip entry '" + name + "': STORED size mismatch");
            }
            long dataOffset = dataOffset(bytes, localHeaderOffset);
            if (dataOffset > fileSize || compressedSize > fileSize - dataOffset) {
                throw new MzPeakException("corrupt zip entry '" + name + "': data range past end of archive");
            }
            if (entries.putIfAbsent(name, new Entry(name, method, size, compressedSize, dataOffset)) != null) {
                throw new MzPeakException("duplicate member '" + name + "' in zip archive");
            }
            p = (int) recordEnd;
        }
        return entries;
    }

    /**
     * Parse the zip64 extended-information extra field (header id {@code 0x0001}) in a central-directory
     * record. The 8-byte values appear in fixed order — uncompressed size, compressed size, local-header
     * offset — but only for the fields whose 32-bit value was the {@code 0xFFFFFFFF} sentinel.
     *
     * @return {@code [uncompressedSize, compressedSize, localHeaderOffset]} (unused slots are -1)
     */
    private static long[] zip64Extra(ByteBuffer cd, int extraStart, int extraLen,
                                     boolean needSize, boolean needCompressed, boolean needOffset) {
        int q = extraStart;
        int end = extraStart + extraLen;
        while (q + 4 <= end) {
            int id = cd.getShort(q) & 0xFFFF;
            int dataSize = cd.getShort(q + 2) & 0xFFFF;
            int data = q + 4;
            if (data + dataSize > end) {
                throw new MzPeakException("corrupt zip extra field (overruns the record)");
            }
            if (id == 0x0001) {
                int needed = (needSize ? 8 : 0) + (needCompressed ? 8 : 0) + (needOffset ? 8 : 0);
                if (dataSize < needed) {
                    throw new MzPeakException("corrupt zip64 extra field (payload too short)");
                }
                long size = -1;
                long compressed = -1;
                long offset = -1;
                int r = data;
                if (needSize) {
                    size = cd.getLong(r);
                    r += 8;
                }
                if (needCompressed) {
                    compressed = cd.getLong(r);
                    r += 8;
                }
                if (needOffset) {
                    offset = cd.getLong(r);
                }
                return new long[] {size, compressed, offset};
            }
            q = data + dataSize;
        }
        throw new MzPeakException("zip member uses 0xFFFFFFFF size/offset but has no zip64 extra field");
    }

    /** Data starts after the local file header (whose name/extra lengths may differ from the central record). */
    private static long dataOffset(RandomAccessBytes bytes, long localHeaderOffset) throws IOException {
        ByteBuffer lfh = readAt(bytes, localHeaderOffset, 30);
        if (lfh.getInt(0) != LFH_SIG) {
            throw new MzPeakException("corrupt zip local file header at offset " + localHeaderOffset);
        }
        int nameLen = lfh.getShort(26) & 0xFFFF;
        int extraLen = lfh.getShort(28) & 0xFFFF;
        return localHeaderOffset + 30L + nameLen + extraLen;
    }

    /**
     * The central directory named by the end-of-central-directory record at {@code eocdOffset}. When that record
     * carries a {@code 0xFFFF}/{@code 0xFFFFFFFF} sentinel and a zip64 locator directly precedes it, the directory
     * is taken from the zip64 end-of-central-directory record the locator points to. A sentinel without a locator
     * is accepted only as an entry count of exactly 65535 with the size and offset in range.
     */
    private static Directory directory(RandomAccessBytes bytes, long fileSize, long eocdOffset) throws IOException {
        ByteBuffer eocd = readAt(bytes, eocdOffset, 22);
        long totalEntries = eocd.getShort(10) & 0xFFFF;
        long cdSize = eocd.getInt(12) & U32_MAX;
        long cdOffset = eocd.getInt(16) & U32_MAX;
        boolean sizeOrOffsetSentinel = cdSize == U32_MAX || cdOffset == U32_MAX;
        if ((totalEntries == 0xFFFF || sizeOrOffsetSentinel) && eocdOffset >= 20) {
            long locatorOffset = eocdOffset - 20;
            ByteBuffer locator = readAt(bytes, locatorOffset, 20);
            if (locator.getInt(0) == ZIP64_LOCATOR_SIG) {
                return zip64Directory(bytes, locatorOffset, locator.getLong(8));
            }
        }
        if (sizeOrOffsetSentinel) {
            throw new MzPeakException("corrupt zip: the end-of-central-directory record carries a zip64 sentinel"
                    + " but no zip64 locator precedes it");
        }
        if (cdSize > Integer.MAX_VALUE || cdOffset > fileSize || cdSize > fileSize - cdOffset) {
            throw new MzPeakException("corrupt zip: central directory out of bounds");
        }
        return new Directory(cdOffset, cdSize, totalEntries);
    }

    /** The central directory named by the zip64 end-of-central-directory record at {@code recordOffset}. */
    private static Directory zip64Directory(RandomAccessBytes bytes, long locatorOffset, long recordOffset)
            throws IOException {
        if (recordOffset < 0 || recordOffset > locatorOffset - 56) {
            throw new MzPeakException("corrupt zip64 end-of-central-directory locator: record offset "
                    + Long.toUnsignedString(recordOffset) + " out of bounds");
        }
        ByteBuffer record = readAt(bytes, recordOffset, 56);
        if (record.getInt(0) != ZIP64_EOCD_SIG) {
            throw new MzPeakException("corrupt zip64 end-of-central-directory record at offset " + recordOffset);
        }
        long totalEntries = record.getLong(32);
        long cdSize = record.getLong(40);
        long cdOffset = record.getLong(48);
        if (totalEntries < 0) {
            throw new MzPeakException("corrupt zip64 end-of-central-directory record: entry count "
                    + Long.toUnsignedString(totalEntries) + " out of range");
        }
        if (cdSize > Integer.MAX_VALUE) {
            throw new MzPeakException("zip64 central directory of " + cdSize
                    + " bytes is larger than 2 GiB; not supported");
        }
        if (cdSize < 0 || cdOffset < 0 || cdOffset > recordOffset || cdSize > recordOffset - cdOffset) {
            throw new MzPeakException("corrupt zip64: central directory out of bounds");
        }
        return new Directory(cdOffset, cdSize, totalEntries);
    }

    /** The offset of the end-of-central-directory record: the last one whose comment reaches the end of the file. */
    private static long locateEocd(RandomAccessBytes bytes, long fileSize) throws IOException {
        // Most archives carry no comment, so the last KiB is searched before the longest possible tail is read. Both
        // searches run backwards from the end, so the probe finds exactly what the full search would find first.
        long eocd = searchTail(bytes, fileSize, (int) Math.min(fileSize, EOCD_PROBE));
        int maxTail = (int) Math.min(fileSize, 22 + 0xFFFF); // EOCD record + max comment
        if (eocd < 0 && maxTail > EOCD_PROBE) {
            eocd = searchTail(bytes, fileSize, maxTail);
        }
        if (eocd < 0) {
            throw new MzPeakException("not a ZIP archive (no end-of-central-directory record found)");
        }
        return eocd;
    }

    /** The offset of the last EOCD record in the last {@code tailLen} bytes whose comment reaches the end, or -1. */
    private static long searchTail(RandomAccessBytes bytes, long fileSize, int tailLen) throws IOException {
        long start = fileSize - tailLen;
        ByteBuffer tail = readAt(bytes, start, tailLen);
        for (int i = tailLen - 22; i >= 0; i--) {
            if (tail.getInt(i) == EOCD_SIG && (tail.getShort(i + 20) & 0xFFFF) == tailLen - i - 22) {
                return start + i;
            }
        }
        return -1;
    }

    private static ByteBuffer readAt(RandomAccessBytes bytes, long position, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        long pos = position;
        while (buf.hasRemaining()) {
            int n = bytes.read(buf, pos);
            if (n < 0) {
                throw new MzPeakException("unexpected end of ZIP archive while reading " + length
                        + " bytes at " + position);
            }
            pos += n;
        }
        return buf.flip().order(ByteOrder.LITTLE_ENDIAN);
    }
}
