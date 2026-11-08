package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.parquet.io.SeekableInputStream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The manifest of a written dataset conforms to the spec in the dialect the writer uses (WR-1, design §11.4 and
 * §11.6): every entry carries the SHA-512 of its member as 128 lowercase hex characters, an empty
 * {@code column_mapping} and an empty {@code parameters} list; the pin-era role spellings ({@code data arrays},
 * {@code wavelength spectrum}) are kept; and in an archive {@code mzpeak_index.json} is the last ZIP entry, after the
 * members in name order.
 *
 * <p>Each case reads a legacy fixture with {@link ForwardBackwardCorpusTest#readSource} and writes it with the full
 * form of one of the four legacy writer families. The checksums are recomputed here with {@link MessageDigest}: from
 * the file bytes of a directory, and through {@link MzPeakSource#inputFile} for an archive.
 */
class WriterManifestConformanceTest {

    private static final String INDEX = "mzpeak_index.json";
    private static final String SHA512_HEX = "^[0-9a-f]{128}$";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Fixtures read once per class; the tests never modify a source in place. */
    private static final Map<String, Source> SOURCES = new ConcurrentHashMap<>();

    /** The roles mzPeakJ wrote before WR-1, in manifest order: {@code name|entity_type|data_kind}. */
    private static final List<String> SMALL_ROLES = List.of(
            "spectra_metadata.parquet|spectrum|metadata",
            "spectra_data.parquet|spectrum|data arrays",
            "spectra_peaks.parquet|spectrum|peaks",
            "chromatograms_metadata.parquet|chromatogram|metadata",
            "chromatograms_data.parquet|chromatogram|data arrays");
    private static final List<String> HAS_UV_ROLES = Stream.concat(SMALL_ROLES.stream(), Stream.of(
            "wavelength_spectra_metadata.parquet|wavelength spectrum|metadata",
            "wavelength_spectra_data.parquet|wavelength spectrum|data arrays")).toList();

    /** The full form of each legacy writer family. */
    enum Form {
        WRITE_DIRECTORY("writeDirectory", false, (out, s) -> MzPeakWriter.writeDirectory(out, s.spectra,
                s.chromatograms, s.wavelengthSpectra, s.scanSettings, s.fileMetadata)),
        WRITE_ARCHIVE("writeArchive", true, (out, s) -> MzPeakWriter.writeArchive(out, s.spectra,
                s.chromatograms, s.wavelengthSpectra, s.scanSettings, s.fileMetadata)),
        WRITE_DIRECTORY_NUMPRESS("writeDirectoryNumpress", false, (out, s) ->
                MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms, s.wavelengthSpectra,
                        s.scanSettings, s.fileMetadata, MzPeakWriter.NumpressIntensityEncoding.SLOF)),
        WRITE_ARCHIVE_NUMPRESS("writeArchiveNumpress", true, (out, s) ->
                MzPeakWriter.writeArchiveNumpress(out, s.spectra, s.chromatograms, s.wavelengthSpectra,
                        s.scanSettings, s.fileMetadata));

        final String label;
        final boolean archive;
        final BiConsumer<Path, Source> write;

        Form(String label, boolean archive, BiConsumer<Path, Source> write) {
            this.label = label;
            this.archive = archive;
            this.write = write;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<Arguments> cases() {
        List<Arguments> cases = new ArrayList<>();
        for (String fixture : List.of("small.unpacked.mzpeak", "has_uv.mzpeak")) {
            for (Form form : Form.values()) {
                cases.add(Arguments.of(fixture, form));
            }
        }
        return cases.stream();
    }

    static Stream<Arguments> archiveCases() {
        return cases().filter(a -> ((Form) a.get()[1]).archive);
    }

    @ParameterizedTest(name = "{0} via {1}")
    @MethodSource("cases")
    void everyEntryCarriesTheSha512OfItsMember(String fixture, Form form, @TempDir Path tmp) throws IOException {
        Path out = write(fixture, form, tmp);
        JsonNode files = index(out).path("files");
        assertThat(files.isArray()).as("files of %s via %s", fixture, form).isTrue();

        TreeSet<String> listed = new TreeSet<>();
        try (MzPeakSource source = MzPeakSource.open(out)) {
            for (JsonNode entry : files) {
                String name = entry.path("name").asText();
                listed.add(name);
                JsonNode checksum = entry.get("checksum");
                assertThat(checksum).as("%s via %s: checksum of %s", fixture, form, name).isNotNull();
                assertThat(checksum.isTextual()).as("%s via %s: checksum of %s is a string", fixture, form, name)
                        .isTrue();
                assertThat(checksum.asText()).as("%s via %s: checksum of %s", fixture, form, name)
                        .matches(SHA512_HEX)
                        .isEqualTo(form.archive ? sha512(source, name) : sha512(out.resolve(name)));
            }
        }
        assertThat(listed).as("%s via %s: the members the index lists", fixture, form)
                .isEqualTo(membersBesidesIndex(out, form.archive));
    }

    @ParameterizedTest(name = "{0} via {1}")
    @MethodSource("cases")
    void everyEntryHasAnEmptyColumnMappingAndParameterList(String fixture, Form form, @TempDir Path tmp)
            throws IOException {
        Path out = write(fixture, form, tmp);
        for (JsonNode entry : index(out).path("files")) {
            String name = entry.path("name").asText();
            assertThat(fieldNames(entry)).as("%s via %s: keys of %s", fixture, form, name)
                    .containsExactly("name", "entity_type", "data_kind", "column_mapping", "parameters", "checksum");
            for (String key : List.of("column_mapping", "parameters")) {
                assertThat(entry.get(key).isArray()).as("%s via %s: %s of %s is a list", fixture, form, key, name)
                        .isTrue();
                assertThat(entry.get(key)).as("%s via %s: %s of %s", fixture, form, key, name).isEmpty();
            }
        }
    }

    @ParameterizedTest(name = "{0} via {1}")
    @MethodSource("cases")
    void theRoleLiteralsAreThePinEraSpellings(String fixture, Form form, @TempDir Path tmp) throws IOException {
        Path out = write(fixture, form, tmp);
        List<String> roles = new ArrayList<>();
        for (JsonNode entry : index(out).path("files")) {
            roles.add(entry.path("name").asText() + "|" + entry.path("entity_type").asText() + "|"
                    + entry.path("data_kind").asText());
        }
        // every has_uv spectrum is a CENTROID peak copy, which WR-3 writes to spectra_peaks only; spectra_data is
        // still written, without a row (FN-10)
        assertThat(roles).as("roles of %s via %s", fixture, form)
                .containsExactlyElementsOf(fixture.startsWith("has_uv") ? HAS_UV_ROLES : SMALL_ROLES);
    }

    @ParameterizedTest(name = "{0} via {1}")
    @MethodSource("archiveCases")
    void theIndexIsTheLastZipEntryAfterTheMembersInNameOrder(String fixture, Form form, @TempDir Path tmp)
            throws IOException {
        Path out = write(fixture, form, tmp);
        List<String> local = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(out))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                local.add(e.getName());
            }
        }
        List<String> central = new ArrayList<>();
        try (ZipFile zip = new ZipFile(out.toFile())) {
            zip.stream().forEach(e -> central.add(e.getName()));
        }
        assertThat(central).as("central directory of %s via %s", fixture, form).isEqualTo(local);

        List<String> expected = new ArrayList<>(new TreeSet<>(membersBesidesIndex(out, true)));
        expected.add(INDEX);
        assertThat(local).as("ZIP entries of %s via %s", fixture, form).containsExactlyElementsOf(expected);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /**
     * The index of a written dataset with every entry's {@code checksum} removed, for comparing two writes whose
     * members hold the same content in different bytes (e.g. different row groups).
     */
    static JsonNode indexWithoutChecksums(Path dataset) {
        JsonNode index;
        try {
            index = index(dataset);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (JsonNode entry : index.path("files")) {
            assertThat(entry.has("checksum")).as("%s: checksum of %s", dataset, entry.path("name").asText()).isTrue();
            ((ObjectNode) entry).remove("checksum");
        }
        return index;
    }

    private static Path write(String fixture, Form form, Path tmp) {
        Source source = SOURCES.computeIfAbsent(fixture, f -> ForwardBackwardCorpusTest.readSource(Fixtures.legacy(f)));
        Path out = tmp.resolve("out.mzpeak");
        form.write.accept(out, source);
        assertThat(form.archive ? Files.isRegularFile(out) : Files.isDirectory(out))
                .as("%s via %s wrote %s", fixture, form, out).isTrue();
        return out;
    }

    private static JsonNode index(Path out) throws IOException {
        try (MzPeakSource source = MzPeakSource.open(out)) {
            return MAPPER.readTree(source.readManifestBytes());
        }
    }

    /** The names of every member of the written dataset except the index. */
    private static TreeSet<String> membersBesidesIndex(Path out, boolean archive) {
        TreeSet<String> names = new TreeSet<>();
        if (archive) {
            try (ZipFile zip = new ZipFile(out.toFile())) {
                zip.stream().forEach(e -> names.add(e.getName()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            try (Stream<Path> files = Files.list(out)) {
                files.forEach(f -> names.add(f.getFileName().toString()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(names).as("members of %s", out).contains(INDEX);
        names.remove(INDEX);
        return names;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** The SHA-512 of a file's bytes. */
    private static String sha512(Path file) throws IOException {
        return HexFormat.of().formatHex(newSha512().digest(Files.readAllBytes(file)));
    }

    /** The SHA-512 of a member's bytes, read through the source's {@code inputFile}. */
    private static String sha512(MzPeakSource source, String member) throws IOException {
        MessageDigest digest = newSha512();
        byte[] buf = new byte[1 << 16];
        try (SeekableInputStream in = source.inputFile(member).newStream()) {
            for (int n = in.read(buf); n != -1; n = in.read(buf)) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newSha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
