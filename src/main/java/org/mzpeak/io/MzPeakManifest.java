package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mzpeak.model.Param;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

    /**
     * A single file entry from the manifest.
     *
     * @param name          member name, relative to the dataset root
     * @param entityType    the {@code entity_type} string as written
     * @param dataKind      the {@code data_kind} string as written
     * @param columnMapping the {@code column_mapping} list (alias {@code metadata_mapping}) in file order; empty if
     *                      absent
     * @param parameters    the {@code parameters} list; empty if absent
     * @param checksum      the {@code checksum} string as written (lowercase hex SHA-512), or {@code null} if absent
     */
    public record Entry(String name, String entityType, String dataKind, List<ColumnMapping> columnMapping,
                        List<Param> parameters, String checksum) {

        /** Copies both lists; a {@code null} list becomes an empty one. */
        public Entry {
            columnMapping = columnMapping == null ? List.of() : List.copyOf(columnMapping);
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }

        /** An entry with no column mapping, no parameters and no checksum. */
        public Entry(String name, String entityType, String dataKind) {
            this(name, entityType, dataKind, List.of(), List.of(), null);
        }

        /** The first column mapping, in file order, whose accession is {@code accession}. */
        public Optional<ColumnMapping> columnForAccession(String accession) {
            Objects.requireNonNull(accession, "accession");
            for (ColumnMapping m : columnMapping) {
                if (accession.equals(m.accession())) {
                    return Optional.of(m);
                }
            }
            return Optional.empty();
        }

        /** The first column mapping, in file order, whose path is {@code dottedPath} (e.g. {@code ms_level}). */
        public Optional<ColumnMapping> columnAtPath(String dottedPath) {
            Objects.requireNonNull(dottedPath, "dottedPath");
            for (ColumnMapping m : columnMapping) {
                if (dottedPath.equals(m.path())) {
                    return Optional.of(m);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * One {@code column_mapping} item of a file entry: ties a column of the member's Parquet schema to a CV term.
     *
     * @param name       the human-readable term name
     * @param path       the column path, delimited at nesting levels by {@code '.'} and omitting list/element
     *                   levels (e.g. {@code scan_windows.scan_window_lower_limit}); a path given as a JSON array of
     *                   segments is joined with {@code '.'}
     * @param accession  the CURIE of the term, or {@code null}
     * @param unit       the CURIE of the column's unit, or {@code null}; a unit given as a JSON array (a path to a
     *                   column holding the unit) is joined with {@code '.'} like {@code path}
     * @param termMarker the {@code term_marker} flag: the column holds child-term CURIEs or presence booleans of
     *                   the term rather than its value; {@code null} when the key is absent
     */
    public record ColumnMapping(String name, String path, String accession, String unit, Boolean termMarker) {
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
                JsonNode mapping = f.get("column_mapping");
                if (mapping == null || mapping.isNull()) {
                    mapping = f.get("metadata_mapping");
                }
                entries.add(new Entry(
                        text(f, "name"),
                        text(f, "entity_type"),
                        text(f, "data_kind"),
                        parseColumnMapping(mapping),
                        parseParamArray(f.get("parameters"), true),
                        text(f, "checksum")));
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
            List<Param> params = parseParamArray(ss.get("parameters"), false);
            out.add(new ScanSettings(id, params));
        }
        return out;
    }

    /**
     * Parses a param list. With {@code unitKey} the unit is read from {@code unit}, falling back to
     * {@code unit_accession}; without it only {@code unit_accession} is read.
     */
    private static List<Param> parseParamArray(JsonNode arr, boolean unitKey) {
        if (arr == null || !arr.isArray()) return List.of();
        List<Param> out = new ArrayList<>();
        for (JsonNode p : arr) {
            String name = text(p, "name");
            String accession = text(p, "accession");
            String unit = unitKey ? text(p, "unit") : null;
            if (unit == null) unit = text(p, "unit_accession");
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

    private static List<ColumnMapping> parseColumnMapping(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        List<ColumnMapping> out = new ArrayList<>();
        for (JsonNode c : arr) {
            if (!c.isObject()) continue;
            JsonNode marker = c.get("term_marker");
            out.add(new ColumnMapping(
                    text(c, "name"),
                    dottedPath(c.get("path")),
                    text(c, "accession"),
                    dottedPath(c.get("unit")),
                    marker != null && marker.isBoolean() ? Boolean.valueOf(marker.booleanValue()) : null));
        }
        return out;
    }

    /** A string as is, a JSON array of path segments joined with {@code '.'}, or {@code null}. */
    private static String dottedPath(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (!v.isArray()) return v.asText();
        List<String> segments = new ArrayList<>();
        for (JsonNode s : v) {
            segments.add(s.asText());
        }
        return String.join(".", segments);
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
