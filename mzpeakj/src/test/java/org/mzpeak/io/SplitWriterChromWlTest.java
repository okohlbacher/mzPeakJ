package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WR-6: the chromatogram and wavelength-spectrum metadata of the split layout written by {@link SplitMetadataWriter}
 * (design §11.8). chromatograms_metadata has one row per chromatogram with its type as a term marker and, since V-25,
 * empty auxiliary arrays, and chromatograms_metadata_precursors and chromatograms_metadata_selected_ions have the
 * spectrum facets' fields and no rows; wavelength_spectra_metadata has one row per wavelength spectrum, and
 * wavelength_spectra_metadata_scans, with the spectrum-scan schema since V-25, one scan per wavelength spectrum whose
 * start time is the spectrum's time. Each member is written only when the plan has
 * entities of its kind, and is read back by role, its layout detected from its schema.
 *
 * <p>Oracles: has_uv, the only fixture with wavelength spectra, in both sets (2 chromatograms, TIC and 'DAD1 A', and
 * 520 wavelength spectra), the manifest of current has_uv for the mapping rows, and constructed entities for the
 * absent values. The PACKED output stays pinned by the PACKED cases of {@link WriterSnapshotTest}.
 */
class SplitWriterChromWlTest {

    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HAS_UV = "has_uv.mzpeak";

    private static final String CHROMATOGRAMS = "chromatograms_metadata.parquet";
    private static final String CHROMATOGRAM_PRECURSORS = "chromatograms_metadata_precursors.parquet";
    private static final String CHROMATOGRAM_SELECTED_IONS = "chromatograms_metadata_selected_ions.parquet";
    private static final String WAVELENGTH = "wavelength_spectra_metadata.parquet";
    private static final String WAVELENGTH_SCANS = "wavelength_spectra_metadata_scans.parquet";
    private static final List<String> CHROMATOGRAM_MEMBERS =
            List.of(CHROMATOGRAMS, CHROMATOGRAM_PRECURSORS, CHROMATOGRAM_SELECTED_IONS);
    private static final List<String> WAVELENGTH_MEMBERS = List.of(WAVELENGTH, WAVELENGTH_SCANS);

    /** Fixtures read once per class; the tests never modify a source in place. */
    private static final Map<Path, Source> SOURCES = new ConcurrentHashMap<>();

    static Stream<Arguments> roundTrips() {
        return Stream.of(
                Arguments.of(Fixtures.legacy(HAS_UV), true),
                Arguments.of(Fixtures.current(HAS_UV), true),
                Arguments.of(Fixtures.current(HAS_UV), false));
    }

    /** Acceptance (a): the chromatograms and wavelength spectra of has_uv read back equal from the split members. */
    @ParameterizedTest(name = "{0} archive={1}")
    @MethodSource("roundTrips")
    void hasUvRoundTripsItsChromatogramsAndWavelengthSpectra(Path fixture, boolean archive, @TempDir Path tmp) {
        Source source = source(fixture);
        Path out = write(source, archive, tmp.resolve("out.mzpeak"));
        Source back = ForwardBackwardCorpusTest.readSource(out);

        assertThat(shape(out, EntityType.CHROMATOGRAM, "chromatogram")).isEqualTo(MetadataShape.SPLIT);
        assertThat(shape(out, EntityType.WAVELENGTH_SPECTRUM, "spectrum")).isEqualTo(MetadataShape.SPLIT);

        assertThat(source.chromatograms).extracting(Chromatogram::typeCurie)
                .containsExactly("MS:1000235", "MS:1000812");
        assertThat(source.chromatograms).extracting(Chromatogram::size).containsExactly(212, 526);
        assertThat(back.chromatograms).hasSameSizeAs(source.chromatograms);
        for (int i = 0; i < source.chromatograms.size(); i++) {
            Chromatogram a = source.chromatograms.get(i);
            Chromatogram b = back.chromatograms.get(i);
            assertThat(b.index()).as("chromatogram %d index", i).isEqualTo(a.index());
            assertThat(b.id()).as("chromatogram %d id", i).isEqualTo(a.id());
            assertThat(b.typeCurie()).as("chromatogram %d type", i).isEqualTo(a.typeCurie());
            assertThat(b.time()).as("chromatogram %s time", a.id()).isEqualTo(a.time());
            assertThat(b.intensity()).as("chromatogram %s intensity", a.id()).isEqualTo(a.intensity());
        }

        assertThat(source.wavelengthSpectra).hasSize(520);
        assertThat(back.wavelengthSpectra).hasSameSizeAs(source.wavelengthSpectra);
        for (int i = 0; i < source.wavelengthSpectra.size(); i++) {
            assertWavelengthSpectrumEqual(source.wavelengthSpectra.get(i), back.wavelengthSpectra.get(i));
        }
    }

    @Test
    void theMembersHaveTheSplitSchemasAndOneRowPerEntity(@TempDir Path tmp) {
        Source source = source(Fixtures.current(HAS_UV));
        Path out = write(source, false, tmp.resolve("out.mzpeak"));

        assertThat(columns(schema(out, CHROMATOGRAMS))).containsExactly(
                "index required INT64 uint64", "id optional BINARY string", "scan_polarity optional INT32 -",
                "chromatogram_type optional BINARY string", "number_of_data_points optional INT64 uint64",
                "parameters optional group list", "auxiliary_arrays optional group list",
                "number_of_auxiliary_arrays optional INT32 uint32");
        // The chromatogram facets have the fields of the spectrum facets.
        assertThat(schema(out, CHROMATOGRAM_PRECURSORS).getFields())
                .isEqualTo(schema(out, "spectra_metadata_precursors.parquet").getFields());
        assertThat(schema(out, CHROMATOGRAM_SELECTED_IONS).getFields())
                .isEqualTo(schema(out, "spectra_metadata_selected_ions.parquet").getFields());
        assertThat(columns(schema(out, WAVELENGTH))).containsExactly(
                "index required INT64 uint64", "id optional BINARY string", "time optional DOUBLE -",
                "spectrum_type optional BINARY string", "spectrum_representation optional BINARY string",
                "lowest_observed_wavelength optional DOUBLE -", "highest_observed_wavelength optional DOUBLE -",
                "number_of_data_points optional INT64 uint64",
                "lambda_max optional DOUBLE -", "base_peak_intensity optional DOUBLE -",
                "total_ion_current optional DOUBLE -", "parameters optional group list",
                "auxiliary_arrays optional group list", "number_of_auxiliary_arrays optional INT32 uint32");
        // V-25: the wavelength scans have the spectrum-scan schema, as in the reference.
        assertThat(schema(out, WAVELENGTH_SCANS).getFields())
                .isEqualTo(schema(out, "spectra_metadata_scans.parquet").getFields());
        assertThat(columns(schema(out, WAVELENGTH_SCANS))).containsExactly(
                "source_index required INT64 uint64", "scan_index required INT64 uint64",
                "scan_start_time optional DOUBLE -", "preset_scan_configuration optional INT32 -",
                "filter_string optional BINARY string", "ion_injection_time optional DOUBLE -",
                "ion_mobility_value optional DOUBLE -", "ion_mobility_type optional BINARY string",
                "instrument_configuration_id optional INT32 -", "spectrum_reference optional BINARY string",
                "parameters optional group list", "scan_windows optional group list");

        List<Group> chromatogramRows = rows(out.resolve(CHROMATOGRAMS));
        assertThat(chromatogramRows).hasSameSizeAs(source.chromatograms);
        for (int i = 0; i < chromatogramRows.size(); i++) {
            Group row = chromatogramRows.get(i);
            Chromatogram c = source.chromatograms.get(i);
            assertThat(row.getLong("index", 0)).isEqualTo(c.index());
            assertThat(row.getString("id", 0)).isEqualTo(c.id());
            assertThat(ParquetGroups.has(row, "scan_polarity")).as("scan_polarity of %s", c.id()).isFalse();
            assertThat(row.getString("chromatogram_type", 0)).isEqualTo(c.typeCurie());
            assertThat(row.getLong("number_of_data_points", 0)).isEqualTo(c.size());
            assertThat(ParquetGroups.has(row, "parameters")).as("parameters of %s is not null", c.id()).isTrue();
            assertThat(ParquetGroups.groupList(row, "parameters")).isEmpty();
            assertThat(ParquetGroups.has(row, "auxiliary_arrays")).as("auxiliary_arrays of %s", c.id()).isTrue();
            assertThat(ParquetGroups.groupList(row, "auxiliary_arrays")).isEmpty();
            assertThat(row.getInteger("number_of_auxiliary_arrays", 0)).isZero();
        }
        assertThat(rows(out.resolve(CHROMATOGRAM_PRECURSORS))).isEmpty();
        assertThat(rows(out.resolve(CHROMATOGRAM_SELECTED_IONS))).isEmpty();

        List<Group> spectrumRows = rows(out.resolve(WAVELENGTH));
        List<Group> scanRows = rows(out.resolve(WAVELENGTH_SCANS));
        assertThat(spectrumRows).hasSize(520);
        assertThat(scanRows).hasSize(520);
        for (int i = 0; i < spectrumRows.size(); i++) {
            WavelengthSpectrum w = source.wavelengthSpectra.get(i);
            Group row = spectrumRows.get(i);
            assertThat(row.getLong("index", 0)).isEqualTo(w.index());
            assertThat(row.getString("id", 0)).isEqualTo(w.id());
            assertThat(row.getDouble("time", 0)).isEqualTo(w.time());
            assertThat(row.getLong("number_of_data_points", 0)).isEqualTo(w.size());
            assertThat(row.getDouble("lambda_max", 0)).isEqualTo(w.lambdaMax());
            // MZ-4: the four list Params go to the list, the type and wavelength range also to their columns, as
            // in has_uv; the representation, base peak intensity and TIC, which the reader appended from has_uv's
            // columns, go to the same columns only.
            assertThat(w.parameters()).as("parameters of %s", w.id()).hasSize(7);
            assertThat(ParquetGroups.readParams(row, "parameters")).as("parameters of %s", w.id())
                    .isEqualTo(w.parameters().subList(0, 4));
            assertThat(row.getString("spectrum_type", 0)).isEqualTo(w.parameters().get(0).accession())
                    .isEqualTo("MS:1000804");
            assertThat(row.getDouble("lowest_observed_wavelength", 0)).isEqualTo(w.parameters().get(1).value());
            assertThat(row.getDouble("highest_observed_wavelength", 0)).isEqualTo(w.parameters().get(2).value());
            assertThat(row.getString("spectrum_representation", 0)).isEqualTo(w.parameters().get(4).accession());
            assertThat(row.getDouble("base_peak_intensity", 0)).isEqualTo(w.parameters().get(5).value());
            assertThat(row.getDouble("total_ion_current", 0)).isEqualTo(w.parameters().get(6).value());

            Group scan = scanRows.get(i);
            assertThat(scan.getLong("source_index", 0)).isEqualTo(w.index());
            assertThat(scan.getLong("scan_index", 0)).isEqualTo(i);
            assertThat(scan.getDouble("scan_start_time", 0)).isEqualTo(w.time());
            assertThat(ParquetGroups.has(scan, "parameters")).as("parameters of scan %d is not null", i).isTrue();
            assertThat(ParquetGroups.groupList(scan, "parameters")).isEmpty();
            assertThat(ParquetGroups.has(scan, "scan_windows")).as("scan_windows of scan %d is not null", i).isTrue();
            assertThat(ParquetGroups.groupList(scan, "scan_windows")).isEmpty();
        }
        WavelengthSpectrum w0 = source.wavelengthSpectra.get(0);
        assertThat(w0.id()).isEqualTo("merged=212 row=0");
        assertThat(w0.time()).isEqualTo(0.001833333333);
        assertThat(w0.lambdaMax()).isEqualTo(374.0);
        assertThat(w0.size()).isEqualTo(96);
    }

    /**
     * The chromatogram members carry {@code chromatogram_count}, the wavelength members the file metadata of
     * spectra_metadata and {@code wavelength_spectrum_count}, as current has_uv's do; chromatograms_metadata and
     * wavelength_spectra_metadata also their Arrow schema hint.
     */
    @Test
    void theFootersCarryTheEntityCounts(@TempDir Path tmp) {
        Source source = source(Fixtures.current(HAS_UV));
        Path out = write(source, true, tmp.resolve("out.mzpeak"));

        try (MzPeakSource dataset = MzPeakSource.open(out)) {
            Map<String, String> fileMetadata = withoutWriterModel(dataset.inputFile("spectra_metadata.parquet"));
            assertThat(fileMetadata.remove("spectrum_count")).isEqualTo("212");
            // the Arrow schema hint of each member is its own (SplitWriterRustReaderTest)
            assertThat(fileMetadata.remove(ArrowSchemaHint.KEY))
                    .isEqualTo(ArrowSchemaHint.encode(schema(out, "spectra_metadata.parquet")));
            assertThat(fileMetadata).containsKey("run");
            for (String member : CHROMATOGRAM_MEMBERS) {
                Map<String, String> footer = withoutWriterModel(dataset.inputFile(member));
                if (member.equals(CHROMATOGRAMS)) {
                    // V-25: the member with auxiliary_arrays carries its Arrow schema hint (R-8)
                    assertThat(footer.remove(ArrowSchemaHint.KEY)).isEqualTo(ArrowSchemaHint.encode(schema(out,
                            CHROMATOGRAMS)));
                }
                assertThat(footer).as("footer of %s", member).containsExactly(Map.entry("chromatogram_count", "2"));
            }
            for (String member : WAVELENGTH_MEMBERS) {
                Map<String, String> footer = withoutWriterModel(dataset.inputFile(member));
                assertThat(footer.remove("wavelength_spectrum_count")).as("count of %s", member).isEqualTo("520");
                if (member.equals(WAVELENGTH)) {
                    assertThat(footer.remove(ArrowSchemaHint.KEY)).isEqualTo(ArrowSchemaHint.encode(schema(out,
                            WAVELENGTH)));
                }
                assertThat(footer).as("file metadata of %s", member).isEqualTo(fileMetadata);
            }
        }
    }

    /**
     * Acceptance (b): the chromatogram members map their columns exactly as current has_uv's do; the wavelength
     * members map the columns they write, each row as current has_uv maps it.
     */
    @Test
    void theMappingRowsAreThoseOfCurrentHasUv(@TempDir Path tmp) throws IOException {
        Path fixture = Fixtures.current(HAS_UV);
        Path out = write(source(fixture), true, tmp.resolve("out.mzpeak"));
        JsonNode current = manifest(fixture);
        JsonNode written = manifest(out);

        for (String member : CHROMATOGRAM_MEMBERS) {
            JsonNode rows = entry(written, member).path("column_mapping");
            assertThat(rows).as("column_mapping of %s", member).isNotEmpty()
                    .isEqualTo(entry(current, member).path("column_mapping"));
        }
        assertThat(mappedPaths(entry(written, CHROMATOGRAMS))).containsExactly("scan_polarity", "chromatogram_type",
                "number_of_data_points");
        assertThat(mappedPaths(entry(written, CHROMATOGRAM_PRECURSORS))).hasSize(5);
        assertThat(mappedPaths(entry(written, CHROMATOGRAM_SELECTED_IONS))).hasSize(3);

        assertThat(mappedPaths(entry(written, WAVELENGTH))).containsExactly("spectrum_type",
                "spectrum_representation", "lowest_observed_wavelength", "highest_observed_wavelength",
                "number_of_data_points", "lambda_max", "base_peak_intensity", "total_ion_current");
        assertThat(mappedPaths(entry(written, WAVELENGTH_SCANS))).containsExactly("scan_start_time",
                "preset_scan_configuration", "filter_string", "ion_injection_time",
                "scan_windows.scan_window_lower_limit", "scan_windows.scan_window_upper_limit");
        for (String member : WAVELENGTH_MEMBERS) {
            List<JsonNode> currentRows = list(entry(current, member).path("column_mapping"));
            assertThat(list(entry(written, member).path("column_mapping"))).as("column_mapping of %s", member)
                    .isSubsetOf(currentRows);
        }

        List<String> roles = new ArrayList<>();
        for (JsonNode e : written.path("files")) {
            String name = e.path("name").asText();
            if (CHROMATOGRAM_MEMBERS.contains(name) || WAVELENGTH_MEMBERS.contains(name)) {
                roles.add(name + "|" + e.path("entity_type").asText() + "|" + e.path("data_kind").asText());
            }
        }
        assertThat(roles).containsExactly(
                "chromatograms_metadata.parquet|chromatogram|metadata",
                "chromatograms_metadata_precursors.parquet|chromatogram|precursors",
                "chromatograms_metadata_selected_ions.parquet|chromatogram|selected_ions",
                "wavelength_spectra_metadata.parquet|wavelength_spectrum|metadata",
                "wavelength_spectra_metadata_scans.parquet|wavelength_spectrum|scans");
    }

    /** No chromatogram member, the empty facets included, without chromatograms; no wavelength member without UV. */
    @Test
    void theMembersAreWrittenOnlyForEntitiesThePlanHas(@TempDir Path tmp) throws IOException {
        Source source = source(Fixtures.current(HAS_UV));
        Path noChromatograms = tmp.resolve("no-chromatograms.mzpeak");
        MzPeakWriter.writeDirectory(noChromatograms, source.spectra, List.of(), source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, SPLIT);
        assertThat(memberNames(noChromatograms)).doesNotContainAnyElementsOf(CHROMATOGRAM_MEMBERS)
                .containsAll(WAVELENGTH_MEMBERS);

        Path noWavelength = tmp.resolve("no-wavelength.mzpeak");
        MzPeakWriter.writeDirectory(noWavelength, source.spectra, source.chromatograms, List.of(),
                source.scanSettings, source.fileMetadata, SPLIT);
        assertThat(memberNames(noWavelength)).containsAll(CHROMATOGRAM_MEMBERS)
                .doesNotContainAnyElementsOf(WAVELENGTH_MEMBERS);
        try (MzPeakReader reader = MzPeakReader.open(noWavelength)) {
            assertThat(reader.chromatograms()).hasSize(2);
            assertThat(reader.wavelengthSpectra()).isEmpty();
        }
    }

    /**
     * Absent values stay absent: a chromatogram without id or type, a wavelength spectrum without id, time or lambda
     * max, and a NaN lambda max read back as given; a scan of a spectrum without time has no start time.
     */
    @Test
    void absentValuesRoundTrip(@TempDir Path tmp) {
        Source source = source(Fixtures.current(HAS_UV));
        List<Chromatogram> chromatograms = List.of(
                new Chromatogram(0, null, null, new double[] {1.0, 2.0}, new double[] {10.0, 20.0}),
                new Chromatogram(1, "TIC", "MS:1000235", new double[0], new double[0]));
        List<WavelengthSpectrum> wavelengthSpectra = List.of(
                new WavelengthSpectrum(0, null, Double.NaN, null, new double[] {200.0, 210.0},
                        new double[] {0.5, -0.25}),
                new WavelengthSpectrum(1, "w1", 0.5, Double.NaN, new double[] {300.0}, new double[] {1.0}));
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, chromatograms, wavelengthSpectra, source.scanSettings,
                source.fileMetadata, SPLIT);

        Group first = rows(out.resolve(CHROMATOGRAMS)).get(0);
        assertThat(ParquetGroups.has(first, "id")).isFalse();
        assertThat(ParquetGroups.has(first, "chromatogram_type")).isFalse();
        List<Group> scans = rows(out.resolve(WAVELENGTH_SCANS));
        assertThat(ParquetGroups.has(scans.get(0), "scan_start_time")).isFalse();
        assertThat(scans.get(1).getDouble("scan_start_time", 0)).isEqualTo(0.5);

        try (MzPeakReader reader = MzPeakReader.open(out)) {
            List<Chromatogram> back = reader.chromatograms();
            assertThat(back).hasSize(2);
            for (int i = 0; i < back.size(); i++) {
                Chromatogram a = chromatograms.get(i);
                Chromatogram b = back.get(i);
                assertThat(b.index()).isEqualTo(a.index());
                assertThat(b.id()).isEqualTo(a.id());
                assertThat(b.typeCurie()).isEqualTo(a.typeCurie());
                assertThat(b.time()).isEqualTo(a.time());
                assertThat(b.intensity()).isEqualTo(a.intensity());
            }
            List<WavelengthSpectrum> wavelengthBack = reader.wavelengthSpectra();
            assertThat(wavelengthBack).hasSize(2);
            for (int i = 0; i < wavelengthBack.size(); i++) {
                assertWavelengthSpectrumEqual(wavelengthSpectra.get(i), wavelengthBack.get(i));
            }
        }
    }

    /** Acceptance (c) from the reading side: PACKED still writes the packed members, without the split facets. */
    @Test
    void thePackedLayoutKeepsItsChromatogramAndWavelengthMembers(@TempDir Path tmp) {
        Source source = source(Fixtures.legacy(HAS_UV));
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, Options.defaults().withMetadataLayout(MetadataLayout.PACKED));

        assertThat(shape(out, EntityType.CHROMATOGRAM, "chromatogram")).isEqualTo(MetadataShape.PACKED);
        assertThat(shape(out, EntityType.WAVELENGTH_SPECTRUM, "spectrum")).isEqualTo(MetadataShape.PACKED);
        assertThat(memberNames(out)).contains(CHROMATOGRAMS, WAVELENGTH)
                .doesNotContain(CHROMATOGRAM_PRECURSORS, CHROMATOGRAM_SELECTED_IONS, WAVELENGTH_SCANS);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Every component equal; time and lambda max boxed, so that NaN equals NaN. */
    private static void assertWavelengthSpectrumEqual(WavelengthSpectrum a, WavelengthSpectrum b) {
        assertThat(b.index()).as("wavelength spectrum %d index", a.index()).isEqualTo(a.index());
        assertThat(b.id()).as("wavelength spectrum %d id", a.index()).isEqualTo(a.id());
        assertThat((Object) b.time()).as("wavelength spectrum %d time", a.index()).isEqualTo(a.time());
        assertThat((Object) b.lambdaMax()).as("wavelength spectrum %d lambda max", a.index())
                .isEqualTo(a.lambdaMax());
        assertThat(b.wavelength()).as("wavelength spectrum %d wavelength", a.index()).isEqualTo(a.wavelength());
        assertThat(b.intensity()).as("wavelength spectrum %d intensity", a.index()).isEqualTo(a.intensity());
        assertThat(b.parameters()).as("wavelength spectrum %d parameters", a.index()).isEqualTo(a.parameters());
    }

    private static Source source(Path fixture) {
        return SOURCES.computeIfAbsent(fixture, ForwardBackwardCorpusTest::readSource);
    }

    private static Path write(Source s, boolean archive, Path out) {
        if (archive) {
            MzPeakWriter.writeArchive(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                    s.fileMetadata, SPLIT);
        } else {
            MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                    s.fileMetadata, SPLIT);
        }
        return out;
    }

    /** The shape of the metadata member of {@code entity} in a written dataset, found by role. */
    private static MetadataShape shape(Path out, EntityType entity, String packedStruct) {
        try (MzPeakSource dataset = MzPeakSource.open(out)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(dataset);
            String member = manifest.find(entity, DataKind.METADATA).orElseThrow().name();
            return MetadataShape.detect(ParquetFooter.read(dataset.inputFile(member)), packedStruct, "index", member);
        }
    }

    /** The member's footer key-values without the {@code writer.model.name} parquet-java adds to every file. */
    private static Map<String, String> withoutWriterModel(org.apache.parquet.io.InputFile file) {
        Map<String, String> footer = new LinkedHashMap<>(ParquetGroups.footerKeyValue(file));
        footer.remove("writer.model.name");
        return footer;
    }

    private static List<String> memberNames(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(f -> f.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static JsonNode manifest(Path dataset) throws IOException {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            return MAPPER.readTree(source.readManifestBytes());
        }
    }

    /** The manifest entry named {@code name}. */
    private static JsonNode entry(JsonNode manifest, String name) {
        for (JsonNode e : manifest.path("files")) {
            if (name.equals(e.path("name").asText())) {
                return e;
            }
        }
        throw new AssertionError("no manifest entry " + name);
    }

    private static List<String> mappedPaths(JsonNode entry) {
        List<String> out = new ArrayList<>();
        entry.path("column_mapping").forEach(row -> out.add(row.path("path").asText()));
        return out;
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    private static MessageType schema(Path out, String member) {
        try (MzPeakSource dataset = MzPeakSource.open(out)) {
            return ParquetFooter.read(dataset.inputFile(member)).schema();
        }
    }

    /** Each top-level field as {@code name repetition type annotation}, a group as {@code name repetition group}. */
    private static List<String> columns(MessageType schema) {
        List<String> out = new ArrayList<>();
        for (Type t : schema.getFields()) {
            String repetition = t.getRepetition().name().toLowerCase(Locale.ROOT);
            if (t.isPrimitive()) {
                PrimitiveType p = t.asPrimitiveType();
                LogicalTypeAnnotation a = p.getLogicalTypeAnnotation();
                String annotation = a == null ? "-"
                        : a instanceof LogicalTypeAnnotation.IntLogicalTypeAnnotation i
                        ? (i.isSigned() ? "int" : "uint") + i.getBitWidth()
                        : a.toString().toLowerCase(Locale.ROOT);
                out.add(t.getName() + " " + repetition + " " + p.getPrimitiveTypeName() + " " + annotation);
            } else {
                LogicalTypeAnnotation a = t.getLogicalTypeAnnotation();
                out.add(t.getName() + " " + repetition + " group"
                        + (a == null ? "" : " " + a.toString().toLowerCase(Locale.ROOT)));
            }
        }
        return out;
    }

    private static List<Group> rows(Path file) {
        List<Group> out = new ArrayList<>();
        ParquetGroups.forEach(file, out::add);
        return out;
    }
}
