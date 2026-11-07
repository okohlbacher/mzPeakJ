package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.parquet.io.SeekableInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that the vendored current examples ({@link Fixtures#CURRENT}, HUPO-PSI/mzPeak 2ee2589) arrived intact
 * and conform to the spec's basic-integrity rule (conformance.md, "Basic Integrity"): every member described in
 * {@code mzpeak_index.json} carries a lowercase hex SHA-512 checksum, and rehashing the member reproduces it.
 * Members are streamed through {@link MzPeakSource#inputFile(String)}, so a ZIP member is hashed over the bytes
 * the reader sees. In each single-file archive the index is the last entry, after the members it describes.
 *
 * <p>The reader does not yet decode this layout; this class opens the current set only through
 * {@link MzPeakSource}.
 */
class UpstreamFixtureIntegrityTest {

    private static final String INDEX = "mzpeak_index.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One manifest member: its name, the checksum the index declares, and the SHA-512 of its bytes. */
    record Member(String name, String expected, String actual) {
        boolean matches() {
            return actual.equals(expected);
        }
    }

    static Stream<String> currentFixtures() {
        return Fixtures.CURRENT_NAMES.stream();
    }

    static Stream<String> currentArchives() {
        return currentFixtures().filter(name -> Files.isRegularFile(Fixtures.current(name)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void everyMemberMatchesItsManifestChecksum(String fixture) throws IOException {
        List<Member> members = members(Fixtures.current(fixture));
        assertThat(members).as("manifest members of %s", fixture).isNotEmpty();
        for (Member m : members) {
            assertThat(m.expected()).as("%s: checksum of %s", fixture, m.name()).matches("[0-9a-f]{128}");
            assertThat(m.actual()).as("%s: SHA-512 of %s", fixture, m.name()).isEqualTo(m.expected());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentArchives")
    void indexIsTheLastArchiveEntry(String fixture) throws IOException {
        Path archive = Fixtures.current(fixture);
        List<String> central = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            zip.stream().forEach(e -> central.add(e.getName()));
        }
        List<String> local = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(archive))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                local.add(e.getName());
            }
        }
        List<String> described = new ArrayList<>();
        for (Member m : members(archive)) {
            described.add(m.name());
        }
        described.add(INDEX);

        assertThat(central).as("central directory of %s", fixture)
                .containsExactlyInAnyOrderElementsOf(described)
                .last().isEqualTo(INDEX);
        assertThat(local).as("local entries of %s in file order", fixture).isEqualTo(central);
    }

    @Test
    void aFlippedByteIsReported(@TempDir Path tmp) throws IOException {
        Path original = Fixtures.current("small.unpacked.mzpeak");
        Path copy = tmp.resolve("small.unpacked.mzpeak");
        Files.createDirectories(copy);
        try (Stream<Path> files = Files.list(original)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Files.copy(f, copy.resolve(f.getFileName().toString()));
            }
        }
        assertThat(members(copy)).allMatch(Member::matches);

        String victim = members(copy).get(0).name();
        Path victimPath = copy.resolve(victim);
        byte[] bytes = Files.readAllBytes(victimPath);
        bytes[bytes.length / 2] ^= (byte) 0xFF;
        Files.write(victimPath, bytes);

        assertThat(members(copy).stream().filter(m -> !m.matches()).map(Member::name))
                .containsExactly(victim);
    }

    /** Every member listed in the dataset's index, with its declared and its recomputed checksum. */
    static List<Member> members(Path dataset) throws IOException {
        List<Member> out = new ArrayList<>();
        try (MzPeakSource src = MzPeakSource.open(dataset)) {
            JsonNode files = MAPPER.readTree(src.readManifestBytes()).path("files");
            for (JsonNode f : files) {
                String name = f.path("name").asText();
                String expected = f.path("checksum").asText("");
                out.add(new Member(name, expected, sha512Hex(src, name)));
            }
        }
        return out;
    }

    private static String sha512Hex(MzPeakSource src, String member) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buf = new byte[1 << 16];
        try (SeekableInputStream in = src.inputFile(member).newStream()) {
            for (int n = in.read(buf); n != -1; n = in.read(buf)) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
