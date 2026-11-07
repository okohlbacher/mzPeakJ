package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.model.Activation;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds one spectrum's {@link Precursor}s from its {@code precursor} and {@code selected_ion} structs: every
 * selected ion goes to the first precursor, and selected ions without a precursor record form a precursor-less
 * {@link Precursor}.
 */
final class PrecursorJoin {

    private PrecursorJoin() {
    }

    /** The precursors of one spectrum; either list is {@code null} when the spectrum has no such struct. */
    static List<Precursor> join(List<Group> precursorGroups, List<Group> selectedIonGroups) {
        if (precursorGroups == null && selectedIonGroups == null) {
            return List.of();
        }
        List<SelectedIon> ions = new ArrayList<>();
        if (selectedIonGroups != null) {
            for (Group ion : selectedIonGroups) {
                SelectedIon selectedIon = SelectedIonRowDecoder.decode(ion);
                if (selectedIon == null) {
                    continue;
                }
                ions.add(selectedIon);
            }
        }
        if (precursorGroups == null || precursorGroups.isEmpty()) {
            // selected ions but no precursor record: surface a precursor carrying the ions
            return List.of(new Precursor(null, null, null, ions, Activation.EMPTY));
        }
        // Build one Precursor per precursor record. All selected ions are attached to the first precursor;
        // real-world mzPeak spectra have exactly one precursor per MSn acquisition.
        List<Precursor> out = new ArrayList<>(precursorGroups.size());
        for (int i = 0; i < precursorGroups.size(); i++) {
            PrecursorRowDecoder.PrecursorPart precursor = PrecursorRowDecoder.decode(precursorGroups.get(i));
            out.add(new Precursor(
                    precursor.precursorIndex(),
                    precursor.precursorId(),
                    precursor.isolationWindow(),
                    i == 0 ? ions : List.of(),
                    precursor.activation()));
        }
        return out;
    }
}
