package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ZipIndex} on the shapes of central directory that neither the fixtures nor {@link ContainerNegativePathTest},
 * {@link Zip64Test} and {@link ZipSourceCharacterizationTest} build: records that defer any one of their size,
 * compressed size and local-header offset to the zip64 extra field, negative values in that field and in the zip64
 * end record, an end record too close to the start of the file for a zip64 locator, and a classic central directory
 * above 2 GiB. The archives are read through {@link MzPeakSource#zip(String, RandomAccessBytes)}; the members are those
 * of the legacy {@code small.unpacked.mzpeak}, or the legacy {@code small.mzpeak} is edited.
 */
class ZipIndexTest {

    private static final Path LEGACY_ARCHIVE = Fixtures.legacy("small.mzpeak");
    private static final Path UNPACKED = Fixtures.legacy("small.unpacked.mzpeak");
    private static final String INDEX = "mzpeak_index.json";
    private static final String METADATA = "spectra_metadata.parquet";
    private static final int U32_SENTINEL = 0xFFFF_FFFF;

    /** A 32-bit field of a central-directory record that holds the sentinel, its value following in the zip64 extra. */
    enum Deferred { SIZE, COMPRESSED, OFFSET }

    @Test
    void recordsThatDeferAnyOfTheirFieldsToZip64ReadLikeTheDirectory() throws IOException {
        Map<String, Set<Deferred>> deferred = new LinkedHashMap<>();
        deferred.put("chromatograms_data.parquet", EnumSet.of(Deferred.SIZE));
        deferred.put("chromatograms_metadata.parquet", EnumSet.of(Deferred.COMPRESSED));
        deferred.put("spectra_data.parquet", EnumSet.of(Deferred.OFFSET));
        deferred.put(METADATA, EnumSet.allOf(Deferred.class));
        Built zip = Built.of(deferred);
        for (Map.Entry<String, Set<Deferred>> member : deferred.entrySet()) {
            int record = zip.records.get(member.getKey());
            Set<Deferred> fields = member.getValue();
            assertThat(zip.getInt(record + 20) == U32_SENTINEL).as("compressed-size sentinel of %s", member.getKey())
                    .isEqualTo(fields.contains(Deferred.COMPRESSED));
            assertThat(zip.getInt(record + 24) == U32_SENTINEL).as("size sentinel of %s", member.getKey())
                    .isEqualTo(fields.contains(Deferred.SIZE));
            assertThat(zip.getInt(record + 42) == U32_SENTINEL).as("offset sentinel of %s", member.getKey())
                    .isEqualTo(fields.contains(Deferred.OFFSET));
        }

        Map<String, ZipIndex.Entry> index = ZipIndex.read(RemoteSourceTest.inMemory(zip.bytes));
        assertThat(index.keySet()).containsExactlyElementsOf(zip.members.keySet());
        for (ZipIndex.Entry e : index.values()) {
            byte[] expected = zip.members.get(e.name());
            assertThat(e.method()).as("method of %s", e.name()).isEqualTo(ZipIndex.STORED);
            assertThat(e.size()).as("size of %s", e.name()).isEqualTo(expected.length);
            assertThat(e.compressedSize()).as("compressed size of %s", e.name()).isEqualTo(expected.length);
            assertThat(e.dataOffset()).as("data offset of %s", e.name())
                    .isEqualTo((long) zip.dataOffsets.get(e.name()));
            byte[] data = new byte[expected.length];
            System.arraycopy(zip.bytes, (int) e.dataOffset(), data, 0, data.length);
            assertThat(data).as("bytes at the data offset of %s", e.name()).isEqualTo(expected);
        }

        try (MzPeakReader directory = MzPeakReader.open(UNPACKED);
             MzPeakReader archive = MzPeakReader.open(MzPeakSource.zip("memory:zip64-fields",
                     RemoteSourceTest.inMemory(zip.bytes)))) {
            List<String> content = TestSources.content(directory);
            assertThat(content).noneMatch(line -> line.contains(" throws "));
            assertThat(TestSources.content(archive)).containsExactlyElementsOf(content);
        }
    }

    @Test
    void aNegativeSizeInTheZip64ExtraField() throws IOException {
        byte[] bytes = Files.readAllBytes(LEGACY_ARCHIVE);
        int payload = legacyZip64Payload(bytes, METADATA);
        le(bytes).putLong(payload, -1); // the size; the compressed size that follows is intact
        assertOpenFails(bytes, "corrupt zip entry '" + METADATA + "': size/offset out of range");
    }

    @Test
    void aNegativeCompressedSizeInTheZip64ExtraField() throws IOException {
        byte[] bytes = Files.readAllBytes(LEGACY_ARCHIVE);
        int payload = legacyZip64Payload(bytes, METADATA);
        le(bytes).putLong(payload + 8, -1); // the compressed size; the size before it is intact
        assertOpenFails(bytes, "corrupt zip entry '" + METADATA + "': size/offset out of range");
    }

    @Test
    void aNegativeLocalHeaderOffsetInTheZip64ExtraField() throws IOException {
        Built zip = Built.of(Map.of(METADATA, EnumSet.of(Deferred.OFFSET)));
        assertThat(ZipIndex.read(RemoteSourceTest.inMemory(zip.bytes)).get(METADATA).dataOffset())
                .isEqualTo((long) zip.dataOffsets.get(METADATA));
        zip.le.putLong(zip.payloads.get(METADATA), -1);
        assertOpenFails(zip.bytes, "corrupt zip entry '" + METADATA + "': size/offset out of range");
    }

    /**
     * An end record that starts less than 20 bytes into the file leaves no room for a zip64 locator before it, so a
     * sentinel in it is reported as such, without a read before the start of the file.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 19})
    void aSentinelInAnEndRecordTooCloseToTheStartForALocator(int offset) {
        Le out = new Le();
        out.put(new byte[offset]);
        out.i32(0x06054b50).i16(0).i16(0).i16(0).i16(0).i32(U32_SENTINEL).i32(0).i16(0);
        assertOpenFails(out.toByteArray(), "corrupt zip: the end-of-central-directory record carries a zip64 sentinel"
                + " but no zip64 locator precedes it");
    }

    /** The central directory must fit in 2 GiB, also when the archive is large enough to hold a larger one. */
    @Test
    void aClassicCentralDirectoryOf2GiB() {
        long cdSize = Integer.MAX_VALUE + 1L;
        Le eocd = new Le().i32(0x06054b50).i16(0).i16(0).i16(1).i16(1).i32((int) cdSize).i32(0).i16(0);
        Sparse bytes = new Sparse(cdSize + 22).put(cdSize, eocd.toByteArray());
        assertThatThrownBy(() -> MzPeakSource.zip("memory:2gib", bytes))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("corrupt zip: central directory out of bounds");
        assertThat(bytes.closed).as("closed by the failed open").isTrue();
    }

    @Test
    void anEmptyZip64Archive() throws IOException {
        assertThat(ZipIndex.read(RemoteSourceTest.inMemory(emptyZip64()))).isEmpty();
    }

    @Test
    void aZip64LocatorWithANegativeRecordOffset() {
        byte[] bytes = emptyZip64();
        le(bytes).putLong(56 + 8, -1);
        assertOpenFails(bytes, "corrupt zip64 end-of-central-directory locator: record offset 18446744073709551615"
                + " out of bounds");
    }

    @Test
    void aZip64RecordWithANegativeCentralDirectorySize() {
        byte[] bytes = emptyZip64();
        le(bytes).putLong(40, -1);
        assertOpenFails(bytes, "corrupt zip64: central directory out of bounds");
    }

    @Test
    void aZip64RecordWithANegativeCentralDirectoryOffset() {
        byte[] bytes = emptyZip64();
        le(bytes).putLong(48, -1);
        assertOpenFails(bytes, "corrupt zip64: central directory out of bounds");
    }

    private static void assertOpenFails(byte[] archive, String message) {
        assertThatThrownBy(() -> MzPeakSource.zip("memory:corrupt", RemoteSourceTest.inMemory(archive)))
                .isInstanceOf(MzPeakException.class)
                .hasMessage(message);
    }

    /**
     * An archive without members that ends with a zip64 end record at 0 (entry count at 32, central-directory size at
     * 40 and offset at 48), its locator at 56 (record offset at 64) and a classic end record of sentinels at 76.
     */
    private static byte[] emptyZip64() {
        return new Le()
                .i32(0x06064b50).i64(44).i16(45).i16(45).i32(0).i32(0).i64(0).i64(0).i64(0).i64(0)
                .i32(0x07064b50).i32(0).i64(0).i32(1)
                .i32(0x06054b50).i16(0).i16(0).i16(0xFFFF).i16(0xFFFF).i32(U32_SENTINEL).i32(U32_SENTINEL).i16(0)
                .toByteArray();
    }

    /** The offset of the zip64 extra payload (size, then compressed size) of {@code name} in the legacy archive. */
    private static int legacyZip64Payload(byte[] bytes, String name) {
        ByteBuffer le = le(bytes);
        int eocd = bytes.length - 22;
        assertThat(le.getInt(eocd)).isEqualTo(0x06054b50);
        int p = le.getInt(eocd + 16);
        for (int i = 0; i < (le.getShort(eocd + 10) & 0xFFFF); i++) {
            assertThat(le.getInt(p)).isEqualTo(0x02014b50);
            int nameLen = le.getShort(p + 28) & 0xFFFF;
            int extraLen = le.getShort(p + 30) & 0xFFFF;
            if (new String(bytes, p + 46, nameLen, StandardCharsets.UTF_8).equals(name)) {
                int extra = p + 46 + nameLen;
                assertThat(extraLen).as("extra field length of %s", name).isEqualTo(20);
                assertThat(le.getShort(extra) & 0xFFFF).as("extra field id of %s", name).isEqualTo(0x0001);
                assertThat(le.getShort(extra + 2) & 0xFFFF).as("zip64 payload of %s", name).isEqualTo(16);
                assertThat(le.getInt(p + 24)).as("size of %s", name).isEqualTo(U32_SENTINEL);
                assertThat(le.getInt(p + 20)).as("compressed size of %s", name).isEqualTo(U32_SENTINEL);
                return extra + 4;
            }
            p += 46 + nameLen + extraLen + (le.getShort(p + 32) & 0xFFFF);
        }
        throw new AssertionError("no central-directory record " + name);
    }

    private static ByteBuffer le(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * A STORED archive of the legacy unpacked members, sorted, the manifest last, without data descriptors and closed
     * by a classic end record. The central-directory record of a member named in {@code deferred} holds the sentinel
     * in the fields given there and their values in a zip64 extra field.
     */
    private static final class Built {

        final byte[] bytes;
        final ByteBuffer le;
        /** The members and their bytes, in archive order. */
        final Map<String, byte[]> members;
        /** The offset of each member's central-directory record. */
        final Map<String, Integer> records;
        /** Where each member's data starts. */
        final Map<String, Integer> dataOffsets;
        /** The offset of the first value in each zip64 extra payload. */
        final Map<String, Integer> payloads;

        private Built(byte[] bytes, Map<String, byte[]> members, Map<String, Integer> records,
                      Map<String, Integer> dataOffsets, Map<String, Integer> payloads) {
            this.bytes = bytes;
            this.le = le(bytes);
            this.members = members;
            this.records = records;
            this.dataOffsets = dataOffsets;
            this.payloads = payloads;
        }

        static Built of(Map<String, Set<Deferred>> deferred) throws IOException {
            Map<String, byte[]> members = new LinkedHashMap<>();
            try (Stream<Path> files = Files.list(UNPACKED)) {
                for (Path f : (Iterable<Path>) files.sorted()::iterator) {
                    if (!f.getFileName().toString().equals(INDEX)) {
                        members.put(f.getFileName().toString(), Files.readAllBytes(f));
                    }
                }
            }
            members.put(INDEX, Files.readAllBytes(UNPACKED.resolve(INDEX)));
            assertThat(members.keySet()).containsAll(deferred.keySet());

            Le out = new Le();
            Map<String, Integer> headers = new LinkedHashMap<>();
            Map<String, Integer> crcs = new LinkedHashMap<>();
            Map<String, Integer> dataOffsets = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> member : members.entrySet()) {
                byte[] name = member.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] data = member.getValue();
                CRC32 crc = new CRC32();
                crc.update(data);
                crcs.put(member.getKey(), (int) crc.getValue());
                headers.put(member.getKey(), out.size());
                out.i32(0x04034b50).i16(45).i16(0).i16(0).i32(0).i32((int) crc.getValue()).i32(data.length)
                        .i32(data.length).i16(name.length).i16(0).put(name);
                dataOffsets.put(member.getKey(), out.size());
                out.put(data);
            }
            int cdOffset = out.size();
            Map<String, Integer> records = new LinkedHashMap<>();
            Map<String, Integer> payloads = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> member : members.entrySet()) {
                String key = member.getKey();
                byte[] name = key.getBytes(StandardCharsets.UTF_8);
                int length = member.getValue().length;
                int header = headers.get(key);
                Set<Deferred> fields = deferred.getOrDefault(key, EnumSet.noneOf(Deferred.class));
                List<Long> values = new ArrayList<>();
                if (fields.contains(Deferred.SIZE)) {
                    values.add((long) length);
                }
                if (fields.contains(Deferred.COMPRESSED)) {
                    values.add((long) length);
                }
                if (fields.contains(Deferred.OFFSET)) {
                    values.add((long) header);
                }
                int extraLen = values.isEmpty() ? 0 : 4 + 8 * values.size();
                records.put(key, out.size());
                out.i32(0x02014b50).i16(45).i16(45).i16(0).i16(0).i32(0).i32(crcs.get(key))
                        .i32(fields.contains(Deferred.COMPRESSED) ? U32_SENTINEL : length)
                        .i32(fields.contains(Deferred.SIZE) ? U32_SENTINEL : length)
                        .i16(name.length).i16(extraLen).i16(0).i16(0).i16(0).i32(0)
                        .i32(fields.contains(Deferred.OFFSET) ? U32_SENTINEL : header).put(name);
                if (!values.isEmpty()) {
                    out.i16(0x0001).i16(8 * values.size());
                    payloads.put(key, out.size());
                    for (long v : values) {
                        out.i64(v);
                    }
                }
            }
            int cdSize = out.size() - cdOffset;
            out.i32(0x06054b50).i16(0).i16(0).i16(members.size()).i16(members.size()).i32(cdSize).i32(cdOffset)
                    .i16(0);
            return new Built(out.toByteArray(), members, records, dataOffsets, payloads);
        }

        int getInt(int at) {
            return le.getInt(at);
        }
    }

    /** Appends little-endian values. */
    static final class Le extends ByteArrayOutputStream {

        Le i16(int v) {
            write(v);
            write(v >>> 8);
            return this;
        }

        Le i32(int v) {
            return i16(v).i16(v >>> 16);
        }

        Le i64(long v) {
            return i32((int) v).i32((int) (v >>> 32));
        }

        Le put(byte[] b) {
            writeBytes(b);
            return this;
        }
    }

    /**
     * {@code size} bytes, zero except where {@link #put} placed data, without holding them in memory: archives larger
     * than 2 GiB whose reads only touch their start and end.
     */
    static final class Sparse implements RandomAccessBytes {

        private final long size;
        private final NavigableMap<Long, byte[]> regions = new TreeMap<>();
        volatile boolean closed;

        Sparse(long size) {
            this.size = size;
        }

        /** Places {@code data} at {@code offset}; regions do not overlap. */
        Sparse put(long offset, byte[] data) {
            regions.put(offset, data);
            return this;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public int read(ByteBuffer dst, long position) throws IOException {
            if (closed) {
                throw new IOException("closed");
            }
            if (!dst.hasRemaining()) {
                return 0;
            }
            if (position >= size) {
                return -1;
            }
            int n = (int) Math.min(dst.remaining(), size - position);
            byte[] out = new byte[n];
            for (Map.Entry<Long, byte[]> region : regions.headMap(position + n, false).entrySet()) {
                long start = region.getKey();
                byte[] data = region.getValue();
                long from = Math.max(start, position);
                long to = Math.min(start + data.length, position + n);
                if (from < to) {
                    System.arraycopy(data, (int) (from - start), out, (int) (from - position), (int) (to - from));
                }
            }
            dst.put(out);
            return n;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
