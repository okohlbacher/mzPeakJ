package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.WavelengthSpectrum;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Wavelength (UV/DAD) spectra + multi-chromatogram coverage, against the has_uv fixture. */
class WavelengthAndChromatogramTest {

    private static final String BASE = "src/test/resources/mzpeak/";

    @Test
    void readsWavelengthSpectra() {
        try (MzPeakReader reader = MzPeakReader.open(Path.of(BASE + "has_uv.mzpeak"))) {
            List<WavelengthSpectrum> ws = reader.wavelengthSpectra();
            assertThat(ws).hasSize(520);

            WavelengthSpectrum w0 = reader.getWavelengthSpectrum(0).orElseThrow();
            assertThat(w0.id()).isEqualTo("merged=212 row=0");
            assertThat(w0.time()).isCloseTo(0.0018, within(1e-3));
            assertThat(w0.lambdaMax()).isCloseTo(374.0, within(1e-6));
            assertThat(w0.size()).isEqualTo(96);
            assertThat(w0.wavelength()[0]).isCloseTo(210.0, within(1e-6));
            assertThat(w0.intensity()[0]).isCloseTo(-0.11, within(1e-2)); // absorbance can be negative
        }
    }

    @Test
    void readsMultipleChromatograms() {
        try (MzPeakReader reader = MzPeakReader.open(Path.of(BASE + "has_uv.mzpeak"))) {
            assertThat(reader.chromatograms()).hasSize(2);

            Chromatogram tic = reader.getChromatogram(0).orElseThrow();
            assertThat(tic.id()).isEqualTo("TIC");
            assertThat(tic.typeCurie()).isEqualTo("MS:1000235");

            Chromatogram dad = reader.getChromatogram(1).orElseThrow();
            assertThat(dad.id()).startsWith("DAD1");
            assertThat(dad.typeCurie()).isEqualTo("MS:1000812"); // absorption chromatogram
            assertThat(dad.size()).isGreaterThan(0);
        }
    }

    @Test
    void datasetsWithoutWavelengthReturnEmpty() {
        try (MzPeakReader reader = MzPeakReader.open(Path.of(BASE + "small.unpacked.mzpeak"))) {
            assertThat(reader.wavelengthSpectra()).isEmpty();
            assertThat(reader.getWavelengthSpectrum(0)).isEmpty();
        }
    }
}
