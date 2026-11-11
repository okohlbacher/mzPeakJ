package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.model.Spectrum;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reads ZIP archives whose end-of-central-directory record defers to a zip64 record (design §9.1). The archives
 * are written here from the members of the legacy {@code small.unpacked.mzpeak}: STORED, without extra fields,
 * the manifest last. A zip64 archive ends with the zip64 end-of-central-directory record, its locator and a
 * classic record whose fields carry the {@code 0xFFFF}/{@code 0xFFFFFFFF} sentinels. A central directory whose size
 * does not suit its entry count, or exceeds {@link ZipIndex#MAX_DIRECTORY_BYTES}, is rejected before it is read (F-2).
 *
 * <p>{@code -Dmzpeak.zip64.file=<archive>} additionally opens a real zip64 archive, such as one above 4 GiB, and
 * reads the footer of its spectrum metadata member.
 */
class Zip64Test {

    private static final Path UNPACKED = Fixtures.legacy("small.unpacked.mzpeak");
    private static final String INDEX = "mzpeak_index.json";
    private static final int SPECTRA = 48;
    /** The end-of-central-directory search window: the record and the longest comment. */
    private static final long MAX_TAIL = 22 + 0xFFFF;

    @Test
    void zip64EndOfCentralDirectoryReadsLikeTheDirectory(@TempDir Path tmp) throws IOException {
        Archive zip = Archive.build(0, Trailer.ZIP64_ALL_SENTINELS);
        assertReadsLikeTheDirectory(zip.save(tmp.resolve("zip64.mzpeak")));
    }

    @Test
    void zip64WithOnlyTheOffsetSentinel(@TempDir Path tmp) throws IOException {
        // The shape of a large archive with few members: only the central-directory offset overflows 32 bits.
        Archive zip = Archive.build(0, Trailer.ZIP64_OFFSET_SENTINEL);
        assertReadsLikeTheDirectory(zip.save(tmp.resolve("zip64-offset.mzpeak")));
    }

    @Test
    void zip64WithOnlyTheEntryCountSentinel(@TempDir Path tmp) throws IOException {
        // The shape of an archive with more than 65535 members, as Python zipfile and the Rust zip crate write it:
        // only the classic count fields hold 0xFFFF, the size and offset are real, and a zip64 locator precedes.
        Archive zip = Archive.build(0, Trailer.ZIP64_COUNT_SENTINEL);
        assertThat(zip.getShort(zip.eocd + 8)).isEqualTo(0xFFFF);
        assertThat(zip.getShort(zip.eocd + 10)).isEqualTo(0xFFFF);
        assertThat(zip.getInt(zip.eocd + 12)).isEqualTo(zip.cdSize);
        assertThat(zip.getInt(zip.eocd + 16)).isEqualTo(zip.cdOffset);
        assertReadsLikeTheDirectory(zip.save(tmp.resolve("zip64-count.mzpeak")));
    }

    @Test
    void entryCountSentinelWithoutLocatorIsAClassicArchive(@TempDir Path tmp) throws IOException {
        // Exactly 65535 entries: the classic count field holds 0xFFFF, the size and offset are in range.
        Archive zip = Archive.build(0xFFFF - Archive.memberCount(), Trailer.CLASSIC);
        assertThat(zip.getShort(zip.eocd + 10)).isEqualTo(0xFFFF);
        assertReadsLikeTheDirectory(zip.save(tmp.resolve("65535.mzpeak")));
    }

    @Test
    void offsetSentinelWithoutLocator(@TempDir Path tmp) throws IOException {
        Archive zip = Archive.build(0, Trailer.ZIP64_ALL_SENTINELS);
        zip.putInt(zip.locator, 0);
        assertOpenFails(zip.save(tmp.resolve("no-locator.mzpeak")), "zip64", "locator");
    }

    @Test
    void locatorPointingBeyondTheEnd(@TempDir Path tmp) throws IOException {
        Archive zip = Archive.build(0, Trailer.ZIP64_ALL_SENTINELS);
        zip.putLong(zip.locator + 8, zip.bytes.length);
        assertOpenFails(zip.save(tmp.resolve("locator.mzpeak")), "zip64", "out of bounds");
    }

    @Test
    void zip64RecordOverlappingTheLocator(@TempDir Path tmp) throws IOException {
        Archive zip = Archive.build(0, Trailer.ZIP64_ALL_SENTINELS);
        zip.putLong(zip.locator + 8, zip.zip64Record + 1);
        assertOpenFails(zip.save(tmp.resolve("record-overlap.mzpeak")), "zip64", "out of bounds");
    }

    @Test
    void corruptZip64RecordSignature(@TempDir Path tmp) throws IOException {
        Archive zip = Archive.build(0, Trailer.ZIP64_ALL_SENTINELS);
        zip.putInt(zip.zip64Record, 0);
        assertOpenFails(zip.save(tmp.resolve("record-signature.mzpeak")), "corrupt zip64 end-of-central-directory record");
    }

    @Test
    void centralDirectoryReachingIntoTheZip64Record(@TempDir Path tmp) throws IOException {
        Archive zip = Archive.build(0, Trailer.ZIP64_ALL_SENTINELS);
        zip.putLong(zip.zip64Record + 40, zip.cdSize + 1);
        assertOpenFails(zip.save(tmp.resolve("cd-size.mzpeak")), "zip64", "central directory out of bounds");
    }

    @Test
    void centralDirectoryOffsetPastTheZip64Record(@TempDir Path tmp) throws IOException {
        Archive zip = Archive.build(0, Trailer.ZIP64_ALL_SENTINELS);
        zip.putLong(zip.zip64Record + 48, zip.zip64Record + 1);
        assertOpenFails(zip.save(tmp.resolve("cd-offset.mzpeak")), "zip64", "central directory out of bounds");
    }

    @Test
    void centralDirectoryOver2GiB(@TempDir Path tmp) throws IOException {
        Archive zip = Archive.build(0, Trailer.ZIP64_ALL_SENTINELS);
        zip.putLong(zip.zip64Record + 40, Integer.MAX_VALUE + 1L);
        assertOpenFails(zip.save(tmp.resolve("cd-2gib.mzpeak")), "zip64 central directory", "2147483648",
                "larger than 2 GiB");
    }

    @Test
    void negativeZip64EntryCount(@TempDir Path tmp) throws IOException {
        Archive zip = Archive.build(0, Trailer.ZIP64_ALL_SENTINELS);
        zip.putLong(zip.zip64Record + 32, -1);
        assertOpenFails(zip.save(tmp.resolve("entries.mzpeak")), "zip64", "entry count");
    }

    /**
     * F-2: a zip64 central directory of 100 MiB that lies inside a 3 GiB archive is rejected as too large for its
     * 1,000 entries before it is read: no read larger than the end-record search window is requested.
     */
    @Test
    void zip64CentralDirectoryOverTheCapIsRejectedBeforeItIsRead() {
        Tail archive = Tail.zip64(1_000, 100L * 1024 * 1024);
        assertDirectoryRejected(archive, "corrupt zip: central directory of 104857600 bytes for 1000 entries"
                + " (allowed 46000..67108864)");
        assertThat(archive.largestRead).as("largest read").isLessThanOrEqualTo(MAX_TAIL);
    }

    @Test
    void zip64CentralDirectoryLargerThanItsEntriesCanBe() {
        assertDirectoryRejected(Tail.zip64(10, 2_000_000), "corrupt zip: central directory of 2000000 bytes for"
                + " 10 entries (allowed 460..1966510)");
    }

    @Test
    void zip64CentralDirectorySmallerThanItsEntriesCanBe() {
        assertDirectoryRejected(Tail.zip64(1_000, 45_999), "corrupt zip: central directory of 45999 bytes for"
                + " 1000 entries (allowed 46000..67108864)");
    }

    @Test
    void zip64EntryCountWhoseLowerBoundOverflows() {
        long entries = 1L << 62;
        assertDirectoryRejected(Tail.zip64(entries, 1024 * 1024), "corrupt zip: central directory of 1048576 bytes"
                + " for " + entries + " entries (allowed 9223372036854775807..67108864)");
    }

    @Test
    void classicCentralDirectoryOverTheCapIsRejectedBeforeItIsRead() {
        Tail archive = Tail.classic(0xFFFF, 100L * 1024 * 1024);
        assertDirectoryRejected(archive, "corrupt zip: central directory of 104857600 bytes for 65535 entries"
                + " (allowed 3014610..67108864)");
        assertThat(archive.largestRead).as("largest read").isLessThanOrEqualTo(MAX_TAIL);
    }

    /**
     * A STORED copy of the current small archive with one more member whose central-directory header carries a
     * 1,000-byte name, a 2,000-byte extra field and a 3,000-byte comment: long fields stay within the bounds.
     */
    @Test
    void centralDirectoryHeaderWithLongFields(@TempDir Path tmp) throws IOException {
        Path archive = tmp.resolve("long-fields.mzpeak");
        try (ZipFile source = new ZipFile(Fixtures.current("small.mzpeak").toFile());
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (ZipEntry entry : Collections.list(source.entries())) {
                byte[] data;
                try (InputStream in = source.getInputStream(entry)) {
                    data = in.readAllBytes();
                }
                putStored(out, new ZipEntry(entry.getName()), data);
            }
            ZipEntry pad = new ZipEntry("pad/" + "a".repeat(1_000));
            byte[] extra = new byte[2_000];
            ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 0x6666).putShort((short) 1_996);
            pad.setExtra(extra);
            pad.setComment("c".repeat(3_000));
            putStored(out, pad, new byte[] {1, 2, 3});
        }
        Map<String, ZipIndex.Entry> index;
        try (FileChannel channel = FileChannel.open(archive)) {
            index = ZipIndex.read(channel);
        }
        assertThat(index).containsKey("pad/" + "a".repeat(1_000));
        try (MzPeakReader reader = MzPeakReader.open(archive)) {
            assertThat(reader.size()).isEqualTo(SPECTRA);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "mzpeak.zip64.file", matches = ".+")
    void opensTheZip64ArchiveNamedBySystemProperty() {
        Path file = Path.of(System.getProperty("mzpeak.zip64.file"));
        assertThat(file).isRegularFile();
        try (MzPeakSource source = MzPeakSource.open(file)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            MzPeakManifest.Entry metadata = manifest.find(EntityType.SPECTRUM, DataKind.METADATA).orElseThrow();
            ParquetFooter footer = ParquetFooter.read(source.inputFile(metadata.name()));
            assertThat(footer.rowCount()).as("rows of %s", metadata.name()).isPositive();
            assertThat(footer.topLevelFields()).as("fields of %s", metadata.name()).isNotEmpty();
        }
    }

    private static void assertReadsLikeTheDirectory(Path archive) {
        List<Spectrum> expected = new ArrayList<>();
        try (MzPeakReader directory = MzPeakReader.open(UNPACKED)) {
            for (Spectrum s : directory) {
                expected.add(s);
            }
        }
        assertThat(expected).hasSize(SPECTRA);
        try (MzPeakReader zip = MzPeakReader.open(archive)) {
            assertThat(zip.size()).isEqualTo(SPECTRA);
            for (Spectrum s : expected) {
                assertThat(Canonical.describe(zip.getSpectrum(s.index()).orElseThrow().description()))
                        .as("description of spectrum %d", s.index())
                        .isEqualTo(Canonical.describe(s.description()));
            }
            StreamingReaderTest.assertReReadsExactly(zip, expected);
        }
    }

    private static void putStored(ZipOutputStream out, ZipEntry entry, byte[] data) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(data);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        entry.setCrc(crc.getValue());
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    private static void assertDirectoryRejected(Tail archive, String message) {
        assertThatThrownBy(() -> ZipIndex.read(archive))
                .isInstanceOf(MzPeakException.class)
                .hasMessage(message);
    }

    private static void assertOpenFails(Path dataset, String... fragments) {
        assertThatThrownBy(() -> MzPeakReader.open(dataset).close())
                .isInstanceOf(MzPeakException.class)
                .hasMessageContainingAll(fragments);
    }

    /** The records that close an archive written by {@link Archive#build}. */
    private enum Trailer {
        /** Only the classic end-of-central-directory record, with the real values. */
        CLASSIC,
        /** zip64 record, locator, and a classic record whose count, size and offset fields are all sentinels. */
        ZIP64_ALL_SENTINELS,
        /** zip64 record, locator, and a classic record whose central-directory offset alone is the sentinel. */
        ZIP64_OFFSET_SENTINEL,
        /** zip64 record, locator, and a classic record whose entry counts alone are the sentinel. */
        ZIP64_COUNT_SENTINEL
    }

    /** A STORED archive held in memory for editing, with the offsets of its directory and trailing records. */
    private static final class Archive {

        final byte[] bytes;
        final int cdOffset;
        final int cdSize;
        final int zip64Record;
        final int locator;
        final int eocd;
        private final ByteBuffer le;

        private Archive(byte[] bytes, int cdOffset, int cdSize, int zip64Record, int locator, int eocd) {
            this.bytes = bytes;
            this.cdOffset = cdOffset;
            this.cdSize = cdSize;
            this.zip64Record = zip64Record;
            this.locator = locator;
            this.eocd = eocd;
            this.le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }

        /** The legacy unpacked members, sorted, the manifest last. */
        static List<Path> members() throws IOException {
            List<Path> members = new ArrayList<>();
            try (Stream<Path> files = Files.list(UNPACKED)) {
                files.filter(f -> !f.getFileName().toString().equals(INDEX)).sorted().forEach(members::add);
            }
            members.add(UNPACKED.resolve(INDEX));
            return members;
        }

        static int memberCount() throws IOException {
            return members().size();
        }

        /** The members plus {@code padding} empty entries before the manifest, closed by {@code trailer}. */
        static Archive build(int padding, Trailer trailer) throws IOException {
            List<String> names = new ArrayList<>();
            List<byte[]> data = new ArrayList<>();
            for (Path f : members()) {
                if (f.getFileName().toString().equals(INDEX)) {
                    for (int i = 0; i < padding; i++) {
                        names.add(String.format("padding/%05d", i));
                        data.add(new byte[0]);
                    }
                }
                names.add(f.getFileName().toString());
                data.add(Files.readAllBytes(f));
            }
            Le out = new Le();
            int[] headers = new int[names.size()];
            int[] crcs = new int[names.size()];
            for (int i = 0; i < names.size(); i++) {
                byte[] name = names.get(i).getBytes(StandardCharsets.UTF_8);
                byte[] content = data.get(i);
                CRC32 crc = new CRC32();
                crc.update(content);
                crcs[i] = (int) crc.getValue();
                headers[i] = out.size();
                out.i32(0x04034b50).i16(20).i16(0).i16(0).i32(0).i32(crcs[i]).i32(content.length).i32(content.length)
                        .i16(name.length).i16(0).put(name).put(content);
            }
            int cdOffset = out.size();
            for (int i = 0; i < names.size(); i++) {
                byte[] name = names.get(i).getBytes(StandardCharsets.UTF_8);
                int length = data.get(i).length;
                out.i32(0x02014b50).i16(20).i16(20).i16(0).i16(0).i32(0).i32(crcs[i]).i32(length).i32(length)
                        .i16(name.length).i16(0).i16(0).i16(0).i16(0).i32(0).i32(headers[i]).put(name);
            }
            int cdSize = out.size() - cdOffset;
            int entries = names.size();
            int zip64Record = -1;
            int locator = -1;
            if (trailer != Trailer.CLASSIC) {
                zip64Record = out.size();
                out.i32(0x06064b50).i64(44).i16(45).i16(45).i32(0).i32(0).i64(entries).i64(entries).i64(cdSize)
                        .i64(cdOffset);
                locator = out.size();
                out.i32(0x07064b50).i32(0).i64(zip64Record).i32(1);
            }
            int eocd = out.size();
            boolean all = trailer == Trailer.ZIP64_ALL_SENTINELS;
            int count = all || trailer == Trailer.ZIP64_COUNT_SENTINEL ? 0xFFFF : entries;
            boolean realOffset = trailer == Trailer.CLASSIC || trailer == Trailer.ZIP64_COUNT_SENTINEL;
            out.i32(0x06054b50).i16(0).i16(0).i16(count).i16(count).i32(all ? -1 : cdSize)
                    .i32(realOffset ? cdOffset : -1).i16(0);
            return new Archive(out.toByteArray(), cdOffset, cdSize, zip64Record, locator, eocd);
        }

        int getShort(int at) {
            return le.getShort(at) & 0xFFFF;
        }

        int getInt(int at) {
            return le.getInt(at);
        }

        void putInt(int at, int value) {
            le.putInt(at, value);
        }

        void putLong(int at, long value) {
            le.putLong(at, value);
        }

        Path save(Path target) throws IOException {
            return Files.write(target, bytes);
        }
    }

    /**
     * A 3 GiB archive of zeros that ends with the given records, built like {@link Archive#build} builds them; it
     * records the largest read it is asked for.
     */
    private static final class Tail implements RandomAccessBytes {

        private static final long SIZE = 3L << 30;

        private final ZipIndexTest.Sparse bytes;
        long largestRead;

        private Tail(byte[] records) {
            bytes = new ZipIndexTest.Sparse(SIZE).put(SIZE - records.length, records);
        }

        /** A zip64 record, its locator and a classic record of sentinels, naming a directory at offset 0. */
        static Tail zip64(long entries, long cdSize) {
            long record = SIZE - 98;
            return new Tail(new Le()
                    .i32(0x06064b50).i64(44).i16(45).i16(45).i32(0).i32(0).i64(entries).i64(entries).i64(cdSize).i64(0)
                    .i32(0x07064b50).i32(0).i64(record).i32(1)
                    .i32(0x06054b50).i16(0).i16(0).i16(0xFFFF).i16(0xFFFF).i32(-1).i32(-1).i16(0)
                    .toByteArray());
        }

        /** A classic end record naming a directory at offset 0. */
        static Tail classic(int entries, long cdSize) {
            return new Tail(new Le()
                    .i32(0x06054b50).i16(0).i16(0).i16(entries).i16(entries).i32((int) cdSize).i32(0).i16(0)
                    .toByteArray());
        }

        @Override
        public long size() {
            return SIZE;
        }

        @Override
        public int read(ByteBuffer dst, long position) throws IOException {
            largestRead = Math.max(largestRead, dst.remaining());
            return bytes.read(dst, position);
        }

        @Override
        public void close() {
            bytes.close();
        }
    }

    /** Appends little-endian values. */
    private static final class Le extends ByteArrayOutputStream {

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
}
