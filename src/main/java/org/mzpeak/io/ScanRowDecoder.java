package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Param;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;

import java.util.ArrayList;
import java.util.List;

/** Decodes one {@code scan} struct of the packed spectrum metadata layout into a {@link ScanEvent}. */
final class ScanRowDecoder {

    // scan facet
    private static final String F_SCAN_START = "MS_1000016_scan_start_time_unit_UO_0000031";
    private static final String F_INJECTION = "MS_1000927_ion_injection_time_unit_UO_0000028";
    private static final String F_FILTER = "MS_1000512_filter_string";
    private static final String F_PRESET_SCAN = "MS_1000616_preset_scan_configuration";
    private static final String F_INSTRUMENT_CONFIG_REF = "instrument_configuration_ref";
    private static final String F_SPECTRUM_REFERENCE = "spectrum_reference";
    private static final String F_SCAN_WINDOWS = "scan_windows";
    private static final String F_WINDOW_LOWER = "MS_1000501_scan_window_lower_limit_unit_MS_1000040";
    private static final String F_WINDOW_UPPER = "MS_1000500_scan_window_upper_limit_unit_MS_1000040";

    // Dedicated column names for ion mobility (newer files promote these to typed columns)
    private static final String F_ION_MOBILITY_VALUE = "ion_mobility_value";
    private static final String F_ION_MOBILITY_TYPE  = "ion_mobility_type";
    // Dedicated column names for MSI imaging position (newer files promote IMS:1000050/51 to typed columns)
    private static final String F_POSITION_X = "IMS_1000050_position_x";
    private static final String F_POSITION_Y = "IMS_1000051_position_y";

    private ScanRowDecoder() {
    }

    /** Decodes one {@code scan} struct. */
    static ScanEvent decode(Group scan) {
        Double start = ParquetGroups.optDouble(scan, F_SCAN_START);
        Double injection = ParquetGroups.optDouble(scan, F_INJECTION);
        String filter = ParquetGroups.optString(scan, F_FILTER);
        Integer presetScan = ParquetGroups.optInt(scan, F_PRESET_SCAN);
        Integer instrConfigRef = ParquetGroups.optInt(scan, F_INSTRUMENT_CONFIG_REF);
        String specRef = ParquetGroups.optString(scan, F_SPECTRUM_REFERENCE);

        List<ScanWindow> windows = buildScanWindows(scan);
        List<Param> scanParams = ParquetGroups.readParams(scan, "parameters");

        // Newer files may promote ion mobility and imaging position to dedicated typed columns.
        // Normalise them into scanParams so ScanEvent.ionMobilityValue() / imagingPosition() work
        // uniformly regardless of file generation.
        scanParams = normalizeScanParams(scan, scanParams);

        return new ScanEvent(start == null ? Double.NaN : start, injection, filter,
                presetScan, instrConfigRef, specRef, windows, scanParams);
    }

    private static List<ScanWindow> buildScanWindows(Group scan) {
        List<Group> windowGroups = ParquetGroups.groupList(scan, F_SCAN_WINDOWS);
        if (windowGroups.isEmpty()) {
            return List.of();
        }
        List<ScanWindow> out = new ArrayList<>(windowGroups.size());
        for (Group w : windowGroups) {
            Double lower = ParquetGroups.optDouble(w, F_WINDOW_LOWER);
            Double upper = ParquetGroups.optDouble(w, F_WINDOW_UPPER);
            if (lower != null && upper != null) {
                out.add(new ScanWindow(lower, upper));
            }
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
}
