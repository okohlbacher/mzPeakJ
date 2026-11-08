package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ManifestWriter.Dialect;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ManifestWriter} below the writer forms (WR-1): how each {@link Dialect} spells the roles, and how a
 * {@link WrittenMember}'s column mapping and checksum are written. The packed writer itself only writes the pin-era
 * dialect with empty column mappings; {@code WriterManifestConformanceTest} covers that output.
 */
class ManifestWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ABC_SHA512 = "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
            + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f";

    @Test
    void thePinEraDialectSpellsOnlyDataArraysAndWavelengthSpectrumWithASpace() {
        for (EntityType entity : EntityType.values()) {
            assertThat(Dialect.CURRENT.entityType(entity)).as("CURRENT %s", entity).isEqualTo(entity.canonical());
            assertThat(Dialect.PIN_29E59B2.entityType(entity)).as("PIN_29E59B2 %s", entity).isEqualTo(
                    entity == EntityType.WAVELENGTH_SPECTRUM ? "wavelength spectrum" : entity.canonical());
            for (Dialect dialect : Dialect.values()) {
                assertThat(EntityType.parse(dialect.entityType(entity))).as("%s %s parsed", dialect, entity)
                        .isEqualTo(entity);
            }
        }
        for (DataKind kind : DataKind.values()) {
            assertThat(Dialect.CURRENT.dataKind(kind)).as("CURRENT %s", kind).isEqualTo(kind.canonical());
            assertThat(Dialect.PIN_29E59B2.dataKind(kind)).as("PIN_29E59B2 %s", kind).isEqualTo(
                    kind == DataKind.DATA_ARRAYS ? "data arrays" : kind.canonical());
            for (Dialect dialect : Dialect.values()) {
                assertThat(DataKind.parse(dialect.dataKind(kind))).as("%s %s parsed", dialect, kind).isEqualTo(kind);
            }
        }
    }

    @Test
    void membersAreListedInOrderWithTheirColumnMappingAndChecksum(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("wavelength_spectra_data.parquet"), "abc".getBytes(StandardCharsets.US_ASCII));
        Files.write(dir.resolve("spectra_metadata.parquet"), new byte[0]);
        List<ColumnMapping> mapping = List.of(
                new ColumnMapping("ms level", "ms_level", "MS:1000511", null, null),
                new ColumnMapping("scan start time", "scan_start_time", "MS:1000016", "UO:0000031", Boolean.FALSE),
                new ColumnMapping("chromatogram type", "chromatogram_type", "MS:1000626", null, Boolean.TRUE),
                new ColumnMapping("lower limit", "scan_windows.scan_window_lower_limit", null, "MS:1000040", null));
        List<WrittenMember> members = List.of(
                new WrittenMember("wavelength_spectra_data.parquet", EntityType.WAVELENGTH_SPECTRUM,
                        DataKind.DATA_ARRAYS),
                new WrittenMember("spectra_metadata.parquet", EntityType.SPECTRUM, DataKind.METADATA, mapping));
        WritePlan plan = WritePlanner.plan(List.of(), List.of(), List.of(), List.of(), FileMetadata.EMPTY,
                MzPeakWriter.Options.defaults());

        ManifestWriter.writeManifest(dir, members, plan, Dialect.CURRENT);

        JsonNode files = MAPPER.readTree(Files.readAllBytes(dir.resolve(ManifestWriter.INDEX))).path("files");
        assertThat(files).hasSize(2);
        JsonNode data = files.get(0);
        assertThat(fieldNames(data))
                .containsExactly("name", "entity_type", "data_kind", "column_mapping", "parameters", "checksum");
        assertThat(data.path("name").asText()).isEqualTo("wavelength_spectra_data.parquet");
        assertThat(data.path("entity_type").asText()).isEqualTo("wavelength_spectrum");
        assertThat(data.path("data_kind").asText()).isEqualTo("data_arrays");
        assertThat(data.get("column_mapping").isArray()).isTrue();
        assertThat(data.get("column_mapping")).isEmpty();
        assertThat(data.get("parameters").isArray()).isTrue();
        assertThat(data.get("parameters")).isEmpty();
        assertThat(data.path("checksum").asText()).isEqualTo(ABC_SHA512);

        JsonNode metadata = files.get(1);
        assertThat(metadata.path("checksum").asText()).isEqualTo(Sha512.hex(dir.resolve("spectra_metadata.parquet")));
        JsonNode columns = metadata.get("column_mapping");
        assertThat(columns).hasSize(4);
        assertThat(fieldNames(columns.get(0))).containsExactly("name", "path", "accession", "unit");
        assertThat(columns.get(0).get("unit").isNull()).isTrue();
        assertThat(fieldNames(columns.get(1))).as("a false term_marker is not written")
                .containsExactly("name", "path", "accession", "unit");
        assertThat(fieldNames(columns.get(2))).containsExactly("name", "path", "accession", "unit", "term_marker");
        assertThat(columns.get(2).get("term_marker").booleanValue()).isTrue();
        assertThat(columns.get(3).get("accession").isNull()).isTrue();
        assertThat(columns.get(3).get("path").asText()).isEqualTo("scan_windows.scan_window_lower_limit");

        MzPeakManifest manifest;
        try (MzPeakSource source = MzPeakSource.open(dir)) {
            manifest = MzPeakManifest.fromSource(source);
        }
        // The reader rebuilds every mapping item; only a false term_marker comes back as absent.
        assertThat(manifest.find(EntityType.SPECTRUM, DataKind.METADATA).orElseThrow().columnMapping())
                .containsExactly(
                        mapping.get(0),
                        new ColumnMapping("scan start time", "scan_start_time", "MS:1000016", "UO:0000031", null),
                        mapping.get(2),
                        mapping.get(3));
        assertThat(manifest.find(EntityType.WAVELENGTH_SPECTRUM, DataKind.DATA_ARRAYS).orElseThrow().checksum())
                .isEqualTo(ABC_SHA512);
    }

    @Test
    void thePinEraDialectWritesTheSameKeys(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("chromatograms_data.parquet"), "abc".getBytes(StandardCharsets.US_ASCII));
        WritePlan plan = WritePlanner.plan(List.of(), List.of(), List.of(), List.of(), FileMetadata.EMPTY,
                MzPeakWriter.Options.defaults());

        ManifestWriter.writeManifest(dir, List.of(new WrittenMember("chromatograms_data.parquet",
                EntityType.CHROMATOGRAM, DataKind.DATA_ARRAYS)), plan, Dialect.PIN_29E59B2);

        JsonNode entry = MAPPER.readTree(Files.readAllBytes(dir.resolve(ManifestWriter.INDEX))).path("files").get(0);
        assertThat(fieldNames(entry))
                .containsExactly("name", "entity_type", "data_kind", "column_mapping", "parameters", "checksum");
        assertThat(entry.path("entity_type").asText()).isEqualTo("chromatogram");
        assertThat(entry.path("data_kind").asText()).isEqualTo("data arrays");
        assertThat(entry.path("checksum").asText()).isEqualTo(ABC_SHA512);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
