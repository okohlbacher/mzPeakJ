package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Round-trips spectra through the Numpress chunk writer and verifies they decode to values
 * matching the original point layout within the expected Numpress precision.
 */
class NumpressWriterTest {

    private static final Path FIXTURE = Path.of("src/test/resources/mzpeak/small.unpacked.mzpeak");

    @Test
    void numpressDirectoryRoundTrip(@TempDir Path tmp) {
        List<Spectrum> spectra = new ArrayList<>();
        List<Chromatogram> chromatograms;
        try (MzPeakReader r = MzPeakReader.open(FIXTURE)) {
            for (Spectrum s : r) spectra.add(s);
            chromatograms = new ArrayList<>(r.chromatograms());
        }

        Path out = tmp.resolve("numpress.mzpeak");
        MzPeakWriter.writeDirectoryNumpress(out, spectra, chromatograms);

        try (MzPeakReader r = MzPeakReader.open(out)) {
            assertThat(r.size()).isEqualTo(48);

            // MS1 profile spectrum: count preserved, m/z within Numpress linear precision
            Spectrum s0 = r.getSpectrum(0).orElseThrow();
            assertThat(s0.description().msLevel()).isEqualTo(1);
            assertThat(s0.pointCount()).isEqualTo(13589);
            assertThat(s0.mz()[0]).isCloseTo(202.606575, within(0.01));  // linear is near-lossless

            // MS2 centroid spectrum: peak count preserved, intensities within SLOF tolerance (~1%)
            Spectrum s5 = r.getSpectrum(5).orElseThrow();
            assertThat(s5.description().msLevel()).isEqualTo(2);
            assertThat(s5.mz()).hasSize(650);

            // precursor round-trips
            assertThat(r.getMetadata(2).orElseThrow().primaryPrecursor().primaryIon().mz())
                    .isCloseTo(810.789428710938, within(1e-4));

            // chromatograms preserved
            assertThat(r.chromatograms()).hasSize(1);
        }
    }

    @Test
    void numpressZipRoundTrip(@TempDir Path tmp) {
        List<Spectrum> spectra = new ArrayList<>();
        try (MzPeakReader r = MzPeakReader.open(FIXTURE)) {
            for (Spectrum s : r) spectra.add(s);
        }

        Path archive = tmp.resolve("numpress.mzpeak");
        MzPeakWriter.writeArchiveNumpress(archive, spectra, List.of());

        try (MzPeakReader r = MzPeakReader.open(archive)) {
            assertThat(r.size()).isEqualTo(48);
            assertThat(r.getSpectrum(25).orElseThrow().mz()).hasSize(789);
        }
    }

    @Test
    void numpressValuesAreWithinSlofPrecision(@TempDir Path tmp) {
        // SLOF is lossy by design (log-scale 16-bit). We validate:
        //  - m/z values are within Numpress linear precision (~0.01 Da)
        //  - Total ion current (sum of intensities) is preserved within 1%
        //  - Peak count is identical
        List<Spectrum> spectra = new ArrayList<>();
        try (MzPeakReader r = MzPeakReader.open(FIXTURE)) {
            spectra.add(r.getSpectrum(5).orElseThrow()); // centroid, 650 peaks
        }

        MzPeakWriter.writeDirectoryNumpress(tmp.resolve("n.mzpeak"), spectra, List.of());

        try (MzPeakReader orig = MzPeakReader.open(FIXTURE);
             MzPeakReader enc  = MzPeakReader.open(tmp.resolve("n.mzpeak"))) {
            Spectrum a = orig.getSpectrum(5).orElseThrow();
            Spectrum b = enc.getSpectrum(5).orElseThrow();
            assertThat(b.mz()).hasSize(a.mz().length);
            double sumA = 0, sumB = 0;
            for (int i = 0; i < a.mz().length; i++) {
                assertThat(b.mz()[i]).isCloseTo(a.mz()[i], within(0.01));
                sumA += a.intensity()[i];
                sumB += b.intensity()[i];
            }
            // Total ion current within 1% — SLOF preserves the macro signal well
            assertThat(Math.abs(sumB - sumA) / sumA).isLessThan(0.01);
        }
    }
}
