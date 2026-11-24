package org.mzpeak.model;

import java.util.List;

/**
 * Precursor information for an MSn spectrum.
 *
 * @param precursorIndex  index of the spectrum this precursor was isolated from; may be {@code null}
 * @param precursorId     native id of the precursor spectrum; may be {@code null}
 * @param isolationWindow isolation window; may be {@code null}
 * @param selectedIons    selected ions (usually one); never {@code null}, may be empty
 * @param activation      activation description (dissociation method + energy); never {@code null}
 * @param msLevel         the MS level of the precursor (the precursor row's {@code ms_level}), or {@code null} when
 *                        the file gives none; read only, mzPeakJ does not write it
 */
public record Precursor(Long precursorIndex,
                        String precursorId,
                        IsolationWindow isolationWindow,
                        List<SelectedIon> selectedIons,
                        Activation activation,
                        Integer msLevel) {

    public Precursor {
        selectedIons = selectedIons == null ? List.of() : List.copyOf(selectedIons);
        activation = activation == null ? Activation.EMPTY : activation;
    }

    /** A precursor without MS level ({@link #msLevel} {@code null}). */
    public Precursor(Long precursorIndex, String precursorId, IsolationWindow isolationWindow,
                     List<SelectedIon> selectedIons, Activation activation) {
        this(precursorIndex, precursorId, isolationWindow, selectedIons, activation, null);
    }


    /** The first selected ion, or {@code null} if none. */
    public SelectedIon primaryIon() {
        return selectedIons.isEmpty() ? null : selectedIons.get(0);
    }
}
