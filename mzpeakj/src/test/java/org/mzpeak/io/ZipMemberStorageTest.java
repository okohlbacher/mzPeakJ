package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.IntegrityReport.MemberResult;
import org.mzpeak.io.IntegrityReport.Outcome;
import org.mzpeak.io.IntegrityReport.Status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * How the storage of the members of a single-file archive affects reading: the current {@code small.mzpeak} rewritten
 * with {@link ZipOutputStream}, every member STORED unless a case gives it another method, an extra member written
 * before the manifest.
 *
 * <p>R9: the container spec ({@code docs/foundations/container.md} l.20-21, l.80) requires STORED members and forbids
 * ZIP encryption. A listed member that is compressed or has the encryption flag (general-purpose bit 0) fails the open
 * with a message naming it, and {@link MzPeakReader#verifyIntegrity(Path)} reports it
 * {@link Status#REJECTED_MEMBER}; a member the manifest does not list is never read and changes nothing.
 */
class ZipMemberStorageTest {

    private static final Path SMALL = Fixtures.current("small.mzpeak");
    private static final Path UNPACKED = Fixtures.current("small.unpacked.mzpeak");
    private static final String INDEX = "mzpeak_index.json";
    private static final String EXTRA = "extra.txt";
    private static final String METADATA = "spectra_metadata.parquet";
    private static final String DATA = "spectra_data.parquet";

    @TempDir
    Path tmp;

    @Test
    void aRewrittenStoredArchiveReadsLikeTheFixture() throws IOException {
        Path archive = rewrite("stored.mzpeak", Map.of(), Map.of());

        assertReadsLikeTheFixture(archive);
        assertThat(MzPeakReader.verifyIntegrity(archive)).isEqualTo(MzPeakReader.verifyIntegrity(SMALL));
    }

    /** A member the manifest does not list is never read, whatever its method. */
    @Test
    void anUnlistedDeflatedMemberDoesNotFailTheOpen() throws IOException {
        Path archive = rewrite("extra.mzpeak", Map.of(EXTRA, ZipEntry.DEFLATED),
                Map.of(EXTRA, "not part of the dataset\n".repeat(20).getBytes(StandardCharsets.UTF_8)));
        try (FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ)) {
            Map<String, ZipIndex.Entry> index = ZipIndex.read(ch);
            assertThat(index.get(EXTRA).method()).isEqualTo(ZipIndex.DEFLATED);
            assertThat(index.get(INDEX).method()).isEqualTo(ZipIndex.STORED);
        }

        assertReadsLikeTheFixture(archive);
        IntegrityReport report = MzPeakReader.verifyIntegrity(archive);
        assertThat(report.outcome()).isEqualTo(Outcome.VALID);
        assertThat(report.members()).extracting(MemberResult::name).doesNotContain(EXTRA);
        assertThat(report).isEqualTo(MzPeakReader.verifyIntegrity(SMALL));
    }

    @Test
    void aDeflatedMetadataMemberFailsTheOpen() throws IOException {
        Path archive = rewrite("deflated-metadata.mzpeak", Map.of(METADATA, ZipEntry.DEFLATED), Map.of());

        assertOpenFails(archive, deflated(archive, METADATA));
        try (MzPeakSource source = MzPeakSource.open(archive)) {
            assertThatThrownBy(() -> source.inputFile(METADATA))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(deflated(archive, METADATA));
            assertThat(source.inputFile(DATA).getLength()).isEqualTo(Files.size(UNPACKED.resolve(DATA)));
        }
    }

    @Test
    void aDeflatedManifestFailsTheOpen() throws IOException {
        Path archive = rewrite("deflated-index.mzpeak", Map.of(INDEX, ZipEntry.DEFLATED), Map.of());

        assertOpenFails(archive, deflated(archive, INDEX));
        assertThatThrownBy(() -> MzPeakReader.verifyIntegrity(archive))
                .isInstanceOf(MzPeakException.class)
                .hasMessage(deflated(archive, INDEX));
    }

    @Test
    void theEncryptionFlagOnAStoredDataMemberFailsTheOpen() throws IOException {
        Path archive = rewrite("flagged-data.mzpeak", Map.of(), Map.of());
        ZipBytes zip = ZipBytes.of(archive);
        zip.setEncryptionFlag(DATA);
        zip.save();

        assertOpenFails(archive, encrypted(archive, DATA));
        try (MzPeakSource source = MzPeakSource.open(archive)) {
            assertThatThrownBy(() -> source.inputFile(DATA))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(encrypted(archive, DATA));
        }
        IntegrityReport report = MzPeakReader.verifyIntegrity(archive);
        assertThat(report.outcome()).isEqualTo(Outcome.INVALID);
        assertThat(report.failures()).extracting(MemberResult::name, MemberResult::status, MemberResult::reason)
                .containsExactly(tuple(DATA, Status.REJECTED_MEMBER, encrypted(archive, DATA)));
    }

    /**
     * A member encrypted with the traditional PKWARE cipher (APPNOTE 6.1): STORED, its compressed size the size plus
     * the 12-byte encryption header (Python's {@code zipfile} decrypts it with the password to the fixture's member).
     * The encryption is named, not the size difference, for which a STORED member without the flag is rejected as
     * corrupt.
     */
    @Test
    void aReallyEncryptedStoredMemberIsNamedEncrypted() throws IOException {
        byte[] plain = Files.readAllBytes(UNPACKED.resolve(DATA));
        CRC32 crc = new CRC32();
        crc.update(plain);
        byte[] cipher = PkwareCipher.encrypt(plain, (int) crc.getValue(), "secret");
        Path archive = rewriteMember(rewrite("encrypted-data.mzpeak", Map.of(), Map.of()), DATA, cipher);
        ZipBytes zip = ZipBytes.of(archive);
        zip.setEncryptionFlag(DATA);
        zip.setPlainSizeAndCrc(DATA, plain.length, (int) crc.getValue());
        zip.save();
        try (FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ)) {
            ZipIndex.Entry entry = ZipIndex.read(ch).get(DATA);
            assertThat(entry.method()).isEqualTo(ZipIndex.STORED);
            assertThat(entry.size()).isEqualTo(plain.length);
            assertThat(entry.compressedSize()).isEqualTo(plain.length + 12L);
        }

        assertOpenFails(archive, encrypted(archive, DATA));
        IntegrityReport report = MzPeakReader.verifyIntegrity(archive);
        assertThat(report.outcome()).isEqualTo(Outcome.INVALID);
        assertThat(report.failures()).extracting(MemberResult::name, MemberResult::reason)
                .containsExactly(tuple(DATA, encrypted(archive, DATA)));
    }

    /** {@link MzPeakReader#verifyIntegrity(Path)} reports a compressed member instead of throwing. */
    @Test
    void verifyIntegrityReportsADeflatedMemberAsRejected() throws IOException {
        Path archive = rewrite("deflated-metadata.mzpeak", Map.of(METADATA, ZipEntry.DEFLATED), Map.of());
        IntegrityReport fixture = MzPeakReader.verifyIntegrity(SMALL);
        String declared = fixture.members().stream()
                .filter(m -> m.name().equals(METADATA)).findFirst().orElseThrow().expected();

        IntegrityReport report = MzPeakReader.verifyIntegrity(archive);

        assertThat(report.outcome()).isEqualTo(Outcome.INVALID);
        assertThat(report.valid()).isFalse();
        assertThat(declared).isNotNull();
        assertThat(report.failures()).containsExactly(new MemberResult(METADATA, Status.REJECTED_MEMBER,
                declared, null, deflated(archive, METADATA)));
        assertThat(report.members()).filteredOn(m -> !m.name().equals(METADATA))
                .containsExactlyElementsOf(fixture.members().stream()
                        .filter(m -> !m.name().equals(METADATA)).toList());
    }

    private static String deflated(Path archive, String member) {
        return "mzPeak archive " + archive + ": member " + member
                + " is stored with ZIP compression method 8; mzPeak requires STORED members";
    }

    private static String encrypted(Path archive, String member) {
        return "mzPeak archive " + archive + ": member " + member + " is encrypted; mzPeak forbids ZIP encryption";
    }

    private static void assertOpenFails(Path archive, String message) {
        assertThatThrownBy(() -> MzPeakReader.open(archive).close())
                .isInstanceOf(MzPeakException.class)
                .hasMessage(message);
    }

    private static void assertReadsLikeTheFixture(Path archive) {
        try (MzPeakReader fixture = MzPeakReader.open(SMALL);
             MzPeakReader rewritten = MzPeakReader.open(archive)) {
            List<String> content = TestSources.content(fixture);
            assertThat(content).noneMatch(line -> line.contains(" throws "));
            assertThat(TestSources.content(rewritten)).containsExactlyElementsOf(content);
        }
    }

    /**
     * The members of {@link #SMALL} in archive order with {@code extra} inserted before the manifest, written to
     * {@code name} in the temporary directory; a member is STORED unless {@code methods} names another method.
     */
    Path rewrite(String name, Map<String, Integer> methods, Map<String, byte[]> extra) throws IOException {
        Map<String, byte[]> members = members(SMALL);
        byte[] index = members.remove(INDEX);
        members.putAll(extra);
        members.put(INDEX, index);
        Path target = tmp.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            for (Map.Entry<String, byte[]> member : members.entrySet()) {
                int method = methods.getOrDefault(member.getKey(), ZipEntry.STORED);
                if (method == ZipEntry.STORED) {
                    putStored(out, member.getKey(), member.getValue());
                } else {
                    ZipEntry e = new ZipEntry(member.getKey());
                    e.setMethod(method);
                    out.putNextEntry(e);
                    out.write(member.getValue());
                    out.closeEntry();
                }
            }
        }
        return target;
    }

    /** {@code archive} written again, all members STORED, the member {@code name} holding {@code data}. */
    private static Path rewriteMember(Path archive, String name, byte[] data) throws IOException {
        Map<String, byte[]> members = members(archive);
        members.put(name, data);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Map.Entry<String, byte[]> member : members.entrySet()) {
                putStored(out, member.getKey(), member.getValue());
            }
        }
        return archive;
    }

    /** The members of {@code archive} in archive order, read with {@link ZipFile}. */
    private static Map<String, byte[]> members(Path archive) throws IOException {
        Map<String, byte[]> members = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                try (InputStream in = zip.getInputStream(entry)) {
                    members.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return members;
    }

    private static void putStored(ZipOutputStream out, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        CRC32 crc = new CRC32();
        crc.update(data);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        e.setCrc(crc.getValue());
        out.putNextEntry(e);
        out.write(data);
        out.closeEntry();
    }

    /**
     * The bytes of an archive {@link ZipOutputStream} wrote (no comment, no zip64), whose central-directory records
     * and local headers are edited in place.
     */
    private record ZipBytes(Path path, byte[] bytes, Map<String, Integer> records) {

        static ZipBytes of(Path path) throws IOException {
            byte[] bytes = Files.readAllBytes(path);
            ByteBuffer le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int eocd = bytes.length - 22;
            assertThat(le.getInt(eocd)).isEqualTo(0x06054b50);
            int count = le.getShort(eocd + 10) & 0xFFFF;
            int p = le.getInt(eocd + 16);
            Map<String, Integer> records = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                assertThat(le.getInt(p)).isEqualTo(0x02014b50);
                int nameLen = le.getShort(p + 28) & 0xFFFF;
                records.put(new String(bytes, p + 46, nameLen, StandardCharsets.UTF_8), p);
                p += 46 + nameLen + (le.getShort(p + 30) & 0xFFFF) + (le.getShort(p + 32) & 0xFFFF);
            }
            return new ZipBytes(path, bytes, records);
        }

        ByteBuffer le() {
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }

        int localHeader(String name) {
            return le().getInt(records.get(name) + 42);
        }

        /** Sets general-purpose flag bit 0, in the central directory and the local header. */
        void setEncryptionFlag(String name) {
            int record = records.get(name);
            int header = localHeader(name);
            le().putShort(record + 8, (short) (le().getShort(record + 8) | 1));
            le().putShort(header + 6, (short) (le().getShort(header + 6) | 1));
        }

        /** Sets the uncompressed size and CRC-32, in the central directory and the local header. */
        void setPlainSizeAndCrc(String name, int size, int crc) {
            int record = records.get(name);
            int header = localHeader(name);
            le().putInt(record + 16, crc).putInt(record + 24, size);
            le().putInt(header + 14, crc).putInt(header + 22, size);
        }

        void save() throws IOException {
            Files.write(path, bytes);
        }
    }

    /** The traditional PKWARE encryption of APPNOTE 6.1 section 6.1 ("ZipCrypto"). */
    private static final class PkwareCipher {

        private final int[] keys = {0x12345678, 0x23456789, 0x34567890};

        private PkwareCipher(String password) {
            for (byte b : password.getBytes(StandardCharsets.UTF_8)) {
                update(b);
            }
        }

        /** The 12-byte encryption header (its last byte the CRC's high byte) and {@code plain}, encrypted. */
        static byte[] encrypt(byte[] plain, int crc, String password) {
            PkwareCipher cipher = new PkwareCipher(password);
            byte[] header = new byte[12];
            new Random(42).nextBytes(header);
            header[11] = (byte) (crc >>> 24);
            byte[] out = new byte[12 + plain.length];
            for (int i = 0; i < out.length; i++) {
                byte p = i < 12 ? header[i] : plain[i - 12];
                out[i] = (byte) (p ^ cipher.streamByte());
                cipher.update(p);
            }
            return out;
        }

        private int streamByte() {
            int t = (keys[2] | 2) & 0xFFFF;
            return ((t * (t ^ 1)) >>> 8) & 0xFF;
        }

        private void update(byte b) {
            keys[0] = crc32(keys[0], b);
            keys[1] = (keys[1] + (keys[0] & 0xFF)) * 134775813 + 1;
            keys[2] = crc32(keys[2], (byte) (keys[1] >>> 24));
        }

        private static int crc32(int crc, byte b) {
            int c = (crc ^ b) & 0xFF;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
            }
            return c ^ (crc >>> 8);
        }
    }
}
