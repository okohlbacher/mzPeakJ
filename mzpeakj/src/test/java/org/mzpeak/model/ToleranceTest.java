package org.mzpeak.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link Tolerance}: the constructor rejects a null unit and a value that is not a finite non-negative number. */
class ToleranceTest {

    @Test
    void aNullUnitIsRejected() {
        assertThatThrownBy(() -> new Tolerance(10.0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tolerance unit must not be null");
    }

    @Test
    void aNonFiniteOrNegativeValueIsRejected() {
        assertThatThrownBy(() -> Tolerance.ppm(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tolerance value must be finite and non-negative: NaN");
        assertThatThrownBy(() -> Tolerance.da(Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tolerance value must be finite and non-negative: Infinity");
        assertThatThrownBy(() -> Tolerance.ppm(-0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tolerance value must be finite and non-negative: -0.5");
    }

    @Test
    void zeroIsAccepted() {
        Tolerance zero = Tolerance.da(0.0);
        assertThat(zero.unit()).isEqualTo(Tolerance.Unit.DA);
        assertThat(zero.bounds(500.0)).containsExactly(500.0, 500.0);
        assertThat(zero.test(500.0, 500.0)).isTrue();
        assertThat(zero.test(500.0001, 500.0)).isFalse();
    }
}
