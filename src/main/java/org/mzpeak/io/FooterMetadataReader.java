package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.Component;
import org.mzpeak.model.meta.FileMetadata.ComponentType;
import org.mzpeak.model.meta.FileMetadata.DataProcessing;
import org.mzpeak.model.meta.FileMetadata.FileDescription;
import org.mzpeak.model.meta.FileMetadata.InstrumentConfiguration;
import org.mzpeak.model.meta.FileMetadata.MsRun;
import org.mzpeak.model.Param;
import org.mzpeak.model.meta.FileMetadata.ProcessingMethod;
import org.mzpeak.model.meta.FileMetadata.Sample;
import org.mzpeak.model.meta.FileMetadata.SourceFile;
import org.mzpeak.model.meta.FileMetadata.Software;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Reads the file/run-level metadata documents ({@code file_description}, {@code instrument_configuration_list},
 * {@code data_processing_method_list}, {@code sample_list}, {@code software_list}, {@code run} and
 * {@code scan_settings_list}) into a {@link FileMetadata} and a scan-settings list.
 *
 * <p>The manifest's {@code metadata} object comes first, as the spec requires. The spectrum metadata member's footer
 * key-value map, where writers also put the documents (and some writers only there), is read lazily and only for
 * documents the manifest lacks; a malformed footer document the manifest supplies is ignored. The {@code run} is
 * merged field by field, manifest first. Lenient: a missing document yields an empty/absent object; a malformed
 * footer document that is needed throws.
 */
final class FooterMetadataReader {

    private static final String FILE_DESCRIPTION = "file_description";
    private static final String INSTRUMENT_CONFIGURATION_LIST = "instrument_configuration_list";
    private static final String DATA_PROCESSING_METHOD_LIST = "data_processing_method_list";
    private static final String SAMPLE_LIST = "sample_list";
    private static final String SOFTWARE_LIST = "software_list";
    private static final String RUN = "run";
    private static final String SCAN_SETTINGS_LIST = "scan_settings_list";

    /** The documents of a {@link FileMetadata}. */
    private static final List<String> FILE_DOCUMENTS = List.of(FILE_DESCRIPTION, INSTRUMENT_CONFIGURATION_LIST,
            DATA_PROCESSING_METHOD_LIST, SAMPLE_LIST, SOFTWARE_LIST, RUN);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FooterMetadataReader() {
    }

    /** The file metadata of a footer key-value map alone. */
    static FileMetadata parse(Map<String, String> kv) {
        if (kv == null || kv.isEmpty()) {
            return FileMetadata.EMPTY;
        }
        return resolve(null, () -> kv);
    }

    /**
     * The file metadata, each document from {@code indexMetadata} (the manifest's {@code metadata} object, may be
     * {@code null}) when it holds the document, else from the footer. {@code footer} is called at most once, and
     * only when a document is missing from the index or the index's {@code run} lacks a field.
     *
     * <p>The {@code run} is merged field by field, index first. The default instrument is read from
     * {@code default_instrument_configuration_id}, then {@code default_instrument_id}; the params from
     * {@code parameters}, else {@code params} (param.json-shaped items).
     *
     * @throws MzPeakException if a footer document the index lacks is not valid JSON
     */
    static FileMetadata resolve(JsonNode indexMetadata, Supplier<Map<String, String>> footer) {
        Map<String, JsonNode> trees = new HashMap<>();
        List<String> lacking = new ArrayList<>();
        for (String key : FILE_DOCUMENTS) {
            JsonNode document = indexDocument(indexMetadata, key);
            if (document == null) {
                lacking.add(key);
            } else {
                trees.put(key, document);
            }
        }
        JsonNode indexRun = trees.get(RUN);
        boolean mergeRun = indexRun != null && !complete(indexRun);
        JsonNode footerRun = null;
        if (!lacking.isEmpty() || mergeRun) {
            Map<String, String> kv = footer.get();
            trees.putAll(parseTrees(kv, lacking));
            if (mergeRun) {
                footerRun = parseIgnoringErrors(kv, RUN);
            }
        }
        return new FileMetadata(
                fileDescription(trees.get(FILE_DESCRIPTION)),
                instruments(trees.get(INSTRUMENT_CONFIGURATION_LIST)),
                dataProcessing(trees.get(DATA_PROCESSING_METHOD_LIST)),
                samples(trees.get(SAMPLE_LIST)),
                software(trees.get(SOFTWARE_LIST)),
                run(trees.get(RUN), footerRun));
    }

    /**
     * The scan settings: the index's {@code scan_settings_list} when it has one (even an empty one), else the
     * footer's. Parsed by {@link MzPeakManifest#parseScanSettings}, so a param's unit is read from {@code unit},
     * falling back to {@code unit_accession}. {@code footer} is called only when the index lacks the document.
     *
     * @throws MzPeakException if the footer document is needed and is not valid JSON
     */
    static List<ScanSettings> scanSettings(JsonNode indexMetadata, Supplier<Map<String, String>> footer) {
        JsonNode list = indexDocument(indexMetadata, SCAN_SETTINGS_LIST);
        if (list == null) {
            list = parseTrees(footer.get(), List.of(SCAN_SETTINGS_LIST)).get(SCAN_SETTINGS_LIST);
        }
        return List.copyOf(MzPeakManifest.parseScanSettings(list));
    }

    /**
     * The JSON trees of the footer documents {@code keys}; a document that is absent or blank is left out.
     *
     * @throws MzPeakException naming the key, if a document is not valid JSON
     */
    static Map<String, JsonNode> parseTrees(Map<String, String> kv, Collection<String> keys) {
        Map<String, JsonNode> out = new HashMap<>();
        for (String key : keys) {
            JsonNode tree = tree(kv, key);
            if (tree != null) {
                out.put(key, tree);
            }
        }
        return out;
    }

    /** The index's document {@code key}, or {@code null} if the index is absent or has none (or JSON null). */
    private static JsonNode indexDocument(JsonNode indexMetadata, String key) {
        JsonNode v = indexMetadata == null ? null : indexMetadata.get(key);
        return v == null || v.isNull() ? null : v;
    }

    private static JsonNode tree(Map<String, String> kv, String key) {
        String json = kv == null ? null : kv.get(key);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new MzPeakException("Malformed footer metadata JSON for key '" + key + "'", e);
        }
    }

    /** The footer document {@code key}, or {@code null} if it is absent, blank or not valid JSON. */
    private static JsonNode parseIgnoringErrors(Map<String, String> kv, String key) {
        try {
            return tree(kv, key);
        } catch (MzPeakException e) {
            return null;
        }
    }

    private static FileDescription fileDescription(JsonNode n) {
        if (n == null) {
            return null;
        }
        List<SourceFile> sources = new ArrayList<>();
        for (JsonNode sf : array(n, "source_files")) {
            sources.add(new SourceFile(text(sf, "id"), text(sf, "name"), text(sf, "location"), params(sf)));
        }
        return new FileDescription(paramsOf(n.get("contents")), sources);
    }

    private static List<InstrumentConfiguration> instruments(JsonNode n) {
        List<InstrumentConfiguration> out = new ArrayList<>();
        for (JsonNode ic : asArray(n)) {
            List<Component> components = new ArrayList<>();
            for (JsonNode c : array(ic, "components")) {
                components.add(new Component(
                        ComponentType.parse(text(c, "component_type")), intValue(c, "order", 0), params(c)));
            }
            out.add(new InstrumentConfiguration(
                    intValue(ic, "id", 0), components, text(ic, "software_reference"), params(ic)));
        }
        return out;
    }

    private static List<DataProcessing> dataProcessing(JsonNode n) {
        List<DataProcessing> out = new ArrayList<>();
        for (JsonNode dp : asArray(n)) {
            List<ProcessingMethod> methods = new ArrayList<>();
            for (JsonNode m : array(dp, "methods")) {
                methods.add(new ProcessingMethod(
                        intValue(m, "order", 0), text(m, "software_reference"), params(m)));
            }
            out.add(new DataProcessing(text(dp, "id"), methods));
        }
        return out;
    }

    private static List<Sample> samples(JsonNode n) {
        List<Sample> out = new ArrayList<>();
        for (JsonNode s : asArray(n)) {
            out.add(new Sample(text(s, "id"), text(s, "name"), params(s)));
        }
        return out;
    }

    private static List<Software> software(JsonNode n) {
        List<Software> out = new ArrayList<>();
        for (JsonNode s : asArray(n)) {
            out.add(new Software(text(s, "id"), text(s, "version"), params(s)));
        }
        return out;
    }

    /** The run of {@code primary}, each field missing there taken from {@code fallback} (may be {@code null}). */
    private static MsRun run(JsonNode primary, JsonNode fallback) {
        if (primary == null) {
            return null;
        }
        List<JsonNode> sources = fallback == null ? List.of(primary) : List.of(primary, fallback);
        List<Param> parameters = first(sources, FooterMetadataReader::runParams);
        return new MsRun(first(sources, n -> text(n, "id")), first(sources, FooterMetadataReader::instrumentId),
                first(sources, n -> text(n, "default_data_processing_id")),
                first(sources, n -> text(n, "default_source_file_id")),
                first(sources, n -> text(n, "start_time")), parameters == null ? List.of() : parameters);
    }

    /** True if the run has every field, so no footer run is needed. */
    private static boolean complete(JsonNode run) {
        return text(run, "id") != null && instrumentId(run) != null
                && text(run, "default_data_processing_id") != null && text(run, "default_source_file_id") != null
                && text(run, "start_time") != null && runParams(run) != null;
    }

    /** {@code default_instrument_configuration_id} (reference spelling), else {@code default_instrument_id}. */
    private static Integer instrumentId(JsonNode run) {
        Integer id = intOrNull(run, "default_instrument_configuration_id");
        return id != null ? id : intOrNull(run, "default_instrument_id");
    }

    /** The {@code parameters} array, else the {@code params} array; {@code null} if the run has neither. */
    private static List<Param> runParams(JsonNode run) {
        for (String field : List.of("parameters", "params")) {
            JsonNode arr = run.get(field);
            if (arr != null && arr.isArray()) {
                return paramsOf(arr);
            }
        }
        return null;
    }

    /** The first non-null result of {@code read} over {@code sources}, in order. */
    private static <T> T first(List<JsonNode> sources, Function<JsonNode, T> read) {
        for (JsonNode n : sources) {
            T value = read.apply(n);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    // ---- param + json helpers ---------------------------------------------------------------------

    private static List<Param> params(JsonNode owner) {
        return paramsOf(owner.get("parameters"));
    }

    private static List<Param> paramsOf(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<Param> out = new ArrayList<>();
        for (JsonNode p : arr) {
            out.add(new Param(text(p, "name"), text(p, "accession"), value(p.get("value")), text(p, "unit")));
        }
        return out;
    }

    private static Object value(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isIntegralNumber()) {
            return v.asLong();
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        return v.asText();
    }

    private static Iterable<JsonNode> array(JsonNode parent, String field) {
        return asArray(parent.get(field));
    }

    private static Iterable<JsonNode> asArray(JsonNode n) {
        return (n != null && n.isArray()) ? n : List.of();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static int intValue(JsonNode n, String field, int def) {
        JsonNode v = n.get(field);
        return (v == null || !v.isNumber()) ? def : v.asInt();
    }

    private static Integer intOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || !v.isNumber()) ? null : v.asInt();
    }
}
