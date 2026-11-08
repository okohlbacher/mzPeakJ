package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.model.Param;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.Component;
import org.mzpeak.model.meta.FileMetadata.DataProcessing;
import org.mzpeak.model.meta.FileMetadata.FileDescription;
import org.mzpeak.model.meta.FileMetadata.InstrumentConfiguration;
import org.mzpeak.model.meta.FileMetadata.MsRun;
import org.mzpeak.model.meta.FileMetadata.ProcessingMethod;
import org.mzpeak.model.meta.FileMetadata.Sample;
import org.mzpeak.model.meta.FileMetadata.Software;
import org.mzpeak.model.meta.FileMetadata.SourceFile;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * File-level metadata: {@link MzPeakReader#fileMetadata()}, {@link MzPeakReader#scanSettings()} and
 * {@link MzPeakReader#isImaging()} on every fixture of both sets.
 *
 * <p>The characterization pinned what the reader returned before RD-10 made the index the first source: per fixture
 * the run as one canonical line, the list sizes, the SHA-256 of the canonical lines of the other five documents,
 * the canonical scan settings and the imaging flag. A unit that changes these on purpose updates the values and
 * says why. Final review: a param's absent name, accession or unit reads U+2400 instead of "null"
 * ({@link Canonical#ABSENT}), which changed every documents digest and the scan settings of Example_Processed.img.
 *
 * <p>RD-10: the index's {@code metadata} is read first, the {@code spectra_metadata.parquet} footer only for the
 * documents the index lacks ({@link FooterMetadataReader#resolve}, {@link FooterMetadataReader#scanSettings}).
 */
class IndexMetadataTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INDEX = "mzpeak_index.json";

    /** What the reader returns for one fixture; {@code sourceFiles} is -1 without a file description. */
    record Snapshot(String run, int instruments, int software, int dataProcessing, int samples, int sourceFiles,
                    String documentsSha256, String scanSettings, boolean imaging) {
    }

    private static final String SMALL_RUN =
            "(small|0|pwiz_Reader_Thermo_conversion|RAW1|2005-07-20T19:44:22Z|[])";
    private static final String HAS_UV_RUN =
            "(TOFsulfasMS4GHzDualMode_x002b_DADSpectra_x002b_UVSignal272-NoProfile"
                    + "|0|pwiz_Reader_conversion|MSScan.bin|2007-10-20T20:12:18Z|[])";
    private static final String DIA_PASEF_RUN =
            "(test/data/diaPASEF.d|1|null|analysis.tdf|2019-08-24T18:27:02.345+02:00|[])";
    private static final String LEGACY_SMALL_RUN =
            "(small|0|pwiz_Reader_Thermo_conversion|RAW1|2005-07-20T19:44:22Z|[])";
    private static final String LEGACY_HAS_UV_RUN =
            "(TOFsulfasMS4GHzDualMode_x002b_DADSpectra_x002b_UVSignal272-NoProfile"
                    + "|0|pwiz_Reader_conversion|MSScan.bin|2007-10-20T20:12:18Z|[])";

    private static final String SMALL_DOCUMENTS = "21127984c26090a9ac748f12a9ec76e5de0e2531003744653c5be4b43f8f530e";
    private static final String HAS_UV_DOCUMENTS = "978fe8d70a9b0ba9fb4dc903fdc14ab861419be977efc26db6899d0d01b11695";

    /** Example_Processed.img's footer scan settings, in file order. */
    private static final String IMG_SCAN_SETTINGS = "[(scansettings1|["
            + "(top down|IMS:1000401|null|␀);(flyback|IMS:1000413|null|␀);"
            + "(horizontal line scan|IMS:1000480|null|␀);(linescan left right|IMS:1000491|null|␀);"
            + "(max count of pixels x|IMS:1000042|Long:3|␀);(max count of pixels y|IMS:1000043|Long:3|␀);"
            + "(max dimension x|IMS:1000044|Long:300|UO:0000017);(max dimension y|IMS:1000045|Long:300|UO:0000017);"
            + "(pixel size (x)|IMS:1000046|Double:100.0|UO:0000017);"
            + "(pixel size y|IMS:1000047|Double:100.0|UO:0000017)])]";

    /**
     * The current set (2ee2589). Changed by RD-10: the default instrument, null while only the footer was read
     * (it spells {@code default_instrument_configuration_id}), now reads 0 (diaPASEF: 1) from the index or either
     * footer spelling; Example_Processed.img, whose index metadata is {}, gains its footer scan settings and is
     * imaging. Every other document reads as before.
     */
    private static final Map<String, Snapshot> CURRENT = Map.of(
            "small.mzpeak", new Snapshot(SMALL_RUN, 2, 3, 2, 1, 1, SMALL_DOCUMENTS, "[]", false),
            // small.chunked and small.numpress record their own conversion options
            "small.chunked.mzpeak", new Snapshot(SMALL_RUN, 2, 3, 2, 1, 1,
                    "df75d5b44da7d65fffb8dac3ade21e9040e4e3f2bdd361d87d1b297fbbbd6b9b", "[]", false),
            "small.numpress.mzpeak", new Snapshot(SMALL_RUN, 2, 3, 2, 1, 1,
                    "205827cd87cab05ef36164d3237148fc65d10a3ae8ff6e9bd203b720732f46f5", "[]", false),
            "small.unpacked.mzpeak", new Snapshot(SMALL_RUN, 2, 3, 2, 1, 1, SMALL_DOCUMENTS, "[]", false),
            "has_uv.mzpeak", new Snapshot(HAS_UV_RUN, 1, 4, 3, 0, 18, HAS_UV_DOCUMENTS, "[]", false),
            "diaPASEF.ref.mzpeak", new Snapshot(DIA_PASEF_RUN, 1, 3, 1, 1, 2,
                    "83e3b61484da5e4da49a56a3c689b20b8f209d2f0a46298bd46edd0c84a704fe", "[]", false),
            "diaPASEF.grid.mzpeak", new Snapshot(DIA_PASEF_RUN, 1, 3, 1, 1, 2,
                    "19590180ad7a413fae6ef6b406f123366c4b65e1ae88c120c751a991f4888787", "[]", false),
            // its index metadata is {}; its scan settings exist only in the footer
            "Example_Processed.img.mzpeak", new Snapshot("(Experiment01|0|XcaliburProcessing|sf1|null|[])",
                    1, 3, 3, 1, 1, "d592789f5540114567a94c8178751fd7bbf7b258d5a6cd6fc041a0a9a9bc8911",
                    IMG_SCAN_SETTINGS, true));

    /** The legacy set (29e59b2): footer and index hold the same documents; the run spells default_instrument_id. */
    private static final Map<String, Snapshot> LEGACY = Map.of(
            "small.mzpeak", new Snapshot(LEGACY_SMALL_RUN, 2, 3, 2, 1, 1, SMALL_DOCUMENTS, "[]", false),
            "small.chunked.mzpeak", new Snapshot(LEGACY_SMALL_RUN, 2, 3, 2, 1, 1,
                    "60462294af6d969819d27189e8a212383b3834f700c556341dfa2027fec9ae29", "[]", false),
            "small.numpress.mzpeak", new Snapshot(LEGACY_SMALL_RUN, 2, 3, 2, 1, 1,
                    "200ae1438fa3b2ec4826f8fda7d6d8bd5ad4913d582f53936553a42c36c7b2ce", "[]", false),
            "small.unpacked.mzpeak", new Snapshot(LEGACY_SMALL_RUN, 2, 3, 2, 1, 1, SMALL_DOCUMENTS, "[]", false),
            "has_uv.mzpeak", new Snapshot(LEGACY_HAS_UV_RUN, 1, 4, 3, 0, 18, HAS_UV_DOCUMENTS, "[]", false));

    static Stream<Arguments> allFixtures() {
        return Stream.concat(
                Fixtures.CURRENT_NAMES.stream().map(n -> Arguments.of("current", n, Fixtures.current(n))),
                Fixtures.LEGACY_NAMES.stream().map(n -> Arguments.of("legacy", n, Fixtures.legacy(n))));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("allFixtures")
    void fileMetadataMatchesSnapshot(String set, String fixture, Path dataset) {
        Snapshot expected = ("current".equals(set) ? CURRENT : LEGACY).get(fixture);
        assertThat(expected).as("snapshot for %s %s", set, fixture).isNotNull();
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            assertThat(snapshot(reader)).as("file metadata of %s %s", set, fixture).isEqualTo(expected);
        }
    }

    @Test
    void currentSmallUnpackedAnchorLines() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.unpacked.mzpeak"))) {
            List<String> documents = documents(reader.fileMetadata());
            assertThat(documents.get(3)).isEqualTo("sample_list=[(_x0031_||[(sample name|MS:1000002|Long:1|␀)])]");
            assertThat(documents.get(4)).isEqualTo("software_list=["
                    + "(Xcalibur|1.1 Beta 7|[(Xcalibur|MS:1000532|null|␀)]);"
                    + "(pwiz|3.0.23307|[(ProteoWizard software|MS:1000615|null|␀)]);"
                    + "(mzpeak_prototyping_convert1|0.1.0|[(custom unreleased software tool|MS:1000799"
                    + "|String:mzpeak_prototyping_convert|␀)])]");
        }
    }

    // ---- index first (RD-10) ----------------------------------------------------------------------

    /** (a) The run comes from the index, whose run spells {@code default_instrument_id}. */
    @Test
    void currentSmallUnpackedReadsTheIndexMetadata() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.unpacked.mzpeak"))) {
            FileMetadata m = reader.fileMetadata();
            assertThat(m.run().id()).isEqualTo("small");
            assertThat(m.run().defaultInstrumentId()).isEqualTo(0);
            assertThat(m.run().defaultDataProcessingId()).isEqualTo("pwiz_Reader_Thermo_conversion");
            assertThat(m.run().defaultSourceFileId()).isEqualTo("RAW1");
            assertThat(m.instrumentConfigurations()).hasSize(2);
            assertThat(m.software()).hasSize(3);
            assertThat(m.dataProcessingMethods()).hasSize(2);
            assertThat(m.samples()).hasSize(1);
            assertThat(m.fileDescription().sourceFiles()).hasSize(1);
        }
    }

    /** (b) An index value wins over the footer's. */
    @Test
    void anEditedIndexRunIdWins(@TempDir Path tmp) throws IOException {
        Path dataset = unpack(Fixtures.current("small.unpacked.mzpeak"), tmp);
        editIndex(dataset, index -> ((ObjectNode) index.path("metadata").path("run")).put("id", "edited"));
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            MsRun run = reader.fileMetadata().run();
            assertThat(run.id()).isEqualTo("edited");
            assertThat(run.defaultInstrumentId()).isEqualTo(0);
            assertThat(run.startTime()).isEqualTo("2005-07-20T19:44:22Z");
        }
    }

    /** (b) Without index metadata every document comes from the footer, which spells the run key differently. */
    @Test
    void emptyIndexMetadataReadsTheSameFromTheFooter(@TempDir Path tmp) throws IOException {
        Path original = Fixtures.current("small.unpacked.mzpeak");
        Path dataset = unpack(original, tmp);
        editIndex(dataset, index -> index.putObject("metadata"));
        FileMetadata expected;
        try (MzPeakReader reader = MzPeakReader.open(original)) {
            expected = reader.fileMetadata();
        }
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            assertThat(reader.manifest().cvList()).isEmpty();
            assertThat(reader.fileMetadata()).isEqualTo(expected);
            assertThat(reader.fileMetadata().run().defaultInstrumentId()).isEqualTo(0);
        }
    }

    /** (c) Example_Processed.img's index metadata is {}; its scan settings exist only in the footer. */
    @Test
    void exampleProcessedImgReadsTheFooterScanSettings() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("Example_Processed.img.mzpeak"))) {
            assertThat(reader.manifest().scanSettingsList()).isEmpty();
            List<ScanSettings> settings = reader.scanSettings();
            assertThat(settings).extracting(ScanSettings::id).containsExactly("scansettings1");
            List<Param> params = settings.get(0).parameters();
            assertThat(params).hasSize(10);
            assertThat(Param.find(params, "IMS:1000042")).get().extracting(Param::value).isEqualTo(3L);
            assertThat(Param.find(params, "IMS:1000043")).get().extracting(Param::value).isEqualTo(3L);
            assertThat(Param.find(params, "IMS:1000046")).get()
                    .isEqualTo(new Param("pixel size (x)", "IMS:1000046", 100.0, "UO:0000017"));
            assertThat(settings.get(0).maxPixelX()).contains(3);
            assertThat(settings.get(0).pixelSizeY()).contains(100.0);
            assertThat(reader.isImaging()).isTrue();
        }
    }

    /** Index scan settings win over the footer's, even when empty. */
    @Test
    void indexScanSettingsWinOverTheFooter(@TempDir Path tmp) throws IOException {
        Path dataset = unpack(Fixtures.current("Example_Processed.img.mzpeak"), tmp);
        editIndex(dataset, index -> ((ObjectNode) index.path("metadata")).putArray("scan_settings_list"));
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            assertThat(reader.scanSettings()).isEmpty();
            assertThat(reader.isImaging()).isFalse();
        }
    }

    /** Scan-settings param units are read from {@code unit}, falling back to {@code unit_accession}. */
    @Test
    void indexScanSettingsReadTheUnitFirst(@TempDir Path tmp) throws IOException {
        Path dataset = unpack(Fixtures.current("small.unpacked.mzpeak"), tmp);
        editIndex(dataset, index -> {
            ArrayNode params = ((ObjectNode) index.path("metadata")).putArray("scan_settings_list")
                    .addObject().put("id", "s1").putArray("parameters");
            params.addObject().put("name", "pixel size (x)").put("accession", "IMS:1000046").put("value", 100.0)
                    .put("unit", "UO:0000017");
            params.addObject().put("name", "pixel size y").put("accession", "IMS:1000047").put("value", 50.0)
                    .put("unit_accession", "UO:0000017");
            params.addObject().put("name", "max dimension x").put("accession", "IMS:1000044").put("value", 300)
                    .put("unit", "UO:0000017").put("unit_accession", "UO:0000008");
        });
        List<Param> expected = List.of(
                new Param("pixel size (x)", "IMS:1000046", 100.0, "UO:0000017"),
                new Param("pixel size y", "IMS:1000047", 50.0, "UO:0000017"),
                new Param("max dimension x", "IMS:1000044", 300L, "UO:0000017"));
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            assertThat(reader.manifest().scanSettingsList()).singleElement()
                    .extracting(ScanSettings::parameters).isEqualTo(expected);
            assertThat(reader.scanSettings()).isEqualTo(reader.manifest().scanSettingsList());
            assertThat(reader.isImaging()).isTrue();
        }
    }

    /** (e) A malformed footer run is ignored when the index supplies the run. */
    @Test
    void anInvalidFooterRunStillYieldsTheIndexRun() {
        JsonNode index = indexMetadata(Fixtures.current("small.unpacked.mzpeak"));
        MsRun expected = new MsRun("small", 0, "pwiz_Reader_Thermo_conversion", "RAW1", "2005-07-20T19:44:22Z",
                List.of());
        assertThat(FooterMetadataReader.resolve(index, () -> Map.of("run", "{not json")).run()).isEqualTo(expected);

        // a run without start_time makes resolve read the footer run; malformed, it is still ignored
        ObjectNode partial = index.deepCopy();
        ((ObjectNode) partial.get("run")).remove("start_time");
        assertThat(FooterMetadataReader.resolve(partial, () -> Map.of("run", "{not json")).run())
                .isEqualTo(new MsRun("small", 0, "pwiz_Reader_Thermo_conversion", "RAW1", null, List.of()));
    }

    /** A malformed footer document is ignored when the index supplies it, also when the footer is read. */
    @Test
    void aMalformedFooterDocumentTheIndexSuppliesIsIgnored() {
        ObjectNode index = indexMetadata(Fixtures.current("small.unpacked.mzpeak")).deepCopy();
        index.remove("sample_list");
        Map<String, String> footer = Map.of(
                "sample_list", "[{\"id\": \"from-footer\", \"parameters\": []}]",
                "software_list", "[not json",
                "run", "{not json");
        FileMetadata m = FooterMetadataReader.resolve(index, () -> footer);
        assertThat(m.samples()).extracting(Sample::id).containsExactly("from-footer");
        assertThat(m.software()).extracting(Software::id)
                .containsExactly("Xcalibur", "pwiz", "mzpeak_prototyping_convert1");
        assertThat(m.run().id()).isEqualTo("small");
    }

    /** A malformed footer document the index lacks still throws, naming the key. */
    @Test
    void aMalformedFooterDocumentTheIndexLacksThrows() {
        ObjectNode index = indexMetadata(Fixtures.current("small.unpacked.mzpeak")).deepCopy();
        index.remove("software_list");
        assertThatThrownBy(() -> FooterMetadataReader.resolve(index, () -> Map.of("software_list", "[not json")))
                .isInstanceOf(MzPeakException.class).hasMessageContaining("'software_list'");
        assertThatThrownBy(() -> FooterMetadataReader.scanSettings(MAPPER.createObjectNode(),
                () -> Map.of("scan_settings_list", "[not json")))
                .isInstanceOf(MzPeakException.class).hasMessageContaining("'scan_settings_list'");
    }

    /** An index that holds every document and a complete run never reads the footer. */
    @Test
    void aCompleteIndexNeverReadsTheFooter() {
        Supplier<Map<String, String>> footer = () -> {
            throw new AssertionError("footer read");
        };
        for (String fixture : Fixtures.CURRENT_NAMES) {
            if (fixture.startsWith("small") || fixture.equals("has_uv.mzpeak")) {
                JsonNode index = indexMetadata(Fixtures.current(fixture));
                assertThat(FooterMetadataReader.resolve(index, footer).run().defaultInstrumentId())
                        .as(fixture).isEqualTo(0);
                assertThat(FooterMetadataReader.scanSettings(index, footer)).as(fixture).isEmpty();
            }
        }
    }

    /** Index documents win over the footer's, document by document. */
    @Test
    void indexDocumentsWinOverTheFooter() {
        ObjectNode index = MAPPER.createObjectNode();
        index.putArray("software_list").addObject().put("id", "index-software").put("version", "1");
        index.putArray("scan_settings_list").addObject().put("id", "index-settings").putArray("parameters");
        Map<String, String> footer = Map.of(
                "software_list", "[{\"id\": \"footer-software\", \"version\": \"2\"}]",
                "sample_list", "[{\"id\": \"footer-sample\"}]",
                "scan_settings_list", "[{\"id\": \"footer-settings\", \"parameters\": []}]");
        FileMetadata m = FooterMetadataReader.resolve(index, () -> footer);
        assertThat(m.software()).extracting(Software::id).containsExactly("index-software");
        assertThat(m.samples()).extracting(Sample::id).containsExactly("footer-sample");
        assertThat(m.run()).isNull();
        assertThat(FooterMetadataReader.scanSettings(index, () -> footer)).extracting(ScanSettings::id)
                .containsExactly("index-settings");
        assertThat(FooterMetadataReader.scanSettings(MAPPER.createObjectNode(), () -> footer))
                .extracting(ScanSettings::id).containsExactly("footer-settings");
    }

    /** Both run key spellings: {@code default_instrument_configuration_id} first; {@code parameters}, else params. */
    @Test
    void runAcceptsBothKeySpellings() {
        assertThat(footerRun("{\"default_instrument_configuration_id\": 2, \"default_instrument_id\": 5}")
                .defaultInstrumentId()).isEqualTo(2);
        assertThat(footerRun("{\"default_instrument_configuration_id\": 2}").defaultInstrumentId()).isEqualTo(2);
        assertThat(footerRun("{\"default_instrument_id\": 5}").defaultInstrumentId()).isEqualTo(5);
        assertThat(footerRun("{\"id\": \"r\"}").defaultInstrumentId()).isNull();

        String p1 = "{\"name\": \"a\", \"accession\": \"MS:1\", \"value\": 1, \"unit\": \"UO:1\"}";
        String p2 = "{\"name\": \"b\", \"accession\": null, \"value\": \"x\", \"unit\": null}";
        assertThat(footerRun("{\"parameters\": [" + p1 + "], \"params\": [" + p2 + "]}").parameters())
                .containsExactly(new Param("a", "MS:1", 1L, "UO:1"));
        assertThat(footerRun("{\"params\": [" + p2 + "]}").parameters())
                .containsExactly(new Param("b", null, "x", null));
        assertThat(footerRun("{\"id\": \"r\"}").parameters()).isEmpty();
    }

    /** The run is merged field by field: what the index's run lacks comes from the footer's. */
    @Test
    void runIsMergedFieldByField() {
        ObjectNode index = MAPPER.createObjectNode();
        index.putObject("run").put("id", "index-run").putNull("start_time");
        Map<String, String> footer = Map.of("run", "{\"id\": \"footer-run\", \"default_instrument_id\": 3,"
                + " \"default_data_processing_id\": \"dp\", \"default_source_file_id\": \"sf\","
                + " \"start_time\": \"2005-07-20T19:44:22Z\","
                + " \"parameters\": [{\"name\": \"n\", \"accession\": \"MS:2\", \"value\": 2.5, \"unit\": null}]}");
        assertThat(FooterMetadataReader.resolve(index, () -> footer).run()).isEqualTo(new MsRun("index-run", 3, "dp",
                "sf", "2005-07-20T19:44:22Z", List.of(new Param("n", "MS:2", 2.5, null))));
    }

    /** An index document set to JSON null counts as missing: the footer's is read, the run's too (FM5). */
    @Test
    void aJsonNullIndexDocumentIsReadFromTheFooter() {
        ObjectNode index = MAPPER.createObjectNode();
        index.putNull("run");
        index.putNull("software_list");
        Map<String, String> footer = Map.of(
                "run", "{\"id\": \"footer-run\", \"default_instrument_id\": 3}",
                "software_list", "[{\"id\": \"footer-software\", \"version\": \"2\"}]");
        FileMetadata m = FooterMetadataReader.resolve(index, () -> footer);
        assertThat(m.run()).isEqualTo(new MsRun("footer-run", 3, null, null, null, List.of()));
        assertThat(m.software()).extracting(Software::id).containsExactly("footer-software");
    }

    /** An index run with every field but its parameters takes the footer run's parameters (FM8). */
    @Test
    void anIndexRunWithoutParametersTakesTheFootersParameters() {
        ObjectNode index = MAPPER.createObjectNode();
        index.putObject("run").put("id", "index-run").put("default_instrument_configuration_id", 1)
                .put("default_data_processing_id", "dp").put("default_source_file_id", "sf")
                .put("start_time", "2005-07-20T19:44:22Z");
        Map<String, String> footer = Map.of("run", "{\"id\": \"footer-run\","
                + " \"parameters\": [{\"name\": \"n\", \"accession\": \"MS:2\", \"value\": 2.5, \"unit\": null}]}");
        assertThat(FooterMetadataReader.resolve(index, () -> footer).run()).isEqualTo(new MsRun("index-run", 1, "dp",
                "sf", "2005-07-20T19:44:22Z", List.of(new Param("n", "MS:2", 2.5, null))));
    }

    /** Canonical writes components by name; this fails when a model record gains one it does not write. */
    @Test
    void canonicalFormCoversEveryRecordComponent() {
        assertThat(components(FileMetadata.class)).containsExactly("fileDescription", "instrumentConfigurations",
                "dataProcessingMethods", "samples", "software", "run");
        assertThat(components(FileDescription.class)).containsExactly("contents", "sourceFiles");
        assertThat(components(SourceFile.class)).containsExactly("id", "name", "location", "parameters");
        assertThat(components(InstrumentConfiguration.class)).containsExactly("id", "components",
                "softwareReference", "parameters");
        assertThat(components(Component.class)).containsExactly("componentType", "order", "parameters");
        assertThat(components(DataProcessing.class)).containsExactly("id", "methods");
        assertThat(components(ProcessingMethod.class)).containsExactly("order", "softwareReference", "parameters");
        assertThat(components(Sample.class)).containsExactly("id", "name", "parameters");
        assertThat(components(Software.class)).containsExactly("id", "version", "parameters");
        assertThat(components(MsRun.class)).containsExactly("id", "defaultInstrumentId", "defaultDataProcessingId",
                "defaultSourceFileId", "startTime", "parameters");
        assertThat(components(ScanSettings.class)).containsExactly("id", "parameters");
    }

    // ---- canonical form ---------------------------------------------------------------------------

    static Snapshot snapshot(MzPeakReader reader) {
        FileMetadata m = reader.fileMetadata();
        return new Snapshot(
                run(m.run()),
                m.instrumentConfigurations().size(),
                m.software().size(),
                m.dataProcessingMethods().size(),
                m.samples().size(),
                m.fileDescription() == null ? -1 : m.fileDescription().sourceFiles().size(),
                Canonical.sha256(documents(m)),
                list(reader.scanSettings(), IndexMetadataTest::scanSettings),
                reader.isImaging());
    }

    /** {@code (id|defaultInstrumentId|defaultDataProcessingId|defaultSourceFileId|startTime|[params])}. */
    static String run(MsRun r) {
        if (r == null) {
            return "null";
        }
        return "(" + r.id() + "|" + r.defaultInstrumentId() + "|" + r.defaultDataProcessingId()
                + "|" + r.defaultSourceFileId() + "|" + r.startTime() + "|" + params(r.parameters()) + ")";
    }

    /** One line per document other than the run, in {@link FileMetadata} component order. */
    static List<String> documents(FileMetadata m) {
        return List.of(
                "file_description=" + fileDescription(m.fileDescription()),
                "instrument_configuration_list=" + list(m.instrumentConfigurations(), IndexMetadataTest::instrument),
                "data_processing_method_list=" + list(m.dataProcessingMethods(), IndexMetadataTest::dataProcessing),
                "sample_list=" + list(m.samples(), s -> "(" + s.id() + "|" + s.name() + "|" + params(s.parameters())
                        + ")"),
                "software_list=" + list(m.software(), s -> "(" + s.id() + "|" + s.version()
                        + "|" + params(s.parameters()) + ")"));
    }

    static String fileDescription(FileDescription d) {
        if (d == null) {
            return "null";
        }
        return "(" + params(d.contents()) + "|" + list(d.sourceFiles(), f -> "(" + f.id() + "|" + f.name()
                + "|" + f.location() + "|" + params(f.parameters()) + ")") + ")";
    }

    static String instrument(InstrumentConfiguration ic) {
        return "(" + ic.id() + "|" + list(ic.components(), c -> "(" + c.componentType() + "|" + c.order()
                + "|" + params(c.parameters()) + ")") + "|" + ic.softwareReference() + "|" + params(ic.parameters())
                + ")";
    }

    static String dataProcessing(DataProcessing dp) {
        return "(" + dp.id() + "|" + list(dp.methods(), pm -> "(" + pm.order() + "|" + pm.softwareReference()
                + "|" + params(pm.parameters()) + ")") + ")";
    }

    static String scanSettings(ScanSettings s) {
        return "(" + s.id() + "|" + params(s.parameters()) + ")";
    }

    static String params(List<Param> params) {
        return list(params, Canonical::param);
    }

    static <T> String list(List<T> items, Function<T, String> describe) {
        return items.stream().map(describe).collect(Collectors.joining(";", "[", "]"));
    }

    private static List<String> components(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    // ---- datasets ---------------------------------------------------------------------------------

    private static JsonNode indexMetadata(Path dataset) {
        try (MzPeakSource src = MzPeakSource.open(dataset)) {
            return MzPeakManifest.fromSource(src).metadataJson();
        }
    }

    /** The run of a footer that holds only {@code runJson}. */
    private static MsRun footerRun(String runJson) {
        return FooterMetadataReader.parse(Map.of("run", runJson)).run();
    }

    /** Copies an unpacked dataset, or extracts a single-file one, into a directory under {@code tmp}. */
    private static Path unpack(Path dataset, Path tmp) throws IOException {
        Path copy = tmp.resolve(dataset.getFileName().toString());
        Files.createDirectories(copy);
        if (Files.isDirectory(dataset)) {
            try (Stream<Path> files = Files.list(dataset)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    Files.copy(f, copy.resolve(f.getFileName().toString()));
                }
            }
            return copy;
        }
        try (ZipFile zip = new ZipFile(dataset.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                Path target = copy.resolve(e.getName()).normalize();
                if (e.isDirectory() || !target.startsWith(copy)) {
                    throw new AssertionError("unexpected ZIP entry " + e.getName() + " in " + dataset);
                }
                try (InputStream in = zip.getInputStream(e)) {
                    Files.copy(in, target);
                }
            }
        }
        return copy;
    }

    private static void editIndex(Path unpacked, Consumer<ObjectNode> edit) throws IOException {
        ObjectNode index = (ObjectNode) MAPPER.readTree(Files.readAllBytes(unpacked.resolve(INDEX)));
        edit.accept(index);
        Files.write(unpacked.resolve(INDEX), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(index));
    }
}
