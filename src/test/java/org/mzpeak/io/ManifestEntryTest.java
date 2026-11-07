package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.model.Param;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing of the per-file entries of {@code mzpeak_index.json}: {@code column_mapping} (alias
 * {@code metadata_mapping}) with {@code term_marker}, {@code parameters} and {@code checksum}. Expected values are
 * taken from the vendored manifests; entries are looked up by file name.
 */
class ManifestEntryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INDEX = "mzpeak_index.json";

    static Stream<String> currentFixtures() {
        return Fixtures.CURRENT_NAMES.stream();
    }

    static Stream<String> legacyFixtures() {
        return Fixtures.LEGACY_NAMES.stream();
    }

    @Test
    void currentSpectrumMetadataMapsItsColumnsToTerms() {
        Entry e = entry(manifest(Fixtures.current("small.unpacked.mzpeak")), "spectra_metadata.parquet");

        assertThat(e.columnMapping()).hasSize(12);
        assertThat(e.columnMapping()).extracting(ColumnMapping::path).containsExactly(
                "ms_level", "scan_polarity", "spectrum_representation", "spectrum_type", "lowest_observed_mz",
                "highest_observed_mz", "number_of_data_points", "number_of_peaks", "base_peak_mz",
                "base_peak_intensity", "total_ion_current", "polynomial_delta_coordinate_interpolation");
        assertThat(e.columnForAccession("MS:1000511")).contains(
                new ColumnMapping("ms level", "ms_level", "MS:1000511", null, null));
        assertThat(e.columnForAccession("MS:1003821")).map(ColumnMapping::path)
                .contains("polynomial_delta_coordinate_interpolation");
        assertThat(e.columnAtPath("spectrum_representation")).contains(new ColumnMapping(
                "spectrum representation", "spectrum_representation", "MS:1000525", null, Boolean.TRUE));
        assertThat(e.columnAtPath("base_peak_intensity")).map(ColumnMapping::unit).contains("MS:1000131");
        assertThat(e.parameters()).isEmpty();
        assertThat(e.checksum()).isEqualTo("1b0a2e0f1f02e0c20510383a9473d40ca3c243d791763e8592d17f76591ce0a3"
                + "0813d30bab469f7950b1076d9a51fc1ca31e4f65655fdab30a87e41884a69a1e");
    }

    @Test
    void currentPrecursorsMapActivationAndIsolationWindow() {
        Entry e = entry(manifest(Fixtures.current("small.unpacked.mzpeak")), "spectra_metadata_precursors.parquet");

        assertThat(e.columnForAccession("MS:1000044")).contains(new ColumnMapping(
                "dissociation method", "activation.dissociation_method", "MS:1000044", null, Boolean.TRUE));
        assertThat(e.columnForAccession("MS:1000827")).contains(new ColumnMapping(
                "isolation window target m/z", "isolation_window.isolation_window_target", "MS:1000827",
                "MS:1000040", null));
        assertThat(e.columnAtPath("activation.collision_energy")).map(ColumnMapping::accession)
                .contains("MS:1000045");
        assertThat(e.columnForAccession("MS:1000511")).isEmpty();
        assertThat(e.columnAtPath("ms_level")).isEmpty();
    }

    @Test
    void imagingScansMapPositionColumns() {
        Entry e = entry(manifest(Fixtures.current("Example_Processed.img.mzpeak")), "spectra_metadata_scans.parquet");

        assertThat(e.columnMapping()).hasSize(8);
        assertThat(e.columnForAccession("IMS:1000050")).contains(
                new ColumnMapping("position x", "opt_IMS_1000050_position_x", "IMS:1000050", null, null));
        assertThat(e.columnForAccession("IMS:1000051")).map(ColumnMapping::path)
                .contains("opt_IMS_1000051_position_y");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void currentEntriesCarryTheirChecksum(String fixture) throws IOException {
        Path dataset = Fixtures.current(fixture);
        JsonNode files = rawIndex(dataset).path("files");
        List<Entry> entries = manifest(dataset).files();

        assertThat(entries).hasSize(files.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            assertThat(e.checksum()).as("%s: checksum of %s", fixture, e.name())
                    .matches("^[0-9a-f]{128}$")
                    .isEqualTo(files.get(i).path("checksum").asText());
            assertThat(e.columnMapping()).as("%s: mappings of %s", fixture, e.name())
                    .hasSize(files.get(i).path("column_mapping").size());
            assertThat(e.parameters()).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("legacyFixtures")
    void legacyEntriesHaveNoChecksumAndNoMapping(String fixture) {
        List<Entry> entries = manifest(Fixtures.legacy(fixture)).files();

        assertThat(entries).isNotEmpty();
        for (Entry e : entries) {
            assertThat(e.checksum()).as("%s: checksum of %s", fixture, e.name()).isNull();
            assertThat(e.columnMapping()).as("%s: mappings of %s", fixture, e.name()).isEmpty();
            assertThat(e.parameters()).isEmpty();
            assertThat(e).isEqualTo(new Entry(e.name(), e.entityType(), e.dataKind()));
        }
    }

    @Test
    void metadataMappingIsAnAliasOfColumnMapping(@TempDir Path tmp) throws IOException {
        Path original = Fixtures.current("small.unpacked.mzpeak");
        Path copy = copyDataset(original, tmp);
        ObjectNode index = rawIndex(copy);
        for (JsonNode f : index.path("files")) {
            ObjectNode file = (ObjectNode) f;
            file.set("metadata_mapping", file.remove("column_mapping"));
        }
        writeIndex(copy, index);
        assertThat(Files.readString(copy.resolve(INDEX))).doesNotContain("column_mapping");

        List<Entry> expected = manifest(original).files();
        List<Entry> aliased = manifest(copy).files();

        assertThat(aliased).isEqualTo(expected);
        assertThat(entry(aliased, "spectra_metadata.parquet").columnMapping()).hasSize(12);
    }

    @Test
    void termMarkerIsNullWhenTheKeyIsAbsent(@TempDir Path tmp) throws IOException {
        Path copy = copyDataset(Fixtures.current("small.unpacked.mzpeak"), tmp);
        ObjectNode index = rawIndex(copy);
        ObjectNode metadata = rawEntry(index, "spectra_metadata.parquet");
        ((ObjectNode) mappingAt(metadata, "spectrum_representation")).remove("term_marker");
        ((ObjectNode) mappingAt(metadata, "spectrum_type")).put("term_marker", false);
        writeIndex(copy, index);

        Entry e = entry(manifest(copy), "spectra_metadata.parquet");

        assertThat(e.columnForAccession("MS:1000525").orElseThrow().termMarker()).isNull();
        assertThat(e.columnForAccession("MS:1000559").orElseThrow().termMarker()).isFalse();
        assertThat(entry(manifest(Fixtures.current("small.unpacked.mzpeak")), "spectra_metadata.parquet")
                .columnForAccession("MS:1000559").orElseThrow().termMarker()).isTrue();
    }

    @Test
    void pathGivenAsArrayIsJoinedWithDots(@TempDir Path tmp) throws IOException {
        Path original = Fixtures.current("small.unpacked.mzpeak");
        Path copy = copyDataset(original, tmp);
        ObjectNode index = rawIndex(copy);
        ObjectNode precursors = rawEntry(index, "spectra_metadata_precursors.parquet");
        ObjectNode target = (ObjectNode) mappingAt(precursors, "isolation_window.isolation_window_target");
        target.putArray("path").add("isolation_window").add("isolation_window_target");
        ObjectNode msLevel = (ObjectNode) mappingAt(rawEntry(index, "spectra_metadata.parquet"), "ms_level");
        msLevel.putArray("path").add("ms_level");
        writeIndex(copy, index);

        assertThat(manifest(copy).files()).isEqualTo(manifest(original).files());
    }

    @Test
    void unitGivenAsArrayIsJoinedWithDots(@TempDir Path tmp) throws IOException {
        Path copy = copyDataset(Fixtures.current("small.unpacked.mzpeak"), tmp);
        ObjectNode index = rawIndex(copy);
        ObjectNode energy = (ObjectNode) mappingAt(rawEntry(index, "spectra_metadata_precursors.parquet"),
                "activation.collision_energy");
        energy.putArray("unit").add("activation").add("opt_collision_energy_unit");
        writeIndex(copy, index);

        Entry e = entry(manifest(copy), "spectra_metadata_precursors.parquet");

        assertThat(e.columnForAccession("MS:1000045")).map(ColumnMapping::unit)
                .contains("activation.opt_collision_energy_unit");
        assertThat(e.columnForAccession("MS:1000827")).map(ColumnMapping::unit).contains("MS:1000040");
    }

    @Test
    void parametersReadUnitElseUnitAccession(@TempDir Path tmp) throws IOException {
        Path copy = copyDataset(Fixtures.current("small.unpacked.mzpeak"), tmp);
        ObjectNode index = rawIndex(copy);
        ArrayNode params = rawEntry(index, "spectra_data.parquet").putArray("parameters");
        params.addObject().put("name", "scan start time").put("accession", "MS:1000016")
                .put("value", 1.5).put("unit", "UO:0000031");
        params.addObject().put("name", "label").putNull("accession")
                .put("value", "x").put("unit_accession", "UO:0000010");
        params.addObject().put("name", "both").putNull("accession")
                .put("value", true).put("unit", "UO:0000031").put("unit_accession", "UO:0000010");
        params.addObject().put("name", "neither").putNull("accession").putNull("value").putNull("unit");
        params.addObject().put("name", "serial").putNull("accession").put("value", "00123");
        params.addObject().put("name", "version").putNull("accession").put("value", "1.10");
        params.addObject().put("name", "suffix").putNull("accession").put("value", "5f");
        params.addObject().put("name", "nan").putNull("accession").put("value", "NaN");
        params.addObject().put("name", "count").putNull("accession").put("value", 42);
        params.addObject().put("name", "list").putNull("accession").putArray("value").add(1).add("a");
        params.addObject().put("name", "object").putNull("accession").putObject("value").put("k", "v");
        params.addObject().put("name", "absent").putNull("accession");
        writeIndex(copy, index);

        Entry e = entry(manifest(copy), "spectra_data.parquet");

        assertThat(e.parameters()).containsExactly(
                new Param("scan start time", "MS:1000016", 1.5, "UO:0000031"),
                new Param("label", null, "x", "UO:0000010"),
                new Param("both", null, true, "UO:0000031"),
                new Param("neither", null, null, null),
                new Param("serial", null, "00123", null),
                new Param("version", null, "1.10", null),
                new Param("suffix", null, "5f", null),
                new Param("nan", null, "NaN", null),
                new Param("count", null, 42L, null),
                new Param("list", null, "[1,\"a\"]", null),
                new Param("object", null, "{\"k\":\"v\"}", null),
                new Param("absent", null, null, null));
    }

    @Test
    void threeArgumentConstructorUsesTheDefaults() {
        Entry three = new Entry("spectra_metadata.parquet", "spectrum", "metadata");
        Entry six = new Entry("spectra_metadata.parquet", "spectrum", "metadata", List.of(), List.of(), null);

        assertThat(three).isEqualTo(six).hasSameHashCodeAs(six);
        assertThat(three.columnMapping()).isEmpty();
        assertThat(three.parameters()).isEmpty();
        assertThat(three.checksum()).isNull();
        assertThat(new Entry("spectra_metadata.parquet", "spectrum", "metadata", null, null, null)).isEqualTo(three);
    }

    @Test
    void listsAreCopiedDefensively() {
        List<ColumnMapping> mapping = new ArrayList<>();
        mapping.add(new ColumnMapping("ms level", "ms_level", "MS:1000511", null, null));
        List<Param> params = new ArrayList<>();
        params.add(new Param("label", null, "x", null));

        Entry e = new Entry("spectra_metadata.parquet", "spectrum", "metadata", mapping, params, null);
        mapping.clear();
        params.clear();

        assertThat(e.columnMapping()).hasSize(1);
        assertThat(e.parameters()).hasSize(1);
        assertThatThrownBy(() -> e.columnMapping().add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> e.parameters().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void lookupsReturnTheFirstMatchInFileOrder() {
        ColumnMapping first = new ColumnMapping("dissociation method", "activation.dissociation_method",
                "MS:1000044", null, Boolean.TRUE);
        ColumnMapping second = new ColumnMapping("supplemental beam-type collision-induced dissociation",
                "activation.opt_MS_1002678_suppl_beam_disc", "MS:1000044", null, Boolean.TRUE);
        ColumnMapping samePath = new ColumnMapping("dissociation method (again)", "activation.dissociation_method",
                "MS:1000045", null, null);
        Entry e = new Entry("spectra_metadata_precursors.parquet", "spectrum", "precursors",
                List.of(first, second, samePath), List.of(), null);

        assertThat(e.columnForAccession("MS:1000044")).containsSame(first);
        assertThat(e.columnAtPath("activation.dissociation_method")).containsSame(first);
        assertThat(e.columnAtPath("activation.opt_MS_1002678_suppl_beam_disc")).containsSame(second);
        assertThat(e.columnForAccession("MS:1000045")).containsSame(samePath);
        assertThat(e.columnForAccession("MS:1000511")).isEmpty();
        assertThat(e.columnAtPath("activation")).isEmpty();
        assertThatThrownBy(() -> e.columnForAccession(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> e.columnAtPath(null)).isInstanceOf(NullPointerException.class);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static MzPeakManifest manifest(Path dataset) {
        try (MzPeakSource src = MzPeakSource.open(dataset)) {
            return MzPeakManifest.fromSource(src);
        }
    }

    private static Entry entry(MzPeakManifest manifest, String fileName) {
        return entry(manifest.files(), fileName);
    }

    private static Entry entry(List<Entry> entries, String fileName) {
        return entries.stream().filter(e -> fileName.equals(e.name())).findFirst()
                .orElseThrow(() -> new AssertionError("no manifest entry " + fileName));
    }

    private static ObjectNode rawIndex(Path dataset) throws IOException {
        try (MzPeakSource src = MzPeakSource.open(dataset)) {
            return (ObjectNode) MAPPER.readTree(src.readManifestBytes());
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

    private static JsonNode mappingAt(ObjectNode rawEntry, String path) {
        for (JsonNode m : rawEntry.path("column_mapping")) {
            if (path.equals(m.path("path").asText())) {
                return m;
            }
        }
        throw new AssertionError("no column mapping " + path + " in " + rawEntry.path("name").asText());
    }

    private static Path copyDataset(Path unpacked, Path tmp) throws IOException {
        Path copy = tmp.resolve(unpacked.getFileName().toString());
        Files.createDirectories(copy);
        try (Stream<Path> files = Files.list(unpacked)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Files.copy(f, copy.resolve(f.getFileName().toString()));
            }
        }
        return copy;
    }

    private static void writeIndex(Path unpacked, JsonNode index) throws IOException {
        Files.write(unpacked.resolve(INDEX), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(index));
    }
}
