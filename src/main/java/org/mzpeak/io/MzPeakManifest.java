package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The {@code mzpeak_index.json} manifest. Maps each member Parquet file to its role
 * {@code (entity_type, data_kind)} so readers resolve files by role rather than by hardcoded filename.
 */
public final class MzPeakManifest {

    /** A single file entry from the manifest. */
    public record Entry(String name, String entityType, String dataKind) {
    }

    private final List<Entry> files;

    private MzPeakManifest(List<Entry> files) {
        this.files = List.copyOf(files);
    }

    public List<Entry> files() {
        return files;
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
            return new MzPeakManifest(entries);
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

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
