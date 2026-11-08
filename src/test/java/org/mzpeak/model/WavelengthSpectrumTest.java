package org.mzpeak.model;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link WavelengthSpectrum}: a has_uv spectrum, null arrays, and arrays of different length. */
class WavelengthSpectrumTest {

    @Test
    void theFixtureSpectrumHasParallelArrays() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("has_uv.mzpeak"))) {
            assertThat(reader.wavelengthSpectra()).hasSize(520);
            WavelengthSpectrum first = reader.wavelengthSpectra().get(0);
            assertThat(first.size()).isEqualTo(96);
            assertThat(first.wavelength()).hasSize(96);
            assertThat(first.intensity()).hasSize(96);
        }
    }

    @Test
    void nullArraysAreEmpty() {
        WavelengthSpectrum spectrum = new WavelengthSpectrum(0, "id", 1.0, null, null, null);
        assertThat(spectrum.size()).isZero();
        assertThat(spectrum.wavelength()).isEmpty();
        assertThat(spectrum.intensity()).isEmpty();
    }

    @Test
    void arraysOfDifferentLengthAreRejected() {
        assertThatThrownBy(() -> new WavelengthSpectrum(0, "id", 1.0, 254.0,
                new double[] {200.0, 210.0}, new double[] {0.5}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("wavelength/intensity length mismatch: 2 vs 1");
        assertThatThrownBy(() -> new WavelengthSpectrum(0, "id", 1.0, null, null, new double[] {0.5}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("wavelength/intensity length mismatch: 0 vs 1");
        assertThatThrownBy(() -> new WavelengthSpectrum(0, "id", 1.0, null, new double[] {200.0}, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("wavelength/intensity length mismatch: 1 vs 0");
    }
}
