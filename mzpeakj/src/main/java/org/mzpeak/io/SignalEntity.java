package org.mzpeak.io;

/**
 * The entity a signal member stores arrays for. It names the member's array index footer key
 * ({@code <key>_array_index}), the default index field ({@code <key>_index}) and the main axis the entity is
 * sorted by.
 */
enum SignalEntity {

    SPECTRUM("spectrum", "MS:1000514", "m/z"),
    CHROMATOGRAM("chromatogram", "MS:1000595", "time"),
    WAVELENGTH_SPECTRUM("wavelength_spectrum", "MS:1000617", "wavelength");

    /**
     * One entity of a signal member in error messages: {@code toString()} is its index field and index, such as
     * {@code spectrum_index 5}.
     */
    record Ref(SignalEntity entity, long index) {

        @Override
        public String toString() {
            return entity.indexField() + " " + index;
        }
    }

    private final String key;
    private final String defaultAxis;
    private final String axisName;

    SignalEntity(String key, String defaultAxis, String axisName) {
        this.key = key;
        this.defaultAxis = defaultAxis;
        this.axisName = axisName;
    }

    /** The entity name used in footer keys, index fields and an index entry's {@code context}. */
    String key() {
        return key;
    }

    /** The array type CURIE of the main axis: m/z, time or wavelength. */
    String defaultAxis() {
        return defaultAxis;
    }

    /** The name of the main axis in error messages: m/z, time or wavelength. */
    String axisName() {
        return axisName;
    }

    /** The entity {@code index} of this kind, for error messages. */
    Ref ref(long index) {
        return new Ref(this, index);
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
