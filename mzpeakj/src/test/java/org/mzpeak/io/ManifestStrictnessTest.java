package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.model.Param;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Strict parsing of {@code mzpeak_index.json} like the reference implementation's serde structs (D-7): every shape
 * the Rust {@code FileIndex}/{@code FileEntry}/{@code MetadataColumn}/{@code MetaParam} deserialization refuses is
 * rejected with a message naming the location and the problem, and the shapes it accepts (unknown keys, duplicate
 * unknown keys, duplicate keys inside {@code metadata}, a {@code metadata.version} of any type) parse. Each manifest
 * is an edit of a {@code @TempDir} copy of current {@code small.unpacked.mzpeak}; only {@code mzpeak_index.json}
 * changes. Until D-7 every rejected shape below parsed leniently (left out, skipped or read as absent).
 */
class ManifestStrictnessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final String INDEX = "mzpeak_index.json";

    private static final String DATA = "files[0] (spectra_data.parquet)";
    private static final String SCANS = "files[3] (spectra_metadata_scans.parquet)";
    /** The {@code filter string} mapping of the scans entry. */
    private static final String MAPPING = SCANS + ".column_mapping[2]";
    /** The parameter {@link #param} adds to the data entry. */
    private static final String PARAM = DATA + ".parameters[0]";

    @TempDir
    Path tmp;

    /** One rejected shape: the edit, and the location and problem the message names. */
    record Case(String name, Consumer<ObjectNode> edit, String location, String problem) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> rejected() {
        return Stream.of(
                // root
                new Case("files absent", i -> i.remove("files"), "files", "absent"),
                new Case("files an object", i -> i.putObject("files"), "files", "not an array (found object)"),
                new Case("files null", i -> i.putNull("files"), "files", "not an array (found null)"),
                new Case("metadata absent", i -> i.remove("metadata"), "metadata", "absent"),
                new Case("metadata an array", i -> i.putArray("metadata"), "metadata", "not an object (found array)"),
                new Case("metadata null", i -> i.putNull("metadata"), "metadata", "not an object (found null)"),
                // file entry
                new Case("entry a number", i -> files(i).set(1, MAPPER.getNodeFactory().numberNode(1)),
                        "files[1]", "not an object (found number)"),
                new Case("entry an array", i -> files(i).set(1, MAPPER.createArrayNode()),
                        "files[1]", "not an object (found array)"),
                new Case("name absent", i -> entry(i, 0).remove("name"),
                        "files[0] (spectrum/data_arrays).name", "absent"),
                new Case("name null", i -> entry(i, 0).putNull("name"),
                        "files[0] (spectrum/data_arrays).name", "null"),
                new Case("name a number", i -> entry(i, 0).put("name", 7),
                        "files[0] (spectrum/data_arrays).name", "not a string (found number)"),
                new Case("entity_type absent", i -> entry(i, 0).remove("entity_type"),
                        DATA + ".entity_type", "absent"),
                new Case("entity_type null", i -> entry(i, 0).putNull("entity_type"),
                        DATA + ".entity_type", "null"),
                new Case("data_kind a boolean", i -> entry(i, 0).put("data_kind", true),
                        DATA + ".data_kind", "not a string (found boolean)"),
                new Case("column_mapping an object", i -> entry(i, 3).putObject("column_mapping"),
                        SCANS + ".column_mapping", "not an array (found object)"),
                new Case("column_mapping null", i -> entry(i, 3).putNull("column_mapping"),
                        SCANS + ".column_mapping", "not an array (found null)"),
                new Case("metadata_mapping a string", i -> {
                    entry(i, 3).remove("column_mapping");
                    entry(i, 3).put("metadata_mapping", "scan_start_time");
                }, SCANS + ".metadata_mapping", "not an array (found \"scan_start_time\")"),
                new Case("parameters an object", i -> entry(i, 0).putObject("parameters").put("name", "label"),
                        DATA + ".parameters", "not an array (found object)"),
                new Case("parameters null", i -> entry(i, 0).putNull("parameters"),
                        DATA + ".parameters", "not an array (found null)"),
                new Case("checksum a number", i -> entry(i, 0).put("checksum", 1),
                        DATA + ".checksum", "not a string or null (found number)"),
                new Case("checksum an object", i -> entry(i, 0).putObject("checksum"),
                        DATA + ".checksum", "not a string or null (found object)"),
                // mapping item
                new Case("mapping item a string", i -> mappings(i).set(2, MAPPER.getNodeFactory().textNode("x")),
                        MAPPING, "not an object (found \"x\")"),
                new Case("mapping item an array", i -> mappings(i).set(2, MAPPER.createArrayNode()),
                        MAPPING, "not an object (found array)"),
                new Case("mapping name absent", i -> mapping(i).remove("name"), MAPPING + ".name", "absent"),
                new Case("mapping name null", i -> mapping(i).putNull("name"), MAPPING + ".name", "null"),
                new Case("mapping name a number", i -> mapping(i).put("name", 1),
                        MAPPING + ".name", "not a string (found number)"),
                new Case("path absent", i -> mapping(i).remove("path"), MAPPING + ".path", "absent"),
                new Case("path null", i -> mapping(i).putNull("path"),
                        MAPPING + ".path", "not a string or an array of strings (found null)"),
                new Case("path an array with a number", i -> mapping(i).putArray("path").add("filter").add(1),
                        MAPPING + ".path", "not a string or an array of strings (found array)"),
                new Case("accession absent", i -> mapping(i).remove("accession"), MAPPING + ".accession", "absent"),
                new Case("accession a name", i -> mapping(i).put("accession", "filter string"),
                        MAPPING + ".accession", "not a CURIE or null (found \"filter string\")"),
                new Case("accession a number", i -> mapping(i).put("accession", 1000512),
                        MAPPING + ".accession", "not a CURIE or null (found number)"),
                new Case("unit a word", i -> mapping(i).put("unit", "minute"),
                        MAPPING + ".unit", "not a CURIE, null or an array of strings (found \"minute\")"),
                new Case("unit a number", i -> mapping(i).put("unit", 31),
                        MAPPING + ".unit", "not a CURIE, null or an array of strings (found number)"),
                new Case("unit an array with a null", i -> mapping(i).putArray("unit").add("unit").addNull(),
                        MAPPING + ".unit", "not a CURIE, null or an array of strings (found array)"),
                new Case("term_marker a string", i -> mapping(i).put("term_marker", "true"),
                        MAPPING + ".term_marker", "not a boolean (found \"true\")"),
                new Case("term_marker null", i -> mapping(i).putNull("term_marker"),
                        MAPPING + ".term_marker", "not a boolean (found null)"),
                // parameter item
                new Case("parameter a number", i -> entry(i, 0).putArray("parameters").add(1),
                        PARAM, "not an object (found number)"),
                new Case("parameter name a number", i -> param(i).put("name", 1),
                        PARAM + ".name", "not a string or null (found number)"),
                new Case("parameter accession absent", i -> param(i).remove("accession"),
                        PARAM + ".accession", "absent"),
                new Case("parameter accession a name", i -> param(i).put("accession", "scan start time"),
                        PARAM + ".accession", "not a CURIE or null (found \"scan start time\")"),
                new Case("parameter unit absent", i -> param(i).remove("unit"), PARAM + ".unit", "absent"),
                new Case("parameter unit_accession alone", i -> {
                    param(i).remove("unit");
                    param(i).put("unit_accession", "UO:0000010");
                }, PARAM + ".unit", "absent"),
                new Case("parameter unit an object", i -> param(i).putObject("unit"),
                        PARAM + ".unit", "not a CURIE or null (found object)"),
                // metadata documents
                new Case("cv_list an object", i -> metadata(i).putObject("cv_list"),
                        "metadata.cv_list", "not an array (found object)"),
                new Case("scan_settings_list null", i -> metadata(i).putNull("scan_settings_list"),
                        "metadata.scan_settings_list", "not an array (found null)"),
                new Case("software_list a string", i -> metadata(i).put("software_list", "x"),
                        "metadata.software_list", "not an array (found \"x\")"),
                new Case("sample_list a number", i -> metadata(i).put("sample_list", 1),
                        "metadata.sample_list", "not an array (found number)"),
                new Case("instrument_configuration_list an object",
                        i -> metadata(i).putObject("instrument_configuration_list"),
                        "metadata.instrument_configuration_list", "not an array (found object)"),
                new Case("data_processing_method_list a boolean",
                        i -> metadata(i).put("data_processing_method_list", true),
                        "metadata.data_processing_method_list", "not an array (found boolean)"),
                new Case("run an array", i -> metadata(i).putArray("run"), "metadata.run",
                        "not an object (found array)"),
                new Case("file_description null", i -> metadata(i).putNull("file_description"),
                        "metadata.file_description", "not an object (found null)"),
                new Case("imaging a boolean", i -> metadata(i).put("imaging", true),
                        "metadata.imaging", "not an object (found boolean)"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void aShapeTheReferenceRefusesIsRejected(Case c) throws IOException {
        ObjectNode index = fixtureIndex();
        c.edit().accept(index);
        Path copy = copyWith(MAPPER.writeValueAsString(index));

        assertRejected(copy, c.location(), c.problem());
    }

    @ParameterizedTest(name = "root = {0}")
    @ValueSource(strings = {"[]", "\"files\"", "1"})
    void aRootThatIsNotAnObjectIsRejected(String root) throws IOException {
        Path copy = copyWith(root);

        assertRejected(copy, "root", "not an object (found " + (root.startsWith("\"") ? root
                : root.equals("1") ? "number" : "array") + ")");
    }

    /** An empty or whitespace-only manifest has no root value; Jackson reads it as a missing node. */
    @ParameterizedTest(name = "manifest = \"{0}\"")
    @ValueSource(strings = {"", " \n\t "})
    void anEmptyManifestIsRejected(String json) throws IOException {
        Path copy = copyWith(json);

        assertRejected(copy, "root", "not an object (found missing)");
    }

    /** serde_json::from_reader refuses anything but whitespace after the document; so does the manifest parser. */
    @Test
    void aSecondObjectAfterTheManifestIsRejected() throws IOException {
        Path copy = copyWith(compact(fixtureIndex()) + "\n{}");

        assertRejected(copy, "root", "content after the JSON document");
    }

    @Test
    void aScalarAfterTheManifestIsRejected() throws IOException {
        Path copy = copyWith(compact(fixtureIndex()) + " 1");

        assertRejected(copy, "root", "content after the JSON document");
    }

    @Test
    void aScalarRootFollowedByAnObjectIsRejectedAsTrailingContent() throws IOException {
        Path copy = copyWith("1 " + compact(fixtureIndex()));

        assertRejected(copy, "root", "content after the JSON document");
    }

    @Test
    void trailingWhitespaceIsAccepted() throws IOException {
        Path copy = copyWith(compact(fixtureIndex()) + " \r\n\t\n");

        assertThat(manifest(copy).files()).hasSize(manifest(SMALL).files().size());
    }

    /** CURIE-shaped means a prefix of a letter, then letters, digits, '_', '.' or '-', a colon and a local part. */
    @ParameterizedTest(name = "accession = {0}")
    @ValueSource(strings = {"MS:", ":1000512", "1MS:1000512", "MS:1000:512", "MS:1000 512", "M S:1000512",
            "MS 1000512", ""})
    void anAccessionThatIsNotCurieShapedIsRejected(String accession) throws IOException {
        ObjectNode index = fixtureIndex();
        mapping(index).put("accession", accession);

        assertRejected(copyWith(MAPPER.writeValueAsString(index)), MAPPING + ".accession",
                "not a CURIE or null (found \"" + accession + "\")");
    }

    @ParameterizedTest(name = "accession = {0}")
    @ValueSource(strings = {"MS:1000512", "IMS:1000050", "x.y-z_1:a-b.c", "NCIT:C25330", "MS:1003145a"})
    void aCurieShapedAccessionParses(String accession) throws IOException {
        ObjectNode index = fixtureIndex();
        mapping(index).put("accession", accession);

        assertThat(scansMapping(manifest(copyWith(MAPPER.writeValueAsString(index)))).accession())
                .isEqualTo(accession);
    }

    /**
     * The shapes serde accepts besides the fixtures': a null checksum, a null or array unit, an array path, an absent
     * term marker, a parameter without name or with a null name, value and units, and no {@code column_mapping}
     * or {@code parameters} at all.
     */
    @Test
    void theOptionalAndNullableShapesParse() throws IOException {
        ObjectNode index = fixtureIndex();
        entry(index, 0).putNull("checksum");
        entry(index, 1).remove("checksum");
        entry(index, 1).remove("column_mapping");
        entry(index, 1).remove("parameters");
        mapping(index).putNull("unit");
        mapping(index).remove("term_marker");
        mapping(index).putArray("path").add("filter_string");
        ObjectNode withUnitPath = (ObjectNode) mappings(index).get(0);
        withUnitPath.putArray("unit").add("scan_start_time_unit");
        ArrayNode params = entry(index, 0).putArray("parameters");
        params.addObject().putNull("accession").put("value", "x").putNull("unit");
        params.addObject().putNull("name").put("accession", "MS:1000016").put("value", 1.5).put("unit", "UO:0000031");
        params.addObject().putNull("name").putNull("accession").putNull("unit");

        MzPeakManifest m = manifest(copyWith(MAPPER.writeValueAsString(index)));

        assertThat(m.files().get(0).checksum()).isNull();
        assertThat(m.files().get(0).parameters()).containsExactly(
                new Param(null, null, "x", null),
                new Param(null, "MS:1000016", 1.5, "UO:0000031"),
                new Param(null, null, null, null));
        assertThat(m.files().get(1)).isEqualTo(new Entry("spectra_peaks.parquet", "spectrum", "peaks"));
        assertThat(scansMapping(m)).isEqualTo(
                new ColumnMapping("filter string", "filter_string", "MS:1000512", null, null, null));
        assertThat(m.files().get(3).columnMapping().get(0).unitPath()).isEqualTo("scan_start_time_unit");
    }

    // ---- duplicate fields ------------------------------------------------------------------

    @Test
    void aNameTwiceInAFileEntryIsRejected() throws IOException {
        String json = compact(fixtureIndex());

        Path copy = copyWith(replaceOnce(json, "{\"name\":\"spectra_data.parquet\",",
                "{\"name\":\"spectra_data.parquet\",\"name\":\"other.parquet\","));

        assertRejected(copy, DATA, "duplicate field name");
    }

    /** Before the entry's name is read, the location is the entry's index alone. */
    @Test
    void aDuplicateBeforeTheEntryNameIsLocatedByIndex() throws IOException {
        String json = compact(fixtureIndex());

        Path copy = copyWith(replaceOnce(json, "{\"name\":\"spectra_data.parquet\",",
                "{\"data_kind\":\"peaks\",\"data_kind\":\"peaks\",\"name\":\"spectra_data.parquet\","));

        assertRejected(copy, "files[0]", "duplicate field data_kind");
    }

    @Test
    void aPathTwiceInAMappingItemIsRejected() throws IOException {
        String json = compact(fixtureIndex());

        Path copy = copyWith(replaceOnce(json, "\"path\":\"filter_string\"",
                "\"path\":\"filter_string\",\"path\":\"filter_string\""));

        assertRejected(copy, MAPPING, "duplicate field path");
    }

    @Test
    void anAccessionTwiceInAParameterItemIsRejected() throws IOException {
        ObjectNode index = fixtureIndex();
        param(index);
        String json = compact(index);

        Path copy = copyWith(replaceOnce(json, "\"name\":\"label\",\"accession\":null,",
                "\"name\":\"label\",\"accession\":null,\"accession\":null,"));

        assertRejected(copy, PARAM, "duplicate field accession");
    }

    @Test
    void filesTwiceAtTheRootIsRejected() throws IOException {
        String json = compact(fixtureIndex());

        Path copy = copyWith(replaceOnce(json, "{\"files\":[", "{\"files\":[],\"files\":["));

        assertRejected(copy, "root", "duplicate field files");
    }

    @Test
    void metadataTwiceAtTheRootIsRejected() throws IOException {
        String json = compact(fixtureIndex());

        Path copy = copyWith(replaceOnce(json, "{\"files\":[", "{\"metadata\":{},\"files\":["));

        assertRejected(copy, "root", "duplicate field metadata");
    }

    /** {@code metadata_mapping} is serde's alias of {@code column_mapping}: both in one entry are one field twice. */
    @Test
    void columnMappingWithMetadataMappingIsRejected() throws IOException {
        ObjectNode index = fixtureIndex();
        ObjectNode scans = entry(index, 3);
        scans.set("metadata_mapping", scans.get("column_mapping").deepCopy());

        assertRejected(copyWith(MAPPER.writeValueAsString(index)), SCANS, "duplicate field column_mapping");
    }

    @Test
    void metadataMappingTwiceIsRejected() throws IOException {
        ObjectNode index = fixtureIndex();
        entry(index, 3).set("metadata_mapping", entry(index, 3).remove("column_mapping"));
        String json = compact(index);

        Path copy = copyWith(replaceOnce(json, "\"metadata_mapping\":[", "\"metadata_mapping\":[],"
                + "\"metadata_mapping\":["));

        assertRejected(copy, SCANS, "duplicate field column_mapping");
    }

    /**
     * Duplicates serde accepts: an unknown key twice at every struct level, and any key twice inside
     * {@code metadata} (a map; the last wins) or inside a nested value no struct describes.
     */
    @Test
    void duplicateUnknownKeysAndDuplicatesInsideMetadataParse() throws IOException {
        ObjectNode index = fixtureIndex();
        param(index).putObject("value").put("k", 1);
        ObjectNode run = (ObjectNode) metadata(index).get("run");
        String json = compact(index);
        json = replaceOnce(json, "{\"files\":[", "{\"x-extra\":1,\"x-extra\":2,\"files\":[");
        json = replaceOnce(json, "{\"name\":\"spectra_data.parquet\",",
                "{\"name\":\"spectra_data.parquet\",\"x-extra\":1,\"x-extra\":2,");
        json = replaceOnce(json, "\"path\":\"filter_string\"", "\"path\":\"filter_string\",\"x\":1,\"x\":2");
        json = replaceOnce(json, "\"name\":\"label\",", "\"name\":\"label\",\"x\":1,\"x\":2,");
        json = replaceOnce(json, "\"value\":{\"k\":1}", "\"value\":{\"k\":0,\"k\":1}");
        json = replaceOnce(json, "\"metadata\":{", "\"metadata\":{\"run\":{\"id\":\"first\"},"
                + "\"x\":{\"files\":1,\"files\":2},");

        MzPeakManifest m = manifest(copyWith(json));

        assertThat(m.metadataJson().get("run")).isEqualTo(run);
        assertThat(m.metadataJson().get("x").get("files").intValue()).isEqualTo(2);
        assertThat(m.files().get(0).parameters()).containsExactly(new Param("label", null, "{\"k\":1}", null));
        assertThat(m.files().subList(1, 10)).isEqualTo(manifest(SMALL).files().subList(1, 10));
    }

    @Test
    void unknownKeysAtEveryLevelAreIgnored() throws IOException {
        ObjectNode index = fixtureIndex();
        index.put("x-root", "ignored");
        index.putArray("x-root-list").addObject().put("name", 1);
        for (JsonNode f : files(index)) {
            ((ObjectNode) f).put("x-entry", 1).putArray("x-entry-list").add(1);
            for (JsonNode c : f.get("column_mapping")) {
                ((ObjectNode) c).putObject("x-mapping").put("path", 1);
            }
        }
        param(index).put("unit_accession", "UO:0000010").put("x-param", true);

        MzPeakManifest m = manifest(copyWith(MAPPER.writeValueAsString(index)));

        List<Entry> expected = new ArrayList<>(manifest(SMALL).files());
        Entry data = expected.get(0);
        expected.set(0, new Entry(data.name(), data.entityType(), data.dataKind(), List.of(),
                List.of(new Param("label", null, "x", "UO:0000010")), data.checksum()));
        assertThat(m.files()).isEqualTo(expected);
    }

    // ---- metadata.version ------------------------------------------------------------------

    /** A non-string version is absent, as Rust {@code FileIndex::version()} reads it ({@code as_str}). */
    @ParameterizedTest(name = "version = {0}")
    @ValueSource(strings = {"1", "null", "{}", "[\"0.9.0\"]", "true"})
    void aVersionOfAnyTypeParses(String version) throws IOException {
        ObjectNode index = fixtureIndex();
        metadata(index).set("version", MAPPER.readTree(version));

        MzPeakManifest m = manifest(copyWith(MAPPER.writeValueAsString(index)));

        assertThat(m.version()).isNull();
        assertThat(m.metadataJson().get("version")).isEqualTo(MAPPER.readTree(version));
        assertThat(m.files()).isEqualTo(manifest(SMALL).files());
    }

    // ---- what the writers write ------------------------------------------------------------

    static Stream<Path> fixtures() {
        return Stream.concat(Fixtures.CURRENT_NAMES.stream().map(Fixtures::current),
                Fixtures.LEGACY_NAMES.stream().map(Fixtures::legacy));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void everyFixtureParses(Path fixture) {
        assertThat(manifest(fixture).files()).isNotEmpty();
    }

    @Test
    void thirteenFixtures() {
        assertThat(fixtures()).hasSize(13);
    }

    /** Every dataset the writer writes for the interop checks, both metadata layouts, parses. */
    @Test
    void everyInteropOutputParses() throws IOException {
        List<Path> written = new ArrayList<>();
        for (InteropOutputs.Output o : InteropOutputs.write(Files.createDirectories(tmp.resolve("split")))) {
            written.add(o.path());
        }
        written.add(InteropOutputs.writePacked(Files.createDirectories(tmp.resolve("packed"))).path());

        assertThat(written).hasSizeGreaterThan(10);
        for (Path p : written) {
            assertThat(manifest(p).files()).as("%s", p.getFileName()).isNotEmpty();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static void assertRejected(Path copy, String location, String problem) {
        assertThatThrownBy(() -> manifest(copy)).isInstanceOf(MzPeakException.class)
                .hasMessage("mzpeak_index.json " + location + ": " + problem + " in " + copy);
    }

    private static MzPeakManifest manifest(Path dataset) {
        try (MzPeakSource src = MzPeakSource.open(dataset)) {
            return MzPeakManifest.fromSource(src);
        }
    }

    /** A copy of current small.unpacked in the temporary directory, its manifest replaced by {@code json}. */
    private Path copyWith(String json) throws IOException {
        Path copy = Files.createDirectories(tmp.resolve("small.unpacked.mzpeak"));
        try (Stream<Path> files = Files.list(SMALL)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (!f.getFileName().toString().equals(INDEX)) {
                    Files.copy(f, copy.resolve(f.getFileName().toString()));
                }
            }
        }
        Files.writeString(copy.resolve(INDEX), json, StandardCharsets.UTF_8);
        return copy;
    }

    private static ObjectNode fixtureIndex() {
        try {
            return (ObjectNode) MAPPER.readTree(SMALL.resolve(INDEX).toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String compact(JsonNode index) throws IOException {
        return MAPPER.writeValueAsString(index);
    }

    private static String replaceOnce(String json, String target, String replacement) {
        int at = json.indexOf(target);
        assertThat(at).as("%s in the manifest", target).isNotNegative();
        return json.substring(0, at) + replacement + json.substring(at + target.length());
    }

    private static ArrayNode files(ObjectNode index) {
        return (ArrayNode) index.get("files");
    }

    private static ObjectNode entry(ObjectNode index, int i) {
        return (ObjectNode) files(index).get(i);
    }

    private static ObjectNode metadata(ObjectNode index) {
        return (ObjectNode) index.get("metadata");
    }

    private static ArrayNode mappings(ObjectNode index) {
        return (ArrayNode) entry(index, 3).get("column_mapping");
    }

    /** The {@code filter string} mapping of the scans entry, {@link #MAPPING}. */
    private static ObjectNode mapping(ObjectNode index) {
        ObjectNode m = (ObjectNode) mappings(index).get(2);
        assertThat(m.get("path").asText()).isEqualTo("filter_string");
        return m;
    }

    private static ColumnMapping scansMapping(MzPeakManifest m) {
        return m.files().get(3).columnMapping().get(2);
    }

    /**
     * The first parameter of the data entry, {@link #PARAM}: added as {@code {"name": "label", "accession": null,
     * "value": "x", "unit": null}} on first use.
     */
    private static ObjectNode param(ObjectNode index) {
        ObjectNode data = entry(index, 0);
        if (data.get("parameters").isEmpty()) {
            data.putArray("parameters").addObject().put("name", "label").putNull("accession").put("value", "x")
                    .putNull("unit");
        }
        return (ObjectNode) data.get("parameters").get(0);
    }
}
