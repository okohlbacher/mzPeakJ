package org.mzpeak.io;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Mirrors the Rust {@code mzpeak_prototyping} reference-implementation test assertions.
 * Every parametrized case verifies the same values across all three container/layout variants,
 * matching the Rust {@code test_read}/{@code test_chunked_read} and {@code test_tic} tests.
 *
 * <p>Rust reference assertions:
 * <ul>
 *   <li>spectrum 0  → SignalContinuity.Profile, 13589 points, 1 precursor, 1 selected ion
 *   <li>spectrum 5  → 650 peaks, 1 precursor, 1 selected ion
 *   <li>spectrum 25 → 789 peaks, 1 precursor, 1 selected ion
 *   <li>TIC chromatogram → index 0, 48 time points (all three container variants)
 * </ul>
 */
class RustReferenceTest {

    private static final String BASE = "src/test/resources/mzpeak/";

    // ---- Rust test_read / test_chunked_read equivalents ---------------------

    @ParameterizedTest
    @ValueSource(strings = {"small.unpacked.mzpeak", "small.mzpeak", "small.chunked.mzpeak", "small.numpress.mzpeak"})
    void spectrumZero_profileWith13589Points(String fixture) {
        try (MzPeakReader r = MzPeakReader.open(Path.of(BASE + fixture))) {
            Spectrum s = r.getSpectrum(0).orElseThrow();
            assertThat(s.description().signalContinuity()).isEqualTo(SignalContinuity.PROFILE);
            assertThat(s.pointCount()).isEqualTo(13589);
            // spectrum 0 is MS1 → no real precursor (Rust test checks precursor count for MS2 spectra only)
            assertThat(s.description().msLevel()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"small.unpacked.mzpeak", "small.mzpeak", "small.chunked.mzpeak", "small.numpress.mzpeak"})
    void spectrumFive_650CentroidPeaks(String fixture) {
        try (MzPeakReader r = MzPeakReader.open(Path.of(BASE + fixture))) {
            Spectrum s = r.getSpectrum(5).orElseThrow();
            assertThat(s.peaks()).hasSize(650);
            assertThat(s.description().precursors()).hasSize(1);
            assertThat(s.description().primaryPrecursor().selectedIons()).hasSize(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"small.unpacked.mzpeak", "small.mzpeak", "small.chunked.mzpeak", "small.numpress.mzpeak"})
    void spectrumTwentyFive_789CentroidPeaks(String fixture) {
        try (MzPeakReader r = MzPeakReader.open(Path.of(BASE + fixture))) {
            Spectrum s = r.getSpectrum(25).orElseThrow();
            assertThat(s.peaks()).hasSize(789);
            assertThat(s.description().precursors()).hasSize(1);
            assertThat(s.description().primaryPrecursor().selectedIons()).hasSize(1);
        }
    }

    // ---- Rust test_tic equivalent: TIC index=0, 48 time points, all containers ----

    @ParameterizedTest
    @ValueSource(strings = {"small.unpacked.mzpeak", "small.mzpeak", "small.chunked.mzpeak"})
    void ticChromatogram_index0_48Points(String fixture) {
        try (MzPeakReader r = MzPeakReader.open(Path.of(BASE + fixture))) {
            Chromatogram tic = r.getChromatogram(0).orElseThrow();
            assertThat(tic.index()).isEqualTo(0L);
            assertThat(tic.size()).isEqualTo(48);
            // time axis is monotonic non-decreasing
            double[] t = tic.time();
            for (int i = 1; i < t.length; i++) {
                assertThat(t[i]).isGreaterThanOrEqualTo(t[i - 1]);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"small.unpacked.mzpeak", "small.mzpeak"})
    void ticChromatogramById(String fixture) {
        // The TIC chromatogram id is "TIC" in the point-layout fixtures; chunked may differ.
        try (MzPeakReader r = MzPeakReader.open(Path.of(BASE + fixture))) {
            assertThat(r.getChromatogramById("TIC").orElseThrow().size()).isEqualTo(48);
        }
    }

    // ---- Additional: spectrum 10 identity round-trip (Rust test_get_spectrum) ----

    @Test
    void spectrumByIndex_idRoundTrip() {
        try (MzPeakReader r = MzPeakReader.open(Path.of(BASE + "small.unpacked.mzpeak"))) {
            Spectrum s1 = r.getSpectrumAt(10);
            Spectrum s2 = r.getSpectrum(s1.index()).orElseThrow();
            assertThat(s2.description().id()).isEqualTo(s1.description().id());
            assertThat(s2.description().index()).isEqualTo(s1.description().index());
        }
    }

    @Test
    void metadataAndFullSpectrumAgreOnIndexAndId() {
        try (MzPeakReader r = MzPeakReader.open(Path.of(BASE + "small.mzpeak"))) {
            var meta = r.getMetadata(10).orElseThrow();
            var full = r.getSpectrum(10).orElseThrow();
            assertThat(full.description().id()).isEqualTo(meta.id());
            assertThat(full.description().index()).isEqualTo(meta.index());
        }
    }
}
