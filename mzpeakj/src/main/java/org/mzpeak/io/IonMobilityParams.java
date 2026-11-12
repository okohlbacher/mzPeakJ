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
     * The unit the reader gives a typed ion mobility of {@code type}, or {@code null} when {@code type} is not one of
     * the four types it names ({@link #NAMES_AND_UNITS}). The split writer promotes only those (V-24).
     *
     * @param type a CURIE, not {@code null}
     */
    static String unitOf(String type) {
        String[] nameAndUnit = NAMES_AND_UNITS.get(type);
        return nameAndUnit == null ? null : nameAndUnit[1];
    }

    /**
     * {@code params} plus the row's typed ion mobility as {@code Param(name, type, value, unit)}, appended when
     * {@code params} hold no ion mobility type ({@link CvTerms#ION_MOBILITY_TYPES}) and the row has a finite value. The
     * name and unit are those the Rust reader gives the type: ion mobility drift time (UO:0000028 millisecond),
     * inverse reduced ion mobility drift time (MS:1002814), FAIMS compensation voltage and SELEXION compensation
     * voltage (UO:0000218 volt); any other type is named {@code ion mobility value} and has no unit. Returns
     * {@code params} itself when nothing is appended.
     *
     * @throws MzPeakException if such a value has a null or blank type (D-8; the Rust reader panics there,
     *                         visitor.rs:879)
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
        if (type == null || type.isBlank()) {
            throw new MzPeakException(columns.member() + ": " + row.facet() + " row with "
                    + FacetRow.keyColumn(g.getType(), row.facet()) + " " + row.key() + " has " + F_ION_MOBILITY_VALUE
                    + " " + value + " but no " + F_ION_MOBILITY_TYPE);
        }
        List<Param> out = new ArrayList<>(params);
        String[] nameAndUnit = NAMES_AND_UNITS.get(type);
        out.add(nameAndUnit == null ? new Param(OTHER_NAME, type, value, null)
                : new Param(nameAndUnit[0], type, value, nameAndUnit[1]));
        return out;
    }
}
