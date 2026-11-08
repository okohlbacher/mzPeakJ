package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.mzpeak.io.ColumnTable.Column;
import org.mzpeak.io.ManifestWriter.Dialect;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.WritePlan.SpectrumEntry;
import org.mzpeak.model.CvTerms;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import static org.mzpeak.io.DatasetWriter.member;
import static org.mzpeak.io.ParquetOutput.UINT64;
import static org.mzpeak.io.ParquetOutput.countKey;
import static org.mzpeak.io.ParquetOutput.openWriter;
import static org.mzpeak.io.ParquetOutput.paramListField;
import static org.mzpeak.io.ParquetOutput.writeParams;

/**
 * Writes the split metadata layout of {@link MzPeakWriter} (HUPO-PSI/mzPeak 0a28759 and later, design §11.8): one
 * flat table per facet, listed in the {@link Dialect#CURRENT} manifest dialect with a column mapping for every
 * CV-mapped column written. Column names, accessions, units and term markers come from {@link ColumnTable}, which the
 * reader resolves them by, so writer and reader cannot drift.
 *
 * <ul>
 *   <li>spectra_metadata: one row per spectrum with its typed columns (index, id, ms level, time, polarity,
 *       representation, spectrum type, the five summary values, the point counts of the files written) and its
 *       {@code parameters};
 *   <li>spectra_metadata_scans: one row per scan of every spectrum, keyed by {@code source_index} with a global
 *       {@code scan_index}, with its typed columns, {@code parameters}, {@code scan_windows} and, when some scan has
 *       one, the imaging positions;
 *   <li>spectra_metadata_precursors: one row per precursor of every spectrum, in the spectrum's order, keyed by
 *       {@code source_index} and the precursor's {@code precursor_index}, with its {@code precursor_id}, its
 *       {@code isolation_window} and its {@code activation} (dissociation method, collision energy,
 *       {@code parameters});
 *   <li>spectra_metadata_selected_ions: one row per selected ion of every precursor, keyed by {@code source_index}
 *       and the {@code precursor_index} of its precursor, with a global {@code selected_ion_index}, its typed columns
 *       and {@code parameters}.
 * </ul>
 *
 * <p>Index and count columns are UINT64, other integers INT32, floats DOUBLE. A Param moves to its standardized
 * column only if the reader rebuilds it equal apart from its name ({@link Promotion}, WR-5c): a summary value, the
 * spectrum type, an imaging position, the dissociation method or the collision energy. Every other Param stays in
 * its {@code parameters} list, ion mobility included. The summary values are derived from the payload only when the
 * spectrum's Params have none of the five, and the spectrum type from the MS level only when they have no spectrum
 * type. The {@code parameters} and {@code scan_windows} lists are empty, never null, when there is nothing in them,
 * as in the reference's output; the isolation window's {@code parameters} list is always empty.
 *
 * <p>Every precursor is written as given, its {@code precursor_index} included. Both readers attach a selected ion
 * to the first precursor of its spectrum whose key {@code (source_index, precursor_index)} equals the ion's, so the
 * ions of a precursor that shares its key with an earlier one of the same spectrum read back on that earlier one
 * (WR-8 adds a planner check that refuses such input). The precursor and selected-ion members are written even
 * when no spectrum has a precursor, as the reference does.
 *
 * <p>Not written yet: the split chromatogram and wavelength-spectrum metadata (WR-6), which are written in the packed
 * layout until then. The reader finds every member by role and detects its layout from its own schema.
 */
final class SplitMetadataWriter implements MetadataTablesWriter {

    /** The split layout's writer; it holds no state. */
    static final SplitMetadataWriter INSTANCE = new SplitMetadataWriter();

    private static final String SPECTRA_METADATA = "spectra_metadata.parquet";
    private static final String SPECTRA_SCANS = "spectra_metadata_scans.parquet";
    private static final String SPECTRA_PRECURSORS = "spectra_metadata_precursors.parquet";
    private static final String SPECTRA_SELECTED_IONS = "spectra_metadata_selected_ions.parquet";

    // structural columns, which no column mapping names
    private static final String INDEX = "index";
    private static final String ID = "id";
    private static final String TIME = "time";
    private static final String SOURCE_INDEX = "source_index";
    private static final String SCAN_INDEX = "scan_index";
    private static final String INSTRUMENT_CONFIGURATION_ID = "instrument_configuration_id";
    private static final String SPECTRUM_REFERENCE = "spectrum_reference";
    private static final String PRECURSOR_INDEX = "precursor_index";
    private static final String PRECURSOR_ID = "precursor_id";
    private static final String SELECTED_ION_INDEX = "selected_ion_index";
    private static final String ACTIVATION = "activation";
    private static final String PARAMETERS = "parameters";

    // CV-mapped spectrum columns
    private static final Column MS_LEVEL = column(Facet.SPECTRUM, "MS:1000511");
    private static final Column SCAN_POLARITY = column(Facet.SPECTRUM, "MS:1000465");
    private static final Column REPRESENTATION = column(Facet.SPECTRUM, "MS:1000525");
    private static final Column SPECTRUM_TYPE = column(Facet.SPECTRUM, "MS:1000559");
    private static final Column LOWEST_MZ = column(Facet.SPECTRUM, "MS:1000528");
    private static final Column HIGHEST_MZ = column(Facet.SPECTRUM, "MS:1000527");
    private static final Column N_POINTS = column(Facet.SPECTRUM, "MS:1003060");
    private static final Column N_PEAKS = column(Facet.SPECTRUM, "MS:1003059");
    private static final Column BASE_PEAK_MZ = column(Facet.SPECTRUM, "MS:1000504");
    private static final Column BASE_PEAK_INTENSITY = column(Facet.SPECTRUM, "MS:1000505");
    private static final Column TIC = column(Facet.SPECTRUM, "MS:1000285");

    /** The summary columns, in the order of {@link SpectrumSummary.Values}. */
    private static final List<Column> SUMMARY = List.of(LOWEST_MZ, HIGHEST_MZ, BASE_PEAK_MZ, BASE_PEAK_INTENSITY, TIC);
    private static final Set<String> SUMMARY_ACCESSIONS = Set.copyOf(SUMMARY.stream().map(Column::accession).toList());

    // CV-mapped scan columns
    private static final Column SCAN_START_TIME = column(Facet.SPECTRUM_SCAN, "MS:1000016");
    private static final Column PRESET_SCAN_CONFIGURATION = column(Facet.SPECTRUM_SCAN, "MS:1000616");
    private static final Column FILTER_STRING = column(Facet.SPECTRUM_SCAN, "MS:1000512");
    private static final Column ION_INJECTION_TIME = column(Facet.SPECTRUM_SCAN, "MS:1000927");
    private static final Column WINDOW_LOWER = column(Facet.SPECTRUM_SCAN, "MS:1000501");
    private static final Column WINDOW_UPPER = column(Facet.SPECTRUM_SCAN, "MS:1000500");
    private static final Column POSITION_X = column(Facet.SPECTRUM_SCAN, "IMS:1000050");
    private static final Column POSITION_Y = column(Facet.SPECTRUM_SCAN, "IMS:1000051");

    /** The struct list holding the scan windows, the parent of {@link #WINDOW_LOWER} and {@link #WINDOW_UPPER}. */
    private static final String SCAN_WINDOWS = parent(WINDOW_LOWER);

    // CV-mapped precursor columns
    private static final Column ISOLATION_TARGET = column(Facet.SPECTRUM_PRECURSOR, "MS:1000827");
    private static final Column ISOLATION_LOWER = column(Facet.SPECTRUM_PRECURSOR, "MS:1000828");
    private static final Column ISOLATION_UPPER = column(Facet.SPECTRUM_PRECURSOR, "MS:1000829");
    private static final Column DISSOCIATION_METHOD = column(Facet.SPECTRUM_PRECURSOR, CvTerms.DISSOCIATION_METHOD);
    private static final Column COLLISION_ENERGY = column(Facet.SPECTRUM_PRECURSOR, CvTerms.COLLISION_ENERGY);

    /** The struct holding the isolation window, the parent of {@link #ISOLATION_TARGET} and its offsets. */
    private static final String ISOLATION_WINDOW = parent(ISOLATION_TARGET);

    // CV-mapped selected-ion columns
    private static final Column SELECTED_ION_MZ = column(Facet.SPECTRUM_SELECTED_ION, "MS:1000744");
    private static final Column CHARGE_STATE = column(Facet.SPECTRUM_SELECTED_ION, "MS:1000041");
    private static final Column PEAK_INTENSITY = column(Facet.SPECTRUM_SELECTED_ION, "MS:1000042");

    private static final MessageType SPECTRUM_SCHEMA = Types.buildMessage()
            .required(PrimitiveTypeName.INT64).as(UINT64).named(INDEX)
            .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named(ID)
            .optional(PrimitiveTypeName.INT32).named(field(MS_LEVEL))
            .optional(PrimitiveTypeName.DOUBLE).named(TIME)
            .optional(PrimitiveTypeName.INT32).named(field(SCAN_POLARITY))
            .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named(field(REPRESENTATION))
            .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named(field(SPECTRUM_TYPE))
            .optional(PrimitiveTypeName.DOUBLE).named(field(LOWEST_MZ))
            .optional(PrimitiveTypeName.DOUBLE).named(field(HIGHEST_MZ))
            .optional(PrimitiveTypeName.INT64).as(UINT64).named(field(N_POINTS))
            .optional(PrimitiveTypeName.INT64).as(UINT64).named(field(N_PEAKS))
            .optional(PrimitiveTypeName.DOUBLE).named(field(BASE_PEAK_MZ))
            .optional(PrimitiveTypeName.DOUBLE).named(field(BASE_PEAK_INTENSITY))
            .optional(PrimitiveTypeName.DOUBLE).named(field(TIC))
            .addField(paramListField())
            .named("spectra_metadata");

    private static final MessageType PRECURSOR_SCHEMA = Types.buildMessage()
            .required(PrimitiveTypeName.INT64).as(UINT64).named(SOURCE_INDEX)
            .optional(PrimitiveTypeName.INT64).as(UINT64).named(PRECURSOR_INDEX)
            .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named(PRECURSOR_ID)
            .addField(Types.optionalGroup()
                    .optional(PrimitiveTypeName.DOUBLE).named(field(ISOLATION_TARGET))
                    .optional(PrimitiveTypeName.DOUBLE).named(field(ISOLATION_LOWER))
                    .optional(PrimitiveTypeName.DOUBLE).named(field(ISOLATION_UPPER))
                    .addField(paramListField())
                    .named(ISOLATION_WINDOW))
            .addField(Types.optionalGroup()
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                    .named(field(DISSOCIATION_METHOD))
                    .optional(PrimitiveTypeName.DOUBLE).named(field(COLLISION_ENERGY))
                    .addField(paramListField())
                    .named(ACTIVATION))
            .named("spectra_metadata_precursors");

    private static final MessageType SELECTED_ION_SCHEMA = Types.buildMessage()
            .required(PrimitiveTypeName.INT64).as(UINT64).named(SOURCE_INDEX)
            .optional(PrimitiveTypeName.INT64).as(UINT64).named(PRECURSOR_INDEX)
            .required(PrimitiveTypeName.INT64).as(UINT64).named(SELECTED_ION_INDEX)
            .optional(PrimitiveTypeName.DOUBLE).named(field(SELECTED_ION_MZ))
            .optional(PrimitiveTypeName.INT32).named(field(CHARGE_STATE))
            .optional(PrimitiveTypeName.DOUBLE).named(field(PEAK_INTENSITY))
            .addField(paramListField())
            .named("spectra_metadata_selected_ions");

    private static final List<ColumnMapping> SPECTRUM_MAPPING = mapping(Facet.SPECTRUM,
            Set.of(MS_LEVEL, SCAN_POLARITY, REPRESENTATION, SPECTRUM_TYPE, LOWEST_MZ, HIGHEST_MZ, N_POINTS, N_PEAKS,
                    BASE_PEAK_MZ, BASE_PEAK_INTENSITY, TIC));
    /** The scan columns always written; the position columns are added when some scan has a position. */
    private static final Set<Column> SCAN_COLUMNS = Set.of(SCAN_START_TIME, PRESET_SCAN_CONFIGURATION, FILTER_STRING,
            ION_INJECTION_TIME, WINDOW_LOWER, WINDOW_UPPER);
    private static final List<ColumnMapping> PRECURSOR_MAPPING = mapping(Facet.SPECTRUM_PRECURSOR,
            Set.of(ISOLATION_TARGET, ISOLATION_LOWER, ISOLATION_UPPER, DISSOCIATION_METHOD, COLLISION_ENERGY));
    private static final List<ColumnMapping> SELECTED_ION_MAPPING = mapping(Facet.SPECTRUM_SELECTED_ION,
            Set.of(SELECTED_ION_MZ, CHARGE_STATE, PEAK_INTENSITY));

    private SplitMetadataWriter() {
    }

    @Override
    public Dialect dialect() {
        return Dialect.CURRENT;
    }

    /**
     * Writes spectra_metadata, spectra_metadata_scans, spectra_metadata_precursors and
     * spectra_metadata_selected_ions. Each carries the plan's file metadata and {@code spectrum_count}, the spectra in
     * the archive, as footer key-values, as every metadata member of the reference does.
     */
    @Override
    public void writeSpectrumMetadata(Path directory, List<WrittenMember> members, WritePlan plan)
            throws IOException {
        Map<String, String> footer = new LinkedHashMap<>(plan.footer());
        footer.put(countKey(SignalEntity.SPECTRUM), Long.toString(plan.spectra().size()));
        writeSpectra(member(directory, members, SPECTRA_METADATA, EntityType.SPECTRUM, DataKind.METADATA,
                SPECTRUM_MAPPING), plan, footer);
        List<Column> positions = positionColumns(plan);
        Set<Column> scanColumns = new HashSet<>(SCAN_COLUMNS);
        scanColumns.addAll(positions);
        writeScans(member(directory, members, SPECTRA_SCANS, EntityType.SPECTRUM, DataKind.SCANS,
                mapping(Facet.SPECTRUM_SCAN, scanColumns)), plan, footer, positions);
        writePrecursors(member(directory, members, SPECTRA_PRECURSORS, EntityType.SPECTRUM, DataKind.PRECURSORS,
                PRECURSOR_MAPPING), plan, footer);
        writeSelectedIons(member(directory, members, SPECTRA_SELECTED_IONS, EntityType.SPECTRUM,
                DataKind.SELECTED_IONS, SELECTED_ION_MAPPING), plan, footer);
    }

    /** Writes the packed chromatogram metadata, until WR-6 splits it. */
    @Override
    public void writeChromatogramMetadata(Path directory, List<WrittenMember> members, WritePlan plan)
            throws IOException {
        PackedMetadataWriter.INSTANCE.writeChromatogramMetadata(directory, members, plan);
    }

    /** Writes the packed wavelength-spectrum metadata, until WR-6 splits it. */
    @Override
    public void writeWavelengthMetadata(Path directory, List<WrittenMember> members, WritePlan plan)
            throws IOException {
        PackedMetadataWriter.INSTANCE.writeWavelengthMetadata(directory, members, plan);
    }

    // ---- spectra_metadata ---------------------------------------------------------------------------------------

    private static void writeSpectra(Path file, WritePlan plan, Map<String, String> footer) throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(SPECTRUM_SCHEMA);
        try (ParquetWriter<Group> writer = openWriter(file, SPECTRUM_SCHEMA, footer, plan.options())) {
            for (SpectrumEntry entry : plan.spectra()) {
                SpectrumDescription d = entry.source().description();
                Group row = factory.newGroup();
                row.add(INDEX, entry.sourceIndex());
                if (d.id() != null) {
                    row.add(ID, d.id());
                }
                row.add(field(MS_LEVEL), d.msLevel());
                if (Double.isFinite(d.retentionTime())) {
                    row.add(TIME, d.retentionTime());
                }
                Integer polarity = polarityCode(d);
                if (polarity != null) {
                    row.add(field(SCAN_POLARITY), polarity);
                }
                String representation = representationCurie(d);
                if (representation != null) {
                    row.add(field(REPRESENTATION), representation);
                }
                Promotion params = new Promotion(d.parameters());
                Param type = params.promote(termOf(SpectrumSummary.SPECTRUM_TYPES));
                if (type != null) {
                    row.add(field(SPECTRUM_TYPE), type.accession());
                } else if (!params.hasAny(SpectrumSummary.SPECTRUM_TYPES)) {
                    row.add(field(SPECTRUM_TYPE), SpectrumSummary.spectrumType(d.msLevel()));
                }
                List<Double> summary = summary(entry.source(), params);
                for (int i = 0; i < SUMMARY.size(); i++) {
                    addIfPresent(row, field(SUMMARY.get(i)), summary.get(i));
                }
                // Each count is the rows written to its file, and only for a file the spectrum is written to.
                if (entry.data()) {
                    row.add(field(N_POINTS), (long) entry.dataRows());
                }
                if (entry.peaks()) {
                    row.add(field(N_PEAKS), (long) entry.peakRows());
                }
                params(row, params.rest());
                writer.write(row);
            }
        }
    }

    /**
     * The five summary values of {@code s}, in {@link #SUMMARY} order, each {@code null} when absent: derived from the
     * payload when its Params have none of the five, else the Params promoted to their columns.
     */
    private static List<Double> summary(Spectrum s, Promotion params) {
        if (!params.hasAny(SUMMARY_ACCESSIONS)) {
            SpectrumSummary.Values v = SpectrumSummary.derive(s);
            return Arrays.asList(v.lowestMz(), v.highestMz(), v.basePeakMz(), v.basePeakIntensity(), v.tic());
        }
        List<Double> out = new ArrayList<>(SUMMARY.size());
        for (Column column : SUMMARY) {
            Param p = params.promote(doubleIn(column));
            out.add(p == null ? null : (Double) p.value());
        }
        return out;
    }

    // ---- spectra_metadata_scans ---------------------------------------------------------------------------------

    /**
     * Writes the scans, with the {@code positions} columns, those of {@link #positionColumns}, after the
     * {@code scan_windows}, as the reference does.
     */
    private static void writeScans(Path file, WritePlan plan, Map<String, String> footer, List<Column> positions)
            throws IOException {
        Types.GroupBuilder<MessageType> builder = Types.buildMessage()
                .required(PrimitiveTypeName.INT64).as(UINT64).named(SOURCE_INDEX)
                .required(PrimitiveTypeName.INT64).as(UINT64).named(SCAN_INDEX)
                .optional(PrimitiveTypeName.DOUBLE).named(field(SCAN_START_TIME))
                .optional(PrimitiveTypeName.INT32).named(field(PRESET_SCAN_CONFIGURATION))
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named(field(FILTER_STRING))
                .optional(PrimitiveTypeName.DOUBLE).named(field(ION_INJECTION_TIME))
                .optional(PrimitiveTypeName.INT32).named(INSTRUMENT_CONFIGURATION_ID)
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named(SPECTRUM_REFERENCE)
                .addField(paramListField())
                .addField(scanWindowListField());
        for (Column position : positions) {
            builder = builder.optional(PrimitiveTypeName.INT32).named(field(position));
        }
        MessageType schema = builder.named("spectra_metadata_scans");
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);
        long scanIndex = 0;
        try (ParquetWriter<Group> writer = openWriter(file, schema, footer, plan.options())) {
            for (SpectrumEntry entry : plan.spectra()) {
                for (ScanEvent scan : entry.source().description().scans()) {
                    Group row = factory.newGroup();
                    row.add(SOURCE_INDEX, entry.sourceIndex());
                    row.add(SCAN_INDEX, scanIndex++);
                    if (Double.isFinite(scan.startTime())) {
                        row.add(field(SCAN_START_TIME), scan.startTime());
                    }
                    if (scan.presetScanConfiguration() != null) {
                        row.add(field(PRESET_SCAN_CONFIGURATION), scan.presetScanConfiguration());
                    }
                    if (scan.filterString() != null) {
                        row.add(field(FILTER_STRING), scan.filterString());
                    }
                    if (scan.injectionTime() != null) {
                        row.add(field(ION_INJECTION_TIME), scan.injectionTime());
                    }
                    if (scan.instrumentConfigurationRef() != null) {
                        row.add(INSTRUMENT_CONFIGURATION_ID, scan.instrumentConfigurationRef());
                    }
                    if (scan.spectrumReference() != null) {
                        row.add(SPECTRUM_REFERENCE, scan.spectrumReference());
                    }
                    Promotion params = new Promotion(scan.parameters());
                    for (Column position : positions) {
                        Param p = params.promote(positionIn(position));
                        if (p != null) {
                            row.add(field(position), ((Long) p.value()).intValue());
                        }
                    }
                    params(row, params.rest());
                    Group windows = row.addGroup(SCAN_WINDOWS);
                    for (ScanWindow w : scan.scanWindows()) {
                        Group item = windows.addGroup("list").addGroup("item");
                        item.add(field(WINDOW_LOWER), w.lowerLimit());
                        item.add(field(WINDOW_UPPER), w.upperLimit());
                    }
                    writer.write(row);
                }
            }
        }
    }

    // ---- spectra_metadata_precursors ----------------------------------------------------------------------------

    private static void writePrecursors(Path file, WritePlan plan, Map<String, String> footer) throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(PRECURSOR_SCHEMA);
        try (ParquetWriter<Group> writer = openWriter(file, PRECURSOR_SCHEMA, footer, plan.options())) {
            for (SpectrumEntry entry : plan.spectra()) {
                for (Precursor precursor : entry.source().description().precursors()) {
                    Group row = factory.newGroup();
                    row.add(SOURCE_INDEX, entry.sourceIndex());
                    if (precursor.precursorIndex() != null) {
                        row.add(PRECURSOR_INDEX, precursor.precursorIndex());
                    }
                    if (precursor.precursorId() != null) {
                        row.add(PRECURSOR_ID, precursor.precursorId());
                    }
                    IsolationWindow window = precursor.isolationWindow();
                    if (window != null) {
                        Group iso = row.addGroup(ISOLATION_WINDOW);
                        addIfPresent(iso, field(ISOLATION_TARGET), window.targetMz());
                        addIfPresent(iso, field(ISOLATION_LOWER), window.lowerOffset());
                        addIfPresent(iso, field(ISOLATION_UPPER), window.upperOffset());
                        // The model has no isolation-window Params yet.
                        params(iso, List.of());
                    }
                    Group activation = row.addGroup(ACTIVATION);
                    Promotion params = new Promotion(precursor.activation().parameters());
                    Param method = params.promote(termOf(CvTerms.DISSOCIATION_METHODS.keySet()));
                    if (method != null) {
                        activation.add(field(DISSOCIATION_METHOD), method.accession());
                    }
                    Param energy = params.promote(doubleIn(COLLISION_ENERGY));
                    if (energy != null) {
                        activation.add(field(COLLISION_ENERGY), (Double) energy.value());
                    }
                    params(activation, params.rest());
                    writer.write(row);
                }
            }
        }
    }

    // ---- spectra_metadata_selected_ions -------------------------------------------------------------------------

    private static void writeSelectedIons(Path file, WritePlan plan, Map<String, String> footer) throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(SELECTED_ION_SCHEMA);
        long selectedIonIndex = 0;
        try (ParquetWriter<Group> writer = openWriter(file, SELECTED_ION_SCHEMA, footer, plan.options())) {
            for (SpectrumEntry entry : plan.spectra()) {
                for (Precursor precursor : entry.source().description().precursors()) {
                    for (SelectedIon ion : precursor.selectedIons()) {
                        Group row = factory.newGroup();
                        row.add(SOURCE_INDEX, entry.sourceIndex());
                        if (precursor.precursorIndex() != null) {
                            row.add(PRECURSOR_INDEX, precursor.precursorIndex());
                        }
                        row.add(SELECTED_ION_INDEX, selectedIonIndex++);
                        row.add(field(SELECTED_ION_MZ), ion.mz());
                        if (ion.charge() != null) {
                            row.add(field(CHARGE_STATE), ion.charge());
                        }
                        addIfPresent(row, field(PEAK_INTENSITY), ion.intensity());
                        params(row, ion.parameters());
                        writer.write(row);
                    }
                }
            }
        }
    }

    /** {@code scan_windows: list<item: struct<scan_window_lower_limit, scan_window_upper_limit>>}. */
    private static Type scanWindowListField() {
        return Types.optionalGroup().as(LogicalTypeAnnotation.listType())
                .addField(Types.repeatedGroup()
                        .addField(Types.optionalGroup()
                                .optional(PrimitiveTypeName.DOUBLE).named(field(WINDOW_LOWER))
                                .optional(PrimitiveTypeName.DOUBLE).named(field(WINDOW_UPPER))
                                .named("item"))
                        .named("list"))
                .named(SCAN_WINDOWS);
    }

    /** The position columns, in table order, that some scan of the plan has a position to promote to. */
    private static List<Column> positionColumns(WritePlan plan) {
        List<Column> out = new ArrayList<>();
        for (Column position : List.of(POSITION_X, POSITION_Y)) {
            boolean promoted = false;
            for (SpectrumEntry entry : plan.spectra()) {
                for (ScanEvent scan : entry.source().description().scans()) {
                    promoted |= new Promotion(scan.parameters()).promote(positionIn(position)) != null;
                }
            }
            if (promoted) {
                out.add(position);
            }
        }
        return out;
    }

    // ---- promotion ----------------------------------------------------------------------------------------------

    /**
     * The Params of one scope as they are written (WR-5c, design §11.8). A Param moves to its column, and leaves the
     * {@code parameters} list, only if the reader rebuilds it equal apart from its name. Per column only the first
     * Param that qualifies is promoted, and only when no other Param of the list has its accession, so no accession
     * is ever both a column and a parameter of the scope.
     */
    private static final class Promotion {

        private final List<Param> params;
        private final boolean[] promoted;

        Promotion(List<Param> params) {
            this.params = params;
            this.promoted = new boolean[params.size()];
        }

        /**
         * Promotes the first Param that {@code qualifies} and returns it; {@code null} when there is none or another
         * Param of the list has its accession.
         */
        Param promote(Predicate<Param> qualifies) {
            for (int i = 0; i < params.size(); i++) {
                Param p = params.get(i);
                if (!promoted[i] && p.accession() != null && qualifies.test(p)) {
                    for (int j = 0; j < params.size(); j++) {
                        if (j != i && p.accession().equals(params.get(j).accession())) {
                            return null;
                        }
                    }
                    promoted[i] = true;
                    return p;
                }
            }
            return null;
        }

        /** Whether a Param of the list, promoted or not, has one of {@code accessions}. */
        boolean hasAny(Collection<String> accessions) {
            for (Param p : params) {
                if (p.accession() != null && accessions.contains(p.accession())) {
                    return true;
                }
            }
            return false;
        }

        /** The Params not promoted, in list order: the scope's {@code parameters} list. */
        List<Param> rest() {
            List<Param> out = new ArrayList<>(params.size());
            for (int i = 0; i < params.size(); i++) {
                if (!promoted[i]) {
                    out.add(params.get(i));
                }
            }
            return out;
        }
    }

    /**
     * A term the reader rebuilds from a term-marker cell as {@code Param(name, cell)}: one of {@code terms}, without
     * value or unit.
     */
    private static Predicate<Param> termOf(Collection<String> terms) {
        return p -> terms.contains(p.accession()) && p.value() == null && p.unit() == null;
    }

    /**
     * A value the reader rebuilds from the DOUBLE {@code column} with its mapping's accession and unit: a finite
     * Double with the column's unit.
     */
    private static Predicate<Param> doubleIn(Column column) {
        return p -> column.accession().equals(p.accession()) && p.value() instanceof Double v && Double.isFinite(v)
                && Objects.equals(column.unit(), p.unit());
    }

    /**
     * An imaging position the reader rebuilds from the INT32 {@code column}: a Long in [0, 2^31) without unit. A
     * negative one stays a Param, as the reference types the column UInt32.
     */
    private static Predicate<Param> positionIn(Column column) {
        return p -> column.accession().equals(p.accession()) && p.value() instanceof Long v && v >= 0
                && v <= Integer.MAX_VALUE && p.unit() == null;
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Writes {@code params} as the row's {@code parameters} list, an empty list when there are none. */
    private static void params(Group row, List<Param> params) {
        if (params.isEmpty()) {
            row.addGroup(PARAMETERS);
        } else {
            writeParams(row, params);
        }
    }

    /** Sets {@code field} of {@code group} to {@code value} unless it is null. */
    private static void addIfPresent(Group group, String field, Double value) {
        if (value != null) {
            group.add(field, value);
        }
    }

    /** The scan polarity code: 1 positive, -1 negative, none when unknown. */
    private static Integer polarityCode(SpectrumDescription d) {
        if (d.polarity() == null) {
            return null;
        }
        return switch (d.polarity()) {
            case POSITIVE -> 1;
            case NEGATIVE -> -1;
            case UNKNOWN -> null;
        };
    }

    /** The spectrum representation term: MS:1000127 centroid, MS:1000128 profile, none when unknown. */
    private static String representationCurie(SpectrumDescription d) {
        if (d.signalContinuity() == null) {
            return null;
        }
        return switch (d.signalContinuity()) {
            case CENTROID -> "MS:1000127";
            case PROFILE -> "MS:1000128";
            case UNKNOWN -> null;
        };
    }

    /** The {@link ColumnTable} column of {@code facet} whose term is {@code accession}. */
    private static Column column(Facet facet, String accession) {
        return ColumnTable.byAccession(facet, accession).orElseThrow(
                () -> new IllegalStateException("no " + accession + " column in " + facet));
    }

    /** The field name of {@code column} in its scope: the last segment of its path. */
    private static String field(Column column) {
        String path = column.path();
        return path.substring(path.lastIndexOf('.') + 1);
    }

    /** The scope of {@code column} below its table: its path without the last segment. */
    private static String parent(Column column) {
        String path = column.path();
        return path.substring(0, path.lastIndexOf('.'));
    }

    /**
     * The column mapping of a member of {@code facet} that writes {@code written}: their {@link ColumnTable} rows, in
     * table order, with an explicit term marker.
     */
    private static List<ColumnMapping> mapping(Facet facet, Set<Column> written) {
        List<ColumnMapping> out = new ArrayList<>();
        for (Column c : ColumnTable.columns(facet)) {
            if (written.contains(c)) {
                out.add(new ColumnMapping(c.name(), c.path(), c.accession(), c.unit(), c.termMarker()));
            }
        }
        return List.copyOf(out);
    }
}
