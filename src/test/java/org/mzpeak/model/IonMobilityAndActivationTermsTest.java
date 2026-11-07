package org.mzpeak.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RD-11: the ion-mobility and dissociation-method CV tables behind {@link ScanEvent#ionMobilityValue()},
 * {@link ScanEvent#ionMobilityType()}, {@link SelectedIon#ionMobilityValue()}, {@link Activation#methods()} and
 * {@link Activation#summary()}.
 *
 * <p>The characterization cases pin the behaviour of the hand-written lists, one accession at a time.
 */
class IonMobilityAndActivationTermsTest {

    private static final String COLLISION_ENERGY = "MS:1000045";
    private static final String ELECTRONVOLT = "UO:0000266";
    private static final Param ENERGY = new Param("collision energy", COLLISION_ENERGY, 35.0, ELECTRONVOLT);

    // ---- characterization: ion mobility ----------------------------------------------------------------------

    /**
     * A scan or selected ion whose only CV param is {@code accession}, valued 1.5 and preceded by a user param: the
     * value and type are reported exactly when the accession is recognised as an ion mobility term.
     */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            // the hand-written list
            "MS:1002476, ion mobility drift time,                          true",
            "MS:1002477, mean ion mobility drift time array,               true",
            "MS:1002478, mean charge array,                                true",
            "MS:1002814, volt-second per square centimeter,                true",
            "MS:1002816, mean ion mobility array,                          true",
            "MS:1003006, mean inverse reduced ion mobility array,          true",
            "MS:1003007, raw ion mobility array,                           true",
            "MS:1003153, raw ion mobility drift time array,                true",
            "MS:1003155, deconvoluted inverse reduced ion mobility array,  true",
            "MS:1003156, deconvoluted ion mobility drift time array,       true",
            // children of MS:1002892 the list misses
            "MS:1001581, FAIMS compensation voltage,                       false",
            "MS:1002815, inverse reduced ion mobility,                     false",
            "MS:1003371, SelexION compensation voltage,                    false",
            "MS:1003394, SelexION separation voltage,                      false",
            "MS:1003450, FAIMS compensation voltage ramp start,            false",
            "MS:1003451, FAIMS compensation voltage ramp end,              false",
            // ion mobility arrays the list misses
            "MS:1002893, ion mobility array,                               false",
            "MS:1003008, raw inverse reduced ion mobility array,           false",
            "MS:1003154, deconvoluted ion mobility array,                  false"
    })
    void ionMobilityIsReportedForTheRecognisedAccessions(String accession, String name, boolean recognised) {
        List<Param> params = List.of(new Param("window group", null, 1L, null), new Param(name, accession, 1.5, null));
        ScanEvent scan = scan(params);
        SelectedIon ion = new SelectedIon(500.0, 2, null, params);
        if (recognised) {
            assertThat(scan.ionMobilityValue()).hasValue(1.5);
            assertThat(scan.ionMobilityType()).isEqualTo(accession);
            assertThat(ion.ionMobilityValue()).hasValue(1.5);
        } else {
            assertThat(scan.ionMobilityValue()).isEmpty();
            assertThat(scan.ionMobilityType()).isNull();
            assertThat(ion.ionMobilityValue()).isEmpty();
        }
    }

    /** A user param (null accession) never carries ion mobility, whatever its name or value. */
    @Test
    void aUserParamWithANullAccessionIsNoIonMobility() {
        List<Param> params = List.of(new Param("ion mobility drift time", null, 1.5, null));
        assertThat(scan(params).ionMobilityValue()).isEmpty();
        assertThat(scan(params).ionMobilityType()).isNull();
        assertThat(new SelectedIon(500.0, null, null, params).ionMobilityValue()).isEmpty();
        assertThat(scan(List.of()).ionMobilityValue()).isEmpty();
        assertThat(scan(List.of()).ionMobilityType()).isNull();
    }

    /** A value that is not a finite number gives no value, but the scan still names the type. */
    @Test
    void aNonFiniteOrNonNumericValueGivesNoValueButTheScanKeepsTheType() {
        for (Object value : new Object[] {Double.NaN, Double.POSITIVE_INFINITY, "1.5", null}) {
            List<Param> params = List.of(new Param("ion mobility drift time", "MS:1002476", value, null));
            assertThat(scan(params).ionMobilityValue()).as("%s", value).isEmpty();
            assertThat(scan(params).ionMobilityType()).as("%s", value).isEqualTo("MS:1002476");
            assertThat(new SelectedIon(500.0, null, null, params).ionMobilityValue()).as("%s", value).isEmpty();
        }
        // a later recognised param with a finite value still gives the value
        List<Param> params = List.of(new Param("ion mobility drift time", "MS:1002476", Double.NaN, null),
                new Param("ion mobility drift time", "MS:1002476", 2.0, null));
        assertThat(scan(params).ionMobilityValue()).hasValue(2.0);
        assertThat(new SelectedIon(500.0, null, null, params).ionMobilityValue()).hasValue(2.0);
    }

    /**
     * With two recognised params a scan reports the one whose accession comes first in its table, a selected ion
     * the first in list order.
     */
    @Test
    void twoRecognisedParamsAScanPrefersTheTableOrderASelectedIonTheListOrder() {
        List<Param> params = List.of(new Param("mean inverse reduced ion mobility array", "MS:1003006", 2.0, null),
                new Param("ion mobility drift time", "MS:1002476", 1.0, null));
        assertThat(scan(params).ionMobilityValue()).hasValue(1.0);
        assertThat(scan(params).ionMobilityType()).isEqualTo("MS:1002476");
        assertThat(new SelectedIon(500.0, null, null, params).ionMobilityValue()).hasValue(2.0);
    }

    /**
     * diaPASEF.ref spectrum 1: the scan's parameters list carries MS:1002815, and the reader appends the typed
     * ion_mobility_value/ion_mobility_type cells as a second MS:1002815 Param because the list's term is not
     * recognised as an ion mobility term.
     */
    @Test
    void diaPasefRefScanParamsOfSpectrum1() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("diaPASEF.ref.mzpeak"))) {
            ScanEvent scan = reader.getMetadata(1).orElseThrow().scans().get(0);
            assertThat(scan.parameters()).containsExactly(
                    new Param("window group", null, 1L, null),
                    new Param("inverse reduced ion mobility", "MS:1002815", 1.2517460582524271, "MS:1002814"),
                    new Param("ion mobility value", "MS:1002815", 1.2517460582524271, null));
        }
    }

    // ---- characterization: dissociation methods --------------------------------------------------------------

    /**
     * An activation holding one method Param and the collision energy: the method is listed by {@code methods()}
     * and named by {@code summary()} exactly when its accession is recognised as a dissociation method.
     */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "MS:1000133, collision-induced dissociation,                          true",
            "MS:1000422, beam-type collision-induced dissociation,                true",
            "MS:1000598, electron transfer dissociation,                          true",
            "MS:1000250, electron capture dissociation,                           true",
            "MS:1000262, infrared multiphoton dissociation,                       true",
            "MS:1000435, photodissociation,                                       true",
            "MS:1000044, dissociation method,                                     true",
            "MS:1002631, electron-transfer/higher-energy collision dissociation,  true",
            "MS:1002678, supplemental beam-type collision-induced dissociation,   true",
            "MS:1003181, combined dissociation method,                            true",
            // descendants of MS:1000044 the list misses
            "MS:1002679, supplemental collision-induced dissociation,             false",
            "MS:1003246, ultraviolet photodissociation,                           false",
            "MS:1003294, electron activated dissociation,                         false",
            // not a dissociation method
            "MS:1000045, collision energy,                                        false"
    })
    void methodsAndSummaryForEachAccession(String accession, String name, boolean recognised) {
        Param method = new Param(name, accession, null, null);
        Activation activation = new Activation(List.of(method, ENERGY));
        if (recognised) {
            assertThat(activation.methods()).containsExactly(method);
            assertThat(activation.summary()).isEqualTo(name + " @ 35.0 eV");
        } else {
            assertThat(activation.methods()).isEmpty();
            assertThat(activation.summary()).isEqualTo("custom");
        }
        assertThat(activation.parameters()).containsExactly(method, ENERGY);
    }

    @Test
    void summaryFormsAndMethodOrder() {
        Param etd = new Param("electron transfer dissociation", "MS:1000598", null, null);
        Param sbcid = new Param("supplemental beam-type collision-induced dissociation", "MS:1002678", null, null);
        Param user = new Param("free text", null, "pulsed", null);
        assertThat(Activation.EMPTY.methods()).isEmpty();
        assertThat(Activation.EMPTY.summary()).isEqualTo("(none)");
        assertThat(new Activation(List.of(ENERGY)).summary()).isEqualTo("custom");
        assertThat(new Activation(List.of(user)).methods()).isEmpty();
        assertThat(new Activation(List.of(user)).summary()).isEqualTo("custom");
        assertThat(new Activation(List.of(etd)).summary()).isEqualTo("electron transfer dissociation");
        // a method without a name is shown by its accession
        assertThat(new Activation(List.of(new Param(null, "MS:1000133", null, null), ENERGY)).summary())
                .isEqualTo("MS:1000133 @ 35.0 eV");
        // several methods keep their list order and are joined with ' + '
        Activation ethcd = new Activation(List.of(sbcid, user, ENERGY, etd));
        assertThat(ethcd.methods()).containsExactly(sbcid, etd);
        assertThat(ethcd.summary()).isEqualTo("supplemental beam-type collision-induced dissociation"
                + " + electron transfer dissociation @ 35.0 eV");
    }

    /**
     * Current small names the method only by the dissociation_method CURIE, so its summary shows the CURIE; legacy
     * small names it in its parameters list.
     */
    @Test
    void smallSpectrum2ActivationSummaryCurrentAndLegacy() {
        try (MzPeakReader current = MzPeakReader.open(Fixtures.current("small.mzpeak"));
             MzPeakReader legacy = MzPeakReader.open(Fixtures.legacy("small.mzpeak"))) {
            Activation c = current.getMetadata(2).orElseThrow().primaryPrecursor().activation();
            Activation l = legacy.getMetadata(2).orElseThrow().primaryPrecursor().activation();
            assertThat(c.parameters()).containsExactly(new Param(null, "MS:1000133", null, null), ENERGY);
            assertThat(c.summary()).isEqualTo("MS:1000133 @ 35.0 eV");
            assertThat(l.summary()).isEqualTo("collision-induced dissociation @ 35.0 eV");
        }
    }

    private static ScanEvent scan(List<Param> params) {
        return new ScanEvent(1.0, null, null, null, null, null, List.of(), params);
    }
}
