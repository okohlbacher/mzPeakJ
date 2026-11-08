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
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
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

/**
 * The {@code metadata.cv_list} the writer declares (WR-4, design §11.6). conformance.md requires an archive to
 * declare every CV prefix it uses, with a {@code uri} that names a fixed release and the matching {@code version}.
 * The known CVs are those of the reference's {@code ControlledVocabularyEntry} table (param.rs:272-336 at 2ee2589),
 * pinned here as literal JSON: MS and UO are always declared, as the reference writer does, and every other known
 * CV only when the written dataset uses it, in the table's order. A prefix outside the table is written as it is,
 * left undeclared and reported once as a warning.
 *
 * <p>{@link #assertEveryWrittenPrefixIsDeclared} reads the written files, not the model: the CURIEs of the
 * manifest, of every Parquet footer document, of the CV-coded names of the columns that hold a value, and of the
 * accession, unit and term cells.
 */
class WriterCvListTest {

    private static final String INDEX = "mzpeak_index.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        // writeDirectory writes the packed metadata layout with the pin-era manifest dialect.
        MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata);

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

    /** The WARNING records {@link MzPeakWriter} logs while {@code write} runs. */
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

    private static Source source(Path fixture) {
        return SOURCES.computeIfAbsent(fixture, ForwardBackwardCorpusTest::readSource);
    }
}
