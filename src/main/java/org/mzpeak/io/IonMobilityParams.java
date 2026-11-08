package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.model.CvTerms;
import org.mzpeak.model.Param;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The typed ion mobility columns ({@code ion_mobility_value}, {@code ion_mobility_type}) that writers promote out of
 * a row's {@code parameters} list, turned back into a {@link Param} so that callers see one form whatever the file
 * generation.
 */
final class IonMobilityParams {

    private static final String F_ION_MOBILITY_VALUE = "ion_mobility_value";
    private static final String F_ION_MOBILITY_TYPE = "ion_mobility_type";

    /** The type of a value whose row names none: MS:1002476 'ion mobility drift time'. */
    private static final String DEFAULT_TYPE = "MS:1002476";

    /** The name of the Param of a type {@link #NAMES_AND_UNITS} does not list. */
    private static final String OTHER_NAME = "ion mobility value";

    /**
     * The PSI-MS name and unit of the Param of each type the Rust reader names (visitor.rs:878-920): drift time in
     * milliseconds (UO:0000028), inverse reduced ion mobility in volt-second per square centimeter (MS:1002814), the
     * FAIMS and SelexION compensation voltages in volts (UO:0000218).
     */
    private static final Map<String, String[]> NAMES_AND_UNITS = Map.of(
            "MS:1002476", new String[] {"ion mobility drift time", "UO:0000028"},
            "MS:1002815", new String[] {"inverse reduced ion mobility drift time", "MS:1002814"},
            "MS:1001581", new String[] {"FAIMS compensation voltage", "UO:0000218"},
            "MS:1003371", new String[] {"SELEXION compensation voltage", "UO:0000218"});

    private IonMobilityParams() {
    }

    /**
     * {@code params} plus the row's typed ion mobility as {@code Param(name, type, value, unit)}, appended when
     * {@code params} hold no ion mobility type ({@link CvTerms#ION_MOBILITY_TYPES}) and the row has a finite value. A
     * null or blank type is {@code MS:1002476}. The name and unit are those the Rust reader gives the type: ion
     * mobility drift time (UO:0000028 millisecond), inverse reduced ion mobility drift time (MS:1002814), FAIMS
     * compensation voltage and SELEXION compensation voltage (UO:0000218 volt); any other type is named
     * {@code ion mobility value} and has no unit. Returns {@code params} itself when nothing is appended.
     */
    static List<Param> withTypedIonMobility(FacetRow row, List<Param> params) {
        if (params.stream().anyMatch(p -> CvTerms.ION_MOBILITY_TYPES.contains(p.accession()))) {
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
        String[] nameAndUnit = NAMES_AND_UNITS.get(accession);
        out.add(nameAndUnit == null ? new Param(OTHER_NAME, accession, value, null)
                : new Param(nameAndUnit[0], accession, value, nameAndUnit[1]));
        return out;
    }
}
