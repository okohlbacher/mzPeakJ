package org.mzpeak.io;

import org.mzpeak.io.MzPeakManifest.Entry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The nine metadata facets of an mzPeak dataset. In the split layout each facet is its own member, found by its
 * manifest role {@code (entity_type, data_kind)}. In the pin-era packed layout all facets of an entity are top-level
 * structs of the entity's {@code metadata} member, named {@link #packedStruct()}.
 */
enum Facet {

    SPECTRUM("spectrum", "metadata", "spectrum", "index"),
    SPECTRUM_SCAN("spectrum", "scans", "scan", "source_index", "scan_index"),
    SPECTRUM_PRECURSOR("spectrum", "precursors", "precursor", "source_index", "precursor_index"),
    SPECTRUM_SELECTED_ION("spectrum", "selected_ions", "selected_ion",
            "source_index", "precursor_index", "selected_ion_index"),
    CHROMATOGRAM("chromatogram", "metadata", "chromatogram", "index"),
    CHROMATOGRAM_PRECURSOR("chromatogram", "precursors", "precursor", "source_index", "precursor_index"),
    CHROMATOGRAM_SELECTED_ION("chromatogram", "selected_ions", "selected_ion",
            "source_index", "precursor_index", "selected_ion_index"),
    WAVELENGTH_SPECTRUM("wavelength_spectrum", "metadata", "spectrum", "index"),
    WAVELENGTH_SCAN("wavelength_spectrum", "scans", "scan", "source_index", "scan_index");

    private final String entityType;
    private final String dataKind;
    private final String packedStruct;
    private final List<String> keyColumns;

    Facet(String entityType, String dataKind, String packedStruct, String... keyColumns) {
        this.entityType = entityType;
        this.dataKind = dataKind;
        this.packedStruct = packedStruct;
        this.keyColumns = List.of(keyColumns);
    }

    /** The canonical {@code entity_type} of the facet's member, as current manifests spell it. */
    String entityType() {
        return entityType;
    }

    /** The canonical {@code data_kind} of the facet's member, as current manifests spell it. */
    String dataKind() {
        return dataKind;
    }

    /** The top-level struct that holds this facet in the packed layout. */
    String packedStruct() {
        return packedStruct;
    }

    /** {@code true} for the entity's own table, keyed by {@code index}; {@code false} for a facet of it. */
    boolean primary() {
        return "metadata".equals(dataKind);
    }

    /**
     * The facet's key columns: first the row key that ties a row to its entity ({@code index} in a primary facet,
     * else {@code source_index}), then the facet's own index columns.
     */
    List<String> keyColumns() {
        return keyColumns;
    }

    /**
     * The facet's member, found by role. Empty in the packed layout for every facet but the primary one, because
     * there all facets live in the primary member.
     */
    Optional<Entry> entry(MzPeakManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        return manifest.find(entityType, dataKind);
    }
}
