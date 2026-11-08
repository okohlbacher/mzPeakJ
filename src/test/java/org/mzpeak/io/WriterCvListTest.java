package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.ManifestWriter.Dialect;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.CvEntry;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Activation;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code metadata.cv_list} the writer declares (WR-4, design §11.6). conformance.md requires an archive to
 * declare every CV prefix it uses, with a {@code uri} that names a fixed release and the matching {@code version}.
 * The known CVs are those of the reference's {@code ControlledVocabularyEntry} table (param.rs:272-336 at 2ee2589),
 * pinned here as literal JSON: MS and UO are always declared, as the reference writer does, and every other known
 * CV only when the written dataset uses it, in the table's order. A prefix outside the table is written as it is,
 * left undeclared and reported once as a warning. A caller's cv list ({@link MzPeakWriter.Options#withCvList}, MZ-8)
 * gives the entry of each declared CV it lists, and declares a prefix in use outside the table, after the known CVs
 * and without the warning.
 *
 * <p>{@link #assertEveryWrittenPrefixIsDeclared} reads the written files, not the model: the CURIEs of the
 * manifest, of every Parquet footer document, of the CV-coded names of the columns that hold a value, and of the
 * accession, unit and term cells.
 */
class WriterCvListTest {

    private static final String INDEX = "mzpeak_index.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** The pin-era packed metadata layout, selected explicitly. */
    private static final MzPeakWriter.Options PACKED =
            MzPeakWriter.Options.defaults().withMetadataLayout(MzPeakWriter.MetadataLayout.PACKED);

    /** The reference's table in its order, one JSON object per CV as the reference serialises it. */
    private static final List<String> REFERENCE_TABLE = List.of(
            "{\"id\":\"MS\",\"full_name\":\"Proteomics Standards Initiative Mass Spectrometry Ontology\","
                    + "\"uri\":\"http://purl.obolibrary.org/obo/ms/4.1.249/psi-ms.obo\",\"version\":\"4.1.249\"}",
            "{\"id\":\"UO\",\"full_name\":\"Units of measurement ontology\","
                    + "\"uri\":\"http://purl.obolibrary.org/obo/uo/releases/2026-01-16/uo.obo\","
                    + "\"version\":\"2026-01-16\"}",
            "{\"id\":\"EFO\",\"full_name\":\"Experimental Factor Ontology\","
                    + "\"uri\":\"http://www.ebi.ac.uk/efo/releases/v3.90.0/efo.obo\",\"version\":\"v3.90.0\"}",
            "{\"id\":\"OBI\",\"full_name\":\"Ontology for Biomedical Investigations\","
                    + "\"uri\":\"http://purl.obolibrary.org/obo/obi/2026-05-08/obi.obo\",\"version\":\"2026-05-08\"}",
            "{\"id\":\"HANCESTRO\",\"full_name\":\"Human Ancestry Ontology\","
                    + "\"uri\":\"http://purl.obolibrary.org/obo/hancestro/releases/2025-10-14/hancestro.obo\","
                    + "\"version\":\"2025-10-14\"}",
            "{\"id\":\"BFO\",\"full_name\":\"Basic Formal Ontology\","
                    + "\"uri\":\"http://purl.obolibrary.org/obo/bfo/2019-08-26/bfo.obo\",\"version\":\"2019-08-26\"}",
            "{\"id\":\"NCIT\",\"full_name\":\"NCI Thesaurus OBO Edition\","
                    + "\"uri\":\"http://purl.obolibrary.org/obo/ncit/releases/2026-03-19/ncit.obo\","
                    + "\"version\":\"26.02d\"}",
            "{\"id\":\"BTO\",\"full_name\":\"The BRENDA Tissue Ontology (BTO)\","
                    + "\"uri\":\"http://purl.obolibrary.org/obo/bto/releases/2021-10-26/bto.owl\","
                    + "\"version\":\"2021-10-26\"}",
            "{\"id\":\"PRIDE\",\"full_name\":\"Proteomics Identification Database Ontology\","
                    + "\"uri\":\"http://purl.obolibrary.org/obo/pride/releases/2026-06-01/pride.obo\","
                    + "\"version\":\"2026-06-01\"}",
            "{\"id\":\"IMS\",\"full_name\":\"Imaging Mass Spectrometry Ontology\","
                    + "\"uri\":\"https://raw.githubusercontent.com/imzML/imzML/refs/heads/master/imagingMS.obo\","
                    + "\"version\":\"1.1.0\"}");
    private static final String MS = REFERENCE_TABLE.get(0);
    private static final String UO = REFERENCE_TABLE.get(1);
    private static final String IMS = REFERENCE_TABLE.get(9);

    /** The MS and UO of an mzML source's cv list: other releases than the writer's, under other URIs. */
    private static final CvEntry CALLER_MS = new CvEntry("MS",
            "Proteomics Standards Initiative Mass Spectrometry Ontology",
            "https://raw.githubusercontent.com/HUPO-PSI/psi-ms-CV/master/psi-ms.obo", "4.1.117");
    private static final CvEntry CALLER_UO = new CvEntry("UO", "Unit Ontology",
            "https://raw.githubusercontent.com/bio-ontology-research-group/unit-ontology/master/unit.obo",
            "09:04:2014");
    /** A known CV in a caller's own release. */
    private static final CvEntry CALLER_BTO = new CvEntry("BTO", "The BRENDA Tissue Ontology (BTO)",
            "https://example.org/bto.owl", "caller-1");
    /** A CV mzPeakJ does not know. */
    private static final CvEntry XYZ = new CvEntry("XYZ", "An ontology mzPeakJ does not know",
            "https://example.org/xyz.obo", "1.2");

    /** A CURIE as a whole cell value: prefix, colon, local id. */
    private static final Pattern CURIE = Pattern.compile("([A-Za-z][A-Za-z0-9_.-]*):([A-Za-z0-9_]+)");
    /** A CV-coded column name segment such as {@code MS_1000511} or {@code unit_UO_0000031}. */
    private static final Pattern CODED_COLUMN = Pattern.compile("([A-Z][A-Za-z]*)[_:](\\d{7})(?!\\d)");
    /** The JSON keys and Parquet leaves whose value is a CURIE. */
    private static final List<String> CURIE_FIELDS = List.of("accession", "unit", "unit_accession");

    /** Fixtures read once per class; the tests never modify a source in place. */
    private static final Map<Path, Source> SOURCES = new ConcurrentHashMap<>();

    // ---- MS and UO ----------------------------------------------------------------------------------------------

    @Test
    void legacySmallUnpackedDeclaresExactlyMsAndUoWithThePinnedUris(@TempDir Path tmp) throws IOException {
        Source s = source(Fixtures.legacy("small.unpacked.mzpeak"));
        Path out = tmp.resolve("small.mzpeak");
        MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata);

        assertThat(cvList(out)).containsExactly(MS, UO);
        // The same two entries the reference wrote into the current small.unpacked example.
        try (MzPeakReader written = MzPeakReader.open(out);
             MzPeakReader reference = MzPeakReader.open(Fixtures.current("small.unpacked.mzpeak"))) {
            assertThat(written.manifest().cvList()).isEqualTo(reference.manifest().cvList())
                    .extracting(CvEntry::id).containsExactly("MS", "UO");
        }
    }

    // ---- IMS when used, packed scan settings --------------------------------------------------------------------

    @Test
    void currentExampleProcessedImgWrittenPackedDeclaresIms(@TempDir Path tmp) throws IOException {
        Source s = source(Fixtures.current("Example_Processed.img.mzpeak"));
        assertThat(s.scanSettings).as("Example_Processed.img scan settings").isNotEmpty();
        Path out = tmp.resolve("img.mzpeak");
        // The packed metadata layout writes the pin-era manifest dialect.
        MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, PACKED);

        assertThat(cvList(out)).containsExactly(MS, UO, IMS);
        assertEveryWrittenPrefixIsDeclared(out);

        // PACKED keeps the scan-settings unit key that mzPeakJ 0.10.3 and earlier read.
        List<String> unitKeys = new ArrayList<>();
        for (JsonNode settings : index(out).path("metadata").path("scan_settings_list")) {
            for (JsonNode param : settings.path("parameters")) {
                assertThat(param.has("unit")).as("scan-settings param %s has a 'unit' key", param).isFalse();
                if (param.has("unit_accession")) {
                    unitKeys.add(param.path("accession").asText() + " " + param.path("unit_accession").asText());
                }
            }
        }
        assertThat(unitKeys).contains("IMS:1000046 UO:0000017");
    }

    // ---- every written prefix -----------------------------------------------------------------------------------

    static Stream<Arguments> writtenOutputs() {
        List<Arguments> cases = new ArrayList<>();
        List<Path> fixtures = List.of(
                Fixtures.legacy("small.unpacked.mzpeak"),
                Fixtures.legacy("small.numpress.mzpeak"),
                Fixtures.legacy("has_uv.mzpeak"),
                Fixtures.current("small.unpacked.mzpeak"),
                Fixtures.current("has_uv.mzpeak"),
                Fixtures.current("diaPASEF.ref.mzpeak"),
                Fixtures.current("Example_Processed.img.mzpeak"));
        for (Path fixture : fixtures) {
            cases.add(Arguments.of(fixture, MzPeakWriter.SignalEncoding.POINT));
            cases.add(Arguments.of(fixture, MzPeakWriter.SignalEncoding.NUMPRESS));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} via {1}")
    @MethodSource("writtenOutputs")
    void everyPrefixInTheWrittenOutputIsDeclared(Path fixture, MzPeakWriter.SignalEncoding encoding,
                                                 @TempDir Path tmp) {
        Source s = source(fixture);
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, MzPeakWriter.Options.defaults().withSignalEncoding(encoding));

        Map<String, String> used = assertEveryWrittenPrefixIsDeclared(out);
        assertThat(used).as("prefixes written for %s", fixture).containsKeys("MS", "UO");
        assertThat(cvList(out)).as("cv_list of %s", fixture).startsWith(MS, UO);
        if (fixture.endsWith("Example_Processed.img.mzpeak")) {
            assertThat(used).containsKey("IMS");
        } else {
            assertThat(cvList(out)).as("cv_list of %s", fixture).containsExactly(MS, UO);
        }
    }

    // ---- every known CV, in the table's order -------------------------------------------------------------------

    @Test
    void everyKnownCvIsDeclaredWhereverItIsUsedInTheTablesOrder(@TempDir Path tmp) throws IOException {
        // Each known CV besides MS and UO is used in one place the writer writes, none in the table's order.
        ScanEvent scan = new ScanEvent(1.0, null, null, null, null, null, List.of(),
                List.of(new Param("tissue", "BTO:0000089", null, null)));
        SelectedIon ion = new SelectedIon(500.0, 2, null,
                List.of(new Param("ancestry", "HANCESTRO:0004", null, null)));
        Precursor precursor = new Precursor(null, null, null, List.of(ion),
                new Activation(List.of(new Param("concept", "NCIT:C25330", null, null))));
        SpectrumDescription description = new SpectrumDescription(0, "scan=1", 2, 1.0, null, null, 0, 0,
                List.of(scan), List.of(precursor), List.of(new Param("project", "PRIDE:0000001", null, null)));
        Spectrum spectrum = new Spectrum(description, new double[] {100.0, 200.0}, new double[] {1.0, 2.0},
                List.of());
        Chromatogram chromatogram = new Chromatogram(0, "assay", "OBI:0000070", new double[] {1.0},
                new double[] {5.0});
        ScanSettings settings = new ScanSettings("settings", List.of(
                new Param("pixel size x", "IMS:1000046", 50.0, "UO:0000017")));
        FileMetadata metadata = new FileMetadata(null, List.of(), List.of(),
                List.of(new FileMetadata.Sample("sample", "sample", List.of(
                        new Param("entity", "BFO:0000001", null, null),
                        new Param("disease", "EFO:0000408", null, null)))),
                List.of(), null);
        Path out = tmp.resolve("all.mzpeak");

        List<LogRecord> warnings = writerWarnings(() -> MzPeakWriter.writeDirectory(out, List.of(spectrum),
                List.of(chromatogram), List.of(), List.of(settings), metadata));

        assertThat(cvList(out)).containsExactlyElementsOf(REFERENCE_TABLE);
        assertThat(warnings).isEmpty();
        assertEveryWrittenPrefixIsDeclared(out);
    }

    // ---- unknown prefix -----------------------------------------------------------------------------------------

    @Test
    void aPatoParamGivesExactlyOneWarningAndStaysUndeclared(@TempDir Path tmp) throws IOException {
        Source s = source(Fixtures.legacy("small.unpacked.mzpeak"));
        Param pato = new Param("quality", "PATO:0000001", null, null);
        // The same unknown prefix on two spectra and on the run: still one warning.
        List<Spectrum> spectra = List.of(withParam(s.spectra.get(0), pato), withParam(s.spectra.get(1), pato));
        FileMetadata.MsRun run = s.fileMetadata.run();
        FileMetadata metadata = new FileMetadata(s.fileMetadata.fileDescription(),
                s.fileMetadata.instrumentConfigurations(), s.fileMetadata.dataProcessingMethods(),
                s.fileMetadata.samples(), s.fileMetadata.software(),
                new FileMetadata.MsRun(run.id(), run.defaultInstrumentId(), run.defaultDataProcessingId(),
                        run.defaultSourceFileId(), run.startTime(),
                        List.of(new Param("trait", "PATO:0000070", null, null))));
        Path out = tmp.resolve("pato.mzpeak");

        List<LogRecord> warnings = writerWarnings(() -> MzPeakWriter.writeDirectory(out, spectra, List.of(),
                List.of(), List.of(), metadata));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage()).contains("PATO");
        assertThat(cvList(out)).containsExactly(MS, UO);
        Map<String, String> used = writtenPrefixes(out);
        assertThat(used).containsKey("PATO");
        assertThat(declaredIds(out)).doesNotContain("PATO").containsAll(
                used.keySet().stream().filter(p -> !p.equals("PATO")).toList());
        // The term itself is written and read back as it was given.
        try (MzPeakReader r = MzPeakReader.open(out)) {
            assertThat(r.getSpectrumAt(0).description().parameters()).contains(pato);
        }
    }

    // ---- column_mapping -----------------------------------------------------------------------------------------

    @Test
    void columnMappingPrefixesAreDeclaredAndAnUnknownOneIsReported(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("spectra_metadata.parquet"), new byte[0]);
        List<ColumnMapping> mapping = List.of(
                new ColumnMapping("position x", "IMS_1000050_position_x", "IMS:1000050", null, null),
                new ColumnMapping("odd", "odd", "XYZ:0000001", "XYZ:0000002", null));
        WritePlan plan = WritePlanner.plan(List.of(), List.of(), List.of(), List.of(), FileMetadata.EMPTY,
                MzPeakWriter.Options.defaults());

        ManifestWriter.writeManifest(dir, List.of(new WrittenMember("spectra_metadata.parquet", EntityType.SPECTRUM,
                DataKind.METADATA, mapping)), plan, Dialect.CURRENT);

        assertThat(cvList(dir)).containsExactly(MS, UO, IMS);
        assertThat(plan.report().warnings()).hasSize(1);
        assertThat(plan.report().warnings().get(0)).contains("XYZ");
    }

    // ---- prefixes used only by a unit ---------------------------------------------------------------------------

    @Test
    void aKnownPrefixUsedOnlyAsAParamUnitIsDeclared(@TempDir Path tmp) throws IOException {
        Source s = source(Fixtures.legacy("small.unpacked.mzpeak"));
        // MS accessions; BTO and NCIT appear only as the units, BTO first although NCIT comes first in the table.
        Param spectrumParam = new Param("sample mass", "MS:1000004", 1.5, "BTO:0000089");
        Param scanParam = new Param("sample volume", "MS:1000005", 2.5, "NCIT:C48505");
        List<Spectrum> spectra = new ArrayList<>(s.spectra);
        spectra.set(0, withParam(spectra.get(0), spectrumParam));
        spectra.set(1, withScanParam(spectra.get(1), scanParam));
        Path out = tmp.resolve("units.mzpeak");

        List<LogRecord> warnings = writerWarnings(() -> MzPeakWriter.writeDirectory(out, spectra, s.chromatograms,
                s.wavelengthSpectra, s.scanSettings, s.fileMetadata));

        assertThat(cvList(out)).containsExactly(MS, UO, REFERENCE_TABLE.get(6), REFERENCE_TABLE.get(7));
        assertThat(warnings).isEmpty();
        assertThat(assertEveryWrittenPrefixIsDeclared(out)).containsEntry("BTO", "BTO:0000089")
                .containsEntry("NCIT", "NCIT:C48505");
        try (MzPeakReader r = MzPeakReader.open(out)) {
            assertThat(r.getSpectrumAt(0).description().parameters()).contains(spectrumParam);
            assertThat(r.getSpectrumAt(1).description().scans().get(0).parameters()).contains(scanParam);
        }
    }

    @Test
    void aKnownPrefixUsedOnlyAsAManifestUnitIsDeclared(@TempDir Path tmp) throws IOException {
        Source s = source(Fixtures.legacy("small.unpacked.mzpeak"));
        // MS accessions; EFO is only the unit_accession of a scan-settings param (the packed layout's pin-era
        // dialect), OBI only the unit of a sample param, which the file-metadata footer and so the manifest's
        // metadata carry.
        ScanSettings settings = new ScanSettings("settings", List.of(
                new Param("sample volume", "MS:1000005", 10.0, "EFO:0000001")));
        FileMetadata m = s.fileMetadata;
        FileMetadata metadata = new FileMetadata(m.fileDescription(), m.instrumentConfigurations(),
                m.dataProcessingMethods(), List.of(new FileMetadata.Sample("sample", "sample", List.of(
                        new Param("sample mass", "MS:1000004", 1.0, "OBI:0000070")))),
                m.software(), m.run());
        Path out = tmp.resolve("manifest-units.mzpeak");

        List<LogRecord> warnings = writerWarnings(() -> MzPeakWriter.writeDirectory(out, s.spectra,
                s.chromatograms, s.wavelengthSpectra, List.of(settings), metadata, PACKED));

        assertThat(cvList(out)).containsExactly(MS, UO, REFERENCE_TABLE.get(2), REFERENCE_TABLE.get(3));
        assertThat(warnings).isEmpty();
        assertEveryWrittenPrefixIsDeclared(out);
        // Each of the two prefixes is the value of a different manifest key.
        JsonNode meta = index(out).path("metadata");
        assertThat(meta.findValuesAsText("unit_accession")).containsExactly("EFO:0000001");
        assertThat(meta.findValuesAsText("unit")).contains("OBI:0000070").doesNotContain("EFO:0000001");
    }

    @Test
    void columnMappingUnitPrefixesAreDeclaredOrReportedOnTheirOwn(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("spectra_metadata.parquet"), new byte[0]);
        List<ColumnMapping> mapping = List.of(
                new ColumnMapping("tissue mass", "tissue_mass", "MS:1000004", "BTO:0000089", null),
                new ColumnMapping("quality mass", "quality_mass", "MS:1000004", "PATO:0000117", null));
        WritePlan plan = WritePlanner.plan(List.of(), List.of(), List.of(), List.of(), FileMetadata.EMPTY,
                MzPeakWriter.Options.defaults());

        ManifestWriter.writeManifest(dir, List.of(new WrittenMember("spectra_metadata.parquet", EntityType.SPECTRUM,
                DataKind.METADATA, mapping)), plan, Dialect.CURRENT);

        assertThat(cvList(dir)).containsExactly(MS, UO, REFERENCE_TABLE.get(7));
        assertThat(plan.report().warnings()).hasSize(1);
        assertThat(plan.report().warnings().get(0)).contains("PATO:0000117");
    }

    @Test
    void anUnknownPrefixUsedOnlyAsAParamUnitGivesExactlyOneWarningAndStaysUndeclared(@TempDir Path tmp)
            throws IOException {
        Source s = source(Fixtures.legacy("small.unpacked.mzpeak"));
        Param pato = new Param("sample mass", "MS:1000004", 1.5, "PATO:0000117");
        // The same unknown unit on two spectra: still one warning.
        List<Spectrum> spectra = new ArrayList<>(s.spectra);
        spectra.set(0, withParam(spectra.get(0), pato));
        spectra.set(1, withParam(spectra.get(1), pato));
        Path out = tmp.resolve("pato-unit.mzpeak");

        List<LogRecord> warnings = writerWarnings(() -> MzPeakWriter.writeDirectory(out, spectra, s.chromatograms,
                s.wavelengthSpectra, s.scanSettings, s.fileMetadata));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage()).contains("PATO:0000117");
        assertThat(cvList(out)).containsExactly(MS, UO);
        assertThat(writtenPrefixes(out)).containsEntry("PATO", "PATO:0000117");
        assertThat(declaredIds(out)).doesNotContain("PATO");
        try (MzPeakReader r = MzPeakReader.open(out)) {
            assertThat(r.getSpectrumAt(0).description().parameters()).contains(pato);
        }
    }

    // ---- prefixes used only by an isolation window param (AP-7) -------------------------------------------------

    @ParameterizedTest
    @EnumSource(MzPeakWriter.MetadataLayout.class)
    void aKnownPrefixUsedOnlyInAnIsolationWindowParamIsDeclared(MzPeakWriter.MetadataLayout layout,
                                                                @TempDir Path tmp) throws IOException {
        Source s = source(Fixtures.legacy("small.unpacked.mzpeak"));
        // BTO is only the unit of one window param and NCIT only the accession of another, BTO first although NCIT
        // comes first in the table.
        Param btoUnit = new Param("sample mass", "MS:1000004", 1.5, "BTO:0000089");
        Param ncit = new Param("concept", "NCIT:C25330", null, null);
        List<Spectrum> spectra = new ArrayList<>(s.spectra);
        int ms2 = precursorSpectra(spectra).get(0);
        spectra.set(ms2, withWindowParams(spectra.get(ms2), List.of(btoUnit, ncit)));
        Path out = tmp.resolve("window.mzpeak");

        List<LogRecord> warnings = writerWarnings(() -> MzPeakWriter.writeDirectory(out, spectra, s.chromatograms,
                s.wavelengthSpectra, s.scanSettings, s.fileMetadata,
                MzPeakWriter.Options.defaults().withMetadataLayout(layout)));

        assertThat(cvList(out)).containsExactly(MS, UO, REFERENCE_TABLE.get(6), REFERENCE_TABLE.get(7));
        assertThat(warnings).isEmpty();
        assertThat(assertEveryWrittenPrefixIsDeclared(out)).containsEntry("BTO", "BTO:0000089")
                .containsEntry("NCIT", "NCIT:C25330");
        try (MzPeakReader r = MzPeakReader.open(out)) {
            assertThat(r.getSpectrumAt(ms2).description().precursors().get(0).isolationWindow().parameters())
                    .contains(btoUnit, ncit);
        }
    }

    @ParameterizedTest
    @EnumSource(MzPeakWriter.MetadataLayout.class)
    void anUnknownPrefixInAnIsolationWindowParamGivesExactlyOneWarningAndStaysUndeclared(
            MzPeakWriter.MetadataLayout layout, @TempDir Path tmp) throws IOException {
        Source s = source(Fixtures.legacy("small.unpacked.mzpeak"));
        Param pato = new Param("quality", "PATO:0000001", null, null);
        // The same unknown prefix in the windows of two spectra: still one warning.
        List<Spectrum> spectra = new ArrayList<>(s.spectra);
        List<Integer> ms2 = precursorSpectra(spectra);
        spectra.set(ms2.get(0), withWindowParams(spectra.get(ms2.get(0)), List.of(pato)));
        spectra.set(ms2.get(1), withWindowParams(spectra.get(ms2.get(1)), List.of(pato)));
        Path out = tmp.resolve("window-pato.mzpeak");

        List<LogRecord> warnings = writerWarnings(() -> MzPeakWriter.writeDirectory(out, spectra, s.chromatograms,
                s.wavelengthSpectra, s.scanSettings, s.fileMetadata,
                MzPeakWriter.Options.defaults().withMetadataLayout(layout)));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage()).contains("PATO:0000001");
        assertThat(cvList(out)).containsExactly(MS, UO);
        assertThat(writtenPrefixes(out)).containsEntry("PATO", "PATO:0000001");
        assertThat(declaredIds(out)).doesNotContain("PATO");
        try (MzPeakReader r = MzPeakReader.open(out)) {
            assertThat(r.getSpectrumAt(ms2.get(1)).description().precursors().get(0).isolationWindow().parameters())
                    .contains(pato);
        }
    }

    // ---- the known CVs and the caller's cv list (MZ-8) ---------------------------------------------------------

    @Test
    void theKnownCvsAreTheReferenceTableInItsOrder() {
        List<CvEntry> known = CvEntry.known();

        assertThat(known).extracting(CvEntry::id).containsExactly("MS", "UO", "EFO", "OBI", "HANCESTRO", "BFO", "NCIT",
                "BTO", "PRIDE", "IMS");
        assertThat(known).extracting(WriterCvListTest::json).containsExactlyElementsOf(REFERENCE_TABLE);
        assertThat(known.get(0)).isEqualTo(new CvEntry("MS", "Proteomics Standards Initiative Mass Spectrometry "
                + "Ontology", "http://purl.obolibrary.org/obo/ms/4.1.249/psi-ms.obo", "4.1.249"));
        assertThat(known.get(known.size() - 1).id()).isEqualTo("IMS");
        assertThatThrownBy(() -> known.add(XYZ)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> known.set(0, CALLER_MS)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(CvEntry.known()).containsExactlyElementsOf(known);
    }

    /** The cv list of an mzML source, with releases of MS and UO other than the writer's: both are kept. */
    @Test
    void theCallersMsAndUoEntriesReplaceTheBuiltInOnes(@TempDir Path tmp) {
        Source s = source(Fixtures.current("small.unpacked.mzpeak"));
        Path out = tmp.resolve("small.mzpeak");

        List<LogRecord> warnings = writerWarnings(() -> MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms,
                s.wavelengthSpectra, s.scanSettings, s.fileMetadata,
                MzPeakWriter.Options.defaults().withCvList(List.of(CALLER_MS, CALLER_UO))));

        assertThat(cvList(out)).containsExactly(
                "{\"id\":\"MS\",\"full_name\":\"Proteomics Standards Initiative Mass Spectrometry Ontology\","
                        + "\"uri\":\"https://raw.githubusercontent.com/HUPO-PSI/psi-ms-CV/master/psi-ms.obo\","
                        + "\"version\":\"4.1.117\"}",
                "{\"id\":\"UO\",\"full_name\":\"Unit Ontology\",\"uri\":\"https://raw.githubusercontent.com/"
                        + "bio-ontology-research-group/unit-ontology/master/unit.obo\",\"version\":\"09:04:2014\"}");
        assertThat(warnings).isEmpty();
        try (MzPeakReader r = MzPeakReader.open(out)) {
            assertThat(r.manifest().cvList()).containsExactly(CALLER_MS, CALLER_UO);
        }
    }

    /**
     * A prefix in use that mzPeakJ does not know is declared by the caller's entry for it, without the warning, and
     * still reported when the caller's list lacks it; caller entries for prefixes not in use, known or not, are not
     * declared.
     */
    @Test
    void aCallerEntryForAnUnknownPrefixInUseIsDeclaredAndAnUnusedOneIsNot(@TempDir Path tmp) {
        Source s = source(Fixtures.legacy("small.unpacked.mzpeak"));
        List<Spectrum> spectra = new ArrayList<>(s.spectra);
        spectra.set(0, withParam(spectra.get(0), new Param("odd", "XYZ:0000001", null, null)));
        spectra.set(1, withParam(spectra.get(1), new Param("quality", "PATO:0000001", null, null)));
        CvEntry unused = new CvEntry("ABC", "An ontology nothing uses", "https://example.org/abc.obo", "3");
        Path plain = tmp.resolve("plain.mzpeak");
        Path out = tmp.resolve("caller.mzpeak");

        List<LogRecord> plainWarnings = writerWarnings(() -> MzPeakWriter.writeDirectory(plain, spectra,
                s.chromatograms, s.wavelengthSpectra, s.scanSettings, s.fileMetadata));
        List<LogRecord> warnings = writerWarnings(() -> MzPeakWriter.writeDirectory(out, spectra, s.chromatograms,
                s.wavelengthSpectra, s.scanSettings, s.fileMetadata,
                MzPeakWriter.Options.defaults().withCvList(List.of(unused, CALLER_BTO, XYZ))));

        assertThat(plainWarnings).extracting(LogRecord::getMessage).hasSize(2)
                .anySatisfy(m -> assertThat(m).contains("XYZ:0000001"))
                .anySatisfy(m -> assertThat(m).contains("PATO:0000001"));
        assertThat(cvList(plain)).containsExactly(MS, UO);
        assertThat(cvList(out)).containsExactly(MS, UO, json(XYZ));
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage()).contains("PATO:0000001").doesNotContain("XYZ");
        assertThat(writtenPrefixes(out)).containsEntry("XYZ", "XYZ:0000001").doesNotContainKeys("ABC", "BTO");
    }

    /**
     * MS and UO, then the known CVs in use in the table's order, then the caller's entries for the unknown ones in
     * the caller's order; a caller entry replaces the built-in one of its id, and of two caller entries with one id
     * the first counts.
     */
    @Test
    void theCallersEntriesFollowTheKnownCvsInTheCallersOrder(@TempDir Path tmp) {
        Source s = source(Fixtures.legacy("small.unpacked.mzpeak"));
        // Used in the order XYZ, BTO, QRS, NCIT.
        List<Spectrum> spectra = new ArrayList<>(s.spectra);
        spectra.set(0, withParam(withParam(spectra.get(0), new Param("odd", "XYZ:0000001", null, null)),
                new Param("brain", "BTO:0000142", null, null)));
        spectra.set(1, withParam(withParam(spectra.get(1), new Param("other", "QRS:0000001", null, null)),
                new Param("concept", "NCIT:C25330", null, null)));
        CvEntry qrs = new CvEntry("QRS", "Another ontology mzPeakJ does not know", "https://example.org/qrs.obo", "7");
        List<CvEntry> caller = List.of(qrs, CALLER_BTO, CALLER_MS, XYZ,
                new CvEntry("XYZ", "A second XYZ", "https://example.org/xyz-2.obo", "2.0"),
                new CvEntry("MS", "A second MS", "https://example.org/ms-2.obo", "9.9.9"));
        Path out = tmp.resolve("order.mzpeak");

        List<LogRecord> warnings = writerWarnings(() -> MzPeakWriter.writeDirectory(out, spectra, s.chromatograms,
                s.wavelengthSpectra, s.scanSettings, s.fileMetadata,
                MzPeakWriter.Options.defaults().withCvList(caller)));

        assertThat(cvList(out)).containsExactly(json(CALLER_MS), UO, REFERENCE_TABLE.get(6), json(CALLER_BTO),
                json(qrs), json(XYZ));
        assertThat(warnings).isEmpty();
        assertEveryWrittenPrefixIsDeclared(out);
    }

    static Stream<Arguments> callerEntriesForCvsUsedOnlyInWavelengthOrChromatogramParams() {
        return Stream.of(
                Arguments.of("wavelength spectrum", new Param("odd", "XYZ:0000001", null, null), XYZ, List.of()),
                Arguments.of("chromatogram", new Param("odd", "XYZ:0000001", null, null), XYZ, List.of()),
                Arguments.of("chromatogram", new Param("brain", "BTO:0000142", null, null), CALLER_BTO,
                        List.of(REFERENCE_TABLE.get(7))));
    }

    /**
     * A CV that only a param of has_uv's first wavelength spectrum or first chromatogram uses is declared by the
     * caller's entry, without a warning; without that entry, an unknown one is left undeclared and a known one is
     * declared by the built-in entry.
     */
    @ParameterizedTest(name = "{1} on a {0}")
    @MethodSource("callerEntriesForCvsUsedOnlyInWavelengthOrChromatogramParams")
    void aCallerEntryForACvUsedOnlyInAWavelengthOrChromatogramParamIsDeclared(String where, Param param,
                                                                               CvEntry entry, List<String> builtIn,
                                                                               @TempDir Path tmp) {
        Source s = source(Fixtures.current("has_uv.mzpeak"));
        List<Chromatogram> chromatograms = new ArrayList<>(s.chromatograms);
        List<WavelengthSpectrum> waves = new ArrayList<>(s.wavelengthSpectra);
        if (where.equals("chromatogram")) {
            chromatograms.set(0, withParam(chromatograms.get(0), param));
        } else {
            waves.set(0, withParam(waves.get(0), param));
        }
        Path plain = tmp.resolve("plain.mzpeak");
        Path out = tmp.resolve("caller.mzpeak");

        List<LogRecord> plainWarnings = writerWarnings(() -> MzPeakWriter.writeDirectory(plain, s.spectra,
                chromatograms, waves, s.scanSettings, s.fileMetadata));
        List<LogRecord> warnings = writerWarnings(() -> MzPeakWriter.writeDirectory(out, s.spectra, chromatograms,
                waves, s.scanSettings, s.fileMetadata, MzPeakWriter.Options.defaults().withCvList(List.of(entry))));

        List<String> plainCvs = new ArrayList<>(List.of(MS, UO));
        plainCvs.addAll(builtIn);
        assertThat(cvList(plain)).containsExactlyElementsOf(plainCvs);
        assertThat(plainWarnings).hasSize(builtIn.isEmpty() ? 1 : 0);
        assertThat(cvList(out)).containsExactly(MS, UO, json(entry));
        assertThat(warnings).isEmpty();
        assertThat(assertEveryWrittenPrefixIsDeclared(out)).containsEntry(entry.id(), param.accession());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /**
     * Asserts that every CURIE prefix the written dataset {@code dir} uses is declared in its cv_list, and returns
     * the prefixes used, each with one CURIE that uses it.
     */
    static Map<String, String> assertEveryWrittenPrefixIsDeclared(Path dir) {
        Map<String, String> used = writtenPrefixes(dir);
        List<String> declared = declaredIds(dir);
        assertThat(declared).as("cv_list of %s", dir).doesNotHaveDuplicates();
        for (Map.Entry<String, String> e : used.entrySet()) {
            assertThat(declared).as("cv_list of %s declares the prefix of %s", dir, e.getValue())
                    .contains(e.getKey());
        }
        return used;
    }

    /**
     * Every CURIE prefix in the files of the unpacked dataset {@code dir}, each with one CURIE that uses it: the
     * accession, unit and unit_accession values of the manifest outside cv_list and of every JSON footer
     * key-value, the CV-coded names of the Parquet columns that hold a value, and every string cell that is an
     * accession or unit leaf or a whole CURIE in a CV-coded, {@code *_type} or {@code chunk_encoding} column. A
     * column without a single value uses no term: the packed schema has its {@code IMS_1000050_position_x} and
     * {@code IMS_1000051_position_y} columns in every file, and fills them only for imaging scans.
     */
    static Map<String, String> writtenPrefixes(Path dir) {
        Map<String, String> used = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.equals(INDEX)) {
                    JsonNode index = MAPPER.readTree(Files.readAllBytes(file));
                    ((ObjectNode) index.path("metadata")).remove("cv_list");
                    jsonCuries(index, used);
                } else if (name.endsWith(".parquet")) {
                    parquetCuries(file, used);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return used;
    }

    private static void parquetCuries(Path file, Map<String, String> used) throws IOException {
        LocalInputFile input = new LocalInputFile(file);
        try (ParquetFileReader reader = ParquetFileReader.open(input, ParquetGroups.readOptions())) {
            for (String value : reader.getFooter().getFileMetaData().getKeyValueMetaData().values()) {
                String trimmed = value.trim();
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    jsonCuries(MAPPER.readTree(trimmed), used);
                }
            }
        }
        ParquetGroups.forEach(input, record -> groupCuries(file.getFileName().toString(), record, used));
    }

    private static void groupCuries(String file, Group group, Map<String, String> used) {
        GroupType type = group.getType();
        for (int field = 0; field < type.getFieldCount(); field++) {
            Type fieldType = type.getType(field);
            if (group.getFieldRepetitionCount(field) > 0) {
                Matcher m = CODED_COLUMN.matcher(fieldType.getName());
                while (m.find()) {
                    used.putIfAbsent(m.group(1), file + " column " + fieldType.getName());
                }
            }
            for (int i = 0; i < group.getFieldRepetitionCount(field); i++) {
                if (!fieldType.isPrimitive()) {
                    groupCuries(file, group.getGroup(field, i), used);
                } else if (fieldType.asPrimitiveType().getPrimitiveTypeName() == PrimitiveTypeName.BINARY) {
                    String name = fieldType.getName();
                    String value = group.getString(field, i);
                    boolean curieLeaf = CURIE_FIELDS.contains(name);
                    boolean termColumn = CODED_COLUMN.matcher(name).lookingAt() || name.endsWith("_type")
                            || name.equals("chunk_encoding");
                    if (curieLeaf || termColumn) {
                        addCurie(value, used, curieLeaf);
                    }
                }
            }
        }
    }

    private static void jsonCuries(JsonNode node, Map<String, String> used) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (CURIE_FIELDS.contains(field.getKey()) && field.getValue().isTextual()) {
                    addCurie(field.getValue().asText(), used, true);
                } else {
                    jsonCuries(field.getValue(), used);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                jsonCuries(child, used);
            }
        }
    }

    /** A CURIE leaf counts with any prefix before a colon; a term cell only when it is a whole CURIE. */
    private static void addCurie(String value, Map<String, String> used, boolean curieLeaf) {
        Matcher m = CURIE.matcher(value);
        if (m.matches()) {
            used.putIfAbsent(m.group(1), value);
        } else if (curieLeaf && value.indexOf(':') > 0) {
            used.putIfAbsent(value.substring(0, value.indexOf(':')), value);
        }
    }

    /** The cv_list of the written dataset {@code dir}, one compact JSON object per entry. */
    private static List<String> cvList(Path dir) {
        List<String> entries = new ArrayList<>();
        for (JsonNode cv : index(dir).path("metadata").path("cv_list")) {
            entries.add(cv.toString());
        }
        return entries;
    }

    private static List<String> declaredIds(Path dir) {
        List<String> ids = new ArrayList<>();
        for (JsonNode cv : index(dir).path("metadata").path("cv_list")) {
            ids.add(cv.path("id").asText());
        }
        return ids;
    }

    private static JsonNode index(Path dir) {
        try {
            return MAPPER.readTree(new String(Files.readAllBytes(dir.resolve(INDEX)), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The WARNING records {@link MzPeakWriter} logs while {@code write} runs; since MZ-2, legacy small.unpacked's
     * chromatograms add none.
     */
    private static List<LogRecord> writerWarnings(Runnable write) {
        return ArrayIndexTest.captureLog(MzPeakWriter.class, write).stream()
                .filter(r -> r.getLevel() == Level.WARNING).toList();
    }

    private static Spectrum withParam(Spectrum s, Param param) {
        SpectrumDescription d = s.description();
        List<Param> params = new ArrayList<>(d.parameters());
        params.add(param);
        SpectrumDescription changed = new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(),
                d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(),
                d.precursors(), params);
        return new Spectrum(changed, s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
    }

    /** {@code w} with {@code param} added to its Params. */
    private static WavelengthSpectrum withParam(WavelengthSpectrum w, Param param) {
        List<Param> params = new ArrayList<>(w.parameters());
        params.add(param);
        return new WavelengthSpectrum(w.index(), w.id(), w.time(), w.lambdaMax(), w.wavelength(), w.intensity(),
                params);
    }

    /** {@code c} with {@code param} added to its Params. */
    private static Chromatogram withParam(Chromatogram c, Param param) {
        List<Param> params = new ArrayList<>(c.parameters());
        params.add(param);
        return new Chromatogram(c.index(), c.id(), c.typeCurie(), c.time(), c.intensity(), c.intensityUnit(),
                c.arrays(), c.polarity(), c.dataProcessingRef(), params, c.precursors());
    }

    /** {@code cv} as one compact JSON object, with the keys in the order the writer writes them. */
    private static String json(CvEntry cv) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", cv.id());
        node.put("full_name", cv.fullName());
        node.put("uri", cv.uri());
        node.put("version", cv.version());
        return node.toString();
    }

    /** {@code s} with {@code param} added to its first scan. */
    private static Spectrum withScanParam(Spectrum s, Param param) {
        SpectrumDescription d = s.description();
        List<ScanEvent> scans = new ArrayList<>(d.scans());
        ScanEvent first = scans.get(0);
        List<Param> params = new ArrayList<>(first.parameters());
        params.add(param);
        scans.set(0, new ScanEvent(first.startTime(), first.injectionTime(), first.filterString(),
                first.presetScanConfiguration(), first.instrumentConfigurationRef(), first.spectrumReference(),
                first.scanWindows(), params));
        SpectrumDescription changed = new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(),
                d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), scans,
                d.precursors(), d.parameters());
        return new Spectrum(changed, s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
    }

    /** The positions in {@code spectra} of the spectra whose first precursor has an isolation window. */
    private static List<Integer> precursorSpectra(List<Spectrum> spectra) {
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < spectra.size(); i++) {
            List<Precursor> precursors = spectra.get(i).description().precursors();
            if (!precursors.isEmpty() && precursors.get(0).isolationWindow() != null) {
                positions.add(i);
            }
        }
        return positions;
    }

    /** {@code s} with {@code params} added to the isolation window of its first precursor. */
    private static Spectrum withWindowParams(Spectrum s, List<Param> params) {
        SpectrumDescription d = s.description();
        List<Precursor> precursors = new ArrayList<>(d.precursors());
        Precursor first = precursors.get(0);
        IsolationWindow w = first.isolationWindow();
        List<Param> windowParams = new ArrayList<>(w.parameters());
        windowParams.addAll(params);
        precursors.set(0, new Precursor(first.precursorIndex(), first.precursorId(),
                new IsolationWindow(w.targetMz(), w.lowerOffset(), w.upperOffset(), windowParams),
                first.selectedIons(), first.activation()));
        SpectrumDescription changed = new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(),
                d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(),
                precursors, d.parameters());
        return new Spectrum(changed, s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
    }

    private static Source source(Path fixture) {
        return SOURCES.computeIfAbsent(fixture, ForwardBackwardCorpusTest::readSource);
    }
}
