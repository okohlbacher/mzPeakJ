package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.parquet.io.InputFile;
import org.mzpeak.io.parquet.ParquetGroups;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the file/run-level metadata JSON from a {@code spectra_metadata.parquet} footer key-value map into a
 * {@link FileMetadata}. Lenient: a missing key yields an empty/absent object; a present-but-malformed key throws.
 */
final class FooterMetadataReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FooterMetadataReader() {
    }

    static FileMetadata read(InputFile metadataFile) {
        return parse(ParquetGroups.footerKeyValue(metadataFile));
    }

    static FileMetadata parse(Map<String, String> kv) {
        if (kv == null || kv.isEmpty()) {
            return FileMetadata.EMPTY;
        }
        return new FileMetadata(
                fileDescription(tree(kv, "file_description")),
                instruments(tree(kv, "instrument_configuration_list")),
                dataProcessing(tree(kv, "data_processing_method_list")),
                samples(tree(kv, "sample_list")),
                software(tree(kv, "software_list")),
                run(tree(kv, "run")));
    }

    private static JsonNode tree(Map<String, String> kv, String key) {
        String json = kv.get(key);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new MzPeakException("Malformed footer metadata JSON for key '" + key + "'", e);
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

    private static MsRun run(JsonNode n) {
        if (n == null) {
            return null;
        }
        return new MsRun(text(n, "id"), intOrNull(n, "default_instrument_id"),
                text(n, "default_data_processing_id"), text(n, "default_source_file_id"),
                text(n, "start_time"), params(n));
    }

    // ---- param + json helpers ---------------------------------------------------------------------

    private static List<Param> params(JsonNode owner) {
        return owner == null ? List.of() : paramsOf(owner.get("parameters"));
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
        return parent == null ? List.of() : asArray(parent.get(field));
    }

    private static Iterable<JsonNode> asArray(JsonNode n) {
        return (n != null && n.isArray()) ? n : List.of();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static int intValue(JsonNode n, String field, int def) {
        JsonNode v = n == null ? null : n.get(field);
        return (v == null || !v.isNumber()) ? def : v.asInt();
    }

    private static Integer intOrNull(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return (v == null || !v.isNumber()) ? null : v.asInt();
    }
}
