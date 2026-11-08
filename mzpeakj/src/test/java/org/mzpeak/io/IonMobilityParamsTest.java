package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mzpeak.model.CvTerms;
import org.mzpeak.model.Param;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RD-11: the no-duplicate path of {@link IonMobilityParams#withTypedIonMobility}. A row's typed ion mobility is
 * appended to its Params only when they hold no ion mobility type ({@link CvTerms#ION_MOBILITY_TYPES}), on a
 * constructed packed scan struct.
 *
 * <p>Documented changes against the hand-written list: the array terms, the charge array MS:1002478 and the unit
 * MS:1002814 no longer keep the typed cells out, and the real children of MS:1002892 now do. FN-8: the appended Param
 * has the PSI-MS name and unit the Rust reader gives its type, not {@code ion mobility value} without a unit.
 */
class IonMobilityParamsTest {

    private static final MessageType SCAN = Types.buildMessage()
            .optional(PrimitiveTypeName.DOUBLE).named("ion_mobility_value")
            .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("ion_mobility_type")
            .named("scan");

    private static final Param TYPED =
            new Param("inverse reduced ion mobility drift time", "MS:1002815", 1.25, "MS:1002814");

    /**
     * Params that hold {@code accession} keep the row's typed cells out exactly when the accession is recognised as
     * an ion mobility term; otherwise the typed value is appended after them.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // the former hand-written list: only MS:1002476 is an ion mobility type (documented change)
            "MS:1002476, true",
            "MS:1002477, false",
            "MS:1002478, false",
            "MS:1002814, false",
            "MS:1002816, false",
            "MS:1003006, false",
            "MS:1003007, false",
            "MS:1003153, false",
            "MS:1003155, false",
            "MS:1003156, false",
            // the children of MS:1002892 the list missed (documented change)
            "MS:1001581, true",
            "MS:1002815, true",
            "MS:1003371, true",
            "MS:1003394, true",
            "MS:1003450, true",
            "MS:1003451, true",
            // ion mobility arrays the list missed
            "MS:1002893, false",
            "MS:1003008, false",
            "MS:1003154, false"
    })
    void aRecognisedParamKeepsTheTypedCellsOut(String accession, boolean recognised) {
        List<Param> params = List.of(new Param("window group", null, 1L, null),
                new Param("stored ion mobility", accession, 2.5, null));
        List<Param> out = IonMobilityParams.withTypedIonMobility(row(1.25, "MS:1002815"), params);
        if (recognised) {
            assertThat(out).isSameAs(params);
        } else {
            List<Param> expected = new ArrayList<>(params);
            expected.add(TYPED);
            assertThat(out).isEqualTo(expected);
        }
    }

    /** A user param (null accession) does not keep the typed cells out, whatever its name. */
    @Test
    void aUserParamWithANullAccessionDoesNotKeepTheTypedCellsOut() {
        List<Param> params = List.of(new Param("ion mobility value", null, 2.5, null));
        assertThat(IonMobilityParams.withTypedIonMobility(row(1.25, "MS:1002815"), params))
                .containsExactly(params.get(0), TYPED);
    }

    /** Without a typed value nothing is appended, and the given list is returned as is. */
    @Test
    void noTypedValueReturnsTheParams() {
        List<Param> params = List.of(new Param("window group", null, 1L, null));
        assertThat(IonMobilityParams.withTypedIonMobility(row(null, "MS:1002815"), params)).isSameAs(params);
        assertThat(IonMobilityParams.withTypedIonMobility(row(Double.NaN, null), params)).isSameAs(params);
        assertThat(IonMobilityParams.withTypedIonMobility(row(0.5, null), params))
                .containsExactly(params.get(0), new Param("ion mobility drift time", "MS:1002476", 0.5, "UO:0000028"));
        assertThat(IonMobilityParams.withTypedIonMobility(row(0.5, " "), params))
                .containsExactly(params.get(0), new Param("ion mobility drift time", "MS:1002476", 0.5, "UO:0000028"));
    }

    /** The appended Param has the name and unit of visitor.rs:878-920; any other type keeps the generic name. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "null", value = {
            "MS:1002476, ion mobility drift time,                 UO:0000028",
            "MS:1002815, inverse reduced ion mobility drift time, MS:1002814",
            "MS:1001581, FAIMS compensation voltage,              UO:0000218",
            "MS:1003371, SELEXION compensation voltage,           UO:0000218",
            "MS:1003394, ion mobility value,                      null",
            "MS:1003450, ion mobility value,                      null"
    })
    void theTypedParamHasThePsiNameAndUnitOfItsType(String type, String name, String unit) {
        assertThat(IonMobilityParams.withTypedIonMobility(row(-3.5, type), List.of()))
                .containsExactly(new Param(name, type, -3.5, unit));
    }

    private static FacetRow row(Double value, String type) {
        Group g = new SimpleGroup(SCAN);
        if (value != null) {
            g.add("ion_mobility_value", value);
        }
        if (type != null) {
            g.add("ion_mobility_type", type);
        }
        return new FacetRow(Facet.SPECTRUM_SCAN, g, ColumnResolver.packed(SCAN, Facet.SPECTRUM_SCAN));
    }
}
