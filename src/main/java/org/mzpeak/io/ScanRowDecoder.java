package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.model.Param;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Decodes one scan row of the spectrum metadata into a {@link ScanEvent}; columns are resolved by accession. */
final class ScanRowDecoder {

    // scan facet
    private static final String SCAN_START_TIME = "MS:1000016";
    private static final String ION_INJECTION_TIME = "MS:1000927";
    private static final String FILTER_STRING = "MS:1000512";
    private static final String PRESET_SCAN_CONFIGURATION = "MS:1000616";
    private static final String F_INSTRUMENT_CONFIGURATION_ID = "instrument_configuration_id";
    private static final String F_INSTRUMENT_CONFIG_REF = "instrument_configuration_ref";
    private static final String F_SPECTRUM_REFERENCE = "spectrum_reference";
    private static final String F_SCAN_WINDOWS = "scan_windows";
    private static final String WINDOW_LOWER = "MS:1000501";
    private static final String WINDOW_UPPER = "MS:1000500";
    /** The typed ion mobility columns, which {@link IonMobilityParams} reads. */
    private static final String F_ION_MOBILITY_VALUE = "ion_mobility_value";
    private static final String F_ION_MOBILITY_TYPE = "ion_mobility_type";

    // MSI imaging position (newer files promote these from the params to typed columns)
    private static final String POSITION_X = "IMS:1000050";
    private static final String POSITION_Y = "IMS:1000051";

    private ScanRowDecoder() {
    }

    /**
     * Decodes one scan row. Its Params are the {@code parameters} list, then the typed ion mobility
     * ({@link IonMobilityParams}), then the imaging positions the list lacks, then the scope's leftovers
     * ({@link ColumnResolver#leftovers}, split layout only). The typed ion mobility and position columns count as
     * consumed whether or not they were added, so none of them appears twice.
     */
    static ScanEvent decode(FacetRow scan) {
        ColumnResolver columns = scan.columns();
        Group g = scan.group();
        Set<String> consumed = new HashSet<>();
        Double start = startTimeInMinutes(columns, g, consume(consumed, columns.resolve(SCAN_START_TIME)));
        Double injection = columns.doubleValue(g, consume(consumed, columns.resolve(ION_INJECTION_TIME)));
        String filter = columns.stringValue(g, consume(consumed, columns.resolve(FILTER_STRING)));
        Integer presetScan = columns.intValue(g, consume(consumed, columns.resolve(PRESET_SCAN_CONFIGURATION)));
        Integer instrConfigRef = columns.intValue(g, consume(consumed,
                columns.resolveName(F_INSTRUMENT_CONFIGURATION_ID, F_INSTRUMENT_CONFIG_REF)));
        String specRef = columns.stringValue(g, consume(consumed, columns.resolveName(F_SPECTRUM_REFERENCE)));

        List<ScanWindow> windows = buildScanWindows(scan);
        List<Param> declared = columns.params(g);

        // Newer files may promote ion mobility and imaging position to dedicated typed columns.
        // Normalise them into scanParams so ScanEvent.ionMobilityValue() / imagingPosition() work
        // uniformly regardless of file generation.
        List<Param> scanParams = IonMobilityParams.withTypedIonMobility(scan, declared);
        scanParams = withPositions(scan, declared, scanParams);

        consumed.add(F_ION_MOBILITY_VALUE);
        consumed.add(F_ION_MOBILITY_TYPE);
        consume(consumed, columns.resolve(POSITION_X));
        consume(consumed, columns.resolve(POSITION_Y));
        List<Param> leftovers = columns.leftovers(g, consumed);
        if (!leftovers.isEmpty()) {
            scanParams = new ArrayList<>(scanParams);
            scanParams.addAll(leftovers);
        }

        return new ScanEvent(start == null ? Double.NaN : start, injection, filter,
                presetScan, instrConfigRef, specRef, windows, scanParams);
    }

    private static List<ScanWindow> buildScanWindows(FacetRow scan) {
        List<FacetRow> windowRows = scan.children(F_SCAN_WINDOWS);
        if (windowRows.isEmpty()) {
            return List.of();
        }
        List<ScanWindow> out = new ArrayList<>(windowRows.size());
        for (FacetRow w : windowRows) {
            ColumnResolver columns = w.columns();
            Double lower = columns.doubleValue(w.group(), columns.resolve(WINDOW_LOWER));
            Double upper = columns.doubleValue(w.group(), columns.resolve(WINDOW_UPPER));
            if (lower != null && upper != null) {
                out.add(new ScanWindow(lower, upper));
            }
        }
        return out;
    }

    /**
     * Read the typed imaging position columns (IMS:1000050/51) and append them to {@code params} as synthetic
     * {@link Param} entries, valued as Long, each only when the row's {@code declared} parameters do not already
     * carry its accession (older files store them there).
     */
    private static List<Param> withPositions(FacetRow scan, List<Param> declared, List<Param> params) {
        boolean hasPosX = declared.stream().anyMatch(p -> POSITION_X.equals(p.accession()));
        boolean hasPosY = declared.stream().anyMatch(p -> POSITION_Y.equals(p.accession()));
        ColumnResolver columns = scan.columns();
        Group g = scan.group();

        List<Param> extra = null;
        if (!hasPosX) {
            Long x = columns.longValue(g, columns.resolve(POSITION_X));
            if (x != null) {
                extra = new ArrayList<>(params);
                extra.add(new Param("position x", POSITION_X, x, null));
            }
        }
        if (!hasPosY) {
            Long y = columns.longValue(g, columns.resolve(POSITION_Y));
            if (y != null) {
                if (extra == null) extra = new ArrayList<>(params);
                extra.add(new Param("position y", POSITION_Y, y, null));
            }
        }
        return extra != null ? extra : params;
    }

    /** Adds the resolved {@code field}, unless {@code null}, to {@code consumed}, and returns it. */
    private static String consume(Set<String> consumed, String field) {
        if (field != null) {
            consumed.add(field);
        }
        return field;
    }

    /**
     * The scan start time cell of {@code field} in minutes, converted from the unit of its mapping (the
     * column_mapping, else {@link ColumnTable}, which gives minutes): minutes or no unit as is, seconds, milliseconds
     * and hours converted, as the Rust reader does. {@code null} when the cell is null or the field absent.
     *
     * @throws MzPeakException if the unit is any other, or is given per row by a unit column (not resolved yet)
     */
    private static Double startTimeInMinutes(ColumnResolver columns, Group g, String field) {
        Double value = columns.doubleValue(g, field);
        if (value == null) {
            return null;
        }
        MzPeakManifest.ColumnMapping mapping = columns.mapping(field).orElse(null);
        if (mapping != null && mapping.unitPath() != null) {
            throw new MzPeakException(columns.member() + ": scan start time column " + columns.path(field)
                    + " takes its unit from the column " + mapping.unitPath() + ", which is not supported");
        }
        String unit = mapping == null ? null : mapping.unit();
        if (unit == null) {
            return value;
        }
        return switch (unit) {
            case "UO:0000031" -> value; // minute
            case "UO:0000010" -> value / 60; // second
            case "UO:0000028" -> value / 60_000; // millisecond
            case "UO:0000032" -> value * 60; // hour
            default -> throw new MzPeakException(columns.member() + ": scan start time column " + columns.path(field)
                    + " has the unit " + unit + "; supported are minute (UO:0000031), second (UO:0000010),"
                    + " millisecond (UO:0000028) and hour (UO:0000032)");
        };
    }
}
