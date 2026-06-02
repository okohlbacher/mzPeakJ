package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** Parse {@code mzpeak_index.json} from an unpacked mzPeak directory. */
    public static MzPeakManifest fromDirectory(Path directory) {
        Path manifestPath = directory.resolve("mzpeak_index.json");
        if (!Files.isRegularFile(manifestPath)) {
            throw new MzPeakException("Not an unpacked mzPeak directory (no mzpeak_index.json): " + directory);
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(Files.readAllBytes(manifestPath));
            JsonNode filesNode = root.get("files");
            if (filesNode == null || !filesNode.isArray()) {
                throw new MzPeakException("mzpeak_index.json has no 'files' array: " + manifestPath);
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
            throw new MzPeakException("Failed to parse " + manifestPath, e);
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
