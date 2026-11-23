package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.model.Param;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.InstrumentConfiguration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Wavelength spectrum scans (R8, D13-24; wavelength-spectra.md §Wavelength spectrum scan metadata): read from
 * {@code wavelength_spectra_metadata_scans} into {@link WavelengthSpectrum#scans()}, with scan windows in nanometers
 * (UO:0000018), and written as given by the split layout, the windows FLOAT mapped in nanometers. The crafted cases
 * rewrite has_uv's scans member with parquet-java ({@link MemberRewrite}): spectrum 0
 * gets a second scan (instrument configuration 1, scan window 200-400 nm), the windows stored as FLOAT (as has_uv
 * stores them, float32 as the spec gives) or DOUBLE.
 */
class WavelengthScansTest {

    private static final String SCANS = "wavelength_spectra_metadata_scans.parquet";
    private static final String METADATA = "wavelength_spectra_metadata.parquet";
    private static final String NANOMETER = "UO:0000018";
    /** The scan start time of has_uv's wavelength spectrum 0, in minutes (UO:0000031), as stored. */
    private static final double START0 = 0.001833333333;
    private static final ScanEvent SECOND = new ScanEvent(START0, null, null, null, 1, null,
            List.of(new ScanWindow(200, 400)), List.of());

    @TempDir
    Path tmp;

    @Test
    void hasUvWavelengthSpectrumZeroHasOneScan() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("has_uv.mzpeak"))) {
            WavelengthSpectrum w0 = reader.getWavelengthSpectrum(0).orElseThrow();
            assertThat(w0.scans()).containsExactly(new ScanEvent(START0, 0.0, null, null, 0, null, List.of(),
                    List.of()));
            assertThat(w0.time()).isEqualTo(START0);
            assertThat(reader.wavelengthSpectra()).hasSize(520).allSatisfy(w -> {
                assertThat(w.scans()).as("wavelength spectrum %d", w.index()).hasSize(1);
                assertThat(w.scans().get(0).startTime()).as("wavelength spectrum %d", w.index())
                        .isEqualTo(w.time());
                assertThat(w.scans().get(0).instrumentConfigurationRef()).isZero();
            });
        }
    }

    /** has_uv given the second scan with FLOAT windows, its manifest mapping them in nanometers. */
    @Test
    void aCraftedSecondScanReadsTwoScansWithANanometerWindow() {
        MzPeakSource source = withSecondScan(WavelengthScansTest::windowsInNanometers);
        assertThat(windowUnits(source)).containsExactly(NANOMETER, NANOMETER);
        try (MzPeakReader reader = MzPeakReader.open(source)) {
            WavelengthSpectrum w0 = reader.getWavelengthSpectrum(0).orElseThrow();
            assertThat(w0.scans()).containsExactly(new ScanEvent(START0, 0.0, null, null, 0, null, List.of(),
                    List.of()), SECOND);
            assertThat(reader.getWavelengthSpectrum(1).orElseThrow().scans()).hasSize(1);
            assertThat(reader.wavelengthSpectra()).hasSize(520);
        }
    }

    /** Without column_mapping rows for the windows, they resolve through the table, which maps them in nanometers. */
    @Test
    void windowsWithoutAMappingTakeTheNanometerUnitOfTheTable() {
        MzPeakSource source = withSecondScan(withoutWindowMappings());
        assertThat(windowUnits(source)).containsExactly(NANOMETER, NANOMETER);
        try (MzPeakReader reader = MzPeakReader.open(source)) {
            assertThat(reader.getWavelengthSpectrum(0).orElseThrow().scans()).hasSize(2).last().isEqualTo(SECOND);
        }
    }

    /**
     * Windows mapped in micrometers, millimeters or meters read in nanometers (D13-24): the crafted 200-400 window
     * is taken in the mapped unit and multiplied by {@code factor}.
     */
    @ParameterizedTest
    @CsvSource({"UO:0000017, 1e3", "UO:0000016, 1e6", "UO:0000008, 1e9"})
    void windowsInALengthUnitReadInNanometers(String unit, double factor) {
        MzPeakSource source = withSecondScan(windowsIn(unit));
        assertThat(windowUnits(source)).containsExactly(unit, unit);
        try (MzPeakReader reader = MzPeakReader.open(source)) {
            assertThat(reader.getWavelengthSpectrum(0).orElseThrow().scans()).hasSize(2).last()
                    .extracting(ScanEvent::scanWindows).isEqualTo(List.of(new ScanWindow(200 * factor,
                            400 * factor)));
            assertThat(reader.wavelengthSpectra()).hasSize(520);
        }
    }

    /** Window mappings without a unit read the stored values as nanometers. */
    @Test
    void windowsMappedWithoutAUnitReadAsStored() {
        MzPeakSource source = withSecondScan(index -> {
            for (JsonNode mapping : scansEntry(index).path("column_mapping")) {
                if (mapping.path("path").asText().startsWith("scan_windows.")) {
                    ((ObjectNode) mapping).remove("unit");
                }
            }
        });
        assertThat(windowUnits(source)).containsExactly(null, null);
        try (MzPeakReader reader = MzPeakReader.open(source)) {
            assertThat(reader.getWavelengthSpectrum(0).orElseThrow().scans()).hasSize(2).last().isEqualTo(SECOND);
        }
    }

    /**
     * A window unit that is no length unit is refused, naming the member and the column: has_uv's own m/z mapping
     * once a window holds values, and a unit given per row by a unit column.
     */
    @Test
    void aWindowUnitThatIsNoLengthUnitIsRefused() {
        MzPeakSource mz = withSecondScan(index -> { });
        assertThat(windowUnits(mz)).containsExactly("MS:1000040", "MS:1000040");
        assertThatThrownBy(() -> {
            try (MzPeakReader reader = MzPeakReader.open(mz)) {
                reader.getWavelengthSpectrum(0);
            }
        }).isInstanceOf(MzPeakException.class).hasMessage(SCANS + ": wavelength scan window column"
                + " scan_windows.scan_window_lower_limit has the unit MS:1000040; supported are nanometer"
                + " (UO:0000018), micrometer (UO:0000017), millimeter (UO:0000016) and meter (UO:0000008)");
        MzPeakSource perRow = withSecondScan(index -> {
            for (JsonNode mapping : scansEntry(index).path("column_mapping")) {
                if (mapping.path("path").asText().startsWith("scan_windows.")) {
                    ((ObjectNode) mapping).putArray("unit").add("scan_windows.window_unit");
                }
            }
        });
        assertThatThrownBy(() -> {
            try (MzPeakReader reader = MzPeakReader.open(perRow)) {
                reader.getWavelengthSpectrum(0);
            }
        }).isInstanceOf(MzPeakException.class).hasMessage(SCANS + ": wavelength scan window column"
                + " scan_windows.scan_window_lower_limit takes its unit from the column scan_windows.window_unit,"
                + " which is not supported");
    }

    /** A scans member of only spectrum 0's two scans, with the windows as FLOAT or DOUBLE. */
    @ParameterizedTest
    @EnumSource(value = PrimitiveTypeName.class, names = {"FLOAT", "DOUBLE"})
    void windowsReadAsFloatAndAsDouble(PrimitiveTypeName windowType) throws IOException {
        MessageType schema = Types.buildMessage()
                .required(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("source_index")
                .required(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("scan_index")
                .optional(PrimitiveTypeName.DOUBLE).named("scan_start_time")
                .optional(PrimitiveTypeName.INT32).as(LogicalTypeAnnotation.intType(32, false))
                .named("instrument_configuration_id")
                .optionalGroup().as(LogicalTypeAnnotation.listType())
                .repeatedGroup()
                .optionalGroup()
                .optional(windowType).named("scan_window_lower_limit")
                .optional(windowType).named("scan_window_upper_limit")
                .named("item")
                .named("list")
                .named("scan_windows")
                .named("arrow_schema");
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);
        MemberRewrite.Member m = new MemberRewrite.Member();
        m.schema = schema;
        Group first = factory.newGroup().append("source_index", 0L).append("scan_index", 0L)
                .append("scan_start_time", START0).append("instrument_configuration_id", 0);
        first.addGroup("scan_windows");
        Group second = factory.newGroup().append("source_index", 0L).append("scan_index", 1L)
                .append("scan_start_time", START0).append("instrument_configuration_id", 1);
        Group window = second.addGroup("scan_windows").addGroup("list").addGroup("item");
        if (windowType == PrimitiveTypeName.FLOAT) {
            window.append("scan_window_lower_limit", 200f).append("scan_window_upper_limit", 400f);
        } else {
            window.append("scan_window_lower_limit", 200.0).append("scan_window_upper_limit", 400.0);
        }
        m.rows.add(first);
        m.rows.add(second);
        Path file = tmp.resolve("scans-" + windowType + ".parquet");
        MemberRewrite.write(m, file);

        MzPeakSource source = MzPeakReaderEdgeCasesTest.edited(Fixtures.current("has_uv.mzpeak"),
                WavelengthScansTest::windowsInNanometers, Map.of(SCANS, Files.readAllBytes(file)));
        try (MzPeakReader reader = MzPeakReader.open(source)) {
            assertThat(reader.getWavelengthSpectrum(0).orElseThrow().scans()).containsExactly(
                    new ScanEvent(START0, null, null, null, 0, null, List.of(), List.of()), SECOND);
            assertThat(reader.getWavelengthSpectrum(1).orElseThrow().scans()).isEmpty();
        }
    }

    /**
     * The legacy packed has_uv keeps its wavelength scans in the {@code scan} struct of its packed metadata member;
     * the frozen PACKED dialect does not read them (D13-24), so its wavelength spectra have no scans.
     */
    @Test
    void legacyPackedWavelengthSpectraReadWithoutScans() {
        Path legacy = Fixtures.legacy("has_uv.mzpeak");
        try (MzPeakSource source = MzPeakSource.open(legacy)) {
            MessageType schema = ParquetFooter.read(source.inputFile(METADATA)).schema();
            assertThat(schema.containsField("scan")).as("packed scan struct").isTrue();
            assertThat(Facet.WAVELENGTH_SCAN.entry(MzPeakManifest.fromSource(source))).isEmpty();
        }
        try (MzPeakReader reader = MzPeakReader.open(legacy)) {
            assertThat(reader.wavelengthSpectra()).hasSize(520).allSatisfy(w -> assertThat(w.scans()).isEmpty());
        }
    }

    // ---- writing (R8 writer, X-34) ------------------------------------------------------------------------------

    /**
     * The crafted case read, written split and read again keeps both scans of wavelength spectrum 0 and the one scan
     * of every other, instrument configuration 1 declared in the file metadata.
     */
    @Test
    void aWriteKeepsBothScansOfTheCraftedCase() {
        List<WavelengthSpectrum> read;
        FileMetadata meta;
        try (MzPeakReader reader = MzPeakReader.open(withSecondScan(WavelengthScansTest::windowsInNanometers))) {
            read = reader.wavelengthSpectra();
            meta = withConfiguration(reader.fileMetadata(), 1);
        }
        assertThat(read.get(0).scans()).hasSize(2);
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(), List.of(), read, List.of(), meta);
        try (MzPeakReader back = MzPeakReader.open(out)) {
            assertThat(back.getWavelengthSpectrum(0).orElseThrow().scans()).containsExactly(
                    new ScanEvent(START0, 0.0, null, null, 0, null, List.of(), List.of()), SECOND);
            assertThat(back.wavelengthSpectra()).extracting(WavelengthSpectrum::scans)
                    .containsExactlyElementsOf(read.stream().map(WavelengthSpectrum::scans).toList());
        }
    }

    /**
     * The written scan windows are FLOAT (float32), as wavelength-spectra.md gives them, and the manifest maps both
     * in nanometers (UO:0000018).
     */
    @Test
    void theWrittenWindowsAreFloatMappedInNanometers() {
        Path out = tmp.resolve("float.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(), List.of(), List.of(wavelength(List.of(SECOND))), List.of(),
                withConfiguration(FileMetadata.EMPTY, 1));
        try (MzPeakSource source = MzPeakSource.open(out)) {
            MessageType schema = ParquetFooter.read(source.inputFile(SCANS)).schema();
            for (String window : List.of("scan_window_lower_limit", "scan_window_upper_limit")) {
                assertThat(schema.getType("scan_windows", "list", "item", window).asPrimitiveType()
                        .getPrimitiveTypeName()).as(window).isEqualTo(PrimitiveTypeName.FLOAT);
            }
            Entry entry = Facet.WAVELENGTH_SCAN.entry(MzPeakManifest.fromSource(source)).orElseThrow();
            assertThat(entry.columnMapping()).filteredOn(m -> m.path().startsWith("scan_windows."))
                    .extracting(MzPeakManifest.ColumnMapping::path, MzPeakManifest.ColumnMapping::unit)
                    .containsExactly(tuple("scan_windows.scan_window_lower_limit", NANOMETER),
                            tuple("scan_windows.scan_window_upper_limit", NANOMETER));
            assertThat(windowUnits(source)).containsExactly(NANOMETER, NANOMETER);
        }
        try (MzPeakReader back = MzPeakReader.open(out)) {
            assertThat(back.getWavelengthSpectrum(0).orElseThrow().scans()).containsExactly(SECOND);
        }
    }

    /**
     * A NaN window limit is written as null, and the reader drops a window without both limits, as it does for
     * spectrum scans; the scan's Params and other typed columns are written as given.
     */
    @Test
    void aNaNWindowLimitIsWrittenAsNull() {
        Param param = new Param("scan title", null, "uv", null);
        ScanEvent scan = new ScanEvent(2.0, 0.5, "filter", 3, null, null,
                List.of(new ScanWindow(Double.NaN, 400), new ScanWindow(250.25, 300.5)), List.of(param));
        Path out = tmp.resolve("nan.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(), List.of(), List.of(wavelength(List.of(scan))), List.of(),
                FileMetadata.EMPTY);
        try (MzPeakReader back = MzPeakReader.open(out)) {
            assertThat(back.getWavelengthSpectrum(0).orElseThrow().scans()).containsExactly(new ScanEvent(2.0, 0.5,
                    "filter", 3, null, null, List.of(new ScanWindow(250.25, 300.5)), List.of(param)));
        }
    }

    /** The split layout refuses a wavelength scan with a spectrum reference, which the scan table cannot hold. */
    @Test
    void aSpectrumReferenceOfAWavelengthScanIsRefused() {
        WavelengthSpectrum w = wavelength(List.of(new ScanEvent(1.5, null, null, null, null, "scan=7", List.of(),
                List.of())));
        Path out = tmp.resolve("ref.mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(out, List.of(), List.of(), List.of(w), List.of(),
                FileMetadata.EMPTY)).isInstanceOf(MzPeakException.class).hasMessage("wavelength spectrum 0 (w0):"
                + " scan 0 has the spectrum reference scan=7, which mzPeak cannot write: the wavelength scan table"
                + " has no spectrum_reference column (D13-12)");
        assertThat(out).doesNotExist();
    }

    /**
     * A session refuses a wavelength spectrum whose scan has a spectrum reference when it is added, with the message of
     * the batch write, and does not add it: the session goes on and finishes with the valid wavelength spectrum.
     */
    @Test
    void aSessionRefusesASpectrumReferenceOfAWavelengthScanWhenItIsAdded() {
        WavelengthSpectrum referenced = wavelength(List.of(new ScanEvent(1.5, null, null, null, null, "scan=7",
                List.of(), List.of())));
        WavelengthSpectrum valid = wavelength(List.of(new ScanEvent(1.5, null, null, null, null, null,
                List.of(new ScanWindow(200, 400)), List.of())));
        Path out = tmp.resolve("session.mzpeak");
        try (MzPeakWriter.Session session = MzPeakWriter.openDirectory(out, FileMetadata.EMPTY, List.of(), Set.of(),
                MzPeakWriter.Options.defaults())) {
            assertThatThrownBy(() -> session.addWavelengthSpectrum(referenced)).isInstanceOf(MzPeakException.class)
                    .hasMessage("wavelength spectrum 0 (w0): scan 0 has the spectrum reference scan=7, which mzPeak"
                            + " cannot write: the wavelength scan table has no spectrum_reference column (D13-12)");
            session.addWavelengthSpectrum(valid);
            session.finish();
        }
        try (MzPeakReader back = MzPeakReader.open(out)) {
            assertThat(back.wavelengthSpectra()).hasSize(1);
            WavelengthSpectrum w = back.getWavelengthSpectrum(0).orElseThrow();
            assertThat(w.scans()).containsExactly(valid.scans().get(0));
            assertThat(w.wavelength()).containsExactly(200.0, 400.0);
            assertThat(w.intensity()).containsExactly(1.0, 2.0);
        }
    }

    /**
     * The scans of a wavelength spectrum are checked as those of a spectrum (W12): an undeclared instrument
     * configuration, a param with an undeclared CV prefix and a param value of a type mzPeak cannot write are
     * refused, naming the scan.
     */
    @Test
    void theScansOfAWavelengthSpectrumAreChecked() {
        Path out = tmp.resolve("checked.mzpeak");
        WavelengthSpectrum configuration = wavelength(List.of(SECOND));
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(out, List.of(), List.of(), List.of(configuration),
                List.of(), FileMetadata.EMPTY)).isInstanceOf(MzPeakException.class).hasMessage(
                "wavelength spectrum 0: scan 0 instrument_configuration_id 1 names no instrument configuration"
                        + " (declared: none)");
        Param odd = new Param("odd", "XYZ:0000001", null, null);
        WavelengthSpectrum prefix = wavelength(List.of(new ScanEvent(1.5, null, null, null, null, null, List.of(),
                List.of(odd))));
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(out, List.of(), List.of(), List.of(prefix), List.of(),
                FileMetadata.EMPTY)).isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("wavelength spectrum 0: scan 0: " + odd + " uses XYZ:0000001");
        WavelengthSpectrum value = wavelength(List.of(new ScanEvent(1.5, null, null, null, null, null, List.of(),
                List.of(new Param("odd", null, new Object(), null)))));
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(out, List.of(), List.of(), List.of(value), List.of(),
                FileMetadata.EMPTY)).isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("wavelength spectrum 0 (w0): the value of the parameter \"odd\" is a");
        assertThat(out).doesNotExist();
    }

    /**
     * The packed layout has no wavelength scans member: it writes none of the scans, a spectrum reference included,
     * and warns once with the counts; its wavelength spectra read back without scans.
     */
    @Test
    void thePackedLayoutWarnsOfTheScansItDoesNotWrite() {
        ScanEvent referenced = new ScanEvent(1.5, null, null, null, null, "scan=7", List.of(), List.of());
        List<WavelengthSpectrum> spectra = List.of(wavelength(List.of(referenced, SECOND)),
                new WavelengthSpectrum(1, "w1", 2.5, null, new double[] {200.0}, new double[] {1.0}));
        Path out = tmp.resolve("packed.mzpeak");
        List<String> warnings = ArrayIndexTest.captureLog(MzPeakWriter.class, () -> MzPeakWriter.writeDirectory(out,
                        List.of(), List.of(), spectra, List.of(), withConfiguration(FileMetadata.EMPTY, 1),
                        MzPeakWriter.Options.defaults().withMetadataLayout(MzPeakWriter.MetadataLayout.PACKED)))
                .stream().filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage).toList();
        assertThat(warnings).containsExactly(
                "1 wavelength spectrum carries scans (2 in all), which the packed metadata layout does not write");
        try (MzPeakReader back = MzPeakReader.open(out)) {
            assertThat(back.wavelengthSpectra()).hasSize(2).allSatisfy(w -> assertThat(w.scans()).isEmpty());
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Wavelength spectrum 0, id w0, at 1.5 min, with two points and {@code scans}. */
    private static WavelengthSpectrum wavelength(List<ScanEvent> scans) {
        return new WavelengthSpectrum(0, "w0", 1.5, null, new double[] {200.0, 400.0}, new double[] {1.0, 2.0},
                List.of(), scans, List.of());
    }

    /**
     * has_uv with its scans member rewritten: a second scan of wavelength spectrum 0 (instrument configuration 1,
     * one window 200-400 nm, stored as FLOAT like has_uv's window columns) right after its first; the manifest edited
     * by {@code manifest}.
     */
    private MzPeakSource withSecondScan(Consumer<ObjectNode> manifest) {
        try {
            Path file = tmp.resolve("scans-" + System.nanoTime() + ".parquet");
            Files.write(file, TestSources.memory(Fixtures.current("has_uv.mzpeak")).member(SCANS));
            MemberRewrite.Member m = MemberRewrite.read(file);
            assertThat(m.rows.get(0).getLong("source_index", 0)).isZero();
            Group second = new SimpleGroupFactory(m.schema).newGroup().append("source_index", 0L)
                    .append("scan_index", (long) m.rows.size()).append("scan_start_time", START0)
                    .append("instrument_configuration_id", 1);
            second.addGroup("parameters");
            second.addGroup("scan_windows").addGroup("list").addGroup("item")
                    .append("scan_window_lower_limit", 200f).append("scan_window_upper_limit", 400f);
            m.rows.add(1, second);
            MemberRewrite.write(m, file);
            return MzPeakReaderEdgeCasesTest.edited(Fixtures.current("has_uv.mzpeak"), manifest,
                    Map.of(SCANS, Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code meta} with an instrument configuration of {@code id} added, without components. */
    static FileMetadata withConfiguration(FileMetadata meta, int id) {
        List<InstrumentConfiguration> configurations = new ArrayList<>(meta.instrumentConfigurations());
        configurations.add(new InstrumentConfiguration(id, List.of(), null, List.of()));
        return new FileMetadata(meta.fileDescription(), configurations, meta.dataProcessingMethods(), meta.samples(),
                meta.software(), meta.run());
    }

    /** Sets the unit of the scans member's window mappings to nanometers. */
    private static void windowsInNanometers(ObjectNode index) {
        windowsIn(NANOMETER).accept(index);
    }

    /** Sets the unit of the scans member's window mappings to {@code unit}. */
    private static Consumer<ObjectNode> windowsIn(String unit) {
        return index -> {
            for (JsonNode mapping : scansEntry(index).path("column_mapping")) {
                if (mapping.path("path").asText().startsWith("scan_windows.")) {
                    ((ObjectNode) mapping).put("unit", unit);
                }
            }
        };
    }

    /** Drops the scans member's window mappings. */
    private static Consumer<ObjectNode> withoutWindowMappings() {
        return index -> {
            ArrayNode mappings = (ArrayNode) scansEntry(index).path("column_mapping");
            int before = mappings.size();
            for (int i = mappings.size() - 1; i >= 0; i--) {
                if (mappings.get(i).path("path").asText().startsWith("scan_windows.")) {
                    mappings.remove(i);
                }
            }
            assertThat(before - mappings.size()).as("window mappings removed").isEqualTo(2);
        };
    }

    private static ObjectNode scansEntry(ObjectNode index) {
        for (JsonNode file : index.path("files")) {
            if (SCANS.equals(file.path("name").asText())) {
                return (ObjectNode) file;
            }
        }
        throw new AssertionError("no manifest entry " + SCANS);
    }

    /** The units of the lower and upper window columns of {@code source}'s scans member, as the reader maps them. */
    private static List<String> windowUnits(MzPeakSource source) {
        Entry entry = Facet.WAVELENGTH_SCAN.entry(MzPeakManifest.fromSource(source)).orElseThrow();
        MessageType schema = ParquetFooter.read(source.inputFile(entry.name())).schema();
        ColumnResolver windows = ColumnResolver.split(schema, entry, Facet.WAVELENGTH_SCAN).child("scan_windows");
        return List.of("MS:1000501", "MS:1000500").stream()
                .map(accession -> windows.mapping(windows.resolve(accession)).orElseThrow().unit())
                .toList();
    }
}
