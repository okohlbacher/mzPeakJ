package org.mzpeak.model;

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
 */
public record WavelengthSpectrum(long index, String id, double time, Double lambdaMax,
                                 double[] wavelength, double[] intensity) {

    public WavelengthSpectrum {
        wavelength = wavelength == null ? new double[0] : wavelength;
        intensity = intensity == null ? new double[0] : intensity;
        if (wavelength.length != intensity.length) {
            throw new IllegalArgumentException(
                    "wavelength/intensity length mismatch: " + wavelength.length + " vs " + intensity.length);
        }
    }

    public int size() {
        return wavelength.length;
    }
}
