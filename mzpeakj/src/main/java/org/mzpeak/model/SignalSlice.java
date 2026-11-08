package org.mzpeak.model;

/**
 * The part of one spectrum's signal that a range query selects: the points (or peaks) of the spectrum that pass the
 * query's m/z and ion-mobility filters, in stored order, with the spectrum's identity. A spectrum without matching
 * points gives an empty slice.
 *
 * <p>Record semantics: {@code equals}, {@code hashCode} and {@code toString} treat the {@code double[]} components by
 * reference, so two slices with equal values in different arrays are not equal.
 *
 * @param index       0-based spectrum index
 * @param time        retention time of the spectrum (scan start time), in the file's time unit
 * @param msLevel     MS level of the spectrum; 0 if absent
 * @param mz          m/z of the selected points
 * @param intensity   intensity of the selected points, parallel to {@link #mz}
 * @param ionMobility ion mobility of the selected points, parallel to {@link #mz}, or {@code null} when the spectrum
 *                    has no ion mobility array
 */
public record SignalSlice(long index, double time, int msLevel, double[] mz, double[] intensity,
                          double[] ionMobility) {

    /**
     * {@code null} for {@code mz} or {@code intensity} is the same as an empty array.
     *
     * @throws IllegalArgumentException when {@code intensity}, or a non-null {@code ionMobility}, differs in length
     *                                  from {@code mz}
     */
    public SignalSlice {
        mz = mz == null ? new double[0] : mz;
        intensity = intensity == null ? new double[0] : intensity;
        if (mz.length != intensity.length) {
            throw new IllegalArgumentException(
                    "mz/intensity length mismatch: " + mz.length + " vs " + intensity.length);
        }
        if (ionMobility != null && ionMobility.length != mz.length) {
            throw new IllegalArgumentException(
                    "mz/ionMobility length mismatch: " + mz.length + " vs " + ionMobility.length);
        }
    }

    /** The number of selected points. */
    public int size() {
        return mz.length;
    }
}
