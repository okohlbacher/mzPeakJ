package org.mzpeak.io;

import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.parquet.ByteArrayInputFile;
import org.mzpeak.io.parquet.FileSliceInputFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterizes what {@link ZipIndex} and {@link ZipSource} (through {@link MzPeakSource#open(Path)}) return, before
 * AP-6 moves them from {@link FileChannel} to {@code RandomAccessBytes}. It covers what {@link
 * ContainerNegativePathTest} and {@link Zip64Test} leave out: the offsets and sizes of the index, the member bytes
 * served in place, DEFLATE members, duplicate member names, files too short to be an archive and reads after
 * close. The oracle for member bytes is {@link ZipFile}, which shares no code with the reader.
 */
class ZipSourceCharacterizationTest {

    private static final Path LEGACY_ARCHIVE = Fixtures.legacy("small.mzpeak");
    private static final Path UNPACKED = Fixtures.legacy("small.unpacked.mzpeak");
    private static final String INDEX = "mzpeak_index.json";
    private static final String PEAKS = "spectra_peaks.parquet";

    static Stream<Arguments> archives() {
        return Stream.of(
                Arguments.of(LEGACY_ARCHIVE),
                Arguments.of(Fixtures.current("small.mzpeak")),
                Arguments.of(Fixtures.current("diaPASEF.grid.mzpeak")));
    }

    @ParameterizedTest
    @MethodSource("archives")
    void indexMapsEveryMemberToItsStoredBytes(Path archive) throws IOException {
        Map<String, byte[]> expected = zipFileMembers(archive);
        try (FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ)) {
            Map<String, ZipIndex.Entry> index = ZipIndex.read(ch);
            assertThat(index.keySet()).containsExactlyElementsOf(expected.keySet());
            for (ZipIndex.Entry e : index.values()) {
                byte[] bytes = expected.get(e.name());
                assertThat(index.get(e.name())).isSameAs(e);
                assertThat(e.method()).as("method of %s", e.name()).isEqualTo(ZipIndex.STORED);
                assertThat(e.size()).as("size of %s", e.name()).isEqualTo(bytes.length);
                assertThat(e.compressedSize()).as("compressed size of %s", e.name()).isEqualTo(bytes.length);
                ByteBuffer data = ByteBuffer.allocate(bytes.length);
                ch.read(data, e.dataOffset());
                assertThat(data.array()).as("bytes at the data offset of %s", e.name()).isEqualTo(bytes);
            }
            assertThat(ch.position()).as("the index reads positionally").isZero();
            // the iteration order is the central-directory order, which puts the manifest last in these archives
            assertThat(List.copyOf(index.keySet())).last().isEqualTo(INDEX);
        }
    }

    @ParameterizedTest
    @MethodSource("archives")
    void sourceServesStoredMembersInPlace(Path archive) throws IOException {
        Map<String, byte[]> expected = zipFileMembers(archive);
        try (MzPeakSource source = MzPeakSource.open(archive)) {
            assertThat(source.describe()).isEqualTo(archive.toString());
            assertThat(source.readManifestBytes()).isEqualTo(expected.get(INDEX));
            for (Map.Entry<String, byte[]> member : expected.entrySet()) {
                InputFile file = source.inputFile(member.getKey());
                assertThat(file).as("in-place file of %s", member.getKey()).isInstanceOf(FileSliceInputFile.class);
                assertThat(file.getLength()).isEqualTo(member.getValue().length);
                assertThat(readAll(file)).as("bytes of %s", member.getKey()).isEqualTo(member.getValue());
            }
        }
    }

    @Test
    void deflatedArchiveReadsLikeTheDirectory(@TempDir Path tmp) throws IOException {
        Path archive = writeArchive(tmp.resolve("deflated.mzpeak"), ZipEntry.DEFLATED, Map.of());
        Map<String, byte[]> expected = zipFileMembers(archive);
        try (MzPeakSource source = MzPeakSource.open(archive)) {
            assertThat(source.readManifestBytes()).isEqualTo(Files.readAllBytes(UNPACKED.resolve(INDEX)));
            for (Map.Entry<String, byte[]> member : expected.entrySet()) {
                InputFile file = source.inputFile(member.getKey());
                assertThat(file).as("inflated file of %s", member.getKey()).isInstanceOf(ByteArrayInputFile.class);
                assertThat(readAll(file)).as("bytes of %s", member.getKey()).isEqualTo(member.getValue());
            }
        }
        try (MzPeakReader directory = MzPeakReader.open(UNPACKED);
             MzPeakReader deflated = MzPeakReader.open(archive)) {
            List<String> content = TestSources.content(directory);
            assertThat(content).noneMatch(line -> line.contains(" throws "));
            assertThat(TestSources.content(deflated)).containsExactlyElementsOf(content);
        }
    }

    @Test
    void deflatedMemberShorterThanItsDeclaredSize(@TempDir Path tmp) throws IOException {
        Path archive = writeArchive(tmp.resolve("deflated.mzpeak"), ZipEntry.DEFLATED, Map.of());
        byte[] bytes = Files.readAllBytes(archive);
        int record = centralRecords(bytes).get(INDEX);
        ByteBuffer le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int size = le.getInt(record + 24);
        le.putInt(record + 24, size + 1);
        Files.write(archive, bytes);
        try (MzPeakSource source = MzPeakSource.open(archive)) {
            assertThatThrownBy(source::readManifestBytes)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("truncated/corrupt DEFLATE member " + INDEX + " in " + archive
                            + " (inflated " + size + " of " + (size + 1) + " bytes)");
        }
    }

    @Test
    void corruptDeflateData(@TempDir Path tmp) throws IOException {
        Path archive = writeArchive(tmp.resolve("deflated.mzpeak"), ZipEntry.DEFLATED, Map.of());
        byte[] bytes = Files.readAllBytes(archive);
        ByteBuffer le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int header = le.getInt(centralRecords(bytes).get(INDEX) + 42);
        int data = header + 30 + (le.getShort(header + 26) & 0xFFFF) + (le.getShort(header + 28) & 0xFFFF);
        bytes[data] = (byte) 0xFF; // final block of the reserved type 3
        Files.write(archive, bytes);
        try (MzPeakSource source = MzPeakSource.open(archive)) {
            assertThatThrownBy(source::readManifestBytes)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("Failed to inflate member " + INDEX + " in " + archive)
                    .hasCauseInstanceOf(DataFormatException.class);
        }
    }

    @Test
    void duplicateMemberNamesThrow(@TempDir Path tmp) throws IOException {
        // ZipOutputStream refuses duplicate names, so the copy is written as spectra_peaks.parqueX and renamed in
        // both its local header and its central-directory record.
        String alias = PEAKS.substring(0, PEAKS.length() - 1) + "X";
        Path archive = writeArchive(tmp.resolve("duplicate.mzpeak"), ZipEntry.STORED,
                Map.of(alias, Files.readAllBytes(UNPACKED.resolve(PEAKS))));
        byte[] bytes = Files.readAllBytes(archive);
        ByteBuffer le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int record = centralRecords(bytes).get(alias);
        int header = le.getInt(record + 42);
        bytes[record + 46 + alias.length() - 1] = 't';
        bytes[header + 30 + alias.length() - 1] = 't';
        Files.write(archive, bytes);
        assertThat(centralRecords(bytes)).doesNotContainKey(alias);

        assertThatThrownBy(() -> MzPeakSource.open(archive))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("duplicate member '" + PEAKS + "' in zip archive");
        assertThatThrownBy(() -> MzPeakReader.open(archive))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("duplicate member '" + PEAKS + "' in zip archive");
    }

    @Test
    void filesTooShortForAnArchive(@TempDir Path tmp) throws IOException {
        Path empty = Files.write(tmp.resolve("empty.mzpeak"), new byte[0]);
        Path tiny = Files.write(tmp.resolve("tiny.mzpeak"), "PK\u0005\u0006".getBytes(StandardCharsets.ISO_8859_1));
        for (Path file : List.of(empty, tiny)) {
            assertThatThrownBy(() -> MzPeakSource.open(file))
                    .as("opening %s", file)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("not a ZIP archive (no end-of-central-directory record found)");
        }
    }

    @Test
    void readsAfterCloseFail() throws IOException {
        MzPeakSource source = MzPeakSource.open(LEGACY_ARCHIVE);
        InputFile file = source.inputFile(PEAKS);
        try (SeekableInputStream in = file.newStream()) {
            assertThat(in.read()).isEqualTo('P');
            source.close();
            assertThatThrownBy(in::read).isInstanceOf(ClosedChannelException.class);
            assertThatThrownBy(() -> in.readFully(new byte[3])).isInstanceOf(ClosedChannelException.class);
        }
        // the index stays in memory: members still resolve, their bytes do not
        assertThat(source.inputFile(PEAKS).getLength()).isEqualTo(file.getLength());
        assertThatThrownBy(source::readManifestBytes)
                .isInstanceOf(MzPeakException.class)
                .hasMessage("Failed to read member " + INDEX + " from " + LEGACY_ARCHIVE)
                .hasCauseInstanceOf(ClosedChannelException.class);
        assertThatThrownBy(() -> source.inputFile("absent.parquet"))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("mzPeak archive " + LEGACY_ARCHIVE + " has no member absent.parquet");
        source.close(); // a second close is a no-op
    }

    /** Every member of {@code archive}, in central-directory order, read with {@link ZipFile}. */
    static Map<String, byte[]> zipFileMembers(Path archive) throws IOException {
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

    private static byte[] readAll(InputFile file) throws IOException {
        byte[] out = new byte[(int) file.getLength()];
        try (SeekableInputStream in = file.newStream()) {
            in.readFully(out);
        }
        return out;
    }

    /**
     * The legacy unpacked members, sorted, the manifest last, plus {@code extra} members before the manifest, written
     * with {@link ZipOutputStream} using {@code method}.
     */
    private static Path writeArchive(Path target, int method, Map<String, byte[]> extra) throws IOException {
        Map<String, byte[]> members = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(UNPACKED)) {
            for (Path f : (Iterable<Path>) files.sorted()::iterator) {
                if (!f.getFileName().toString().equals(INDEX)) {
                    members.put(f.getFileName().toString(), Files.readAllBytes(f));
                }
            }
        }
        members.putAll(extra);
        members.put(INDEX, Files.readAllBytes(UNPACKED.resolve(INDEX)));
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            for (Map.Entry<String, byte[]> member : members.entrySet()) {
                byte[] data = member.getValue();
                ZipEntry e = new ZipEntry(member.getKey());
                e.setMethod(method);
                if (method == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    e.setSize(data.length);
                    e.setCompressedSize(data.length);
                    e.setCrc(crc.getValue());
                }
                out.putNextEntry(e);
                out.write(data);
                out.closeEntry();
            }
        }
        return target;
    }

    /** The absolute offset of each central-directory record, by member name; the archive has no comment. */
    private static Map<String, Integer> centralRecords(byte[] bytes) {
        ByteBuffer le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = bytes.length - 22;
        assertThat(le.getInt(eocd)).isEqualTo(0x06054b50);
        int p = le.getInt(eocd + 16);
        int count = le.getShort(eocd + 10) & 0xFFFF;
        Map<String, Integer> records = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            assertThat(le.getInt(p)).isEqualTo(0x02014b50);
            int nameLen = le.getShort(p + 28) & 0xFFFF;
            String name = new String(bytes, p + 46, nameLen, StandardCharsets.UTF_8);
            records.put(name, p);
            p += 46 + nameLen + (le.getShort(p + 30) & 0xFFFF) + (le.getShort(p + 32) & 0xFFFF);
        }
        return records;
    }
}
