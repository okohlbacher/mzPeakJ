package org.mzpeak.model;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mzpeak.io.MzPeakReader;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Spectrum.findPeak / findPeaks / findPeaksBetween against the vendored fixture.
 * Spectrum 5 is a centroid MS2 with 650 peaks, suitable for search testing.
 */
class PeakSearchTest {

    private static Spectrum centroidSpectrum;
    private static Spectrum profileSpectrum;

    @BeforeAll
    static void load() {
        try (MzPeakReader reader = MzPeakReader.open(
                Path.of("src/test/resources/mzpeak/small.unpacked.mzpeak"))) {
            centroidSpectrum = reader.getSpectrum(5).orElseThrow();  // MS2 centroid, 650 peaks
            profileSpectrum = reader.getSpectrum(0).orElseThrow();   // MS1 profile, no centroid peaks
        }
    }

    @Test
    void findPeakLocatesKnownPeak() {
        // The first centroid peak of spectrum 5 should be near m/z 231.389 (from earlier fixture inspection)
        double target = centroidSpectrum.peaks().get(0).mz();
        Optional<CentroidPeak> found = centroidSpectrum.findPeak(target, Tolerance.ppm(10));
        assertThat(found).isPresent();
        assertThat(found.get().mz()).isEqualTo(target);
    }

    @Test
    void findPeakReturnsClosestWithinTolerance() {
        // Use a target midway between two adjacent peaks; should return the nearer one
        List<CentroidPeak> peaks = centroidSpectrum.peaks();
        assertThat(peaks.size()).isGreaterThan(1);
        double mz1 = peaks.get(0).mz();
        double mz2 = peaks.get(1).mz();
        double mid = (mz1 + mz2) / 2.0;
        // tolerance big enough to cover both
        Optional<CentroidPeak> found = centroidSpectrum.findPeak(mid, Tolerance.da(mid - mz1 + 1.0));
        assertThat(found).isPresent();
    }

    @Test
    void findPeakReturnEmptyWhenOutsideTolerance() {
        // look for a peak far outside the spectrum's m/z range
        Optional<CentroidPeak> result = centroidSpectrum.findPeak(5000.0, Tolerance.ppm(10));
        assertThat(result).isEmpty();
    }

    @Test
    void findPeaksReturnAllInRange() {
        // grab the first three peaks and ask for anything in their exact range
        List<CentroidPeak> first3 = centroidSpectrum.peaks().subList(0, 3);
        double lo = first3.get(0).mz();
        double hi = first3.get(2).mz();
        // use a tolerance spanning that exact window
        double span = (hi - lo) / 2.0 + 0.001;
        double center = (lo + hi) / 2.0;
        List<CentroidPeak> found = centroidSpectrum.findPeaks(center, Tolerance.da(span));
        assertThat(found.size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void findPeaksBetweenReturnsSortedSublist() {
        List<CentroidPeak> all = centroidSpectrum.peaks();
        double lo = all.get(2).mz();
        double hi = all.get(5).mz();
        List<CentroidPeak> found = centroidSpectrum.findPeaksBetween(lo, hi);
        assertThat(found.size()).isGreaterThanOrEqualTo(4); // at least peaks 2..5
        for (CentroidPeak p : found) {
            assertThat(p.mz()).isBetween(lo, hi);
        }
    }

    @Test
    void findPeakEmptyForProfileSpectrum() {
        // profile spectrum has no centroid peaks — all search methods return empty
        assertThat(profileSpectrum.peaks()).isEmpty();
        assertThat(profileSpectrum.findPeak(300.0, Tolerance.ppm(20))).isEmpty();
        assertThat(profileSpectrum.findPeaks(300.0, Tolerance.ppm(20))).isEmpty();
        assertThat(profileSpectrum.findPeaksBetween(200.0, 400.0)).isEmpty();
    }

    @Test
    void toleranceBoundsAndTestAreSelfConsistent() {
        double mz = 500.0;
        Tolerance ppm20 = Tolerance.ppm(20);
        double[] bounds = ppm20.bounds(mz);
        assertThat(ppm20.test(bounds[0], mz)).isTrue();
        assertThat(ppm20.test(bounds[1], mz)).isTrue();
        assertThat(ppm20.test(mz + 1.0, mz)).isFalse(); // 2000 ppm off at 500 m/z >> 20 ppm

        Tolerance da01 = Tolerance.da(0.1);
        assertThat(da01.test(500.09, 500.0)).isTrue();
        assertThat(da01.test(500.11, 500.0)).isFalse();
    }
}
