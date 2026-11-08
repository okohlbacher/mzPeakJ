package org.mzpeak.io;

import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.parquet.FileSliceInputFile;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AP-6: {@link MzPeakSource#zip(String, RandomAccessBytes)} and
 * {@link MzPeakSource#unpacked(String, MzPeakSource.MemberOpener)} read datasets from storage the caller supplies as
 * {@link RandomAccessBytes} (design sections 9.1 and 9.2).
 *
 * <p>Oracle: {@link MzPeakReader#open(Path)} on the same fixture, compared through {@link TestSources#content}. The
 * archives are served from memory by {@link #inMemory(byte[])}, which returns at most {@value #MAX_READ} bytes per read
 * so that every caller has to cope with short reads, as it would with a remote store.
 */
class RemoteSourceTest {

    private static final int MAX_READ = 8192;
    private static final String INDEX = "mzpeak_index.json";

    static Stream<Path> zipFixtures() {
        return Stream.concat(
                Fixtures.CURRENT_NAMES.stream().map(Fixtures::current),
                Fixtures.LEGACY_NAMES.stream().map(Fixtures::legacy))
                .filter(Files::isRegularFile);
    }

    static Stream<Path> unpackedFixtures() {
        return Stream.of(Fixtures.current("small.unpacked.mzpeak"), Fixtures.legacy("small.unpacked.mzpeak"));
    }

    @Test
    void everyZipFixtureIsCovered() {
        assertThat(zipFixtures()).hasSize(11);
    }

    @ParameterizedTest
    @MethodSource("zipFixtures")
    void zipReadsLikeThePath(Path fixture) throws IOException {
        Counting bytes = new Counting(inMemory(Files.readAllBytes(fixture)));
        MzPeakSource source = MzPeakSource.zip("memory:" + fixture, bytes);
        assertThat(source.describe()).isEqualTo("memory:" + fixture);
        try (MzPeakReader fromPath = MzPeakReader.open(fixture);
             MzPeakReader fromBytes = MzPeakReader.open(source)) {
            // Everything is compared, diaPASEF.grid's spectra included: the grid decoder (RD-20) is in place, so the
            // comparison is not limited to its metadata, chromatograms and file metadata.
            List<String> expected = TestSources.content(fromPath);
            assertThat(fromPath.size()).isPositive();
            assertThat(expected).noneMatch(line -> line.contains(" throws "));
            assertThat(TestSources.content(fromBytes)).containsExactlyElementsOf(expected);
            assertThat(bytes.closes()).isZero();
        }
        assertThat(bytes.closes()).isEqualTo(1);
        assertThat(bytes.maxEnd()).as("end of the furthest read").isLessThanOrEqualTo(Files.size(fixture));
        assertThat(bytes.readsAtOrPastEnd()).as("reads starting at or past size()").isZero();
    }

    @Test
    void zipMembersMatchTheArchiveUnderConcurrentReads() throws Exception {
        Path fixture = Fixtures.current("small.mzpeak");
        Map<String, byte[]> expected = ZipSourceCharacterizationTest.zipFileMembers(fixture);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try (MzPeakSource source = MzPeakSource.zip("memory", inMemory(Files.readAllBytes(fixture)))) {
            List<Future<Boolean>> reads = new ArrayList<>();
            for (int round = 0; round < 3; round++) {
                for (Map.Entry<String, byte[]> member : expected.entrySet()) {
                    InputFile file = source.inputFile(member.getKey());
                    assertThat(file).isInstanceOf(FileSliceInputFile.class);
                    reads.add(pool.submit(() -> Arrays.equals(readAll(file), member.getValue())));
                }
            }
            for (Future<Boolean> read : reads) {
                assertThat(read.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void zipTakesOwnershipOfUnreadableBytes() throws IOException {
        Counting notAnArchive = new Counting(inMemory(
                Files.readAllBytes(Fixtures.legacy("small.unpacked.mzpeak").resolve(INDEX))));
        assertThatThrownBy(() -> MzPeakSource.zip("memory:index", notAnArchive))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("not a ZIP archive (no end-of-central-directory record found)");
        assertThat(notAnArchive.closes()).isEqualTo(1);

        Counting failing = new Counting(new RandomAccessBytes() {
            @Override
            public long size() throws IOException {
                throw new IOException("connection reset");
            }

            @Override
            public int read(ByteBuffer dst, long position) {
                throw new AssertionError("not reached");
            }

            @Override
            public void close() {
            }
        });
        assertThatThrownBy(() -> MzPeakSource.zip("https://example.org/a.mzpeak", failing))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("Failed to open mzPeak archive https://example.org/a.mzpeak")
                .cause().hasMessage("connection reset");
        assertThat(failing.closes()).isEqualTo(1);
    }

    @Test
    void zipDuplicateMemberNamesStillThrow() throws IOException {
        // spectra_metadata_scans.parquet and chromatograms_metadata.parquet have names of the same length; the second
        // is renamed to the first in its central-directory record and its local header
        Path fixture = Fixtures.current("small.mzpeak");
        byte[] archive = Files.readAllBytes(fixture);
        String kept = "spectra_metadata_scans.parquet";
        String renamed = "chromatograms_metadata.parquet";
        assertThat(kept).hasSameSizeAs(renamed);
        ByteBuffer le = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        int record = centralRecord(archive, renamed);
        byte[] name = kept.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(name, 0, archive, record + 46, name.length);
        System.arraycopy(name, 0, archive, le.getInt(record + 42) + 30, name.length);

        Counting bytes = new Counting(inMemory(archive));
        assertThatThrownBy(() -> MzPeakSource.zip("memory:duplicate", bytes))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("duplicate member '" + kept + "' in zip archive");
        assertThat(bytes.closes()).isEqualTo(1);
    }

    @Test
    void zipNamesItsDescriptionInMessages() throws IOException {
        Path fixture = Fixtures.legacy("small.mzpeak");
        try (MzPeakSource source = MzPeakSource.zip("s3://bucket/small.mzpeak",
                inMemory(Files.readAllBytes(fixture)))) {
            assertThat(source.describe()).isEqualTo("s3://bucket/small.mzpeak");
            assertThat(source.readManifestBytes()).isEqualTo(
                    ZipSourceCharacterizationTest.zipFileMembers(fixture).get(INDEX));
            assertThatThrownBy(() -> source.inputFile("absent.parquet"))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("mzPeak archive s3://bucket/small.mzpeak has no member absent.parquet");
        }
    }

    @Test
    void nullArgumentsThrowNullPointerException() throws IOException {
        Counting bytes = new Counting(inMemory(new byte[0]));
        assertThatThrownBy(() -> MzPeakSource.zip(null, bytes)).isInstanceOf(NullPointerException.class);
        assertThat(bytes.closes()).as("zip owns the bytes it is handed").isEqualTo(1);
        assertThatThrownBy(() -> MzPeakSource.zip("d", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MzPeakSource.unpacked(null, name -> null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MzPeakSource.unpacked("d", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RandomAccessBytes.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FileSliceInputFile((RandomAccessBytes) null, 0, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @MethodSource("unpackedFixtures")
    void unpackedReadsLikeTheDirectory(Path dir) {
        Opener opener = new Opener(dir);
        try (MzPeakReader fromPath = MzPeakReader.open(dir);
             MzPeakReader fromOpener = MzPeakReader.open(MzPeakSource.unpacked("files:" + dir, opener))) {
            List<String> expected = TestSources.content(fromPath);
            assertThat(expected).noneMatch(line -> line.contains(" throws "));
            assertThat(TestSources.content(fromOpener)).containsExactlyElementsOf(expected);
            assertThat(opener.opened()).doesNotHaveDuplicates()
                    .contains(INDEX, "spectra_metadata.parquet", "spectra_data.parquet", "spectra_peaks.parquet");
            assertThat(opener.closes()).allSatisfy((name, closes) -> assertThat(closes).as(name).isZero());
        }
        assertThat(opener.closes()).hasSameSizeAs(opener.opened())
                .allSatisfy((name, closes) -> assertThat(closes).as("closes of %s", name).isEqualTo(1));
    }

    @Test
    void unpackedOpensEachMemberOnceOnFirstUse() throws IOException {
        Path dir = Fixtures.legacy("small.unpacked.mzpeak");
        Opener opener = new Opener(dir);
        MzPeakSource source = MzPeakSource.unpacked("files", opener);
        assertThat(source.describe()).isEqualTo("files");
        assertThat(opener.opened()).isEmpty();

        byte[] peaks = Files.readAllBytes(dir.resolve("spectra_peaks.parquet"));
        InputFile first = source.inputFile("spectra_peaks.parquet");
        InputFile second = source.inputFile("spectra_peaks.parquet");
        assertThat(first.getLength()).isEqualTo(second.getLength()).isEqualTo(peaks.length);
        assertThat(readAll(first)).isEqualTo(peaks);
        assertThat(readAll(second)).isEqualTo(peaks);
        assertThat(source.readManifestBytes()).isEqualTo(Files.readAllBytes(dir.resolve(INDEX)));
        assertThat(source.readManifestBytes()).isEqualTo(Files.readAllBytes(dir.resolve(INDEX)));
        assertThat(opener.opened()).containsExactly("spectra_peaks.parquet", INDEX);

        source.close();
        assertThat(opener.closes()).containsOnly(Map.entry("spectra_peaks.parquet", 1), Map.entry(INDEX, 1));
        source.close();
        assertThat(opener.closes()).containsOnly(Map.entry("spectra_peaks.parquet", 1), Map.entry(INDEX, 1));
        assertThatThrownBy(() -> source.inputFile("spectra_data.parquet"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("files is closed");
        assertThat(opener.opened()).containsExactly("spectra_peaks.parquet", INDEX);
    }

    @Test
    void unpackedRejectsEscapingNamesWithoutCallingTheOpener() {
        Opener opener = new Opener(Fixtures.legacy("small.unpacked.mzpeak"));
        try (MzPeakSource source = MzPeakSource.unpacked("files", opener)) {
            for (String name : List.of("../x", "..", "a/../../x", "a/..", "/etc/passwd", "\\x", "a\\..\\x", "C:/x")) {
                assertThatThrownBy(() -> source.inputFile(name))
                        .as(name)
                        .isInstanceOf(MzPeakException.class)
                        .hasMessage("manifest member escapes dataset directory: " + name + " in files");
            }
            assertThat(opener.opened()).isEmpty();
            // names that only look similar are passed on
            assertThatThrownBy(() -> source.inputFile("..x/a.parquet"))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("Failed to open member ..x/a.parquet of files")
                    .hasCauseInstanceOf(NoSuchFileException.class);
            assertThat(opener.opened()).containsExactly("..x/a.parquet");
        }
    }

    @Test
    void unpackedRejectsAnEscapingManifestMember(@TempDir Path tmp) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("small.unpacked.mzpeak"));
        Path legacy = Fixtures.legacy("small.unpacked.mzpeak");
        try (Stream<Path> files = Files.list(legacy)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Files.copy(f, dir.resolve(f.getFileName().toString()));
            }
        }
        // A readable copy of the member sits at ../x, so only the name check stops the open.
        Files.copy(dir.resolve("spectra_metadata.parquet"), tmp.resolve("x"));
        String json = Files.readString(dir.resolve(INDEX), StandardCharsets.UTF_8);
        String edited = json.replace("\"spectra_metadata.parquet\"", "\"../x\"");
        assertThat(edited).isNotEqualTo(json);
        Files.writeString(dir.resolve(INDEX), edited, StandardCharsets.UTF_8);

        Opener opener = new Opener(dir);
        assertThatThrownBy(() -> MzPeakReader.open(MzPeakSource.unpacked("files", opener)))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("manifest member escapes dataset directory: ../x in files");
        assertThat(opener.opened()).containsExactly(INDEX);
        assertThat(opener.closes()).containsOnly(Map.entry(INDEX, 1));
    }

    @Test
    void unpackedReportsMissingAndUnopenableMembers() {
        Path dir = Fixtures.legacy("small.unpacked.mzpeak");
        Opener opener = new Opener(dir);
        try (MzPeakSource source = MzPeakSource.unpacked("files", name ->
                name.equals("absent.parquet") ? null : opener.open(name))) {
            assertThatThrownBy(() -> source.inputFile("absent.parquet"))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("files has no member absent.parquet");
            assertThatThrownBy(() -> source.inputFile("missing.parquet"))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("Failed to open member missing.parquet of files")
                    .hasCauseInstanceOf(NoSuchFileException.class);
        }
        try (MzPeakSource empty = MzPeakSource.unpacked("nothing", name -> null)) {
            assertThatThrownBy(() -> MzPeakReader.open(empty))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("nothing has no member " + INDEX);
        }
    }

    @Test
    void randomAccessBytesOfAFileChannel() throws IOException {
        Path file = Fixtures.legacy("small.unpacked.mzpeak").resolve(INDEX);
        byte[] expected = Files.readAllBytes(file);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        try (RandomAccessBytes bytes = RandomAccessBytes.of(channel)) {
            assertThat(bytes.size()).isEqualTo(expected.length);
            ByteBuffer buf = ByteBuffer.allocate(16);
            assertThat(bytes.read(buf, 10)).isEqualTo(16);
            assertThat(buf.array()).isEqualTo(Arrays.copyOfRange(expected, 10, 26));
            assertThat(channel.position()).isZero();
            buf.clear();
            assertThat(bytes.read(buf, expected.length - 4)).isEqualTo(4);
            buf.clear();
            assertThat(bytes.read(buf, expected.length)).isEqualTo(-1);
            assertThat(bytes.read(ByteBuffer.allocate(0), 0)).isZero();
        }
        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    void sliceOverBytesReadsLikeSliceOverChannel() throws IOException {
        Path file = Fixtures.legacy("small.unpacked.mzpeak").resolve("spectra_peaks.parquet");
        byte[] all = Files.readAllBytes(file);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            InputFile overChannel = new FileSliceInputFile(channel, 100, 5000);
            InputFile overBytes = new FileSliceInputFile(inMemory(all), 100, 5000);
            assertThat(overBytes.getLength()).isEqualTo(overChannel.getLength());
            assertThat(readAll(overBytes)).isEqualTo(readAll(overChannel))
                    .isEqualTo(Arrays.copyOfRange(all, 100, 5100));
            try (SeekableInputStream in = overBytes.newStream()) {
                in.seek(5000 - 3);
                assertThat(in.read(new byte[10], 0, 10)).isEqualTo(3);
                assertThat(in.read()).isEqualTo(-1);
            }
        }
    }

    @Test
    void publicApiOfTheSourceSpi() throws NoSuchMethodException {
        assertThat(abstractMethods(RandomAccessBytes.class))
                .containsExactly("close()", "read(ByteBuffer,long)", "size()");
        assertThat(Modifier.isStatic(RandomAccessBytes.class.getMethod("of", FileChannel.class).getModifiers()))
                .isTrue();
        assertThat(abstractMethods(MzPeakSource.MemberOpener.class)).containsExactly("open(String)");
        assertThat(Modifier.isPublic(MzPeakSource.MemberOpener.class.getModifiers())).isTrue();
        assertThat(MzPeakSource.class.getMethod("open", Path.class).getReturnType()).isEqualTo(MzPeakSource.class);
        assertThat(MzPeakSource.class.getMethod("zip", String.class, RandomAccessBytes.class).getReturnType())
                .isEqualTo(MzPeakSource.class);
        assertThat(MzPeakSource.class.getMethod("unpacked", String.class, MzPeakSource.MemberOpener.class)
                .getReturnType()).isEqualTo(MzPeakSource.class);
        assertThat(FileSliceInputFile.class.getConstructor(FileChannel.class, long.class, long.class)).isNotNull();
        assertThat(FileSliceInputFile.class.getConstructor(RandomAccessBytes.class, long.class, long.class))
                .isNotNull();
    }

    private static List<String> abstractMethods(Class<?> type) {
        return Arrays.stream(type.getMethods())
                .filter(m -> Modifier.isAbstract(m.getModifiers()))
                .map(RemoteSourceTest::signature)
                .sorted()
                .collect(Collectors.toList());
    }

    private static String signature(Method m) {
        return m.getName() + Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName)
                .collect(Collectors.joining(",", "(", ")"));
    }

    private static byte[] readAll(InputFile file) throws IOException {
        byte[] out = new byte[(int) file.getLength()];
        try (SeekableInputStream in = file.newStream()) {
            in.readFully(out);
        }
        return out;
    }

    /** The offset of the central-directory record of {@code name}; the archive has no comment. */
    private static int centralRecord(byte[] archive, String name) {
        ByteBuffer le = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = archive.length - 22;
        assertThat(le.getInt(eocd)).isEqualTo(0x06054b50);
        int p = le.getInt(eocd + 16);
        for (int i = 0; i < (le.getShort(eocd + 10) & 0xFFFF); i++) {
            assertThat(le.getInt(p)).isEqualTo(0x02014b50);
            int nameLen = le.getShort(p + 28) & 0xFFFF;
            if (new String(archive, p + 46, nameLen, StandardCharsets.UTF_8).equals(name)) {
                return p;
            }
            p += 46 + nameLen + (le.getShort(p + 30) & 0xFFFF) + (le.getShort(p + 32) & 0xFFFF);
        }
        throw new AssertionError("no central-directory record " + name);
    }

    /**
     * {@code data} as {@link RandomAccessBytes}, returning at most {@value #MAX_READ} bytes per read. Reads after
     * {@link RandomAccessBytes#close()} throw.
     */
    static RandomAccessBytes inMemory(byte[] data) {
        return new RandomAccessBytes() {
            private volatile boolean closed;

            @Override
            public long size() throws IOException {
                checkOpen();
                return data.length;
            }

            @Override
            public int read(ByteBuffer dst, long position) throws IOException {
                checkOpen();
                if (position < 0) {
                    throw new IllegalArgumentException("negative position " + position);
                }
                if (!dst.hasRemaining()) {
                    return 0;
                }
                if (position >= data.length) {
                    return -1;
                }
                int n = (int) Math.min(Math.min(dst.remaining(), MAX_READ), data.length - position);
                dst.put(data, (int) position, n);
                return n;
            }

            private void checkOpen() throws IOException {
                if (closed) {
                    throw new IOException("closed");
                }
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }

    /** Counts the bytes read, the furthest position asked for, the reads starting at or past the end and the closes. */
    static final class Counting implements RandomAccessBytes {

        private final RandomAccessBytes delegate;
        private final long size;
        private final AtomicLong bytesRead = new AtomicLong();
        private final AtomicLong maxEnd = new AtomicLong();
        private final AtomicInteger readsAtOrPastEnd = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        Counting(RandomAccessBytes delegate) {
            this.delegate = delegate;
            this.size = sizeOrMinusOne(delegate);
        }

        private static long sizeOrMinusOne(RandomAccessBytes delegate) {
            try {
                return delegate.size();
            } catch (IOException e) {
                return -1;
            }
        }

        long bytesRead() {
            return bytesRead.get();
        }

        long maxEnd() {
            return maxEnd.get();
        }

        int readsAtOrPastEnd() {
            return readsAtOrPastEnd.get();
        }

        int closes() {
            return closes.get();
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public int read(ByteBuffer dst, long position) throws IOException {
            maxEnd.accumulateAndGet(position + dst.remaining(), Math::max);
            if (position >= size) {
                readsAtOrPastEnd.incrementAndGet();
            }
            int n = delegate.read(dst, position);
            if (n > 0) {
                bytesRead.addAndGet(n);
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            closes.incrementAndGet();
            delegate.close();
        }
    }

    /** Opens the members of a local directory, logging the names asked for and counting the closes per member. */
    static final class Opener implements MzPeakSource.MemberOpener {

        private final Path dir;
        private final List<String> opened = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, AtomicInteger> closes = Collections.synchronizedMap(new LinkedHashMap<>());

        Opener(Path dir) {
            this.dir = dir;
        }

        /** The names asked for, in call order, repeats included. */
        List<String> opened() {
            synchronized (opened) {
                return List.copyOf(opened);
            }
        }

        /** The closes per member opened. */
        Map<String, Integer> closes() {
            synchronized (closes) {
                Map<String, Integer> out = new LinkedHashMap<>();
                closes.forEach((name, count) -> out.put(name, count.get()));
                return out;
            }
        }

        @Override
        public RandomAccessBytes open(String name) throws IOException {
            opened.add(name);
            RandomAccessBytes bytes = RandomAccessBytes.of(FileChannel.open(dir.resolve(name),
                    StandardOpenOption.READ));
            AtomicInteger count = new AtomicInteger();
            closes.put(name, count);
            return new RandomAccessBytes() {
                @Override
                public long size() throws IOException {
                    return bytes.size();
                }

                @Override
                public int read(ByteBuffer dst, long position) throws IOException {
                    return bytes.read(dst, position);
                }

                @Override
                public void close() throws IOException {
                    count.incrementAndGet();
                    bytes.close();
                }
            };
        }
    }
}
