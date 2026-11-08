package org.mzpeak.io;

/**
 * The entity a signal member stores arrays for. It names the member's array index footer key
 * ({@code <key>_array_index}), the default index field ({@code <key>_index}) and the main axis the entity is
 * sorted by.
 */
enum SignalEntity {

    SPECTRUM("spectrum", "MS:1000514"),
    CHROMATOGRAM("chromatogram", "MS:1000595"),
    WAVELENGTH_SPECTRUM("wavelength_spectrum", "MS:1000617");

    private final String key;
    private final String defaultAxis;

    SignalEntity(String key, String defaultAxis) {
        this.key = key;
        this.defaultAxis = defaultAxis;
    }

    /** The entity name used in footer keys, index fields and an index entry's {@code context}. */
    String key() {
        return key;
    }

    /** The array type CURIE of the main axis: m/z, time or wavelength. */
    String defaultAxis() {
        return defaultAxis;
    }

    /** The index field used when the array index has no {@code index} entry, as in the pin-era files. */
    String indexField() {
        return key + "_index";
    }

    /** The footer key-value key holding the array index JSON. */
    String arrayIndexKey() {
        return key + "_array_index";
    }
}
