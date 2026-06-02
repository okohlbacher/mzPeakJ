package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.mzpeak.model.Activation;
import org.mzpeak.model.CvTerms;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Verifies that per-precursor activation parameters (dissociation method + collision energy) are decoded
 * from the {@code precursor.activation.parameters} column, against the known values for the small fixture:
 * spectrum 2 → CID (MS:1000133) at 35.0 eV.
 */
class ActivationParamsTest {

    private static final Path FIXTURE = Path.of("src/test/resources/mzpeak/small.unpacked.mzpeak");

    @Test
    void decodesActivationMethodAndEnergy() {
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            SpectrumDescription d = reader.getMetadata(2).orElseThrow();
            assertThat(d.msLevel()).isEqualTo(2);

            Precursor precursor = d.primaryPrecursor();
            assertThat(precursor).isNotNull();

            Activation act = precursor.activation();
            assertThat(act.isEmpty()).isFalse();

            // method should contain CID
            assertThat(act.methods())
                    .anyMatch(p -> p.hasAccession(CvTerms.CID));

            // collision energy = 35 eV
            assertThat(act.collisionEnergy()).isPresent();
            assertThat(act.collisionEnergy().getAsDouble()).isCloseTo(35.0, within(1e-3));

            // summary rendered correctly
            assertThat(act.summary()).contains("collision-induced dissociation");
            assertThat(act.summary()).contains("35.0 eV");
        }
    }

    @Test
    void ms1SpectraHaveEmptyActivation() {
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            SpectrumDescription d = reader.getMetadata(0).orElseThrow();
            assertThat(d.msLevel()).isEqualTo(1);
            assertThat(d.precursors()).isEmpty();
        }
    }

    @Test
    void activationReadFromPackedZip() {
        try (MzPeakReader reader = MzPeakReader.open(
                Path.of("src/test/resources/mzpeak/small.mzpeak"))) {
            Activation act = reader.getMetadata(2).orElseThrow()
                    .primaryPrecursor().activation();
            assertThat(act.collisionEnergy().getAsDouble()).isCloseTo(35.0, within(1e-3));
        }
    }
}
