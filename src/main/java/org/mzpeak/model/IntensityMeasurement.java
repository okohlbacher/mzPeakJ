package org.mzpeak.model;

/** Something with a measured signal magnitude. Mirrors {@code IntensityMeasurement} from {@code mzpeaks}. */
public interface IntensityMeasurement {
    /** Signal magnitude. Stored as {@code double} to match FragPipe/MSFTBX {@code ISpectrum}. */
    double intensity();
}
