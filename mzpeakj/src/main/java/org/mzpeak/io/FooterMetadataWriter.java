package org.mzpeak.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.Component;
import org.mzpeak.model.meta.FileMetadata.Contact;
import org.mzpeak.model.meta.FileMetadata.DataProcessing;
import org.mzpeak.model.meta.FileMetadata.FileDescription;
import org.mzpeak.model.meta.FileMetadata.InstrumentConfiguration;
import org.mzpeak.model.meta.FileMetadata.MsRun;
import org.mzpeak.model.Param;
import org.mzpeak.model.meta.FileMetadata.ProcessingMethod;
import org.mzpeak.model.meta.FileMetadata.Sample;
import org.mzpeak.model.meta.FileMetadata.Software;
import org.mzpeak.model.meta.FileMetadata.SourceFile;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serializes a {@link FileMetadata} back into the per-document JSON strings stored in the Parquet footer
 * key-value metadata (symmetric to {@link FooterMetadataReader}); the result is passed to the writer's
 * {@code withExtraMetaData}, and the manifest's {@code metadata} holds the same documents. Only non-empty documents
 * are emitted.
 *
 * <p>Every reference between the documents resolves, and the keys the specification's schemas require are present
 * (WR-9, D-17); what is missing is synthesized, never refused:
 * <ul>
 *   <li>the run's id defaults to {@code mzpeakj_run}; its default instrument to the first instrument configuration,
 *       its default data processing to the first data processing, its default source file to the first source file;
 *       without such an entity to {@code 0}, {@code mzpeakj_export} and {@code mzpeakj_source};
 *   <li>a run default, or a {@code software_reference} of an instrument configuration or a processing method, that
 *       names no entity of its list gets a stub entity of that id at the end of the list: an instrument configuration
 *       without components and params, a data processing without methods, a source file {@code unknown} at
 *       {@code file:///unknown} without params (with a file description holding only it when the metadata has none),
 *       a software with an empty version and no params;
 *   <li>an instrument configuration without a software (a stub one included) names the first configuration's
 *       software, else the first software, else the stub software {@code mzpeakj_software};
 *   <li>a software without a version is written with the empty version.
 * </ul>
 * Each stub is logged once as a WARNING naming its document, its id and the reference that names it.
 */
final class FooterMetadataWriter {

    private static final System.Logger LOG = System.getLogger(FooterMetadataWriter.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RUN_ID = "mzpeakj_run";
    private static final String DATA_PROCESSING_ID = "mzpeakj_export";
    private static final String SOURCE_FILE_ID = "mzpeakj_source";
    private static final String SOFTWARE_ID = "mzpeakj_software";

    private FooterMetadataWriter() {
    }

    static Map<String, String> serialize(FileMetadata meta) {
        Map<String, String> kv = new LinkedHashMap<>();
        if (meta == null) {
            return kv;
        }
        List<Software> software = new ArrayList<>(meta.software());
        Set<String> softwareIds = new HashSet<>();
        software.forEach(s -> softwareIds.add(s.id()));
        List<InstrumentConfiguration> configurations = new ArrayList<>(meta.instrumentConfigurations());
        List<DataProcessing> processing = new ArrayList<>(meta.dataProcessingMethods());
        FileDescription description = meta.fileDescription();
        List<SourceFile> sources = new ArrayList<>(description == null ? List.of() : description.sourceFiles());
        MsRun run = meta.run();
        ObjectNode runJson = null;
        if (run != null) {
            int instrumentId = run.defaultInstrumentId() != null ? run.defaultInstrumentId()
                    : configurations.isEmpty() ? 0 : configurations.get(0).id();
            if (configurations.stream().noneMatch(ic -> ic.id() == instrumentId)) {
                configurations.add(new InstrumentConfiguration(instrumentId, List.of(), null, List.of()));
                warnStub("instrument_configuration_list", "instrument configuration " + instrumentId,
                        "run.default_instrument_id");
            }
            // default_data_processing_id: required by mzPeak spec
            String dpId = run.defaultDataProcessingId();
            if (dpId == null && !processing.isEmpty()) {
                dpId = processing.get(0).id();
            }
            if (dpId == null) {
                dpId = DATA_PROCESSING_ID;
            }
            String effectiveDpId = dpId;
            if (processing.stream().noneMatch(dp -> effectiveDpId.equals(dp.id()))) {
                processing.add(new DataProcessing(effectiveDpId, List.of()));
                warnStub("data_processing_method_list", "data processing " + effectiveDpId,
                        "run.default_data_processing_id");
            }
            // default_source_file_id: required by mzPeak spec
            String sfId = run.defaultSourceFileId();
            if (sfId == null && !sources.isEmpty()) {
                sfId = sources.get(0).id();
            }
            if (sfId == null) {
                sfId = SOURCE_FILE_ID;
            }
            String effectiveSfId = sfId;
            if (sources.stream().noneMatch(sf -> effectiveSfId.equals(sf.id()))) {
                sources.add(new SourceFile(effectiveSfId, "unknown", "file:///unknown", List.of()));
                warnStub("file_description", "source file " + effectiveSfId, "run.default_source_file_id");
            }
            runJson = MAPPER.createObjectNode();
            runJson.put("id", run.id() != null ? run.id() : RUN_ID);
            runJson.put("default_instrument_id", instrumentId);
            runJson.put("default_data_processing_id", effectiveDpId);
            runJson.put("default_source_file_id", effectiveSfId);
            putText(runJson, "start_time", run.startTime());
            putParams(runJson, run.parameters());
        }
        String defaultSoftware = defaultSoftware(meta);
        for (int i = 0; i < configurations.size(); i++) {
            InstrumentConfiguration ic = configurations.get(i);
            String reference = ic.softwareReference() != null ? ic.softwareReference() : defaultSoftware;
            requireSoftware(reference, "instrument_configuration_list[" + i + "].software_reference", software,
                    softwareIds);
            configurations.set(i, new InstrumentConfiguration(ic.id(), ic.components(), reference, ic.parameters()));
        }
        for (int i = 0; i < processing.size(); i++) {
            List<ProcessingMethod> methods = processing.get(i).methods();
            for (int j = 0; j < methods.size(); j++) {
                requireSoftware(methods.get(j).softwareReference(), "data_processing_method_list[" + i + "].methods["
                        + j + "].software_reference", software, softwareIds);
            }
        }

        if (runJson != null) {
            kv.put("run", runJson.toString());
        }
        if (!software.isEmpty()) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (Software s : software) {
                ObjectNode n = arr.addObject();
                putText(n, "id", s.id());
                n.put("version", s.version() != null ? s.version() : "");
                putParams(n, s.parameters());
            }
            kv.put("software_list", arr.toString());
        }
        if (!configurations.isEmpty()) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (InstrumentConfiguration ic : configurations) {
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
        if (!processing.isEmpty()) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (DataProcessing dp : processing) {
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
            kv.put("data_processing_method_list", arr.toString());
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
        // A run always names a source file, so without a file description one is written to declare its stub.
        if (description != null || !sources.isEmpty()) {
            ObjectNode fd = MAPPER.createObjectNode();
            putParamsArray(fd, "contents", description == null ? null : description.contents());
            ArrayNode sourceArray = fd.putArray("source_files");
            for (SourceFile sf : sources) {
                ObjectNode sn = sourceArray.addObject();
                putText(sn, "id", sf.id());
                putText(sn, "name", sf.name());
                putText(sn, "location", sf.location());
                putParams(sn, sf.parameters());
            }
            // Left out when empty, as the reference serialises FileDescription.contacts (skip_serializing_if empty).
            if (description != null && !description.contacts().isEmpty()) {
                ArrayNode contacts = fd.putArray("contacts");
                for (Contact c : description.contacts()) {
                    ObjectNode cn = contacts.addObject();
                    putText(cn, "contact_name", c.name());
                    putText(cn, "contact_affiliation", c.affiliation());
                    putParams(cn, c.parameters());
                }
            }
            kv.put("file_description", fd.toString());
        }
        return kv;
    }

    /**
     * The software an instrument configuration without one names: the first configuration's software, else the
     * first software, else the stub software.
     */
    private static String defaultSoftware(FileMetadata meta) {
        List<InstrumentConfiguration> configurations = meta.instrumentConfigurations();
        if (!configurations.isEmpty() && configurations.get(0).softwareReference() != null) {
            return configurations.get(0).softwareReference();
        }
        if (!meta.software().isEmpty()) {
            return meta.software().get(0).id();
        }
        return SOFTWARE_ID;
    }

    /** Adds a stub software {@code id} to {@code software} when {@code id} is not null and names none of them. */
    private static void requireSoftware(String id, String reference, List<Software> software,
                                        Set<String> softwareIds) {
        if (id != null && softwareIds.add(id)) {
            software.add(new Software(id, "", List.of()));
            warnStub("software_list", "software " + id, reference);
        }
    }

    private static void warnStub(String document, String stub, String reference) {
        LOG.log(Level.WARNING, document + ": added the stub " + stub + ", which " + reference
                + " names but the file metadata does not declare");
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
