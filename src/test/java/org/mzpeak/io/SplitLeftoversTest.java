package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.GoldenMeta.Row;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.model.Param;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SpectrumDescription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RD-6: the scalar columns of the split spectrum and scan tables that the row decoders do not consume become Params,
 * after the scope's {@code parameters} list (design 4.3, 5.2): the summary columns (lowest and highest observed m/z,
 * base peak, total ion current), the {@code spectrum_type} term marker and unknown {@code opt_} columns. Structural
 * columns ({@code data_processing_id}, {@code auxiliary_arrays}, {@code number_of_auxiliary_arrays}, the keys),
 * consumed columns, lists and null cells never do; the imaging positions count as consumed, so they appear once.
 *
 * <p>Oracles: the FX-2 cells of the 8 current fixtures, read at the paths each member's column_mapping gives, and rows
 * constructed from current small.unpacked and Example_Processed.img.
 */
class SplitLeftoversTest {

    private static final String SMALL_UNPACKED = "small.unpacked.mzpeak";
    private static final String IMG = "Example_Processed.img.mzpeak";

    private static final String MZ = "MS:1000040";
    private static final String COUNTS = "MS:1000131";
    private static final String POSITION_X = "IMS:1000050";
    private static final String POSITION_Y = "IMS:1000051";
    private static final String DEFAULT_ION_MOBILITY_TYPE = "MS:1002476";

    /** Key and bookkeeping columns: never Params (design 4.2). */
    private static final Set<String> STRUCTURAL = Set.of("index", "id", "source_index", "spectrum_index",
            "scan_index", "precursor_index", "selected_ion_index", "parameters", "auxiliary_arrays",
            "number_of_auxiliary_arrays", "data_processing_id");

    /**
     * What SpectrumRowDecoder consumes (design 5.2): by accession through the mapping, else by field name, plus the
     * one m/z delta-model column it reads ({@link #deltaModelField}).
     */
    private static final Set<String> SPECTRUM_CONSUMED_ACCESSIONS = Set.of("MS:1000511", "MS:1000465", "MS:1000525",
            "MS:1003060", "MS:1003059");
    private static final Set<String> SPECTRUM_CONSUMED_FIELDS = Set.of("time");

    /**
     * What ScanRowDecoder consumes (design 5.2), the imaging positions included, plus the one instrument
     * configuration alias it reads ({@link #instrumentConfigurationField}).
     */
    private static final Set<String> SCAN_CONSUMED_ACCESSIONS = Set.of("MS:1000016", "MS:1000927", "MS:1000512",
            "MS:1000616", POSITION_X, POSITION_Y);
    private static final Set<String> SCAN_CONSUMED_FIELDS = Set.of("spectrum_reference", "ion_mobility_value",
            "ion_mobility_type");

    static Stream<String> smallFixtures() {
        return Stream.of("small.mzpeak", "small.chunked.mzpeak", "small.numpress.mzpeak", SMALL_UNPACKED);
    }

    // ---- (a), (b) current small spectrum 0 -----------------------------------------------------------------------

    /**
     * Spectrum 0 has an empty parameters list; its Params are the spectrum_type marker and the five summary columns,
     * in schema order, with their mappings' names and units. The structural columns give none.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void smallSpectrum0ParametersAreItsSpectrumTypeAndSummaryColumns(String fixture) {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            List<Param> params = reader.getMetadata(0).orElseThrow().parameters();
            assertThat(params).containsExactly(
                    new Param(null, "MS:1000579", null, null),
                    new Param("lowest observed m/z", "MS:1000528", 200.00018816645024, MZ),
                    new Param("highest observed m/z", "MS:1000527", 1999.9857293095915, MZ),
                    new Param("base peak m/z", "MS:1000504", 810.4154747204038, MZ),
                    new Param("base peak intensity", "MS:1000505", 1471224.875, COUNTS),
                    new Param("total ion current", "MS:1000285", 71263168.0, COUNTS));
            assertThat(params).extracting(Param::name)
                    .doesNotContain("data_processing_id", "auxiliary_arrays", "number_of_auxiliary_arrays");
        }
    }

    /** Scan 0 of spectrum 0 has nothing left over: its Params are its parameters list. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void smallSpectrum0Scan0ParametersAreItsParametersList(String fixture) {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            List<ScanEvent> scans = reader.getMetadata(0).orElseThrow().scans();
            assertThat(scans).hasSize(1);
            List<Param> params = scans.get(0).parameters();
            assertThat(params).containsExactly(new Param("mass resolving power", "MS:1000800", 100000L, null));
            assertThat(params.get(0).value()).isInstanceOf(Long.class);
        }
    }

    // ---- (c) every spectrum and scan cell of the 8 fixtures ------------------------------------------------------

    /**
     * The Params of every spectrum and scan are exactly the scope's parameters list entries, then (scans only) the
     * imaging positions the list lacks, then one Param per non-null, unconsumed, non-structural scalar cell in column
     * order, with its mapping's accession and unit. The typed ion mobility Param of a scan is taken out before the
     * comparison: whether it is added next to a list entry of the same term depends on the ion mobility term table,
     * which RD-11 changes, and RD-5 checks its value. Rows are matched to spectra by index and source_index; the
     * counts pin that the check is not vacuous.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "small.mzpeak,                  48,  288,   0,  48,  41, 0",
            "small.chunked.mzpeak,          48,  288,   0,  48,  41, 0",
            "small.numpress.mzpeak,         48,  288,   0,  48,  41, 0",
            "small.unpacked.mzpeak,         48,  288,   0,  48,  41, 0",
            "has_uv.mzpeak,                212, 1272, 212, 212,   0, 0",
            "diaPASEF.ref.mzpeak,            9,   54,  16,   9,  16, 8",
            "diaPASEF.grid.mzpeak,           9,   54,  16,   9,  16, 8",
            "Example_Processed.img.mzpeak,   9,   54,   0,   9,   9, 18"
    })
    void everySpectrumAndScanCellAppearsInTheParameters(String fixture, int spectra, int spectrumLeftovers,
                                                        int spectrumEntries, int scans, int scanEntries,
                                                        int scanTyped) {
        GoldenMeta meta = GoldenMeta.load(fixture);
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            MzPeakManifest manifest = reader.manifest();
            Entry spectrumEntry = Facet.SPECTRUM.entry(manifest).orElseThrow();
            Entry scanEntry = Facet.SPECTRUM_SCAN.entry(manifest).orElseThrow();
            Map<Long, Row> spectrumRows = new LinkedHashMap<>();
            for (Row row : meta.rows(spectrumEntry.name())) {
                assertThat(spectrumRows.put((Long) row.get("index"), row)).as("duplicate index").isNull();
            }
            Map<Long, List<Row>> scanRows = bySourceIndex(meta.rows(scanEntry.name()));

            int[] seen = new int[6];   // spectra, spectrum leftovers, spectrum entries, scans, scan entries, typed
            for (SpectrumDescription d : reader.metadata()) {
                String where = fixture + " spectrum " + d.index();
                Row row = spectrumRows.get(d.index());
                assertThat(row).as(where + " row").isNotNull();
                List<Param> entries = entries(row);
                List<Param> leftovers = leftovers(row, spectrumEntry, SPECTRUM_CONSUMED_ACCESSIONS,
                        with(SPECTRUM_CONSUMED_FIELDS, deltaModelField(row, spectrumEntry)));
                assertThat(d.parameters()).as(where)
                        .containsExactlyElementsOf(concat(withoutAccessionsOf(entries, leftovers), leftovers));
                assertOnce(where, d.parameters(), leftovers);
                seen[0]++;
                seen[1] += leftovers.size();
                seen[2] += entries.size();

                List<Row> cells = scanRows.getOrDefault(d.index(), List.of());
                assertThat(d.scans()).as(where + " scans").hasSameSizeAs(cells);
                for (int k = 0; k < cells.size(); k++) {
                    String scan = where + " scan " + k;
                    Row scanRow = cells.get(k);
                    List<Param> listed = entries(scanRow);
                    List<Param> positions = positions(scanRow, scanEntry, listed);
                    List<Param> scanLeftovers = leftovers(scanRow, scanEntry, SCAN_CONSUMED_ACCESSIONS,
                            with(SCAN_CONSUMED_FIELDS, instrumentConfigurationField(scanRow)));
                    List<Param> params = new ArrayList<>(d.scans().get(k).parameters());
                    Param mobility = typedIonMobility(scanRow);
                    if (mobility != null) {
                        assertThat(Collections.frequency(params, mobility)).as(scan + " typed ion mobility")
                                .isLessThanOrEqualTo(1 + Collections.frequency(listed, mobility));
                        int at = params.lastIndexOf(mobility);
                        if (at >= listed.size()) {
                            params.remove(at);
                        }
                        seen[5]++;
                    }
                    assertThat(params).as(scan).containsExactlyElementsOf(
                            concat(concat(listed, positions), scanLeftovers));
                    assertOnce(scan, params, scanLeftovers);
                    for (String position : List.of(POSITION_X, POSITION_Y)) {
                        assertThat(params.stream().filter(p -> p.hasAccession(position)).count())
                                .as(scan + " " + position).isLessThanOrEqualTo(1);
                    }
                    seen[3]++;
                    seen[4] += listed.size();
                    seen[5] += positions.size();
                }
            }
            assertThat(seen).as("spectra, spectrum leftovers, spectrum entries, scans, scan entries, typed")
                    .containsExactly(spectra, spectrumLeftovers, spectrumEntries, scans, scanEntries, scanTyped);
            assertThat(spectrumRows).hasSize(spectra);
        }
    }

    // ---- constructed rows ----------------------------------------------------------------------------------------

    /**
     * Unknown opt_ columns of a spectrum row become Params under their field names, after the parameters list and
     * the mapped leftovers. A non-null data_processing_id stays structural.
     */
    @Test
    void unknownSpectrumColumnsBecomeParamsAfterTheListAndTheMappedOnes() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current(SMALL_UNPACKED))) {
            Entry entry = Facet.SPECTRUM.entry(MzPeakManifest.fromSource(source)).orElseThrow();
            FacetRow spectrum0 = first(source, entry, Facet.SPECTRUM);
            MessageType extended = extend(spectrum0.group().getType(),
                    Types.optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                            .named("opt_spectrum_note"),
                    Types.optional(PrimitiveTypeName.INT32).named("opt_acquisition_count"),
                    Types.optional(PrimitiveTypeName.BOOLEAN).named("opt_vendor_centroided"),
                    Types.optional(PrimitiveTypeName.DOUBLE).named("opt_never_set"));
            Group g = new SimpleGroup(extended);
            copy(spectrum0.group(), g, Set.of("data_processing_id"));
            g.add("data_processing_id", "dp_0");
            g.add("opt_spectrum_note", "from the instrument");
            g.add("opt_acquisition_count", 3);
            g.add("opt_vendor_centroided", false);
            addParam(g, "spectrum title", "MS:1000796", "first", null);

            SpectrumRowDecoder.SpectrumHead head = SpectrumRowDecoder.decode(
                    new FacetRow(Facet.SPECTRUM, g, ColumnResolver.split(extended, entry, Facet.SPECTRUM)), 0);

            assertThat(head.spectrumParams).containsExactly(
                    new Param("spectrum title", "MS:1000796", "first", null),
                    new Param(null, "MS:1000579", null, null),
                    new Param("lowest observed m/z", "MS:1000528", 200.00018816645024, MZ),
                    new Param("highest observed m/z", "MS:1000527", 1999.9857293095915, MZ),
                    new Param("base peak m/z", "MS:1000504", 810.4154747204038, MZ),
                    new Param("base peak intensity", "MS:1000505", 1471224.875, COUNTS),
                    new Param("total ion current", "MS:1000285", 71263168.0, COUNTS),
                    new Param("opt_spectrum_note", null, "from the instrument", null),
                    new Param("opt_acquisition_count", null, 3L, null),
                    new Param("opt_vendor_centroided", null, false, null));
            assertThat(head.msLevel).isEqualTo(1);
            assertThat(head.numberOfDataPoints).isEqualTo(13589);
        }
    }

    /**
     * An unknown opt_ column of a scan row becomes a Param after the parameters list and the imaging positions. The
     * position columns are consumed: a position the list already holds is not repeated from its column.
     */
    @Test
    void unknownScanColumnsBecomeParamsAndPositionsAppearOnce() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current(IMG))) {
            Entry entry = Facet.SPECTRUM_SCAN.entry(MzPeakManifest.fromSource(source)).orElseThrow();
            FacetRow scan0 = first(source, entry, Facet.SPECTRUM_SCAN);
            MessageType extended = extend(scan0.group().getType(),
                    Types.optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                            .named("opt_scan_note"));
            ColumnResolver columns = ColumnResolver.split(extended, entry, Facet.SPECTRUM_SCAN);
            Param increasing = new Param("increasing m/z scan", "MS:1000093", null, null);

            Group g = new SimpleGroup(extended);
            copy(scan0.group(), g, Set.of());
            g.add("opt_scan_note", "raster");
            assertThat(ScanRowDecoder.decode(new FacetRow(Facet.SPECTRUM_SCAN, g, columns)).parameters())
                    .containsExactly(
                            increasing,
                            new Param("position x", POSITION_X, 1L, null),
                            new Param("position y", POSITION_Y, 1L, null),
                            new Param("opt_scan_note", null, "raster", null));

            Group declared = new SimpleGroup(extended);
            copy(scan0.group(), declared, Set.of());
            declared.add("opt_scan_note", "raster");
            addParam(declared, "position x", POSITION_X, 7L, null);
            ScanEvent scan = ScanRowDecoder.decode(new FacetRow(Facet.SPECTRUM_SCAN, declared, columns));
            assertThat(scan.parameters()).containsExactly(
                    increasing,
                    new Param("position x", POSITION_X, 7L, null),
                    new Param("position y", POSITION_Y, 1L, null),
                    new Param("opt_scan_note", null, "raster", null));
            assertThat(scan.filterString()).isEqualTo("ITMS - p NSI Full ms [100,00-800,00]");
        }
    }

    // ---- FN-9: a column wins over a list entry; the delta-model and instrument configuration choices ------------

    /** A base_peak_mz column and a parameters-list entry of its accession give one Param, the column's. */
    @Test
    void aSpectrumColumnWinsOverAListEntryOfTheSameAccession() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current(SMALL_UNPACKED))) {
            Entry entry = Facet.SPECTRUM.entry(MzPeakManifest.fromSource(source)).orElseThrow();
            FacetRow spectrum0 = first(source, entry, Facet.SPECTRUM);
            MessageType schema = extend(spectrum0.group().getType());
            Group g = new SimpleGroup(schema);
            copy(spectrum0.group(), g, Set.of());
            addParam(g, "base peak m/z", "MS:1000504", 123.0, MZ);
            addParam(g, "spectrum title", "MS:1000796", "first", null);

            SpectrumRowDecoder.SpectrumHead head = SpectrumRowDecoder.decode(
                    new FacetRow(Facet.SPECTRUM, g, ColumnResolver.split(schema, entry, Facet.SPECTRUM)), 0);

            assertThat(head.spectrumParams).filteredOn(p -> p.hasAccession("MS:1000504"))
                    .containsExactly(new Param("base peak m/z", "MS:1000504", 810.4154747204038, MZ));
            assertThat(head.spectrumParams).first()
                    .isEqualTo(new Param("spectrum title", "MS:1000796", "first", null));
        }
    }

    /** A row whose only delta model is median_delta reads it as a one-coefficient model, not as a Param. */
    @Test
    void medianDeltaAloneIsTheDeltaModelAndNoParam() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current(SMALL_UNPACKED))) {
            Entry entry = Facet.SPECTRUM.entry(MzPeakManifest.fromSource(source)).orElseThrow();
            FacetRow spectrum0 = first(source, entry, Facet.SPECTRUM);
            String list = "polynomial_delta_coordinate_interpolation";
            assertThat(spectrum0.group().getType().containsField(list)).isTrue();
            MessageType schema = extend(without(spectrum0.group().getType(), list),
                    Types.optional(PrimitiveTypeName.DOUBLE).named("median_delta"));
            Group g = new SimpleGroup(schema);
            copy(spectrum0.group(), g, Set.of(list));
            g.add("median_delta", 0.0025);

            SpectrumRowDecoder.SpectrumHead head = SpectrumRowDecoder.decode(
                    new FacetRow(Facet.SPECTRUM, g, ColumnResolver.split(schema, entry, Facet.SPECTRUM)), 0);

            assertThat(head.mzDeltaModel).containsExactly(0.0025);
            assertThat(head.spectrumParams).extracting(Param::name).doesNotContain("median_delta");
            assertThat(head.spectrumParams).noneMatch(p -> Double.valueOf(0.0025).equals(p.value()));
        }
    }

    /**
     * A scan row with both instrument configuration aliases reads instrument_configuration_id; the other alias is
     * not consumed and becomes a Param under its field name.
     */
    @Test
    void theInstrumentConfigurationAliasTheDecoderDoesNotReadBecomesAParam() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current(SMALL_UNPACKED))) {
            Entry entry = Facet.SPECTRUM_SCAN.entry(MzPeakManifest.fromSource(source)).orElseThrow();
            FacetRow scan0 = first(source, entry, Facet.SPECTRUM_SCAN);
            assertThat(scan0.group().getType().containsField("instrument_configuration_id")).isTrue();
            MessageType schema = extend(scan0.group().getType(),
                    Types.optional(PrimitiveTypeName.INT32).named("instrument_configuration_ref"));
            Group g = new SimpleGroup(schema);
            copy(scan0.group(), g, Set.of());
            g.add("instrument_configuration_ref", 5);

            ScanEvent scan = ScanRowDecoder.decode(
                    new FacetRow(Facet.SPECTRUM_SCAN, g, ColumnResolver.split(schema, entry, Facet.SPECTRUM_SCAN)));

            assertThat(scan.instrumentConfigurationRef()).isNotNull().isNotEqualTo(5);
            assertThat(scan.parameters()).last()
                    .isEqualTo(new Param("instrument_configuration_ref", null, 5L, null));
        }
    }

    // ---- oracle --------------------------------------------------------------------------------------------------

    /**
     * The m/z delta-model column SpectrumRowDecoder reads from {@code row}: the column of MS:1003821, else of
     * MS:1003820 (also named {@code coordinate_spacing_model} or {@code mz_delta_model}), else {@code median_delta};
     * null when the row has none.
     */
    private static String deltaModelField(Row row, Entry entry) {
        for (String accession : List.of("MS:1003821", "MS:1003820")) {
            Optional<ColumnMapping> mapped = entry.columnForAccession(accession);
            if (mapped.isPresent() && row.values().containsKey(mapped.get().path())) {
                return mapped.get().path();
            }
            List<String> names = accession.equals("MS:1003821")
                    ? List.of("polynomial_delta_coordinate_interpolation")
                    : List.of("coordinate_spacing_model", "mz_delta_model");
            for (String name : names) {
                if (row.values().containsKey(name)) {
                    return name;
                }
            }
        }
        return row.values().containsKey("median_delta") ? "median_delta" : null;
    }

    /** The instrument configuration alias ScanRowDecoder reads: the first of the two the row has, or null. */
    private static String instrumentConfigurationField(Row row) {
        for (String name : List.of("instrument_configuration_id", "instrument_configuration_ref")) {
            if (row.values().containsKey(name)) {
                return name;
            }
        }
        return null;
    }

    /** {@code fields} plus {@code field}, unless null. */
    private static Set<String> with(Set<String> fields, String field) {
        if (field == null) {
            return fields;
        }
        Set<String> out = new HashSet<>(fields);
        out.add(field);
        return out;
    }

    /** {@code entries} without those whose accession a column of {@code leftovers} carries: the column wins. */
    private static List<Param> withoutAccessionsOf(List<Param> entries, List<Param> leftovers) {
        Set<String> accessions = new HashSet<>();
        for (Param p : leftovers) {
            if (p.accession() != null) {
                accessions.add(p.accession());
            }
        }
        return entries.stream().filter(p -> p.accession() == null || !accessions.contains(p.accession())).toList();
    }

    /** The scope's parameters list entries of {@code row} as the Params they decode to. */
    private static List<Param> entries(Row row) {
        List<Param> out = new ArrayList<>();
        for (Object entry : row.list("parameters")) {
            out.add(param(entry));
        }
        return out;
    }

    /**
     * The Params the non-null scalar cells of {@code row} give in column order, skipping structural columns and the
     * consumed ones: a term-marker string as {@code Param(null, cell)}, a true term-marker boolean as the mapping's
     * term, a mapped cell with the mapping's name, accession and unit, an unmapped cell under its field name.
     */
    private static List<Param> leftovers(Row row, Entry entry, Set<String> consumedAccessions,
                                         Set<String> consumedFields) {
        List<Param> out = new ArrayList<>();
        for (Map.Entry<String, Object> cell : row.values().entrySet()) {
            String field = cell.getKey();
            Object value = cell.getValue();
            if (value == null || value instanceof List || value instanceof Map || STRUCTURAL.contains(field)
                    || consumedFields.contains(field)) {
                continue;
            }
            Optional<ColumnMapping> mapped = entry.columnAtPath(field);
            if (mapped.isEmpty()) {
                out.add(new Param(field, null, value, null));
                continue;
            }
            ColumnMapping m = mapped.get();
            if (m.accession() != null && consumedAccessions.contains(m.accession())) {
                continue;
            }
            if (Boolean.TRUE.equals(m.termMarker()) && value instanceof String curie) {
                out.add(new Param(null, curie, null, null));
            } else if (Boolean.TRUE.equals(m.termMarker()) && value instanceof Boolean present) {
                if (present) {
                    out.add(new Param(m.name(), m.accession(), null, null));
                }
            } else {
                out.add(new Param(m.name(), m.accession(), value, m.unit()));
            }
        }
        return out;
    }

    /** The Param the typed ion mobility cells of a scan row give, or {@code null} when the value cell is null. */
    private static Param typedIonMobility(Row row) {
        Object value = row.get("ion_mobility_value");
        if (value == null) {
            return null;
        }
        Object type = row.get("ion_mobility_type");
        String accession = type == null ? DEFAULT_ION_MOBILITY_TYPE : (String) type;
        // FN-8: the name and unit of visitor.rs:878-920
        return switch (accession) {
            case "MS:1002476" -> new Param("ion mobility drift time", accession, value, "UO:0000028");
            case "MS:1002815" -> new Param("inverse reduced ion mobility drift time", accession, value, "MS:1002814");
            case "MS:1001581" -> new Param("FAIMS compensation voltage", accession, value, "UO:0000218");
            case "MS:1003371" -> new Param("SELEXION compensation voltage", accession, value, "UO:0000218");
            default -> new Param("ion mobility value", accession, value, null);
        };
    }

    /** The Params the imaging position cells of a scan row give when its parameters list lacks them. */
    private static List<Param> positions(Row row, Entry entry, List<Param> entries) {
        List<Param> out = new ArrayList<>();
        for (String position : List.of(POSITION_X, POSITION_Y)) {
            Optional<ColumnMapping> mapped = entry.columnForAccession(position);
            Object cell = mapped.isEmpty() ? null : row.get(mapped.get().path());
            if (cell != null && entries.stream().noneMatch(p -> p.hasAccession(position))) {
                out.add(new Param(position.equals(POSITION_X) ? "position x" : "position y", position, cell, null));
            }
        }
        return out;
    }

    /** Every one of {@code leftovers} appears exactly once in {@code params}. */
    private static void assertOnce(String where, List<Param> params, List<Param> leftovers) {
        for (Param p : leftovers) {
            assertThat(Collections.frequency(params, p)).as(where + " " + p).isEqualTo(1);
        }
    }

    /** A {@code parameters} list entry of the FX-2 cells as the Param it decodes to: the first non-null arm wins. */
    private static Param param(Object entry) {
        Map<?, ?> cell = (Map<?, ?>) entry;
        Map<?, ?> value = (Map<?, ?>) cell.get("value");
        Object v = null;
        if (value != null) {
            for (String arm : List.of("integer", "float", "string", "boolean")) {
                if (value.get(arm) != null) {
                    v = value.get(arm);
                    break;
                }
            }
        }
        return new Param((String) cell.get("name"), (String) cell.get("accession"), v, (String) cell.get("unit"));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static Map<Long, List<Row>> bySourceIndex(List<Row> rows) {
        Map<Long, List<Row>> out = new LinkedHashMap<>();
        for (Row row : rows) {
            Object key = row.get("source_index");
            assertThat(key).as("%s row %d source_index", row.member(), row.row()).isInstanceOf(Long.class);
            out.computeIfAbsent((Long) key, k -> new ArrayList<>()).add(row);
        }
        return out;
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> out = new ArrayList<>(first);
        out.addAll(second);
        return out;
    }

    /** The first row of {@code entry}'s member, as {@link MetadataTable} reads it. */
    private static FacetRow first(MzPeakSource source, Entry entry, Facet facet) {
        List<FacetRow> rows = new ArrayList<>();
        MetadataTable.forEachRow(source, entry, facet, rows::add);
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).key()).isEqualTo(0L);
        return rows.get(0);
    }

    /** {@code schema} without the top-level fields {@code names}. */
    private static GroupType without(GroupType schema, String... names) {
        List<Type> fields = new ArrayList<>(schema.getFields());
        fields.removeIf(t -> List.of(names).contains(t.getName()));
        return new MessageType(schema.getName(), fields);
    }

    /** {@code schema} with {@code extra} appended at the top level. */
    private static MessageType extend(GroupType schema, Type... extra) {
        List<Type> fields = new ArrayList<>(schema.getFields());
        fields.addAll(List.of(extra));
        return new MessageType(schema.getName(), fields);
    }

    /** Appends {@code Param(name, accession, value, unit)} to the {@code parameters} list of {@code struct}. */
    private static void addParam(Group struct, String name, String accession, Object value, String unit) {
        Group item = struct.getGroup("parameters", 0).addGroup("list").addGroup("item");
        Group v = item.addGroup("value");
        if (value instanceof Long l) {
            v.add("integer", l);
        } else if (value instanceof Double d) {
            v.add("float", d);
        } else if (value instanceof String s) {
            v.add("string", s);
        } else if (value instanceof Boolean b) {
            v.add("boolean", b);
        }
        if (accession != null) {
            item.add("accession", accession);
        }
        if (name != null) {
            item.add("name", name);
        }
        if (unit != null) {
            item.add("unit", unit);
        }
    }

    /** Copies every value of {@code src} except the fields {@code omit} into {@code dst} by name, recursively. */
    private static void copy(Group src, Group dst, Set<String> omit) {
        GroupType type = src.getType();
        for (int f = 0; f < type.getFieldCount(); f++) {
            Type field = type.getType(f);
            String name = field.getName();
            if (omit.contains(name)) {
                continue;
            }
            for (int i = 0; i < src.getFieldRepetitionCount(f); i++) {
                if (!field.isPrimitive()) {
                    copy(src.getGroup(f, i), dst.addGroup(name), Set.of());
                    continue;
                }
                PrimitiveTypeName primitive = field.asPrimitiveType().getPrimitiveTypeName();
                switch (primitive) {
                    case INT32 -> dst.add(name, src.getInteger(f, i));
                    case INT64 -> dst.add(name, src.getLong(f, i));
                    case FLOAT -> dst.add(name, src.getFloat(f, i));
                    case DOUBLE -> dst.add(name, src.getDouble(f, i));
                    case BOOLEAN -> dst.add(name, src.getBoolean(f, i));
                    case BINARY -> dst.add(name, src.getBinary(f, i));
                    default -> throw new AssertionError("unexpected " + primitive + " field " + name);
                }
            }
        }
    }
}
