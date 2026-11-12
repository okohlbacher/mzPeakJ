package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.model.meta.FileMetadata;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * WR-9 (D-17): the file metadata documents {@link FooterMetadataWriter} writes into the footers and the manifest
 * resolve every reference and carry the keys and types the specification's schemas require ({@link SpecSchemaCheck}).
 *
 * <p>Oracle: the HUPO-PSI example datasets legacy small, current small, has_uv and Example_Processed.img, whose
 * file metadata needs no stub, so it serializes as before WR-9 (pinned by the SHA-256 of the documents).
 */
class RunReferenceConformanceTest {

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
        Map<String, String> footer = FooterMetadataWriter.serialize(fileMetadata(fixture));

        assertThat(digest(footer)).as("%s: %s", name, footer).isEqualTo(sha256);
        assertThat(SpecSchemaCheck.violations(footer)).isEmpty();
        assertThat(SpecSchemaCheck.unresolved(footer)).isEmpty();
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
