package org.mzpeak.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.model.Param;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes the {@code mzpeak_index.json} manifest of a dataset written by {@link MzPeakWriter}: the member list,
 * the CV list, the scan settings and the file-level metadata. Every member entry carries its role in the
 * {@link Dialect}'s spelling, its {@code column_mapping}, an empty {@code parameters} list and the SHA-512
 * {@code checksum} of the member's bytes.
 */
final class ManifestWriter {

    /** The name of the manifest at the dataset root. */
    static final String INDEX = "mzpeak_index.json";

    /**
     * How the manifest spells the roles of its members. Only the spelling of {@code entity_type} and
     * {@code data_kind} differs; both dialects write the same keys.
     */
    enum Dialect {
        /**
         * The spellings of the 29e59b2 pin, {@code data arrays} and {@code wavelength spectrum}: the pin-era
         * reference deserialises only these, and mzPeakJ 0.10.3 and earlier match roles by exact string. Written with
         * the packed metadata layout.
         */
        PIN_29E59B2,
        /** The current spec's spellings, {@link EntityType#canonical()} and {@link DataKind#canonical()}. */
        CURRENT;

        /** The {@code entity_type} string of {@code entity} in this dialect. */
        String entityType(EntityType entity) {
            return this == PIN_29E59B2 && entity == EntityType.WAVELENGTH_SPECTRUM
                    ? "wavelength spectrum" : entity.canonical();
        }

        /** The {@code data_kind} string of {@code kind} in this dialect. */
        String dataKind(DataKind kind) {
            return this == PIN_29E59B2 && kind == DataKind.DATA_ARRAYS ? "data arrays" : kind.canonical();
        }
    }

    private ManifestWriter() {
    }

    /**
     * Write {@link #INDEX} into {@code directory}: the {@code members} in the given order, each of which must already
     * be written and closed in {@code directory} because its checksum is computed from its file here, then the plan's
     * scan settings and file metadata (the footer key-values).
     */
    static void writeManifest(Path directory, List<WrittenMember> members, WritePlan plan, Dialect dialect)
            throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ArrayNode files = root.putArray("files");
        for (WrittenMember member : members) {
            addManifestEntry(files, member, dialect, Sha512.hex(directory.resolve(member.name())));
        }
        ObjectNode meta = root.putObject("metadata");
        // Match the reference writer: the spec example declares version 0.9.0.
        meta.put("version", "0.9.0");
        // Always declare the standard CV ontologies; every mzPeak file uses at minimum MS + UO.
        // Including IMS is harmless for non-imaging files (extra declarations are not errors).
        ArrayNode cvList = meta.putArray("cv_list");
        addCvEntry(cvList, "MS", "PSI-MS controlled vocabulary",
                "https://raw.githubusercontent.com/HUPO-PSI/psi-ms-CV/master/psi-ms.obo", "4.1.x");
        addCvEntry(cvList, "IMS", "Mass Spectrometry Imaging controlled vocabulary",
                "https://raw.githubusercontent.com/imzML/imzML/master/imagingMS.obo", "1.1.x");
        addCvEntry(cvList, "UO", "Unit Ontology",
                "https://raw.githubusercontent.com/bio-ontology-research-group/unit-ontology/master/unit.obo",
                "2024-01-01");
        writeScanSettingsList(meta.putArray("scan_settings_list"), plan.scanSettings());
        for (var kv : plan.footer().entrySet()) {
            meta.set(kv.getKey(), mapper.readTree(kv.getValue()));
        }
        Files.write(directory.resolve(INDEX), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
    }

    /** Serialize imaging scan settings into the manifest, matching {@code MzPeakManifest.parseParamArray}. */
    private static void writeScanSettingsList(ArrayNode arr, List<ScanSettings> scanSettings) {
        if (scanSettings == null) {
            return;
        }
        for (ScanSettings ss : scanSettings) {
            ObjectNode n = arr.addObject();
            if (ss.id() != null) {
                n.put("id", ss.id());
            }
            ArrayNode params = n.putArray("parameters");
            for (Param p : ss.parameters()) {
                ObjectNode pn = params.addObject();
                if (p.name() != null) pn.put("name", p.name());
                if (p.accession() != null) pn.put("accession", p.accession());
                Object v = p.value();
                if (v instanceof Long l) pn.put("value", l);
                else if (v instanceof Integer i) pn.put("value", i.longValue());
                else if (v instanceof Double dd) pn.put("value", dd);
                else if (v instanceof Boolean b) pn.put("value", b);
                else if (v != null) pn.put("value", v.toString());
                if (p.unit() != null) pn.put("unit_accession", p.unit());
            }
        }
    }

    /**
     * One {@code files} entry, with its keys in the reference's order. A column mapping item is written as the
     * reference serialises it: {@code path} as a dotted string, a missing accession or unit as {@code null}, and
     * {@code term_marker} only when it is set.
     */
    private static void addManifestEntry(ArrayNode files, WrittenMember member, Dialect dialect, String checksum) {
        ObjectNode e = files.addObject();
        e.put("name", member.name());
        e.put("entity_type", dialect.entityType(member.entity()));
        e.put("data_kind", dialect.dataKind(member.kind()));
        ArrayNode mapping = e.putArray("column_mapping");
        for (ColumnMapping c : member.columnMapping()) {
            ObjectNode m = mapping.addObject();
            m.put("name", c.name());
            m.put("path", c.path());
            m.put("accession", c.accession());
            m.put("unit", c.unit());
            if (Boolean.TRUE.equals(c.termMarker())) {
                m.put("term_marker", true);
            }
        }
        e.putArray("parameters");
        e.put("checksum", checksum);
    }

    private static void addCvEntry(ArrayNode cvList, String id, String fullName, String uri, String version) {
        ObjectNode cv = cvList.addObject();
        cv.put("id", id);
        cv.put("full_name", fullName);
        cv.put("uri", uri);
        cv.put("version", version);
    }
}
