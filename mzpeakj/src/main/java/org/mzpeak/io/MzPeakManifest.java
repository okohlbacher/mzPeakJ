package org.mzpeak.io;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mzpeak.model.Param;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code mzpeak_index.json} manifest. Maps each member Parquet file to its role
 * {@code (entity_type, data_kind)} so readers resolve files by role rather than by hardcoded filename. Roles are
 * normalized as the reference implementation does ({@link EntityType}, {@link DataKind}), so the pin-era spellings
 * ({@code data arrays}, {@code wavelength spectrum}) and the current ones ({@code data_arrays},
 * {@code wavelength_spectrum}) resolve alike; {@link Entry} keeps the strings as written.
 *
 * <p>Also parses the {@code metadata} object (required, as in the reference implementation), which may carry:
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

        /**
         * The controlled vocabularies {@link MzPeakWriter} knows, with the entries it declares for them unless the
         * caller gives its own ({@link MzPeakWriter.Options#withCvList}): MS, UO, EFO, OBI, HANCESTRO, BFO, NCIT, BTO,
         * PRIDE and IMS, in this order, which is the reference implementation's. Unmodifiable.
         */
        public static List<CvEntry> known() {
            return KnownCvs.ALL;
        }
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
        this.cvList = List.copyOf(cvList);
        this.metadataJson = metadataJson;
    }

    public List<Entry> files() {
        return files;
    }

    /**
     * Scan settings from {@code metadata.scan_settings_list} — present for imaging (MSI / imzML)
     * datasets; empty for conventional LC-MS files. A param's unit is read from {@code unit}, falling back to
     * {@code unit_accession}, and its value keeps its JSON type, in the params and in the targets.
     * {@link MzPeakReader#scanSettings()} also finds scan settings that only the footer holds.
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

    /**
     * Format version string from {@code metadata.version} (e.g. {@code "0.9.0"}); {@code null} if absent or not a
     * JSON string, as Rust {@code FileIndex::version} reads it.
     */
    public String version() {
        return version;
    }

    /** CV ontology references from {@code metadata.cv_list}; empty if absent. */
    public List<CvEntry> cvList() {
        return cvList;
    }

    /**
     * The {@code metadata} object as parsed; never {@code null} (a manifest without one is rejected). Shared, not
     * copied: callers must not modify it.
     */
    JsonNode metadataJson() {
        return metadataJson;
    }

    /**
     * Parse {@code mzpeak_index.json} from a source (directory or ZIP). The manifest is checked first against the
     * reference implementation's structs (Rust {@code FileIndex}, {@code FileEntry}, {@code MetadataColumn},
     * {@code MetaParam}, deserialized by serde): a missing required field, a field of the wrong JSON type, an
     * accession or unit that is not CURIE-shaped, or a known field given twice in the same object is rejected with
     * a message naming the entry and field. Unknown keys, and the content of the {@code metadata} documents beyond
     * their JSON type, are not checked.
     *
     * @throws MzPeakException if the manifest is not valid JSON or does not have the reference's shape
     */
    public static MzPeakManifest fromSource(MzPeakSource source) {
        try {
            byte[] bytes = source.readManifestBytes();
            Strict strict = new Strict(source.describe());
            strict.rejectDuplicateFields(bytes);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(bytes);
            strict.check(root);
            JsonNode filesNode = root.get("files");
            List<Entry> entries = new ArrayList<>();
            for (JsonNode f : filesNode) {
                JsonNode mapping = f.get("column_mapping");
                if (mapping == null) {
                    mapping = f.get("metadata_mapping");
                }
                entries.add(new Entry(
                        text(f, "name"),
                        text(f, "entity_type"),
                        text(f, "data_kind"),
                        parseColumnMapping(mapping),
                        parseParamArray(f.get("parameters")),
                        text(f, "checksum")));
            }

            JsonNode meta = root.get("metadata");
            JsonNode version = meta.get("version");
            return new MzPeakManifest(entries, parseScanSettings(meta.get("scan_settings_list")),
                    isImagingFlag(meta), version != null && version.isTextual() ? version.textValue() : null,
                    parseCvList(meta), meta);
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

    /**
     * Parses a {@code scan_settings_list} document, from the manifest or a footer; anything but an array gives an
     * empty list. An entry's {@code parameters} and each of its {@code targets} are read by
     * {@link #parseParamArray}; a {@code source_file_refs} or {@code targets} that is absent or not an array reads as
     * empty, and a {@code null} source file reference is skipped. The schema form is read too (the spec's
     * {@code schema/scan_settings_list.json} and the mzpeak-0.9 validator's): the references under
     * {@code source_file_references} when {@code source_file_refs} is absent or not an array, and a target that is an
     * object as its {@code parameters} array ({@code {"parameters": [...]}}); an object without one is an empty
     * target. The writer writes the Rust form, {@code source_file_refs} and bare arrays.
     */
    static List<ScanSettings> parseScanSettings(JsonNode ssList) {
        if (ssList == null || !ssList.isArray()) return List.of();
        List<ScanSettings> out = new ArrayList<>();
        for (JsonNode ss : ssList) {
            JsonNode refsNode = ss.get("source_file_refs");
            if (refsNode == null || !refsNode.isArray()) refsNode = ss.get("source_file_references");
            List<String> refs = new ArrayList<>();
            for (JsonNode ref : arrayOf(refsNode)) {
                if (!ref.isNull()) refs.add(ref.asText());
            }
            List<List<Param>> targets = new ArrayList<>();
            for (JsonNode target : arrayOf(ss.get("targets"))) {
                targets.add(parseParamArray(target.isObject() ? target.get("parameters") : target));
            }
            out.add(new ScanSettings(text(ss, "id"), parseParamArray(ss.get("parameters")), refs, targets));
        }
        return out;
    }

    /** The elements of {@code node} if it is an array, else none. */
    private static Iterable<JsonNode> arrayOf(JsonNode node) {
        return node != null && node.isArray() ? node : List.of();
    }

    /**
     * Parses a param list: a file entry's, or a scan setting's params or one of its targets. The unit is read from
     * {@code unit}, falling back to {@code unit_accession}; the value keeps its JSON type ({@link #jsonValue}).
     * Anything but an array gives an empty list.
     */
    private static List<Param> parseParamArray(JsonNode arr) {
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

    /** The mapping array of a file entry {@link Strict} accepted, or none if it is absent. */
    private static List<ColumnMapping> parseColumnMapping(JsonNode arr) {
        if (arr == null) return List.of();
        List<ColumnMapping> out = new ArrayList<>();
        for (JsonNode c : arr) {
            JsonNode marker = c.get("term_marker");
            JsonNode unit = c.get("unit");
            boolean unitIsPath = unit != null && unit.isArray();
            out.add(new ColumnMapping(
                    text(c, "name"),
                    dottedPath(c.get("path")),
                    text(c, "accession"),
                    unitIsPath ? null : dottedPath(unit),
                    marker != null ? Boolean.valueOf(marker.booleanValue()) : null,
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
        JsonNode arr = meta.get("cv_list");
        if (arr == null) return List.of();
        List<CvEntry> out = new ArrayList<>();
        for (JsonNode cv : arr) {
            out.add(new CvEntry(text(cv, "id"), text(cv, "full_name"), text(cv, "uri"), text(cv, "version")));
        }
        return out;
    }

    private static boolean isImagingFlag(JsonNode meta) {
        JsonNode imaging = meta.get("imaging");
        if (imaging == null) return false;
        JsonNode flag = imaging.get("is_imaging");
        return flag != null && flag.isBoolean() && flag.asBoolean();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    // ---- strict validation -----------------------------------------------------------------

    /** The struct levels of the reference manifest and the fields each knows (Rust serde structs). */
    private enum Level {
        /** {@code FileIndex}. */
        ROOT(Set.of("files", "metadata")),
        /** {@code FileEntry}; {@code metadata_mapping} is the alias of {@code column_mapping}. */
        ENTRY(Set.of("name", "entity_type", "data_kind", "column_mapping", "parameters", "checksum")),
        /** {@code MetadataColumn}. */
        MAPPING(Set.of("name", "path", "accession", "unit", "term_marker")),
        /** {@code MetaParam}. */
        PARAM(Set.of("name", "accession", "value", "unit"));

        private final Set<String> fields;

        Level(Set<String> fields) {
            this.fields = fields;
        }
    }

    /**
     * Checks a manifest against the reference implementation's structs ({@code src/archive/file_index.rs} and
     * {@code src/param.rs}), so that a manifest the reference refuses is refused here too, with
     * {@code MzPeakException("mzpeak_index.json <location>: <problem> in <source>")}. The location names the entry
     * and field, e.g. {@code files[3] (spectra_metadata_scans.parquet).column_mapping[2].path}. Unknown keys are
     * ignored, as serde does without {@code deny_unknown_fields}; the {@code metadata} documents are checked for
     * their JSON type only (their content keeps its lenient reader), and {@code metadata.version} not at all.
     */
    private static final class Strict {

        /** A CURIE by shape: a prefix and a local part, e.g. {@code MS:1000511}; the CV is not checked. */
        private static final Pattern CURIE = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]*:[^\\s:]+$");

        /** The {@code metadata} documents the reference reads as lists. */
        private static final List<String> METADATA_LISTS = List.of("cv_list", "scan_settings_list",
                "software_list", "sample_list", "instrument_configuration_list", "data_processing_method_list");

        /** The {@code metadata} documents the reference reads as objects. */
        private static final List<String> METADATA_OBJECTS = List.of("run", "file_description", "imaging");

        private final String source;

        Strict(String source) {
            this.source = source;
        }

        // ---- duplicate fields (streaming) ----

        /** An object or array of a struct level the duplicate check walks into. */
        private static final class Frame {
            /** The level of this object, or of the object items of this array. */
            private final Level level;
            private final String location;
            private final boolean array;
            private final Set<String> seen = new HashSet<>();
            /** The last field name of this object. */
            private String field;
            /** The {@code name} of this file entry, once read. */
            private String entryName;
            /** The number of items of this array so far. */
            private int items;

            Frame(Level level, String location, boolean array) {
                this.level = level;
                this.location = location;
                this.array = array;
            }

            String location() {
                return entryName == null ? location : location + " (" + entryName + ")";
            }
        }

        /**
         * Rejects a known field given twice in the same object (serde's "duplicate field"), at the four struct
         * levels of {@link Level}; {@code column_mapping} and {@code metadata_mapping} count as one field. A JSON
         * tree keeps the last duplicate silently, so this streams over the bytes. Duplicates of unknown keys and
         * inside {@code metadata} (a map in the reference, which keeps the last) are accepted, which is why
         * Jackson's global duplicate detection is not used.
         */
        void rejectDuplicateFields(byte[] json) throws IOException {
            try (JsonParser p = new ObjectMapper().getFactory().createParser(json)) {
                Deque<Frame> stack = new ArrayDeque<>();
                JsonToken t;
                while ((t = p.nextToken()) != null) {
                    Frame parent = stack.peek();
                    if (t == JsonToken.FIELD_NAME) {
                        field(parent, p.currentName());
                    } else if (t.isStructEnd()) {
                        stack.pop();
                    } else {
                        Frame child = child(parent, t, p);
                        if (child != null) {
                            stack.push(child);
                        } else if (t.isStructStart()) {
                            p.skipChildren();
                        }
                    }
                }
            }
        }

        private void field(Frame object, String name) {
            object.field = name;
            String canonical = object.level == Level.ENTRY && name.equals("metadata_mapping")
                    ? "column_mapping" : name;
            if (object.level.fields.contains(canonical) && !object.seen.add(canonical)) {
                throw fail(object.location(), "duplicate field " + canonical);
            }
        }

        /** The frame of the value at token {@code t} under {@code parent}, or {@code null} if it is not walked. */
        private static Frame child(Frame parent, JsonToken t, JsonParser p) throws IOException {
            if (parent == null) {
                return t == JsonToken.START_OBJECT ? new Frame(Level.ROOT, "root", false) : null;
            }
            if (parent.array) {
                int index = parent.items++;
                return t == JsonToken.START_OBJECT
                        ? new Frame(parent.level, parent.location + "[" + index + "]", false) : null;
            }
            if (t == JsonToken.START_ARRAY) {
                Level items = itemLevel(parent.level, parent.field);
                if (items == null) {
                    return null;
                }
                return new Frame(items, parent.level == Level.ROOT ? parent.field
                        : parent.location() + "." + parent.field, true);
            }
            if (parent.level == Level.ENTRY && t == JsonToken.VALUE_STRING && parent.field.equals("name")) {
                parent.entryName = p.getText();
            }
            return null;
        }

        /** The level of the object items of the array in field {@code field} of a {@code level} object. */
        private static Level itemLevel(Level level, String field) {
            if (level == Level.ROOT) {
                return field.equals("files") ? Level.ENTRY : null;
            }
            if (level == Level.ENTRY) {
                return switch (field) {
                    case "column_mapping", "metadata_mapping" -> Level.MAPPING;
                    case "parameters" -> Level.PARAM;
                    default -> null;
                };
            }
            return null;
        }

        // ---- types (tree) ----

        void check(JsonNode root) {
            if (!root.isObject()) {
                throw fail("root", "not an object" + found(root));
            }
            JsonNode files = root.get("files");
            if (files == null) {
                throw fail("files", "absent");
            }
            if (!files.isArray()) {
                throw fail("files", "not an array" + found(files));
            }
            JsonNode meta = root.get("metadata");
            if (meta == null) {
                throw fail("metadata", "absent");
            }
            if (!meta.isObject()) {
                throw fail("metadata", "not an object" + found(meta));
            }
            for (int i = 0; i < files.size(); i++) {
                checkEntry(i, files.get(i));
            }
            for (String key : METADATA_LISTS) {
                JsonNode v = meta.get(key);
                if (v != null && !v.isArray()) {
                    throw fail("metadata." + key, "not an array" + found(v));
                }
            }
            for (String key : METADATA_OBJECTS) {
                JsonNode v = meta.get(key);
                if (v != null && !v.isObject()) {
                    throw fail("metadata." + key, "not an object" + found(v));
                }
            }
        }

        private void checkEntry(int i, JsonNode f) {
            String at = "files[" + i + "]";
            if (!f.isObject()) {
                throw fail(at, "not an object" + found(f));
            }
            String name = string(f, "name");
            at += " (" + (name != null ? name : string(f, "entity_type") + "/" + string(f, "data_kind")) + ")";
            requireString(at, f, "name");
            requireString(at, f, "entity_type");
            requireString(at, f, "data_kind");
            String key = f.has("column_mapping") ? "column_mapping" : "metadata_mapping";
            JsonNode mapping = f.get(key);
            if (mapping != null) {
                requireArray(at + "." + key, mapping);
                for (int j = 0; j < mapping.size(); j++) {
                    checkMapping(at + "." + key + "[" + j + "]", mapping.get(j));
                }
            }
            JsonNode params = f.get("parameters");
            if (params != null) {
                requireArray(at + ".parameters", params);
                for (int j = 0; j < params.size(); j++) {
                    checkParam(at + ".parameters[" + j + "]", params.get(j));
                }
            }
            JsonNode checksum = f.get("checksum");
            if (checksum != null && !checksum.isNull() && !checksum.isTextual()) {
                throw fail(at + ".checksum", "not a string or null" + found(checksum));
            }
        }

        private void checkMapping(String at, JsonNode c) {
            if (!c.isObject()) {
                throw fail(at, "not an object" + found(c));
            }
            requireString(at, c, "name");
            JsonNode path = c.get("path");
            if (path == null) {
                throw fail(at + ".path", "absent");
            }
            if (!path.isTextual() && !isStringArray(path)) {
                throw fail(at + ".path", "not a string or an array of strings" + found(path));
            }
            requireCurieOrNull(at, c, "accession");
            JsonNode unit = c.get("unit");
            if (unit != null && !unit.isNull() && !isCurie(unit) && !isStringArray(unit)) {
                throw fail(at + ".unit", "not a CURIE, null or an array of strings" + found(unit));
            }
            JsonNode marker = c.get("term_marker");
            if (marker != null && !marker.isBoolean()) {
                throw fail(at + ".term_marker", "not a boolean" + found(marker));
            }
        }

        private void checkParam(String at, JsonNode p) {
            if (!p.isObject()) {
                throw fail(at, "not an object" + found(p));
            }
            JsonNode name = p.get("name");
            if (name != null && !name.isNull() && !name.isTextual()) {
                throw fail(at + ".name", "not a string or null" + found(name));
            }
            requireCurieOrNull(at, p, "accession");
            requireCurieOrNull(at, p, "unit");
        }

        private void requireString(String at, JsonNode object, String field) {
            JsonNode v = object.get(field);
            if (v == null) {
                throw fail(at + "." + field, "absent");
            }
            if (!v.isTextual()) {
                throw fail(at + "." + field, v.isNull() ? "null" : "not a string" + found(v));
            }
        }

        private void requireArray(String at, JsonNode v) {
            if (!v.isArray()) {
                throw fail(at, "not an array" + found(v));
            }
        }

        /** The key is required (serde has no default for it); its value is a CURIE-shaped string or null. */
        private void requireCurieOrNull(String at, JsonNode object, String field) {
            JsonNode v = object.get(field);
            if (v == null) {
                throw fail(at + "." + field, "absent");
            }
            if (!v.isNull() && !isCurie(v)) {
                throw fail(at + "." + field, "not a CURIE or null" + found(v));
            }
        }

        private static boolean isCurie(JsonNode v) {
            return v.isTextual() && CURIE.matcher(v.textValue()).matches();
        }

        private static boolean isStringArray(JsonNode v) {
            if (!v.isArray()) {
                return false;
            }
            for (JsonNode s : v) {
                if (!s.isTextual()) {
                    return false;
                }
            }
            return true;
        }

        /** The value of {@code field} if it is a JSON string, else {@code null}. */
        private static String string(JsonNode object, String field) {
            JsonNode v = object.get(field);
            return v != null && v.isTextual() ? v.textValue() : null;
        }

        /** What was found instead: the string itself, or the JSON type. */
        private static String found(JsonNode v) {
            return " (found " + (v.isTextual() ? v.toString() : v.getNodeType().name().toLowerCase(Locale.ROOT))
                    + ")";
        }

        private MzPeakException fail(String location, String problem) {
            return new MzPeakException("mzpeak_index.json " + location + ": " + problem + " in " + source);
        }
    }
}
