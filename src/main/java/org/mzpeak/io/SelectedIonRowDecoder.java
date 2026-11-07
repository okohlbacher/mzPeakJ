package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.model.SelectedIon;

/**
 * Decodes one selected-ion row of the spectrum metadata into a {@link SelectedIon}; columns are resolved by
 * accession.
 */
final class SelectedIonRowDecoder {

    // selected_ion facet
    private static final String SELECTED_MZ = "MS:1000744";
    private static final String CHARGE = "MS:1000041";
    private static final String SELECTED_INTENSITY = "MS:1000042";

    private SelectedIonRowDecoder() {
    }

    /** Decodes one selected-ion row, or returns {@code null} when its m/z is null. */
    static SelectedIon decode(FacetRow ion) {
        ColumnResolver columns = ion.columns();
        Group g = ion.group();
        Double mz = columns.doubleValue(g, columns.resolve(SELECTED_MZ));
        if (mz == null) {
            return null;
        }
        return new SelectedIon(mz,
                columns.intValue(g, columns.resolve(CHARGE)),
                columns.doubleValue(g, columns.resolve(SELECTED_INTENSITY)),
                columns.params(g));
    }
}
