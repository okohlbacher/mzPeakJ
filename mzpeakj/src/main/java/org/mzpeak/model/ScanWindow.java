package org.mzpeak.model;

/**
 * A scan acquisition m/z window.
 *
 * @param lowerLimit MS:1000501
 * @param upperLimit MS:1000500
 */
public record ScanWindow(double lowerLimit, double upperLimit) {
}
