package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.model.Param;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.Component;
import org.mzpeak.model.meta.FileMetadata.ComponentType;
import org.mzpeak.model.meta.FileMetadata.DataProcessing;
import org.mzpeak.model.meta.FileMetadata.FileDescription;
import org.mzpeak.model.meta.FileMetadata.InstrumentConfiguration;
import org.mzpeak.model.meta.FileMetadata.MsRun;
import org.mzpeak.model.meta.FileMetadata.ProcessingMethod;
import org.mzpeak.model.meta.FileMetadata.Sample;
import org.mzpeak.model.meta.FileMetadata.Software;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge paths of {@link FooterMetadataReader}: absent, blank and malformed footer documents and fields, and an index
 * run that lacks one field. The index documents come from the manifest of the vendored {@code small.unpacked.mzpeak};
 * the footer maps are written here, since no fixture holds such documents.
 */
class FooterMetadataReaderTest {

    /** The {@code run} of the vendored {@code small.unpacked.mzpeak} manifest. */
    private static final MsRun FIXTURE_RUN = new MsRun("small", 0, "pwiz_Reader_Thermo_conversion", "RAW1",
            "2005-07-20T19:44:22Z", List.of());

    /** A footer run whose every field differs from {@link #FIXTURE_RUN}. */
    private static final String FOOTER_RUN = "{\"id\": \"footer-run\", \"default_instrument_id\": 7,"
            + " \"default_data_processing_id\": \"footer-dp\", \"default_source_file_id\": \"footer-sf\","
            + " \"start_time\": \"2000-01-01T00:00:00Z\","
            + " \"parameters\": [{\"name\": \"n\", \"accession\": \"MS:2\", \"value\": 2.5, \"unit\": null}]}";

    @Test
    void noFooterMapIsEmpty() {
        assertThat(FooterMetadataReader.parse(null)).isSameAs(FileMetadata.EMPTY);
        assertThat(FooterMetadataReader.parse(Map.of())).isSameAs(FileMetadata.EMPTY);
        assertThat(FooterMetadataReader.parseTrees(null, List.of("run", "software_list"))).isEmpty();
        assertThat(FooterMetadataReader.resolve(null, () -> null)).isEqualTo(FileMetadata.EMPTY);
        assertThat(FooterMetadataReader.scanSettings(null, () -> null)).isEmpty();
    }

    /** A blank footer document is left out, as an absent one is. */
    @Test
    void blankFooterDocumentsAreAbsent() {
        Map<String, String> footer = Map.of(
                "software_list", "   ",
                "run", "",
                "sample_list", "[{\"id\": \"footer-sample\"}]");

        assertThat(FooterMetadataReader.parseTrees(footer, List.of("software_list", "run", "sample_list")))
                .containsOnlyKeys("sample_list");
        FileMetadata m = FooterMetadataReader.parse(footer);
        assertThat(m.software()).isEmpty();
        assertThat(m.run()).isNull();
        assertThat(m.samples()).containsExactly(new Sample("footer-sample", null, List.of()));
        assertThat(FooterMetadataReader.scanSettings(null, () -> Map.of("scan_settings_list", " "))).isEmpty();
    }

    /**
     * A footer {@code scan_settings_list} that is not an array reads as empty: the footer documents keep their
     * lenient reader, while the manifest rejects such a list (D-7).
     */
    @Test
    void aFooterScanSettingsListThatIsNotAnArrayIsEmpty() {
        assertThat(FooterMetadataReader.scanSettings(null,
                () -> Map.of("scan_settings_list", "{\"id\": \"settings\"}"))).isEmpty();
    }

    /** An index run that lacks any one field reads the footer once and takes only that field from its run. */
    @ParameterizedTest(name = "without {0}")
    @ValueSource(strings = {"id", "default_instrument_id", "default_data_processing_id", "default_source_file_id"})
    void anIndexRunLackingOneFieldTakesItFromTheFooter(String field) {
        ObjectNode index = fixtureMetadata();
        ((ObjectNode) index.get("run")).remove(field);
        AtomicInteger reads = new AtomicInteger();
        Supplier<Map<String, String>> footer = () -> {
            reads.incrementAndGet();
            return Map.of("run", FOOTER_RUN);
        };

        MsRun run = FooterMetadataReader.resolve(index, footer).run();

        assertThat(reads).hasValue(1);
        MsRun expected = switch (field) {
            case "id" -> new MsRun("footer-run", 0, "pwiz_Reader_Thermo_conversion", "RAW1",
                    "2005-07-20T19:44:22Z", List.of());
            case "default_instrument_id" -> new MsRun("small", 7, "pwiz_Reader_Thermo_conversion", "RAW1",
                    "2005-07-20T19:44:22Z", List.of());
            case "default_data_processing_id" -> new MsRun("small", 0, "footer-dp", "RAW1",
                    "2005-07-20T19:44:22Z", List.of());
            default -> new MsRun("small", 0, "pwiz_Reader_Thermo_conversion", "footer-sf",
                    "2005-07-20T19:44:22Z", List.of());
        };
        assertThat(run).isEqualTo(expected);
        assertThat(FooterMetadataReader.resolve(fixtureMetadata(), footer).run()).isEqualTo(FIXTURE_RUN);
        assertThat(reads).hasValue(1);
    }

    /** A run param list that is not an array is skipped: {@code params} is read instead, else there are none. */
    @Test
    void runParametersThatAreNotAnArrayAreSkipped() {
        String p = "{\"name\": \"b\", \"accession\": null, \"value\": \"x\", \"unit\": null}";

        assertThat(footerRun("{\"parameters\": {\"name\": \"a\"}, \"params\": [" + p + "]}").parameters())
                .containsExactly(new Param("b", null, "x", null));
        assertThat(footerRun("{\"parameters\": \"a\"}").parameters()).isEmpty();
    }

    /** A default instrument id that is not a number is skipped: {@code default_instrument_id} is read instead. */
    @Test
    void anInstrumentIdThatIsNotANumberIsSkipped() {
        assertThat(footerRun("{\"default_instrument_configuration_id\": \"2\", \"default_instrument_id\": 5}")
                .defaultInstrumentId()).isEqualTo(5);
        assertThat(footerRun("{\"default_instrument_configuration_id\": \"2\"}").defaultInstrumentId()).isNull();
    }

    /**
     * Malformed documents and fields read as empty or default: a list document or nested list that is not an array
     * is empty, a param list that is not an array is empty, and an {@code id} or {@code order} that is absent or not
     * a number is 0.
     */
    @Test
    void malformedDocumentsAndFieldsReadAsEmptyOrDefault() {
        Map<String, String> footer = Map.of(
                "software_list", "{\"id\": \"not-a-list\"}",
                "file_description", "{\"contents\": {\"name\": \"x\"}, \"source_files\": {}}",
                "instrument_configuration_list", "[{\"id\": \"seven\", \"components\":"
                        + " [{\"component_type\": \"analyzer\", \"order\": \"2\"}, {\"component_type\": \"detector\"}],"
                        + " \"software_reference\": \"sw\", \"parameters\": {\"name\": \"x\"}}]",
                "data_processing_method_list", "[{\"id\": \"dp\", \"methods\": [{\"software_reference\": \"sw\"}]},"
                        + " {\"id\": \"no-methods\", \"methods\": \"none\"}]");

        FileMetadata m = FooterMetadataReader.parse(footer);

        assertThat(m.software()).isEmpty();
        assertThat(m.fileDescription()).isEqualTo(new FileDescription(List.of(), List.of()));
        assertThat(m.instrumentConfigurations()).containsExactly(new InstrumentConfiguration(0, List.of(
                new Component(ComponentType.ANALYZER, 0, List.of()),
                new Component(ComponentType.DETECTOR, 0, List.of())), "sw", List.of()));
        assertThat(m.dataProcessingMethods()).containsExactly(
                new DataProcessing("dp", List.of(new ProcessingMethod(0, "sw", List.of()))),
                new DataProcessing("no-methods", List.of()));
    }

    /**
     * A footer param value keeps its JSON type without coercion: a boolean or number as such, text as the same
     * {@code String}, and an absent or null value as {@code null}.
     */
    @Test
    void footerParamValuesKeepTheirJsonType() {
        Map<String, String> footer = Map.of("software_list", "[{\"id\": \"sw\", \"version\": \"1\", \"parameters\": ["
                + "{\"name\": \"absent\"},"
                + " {\"name\": \"null\", \"value\": null},"
                + " {\"name\": \"true\", \"value\": true},"
                + " {\"name\": \"false\", \"value\": false},"
                + " {\"name\": \"integer\", \"value\": 3},"
                + " {\"name\": \"double\", \"value\": 0.5},"
                + " {\"name\": \"text\", \"accession\": \"MS:1000529\", \"value\": \"12\", \"unit\": \"UO:1\"}]}]");

        assertThat(FooterMetadataReader.parse(footer).software()).containsExactly(new Software("sw", "1", List.of(
                new Param("absent", null, null, null),
                new Param("null", null, null, null),
                new Param("true", null, true, null),
                new Param("false", null, false, null),
                new Param("integer", null, 3L, null),
                new Param("double", null, 0.5, null),
                new Param("text", "MS:1000529", "12", "UO:1"))));
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** A fresh, editable copy of the {@code metadata} object of the vendored {@code small.unpacked.mzpeak}. */
    private static ObjectNode fixtureMetadata() {
        try (MzPeakSource src = MzPeakSource.open(Fixtures.current("small.unpacked.mzpeak"))) {
            JsonNode metadata = MzPeakManifest.fromSource(src).metadataJson();
            return (ObjectNode) metadata.deepCopy();
        }
    }

    /** The run of a footer that holds only {@code runJson}. */
    private static MsRun footerRun(String runJson) {
        return FooterMetadataReader.parse(Map.of("run", runJson)).run();
    }
}
