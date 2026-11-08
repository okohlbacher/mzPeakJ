package org.mzpeak.io;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The constant column identity of the nine metadata facets, shared by reader and writer so that column names cannot
 * drift between them. Legacy names are kept here as data rather than in a second decoder.
 *
 * <p>Each facet lists its CV-mapped columns. The canonical rows are the union of the {@code column_mapping}s of the
 * 8 HUPO-PSI/mzPeak 2ee2589 example manifests, copied verbatim and in file order. Each row also lists fallbacks,
 * the other names the same column has gone by: first the spec's alternate names, then the pin-era packed names
 * (HUPO-PSI/mzPeak 29e59b2, mzPeakJ 0.10.3 and earlier).
 *
 * <p>Paths are dotted, relative to the facet's table or packed struct, and omit list and element levels, like
 * {@link MzPeakManifest.ColumnMapping#path()}.
 */
final class ColumnTable {

    /**
     * One CV-mapped column of a facet.
     *
     * @param facet      the facet whose table or packed struct holds the column
     * @param path       the canonical dotted path, as current manifests map it
     * @param accession  the CURIE of the column's term
     * @param name       the term name, as current manifests give it
     * @param unit       the CURIE of the column's unit, or {@code null}
     * @param termMarker the column holds child-term CURIEs (or presence booleans) of the term rather than its value;
     *                   {@code false} where the manifests omit the key
     * @param fallbacks  other dotted paths of the same column, most current first; never {@code null}
     */
    record Column(Facet facet, String path, String accession, String name, String unit, boolean termMarker,
                  List<String> fallbacks) {

        Column {
            Objects.requireNonNull(facet, "facet");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(accession, "accession");
            Objects.requireNonNull(name, "name");
            fallbacks = List.copyOf(fallbacks);
        }
    }

    private static final String MZ = "MS:1000040";
    private static final String COUNTS = "MS:1000131";

    private static final Map<Facet, List<Column>> TABLE = table();

    /** Key and bookkeeping columns, never data and never Params. */
    private static final Set<String> STRUCTURAL = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "index", "id", "source_index", "spectrum_index",
            "scan_index", "precursor_index", "selected_ion_index",
            "parameters", "auxiliary_arrays", "number_of_auxiliary_arrays", "data_processing_id",
            "data_processing_ref")));

    private ColumnTable() {
    }

    /** The CV-mapped columns of {@code facet}, in the order current manifests list them. Unmodifiable. */
    static List<Column> columns(Facet facet) {
        return TABLE.get(Objects.requireNonNull(facet, "facet"));
    }

    /** The column of {@code facet} whose term is {@code accession}. */
    static Optional<Column> byAccession(Facet facet, String accession) {
        Objects.requireNonNull(accession, "accession");
        for (Column c : columns(facet)) {
            if (accession.equals(c.accession())) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /** The column of {@code facet} whose canonical path or one of whose fallbacks is {@code dottedPath}. */
    static Optional<Column> byPath(Facet facet, String dottedPath) {
        Objects.requireNonNull(dottedPath, "dottedPath");
        for (Column c : columns(facet)) {
            if (dottedPath.equals(c.path()) || c.fallbacks().contains(dottedPath)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /**
     * The columns of {@code facet} that are never data and never become Params: the keys ({@code index}, {@code id},
     * {@code source_index}, its alias {@code spectrum_index}, {@code scan_index}, {@code precursor_index} and
     * {@code selected_ion_index}), {@code parameters}, {@code auxiliary_arrays}, {@code number_of_auxiliary_arrays}
     * and the data processing reference in both spellings, {@code data_processing_id} and the spec's
     * {@code data_processing_ref}. The set is the same for every facet, so a stray key or bookkeeping column in any
     * facet is not taken for data. Unmodifiable.
     */
    static Set<String> structural(Facet facet) {
        Objects.requireNonNull(facet, "facet");
        return STRUCTURAL;
    }

    // ---- table ------------------------------------------------------------------------------

    private static Map<Facet, List<Column>> table() {
        Map<Facet, List<Column>> t = new EnumMap<>(Facet.class);
        Facet s = Facet.SPECTRUM;
        t.put(s, List.of(
                column(s, "ms_level", "MS:1000511", "ms level", null, "MS_1000511_ms_level"),
                column(s, "scan_polarity", "MS:1000465", "scan polarity", null, "MS_1000465_scan_polarity"),
                marker(s, "spectrum_representation", "MS:1000525", "spectrum representation",
                        "MS_1000525_spectrum_representation"),
                marker(s, "spectrum_type", "MS:1000559", "spectrum type", "MS_1000559_spectrum_type"),
                column(s, "lowest_observed_mz", "MS:1000528", "lowest observed m/z", MZ,
                        "MS_1000528_lowest_observed_mz_unit_MS_1000040"),
                column(s, "highest_observed_mz", "MS:1000527", "highest observed m/z", MZ,
                        "MS_1000527_highest_observed_mz_unit_MS_1000040"),
                column(s, "number_of_data_points", "MS:1003060", "number of data points", null,
                        "MS_1003060_number_of_data_points"),
                column(s, "number_of_peaks", "MS:1003059", "number of peaks", null, "MS_1003059_number_of_peaks"),
                column(s, "base_peak_mz", "MS:1000504", "base peak m/z", MZ,
                        "MS_1000504_base_peak_mz_unit_MS_1000040"),
                column(s, "base_peak_intensity", "MS:1000505", "base peak intensity", COUNTS,
                        "MS_1000505_base_peak_intensity_unit_MS_1000131"),
                column(s, "total_ion_current", "MS:1000285", "total ion current", COUNTS,
                        "MS_1000285_total_ion_current_unit_MS_1000131"),
                column(s, "polynomial_delta_coordinate_interpolation", "MS:1003821",
                        "polynomial delta coordinate interpolation", null)));
        t.put(Facet.SPECTRUM_SCAN, concat(scans(Facet.SPECTRUM_SCAN), List.of(
                column(Facet.SPECTRUM_SCAN, "opt_IMS_1000050_position_x", "IMS:1000050", "position x", null,
                        "position_x", "IMS_1000050_position_x"),
                column(Facet.SPECTRUM_SCAN, "opt_IMS_1000051_position_y", "IMS:1000051", "position y", null,
                        "position_y", "IMS_1000051_position_y"))));
        t.put(Facet.SPECTRUM_PRECURSOR, precursors(Facet.SPECTRUM_PRECURSOR));
        t.put(Facet.SPECTRUM_SELECTED_ION, selectedIons(Facet.SPECTRUM_SELECTED_ION));
        Facet c = Facet.CHROMATOGRAM;
        t.put(c, List.of(
                column(c, "scan_polarity", "MS:1000465", "scan polarity", null, "MS_1000465_scan_polarity"),
                marker(c, "chromatogram_type", "MS:1000626", "chromatogram type", "MS_1000626_chromatogram_type"),
                column(c, "number_of_data_points", "MS:1003060", "number of data points", null,
                        "MS_1003060_number_of_data_points")));
        t.put(Facet.CHROMATOGRAM_PRECURSOR, precursors(Facet.CHROMATOGRAM_PRECURSOR));
        t.put(Facet.CHROMATOGRAM_SELECTED_ION, selectedIons(Facet.CHROMATOGRAM_SELECTED_ION));
        Facet w = Facet.WAVELENGTH_SPECTRUM;
        t.put(w, List.of(
                marker(w, "spectrum_type", "MS:1000559", "spectrum type", "MS_1000559_spectrum_type"),
                marker(w, "spectrum_representation", "MS:1000525", "spectrum representation",
                        "MS_1000525_spectrum_representation"),
                column(w, "lowest_observed_wavelength", "MS:1000619", "lowest observed wavelength", "UO:0000018",
                        "MS_1000619_lowest_observed_wavelength_unit_UO_0000018"),
                column(w, "highest_observed_wavelength", "MS:1000618", "highest observed wavelength", "UO:0000018",
                        "MS_1000618_highest_observed_wavelength_unit_UO_0000018"),
                column(w, "number_of_data_points", "MS:1003060", "number of data points", null,
                        "MS_1003060_number_of_data_points"),
                // the pin-era name really has a colon in the unit part
                column(w, "lambda_max", "MS:1003812", "lambda max", "UO:0000018",
                        "MS_1003812_lambda_max_unit_UO:0000018"),
                column(w, "base_peak_intensity", "MS:1000505", "base peak intensity", COUNTS,
                        "MS_1000505_base_peak_intensity_unit_MS_1000131"),
                column(w, "total_ion_current", "MS:1000285", "total ion current", COUNTS,
                        "MS_1000285_total_ion_current_unit_MS_1000131")));
        t.put(Facet.WAVELENGTH_SCAN, scans(Facet.WAVELENGTH_SCAN));
        return Collections.unmodifiableMap(t);
    }

    /** The scan columns shared by spectrum and wavelength-spectrum scans. */
    private static List<Column> scans(Facet f) {
        return List.of(
                column(f, "scan_start_time", "MS:1000016", "scan start time", "UO:0000031",
                        "MS_1000016_scan_start_time_unit_UO_0000031"),
                column(f, "preset_scan_configuration", "MS:1000616", "preset scan configuration", null,
                        "MS_1000616_preset_scan_configuration"),
                column(f, "filter_string", "MS:1000512", "filter string", null, "MS_1000512_filter_string"),
                column(f, "ion_injection_time", "MS:1000927", "ion injection time", "UO:0000028",
                        "MS_1000927_ion_injection_time_unit_UO_0000028"),
                column(f, "scan_windows.scan_window_lower_limit", "MS:1000501", "scan window lower limit", MZ,
                        "scan_windows.MS_1000501_scan_window_lower_limit_unit_MS_1000040"),
                column(f, "scan_windows.scan_window_upper_limit", "MS:1000500", "scan window upper limit", MZ,
                        "scan_windows.MS_1000500_scan_window_upper_limit_unit_MS_1000040"));
    }

    /** The precursor columns shared by spectra and chromatograms. */
    private static List<Column> precursors(Facet f) {
        return List.of(
                column(f, "isolation_window.isolation_window_target", "MS:1000827", "isolation window target m/z",
                        MZ, "isolation_window.MS_1000827_isolation_window_target_mz"),
                column(f, "isolation_window.isolation_window_lower_offset", "MS:1000828",
                        "isolation window lower offset", MZ,
                        "isolation_window.MS_1000828_isolation_window_lower_offset"),
                column(f, "isolation_window.isolation_window_upper_offset", "MS:1000829",
                        "isolation window upper offset", MZ,
                        "isolation_window.MS_1000829_isolation_window_upper_offset"),
                marker(f, "activation.dissociation_method", "MS:1000044", "dissociation method"),
                column(f, "activation.collision_energy", "MS:1000045", "collision energy", "UO:0000266"));
    }

    /** The selected-ion columns shared by spectra and chromatograms. */
    private static List<Column> selectedIons(Facet f) {
        return List.of(
                column(f, "selected_ion_mz", "MS:1000744", "selected ion m/z", MZ,
                        "MS_1000744_selected_ion_mz_unit_MS_1000040"),
                column(f, "charge_state", "MS:1000041", "charge state", null, "MS_1000041_charge_state"),
                column(f, "peak_intensity", "MS:1000042", "intensity", COUNTS,
                        "intensity", "MS_1000042_intensity_unit_MS_1000131"));
    }

    private static Column column(Facet f, String path, String accession, String name, String unit,
                                 String... fallbacks) {
        return new Column(f, path, accession, name, unit, false, List.of(fallbacks));
    }

    private static Column marker(Facet f, String path, String accession, String name, String... fallbacks) {
        return new Column(f, path, accession, name, null, true, List.of(fallbacks));
    }

    private static List<Column> concat(List<Column> first, List<Column> second) {
        List<Column> all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }
}
