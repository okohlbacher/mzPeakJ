package org.mzpeak.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.parquet.io.InputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.IntegrityReport.MemberResult;
import org.mzpeak.io.IntegrityReport.Outcome;
import org.mzpeak.io.IntegrityReport.Status;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * RD-19 (D-16): {@link MzPeakReader#verifyIntegrity()} and {@link MzPeakReader#verifyIntegrity(Path)} compare the
 * SHA-512 checksum the manifest declares for each member with the checksum of the member's bytes, one
 * {@link MemberResult} per manifest entry. A missing member is {@link Status#MISSING_MEMBER}; any other failure to
 * read a member throws. The corrupted datasets are {@code @TempDir} copies of the vendored fixtures.
 */
class VerifyIntegrityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final String INDEX = "mzpeak_index.json";
    private static final Predicate<String> SHA512_HEX = s -> s.matches("[0-9a-f]{128}");

    @TempDir
    Path tmp;

    static List<String> currentNames() {
        return Fixtures.CURRENT_NAMES;
    }

    static List<String> legacyNames() {
        return Fixtures.LEGACY_NAMES;
    }

    // ---- (a) the current fixtures are VALID ----------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentNames")
    void theCurrentFixturesAreValid(String name) {
        Path dataset = Fixtures.current(name);

        IntegrityReport report = MzPeakReader.verifyIntegrity(dataset);

        assertAllMatch(report, dataset);
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            assertThat(reader.verifyIntegrity()).isEqualTo(report);
        }
    }

    @Test
    void theUnpackedFixturePackedIntoAZipIsValid() {
        Path zip = tmp.resolve("small.mzpeak");
        ArchivePacker.pack(SMALL, zip, false);

        IntegrityReport report = MzPeakReader.verifyIntegrity(zip);

        assertAllMatch(report, zip);
        assertThat(report).isEqualTo(MzPeakReader.verifyIntegrity(SMALL));
    }

    @Test
    void anUppercaseChecksumMatches() throws IOException {
        Path copy = copy(SMALL);
        editManifest(copy, "spectra_peaks.parquet", e -> e.put("checksum",
                e.get("checksum").asText().toUpperCase(Locale.ROOT)));

        IntegrityReport report = MzPeakReader.verifyIntegrity(copy);

        assertThat(report.outcome()).isEqualTo(Outcome.VALID);
        MemberResult peaks = result(report, "spectra_peaks.parquet");
        assertThat(peaks.status()).isEqualTo(Status.MATCH);
        assertThat(peaks.expected()).isEqualTo(peaks.actual().toUpperCase(Locale.ROOT));
    }

    // ---- (b) the legacy fixtures are UNVERIFIABLE ----------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("legacyNames")
    void theLegacyFixturesAreUnverifiable(String name) {
        Path dataset = Fixtures.legacy(name);

        IntegrityReport report = MzPeakReader.verifyIntegrity(dataset);

        assertThat(report.outcome()).isEqualTo(Outcome.UNVERIFIABLE);
        assertThat(report.valid()).isFalse();
        assertThat(report.failures()).isEmpty();
        assertThat(report.members()).extracting(MemberResult::name).containsExactlyElementsOf(manifestNames(dataset));
        assertThat(report.members()).allSatisfy(m -> {
            assertThat(m.status()).isEqualTo(Status.MISSING_CHECKSUM);
            assertThat(m.expected()).isNull();
            assertThat(m.actual()).hasSize(128).matches(SHA512_HEX);
        });
    }

    @Test
    void theChecksumsOfTheLegacyUnpackedFixtureAreThoseOfItsFiles() throws IOException {
        Path dataset = Fixtures.legacy("small.unpacked.mzpeak");

        for (MemberResult m : MzPeakReader.verifyIntegrity(dataset).members()) {
            assertThat(m.actual()).as(m.name()).isEqualTo(Sha512.hex(dataset.resolve(m.name())));
        }
    }

    // ---- (c) a flipped byte in the peaks member ------------------------------------------------

    @Test
    void aFlippedByteInThePeaksMemberIsAMismatch() throws IOException {
        Path copy = copy(SMALL);
        Path peaks = copy.resolve("spectra_peaks.parquet");
        flipByte(peaks, Files.size(peaks) / 2);

        IntegrityReport report = MzPeakReader.verifyIntegrity(copy);

        assertThat(report.outcome()).isEqualTo(Outcome.INVALID);
        assertThat(report.valid()).isFalse();
        assertThat(report.failures()).singleElement().satisfies(m -> {
            assertThat(m.name()).isEqualTo("spectra_peaks.parquet");
            assertThat(m.status()).isEqualTo(Status.MISMATCH);
            assertThat(m.expected()).isEqualTo(manifestChecksum(SMALL, "spectra_peaks.parquet"));
            assertThat(m.actual()).isEqualTo(Sha512.hex(peaks));
        });
        assertThat(report.members()).filteredOn(m -> !m.name().equals("spectra_peaks.parquet"))
                .hasSize(9).allSatisfy(m -> assertThat(m.status()).isEqualTo(Status.MATCH));
        try (MzPeakReader reader = MzPeakReader.open(copy)) {
            assertThat(reader.verifyIntegrity()).isEqualTo(report);
        }
    }

    // ---- (d) a flipped byte in the metadata footer ---------------------------------------------

    @Test
    void aFlippedByteInTheMetadataFooterIsReportedNotThrown() throws IOException {
        Path copy = copy(SMALL);
        Path metadata = copy.resolve("spectra_metadata.parquet");
        flipByte(metadata, Files.size(metadata) - 1); // the last byte of the trailing magic PAR1

        assertThatThrownBy(() -> MzPeakReader.open(copy)).isInstanceOf(RuntimeException.class);
        IntegrityReport report = MzPeakReader.verifyIntegrity(copy);

        assertThat(report.outcome()).isEqualTo(Outcome.INVALID);
        assertThat(report.failures()).singleElement().satisfies(m -> {
            assertThat(m.name()).isEqualTo("spectra_metadata.parquet");
            assertThat(m.status()).isEqualTo(Status.MISMATCH);
        });
    }

    // ---- (e) a deleted member ------------------------------------------------------------------

    @Test
    void aDeletedMemberIsMissing() throws IOException {
        Path copy = copy(SMALL);
        Files.delete(copy.resolve("chromatograms_data.parquet"));

        IntegrityReport report = MzPeakReader.verifyIntegrity(copy);

        assertMissingOnly(report, "chromatograms_data.parquet",
                manifestChecksum(SMALL, "chromatograms_data.parquet"));
    }

    // ---- (f) a removed checksum and a corrupted member -----------------------------------------

    @Test
    void aMismatchOutweighsAMissingChecksum() throws IOException {
        Path copy = copy(SMALL);
        editManifest(copy, "spectra_metadata_scans.parquet", e -> e.remove("checksum"));
        Path chromatograms = copy.resolve("chromatograms_data.parquet");
        flipByte(chromatograms, 100);

        IntegrityReport report = MzPeakReader.verifyIntegrity(copy);

        assertThat(report.outcome()).isEqualTo(Outcome.INVALID);
        assertThat(result(report, "spectra_metadata_scans.parquet").status()).isEqualTo(Status.MISSING_CHECKSUM);
        assertThat(result(report, "spectra_metadata_scans.parquet").actual()).matches(SHA512_HEX);
        assertThat(report.failures()).extracting(MemberResult::name, MemberResult::status)
                .containsExactly(tuple("chromatograms_data.parquet", Status.MISMATCH));
    }

    @Test
    void aMissingChecksumAloneIsUnverifiable() throws IOException {
        Path copy = copy(SMALL);
        editManifest(copy, "spectra_metadata_scans.parquet", e -> e.remove("checksum"));

        IntegrityReport report = MzPeakReader.verifyIntegrity(copy);

        assertThat(report.outcome()).isEqualTo(Outcome.UNVERIFIABLE);
        assertThat(report.failures()).isEmpty();
    }

    @Test
    void failuresAreInManifestOrder() throws IOException {
        Path copy = copy(SMALL);
        Files.delete(copy.resolve("chromatograms_data.parquet"));
        flipByte(copy.resolve("spectra_data.parquet"), 1000);

        IntegrityReport report = MzPeakReader.verifyIntegrity(copy);

        List<String> order = manifestNames(copy);
        assertThat(order.indexOf("spectra_data.parquet")).isLessThan(order.indexOf("chromatograms_data.parquet"));
        assertThat(report.failures()).extracting(MemberResult::name, MemberResult::status).containsExactly(
                tuple("spectra_data.parquet", Status.MISMATCH),
                tuple("chromatograms_data.parquet", Status.MISSING_MEMBER));
    }

    // ---- (g) a flipped byte inside a STORED ZIP ------------------------------------------------

    @Test
    void aFlippedByteInsideAStoredZipIsAMismatch() throws IOException {
        Path zip = tmp.resolve("small.mzpeak");
        Files.copy(Fixtures.current("small.mzpeak"), zip);
        ZipIndex.Entry data;
        try (FileChannel channel = FileChannel.open(zip, StandardOpenOption.READ)) {
            data = ZipIndex.read(channel).get("spectra_data.parquet");
        }
        assertThat(data.method()).isEqualTo(ZipIndex.STORED);
        flipByte(zip, data.dataOffset() + data.size() / 2);

        IntegrityReport report = MzPeakReader.verifyIntegrity(zip);

        assertThat(report.outcome()).isEqualTo(Outcome.INVALID);
        assertThat(report.failures()).extracting(MemberResult::name, MemberResult::status).containsExactly(
                tuple("spectra_data.parquet", Status.MISMATCH));
    }

    // ---- (h) an archive the writer writes is VALID ---------------------------------------------

    @Test
    void anArchiveTheWriterWritesIsValid() {
        List<Spectrum> spectra = new ArrayList<>();
        List<Chromatogram> chromatograms;
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            reader.forEach(spectra::add);
            chromatograms = reader.chromatograms();
        }
        Path archive = tmp.resolve("written.mzpeak");
        MzPeakWriter.writeArchive(archive, spectra, chromatograms);

        IntegrityReport report = MzPeakReader.verifyIntegrity(archive);

        assertAllMatch(report, archive);
        try (MzPeakReader reader = MzPeakReader.open(archive)) {
            assertThat(reader.verifyIntegrity()).isEqualTo(report);
        }
    }

    // ---- (i) presence: absence is MISSING_MEMBER, other failures throw -------------------------

    @Test
    void aMemberAbsentFromARepackedZipIsMissing() throws IOException {
        Path copy = copy(SMALL);
        Files.delete(copy.resolve("spectra_metadata_selected_ions.parquet"));
        Path zip = tmp.resolve("repacked.mzpeak");
        ArchivePacker.pack(copy, zip, false);

        IntegrityReport report = MzPeakReader.verifyIntegrity(zip);

        assertMissingOnly(report, "spectra_metadata_selected_ions.parquet",
                manifestChecksum(SMALL, "spectra_metadata_selected_ions.parquet"));
    }

    @Test
    void aMemberTheOpenerReturnsNullForIsMissingAndEachMemberIsOpenedOnce() {
        Map<String, Integer> opened = new ConcurrentHashMap<>();
        MzPeakSource.MemberOpener opener = name -> {
            opened.merge(name, 1, Integer::sum);
            return name.equals("chromatograms_data.parquet") ? null : openFile(SMALL.resolve(name));
        };

        IntegrityReport report;
        try (MzPeakReader reader = MzPeakReader.open(MzPeakSource.unpacked("unpacked small", opener))) {
            report = reader.verifyIntegrity();
        }

        assertMissingOnly(report, "chromatograms_data.parquet",
                manifestChecksum(SMALL, "chromatograms_data.parquet"));
        opened.remove("chromatograms_data.parquet");
        assertThat(opened.keySet()).hasSize(10).contains(INDEX);
        assertThat(opened.values()).containsOnly(1);
    }

    @Test
    void anOpenerThatThrowsIsAFailureNotAMissingMember() {
        MzPeakSource.MemberOpener opener = name -> {
            if (name.equals("chromatograms_data.parquet")) {
                throw new IOException("network down");
            }
            return openFile(SMALL.resolve(name));
        };

        try (MzPeakReader reader = MzPeakReader.open(MzPeakSource.unpacked("unpacked small", opener))) {
            assertThatThrownBy(reader::verifyIntegrity).isInstanceOf(MzPeakException.class)
                    .hasMessageContaining("chromatograms_data.parquet").rootCause().hasMessage("network down");
        }
    }

    @Test
    void anUnreadableMemberIsAFailureNotAMissingMember() throws IOException {
        Path copy = copy(SMALL);
        Path member = copy.resolve("chromatograms_data.parquet");
        assumeTrue(Files.getFileStore(member).supportsFileAttributeView(PosixFileAttributeView.class),
                "no POSIX permissions");
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(member);
        Files.setPosixFilePermissions(member, Set.of());
        try {
            assumeFalse(Files.isReadable(member), "running as root: chmod 000 does not prevent reading");

            assertThatThrownBy(() -> MzPeakReader.verifyIntegrity(copy)).isInstanceOf(MzPeakException.class)
                    .hasMessageContaining("chromatograms_data.parquet");
        } finally {
            Files.setPosixFilePermissions(member, permissions);
        }
    }

    @Test
    void aMemberThatIsADirectoryIsAFailureNotAMissingMember() throws IOException {
        Path copy = copy(SMALL);
        Path member = copy.resolve("chromatograms_data.parquet");
        Files.delete(member);
        Files.createDirectory(member);

        assertThatThrownBy(() -> MzPeakReader.verifyIntegrity(copy)).isInstanceOf(MzPeakException.class)
                .hasMessageContaining("chromatograms_data.parquet");
    }

    @Test
    void aMemberNameThatEscapesTheDirectoryThrows() throws IOException {
        Path copy = copy(SMALL);
        editManifest(copy, "chromatograms_data.parquet", e -> e.put("name", "../chromatograms_data.parquet"));

        assertThatThrownBy(() -> MzPeakReader.verifyIntegrity(copy)).isInstanceOf(MzPeakException.class)
                .hasMessageContaining("escapes dataset directory");
    }

    @Test
    void anotherSourceImplementationIsReadAndAMissingMemberThrows() throws IOException {
        Path copy = copy(SMALL);

        try (MzPeakReader reader = MzPeakReader.open(new Delegating(MzPeakSource.open(copy)))) {
            assertAllMatch(reader.verifyIntegrity(), copy);
            Files.delete(copy.resolve("chromatograms_data.parquet"));
            assertThatThrownBy(reader::verifyIntegrity).isInstanceOf(MzPeakException.class)
                    .hasMessageContaining("chromatograms_data.parquet");
        }
    }

    // ---- API contract --------------------------------------------------------------------------

    @Test
    void aClosedReaderRefusesToVerify() {
        MzPeakReader reader = MzPeakReader.open(SMALL);
        reader.close();

        assertThatThrownBy(reader::verifyIntegrity).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aNullOrAbsentDatasetIsRefused() {
        assertThatThrownBy(() -> MzPeakReader.verifyIntegrity(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("dataset");
        assertThatThrownBy(() -> MzPeakReader.verifyIntegrity(tmp.resolve("none.mzpeak")))
                .isInstanceOf(MzPeakException.class).hasMessageContaining("No such mzPeak dataset");
    }

    @Test
    void theReportIsUnmodifiableAndRejectsNulls() {
        MemberResult match = new MemberResult("a", Status.MATCH, "x", "x");
        List<MemberResult> members = new ArrayList<>(List.of(match));
        IntegrityReport report = new IntegrityReport(Outcome.VALID, members);
        members.clear();

        assertThat(report.members()).containsExactly(match);
        assertThat(report.valid()).isTrue();
        assertThatThrownBy(() -> report.members().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> report.failures().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new IntegrityReport(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IntegrityReport(Outcome.VALID, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MemberResult(null, Status.MATCH, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MemberResult("a", null, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void anEmptyManifestIsValid() throws IOException {
        Path copy = copy(SMALL);
        ObjectNode index = (ObjectNode) MAPPER.readTree(copy.resolve(INDEX).toFile());
        index.putArray("files");
        Files.writeString(copy.resolve(INDEX), MAPPER.writeValueAsString(index), StandardCharsets.UTF_8);

        IntegrityReport report = MzPeakReader.verifyIntegrity(copy);

        assertThat(report.outcome()).isEqualTo(Outcome.VALID);
        assertThat(report.members()).isEmpty();
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Every member MATCH, in manifest order, with the manifest checksum and a lowercase 128-character actual. */
    private static void assertAllMatch(IntegrityReport report, Path dataset) {
        assertThat(report.outcome()).isEqualTo(Outcome.VALID);
        assertThat(report.valid()).isTrue();
        assertThat(report.failures()).isEmpty();
        assertThat(report.members()).extracting(MemberResult::name).containsExactlyElementsOf(manifestNames(dataset));
        assertThat(report.members()).isNotEmpty().allSatisfy(m -> {
            assertThat(m.status()).isEqualTo(Status.MATCH);
            assertThat(m.actual()).hasSize(128).matches(SHA512_HEX).isEqualToIgnoringCase(m.expected());
        });
    }

    private static void assertMissingOnly(IntegrityReport report, String missing, String expected) {
        assertThat(report.outcome()).isEqualTo(Outcome.INVALID);
        assertThat(report.failures()).singleElement().satisfies(m -> {
            assertThat(m.name()).isEqualTo(missing);
            assertThat(m.status()).isEqualTo(Status.MISSING_MEMBER);
            assertThat(m.expected()).isEqualTo(expected);
            assertThat(m.actual()).isNull();
        });
        assertThat(report.members()).filteredOn(m -> !m.name().equals(missing))
                .isNotEmpty().allSatisfy(m -> assertThat(m.status()).isEqualTo(Status.MATCH));
    }

    private static MemberResult result(IntegrityReport report, String name) {
        return report.members().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static List<String> manifestNames(Path dataset) {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            return MzPeakManifest.fromSource(source).files().stream().map(MzPeakManifest.Entry::name).toList();
        }
    }

    private static String manifestChecksum(Path dataset, String name) {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            return MzPeakManifest.fromSource(source).files().stream().filter(e -> e.name().equals(name))
                    .findFirst().orElseThrow().checksum();
        }
    }

    /** A copy of the unpacked dataset {@code dataset} in the temp directory. */
    private Path copy(Path dataset) throws IOException {
        Path copy = tmp.resolve(dataset.getFileName());
        Files.createDirectories(copy);
        try (Stream<Path> members = Files.list(dataset)) {
            for (Path f : (Iterable<Path>) members::iterator) {
                Files.copy(f, copy.resolve(f.getFileName()));
            }
        }
        return copy;
    }

    /** Applies {@code edit} to the manifest entry of {@code dataset} named {@code name}. */
    private static void editManifest(Path dataset, String name, Consumer<ObjectNode> edit)
            throws IOException {
        ObjectNode index = (ObjectNode) MAPPER.readTree(dataset.resolve(INDEX).toFile());
        index.get("files").forEach(e -> {
            if (e.get("name").asText().equals(name)) {
                edit.accept((ObjectNode) e);
            }
        });
        Files.writeString(dataset.resolve(INDEX), MAPPER.writeValueAsString(index), StandardCharsets.UTF_8);
    }

    private static void flipByte(Path file, long offset) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer b = ByteBuffer.allocate(1);
            channel.read(b, offset);
            b.put(0, (byte) (b.get(0) ^ 0xFF));
            b.rewind();
            channel.write(b, offset);
        }
    }

    private static RandomAccessBytes openFile(Path file) throws IOException {
        return RandomAccessBytes.of(FileChannel.open(file, StandardOpenOption.READ));
    }

    /** A source implementation other than the built-in ones, delegating to one of them. */
    private record Delegating(MzPeakSource delegate) implements MzPeakSource {

        @Override
        public byte[] readManifestBytes() {
            return delegate.readManifestBytes();
        }

        @Override
        public InputFile inputFile(String name) {
            return delegate.inputFile(name);
        }

        @Override
        public String describe() {
            return delegate.describe();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
