package org.mzpeak.model;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ImagingPosition}: the 3 x 3 pixel grid of Example_Processed.img, and positions outside the 1-based grid. */
class ImagingPositionTest {

    @Test
    void theFixturePixelsAreValidAndPrintAsPairs() {
        List<String> printed = new ArrayList<>();
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("Example_Processed.img.mzpeak"))) {
            for (SpectrumDescription d : reader.metadata()) {
                ImagingPosition position = d.scans().get(0).imagingPosition();
                assertThat(position.isValid()).as("pixel of %s", d.id()).isTrue();
                printed.add(position.toString());
            }
        }
        assertThat(printed).containsExactly(
                "(1, 1)", "(2, 1)", "(3, 1)", "(1, 2)", "(2, 2)", "(3, 2)", "(1, 3)", "(2, 3)", "(3, 3)");
    }

    @Test
    void aCoordinateBelowOneIsInvalid() {
        assertThat(new ImagingPosition(1, 1).isValid()).isTrue();
        assertThat(new ImagingPosition(0, 1).isValid()).isFalse();
        assertThat(new ImagingPosition(-2, 3).isValid()).isFalse();
        assertThat(new ImagingPosition(1, 0).isValid()).isFalse();
        assertThat(new ImagingPosition(3, -2).isValid()).isFalse();
        assertThat(new ImagingPosition(-4, 0)).hasToString("(-4, 0)");
    }
}
