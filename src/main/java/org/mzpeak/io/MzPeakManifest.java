package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mzpeak.model.Param;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The {@code mzpeak_index.json} manifest. Maps each member Parquet file to its role
 * {@code (entity_type, data_kind)} so readers resolve files by role rather than by hardcoded filename.
 *
 * <p>Also parses the optional {@code metadata} object, which may carry:
 * <ul>
 *   <li>{@code scan_settings_list} — imzML-derived pixel-raster settings ({@link ScanSettings})
 *   <li>{@code imaging} — mzML2mzPeak-specific imaging summary (exposed via {@link #isImaging()})
 * </ul>
 */
public final class MzPeakManifest {

    /** A single file entry from the manifest. */
    public record Entry(String name, String entityType, String dataKind) {
    }

    /** A controlled-vocabulary ontology reference from {@code metadata.cv_list}. */
    public record CvEntry(String id, String fullName, String uri, String version) {
    }

    private final List<Entry> files;
    private final List<ScanSettings> scanSettingsList;
    private final boolean imaging;
    private final String version;
    private final List<CvEntry> cvList;

    private MzPeakManifest(List<Entry> files, List<ScanSettings> scanSettingsList, boolean imaging,
                            String version, List<CvEntry> cvList) {
        this.files = List.copyOf(files);
        this.scanSettingsList = List.copyOf(scanSettingsList);
        this.imaging = imaging;
        this.version = version;
        this.cvList = cvList == null ? List.of() : List.copyOf(cvList);
    }

    public List<Entry> files() {
        return files;
    }

    /**
     * Scan settings from {@code metadata.scan_settings_list} — present for imaging (MSI / imzML)
     * datasets; empty for conventional LC-MS files.
     */
    public List<ScanSettings> scanSettingsList() {
        return scanSettingsList;
    }

    /**
     * {@code true} if the manifest's {@code metadata.imaging.is_imaging} flag is set.
     * Convenience shortcut — {@link #scanSettingsList()} being non-empty is also a reliable indicator.
     */
    public boolean isImaging() {
        return imaging;
    }

    /** Format version string from {@code metadata.version} (e.g. {@code "0.9.0"}); {@code null} if absent. */
    public String version() {
        return version;
    }

    /** CV ontology references from {@code metadata.cv_list}; empty if absent. */
    public List<CvEntry> cvList() {
        return cvList;
    }

    /** Parse {@code mzpeak_index.json} from a source (directory or ZIP). */
    public static MzPeakManifest fromSource(MzPeakSource source) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(source.readManifestBytes());
            JsonNode filesNode = root.get("files");
            if (filesNode == null || !filesNode.isArray()) {
                throw new MzPeakException("mzpeak_index.json has no 'files' array in " + source.describe());
            }
            List<Entry> entries = new ArrayList<>();
            for (JsonNode f : filesNode) {
                entries.add(new Entry(
                        text(f, "name"),
                        text(f, "entity_type"),
                        text(f, "data_kind")));
            }

            // Parse optional metadata block
            JsonNode meta = root.get("metadata");
            List<ScanSettings> scanSettings = parseScanSettingsList(meta);
            boolean isImaging = isImagingFlag(meta);
            String version = meta != null && meta.isObject() ? text(meta, "version") : null;
            List<CvEntry> cvList = parseCvList(meta);

            return new MzPeakManifest(entries, scanSettings, isImaging, version, cvList);
        } catch (IOException e) {
            throw new MzPeakException("Failed to parse mzpeak_index.json in " + source.describe(), e);
        }
    }

    /** Resolve the single file matching a role, or empty if none. */
    public Optional<Entry> find(String entityType, String dataKind) {
        return files.stream()
                .filter(e -> entityType.equals(e.entityType()) && dataKind.equals(e.dataKind()))
                .findFirst();
    }

    // ---- metadata parsing ------------------------------------------------------------------

    private static List<ScanSettings> parseScanSettingsList(JsonNode meta) {
        if (meta == null || !meta.isObject()) return List.of();
        JsonNode ssList = meta.get("scan_settings_list");
        if (ssList == null || !ssList.isArray()) return List.of();
        List<ScanSettings> out = new ArrayList<>();
        for (JsonNode ss : ssList) {
            String id = text(ss, "id");
            List<Param> params = parseParamArray(ss.get("parameters"));
            out.add(new ScanSettings(id, params));
        }
        return out;
    }

    private static List<Param> parseParamArray(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        List<Param> out = new ArrayList<>();
        for (JsonNode p : arr) {
            String name = text(p, "name");
            String accession = text(p, "accession");
            String unit = text(p, "unit_accession");
            Object value = parseParamValue(p.get("value"));
            out.add(new Param(name, accession, value, unit));
        }
        return out;
    }

    private static Object parseParamValue(JsonNode v) {
        if (v == null || v.isNull() || !v.isTextual() && !v.isNumber() && !v.isBoolean()) return null;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isIntegralNumber()) return v.asLong();
        if (v.isNumber()) return v.asDouble();
        // Textual: try parsing as number
        String s = v.asText();
        try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        return s;
    }

    private static List<CvEntry> parseCvList(JsonNode meta) {
        if (meta == null || !meta.isObject()) return List.of();
        JsonNode arr = meta.get("cv_list");
        if (arr == null || !arr.isArray()) return List.of();
        List<CvEntry> out = new ArrayList<>();
        for (JsonNode cv : arr) {
            out.add(new CvEntry(text(cv, "id"), text(cv, "full_name"), text(cv, "uri"), text(cv, "version")));
        }
        return out;
    }

    private static boolean isImagingFlag(JsonNode meta) {
        if (meta == null || !meta.isObject()) return false;
        JsonNode imaging = meta.get("imaging");
        if (imaging == null || !imaging.isObject()) return false;
        JsonNode flag = imaging.get("is_imaging");
        return flag != null && flag.isBoolean() && flag.asBoolean();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
