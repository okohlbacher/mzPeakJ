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
 */
public record Precursor(Long precursorIndex,
                        String precursorId,
                        IsolationWindow isolationWindow,
                        List<SelectedIon> selectedIons,
                        Activation activation) {

    public Precursor {
        selectedIons = selectedIons == null ? List.of() : List.copyOf(selectedIons);
        activation = activation == null ? Activation.EMPTY : activation;
    }

    /** Backwards-compatible constructor without activation (defaults to empty). */
    public Precursor(Long precursorIndex, String precursorId, IsolationWindow isolationWindow,
                     List<SelectedIon> selectedIons) {
        this(precursorIndex, precursorId, isolationWindow, selectedIons, Activation.EMPTY);
    }

    /** The first selected ion, or {@code null} if none. */
    public SelectedIon primaryIon() {
        return selectedIons.isEmpty() ? null : selectedIons.get(0);
    }
}
