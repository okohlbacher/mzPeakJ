package org.mzpeak.model;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link SignalContinuity#fromCurie(String)}: profile, centroid, and every other CURIE is unknown. */
class SignalContinuityTest {

    @Test
    void theFixtureHasProfileMs1AndCentroidMs2() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            assertThat(reader.metadataAt(0).signalContinuity()).isEqualTo(SignalContinuity.PROFILE);
            assertThat(reader.metadataAt(5).signalContinuity()).isEqualTo(SignalContinuity.CENTROID);
        }
    }

    @Test
    void fromCurieTrimsAndFallsBackToUnknown() {
        assertThat(SignalContinuity.fromCurie("MS:1000128")).isEqualTo(SignalContinuity.PROFILE);
        assertThat(SignalContinuity.fromCurie(" MS:1000127 ")).isEqualTo(SignalContinuity.CENTROID);
        assertThat(SignalContinuity.fromCurie("MS:1000525")).isEqualTo(SignalContinuity.UNKNOWN);
        assertThat(SignalContinuity.fromCurie("ms:1000127")).isEqualTo(SignalContinuity.UNKNOWN);
        assertThat(SignalContinuity.fromCurie("")).isEqualTo(SignalContinuity.UNKNOWN);
        assertThat(SignalContinuity.fromCurie(null)).isEqualTo(SignalContinuity.UNKNOWN);
    }
}
