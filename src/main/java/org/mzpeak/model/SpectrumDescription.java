package org.mzpeak.model;

import java.util.List;

/**
 * Metadata for one spectrum (everything except the m/z + intensity payload).
 *
 * @param index               0-based spectrum index (primary key; mzPeak stores this as uint64, but mzPeakJ
 *                            requires {@code 0 <= index <= Long.MAX_VALUE})
 * @param id                  native id string (vendor scan number is embedded here)
 * @param msLevel             MS level (1, 2, ...)
 * @param retentionTime       scan start time (MS:1000016 / spectrum.time), in the file's unit (typically minutes)
 * @param polarity            scan polarity
 * @param signalContinuity    profile vs centroid
 * @param numberOfDataPoints  declared profile point count (MS:1003060); 0 if absent
 * @param numberOfPeaks       declared centroid count (MS:1003059); 0 if absent
 * @param scans               acquisition events; never {@code null}
 * @param precursors          precursors (empty for MS1); never {@code null}
 * @param parameters          additional CV/user params from the spectrum facet; never {@code null}, may be empty
 */
public record SpectrumDescription(long index,
                                  String id,
                                  int msLevel,
                                  double retentionTime,
                                  Polarity polarity,
                                  SignalContinuity signalContinuity,
                                  long numberOfDataPoints,
                                  long numberOfPeaks,
                                  List<ScanEvent> scans,
                                  List<Precursor> precursors,
                                  List<Param> parameters) {

    public SpectrumDescription {
        scans = scans == null ? List.of() : List.copyOf(scans);
        precursors = precursors == null ? List.of() : List.copyOf(precursors);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }


    public boolean isMsn() {
        return msLevel > 1;
    }

    /** The first precursor, or {@code null} for MS1 / no precursor. */
    public Precursor primaryPrecursor() {
        return precursors.isEmpty() ? null : precursors.get(0);
    }
}
