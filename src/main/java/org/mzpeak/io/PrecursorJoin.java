package org.mzpeak.io;

import org.mzpeak.model.Activation;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds one spectrum's {@link Precursor}s from its precursor and selected-ion rows, in both layouts. The rows of one
 * spectrum already share their {@code source_index}, so the join key {@code (source_index, precursor_index)} comes
 * down to {@code precursor_index}, with null equal to null:
 * <ul>
 *   <li>precursors keep their file order;
 *   <li>a selected ion goes to the first precursor with its key, and one whose key no precursor has goes to the
 *       first precursor (the Rust reference falls back to the last one);
 *   <li>selected ions without any precursor row form a precursor-less {@link Precursor} (the Rust reference drops
 *       them).
 * </ul>
 */
final class PrecursorJoin {

    private static final String F_PRECURSOR_INDEX = "precursor_index";

    private PrecursorJoin() {
    }

    /** The precursors of one spectrum; either list is {@code null} when the spectrum has no such row. */
    static List<Precursor> join(List<FacetRow> precursorRows, List<FacetRow> selectedIonRows) {
        if (precursorRows == null && selectedIonRows == null) {
            return List.of();
        }
        List<PrecursorRowDecoder.PrecursorPart> precursors = new ArrayList<>();
        List<List<SelectedIon>> ionsOf = new ArrayList<>();
        if (precursorRows != null) {
            for (FacetRow row : precursorRows) {
                precursors.add(PrecursorRowDecoder.decode(row));
                ionsOf.add(new ArrayList<>());
            }
        }
        List<SelectedIon> orphans = new ArrayList<>();
        if (selectedIonRows != null) {
            for (FacetRow ion : selectedIonRows) {
                SelectedIon selectedIon = SelectedIonRowDecoder.decode(ion);
                if (selectedIon == null) {
                    continue;
                }
                if (precursors.isEmpty()) {
                    orphans.add(selectedIon);
                } else {
                    Long key = ion.columns().longValue(ion.group(), F_PRECURSOR_INDEX);
                    ionsOf.get(owner(precursors, key)).add(selectedIon);
                }
            }
        }
        if (precursors.isEmpty()) {
            // selected ions but no precursor record: surface a precursor carrying the ions
            return List.of(new Precursor(null, null, null, orphans, Activation.EMPTY));
        }
        List<Precursor> out = new ArrayList<>(precursors.size());
        for (int i = 0; i < precursors.size(); i++) {
            PrecursorRowDecoder.PrecursorPart precursor = precursors.get(i);
            out.add(new Precursor(
                    precursor.precursorIndex(),
                    precursor.precursorId(),
                    precursor.isolationWindow(),
                    ionsOf.get(i),
                    precursor.activation()));
        }
        return out;
    }

    /** The position of the first precursor whose precursor_index equals {@code key}, else 0. */
    private static int owner(List<PrecursorRowDecoder.PrecursorPart> precursors, Long key) {
        for (int i = 0; i < precursors.size(); i++) {
            if (Objects.equals(precursors.get(i).precursorIndex(), key)) {
                return i;
            }
        }
        return 0;
    }
}
