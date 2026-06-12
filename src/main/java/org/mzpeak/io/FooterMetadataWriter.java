package org.mzpeak.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.Component;
import org.mzpeak.model.meta.FileMetadata.DataProcessing;
import org.mzpeak.model.meta.FileMetadata.InstrumentConfiguration;
import org.mzpeak.model.Param;
import org.mzpeak.model.meta.FileMetadata.ProcessingMethod;
import org.mzpeak.model.meta.FileMetadata.Sample;
import org.mzpeak.model.meta.FileMetadata.Software;
import org.mzpeak.model.meta.FileMetadata.SourceFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes a {@link FileMetadata} back into the per-document JSON strings stored in the Parquet footer
 * key-value metadata (symmetric to {@link FooterMetadataReader}); the result is passed to the writer's
 * {@code withExtraMetaData}. Only non-empty documents are emitted.
 */
final class FooterMetadataWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FooterMetadataWriter() {
    }

    static Map<String, String> serialize(FileMetadata meta) {
        Map<String, String> kv = new LinkedHashMap<>();
        if (meta == null) {
            return kv;
        }
        // Pre-compute effective required-field ids so run can reference them and the synthetic
        // sentinel entries are added to the respective lists consistently.
        // default_data_processing_id: required by mzPeak spec
        String effectiveDpId = null;
        // default_source_file_id: required by mzPeak spec
        String effectiveSfId = null;
        if (meta.run() != null) {
            effectiveDpId = meta.run().defaultDataProcessingId();
            if (effectiveDpId == null && !meta.dataProcessingMethods().isEmpty()) {
                effectiveDpId = meta.dataProcessingMethods().get(0).id();
            }
            if (effectiveDpId == null) {
                effectiveDpId = "mzpeakj_export"; // sentinel; corresponding DP entry synthesised below
            }
            effectiveSfId = meta.run().defaultSourceFileId();
            boolean hasSources = meta.fileDescription() != null
                    && !meta.fileDescription().sourceFiles().isEmpty();
            if (effectiveSfId == null && hasSources) {
                effectiveSfId = meta.fileDescription().sourceFiles().get(0).id();
            }
            if (effectiveSfId == null) {
                effectiveSfId = "mzpeakj_source"; // sentinel; corresponding source entry synthesised below
            }
        }
        if (meta.run() != null) {
            ObjectNode run = MAPPER.createObjectNode();
            putText(run, "id", meta.run().id());
            if (meta.run().defaultInstrumentId() != null) {
                run.put("default_instrument_id", meta.run().defaultInstrumentId());
            }
            putText(run, "default_data_processing_id", effectiveDpId);
            putText(run, "default_source_file_id", effectiveSfId);
            putText(run, "start_time", meta.run().startTime());
            putParams(run, meta.run().parameters());
            kv.put("run", run.toString());
        }
        if (!meta.software().isEmpty()) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (Software s : meta.software()) {
                ObjectNode n = arr.addObject();
                putText(n, "id", s.id());
                putText(n, "version", s.version());
                putParams(n, s.parameters());
            }
            kv.put("software_list", arr.toString());
        }
        if (!meta.instrumentConfigurations().isEmpty()) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (InstrumentConfiguration ic : meta.instrumentConfigurations()) {
                ObjectNode n = arr.addObject();
                n.put("id", ic.id());
                putText(n, "software_reference", ic.softwareReference());
                ArrayNode comps = n.putArray("components");
                for (Component c : ic.components()) {
                    ObjectNode cn = comps.addObject();
                    cn.put("component_type", componentTypeJson(c.componentType()));
                    cn.put("order", c.order());
                    putParams(cn, c.parameters());
                }
                putParams(n, ic.parameters());
            }
            kv.put("instrument_configuration_list", arr.toString());
        }
        // Always emit a data_processing_method_list; if none came from the source, synthesise
        // a minimal sentinel entry so the run's default_data_processing_id is satisfiable.
        {
            ArrayNode arr = MAPPER.createArrayNode();
            if (!meta.dataProcessingMethods().isEmpty()) {
                for (DataProcessing dp : meta.dataProcessingMethods()) {
                    ObjectNode n = arr.addObject();
                    putText(n, "id", dp.id());
                    ArrayNode methods = n.putArray("methods");
                    for (ProcessingMethod m : dp.methods()) {
                        ObjectNode mn = methods.addObject();
                        mn.put("order", m.order());
                        putText(mn, "software_reference", m.softwareReference());
                        putParams(mn, m.parameters());
                    }
                }
            } else if (meta.run() != null) {
                // Sentinel: the run references "mzpeakj_export" so we must declare it.
                ObjectNode n = arr.addObject();
                n.put("id", "mzpeakj_export");
                n.putArray("methods");
            }
            if (arr.size() > 0) {
                kv.put("data_processing_method_list", arr.toString());
            }
        }
        if (!meta.samples().isEmpty()) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (Sample s : meta.samples()) {
                ObjectNode n = arr.addObject();
                putText(n, "id", s.id());
                putText(n, "name", s.name());
                putParams(n, s.parameters());
            }
            kv.put("sample_list", arr.toString());
        }
        if (meta.fileDescription() != null) {
            ObjectNode fd = MAPPER.createObjectNode();
            putParamsArray(fd, "contents", meta.fileDescription().contents());
            ArrayNode sources = fd.putArray("source_files");
            for (SourceFile sf : meta.fileDescription().sourceFiles()) {
                ObjectNode sn = sources.addObject();
                putText(sn, "id", sf.id());
                putText(sn, "name", sf.name());
                putText(sn, "location", sf.location());
                putParams(sn, sf.parameters());
            }
            // If the sentinel source was used (no real source files), add a minimal stub so the
            // run's default_source_file_id reference is satisfiable by the validator.
            if ("mzpeakj_source".equals(effectiveSfId) && meta.fileDescription().sourceFiles().isEmpty()) {
                ObjectNode sn = sources.addObject();
                sn.put("id", "mzpeakj_source");
                sn.put("name", "unknown");
                sn.put("location", "file:///unknown");
                sn.putArray("parameters");
            }
            kv.put("file_description", fd.toString());
        }
        return kv;
    }

    private static void putParams(ObjectNode owner, List<Param> params) {
        putParamsArray(owner, "parameters", params);
    }

    private static void putParamsArray(ObjectNode owner, String field, List<Param> params) {
        ArrayNode arr = owner.putArray(field);
        if (params == null) {
            return;
        }
        for (Param p : params) {
            ObjectNode pn = arr.addObject();
            putText(pn, "name", p.name());
            putText(pn, "accession", p.accession());
            Object v = p.value();
            if (v == null) {
                pn.putNull("value");
            } else if (v instanceof Boolean b) {
                pn.put("value", b);
            } else if (v instanceof Long l) {
                pn.put("value", l);
            } else if (v instanceof Integer i) {
                pn.put("value", i.longValue());
            } else if (v instanceof Double d) {
                pn.put("value", d);
            } else {
                pn.put("value", v.toString());
            }
            putText(pn, "unit", p.unit());
        }
    }

    private static void putText(ObjectNode n, String field, String value) {
        if (value != null) {
            n.put(field, value);
        }
    }

    private static String componentTypeJson(FileMetadata.ComponentType t) {
        return switch (t) {
            case ION_SOURCE -> "ionsource";
            case ANALYZER -> "analyzer";
            case DETECTOR -> "detector";
            case UNKNOWN -> "unknown";
        };
    }
}
