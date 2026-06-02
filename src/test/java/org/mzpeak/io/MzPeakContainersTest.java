package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Cross-container + cross-implementation tests. The expected peak/point counts mirror the Rust
 * {@code mzpeak_prototyping} reference test (small.* fixtures): spectrum 0 → 13589 points (profile,
 * reconstructed), spectrum 5 → 650 peaks, spectrum 25 → 789 peaks. Verified for the unpacked directory,
 * the packed ZIP, and the delta-chunked ZIP.
 */
class MzPeakContainersTest {

    private static final String BASE = "src/test/resources/mzpeak/";

    @ParameterizedTest
    @ValueSource(strings = {
            "small.unpacked.mzpeak",   // unpacked directory, point layout
            "small.mzpeak",            // single-file STORED ZIP, point layout
            "small.chunked.mzpeak",    // single-file ZIP, delta-chunk layout
            "small.numpress.mzpeak"    // single-file ZIP, MS-Numpress linear(mz)+SLOF(intensity) chunk layout
    })
    void readsConsistentlyAcrossContainers(String fixture) {
        try (MzPeakReader reader = MzPeakReader.open(Path.of(BASE + fixture))) {
            assertThat(reader.size()).isEqualTo(48);

            Spectrum s0 = reader.getSpectrum(0).orElseThrow();
            assertThat(s0.description().msLevel()).isEqualTo(1);
            assertThat(s0.pointCount()).isEqualTo(13589);

            Spectrum s5 = reader.getSpectrum(5).orElseThrow();
            assertThat(s5.peaks()).hasSize(650);

            Spectrum s25 = reader.getSpectrum(25).orElseThrow();
            assertThat(s25.peaks()).hasSize(789);
        }
    }

    @Test
    void packedZipMatchesUnpackedForPrecursor() {
        try (MzPeakReader zip = MzPeakReader.open(Path.of(BASE + "small.mzpeak"))) {
            Spectrum s2 = zip.getSpectrum(2).orElseThrow();
            assertThat(s2.description().primaryPrecursor().primaryIon().mz())
                    .isCloseTo(810.789428710938, within(1e-6));
        }
    }

    @Test
    void chunkedArraysMatchPointLayoutForCentroidSpectra() {
        // Centroid spectra have no null-marking, so chunk delta-decode must reproduce the point-layout arrays
        // exactly (m/z and intensity) — guards against a chunk-only silent mismatch / misalignment.
        try (MzPeakReader point = MzPeakReader.open(Path.of(BASE + "small.unpacked.mzpeak"));
             MzPeakReader chunked = MzPeakReader.open(Path.of(BASE + "small.chunked.mzpeak"))) {
            for (long idx : new long[] {5, 25}) {
                Spectrum a = point.getSpectrum(idx).orElseThrow();
                Spectrum b = chunked.getSpectrum(idx).orElseThrow();
                assertThat(b.mz()).hasSize(a.mz().length);
                assertThat(b.intensity()).hasSize(a.intensity().length);
                for (int i = 0; i < a.mz().length; i++) {
                    assertThat(b.mz()[i]).isCloseTo(a.mz()[i], within(1e-6));
                    assertThat(b.intensity()[i]).isCloseTo(a.intensity()[i], within(1e-3));
                }
            }
        }
    }

    @Test
    void chunkedProfileMatchesPointLayoutCountAndSignal() {
        // For a profile spectrum, null-marked m/z are reconstructed approximately, so individual array
        // positions may differ; but the point count and the total ion signal (sum of intensities) must agree.
        try (MzPeakReader point = MzPeakReader.open(Path.of(BASE + "small.unpacked.mzpeak"));
             MzPeakReader chunked = MzPeakReader.open(Path.of(BASE + "small.chunked.mzpeak"))) {
            Spectrum a = point.getSpectrum(0).orElseThrow();
            Spectrum b = chunked.getSpectrum(0).orElseThrow();
            assertThat(b.pointCount()).isEqualTo(a.pointCount());
            double sumA = 0;
            double sumB = 0;
            for (double v : a.intensity()) {
                sumA += v;
            }
            for (double v : b.intensity()) {
                sumB += v;
            }
            assertThat(sumB).isCloseTo(sumA, within(sumA * 1e-6));
        }
    }

    @Test
    void readsTicChromatogram() {
        try (MzPeakReader reader = MzPeakReader.open(Path.of(BASE + "small.unpacked.mzpeak"))) {
            assertThat(reader.chromatograms()).hasSize(1);
            Chromatogram tic = reader.getChromatogram(0).orElseThrow();
            assertThat(tic.id()).isEqualTo("TIC");
            assertThat(tic.typeCurie()).isEqualTo("MS:1000235");
            assertThat(tic.size()).isEqualTo(48);
            assertThat(reader.getChromatogramById("TIC")).isPresent();
            // time axis is monotonic non-decreasing
            double[] t = tic.time();
            for (int i = 1; i < t.length; i++) {
                assertThat(t[i]).isGreaterThanOrEqualTo(t[i - 1]);
            }
        }
    }

    @Test
    void lookupByScanNumberIdAndTime() {
        try (MzPeakReader reader = MzPeakReader.open(Path.of(BASE + "small.unpacked.mzpeak"))) {
            // nativeID of spectrum 0 is "...scan=1"
            Spectrum byScan = reader.getSpectrumByScanNumber(1).orElseThrow();
            assertThat(byScan.index()).isEqualTo(0);

            String id0 = reader.metadataAt(0).id();
            assertThat(reader.getSpectrumById(id0).orElseThrow().index()).isEqualTo(0);

            double rt0 = reader.metadataAt(0).retentionTime();
            assertThat(reader.getSpectrumByTime(rt0).orElseThrow().index()).isEqualTo(0);
        }
    }

    @Test
    void numpressDecodesCloseToPointLayout() {
        // MS-Numpress is lossy (linear m/z is near-lossless; SLOF intensity is log-scaled 16-bit), so a
        // centroid spectrum decodes to the same peak count and m/z within a tiny tolerance, with intensities
        // within ~1% relative.
        try (MzPeakReader point = MzPeakReader.open(Path.of(BASE + "small.unpacked.mzpeak"));
             MzPeakReader numpress = MzPeakReader.open(Path.of(BASE + "small.numpress.mzpeak"))) {
            Spectrum a = point.getSpectrum(5).orElseThrow();
            Spectrum b = numpress.getSpectrum(5).orElseThrow();
            assertThat(b.mz()).hasSize(a.mz().length);
            for (int i = 0; i < a.mz().length; i++) {
                assertThat(b.mz()[i]).isCloseTo(a.mz()[i], within(5e-4));
                if (a.intensity()[i] > 0) {
                    assertThat(b.intensity()[i]).isCloseTo(a.intensity()[i], within(0.01 * a.intensity()[i]));
                }
            }
        }
    }

    @Test
    void opensDatasetWithWavelengthSpectra() {
        try (MzPeakReader reader = MzPeakReader.open(Path.of(BASE + "has_uv.mzpeak"))) {
            assertThat(reader.size()).isGreaterThan(0);
            // wavelength (UV) spectra are ignored in milestone 1; MS spectra still read
            Spectrum first = reader.getSpectrumAt(0);
            assertThat(first).isNotNull();
        }
    }
}
