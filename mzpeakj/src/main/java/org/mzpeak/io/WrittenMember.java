package org.mzpeak.io;

import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;

import java.util.List;
import java.util.Objects;

/**
 * A member {@link DatasetWriter} has written, as {@link ManifestWriter} lists it: its name relative to the dataset
 * root, its role and its column mapping. The manifest's {@link ManifestWriter.Dialect} decides how the role is
 * spelled, and the checksum is computed from the closed file when the manifest is written.
 *
 * @param name          the member name, relative to the dataset root
 * @param entity        the entity the member describes
 * @param kind          the facet of the entity the member holds
 * @param columnMapping the member's {@code column_mapping} in the order it is written; copied
 */
record WrittenMember(String name, EntityType entity, DataKind kind, List<ColumnMapping> columnMapping) {

    WrittenMember {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(kind, "kind");
        columnMapping = List.copyOf(columnMapping);
    }

    /** A member with an empty column mapping. */
    WrittenMember(String name, EntityType entity, DataKind kind) {
        this(name, entity, kind, List.of());
    }
}
