package org.mzpeak.model;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link SpectrumDescription#isMsn()}: true from MS level 2. */
class SpectrumDescriptionTest {

    @Test
    void ms1IsNotMsnAndMs2Is() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            SpectrumDescription ms1 = reader.metadataAt(0);
            SpectrumDescription ms2 = reader.metadataAt(5);
            assertThat(ms1.msLevel()).isEqualTo(1);
            assertThat(ms1.isMsn()).isFalse();
            assertThat(ms2.msLevel()).isEqualTo(2);
            assertThat(ms2.isMsn()).isTrue();
        }
    }

    @Test
    void anUnknownLevelIsNotMsn() {
        SpectrumDescription d = new SpectrumDescription(0, "scan=1", 0, 0.5, Polarity.UNKNOWN,
                SignalContinuity.UNKNOWN, 0, 0, null, null, null);
        assertThat(d.isMsn()).isFalse();
        assertThat(d.primaryPrecursor()).isNull();
    }
}
