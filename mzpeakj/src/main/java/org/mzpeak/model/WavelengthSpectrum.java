package org.mzpeak.model;

import java.util.List;

/**
 * A wavelength (UV/DAD) spectrum: parallel wavelength + intensity (absorbance) arrays. Distinct from a mass
 * {@link Spectrum} — it has a wavelength axis (nm), no precursor, and intensities may be negative.
 *
 * @param index      0-based wavelength-spectrum index
 * @param id         native id
 * @param time       acquisition time (spectrum.time), in the file's time unit
 * @param lambdaMax  wavelength of maximum absorbance (MS:1003812), in nm; may be {@code null}
 * @param wavelength wavelength axis (MS:1000617), in nm
 * @param intensity  absorbance/intensity axis (MS:1000515), parallel to {@link #wavelength}
 * @param parameters the spectrum's CV and user params, such as its spectrum type, representation, observed
 *                   wavelength range and title; never {@code null}, may be empty. Unmodifiable, a copy of the given
 *                   list
 */
public record WavelengthSpectrum(long index, String id, double time, Double lambdaMax,
                                 double[] wavelength, double[] intensity, List<Param> parameters) {

    /**
     * @throws IllegalArgumentException when {@code wavelength} and {@code intensity} differ in length
     * @throws NullPointerException     when {@code parameters} holds a {@code null} element
     */
    public WavelengthSpectrum {
        wavelength = wavelength == null ? new double[0] : wavelength;
        intensity = intensity == null ? new double[0] : intensity;
        if (wavelength.length != intensity.length) {
            throw new IllegalArgumentException(
                    "wavelength/intensity length mismatch: " + wavelength.length + " vs " + intensity.length);
        }
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /** A wavelength spectrum without parameters. */
    public WavelengthSpectrum(long index, String id, double time, Double lambdaMax,
                              double[] wavelength, double[] intensity) {
        this(index, id, time, lambdaMax, wavelength, intensity, List.of());
    }

    public int size() {
        return wavelength.length;
    }
}
