package org.mzpeak.model;

/**
 * A scan acquisition window: in m/z for a mass spectrum, in nm for a wavelength spectrum.
 *
 * @param lowerLimit MS:1000501
 * @param upperLimit MS:1000500
 */
public record ScanWindow(double lowerLimit, double upperLimit) {
}
