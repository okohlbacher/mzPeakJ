package org.mzpeak.model;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link WavelengthSpectrum}: a has_uv spectrum, null arrays, arrays of different length, and its scans. */
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

    @Test
    void scansAreAnUnmodifiableCopy() {
        ScanEvent scan = new ScanEvent(0.5, null, null, null, 1, null, List.of(new ScanWindow(200, 400)), List.of());
        List<ScanEvent> given = new ArrayList<>(List.of(scan));
        WavelengthSpectrum spectrum = new WavelengthSpectrum(0, "id", 0.5, null, null, null, null, given, null);
        given.clear();

        assertThat(spectrum.scans()).containsExactly(scan);
        assertThatThrownBy(() -> spectrum.scans().add(scan)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(spectrum.parameters()).isEmpty();
        assertThat(spectrum.auxiliaryArrays()).isEmpty();
    }

    @Test
    void nullScansAreEmptyAndANullScanIsRejected() {
        assertThat(new WavelengthSpectrum(0, "id", 1.0, null, null, null, null, null, null).scans()).isEmpty();
        assertThatThrownBy(() -> new WavelengthSpectrum(0, "id", 1.0, null, null, null, null,
                Arrays.asList((ScanEvent) null), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theOldConstructorsGiveNoScans() {
        assertThat(new WavelengthSpectrum(0, "id", 1.0, null, null, null).scans()).isEmpty();
        assertThat(new WavelengthSpectrum(0, "id", 1.0, null, null, null, List.of()).scans()).isEmpty();
        assertThat(new WavelengthSpectrum(0, "id", 1.0, null, null, null, List.of(), List.of()).scans()).isEmpty();
    }
}
