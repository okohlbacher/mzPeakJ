package org.mzpeak.model;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link Polarity#fromCode(Integer)}: the sign of the code decides, null and 0 are unknown. */
class PolarityTest {

    @Test
    void theFixtureSpectraArePositive() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            assertThat(reader.metadataAt(0).polarity()).isEqualTo(Polarity.POSITIVE);
        }
    }

    @Test
    void fromCodeMapsTheSign() {
        assertThat(Polarity.fromCode(1)).isEqualTo(Polarity.POSITIVE);
        assertThat(Polarity.fromCode(7)).isEqualTo(Polarity.POSITIVE);
        assertThat(Polarity.fromCode(-1)).isEqualTo(Polarity.NEGATIVE);
        assertThat(Polarity.fromCode(Integer.MIN_VALUE)).isEqualTo(Polarity.NEGATIVE);
        assertThat(Polarity.fromCode(0)).isEqualTo(Polarity.UNKNOWN);
        assertThat(Polarity.fromCode(null)).isEqualTo(Polarity.UNKNOWN);
    }
}
