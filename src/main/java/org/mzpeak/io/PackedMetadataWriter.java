package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;
import org.mzpeak.io.ManifestWriter.Dialect;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.WritePlan.Entry;
import org.mzpeak.io.WritePlan.SpectrumEntry;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.mzpeak.io.DatasetWriter.member;
import static org.mzpeak.io.ParquetOutput.UINT64;
import static org.mzpeak.io.ParquetOutput.countKey;
import static org.mzpeak.io.ParquetOutput.openWriter;
import static org.mzpeak.io.ParquetOutput.paramListField;
import static org.mzpeak.io.ParquetOutput.scanWindowListField;
import static org.mzpeak.io.ParquetOutput.writeParams;
import static org.mzpeak.io.SpectrumSummary.writeDerivedColumns;

/**
 * Writes the packed metadata layout of {@link MzPeakWriter}: one spectra_metadata row per spectrum with its
 * spectrum, primary scan, primary precursor and primary selected-ion facets, and one chromatograms_metadata and
 * wavelength_spectra_metadata row per chromatogram and wavelength spectrum. Its members are listed in the pin-era
 * dialect, {@link Dialect#PIN_29E59B2}. The wavelength schema is frozen without a {@code parameters} list, so the
 * Params of wavelength spectra are not written; the chromatogram schema is frozen with its index, id, type and point
 * count only, so the polarity, data processing reference, Params and precursors of chromatograms are not written
 * either. {@link WritePlanner} warns about both.
 */
final class PackedMetadataWriter implements MetadataTablesWriter {

    /** The packed layout's writer; it holds no state. */
    static final PackedMetadataWriter INSTANCE = new PackedMetadataWriter();

    private static final MessageType METADATA_SCHEMA = Types.buildMessage()
            .addField(Types.optionalGroup()
                    .required(PrimitiveTypeName.INT64).as(UINT64).named("index")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("id")
                    .optional(PrimitiveTypeName.INT32).named("MS_1000511_ms_level")
                    .optional(PrimitiveTypeName.DOUBLE).named("time")
                    .optional(PrimitiveTypeName.INT32).named("MS_1000465_scan_polarity")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                    .named("MS_1000525_spectrum_representation")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                    .named("MS_1000559_spectrum_type")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000528_lowest_observed_mz_unit_MS_1000040")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000527_highest_observed_mz_unit_MS_1000040")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000504_base_peak_mz_unit_MS_1000040")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000505_base_peak_intensity_unit_MS_1000131")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000285_total_ion_current_unit_MS_1000131")
                    .optional(PrimitiveTypeName.INT64).as(UINT64).named("MS_1003060_number_of_data_points")
                    .optional(PrimitiveTypeName.INT64).as(UINT64).named("MS_1003059_number_of_peaks")
                    .addField(paramListField())
                    .named("spectrum"))
            .addField(Types.optionalGroup()
                    .required(PrimitiveTypeName.INT64).as(UINT64).named("source_index")
                    .optional(PrimitiveTypeName.INT64).as(UINT64).named("scan_index")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000016_scan_start_time_unit_UO_0000031")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000927_ion_injection_time_unit_UO_0000028")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("MS_1000512_filter_string")
                    .optional(PrimitiveTypeName.INT32).named("MS_1000616_preset_scan_configuration")
                    .optional(PrimitiveTypeName.INT32).named("instrument_configuration_ref")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("spectrum_reference")
                    .optional(PrimitiveTypeName.DOUBLE).named("ion_mobility_value")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("ion_mobility_type")
                    .optional(PrimitiveTypeName.INT32).named("IMS_1000050_position_x")
                    .optional(PrimitiveTypeName.INT32).named("IMS_1000051_position_y")
                    .addField(scanWindowListField())
                    .addField(paramListField())
                    .named("scan"))
            .addField(Types.optionalGroup()
                    .required(PrimitiveTypeName.INT64).as(UINT64).named("source_index")
                    .optional(PrimitiveTypeName.INT64).as(UINT64).named("precursor_index")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("precursor_id")
                    .optionalGroup()
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000827_isolation_window_target_mz")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000828_isolation_window_lower_offset")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000829_isolation_window_upper_offset")
                    .addField(paramListField())
                    .named("isolation_window")
                    .addField(Types.optionalGroup().addField(paramListField()).named("activation"))
                    .named("precursor"))
            .addField(Types.optionalGroup()
                    .required(PrimitiveTypeName.INT64).as(UINT64).named("source_index")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000744_selected_ion_mz_unit_MS_1000040")
                    .optional(PrimitiveTypeName.INT32).named("MS_1000041_charge_state")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1000042_intensity_unit_MS_1000131")
                    .addField(paramListField())
                    .named("selected_ion"))
            .named("spectrum_metadata");

    /** Wavelength (UV/DAD) metadata schema — minimal spectrum facet read by {@link WavelengthSpectrumStore}. */
    private static final MessageType WAVELENGTH_META_SCHEMA = Types.buildMessage()
            .addField(Types.optionalGroup()
                    .required(PrimitiveTypeName.INT64).as(UINT64).named("index")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("id")
                    .optional(PrimitiveTypeName.DOUBLE).named("time")
                    .optional(PrimitiveTypeName.DOUBLE).named("MS_1003812_lambda_max_unit_UO:0000018")
                    .optional(PrimitiveTypeName.INT64).as(UINT64).named("MS_1003060_number_of_data_points")
                    .named("spectrum"))
            .named("wavelength_spectra_metadata");

    private static final MessageType CHROM_META_SCHEMA = Types.buildMessage()
            .addField(Types.optionalGroup()
                    .required(PrimitiveTypeName.INT64).as(UINT64).named("index")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("id")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                    .named("MS_1000626_chromatogram_type")
                    .optional(PrimitiveTypeName.INT64).as(UINT64).named("MS_1003060_number_of_data_points")
                    .named("chromatogram"))
            .named("chromatogram_metadata");

    private PackedMetadataWriter() {
    }

    @Override
    public Dialect dialect() {
        return Dialect.PIN_29E59B2;
    }

    @Override
    public void writeSpectrumMetadata(Path directory, List<WrittenMember> members, WritePlan plan)
            throws IOException {
        writeSpectrumFile(member(directory, members, "spectra_metadata.parquet", EntityType.SPECTRUM,
                DataKind.METADATA), plan);
    }

    @Override
    public void writeChromatogramMetadata(Path directory, List<WrittenMember> members, WritePlan plan)
            throws IOException {
        writeChromatogramFile(member(directory, members, "chromatograms_metadata.parquet", EntityType.CHROMATOGRAM,
                DataKind.METADATA), plan);
    }

    @Override
    public void writeWavelengthMetadata(Path directory, List<WrittenMember> members, WritePlan plan)
            throws IOException {
        writeWavelengthFile(member(directory, members, "wavelength_spectra_metadata.parquet",
                EntityType.WAVELENGTH_SPECTRUM, DataKind.METADATA), plan);
    }

    /**
     * Write spectra_metadata, one row per spectrum, with the plan's file metadata and {@code spectrum_count}, the
     * spectra in the archive, as footer key-values.
     */
    private static void writeSpectrumFile(Path file, WritePlan plan) throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(METADATA_SCHEMA);
        long scanIndexCounter = 0;
        Map<String, String> footer = new LinkedHashMap<>(plan.footer());
        footer.put(countKey(SignalEntity.SPECTRUM), Long.toString(plan.spectra().size()));
        try (ParquetWriter<Group> writer = openWriter(file, METADATA_SCHEMA, footer, plan.options())) {
            for (SpectrumEntry entry : plan.spectra()) {
                SpectrumDescription d = entry.description();
                Group row = factory.newGroup();

                Group spectrum = row.addGroup("spectrum");
                spectrum.add("index", entry.sourceIndex());
                if (d.id() != null) {
                    spectrum.add("id", d.id());
                }
                spectrum.add("MS_1000511_ms_level", d.msLevel());
                if (Double.isFinite(d.retentionTime())) {
                    spectrum.add("time", d.retentionTime());
                }
                Integer polarity = polarityCode(d);
                if (polarity != null) {
                    spectrum.add("MS_1000465_scan_polarity", polarity);
                }
                spectrum.add("MS_1000525_spectrum_representation", continuityCurie(d));
                spectrum.add("MS_1000559_spectrum_type", d.msLevel() > 1 ? "MS:1000580" : "MS:1000579");
                writeDerivedColumns(spectrum, entry.summary());
                // Each count is the rows written to its file, and only for a file the spectrum is written to; a
                // spectrum may go to both spectra_data and spectra_peaks, so both counts can be present.
                if (entry.data()) {
                    spectrum.add("MS_1003060_number_of_data_points", (long) entry.dataRows());
                }
                if (entry.peaks()) {
                    spectrum.add("MS_1003059_number_of_peaks", (long) entry.peakRows());
                }
                writeParams(spectrum, d.parameters());

                ScanEvent firstScan = d.scans().isEmpty() ? null : d.scans().get(0);
                populateScan(row.addGroup("scan"), entry.sourceIndex(), scanIndexCounter++, d.retentionTime(),
                        firstScan);

                writePrecursor(row, entry.sourceIndex(), entry.primaryPrecursor());
                writer.write(row);
                // NOTE: multiple scans / selected ions per spectrum (e.g. Bruker PASEF combineIMS frames carry
                // ~25 mobility scans) are NOT yet written as additional packed-facet rows — only the primary
                // scan and selected ion are emitted. The m/z + intensity payload is preserved in full; the
                // forward-backward test surfaces the dropped secondary records via its scans/precursors/ion
                // count checks. Packed multi-facet writing is a deferred enhancement.
            }
        }
    }

    /** Populate a {@code scan} facet group. {@code se} may be {@code null} for a spectrum with no scan events. */
    private static void populateScan(Group scan, long sourceIndex, long scanIndex, double rtFallback, ScanEvent se) {
        scan.add("source_index", sourceIndex);
        scan.add("scan_index", scanIndex);
        double rt = (se != null && Double.isFinite(se.startTime())) ? se.startTime() : rtFallback;
        if (Double.isFinite(rt)) {
            scan.add("MS_1000016_scan_start_time_unit_UO_0000031", rt);
        }
        if (se == null) {
            return;
        }
        if (se.injectionTime() != null) {
            scan.add("MS_1000927_ion_injection_time_unit_UO_0000028", se.injectionTime());
        }
        if (se.filterString() != null) {
            scan.add("MS_1000512_filter_string", se.filterString());
        }
        if (se.presetScanConfiguration() != null) {
            scan.add("MS_1000616_preset_scan_configuration", se.presetScanConfiguration());
        }
        if (se.instrumentConfigurationRef() != null) {
            scan.add("instrument_configuration_ref", se.instrumentConfigurationRef());
        }
        if (se.spectrumReference() != null) {
            scan.add("spectrum_reference", se.spectrumReference());
        }
        // Typed ion-mobility columns (also kept in params; the reader prefers params on read, so there is
        // no duplication — the typed columns satisfy reference/validator parity).
        OptionalDouble im = se.ionMobilityValue();
        if (im.isPresent()) {
            scan.add("ion_mobility_value", im.getAsDouble());
            // Never null with a value present: the type is the accession of the param the value comes from.
            scan.add("ion_mobility_type", se.ionMobilityType());
        }
        // Typed imaging-position columns (required by the validator for imaging archives, which also checks
        // they are 1-based). Value preserved in params for round-trip.
        var pos = se.imagingPosition();
        if (pos != null) {
            scan.add("IMS_1000050_position_x", pos.x());
            scan.add("IMS_1000051_position_y", pos.y());
        }
        writeScanWindows(scan, se.scanWindows());
        writeParams(scan, se.parameters());
    }

    private static void writeScanWindows(Group scan, List<ScanWindow> windows) {
        if (windows.isEmpty()) {
            return;
        }
        Group wrapper = scan.addGroup("scan_windows");
        for (ScanWindow w : windows) {
            Group item = wrapper.addGroup("list").addGroup("item");
            item.add("MS_1000501_scan_window_lower_limit_unit_MS_1000040", w.lowerLimit());
            item.add("MS_1000500_scan_window_upper_limit_unit_MS_1000040", w.upperLimit());
        }
    }

    /** Populate the {@code precursor} and {@code selected_ion} facet groups; {@code precursor} may be {@code null}. */
    private static void writePrecursor(Group row, long sourceIndex, Precursor precursor) {
        if (precursor == null) {
            return;
        }
        Group p = row.addGroup("precursor");
        p.add("source_index", sourceIndex);
        if (precursor.precursorIndex() != null) {
            p.add("precursor_index", precursor.precursorIndex());
        }
        if (precursor.precursorId() != null) {
            p.add("precursor_id", precursor.precursorId());
        }
        if (precursor.isolationWindow() != null && hasAnyWindowValue(precursor)) {
            Group iso = p.addGroup("isolation_window");
            addIfPresent(iso, "MS_1000827_isolation_window_target_mz", precursor.isolationWindow().targetMz());
            addIfPresent(iso, "MS_1000828_isolation_window_lower_offset", precursor.isolationWindow().lowerOffset());
            addIfPresent(iso, "MS_1000829_isolation_window_upper_offset", precursor.isolationWindow().upperOffset());
            // The pin-era schema's isolation_window.parameters (AP-7).
            writeParams(iso, precursor.isolationWindow().parameters());
        }
        // Precursor activation (dissociation method + collision energy). Round-trips via the reader's
        // Precursor.activation(); previously dropped entirely on write.
        if (!precursor.activation().isEmpty()) {
            Group activation = p.addGroup("activation");
            writeParams(activation, precursor.activation().parameters());
        }
        SelectedIon ion = precursor.primaryIon();
        if (ion != null) {
            writeSelectedIon(row.addGroup("selected_ion"), sourceIndex, ion);
        }
    }

    /** Populate a {@code selected_ion} facet group. */
    private static void writeSelectedIon(Group si, long sourceIndex, SelectedIon ion) {
        si.add("source_index", sourceIndex);
        si.add("MS_1000744_selected_ion_mz_unit_MS_1000040", ion.mz());
        if (ion.charge() != null) {
            si.add("MS_1000041_charge_state", ion.charge());
        }
        addIfPresent(si, "MS_1000042_intensity_unit_MS_1000131", ion.intensity());
        writeParams(si, ion.parameters());
    }

    private static void writeChromatogramFile(Path file, WritePlan plan) throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(CHROM_META_SCHEMA);
        try (ParquetWriter<Group> writer = openWriter(file, CHROM_META_SCHEMA, plan.options())) {
            for (Entry<Chromatogram> entry : plan.chromatograms()) {
                Chromatogram c = entry.source();
                Group row = factory.newGroup();
                Group chrom = row.addGroup("chromatogram");
                chrom.add("index", entry.sourceIndex());
                if (c.id() != null) {
                    chrom.add("id", c.id());
                }
                if (c.typeCurie() != null) {
                    chrom.add("MS_1000626_chromatogram_type", c.typeCurie());
                }
                chrom.add("MS_1003060_number_of_data_points", (long) c.size());
                writer.write(row);
            }
        }
    }

    private static void writeWavelengthFile(Path file, WritePlan plan) throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(WAVELENGTH_META_SCHEMA);
        try (ParquetWriter<Group> writer = openWriter(file, WAVELENGTH_META_SCHEMA, plan.options())) {
            for (Entry<WavelengthSpectrum> entry : plan.wavelengthSpectra()) {
                WavelengthSpectrum w = entry.source();
                Group row = factory.newGroup();
                Group s = row.addGroup("spectrum");
                s.add("index", entry.sourceIndex());
                if (w.id() != null) {
                    s.add("id", w.id());
                }
                if (Double.isFinite(w.time())) {
                    s.add("time", w.time());
                }
                if (w.lambdaMax() != null) {
                    s.add("MS_1003812_lambda_max_unit_UO:0000018", w.lambdaMax());
                }
                s.add("MS_1003060_number_of_data_points", (long) w.size());
                writer.write(row);
            }
        }
    }

    private static boolean hasAnyWindowValue(Precursor p) {
        return p.isolationWindow().targetMz() != null
                || p.isolationWindow().lowerOffset() != null
                || p.isolationWindow().upperOffset() != null
                || !p.isolationWindow().parameters().isEmpty();
    }

    private static void addIfPresent(Group group, String field, Double value) {
        if (value != null) {
            group.add(field, value);
        }
    }

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

    private static String continuityCurie(SpectrumDescription d) {
        return d.signalContinuity() == SignalContinuity.CENTROID ? "MS:1000127" : "MS:1000128";
    }
}
