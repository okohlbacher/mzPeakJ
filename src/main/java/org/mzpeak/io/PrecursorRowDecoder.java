package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Activation;
import org.mzpeak.model.IsolationWindow;

/**
 * Decodes one {@code precursor} struct of the packed spectrum metadata layout into a {@link PrecursorPart}; the
 * selected ions are attached by {@link PrecursorJoin}.
 */
final class PrecursorRowDecoder {

    // precursor facet
    private static final String F_PRECURSOR_INDEX = "precursor_index";
    private static final String F_PRECURSOR_ID = "precursor_id";
    private static final String F_ACTIVATION = "activation";
    private static final String F_ISOLATION = "isolation_window";
    private static final String F_ISO_TARGET = "MS_1000827_isolation_window_target_mz";
    private static final String F_ISO_LOWER = "MS_1000828_isolation_window_lower_offset";
    private static final String F_ISO_UPPER = "MS_1000829_isolation_window_upper_offset";

    private PrecursorRowDecoder() {
    }

    /** Decodes one {@code precursor} struct. */
    static PrecursorPart decode(Group precursor) {
        Group iso = ParquetGroups.optGroup(precursor, F_ISOLATION);
        IsolationWindow window = iso == null ? null : new IsolationWindow(
                ParquetGroups.optDouble(iso, F_ISO_TARGET),
                ParquetGroups.optDouble(iso, F_ISO_LOWER),
                ParquetGroups.optDouble(iso, F_ISO_UPPER));
        Group activationGroup = ParquetGroups.optGroup(precursor, F_ACTIVATION);
        Activation activation = activationGroup == null
                ? Activation.EMPTY
                : new Activation(ParquetGroups.readParams(activationGroup, "parameters"));
        return new PrecursorPart(
                ParquetGroups.optLong(precursor, F_PRECURSOR_INDEX),
                ParquetGroups.optString(precursor, F_PRECURSOR_ID),
                window,
                activation);
    }

    /** One precursor record without its selected ions. */
    record PrecursorPart(Long precursorIndex, String precursorId, IsolationWindow isolationWindow,
                         Activation activation) {
    }
}
