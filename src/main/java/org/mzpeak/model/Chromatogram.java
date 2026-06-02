package org.mzpeak.model;

/**
 * A chromatogram (e.g. total ion current, base peak, or selected ion) — parallel time + intensity arrays.
 *
 * @param index     0-based chromatogram index
 * @param id        chromatogram id (e.g. {@code "TIC"})
 * @param typeCurie PSI-MS chromatogram type CURIE (e.g. {@code MS:1000235} = total ion current)
 * @param time      time axis (MS:1000595), in the file's time unit
 * @param intensity intensity axis (MS:1000515), parallel to {@link #time}
 */
public record Chromatogram(long index, String id, String typeCurie, double[] time, double[] intensity) {

    public Chromatogram {
        time = time == null ? new double[0] : time;
        intensity = intensity == null ? new double[0] : intensity;
        if (time.length != intensity.length) {
            throw new IllegalArgumentException(
                    "time/intensity length mismatch: " + time.length + " vs " + intensity.length);
        }
    }

    public int size() {
        return time.length;
    }
}
