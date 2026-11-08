package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.GoldenMeta.Row;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.WriterSnapshotTest.FixtureSet;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AP-7: the isolation window of every precursor, in both fixture sets ({@link Fixtures#CURRENT} and
 * {@link Fixtures#LEGACY}).
 *
 * <p>Characterization of the base code: every precursor's window, given as its target, its offsets and its bounds
 * ({@link #window}), is pinned per fixture by the number of precursors, the number of windows and the SHA-256 of the
 * window lines ({@link Canonical#sha256}). Oracles: the FX-2 cells of the current fixtures, found at the paths the
 * precursor member's column_mapping gives, and legacy small against current small.unpacked.
 */
class IsolationWindowParamsTest {

    private static final String SMALL_UNPACKED = "small.unpacked.mzpeak";
    private static final String TARGET = "MS:1000827";
    private static final String LOWER = "MS:1000828";
    private static final String UPPER = "MS:1000829";

    /** The precursors of a fixture, those with a window, and the SHA-256 of their {@link #windowLines}. */
    record Snapshot(int precursors, int windows, String sha256) {
    }

    /** SHA-256 of zero lines, for a fixture without precursors. */
    private static final String NONE = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** The windows of small in both sets, in all four containers. */
    private static final Snapshot SMALL = new Snapshot(34, 34,
            "5b01a16087d591a18a1af5f05006225b360b50c6abdb552bf8567ce69a1690eb");
    /** The windows of diaPASEF.ref and diaPASEF.grid, which share their metadata. */
    private static final Snapshot DIA = new Snapshot(8, 8,
            "f0f3b6904f171f53492a241353c2dcaece3fc9f96f523d9a2ba235656bc2717e");

    private static final Map<String, Snapshot> EXPECTED = expected();

    private static Map<String, Snapshot> expected() {
        Map<String, Snapshot> out = new LinkedHashMap<>();
        for (String small : List.of("small.mzpeak", "small.chunked.mzpeak", "small.numpress.mzpeak", SMALL_UNPACKED)) {
            out.put(FixtureSet.CURRENT.label + " " + small, SMALL);
            out.put(FixtureSet.LEGACY.label + " " + small, SMALL);
        }
        out.put(FixtureSet.CURRENT.label + " has_uv.mzpeak", new Snapshot(0, 0, NONE));
        out.put(FixtureSet.CURRENT.label + " diaPASEF.ref.mzpeak", DIA);
        out.put(FixtureSet.CURRENT.label + " diaPASEF.grid.mzpeak", DIA);
        out.put(FixtureSet.CURRENT.label + " Example_Processed.img.mzpeak", new Snapshot(0, 0, NONE));
        out.put(FixtureSet.LEGACY.label + " has_uv.mzpeak", new Snapshot(0, 0, NONE));
        return out;
    }

    static Stream<Arguments> bothSets() {
        List<Arguments> out = new ArrayList<>();
        for (String fixture : Fixtures.CURRENT_NAMES) {
            out.add(Arguments.of(FixtureSet.CURRENT, fixture));
        }
        for (String fixture : Fixtures.LEGACY_NAMES) {
            out.add(Arguments.of(FixtureSet.LEGACY, fixture));
        }
        return out.stream();
    }

    static Stream<String> currentFixtures() {
        return Fixtures.CURRENT_NAMES.stream();
    }

    // ---- characterization ---------------------------------------------------------------------------------------

    /** Every precursor's window, with its target, offsets and bounds, matches the base code's. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("bothSets")
    void everyPrecursorWindowMatchesItsSnapshot(FixtureSet set, String fixture) {
        Snapshot expected = EXPECTED.get(set.label + " " + fixture);
        assertThat(expected).as("snapshot for %s %s", set.label, fixture).isNotNull();
        try (MzPeakReader reader = MzPeakReader.open(set.path(fixture))) {
            List<SpectrumDescription> metadata = reader.metadata();
            List<String> lines = windowLines(metadata);
            int windows = 0;
            for (SpectrumDescription d : metadata) {
                for (Precursor p : d.precursors()) {
                    windows += p.isolationWindow() == null ? 0 : 1;
                }
            }
            assertThat(new Snapshot(lines.size(), windows, Canonical.sha256(lines)))
                    .as("isolation windows of %s %s", set.label, fixture).isEqualTo(expected);
        }
    }

    /** The anchor windows of the snapshots in full. */
    @Test
    void anchorWindows() {
        try (MzPeakReader current = MzPeakReader.open(Fixtures.current(SMALL_UNPACKED));
             MzPeakReader legacy = MzPeakReader.open(Fixtures.legacy(SMALL_UNPACKED));
             MzPeakReader dia = MzPeakReader.open(Fixtures.current("diaPASEF.ref.mzpeak"))) {
            assertThat(windowLines(List.of(current.getMetadata(2).orElseThrow()))).containsExactly(
                    "2/0 (810.7894287109375|1.0|1.0|809.7894287109375|811.7894287109375)");
            assertThat(windowLines(List.of(legacy.getMetadata(5).orElseThrow()))).containsExactly(
                    "5/0 (558.8689575195312|1.0|1.0|557.8689575195312|559.8689575195312)");
            assertThat(windowLines(List.of(dia.getMetadata(1).orElseThrow()))).containsExactly(
                    "1/0 (813.0|13.0|13.0|800.0|826.0)");
        }
    }

    /** Every window of a current fixture holds the target and offsets of its FX-2 cells. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void everyCurrentWindowHoldsItsRawCells(String fixture) {
        GoldenMeta meta = GoldenMeta.load(fixture);
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            Entry precursors = Facet.SPECTRUM_PRECURSOR.entry(reader.manifest()).orElseThrow();
            Map<Long, List<Row>> cells = bySourceIndex(meta.rows(precursors.name()));
            int checked = 0;
            for (SpectrumDescription d : reader.metadata()) {
                List<Row> rows = cells.getOrDefault(d.index(), List.of());
                assertThat(d.precursors()).as("%s spectrum %d precursors", fixture, d.index()).hasSameSizeAs(rows);
                for (int k = 0; k < rows.size(); k++) {
                    String where = fixture + " spectrum " + d.index() + " precursor " + k;
                    IsolationWindow w = d.precursors().get(k).isolationWindow();
                    Row row = rows.get(k);
                    assertThat(row.get("isolation_window")).as(where).isNotNull();
                    assertThat(w).as(where).isNotNull();
                    assertThat(w.targetMz()).as(where + " target").isEqualTo(cell(row, precursors, TARGET));
                    assertThat(w.lowerOffset()).as(where + " lower offset").isEqualTo(cell(row, precursors, LOWER));
                    assertThat(w.upperOffset()).as(where + " upper offset").isEqualTo(cell(row, precursors, UPPER));
                    checked++;
                }
            }
            assertThat(checked).as("%s precursor rows", fixture).isEqualTo(meta.rows(precursors.name()).size());
        }
    }

    /** Legacy small and current small.unpacked hold the same windows, spectrum by spectrum. */
    @Test
    void legacyAndCurrentSmallHoldTheSameWindows() {
        try (MzPeakReader current = MzPeakReader.open(Fixtures.current(SMALL_UNPACKED));
             MzPeakReader legacy = MzPeakReader.open(Fixtures.legacy(SMALL_UNPACKED))) {
            assertThat(windowLines(current.metadata())).hasSize(34).isEqualTo(windowLines(legacy.metadata()));
        }
    }

    /** Current small.unpacked written in either metadata layout reads back with the same windows. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(MetadataLayout.class)
    void writtenWindowsKeepTheirTargetAndOffsets(MetadataLayout layout, @TempDir Path tmp) {
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current(SMALL_UNPACKED));
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeArchive(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, Options.defaults().withMetadataLayout(layout));
        List<SpectrumDescription> written = new ArrayList<>();
        for (Spectrum s : source.spectra) {
            written.add(s.description());
        }
        try (MzPeakReader back = MzPeakReader.open(out)) {
            assertThat(windowLines(back.metadata())).hasSize(34).isEqualTo(windowLines(written));
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** One line per precursor: {@code <spectrum index>/<position> }{@link #window}. */
    static List<String> windowLines(List<SpectrumDescription> metadata) {
        List<String> out = new ArrayList<>();
        for (SpectrumDescription d : metadata) {
            for (int k = 0; k < d.precursors().size(); k++) {
                out.add(d.index() + "/" + k + " " + window(d.precursors().get(k).isolationWindow()));
            }
        }
        return out;
    }

    /** {@code (target|lower offset|upper offset|lower bound|upper bound)}, or {@code null}. */
    static String window(IsolationWindow w) {
        if (w == null) {
            return "null";
        }
        return "(" + Canonical.num(w.targetMz()) + "|" + Canonical.num(w.lowerOffset()) + "|"
                + Canonical.num(w.upperOffset()) + "|" + Canonical.num(w.lowerBound()) + "|"
                + Canonical.num(w.upperBound()) + ")";
    }

    private static Object cell(Row row, Entry entry, String accession) {
        String path = entry.columnForAccession(accession).map(MzPeakManifest.ColumnMapping::path)
                .orElseThrow(() -> new AssertionError(entry.name() + " maps no column to " + accession));
        return row.get(path);
    }

    private static Map<Long, List<Row>> bySourceIndex(List<Row> rows) {
        Map<Long, List<Row>> out = new LinkedHashMap<>();
        for (Row row : rows) {
            out.computeIfAbsent((Long) row.get("source_index"), k -> new ArrayList<>()).add(row);
        }
        return out;
    }
}
