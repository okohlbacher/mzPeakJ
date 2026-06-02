package org.mzpeak.io;

/** Thrown when an mzPeak dataset cannot be read or is malformed. */
public class MzPeakException extends RuntimeException {
    public MzPeakException(String message) {
        super(message);
    }

    public MzPeakException(String message, Throwable cause) {
        super(message, cause);
    }
}
