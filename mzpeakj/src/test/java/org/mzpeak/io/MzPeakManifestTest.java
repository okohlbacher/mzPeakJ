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
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.model.Param;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @Test
    void aManifestWithoutAFilesArrayIsRejected() {
        ObjectNode absent = fixtureIndex();
        absent.remove("files");
        ObjectNode object = fixtureIndex();
        object.putObject("files").put("name", "spectra_data.parquet");

        for (ObjectNode index : List.of(absent, object)) {
            assertThatThrownBy(() -> parse(index)).isInstanceOf(MzPeakException.class)
                    .hasMessage("mzpeak_index.json has no 'files' array in " + DESCRIPTION);
        }
    }

    /** A {@code column_mapping} of JSON null counts as absent, so its {@code metadata_mapping} alias is read. */
    @Test
    void aNullColumnMappingFallsBackToMetadataMapping() {
        ObjectNode index = fixtureIndex();
        ObjectNode metadata = rawEntry(index, "spectra_metadata.parquet");
        metadata.set("metadata_mapping", metadata.get("column_mapping"));
        metadata.putNull("column_mapping");

        Entry e = entry(parse(index), "spectra_metadata.parquet");

        assertThat(e).isEqualTo(entry(parse(fixtureIndex()), "spectra_metadata.parquet"));
        assertThat(e.columnMapping()).hasSize(12);
    }

    /**
     * A {@code column_mapping} or {@code parameters} that is not an array reads as empty; a mapping item that is not
     * an object is skipped; an absent {@code unit} or {@code path} is {@code null}, and so is a {@code term_marker}
     * that is not a boolean.
     */
    @Test
    void malformedEntryPartsAreLeftOut() {
        ObjectNode index = fixtureIndex();
        ObjectNode data = rawEntry(index, "spectra_data.parquet");
        data.putObject("column_mapping").put("path", "mz");
        data.putObject("parameters").put("name", "label");
        ArrayNode mapping = rawEntry(index, "spectra_metadata_scans.parquet").putArray("column_mapping");
        mapping.add("scan_start_time");
        mapping.add(1);
        mapping.addNull();
        mapping.addArray().add("scan_start_time");
        mapping.addObject().put("name", "scan start time").put("path", "scan_start_time")
                .put("accession", "MS:1000016");
        mapping.addObject().put("name", "filter string").putNull("path").put("accession", "MS:1000512")
                .putNull("unit").putNull("term_marker");
        mapping.addObject().put("name", "preset scan configuration").put("accession", "MS:1000616")
                .put("term_marker", "true");

        MzPeakManifest m = parse(index);

        Entry e = entry(m, "spectra_data.parquet");
        assertThat(e.columnMapping()).isEmpty();
        assertThat(e.parameters()).isEmpty();
        assertThat(entry(m, "spectra_metadata_scans.parquet").columnMapping()).containsExactly(
                new ColumnMapping("scan start time", "scan_start_time", "MS:1000016", null, null, null),
                new ColumnMapping("filter string", null, "MS:1000512", null, null, null),
                new ColumnMapping("preset scan configuration", null, "MS:1000616", null, null, null));
    }

    /** A {@code metadata} that is absent or not an object yields no version, CVs, scan settings or imaging flag. */
    @ParameterizedTest(name = "metadata = {0}")
    @ValueSource(strings = {"absent", "null", "[]", "\"0.9.0\"", "1"})
    void metadataThatIsNotAnObjectIsIgnored(String metadata) {
        ObjectNode index = fixtureIndex();
        if (metadata.equals("absent")) {
            index.remove("metadata");
        } else {
            index.set("metadata", json(metadata));
        }

        MzPeakManifest m = parse(index);

        assertThat(m.files()).isEqualTo(parse(fixtureIndex()).files());
        assertThat(m.version()).isNull();
        assertThat(m.cvList()).isEmpty();
        assertThat(m.scanSettingsList()).isEmpty();
        assertThat(m.isImaging()).isFalse();
        assertThat(m.metadataJson()).isNull();
    }

    /** A {@code scan_settings_list} or {@code cv_list} that is not an array reads as empty. */
    @Test
    void metadataListsThatAreNotArraysAreEmpty() {
        ObjectNode index = fixtureIndex();
        ObjectNode metadata = (ObjectNode) index.get("metadata");
        metadata.putObject("scan_settings_list").put("id", "settings");
        metadata.putObject("cv_list").put("id", "MS");

        MzPeakManifest m = parse(index);

        assertThat(m.scanSettingsList()).isEmpty();
        assertThat(m.cvList()).isEmpty();
        assertThat(m.version()).isEqualTo("0.9.0");
        assertThat(m.metadataJson()).isEqualTo(metadata);
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
                Arguments.of("true", false),
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

    private static Entry entry(MzPeakManifest manifest, String fileName) {
        return manifest.files().stream().filter(e -> fileName.equals(e.name())).findFirst()
                .orElseThrow(() -> new AssertionError("no manifest entry " + fileName));
    }
}
