package org.mzpeak.io;

import org.mzpeak.io.SpectrumMetadataDecoder.Decoded;
import org.mzpeak.io.SpectrumRowDecoder.SpectrumHead;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SpectrumDescription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Assembles {@link SpectrumDescription}s from the rows of the four spectrum metadata facets, whatever layout they come
 * from. Rows are joined strictly by key ({@code index} for spectra, {@code source_index} for the other facets), never
 * by row position; facet rows of a spectrum that has no spectrum row are dropped.
 */
final class SpectrumFacetAssembler {

    private final Map<Long, SpectrumHead> spectra = new LinkedHashMap<>();
    private final Map<Long, List<FacetRow>> scansBySource = new HashMap<>();
    private final Map<Long, List<FacetRow>> precursorsBySource = new HashMap<>();
    private final Map<Long, List<FacetRow>> selectedIonsBySource = new HashMap<>();
    private final Map<Long, double[]> deltaModels = new HashMap<>();

    /**
     * Takes one row whose (non-negative) key is {@code key}. A spectrum row is decoded at once; scan, precursor and
     * selected-ion rows are kept, in the order given, until {@link #build()}.
     *
     * @throws MzPeakException          if a spectrum row repeats the index of an earlier one
     * @throws IllegalArgumentException if the row is not of one of the four spectrum facets
     */
    void accept(long key, FacetRow row) {
        Objects.requireNonNull(row, "row");
        switch (row.facet()) {
            case SPECTRUM -> {
                SpectrumHead head = SpectrumRowDecoder.decode(row, key);
                if (spectra.putIfAbsent(key, head) != null) {
                    throw new MzPeakException("duplicate spectrum.index in metadata: " + key);
                }
                double[] model = head.mzDeltaModel;
                if (model.length > 0) {
                    deltaModels.put(key, model);
                }
            }
            case SPECTRUM_SCAN -> bucket(scansBySource, key, row);
            case SPECTRUM_PRECURSOR -> bucket(precursorsBySource, key, row);
            case SPECTRUM_SELECTED_ION -> bucket(selectedIonsBySource, key, row);
            default -> throw new IllegalArgumentException("not a spectrum metadata facet: " + row.facet());
        }
    }

    /** The spectra taken so far, with their scans and precursors attached, sorted by index. */
    Decoded build() {
        List<SpectrumDescription> out = new ArrayList<>(spectra.size());
        for (SpectrumHead b : spectra.values()) {
            List<ScanEvent> scans = buildScans(scansBySource.get(b.index));
            List<Precursor> precursors = PrecursorJoin.join(
                    precursorsBySource.get(b.index), selectedIonsBySource.get(b.index));
            out.add(new SpectrumDescription(
                    b.index, b.id, b.msLevel, retentionTime(b.time, scans), b.polarity, b.continuity,
                    b.numberOfDataPoints, b.numberOfPeaks, scans, precursors,
                    b.spectrumParams));
        }
        out.sort((a, b) -> Long.compare(a.index(), b.index()));
        return new Decoded(out, deltaModels);
    }

    /**
     * The spectrum's {@code time}, or, when that is NaN (absent), the smallest finite start time of its scans, which
     * is where the Rust reader takes the retention time from; NaN when there is neither.
     */
    private static double retentionTime(double time, List<ScanEvent> scans) {
        if (!Double.isNaN(time)) {
            return time;
        }
        double earliest = Double.NaN;
        for (ScanEvent scan : scans) {
            double start = scan.startTime();
            if (Double.isFinite(start) && (Double.isNaN(earliest) || start < earliest)) {
                earliest = start;
            }
        }
        return earliest;
    }

    private static void bucket(Map<Long, List<FacetRow>> target, long key, FacetRow row) {
        target.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
    }

    private static List<ScanEvent> buildScans(List<FacetRow> scans) {
        if (scans == null) {
            return List.of();
        }
        List<ScanEvent> out = new ArrayList<>(scans.size());
        for (FacetRow scan : scans) {
            out.add(ScanRowDecoder.decode(scan));
        }
        return out;
    }
}
