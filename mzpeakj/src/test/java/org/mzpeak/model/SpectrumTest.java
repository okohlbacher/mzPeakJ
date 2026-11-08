package org.mzpeak.model;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Spectrum}: {@code toString}, {@code hasPeaks}, and the peak searches that find nothing in a gap between
 * peaks or in a reversed range, on the profile MS1 spectrum 0 and the centroid MS2 spectrum 5 of small (whose first
 * peaks are at m/z 164.0916 and 166.0664).
 */
class SpectrumTest {

    private static Spectrum profile;
    private static Spectrum centroid;

    @BeforeAll
    static void load() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            profile = reader.getSpectrum(0).orElseThrow();
            centroid = reader.getSpectrum(5).orElseThrow();
        }
    }

    @Test
    void toStringNamesIndexIdLevelAndCounts() {
        assertThat(profile).hasToString(
                "Spectrum{index=0, id=controllerType=0 controllerNumber=1 scan=1, msLevel=1, points=13589, peaks=0}");
        assertThat(centroid).hasToString(
                "Spectrum{index=5, id=controllerType=0 controllerNumber=1 scan=6, msLevel=2, points=650, peaks=650}");
    }

    @Test
    void hasPeaksOnlyWithCentroids() {
        assertThat(profile.hasPeaks()).isFalse();
        assertThat(centroid.hasPeaks()).isTrue();
    }

    @Test
    void findPeaksInAGapBetweenPeaksIsEmpty() {
        List<CentroidPeak> peaks = centroid.peaks();
        assertThat(peaks.get(0).mz()).isEqualTo(164.09158325195312);
        assertThat(peaks.get(1).mz()).isEqualTo(166.0663604736328);
        assertThat(centroid.findPeaks(165.0, Tolerance.da(0.5))).isEmpty();
        assertThat(centroid.findPeaks(165.0, Tolerance.da(1.0))).containsExactly(peaks.get(0));
    }

    @Test
    void findPeaksBetweenAGapOrAReversedRangeIsEmpty() {
        List<CentroidPeak> peaks = centroid.peaks();
        double first = peaks.get(0).mz();
        double second = peaks.get(1).mz();
        assertThat(centroid.findPeaksBetween(first, second)).containsExactly(peaks.get(0), peaks.get(1));
        assertThat(centroid.findPeaksBetween(second, first)).isEmpty();
        assertThat(centroid.findPeaksBetween(164.5, 165.5)).isEmpty();
    }
}
