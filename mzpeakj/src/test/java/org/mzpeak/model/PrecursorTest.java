package org.mzpeak.model;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link Precursor}: the primary ion of a fixture precursor, and the defaults for null ions and activation. */
class PrecursorTest {

    @Test
    void theFixturePrecursorHasOnePrimaryIon() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            Precursor precursor = reader.metadataAt(5).primaryPrecursor();
            assertThat(precursor.precursorIndex()).isEqualTo(1L);
            assertThat(precursor.selectedIons()).hasSize(1);
            assertThat(precursor.primaryIon()).isSameAs(precursor.selectedIons().get(0));
            assertThat(precursor.primaryIon().mz()).isEqualTo(558.868957519531);
        }
    }

    @Test
    void nullIonsAndActivationBecomeEmpty() {
        Precursor precursor = new Precursor(null, null, null, null, null);
        assertThat(precursor.selectedIons()).isEmpty();
        assertThat(precursor.activation()).isSameAs(Activation.EMPTY);
        assertThat(precursor.primaryIon()).isNull();
    }

    @Test
    void theIonListIsCopied() {
        SelectedIon ion = new SelectedIon(500.25, 2, 1000.0, List.of());
        List<SelectedIon> ions = new ArrayList<>(List.of(ion));
        Precursor precursor = new Precursor(3L, "scan=4", null, ions, Activation.EMPTY);
        ions.clear();
        assertThat(precursor.selectedIons()).containsExactly(ion);
        assertThat(precursor.primaryIon()).isSameAs(ion);
    }
}
