package org.mzpeak.io;

import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakReader.Options;
import org.mzpeak.io.MzPeakReader.SignalMode;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.DoubleRange;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.SignalSlice;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Supplier;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AP-3: range queries ({@link MzPeakReader#select}, {@link MzPeakReader#extractSignal},
 * {@link MzPeakReader#queryPeaks}) with a {@link SignalQuery}, returning one {@link SignalSlice} per selected spectrum
 * (design section 10).
 *
 * <p>Oracles: the spectrum metadata for the selection; {@link MzPeakReader#getSpectrum} in {@link SignalMode#PROFILE}
 * mode (data arrays) and {@link SignalMode#CENTROID} mode (stored-order peaks) for the slices, on every fixture but
 * diaPASEF.grid, whose grid decoding is in flux (RD-20); and the counts of the unit's acceptance: on small, time
 * [0.3, 0.4] selects spectra 30..38, whose stored points in m/z [800, 820] number 562 (563 up to 820.00002, the count
 * Rust's isclose edges give), and whose MS2 peaks in that m/z range number 96. A constructed signal member with
 * infinite m/z covers the non-finite case, which no fixture has. A 32 KiB row-group copy of legacy small.unpacked and
 * a {@link TestSources.Counting} source measure the bytes an index range reads.
 */
class SignalQueryTest {

    private static final String ROW_GROUP_SIZE = "mzpeak.writer.rowGroupSize";
    private static final String IM = "MS:1003006";
    private static final List<Long> SPECTRA_30_TO_38 = LongStream.rangeClosed(30, 38).boxed().toList();
    /** The query of acceptance (b) and (c). */
    private static final SignalQuery SMALL_WINDOW = SignalQuery.timeRange(0.3, 0.4).withMz(800, 820);

    @TempDir
    Path tmp;

    // ---- (a) selection by time ----------------------------------------------------------------------------------

    /** The current small dataset in its containers (ZIP, unpacked directory, in memory), and legacy small.unpacked. */
    static Stream<Named<Supplier<MzPeakReader>>> smallContainers() {
        return Stream.of(
                Named.of("current small.mzpeak (ZIP)", () -> MzPeakReader.open(Fixtures.current("small.mzpeak"))),
                Named.of("current small.unpacked.mzpeak (directory)",
                        () -> MzPeakReader.open(Fixtures.current("small.unpacked.mzpeak"))),
                Named.of("current small.mzpeak (in memory)",
                        () -> MzPeakReader.open(TestSources.memory(Fixtures.current("small.mzpeak")))),
                Named.of("current small.chunked.mzpeak", () -> MzPeakReader.open(
                        Fixtures.current("small.chunked.mzpeak"))),
                Named.of("current small.numpress.mzpeak", () -> MzPeakReader.open(
                        Fixtures.current("small.numpress.mzpeak"))),
                Named.of("legacy small.unpacked.mzpeak", () -> MzPeakReader.open(
                        Fixtures.legacy("small.unpacked.mzpeak"))));
    }

    @ParameterizedTest
    @MethodSource("smallContainers")
    void timeRangeSelectsSpectra30To38(Supplier<MzPeakReader> open) {
        try (MzPeakReader reader = open.get()) {
            List<SpectrumDescription> selected = reader.select(SignalQuery.timeRange(0.3, 0.4));
            assertThat(indices(selected)).isEqualTo(SPECTRA_30_TO_38);
            // exact: the selection is every spectrum whose retention time lies in the closed interval
            List<Long> expected = new ArrayList<>();
            for (SpectrumDescription d : reader.metadata()) {
                if (d.retentionTime() >= 0.3 && d.retentionTime() <= 0.4) {
                    expected.add(d.index());
                }
            }
            assertThat(indices(selected)).isEqualTo(expected);

            // one slice per selected spectrum, carrying its metadata
            for (List<SignalSlice> slices : List.of(reader.extractSignal(SignalQuery.timeRange(0.3, 0.4)).toList(),
                    reader.queryPeaks(SignalQuery.timeRange(0.3, 0.4)).toList())) {
                assertThat(slices).extracting(SignalSlice::index).isEqualTo(SPECTRA_30_TO_38);
                for (int i = 0; i < slices.size(); i++) {
                    assertThat(slices.get(i).time()).isEqualTo(selected.get(i).retentionTime());
                    assertThat(slices.get(i).msLevel()).isEqualTo(selected.get(i).msLevel());
                }
            }
        }
    }

    @Test
    void selectIsInIndexOrderAndAllSelectsEverySpectrum() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("has_uv.mzpeak"))) {
            List<SpectrumDescription> all = reader.select(SignalQuery.all());
            assertThat(all).hasSize(reader.size()).containsExactlyInAnyOrderElementsOf(reader.metadata());
            assertThat(indices(all)).isSorted();
            assertThat(reader.select(SignalQuery.timeRange(1e9, Double.POSITIVE_INFINITY))).isEmpty();
            assertThat(reader.select(SignalQuery.indexRange(10_000, Long.MAX_VALUE))).isEmpty();
            assertThatThrownBy(() -> all.add(all.get(0))).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void aNonFiniteRetentionTimeNeverMatchesATimeRange() {
        SignalQuery everyTime = SignalQuery.timeRange(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        assertThat(everyTime.selects(description(0, 1, 0.5))).isTrue();
        assertThat(everyTime.selects(description(0, 1, Double.NaN))).isFalse();
        assertThat(everyTime.selects(description(0, 1, Double.POSITIVE_INFINITY))).isFalse();
        assertThat(everyTime.selects(description(0, 1, Double.NEGATIVE_INFINITY))).isFalse();
        // without a time range the retention time plays no part
        assertThat(SignalQuery.all().selects(description(0, 1, Double.NaN))).isTrue();
        assertThat(SignalQuery.indexRange(0, 0).selects(description(0, 1, Double.NaN))).isTrue();
    }

    @Test
    void rangesAreClosed() {
        assertThat(SignalQuery.timeRange(0.5, 0.5).selects(description(0, 1, 0.5))).isTrue();
        assertThat(SignalQuery.timeRange(0.5, 0.6).selects(description(0, 1, Math.nextDown(0.5)))).isFalse();
        assertThat(SignalQuery.timeRange(0.4, 0.5).selects(description(0, 1, Math.nextUp(0.5)))).isFalse();
        assertThat(SignalQuery.indexRange(3, 5).selects(description(3, 1, 0))).isTrue();
        assertThat(SignalQuery.indexRange(3, 5).selects(description(5, 1, 0))).isTrue();
        assertThat(SignalQuery.indexRange(3, 5).selects(description(2, 1, 0))).isFalse();
        assertThat(SignalQuery.indexRange(3, 5).selects(description(6, 1, 0))).isFalse();
        assertThat(SignalQuery.all().withMsLevels(2, 3).selects(description(0, 2, 0))).isTrue();
        assertThat(SignalQuery.all().withMsLevels(2, 3).selects(description(0, 3, 0))).isTrue();
        assertThat(SignalQuery.all().withMsLevels(2, 3).selects(description(0, 1, 0))).isFalse();
        assertThat(SignalQuery.all().withMsLevels(2, 3).selects(description(0, 4, 0))).isFalse();
        // a spectrum without an MS level (0) only passes when there is no MS-level filter
        assertThat(SignalQuery.all().selects(description(0, 0, 0))).isTrue();
        assertThat(SignalQuery.all().withMsLevels(1, Integer.MAX_VALUE).selects(description(0, 0, 0))).isFalse();

        DoubleRange range = new DoubleRange(800, 820);
        assertThat(range.contains(800)).isTrue();
        assertThat(range.contains(820)).isTrue();
        assertThat(range.contains(Math.nextDown(800.0))).isFalse();
        assertThat(range.contains(Math.nextUp(820.0))).isFalse();
        assertThat(range.contains(Double.NaN)).isFalse();
        DoubleRange open = new DoubleRange(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        assertThat(open.contains(Double.MAX_VALUE)).isTrue();
        assertThat(open.contains(Double.POSITIVE_INFINITY)).isTrue();
        assertThat(open.contains(Double.NaN)).isFalse();
    }

    // ---- (b) exact m/z intervals --------------------------------------------------------------------------------

    static Stream<Path> nullMarkedSmall() {
        return Stream.of(Fixtures.current("small.mzpeak"), Fixtures.current("small.chunked.mzpeak"),
                Fixtures.current("small.unpacked.mzpeak"), Fixtures.legacy("small.mzpeak"),
                Fixtures.legacy("small.chunked.mzpeak"), Fixtures.legacy("small.unpacked.mzpeak"));
    }

    @ParameterizedTest
    @MethodSource("nullMarkedSmall")
    void storedPointsInTheMzWindowAreCountedExactly(Path fixture) {
        try (MzPeakReader reader = MzPeakReader.open(fixture, Options.defaults().withReconstructProfile(false))) {
            List<SignalSlice> slices = reader.extractSignal(SMALL_WINDOW).toList();
            assertThat(slices).extracting(SignalSlice::index).isEqualTo(SPECTRA_30_TO_38);
            assertThat(points(slices)).isEqualTo(562);
            for (SignalSlice s : slices) {
                assertAllIn(s.mz(), 800, 820, "spectrum " + s.index());
                assertThat(s.ionMobility()).isNull();
            }
            // one stored point lies in (820, 820.00002]; Rust's isclose edges count it at 820
            assertThat(points(reader.extractSignal(SignalQuery.timeRange(0.3, 0.4).withMz(800, 820.00002))))
                    .isEqualTo(563);
        }
    }

    // ---- (c) peaks with an MS-level filter ----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"small.mzpeak", "small.chunked.mzpeak"})
    void ms2PeaksInTheMzWindow(String name) {
        for (boolean reconstruct : new boolean[] {true, false}) {
            try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(name),
                    Options.defaults().withReconstructProfile(reconstruct))) {
                List<SignalSlice> slices = reader.queryPeaks(SMALL_WINDOW.withMsLevels(2, 10)).toList();
                assertThat(slices).isNotEmpty();
                assertThat(slices).extracting(SignalSlice::msLevel).allSatisfy(l -> assertThat(l).isBetween(2, 10));
                assertThat(SPECTRA_30_TO_38).containsAll(slices.stream().map(SignalSlice::index).toList());
                assertThat(points(slices)).as("%s reconstructProfile=%s", name, reconstruct).isEqualTo(96);
                for (SignalSlice s : slices) {
                    assertAllIn(s.mz(), 800, 820, "spectrum " + s.index());
                }
            }
        }
    }

    // ---- (d) slices against getSpectrum in PROFILE and CENTROID mode --------------------------------------------

    /** Every fixture of both sets but diaPASEF.grid, for both reconstructProfile values. */
    static Stream<Arguments> fixturesAndReconstruct() {
        List<Path> fixtures = new ArrayList<>();
        Fixtures.CURRENT_NAMES.stream().filter(n -> !n.equals("diaPASEF.grid.mzpeak")).map(Fixtures::current)
                .forEach(fixtures::add);
        Fixtures.LEGACY_NAMES.stream().map(Fixtures::legacy).forEach(fixtures::add);
        return fixtures.stream().flatMap(f -> Stream.of(Arguments.of(f, true), Arguments.of(f, false)));
    }

    private static final List<SignalQuery> QUERIES = List.of(
            SignalQuery.all(),
            SignalQuery.all().withMz(400, 900),
            SignalQuery.all().withMz(400, 900).withIonMobility(0.8, 0.9),
            SignalQuery.all().withIonMobility(1.0, 1.2).withMsLevels(2, 2),
            SMALL_WINDOW,
            SignalQuery.indexRange(2, 6).withMz(500, 1000));

    @ParameterizedTest(name = "{0} reconstructProfile={1}")
    @MethodSource("fixturesAndReconstruct")
    void slicesAreTheInRangePointsOfTheSpectrum(Path fixture, boolean reconstruct) {
        Options options = Options.defaults().withReconstructProfile(reconstruct);
        Map<Long, Spectrum> profile = spectra(fixture, options.withSignalMode(SignalMode.PROFILE));
        Map<Long, Spectrum> centroid = spectra(fixture, options.withSignalMode(SignalMode.CENTROID));
        try (MzPeakReader reader = MzPeakReader.open(fixture, options)) {
            for (SignalQuery query : QUERIES) {
                List<Long> selected = indices(reader.select(query));
                List<SignalSlice> signal = reader.extractSignal(query).toList();
                List<SignalSlice> peaks = reader.queryPeaks(query).toList();
                assertThat(signal).extracting(SignalSlice::index).isEqualTo(selected);
                assertThat(peaks).extracting(SignalSlice::index).isEqualTo(selected);
                for (int i = 0; i < selected.size(); i++) {
                    String at = fixture + " " + query + " spectrum " + selected.get(i);
                    assertSlice(signal.get(i), inRange(profile.get(selected.get(i)), query, false), at + " signal");
                    assertSlice(peaks.get(i), inRange(centroid.get(selected.get(i)), query, true), at + " peaks");
                }
            }
        }
    }

    // ---- (e) ion mobility ---------------------------------------------------------------------------------------

    @Test
    void ionMobilityFilterOnDiaPasefRef() {
        Path fixture = Fixtures.current("diaPASEF.ref.mzpeak");
        try (MzPeakReader both = MzPeakReader.open(fixture);
             MzPeakReader reader = MzPeakReader.open(fixture)) {
            List<SignalSlice> slices = reader.queryPeaks(SignalQuery.all().withIonMobility(0.8, 0.9)).toList();
            assertThat(slices).hasSize(9);
            long kept = 0;
            long total = 0;
            for (SignalSlice s : slices) {
                Spectrum spectrum = both.getSpectrum(s.index()).orElseThrow();
                double[] im = spectrum.peakArrays().get(IM);
                double[] storedMz = spectrum.mz();
                assertThat(im).as("spectrum %d", s.index()).isNotNull();
                assertThat(s.ionMobility()).as("spectrum %d", s.index()).isNotNull();
                assertAllIn(s.ionMobility(), 0.8, 0.9, "spectrum " + s.index());
                int expected = 0;
                for (int i = 0; i < im.length; i++) {
                    if (im[i] >= 0.8 && im[i] <= 0.9 && Double.isFinite(storedMz[i])) {
                        expected++;
                    }
                }
                assertThat(s.size()).as("spectrum %d", s.index()).isEqualTo(expected);
                kept += s.size();
                total += im.length;
            }
            // the filter keeps some peaks and drops others
            assertThat(kept).isPositive().isLessThan(total);

            // the data arrays member holds nothing for diaPASEF.ref: every slice is empty
            List<SignalSlice> signal = reader.extractSignal(SignalQuery.all().withIonMobility(0.8, 0.9)).toList();
            assertThat(signal).hasSize(9).allSatisfy(s -> {
                assertThat(s.size()).isZero();
                assertThat(s.ionMobility()).isNull();
            });
        }
    }

    @Test
    void ionMobilityFilterHasNoEffectWithoutIonMobility() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            for (SignalQuery query : List.of(SignalQuery.all(), SMALL_WINDOW)) {
                SignalQuery withIm = query.withIonMobility(0.8, 0.9);
                assertSameSlices(reader.extractSignal(withIm).toList(), reader.extractSignal(query).toList());
                assertSameSlices(reader.queryPeaks(withIm).toList(), reader.queryPeaks(query).toList());
                assertThat(reader.extractSignal(withIm)).allSatisfy(s -> assertThat(s.ionMobility()).isNull());
            }
        }
    }

    // ---- (f) MS-level filter with an index range ----------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("smallContainers")
    void indexRangeWithMsLevel1GivesTheMs1Spectra(Supplier<MzPeakReader> open) {
        try (MzPeakReader reader = open.get()) {
            List<Long> ms1 = new ArrayList<>();
            List<Long> other = new ArrayList<>();
            for (SpectrumDescription d : reader.metadata()) {
                if (d.index() >= 40 && d.index() <= 47) {
                    (d.msLevel() == 1 ? ms1 : other).add(d.index());
                }
            }
            assertThat(ms1).containsExactly(41L, 42L);
            assertThat(other).isNotEmpty();

            SignalQuery query = SignalQuery.indexRange(40, 47).withMsLevels(1, 1);
            assertThat(indices(reader.select(query))).isEqualTo(ms1);
            assertThat(reader.extractSignal(query)).extracting(SignalSlice::index).isEqualTo(ms1);
            assertThat(reader.queryPeaks(query)).extracting(SignalSlice::index).isEqualTo(ms1);
            assertThat(reader.select(SignalQuery.indexRange(40, 47))).extracting(SpectrumDescription::index)
                    .containsExactly(40L, 41L, 42L, 43L, 44L, 45L, 46L, 47L);
        }
    }

    // ---- (g) an index range reads a fraction of the signal ------------------------------------------------------

    /**
     * The copy has 179 data and 19 peaks row groups. When recorded, a full iteration read 3,292,968 bytes of the two
     * signal members, and extractSignal plus queryPeaks of spectra 40..47 read 669,874.
     */
    @Test
    void anIndexRangeReadsLessThanHalfOfAFullIteration() throws Exception {
        Path source = Fixtures.legacy("small.unpacked.mzpeak");
        List<Spectrum> spectra = new ArrayList<>();
        List<Chromatogram> chromatograms;
        try (MzPeakReader r = MzPeakReader.open(source)) {
            for (Spectrum s : r) {
                spectra.add(s);
            }
            chromatograms = new ArrayList<>(r.chromatograms());
        }
        Path out = tmp.resolve("multirg.mzpeak");
        String previous = System.getProperty(ROW_GROUP_SIZE);
        System.setProperty(ROW_GROUP_SIZE, "32768");
        try {
            MzPeakWriter.writeDirectory(out, spectra, chromatograms);
        } finally {
            StreamingReaderTest.restoreProperty(ROW_GROUP_SIZE, previous);
        }
        assertThat(System.getProperty(ROW_GROUP_SIZE)).isEqualTo(previous);

        SignalQuery query = SignalQuery.indexRange(40, 47);
        long full;
        Map<String, Long> fullByMember;
        TestSources.Counting counting = TestSources.counting(MzPeakSource.open(out));
        try (MzPeakReader reader = MzPeakReader.open(counting)) {
            assertThat(rowGroups(reader, out, DataKind.DATA_ARRAYS)).isGreaterThan(20);
            assertThat(rowGroups(reader, out, DataKind.PEAKS)).isGreaterThan(5);
            long atOpen = counting.totalBytesRead();
            for (Spectrum ignored : reader) {
                // read every spectrum
            }
            full = counting.totalBytesRead() - atOpen;
            fullByMember = counting.bytesRead();
        }

        List<SignalSlice> signal;
        List<SignalSlice> peaks;
        long ranged;
        counting = TestSources.counting(MzPeakSource.open(out));
        try (MzPeakReader reader = MzPeakReader.open(counting)) {
            long atOpen = counting.totalBytesRead();
            signal = reader.extractSignal(query).toList();
            peaks = reader.queryPeaks(query).toList();
            ranged = counting.totalBytesRead() - atOpen;
            Map<String, Long> byMember = counting.bytesRead();
            for (DataKind kind : List.of(DataKind.DATA_ARRAYS, DataKind.PEAKS)) {
                String member = member(reader, kind);
                assertThat(byMember.get(member)).as(member).isPositive()
                        .isLessThan(fullByMember.get(member) / 2);
            }
        }
        assertThat(ranged).as("bytes read for %s against a full iteration (%d)", query, full)
                .isPositive().isLessThan(full / 2);

        // and the slices are the spectra that span the row groups, as getSpectrum reads them
        Map<Long, Spectrum> profile = spectra(out, Options.defaults().withSignalMode(SignalMode.PROFILE));
        Map<Long, Spectrum> centroid = spectra(out, Options.defaults().withSignalMode(SignalMode.CENTROID));
        assertThat(signal).extracting(SignalSlice::index).containsExactly(40L, 41L, 42L, 43L, 44L, 45L, 46L, 47L);
        assertThat(peaks).extracting(SignalSlice::index).containsExactly(40L, 41L, 42L, 43L, 44L, 45L, 46L, 47L);
        for (int i = 0; i < signal.size(); i++) {
            long index = signal.get(i).index();
            assertSlice(signal.get(i), inRange(profile.get(index), query, false), "signal " + index);
            assertSlice(peaks.get(i), inRange(centroid.get(index), query, true), "peaks " + index);
        }
        assertThat(points(signal.stream())).isPositive();
        assertThat(points(peaks.stream())).isPositive();
    }

    // ---- step 5: missing data, copies, laziness, close ----------------------------------------------------------

    @Test
    void missingDataGivesAnEmptySlice() {
        try (MzPeakReader chunked = MzPeakReader.open(Fixtures.current("small.chunked.mzpeak"));
             MzPeakReader unpacked = MzPeakReader.open(Fixtures.current("small.unpacked.mzpeak"))) {
            // spectrum 5 is stored only as centroids, spectrum 0 of small.unpacked only as profile
            SignalSlice noData = chunked.extractSignal(SignalQuery.indexRange(5, 5)).findFirst().orElseThrow();
            assertThat(noData.index()).isEqualTo(5);
            assertThat(noData.mz()).isEmpty();
            assertThat(noData.intensity()).isEmpty();
            assertThat(noData.ionMobility()).isNull();
            assertThat(chunked.queryPeaks(SignalQuery.indexRange(5, 5)).findFirst().orElseThrow().size())
                    .isEqualTo(650);

            SignalSlice noPeaks = unpacked.queryPeaks(SignalQuery.indexRange(0, 0)).findFirst().orElseThrow();
            assertThat(noPeaks.index()).isZero();
            assertThat(noPeaks.size()).isZero();
            assertThat(unpacked.extractSignal(SignalQuery.indexRange(0, 0)).findFirst().orElseThrow().size())
                    .isEqualTo(13589);
            // one slice per selected spectrum, empty ones included
            assertThat(unpacked.queryPeaks(SignalQuery.all()).toList()).hasSize(unpacked.size());
        }
    }

    /**
     * No fixture stores a non-finite m/z, so legacy small.unpacked gets a constructed signal member, used as both its
     * data arrays and its peaks member: spectrum 2 with the m/z 100.5, +Infinity, 300.25 and -Infinity. queryPeaks
     * drops the two infinite peaks whatever the filter, as {@link Spectrum#peaks()} does; extractSignal keeps them
     * unless the m/z filter excludes them.
     */
    @ParameterizedTest(name = "reconstructProfile={0}")
    @ValueSource(booleans = {true, false})
    void queryPeaksDropsNonFiniteMz(boolean reconstruct) throws Exception {
        Path dataset = Fixtures.legacy("small.unpacked.mzpeak");
        TestSources.Memory fixture = TestSources.memory(dataset);
        String data;
        String peaksMember;
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            data = member(reader, DataKind.DATA_ARRAYS);
            peaksMember = member(reader, DataKind.PEAKS);
        }
        byte[] member = infiniteMzMember();
        Map<String, byte[]> members = new HashMap<>();
        for (String name : fixture.names()) {
            members.put(name, name.equals(data) || name.equals(peaksMember) ? member : fixture.member(name));
        }
        double inf = Double.POSITIVE_INFINITY;
        try (MzPeakReader reader = MzPeakReader.open(TestSources.memory("infinite m/z", members),
                Options.defaults().withReconstructProfile(reconstruct))) {
            Spectrum spectrum = reader.getSpectrum(2).orElseThrow();
            assertThat(spectrum.mz()).containsExactly(100.5, inf, 300.25, -inf);
            assertThat(spectrum.peaks()).hasSize(2);

            for (SignalQuery query : List.of(SignalQuery.indexRange(2, 2),
                    SignalQuery.indexRange(2, 2).withMz(-inf, inf))) {
                SignalSlice peaks = reader.queryPeaks(query).findFirst().orElseThrow();
                assertThat(peaks.mz()).as("%s", query).containsExactly(100.5, 300.25);
                assertThat(peaks.intensity()).as("%s", query).containsExactly(1.0, 3.0);
                SignalSlice signal = reader.extractSignal(query).findFirst().orElseThrow();
                assertThat(signal.mz()).as("%s", query).containsExactly(100.5, inf, 300.25, -inf);
                assertThat(signal.intensity()).as("%s", query).containsExactly(1.0, 2.0, 3.0, 4.0);
            }
            SignalSlice finite = reader.extractSignal(SignalQuery.indexRange(2, 2).withMz(0, 1000)).findFirst()
                    .orElseThrow();
            assertThat(finite.mz()).containsExactly(100.5, 300.25);
            assertThat(finite.intensity()).containsExactly(1.0, 3.0);
        }
    }

    @Test
    void unfilteredArraysAreNotCopied() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"));
             MzPeakReader dia = MzPeakReader.open(Fixtures.current("diaPASEF.ref.mzpeak"))) {
            Spectrum s0 = reader.getSpectrum(0).orElseThrow();
            SignalSlice whole = reader.extractSignal(SignalQuery.indexRange(0, 0)).findFirst().orElseThrow();
            assertThat(whole.mz()).isSameAs(s0.mz());
            assertThat(whole.intensity()).isSameAs(s0.intensity());
            // a filter that keeps every point does not copy either
            SignalSlice kept = reader.extractSignal(SignalQuery.indexRange(0, 0).withMz(0, 1e9)).findFirst()
                    .orElseThrow();
            assertThat(kept.mz()).isSameAs(s0.mz());
            // a filter that drops points gives new arrays
            SignalSlice narrowed = reader.extractSignal(SignalQuery.indexRange(0, 0).withMz(800, 820)).findFirst()
                    .orElseThrow();
            assertThat(narrowed.size()).isPositive().isLessThan(s0.mz().length);
            assertThat(narrowed.mz()).isNotSameAs(s0.mz());

            Spectrum d0 = dia.getSpectrum(0).orElseThrow();
            SignalSlice peaks = dia.queryPeaks(SignalQuery.indexRange(0, 0)).findFirst().orElseThrow();
            assertThat(peaks.mz()).isSameAs(d0.mz());
            assertThat(peaks.ionMobility()).isSameAs(d0.peakArrays().get(IM));
        }
    }

    @Test
    void streamsAreLazyAndReadOnlyTheirMember() {
        Path fixture = Fixtures.current("small.chunked.mzpeak");
        TestSources.Recording source = TestSources.recording(MzPeakSource.open(fixture));
        try (MzPeakReader reader = MzPeakReader.open(source)) {
            String data = member(reader, DataKind.DATA_ARRAYS);
            String peaksMember = member(reader, DataKind.PEAKS);
            List<String> atOpen = source.opened();
            Stream<SignalSlice> peaks = reader.queryPeaks(SignalQuery.all());
            Stream<SignalSlice> signal = reader.extractSignal(SignalQuery.all());
            assertThat(peaks.isParallel()).isFalse();
            assertThat(signal.isParallel()).isFalse();
            assertThat(source.opened()).isEqualTo(atOpen);

            assertThat(peaks.toList()).hasSize(reader.size());
            assertThat(source.opened()).contains(peaksMember).doesNotContain(data);
            assertThat(signal.toList()).hasSize(reader.size());
            assertThat(source.opened()).contains(data);
        }
        assertThat(source.closeCount()).isEqualTo(1);
    }

    @Test
    void aSliceRequestedAfterCloseThrows() {
        MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"));
        Iterator<SignalSlice> signal = reader.extractSignal(SignalQuery.all()).iterator();
        Stream<SignalSlice> peaks = reader.queryPeaks(SignalQuery.all());
        assertThat(signal.next().index()).isZero();
        reader.close();
        assertThatThrownBy(signal::next).isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
        assertThatThrownBy(peaks::toList).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reader.extractSignal(SignalQuery.all())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reader.queryPeaks(SignalQuery.all())).isInstanceOf(IllegalStateException.class);
    }

    // ---- (h) arguments ------------------------------------------------------------------------------------------

    @Test
    void invalidArgumentsThrow() {
        assertThatThrownBy(() -> new DoubleRange(Double.NaN, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DoubleRange(0, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DoubleRange(2, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignalQuery.timeRange(Double.NaN, 0.4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignalQuery.timeRange(0.4, 0.3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignalQuery.indexRange(-1, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignalQuery.indexRange(5, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignalQuery.all().withMz(820, 800)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignalQuery.all().withMz(Double.NaN, 800))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignalQuery.all().withIonMobility(0.9, 0.8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignalQuery.all().withIonMobility(0.8, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignalQuery.all().withMsLevels(0, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SignalQuery.all().withMsLevels(3, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SignalSlice(0, 0, 1, new double[2], new double[1], null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SignalSlice(0, 0, 1, new double[2], new double[2], new double[3]))
                .isInstanceOf(IllegalArgumentException.class);

        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            assertThatThrownBy(() -> reader.select(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> reader.extractSignal(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> reader.queryPeaks(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void validEdgeArgumentsAreAccepted() {
        assertThat(new DoubleRange(1, 1).contains(1)).isTrue();
        assertThat(SignalQuery.indexRange(0, 0).firstIndex()).hasValue(0);
        assertThat(SignalQuery.indexRange(7, Long.MAX_VALUE).lastIndex()).hasValue(Long.MAX_VALUE);
        assertThat(SignalQuery.all().withMsLevels(1, 1).maxMsLevel()).hasValue(1);
        assertThat(SignalQuery.timeRange(Double.NEGATIVE_INFINITY, 0).time())
                .contains(new DoubleRange(Double.NEGATIVE_INFINITY, 0));

        SignalSlice empty = new SignalSlice(3, 0.5, 2, null, null, null);
        assertThat(empty.mz()).isEmpty();
        assertThat(empty.intensity()).isEmpty();
        assertThat(empty.ionMobility()).isNull();
        assertThat(empty.size()).isZero();
        double[] mz = {1, 2};
        SignalSlice slice = new SignalSlice(3, 0.5, 2, mz, new double[] {3, 4}, new double[] {0.5, 0.6});
        assertThat(slice.mz()).isSameAs(mz);
        assertThat(slice.size()).isEqualTo(2);
    }

    // ---- SignalQuery as a value ---------------------------------------------------------------------------------

    @Test
    void queriesAreImmutableValues() {
        SignalQuery all = SignalQuery.all();
        assertThat(all.time()).isEmpty();
        assertThat(all.firstIndex()).isEmpty();
        assertThat(all.lastIndex()).isEmpty();
        assertThat(all.mz()).isEmpty();
        assertThat(all.ionMobility()).isEmpty();
        assertThat(all.minMsLevel()).isEmpty();
        assertThat(all.maxMsLevel()).isEmpty();

        SignalQuery query = SignalQuery.timeRange(0.3, 0.4).withMz(1, 2).withMz(800, 820).withIonMobility(0.8, 0.9)
                .withMsLevels(2, 10);
        assertThat(query.time()).contains(new DoubleRange(0.3, 0.4));
        assertThat(query.firstIndex()).isEqualTo(OptionalLong.empty());
        assertThat(query.mz()).contains(new DoubleRange(800, 820));
        assertThat(query.ionMobility()).contains(new DoubleRange(0.8, 0.9));
        assertThat(query.minMsLevel()).isEqualTo(OptionalInt.of(2));
        assertThat(query.maxMsLevel()).isEqualTo(OptionalInt.of(10));

        SignalQuery byIndex = SignalQuery.indexRange(40, 47).withMsLevels(1, 1);
        assertThat(byIndex.firstIndex()).hasValue(40);
        assertThat(byIndex.lastIndex()).hasValue(47);
        assertThat(byIndex.time()).isEmpty();

        SignalQuery narrowed = SMALL_WINDOW.withMsLevels(2, 10);
        assertThat(narrowed).isNotSameAs(SMALL_WINDOW).isNotEqualTo(SMALL_WINDOW);
        assertThat(SMALL_WINDOW.minMsLevel()).isEmpty();
        assertThat(narrowed).isEqualTo(SignalQuery.timeRange(0.3, 0.4).withMsLevels(2, 10).withMz(800, 820))
                .hasSameHashCodeAs(SignalQuery.timeRange(0.3, 0.4).withMsLevels(2, 10).withMz(800, 820));
        assertThat(SignalQuery.indexRange(0, 1)).isNotEqualTo(SignalQuery.all());
        assertThat(narrowed.toString()).contains("time=[0.3, 0.4]").contains("msLevels=[2, 10]")
                .contains("mz=[800.0, 820.0]");
        assertThat(byIndex.toString()).contains("index=[40, 47]");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static List<Long> indices(List<SpectrumDescription> descriptions) {
        return descriptions.stream().map(SpectrumDescription::index).toList();
    }

    private static long points(List<SignalSlice> slices) {
        return points(slices.stream());
    }

    private static long points(Stream<SignalSlice> slices) {
        return slices.mapToLong(SignalSlice::size).sum();
    }

    private static SpectrumDescription description(long index, int msLevel, double time) {
        return new SpectrumDescription(index, null, msLevel, time, Polarity.UNKNOWN, SignalContinuity.UNKNOWN, 0, 0,
                null, null, null);
    }

    /** Every spectrum of {@code fixture} read with {@code options}, by index. */
    private static Map<Long, Spectrum> spectra(Path fixture, Options options) {
        Map<Long, Spectrum> out = new HashMap<>();
        try (MzPeakReader reader = MzPeakReader.open(fixture, options)) {
            for (Spectrum s : reader) {
                out.put(s.index(), s);
            }
        }
        return out;
    }

    /**
     * The points of {@code s} ({@link Spectrum#mz()}, {@link Spectrum#intensity()}, {@link Spectrum#ionMobility()})
     * that pass the m/z filter of {@code query}, and its ion-mobility filter when {@code s} has ion mobility; with
     * {@code finiteOnly}, only those with a finite m/z.
     */
    private static SignalSlice inRange(Spectrum s, SignalQuery query, boolean finiteOnly) {
        double[] mz = s.mz();
        double[] intensity = s.intensity();
        double[] im = s.ionMobility().orElse(null);
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < mz.length; i++) {
            double m = mz[i];
            boolean pass = (!finiteOnly || Double.isFinite(m))
                    && query.mz().map(r -> m >= r.low() && m <= r.high()).orElse(true);
            if (pass && im != null && query.ionMobility().isPresent()) {
                DoubleRange r = query.ionMobility().get();
                pass = im[i] >= r.low() && im[i] <= r.high();
            }
            if (pass) {
                kept.add(i);
            }
        }
        double[] keptMz = kept.stream().mapToDouble(i -> mz[i]).toArray();
        double[] keptIntensity = kept.stream().mapToDouble(i -> intensity[i]).toArray();
        double[] keptIm = im == null ? null : kept.stream().mapToDouble(i -> im[i]).toArray();
        return new SignalSlice(s.index(), s.description().retentionTime(), s.msLevel(), keptMz, keptIntensity,
                keptIm);
    }

    /** Every value of {@code values} lies in {@code [low, high]}. */
    private static void assertAllIn(double[] values, double low, double high, String at) {
        assertThat(Arrays.stream(values).filter(v -> !(v >= low && v <= high)).toArray())
                .as("%s: values outside [%s, %s]", at, low, high).isEmpty();
    }

    private static void assertSlice(SignalSlice actual, SignalSlice expected, String at) {
        assertThat(actual.index()).as(at).isEqualTo(expected.index());
        assertThat(actual.time()).as(at).isEqualTo(expected.time());
        assertThat(actual.msLevel()).as(at).isEqualTo(expected.msLevel());
        assertThat(actual.mz()).as(at + " mz").isEqualTo(expected.mz());
        assertThat(actual.intensity()).as(at + " intensity").isEqualTo(expected.intensity());
        if (expected.ionMobility() == null) {
            assertThat(actual.ionMobility()).as(at + " ionMobility").isNull();
        } else {
            assertThat(actual.ionMobility()).as(at + " ionMobility").isEqualTo(expected.ionMobility());
        }
    }

    private static void assertSameSlices(List<SignalSlice> actual, List<SignalSlice> expected) {
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            assertSlice(actual.get(i), expected.get(i), "slice " + i);
        }
    }

    /** The name of the spectrum member of {@code kind}, found by its manifest role. */
    private static String member(MzPeakReader reader, DataKind kind) {
        return reader.manifest().find(EntityType.SPECTRUM, kind).orElseThrow().name();
    }

    /**
     * A point-layout spectrum signal member with an array index: spectrum 2 with the m/z 100.5, +Infinity, 300.25,
     * -Infinity and the intensities 1 to 4.
     */
    private byte[] infiniteMzMember() throws Exception {
        MessageType schema = MessageTypeParser.parseMessageType("""
                message m {
                  optional group point {
                    optional int64 spectrum_index (INTEGER(64,false));
                    optional double mz;
                    optional float intensity;
                  }
                }""");
        String index = "{\"prefix\": \"point\", \"entries\": ["
                + "{\"path\": \"point.spectrum_index\", \"buffer_format\": \"index\", \"array_type\": \"MS:1003870\","
                + " \"sorting_rank\": 0, \"context\": \"spectrum\"}, "
                + "{\"path\": \"point.mz\", \"buffer_format\": \"point\", \"array_type\": \"MS:1000514\","
                + " \"sorting_rank\": 1, \"context\": \"spectrum\"}, "
                + "{\"path\": \"point.intensity\", \"buffer_format\": \"point\", \"array_type\": \"MS:1000515\","
                + " \"sorting_rank\": null, \"context\": \"spectrum\"}]}";
        double[] mz = {100.5, Double.POSITIVE_INFINITY, 300.25, Double.NEGATIVE_INFINITY};
        Path file = tmp.resolve("infinite-mz.parquet");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withExtraMetaData(Map.of(SignalEntity.SPECTRUM.arrayIndexKey(), index))
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .build()) {
            for (int i = 0; i < mz.length; i++) {
                Group record = new SimpleGroupFactory(schema).newGroup();
                Group point = record.addGroup("point");
                point.add("spectrum_index", 2L);
                point.add("mz", mz[i]);
                point.add("intensity", (float) (i + 1));
                writer.write(record);
            }
        }
        return Files.readAllBytes(file);
    }

    private static int rowGroups(MzPeakReader reader, Path dataset, DataKind kind) throws Exception {
        try (ParquetFileReader pfr = ParquetFileReader.open(
                new LocalInputFile(dataset.resolve(member(reader, kind))), ParquetGroups.readOptions())) {
            return pfr.getRowGroups().size();
        }
    }
}
