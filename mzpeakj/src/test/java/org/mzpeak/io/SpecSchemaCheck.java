package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mzpeak.io.parquet.ParquetGroups;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks the file metadata documents mzPeakJ writes (WR-9, D-17) against verbatim copies of the specification's JSON
 * schemas in {@code src/test/resources/spec-schema/} ({@code SOURCE.txt} names the specification commit), and checks
 * that every reference between the documents resolves.
 *
 * <p>The schema check is a small subset of JSON Schema, enough for these five schemas and without a dependency: at
 * every node it follows a local {@code $ref} ({@code #/definitions/...}), checks the {@code type} (a name or a list of
 * names: {@code integer}, {@code number}, {@code string}, {@code boolean}, {@code null}, {@code array},
 * {@code object}), that every key of {@code required} is present, every present key of {@code properties} against its
 * schema and every element of an array against {@code items}. A {@code $ref} to another file (the specification's
 * {@code param.json}) is not followed.
 *
 * <p>The resolver checks, when the documents hold a run, that its {@code default_instrument_id} names a configuration
 * of {@code instrument_configuration_list}, its {@code default_data_processing_id} an entry of
 * {@code data_processing_method_list} and its {@code default_source_file_id} a source file of
 * {@code file_description}; and that every {@code software_reference} of a configuration or a processing method names
 * a software of {@code software_list}.
 */
final class SpecSchemaCheck {

    /** The footer key of each document and the schema file it is checked against. */
    static final Map<String, String> SCHEMAS = schemas();

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INDEX = "mzpeak_index.json";

    private SpecSchemaCheck() {
    }

    private static Map<String, String> schemas() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("run", "ms_run.json");
        m.put("software_list", "software.json");
        m.put("data_processing_method_list", "data_processing.json");
        m.put("instrument_configuration_list", "instrument_configuration.json");
        m.put("file_description", "file_description.json");
        return m;
    }

    /** Every schema violation of the documents in {@code footer} that have a schema; empty when none. */
    static List<String> violations(Map<String, String> footer) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : SCHEMAS.entrySet()) {
            String document = footer.get(e.getKey());
            if (document != null) {
                JsonNode root = schema(e.getValue());
                check(root, root, parse(document), e.getKey(), out);
            }
        }
        return out;
    }

    /** Every reference of the documents in {@code footer} that names nothing; empty when all resolve. */
    static List<String> unresolved(Map<String, String> footer) {
        List<String> out = new ArrayList<>();
        Set<String> software = ids(footer.get("software_list"), "id");
        for (JsonNode configuration : parseOrEmpty(footer.get("instrument_configuration_list"))) {
            reference(configuration.get("software_reference"), software,
                    "instrument configuration " + configuration.get("id") + " software_reference", out);
        }
        for (JsonNode processing : parseOrEmpty(footer.get("data_processing_method_list"))) {
            for (JsonNode method : processing.path("methods")) {
                reference(method.get("software_reference"), software,
                        "data processing " + processing.get("id") + " method software_reference", out);
            }
        }
        String runDocument = footer.get("run");
        if (runDocument != null) {
            JsonNode run = parse(runDocument);
            JsonNode instrument = run.get("default_instrument_id");
            Set<String> configurations = ids(footer.get("instrument_configuration_list"), "id");
            if (instrument == null || !instrument.isIntegralNumber()
                    || !configurations.contains(instrument.asText())) {
                out.add("run default_instrument_id " + instrument + " names no instrument configuration of "
                        + configurations);
            }
            requiredReference(run.get("default_data_processing_id"),
                    ids(footer.get("data_processing_method_list"), "id"), "run default_data_processing_id", out);
            String description = footer.get("file_description");
            Set<String> sources = description == null ? Set.of()
                    : ids(parse(description).path("source_files").toString(), "id");
            requiredReference(run.get("default_source_file_id"), sources, "run default_source_file_id", out);
        }
        return out;
    }

    /**
     * The file metadata documents of every place {@code dataset} (a directory or an archive) stores them: the manifest's
     * {@code metadata} object (key {@code mzpeak_index.json}) and the key-value metadata of each Parquet member the
     * manifest lists (key: the member name), each restricted to the keys of {@link #SCHEMAS}. Places that hold none of
     * them are left out.
     */
    static Map<String, Map<String, String>> documents(Path dataset) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            JsonNode manifest = MAPPER.readTree(source.readManifestBytes());
            Map<String, String> fromManifest = new LinkedHashMap<>();
            for (String key : SCHEMAS.keySet()) {
                JsonNode document = manifest.path("metadata").get(key);
                if (document != null && !document.isNull()) {
                    fromManifest.put(key, document.toString());
                }
            }
            putIfAny(out, INDEX, fromManifest);
            for (JsonNode file : manifest.path("files")) {
                String name = file.path("name").asText();
                Map<String, String> kv = ParquetGroups.footerKeyValue(source.inputFile(name));
                Map<String, String> documents = new LinkedHashMap<>();
                for (String key : SCHEMAS.keySet()) {
                    if (kv.containsKey(key)) {
                        documents.put(key, kv.get(key));
                    }
                }
                putIfAny(out, name, documents);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /**
     * The places of {@code dataset} that hold the file metadata documents when it is written by mzPeakJ, in the order
     * of {@link #documents}: the manifest and each member it lists whose name starts with {@code spectra_metadata} or
     * {@code wavelength_spectra_metadata}. The writers give the file metadata to the footers of the spectrum and
     * wavelength spectrum metadata members, not to the chromatogram metadata or signal members.
     */
    static List<String> fileMetadataPlaces(Path dataset) {
        List<String> out = new ArrayList<>(List.of(INDEX));
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            for (JsonNode file : MAPPER.readTree(source.readManifestBytes()).path("files")) {
                String name = file.path("name").asText();
                if (name.startsWith("spectra_metadata") || name.startsWith("wavelength_spectra_metadata")) {
                    out.add(name);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static void putIfAny(Map<String, Map<String, String>> out, String place, Map<String, String> documents) {
        if (!documents.isEmpty()) {
            out.put(place, documents);
        }
    }

    private static void reference(JsonNode ref, Set<String> targets, String what, List<String> out) {
        if (ref != null && !(ref.isTextual() && targets.contains(ref.asText()))) {
            out.add(what + " " + ref + " names nothing of " + targets);
        }
    }

    private static void requiredReference(JsonNode ref, Set<String> targets, String what, List<String> out) {
        if (ref == null) {
            out.add(what + " is missing");
        } else {
            reference(ref, targets, what, out);
        }
    }

    /** The {@code key} values of the objects of the JSON array {@code document} as text; empty for {@code null}. */
    private static Set<String> ids(String document, String key) {
        Set<String> out = new HashSet<>();
        for (JsonNode n : parseOrEmpty(document)) {
            JsonNode id = n.get(key);
            if (id != null && !id.isNull()) {
                out.add(id.asText());
            }
        }
        return out;
    }

    private static JsonNode parseOrEmpty(String document) {
        return document == null ? MAPPER.createArrayNode() : parse(document);
    }

    private static void check(JsonNode root, JsonNode schema, JsonNode value, String path, List<String> out) {
        JsonNode ref = schema.get("$ref");
        if (ref != null) {
            String target = ref.asText();
            if (!target.startsWith("#/")) {
                return;
            }
            JsonNode resolved = root.at(target.substring(1));
            if (resolved.isMissingNode()) {
                throw new IllegalStateException("unresolvable $ref " + target);
            }
            check(root, resolved, value, path, out);
            return;
        }
        JsonNode type = schema.get("type");
        if (type != null && !hasType(type, value)) {
            out.add(path + ": " + value.getNodeType() + " " + value + " is not of type " + type);
            return;
        }
        if (value.isObject()) {
            for (JsonNode required : schema.path("required")) {
                if (!value.has(required.asText())) {
                    out.add(path + ": required key " + required.asText() + " is missing");
                }
            }
            for (Map.Entry<String, JsonNode> property : schema.path("properties").properties()) {
                JsonNode present = value.get(property.getKey());
                if (present != null) {
                    check(root, property.getValue(), present, path + "." + property.getKey(), out);
                }
            }
        } else if (value.isArray() && schema.has("items")) {
            for (int i = 0; i < value.size(); i++) {
                check(root, schema.get("items"), value.get(i), path + "[" + i + "]", out);
            }
        }
    }

    private static boolean hasType(JsonNode type, JsonNode value) {
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (hasType(t, value)) {
                    return true;
                }
            }
            return false;
        }
        return switch (type.asText()) {
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "string" -> value.isTextual();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> throw new IllegalStateException("unknown schema type " + type);
        };
    }

    /**
     * The schema {@code file} of {@code spec-schema/}, next to the fixture directories (as {@link Fixtures} finds them,
     * also from the converter's tests, whose test jar of the library holds classes only).
     */
    private static JsonNode schema(String file) {
        try {
            return MAPPER.readTree(Fixtures.CURRENT.resolveSibling("spec-schema").resolve(file).toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode parse(String document) {
        try {
            return MAPPER.readTree(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
