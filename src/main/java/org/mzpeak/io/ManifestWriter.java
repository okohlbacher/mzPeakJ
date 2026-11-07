package org.mzpeak.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mzpeak.model.Param;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes the {@code mzpeak_index.json} manifest of a dataset written by {@link MzPeakWriter}: the member list,
 * the CV list, the scan settings and the file-level metadata.
 */
final class ManifestWriter {

    private ManifestWriter() {
    }

    static void writeManifest(Path file, boolean hasProfile, boolean hasPeaks, boolean hasChromatograms,
                               boolean hasWavelength, List<ScanSettings> scanSettings,
                               FileMetadata fileMetadata)
            throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ArrayNode files = root.putArray("files");
        addManifestEntry(files, "spectra_metadata.parquet", "spectrum", "metadata");
        if (hasProfile) {
            addManifestEntry(files, "spectra_data.parquet", "spectrum", "data arrays");
        }
        if (hasPeaks) {
            addManifestEntry(files, "spectra_peaks.parquet", "spectrum", "peaks");
        }
        if (hasChromatograms) {
            addManifestEntry(files, "chromatograms_metadata.parquet", "chromatogram", "metadata");
            addManifestEntry(files, "chromatograms_data.parquet", "chromatogram", "data arrays");
        }
        if (hasWavelength) {
            addManifestEntry(files, "wavelength_spectra_metadata.parquet", "wavelength spectrum", "metadata");
            addManifestEntry(files, "wavelength_spectra_data.parquet", "wavelength spectrum", "data arrays");
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
        writeScanSettingsList(meta.putArray("scan_settings_list"), scanSettings);
        for (var kv : FooterMetadataWriter.serialize(fileMetadata).entrySet()) {
            meta.set(kv.getKey(), mapper.readTree(kv.getValue()));
        }
        Files.write(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
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

    private static void addManifestEntry(ArrayNode files, String name, String entityType, String dataKind) {
        ObjectNode e = files.addObject();
        e.put("name", name);
        e.put("entity_type", entityType);
        e.put("data_kind", dataKind);
    }

    private static void addCvEntry(ArrayNode cvList, String id, String fullName, String uri, String version) {
        ObjectNode cv = cvList.addObject();
        cv.put("id", id);
        cv.put("full_name", fullName);
        cv.put("uri", uri);
        cv.put("version", version);
    }
}
