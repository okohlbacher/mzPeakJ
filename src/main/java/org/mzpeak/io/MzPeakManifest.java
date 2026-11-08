package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mzpeak.model.Param;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code mzpeak_index.json} manifest. Maps each member Parquet file to its role
 * {@code (entity_type, data_kind)} so readers resolve files by role rather than by hardcoded filename. Roles are
 * normalized as the reference implementation does ({@link EntityType}, {@link DataKind}), so the pin-era spellings
 * ({@code data arrays}, {@code wavelength spectrum}) and the current ones ({@code data_arrays},
 * {@code wavelength_spectrum}) resolve alike; {@link Entry} keeps the strings as written.
 *
 * <p>Also parses the optional {@code metadata} object, which may carry:
 * <ul>
 *   <li>{@code scan_settings_list} — imzML-derived pixel-raster settings ({@link ScanSettings})
 *   <li>{@code imaging} — mzML2mzPeak-specific imaging summary (exposed via {@link #isImaging()})
 * </ul>
 */
public final class MzPeakManifest {

    /**
     * The {@code entity_type} of a member: the thing the member describes. Parsed as the reference implementation
     * does (Rust {@code EntityType::from_str}).
     */
    public enum EntityType {
        SPECTRUM("spectrum"),
        CHROMATOGRAM("chromatogram"),
        WAVELENGTH_SPECTRUM("wavelength_spectrum"),
        PROVENANCE("provenance"),
        /** Any other value, including {@code "other"} and an absent one; never matches in {@link #find}. */
        OTHER("other");

        private final String canonical;

        EntityType(String canonical) {
            this.canonical = canonical;
        }

        /** The spelling the current spec writes, e.g. {@code wavelength_spectrum}. */
        public String canonical() {
            return canonical;
        }

        /**
         * Parses an {@code entity_type} string: lowercased and trimmed, accepting the aliases {@code mass spectrum},
         * {@code mass_spectrum} and {@code wavelength spectrum}. Anything else, and {@code null}, is {@link #OTHER}.
         */
        public static EntityType parse(String raw) {
            if (raw == null) {
                return OTHER;
            }
            return switch (raw.toLowerCase(Locale.ROOT).strip()) {
                case "spectrum", "mass spectrum", "mass_spectrum" -> SPECTRUM;
                case "chromatogram" -> CHROMATOGRAM;
                case "wavelength spectrum", "wavelength_spectrum" -> WAVELENGTH_SPECTRUM;
                case "provenance" -> PROVENANCE;
                default -> OTHER;
            };
        }
    }

    /**
     * The {@code data_kind} of a member: which facet of its entity the member holds. Parsed as the reference
     * implementation does (Rust {@code DataKind::from_str}).
     */
    public enum DataKind {
        DATA_ARRAYS("data_arrays"),
        PEAKS("peaks"),
        METADATA("metadata"),
        SCANS("scans"),
        PRECURSORS("precursors"),
        SELECTED_IONS("selected_ions"),
        PRODUCTS("products"),
        SIGNATURE("signature"),
        PROPRIETARY("proprietary"),
        /** Any other value, including {@code "other"} and an absent one; never matches in {@link #find}. */
        OTHER("other");

        private final String canonical;

        DataKind(String canonical) {
            this.canonical = canonical;
        }

        /** The spelling the current spec writes, e.g. {@code data_arrays}. */
        public String canonical() {
            return canonical;
        }

        /**
         * Parses a {@code data_kind} string: lowercased and trimmed, accepting the alias {@code data arrays}.
         * Anything else, and {@code null}, is {@link #OTHER}.
         */
        public static DataKind parse(String raw) {
            if (raw == null) {
                return OTHER;
            }
            return switch (raw.toLowerCase(Locale.ROOT).strip()) {
                case "data arrays", "data_arrays" -> DATA_ARRAYS;
                case "peaks" -> PEAKS;
                case "metadata" -> METADATA;
                case "scans" -> SCANS;
                case "precursors" -> PRECURSORS;
                case "selected_ions" -> SELECTED_IONS;
                case "products" -> PRODUCTS;
                case "signature" -> SIGNATURE;
                case "proprietary" -> PROPRIETARY;
                default -> OTHER;
            };
        }
    }

    /**
     * A single file entry from the manifest.
     *
     * @param name          member name, relative to the dataset root
     * @param entityType    the {@code entity_type} string as written
     * @param dataKind      the {@code data_kind} string as written
     * @param columnMapping the {@code column_mapping} list (alias {@code metadata_mapping}) in file order; empty if
     *                      absent
     * @param parameters    the {@code parameters} list; empty if absent. Values keep their JSON type (a string stays
     *                      a {@code String}); an array or object value becomes its JSON text
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

        /** The parsed {@link #entityType()}; {@link EntityType#OTHER} for an unknown or absent value. */
        public EntityType entity() {
            return EntityType.parse(entityType);
        }

        /** The parsed {@link #dataKind()}; {@link DataKind#OTHER} for an unknown or absent value. */
        public DataKind kind() {
            return DataKind.parse(dataKind);
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
     * @param unit       the CURIE of the column's unit, given as a JSON string, or {@code null}; never a column path
     * @param termMarker the {@code term_marker} flag: the column holds child-term CURIEs or presence booleans of
     *                   the term rather than its value; {@code null} when the key is absent
     * @param unitPath   the path of a column holding a unit CURIE per row, for a unit given as a JSON array of
     *                   segments (Rust {@code PathOrCURIE}), joined with {@code '.'} like {@code path}; {@code null}
     *                   otherwise. Such a unit column is not resolved yet, so the mapped values have no unit
     */
    public record ColumnMapping(String name, String path, String accession, String unit, Boolean termMarker,
                                String unitPath) {

        /** A mapping without a unit column: {@link #unitPath()} is {@code null}. */
        public ColumnMapping(String name, String path, String accession, String unit, Boolean termMarker) {
            this(name, path, accession, unit, termMarker, null);
        }
    }

    /** A controlled-vocabulary ontology reference from {@code metadata.cv_list}. */
    public record CvEntry(String id, String fullName, String uri, String version) {
    }

    private final List<Entry> files;
    private final List<ScanSettings> scanSettingsList;
    private final boolean imaging;
    private final String version;
    private final List<CvEntry> cvList;
    private final JsonNode metadataJson;

    private MzPeakManifest(List<Entry> files, List<ScanSettings> scanSettingsList, boolean imaging,
                            String version, List<CvEntry> cvList, JsonNode metadataJson) {
        this.files = List.copyOf(files);
        this.scanSettingsList = List.copyOf(scanSettingsList);
        this.imaging = imaging;
        this.version = version;
        this.cvList = cvList == null ? List.of() : List.copyOf(cvList);
        this.metadataJson = metadataJson;
    }

    public List<Entry> files() {
        return files;
    }

    /**
     * Scan settings from {@code metadata.scan_settings_list} — present for imaging (MSI / imzML)
     * datasets; empty for conventional LC-MS files. A param's unit is read from {@code unit}, falling back to
     * {@code unit_accession}. {@link MzPeakReader#scanSettings()} also finds scan settings that only the footer
     * holds.
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

    /**
     * The {@code metadata} object as parsed, or {@code null} if it is absent or not an object. Shared, not copied:
     * callers must not modify it.
     */
    JsonNode metadataJson() {
        return metadataJson;
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
            for (int i = 0; i < filesNode.size(); i++) {
                JsonNode f = filesNode.get(i);
                String name = text(f, "name");
                if (name == null) {
                    // both references reject a nameless entry at parse time (Rust FileEntry.name is required)
                    throw new MzPeakException("mzpeak_index.json files[" + i + "] (" + text(f, "entity_type") + "/"
                            + text(f, "data_kind") + ") has no name in " + source.describe());
                }
                JsonNode mapping = f.get("column_mapping");
                if (mapping == null || mapping.isNull()) {
                    mapping = f.get("metadata_mapping");
                }
                entries.add(new Entry(
                        name,
                        text(f, "entity_type"),
                        text(f, "data_kind"),
                        parseColumnMapping(mapping),
                        parseEntryParams(f.get("parameters")),
                        text(f, "checksum")));
            }

            // Parse optional metadata block
            JsonNode meta = root.get("metadata");
            List<ScanSettings> scanSettings = parseScanSettingsList(meta);
            boolean isImaging = isImagingFlag(meta);
            String version = meta != null && meta.isObject() ? text(meta, "version") : null;
            List<CvEntry> cvList = parseCvList(meta);

            return new MzPeakManifest(entries, scanSettings, isImaging, version, cvList,
                    meta != null && meta.isObject() ? meta : null);
        } catch (IOException e) {
            throw new MzPeakException("Failed to parse mzpeak_index.json in " + source.describe(), e);
        }
    }

    /**
     * Resolve the first file, in manifest order, whose parsed role is {@code (entity, kind)}, or empty if none. A
     * role with {@link EntityType#OTHER} or {@link DataKind#OTHER} never matches.
     */
    public Optional<Entry> find(EntityType entity, DataKind kind) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(kind, "kind");
        if (entity == EntityType.OTHER || kind == DataKind.OTHER) {
            return Optional.empty();
        }
        return files.stream()
                .filter(e -> e.entity() == entity && e.kind() == kind)
                .findFirst();
    }

    /**
     * Resolve a file by role strings, parsed with {@link EntityType#parse} and {@link DataKind#parse}, so that
     * {@code ("spectrum", "data arrays")} and {@code ("spectrum", "data_arrays")} find the same file.
     *
     * @see #find(EntityType, DataKind)
     */
    public Optional<Entry> find(String entityType, String dataKind) {
        return find(EntityType.parse(entityType), DataKind.parse(dataKind));
    }

    // ---- metadata parsing ------------------------------------------------------------------

    private static List<ScanSettings> parseScanSettingsList(JsonNode meta) {
        if (meta == null || !meta.isObject()) return List.of();
        return parseScanSettings(meta.get("scan_settings_list"));
    }

    /**
     * Parses a {@code scan_settings_list} document, from the manifest or a footer; anything but an array gives an
     * empty list.
     */
    static List<ScanSettings> parseScanSettings(JsonNode ssList) {
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
            String unit = text(p, "unit");
            if (unit == null) unit = text(p, "unit_accession");
            Object value = parseParamValue(p.get("value"));
            out.add(new Param(name, accession, value, unit));
        }
        return out;
    }

    /**
     * Parses a file entry's param list. The unit is read from {@code unit}, falling back to
     * {@code unit_accession}; the value keeps its JSON type ({@link #jsonValue}).
     */
    private static List<Param> parseEntryParams(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        List<Param> out = new ArrayList<>();
        for (JsonNode p : arr) {
            String unit = text(p, "unit");
            if (unit == null) unit = text(p, "unit_accession");
            out.add(new Param(text(p, "name"), text(p, "accession"), jsonValue(p.get("value")), unit));
        }
        return out;
    }

    /**
     * A param value as typed in JSON, without coercion (Rust {@code MetaParam}): a boolean as {@code Boolean}, an
     * integral number as {@code Long}, any other number as {@code Double}, a string as the same {@code String}, an
     * array or object as its compact JSON text, and an absent or {@code null} value as {@code null}.
     */
    private static Object jsonValue(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isBoolean()) return v.booleanValue();
        if (v.isIntegralNumber()) return v.asLong();
        if (v.isNumber()) return v.asDouble();
        if (v.isContainerNode()) return v.toString();
        return v.asText();
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
            JsonNode unit = c.get("unit");
            boolean unitIsPath = unit != null && unit.isArray();
            out.add(new ColumnMapping(
                    text(c, "name"),
                    dottedPath(c.get("path")),
                    text(c, "accession"),
                    unitIsPath ? null : dottedPath(unit),
                    marker != null && marker.isBoolean() ? Boolean.valueOf(marker.booleanValue()) : null,
                    unitIsPath ? dottedPath(unit) : null));
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
