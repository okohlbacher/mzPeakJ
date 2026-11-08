package org.mzpeak.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A chromatogram (e.g. total ion current, base peak, or selected ion) — parallel time + intensity arrays, plus any
 * further per-point arrays the file stores alongside them.
 *
 * <p>Record semantics: {@code equals}, {@code hashCode} and {@code toString} treat the {@code double[]} components,
 * and the arrays held in {@code arrays}, by reference, so two chromatograms with equal values in different arrays
 * are not equal.
 *
 * @param index         0-based chromatogram index
 * @param id            chromatogram id (e.g. {@code "TIC"})
 * @param typeCurie     PSI-MS chromatogram type CURIE (e.g. {@code MS:1000235} = total ion current)
 * @param time          time axis (MS:1000595), in minutes, the unit of {@link CvTerms#DEFAULT_ARRAY_UNITS}: the
 *                      reader converts a time array stored in seconds, milliseconds or hours
 * @param intensity     intensity axis (MS:1000515), parallel to {@link #time}
 * @param intensityUnit unit CURIE of the intensity values as the file declares it (e.g. {@code MS:1000131} =
 *                      number of detector counts, {@code UO:0000269} = absorbance unit), or {@code null} when the
 *                      file names none, the values come from columns of different units, or the file has no array
 *                      index
 * @param arrays        the other per-point arrays, parallel to {@link #time}, keyed by array type CURIE, or by array
 *                      name for non-standard data arrays (MS:1000786, e.g. {@code "ms level"}); a further array of a
 *                      key is keyed {@code <key>@<column name>}. Missing cells are NaN. Unmodifiable, in the
 *                      iteration order of the given map (the file's array index order when read); the
 *                      {@code double[]} values are not copied
 */
public record Chromatogram(long index, String id, String typeCurie, double[] time, double[] intensity,
                           String intensityUnit, Map<String, double[]> arrays) {

    /**
     * @throws IllegalArgumentException when {@code time} and {@code intensity} differ in length, or when
     *                                  {@code arrays} has a null key, a null value or a value whose length differs
     *                                  from {@code time}'s
     */
    public Chromatogram {
        time = time == null ? new double[0] : time;
        intensity = intensity == null ? new double[0] : intensity;
        if (time.length != intensity.length) {
            throw new IllegalArgumentException(
                    "time/intensity length mismatch: " + time.length + " vs " + intensity.length);
        }
        arrays = copyArrays(arrays == null ? Map.of() : arrays, time.length);
    }

    /** A chromatogram without intensity unit and without further arrays. */
    public Chromatogram(long index, String id, String typeCurie, double[] time, double[] intensity) {
        this(index, id, typeCurie, time, intensity, null, Map.of());
    }

    public int size() {
        return time.length;
    }

    private static Map<String, double[]> copyArrays(Map<String, double[]> arrays, int length) {
        Map<String, double[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : arrays.entrySet()) {
            String key = e.getKey();
            double[] values = e.getValue();
            if (key == null) {
                throw new IllegalArgumentException("arrays has a null key");
            }
            if (values == null) {
                throw new IllegalArgumentException("arrays[" + key + "] is null");
            }
            if (values.length != length) {
                throw new IllegalArgumentException(
                        "arrays[" + key + "]/time length mismatch: " + values.length + " vs " + length);
            }
            copy.put(key, values);
        }
        return Collections.unmodifiableMap(copy);
    }
}
