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
import org.junit.jupiter.params.provider.EnumSource;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wavelength spectrum scans (R8, D13-24; wavelength-spectra.md §Wavelength spectrum scan metadata): read from
 * {@code wavelength_spectra_metadata_scans} into {@link WavelengthSpectrum#scans()}, with scan windows in nanometers
 * (UO:0000018). The crafted cases rewrite has_uv's scans member with parquet-java ({@link MemberRewrite}): spectrum 0
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

    // ---- helpers ------------------------------------------------------------------------------------------------

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

    /** Sets the unit of the scans member's window mappings to nanometers. */
    private static void windowsInNanometers(ObjectNode index) {
        for (JsonNode mapping : scansEntry(index).path("column_mapping")) {
            if (mapping.path("path").asText().startsWith("scan_windows.")) {
                ((ObjectNode) mapping).put("unit", NANOMETER);
            }
        }
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
