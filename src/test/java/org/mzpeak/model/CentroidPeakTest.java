package org.mzpeak.model;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link CentroidPeak#coordinate()} is the m/z of the peak. */
class CentroidPeakTest {

    @Test
    void theCoordinateOfAFixturePeakIsItsMz() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            List<CentroidPeak> peaks = reader.getSpectrum(5).orElseThrow().peaks();
            assertThat(peaks).hasSize(650);
            CentroidPeak first = peaks.get(0);
            assertThat(first.mz()).isEqualTo(164.09158325195312);
            assertThat(first.coordinate()).isEqualTo(164.09158325195312);
            for (CentroidPeak p : peaks) {
                assertThat(p.coordinate()).isEqualTo(p.mz());
            }
        }
    }

    @Test
    void theCoordinateIsTheMzThroughTheInterface() {
        CoordinateLike peak = new CentroidPeak(445.12, 1000.0, 3);
        assertThat(peak.coordinate()).isEqualTo(445.12);
    }
}
