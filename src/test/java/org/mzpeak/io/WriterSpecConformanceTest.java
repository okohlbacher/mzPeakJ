package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.FileDescription;
import org.mzpeak.model.meta.FileMetadata.MsRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the mzPeak spec rules the writer must follow, which the lenient reader cannot catch on its own: every
 * index, count and source_index column is written as UINT64 ({@code intType(64, false)}), the manifest declares
 * version {@code 0.9.0} and the MS, IMS and UO controlled vocabularies, and the footer metadata always gives the
 * run a resolvable default data processing and default source file id.
 *
 * <p>The expected UINT64 columns are spelled out per file and layout. Signed INT64 leaves such as the parameter
 * {@code value.integer} union member are deliberately not covered by the naming rule.
 */
class WriterSpecConformanceTest {

    private static final String BASE = "src/test/resources/mzpeak/";
    private static final Path UNPACKED = Path.of(BASE + "small.unpacked.mzpeak");
    private static final Path HAS_UV = Path.of(BASE + "has_uv.mzpeak");

    private static final LogicalTypeAnnotation UINT64 = LogicalTypeAnnotation.intType(64, false);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** UINT64 columns of the spectrum metadata table, identical for every layout. */
    private static final List<String> SPECTRUM_METADATA_UINT64 = List.of(
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

    /** UINT64 columns of the chromatogram tables (always written in point layout). */
    private static final List<String> CHROMATOGRAM_UINT64 = List.of(
            "chromatograms_metadata.parquet: chromatogram.index",
            "chromatograms_metadata.parquet: chromatogram.MS_1003060_number_of_data_points",
            "chromatograms_data.parquet: point.chromatogram_index");

    /** UINT64 columns of the wavelength-spectrum tables. */
    private static final List<String> WAVELENGTH_UINT64 = List.of(
            "wavelength_spectra_metadata.parquet: spectrum.index",
            "wavelength_spectra_metadata.parquet: spectrum.MS_1003060_number_of_data_points",
            "wavelength_spectra_data.parquet: point.wavelength_spectrum_index");

    @Test
    void pointLayoutDeclaresUint64IndexAndCountColumns(@TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(UNPACKED);
        Path dir = tmp.resolve("point.mzpeak");
        MzPeakWriter.writeDirectory(dir, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata);

        assertUint64Columns(dir, concat(SPECTRUM_METADATA_UINT64, POINT_SPECTRUM_DATA_UINT64, CHROMATOGRAM_UINT64));
        assertManifestVersionAndCvList(dir);
    }

    @Test
    void numpressLayoutDeclaresUint64IndexAndCountColumns(@TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(UNPACKED);
        Path dir = tmp.resolve("numpress.mzpeak");
        MzPeakWriter.writeDirectoryNumpress(dir, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, MzPeakWriter.NumpressIntensityEncoding.SLOF);

        assertUint64Columns(dir, concat(SPECTRUM_METADATA_UINT64,
                List.of("spectra_data.parquet: chunk.spectrum_index",
                        "spectra_peaks.parquet: chunk.spectrum_index"),
                CHROMATOGRAM_UINT64));
        assertManifestVersionAndCvList(dir);
    }

    @Test
    void wavelengthTablesDeclareUint64IndexAndCountColumns(@TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(HAS_UV);
        assertThat(s.wavelengthSpectra).as("has_uv wavelength spectra").isNotEmpty();
        Path dir = tmp.resolve("has_uv.mzpeak");
        MzPeakWriter.writeDirectory(dir, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata);

        assertUint64Columns(dir, concat(SPECTRUM_METADATA_UINT64, POINT_SPECTRUM_DATA_UINT64, CHROMATOGRAM_UINT64,
                WAVELENGTH_UINT64));
        assertManifestVersionAndCvList(dir);
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
        FileMetadata meta = withRun(fixture, runWith(fixture.run(), fixture.run().defaultDataProcessingId(), null));

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
     * written files every INT64 leaf named {@code index}, {@code *_index}, {@code MS_1003059_*} or
     * {@code MS_1003060_*} is UINT64.
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
                            || leaf.startsWith("MS_1003059_") || leaf.startsWith("MS_1003060_");
                    if (mustBeUint64 && !isUint64) {
                        ruleViolations.add(column + " is " + type.getLogicalTypeAnnotation());
                    }
                }
            }
        }
        assertThat(uint64).as("UINT64 columns written to %s", dir.getFileName()).containsAll(expected);
        assertThat(ruleViolations).as("index/count INT64 columns not annotated UINT64").isEmpty();
    }

    private static void assertManifestVersionAndCvList(Path dir) {
        try (MzPeakReader r = MzPeakReader.open(dir)) {
            assertThat(r.manifest().version()).isEqualTo("0.9.0");
            assertThat(r.manifest().cvList()).extracting(MzPeakManifest.CvEntry::id).contains("MS", "IMS", "UO");
        }
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
