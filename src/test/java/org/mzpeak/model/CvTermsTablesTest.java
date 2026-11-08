package org.mzpeak.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FN-15 (CV5, RD-11 note 2): every member of the {@link CvTerms#DISSOCIATION_METHODS} and
 * {@link CvTerms#ION_MOBILITY_ARRAYS} tables, pinned in the default build. The oracle is psi-ms 4.2.11 (mzdata v0.68.0
 * {@code cv/psi-ms.obo.gz}): the 22 non-obsolete descendants of MS:1000044 'dissociation method' with their names, and
 * MS:1002893 'ion mobility array' with its 9 descendants. {@link IonMobilityAndActivationTermsTest} regenerates the
 * tables from that file when {@code -Dmzpeak.psims.obo} names it.
 */
class CvTermsTablesTest {

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "MS:1000133, collision-induced dissociation",
            "MS:1000134, plasma desorption",
            "MS:1000135, post-source decay",
            "MS:1000136, surface-induced dissociation",
            "MS:1000242, blackbody infrared radiative dissociation",
            "MS:1000250, electron capture dissociation",
            "MS:1000262, infrared multiphoton dissociation",
            "MS:1000282, sustained off-resonance irradiation",
            "MS:1000422, beam-type collision-induced dissociation",
            "MS:1000433, low-energy collision-induced dissociation",
            "MS:1000435, photodissociation",
            "MS:1000598, electron transfer dissociation",
            "MS:1000599, pulsed q dissociation",
            "MS:1001880, in-source collision-induced dissociation",
            "MS:1002000, LIFT",
            "MS:1002472, trap-type collision-induced dissociation",
            "MS:1002481, higher energy beam-type collision-induced dissociation",
            "MS:1002678, supplemental beam-type collision-induced dissociation",
            "MS:1002679, supplemental collision-induced dissociation",
            "MS:1003246, ultraviolet photodissociation",
            "MS:1003247, negative electron transfer dissociation",
            "MS:1003294, electron activated dissociation"
    })
    void everyDissociationMethodHasItsPsiMsName(String accession, String name) {
        assertThat(CvTerms.DISSOCIATION_METHODS).containsEntry(accession, name);
    }

    @Test
    void theDissociationMethodTableHasExactlyTheTwentyTwoTerms() {
        assertThat(CvTerms.DISSOCIATION_METHODS).hasSize(22);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MS:1002477", "MS:1002816", "MS:1002893", "MS:1003006", "MS:1003007", "MS:1003008",
            "MS:1003153", "MS:1003154", "MS:1003155", "MS:1003156"})
    void everyIonMobilityArrayIsListed(String accession) {
        assertThat(CvTerms.ION_MOBILITY_ARRAYS).contains(accession);
    }

    @Test
    void theIonMobilityArrayTableHasExactlyTheTenTerms() {
        assertThat(CvTerms.ION_MOBILITY_ARRAYS).hasSize(10);
    }
}
