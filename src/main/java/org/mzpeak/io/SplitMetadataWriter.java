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
import org.mzpeak.model.Param;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *       representation, the point counts of the files written) and its {@code parameters};
 *   <li>spectra_metadata_scans: one row per scan of every spectrum, keyed by {@code source_index} with a global
 *       {@code scan_index}, with its typed columns, {@code parameters} and {@code scan_windows}.
 * </ul>
 *
 * <p>Index and count columns are UINT64, other integers INT32, floats DOUBLE. A Param stays in its
 * {@code parameters} list, including ion mobility; nothing is derived. The {@code parameters} and
 * {@code scan_windows} lists are empty, never null, when there is nothing in them, as in the reference's output.
 *
 * <p>Not written yet: the precursor and selected-ion members (WR-5b), which the reader treats as legal when absent,
 * and the split chromatogram and wavelength-spectrum metadata (WR-6), which are written in the packed layout until
 * then. The reader finds every member by role and detects its layout from its own schema.
 */
final class SplitMetadataWriter implements MetadataTablesWriter {

    /** The split layout's writer; it holds no state. */
    static final SplitMetadataWriter INSTANCE = new SplitMetadataWriter();

    private static final String SPECTRA_METADATA = "spectra_metadata.parquet";
    private static final String SPECTRA_SCANS = "spectra_metadata_scans.parquet";

    // structural columns, which no column mapping names
    private static final String INDEX = "index";
    private static final String ID = "id";
    private static final String TIME = "time";
    private static final String SOURCE_INDEX = "source_index";
    private static final String SCAN_INDEX = "scan_index";
    private static final String INSTRUMENT_CONFIGURATION_ID = "instrument_configuration_id";
    private static final String SPECTRUM_REFERENCE = "spectrum_reference";
    private static final String PARAMETERS = "parameters";

    // CV-mapped spectrum columns
    private static final Column MS_LEVEL = column(Facet.SPECTRUM, "MS:1000511");
    private static final Column SCAN_POLARITY = column(Facet.SPECTRUM, "MS:1000465");
    private static final Column REPRESENTATION = column(Facet.SPECTRUM, "MS:1000525");
    private static final Column N_POINTS = column(Facet.SPECTRUM, "MS:1003060");
    private static final Column N_PEAKS = column(Facet.SPECTRUM, "MS:1003059");

    // CV-mapped scan columns
    private static final Column SCAN_START_TIME = column(Facet.SPECTRUM_SCAN, "MS:1000016");
    private static final Column PRESET_SCAN_CONFIGURATION = column(Facet.SPECTRUM_SCAN, "MS:1000616");
    private static final Column FILTER_STRING = column(Facet.SPECTRUM_SCAN, "MS:1000512");
    private static final Column ION_INJECTION_TIME = column(Facet.SPECTRUM_SCAN, "MS:1000927");
    private static final Column WINDOW_LOWER = column(Facet.SPECTRUM_SCAN, "MS:1000501");
    private static final Column WINDOW_UPPER = column(Facet.SPECTRUM_SCAN, "MS:1000500");

    /** The struct list holding the scan windows, the parent of {@link #WINDOW_LOWER} and {@link #WINDOW_UPPER}. */
    private static final String SCAN_WINDOWS = parent(WINDOW_LOWER);

    private static final MessageType SPECTRUM_SCHEMA = Types.buildMessage()
            .required(PrimitiveTypeName.INT64).as(UINT64).named(INDEX)
            .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named(ID)
            .optional(PrimitiveTypeName.INT32).named(field(MS_LEVEL))
            .optional(PrimitiveTypeName.DOUBLE).named(TIME)
            .optional(PrimitiveTypeName.INT32).named(field(SCAN_POLARITY))
            .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named(field(REPRESENTATION))
            .optional(PrimitiveTypeName.INT64).as(UINT64).named(field(N_POINTS))
            .optional(PrimitiveTypeName.INT64).as(UINT64).named(field(N_PEAKS))
            .addField(paramListField())
            .named("spectra_metadata");

    private static final MessageType SCAN_SCHEMA = Types.buildMessage()
            .required(PrimitiveTypeName.INT64).as(UINT64).named(SOURCE_INDEX)
            .required(PrimitiveTypeName.INT64).as(UINT64).named(SCAN_INDEX)
            .optional(PrimitiveTypeName.DOUBLE).named(field(SCAN_START_TIME))
            .optional(PrimitiveTypeName.INT32).named(field(PRESET_SCAN_CONFIGURATION))
            .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named(field(FILTER_STRING))
            .optional(PrimitiveTypeName.DOUBLE).named(field(ION_INJECTION_TIME))
            .optional(PrimitiveTypeName.INT32).named(INSTRUMENT_CONFIGURATION_ID)
            .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named(SPECTRUM_REFERENCE)
            .addField(paramListField())
            .addField(scanWindowListField())
            .named("spectra_metadata_scans");

    private static final List<ColumnMapping> SPECTRUM_MAPPING = mapping(Facet.SPECTRUM,
            Set.of(MS_LEVEL, SCAN_POLARITY, REPRESENTATION, N_POINTS, N_PEAKS));
    private static final List<ColumnMapping> SCAN_MAPPING = mapping(Facet.SPECTRUM_SCAN,
            Set.of(SCAN_START_TIME, PRESET_SCAN_CONFIGURATION, FILTER_STRING, ION_INJECTION_TIME, WINDOW_LOWER,
                    WINDOW_UPPER));

    private SplitMetadataWriter() {
    }

    @Override
    public Dialect dialect() {
        return Dialect.CURRENT;
    }

    /**
     * Writes spectra_metadata and spectra_metadata_scans. Both carry the plan's file metadata and
     * {@code spectrum_count}, the spectra in the archive, as footer key-values, as every metadata member of the
     * reference does.
     */
    @Override
    public void writeSpectrumMetadata(Path directory, List<WrittenMember> members, WritePlan plan)
            throws IOException {
        Map<String, String> footer = new LinkedHashMap<>(plan.footer());
        footer.put(countKey(SignalEntity.SPECTRUM), Long.toString(plan.spectra().size()));
        writeSpectra(member(directory, members, SPECTRA_METADATA, EntityType.SPECTRUM, DataKind.METADATA,
                SPECTRUM_MAPPING), plan, footer);
        writeScans(member(directory, members, SPECTRA_SCANS, EntityType.SPECTRUM, DataKind.SCANS, SCAN_MAPPING),
                plan, footer);
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
                // Each count is the rows written to its file, and only for a file the spectrum is written to.
                if (entry.data()) {
                    row.add(field(N_POINTS), (long) entry.dataRows());
                }
                if (entry.peaks()) {
                    row.add(field(N_PEAKS), (long) entry.peakRows());
                }
                params(row, d.parameters());
                writer.write(row);
            }
        }
    }

    // ---- spectra_metadata_scans ---------------------------------------------------------------------------------

    private static void writeScans(Path file, WritePlan plan, Map<String, String> footer) throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(SCAN_SCHEMA);
        long scanIndex = 0;
        try (ParquetWriter<Group> writer = openWriter(file, SCAN_SCHEMA, footer, plan.options())) {
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
                    params(row, scan.parameters());
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

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Writes {@code params} as the row's {@code parameters} list, an empty list when there are none. */
    private static void params(Group row, List<Param> params) {
        if (params.isEmpty()) {
            row.addGroup(PARAMETERS);
        } else {
            writeParams(row, params);
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
