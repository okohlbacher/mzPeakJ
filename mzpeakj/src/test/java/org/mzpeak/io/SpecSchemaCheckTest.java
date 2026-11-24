package org.mzpeak.io;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W1: {@link SpecSchemaCheck} against the schemas of spec 963d64a reports a value outside an {@code enum} and a
 * violation of {@code param.json}, which the schemas name by a {@code $ref} to another file of the folder; the resolver
 * reads the current run key {@code default_instrument_configuration_id}, and the packed layout's
 * {@code default_instrument_id} when only that is present.
 */
class SpecSchemaCheckTest {

    private static final String SOFTWARE = "[{\"id\":\"sw\",\"version\":\"1\",\"parameters\":[]}]";

    @Test
    void aValueOutsideAnEnumIsReported() {
        Map<String, String> footer = Map.of("instrument_configuration_list", "[{\"id\":0,\"software_reference\":"
                + "\"sw\",\"parameters\":[],\"components\":[{\"component_type\":\"pump\",\"order\":1,"
                + "\"parameters\":[]},{\"component_type\":\"analyzer\",\"order\":2,\"parameters\":[]}]}]");

        assertThat(SpecSchemaCheck.violations(footer)).containsExactly(
                "instrument_configuration_list[0].components[0].component_type: \"pump\" is not one of"
                        + " [\"ionsource\",\"analyzer\",\"detector\"]");
    }

    @Test
    void aParamJsonViolationIsReported() {
        Map<String, String> footer = Map.of("software_list", "[{\"id\":\"sw\",\"version\":\"1\",\"parameters\":["
                + "{\"accession\":\"MS:1000799\",\"value\":\"x\"},{\"name\":7},{\"name\":\"ok\",\"value\":{}},"
                + "{\"name\":\"fine\",\"accession\":null,\"value\":[1],\"unit\":null}]}]");

        assertThat(SpecSchemaCheck.violations(footer)).containsExactly(
                "software_list[0].parameters[0]: required key name is missing",
                "software_list[0].parameters[1].name: NUMBER 7 is not of type \"string\"",
                "software_list[0].parameters[2].value: OBJECT {} is not of type"
                        + " [\"number\",\"integer\",\"string\",\"boolean\",\"null\",\"array\"]");
    }

    /**
     * A string that has no match of the {@code pattern} of its schema is reported: an array unit that is not a CURIE
     * (array_index.json) and a param accession that is not one (param.json); a CURIE, a null and a param unit, which
     * has no pattern (D13-26), are not.
     */
    @Test
    void aStringWithoutAMatchOfItsPatternIsReported() {
        String entry = "{\"array_name\":\"intensity\",\"buffer_format\":\"point\",\"context\":\"spectrum\","
                + "\"path\":\"point.intensity\",\"data_type\":\"MS:1000523\",\"array_type\":\"MS:1000515\","
                + "\"unit\":%s}";
        String index = "{\"prefix\":\"point\",\"entries\":[" + String.format(entry, "\"percent\"") + ","
                + String.format(entry, "\"MS:1000132\"") + "," + String.format(entry, "null") + "]}";

        assertThat(SpecSchemaCheck.violations("index", "array_index.json", index)).containsExactly(
                "index.entries[0].unit: \"percent\" does not match the pattern \\S+:\\S+");
        Map<String, String> footer = Map.of("software_list", "[{\"id\":\"sw\",\"version\":\"1\",\"parameters\":["
                + "{\"name\":\"a\",\"accession\":\"nocurie\"},{\"name\":\"b\",\"unit\":\"seconds\"}]}]");
        assertThat(SpecSchemaCheck.violations(footer)).containsExactly(
                "software_list[0].parameters[0].accession: \"nocurie\" does not match the pattern \\S+:\\S+");
    }

    @Test
    void theNewlyRequiredSoftwareAndDataProcessingKeysAreReported() {
        Map<String, String> footer = Map.of("software_list", "[{\"version\":\"1\"}]",
                "data_processing_method_list", "[{\"methods\":[{\"order\":1}]}]");

        assertThat(SpecSchemaCheck.violations(footer)).containsExactlyInAnyOrder(
                "software_list[0]: required key id is missing",
                "software_list[0]: required key parameters is missing",
                "data_processing_method_list[0]: required key id is missing",
                "data_processing_method_list[0].methods[0]: required key software_reference is missing",
                "data_processing_method_list[0].methods[0]: required key parameters is missing");
    }

    @Test
    void theResolverReadsTheCurrentRunKeyElseThePackedOne() {
        String configurations = "[{\"id\":0,\"software_reference\":\"sw\",\"components\":[],\"parameters\":[]}]";
        String rest = ",\"default_data_processing_id\":\"dp\",\"default_source_file_id\":\"sf\"}";
        Map<String, String> others = Map.of("software_list", SOFTWARE,
                "instrument_configuration_list", configurations,
                "data_processing_method_list", "[{\"id\":\"dp\",\"methods\":[]}]",
                "file_description", "{\"contents\":[],\"source_files\":[{\"id\":\"sf\",\"name\":\"n\","
                        + "\"location\":\"file:///n\",\"parameters\":[]}]}");

        assertThat(SpecSchemaCheck.unresolved(with(others, "{\"id\":\"r\",\"default_instrument_configuration_id\":0"
                + rest))).isEmpty();
        assertThat(SpecSchemaCheck.unresolved(with(others, "{\"id\":\"r\",\"default_instrument_id\":0" + rest)))
                .isEmpty();
        assertThat(SpecSchemaCheck.unresolved(with(others, "{\"id\":\"r\",\"default_instrument_configuration_id\":2,"
                + "\"default_instrument_id\":0" + rest))).containsExactly(
                "run default_instrument_configuration_id 2 names no instrument configuration of [0]");
        assertThat(SpecSchemaCheck.unresolved(with(others, "{\"id\":\"r\",\"default_instrument_id\":3" + rest)))
                .containsExactly("run default_instrument_id 3 names no instrument configuration of [0]");
        assertThat(SpecSchemaCheck.unresolved(with(others, "{\"id\":\"r\"" + rest))).containsExactly(
                "run default_instrument_configuration_id null names no instrument configuration of [0]");
    }

    private static Map<String, String> with(Map<String, String> others, String run) {
        Map<String, String> footer = new HashMap<>(others);
        footer.put("run", run);
        return footer;
    }
}
