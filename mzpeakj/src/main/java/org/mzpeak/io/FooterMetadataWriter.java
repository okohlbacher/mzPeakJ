package org.mzpeak.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
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
 * In the current (split) layout's dialect the run names its default instrument with
 * {@code default_instrument_configuration_id} (spec 963d64a, W1), a processing method without a software names the
 * software an instrument configuration without one names, and a software or a data processing without an id is
 * written with a fresh one ({@code mzpeakj_software_<i>}, {@code mzpeakj_processing_<i>} for the {@code i}-th entry of
 * its list), as the specification requires these keys; the packed layout keeps the pin-era dialect, with
 * {@code default_instrument_id} and those values left out.
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

    /** The documents in the dialect of the current (split) metadata layout. */
    static Map<String, String> serialize(FileMetadata meta) {
        return serialize(meta, MetadataLayout.SPLIT);
    }

    /** The documents in the dialect of {@code layout}. */
    static Map<String, String> serialize(FileMetadata meta, MetadataLayout layout) {
        boolean current = layout == MetadataLayout.SPLIT;
        String instrumentKey = current ? "default_instrument_configuration_id" : "default_instrument_id";
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
                        "run." + instrumentKey);
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
            runJson.put(instrumentKey, instrumentId);
            runJson.put("default_data_processing_id", effectiveDpId);
            runJson.put("default_source_file_id", effectiveSfId);
            putText(runJson, "start_time", run.startTime());
            putParams(runJson, "run", run.parameters());
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
            List<ProcessingMethod> methods = new ArrayList<>(processing.get(i).methods());
            for (int j = 0; j < methods.size(); j++) {
                ProcessingMethod m = methods.get(j);
                String reference = m.softwareReference() != null || !current ? m.softwareReference()
                        : defaultSoftware;
                requireSoftware(reference, "data_processing_method_list[" + i + "].methods[" + j
                        + "].software_reference", software, softwareIds);
                methods.set(j, new ProcessingMethod(m.order(), reference, m.parameters()));
            }
            processing.set(i, new DataProcessing(processing.get(i).id(), methods));
        }

        if (runJson != null) {
            kv.put("run", runJson.toString());
        }
        if (!software.isEmpty()) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (int i = 0; i < software.size(); i++) {
                Software s = software.get(i);
                ObjectNode n = arr.addObject();
                String id = s.id() != null || !current ? s.id() : freshId("mzpeakj_software_" + i, softwareIds);
                putText(n, "id", id);
                n.put("version", s.version() != null ? s.version() : "");
                putParams(n, "software " + id, s.parameters());
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
                    putParams(cn, "instrument configuration " + ic.id() + " component " + c.order(),
                            c.parameters());
                }
                putParams(n, "instrument configuration " + ic.id(), ic.parameters());
            }
            kv.put("instrument_configuration_list", arr.toString());
        }
        if (!processing.isEmpty()) {
            Set<String> processingIds = new HashSet<>();
            processing.forEach(dp -> processingIds.add(dp.id()));
            ArrayNode arr = MAPPER.createArrayNode();
            for (int i = 0; i < processing.size(); i++) {
                DataProcessing dp = processing.get(i);
                ObjectNode n = arr.addObject();
                String id = dp.id() != null || !current ? dp.id() : freshId("mzpeakj_processing_" + i, processingIds);
                putText(n, "id", id);
                ArrayNode methods = n.putArray("methods");
                for (ProcessingMethod m : dp.methods()) {
                    ObjectNode mn = methods.addObject();
                    mn.put("order", m.order());
                    putText(mn, "software_reference", m.softwareReference());
                    putParams(mn, "data processing " + id + " method " + m.order(), m.parameters());
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
                putParams(n, "sample " + s.id(), s.parameters());
            }
            kv.put("sample_list", arr.toString());
        }
        // A run always names a source file, so without a file description one is written to declare its stub.
        if (description != null || !sources.isEmpty()) {
            ObjectNode fd = MAPPER.createObjectNode();
            putParamsArray(fd, "contents", "file description", description == null ? null : description.contents());
            ArrayNode sourceArray = fd.putArray("source_files");
            for (SourceFile sf : sources) {
                ObjectNode sn = sourceArray.addObject();
                putText(sn, "id", sf.id());
                putText(sn, "name", sf.name());
                putText(sn, "location", sf.location());
                putParams(sn, "source file " + sf.id(), sf.parameters());
            }
            // Left out when empty, as the reference serialises FileDescription.contacts (skip_serializing_if empty).
            if (description != null && !description.contacts().isEmpty()) {
                ArrayNode contacts = fd.putArray("contacts");
                for (Contact c : description.contacts()) {
                    ObjectNode cn = contacts.addObject();
                    putText(cn, "contact_name", c.name());
                    putText(cn, "contact_affiliation", c.affiliation());
                    putParams(cn, "contact " + c.name(), c.parameters());
                }
            }
            kv.put("file_description", fd.toString());
        }
        return kv;
    }

    /**
     * The software an instrument configuration without one names: the first configuration's software, else the
     * first software with an id, else the stub software.
     */
    private static String defaultSoftware(FileMetadata meta) {
        List<InstrumentConfiguration> configurations = meta.instrumentConfigurations();
        if (!configurations.isEmpty() && configurations.get(0).softwareReference() != null) {
            return configurations.get(0).softwareReference();
        }
        for (Software s : meta.software()) {
            if (s.id() != null) {
                return s.id();
            }
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

    /** {@code base}, or {@code base} with {@code _} appended until no id of {@code ids} is it; it is added to them. */
    private static String freshId(String base, Set<String> ids) {
        String id = base;
        while (!ids.add(id)) {
            id += "_";
        }
        return id;
    }

    private static void warnStub(String document, String stub, String reference) {
        LOG.log(Level.WARNING, document + ": added the stub " + stub + ", which " + reference
                + " names but the file metadata does not declare");
    }

    private static void putParams(ObjectNode owner, String entity, List<Param> params) {
        putParamsArray(owner, "parameters", entity, params);
    }

    /**
     * Adds {@code params} as the array {@code field} of {@code owner}, each value as {@link ParamValues#json} gives it;
     * a value of a type the writer cannot write throws {@link MzPeakException} naming {@code entity} and the param.
     */
    private static void putParamsArray(ObjectNode owner, String field, String entity, List<Param> params) {
        ArrayNode arr = owner.putArray(field);
        if (params == null) {
            return;
        }
        ParamValues.require(entity, params);
        for (Param p : params) {
            ObjectNode pn = arr.addObject();
            putText(pn, "name", p.name());
            putText(pn, "accession", p.accession());
            pn.set("value", ParamValues.json(p.value()));
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
