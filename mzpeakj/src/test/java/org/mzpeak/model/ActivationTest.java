package org.mzpeak.model;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Activation}: the activation of a fixture MS2 precursor, the null parameter list and a collision energy that
 * is not a finite number.
 */
class ActivationTest {

    private static final Param CID = new Param("collision-induced dissociation", "MS:1000133", null, null);

    @Test
    void readsTheCidActivationOfAFixturePrecursor() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            Activation activation = reader.metadataAt(5).primaryPrecursor().activation();
            assertThat(activation.methods()).containsExactly(CID);
            assertThat(activation.collisionEnergy()).hasValue(35.0);
            assertThat(activation.summary()).isEqualTo("collision-induced dissociation @ 35.0 eV");
        }
    }

    @Test
    void aNullParameterListIsEmpty() {
        Activation activation = new Activation(null);
        assertThat(activation.parameters()).isEmpty();
        assertThat(activation.isEmpty()).isTrue();
        assertThat(activation.collisionEnergy()).isEmpty();
        assertThat(activation.summary()).isEqualTo("(none)");
        assertThat(activation).isEqualTo(Activation.EMPTY);
    }

    @Test
    void aNonFiniteCollisionEnergyIsAbsent() {
        for (double energy : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            Activation activation = new Activation(
                    List.of(CID, new Param("collision energy", "MS:1000045", energy, "UO:0000266")));
            assertThat(activation.collisionEnergy()).as("energy %s", energy).isEmpty();
            assertThat(activation.summary()).as("energy %s", energy).isEqualTo("collision-induced dissociation");
        }
    }
}
