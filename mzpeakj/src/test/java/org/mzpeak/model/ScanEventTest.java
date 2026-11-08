package org.mzpeak.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScanEvent}: null lists, and {@link ScanEvent#imagingPosition()} when a coordinate is missing or not a number
 * (the fixture pixels are in {@link ImagingPositionTest}).
 */
class ScanEventTest {

    private static final Param X = new Param("position x", "IMS:1000050", 4L, null);
    private static final Param Y = new Param("position y", "IMS:1000051", 5L, null);

    private static ScanEvent scan(List<Param> parameters) {
        return new ScanEvent(1.5, null, null, null, null, null, List.of(), parameters);
    }

    @Test
    void nullListsAreEmpty() {
        ScanEvent scan = new ScanEvent(1.5, null, null, null, null, null, null, null);
        assertThat(scan.scanWindows()).isEmpty();
        assertThat(scan.parameters()).isEmpty();
        assertThat(scan.imagingPosition()).isNull();
        assertThat(scan.ionMobilityValue()).isEmpty();
        assertThat(scan.ionMobilityType()).isNull();
    }

    @Test
    void theImagingPositionNeedsBothNumericCoordinates() {
        assertThat(scan(List.of(X, Y)).imagingPosition()).isEqualTo(new ImagingPosition(4, 5));
        assertThat(scan(List.of(Y, X)).imagingPosition()).isEqualTo(new ImagingPosition(4, 5));
        assertThat(scan(List.of(X)).imagingPosition()).isNull();
        assertThat(scan(List.of(Y)).imagingPosition()).isNull();
    }

    @Test
    void aNonNumericCoordinateIsIgnored() {
        Param textX = new Param("position x", "IMS:1000050", "4", null);
        Param textY = new Param("position y", "IMS:1000051", "5", null);
        assertThat(scan(List.of(textX, Y)).imagingPosition()).isNull();
        assertThat(scan(List.of(X, textY)).imagingPosition()).isNull();
        // a later numeric coordinate still counts
        assertThat(scan(List.of(textX, X, textY, Y)).imagingPosition()).isEqualTo(new ImagingPosition(4, 5));
    }
}
