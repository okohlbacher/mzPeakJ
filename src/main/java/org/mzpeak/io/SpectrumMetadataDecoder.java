package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Activation;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
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
    private static final String F_MZ_DELTA_MODEL = "mz_delta_model";
    // facet keys
    private static final String F_SOURCE_INDEX = "source_index";
    // scan facet
    private static final String F_SCAN_START = "MS_1000016_scan_start_time_unit_UO_0000031";
    private static final String F_INJECTION = "MS_1000927_ion_injection_time_unit_UO_0000028";
    private static final String F_FILTER = "MS_1000512_filter_string";
    // precursor facet
    private static final String F_PRECURSOR_INDEX = "precursor_index";
    private static final String F_PRECURSOR_ID = "precursor_id";
    private static final String F_ACTIVATION = "activation";
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

    static Decoded decode(InputFile metadataFile) {
        Map<Long, Builder> builders = new LinkedHashMap<>();
        Map<Long, List<Group>> scansBySource = new HashMap<>();
        Map<Long, List<Group>> precursorsBySource = new HashMap<>();
        Map<Long, List<Group>> selectedIonsBySource = new HashMap<>();
        Map<Long, double[]> deltaModels = new HashMap<>();

        ParquetGroups.forEach(metadataFile, row -> {
            Group spectrum = ParquetGroups.optGroup(row, "spectrum");
            if (spectrum != null) {
                Long idx = ParquetGroups.optLong(spectrum, F_INDEX);
                if (idx != null) {
                    if (idx < 0) {
                        throw new MzPeakException("spectrum.index exceeds supported range (uint64 high bit set): " + idx);
                    }
                    if (builders.putIfAbsent(idx, Builder.from(spectrum, idx)) != null) {
                        throw new MzPeakException("duplicate spectrum.index in metadata: " + idx);
                    }
                    double[] model = ParquetGroups.doubleListNullable(spectrum, F_MZ_DELTA_MODEL);
                    if (model.length > 0) {
                        deltaModels.put(idx, model);
                    }
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
                    b.numberOfDataPoints, b.numberOfPeaks, scans, precursors,
                    b.spectrumParams));
        }
        out.sort((a, b) -> Long.compare(a.index(), b.index()));
        return new Decoded(out, deltaModels);
    }

    /** Decoded spectrum metadata plus the per-spectrum {@code mz_delta_model} polynomial coefficients. */
    record Decoded(List<SpectrumDescription> descriptions, Map<Long, double[]> deltaModels) {
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

    // Dedicated column names for ion mobility (newer files promote these to typed columns)
    private static final String F_ION_MOBILITY_VALUE = "ion_mobility_value";
    private static final String F_ION_MOBILITY_TYPE  = "ion_mobility_type";
    // Dedicated column names for MSI imaging position (newer files promote IMS:1000050/51 to typed columns)
    private static final String F_POSITION_X = "IMS_1000050_position_x";
    private static final String F_POSITION_Y = "IMS_1000051_position_y";

    private static List<ScanEvent> buildScans(List<Group> scans) {
        if (scans == null) {
            return List.of();
        }
        List<ScanEvent> out = new ArrayList<>(scans.size());
        for (Group scan : scans) {
            Double start = ParquetGroups.optDouble(scan, F_SCAN_START);
            Double injection = ParquetGroups.optDouble(scan, F_INJECTION);
            String filter = ParquetGroups.optString(scan, F_FILTER);
            // scan_windows (a nested LIST) are not decoded.
            List<Param> scanParams = ParquetGroups.readParams(scan, "parameters");

            // Newer files may promote ion mobility and imaging position to dedicated typed columns.
            // Normalise them into scanParams so ScanEvent.ionMobilityValue() / imagingPosition() work
            // uniformly regardless of file generation.
            scanParams = normalizeScanParams(scan, scanParams);

            out.add(new ScanEvent(start == null ? Double.NaN : start, injection, filter, List.of(), scanParams));
        }
        return out;
    }

    /**
     * Read any dedicated scan columns that the Rust writer promotes out of the params list
     * ({@code ion_mobility_value}, {@code IMS_1000050_position_x}, {@code IMS_1000051_position_y})
     * and inject them as synthetic {@link Param} entries so callers get a uniform view.
     */
    private static List<Param> normalizeScanParams(Group scan, List<Param> params) {
        // Check if params already carry these accessions (older format stores them in params directly)
        boolean hasIM   = params.stream().anyMatch(p -> isIonMobilityAccession(p.accession()));
        boolean hasPosX = params.stream().anyMatch(p -> "IMS:1000050".equals(p.accession()));
        boolean hasPosY = params.stream().anyMatch(p -> "IMS:1000051".equals(p.accession()));

        List<Param> extra = null;

        // Dedicated ion_mobility_value column
        if (!hasIM && ParquetGroups.has(scan, F_ION_MOBILITY_VALUE)) {
            Double val = ParquetGroups.optDouble(scan, F_ION_MOBILITY_VALUE);
            if (val != null && Double.isFinite(val)) {
                String type = ParquetGroups.optString(scan, F_ION_MOBILITY_TYPE);
                String acc = (type != null && !type.isBlank()) ? type : "MS:1002476"; // default to mean 1/K0
                if (extra == null) extra = new ArrayList<>(params);
                extra.add(new Param("ion mobility value", acc, val, null));
            }
        }

        // Dedicated imaging position columns (IMS:1000050/51)
        if (!hasPosX && ParquetGroups.has(scan, F_POSITION_X)) {
            Integer x = ParquetGroups.optInt(scan, F_POSITION_X);
            if (x != null) {
                if (extra == null) extra = new ArrayList<>(params);
                extra.add(new Param("position x", "IMS:1000050", (long) x, null));
            }
        }
        if (!hasPosY && ParquetGroups.has(scan, F_POSITION_Y)) {
            Integer y = ParquetGroups.optInt(scan, F_POSITION_Y);
            if (y != null) {
                if (extra == null) extra = new ArrayList<>(params);
                extra.add(new Param("position y", "IMS:1000051", (long) y, null));
            }
        }

        return extra != null ? extra : params;
    }

    private static boolean isIonMobilityAccession(String acc) {
        if (acc == null) return false;
        return acc.equals("MS:1002476") || acc.equals("MS:1002477") || acc.equals("MS:1002478")
            || acc.equals("MS:1002814") || acc.equals("MS:1002816")
            || acc.equals("MS:1003006") || acc.equals("MS:1003007")
            || acc.equals("MS:1003153") || acc.equals("MS:1003155") || acc.equals("MS:1003156");
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
                        ParquetGroups.optDouble(ion, F_SELECTED_INTENSITY),
                        ParquetGroups.readParams(ion, "parameters")));
            }
        }
        if (precursorGroups == null || precursorGroups.isEmpty()) {
            // selected ions but no precursor record: surface a precursor carrying the ions
            return List.of(new Precursor(null, null, null, ions, Activation.EMPTY));
        }
        // Build one Precursor per precursor record. All selected ions are attached to the first precursor;
        // real-world mzPeak spectra have exactly one precursor per MSn acquisition.
        List<Precursor> out = new ArrayList<>(precursorGroups.size());
        for (int i = 0; i < precursorGroups.size(); i++) {
            Group precursor = precursorGroups.get(i);
            Group iso = ParquetGroups.optGroup(precursor, F_ISOLATION);
            IsolationWindow window = iso == null ? null : new IsolationWindow(
                    ParquetGroups.optDouble(iso, F_ISO_TARGET),
                    ParquetGroups.optDouble(iso, F_ISO_LOWER),
                    ParquetGroups.optDouble(iso, F_ISO_UPPER));
            Group activationGroup = ParquetGroups.optGroup(precursor, F_ACTIVATION);
            Activation activation = activationGroup == null
                    ? Activation.EMPTY
                    : new Activation(ParquetGroups.readParams(activationGroup, "parameters"));
            out.add(new Precursor(
                    ParquetGroups.optLong(precursor, F_PRECURSOR_INDEX),
                    ParquetGroups.optString(precursor, F_PRECURSOR_ID),
                    window,
                    i == 0 ? ions : List.of(),
                    activation));
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
        List<Param> spectrumParams = List.of();

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
            b.spectrumParams = ParquetGroups.readParams(spectrum, "parameters");
            return b;
        }
    }
}
