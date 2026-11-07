package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.SelectedIon;

/** Decodes one {@code selected_ion} struct of the packed spectrum metadata layout into a {@link SelectedIon}. */
final class SelectedIonRowDecoder {

    // selected_ion facet
    private static final String F_SELECTED_MZ = "MS_1000744_selected_ion_mz_unit_MS_1000040";
    private static final String F_CHARGE = "MS_1000041_charge_state";
    private static final String F_SELECTED_INTENSITY = "MS_1000042_intensity_unit_MS_1000131";

    private SelectedIonRowDecoder() {
    }

    /** Decodes one {@code selected_ion} struct, or returns {@code null} when its m/z is null. */
    static SelectedIon decode(Group ion) {
        Double mz = ParquetGroups.optDouble(ion, F_SELECTED_MZ);
        if (mz == null) {
            return null;
        }
        return new SelectedIon(mz,
                ParquetGroups.optInt(ion, F_CHARGE),
                ParquetGroups.optDouble(ion, F_SELECTED_INTENSITY),
                ParquetGroups.readParams(ion, "parameters"));
    }
}
