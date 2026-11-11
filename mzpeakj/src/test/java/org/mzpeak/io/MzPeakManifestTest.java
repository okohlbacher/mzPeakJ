package org.mzpeak.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.model.Param;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Edge paths of {@link MzPeakManifest#fromSource}: malformed or absent parts of {@code mzpeak_index.json}. Each test
 * edits the manifest of the vendored {@code small.unpacked.mzpeak} and parses it from memory, since no fixture
 * holds such a manifest.
 */
class MzPeakManifestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DESCRIPTION = "memory:edited small.unpacked.mzpeak";

    /** The vendored manifest parses to ten files, version 0.9.0 and no imaging flag; the tests below edit it. */
    @Test
    void theUneditedFixtureManifest() {
        MzPeakManifest m = parse(fixtureIndex());

        assertThat(m.files()).hasSize(10);
        assertThat(m.version()).isEqualTo("0.9.0");
        assertThat(m.cvList()).extracting(MzPeakManifest.CvEntry::id).startsWith("MS", "UO");
        assertThat(m.scanSettingsList()).isEmpty();
        assertThat(m.isImaging()).isFalse();
        assertThat(m.metadataJson()).isEqualTo(fixtureIndex().get("metadata"));
    }

    /** A manifest without a {@code files} array is rejected (D-7; until then: "has no 'files' array"). */
    @Test
    void aManifestWithoutAFilesArrayIsRejected() {
        ObjectNode absent = fixtureIndex();
        absent.remove("files");
        ObjectNode object = fixtureIndex();
        object.putObject("files").put("name", "spectra_data.parquet");

        assertRejected(absent, "files: absent");
        assertRejected(object, "files: not an array (found object)");
    }

    /**
     * A {@code column_mapping} of JSON null is rejected, also beside its {@code metadata_mapping} alias, as serde
     * rejects both (D-7). Until then a null mapping counted as absent and the alias was read.
     */
    @Test
    void aNullColumnMappingIsRejected() {
        ObjectNode alone = fixtureIndex();
        rawEntry(alone, "spectra_metadata.parquet").putNull("column_mapping");
        ObjectNode withAlias = fixtureIndex();
        ObjectNode metadata = rawEntry(withAlias, "spectra_metadata.parquet");
        metadata.set("metadata_mapping", metadata.get("column_mapping"));
        metadata.putNull("column_mapping");

        assertRejected(alone, "files[2] (spectra_metadata.parquet).column_mapping: not an array (found null)");
        assertRejected(withAlias, "files[2] (spectra_metadata.parquet): duplicate field column_mapping");
    }

    /**
     * Malformed entry parts are rejected with their location (D-7): a {@code column_mapping} or {@code parameters}
     * that is not an array, a mapping item that is not an object, a {@code path} that is null or absent and a
     * {@code term_marker} that is not a boolean. Until then they read as empty, were skipped or read as null.
     */
    @Test
    void malformedEntryPartsAreRejected() {
        String data = "files[0] (spectra_data.parquet)";
        String scans = "files[3] (spectra_metadata_scans.parquet).column_mapping[0]";
        assertRejected(editedEntry("spectra_data.parquet", e -> e.putObject("column_mapping").put("path", "mz")),
                data + ".column_mapping: not an array (found object)");
        assertRejected(editedEntry("spectra_data.parquet", e -> e.putObject("parameters").put("name", "label")),
                data + ".parameters: not an array (found object)");
        assertRejected(editedEntry("spectra_metadata_scans.parquet",
                        e -> e.putArray("column_mapping").add("scan_start_time")),
                scans + ": not an object (found \"scan_start_time\")");
        assertRejected(editedEntry("spectra_metadata_scans.parquet",
                        e -> e.putArray("column_mapping").addObject().put("name", "filter string").putNull("path")
                                .put("accession", "MS:1000512")),
                scans + ".path: not a string or an array of strings (found null)");
        assertRejected(editedEntry("spectra_metadata_scans.parquet",
                        e -> e.putArray("column_mapping").addObject().put("name", "preset scan configuration")
                                .put("accession", "MS:1000616").put("term_marker", "true")),
                scans + ".path: absent");
        assertRejected(editedEntry("spectra_metadata_scans.parquet",
                        e -> e.putArray("column_mapping").addObject().put("name", "preset scan configuration")
                                .put("path", "preset").put("accession", "MS:1000616").put("term_marker", "true")),
                scans + ".term_marker: not a boolean (found \"true\")");
    }

    /**
     * A {@code metadata} that is absent or not an object is rejected (D-7: Rust {@code FileIndex.metadata} is a
     * required map). Until then it yielded no version, CVs, scan settings or imaging flag.
     */
    @ParameterizedTest(name = "metadata = {0}")
    @ValueSource(strings = {"absent", "null", "[]", "\"0.9.0\"", "1"})
    void metadataThatIsNotAnObjectIsRejected(String metadata) {
        ObjectNode index = fixtureIndex();
        if (metadata.equals("absent")) {
            index.remove("metadata");
        } else {
            index.set("metadata", json(metadata));
        }

        assertRejected(index, "metadata: " + switch (metadata) {
            case "absent" -> "absent";
            case "null" -> "not an object (found null)";
            case "[]" -> "not an object (found array)";
            case "1" -> "not an object (found number)";
            default -> "not an object (found " + metadata + ")";
        });
    }

    /** A {@code scan_settings_list} or {@code cv_list} that is not an array is rejected (D-7; it read as empty). */
    @Test
    void metadataListsThatAreNotArraysAreRejected() {
        ObjectNode settings = fixtureIndex();
        ((ObjectNode) settings.get("metadata")).putObject("scan_settings_list").put("id", "settings");
        ObjectNode cvs = fixtureIndex();
        ((ObjectNode) cvs.get("metadata")).putObject("cv_list").put("id", "MS");

        assertRejected(settings, "metadata.scan_settings_list: not an array (found object)");
        assertRejected(cvs, "metadata.cv_list: not an array (found object)");
    }

    /**
     * Scan-settings param values keep their JSON type, as file entry params do (MZ-7): a boolean or number keeps its
     * type, text stays a {@code String} also when it reads as a number, an array or object value becomes its JSON
     * text, and an absent or null value is {@code null}. A {@code parameters} that is absent or not an array reads as
     * empty. Until MZ-7 numeric text became a {@code Long} or {@code Double} and an array or object value
     * {@code null}.
     */
    @Test
    void scanSettingsParamValuesKeepTheirJsonType() {
        ObjectNode index = fixtureIndex();
        ArrayNode list = ((ObjectNode) index.get("metadata")).putArray("scan_settings_list");
        list.addObject().put("id", "no parameters");
        list.addObject().put("id", "object parameters").putObject("parameters").put("name", "x");
        ArrayNode params = list.addObject().put("id", "values").putArray("parameters");
        params.addObject().put("name", "absent");
        params.addObject().put("name", "null").putNull("value");
        params.addObject().put("name", "array").putArray("value").add(1);
        params.addObject().put("name", "object").putObject("value").put("k", "v");
        params.addObject().put("name", "true").put("value", true);
        params.addObject().put("name", "false").put("value", false);
        params.addObject().put("name", "integer").put("value", 42);
        params.addObject().put("name", "double").put("value", 2.5);
        params.addObject().put("name", "integer text").put("value", "123");
        params.addObject().put("name", "double text").put("value", "1.5");
        params.addObject().put("name", "text").put("value", "top down");
        params.addObject().put("name", "pixel size x").put("accession", "IMS:1000046").put("value", 100)
                .put("unit", "UO:0000017").put("unit_accession", "UO:0000018");

        List<ScanSettings> settings = parse(index).scanSettingsList();

        assertThat(settings).containsExactly(
                new ScanSettings("no parameters", List.of()),
                new ScanSettings("object parameters", List.of()),
                new ScanSettings("values", List.of(
                        new Param("absent", null, null, null),
                        new Param("null", null, null, null),
                        new Param("array", null, "[1]", null),
                        new Param("object", null, "{\"k\":\"v\"}", null),
                        new Param("true", null, true, null),
                        new Param("false", null, false, null),
                        new Param("integer", null, 42L, null),
                        new Param("double", null, 2.5, null),
                        new Param("integer text", null, "123", null),
                        new Param("double text", null, "1.5", null),
                        new Param("text", null, "top down", null),
                        new Param("pixel size x", "IMS:1000046", 100L, "UO:0000017"))));
    }

    /**
     * The schema form of scan settings (the spec's schema/scan_settings_list.json and the mzpeak-0.9 validator's,
     * which the maintainer's mzPeakConverter fork writes) reads like the Rust form the writer writes: references under
     * {@code source_file_references} when {@code source_file_refs} is absent or not an array, and a target given as
     * an object {@code {"parameters": [...]}} as that array; an object without a {@code parameters} array is an
     * empty target. Until then, schema-form references and targets read as empty without a message.
     */
    @Test
    void scanSettingsInTheSchemaFormReadTheirReferencesAndTargets() {
        ObjectNode index = fixtureIndex();
        ArrayNode list = ((ObjectNode) index.get("metadata")).putArray("scan_settings_list");
        ObjectNode schema = list.addObject().put("id", "schema");
        schema.putArray("source_file_references").add("sf1").add("sf2");
        ArrayNode schemaTargets = schema.putArray("targets");
        schemaTargets.addObject().putArray("parameters").addObject().put("name", "isolation window target m/z")
                .put("accession", "MS:1000827").put("value", 445.12).put("unit", "MS:1000040");
        schemaTargets.addObject();
        schemaTargets.addObject().put("parameters", "none");
        ObjectNode rust = list.addObject().put("id", "rust");
        rust.putArray("source_file_refs").add("sf1");
        rust.putArray("targets").addArray().addObject().put("name", "isolation window target m/z")
                .put("accession", "MS:1000827").put("value", 445.12).put("unit", "MS:1000040");
        ObjectNode both = list.addObject().put("id", "both");
        both.putArray("source_file_refs").add("rust ref");
        both.putArray("source_file_references").add("schema ref");
        ObjectNode notAnArray = list.addObject().put("id", "refs not an array");
        notAnArray.put("source_file_refs", "rust ref");
        notAnArray.putArray("source_file_references").add("schema ref");

        List<ScanSettings> settings = parse(index).scanSettingsList();

        Param target = new Param("isolation window target m/z", "MS:1000827", 445.12, "MS:1000040");
        assertThat(settings).containsExactly(
                new ScanSettings("schema", List.of(), List.of("sf1", "sf2"),
                        List.of(List.of(target), List.of(), List.of())),
                new ScanSettings("rust", List.of(), List.of("sf1"), List.of(List.of(target))),
                new ScanSettings("both", List.of(), List.of("rust ref"), List.of()),
                new ScanSettings("refs not an array", List.of(), List.of("schema ref"), List.of()));
    }

    static Stream<Arguments> imagingFlags() {
        return Stream.of(
                Arguments.of("{}", false),
                Arguments.of("{\"is_imaging\": null}", false),
                Arguments.of("{\"is_imaging\": \"true\"}", false),
                Arguments.of("{\"is_imaging\": false}", false),
                Arguments.of("{\"is_imaging\": true}", true));
    }

    /** {@code metadata.imaging.is_imaging} counts only as a JSON boolean inside an {@code imaging} object. */
    @ParameterizedTest(name = "imaging = {0}")
    @MethodSource("imagingFlags")
    void imagingFlagIsABooleanInsideAnImagingObject(String imaging, boolean expected) {
        ObjectNode index = fixtureIndex();
        ((ObjectNode) index.get("metadata")).set("imaging", json(imaging));

        assertThat(parse(index).isImaging()).isEqualTo(expected);
    }

    /** An {@code imaging} that is not an object is rejected (D-7; it read as not imaging before). */
    @Test
    void anImagingThatIsNotAnObjectIsRejected() {
        ObjectNode index = fixtureIndex();
        ((ObjectNode) index.get("metadata")).put("imaging", true);

        assertRejected(index, "metadata.imaging: not an object (found boolean)");
    }

    /** The reader reports a dataset as imaging when the manifest flag is set, even without scan settings. */
    @Test
    void theReaderFollowsTheManifestImagingFlag() {
        TestSources.Memory fixture = TestSources.memory(Fixtures.current("small.unpacked.mzpeak"));
        Map<String, byte[]> members = new HashMap<>();
        for (String name : fixture.names()) {
            members.put(name, fixture.member(name));
        }
        ObjectNode index = fixtureIndex();
        ((ObjectNode) index.get("metadata")).putObject("imaging").put("is_imaging", true);
        members.put(TestSources.MANIFEST, bytes(index));

        try (MzPeakReader reader = MzPeakReader.open(TestSources.memory(DESCRIPTION, members))) {
            assertThat(reader.manifest().isImaging()).isTrue();
            assertThat(reader.scanSettings()).isEmpty();
            assertThat(reader.isImaging()).isTrue();
        }
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.unpacked.mzpeak"))) {
            assertThat(reader.isImaging()).isFalse();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static void assertRejected(ObjectNode index, String locationAndProblem) {
        assertThatThrownBy(() -> parse(index)).isInstanceOf(MzPeakException.class)
                .hasMessage("mzpeak_index.json " + locationAndProblem + " in " + DESCRIPTION);
    }

    /** A fresh copy of the fixture manifest with the entry {@code fileName} edited by {@code edit}. */
    private static ObjectNode editedEntry(String fileName, Consumer<ObjectNode> edit) {
        ObjectNode index = fixtureIndex();
        edit.accept(rawEntry(index, fileName));
        return index;
    }

    /** A fresh, editable copy of the vendored {@code small.unpacked.mzpeak} manifest. */
    private static ObjectNode fixtureIndex() {
        try (MzPeakSource src = MzPeakSource.open(Fixtures.current("small.unpacked.mzpeak"))) {
            return (ObjectNode) MAPPER.readTree(src.readManifestBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code index} parsed by {@link MzPeakManifest#fromSource} from a source that holds only the manifest. */
    private static MzPeakManifest parse(JsonNode index) {
        try (MzPeakSource src = TestSources.memory(DESCRIPTION, Map.of(TestSources.MANIFEST, bytes(index)))) {
            return MzPeakManifest.fromSource(src);
        }
    }

    private static byte[] bytes(JsonNode index) {
        try {
            return MAPPER.writeValueAsBytes(index);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ObjectNode rawEntry(ObjectNode index, String fileName) {
        for (JsonNode f : index.path("files")) {
            if (fileName.equals(f.path("name").asText())) {
                return (ObjectNode) f;
            }
        }
        throw new AssertionError("no manifest entry " + fileName);
    }
}
