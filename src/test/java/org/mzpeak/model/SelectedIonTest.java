package org.mzpeak.model;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link SelectedIon}: a fixture ion, and a null parameter list. */
class SelectedIonTest {

    @Test
    void theFixtureIonHasNoParameters() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            SelectedIon ion = reader.metadataAt(5).primaryPrecursor().primaryIon();
            assertThat(ion.mz()).isEqualTo(558.868957519531);
            assertThat(ion.intensity()).isEqualTo(202177.84375);
            assertThat(ion.ionMobilityValue()).isEmpty();
        }
    }

    @Test
    void aNullParameterListIsEmpty() {
        SelectedIon ion = new SelectedIon(500.25, 2, null, null);
        assertThat(ion.parameters()).isEmpty();
        assertThat(ion.ionMobilityValue()).isEmpty();
        assertThat(ion).isEqualTo(new SelectedIon(500.25, 2, null, List.of()));
    }
}
