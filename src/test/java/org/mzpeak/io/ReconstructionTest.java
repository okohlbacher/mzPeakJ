package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.mzpeak.model.Spectrum;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Verifies profile reconstruction via the stored mz_delta_model polynomial. */
class ReconstructionTest {

    private static final Path FIXTURE = Path.of("src/test/resources/mzpeak/small.unpacked.mzpeak");

    @Test
    void reconstructsProfileWithDeltaModel() {
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            Spectrum s = reader.getSpectrum(0).orElseThrow();   // MS1 profile with a 3-term delta model
            double[] mz = s.mz();

            assertThat(mz).hasSize(13589);                       // declared number_of_data_points
            assertThat(s.description().numberOfDataPoints()).isEqualTo(13589L);

            // every gap is filled (no NaN placeholders remain) and the axis is monotonic non-decreasing
            for (int i = 0; i < mz.length; i++) {
                assertThat(Double.isNaN(mz[i])).isFalse();
                if (i > 0) {
                    assertThat(mz[i]).isGreaterThanOrEqualTo(mz[i - 1]);
                }
            }
            // real (non-flank) anchor values are preserved exactly
            assertThat(mz[0]).isCloseTo(202.606575, within(1e-4));
            assertThat(s.intensity()[1]).isCloseTo(1938.1174, within(1e-2));
            // filled points stay within the spectrum's observed m/z range
            assertThat(mz[mz.length - 1]).isLessThan(2001.0);
        }

        // with reconstruction off, only the stored non-null points are returned
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE, false)) {
            assertThat(reader.getSpectrum(0).orElseThrow().pointCount()).isEqualTo(11213);
        }
    }
}
