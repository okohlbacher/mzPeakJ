package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.model.Activation;
import org.mzpeak.model.IsolationWindow;

/**
 * Decodes one precursor row of the spectrum metadata into a {@link PrecursorPart}; columns are resolved by
 * accession. The selected ions are attached by {@link PrecursorJoin}.
 */
final class PrecursorRowDecoder {

    // precursor facet
    private static final String F_PRECURSOR_INDEX = "precursor_index";
    private static final String F_PRECURSOR_ID = "precursor_id";
    private static final String F_ACTIVATION = "activation";
    private static final String F_ISOLATION = "isolation_window";
    private static final String ISO_TARGET = "MS:1000827";
    private static final String ISO_LOWER = "MS:1000828";
    private static final String ISO_UPPER = "MS:1000829";

    private PrecursorRowDecoder() {
    }

    /** Decodes one precursor row. */
    static PrecursorPart decode(FacetRow precursor) {
        FacetRow iso = precursor.child(F_ISOLATION);
        IsolationWindow window = iso == null ? null : isolationWindow(iso);
        FacetRow activationRow = precursor.child(F_ACTIVATION);
        Activation activation = activationRow == null
                ? Activation.EMPTY
                : new Activation(activationRow.columns().params(activationRow.group()));
        ColumnResolver columns = precursor.columns();
        Group g = precursor.group();
        return new PrecursorPart(
                columns.longValue(g, F_PRECURSOR_INDEX),
                columns.stringValue(g, F_PRECURSOR_ID),
                window,
                activation);
    }

    private static IsolationWindow isolationWindow(FacetRow iso) {
        ColumnResolver columns = iso.columns();
        Group g = iso.group();
        return new IsolationWindow(
                columns.doubleValue(g, columns.resolve(ISO_TARGET)),
                columns.doubleValue(g, columns.resolve(ISO_LOWER)),
                columns.doubleValue(g, columns.resolve(ISO_UPPER)));
    }

    /** One precursor record without its selected ions. */
    record PrecursorPart(Long precursorIndex, String precursorId, IsolationWindow isolationWindow,
                         Activation activation) {
    }
}
