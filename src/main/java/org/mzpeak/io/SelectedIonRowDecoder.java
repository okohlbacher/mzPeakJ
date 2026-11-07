package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.model.Param;
import org.mzpeak.model.SelectedIon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decodes one selected-ion row of the spectrum metadata into a {@link SelectedIon}; columns are resolved by
 * accession.
 */
final class SelectedIonRowDecoder {

    // selected_ion facet
    private static final String SELECTED_MZ = "MS:1000744";
    private static final String CHARGE = "MS:1000041";
    private static final String SELECTED_INTENSITY = "MS:1000042";
    /** The typed ion mobility columns, which {@link IonMobilityParams} reads. */
    private static final String F_ION_MOBILITY_VALUE = "ion_mobility_value";
    private static final String F_ION_MOBILITY_TYPE = "ion_mobility_type";

    private SelectedIonRowDecoder() {
    }

    /**
     * Decodes one selected-ion row, or returns {@code null} when its m/z is null. Its Params are the
     * {@code parameters} list, then the typed ion mobility ({@link IonMobilityParams}, in both layouts), then the
     * scope's leftovers ({@link ColumnResolver#leftovers}, split layout only).
     */
    static SelectedIon decode(FacetRow ion) {
        ColumnResolver columns = ion.columns();
        Group g = ion.group();
        String mzField = columns.resolve(SELECTED_MZ);
        Double mz = columns.doubleValue(g, mzField);
        if (mz == null) {
            return null;
        }
        String chargeField = columns.resolve(CHARGE);
        String intensityField = columns.resolve(SELECTED_INTENSITY);
        List<Param> params = IonMobilityParams.withTypedIonMobility(ion, columns.params(g));
        Set<String> consumed = new HashSet<>();
        for (String field : new String[] {mzField, chargeField, intensityField, F_ION_MOBILITY_VALUE,
                F_ION_MOBILITY_TYPE}) {
            if (field != null) {
                consumed.add(field);
            }
        }
        List<Param> leftovers = columns.leftovers(g, consumed);
        if (!leftovers.isEmpty()) {
            params = new ArrayList<>(params);
            params.addAll(leftovers);
        }
        return new SelectedIon(mz,
                columns.intValue(g, chargeField),
                columns.doubleValue(g, intensityField),
                params);
    }
}
