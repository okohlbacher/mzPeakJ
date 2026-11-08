package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Activation;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WR-5b: the precursors and selected ions of the split metadata layout written by {@link SplitMetadataWriter} (design
 * §11.8). spectra_metadata_precursors has one row per precursor, in the spectrum's order, keyed by
 * {@code (source_index, precursor_index)}; spectra_metadata_selected_ions has one row per selected ion with the
 * {@code precursor_index} of its precursor and a global {@code selected_ion_index}. Selected-ion Params, ion mobility
 * included, stay in the {@code parameters} lists; the activation's dissociation method and collision energy move to
 * their columns ({@link SplitWriterPromotionTest}, WR-5c). The full round trips of the WR-5a fixtures are in
 * {@link SplitWriterSpectraTest}.
 *
 * <p>Oracles: current small.unpacked (34 MS2 spectra with one precursor and one selected ion each), diaPASEF.ref
 * (selected ions with ion mobility), Example_Processed.img (no precursors), their manifests, and spectra constructed
 * from current small.unpacked with two precursors each.
 */
class SplitWriterPrecursorsTest {

    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PRECURSORS = "spectra_metadata_precursors.parquet";
    private static final String SELECTED_IONS = "spectra_metadata_selected_ions.parquet";

    /** Fixtures read once per class; the tests never modify a source in place. */
    private static final Map<Path, Source> SOURCES = new ConcurrentHashMap<>();

    @Test
    void everyPrecursorAndSelectedIonIsOneRowKeyedOnSourceAndPrecursorIndex(@TempDir Path tmp) {
        Source source = source(Fixtures.current("small.unpacked.mzpeak"));
        Path out = write(source, tmp.resolve("out.mzpeak"));

        assertThat(columns(schema(out, PRECURSORS))).containsExactly(
                "source_index required INT64 uint64", "precursor_index optional INT64 uint64",
                "precursor_id optional BINARY string",
                "isolation_window optional group", "isolation_window.isolation_window_target optional DOUBLE -",
                "isolation_window.isolation_window_lower_offset optional DOUBLE -",
                "isolation_window.isolation_window_upper_offset optional DOUBLE -",
                "isolation_window.parameters optional group list",
                "activation optional group", "activation.dissociation_method optional BINARY string",
                "activation.collision_energy optional DOUBLE -", "activation.parameters optional group list");
        assertThat(columns(schema(out, SELECTED_IONS))).containsExactly(
                "source_index required INT64 uint64", "precursor_index optional INT64 uint64",
                "selected_ion_index required INT64 uint64", "selected_ion_mz optional DOUBLE -",
                "charge_state optional INT32 -", "peak_intensity optional DOUBLE -", "parameters optional group list");

        List<Group> precursorRows = rows(out.resolve(PRECURSORS));
        List<Group> ionRows = rows(out.resolve(SELECTED_IONS));
        assertThat(precursorRows).hasSize(34);
        assertThat(ionRows).hasSize(34);
        int p = 0;
        int i = 0;
        for (Spectrum s : source.spectra) {
            for (Precursor precursor : s.description().precursors()) {
                String where = "precursor row " + p + " (spectrum " + s.index() + ")";
                Group row = precursorRows.get(p++);
                assertThat(row.getLong("source_index", 0)).as(where).isEqualTo(s.index());
                assertThat(ParquetGroups.optLong(row, "precursor_index")).as(where)
                        .isEqualTo(precursor.precursorIndex());
                assertThat(ParquetGroups.optString(row, "precursor_id")).as(where).isEqualTo(precursor.precursorId());
                Group iso = ParquetGroups.optGroup(row, "isolation_window");
                assertThat(ParquetGroups.optDouble(iso, "isolation_window_target")).as(where)
                        .isEqualTo(precursor.isolationWindow().targetMz());
                assertThat(ParquetGroups.optDouble(iso, "isolation_window_lower_offset")).as(where)
                        .isEqualTo(precursor.isolationWindow().lowerOffset());
                assertThat(ParquetGroups.optDouble(iso, "isolation_window_upper_offset")).as(where)
                        .isEqualTo(precursor.isolationWindow().upperOffset());
                assertThat(ParquetGroups.has(iso, "parameters")).as(where + ": isolation_window.parameters").isTrue();
                // Every window of current small stores the user param 'ms level' (AP-7).
                assertThat(ParquetGroups.readParams(iso, "parameters")).as(where)
                        .isEqualTo(precursor.isolationWindow().parameters()).hasSize(1);
                // The activation's dissociation method and collision energy are columns since WR-5c.
                Group activation = ParquetGroups.optGroup(row, "activation");
                assertThat(precursor.activation().parameters()).as(where).hasSize(2);
                assertThat(ParquetGroups.optString(activation, "dissociation_method")).as(where)
                        .isEqualTo(precursor.activation().parameters().get(0).accession());
                assertThat(ParquetGroups.optDouble(activation, "collision_energy")).as(where)
                        .isEqualTo(precursor.activation().parameters().get(1).value());
                assertThat(ParquetGroups.readParams(activation, "parameters")).as(where).isEmpty();
                for (SelectedIon ion : precursor.selectedIons()) {
                    String at = "selected-ion row " + i + " (spectrum " + s.index() + ")";
                    Group ionRow = ionRows.get(i);
                    assertThat(ionRow.getLong("source_index", 0)).as(at).isEqualTo(s.index());
                    assertThat(ParquetGroups.optLong(ionRow, "precursor_index")).as(at)
                            .isEqualTo(precursor.precursorIndex());
                    assertThat(ionRow.getLong("selected_ion_index", 0)).as(at).isEqualTo(i);
                    assertThat(ParquetGroups.optDouble(ionRow, "selected_ion_mz")).as(at).isEqualTo(ion.mz());
                    assertThat(ParquetGroups.optInt(ionRow, "charge_state")).as(at).isEqualTo(ion.charge());
                    assertThat(ParquetGroups.optDouble(ionRow, "peak_intensity")).as(at).isEqualTo(ion.intensity());
                    assertThat(ParquetGroups.has(ionRow, "parameters")).as(at + ": parameters").isTrue();
                    assertThat(ParquetGroups.readParams(ionRow, "parameters")).as(at).isEqualTo(ion.parameters());
                    i++;
                }
            }
        }
        assertThat(p).isEqualTo(34);
        assertThat(i).isEqualTo(34);
    }

    @Test
    void twoPrecursorsWithDistinctKeysKeepTheirOwnIonsAfterASplitRoundTrip(@TempDir Path tmp) {
        Source small = source(Fixtures.current("small.unpacked.mzpeak"));
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        int first = nextMsn(spectra, 0);
        int second = nextMsn(spectra, first + 1);

        // The fixture's precursor (precursor_index 1) with its ion, then a second precursor with precursor_index 0,
        // a lower key, and two ions of its own.
        Precursor original = spectra.get(first).description().primaryPrecursor();
        SelectedIon a = original.primaryIon();
        SelectedIon b1 = new SelectedIon(401.5, 2, 1500.0, List.of());
        SelectedIon b2 = new SelectedIon(402.0, 3, null, List.of(new Param("ion mobility value", "MS:1002815",
                0.95, null)));
        Precursor other = new Precursor(0L, "controllerType=0 controllerNumber=1 scan=1",
                new IsolationWindow(401.75, 0.5, 0.75), List.of(b1, b2),
                new Activation(List.of(new Param("beam-type collision-induced dissociation", "MS:1000422", null,
                        null), new Param("collision energy", "MS:1000045", 28.0, "UO:0000266"))));
        spectra.set(first, withPrecursors(spectra.get(first), List.of(original, other)));

        // A precursor without key, id, window or activation, then one with precursor_index 7: null joins null.
        SelectedIon c = new SelectedIon(555.25, null, 10.0, List.of());
        SelectedIon d = new SelectedIon(556.5, 1, 20.0, List.of(new Param("note", null, "user", null)));
        Precursor keyless = new Precursor(null, null, null, List.of(c), Activation.EMPTY);
        Precursor seventh = new Precursor(7L, "scan=8", new IsolationWindow(null, 1.0, null), List.of(d),
                Activation.EMPTY);
        spectra.set(second, withPrecursors(spectra.get(second), List.of(keyless, seventh)));

        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, small.chromatograms, small.wavelengthSpectra, small.scanSettings,
                small.fileMetadata, SPLIT);

        List<Long> ionKeys = new ArrayList<>();
        for (Group row : rows(out.resolve(SELECTED_IONS))) {
            long sourceIndex = row.getLong("source_index", 0);
            if (sourceIndex == spectra.get(first).index() || sourceIndex == spectra.get(second).index()) {
                ionKeys.add(ParquetGroups.optLong(row, "precursor_index"));
            }
        }
        // Each spectrum's ions in the order of their precursor's key, null first (the Rust reader needs it).
        assertThat(ionKeys).as("precursor_index of the selected ions of the two spectra")
                .containsExactly(0L, 0L, 1L, null, 7L);

        Source back = ForwardBackwardCorpusTest.readSource(out);
        DescriptionAssert.assertEqual("constructed two-precursor spectra", descriptions(spectra),
                descriptions(back.spectra));
        List<Precursor> firstBack = back.spectra.get(first).description().precursors();
        assertThat(firstBack).extracting(Precursor::precursorIndex).containsExactly(1L, 0L);
        assertThat(firstBack.get(0).selectedIons()).containsExactly(a);
        assertThat(firstBack.get(1).selectedIons()).containsExactly(b1, b2);
        assertThat(firstBack.get(1).activation()).isEqualTo(other.activation());
        assertThat(firstBack.get(1).isolationWindow()).isEqualTo(other.isolationWindow());
        List<Precursor> secondBack = back.spectra.get(second).description().precursors();
        assertThat(secondBack).containsExactly(keyless, seventh);
        assertThat(secondBack.get(0).precursorIndex()).isNull();
    }

    /**
     * The Rust reference reader sorts the precursors by key and walks the selected ions in file order with a cursor
     * that only moves forward (metadata.rs:1156-1219), so the writer puts a spectrum's ions in the order of the
     * precursor_index of their precursor, null first. Spectrum S, written with its parents, has the precursors
     * [P_a (parent written as 1, ion A), P_b (parent written as 0, ion B)]: its ion rows are (S, 0, B) then (S, 1, A),
     * numbered in that order, while its precursor rows keep its own order. When P_b's parent is not written, its key
     * is null and (S, null, B) comes first. The reader reads A on P_a and B on P_b either way.
     */
    @Test
    void aSpectrumsSelectedIonsAreWrittenInTheOrderOfTheirPrecursorKeys(@TempDir Path tmp) {
        SelectedIon a = new SelectedIon(500.25, 2, 100.0, List.of());
        SelectedIon b = new SelectedIon(600.5, 3, 200.0, List.of());
        Spectrum first = constructed(10, 1, List.of());
        Spectrum second = constructed(20, 1, List.of());
        Precursor pa = new Precursor(20L, "scan=21", null, List.of(a), Activation.EMPTY);
        Precursor pb = new Precursor(10L, "scan=11", null, List.of(b), Activation.EMPTY);

        Path out = tmp.resolve("parents.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(first, second, constructed(30, 2, List.of(pa, pb))));

        assertThat(keyRows(out, PRECURSORS)).containsExactly("2|1", "2|0");
        assertThat(keyRows(out, SELECTED_IONS)).containsExactly("2|0|0|600.5", "2|1|1|500.25");
        assertIonsOnTheirOwnPrecursors(out, a, b, 1L, 0L);

        Precursor orphan = new Precursor(15L, "scan=16", null, List.of(b), Activation.EMPTY);
        Path variant = tmp.resolve("orphan.mzpeak");
        MzPeakWriter.writeDirectory(variant, List.of(first, second, constructed(30, 2, List.of(pa, orphan))));

        assertThat(keyRows(variant, PRECURSORS)).containsExactly("2|1", "2|null");
        assertThat(keyRows(variant, SELECTED_IONS)).containsExactly("2|null|0|600.5", "2|1|1|500.25");
        assertIonsOnTheirOwnPrecursors(variant, a, b, 1L, null);
    }

    /** Spectrum 2 of {@code out} has two precursors with the keys given, A on the first and B on the second. */
    private static void assertIonsOnTheirOwnPrecursors(Path out, SelectedIon a, SelectedIon b, Long keyA, Long keyB) {
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            List<Precursor> back = reader.getSpectrumAt(2).description().precursors();
            assertThat(back).extracting(Precursor::precursorIndex).containsExactly(keyA, keyB);
            assertThat(back.get(0).selectedIons()).containsExactly(a);
            assertThat(back.get(1).selectedIons()).containsExactly(b);
        }
    }

    /**
     * The rows of {@code member} as {@code source_index|precursor_index}, with
     * {@code |selected_ion_index|selected_ion_mz} for the selected ions, in row order.
     */
    private static List<String> keyRows(Path out, String member) {
        List<String> lines = new ArrayList<>();
        for (Group row : rows(out.resolve(member))) {
            String line = ParquetGroups.optLong(row, "source_index") + "|" + ParquetGroups.optLong(row,
                    "precursor_index");
            if (member.equals(SELECTED_IONS)) {
                line += "|" + ParquetGroups.optLong(row, "selected_ion_index") + "|"
                        + ParquetGroups.optDouble(row, "selected_ion_mz");
            }
            lines.add(line);
        }
        return lines;
    }

    /** A PROFILE spectrum of three points with one scan, {@code index}, {@code msLevel} and {@code precursors}. */
    private static Spectrum constructed(long index, int msLevel, List<Precursor> precursors) {
        double rt = index / 10.0;
        SpectrumDescription d = new SpectrumDescription(index, "scan=" + (index + 1), msLevel, rt, null,
                SignalContinuity.PROFILE, 3, 0, List.of(new ScanEvent(rt, null, null, null, null, null, List.of(),
                List.of())), precursors, List.of());
        return new Spectrum(d, new double[] {100.0, 100.5, 101.0}, new double[] {10.0, 20.0, 5.0}, List.of());
    }

    /**
     * SPLIT writes the precursor_index of every precursor as UINT64, so a negative one after the first is rejected
     * before any I/O, as a directory and as an archive (no output, no {@code .mzpeak-write-*} temp entry). PACKED
     * writes the primary precursor only and still writes the same input.
     */
    @Test
    void aNegativePrecursorIndexAfterTheFirstIsRejectedBeforeWritingWithSplitOnly(@TempDir Path tmp)
            throws IOException {
        Source small = source(Fixtures.current("small.unpacked.mzpeak"));
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        int msn = nextMsn(spectra, 0);
        Precursor original = spectra.get(msn).description().primaryPrecursor();
        assertThat(original.precursorIndex()).as("fixture precursor_index").isNotNull().isNotNegative();
        Precursor negative = new Precursor(-1L, "scan=0", null,
                List.of(new SelectedIon(401.5, 2, null, List.of())), Activation.EMPTY);
        spectra.set(msn, withPrecursors(spectra.get(msn), List.of(original, negative)));
        String message = "precursor_index must be non-negative (mzPeak indices are uint64): -1";

        Path dir = tmp.resolve("split.mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(dir, spectra, small.chromatograms,
                small.wavelengthSpectra, small.scanSettings, small.fileMetadata, SPLIT))
                .isInstanceOf(MzPeakException.class).hasMessage(message);
        assertThat(dir).as("output directory after a rejected write").doesNotExist();

        Path parent = Files.createDirectories(tmp.resolve("archive"));
        Path archive = parent.resolve("split.mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.writeArchive(archive, spectra, small.chromatograms,
                small.wavelengthSpectra, small.scanSettings, small.fileMetadata, SPLIT))
                .isInstanceOf(MzPeakException.class).hasMessage(message);
        try (Stream<Path> entries = Files.list(parent)) {
            assertThat(entries).as("entries next to %s after a rejected write", archive).isEmpty();
        }

        Path packed = tmp.resolve("packed.mzpeak");
        MzPeakWriter.writeDirectory(packed, spectra, small.chromatograms, small.wavelengthSpectra,
                small.scanSettings, small.fileMetadata, Options.defaults().withMetadataLayout(MetadataLayout.PACKED));
        Source back = ForwardBackwardCorpusTest.readSource(packed);
        assertThat(back.spectra).hasSameSizeAs(spectra);
        assertThat(back.spectra.get(msn).description().precursors()).as("PACKED precursors of the spectrum")
                .containsExactly(original);
    }

    @Test
    void selectedIonIonMobilityStaysInTheParametersListAndReadsBackEqual(@TempDir Path tmp) {
        Source dia = source(Fixtures.current("diaPASEF.ref.mzpeak"));
        int carriers = 0;
        for (Spectrum s : dia.spectra) {
            for (Precursor p : s.description().precursors()) {
                for (SelectedIon ion : p.selectedIons()) {
                    carriers += ion.ionMobilityValue().isPresent() ? 1 : 0;
                }
            }
        }
        assertThat(carriers).as("diaPASEF.ref selected ions with ion mobility").isEqualTo(8);
        Path out = write(dia, tmp.resolve("out.mzpeak"));

        MessageType ions = schema(out, SELECTED_IONS);
        assertThat(ions.containsField("ion_mobility_value")).isFalse();
        assertThat(ions.containsField("ion_mobility_type")).isFalse();
        int written = 0;
        for (Group row : rows(out.resolve(SELECTED_IONS))) {
            written += ParquetGroups.readParams(row, "parameters").stream()
                    .anyMatch(p -> "MS:1002815".equals(p.accession()) && p.value() instanceof Double) ? 1 : 0;
        }
        assertThat(written).as("selected-ion rows with MS:1002815 in parameters").isEqualTo(8);

        Source back = ForwardBackwardCorpusTest.readSource(out);
        DescriptionAssert.assertEqual("diaPASEF.ref", descriptions(dia.spectra), descriptions(back.spectra));
    }

    @Test
    void bothMembersAreWrittenWhenNoSpectrumHasAPrecursor(@TempDir Path tmp) throws IOException {
        Source imaging = source(Fixtures.current("Example_Processed.img.mzpeak"));
        assertThat(imaging.spectra).allSatisfy(s -> assertThat(s.description().precursors()).isEmpty());
        Path out = write(imaging, tmp.resolve("out.mzpeak"));

        List<String> roles = new ArrayList<>();
        for (JsonNode entry : manifest(out).path("files")) {
            roles.add(entry.path("name").asText() + "|" + entry.path("data_kind").asText());
        }
        assertThat(roles).contains(PRECURSORS + "|precursors", SELECTED_IONS + "|selected_ions");
        assertThat(rows(out.resolve(PRECURSORS))).isEmpty();
        assertThat(rows(out.resolve(SELECTED_IONS))).isEmpty();
        try (MzPeakSource dataset = MzPeakSource.open(out)) {
            for (String member : List.of(PRECURSORS, SELECTED_IONS)) {
                assertThat(ParquetGroups.footerKeyValue(dataset.inputFile(member)))
                        .as("footer of %s", member).containsEntry("spectrum_count", "9");
            }
        }
    }

    /**
     * The precursor member maps the three isolation-window columns and, since WR-5c, the two activation columns, and
     * the selected-ion member its three typed columns, each row exactly as current small.unpacked maps it.
     */
    @Test
    void theMappingRowsAreThoseOfTheCurrentManifests(@TempDir Path tmp) throws IOException {
        Path fixture = Fixtures.current("small.unpacked.mzpeak");
        Path out = write(source(fixture), tmp.resolve("out.mzpeak"));
        JsonNode current = manifest(fixture);
        JsonNode written = manifest(out);

        List<JsonNode> currentPrecursorRows = list(entry(current, PRECURSORS).path("column_mapping"));
        assertThat(currentPrecursorRows).hasSize(5);
        assertThat(list(entry(written, PRECURSORS).path("column_mapping")))
                .containsExactlyElementsOf(currentPrecursorRows);
        assertThat(entry(written, SELECTED_IONS).path("column_mapping"))
                .isEqualTo(entry(current, SELECTED_IONS).path("column_mapping"));
    }

    @Test
    void descriptionAssertReportsAnIonOnAnotherPrecursor() {
        SelectedIon a = new SelectedIon(400.0, 2, null, List.of());
        SelectedIon b = new SelectedIon(500.0, 2, null, List.of());
        Precursor first = new Precursor(1L, "scan=2", null, List.of(a), Activation.EMPTY);
        Precursor second = new Precursor(0L, "scan=1", null, List.of(b), Activation.EMPTY);
        SpectrumDescription expected = msn(List.of(first, second));
        SpectrumDescription moved = msn(List.of(
                new Precursor(1L, "scan=2", null, List.of(a, b), Activation.EMPTY),
                new Precursor(0L, "scan=1", null, List.of(), Activation.EMPTY)));

        assertThat(DescriptionAssert.differences("s", expected, msn(List.of(first, second)))).isEmpty();
        assertThat(DescriptionAssert.differences("s", expected, moved)).containsExactly(
                "s.precursors[0].selectedIons.size: expected 1, got 2",
                "s.precursors[1].selectedIons.size: expected 1, got 0");
        assertThat(DescriptionAssert.differences("s", expected, msn(List.of(second, first)))).contains(
                "s.precursors[0].precursorIndex: expected 1, got 0",
                "s.precursors[0].selectedIons[0].mz: expected 400.0, got 500.0");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static SpectrumDescription msn(List<Precursor> precursors) {
        return new SpectrumDescription(3, "scan=4", 2, 1.0, null, null, 0, 0, List.of(), precursors, List.of());
    }

    private static int nextMsn(List<Spectrum> spectra, int from) {
        for (int i = from; i < spectra.size(); i++) {
            if (!spectra.get(i).description().precursors().isEmpty()) {
                return i;
            }
        }
        throw new AssertionError("no MSn spectrum from position " + from);
    }

    /** {@code s} with {@code precursors} in place of its own; everything else is kept. */
    private static Spectrum withPrecursors(Spectrum s, List<Precursor> precursors) {
        SpectrumDescription d = s.description();
        SpectrumDescription changed = new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(),
                d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(), precursors,
                d.parameters());
        return new Spectrum(changed, s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
    }

    private static Source source(Path fixture) {
        return SOURCES.computeIfAbsent(fixture, ForwardBackwardCorpusTest::readSource);
    }

    private static Path write(Source s, Path out) {
        MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, SPLIT);
        return out;
    }

    private static List<SpectrumDescription> descriptions(List<Spectrum> spectra) {
        List<SpectrumDescription> out = new ArrayList<>(spectra.size());
        for (Spectrum s : spectra) {
            out.add(s.description());
        }
        return out;
    }

    private static JsonNode manifest(Path dataset) throws IOException {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            return MAPPER.readTree(source.readManifestBytes());
        }
    }

    /** The manifest entry named {@code name}. */
    private static JsonNode entry(JsonNode manifest, String name) {
        for (JsonNode e : manifest.path("files")) {
            if (name.equals(e.path("name").asText())) {
                return e;
            }
        }
        throw new AssertionError("no manifest entry " + name);
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    private static MessageType schema(Path out, String member) {
        try (MzPeakSource dataset = MzPeakSource.open(out)) {
            return ParquetFooter.read(dataset.inputFile(member)).schema();
        }
    }

    /**
     * Each field down to the structs, as {@code path repetition type annotation}, a group as
     * {@code path repetition group [annotation]}; a list's elements are not listed.
     */
    private static List<String> columns(MessageType schema) {
        List<String> out = new ArrayList<>();
        columns(schema.getFields(), "", out);
        return out;
    }

    private static void columns(List<Type> fields, String prefix, List<String> out) {
        for (Type t : fields) {
            String path = prefix + t.getName();
            String repetition = t.getRepetition().name().toLowerCase(Locale.ROOT);
            if (t.isPrimitive()) {
                PrimitiveType p = t.asPrimitiveType();
                LogicalTypeAnnotation a = p.getLogicalTypeAnnotation();
                String annotation = a == null ? "-"
                        : a instanceof LogicalTypeAnnotation.IntLogicalTypeAnnotation i
                        ? (i.isSigned() ? "int" : "uint") + i.getBitWidth()
                        : a.toString().toLowerCase(Locale.ROOT);
                out.add(path + " " + repetition + " " + p.getPrimitiveTypeName() + " " + annotation);
            } else {
                LogicalTypeAnnotation a = t.getLogicalTypeAnnotation();
                out.add(path + " " + repetition + " group"
                        + (a == null ? "" : " " + a.toString().toLowerCase(Locale.ROOT)));
                if (a == null) {
                    columns(t.asGroupType().getFields(), path + ".", out);
                }
            }
        }
    }

    private static List<Group> rows(Path file) {
        List<Group> out = new ArrayList<>();
        ParquetGroups.forEach(file, out::add);
        return out;
    }
}
