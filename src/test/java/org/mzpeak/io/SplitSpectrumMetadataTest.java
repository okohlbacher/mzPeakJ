package org.mzpeak.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.GoldenMeta.Row;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.model.Activation;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.CvTerms;
import org.mzpeak.model.ImagingPosition;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RD-5: the split spectrum metadata layout of the current fixtures (HUPO-PSI/mzPeak 2ee2589), whose spectrum, scans,
 * precursors and selected-ions facets are members of their own, found by manifest role and joined on
 * {@code source_index}. Spectra are read through {@link MzPeakReader} and checked against three oracles: the HEAD
 * Python golden ({@link Golden}), the raw pyarrow cells ({@link GoldenMeta}) read at the path each member's
 * column_mapping gives, and the legacy packed small.unpacked of the same data. Profile reconstruction uses the
 * renamed m/z delta model, MS:1003821 {@code polynomial_delta_coordinate_interpolation}.
 *
 * <p>Not checked here: parameter cells and leftover columns (RD-6), activation and selected-ion ion mobility (RD-7),
 * and extra signal arrays such as diaPASEF's per-peak ion mobility (RD-14).
 */
class SplitSpectrumMetadataTest {

    private static final String SMALL = "small.mzpeak";
    private static final String DIA_REF = "diaPASEF.ref.mzpeak";
    private static final String DIA_GRID = "diaPASEF.grid.mzpeak";

    /**
     * The measured Numpress tolerance for intensities: the Python reader rounds the decoded SLOF intensities of
     * small.numpress to float32, so they differ from mzPeakJ's doubles by up to float32's unit roundoff, 2^-24
     * (measured: 5.88e-8 relative, and every mzPeakJ intensity rounded to float32 equals the golden value).
     */
    private static final Golden.Tolerance NUMPRESS = new Golden.Tolerance(Golden.Tolerance.DEFAULT.filledAbsDa(),
            Golden.Tolerance.DEFAULT.relExact(), Golden.Tolerance.DEFAULT.relFilled(), Math.scalb(1.0, -24));

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INDEX = "mzpeak_index.json";

    static Stream<String> currentFixtures() {
        return Fixtures.CURRENT_NAMES.stream();
    }

    static Stream<String> smallFixtures() {
        return Stream.of(SMALL, "small.chunked.mzpeak", "small.numpress.mzpeak", "small.unpacked.mzpeak");
    }

    // ---- (a) current small, 4 containers -------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void smallHas48SpectraWithTheGoldenKeysAndTheirMetadataCells(String fixture) {
        Golden golden = Golden.load(fixture);
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            Entry spectra = Facet.SPECTRUM.entry(reader.manifest()).orElseThrow();
            Map<Long, Row> cells = byKey(GoldenMeta.load(fixture).rows(spectra.name()), "index");

            assertThat(reader.size()).isEqualTo(48);
            assertThat(reader.metadata()).extracting(d -> Long.toString(d.index()))
                    .containsExactlyElementsOf(golden.lines(Golden.Kind.S).stream().map(Golden.Line::key).toList());
            for (SpectrumDescription d : reader.metadata()) {
                Row row = cells.get(d.index());
                assertThat(d.msLevel()).as("%s spectrum %d ms level", fixture, d.index())
                        .isEqualTo(intCell(cell(row, spectra, "MS:1000511"), 0)).isPositive();
                assertThat(d.retentionTime()).as("%s spectrum %d time", fixture, d.index())
                        .isEqualTo(row.get("time")).isPositive();
                assertThat(d.id()).as("%s spectrum %d id", fixture, d.index())
                        .isEqualTo(row.get("id"))
                        .isEqualTo("controllerType=0 controllerNumber=1 scan=" + (d.index() + 1));
            }
        }
    }

    // ---- (a) to (e) arrays against the golden ---------------------------------------------------------------

    /**
     * Every spectrum passes the golden rules: its arrays match its S line, its full arrays or digest where the
     * golden holds them, and its peaks match its P line (no peaks without one). The numbers of lines checked are
     * pinned, so the test cannot pass vacuously. diaPASEF.grid's peaks are grid-encoded; {@link GridDecodeTest} checks
     * them against diaPASEF.ref and both goldens.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "small.mzpeak,                 48,  34, 3, 0",
            "small.chunked.mzpeak,         48,  48, 3, 0",
            "small.numpress.mzpeak,        48,  34, 3, 0",
            "small.unpacked.mzpeak,        48,  34, 3, 0",
            "has_uv.mzpeak,               212, 212, 2, 0",
            "diaPASEF.ref.mzpeak,           9,   9, 2, 1",
            "Example_Processed.img.mzpeak,  9,   0, 2, 0"
    })
    void everySpectrumPassesTheGolden(String fixture, int spectra, int peakLines, int fullArrays, int digests) {
        Golden golden = Golden.load(fixture);
        Golden.Tolerance tolerance = fixture.equals("small.numpress.mzpeak") ? NUMPRESS : Golden.Tolerance.DEFAULT;
        List<String> mismatches = new ArrayList<>();
        int peakLinesChecked = 0;
        int fullChecked = 0;
        int digestsChecked = 0;
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            assertThat(reader.size()).isEqualTo(spectra);
            assertThat(reader.metadata()).extracting(d -> Long.toString(d.index()))
                    .containsExactlyElementsOf(golden.lines(Golden.Kind.S).stream().map(Golden.Line::key).toList());
            for (Spectrum s : reader) {
                String key = Long.toString(s.index());
                mismatches.addAll(Golden.Comparator.compare(golden.line(Golden.Kind.S, key).orElseThrow(),
                        s.mz(), s.intensity(), tolerance));
                Optional<Golden.FullArray> full = golden.full(Golden.Kind.S, key);
                if (full.isPresent()) {
                    mismatches.addAll(Golden.Comparator.compareFull(full.get(), s.mz(), s.intensity(), tolerance));
                    fullChecked++;
                }
                Optional<Golden.Digest> digest = golden.digest(Golden.Kind.S, key);
                if (digest.isPresent()) {
                    // m/z and intensity only: the extra arrays (diaPASEF's ion mobility) are RD-14's
                    Golden.Digest d = digest.get();
                    mismatches.addAll(Golden.Comparator.compareDigest(new Golden.Digest(d.kind(), d.key(), d.n(),
                            d.nFilled(), d.sha256X(), d.sha256Y(), Map.of()), s.mz(), s.intensity(), Map.of()));
                    digestsChecked++;
                }
                Optional<Golden.Line> peaks = golden.line(Golden.Kind.P, key);
                if (peaks.isPresent()) {
                    mismatches.addAll(Golden.Comparator.compare(peaks.get(), peakMz(s), peakIntensity(s), tolerance));
                    peakLinesChecked++;
                } else {
                    assertThat(s.peaks()).as("%s spectrum %s peaks", fixture, key).isEmpty();
                }
            }
        }
        assertThat(mismatches).as(fixture).isEmpty();
        assertThat(peakLinesChecked).as("%s P lines", fixture).isEqualTo(peakLines)
                .isEqualTo(golden.lines(Golden.Kind.P).size());
        assertThat(fullChecked).as("%s full arrays", fixture).isEqualTo(fullArrays);
        assertThat(digestsChecked).as("%s digests", fixture).isEqualTo(digests);
    }

    // ---- (b) small anchors ------------------------------------------------------------------------------------

    @Test
    void smallSpectrumZeroIsAProfileWhoseStoredPointsAreTheRawCells() {
        Golden.Line raw = Golden.load(SMALL).line(Golden.Kind.R, "0").orElseThrow();
        try (MzPeakReader full = MzPeakReader.open(Fixtures.current(SMALL), true);
             MzPeakReader stored = MzPeakReader.open(Fixtures.current(SMALL), false)) {
            Spectrum profile = full.getSpectrum(0).orElseThrow();
            assertThat(profile.description().signalContinuity()).isEqualTo(SignalContinuity.PROFILE);
            assertThat(profile.description().numberOfDataPoints()).isEqualTo(13589);
            assertThat(profile.mz()).hasSize(13589);

            Spectrum points = stored.getSpectrum(0).orElseThrow();
            assertThat(points.mz()).hasSize(11213);
            assertThat((long) points.mz().length).isEqualTo(raw.n());
            assertThat(raw.nFilled()).isEqualTo(13589 - 11213);
            assertThat(exactSum(points.mz())).isEqualTo(raw.sumX());
            assertThat(exactSum(points.intensity())).isEqualTo(raw.sumY());

            Spectrum centroid = full.getSpectrum(4).orElseThrow();
            assertThat(centroid.description().signalContinuity()).isEqualTo(SignalContinuity.CENTROID);
            assertThat(centroid.mz()).hasSize(837);
            assertThat(full.getSpectrum(5).orElseThrow().peaks()).hasSize(650);
            assertThat(full.getSpectrum(25).orElseThrow().peaks()).hasSize(789);
        }
        try (MzPeakReader chunked = MzPeakReader.open(Fixtures.current("small.chunked.mzpeak"))) {
            assertThat(chunked.getSpectrum(0).orElseThrow().peaks()).hasSize(1612);
        }
    }

    // ---- (c) imaging --------------------------------------------------------------------------------------------

    @Test
    void exampleProcessedImagingSpectraLieOnAThreeByThreeGrid() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("Example_Processed.img.mzpeak"))) {
            assertThat(reader.size()).isEqualTo(9);
            for (int i = 0; i < 9; i++) {
                SpectrumDescription d = reader.metadataAt(i);
                assertThat(d.index()).isEqualTo(i);
                assertThat(d.signalContinuity()).isEqualTo(SignalContinuity.PROFILE);
                assertThat(d.scans()).hasSize(1);
                assertThat(d.scans().get(0).imagingPosition()).as("spectrum %d", i)
                        .isEqualTo(new ImagingPosition(i % 3 + 1, i / 3 + 1));
            }
        }
    }

    // ---- (d) diaPASEF.ref ion mobility --------------------------------------------------------------------------

    @Test
    void diaPasefRefScansCarryTheirInverseReducedIonMobilityCell() {
        GoldenMeta meta = GoldenMeta.load(DIA_REF);
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(DIA_REF))) {
            String scans = Facet.SPECTRUM_SCAN.entry(reader.manifest()).orElseThrow().name();
            Map<Long, List<Row>> rows = bySourceIndex(meta.rows(scans));
            assertThat(reader.size()).isEqualTo(9);
            for (SpectrumDescription d : reader.metadata()) {
                assertThat(d.scans()).hasSize(1);
                assertThat(rows.get(d.index())).hasSize(1);
                Object cell = rows.get(d.index()).get(0).get("ion_mobility_value");
                List<Param> mobility = d.scans().get(0).parameters().stream()
                        .filter(p -> p.hasAccession("MS:1002815")).toList();
                if (d.index() == 0) {
                    assertThat(cell).isNull();
                    assertThat(mobility).isEmpty();
                } else {
                    assertThat(cell).as("spectrum %d", d.index()).isInstanceOf(Double.class);
                    assertThat(mobility).as("spectrum %d", d.index()).isNotEmpty()
                            .allSatisfy(p -> assertThat(p.value()).isEqualTo(cell));
                }
            }
        }
    }

    // ---- (e) has_uv ----------------------------------------------------------------------------------------------

    @Test
    void hasUvSpectraAreTheirPeaksWithOneScanEach() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("has_uv.mzpeak"))) {
            assertThat(reader.size()).isEqualTo(212);
            for (Spectrum s : reader) {
                assertThat(s.mz()).as("spectrum %d", s.index()).isNotEmpty();
                LayoutEquivalenceTest.assertIdentical("spectrum " + s.index() + " m/z", peakMz(s), s.mz());
                LayoutEquivalenceTest.assertIdentical("spectrum " + s.index() + " intensity", peakIntensity(s),
                        s.intensity());
                List<ScanEvent> scans = s.description().scans();
                assertThat(scans).as("spectrum %d scans", s.index()).hasSize(1);
                assertThat(scans.get(0).startTime()).isEqualTo(s.description().retentionTime());
            }
        }
    }

    // ---- (f) diaPASEF.grid ---------------------------------------------------------------------------------------

    @Test
    void diaPasefGridDescribesItsSpectraLikeDiaPasefRef() {
        try (MzPeakReader grid = MzPeakReader.open(Fixtures.current(DIA_GRID));
             MzPeakReader ref = MzPeakReader.open(Fixtures.current(DIA_REF))) {
            assertThat(grid.size()).isEqualTo(9);
            assertThat(Canonical.lines(grid.metadata(), Canonical::describe))
                    .containsExactlyElementsOf(Canonical.lines(ref.metadata(), Canonical::describe));
            // its grid-encoded peaks decode since RD-20
            assertThat(grid.getSpectrum(0).orElseThrow().mz()).hasSize(ref.getSpectrum(0).orElseThrow().mz().length);
        }
    }

    // ---- (g) cross-version ---------------------------------------------------------------------------------------

    /**
     * Current and legacy small.unpacked hold the same 48 spectra. The legacy scan start time is a float32 column,
     * so it equals the current double rounded to float. Activation is not compared; RD-7 decodes it. The current
     * spectrum Params continue the legacy ones with the split leftovers (RD-6, pinned by SplitLeftoversTest), which
     * the packed layout does not have.
     */
    @Test
    void currentSmallUnpackedDescribesItsSpectraLikeTheLegacyPackedOne() {
        try (MzPeakReader current = MzPeakReader.open(Fixtures.current("small.unpacked.mzpeak"), false);
             MzPeakReader legacy = MzPeakReader.open(Fixtures.legacy("small.unpacked.mzpeak"), false)) {
            assertThat(current.size()).isEqualTo(48);
            assertThat(legacy.size()).isEqualTo(48);
            int precursors = 0;
            for (int i = 0; i < 48; i++) {
                Spectrum c = current.getSpectrumAt(i);
                Spectrum l = legacy.getSpectrumAt(i);
                SpectrumDescription cd = c.description();
                SpectrumDescription ld = l.description();
                List<Object> currentHeader = header(cd);
                List<Object> legacyHeader = header(ld);
                int params = currentHeader.size() - 1;
                assertThat(currentHeader.subList(0, params)).as("spectrum %d", ld.index())
                        .isEqualTo(legacyHeader.subList(0, params));
                assertThat(cd.parameters()).as("spectrum %d parameters", ld.index())
                        .hasSizeGreaterThan(ld.parameters().size());
                assertThat(cd.parameters().subList(0, ld.parameters().size())).as("spectrum %d parameters", ld.index())
                        .isEqualTo(ld.parameters());

                assertThat(cd.scans()).hasSameSizeAs(ld.scans()).isNotEmpty();
                for (int k = 0; k < ld.scans().size(); k++) {
                    ScanEvent s = cd.scans().get(k);
                    assertThat(new ScanEvent((float) s.startTime(), s.injectionTime(), s.filterString(),
                            s.presetScanConfiguration(), s.instrumentConfigurationRef(), s.spectrumReference(),
                            s.scanWindows(), s.parameters()))
                            .as("spectrum %d scan %d", ld.index(), k).isEqualTo(ld.scans().get(k));
                }
                assertThat(cd.precursors().stream().map(SplitSpectrumMetadataTest::withoutActivation).toList())
                        .as("spectrum %d precursors", ld.index())
                        .isEqualTo(ld.precursors().stream().map(SplitSpectrumMetadataTest::withoutActivation).toList());
                precursors += ld.precursors().size();

                LayoutEquivalenceTest.assertIdentical("spectrum " + i + " m/z", l.mz(), c.mz());
                LayoutEquivalenceTest.assertIdentical("spectrum " + i + " intensity", l.intensity(), c.intensity());
                assertThat(c.peaks()).as("spectrum %d peaks", ld.index()).isEqualTo(l.peaks());
            }
            assertThat(precursors).isEqualTo(34);
        }
    }

    // ---- (h) every decoded field against its raw cell ------------------------------------------------------------

    /**
     * Every field this unit decodes equals its pyarrow cell, found at the path the member's column_mapping gives
     * for the field's accession (by column name for the unmapped {@code index}, {@code id}, {@code time},
     * {@code instrument_configuration_id}, {@code precursor_index}, {@code precursor_id} and the typed ion
     * mobility). Facet rows are matched to spectra by {@code source_index}, and every facet row must be used.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void everyDecodedFieldEqualsItsRawCell(String fixture) {
        GoldenMeta meta = GoldenMeta.load(fixture);
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            MzPeakManifest manifest = reader.manifest();
            Entry spectra = Facet.SPECTRUM.entry(manifest).orElseThrow();
            Entry scans = Facet.SPECTRUM_SCAN.entry(manifest).orElseThrow();
            Entry precursors = Facet.SPECTRUM_PRECURSOR.entry(manifest).orElseThrow();
            Entry ions = Facet.SPECTRUM_SELECTED_ION.entry(manifest).orElseThrow();
            Map<Long, Row> spectrumRows = byKey(meta.rows(spectra.name()), "index");
            Map<Long, List<Row>> scanRows = bySourceIndex(meta.rows(scans.name()));
            Map<Long, List<Row>> precursorRows = bySourceIndex(meta.rows(precursors.name()));
            Map<Long, List<Row>> ionRows = bySourceIndex(meta.rows(ions.name()));

            assertThat(reader.metadata()).hasSize(spectrumRows.size()).isNotEmpty();
            int scansChecked = 0;
            int precursorsChecked = 0;
            int ionsChecked = 0;
            for (SpectrumDescription d : reader.metadata()) {
                String where = fixture + " spectrum " + d.index();
                assertSpectrum(where, d, spectrumRows.get(d.index()), spectra);

                List<Row> scanCells = scanRows.getOrDefault(d.index(), List.of());
                assertThat(d.scans()).as(where + " scans").hasSameSizeAs(scanCells);
                for (int k = 0; k < scanCells.size(); k++) {
                    assertScan(where + " scan " + k, d.scans().get(k), scanCells.get(k), scans);
                    scansChecked++;
                }

                List<Row> precursorCells = precursorRows.getOrDefault(d.index(), List.of());
                List<Row> ionCells = ionRows.getOrDefault(d.index(), List.of());
                if (!precursorCells.isEmpty()) {
                    assertThat(d.precursors()).as(where + " precursors").hasSameSizeAs(precursorCells);
                    for (int k = 0; k < precursorCells.size(); k++) {
                        assertPrecursor(where + " precursor " + k, d.precursors().get(k), precursorCells.get(k),
                                precursors);
                        precursorsChecked++;
                    }
                } else {
                    assertThat(d.precursors()).as(where + " precursors").hasSize(ionCells.isEmpty() ? 0 : 1);
                }
                List<SelectedIon> decodedIons = d.precursors().stream().flatMap(p -> p.selectedIons().stream())
                        .toList();
                assertThat(decodedIons).as(where + " selected ions").hasSameSizeAs(ionCells);
                for (int k = 0; k < ionCells.size(); k++) {
                    assertSelectedIon(where + " selected ion " + k, decodedIons.get(k), ionCells.get(k), ions);
                    ionsChecked++;
                }
            }
            assertThat(scansChecked).as("%s scan rows", fixture).isEqualTo(meta.rows(scans.name()).size());
            assertThat(precursorsChecked).as("%s precursor rows", fixture)
                    .isEqualTo(meta.rows(precursors.name()).size());
            assertThat(ionsChecked).as("%s selected-ion rows", fixture).isEqualTo(meta.rows(ions.name()).size());
        }
    }

    // ---- (i) manifest variations ---------------------------------------------------------------------------------

    @Test
    void aManifestWithAnAddedProvenanceEntryStillOpens(@TempDir Path tmp) throws IOException {
        Path dir = copy(Fixtures.current("small.unpacked.mzpeak"), tmp);
        editFiles(dir, files -> files.addObject().put("name", "provenance.parquet")
                .put("entity_type", "provenance").put("data_kind", "signature"));
        try (MzPeakReader reader = MzPeakReader.open(dir);
             MzPeakReader original = MzPeakReader.open(Fixtures.current("small.unpacked.mzpeak"))) {
            assertThat(reader.manifest().files()).anySatisfy(e -> assertThat(e.entity())
                    .isEqualTo(EntityType.PROVENANCE));
            assertThat(reader.size()).isEqualTo(48);
            assertThat(Canonical.lines(reader.metadata(), Canonical::describe))
                    .containsExactlyElementsOf(Canonical.lines(original.metadata(), Canonical::describe));
        }
    }

    /**
     * Facet members are optional: without them the spectra keep their own row and lose only their facets. (A facet
     * entry without a name is an invalid manifest; see {@code ManifestRolesTest}.)
     */
    @Test
    void missingFacetMembersAreLegal(@TempDir Path tmp) throws IOException {
        Path dir = copy(Fixtures.current("small.unpacked.mzpeak"), tmp);
        Set<String> facets = Set.of("scans", "precursors", "selected_ions");
        editFiles(dir, files -> {
            for (int i = files.size() - 1; i >= 0; i--) {
                if (files.get(i).path("entity_type").asText().equals("spectrum")
                        && facets.contains(files.get(i).path("data_kind").asText())) {
                    files.remove(i);
                }
            }
        });
        try (MzPeakReader reader = MzPeakReader.open(dir);
             MzPeakReader original = MzPeakReader.open(Fixtures.current("small.unpacked.mzpeak"))) {
            assertThat(reader.size()).isEqualTo(48);
            for (int i = 0; i < 48; i++) {
                SpectrumDescription d = reader.metadataAt(i);
                assertThat(d.scans()).isEmpty();
                assertThat(d.precursors()).isEmpty();
                assertThat(header(d)).isEqualTo(header(original.metadataAt(i)));
            }
            LayoutEquivalenceTest.assertIdentical("spectrum 0 m/z", original.getSpectrumAt(0).mz(),
                    reader.getSpectrumAt(0).mz());
        }
    }

    // ---- field checks ---------------------------------------------------------------------------------------

    private static void assertSpectrum(String where, SpectrumDescription d, Row row, Entry entry) {
        assertThat(row).as(where + " row").isNotNull();
        assertThat(d.index()).as(where + " index").isEqualTo(row.get("index"));
        assertThat(d.id()).as(where + " id").isEqualTo(row.get("id"));
        assertThat(d.msLevel()).as(where + " ms level").isEqualTo(intCell(cell(row, entry, "MS:1000511"), 0));
        assertThat(d.retentionTime()).as(where + " time").isEqualTo(doubleCell(row.get("time"), Double.NaN));
        Integer polarity = intCell(cell(row, entry, "MS:1000465"), null);
        assertThat(d.polarity()).as(where + " polarity").isEqualTo(Polarity.fromCode(polarity));
        assertThat(d.signalContinuity()).as(where + " continuity")
                .isEqualTo(SignalContinuity.fromCurie((String) cell(row, entry, "MS:1000525")))
                .isNotEqualTo(SignalContinuity.UNKNOWN);
        assertThat(d.numberOfDataPoints()).as(where + " number of data points")
                .isEqualTo(longCell(cell(row, entry, "MS:1003060")));
        assertThat(d.numberOfPeaks()).as(where + " number of peaks")
                .isEqualTo(longCell(cell(row, entry, "MS:1003059")));
    }

    private static void assertScan(String where, ScanEvent s, Row row, Entry entry) {
        assertThat(s.startTime()).as(where + " start time")
                .isEqualTo(doubleCell(cell(row, entry, "MS:1000016"), Double.NaN));
        assertThat(s.injectionTime()).as(where + " injection time").isEqualTo(cell(row, entry, "MS:1000927"));
        assertThat(s.filterString()).as(where + " filter string").isEqualTo(cell(row, entry, "MS:1000512"));
        assertThat(s.presetScanConfiguration()).as(where + " preset scan configuration")
                .isEqualTo(intCell(cell(row, entry, "MS:1000616"), null));
        assertThat(s.instrumentConfigurationRef()).as(where + " instrument configuration")
                .isEqualTo(intCell(row.get("instrument_configuration_id"), null));

        List<Object> lower = row.list(path(entry, "MS:1000501"));
        List<Object> upper = row.list(path(entry, "MS:1000500"));
        assertThat(lower).hasSameSizeAs(upper);
        List<ScanWindow> windows = new ArrayList<>();
        for (int k = 0; k < lower.size(); k++) {
            if (lower.get(k) != null && upper.get(k) != null) {
                windows.add(new ScanWindow((Double) lower.get(k), (Double) upper.get(k)));
            }
        }
        assertThat(s.scanWindows()).as(where + " scan windows").isEqualTo(windows);

        Object mobility = row.values().get("ion_mobility_value");
        if (mobility == null) {
            // no typed ion mobility is appended: the scan's ion mobility Params are those of its parameters list
            long listed = row.list("parameters.accession").stream()
                    .filter(CvTerms.ION_MOBILITY_TYPES::contains).count();
            assertThat(s.parameters()).as(where + " typed ion mobility")
                    .filteredOn(p -> CvTerms.ION_MOBILITY_TYPES.contains(p.accession())).hasSize((int) listed);
        } else {
            Object type = row.values().get("ion_mobility_type");
            String accession = type == null ? "MS:1002476" : (String) type;
            assertThat(s.parameters()).as(where + " typed ion mobility")
                    .anyMatch(p -> p.hasAccession(accession) && mobility.equals(p.value()));
        }

        Optional<String> x = mapped(entry, "IMS:1000050");
        Optional<String> y = mapped(entry, "IMS:1000051");
        if (x.isPresent() && y.isPresent()) {
            Long px = (Long) row.get(x.get());
            Long py = (Long) row.get(y.get());
            assertThat(Param.find(s.parameters(), "IMS:1000050")).as(where + " position x")
                    .map(Param::value).contains(px);
            assertThat(Param.find(s.parameters(), "IMS:1000051")).as(where + " position y")
                    .map(Param::value).contains(py);
            assertThat(s.imagingPosition()).as(where + " imaging position")
                    .isEqualTo(new ImagingPosition(Math.toIntExact(px), Math.toIntExact(py)));
        } else {
            assertThat(s.imagingPosition()).as(where + " imaging position").isNull();
        }
    }

    private static void assertPrecursor(String where, Precursor p, Row row, Entry entry) {
        assertThat(p.precursorIndex()).as(where + " precursor_index").isEqualTo(row.get("precursor_index"));
        assertThat(p.precursorId()).as(where + " precursor_id").isEqualTo(row.get("precursor_id"));
        IsolationWindow expected = row.get("isolation_window") == null ? null : new IsolationWindow(
                (Double) cell(row, entry, "MS:1000827"),
                (Double) cell(row, entry, "MS:1000828"),
                (Double) cell(row, entry, "MS:1000829"));
        assertThat(p.isolationWindow()).as(where + " isolation window").isEqualTo(expected);
    }

    private static void assertSelectedIon(String where, SelectedIon ion, Row row, Entry entry) {
        assertThat(ion.mz()).as(where + " m/z").isEqualTo(cell(row, entry, "MS:1000744"));
        assertThat(ion.charge()).as(where + " charge").isEqualTo(intCell(cell(row, entry, "MS:1000041"), null));
        assertThat(ion.intensity()).as(where + " intensity").isEqualTo(cell(row, entry, "MS:1000042"));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** The fields of a description outside its scans and precursors. */
    private static List<Object> header(SpectrumDescription d) {
        return Arrays.asList(d.index(), d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.parameters());
    }

    private static Precursor withoutActivation(Precursor p) {
        return new Precursor(p.precursorIndex(), p.precursorId(), p.isolationWindow(), p.selectedIons(),
                Activation.EMPTY);
    }

    /** The cell of {@code row} at the path {@code entry}'s column_mapping gives for {@code accession}. */
    private static Object cell(Row row, Entry entry, String accession) {
        return row.get(path(entry, accession));
    }

    private static String path(Entry entry, String accession) {
        return mapped(entry, accession)
                .orElseThrow(() -> new AssertionError(entry.name() + " maps no column to " + accession));
    }

    private static Optional<String> mapped(Entry entry, String accession) {
        return entry.columnForAccession(accession).map(MzPeakManifest.ColumnMapping::path);
    }

    private static Integer intCell(Object cell, Integer absent) {
        return cell == null ? absent : Integer.valueOf(Math.toIntExact((Long) cell));
    }

    private static long longCell(Object cell) {
        return cell == null ? 0L : (Long) cell;
    }

    private static double doubleCell(Object cell, double absent) {
        return cell == null ? absent : (Double) cell;
    }

    /** The rows by their key column {@code key}, which must be unique. */
    private static Map<Long, Row> byKey(List<Row> rows, String key) {
        Map<Long, Row> out = new LinkedHashMap<>();
        for (Row r : rows) {
            assertThat(out.put((Long) r.get(key), r)).as("%s row %d: duplicate %s", r.member(), r.row(), key)
                    .isNull();
        }
        return out;
    }

    /** The rows grouped by {@code source_index}, each group in row order. */
    private static Map<Long, List<Row>> bySourceIndex(List<Row> rows) {
        Map<Long, List<Row>> out = new LinkedHashMap<>();
        for (Row r : rows) {
            out.computeIfAbsent((Long) r.get("source_index"), k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    private static double[] peakMz(Spectrum s) {
        return s.peaks().stream().mapToDouble(CentroidPeak::mz).toArray();
    }

    private static double[] peakIntensity(Spectrum s) {
        return s.peaks().stream().mapToDouble(CentroidPeak::intensity).toArray();
    }

    /** The exactly rounded sum, as Python's {@code math.fsum} computes it. */
    private static double exactSum(double[] values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (double v : values) {
            sum = sum.add(new BigDecimal(v));
        }
        return sum.doubleValue();
    }

    /** Copies the unpacked dataset {@code dataset} into a directory under {@code tmp}. */
    private static Path copy(Path dataset, Path tmp) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve(dataset.getFileName().toString()));
        try (Stream<Path> files = Files.list(dataset)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Files.copy(f, dir.resolve(f.getFileName().toString()));
            }
        }
        return dir;
    }

    /** Applies {@code edit} to the {@code files} array of the manifest of the unpacked dataset {@code dir}. */
    private static void editFiles(Path dir, Consumer<ArrayNode> edit) throws IOException {
        ObjectNode index = (ObjectNode) MAPPER.readTree(Files.readAllBytes(dir.resolve(INDEX)));
        edit.accept((ArrayNode) index.get("files"));
        Files.write(dir.resolve(INDEX), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(index));
    }
}
