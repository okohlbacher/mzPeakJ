package org.mzpeak.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A chromatogram (e.g. total ion current, base peak, or selected ion) — parallel time + intensity arrays, plus any
 * further per-point arrays the file stores alongside them, and its polarity, data processing, Params and precursors.
 *
 * <p>Record semantics: {@code equals}, {@code hashCode} and {@code toString} treat the {@code double[]} components,
 * and the arrays held in {@code arrays}, by reference, so two chromatograms with equal values in different arrays
 * are not equal.
 *
 * <p>The times are as the file stores them, in {@link #timeUnit}; {@link #withTimeUnit} converts them.
 *
 * @param index             0-based chromatogram index
 * @param id                chromatogram id (e.g. {@code "TIC"})
 * @param typeCurie         PSI-MS chromatogram type CURIE (e.g. {@code MS:1000235} = total ion current)
 * @param time              time axis (MS:1000595), in {@link #timeUnit}; the reader keeps the stored values
 * @param intensity         intensity axis (MS:1000515), parallel to {@link #time}
 * @param intensityUnit     unit CURIE of the intensity values as the file declares it (e.g. {@code MS:1000131} =
 *                          number of detector counts, {@code UO:0000269} = absorbance unit), or {@code null} when the
 *                          file names none, the values come from columns of different units, or the file has no
 *                          array index. On read it is the unit of the intensity column that supplied the values, so
 *                          a chromatogram without points reads back with {@code null}, whatever unit it was written
 *                          with
 * @param arrays            the other per-point arrays, parallel to {@link #time}, keyed by array type CURIE, or by
 *                          array name for non-standard data arrays (MS:1000786, e.g. {@code "ms level"}); a further
 *                          array of a key is keyed {@code <key>@<column name>}. Missing cells are NaN. A drift-time
 *                          ion mobility array (MS:1002477, MS:1002816, MS:1003007, MS:1003153, MS:1003154 or
 *                          MS:1003156, also under a {@code <key>@<column name>} key) is in milliseconds, its unit in
 *                          {@link CvTerms#DEFAULT_ARRAY_UNITS}: the reader converts one stored in seconds or minutes
 *                          and drops one in another unit; it keeps every other array as stored. Unmodifiable,
 *                          in the iteration order of the given map (the file's array index order when read); the
 *                          {@code double[]} values are not copied
 * @param polarity          scan polarity (MS:1000465); never {@code null}: {@link Polarity#UNKNOWN} when the file
 *                          gives none or a {@code null} is passed
 * @param dataProcessingRef id of the data processing that governs this chromatogram, or {@code null} when the
 *                          chromatogram uses the run's default data processing
 *                          ({@link org.mzpeak.model.meta.FileMetadata.MsRun#defaultDataProcessingId()})
 * @param parameters        the chromatogram's other CV and user params; never {@code null}, may be empty.
 *                          Unmodifiable, a copy of the given list
 * @param precursors        the precursors whose ions the chromatogram follows, as in selected reaction monitoring;
 *                          never {@code null}, may be empty. Unmodifiable, a copy of the given list. The
 *                          {@link Precursor#precursorIndex()} of a chromatogram precursor names a chromatogram (the
 *                          chromatogram foreign key of the format), its {@link Precursor#precursorId()} may name a
 *                          spectrum. The isolation window of the product ion is not modelled
 * @param timeUnit          unit CURIE of {@link #time}; never {@code null}: {@code UO:0000031} minute, the unit of
 *                          {@link CvTerms#DEFAULT_ARRAY_UNITS}, when a {@code null} is passed. On read it is the unit
 *                          the file declares for the time column, minute when it declares none
 */
public record Chromatogram(long index, String id, String typeCurie, double[] time, double[] intensity,
                           String intensityUnit, Map<String, double[]> arrays, Polarity polarity,
                           String dataProcessingRef, List<Param> parameters, List<Precursor> precursors,
                           String timeUnit) {

    /** UO:0000031 minute, the default {@link #timeUnit}. */
    private static final String MINUTE = "UO:0000031";

    /**
     * The time units {@link #withTimeUnit} converts among, with their length in milliseconds: minute, second,
     * millisecond (UO:0000028) and hour (UO:0000032).
     */
    private static final Map<String, Long> MILLISECONDS = Map.of(
            MINUTE, 60_000L,
            "UO:0000010", 1_000L,
            "UO:0000028", 1L,
            "UO:0000032", 3_600_000L);

    /**
     * @throws IllegalArgumentException when {@code time} and {@code intensity} differ in length, or when
     *                                  {@code arrays} has a null key, a null value or a value whose length differs
     *                                  from {@code time}'s
     * @throws NullPointerException     when {@code parameters} or {@code precursors} holds a {@code null} element
     */
    public Chromatogram {
        time = time == null ? new double[0] : time;
        intensity = intensity == null ? new double[0] : intensity;
        if (time.length != intensity.length) {
            throw new IllegalArgumentException(
                    "time/intensity length mismatch: " + time.length + " vs " + intensity.length);
        }
        arrays = copyArrays(arrays == null ? Map.of() : arrays, time.length);
        polarity = polarity == null ? Polarity.UNKNOWN : polarity;
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        precursors = precursors == null ? List.of() : List.copyOf(precursors);
        timeUnit = timeUnit == null ? MINUTE : timeUnit;
    }

    /** A chromatogram whose times are in minutes (UO:0000031). */
    public Chromatogram(long index, String id, String typeCurie, double[] time, double[] intensity,
                        String intensityUnit, Map<String, double[]> arrays, Polarity polarity,
                        String dataProcessingRef, List<Param> parameters, List<Precursor> precursors) {
        this(index, id, typeCurie, time, intensity, intensityUnit, arrays, polarity, dataProcessingRef, parameters,
                precursors, MINUTE);
    }

    /** A chromatogram without intensity unit and without further arrays, whose times are in minutes (UO:0000031). */
    public Chromatogram(long index, String id, String typeCurie, double[] time, double[] intensity) {
        this(index, id, typeCurie, time, intensity, null, Map.of());
    }

    /**
     * A chromatogram of unknown polarity, under the run's default data processing, without Params and without
     * precursors, whose times are in minutes (UO:0000031).
     */
    public Chromatogram(long index, String id, String typeCurie, double[] time, double[] intensity,
                        String intensityUnit, Map<String, double[]> arrays) {
        this(index, id, typeCurie, time, intensity, intensityUnit, arrays, Polarity.UNKNOWN, null, List.of(),
                List.of());
    }

    public int size() {
        return time.length;
    }

    /**
     * This chromatogram with its times in {@code unit}: {@code this} when it is {@link #timeUnit}, else a copy with
     * every other component the same and each time converted. Supported are minute (UO:0000031), second
     * (UO:0000010), millisecond (UO:0000028) and hour (UO:0000032); a time is multiplied by the whole ratio of the
     * two units when the target unit is the smaller, else divided by it (seconds to minutes: divided by 60).
     *
     * @throws IllegalArgumentException when {@link #timeUnit} or {@code unit} is not one of these; the message names
     *                                  both
     */
    public Chromatogram withTimeUnit(String unit) {
        if (timeUnit.equals(unit)) {
            return this;
        }
        Long from = MILLISECONDS.get(timeUnit);
        Long to = unit == null ? null : MILLISECONDS.get(unit);
        if (from == null || to == null) {
            throw new IllegalArgumentException("cannot convert the times of chromatogram " + index + " from "
                    + timeUnit + " to " + unit + "; supported are minute (UO:0000031), second (UO:0000010),"
                    + " millisecond (UO:0000028) and hour (UO:0000032)");
        }
        double[] converted = new double[time.length];
        for (int i = 0; i < time.length; i++) {
            converted[i] = from > to ? time[i] * (double) (from / to) : time[i] / (double) (to / from);
        }
        return new Chromatogram(index, id, typeCurie, converted, intensity, intensityUnit, arrays, polarity,
                dataProcessingRef, parameters, precursors, unit);
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
