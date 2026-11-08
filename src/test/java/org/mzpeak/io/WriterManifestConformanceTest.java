package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.parquet.io.SeekableInputStream;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.Type;
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
 * The manifest of a written dataset conforms to the spec in the dialect of its metadata layout (WR-1, WR-5a, design
 * §11.4 and §11.6): every entry carries the SHA-512 of its member as 128 lowercase hex characters and an empty
 * {@code parameters} list; in an archive {@code mzpeak_index.json} is the last ZIP entry, after the members in name
 * order.
 * <ul>
 *   <li>The packed layout keeps the pin-era role spellings ({@code data arrays}, {@code wavelength spectrum}) and an
 *       empty {@code column_mapping} on every entry.
 *   <li>The split layout spells roles with underscores, and each split metadata member maps every CV-mapped column
 *       it writes, with the row of {@link ColumnTable} and of the current HUPO-PSI manifests.
 * </ul>
 *
 * <p>Each case reads a legacy fixture with {@link ForwardBackwardCorpusTest#readSource} and writes it with the full
 * form of one of the four legacy writer families, which write the split layout by default since WR-7, or with
 * options that name the split or the packed layout. The checksums are recomputed
 * here with {@link MessageDigest}: from the file bytes of a directory, and through {@link MzPeakSource#inputFile} for
 * an archive.
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
    /**
     * Every has_uv spectrum is a CENTROID peak copy, which WR-3 writes to spectra_peaks only: the point layout writes
     * no spectra_data (Numpress always writes it).
     */
    private static final List<String> HAS_UV_POINT_ROLES = HAS_UV_ROLES.stream()
            .filter(role -> !role.startsWith("spectra_data.parquet|")).toList();

    /**
     * The roles of the split layout's output of small.unpacked, in manifest order. The chromatogram metadata is split
     * since WR-6.
     */
    private static final List<String> SPLIT_SMALL_ROLES = List.of(
            "spectra_metadata.parquet|spectrum|metadata",
            "spectra_metadata_scans.parquet|spectrum|scans",
            "spectra_metadata_precursors.parquet|spectrum|precursors",
            "spectra_metadata_selected_ions.parquet|spectrum|selected_ions",
            "spectra_data.parquet|spectrum|data_arrays",
            "spectra_peaks.parquet|spectrum|peaks",
            "chromatograms_metadata.parquet|chromatogram|metadata",
            "chromatograms_metadata_precursors.parquet|chromatogram|precursors",
            "chromatograms_metadata_selected_ions.parquet|chromatogram|selected_ions",
            "chromatograms_data.parquet|chromatogram|data_arrays");
    private static final List<String> SPLIT_HAS_UV_ROLES = Stream.concat(SPLIT_SMALL_ROLES.stream(), Stream.of(
            "wavelength_spectra_metadata.parquet|wavelength_spectrum|metadata",
            "wavelength_spectra_metadata_scans.parquet|wavelength_spectrum|scans",
            "wavelength_spectra_data.parquet|wavelength_spectrum|data_arrays")).toList();
    private static final List<String> SPLIT_HAS_UV_POINT_ROLES = SPLIT_HAS_UV_ROLES.stream()
            .filter(role -> !role.startsWith("spectra_data.parquet|")).toList();

    /** The members that carry a column mapping in the split layout. */
    private static final List<String> SPLIT_MAPPED = List.of("spectra_metadata.parquet",
            "spectra_metadata_scans.parquet", "spectra_metadata_precursors.parquet",
            "spectra_metadata_selected_ions.parquet", "chromatograms_metadata.parquet",
            "chromatograms_metadata_precursors.parquet", "chromatograms_metadata_selected_ions.parquet",
            "wavelength_spectra_metadata.parquet", "wavelength_spectra_metadata_scans.parquet");

    /**
     * The full form of each legacy writer family, and the directory, archive and Numpress forms with options naming
     * the split or the packed layout.
     */
    enum Form {
        WRITE_DIRECTORY("writeDirectory", false, true, (out, s) -> MzPeakWriter.writeDirectory(out, s.spectra,
                s.chromatograms, s.wavelengthSpectra, s.scanSettings, s.fileMetadata)),
        WRITE_ARCHIVE("writeArchive", true, true, (out, s) -> MzPeakWriter.writeArchive(out, s.spectra,
                s.chromatograms, s.wavelengthSpectra, s.scanSettings, s.fileMetadata)),
        WRITE_DIRECTORY_NUMPRESS("writeDirectoryNumpress", false, true, (out, s) ->
                MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms, s.wavelengthSpectra,
                        s.scanSettings, s.fileMetadata, MzPeakWriter.NumpressIntensityEncoding.SLOF)),
        WRITE_ARCHIVE_NUMPRESS("writeArchiveNumpress", true, true, (out, s) ->
                MzPeakWriter.writeArchiveNumpress(out, s.spectra, s.chromatograms, s.wavelengthSpectra,
                        s.scanSettings, s.fileMetadata)),
        WRITE_DIRECTORY_SPLIT("writeDirectory-SPLIT", false, true, (out, s) -> MzPeakWriter.writeDirectory(out,
                s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings, s.fileMetadata, split())),
        WRITE_ARCHIVE_SPLIT("writeArchive-SPLIT", true, true, (out, s) -> MzPeakWriter.writeArchive(out,
                s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings, s.fileMetadata, split())),
        WRITE_DIRECTORY_NUMPRESS_SPLIT("writeDirectoryNumpress-SPLIT", false, true, (out, s) ->
                MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                        s.fileMetadata, split().withSignalEncoding(MzPeakWriter.SignalEncoding.NUMPRESS))),
        WRITE_DIRECTORY_PACKED("writeDirectory-PACKED", false, false, (out, s) -> MzPeakWriter.writeDirectory(out,
                s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings, s.fileMetadata, packed())),
        WRITE_ARCHIVE_PACKED("writeArchive-PACKED", true, false, (out, s) -> MzPeakWriter.writeArchive(out,
                s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings, s.fileMetadata, packed())),
        WRITE_DIRECTORY_NUMPRESS_PACKED("writeDirectoryNumpress-PACKED", false, false, (out, s) ->
                MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                        s.fileMetadata, packed().withSignalEncoding(MzPeakWriter.SignalEncoding.NUMPRESS))),
        WRITE_ARCHIVE_NUMPRESS_PACKED("writeArchiveNumpress-PACKED", true, false, (out, s) ->
                MzPeakWriter.writeArchive(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                        s.fileMetadata, packed().withSignalEncoding(MzPeakWriter.SignalEncoding.NUMPRESS)));

        final String label;
        final boolean archive;
        final boolean split;
        final BiConsumer<Path, Source> write;

        Form(String label, boolean archive, boolean split, BiConsumer<Path, Source> write) {
            this.label = label;
            this.archive = archive;
            this.split = split;
            this.write = write;
        }

        private static MzPeakWriter.Options split() {
            return MzPeakWriter.Options.defaults().withMetadataLayout(MzPeakWriter.MetadataLayout.SPLIT);
        }

        private static MzPeakWriter.Options packed() {
            return MzPeakWriter.Options.defaults().withMetadataLayout(MzPeakWriter.MetadataLayout.PACKED);
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

    static Stream<Arguments> packedCases() {
        return cases().filter(a -> !((Form) a.get()[1]).split);
    }

    static Stream<Arguments> splitCases() {
        return cases().filter(a -> ((Form) a.get()[1]).split);
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
    @MethodSource("packedCases")
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
    @MethodSource("packedCases")
    void theRoleLiteralsAreThePinEraSpellings(String fixture, Form form, @TempDir Path tmp) throws IOException {
        Path out = write(fixture, form, tmp);
        List<String> hasUvRoles = form.label.contains("Numpress") ? HAS_UV_ROLES : HAS_UV_POINT_ROLES;
        assertThat(roles(out)).as("roles of %s via %s", fixture, form)
                .containsExactlyElementsOf(fixture.startsWith("has_uv") ? hasUvRoles : SMALL_ROLES);
    }

    @ParameterizedTest(name = "{0} via {1}")
    @MethodSource("splitCases")
    void theSplitLayoutSpellsRolesWithUnderscores(String fixture, Form form, @TempDir Path tmp) throws IOException {
        Path out = write(fixture, form, tmp);
        List<String> hasUvRoles = form.label.contains("Numpress") ? SPLIT_HAS_UV_ROLES : SPLIT_HAS_UV_POINT_ROLES;
        assertThat(roles(out)).as("roles of %s via %s", fixture, form)
                .containsExactlyElementsOf(fixture.startsWith("has_uv") ? hasUvRoles : SPLIT_SMALL_ROLES);
    }

    /**
     * Each split metadata member maps the CV-mapped columns of its schema, no others, in {@link ColumnTable} order and
     * each with its {@link ColumnTable} row; every row equals the row at its path in each current HUPO-PSI manifest
     * that maps it for the same role, and at least one does. Every other entry has an empty mapping, and every entry
     * an empty parameter list.
     */
    @ParameterizedTest(name = "{0} via {1}")
    @MethodSource("splitCases")
    void theSplitMetadataMembersMapTheirColumnsAsTheCurrentManifestsDo(String fixture, Form form, @TempDir Path tmp)
            throws IOException {
        Path out = write(fixture, form, tmp);
        List<JsonNode> currentManifests = currentManifests();
        try (MzPeakSource dataset = MzPeakSource.open(out)) {
            for (JsonNode entry : index(out).path("files")) {
                String name = entry.path("name").asText();
                assertThat(fieldNames(entry)).as("%s via %s: keys of %s", fixture, form, name)
                        .containsExactly("name", "entity_type", "data_kind", "column_mapping", "parameters",
                                "checksum");
                assertThat(entry.get("parameters").isArray()).isTrue();
                assertThat(entry.get("parameters")).as("%s via %s: parameters of %s", fixture, form, name).isEmpty();
                JsonNode mapping = entry.get("column_mapping");
                assertThat(mapping.isArray()).isTrue();
                if (!SPLIT_MAPPED.contains(name)) {
                    assertThat(mapping).as("%s via %s: column_mapping of %s", fixture, form, name).isEmpty();
                    continue;
                }
                Facet facet = facet(entry);
                List<String> paths = new ArrayList<>();
                for (JsonNode row : mapping) {
                    String path = row.path("path").asText();
                    paths.add(path);
                    ColumnTable.Column column = ColumnTable.byPath(facet, path).orElseThrow();
                    assertThat(column.path()).as("%s: %s is a canonical path", name, path).isEqualTo(path);
                    assertThat(fieldNames(row)).as("%s: keys of %s", name, path).containsExactlyElementsOf(
                            column.termMarker() ? List.of("name", "path", "accession", "unit", "term_marker")
                                    : List.of("name", "path", "accession", "unit"));
                    assertThat(row.path("name").asText()).isEqualTo(column.name());
                    assertThat(row.path("accession").asText()).isEqualTo(column.accession());
                    assertThat(row.get("unit").isNull() ? null : row.get("unit").asText()).isEqualTo(column.unit());
                    int matched = 0;
                    for (JsonNode current : currentManifests) {
                        JsonNode currentRow = currentRow(current, entry, path);
                        if (currentRow != null) {
                            assertThat(row).as("%s: %s against a current manifest", name, path)
                                    .isEqualTo(currentRow);
                            matched++;
                        }
                    }
                    assertThat(matched).as("%s: current manifests that map %s", name, path).isPositive();
                }
                List<String> expected = new ArrayList<>();
                List<String> schemaPaths = leafPaths(ParquetFooter.read(dataset.inputFile(name)).schema());
                for (ColumnTable.Column column : ColumnTable.columns(facet)) {
                    if (schemaPaths.contains(column.path())) {
                        expected.add(column.path());
                    }
                }
                assertThat(paths).as("%s via %s: mapped paths of %s", fixture, form, name)
                        .isNotEmpty().containsExactlyElementsOf(expected);
            }
        }
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

    /** Each entry as {@code name|entity_type|data_kind}, in manifest order. */
    private static List<String> roles(Path out) throws IOException {
        List<String> roles = new ArrayList<>();
        for (JsonNode entry : index(out).path("files")) {
            roles.add(entry.path("name").asText() + "|" + entry.path("entity_type").asText() + "|"
                    + entry.path("data_kind").asText());
        }
        return roles;
    }

    /** The facet whose canonical role the manifest entry has. */
    private static Facet facet(JsonNode entry) {
        for (Facet f : Facet.values()) {
            if (f.entityType().equals(entry.path("entity_type").asText())
                    && f.dataKind().equals(entry.path("data_kind").asText())) {
                return f;
            }
        }
        throw new AssertionError("no facet has the role of " + entry);
    }

    /** The manifests of the 8 current HUPO-PSI example datasets. */
    private static List<JsonNode> currentManifests() throws IOException {
        List<JsonNode> out = new ArrayList<>();
        for (String name : Fixtures.CURRENT_NAMES) {
            out.add(index(Fixtures.current(name)));
        }
        return out;
    }

    /**
     * The column mapping row at {@code path} of the entry of {@code manifest} that has the role of {@code entry}, or
     * {@code null} when the manifest has no such entry or the entry no such row.
     */
    private static JsonNode currentRow(JsonNode manifest, JsonNode entry, String path) {
        for (JsonNode candidate : manifest.path("files")) {
            if (candidate.path("entity_type").asText().equals(entry.path("entity_type").asText())
                    && candidate.path("data_kind").asText().equals(entry.path("data_kind").asText())) {
                for (JsonNode row : candidate.path("column_mapping")) {
                    if (path.equals(row.path("path").asText())) {
                        return row;
                    }
                }
            }
        }
        return null;
    }

    /** The dotted paths of every primitive leaf of {@code schema}, omitting list and element levels. */
    private static List<String> leafPaths(GroupType schema) {
        List<String> out = new ArrayList<>();
        leafPaths(schema, "", out);
        return out;
    }

    private static void leafPaths(GroupType group, String prefix, List<String> out) {
        for (Type t : group.getFields()) {
            boolean listLevel = t.isRepetition(Type.Repetition.REPEATED) && t.getName().equals("list")
                    || t.getName().equals("item") || t.getName().equals("element");
            String path = listLevel ? prefix : prefix.isEmpty() ? t.getName() : prefix + "." + t.getName();
            if (t.isPrimitive()) {
                out.add(path);
            } else {
                leafPaths(t.asGroupType(), path, out);
            }
        }
    }

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
