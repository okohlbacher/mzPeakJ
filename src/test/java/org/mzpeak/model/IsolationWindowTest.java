package org.mzpeak.model;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link IsolationWindow} bounds: a fixture window, and windows that lack the target or an offset. */
class IsolationWindowTest {

    @Test
    void theBoundsOfAFixtureWindowAreTheTargetMinusAndPlusTheOffsets() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            IsolationWindow window = reader.metadataAt(5).primaryPrecursor().isolationWindow();
            assertThat(window.targetMz()).isEqualTo(558.8689575195312);
            assertThat(window.lowerBound()).isEqualTo(558.8689575195312 - 1.0);
            assertThat(window.upperBound()).isEqualTo(558.8689575195312 + 1.0);
        }
    }

    @Test
    void aBoundIsNullWithoutTheTargetOrItsOffset() {
        IsolationWindow noTarget = new IsolationWindow(null, 1.0, 2.0);
        assertThat(noTarget.lowerBound()).isNull();
        assertThat(noTarget.upperBound()).isNull();

        IsolationWindow noOffsets = new IsolationWindow(500.0, null, null);
        assertThat(noOffsets.lowerBound()).isNull();
        assertThat(noOffsets.upperBound()).isNull();

        IsolationWindow lowerOnly = new IsolationWindow(500.0, 1.5, null);
        assertThat(lowerOnly.lowerBound()).isEqualTo(498.5);
        assertThat(lowerOnly.upperBound()).isNull();

        IsolationWindow upperOnly = new IsolationWindow(500.0, null, 2.5);
        assertThat(upperOnly.lowerBound()).isNull();
        assertThat(upperOnly.upperBound()).isEqualTo(502.5);
    }

    @Test
    void aNullParameterListIsEmpty() {
        IsolationWindow window = new IsolationWindow(500.0, 1.0, 1.0, null);
        assertThat(window.parameters()).isEmpty();
        assertThat(window).isEqualTo(new IsolationWindow(500.0, 1.0, 1.0));
    }
}
