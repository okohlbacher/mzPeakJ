package org.mzpeak.model;

/**
 * Pixel coordinates for a mass-spectrometry imaging (MSI / imzML) scan event.
 *
 * <p>Coordinates are stored in CV params {@code IMS:1000050} (position x) and {@code IMS:1000051}
 * (position y) on the {@link ScanEvent}; access via {@link ScanEvent#imagingPosition()}.
 *
 * <p>Coordinate base is 1 in all current mzPeak imaging files (first pixel is (1,1)).
 */
public record ImagingPosition(int x, int y) {

    /** True if either coordinate is ≤ 0 (invalid pixel position). */
    public boolean isValid() {
        return x > 0 && y > 0;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ")";
    }
}
