package org.mzpeak.io;

import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.Type;
import org.junit.jupiter.api.Test;
import org.mzpeak.io.ColumnTable.Column;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.Entry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The constant column-identity table: its canonical rows against the {@code column_mapping}s of the vendored current
 * manifests, its fallbacks against the pin-era names of the decoders, the packed writer and the legacy fixtures, and
 * its structural columns. Members are found by role through {@link Facet#entry}.
 */
class ColumnTableTest {

    /** A pin-era leaf name: a CURIE with ':' replaced by '_', then '_' and the term name. */
    private static final Pattern PIN_ERA_NAME = Pattern.compile("^(MS|IMS|UO)_(\\d{7})_");

    /** A pin-era name used by today's code, with the facet and accession it belongs to. */
    private record Legacy(Facet facet, String accession, String path) {
    }

    /**
     * Every CV-named constant of SpectrumMetadataDecoder, ChromatogramStore, WavelengthSpectrumStore and the packed
     * MzPeakWriter schemas. A constant nested in a struct or list is given with its parent path.
     */
    private static final List<Legacy> LEGACY_CONSTANTS = List.of(
            // SpectrumMetadataDecoder and MzPeakWriter.METADATA_SCHEMA: spectrum struct
            new Legacy(Facet.SPECTRUM, "MS:1000511", "MS_1000511_ms_level"),
            new Legacy(Facet.SPECTRUM, "MS:1000465", "MS_1000465_scan_polarity"),
            new Legacy(Facet.SPECTRUM, "MS:1000525", "MS_1000525_spectrum_representation"),
            new Legacy(Facet.SPECTRUM, "MS:1003060", "MS_1003060_number_of_data_points"),
            new Legacy(Facet.SPECTRUM, "MS:1003059", "MS_1003059_number_of_peaks"),
            // MzPeakWriter.METADATA_SCHEMA only: spectrum struct
            new Legacy(Facet.SPECTRUM, "MS:1000559", "MS_1000559_spectrum_type"),
            new Legacy(Facet.SPECTRUM, "MS:1000528", "MS_1000528_lowest_observed_mz_unit_MS_1000040"),
            new Legacy(Facet.SPECTRUM, "MS:1000527", "MS_1000527_highest_observed_mz_unit_MS_1000040"),
            new Legacy(Facet.SPECTRUM, "MS:1000504", "MS_1000504_base_peak_mz_unit_MS_1000040"),
            new Legacy(Facet.SPECTRUM, "MS:1000505", "MS_1000505_base_peak_intensity_unit_MS_1000131"),
            new Legacy(Facet.SPECTRUM, "MS:1000285", "MS_1000285_total_ion_current_unit_MS_1000131"),
            // SpectrumMetadataDecoder and MzPeakWriter.METADATA_SCHEMA: scan struct
            new Legacy(Facet.SPECTRUM_SCAN, "MS:1000016", "MS_1000016_scan_start_time_unit_UO_0000031"),
            new Legacy(Facet.SPECTRUM_SCAN, "MS:1000927", "MS_1000927_ion_injection_time_unit_UO_0000028"),
            new Legacy(Facet.SPECTRUM_SCAN, "MS:1000512", "MS_1000512_filter_string"),
            new Legacy(Facet.SPECTRUM_SCAN, "MS:1000616", "MS_1000616_preset_scan_configuration"),
            new Legacy(Facet.SPECTRUM_SCAN, "MS:1000501",
                    "scan_windows.MS_1000501_scan_window_lower_limit_unit_MS_1000040"),
            new Legacy(Facet.SPECTRUM_SCAN, "MS:1000500",
                    "scan_windows.MS_1000500_scan_window_upper_limit_unit_MS_1000040"),
            new Legacy(Facet.SPECTRUM_SCAN, "IMS:1000050", "IMS_1000050_position_x"),
            new Legacy(Facet.SPECTRUM_SCAN, "IMS:1000051", "IMS_1000051_position_y"),
            // SpectrumMetadataDecoder and MzPeakWriter.METADATA_SCHEMA: precursor struct
            new Legacy(Facet.SPECTRUM_PRECURSOR, "MS:1000827",
                    "isolation_window.MS_1000827_isolation_window_target_mz"),
            new Legacy(Facet.SPECTRUM_PRECURSOR, "MS:1000828",
                    "isolation_window.MS_1000828_isolation_window_lower_offset"),
            new Legacy(Facet.SPECTRUM_PRECURSOR, "MS:1000829",
                    "isolation_window.MS_1000829_isolation_window_upper_offset"),
            // SpectrumMetadataDecoder and MzPeakWriter.METADATA_SCHEMA: selected_ion struct
            new Legacy(Facet.SPECTRUM_SELECTED_ION, "MS:1000744", "MS_1000744_selected_ion_mz_unit_MS_1000040"),
            new Legacy(Facet.SPECTRUM_SELECTED_ION, "MS:1000041", "MS_1000041_charge_state"),
            new Legacy(Facet.SPECTRUM_SELECTED_ION, "MS:1000042", "MS_1000042_intensity_unit_MS_1000131"),
            // ChromatogramStore and MzPeakWriter.CHROM_META_SCHEMA
            new Legacy(Facet.CHROMATOGRAM, "MS:1000626", "MS_1000626_chromatogram_type"),
            new Legacy(Facet.CHROMATOGRAM, "MS:1003060", "MS_1003060_number_of_data_points"),
            // WavelengthSpectrumStore and MzPeakWriter.WAVELENGTH_META_SCHEMA
            new Legacy(Facet.WAVELENGTH_SPECTRUM, "MS:1003812", "MS_1003812_lambda_max_unit_UO:0000018"),
            new Legacy(Facet.WAVELENGTH_SPECTRUM, "MS:1003060", "MS_1003060_number_of_data_points"));

    @Test
    void eachFacetHasTheUnionOfTheCurrentMappings() {
        Map<Facet, Integer> sizes = new EnumMap<>(Facet.class);
        for (Facet f : Facet.values()) {
            sizes.put(f, ColumnTable.columns(f).size());
        }

        assertThat(sizes).isEqualTo(Map.of(
                Facet.SPECTRUM, 12, Facet.SPECTRUM_SCAN, 8, Facet.SPECTRUM_PRECURSOR, 5,
                Facet.SPECTRUM_SELECTED_ION, 3, Facet.CHROMATOGRAM, 3, Facet.CHROMATOGRAM_PRECURSOR, 5,
                Facet.CHROMATOGRAM_SELECTED_ION, 3, Facet.WAVELENGTH_SPECTRUM, 8, Facet.WAVELENGTH_SCAN, 6));
    }

    @Test
    void everyRowEqualsEveryCurrentManifestThatHasItsPath() {
        List<String> conflicts = new ArrayList<>();
        List<String> unknownMappings = new ArrayList<>();
        Set<Column> seen = new HashSet<>();
        int compared = 0;

        for (String fixture : Fixtures.CURRENT_NAMES) {
            MzPeakManifest manifest = manifest(Fixtures.current(fixture));
            Set<Entry> covered = new HashSet<>();
            for (Facet facet : Facet.values()) {
                Optional<Entry> member = facet.entry(manifest);
                if (member.isEmpty()) {
                    continue;
                }
                Entry e = member.get();
                covered.add(e);
                List<String> tableOrder = new ArrayList<>();
                for (Column c : ColumnTable.columns(facet)) {
                    Optional<ColumnMapping> m = e.columnAtPath(c.path());
                    if (m.isEmpty()) {
                        continue;
                    }
                    compared++;
                    seen.add(c);
                    tableOrder.add(c.path());
                    if (!sameIdentity(c, m.get())) {
                        conflicts.add(fixture + " " + facet + " " + c.path() + ": table " + identity(c)
                                + ", manifest " + identity(m.get()));
                    }
                }
                List<String> fileOrder = e.columnMapping().stream().map(ColumnMapping::path).toList();
                for (String path : fileOrder) {
                    if (ColumnTable.columns(facet).stream().noneMatch(c -> c.path().equals(path))) {
                        unknownMappings.add(fixture + " " + facet + " " + path);
                    }
                }
                if (!tableOrder.equals(fileOrder)) {
                    conflicts.add(fixture + " " + facet + ": table order " + tableOrder + ", file order " + fileOrder);
                }
            }
            for (Entry e : manifest.files()) {
                if (!e.columnMapping().isEmpty() && !covered.contains(e)) {
                    unknownMappings.add(fixture + " " + e.name() + ": mapped member of no facet");
                }
            }
        }

        assertThat(conflicts).as("rows that differ from a current manifest").isEmpty();
        assertThat(unknownMappings).as("current mappings missing from the table").isEmpty();
        List<String> unused = new ArrayList<>();
        for (Facet f : Facet.values()) {
            for (Column c : ColumnTable.columns(f)) {
                if (!seen.contains(c)) {
                    unused.add(f + " " + c.path());
                }
            }
        }
        assertThat(unused).as("canonical rows found in no current manifest").isEmpty();
        assertThat(compared).isEqualTo(301);
    }

    @Test
    void currentRowsCarryTheirTermAndUnit() {
        assertThat(ColumnTable.byAccession(Facet.SPECTRUM, "MS:1000511")).contains(new Column(Facet.SPECTRUM,
                "ms_level", "MS:1000511", "ms level", null, false, List.of("MS_1000511_ms_level")));
        assertThat(ColumnTable.byAccession(Facet.SPECTRUM, "MS:1003821")).map(Column::path)
                .contains("polynomial_delta_coordinate_interpolation");
        assertThat(ColumnTable.byAccession(Facet.SPECTRUM, "MS:1003821")).map(Column::fallbacks)
                .contains(List.of());
        assertThat(ColumnTable.byAccession(Facet.SPECTRUM_PRECURSOR, "MS:1000044")).contains(new Column(
                Facet.SPECTRUM_PRECURSOR, "activation.dissociation_method", "MS:1000044", "dissociation method",
                null, true, List.of()));
        assertThat(ColumnTable.byAccession(Facet.CHROMATOGRAM_PRECURSOR, "MS:1000045")).contains(new Column(
                Facet.CHROMATOGRAM_PRECURSOR, "activation.collision_energy", "MS:1000045", "collision energy",
                "UO:0000266", false, List.of()));
        assertThat(ColumnTable.byAccession(Facet.CHROMATOGRAM, "MS:1000626")).map(Column::termMarker)
                .contains(true);
        assertThat(ColumnTable.byAccession(Facet.WAVELENGTH_SPECTRUM, "MS:1003812")).contains(new Column(
                Facet.WAVELENGTH_SPECTRUM, "lambda_max", "MS:1003812", "lambda max", "UO:0000018", false,
                List.of("MS_1003812_lambda_max_unit_UO:0000018")));
        assertThat(ColumnTable.columns(Facet.SPECTRUM).stream().filter(Column::termMarker).map(Column::path))
                .containsExactly("spectrum_representation", "spectrum_type");
    }

    @Test
    void everyLegacyConstantIsAFallbackUnderItsAccession() {
        List<String> missing = new ArrayList<>();
        for (Legacy l : LEGACY_CONSTANTS) {
            Optional<Column> row = ColumnTable.byAccession(l.facet(), l.accession());
            if (row.isEmpty() || !row.get().fallbacks().contains(l.path())
                    || !row.equals(ColumnTable.byPath(l.facet(), l.path()))) {
                missing.add(l.facet() + " " + l.accession() + " " + l.path());
            }
        }

        assertThat(missing).isEmpty();
    }

    @Test
    void everyPinEraNameOfTheLegacyFixturesIsAFallbackUnderItsAccession() {
        List<String> missing = new ArrayList<>();
        Set<String> checked = new LinkedHashSet<>();

        for (String fixture : Fixtures.LEGACY_NAMES) {
            Path dataset = Fixtures.legacy(fixture);
            MzPeakManifest manifest = manifest(dataset);
            try (MzPeakSource src = MzPeakSource.open(dataset)) {
                for (Entry e : manifest.files()) {
                    if (!"metadata".equals(e.dataKind())) {
                        continue;
                    }
                    String entity = e.entityType().trim().toLowerCase(Locale.ROOT).replace(' ', '_');
                    for (Type struct : ParquetFooter.read(src.inputFile(e.name())).schema().getFields()) {
                        Facet facet = packedFacet(entity, struct.getName());
                        List<String> leaves = new ArrayList<>();
                        collectLeaves(struct.asGroupType(), "", leaves);
                        for (String path : leaves) {
                            Matcher m = PIN_ERA_NAME.matcher(path.substring(path.lastIndexOf('.') + 1));
                            if (!m.find()) {
                                continue;
                            }
                            String accession = m.group(1) + ":" + m.group(2);
                            checked.add(facet + " " + accession + " " + path);
                            Optional<Column> row = ColumnTable.byPath(facet, path);
                            if (row.isEmpty() || !row.get().accession().equals(accession)
                                    || !row.get().fallbacks().contains(path)) {
                                missing.add(fixture + " " + e.name() + " " + facet + " " + path);
                            }
                        }
                    }
                }
            }
        }

        assertThat(missing).isEmpty();
        assertThat(checked).contains(
                "SPECTRUM MS:1000511 MS_1000511_ms_level",
                "SPECTRUM_SCAN MS:1000501 scan_windows.MS_1000501_scan_window_lower_limit_unit_MS_1000040",
                "SPECTRUM_PRECURSOR MS:1000827 isolation_window.MS_1000827_isolation_window_target_mz",
                "CHROMATOGRAM MS:1000465 MS_1000465_scan_polarity",
                "CHROMATOGRAM_SELECTED_ION MS:1000042 MS_1000042_intensity_unit_MS_1000131",
                "WAVELENGTH_SPECTRUM MS:1003812 MS_1003812_lambda_max_unit_UO:0000018",
                "WAVELENGTH_SPECTRUM MS:1000619 MS_1000619_lowest_observed_wavelength_unit_UO_0000018",
                "WAVELENGTH_SCAN MS:1000016 MS_1000016_scan_start_time_unit_UO_0000031");
        assertThat(checked).hasSize(46);
    }

    @Test
    void specAlternatesAreTheFirstFallbacks() {
        for (Facet f : List.of(Facet.SPECTRUM_SELECTED_ION, Facet.CHROMATOGRAM_SELECTED_ION)) {
            Column intensity = ColumnTable.byAccession(f, "MS:1000042").orElseThrow();
            assertThat(intensity.path()).isEqualTo("peak_intensity");
            assertThat(intensity.fallbacks()).containsExactly("intensity", "MS_1000042_intensity_unit_MS_1000131");
            assertThat(ColumnTable.byPath(f, "intensity")).containsSame(intensity);
        }
        Column x = ColumnTable.byAccession(Facet.SPECTRUM_SCAN, "IMS:1000050").orElseThrow();
        Column y = ColumnTable.byAccession(Facet.SPECTRUM_SCAN, "IMS:1000051").orElseThrow();

        assertThat(x.path()).isEqualTo("opt_IMS_1000050_position_x");
        assertThat(x.fallbacks()).containsExactly("position_x", "IMS_1000050_position_x");
        assertThat(y.path()).isEqualTo("opt_IMS_1000051_position_y");
        assertThat(y.fallbacks()).containsExactly("position_y", "IMS_1000051_position_y");
        assertThat(ColumnTable.byPath(Facet.SPECTRUM_SCAN, "position_x")).containsSame(x);
        assertThat(ColumnTable.byPath(Facet.SPECTRUM_SCAN, "position_y")).containsSame(y);
        assertThat(ColumnTable.byPath(Facet.WAVELENGTH_SCAN, "position_x")).isEmpty();
    }

    @Test
    void namesAreUniqueWithinAFacetAndLookupsFindTheirRow() {
        for (Facet f : Facet.values()) {
            Set<String> accessions = new HashSet<>();
            Set<String> names = new HashSet<>();
            for (Column c : ColumnTable.columns(f)) {
                assertThat(c.facet()).isEqualTo(f);
                assertThat(accessions.add(c.accession())).as("%s: accession %s twice", f, c.accession()).isTrue();
                assertThat(names.add(c.path())).as("%s: name %s twice", f, c.path()).isTrue();
                assertThat(ColumnTable.byAccession(f, c.accession())).containsSame(c);
                assertThat(ColumnTable.byPath(f, c.path())).containsSame(c);
                for (String fallback : c.fallbacks()) {
                    assertThat(names.add(fallback)).as("%s: name %s twice", f, fallback).isTrue();
                    assertThat(ColumnTable.byPath(f, fallback)).containsSame(c);
                }
            }
        }
        assertThat(ColumnTable.byAccession(Facet.SPECTRUM, "MS:1000016")).isEmpty();
        assertThat(ColumnTable.byPath(Facet.SPECTRUM, "scan_start_time")).isEmpty();
        assertThat(ColumnTable.byPath(Facet.SPECTRUM, "mz_delta_model")).isEmpty();
        assertThatThrownBy(() -> ColumnTable.byAccession(Facet.SPECTRUM, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ColumnTable.byPath(Facet.SPECTRUM, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ColumnTable.columns(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void structuralColumnsAreKeysAndBookkeeping() {
        assertThat(ColumnTable.structural(Facet.SPECTRUM)).contains("index", "id", "parameters",
                "auxiliary_arrays", "number_of_auxiliary_arrays", "data_processing_id");
        assertThat(ColumnTable.structural(Facet.SPECTRUM_SCAN)).contains("source_index", "spectrum_index",
                "scan_index", "parameters");
        assertThat(ColumnTable.structural(Facet.SPECTRUM_PRECURSOR)).contains("source_index", "precursor_index");
        assertThat(ColumnTable.structural(Facet.SPECTRUM_SELECTED_ION)).contains("source_index", "precursor_index",
                "selected_ion_index", "parameters");
        for (Facet f : Facet.values()) {
            Set<String> structural = ColumnTable.structural(f);
            assertThat(structural).as("%s: keys are structural", f).containsAll(f.keyColumns());
            for (Column c : ColumnTable.columns(f)) {
                List<String> names = new ArrayList<>(c.fallbacks());
                names.add(c.path());
                assertThat(structural).as("%s: data column %s", f, c.path()).doesNotContainAnyElementsOf(names);
            }
            assertThatThrownBy(() -> structural.add("time")).isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(ColumnTable.structural(Facet.SPECTRUM)).doesNotContain("time", "precursor_id",
                "instrument_configuration_id");
    }

    @Test
    void tableAndRowsAreUnmodifiable() {
        List<String> fallbacks = new ArrayList<>(List.of("a"));
        Column c = new Column(Facet.SPECTRUM, "p", "MS:0000000", "n", null, false, fallbacks);
        fallbacks.clear();

        assertThat(c.fallbacks()).containsExactly("a");
        assertThatThrownBy(() -> c.fallbacks().add("b")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ColumnTable.columns(Facet.SPECTRUM).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ColumnTable.columns(Facet.SPECTRUM).get(0).fallbacks().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void facetsDescribeTheirMembersAndPackedStructs() {
        assertFacet(Facet.SPECTRUM, "spectrum", "metadata", "spectrum", true, "index");
        assertFacet(Facet.SPECTRUM_SCAN, "spectrum", "scans", "scan", false, "source_index", "scan_index");
        assertFacet(Facet.SPECTRUM_PRECURSOR, "spectrum", "precursors", "precursor", false,
                "source_index", "precursor_index");
        assertFacet(Facet.SPECTRUM_SELECTED_ION, "spectrum", "selected_ions", "selected_ion", false,
                "source_index", "precursor_index", "selected_ion_index");
        assertFacet(Facet.CHROMATOGRAM, "chromatogram", "metadata", "chromatogram", true, "index");
        assertFacet(Facet.CHROMATOGRAM_PRECURSOR, "chromatogram", "precursors", "precursor", false,
                "source_index", "precursor_index");
        assertFacet(Facet.CHROMATOGRAM_SELECTED_ION, "chromatogram", "selected_ions", "selected_ion", false,
                "source_index", "precursor_index", "selected_ion_index");
        assertFacet(Facet.WAVELENGTH_SPECTRUM, "wavelength_spectrum", "metadata", "spectrum", true, "index");
        assertFacet(Facet.WAVELENGTH_SCAN, "wavelength_spectrum", "scans", "scan", false,
                "source_index", "scan_index");
    }

    @Test
    void facetsFindTheirMembersByRole() {
        MzPeakManifest hasUv = manifest(Fixtures.current("has_uv.mzpeak"));
        Map<Facet, String> names = new EnumMap<>(Facet.class);
        for (Facet f : Facet.values()) {
            names.put(f, f.entry(hasUv).map(Entry::name).orElse(null));
        }

        assertThat(names).isEqualTo(Map.of(
                Facet.SPECTRUM, "spectra_metadata.parquet",
                Facet.SPECTRUM_SCAN, "spectra_metadata_scans.parquet",
                Facet.SPECTRUM_PRECURSOR, "spectra_metadata_precursors.parquet",
                Facet.SPECTRUM_SELECTED_ION, "spectra_metadata_selected_ions.parquet",
                Facet.CHROMATOGRAM, "chromatograms_metadata.parquet",
                Facet.CHROMATOGRAM_PRECURSOR, "chromatograms_metadata_precursors.parquet",
                Facet.CHROMATOGRAM_SELECTED_ION, "chromatograms_metadata_selected_ions.parquet",
                Facet.WAVELENGTH_SPECTRUM, "wavelength_spectra_metadata.parquet",
                Facet.WAVELENGTH_SCAN, "wavelength_spectra_metadata_scans.parquet"));

        MzPeakManifest legacy = manifest(Fixtures.legacy("small.unpacked.mzpeak"));
        assertThat(Facet.SPECTRUM.entry(legacy)).map(Entry::name).contains("spectra_metadata.parquet");
        assertThat(Facet.SPECTRUM_SCAN.entry(legacy)).isEmpty();
        assertThat(Facet.CHROMATOGRAM_PRECURSOR.entry(legacy)).isEmpty();
        assertThatThrownBy(() -> Facet.SPECTRUM.entry(null)).isInstanceOf(NullPointerException.class);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static boolean sameIdentity(Column c, ColumnMapping m) {
        return c.path().equals(m.path()) && c.accession().equals(m.accession()) && c.name().equals(m.name())
                && Objects.equals(c.unit(), m.unit()) && c.termMarker() == Boolean.TRUE.equals(m.termMarker());
    }

    private static String identity(Column c) {
        return "(" + c.accession() + ", " + c.name() + ", " + c.unit() + ", marker " + c.termMarker() + ")";
    }

    private static String identity(ColumnMapping m) {
        return "(" + m.accession() + ", " + m.name() + ", " + m.unit() + ", marker " + m.termMarker() + ")";
    }

    private static void assertFacet(Facet f, String entityType, String dataKind, String packedStruct,
                                    boolean primary, String... keyColumns) {
        assertThat(f.entityType()).as("%s entity type", f).isEqualTo(entityType);
        assertThat(f.dataKind()).as("%s data kind", f).isEqualTo(dataKind);
        assertThat(f.packedStruct()).as("%s packed struct", f).isEqualTo(packedStruct);
        assertThat(f.primary()).as("%s primary", f).isEqualTo(primary);
        assertThat(f.keyColumns()).as("%s keys", f).containsExactly(keyColumns);
    }

    /** The facet a packed struct of a member of {@code entity} holds. */
    private static Facet packedFacet(String entity, String struct) {
        for (Facet f : Facet.values()) {
            if (f.entityType().equals(entity) && f.packedStruct().equals(struct)) {
                return f;
            }
        }
        throw new AssertionError("no facet for packed struct " + struct + " of " + entity);
    }

    /** Dotted paths of the primitive leaves under {@code group}, omitting list and element levels. */
    private static void collectLeaves(GroupType group, String prefix, List<String> out) {
        for (Type field : group.getFields()) {
            String path = prefix + field.getName();
            if (field.isPrimitive()) {
                out.add(path);
                continue;
            }
            GroupType g = field.asGroupType();
            if (g.getLogicalTypeAnnotation() instanceof LogicalTypeAnnotation.ListLogicalTypeAnnotation) {
                Type repeated = g.getType(0);
                Type element = !repeated.isPrimitive() && repeated.asGroupType().getFieldCount() == 1
                        ? repeated.asGroupType().getType(0) : repeated;
                if (element.isPrimitive()) {
                    out.add(path);
                } else {
                    collectLeaves(element.asGroupType(), path + ".", out);
                }
            } else {
                collectLeaves(g, path + ".", out);
            }
        }
    }

    private static MzPeakManifest manifest(Path dataset) {
        try (MzPeakSource src = MzPeakSource.open(dataset)) {
            return MzPeakManifest.fromSource(src);
        }
    }
}
