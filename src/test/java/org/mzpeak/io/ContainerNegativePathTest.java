package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterizes the error paths of the hand-written container code ({@link ZipIndex}, {@link ZipSource},
 * {@link DirectorySource} and {@link MzPeakSource#open}) before RD-15, AP-6 and RD-16 change it. Each case
 * corrupts a {@code @TempDir} copy of a legacy fixture and expects an {@link MzPeakException} whose message
 * carries a stable fragment. ZIP offsets are found at run time by walking the archive from its
 * end-of-central-directory record.
 *
 * <p>The legacy {@code small.mzpeak} keeps its member sizes in per-entry zip64 extra fields. The size cases
 * therefore run on a plain STORED archive built from {@code small.unpacked.mzpeak}, whose 32-bit size fields
 * hold the real values. The zip64 end-of-central-directory sentinel and duplicate member names are not
 * covered, because RD-15 and RD-16 change them on purpose.
 */
class ContainerNegativePathTest {

    private static final Path ARCHIVE = Fixtures.legacy("small.mzpeak");
    private static final Path UNPACKED = Fixtures.legacy("small.unpacked.mzpeak");
    private static final String INDEX = "mzpeak_index.json";
    private static final String METADATA = "spectra_metadata.parquet";
    private static final int SPECTRA = 48;

    @Test
    void unchangedCopiesOpen(@TempDir Path tmp) throws IOException {
        assertThat(spectrumCount(ZipImage.of(ARCHIVE).save(tmp.resolve("copy.mzpeak")))).isEqualTo(SPECTRA);
        assertThat(spectrumCount(plainArchive(tmp))).isEqualTo(SPECTRA);
        assertThat(spectrumCount(unpackedCopy(tmp.resolve("small.unpacked.mzpeak")))).isEqualTo(SPECTRA);
    }

    @Test
    void archiveWithoutEndOfCentralDirectory(@TempDir Path tmp) throws IOException {
        ZipImage zip = ZipImage.of(ARCHIVE);
        zip.putInt(zip.eocd, 0);
        assertOpenFails(zip.save(tmp.resolve("no-eocd.mzpeak")), "no end-of-central-directory record");
    }

    @Test
    void centralDirectoryOffsetPastEnd(@TempDir Path tmp) throws IOException {
        ZipImage zip = ZipImage.of(ARCHIVE);
        zip.putInt(zip.eocd + 16, zip.bytes.length + 1);
        assertOpenFails(zip.save(tmp.resolve("cd-offset.mzpeak")), "central directory out of bounds");
    }

    @Test
    void centralDirectorySizePastEnd(@TempDir Path tmp) throws IOException {
        ZipImage zip = ZipImage.of(ARCHIVE);
        zip.putInt(zip.eocd + 12, zip.bytes.length - zip.cdOffset() + 1);
        assertOpenFails(zip.save(tmp.resolve("cd-size.mzpeak")), "central directory out of bounds");
    }

    @Test
    void corruptCentralDirectorySignature(@TempDir Path tmp) throws IOException {
        ZipImage zip = ZipImage.of(ARCHIVE);
        zip.putInt(zip.record(METADATA).offset(), 0);
        assertOpenFails(zip.save(tmp.resolve("cd-signature.mzpeak")), "corrupt zip central directory at offset");
    }

    @Test
    void moreEntriesThanTheCentralDirectoryHolds(@TempDir Path tmp) throws IOException {
        ZipImage zip = ZipImage.of(ARCHIVE);
        int entries = zip.records.size() + 1;
        zip.putShort(zip.eocd + 8, entries);
        zip.putShort(zip.eocd + 10, entries);
        assertOpenFails(zip.save(tmp.resolve("cd-count.mzpeak")), "corrupt zip central directory at offset");
    }

    @Test
    void centralDirectoryRecordOverrunsDirectory(@TempDir Path tmp) throws IOException {
        ZipImage zip = ZipImage.of(ARCHIVE);
        CdRecord last = zip.records.get(zip.records.size() - 1);
        zip.putShort(last.offset() + 32, 0x100); // comment length reaching past the directory
        assertOpenFails(zip.save(tmp.resolve("cd-record.mzpeak")), "record overruns directory");
    }

    @Test
    void localHeaderOffsetPastEnd(@TempDir Path tmp) throws IOException {
        ZipImage zip = ZipImage.of(ARCHIVE);
        zip.putInt(zip.record(METADATA).offset() + 42, zip.bytes.length + 1);
        assertOpenFails(zip.save(tmp.resolve("lfh-offset.mzpeak")), "size/offset out of range", METADATA);
    }

    @Test
    void storedSizeMismatch(@TempDir Path tmp) throws IOException {
        ZipImage zip = ZipImage.of(plainArchive(tmp));
        int record = zip.record(METADATA).offset();
        zip.putInt(record + 20, zip.getInt(record + 20) + 1); // compressed size no longer equals size
        assertOpenFails(zip.save(tmp.resolve("stored-size.mzpeak")), "STORED size mismatch", METADATA);
    }

    @Test
    void storedDataPastEndOfArchive(@TempDir Path tmp) throws IOException {
        ZipImage zip = ZipImage.of(plainArchive(tmp));
        int record = zip.record(METADATA).offset();
        zip.putInt(record + 20, zip.bytes.length);
        zip.putInt(record + 24, zip.bytes.length);
        assertOpenFails(zip.save(tmp.resolve("data-range.mzpeak")), "data range past end of archive", METADATA);
    }

    @Test
    void localHeaderExtraFieldPastEndOfArchive(@TempDir Path tmp) throws IOException {
        ZipImage zip = ZipImage.of(ARCHIVE);
        int header = zip.record(INDEX).localHeader();
        assertThat((long) header + 30 + zip.getShort(header + 26) + 0xFFFF).isGreaterThan(zip.bytes.length);
        zip.putShort(header + 28, 0xFFFF); // data would start past the end of the file
        assertOpenFails(zip.save(tmp.resolve("lfh-extra.mzpeak")), "data range past end of archive", INDEX);
    }

    @Test
    void corruptLocalFileHeader(@TempDir Path tmp) throws IOException {
        ZipImage zip = ZipImage.of(ARCHIVE);
        zip.putInt(zip.record(METADATA).localHeader(), 0);
        assertOpenFails(zip.save(tmp.resolve("lfh-signature.mzpeak")), "corrupt zip local file header");
    }

    @Test
    void unsupportedCompressionMethod(@TempDir Path tmp) throws IOException {
        ZipImage zip = ZipImage.of(ARCHIVE);
        CdRecord index = zip.record(INDEX);
        zip.putShort(index.offset() + 10, 12); // BZIP2, in the central directory and the local header
        zip.putShort(index.localHeader() + 8, 12);
        assertOpenFails(zip.save(tmp.resolve("method.mzpeak")), "unsupported ZIP compression method", INDEX);
    }

    @Test
    void archiveWithoutTheRequestedMember() {
        try (MzPeakSource source = MzPeakSource.open(ARCHIVE)) {
            assertThatThrownBy(() -> source.inputFile("no_such_member.parquet"))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageContainingAll("has no member", "no_such_member.parquet");
        }
    }

    @Test
    void directoryWithoutIndex(@TempDir Path tmp) throws IOException {
        Path dir = unpackedCopy(tmp.resolve("small.unpacked.mzpeak"));
        Files.delete(dir.resolve(INDEX));
        assertOpenFails(dir, "no mzpeak_index.json");
    }

    @Test
    void manifestMemberEscapingTheDatasetDirectory(@TempDir Path tmp) throws IOException {
        Path dir = unpackedCopy(tmp.resolve("small.unpacked.mzpeak"));
        // A readable copy of the member sits at ../x, so only the escape check stops the open.
        Files.copy(dir.resolve(METADATA), tmp.resolve("x"));
        Path manifest = dir.resolve(INDEX);
        String json = Files.readString(manifest, StandardCharsets.UTF_8);
        String edited = json.replace('"' + METADATA + '"', "\"../x\"");
        assertThat(edited).isNotEqualTo(json);
        Files.writeString(manifest, edited, StandardCharsets.UTF_8);
        assertOpenFails(dir, "escapes dataset directory", "../x");
    }

    @Test
    void nonExistentPath(@TempDir Path tmp) {
        assertOpenFails(tmp.resolve("absent.mzpeak"), "No such mzPeak dataset");
    }

    private static void assertOpenFails(Path dataset, String... fragments) {
        assertThatThrownBy(() -> MzPeakReader.open(dataset).close())
                .isInstanceOf(MzPeakException.class)
                .hasMessageContainingAll(fragments);
    }

    private static long spectrumCount(Path dataset) {
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            return reader.size();
        }
    }

    private static Path unpackedCopy(Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> files = Files.list(UNPACKED)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Files.copy(f, target.resolve(f.getFileName().toString()));
            }
        }
        return target;
    }

    /** The legacy unpacked members as a STORED ZIP without extra fields, the index last. */
    private static Path plainArchive(Path tmp) throws IOException {
        List<Path> members = new ArrayList<>();
        try (Stream<Path> files = Files.list(UNPACKED)) {
            files.filter(f -> !f.getFileName().toString().equals(INDEX)).sorted().forEach(members::add);
        }
        members.add(UNPACKED.resolve(INDEX));
        Path archive = tmp.resolve("plain.mzpeak");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Path f : members) {
                byte[] data = Files.readAllBytes(f);
                CRC32 crc = new CRC32();
                crc.update(data);
                ZipEntry e = new ZipEntry(f.getFileName().toString());
                e.setMethod(ZipEntry.STORED);
                e.setSize(data.length);
                e.setCompressedSize(data.length);
                e.setCrc(crc.getValue());
                out.putNextEntry(e);
                out.write(data);
                out.closeEntry();
            }
        }
        ZipImage zip = ZipImage.of(archive);
        for (CdRecord r : zip.records) {
            assertThat(zip.getShort(r.offset() + 30)).as("extra field length of %s", r.name()).isZero();
        }
        return archive;
    }

    /** One central-directory record: its absolute offset, its member name and its local-header offset. */
    record CdRecord(String name, int offset, int localHeader) {
    }

    /** An archive held in memory for editing, with its records located from the end-of-central-directory record. */
    private static final class ZipImage {

        private static final int EOCD_SIG = 0x06054b50;
        private static final int CDH_SIG = 0x02014b50;

        final byte[] bytes;
        final int eocd;
        final List<CdRecord> records = new ArrayList<>();
        private final ByteBuffer le;

        private ZipImage(byte[] bytes) {
            this.bytes = bytes;
            this.le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            this.eocd = findEocd();
            int p = cdOffset();
            int count = getShort(eocd + 10);
            for (int i = 0; i < count; i++) {
                assertThat(le.getInt(p)).as("central-directory record %d", i).isEqualTo(CDH_SIG);
                int nameLen = getShort(p + 28);
                String name = new String(bytes, p + 46, nameLen, StandardCharsets.UTF_8);
                records.add(new CdRecord(name, p, getInt(p + 42)));
                p += 46 + nameLen + getShort(p + 30) + getShort(p + 32);
            }
        }

        static ZipImage of(Path archive) throws IOException {
            return new ZipImage(Files.readAllBytes(archive));
        }

        private int findEocd() {
            for (int i = bytes.length - 22; i >= Math.max(0, bytes.length - 22 - 0xFFFF); i--) {
                if (le.getInt(i) == EOCD_SIG && getShort(i + 20) == bytes.length - i - 22) {
                    return i;
                }
            }
            throw new AssertionError("no end-of-central-directory record");
        }

        int cdOffset() {
            return getInt(eocd + 16);
        }

        CdRecord record(String name) {
            return records.stream().filter(r -> r.name().equals(name)).findFirst()
                    .orElseThrow(() -> new AssertionError("no record " + name));
        }

        int getShort(int at) {
            return le.getShort(at) & 0xFFFF;
        }

        int getInt(int at) {
            return le.getInt(at);
        }

        void putShort(int at, int value) {
            le.putShort(at, (short) value);
        }

        void putInt(int at, int value) {
            le.putInt(at, value);
        }

        Path save(Path target) throws IOException {
            return Files.write(target, bytes);
        }
    }
}
