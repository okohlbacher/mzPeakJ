package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.model.Param;

import java.util.ArrayList;
import java.util.List;

/**
 * The typed ion mobility columns ({@code ion_mobility_value}, {@code ion_mobility_type}) that writers promote out of
 * a row's {@code parameters} list, turned back into a {@link Param} so that callers see one form whatever the file
 * generation.
 */
final class IonMobilityParams {

    private static final String F_ION_MOBILITY_VALUE = "ion_mobility_value";
    private static final String F_ION_MOBILITY_TYPE = "ion_mobility_type";

    /** The type of a value whose row names none: mean inverse reduced ion mobility (1/K0). */
    private static final String DEFAULT_TYPE = "MS:1002476";

    private IonMobilityParams() {
    }

    /**
     * {@code params} plus the row's typed ion mobility as {@code Param("ion mobility value", type, value)}, appended
     * when {@code params} hold no ion mobility term and the row has a finite value. A null or blank type is
     * {@code MS:1002476}. Returns {@code params} itself when nothing is appended.
     */
    static List<Param> withTypedIonMobility(FacetRow row, List<Param> params) {
        if (params.stream().anyMatch(p -> isIonMobilityAccession(p.accession()))) {
            return params;
        }
        ColumnResolver columns = row.columns();
        Group g = row.group();
        Double value = columns.doubleValue(g, columns.resolveName(F_ION_MOBILITY_VALUE));
        if (value == null || !Double.isFinite(value)) {
            return params;
        }
        String type = columns.stringValue(g, columns.resolveName(F_ION_MOBILITY_TYPE));
        String accession = type != null && !type.isBlank() ? type : DEFAULT_TYPE;
        List<Param> out = new ArrayList<>(params);
        out.add(new Param("ion mobility value", accession, value, null));
        return out;
    }

    private static boolean isIonMobilityAccession(String acc) {
        if (acc == null) return false;
        return acc.equals("MS:1002476") || acc.equals("MS:1002477") || acc.equals("MS:1002478")
            || acc.equals("MS:1002814") || acc.equals("MS:1002816")
            || acc.equals("MS:1003006") || acc.equals("MS:1003007")
            || acc.equals("MS:1003153") || acc.equals("MS:1003155") || acc.equals("MS:1003156");
    }
}
