package org.mzpeak.model;

import java.util.List;

/**
 * Precursor information for an MSn spectrum.
 *
 * @param precursorIndex  index of the spectrum this precursor was isolated from; may be {@code null}
 * @param precursorId     native id of the precursor spectrum; may be {@code null}
 * @param isolationWindow isolation window; may be {@code null}
 * @param selectedIons    selected ions (usually one); never {@code null}, may be empty
 */
public record Precursor(Long precursorIndex,
                        String precursorId,
                        IsolationWindow isolationWindow,
                        List<SelectedIon> selectedIons) {

    public Precursor {
        selectedIons = selectedIons == null ? List.of() : List.copyOf(selectedIons);
    }

    /** The first selected ion, or {@code null} if none. */
    public SelectedIon primaryIon() {
        return selectedIons.isEmpty() ? null : selectedIons.get(0);
    }
}
