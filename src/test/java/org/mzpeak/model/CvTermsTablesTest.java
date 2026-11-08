package org.mzpeak.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FN-15 (CV5, RD-11 note 2): every member of the {@link CvTerms#DISSOCIATION_METHODS} and
 * {@link CvTerms#ION_MOBILITY_ARRAYS} tables, pinned in the default build. The oracle is psi-ms 4.2.11 (mzdata v0.68.0
 * {@code cv/psi-ms.obo.gz}): the 22 non-obsolete descendants of MS:1000044 'dissociation method' with their names, and
 * MS:1002893 'ion mobility array' with its 9 descendants. {@link IonMobilityAndActivationTermsTest} regenerates the
 * tables from that file when {@code -Dmzpeak.psims.obo} names it.
 *
 * <p>MZ-1: {@link CvTerms#ARRAY_TYPES}, the 31 non-obsolete descendants of MS:1000513 'binary data array' in the same
 * file, with their names, and {@link CvTerms#DEFAULT_ARRAY_UNITS}, the 14 units the mzML design (§5.7) chooses.
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

    // ---- MZ-1: array types and their default units ---------------------------------------------------------------

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "MS:1000514, m/z array",
            "MS:1000515, intensity array",
            "MS:1000516, charge array",
            "MS:1000517, signal to noise array",
            "MS:1000595, time array",
            "MS:1000617, wavelength array",
            "MS:1000786, non-standard data array",
            "MS:1000820, flow rate array",
            "MS:1000821, pressure array",
            "MS:1000822, temperature array",
            "MS:1002477, mean ion mobility drift time array",
            "MS:1002478, mean charge array",
            "MS:1002529, resolution array",
            "MS:1002530, baseline array",
            "MS:1002742, noise array",
            "MS:1002743, sampled noise m/z array",
            "MS:1002744, sampled noise intensity array",
            "MS:1002745, sampled noise baseline array",
            "MS:1002816, mean ion mobility array",
            "MS:1002893, ion mobility array",
            "MS:1003006, mean inverse reduced ion mobility array",
            "MS:1003007, raw ion mobility array",
            "MS:1003008, raw inverse reduced ion mobility array",
            "MS:1003143, mass array",
            "MS:1003153, raw ion mobility drift time array",
            "MS:1003154, deconvoluted ion mobility array",
            "MS:1003155, deconvoluted inverse reduced ion mobility array",
            "MS:1003156, deconvoluted ion mobility drift time array",
            "MS:1003157, scanning quadrupole position lower bound m/z array",
            "MS:1003158, scanning quadrupole position upper bound m/z array",
            "MS:1003870, index array"
    })
    void everyArrayTypeHasItsPsiMsName(String accession, String name) {
        assertThat(CvTerms.ARRAY_TYPES).containsEntry(accession, name);
    }

    /**
     * The 31 non-obsolete descendants of MS:1000513 'binary data array', in accession order, every ion mobility array
     * among them, MS:1000513 itself not.
     */
    @Test
    void theArrayTypeTableHasExactlyTheThirtyOneTermsInAccessionOrder() {
        assertThat(CvTerms.ARRAY_TYPES).hasSize(31).doesNotContainKey("MS:1000513");
        assertThat(CvTerms.ARRAY_TYPES.get("MS:1000514")).isEqualTo("m/z array");
        assertThat(CvTerms.ARRAY_TYPES.get("MS:1003006")).isEqualTo("mean inverse reduced ion mobility array");
        assertThat(List.copyOf(CvTerms.ARRAY_TYPES.keySet())).isSorted().containsAll(CvTerms.ION_MOBILITY_ARRAYS);
    }

    /**
     * Exactly the 14 default units of the mzML design §5.7, in its order: none for the generic ion mobility array
     * MS:1002893, milliseconds for the drift-time arrays.
     */
    @Test
    void theDefaultArrayUnitsAreExactlyTheFourteenChosenOnes() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("MS:1000514", "MS:1000040");
        expected.put("MS:1000515", "MS:1000131");
        expected.put("MS:1000595", "UO:0000031");
        expected.put("MS:1000617", "UO:0000018");
        for (String driftTime : List.of("MS:1002477", "MS:1002816", "MS:1003007", "MS:1003153", "MS:1003154",
                "MS:1003156")) {
            expected.put(driftTime, "UO:0000028");
        }
        for (String inverseReduced : List.of("MS:1003006", "MS:1003008", "MS:1003155")) {
            expected.put(inverseReduced, "MS:1002814");
        }
        expected.put("ms level", "UO:0000186");

        assertThat(CvTerms.DEFAULT_ARRAY_UNITS).containsExactlyEntriesOf(expected);
        assertThat(CvTerms.DEFAULT_ARRAY_UNITS.get("MS:1002893")).isNull();
        assertThat(CvTerms.DEFAULT_ARRAY_UNITS.get("MS:1002477")).isEqualTo("UO:0000028");
        assertThat(CvTerms.ARRAY_TYPES.keySet()).containsAll(
                expected.keySet().stream().filter(k -> k.startsWith("MS:")).toList());
    }

    @Test
    void bothArrayTablesAreUnmodifiable() {
        assertThatThrownBy(() -> CvTerms.ARRAY_TYPES.put("MS:1000513", "binary data array"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> CvTerms.DEFAULT_ARRAY_UNITS.put("MS:1002893", "UO:0000028"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> CvTerms.DEFAULT_ARRAY_UNITS.remove("ms level"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(CvTerms.ARRAY_TYPES.containsKey(null)).isFalse();
        assertThat(CvTerms.DEFAULT_ARRAY_UNITS.containsKey(null)).isFalse();
    }
}
