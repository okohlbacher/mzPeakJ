package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Param;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.FileDescription;
import org.mzpeak.model.meta.FileMetadata.MsRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the mzPeak spec rules the writer must follow, which the lenient reader cannot catch on its own: every
 * index, count and source_index column is written as UINT64 ({@code intType(64, false)}), the manifest declares
 * version {@code 0.9.0} and the controlled vocabularies the dataset uses (MS and UO for these fixtures), and the
 * footer metadata always gives the run a resolvable default data processing and default source file id.
 *
 * <p>The expected UINT64 columns are spelled out per file and layout: the default overloads write the split metadata
 * tables (WR-7), and options naming the packed layout write its structs. Signed INT64 leaves such as the parameter
 * {@code value.integer} union member are deliberately not covered by the naming rule.
 */
class WriterSpecConformanceTest {

    private static final Path UNPACKED = Fixtures.legacy("small.unpacked.mzpeak");
    private static final Path HAS_UV = Fixtures.legacy("has_uv.mzpeak");

    private static final LogicalTypeAnnotation UINT64 = LogicalTypeAnnotation.intType(64, false);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** The pin-era packed metadata layout, selected explicitly. */
    private static final MzPeakWriter.Options PACKED =
            MzPeakWriter.Options.defaults().withMetadataLayout(MzPeakWriter.MetadataLayout.PACKED);

    /** UINT64 columns of the split spectrum metadata tables, identical for every signal encoding. */
    private static final List<String> SPLIT_SPECTRUM_METADATA_UINT64 = List.of(
            "spectra_metadata.parquet: index",
            "spectra_metadata.parquet: number_of_data_points",
            "spectra_metadata.parquet: number_of_peaks",
            "spectra_metadata_scans.parquet: source_index",
            "spectra_metadata_scans.parquet: scan_index",
            "spectra_metadata_precursors.parquet: source_index",
            "spectra_metadata_precursors.parquet: precursor_index",
            "spectra_metadata_selected_ions.parquet: source_index",
            "spectra_metadata_selected_ions.parquet: precursor_index",
            "spectra_metadata_selected_ions.parquet: selected_ion_index");

    /** UINT64 columns of the packed spectrum metadata table, identical for every signal encoding. */
    private static final List<String> PACKED_SPECTRUM_METADATA_UINT64 = List.of(
            "spectra_metadata.parquet: spectrum.index",
            "spectra_metadata.parquet: spectrum.MS_1003060_number_of_data_points",
            "spectra_metadata.parquet: spectrum.MS_1003059_number_of_peaks",
            "spectra_metadata.parquet: scan.source_index",
            "spectra_metadata.parquet: scan.scan_index",
            "spectra_metadata.parquet: precursor.source_index",
            "spectra_metadata.parquet: precursor.precursor_index",
            "spectra_metadata.parquet: selected_ion.source_index");

    /** UINT64 columns of the point-layout spectrum data tables (mz() arrays and peaks() lists). */
    private static final List<String> POINT_SPECTRUM_DATA_UINT64 = List.of(
            "spectra_data.parquet: point.spectrum_index",
            "spectra_peaks.parquet: point.spectrum_index");

    /** UINT64 columns of the split chromatogram tables (the data always written in point layout). */
    private static final List<String> SPLIT_CHROMATOGRAM_UINT64 = List.of(
            "chromatograms_metadata.parquet: index",
            "chromatograms_metadata.parquet: number_of_data_points",
            "chromatograms_metadata_precursors.parquet: source_index",
            "chromatograms_metadata_precursors.parquet: precursor_index",
            "chromatograms_metadata_selected_ions.parquet: source_index",
            "chromatograms_metadata_selected_ions.parquet: precursor_index",
            "chromatograms_metadata_selected_ions.parquet: selected_ion_index",
            "chromatograms_data.parquet: point.chromatogram_index");

    /** UINT64 columns of the packed chromatogram tables (the data always written in point layout). */
    private static final List<String> PACKED_CHROMATOGRAM_UINT64 = List.of(
            "chromatograms_metadata.parquet: chromatogram.index",
            "chromatograms_metadata.parquet: chromatogram.MS_1003060_number_of_data_points",
            "chromatograms_data.parquet: point.chromatogram_index");

    /** UINT64 columns of the split wavelength-spectrum tables. */
    private static final List<String> SPLIT_WAVELENGTH_UINT64 = List.of(
            "wavelength_spectra_metadata.parquet: index",
            "wavelength_spectra_metadata.parquet: number_of_data_points",
            "wavelength_spectra_metadata_scans.parquet: source_index",
            "wavelength_spectra_metadata_scans.parquet: scan_index",
            "wavelength_spectra_data.parquet: point.wavelength_spectrum_index");

    /** UINT64 columns of the packed wavelength-spectrum tables. */
    private static final List<String> PACKED_WAVELENGTH_UINT64 = List.of(
            "wavelength_spectra_metadata.parquet: spectrum.index",
            "wavelength_spectra_metadata.parquet: spectrum.MS_1003060_number_of_data_points",
            "wavelength_spectra_data.parquet: point.wavelength_spectrum_index");

    // ---- the default overloads: the split metadata layout ----------------------------------------------------

    @Test
    void pointLayoutDeclaresUint64IndexAndCountColumns(@TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(UNPACKED);
        Path dir = tmp.resolve("point.mzpeak");
        MzPeakWriter.writeDirectory(dir, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata);

        assertUint64Columns(dir, concat(SPLIT_SPECTRUM_METADATA_UINT64, POINT_SPECTRUM_DATA_UINT64,
                SPLIT_CHROMATOGRAM_UINT64));
        assertManifestVersionAndCvList(dir);
    }

    @Test
    void numpressLayoutDeclaresUint64IndexAndCountColumns(@TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(UNPACKED);
        Path dir = tmp.resolve("numpress.mzpeak");
        MzPeakWriter.writeDirectoryNumpress(dir, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, MzPeakWriter.NumpressIntensityEncoding.SLOF);

        assertUint64Columns(dir, concat(SPLIT_SPECTRUM_METADATA_UINT64,
                List.of("spectra_data.parquet: chunk.spectrum_index",
                        "spectra_peaks.parquet: chunk.spectrum_index"),
                SPLIT_CHROMATOGRAM_UINT64));
        assertManifestVersionAndCvList(dir);
    }

    @Test
    void wavelengthTablesDeclareUint64IndexAndCountColumns(@TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(HAS_UV);
        assertThat(s.wavelengthSpectra).as("has_uv wavelength spectra").isNotEmpty();
        Path dir = tmp.resolve("has_uv.mzpeak");
        MzPeakWriter.writeDirectory(dir, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata);

        // has_uv's spectra are all CENTROID peak copies, so WR-3 writes them to spectra_peaks only.
        assertUint64Columns(dir, concat(SPLIT_SPECTRUM_METADATA_UINT64,
                List.of("spectra_peaks.parquet: point.spectrum_index"), SPLIT_CHROMATOGRAM_UINT64,
                SPLIT_WAVELENGTH_UINT64));
        assertManifestVersionAndCvList(dir);
    }

    // ---- the packed metadata layout, selected explicitly ---------------------------------------------------

    @Test
    void pointLayoutWithPackedMetadataDeclaresUint64IndexAndCountColumns(@TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(UNPACKED);
        Path dir = tmp.resolve("point.mzpeak");
        MzPeakWriter.writeDirectory(dir, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, PACKED);

        assertUint64Columns(dir, concat(PACKED_SPECTRUM_METADATA_UINT64, POINT_SPECTRUM_DATA_UINT64,
                PACKED_CHROMATOGRAM_UINT64));
        assertManifestVersionAndCvList(dir);
    }

    @Test
    void numpressLayoutWithPackedMetadataDeclaresUint64IndexAndCountColumns(@TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(UNPACKED);
        Path dir = tmp.resolve("numpress.mzpeak");
        MzPeakWriter.writeDirectory(dir, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, PACKED.withSignalEncoding(MzPeakWriter.SignalEncoding.NUMPRESS));

        assertUint64Columns(dir, concat(PACKED_SPECTRUM_METADATA_UINT64,
                List.of("spectra_data.parquet: chunk.spectrum_index",
                        "spectra_peaks.parquet: chunk.spectrum_index"),
                PACKED_CHROMATOGRAM_UINT64));
        assertManifestVersionAndCvList(dir);
    }

    @Test
    void wavelengthTablesWithPackedMetadataDeclareUint64IndexAndCountColumns(@TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(HAS_UV);
        assertThat(s.wavelengthSpectra).as("has_uv wavelength spectra").isNotEmpty();
        Path dir = tmp.resolve("has_uv.mzpeak");
        MzPeakWriter.writeDirectory(dir, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, PACKED);

        assertUint64Columns(dir, concat(PACKED_SPECTRUM_METADATA_UINT64,
                List.of("spectra_peaks.parquet: point.spectrum_index"), PACKED_CHROMATOGRAM_UINT64,
                PACKED_WAVELENGTH_UINT64));
        assertManifestVersionAndCvList(dir);
    }

    /**
     * The packed layout also writes a scan's ion mobility into its typed columns: current diaPASEF.ref, written with
     * options naming the layout, has {@code scan.ion_mobility_value} equal to the scan's value and
     * {@code scan.ion_mobility_type} MS:1002815 on each of the 8 spectra whose scan has one, and neither on the other.
     * A scan whose only ion-mobility Param is the array term MS:1002893 has no scan-level value, so it gets no typed
     * ion-mobility cell; the split layout keeps ion mobility in the {@code parameters} list and has no such column.
     */
    @Test
    void thePackedLayoutWritesTypedIonMobilityForAScanValueOnly(@TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(Fixtures.current("diaPASEF.ref.mzpeak"));
        Path dir = tmp.resolve("diaPASEF.mzpeak");
        MzPeakWriter.writeDirectory(dir, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, PACKED);

        Map<Long, Group> scans = packedScans(dir);
        int withValue = 0;
        for (Spectrum spectrum : s.spectra) {
            ScanEvent scan = spectrum.description().scans().get(0);
            Group row = scans.get(spectrum.index());
            if (scan.ionMobilityValue().isPresent()) {
                withValue++;
                assertThat(ParquetGroups.optDouble(row, "ion_mobility_value")).as("spectrum %d", spectrum.index())
                        .isEqualTo(scan.ionMobilityValue().getAsDouble());
                assertThat(ParquetGroups.optString(row, "ion_mobility_type")).as("spectrum %d", spectrum.index())
                        .isEqualTo("MS:1002815");
            } else {
                assertThat(ParquetGroups.has(row, "ion_mobility_value")).as("spectrum %d", spectrum.index()).isFalse();
                assertThat(ParquetGroups.has(row, "ion_mobility_type")).as("spectrum %d", spectrum.index()).isFalse();
            }
        }
        assertThat(withValue).as("diaPASEF.ref spectra with a scan-level ion mobility").isEqualTo(8);

        Spectrum first = s.spectra.get(1);
        SpectrumDescription d = first.description();
        ScanEvent arrayOnly = new ScanEvent(d.scans().get(0).startTime(), null, null, null, null, null, List.of(),
                List.of(new Param("ion mobility array", "MS:1002893", null, null)));
        Spectrum constructed = new Spectrum(new SpectrumDescription(0, d.id(), d.msLevel(), d.retentionTime(),
                d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), List.of(arrayOnly),
                List.of(), d.parameters()), first.mz(), first.intensity(), first.peaks());
        assertThat(arrayOnly.ionMobilityValue()).isEmpty();
        Path packed = tmp.resolve("array-term.mzpeak");
        MzPeakWriter.writeDirectory(packed, List.of(constructed), List.of(), List.of(), List.of(), FileMetadata.EMPTY,
                PACKED);
        Group row = packedScans(packed).get(0L);
        assertThat(ParquetGroups.has(row, "ion_mobility_value")).as("ion_mobility_value of an array term").isFalse();
        assertThat(ParquetGroups.has(row, "ion_mobility_type")).as("ion_mobility_type of an array term").isFalse();
        assertThat(ParquetGroups.readParams(row, "parameters")).containsExactly(arrayOnly.parameters().get(0));

        Path split = tmp.resolve("array-term-split.mzpeak");
        MzPeakWriter.writeDirectory(split, List.of(constructed), List.of(), List.of(), List.of(), FileMetadata.EMPTY,
                MzPeakWriter.Options.defaults());
        try (ParquetFileReader reader = ParquetFileReader.open(
                new LocalInputFile(split.resolve("spectra_metadata_scans.parquet")), ParquetGroups.readOptions())) {
            assertThat(reader.getFooter().getFileMetaData().getSchema().getFields())
                    .noneMatch(f -> f.getName().contains("ion_mobility"));
        }
    }

    /** The {@code scan} struct of each spectra_metadata row of a packed dataset, by its {@code source_index}. */
    private static Map<Long, Group> packedScans(Path dir) {
        Map<Long, Group> out = new HashMap<>();
        ParquetGroups.forEach(dir.resolve("spectra_metadata.parquet"), row -> {
            Group scan = ParquetGroups.optGroup(row, "scan");
            out.put(ParquetGroups.optLong(scan, "source_index"), scan);
        });
        return out;
    }

    // ---- footer metadata fallbacks (FooterMetadataWriter.serialize) ----------------------------------

    @Test
    void missingDefaultDataProcessingIdFallsBackToFirstMethod() throws IOException {
        FileMetadata fixture = fixtureMetadata();
        assertThat(fixture.dataProcessingMethods()).as("fixture data processing methods").isNotEmpty();
        FileMetadata meta = withRun(fixture, runWith(fixture.run(), null, fixture.run().defaultSourceFileId()));

        JsonNode run = json(FooterMetadataWriter.serialize(meta), "run");

        assertThat(run.path("default_data_processing_id").asText())
                .isEqualTo(fixture.dataProcessingMethods().get(0).id());
    }

    @Test
    void missingDefaultDataProcessingIdWithoutMethodsIsSynthesizedAndDeclared() throws IOException {
        FileMetadata fixture = fixtureMetadata();
        FileMetadata meta = new FileMetadata(fixture.fileDescription(), fixture.instrumentConfigurations(),
                List.of(), fixture.samples(), fixture.software(),
                runWith(fixture.run(), null, fixture.run().defaultSourceFileId()));

        Map<String, String> kv = FooterMetadataWriter.serialize(meta);

        assertThat(json(kv, "run").path("default_data_processing_id").asText()).isEqualTo("mzpeakj_export");
        assertThat(ids(json(kv, "data_processing_method_list"))).containsExactly("mzpeakj_export");
    }

    @Test
    void missingDefaultSourceFileIdFallsBackToFirstSourceFile() throws IOException {
        FileMetadata fixture = fixtureMetadata();
        assertThat(fixture.fileDescription()).as("fixture file description").isNotNull();
        assertThat(fixture.fileDescription().sourceFiles()).as("fixture source files").isNotEmpty();
        // A second source file, so that falling back to the last one instead of the first would fail.
        List<FileMetadata.SourceFile> sources = new ArrayList<>(fixture.fileDescription().sourceFiles());
        sources.add(new FileMetadata.SourceFile("second_source", "second.raw", "file:///second", List.of()));
        FileMetadata meta = new FileMetadata(new FileDescription(fixture.fileDescription().contents(), sources),
                fixture.instrumentConfigurations(), fixture.dataProcessingMethods(), fixture.samples(),
                fixture.software(), runWith(fixture.run(), fixture.run().defaultDataProcessingId(), null));

        JsonNode run = json(FooterMetadataWriter.serialize(meta), "run");

        assertThat(run.path("default_source_file_id").asText())
                .isEqualTo(fixture.fileDescription().sourceFiles().get(0).id());
    }

    @Test
    void missingDefaultSourceFileIdWithoutSourceFilesIsSynthesizedAndDeclared() throws IOException {
        FileMetadata fixture = fixtureMetadata();
        assertThat(fixture.fileDescription()).as("fixture file description").isNotNull();
        FileMetadata meta = new FileMetadata(new FileDescription(fixture.fileDescription().contents(), List.of()),
                fixture.instrumentConfigurations(), fixture.dataProcessingMethods(), fixture.samples(),
                fixture.software(), runWith(fixture.run(), fixture.run().defaultDataProcessingId(), null));

        Map<String, String> kv = FooterMetadataWriter.serialize(meta);

        assertThat(json(kv, "run").path("default_source_file_id").asText()).isEqualTo("mzpeakj_source");
        assertThat(ids(json(kv, "file_description").path("source_files"))).containsExactly("mzpeakj_source");
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /**
     * Asserts that every expected (file, column) pair is an INT64 leaf annotated UINT64, and that across all
     * written files every INT64 leaf named {@code index}, {@code *_index}, {@code MS_1003059_*},
     * {@code MS_1003060_*}, {@code number_of_data_points} or {@code number_of_peaks} is UINT64.
     */
    private static void assertUint64Columns(Path dir, List<String> expected) throws IOException {
        Set<String> uint64 = new LinkedHashSet<>();
        List<String> ruleViolations = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".parquet")).sorted().toList();
        }
        for (Path f : files) {
            try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(f), ParquetGroups.readOptions())) {
                for (ColumnDescriptor c : reader.getFooter().getFileMetaData().getSchema().getColumns()) {
                    PrimitiveType type = c.getPrimitiveType();
                    if (type.getPrimitiveTypeName() != PrimitiveTypeName.INT64) {
                        continue;
                    }
                    String[] path = c.getPath();
                    String column = f.getFileName() + ": " + String.join(".", path);
                    boolean isUint64 = UINT64.equals(type.getLogicalTypeAnnotation());
                    if (isUint64) {
                        uint64.add(column);
                    }
                    String leaf = path[path.length - 1];
                    boolean mustBeUint64 = leaf.equals("index") || leaf.endsWith("_index")
                            || leaf.startsWith("MS_1003059_") || leaf.startsWith("MS_1003060_")
                            || leaf.equals("number_of_data_points") || leaf.equals("number_of_peaks");
                    if (mustBeUint64 && !isUint64) {
                        ruleViolations.add(column + " is " + type.getLogicalTypeAnnotation());
                    }
                }
            }
        }
        assertThat(uint64).as("UINT64 columns written to %s", dir.getFileName()).containsAll(expected);
        assertThat(ruleViolations).as("index/count INT64 columns not annotated UINT64").isEmpty();
    }

    /**
     * The manifest declares version 0.9.0 and exactly the MS and UO CVs, each with a URI and a version: the legacy
     * fixtures use no other CV, and IMS is declared only when it is used (WR-4). Every prefix the written files use
     * is declared.
     */
    private static void assertManifestVersionAndCvList(Path dir) {
        try (MzPeakReader r = MzPeakReader.open(dir)) {
            assertThat(r.manifest().version()).isEqualTo("0.9.0");
            assertThat(r.manifest().cvList()).extracting(MzPeakManifest.CvEntry::id).containsExactly("MS", "UO");
            assertThat(r.manifest().cvList()).allSatisfy(cv -> {
                assertThat(cv.uri()).as("uri of %s", cv.id()).isNotBlank();
                assertThat(cv.version()).as("version of %s", cv.id()).isNotBlank();
            });
        }
        WriterCvListTest.assertEveryWrittenPrefixIsDeclared(dir);
    }

    @SafeVarargs
    private static List<String> concat(List<String>... parts) {
        List<String> all = new ArrayList<>();
        for (List<String> p : parts) {
            all.addAll(p);
        }
        return all;
    }

    private static FileMetadata fixtureMetadata() {
        FileMetadata m;
        try (MzPeakReader r = MzPeakReader.open(UNPACKED)) {
            m = r.fileMetadata();
        }
        assertThat(m.run()).as("fixture run").isNotNull();
        return m;
    }

    private static MsRun runWith(MsRun run, String defaultDataProcessingId, String defaultSourceFileId) {
        return new MsRun(run.id(), run.defaultInstrumentId(), defaultDataProcessingId, defaultSourceFileId,
                run.startTime(), run.parameters());
    }

    private static FileMetadata withRun(FileMetadata m, MsRun run) {
        return new FileMetadata(m.fileDescription(), m.instrumentConfigurations(), m.dataProcessingMethods(),
                m.samples(), m.software(), run);
    }

    private static JsonNode json(Map<String, String> kv, String key) throws IOException {
        assertThat(kv).as("footer key-value metadata").containsKey(key);
        return MAPPER.readTree(kv.get(key));
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        for (JsonNode n : array) {
            ids.add(n.path("id").asText());
        }
        return ids;
    }
}
