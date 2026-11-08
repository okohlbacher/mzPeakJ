package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what the batch overloads of {@link MzPeakWriter} do with a target that already exists (MZ-10, design §7),
 * before the writer internals stream: {@code writeArchive} replaces an existing file at the target, and
 * {@code writeDirectory} writes into an existing directory, replacing the members it writes and leaving every other
 * file alone, a stale member of the same name scheme included, which its manifest then does not name.
 *
 * <p>Oracle: the spectra, chromatograms and file metadata of current small.unpacked (48 spectra, no wavelength
 * spectra), written once to a fresh path, whose members the write onto the existing target must digest alike
 * ({@link ContentDigest}; within one JVM the same input gives the same bytes); the stale member is the
 * wavelength_spectra_data.parquet of current has_uv.
 */
class WriterExistingTargetTest {

    private static final String INDEX = ManifestWriter.INDEX;
    private static final String STALE = "wavelength_spectra_data.parquet";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Source small;

    @BeforeAll
    static void readSource() {
        small = ForwardBackwardCorpusTest.readSource(Fixtures.current("small.unpacked.mzpeak"));
        assertThat(small.spectra).hasSize(48);
        assertThat(small.wavelengthSpectra).isEmpty();
    }

    @Test
    void writeArchiveReplacesAnExistingFile(@TempDir Path tmp) throws IOException {
        Path target = tmp.resolve("existing.mzpeak");
        Files.write(target, new byte[]{1, 2, 3, 4, 5});
        Path fresh = tmp.resolve("fresh.mzpeak");

        MzPeakWriter.writeArchive(target, small.spectra, small.chromatograms, small.fileMetadata);
        MzPeakWriter.writeArchive(fresh, small.spectra, small.chromatograms, small.fileMetadata);

        assertThat(Files.size(target)).isGreaterThan(5);
        try (MzPeakReader reader = MzPeakReader.open(target)) {
            assertThat(reader.size()).isEqualTo(48);
        }
        WriterSnapshotTest.Snapshot written = WriterSnapshotTest.snapshot(target, true);
        assertThat(written.members()).containsKey(INDEX).containsKey("spectra_data.parquet");
        assertThat(written).isEqualTo(WriterSnapshotTest.snapshot(fresh, true));
        assertThat(fileNames(tmp)).containsExactlyInAnyOrder("existing.mzpeak", "fresh.mzpeak");
    }

    @Test
    void writeDirectoryReplacesItsMembersAndLeavesOtherFilesAlone(@TempDir Path tmp) throws IOException {
        Path target = tmp.resolve("existing");
        Files.createDirectories(target);
        Files.writeString(target.resolve("other.txt"), "abc", StandardCharsets.UTF_8);
        byte[] stale = staleMember();
        Files.write(target.resolve(STALE), stale);
        // Stale content under the names of members the write produces.
        Files.write(target.resolve("spectra_data.parquet"), new byte[]{1, 2, 3, 4, 5});
        Files.writeString(target.resolve(INDEX), "{}", StandardCharsets.UTF_8);
        Path fresh = tmp.resolve("fresh");

        MzPeakWriter.writeDirectory(target, small.spectra, small.chromatograms, small.fileMetadata);
        MzPeakWriter.writeDirectory(fresh, small.spectra, small.chromatograms, small.fileMetadata);

        List<String> members = manifestMembers(target);
        assertThat(members).contains("spectra_data.parquet", "spectra_metadata.parquet")
                .noneMatch(name -> name.startsWith("wavelength_"));
        assertThat(manifestEntityTypes(target)).doesNotContain("wavelength_spectrum", "wavelength spectrum");
        assertThat(members).isEqualTo(manifestMembers(fresh));
        assertThat(digests(target, members)).isEqualTo(digests(fresh, members));

        assertThat(target.resolve("other.txt")).hasBinaryContent("abc".getBytes(StandardCharsets.UTF_8));
        assertThat(target.resolve(STALE)).hasBinaryContent(stale);
        List<String> expected = new ArrayList<>(members);
        expected.add(INDEX);
        expected.add("other.txt");
        expected.add(STALE);
        assertThat(fileNames(target)).containsExactlyInAnyOrderElementsOf(expected);
        try (MzPeakReader reader = MzPeakReader.open(target)) {
            assertThat(reader.size()).isEqualTo(48);
            assertThat(reader.wavelengthSpectra()).isEmpty();
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** The bytes of wavelength_spectra_data.parquet of current has_uv. */
    private static byte[] staleMember() throws IOException {
        try (ZipFile zip = new ZipFile(Fixtures.current("has_uv.mzpeak").toFile());
             InputStream in = zip.getInputStream(zip.getEntry(STALE))) {
            byte[] bytes = in.readAllBytes();
            assertThat(bytes).hasSizeGreaterThan(8);
            return bytes;
        }
    }

    /** The member names the manifest of {@code dataset} lists, in manifest order. */
    private static List<String> manifestMembers(Path dataset) throws IOException {
        List<String> out = new ArrayList<>();
        manifest(dataset).path("files").forEach(f -> out.add(f.path("name").asText()));
        return out;
    }

    /** The entity types the manifest of {@code dataset} lists, in manifest order. */
    private static List<String> manifestEntityTypes(Path dataset) throws IOException {
        List<String> out = new ArrayList<>();
        manifest(dataset).path("files").forEach(f -> out.add(f.path("entity_type").asText()));
        return out;
    }

    private static JsonNode manifest(Path dataset) throws IOException {
        return MAPPER.readTree(Files.readAllBytes(dataset.resolve(INDEX)));
    }

    /** The content digest of each of {@code members} of {@code dataset} and of its manifest, by name. */
    private static Map<String, ContentDigest> digests(Path dataset, List<String> members) {
        Map<String, ContentDigest> out = new LinkedHashMap<>();
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            for (String member : members) {
                out.put(member, ContentDigest.of(source, member));
            }
            out.put(INDEX, ContentDigest.of(source, INDEX));
        }
        return out;
    }

    /** The names of the files in {@code directory}. */
    private static List<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(f -> f.getFileName().toString()).toList();
        }
    }
}
