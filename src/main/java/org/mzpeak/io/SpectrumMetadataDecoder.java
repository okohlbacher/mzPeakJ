package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.SpectrumDescription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes {@code spectra_metadata.parquet} (the "packed parallel tables" layout) into
 * {@link SpectrumDescription}s.
 *
 * <p>The {@code spectrum}, {@code scan}, {@code precursor} and {@code selected_ion} struct columns are
 * <em>not</em> row-aligned: each facet is densely packed and carries its own {@code source_index} pointing
 * back to the owning spectrum. Placeholder facet rows have a null {@code source_index} and are skipped.
 * Therefore we join strictly by {@code source_index}, never by row position.
 */
final class SpectrumMetadataDecoder {

    // spectrum facet
    private static final String F_INDEX = "index";
    private static final String F_ID = "id";
    private static final String F_MS_LEVEL = "MS_1000511_ms_level";
    private static final String F_TIME = "time";
    private static final String F_POLARITY = "MS_1000465_scan_polarity";
    private static final String F_REPRESENTATION = "MS_1000525_spectrum_representation";
    private static final String F_N_POINTS = "MS_1003060_number_of_data_points";
    private static final String F_N_PEAKS = "MS_1003059_number_of_peaks";
    // facet keys
    private static final String F_SOURCE_INDEX = "source_index";
    // scan facet
    private static final String F_SCAN_START = "MS_1000016_scan_start_time_unit_UO_0000031";
    private static final String F_INJECTION = "MS_1000927_ion_injection_time_unit_UO_0000028";
    private static final String F_FILTER = "MS_1000512_filter_string";
    // precursor facet
    private static final String F_PRECURSOR_INDEX = "precursor_index";
    private static final String F_PRECURSOR_ID = "precursor_id";
    private static final String F_ISOLATION = "isolation_window";
    private static final String F_ISO_TARGET = "MS_1000827_isolation_window_target_mz";
    private static final String F_ISO_LOWER = "MS_1000828_isolation_window_lower_offset";
    private static final String F_ISO_UPPER = "MS_1000829_isolation_window_upper_offset";
    // selected_ion facet
    private static final String F_SELECTED_MZ = "MS_1000744_selected_ion_mz_unit_MS_1000040";
    private static final String F_CHARGE = "MS_1000041_charge_state";
    private static final String F_SELECTED_INTENSITY = "MS_1000042_intensity_unit_MS_1000131";

    private SpectrumMetadataDecoder() {
    }

    static List<SpectrumDescription> decode(InputFile metadataFile) {
        Map<Long, Builder> builders = new LinkedHashMap<>();
        Map<Long, List<Group>> scansBySource = new HashMap<>();
        Map<Long, List<Group>> precursorsBySource = new HashMap<>();
        Map<Long, List<Group>> selectedIonsBySource = new HashMap<>();

        ParquetGroups.forEach(metadataFile, row -> {
            Group spectrum = ParquetGroups.optGroup(row, "spectrum");
            if (spectrum != null) {
                Long idx = ParquetGroups.optLong(spectrum, F_INDEX);
                if (idx != null) {
                    if (idx < 0) {
                        throw new MzPeakException("spectrum.index exceeds supported range (uint64 high bit set): " + idx);
                    }
                    builders.putIfAbsent(idx, Builder.from(spectrum, idx));
                }
            }
            indexFacet(row, "scan", scansBySource);
            indexFacet(row, "precursor", precursorsBySource);
            indexFacet(row, "selected_ion", selectedIonsBySource);
        });

        List<SpectrumDescription> out = new ArrayList<>(builders.size());
        for (Builder b : builders.values()) {
            List<ScanEvent> scans = buildScans(scansBySource.get(b.index));
            List<Precursor> precursors = buildPrecursors(
                    precursorsBySource.get(b.index), selectedIonsBySource.get(b.index));
            out.add(new SpectrumDescription(
                    b.index, b.id, b.msLevel, b.time, b.polarity, b.continuity,
                    b.numberOfDataPoints, b.numberOfPeaks, scans, precursors));
        }
        out.sort((a, b) -> Long.compare(a.index(), b.index()));
        return out;
    }

    private static void indexFacet(Group row, String facetName, Map<Long, List<Group>> target) {
        Group facet = ParquetGroups.optGroup(row, facetName);
        if (facet != null) {
            Long src = ParquetGroups.optLong(facet, F_SOURCE_INDEX);
            if (src != null) {
                target.computeIfAbsent(src, k -> new ArrayList<>()).add(facet);
            }
        }
    }

    private static List<ScanEvent> buildScans(List<Group> scans) {
        if (scans == null) {
            return List.of();
        }
        List<ScanEvent> out = new ArrayList<>(scans.size());
        for (Group scan : scans) {
            Double start = ParquetGroups.optDouble(scan, F_SCAN_START);
            Double injection = ParquetGroups.optDouble(scan, F_INJECTION);
            String filter = ParquetGroups.optString(scan, F_FILTER);
            // scan_windows (a nested LIST) is intentionally not decoded in the prototype.
            out.add(new ScanEvent(start == null ? Double.NaN : start, injection, filter, List.of()));
        }
        return out;
    }

    private static List<Precursor> buildPrecursors(List<Group> precursorGroups, List<Group> selectedIonGroups) {
        if (precursorGroups == null && selectedIonGroups == null) {
            return List.of();
        }
        List<SelectedIon> ions = new ArrayList<>();
        if (selectedIonGroups != null) {
            for (Group ion : selectedIonGroups) {
                Double mz = ParquetGroups.optDouble(ion, F_SELECTED_MZ);
                if (mz == null) {
                    continue;
                }
                ions.add(new SelectedIon(mz,
                        ParquetGroups.optInt(ion, F_CHARGE),
                        ParquetGroups.optDouble(ion, F_SELECTED_INTENSITY)));
            }
        }
        if (precursorGroups == null || precursorGroups.isEmpty()) {
            // selected ions but no precursor record: surface a precursor carrying the ions
            return List.of(new Precursor(null, null, null, ions));
        }
        // Build one Precursor per precursor record (no silent collapse). Selected ions for the spectrum are
        // attached to the first precursor; multi-precursor ion partitioning is future work (the example
        // format has exactly one precursor per MSn spectrum).
        List<Precursor> out = new ArrayList<>(precursorGroups.size());
        for (int i = 0; i < precursorGroups.size(); i++) {
            Group precursor = precursorGroups.get(i);
            Group iso = ParquetGroups.optGroup(precursor, F_ISOLATION);
            IsolationWindow window = iso == null ? null : new IsolationWindow(
                    ParquetGroups.optDouble(iso, F_ISO_TARGET),
                    ParquetGroups.optDouble(iso, F_ISO_LOWER),
                    ParquetGroups.optDouble(iso, F_ISO_UPPER));
            out.add(new Precursor(
                    ParquetGroups.optLong(precursor, F_PRECURSOR_INDEX),
                    ParquetGroups.optString(precursor, F_PRECURSOR_ID),
                    window,
                    i == 0 ? ions : List.of()));
        }
        return out;
    }

    /** Mutable assembly holder for one spectrum's core fields. */
    private static final class Builder {
        long index;
        String id;
        int msLevel;
        double time;
        Polarity polarity;
        SignalContinuity continuity;
        long numberOfDataPoints;
        long numberOfPeaks;

        static Builder from(Group spectrum, long idx) {
            Builder b = new Builder();
            b.index = idx;
            b.id = ParquetGroups.optString(spectrum, F_ID);
            Integer level = ParquetGroups.optInt(spectrum, F_MS_LEVEL);
            b.msLevel = level == null ? 0 : level;
            Double t = ParquetGroups.optDouble(spectrum, F_TIME);
            b.time = t == null ? Double.NaN : t;
            b.polarity = Polarity.fromCode(ParquetGroups.optInt(spectrum, F_POLARITY));
            b.continuity = SignalContinuity.fromCurie(ParquetGroups.optString(spectrum, F_REPRESENTATION));
            Long nPts = ParquetGroups.optLong(spectrum, F_N_POINTS);
            b.numberOfDataPoints = nPts == null ? 0 : nPts;
            Long nPeaks = ParquetGroups.optLong(spectrum, F_N_PEAKS);
            b.numberOfPeaks = nPeaks == null ? 0 : nPeaks;
            return b;
        }
    }
}
