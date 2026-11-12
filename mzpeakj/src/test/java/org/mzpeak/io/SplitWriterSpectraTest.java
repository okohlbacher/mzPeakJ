package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The split metadata layout written by {@link SplitMetadataWriter} (WR-5a, design §11.4 and §11.8): spectra_metadata
 * and spectra_metadata_scans with typed columns, listed in the current manifest dialect, and scan settings with
 * {@code unit}.
 *
 * <p>Each fixture is read with {@link ForwardBackwardCorpusTest#readSource}, written with
 * {@link MetadataLayout#SPLIT}, read back the same way and compared with {@link DescriptionAssert}, precursors and
 * selected ions included since WR-5b ({@link SplitWriterPrecursorsTest}).
 */
class SplitWriterSpectraTest {

    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Fixtures read once per class; the tests never modify a source in place. */
    private static final Map<Path, Source> SOURCES = new ConcurrentHashMap<>();

    /** How a case writes its source. */
    enum Form {
        DIRECTORY, ARCHIVE, DIRECTORY_NUMPRESS
    }

    static Stream<Arguments> roundTrips() {
        return Stream.of(
                Arguments.of(Fixtures.legacy("small.unpacked.mzpeak"), Form.DIRECTORY),
                Arguments.of(Fixtures.current("small.unpacked.mzpeak"), Form.DIRECTORY),
                Arguments.of(Fixtures.current("Example_Processed.img.mzpeak"), Form.DIRECTORY),
                Arguments.of(Fixtures.current("small.unpacked.mzpeak"), Form.ARCHIVE),
                Arguments.of(Fixtures.legacy("has_uv.mzpeak"), Form.ARCHIVE),
                Arguments.of(Fixtures.current("small.unpacked.mzpeak"), Form.DIRECTORY_NUMPRESS));
    }

    @ParameterizedTest(name = "{0} via {1}")
    @MethodSource("roundTrips")
    void aSplitRoundTripKeepsEveryField(Path fixture, Form form, @TempDir Path tmp) {
        Source source = source(fixture);
        Path out = write(source, form, tmp.resolve("out.mzpeak"));
        Source back = ForwardBackwardCorpusTest.readSource(out);

        assertThat(spectrumMetadataShape(out)).isEqualTo(MetadataShape.SPLIT);
        DescriptionAssert.assertEqual(fixture + " via " + form, descriptions(source), descriptions(back));
        assertThat(back.spectra).hasSameSizeAs(source.spectra);
        for (int i = 0; i < source.spectra.size(); i++) {
            Spectrum a = source.spectra.get(i);
            Spectrum b = back.spectra.get(i);
            if (form == Form.DIRECTORY_NUMPRESS) {
                // Numpress is lossy; the point layout cases compare the values, this one the routing.
                assertThat(b.mz()).as("%s spectrum %d m/z", fixture, a.index()).hasSameSizeAs(a.mz());
                assertThat(b.peaks()).as("%s spectrum %d peaks", fixture, a.index()).hasSameSizeAs(a.peaks());
            } else {
                assertThat(b.mz()).as("%s spectrum %d m/z", fixture, a.index()).isEqualTo(a.mz());
                assertThat(b.intensity()).as("%s spectrum %d intensity", fixture, a.index())
                        .isEqualTo(a.intensity());
                assertThat(b.peaks()).as("%s spectrum %d peaks", fixture, a.index()).isEqualTo(a.peaks());
            }
        }
        assertThat(back.chromatograms).hasSameSizeAs(source.chromatograms);
        assertThat(back.wavelengthSpectra).hasSameSizeAs(source.wavelengthSpectra);
        assertThat(back.scanSettings).isEqualTo(source.scanSettings);
    }

    @Test
    void theSpectrumMetadataIsOneTableOfSpectraAndOneOfScans(@TempDir Path tmp) {
        Source source = source(Fixtures.current("small.unpacked.mzpeak"));
        Path out = write(source, Form.DIRECTORY, tmp.resolve("out.mzpeak"));

        MzPeakManifest manifest;
        try (MzPeakSource dataset = MzPeakSource.open(out)) {
            manifest = MzPeakManifest.fromSource(dataset);
        }
        List<String> roles = new ArrayList<>();
        for (MzPeakManifest.Entry e : manifest.files()) {
            roles.add(e.name() + "|" + e.entityType() + "|" + e.dataKind());
        }
        // WR-6 splits the chromatogram metadata too (SplitWriterChromWlTest).
        assertThat(roles).containsExactly(
                "spectra_metadata.parquet|spectrum|metadata",
                "spectra_metadata_scans.parquet|spectrum|scans",
                "spectra_metadata_precursors.parquet|spectrum|precursors",
                "spectra_metadata_selected_ions.parquet|spectrum|selected_ions",
                "spectra_data.parquet|spectrum|data_arrays",
                "spectra_peaks.parquet|spectrum|peaks",
                "chromatograms_metadata.parquet|chromatogram|metadata",
                "chromatograms_metadata_precursors.parquet|chromatogram|precursors",
                "chromatograms_metadata_selected_ions.parquet|chromatogram|selected_ions",
                "chromatograms_data.parquet|chromatogram|data_arrays");

        // WR-5c added spectrum_type and the five summary columns, and the Rust-reader fix the empty auxiliary
        // arrays, in the reference's order.
        MessageType spectra = schema(out, "spectra_metadata.parquet");
        assertThat(columns(spectra)).containsExactly(
                "index required INT64 uint64", "id optional BINARY string", "ms_level optional INT32 -",
                "time optional DOUBLE -", "scan_polarity optional INT32 -",
                "spectrum_representation optional BINARY string", "spectrum_type optional BINARY string",
                "lowest_observed_mz optional DOUBLE -", "highest_observed_mz optional DOUBLE -",
                "number_of_data_points optional INT64 uint64", "number_of_peaks optional INT64 uint64",
                "base_peak_mz optional DOUBLE -", "base_peak_intensity optional DOUBLE -",
                "total_ion_current optional DOUBLE -", "parameters optional group list",
                "auxiliary_arrays optional group list", "number_of_auxiliary_arrays optional INT32 uint32");
        MessageType scans = schema(out, "spectra_metadata_scans.parquet");
        assertThat(columns(scans)).containsExactly(
                "source_index required INT64 uint64", "scan_index required INT64 uint64",
                "scan_start_time optional DOUBLE -", "preset_scan_configuration optional INT32 -",
                "filter_string optional BINARY string", "ion_injection_time optional DOUBLE -",
                "ion_mobility_value optional DOUBLE -", "ion_mobility_type optional BINARY string",
                "instrument_configuration_id optional INT32 -", "spectrum_reference optional BINARY string",
                "parameters optional group list", "scan_windows optional group list");

        // One spectrum row per spectrum in index order; one scan row per scan, scan_index counting across spectra.
        List<Group> spectrumRows = rows(out.resolve("spectra_metadata.parquet"));
        List<Group> scanRows = rows(out.resolve("spectra_metadata_scans.parquet"));
        List<Long> expectedIndices = new ArrayList<>();
        List<Long> expectedScanSources = new ArrayList<>();
        for (Spectrum s : source.spectra) {
            expectedIndices.add(s.index());
            for (ScanEvent ignored : s.description().scans()) {
                expectedScanSources.add(s.index());
            }
        }
        assertThat(spectrumRows).extracting(r -> r.getLong("index", 0)).containsExactlyElementsOf(expectedIndices);
        assertThat(scanRows).extracting(r -> r.getLong("source_index", 0))
                .containsExactlyElementsOf(expectedScanSources);
        List<Long> scanIndices = new ArrayList<>();
        for (long i = 0; i < expectedScanSources.size(); i++) {
            scanIndices.add(i);
        }
        assertThat(scanRows).extracting(r -> r.getLong("scan_index", 0)).containsExactlyElementsOf(scanIndices);
    }

    // ---- spectra without scans ----------------------------------------------------------------------------------

    /**
     * A spectrum without scans gets one scan row holding its retention time, as in the packed layout, since the HEAD
     * Python reader cannot open a spectrum without one: three scanless PROFILE spectra, and a mixed set whose spectra
     * 0 and 2 have scans and 1 and 3 none, the last with a NaN retention time, all written with the default options.
     * Each scanless spectrum has exactly one row, with its source_index and its retention time (null unless finite),
     * empty lists and no other cell; scan_index counts on contiguously; and the reader gives each scanless spectrum
     * one scan whose start time is its retention time.
     */
    @Test
    void aSpectrumWithoutScansGetsOneScanRowHoldingItsRetentionTime(@TempDir Path tmp) {
        List<Spectrum> scanless = List.of(profile(0, 0.5, List.of()), profile(1, 1.25, List.of()),
                profile(2, 2.0, List.of()));
        Path out = tmp.resolve("scanless.mzpeak");
        MzPeakWriter.writeDirectory(out, scanless);

        assertThat(scanRows(out)).containsExactly("0|0|0.5", "1|1|1.25", "2|2|2.0");
        List<Group> rows = rows(out.resolve("spectra_metadata_scans.parquet"));
        assertThat(rows).allSatisfy(r -> {
            assertThat(ParquetGroups.groupList(r, "parameters")).isEmpty();
            assertThat(ParquetGroups.groupList(r, "scan_windows")).isEmpty();
            for (String cell : List.of("preset_scan_configuration", "filter_string", "ion_injection_time",
                    "instrument_configuration_id", "spectrum_reference")) {
                assertThat(ParquetGroups.has(r, cell)).as("%s of a fallback scan row", cell).isFalse();
            }
        });
        assertOneScanAtTheRetentionTime(out, scanless, Set.of(0L, 1L, 2L));

        ScanEvent first = new ScanEvent(3.0, 12.5, "f0", null, null, null, List.of(new ScanWindow(100, 200)),
                List.of());
        ScanEvent second = new ScanEvent(4.0, null, null, null, null, null, List.of(), List.of());
        ScanEvent third = new ScanEvent(4.01, null, null, null, null, null, List.of(), List.of());
        List<Spectrum> mixed = List.of(profile(0, 3.0, List.of(first)), profile(1, 3.5, List.of()),
                profile(2, 4.0, List.of(second, third)), profile(3, Double.NaN, List.of()));
        Path mixedOut = tmp.resolve("mixed.mzpeak");
        MzPeakWriter.writeDirectory(mixedOut, mixed);

        assertThat(scanRows(mixedOut)).containsExactly("0|0|3.0", "1|1|3.5", "2|2|4.0", "2|3|4.01", "3|4|null");
        assertOneScanAtTheRetentionTime(mixedOut, mixed, Set.of(1L, 3L));
        try (MzPeakReader reader = MzPeakReader.open(mixedOut)) {
            assertThat(reader.getSpectrumAt(0).description().scans()).containsExactly(first);
            assertThat(reader.getSpectrumAt(2).description().scans()).containsExactly(second, third);
        }
    }

    /**
     * A scan's spectrum_reference and instrument configuration survive a round trip in both metadata layouts: of two
     * constructed spectra, the scan of spectrum 1 has both, and the raw cells of the scan row with its source_index
     * hold them (spectra_metadata_scans in the split layout, the scan struct of spectra_metadata in the packed one),
     * while the scan of spectrum 0 has neither.
     */
    @ParameterizedTest
    @EnumSource(MetadataLayout.class)
    void aScansSpectrumReferenceAndInstrumentConfigurationRoundTrip(MetadataLayout layout, @TempDir Path tmp) {
        ScanEvent plain = new ScanEvent(0.5, null, null, null, null, null, List.of(), List.of());
        ScanEvent referenced = new ScanEvent(1.0, 20.0, null, null, 3, "index=0", List.of(), List.of());
        List<Spectrum> spectra = List.of(profile(0, 0.5, List.of(plain)), profile(1, 1.0, List.of(referenced)));
        Path out = tmp.resolve(layout + ".mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, List.of(), List.of(), List.of(), FileMetadata.EMPTY,
                Options.defaults().withMetadataLayout(layout));

        Source back = ForwardBackwardCorpusTest.readSource(out);
        DescriptionAssert.assertEqual("scan references via " + layout,
                spectra.stream().map(Spectrum::description).toList(), descriptions(back));
        assertThat(back.spectra.get(1).description().scans()).containsExactly(referenced);

        Map<Long, Group> scans = new HashMap<>();
        if (layout == MetadataLayout.SPLIT) {
            for (Group row : rows(out.resolve("spectra_metadata_scans.parquet"))) {
                scans.put(ParquetGroups.optLong(row, "source_index"), row);
            }
        } else {
            for (Group row : rows(out.resolve("spectra_metadata.parquet"))) {
                Group scan = ParquetGroups.optGroup(row, "scan");
                scans.put(ParquetGroups.optLong(scan, "source_index"), scan);
            }
        }
        String configuration = layout == MetadataLayout.SPLIT ? "instrument_configuration_id"
                : "instrument_configuration_ref";
        assertThat(ParquetGroups.optString(scans.get(1L), "spectrum_reference")).isEqualTo("index=0");
        assertThat(ParquetGroups.optInt(scans.get(1L), configuration)).isEqualTo(3);
        assertThat(ParquetGroups.has(scans.get(0L), "spectrum_reference")).isFalse();
        assertThat(ParquetGroups.has(scans.get(0L), configuration)).isFalse();
    }

    /** Each scan row of {@code out} as {@code source_index|scan_index|scan_start_time}, in row order. */
    private static List<String> scanRows(Path out) {
        List<String> lines = new ArrayList<>();
        for (Group r : rows(out.resolve("spectra_metadata_scans.parquet"))) {
            lines.add(ParquetGroups.optLong(r, "source_index") + "|" + ParquetGroups.optLong(r, "scan_index") + "|"
                    + ParquetGroups.optDouble(r, "scan_start_time"));
        }
        return lines;
    }

    /** The {@code scanless} spectra of {@code written} read back with one scan whose start time is their RT. */
    private static void assertOneScanAtTheRetentionTime(Path out, List<Spectrum> written, Set<Long> scanless) {
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            for (Spectrum s : written) {
                if (!scanless.contains(s.index())) {
                    continue;
                }
                SpectrumDescription back = reader.getSpectrumAt((int) s.index()).description();
                double rt = s.description().retentionTime();
                assertThat(Double.compare(back.retentionTime(), rt)).as("retention time %s of spectrum %d, expected %s",
                        back.retentionTime(), s.index(), rt).isZero();
                assertThat(back.scans()).as("scans of spectrum %d", s.index()).containsExactly(
                        new ScanEvent(rt, null, null, null, null, null, List.of(), List.of()));
            }
        }
    }

    /** A PROFILE MS1 spectrum of three points with {@code index}, retention time {@code rt} and {@code scans}. */
    private static Spectrum profile(long index, double rt, List<ScanEvent> scans) {
        SpectrumDescription d = new SpectrumDescription(index, "scan=" + (index + 1), 1, rt, Polarity.POSITIVE,
                SignalContinuity.PROFILE, 3, 0, scans, List.of(), List.of());
        return new Spectrum(d, new double[] {100.0, 100.5, 101.0}, new double[] {10.0, 20.0, 5.0}, List.of());
    }

    @Test
    void emptyParameterAndScanWindowListsAreWrittenAsEmptyListsNotNull(@TempDir Path tmp) {
        // Every Example_Processed.img scan has no scan window. Every legacy small.unpacked spectrum's Params are its
        // spectrum type and summary values (V-17), which all move to their columns, so every parameters list is empty.
        Source imaging = source(Fixtures.current("Example_Processed.img.mzpeak"));
        Path img = write(imaging, Form.DIRECTORY, tmp.resolve("img.mzpeak"));
        List<Group> scanRows = rows(img.resolve("spectra_metadata_scans.parquet"));
        assertThat(scanRows).hasSize(9).allSatisfy(r -> {
            assertThat(ParquetGroups.has(r, "scan_windows")).as("scan_windows is not null").isTrue();
            assertThat(ParquetGroups.groupList(r, "scan_windows")).isEmpty();
        });

        Source small = source(Fixtures.legacy("small.unpacked.mzpeak"));
        Path out = write(small, Form.DIRECTORY, tmp.resolve("small.mzpeak"));
        List<Group> spectrumRows = rows(out.resolve("spectra_metadata.parquet"));
        for (int i = 0; i < small.spectra.size(); i++) {
            Group row = spectrumRows.get(i);
            assertThat(small.spectra.get(i).description().parameters()).as("params of spectrum %d", i).hasSize(6);
            assertThat(ParquetGroups.has(row, "parameters")).as("parameters of row %d is not null", i).isTrue();
            assertThat(ParquetGroups.groupList(row, "parameters")).as("parameters of row %d", i).isEmpty();
        }
    }

    @Test
    void scanSettingsAreWrittenWithUnitAndReadBackEqual(@TempDir Path tmp) throws IOException {
        Source source = source(Fixtures.current("Example_Processed.img.mzpeak"));
        assertThat(source.scanSettings).as("scan settings of Example_Processed.img").hasSize(1);
        Path out = write(source, Form.DIRECTORY, tmp.resolve("out.mzpeak"));

        JsonNode settings;
        try (MzPeakSource dataset = MzPeakSource.open(out)) {
            settings = MAPPER.readTree(dataset.readManifestBytes()).path("metadata").path("scan_settings_list");
        }
        assertThat(settings).hasSize(1);
        JsonNode entry = settings.get(0);
        List<String> keys = new ArrayList<>();
        entry.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly("id", "source_file_refs", "targets", "parameters");
        assertThat(entry.path("id").asText()).isEqualTo("scansettings1");
        JsonNode pixelSize = null;
        for (JsonNode p : entry.path("parameters")) {
            List<String> paramKeys = new ArrayList<>();
            p.fieldNames().forEachRemaining(paramKeys::add);
            assertThat(paramKeys).as("keys of %s", p).containsExactly("name", "accession", "value", "unit");
            if ("IMS:1000046".equals(p.path("accession").asText())) {
                pixelSize = p;
            }
        }
        assertThat(pixelSize).isNotNull();
        assertThat(pixelSize.path("unit").asText()).isEqualTo("UO:0000017");
        assertThat(pixelSize.path("value").doubleValue()).isEqualTo(100.0);
        assertThat(settings.findValues("unit_accession")).isEmpty();

        try (MzPeakReader reader = MzPeakReader.open(out)) {
            List<ScanSettings> back = reader.scanSettings();
            assertThat(back).isEqualTo(source.scanSettings);
            assertThat(back.get(0).pixelSizeX()).hasValue(100.0);
        }
    }

    /**
     * A scan setting without an id or values keeps every key, and gets the id {@code scan_settings_<position>} with
     * one warning: the reference's ScanSettings.id is a String (param.rs:458-460), so the Rust reader rejects a
     * manifest whose id is null. A nameless param's name stays null, as the reference writes its Option. The packed
     * layout leaves the key out and warns about nothing.
     */
    @Test
    void scanSettingsWithoutIdOrValuesKeepEveryKey(@TempDir Path tmp) throws IOException {
        Source source = source(Fixtures.legacy("small.unpacked.mzpeak"));
        List<Param> params = List.of(new Param(null, "IMS:1000401", null, null));
        List<ScanSettings> settings = List.of(new ScanSettings(null, params));
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, List.of(), settings,
                source.fileMetadata, SPLIT);

        assertThat(scanSettingsList(out).toString()).isEqualTo("[{\"id\":\"scan_settings_0\",\"source_file_refs\":[],"
                + "\"targets\":[],\"parameters\":[{\"name\":null,\"accession\":\"IMS:1000401\",\"value\":null,"
                + "\"unit\":null}]}]");
        assertThat(scanSettingsWarnings(source, settings, SPLIT)).containsExactly(
                "scan settings 0 has no id, which the current manifest requires; written as scan_settings_0");
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.scanSettings()).containsExactly(new ScanSettings("scan_settings_0", params));
        }
        assertThat(scanSettingsWarnings(source, settings, SPLIT.withMetadataLayout(MetadataLayout.PACKED))).isEmpty();
    }

    /**
     * Two scan settings without an id next to one whose id is {@code scan_settings_0}: the first gets
     * {@code scan_settings_0_1}, since {@code scan_settings_0} is taken, the third {@code scan_settings_2}, with one
     * warning naming both, and each keeps its params. The packed layout writes them as given.
     */
    @Test
    void scanSettingsIdsGivenToEntriesWithoutOneAreUnique(@TempDir Path tmp) throws IOException {
        Source source = source(Fixtures.legacy("small.unpacked.mzpeak"));
        Param topDown = new Param("top down", "IMS:1000401", null, null);
        Param pixelSize = new Param("pixel size (x)", "IMS:1000046", 100.0, "UO:0000017");
        List<ScanSettings> settings = List.of(new ScanSettings(null, List.of(topDown)),
                new ScanSettings("scan_settings_0", List.of(pixelSize)), new ScanSettings(null, List.of()));
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, List.of(), List.of(), settings, source.fileMetadata, SPLIT);

        assertThat(scanSettingsList(out).findValuesAsText("id")).containsExactly("scan_settings_0_1",
                "scan_settings_0", "scan_settings_2");
        assertThat(scanSettingsWarnings(source, settings, SPLIT)).containsExactly("scan settings 0 and 2 have no id,"
                + " which the current manifest requires; written as scan_settings_0_1 and scan_settings_2");
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.scanSettings()).containsExactly(new ScanSettings("scan_settings_0_1", List.of(topDown)),
                    settings.get(1), new ScanSettings("scan_settings_2", List.of()));
        }

        Options packed = SPLIT.withMetadataLayout(MetadataLayout.PACKED);
        Path packedOut = tmp.resolve("packed.mzpeak");
        MzPeakWriter.writeDirectory(packedOut, source.spectra, List.of(), List.of(), settings, source.fileMetadata,
                packed);
        assertThat(scanSettingsWarnings(source, settings, packed)).isEmpty();
        try (MzPeakReader reader = MzPeakReader.open(packedOut)) {
            assertThat(reader.scanSettings()).isEqualTo(settings);
        }
    }

    /** The manifest's {@code metadata.scan_settings_list}. */
    private static JsonNode scanSettingsList(Path out) throws IOException {
        try (MzPeakSource dataset = MzPeakSource.open(out)) {
            return MAPPER.readTree(dataset.readManifestBytes()).path("metadata").path("scan_settings_list");
        }
    }

    /** The warnings about scan settings of a write of {@code source}'s spectra with {@code settings}. */
    private static List<String> scanSettingsWarnings(Source source, List<ScanSettings> settings, Options options) {
        return WritePlanner.plan(source.spectra, List.of(), List.of(), settings, source.fileMetadata, options)
                .report().warnings().stream().filter(w -> w.startsWith("scan settings")).toList();
    }

    /** WR-7 made the split layout the default; naming it explicitly changes nothing. */
    @Test
    void theDefaultOptionsWriteTheSplitLayout() {
        assertThat(Options.defaults().metadataLayout()).isEqualTo(MetadataLayout.SPLIT);
        assertThat(SPLIT).isEqualTo(Options.defaults()).hasSameHashCodeAs(Options.defaults());
        Options packed = SPLIT.withMetadataLayout(MetadataLayout.PACKED);
        assertThat(packed).isNotEqualTo(Options.defaults());
        assertThat(packed.withMetadataLayout(MetadataLayout.SPLIT)).isEqualTo(Options.defaults());
        assertThat(SPLIT.withSignalEncoding(SignalEncoding.NUMPRESS).metadataLayout()).isEqualTo(MetadataLayout.SPLIT);
        assertThat(SPLIT.withRowGroupSize(4096).metadataLayout()).isEqualTo(MetadataLayout.SPLIT);
        assertThat(Options.defaults()).hasToString("Options[signalEncoding=POINT, numpressIntensity=SLOF,"
                + " rowGroupSize=OptionalLong.empty, metadataLayout=SPLIT, cvList=[]]");
        assertThat(packed).hasToString("Options[signalEncoding=POINT, numpressIntensity=SLOF,"
                + " rowGroupSize=OptionalLong.empty, metadataLayout=PACKED, cvList=[]]");
    }

    @Test
    void descriptionAssertComparesParamsAsMultisetsAndIgnoresOnlyDerivedAccessionsTheSourceLacks() {
        Param resolution = new Param("mass resolving power", "MS:1000800", 100000L, null);
        Param note = new Param("note", null, "x", null);
        Param tic = new Param("total ion current", "MS:1000285", 5.0, "MS:1000131");
        ScanEvent scan = new ScanEvent(Double.NaN, 1.5, "f", null, 0, null, List.of(new ScanWindow(100, 200)),
                List.of(resolution));
        SpectrumDescription source = description(Double.NaN, scan, List.of(resolution, note));

        // Order, a name next to an accession, NaN against NaN and a derived TIC the source lacks do not count.
        SpectrumDescription same = description(Double.NaN, scan,
                List.of(note, new Param(null, "MS:1000800", 100000L, null), tic));
        assertThat(DescriptionAssert.differences("s", source, same)).isEmpty();

        // A derived accession the source has is compared, and so is every typed field and nested list.
        SpectrumDescription withTic = description(Double.NaN, scan,
                List.of(resolution, note, new Param("total ion current", "MS:1000285", 4.0, "MS:1000131")));
        assertThat(DescriptionAssert.differences("s", withTic, same)).singleElement().asString()
                .startsWith("s.parameters: missing [ParamKey[id=MS:1000285, value=4.0");
        ScanEvent otherWindow = new ScanEvent(Double.NaN, 1.5, "f", null, 0, null,
                List.of(new ScanWindow(100, 201)), List.of(resolution, resolution));
        assertThat(DescriptionAssert.differences("s", source, description(0.5, otherWindow, List.of(note))))
                .containsExactly(
                        "s.retentionTime: expected NaN, got 0.5",
                        "s.parameters: missing [ParamKey[id=MS:1000800, value=100000, unit=null]], unexpected []",
                        "s.scans[0].scanWindows[0].upperLimit: expected 200.0, got 201.0",
                        "s.scans[0].parameters: missing [], unexpected [ParamKey[id=MS:1000800, value=100000,"
                                + " unit=null]]");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static SpectrumDescription description(double time, ScanEvent scan, List<Param> params) {
        return new SpectrumDescription(3, "scan=4", 1, time, Polarity.POSITIVE, SignalContinuity.PROFILE, 10, 0,
                List.of(scan), List.of(), params);
    }

    private static Source source(Path fixture) {
        return SOURCES.computeIfAbsent(fixture, ForwardBackwardCorpusTest::readSource);
    }

    private static Path write(Source s, Form form, Path out) {
        switch (form) {
            case DIRECTORY -> MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra,
                    s.scanSettings, s.fileMetadata, SPLIT);
            case ARCHIVE -> MzPeakWriter.writeArchive(out, s.spectra, s.chromatograms, s.wavelengthSpectra,
                    s.scanSettings, s.fileMetadata, SPLIT);
            case DIRECTORY_NUMPRESS -> MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms,
                    s.wavelengthSpectra, s.scanSettings, s.fileMetadata,
                    SPLIT.withSignalEncoding(SignalEncoding.NUMPRESS));
        }
        return out;
    }

    private static List<SpectrumDescription> descriptions(Source s) {
        List<SpectrumDescription> out = new ArrayList<>(s.spectra.size());
        for (Spectrum spectrum : s.spectra) {
            out.add(spectrum.description());
        }
        return out;
    }

    private static MessageType schema(Path out, String member) {
        try (MzPeakSource dataset = MzPeakSource.open(out)) {
            return ParquetFooter.read(dataset.inputFile(member)).schema();
        }
    }

    /** The shape of the spectrum metadata member of a written dataset, found by role. */
    private static MetadataShape spectrumMetadataShape(Path out) {
        try (MzPeakSource dataset = MzPeakSource.open(out)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(dataset);
            String member = manifest.find(EntityType.SPECTRUM, DataKind.METADATA).orElseThrow().name();
            return MetadataShape.detect(ParquetFooter.read(dataset.inputFile(member)), "spectrum", "index", member);
        }
    }

    /** Each top-level field as {@code name repetition type annotation}, a group as {@code name repetition group}. */
    private static List<String> columns(MessageType schema) {
        List<String> out = new ArrayList<>();
        for (Type t : schema.getFields()) {
            String repetition = t.getRepetition().name().toLowerCase(java.util.Locale.ROOT);
            if (t.isPrimitive()) {
                PrimitiveType p = t.asPrimitiveType();
                LogicalTypeAnnotation a = p.getLogicalTypeAnnotation();
                String annotation = a == null ? "-"
                        : a instanceof LogicalTypeAnnotation.IntLogicalTypeAnnotation i
                        ? (i.isSigned() ? "int" : "uint") + i.getBitWidth()
                        : a.toString().toLowerCase(java.util.Locale.ROOT);
                PrimitiveTypeName name = p.getPrimitiveTypeName();
                out.add(t.getName() + " " + repetition + " " + name + " " + annotation);
            } else {
                LogicalTypeAnnotation a = t.getLogicalTypeAnnotation();
                out.add(t.getName() + " " + repetition + " group"
                        + (a == null ? "" : " " + a.toString().toLowerCase(java.util.Locale.ROOT)));
            }
        }
        return out;
    }

    private static List<Group> rows(Path file) {
        List<Group> out = new ArrayList<>();
        ParquetGroups.forEach(file, out::add);
        return out;
    }
}
