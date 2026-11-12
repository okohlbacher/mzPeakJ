package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.DataProcessing;
import org.mzpeak.model.meta.FileMetadata.InstrumentConfiguration;
import org.mzpeak.model.meta.FileMetadata.MsRun;
import org.mzpeak.model.meta.FileMetadata.ProcessingMethod;
import org.mzpeak.model.meta.FileMetadata.Software;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * WR-9 (D-17): the file metadata documents {@link FooterMetadataWriter} writes into the footers and the manifest
 * resolve every reference and carry the keys and types the specification's schemas require ({@link SpecSchemaCheck}).
 * What a reference names but the file metadata lacks is added as a stub, with one WARNING of
 * {@link FooterMetadataWriter}'s logger per stub.
 *
 * <p>Oracle: the HUPO-PSI example datasets legacy small, current small, has_uv, Example_Processed.img and
 * diaPASEF.ref, the file metadata of legacy small changed where a test says so, and the datasets
 * {@link InteropOutputs} writes from them. The fixtures without a dangling reference serialize as before WR-9 (pinned
 * by the SHA-256 of the documents).
 */
class RunReferenceConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SOURCE_STUB = "{\"id\":\"mzpeakj_source\",\"name\":\"unknown\","
            + "\"location\":\"file:///unknown\",\"parameters\":[]}";
    private static final String UNDECLARED = " names but the file metadata does not declare";

    /**
     * The checker finds a missing required key, a property of the wrong type (also of an item reached through a local
     * {@code $ref}) and a reference that names nothing.
     */
    @Test
    void theCheckerFindsMissingKeysWrongTypesAndUnresolvedReferences() {
        Map<String, String> footer = Map.of(
                "run", "{\"id\":5,\"default_instrument_id\":\"0\",\"default_data_processing_id\":\"dp\"}",
                "instrument_configuration_list", "[{\"id\":0,\"components\":[],\"parameters\":[],"
                        + "\"software_reference\":\"sw\"},{\"id\":\"1\",\"components\":{}}]",
                "data_processing_method_list", "[{\"id\":\"dp\",\"methods\":[{\"order\":1,"
                        + "\"software_reference\":\"other\"}]}]",
                "software_list", "[{\"id\":\"sw\",\"version\":null}]");

        assertThat(SpecSchemaCheck.violations(footer)).containsExactlyInAnyOrder(
                "run: required key default_source_file_id is missing",
                "run.id: NUMBER 5 is not of type \"string\"",
                "run.default_instrument_id: STRING \"0\" is not of type \"integer\"",
                "software_list[0].version: NULL null is not of type \"string\"",
                "instrument_configuration_list[1]: required key parameters is missing",
                "instrument_configuration_list[1]: required key software_reference is missing",
                "instrument_configuration_list[1].components: OBJECT {} is not of type \"array\"",
                "instrument_configuration_list[1].id: STRING \"1\" is not of type \"integer\"");
        assertThat(SpecSchemaCheck.unresolved(footer)).containsExactlyInAnyOrder(
                "data processing \"dp\" method software_reference \"other\" names nothing of [sw]",
                "run default_instrument_id \"0\" names no instrument configuration of [0, 1]",
                "run default_source_file_id is missing");
    }

    /**
     * (a) A run without any id and nothing else: the run gets every key the schema requires, its default instrument
     * a stub configuration 0 (an integer) whose software is the stub software, and its default data processing and
     * source file stubs of their own; every reference resolves and each stub is logged once.
     */
    @Test
    void aRunWithoutIdsAndNothingElseGetsEveryRequiredKeyAndResolvingStubs() throws IOException {
        FileMetadata meta = new FileMetadata(null, List.of(), List.of(), List.of(), List.of(),
                new MsRun(null, null, null, null, null, List.of()));

        Serialized out = serialize(meta);

        assertThat(out.footer.keySet()).containsExactly("run", "software_list", "instrument_configuration_list",
                "data_processing_method_list", "file_description");
        assertThat(out.footer.get("run")).isEqualTo("{\"id\":\"mzpeakj_run\",\"default_instrument_id\":0,"
                + "\"default_data_processing_id\":\"mzpeakj_export\",\"default_source_file_id\":\"mzpeakj_source\","
                + "\"parameters\":[]}");
        assertThat(json(out.footer, "run").get("default_instrument_id").isInt()).isTrue();
        assertThat(out.footer.get("instrument_configuration_list")).isEqualTo("[{\"id\":0,"
                + "\"software_reference\":\"mzpeakj_software\",\"components\":[],\"parameters\":[]}]");
        assertThat(out.footer.get("software_list"))
                .isEqualTo("[{\"id\":\"mzpeakj_software\",\"version\":\"\",\"parameters\":[]}]");
        assertThat(out.footer.get("data_processing_method_list"))
                .isEqualTo("[{\"id\":\"mzpeakj_export\",\"methods\":[]}]");
        assertThat(out.footer.get("file_description"))
                .isEqualTo("{\"contents\":[],\"source_files\":[" + SOURCE_STUB + "]}");
        assertThat(SpecSchemaCheck.unresolved(out.footer)).isEmpty();
        assertThat(SpecSchemaCheck.violations(out.footer)).isEmpty();
        assertThat(out.warnings).containsExactly(
                "instrument_configuration_list: added the stub instrument configuration 0, which"
                        + " run.default_instrument_id" + UNDECLARED,
                "data_processing_method_list: added the stub data processing mzpeakj_export, which"
                        + " run.default_data_processing_id" + UNDECLARED,
                "file_description: added the stub source file mzpeakj_source, which run.default_source_file_id"
                        + UNDECLARED,
                "software_list: added the stub software mzpeakj_software, which"
                        + " instrument_configuration_list[0].software_reference" + UNDECLARED);
    }

    /** One change of legacy small's file metadata and the stub it needs. */
    record Dangling(String name, UnaryOperator<FileMetadata> change, String document, String stub, String what) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Dangling> danglingReferences() {
        return Stream.of(
                new Dangling("configuration software", m -> withConfiguration(m, 0, "missing_sw"),
                        "software_list", "{\"id\":\"missing_sw\",\"version\":\"\",\"parameters\":[]}",
                        "software missing_sw, which instrument_configuration_list[0].software_reference"),
                new Dangling("processing method software", m -> withMethodSoftware(m, "missing_method_sw"),
                        "software_list", "{\"id\":\"missing_method_sw\",\"version\":\"\",\"parameters\":[]}",
                        "software missing_method_sw, which"
                                + " data_processing_method_list[0].methods[0].software_reference"),
                new Dangling("data processing", m -> withRun(m, run(m, "missing_dp", m.run().defaultSourceFileId(),
                        m.run().defaultInstrumentId())), "data_processing_method_list",
                        "{\"id\":\"missing_dp\",\"methods\":[]}",
                        "data processing missing_dp, which run.default_data_processing_id"),
                new Dangling("source file", m -> withRun(m, run(m, m.run().defaultDataProcessingId(), "missing_sf",
                        m.run().defaultInstrumentId())), "file_description",
                        "{\"id\":\"missing_sf\",\"name\":\"unknown\",\"location\":\"file:///unknown\","
                                + "\"parameters\":[]}",
                        "source file missing_sf, which run.default_source_file_id"),
                new Dangling("instrument", m -> withRun(m, run(m, m.run().defaultDataProcessingId(),
                        m.run().defaultSourceFileId(), 7)), "instrument_configuration_list",
                        "{\"id\":7,\"software_reference\":\"Xcalibur\",\"components\":[],\"parameters\":[]}",
                        "instrument configuration 7, which run.default_instrument_id"));
    }

    /**
     * (b) A software, data processing, source file or instrument configuration that a reference names but legacy
     * small's file metadata lacks is added as a stub of that id at the end of its list, with exactly one WARNING; the
     * reference is kept and every reference resolves.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("danglingReferences")
    void aDanglingReferenceGetsAStubAndOneWarning(Dangling dangling) throws IOException {
        FileMetadata meta = dangling.change.apply(fileMetadata(Fixtures.legacy("small.mzpeak")));

        Serialized out = serialize(meta);

        JsonNode document = json(out.footer, dangling.document);
        JsonNode list = document.isArray() ? document : document.path("source_files");
        assertThat(list.get(list.size() - 1).toString()).isEqualTo(dangling.stub);
        assertThat(out.warnings).containsExactly(dangling.document + ": added the stub " + dangling.what
                + UNDECLARED);
        assertThat(SpecSchemaCheck.unresolved(out.footer)).isEmpty();
        assertThat(SpecSchemaCheck.violations(out.footer)).isEmpty();
        JsonNode run = json(out.footer, "run");
        assertThat(run.path("default_instrument_id").asInt()).isEqualTo(meta.run().defaultInstrumentId());
        assertThat(run.path("default_data_processing_id").asText()).isEqualTo(meta.run().defaultDataProcessingId());
        assertThat(run.path("default_source_file_id").asText()).isEqualTo(meta.run().defaultSourceFileId());
    }

    /**
     * Values the schemas require that the file metadata leaves out are filled in without a stub and without a
     * warning: the run id, the default instrument (the first configuration), a software version (empty) and the
     * software of a configuration (the first software when the first configuration names none).
     */
    @Test
    void missingRequiredValuesAreFilledWithoutStubs() throws IOException {
        FileMetadata fixture = fileMetadata(Fixtures.legacy("small.mzpeak"));
        List<Software> software = new ArrayList<>(fixture.software());
        Software first = software.get(0);
        software.set(0, new Software(first.id(), null, first.parameters()));
        MsRun run = fixture.run();
        FileMetadata meta = withConfiguration(new FileMetadata(fixture.fileDescription(),
                fixture.instrumentConfigurations(), fixture.dataProcessingMethods(), fixture.samples(), software,
                new MsRun(null, null, run.defaultDataProcessingId(), run.defaultSourceFileId(), run.startTime(),
                        run.parameters())), 0, null);

        Serialized out = serialize(meta);

        assertThat(out.warnings).isEmpty();
        JsonNode runJson = json(out.footer, "run");
        assertThat(runJson.path("id").asText()).isEqualTo("mzpeakj_run");
        assertThat(runJson.path("default_instrument_id").asInt())
                .isEqualTo(fixture.instrumentConfigurations().get(0).id());
        JsonNode version = json(out.footer, "software_list").get(0).path("version");
        assertThat(version.isTextual()).isTrue();
        assertThat(version.asText()).isEmpty();
        assertThat(json(out.footer, "instrument_configuration_list").get(0).path("software_reference").asText())
                .isEqualTo(first.id());
        assertThat(SpecSchemaCheck.unresolved(out.footer)).isEmpty();
        assertThat(SpecSchemaCheck.violations(out.footer)).isEmpty();
    }

    /**
     * Without configurations a stub configuration 0 names the first software; without a file description the stub
     * source file comes with one; an explicit default data processing without any data processing gets a stub of its
     * own id. A processing method without software stays without one.
     */
    @Test
    void stubsOfFileMetadataWithoutConfigurationsFileDescriptionOrProcessing() throws IOException {
        FileMetadata fixture = fileMetadata(Fixtures.legacy("small.mzpeak"));
        FileMetadata meta = new FileMetadata(null, List.of(), List.of(), fixture.samples(), fixture.software(),
                new MsRun("run", null, "explicit_dp", null, null, List.of()));

        Serialized out = serialize(meta);

        assertThat(out.footer.get("instrument_configuration_list")).isEqualTo("[{\"id\":0,"
                + "\"software_reference\":\"Xcalibur\",\"components\":[],\"parameters\":[]}]");
        assertThat(out.footer.get("data_processing_method_list"))
                .isEqualTo("[{\"id\":\"explicit_dp\",\"methods\":[]}]");
        assertThat(out.footer.get("file_description"))
                .isEqualTo("{\"contents\":[],\"source_files\":[" + SOURCE_STUB + "]}");
        assertThat(out.warnings).hasSize(3);
        assertThat(SpecSchemaCheck.unresolved(out.footer)).isEmpty();
        assertThat(SpecSchemaCheck.violations(out.footer)).isEmpty();

        Serialized withoutSoftware = serialize(withMethodSoftware(fixture, null));
        assertThat(json(withoutSoftware.footer, "data_processing_method_list").get(0).path("methods").get(0)
                .has("software_reference")).isFalse();
        assertThat(withoutSoftware.warnings).isEmpty();
        assertThat(withoutSoftware.footer.get("software_list"))
                .isEqualTo(FooterMetadataWriter.serialize(fixture).get("software_list"));
    }

    /**
     * Without a default instrument the run names the first configuration's id, also when no configuration is 0: no
     * stub configuration and no warning.
     */
    @Test
    void aRunWithoutDefaultInstrumentNamesTheFirstConfigurationAlsoWhenItIsNotZero() throws IOException {
        FileMetadata fixture = fileMetadata(Fixtures.legacy("small.mzpeak"));
        List<InstrumentConfiguration> configurations = new ArrayList<>();
        for (InstrumentConfiguration c : fixture.instrumentConfigurations()) {
            configurations.add(new InstrumentConfiguration(c.id() + 3, c.components(), c.softwareReference(),
                    c.parameters()));
        }
        assertThat(configurations).extracting(InstrumentConfiguration::id).containsExactly(3, 4);
        FileMetadata meta = new FileMetadata(fixture.fileDescription(), configurations,
                fixture.dataProcessingMethods(), fixture.samples(), fixture.software(),
                run(fixture, fixture.run().defaultDataProcessingId(), fixture.run().defaultSourceFileId(), null));

        Serialized out = serialize(meta);

        assertThat(json(out.footer, "run").path("default_instrument_id").asInt()).isEqualTo(3);
        assertThat(json(out.footer, "instrument_configuration_list")).extracting(c -> c.path("id").asInt())
                .containsExactly(3, 4);
        assertThat(out.warnings).isEmpty();
        assertThat(SpecSchemaCheck.violations(out.footer)).isEmpty();
        assertThat(SpecSchemaCheck.unresolved(out.footer)).isEmpty();
    }

    /** Several references to the same undeclared software add one stub of it, with one warning. */
    @Test
    void severalReferencesToOneUndeclaredSoftwareAddOneStub() throws IOException {
        FileMetadata fixture = fileMetadata(Fixtures.legacy("small.mzpeak"));
        FileMetadata meta = withMethodSoftware(withConfiguration(withConfiguration(fixture, 0, "X"), 1, "X"), "X");

        Serialized out = serialize(meta);

        JsonNode software = json(out.footer, "software_list");
        assertThat(software).hasSize(fixture.software().size() + 1);
        assertThat(software).filteredOn(s -> "X".equals(s.path("id").asText())).extracting(JsonNode::toString)
                .containsExactly("{\"id\":\"X\",\"version\":\"\",\"parameters\":[]}");
        assertThat(out.warnings).containsExactly("software_list: added the stub software X, which"
                + " instrument_configuration_list[0].software_reference" + UNDECLARED);
        assertThat(SpecSchemaCheck.violations(out.footer)).isEmpty();
        assertThat(SpecSchemaCheck.unresolved(out.footer)).isEmpty();
    }

    /**
     * A software without an id cannot be named: a configuration without software names the first software with an id,
     * and without one the stub software, so every configuration keeps its required software_reference.
     */
    @Test
    void aSoftwareWithoutIdIsNotNamedByAConfiguration() throws IOException {
        Software anonymous = new Software(null, null, List.of());
        FileMetadata meta = new FileMetadata(null, List.of(), List.of(), List.of(), List.of(anonymous),
                new MsRun(null, null, null, null, null, List.of()));

        Serialized out = serialize(meta);

        assertThat(out.footer.get("instrument_configuration_list")).isEqualTo("[{\"id\":0,"
                + "\"software_reference\":\"mzpeakj_software\",\"components\":[],\"parameters\":[]}]");
        assertThat(out.footer.get("software_list")).isEqualTo("[{\"version\":\"\",\"parameters\":[]},"
                + "{\"id\":\"mzpeakj_software\",\"version\":\"\",\"parameters\":[]}]");
        assertThat(out.warnings).hasSize(4).endsWith("software_list: added the stub software mzpeakj_software, which"
                + " instrument_configuration_list[0].software_reference" + UNDECLARED);
        assertThat(SpecSchemaCheck.violations(out.footer)).isEmpty();
        assertThat(SpecSchemaCheck.unresolved(out.footer)).isEmpty();

        Serialized named = serialize(new FileMetadata(null, List.of(), List.of(), List.of(),
                List.of(anonymous, new Software("named", "1", List.of())), meta.run()));

        assertThat(json(named.footer, "instrument_configuration_list").get(0).path("software_reference").asText())
                .isEqualTo("named");
        assertThat(json(named.footer, "software_list")).hasSize(2);
        assertThat(named.warnings).hasSize(3);
        assertThat(SpecSchemaCheck.violations(named.footer)).isEmpty();
        assertThat(SpecSchemaCheck.unresolved(named.footer)).isEmpty();
    }

    /** Without a run nothing names a default, so only the software references are resolved. */
    @Test
    void withoutARunOnlyTheSoftwareReferencesAreResolved() throws IOException {
        FileMetadata fixture = fileMetadata(Fixtures.legacy("small.mzpeak"));
        FileMetadata meta = withConfiguration(new FileMetadata(null, fixture.instrumentConfigurations(), List.of(),
                List.of(), fixture.software(), null), 1, "missing_sw");

        Serialized out = serialize(meta);

        assertThat(out.footer.keySet()).containsExactly("software_list", "instrument_configuration_list");
        assertThat(json(out.footer, "software_list")).hasSize(fixture.software().size() + 1);
        assertThat(out.warnings).containsExactly("software_list: added the stub software missing_sw, which"
                + " instrument_configuration_list[1].software_reference" + UNDECLARED);
        assertThat(SpecSchemaCheck.unresolved(out.footer)).isEmpty();
        assertThat(SpecSchemaCheck.violations(out.footer)).isEmpty();
    }

    /**
     * (c) diaPASEF.ref regression: the reference writer names default instrument 1, but declares only configuration
     * 0. A stub configuration 1 with configuration 0's software is added, the run keeps naming 1, everything resolves
     * and passes the schema check.
     */
    @Test
    void diaPasefRefGetsAStubConfigurationForItsDefaultInstrument() throws IOException {
        FileMetadata meta = fileMetadata(Fixtures.current("diaPASEF.ref.mzpeak"));
        assertThat(meta.run().defaultInstrumentId()).isEqualTo(1);
        assertThat(meta.instrumentConfigurations()).extracting(InstrumentConfiguration::id).containsExactly(0);

        Serialized out = serialize(meta);

        JsonNode configurations = json(out.footer, "instrument_configuration_list");
        assertThat(configurations).hasSize(2);
        assertThat(configurations.get(1).toString()).isEqualTo(
                "{\"id\":1,\"software_reference\":\"ACQ_SW\",\"components\":[],\"parameters\":[]}");
        assertThat(json(out.footer, "run").path("default_instrument_id").asInt()).isEqualTo(1);
        assertThat(out.warnings).containsExactly("instrument_configuration_list: added the stub instrument"
                + " configuration 1, which run.default_instrument_id" + UNDECLARED);
        assertThat(SpecSchemaCheck.unresolved(out.footer)).isEmpty();
        assertThat(SpecSchemaCheck.violations(out.footer)).isEmpty();
    }

    /**
     * (f) Every file metadata document of every dataset {@link InteropOutputs} writes, in the split and the packed
     * layout, in the manifest and in each member's footer, passes the schema check and resolves.
     */
    @Test
    void everyDocumentInteropOutputsWritesConformsAndResolves(@TempDir Path tmp) {
        List<InteropOutputs.Output> outputs = new ArrayList<>(InteropOutputs.write(tmp));
        outputs.add(InteropOutputs.writePacked(tmp));
        assertThat(outputs).hasSize(15);

        List<String> problems = new ArrayList<>();
        for (InteropOutputs.Output output : outputs) {
            Map<String, Map<String, String>> places = SpecSchemaCheck.documents(output.path());
            if (output.kind() == InteropOutputs.Kind.MS2_SUBSET) {
                // ExtractSpectra writes no file metadata.
                assertThat(places).as(output.name()).isEmpty();
                continue;
            }
            assertThat(places).as(output.name()).containsKey("mzpeak_index.json");
            places.forEach((place, documents) -> {
                assertThat(documents).as(output.name() + " " + place).containsKey("run");
                SpecSchemaCheck.violations(documents).forEach(v -> problems.add(output + " " + place + ": " + v));
                SpecSchemaCheck.unresolved(documents).forEach(u -> problems.add(output + " " + place + ": " + u));
            });
        }
        assertThat(problems).isEmpty();
    }

    static Stream<Arguments> unchangedFixtures() {
        return Stream.of(
                arguments("legacy small", Fixtures.legacy("small.mzpeak"),
                        "2d884f6b44b6550697f6237142c625f86b172c1159180c47c01f43dd41e24490"),
                arguments("current small", Fixtures.current("small.mzpeak"),
                        "2d884f6b44b6550697f6237142c625f86b172c1159180c47c01f43dd41e24490"),
                arguments("has_uv", Fixtures.current("has_uv.mzpeak"),
                        "fafe3cae2690253edbf7328590c39867c5e3bbdf25e80f71179d266d2dd9ad73"),
                arguments("Example_Processed.img", Fixtures.current("Example_Processed.img.mzpeak"),
                        "c7fd6cde3aa7554b385fa01be353a544218c28d2eb0bbe7cbf989ac74a98f6bd"));
    }

    /**
     * (d) The file metadata of a fixture without a dangling reference serializes byte-identically to the writer before
     * WR-9: no stub is added, so their writer snapshots stay. The documents pass the schema check and resolve.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unchangedFixtures")
    void fixturesWithoutDanglingReferencesSerializeAsBefore(String name, Path fixture, String sha256) {
        Serialized out = serialize(fileMetadata(fixture));

        assertThat(digest(out.footer)).as("%s: %s", name, out.footer).isEqualTo(sha256);
        assertThat(out.warnings).isEmpty();
        assertThat(SpecSchemaCheck.violations(out.footer)).isEmpty();
        assertThat(SpecSchemaCheck.unresolved(out.footer)).isEmpty();
    }

    /** The documents {@link FooterMetadataWriter#serialize} returns and the warnings it logs. */
    private record Serialized(Map<String, String> footer, List<String> warnings) {
    }

    private static Serialized serialize(FileMetadata meta) {
        AtomicReference<Map<String, String>> footer = new AtomicReference<>();
        List<String> warnings = ArrayIndexTest.captureLog(FooterMetadataWriter.class,
                        () -> footer.set(FooterMetadataWriter.serialize(meta))).stream()
                .filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage).toList();
        return new Serialized(footer.get(), warnings);
    }

    private static JsonNode json(Map<String, String> footer, String key) throws IOException {
        assertThat(footer).containsKey(key);
        return MAPPER.readTree(footer.get(key));
    }

    private static FileMetadata withRun(FileMetadata m, MsRun run) {
        return new FileMetadata(m.fileDescription(), m.instrumentConfigurations(), m.dataProcessingMethods(),
                m.samples(), m.software(), run);
    }

    private static MsRun run(FileMetadata m, String dataProcessing, String sourceFile, Integer instrument) {
        MsRun r = m.run();
        return new MsRun(r.id(), instrument, dataProcessing, sourceFile, r.startTime(), r.parameters());
    }

    /** {@code m} with configuration {@code i} naming {@code software}. */
    private static FileMetadata withConfiguration(FileMetadata m, int i, String software) {
        List<InstrumentConfiguration> configurations = new ArrayList<>(m.instrumentConfigurations());
        InstrumentConfiguration c = configurations.get(i);
        configurations.set(i, new InstrumentConfiguration(c.id(), c.components(), software, c.parameters()));
        return new FileMetadata(m.fileDescription(), configurations, m.dataProcessingMethods(), m.samples(),
                m.software(), m.run());
    }

    /** {@code m} with the first method of its first data processing naming {@code software}. */
    private static FileMetadata withMethodSoftware(FileMetadata m, String software) {
        List<DataProcessing> processing = new ArrayList<>(m.dataProcessingMethods());
        DataProcessing dp = processing.get(0);
        List<ProcessingMethod> methods = new ArrayList<>(dp.methods());
        ProcessingMethod pm = methods.get(0);
        methods.set(0, new ProcessingMethod(pm.order(), software, pm.parameters()));
        processing.set(0, new DataProcessing(dp.id(), methods));
        return new FileMetadata(m.fileDescription(), m.instrumentConfigurations(), processing, m.samples(),
                m.software(), m.run());
    }

    static FileMetadata fileMetadata(Path dataset) {
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            return reader.fileMetadata();
        }
    }

    /** The SHA-256 of the documents in order, each as its key, a tab, the JSON and a line feed. */
    private static String digest(Map<String, String> footer) {
        StringBuilder text = new StringBuilder();
        footer.forEach((key, json) -> text.append(key).append('\t').append(json).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
