package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.SignalPlan.Column;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RD-14: additional signal arrays on {@link Spectrum}. {@link Spectrum#arrays()} is parallel to {@link Spectrum#mz()},
 * also when the m/z come from the peaks member, and {@link Spectrum#peakArrays()} to the stored peak list.
 *
 * <ul>
 *   <li>diaPASEF.ref carries the per-peak mean inverse reduced ion mobility (MS:1003006) in its peaks member; it is
 *       checked against the HEAD Python golden: the X lines of every spectrum and peak list, and the D line of
 *       spectrum 0 (205,921 points) bit for bit.
 *   <li>The other current fixtures that read, and the legacy set, have no extra arrays; small.chunked's chunk
 *       secondary intensity is an intensity, not an extra.
 *   <li>The {@link Spectrum} constructor copies the maps, shares the arrays and checks their lengths.
 *   <li>{@link NullFill} keeps the extras parallel to the m/z for both {@code reconstructProfile} values.
 *   <li>Constructed members, written with parquet-java into a {@link TempDir} from the first peaks of diaPASEF.ref
 *       spectrum 2, show that a malformed extra is dropped with one WARNING while m/z and intensity still read, and
 *       that two columns of one array type are both exposed.
 * </ul>
 */
class ExtraArraysTest {

    private static final String IM = "MS:1003006";
    private static final String DIA_REF = "diaPASEF.ref.mzpeak";
    private static final String INDEX_KEY = SignalEntity.SPECTRUM.arrayIndexKey();

    @TempDir
    Path tmp;

    private int written;

    // ---- (a), (b) diaPASEF.ref against the golden ---------------------------------------------------------------

    @ParameterizedTest(name = "reconstructProfile={0}")
    @ValueSource(booleans = {true, false})
    void diaPasefRefIonMobilityMatchesTheGolden(boolean reconstructProfile) {
        Golden golden = Golden.load(DIA_REF);
        List<String> mismatches = new ArrayList<>();
        int xLines = 0;
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(DIA_REF), reconstructProfile)) {
            assertThat(reader.size()).isEqualTo(9);
            for (Spectrum s : reader) {
                String key = Long.toString(s.index());
                assertThat(s.peakArrays().keySet()).as("spectrum %s peak arrays", key).containsExactly(IM);
                assertThat(s.peakArrays().get(IM)).hasSize(s.peaks().size());

                double[] mobility = s.arrays().get(IM);
                mismatches.addAll(Golden.Comparator.compare(golden.line(Golden.Kind.X, "S:" + key, IM).orElseThrow(),
                        s.mz(), mobility, Golden.Tolerance.DEFAULT));
                xLines++;
                // P line pairs: each peak with the value at its stored position, CentroidPeak.index()
                double[] peakMz = new double[s.peaks().size()];
                double[] peakMobility = new double[s.peaks().size()];
                for (int i = 0; i < peakMz.length; i++) {
                    CentroidPeak p = s.peaks().get(i);
                    peakMz[i] = p.mz();
                    peakMobility[i] = s.peakArrays().get(IM)[p.index()];
                }
                mismatches.addAll(Golden.Comparator.compare(golden.line(Golden.Kind.X, "P:" + key, IM).orElseThrow(),
                        peakMz, peakMobility, Golden.Tolerance.DEFAULT));
                xLines++;
            }

            Spectrum zero = reader.getSpectrum(0).orElseThrow();
            assertThat(Arrays.stream(zero.ionMobility().orElseThrow()).filter(Double::isFinite).count())
                    .isEqualTo(205_921);
            Golden.Digest digest = golden.digest(Golden.Kind.S, "0").orElseThrow();
            assertThat(digest.sha256Extras()).containsOnlyKeys(IM);
            mismatches.addAll(Golden.Comparator.compareDigest(digest, zero.mz(), zero.intensity(), zero.arrays()));
        }
        assertThat(mismatches).isEmpty();
        assertThat(xLines).isEqualTo(golden.lines(Golden.Kind.X).size()).isEqualTo(18);
    }

    @Test
    void diaPasefRefArraysAreThePeakArraysBecauseTheMzComeFromThePeaks() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(DIA_REF))) {
            for (Spectrum s : reader) {
                double[] peakMobility = s.peakArrays().get(IM);
                assertThat(s.arrays()).as("spectrum %d", s.index()).isEqualTo(s.peakArrays());
                assertThat(s.mz()).hasSameSizeAs(peakMobility);
                assertThat(s.ionMobility()).containsSame(peakMobility);
                assertThat(s.array(IM)).containsSame(peakMobility);
                assertThat(s.ionMobilityArrayType()).contains(IM);
            }
        }
    }

    // ---- (c) fixtures without extra arrays ---------------------------------------------------------------------

    static Stream<Arguments> fixturesWithoutExtras() {
        List<Arguments> out = new ArrayList<>();
        for (String name : List.of("small.mzpeak", "small.chunked.mzpeak", "small.numpress.mzpeak",
                "small.unpacked.mzpeak", "has_uv.mzpeak", "Example_Processed.img.mzpeak")) {
            out.add(Arguments.of("current " + name, Fixtures.current(name)));
        }
        for (String name : Fixtures.LEGACY_NAMES) {
            out.add(Arguments.of("legacy " + name, Fixtures.legacy(name)));
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixturesWithoutExtras")
    void fixturesWithoutExtraColumnsHaveEmptyMaps(String label, Path dataset) {
        for (boolean reconstructProfile : new boolean[] {true, false}) {
            int spectra = 0;
            try (MzPeakReader reader = MzPeakReader.open(dataset, reconstructProfile)) {
                for (Spectrum s : reader) {
                    assertThat(s.arrays()).as("%s spectrum %d", label, s.index()).isEmpty();
                    assertThat(s.peakArrays()).as("%s spectrum %d", label, s.index()).isEmpty();
                    assertThat(s.ionMobility()).isEmpty();
                    assertThat(s.ionMobilityArrayType()).isEmpty();
                    spectra++;
                }
            }
            assertThat(spectra).as(label).isPositive();
        }
    }

    @Test
    void smallChunkedChunkSecondaryIntensityIsNotAnExtra() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current("small.chunked.mzpeak"))) {
            String data = MzPeakManifest.fromSource(source).find(EntityType.SPECTRUM, DataKind.DATA_ARRAYS)
                    .orElseThrow().name();
            try (SpectrumArrayStore store = SpectrumArrayStore.load(source.inputFile(data), data, false, Map.of())) {
                assertThat(store.plan().plainIntensity()).map(Column::path).contains("chunk.intensity");
                assertThat(store.plan().extras()).isEmpty();
                SpectrumArrayStore.Arrays zero = store.get(0);
                assertThat(zero.intensity()).hasSameSizeAs(zero.mz()).isNotEmpty();
                assertThat(zero.extras()).isEmpty();
            }
        }
    }

    // ---- (e) the Spectrum constructor ---------------------------------------------------------------------------

    private static final SpectrumDescription DESCRIPTION = new SpectrumDescription(7, "scan=8", 2, 1.5, null,
            SignalContinuity.CENTROID, 0, 0, List.of(), List.of(), List.of());

    @Test
    void theFourArgumentConstructorHasNoExtraArrays() {
        Spectrum s = new Spectrum(DESCRIPTION, new double[] {1, 2}, new double[] {3, 4},
                List.of(new CentroidPeak(1, 3, 0)));
        assertThat(s.arrays()).isEmpty();
        assertThat(s.peakArrays()).isEmpty();
        assertThat(s.array(IM)).isEmpty();
        assertThat(s.ionMobility()).isEmpty();
        assertThat(s.ionMobilityArrayType()).isEmpty();
        assertThatThrownBy(() -> s.arrays().put(IM, new double[2])).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theMapsAreUnmodifiableOrderedCopiesThatShareTheArrays() {
        double[] msLevel = {1, 1, 2};
        double[] qualified = {0.7, 0.8, 0.9};
        double[] mean = {10, 11, 12};
        double[] inverse = {0.9, 1.0, 1.1};
        Map<String, double[]> arrays = new LinkedHashMap<>();
        arrays.put("ms level", msLevel);
        arrays.put(IM + "@im_b", qualified);
        arrays.put("MS:1002816", mean);
        arrays.put(IM, inverse);
        double[] peakMobility = {1.2, 1.3};
        Map<String, double[]> peakArrays = new LinkedHashMap<>();
        peakArrays.put(IM, peakMobility);

        Spectrum s = new Spectrum(DESCRIPTION, new double[] {100, 101, 102}, new double[] {5, 6, 7},
                List.of(new CentroidPeak(100.5, 9, 0), new CentroidPeak(101.5, 8, 1)), arrays, peakArrays);
        arrays.clear();
        peakArrays.put("MS:1002816", new double[2]);

        assertThat(s.arrays().keySet()).containsExactly("ms level", IM + "@im_b", "MS:1002816", IM);
        assertThat(s.arrays().get("ms level")).isSameAs(msLevel);
        assertThat(s.arrays().get(IM)).isSameAs(inverse);
        assertThat(s.array(IM + "@im_b")).containsSame(qualified);
        assertThat(s.array("MS:1000514")).isEmpty();
        assertThat(s.peakArrays().keySet()).containsExactly(IM);
        assertThat(s.peakArrays().get(IM)).isSameAs(peakMobility);
        // the first plain key that is an ion mobility array type: a qualified key never is
        assertThat(s.ionMobilityArrayType()).contains("MS:1002816");
        assertThat(s.ionMobility()).containsSame(mean);

        assertThatThrownBy(() -> s.arrays().put("x", new double[3])).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> s.arrays().remove(IM)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> s.peakArrays().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> s.array(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullMapsAreEmptyAndAnExtraBesideAnotherIonMobilityTypeIsFound() {
        double[] drift = {3.5};
        Spectrum s = new Spectrum(DESCRIPTION, new double[] {100}, new double[] {5}, null,
                Map.of("MS:1002477", drift), null);
        assertThat(s.peakArrays()).isEmpty();
        assertThat(s.ionMobilityArrayType()).contains("MS:1002477");
        assertThat(s.ionMobility()).containsSame(drift);

        Spectrum none = new Spectrum(DESCRIPTION, null, null, null, null, null);
        assertThat(none.arrays()).isEmpty();
        assertThat(none.peakArrays()).isEmpty();
        assertThat(none.ionMobility()).isEmpty();
    }

    @Test
    void theConstructorRejectsNullKeysNullValuesAndLengthMismatches() {
        double[] mz = {100, 101};
        double[] intensity = {5, 6};
        List<CentroidPeak> peaks = List.of(new CentroidPeak(100, 5, 0), new CentroidPeak(Double.NaN, 6, 1),
                new CentroidPeak(102, 7, 2));
        Map<String, double[]> nullKey = new HashMap<>();
        nullKey.put(null, new double[2]);
        Map<String, double[]> nullValue = new HashMap<>();
        nullValue.put(IM, null);

        assertThatThrownBy(() -> new Spectrum(DESCRIPTION, mz, intensity, peaks, nullKey, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("arrays has a null key");
        assertThatThrownBy(() -> new Spectrum(DESCRIPTION, mz, intensity, peaks, Map.of(), nullKey))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("peakArrays has a null key");
        assertThatThrownBy(() -> new Spectrum(DESCRIPTION, mz, intensity, peaks, nullValue, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("null array for " + IM);
        assertThatThrownBy(() -> new Spectrum(DESCRIPTION, mz, intensity, peaks, Map.of(IM, new double[3]),
                Map.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("arrays: " + IM + " has 3 values but mz has 2");
        // against the supplied peak list (3 peaks), not the 2 finite ones peaks() keeps
        Spectrum ok = new Spectrum(DESCRIPTION, mz, intensity, peaks, Map.of(), Map.of(IM, new double[3]));
        assertThat(ok.peaks()).hasSize(2);
        assertThat(ok.peakArrays().get(IM)).hasSize(3);
        assertThatThrownBy(() -> new Spectrum(DESCRIPTION, mz, intensity, peaks, Map.of(), Map.of(IM, new double[2])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("peakArrays: " + IM + " has 2 values but the peak list has 3");
        assertThatThrownBy(() -> new Spectrum(DESCRIPTION, mz, intensity, null, Map.of(), Map.of(IM, new double[1])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the peak list has 0");
        // the m/z and intensity check comes first and is unchanged
        assertThatThrownBy(() -> new Spectrum(DESCRIPTION, mz, new double[1], peaks, Map.of(IM, new double[3]),
                Map.of())).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("mz/intensity length mismatch: 2 vs 1");
    }

    // ---- (f) the null fill keeps the extras parallel ------------------------------------------------------------

    @Test
    void finalizeArraysDropsTheNullMarkedPointsFromTheExtrasToo() {
        double[] a = {1, 2, 3, 4, 5};
        double[] b = {Double.NaN, 7, 8, 9, 10};
        Map<String, double[]> extras = new LinkedHashMap<>();
        extras.put("b", b);
        extras.put("a", a);

        SpectrumArrayStore.Arrays kept = NullFill.finalizeArrays(new double[] {100, Double.NaN, 102, Double.NaN, 104},
                new double[] {50, 0, 52, 0, 54}, extras, false);

        assertThat(kept.mz()).containsExactly(100, 102, 104);
        assertThat(kept.intensity()).containsExactly(50, 52, 54);
        assertThat(kept.extras().keySet()).containsExactly("b", "a");
        assertThat(kept.extras().get("a")).containsExactly(1, 3, 5);
        assertThat(kept.extras().get("b")).containsExactly(Double.NaN, 8, 10);
        assertThat(a).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void finalizeArraysKeepsTheExtrasAsReadWhenTheGapsAreFilled() {
        double[] a = {1, 2, 3, 4, 5};
        double[] b = {Double.NaN, Double.NaN, 8, Double.NaN, 10};
        Map<String, double[]> extras = new LinkedHashMap<>();
        extras.put("a", a);
        extras.put("b", b);

        SpectrumArrayStore.Arrays filled = NullFill.finalizeArrays(
                new double[] {100, Double.NaN, 102, Double.NaN, 104}, new double[] {50, 0, 52, 0, 54}, extras, true);

        assertThat(filled.mz()).containsExactly(100, 101, 102, 103, 104);
        assertThat(filled.extras().keySet()).containsExactly("a", "b");
        assertThat(filled.extras().get("a")).isSameAs(a).containsExactly(1, 2, 3, 4, 5);
        assertThat(filled.extras().get("b")).isSameAs(b).containsExactly(Double.NaN, Double.NaN, 8, Double.NaN, 10);
    }

    @Test
    void finalizeArraysWithoutNullMarkedPointsKeepsEveryArray() {
        double[] mz = {100, 101};
        double[] intensity = {5, 6};
        double[] a = {1, 2};
        for (boolean reconstructProfile : new boolean[] {true, false}) {
            SpectrumArrayStore.Arrays arrays = NullFill.finalizeArrays(mz, intensity, Map.of("a", a),
                    reconstructProfile);
            assertThat(arrays.mz()).isSameAs(mz);
            assertThat(arrays.intensity()).isSameAs(intensity);
            assertThat(arrays.extras().get("a")).isSameAs(a);
        }
        assertThat(NullFill.finalizeArrays(new double[] {Double.NaN, 1}, new double[] {0, 2}, false).extras())
                .isEmpty();
    }

    // ---- (g) a malformed extra is dropped with one WARNING --------------------------------------------------------

    private static final MessageType CHUNK = MessageTypeParser.parseMessageType("""
            message m {
              optional group chunk {
                optional int64 spectrum_index (INTEGER(64,false));
                optional double mz_chunk_start;
                optional group mz_chunk_values (LIST) { repeated group list { optional double element; } }
                optional binary chunk_encoding (STRING);
                optional group intensity (LIST) { repeated group list { optional float element; } }
                optional group im (LIST) { repeated group list { optional double element; } }
                optional group mobility (LIST) { repeated group list { optional double element; } }
                optional group mobility_numpress_linear_bytes (LIST) {
                  repeated group list { required int32 element (INTEGER(8,false)); }
                }
              }
            }""");

    private static final String CHUNK_INDEX = index("chunk",
            entry("chunk.spectrum_index", "index", "MS:1003870", 0, null, null),
            entry("chunk.mz_chunk_start", "chunk_start", "MS:1000514", 1, null, null),
            entry("chunk.mz_chunk_values", "chunk_values", "MS:1000514", 1, null, null),
            entry("chunk.chunk_encoding", "chunk_encoding", "MS:1000514", 1, null, null),
            entry("chunk.intensity", "chunk_secondary", "MS:1000515", null, null, null),
            entry("chunk.im", "chunk_secondary", IM, null, null, null),
            entry("chunk.mobility", "chunk_secondary", "MS:1002816", null, null, null),
            entry("chunk.mobility_numpress_linear_bytes", "chunk_transform", "MS:1002816", null,
                    MSNumpress.ACC_NUMPRESS_LINEAR, null));

    @Test
    void aMalformedChunkExtraIsDroppedWithOneWarningAndTheSignalStillReads() {
        Peak[] p = Peak.firstOfDiaPasefRefSpectrum2(7);
        // spectrum 0: a chunk whose start is a point (3 intensities for 2 deltas) and one whose ion mobility is short
        Group s0a = chunkRow(0, p[0].mz, new double[] {p[1].mz - p[0].mz, p[2].mz - p[1].mz},
                intensities(p, 0, 3), mobility(p, 0, 3));
        Group s0b = chunkRow(0, p[3].mz, new double[] {p[4].mz - p[3].mz}, intensities(p, 3, 5), mobility(p, 3, 4));
        // spectrum 1: short again (no second warning), and the second extra stored Numpress-encoded
        Group s1 = chunkRow(1, p[5].mz, new double[] {p[6].mz - p[5].mz}, intensities(p, 5, 7), mobility(p, 5, 6));
        addBytes(s1.getGroup("chunk", 0), "mobility_numpress_linear_bytes", numpressLinear(mobility(p, 5, 7)));
        // spectrum 2: well formed, the second extra with a null element
        Group s2 = chunkRow(2, p[0].mz, new double[] {p[1].mz - p[0].mz}, intensities(p, 0, 2), mobility(p, 0, 2));
        Group mobilityList = s2.getGroup("chunk", 0).addGroup("mobility");
        mobilityList.addGroup("list").add("element", p[0].mobility);
        mobilityList.addGroup("list");
        Path file = write(CHUNK, CHUNK_INDEX, s0a, s0b, s1, s2);

        List<LogRecord> records = ArrayIndexTest.captureLog(SpectrumArrayStore.class, () -> {
            try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), "spectra_peaks.parquet",
                    false, Map.of())) {
                assertThat(store.plan().extras()).containsOnlyKeys(IM, "MS:1002816");

                SpectrumArrayStore.Arrays zero = store.get(0);
                assertExact("m/z of 0", new double[] {p[0].mz, p[0].mz + (p[1].mz - p[0].mz),
                        p[0].mz + (p[1].mz - p[0].mz) + (p[2].mz - p[1].mz), p[3].mz,
                        p[3].mz + (p[4].mz - p[3].mz)}, zero.mz());
                assertExact("intensity of 0", widened(intensities(p, 0, 5)), zero.intensity());
                assertThat(zero.extras()).isEmpty();

                SpectrumArrayStore.Arrays one = store.get(1);
                assertExact("m/z of 1", new double[] {p[5].mz, p[5].mz + (p[6].mz - p[5].mz)}, one.mz());
                assertExact("intensity of 1", widened(intensities(p, 5, 7)), one.intensity());
                assertThat(one.extras()).isEmpty();

                SpectrumArrayStore.Arrays two = store.get(2);
                assertThat(two.mz()).hasSize(2);
                assertThat(two.extras().keySet()).containsExactly(IM, "MS:1002816");
                assertExact("ion mobility of 2", mobility(p, 0, 2), two.extras().get(IM));
                assertExact("mean ion mobility of 2", new double[] {p[0].mobility, Double.NaN},
                        two.extras().get("MS:1002816"));
            }
        });

        List<LogRecord> warnings = records.stream().filter(r -> r.getLevel() == Level.WARNING).toList();
        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(0).getMessage())
                .contains("spectra_peaks.parquet", "extra array " + IM + " ", "spectrum_index 0",
                        "has 1 values in a chunk of 2 points", "m/z and intensity are kept");
        assertThat(warnings.get(1).getMessage())
                .contains("spectra_peaks.parquet", "extra array MS:1002816 ", "spectrum_index 1",
                        MSNumpress.ACC_NUMPRESS_LINEAR, "mobility_numpress_linear_bytes");
    }

    // ---- (h) two columns of one array type -----------------------------------------------------------------------

    private static final MessageType POINT = MessageTypeParser.parseMessageType("""
            message m {
              optional group point {
                optional int64 spectrum_index (INTEGER(64,false));
                optional double mz;
                optional float intensity;
                optional double im_a;
                optional float im_b;
              }
            }""");

    private static final String POINT_INDEX = index("point",
            entry("point.spectrum_index", "index", "MS:1003870", 0, null, null),
            entry("point.mz", "point", "MS:1000514", 1, null, null),
            entry("point.intensity", "point", "MS:1000515", null, null, null),
            entry("point.im_a", "point", IM, null, null, "primary"),
            entry("point.im_b", "point", IM, null, null, "secondary"));

    @Test
    void twoIonMobilityColumnsAreBothExposed() {
        Peak[] p = Peak.firstOfDiaPasefRefSpectrum2(5);
        Path file = write(POINT, POINT_INDEX,
                pointRow(2, p[0].mz, p[0].intensity, p[0].mobility, p[0].mobility),
                pointRow(2, null, null, null, null), // a null-marked point
                pointRow(2, p[1].mz, p[1].intensity, p[1].mobility, p[1].mobility),
                pointRow(2, p[2].mz, p[2].intensity, p[2].mobility, p[2].mobility),
                pointRow(4, p[3].mz, p[3].intensity, null, p[3].mobility),
                pointRow(4, p[4].mz, p[4].intensity, null, null));
        String qualified = IM + "@im_b";
        double[] intensity = {(float) p[0].intensity, (float) p[1].intensity, (float) p[2].intensity};

        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), false, Map.of())) {
            SpectrumArrayStore.Arrays two = store.get(2);
            assertExact("m/z", new double[] {p[0].mz, p[1].mz, p[2].mz}, two.mz());
            assertExact("intensity", intensity, two.intensity());
            assertThat(two.extras().keySet()).containsExactly(IM, qualified);
            assertExact(IM, new double[] {p[0].mobility, p[1].mobility, p[2].mobility}, two.extras().get(IM));
            assertExact(qualified, new double[] {(float) p[0].mobility, (float) p[1].mobility, (float) p[2].mobility},
                    two.extras().get(qualified));

            // every im_a cell of spectrum 4 is null: no array; a null im_b cell reads as NaN
            SpectrumArrayStore.Arrays four = store.get(4);
            assertExact("m/z of 4", new double[] {p[3].mz, p[4].mz}, four.mz());
            assertThat(four.extras().keySet()).containsExactly(qualified);
            assertExact(qualified, new double[] {(float) p[3].mobility, Double.NaN}, four.extras().get(qualified));
        }

        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), true, Map.of())) {
            SpectrumArrayStore.Arrays two = store.get(2);
            assertExact("filled m/z", new double[] {p[0].mz, p[0].mz + (p[1].mz - p[0].mz) * 1 / 2, p[1].mz,
                    p[2].mz}, two.mz());
            assertExact("filled intensity", new double[] {intensity[0], 0.0, intensity[1], intensity[2]},
                    two.intensity());
            assertThat(two.extras().keySet()).containsExactly(IM, qualified);
            assertExact(IM, new double[] {p[0].mobility, Double.NaN, p[1].mobility, p[2].mobility},
                    two.extras().get(IM));
            assertThat(two.extras().get(qualified)).hasSize(4);
            assertThat(two.extras().get(qualified)[1]).isNaN();
        }
    }

    // ---- FN-13: the delta-model fill and spectra across row groups -----------------------------------------------

    /**
     * reconstructProfile with a stored delta model fills the null-marked m/z by stepping; the extras stay parallel to
     * the filled m/z and keep their stored cells, NaN at the filled rows (kills SA5, which dropped them there).
     */
    @Test
    void theDeltaModelFillKeepsTheExtrasAsStored() {
        Peak[] p = Peak.firstOfDiaPasefRefSpectrum2(3);
        Path file = write(POINT, POINT_INDEX,
                pointRow(2, p[0].mz, p[0].intensity, p[0].mobility, p[0].mobility),
                pointRow(2, null, null, null, null), // a null-marked point
                pointRow(2, p[1].mz, p[1].intensity, p[1].mobility, null),
                pointRow(2, p[2].mz, p[2].intensity, p[2].mobility, p[2].mobility));
        double step = 0.0125;

        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), true,
                Map.of(2L, new double[] {step}))) {
            SpectrumArrayStore.Arrays two = store.get(2);
            assertExact("m/z", new double[] {p[0].mz, p[0].mz + step, p[1].mz, p[2].mz}, two.mz());
            assertExact("intensity", new double[] {(float) p[0].intensity, 0.0, (float) p[1].intensity,
                    (float) p[2].intensity}, two.intensity());
            assertThat(two.extras().keySet()).containsExactly(IM, IM + "@im_b");
            assertExact(IM, new double[] {p[0].mobility, Double.NaN, p[1].mobility, p[2].mobility},
                    two.extras().get(IM));
            assertExact(IM + "@im_b", new double[] {(float) p[0].mobility, Double.NaN, Double.NaN,
                    (float) p[2].mobility}, two.extras().get(IM + "@im_b"));
        }
    }

    /** An extra of a spectrum whose chunks lie in two row groups is read across the boundary, in row order. */
    @Test
    void anExtraIsReadAcrossARowGroupBoundary() {
        Peak[] p = Peak.firstOfDiaPasefRefSpectrum2(6);
        Path file = writeRowGroups(CHUNK, CHUNK_INDEX, 1,
                chunkRow(2, p[0].mz, new double[] {p[1].mz - p[0].mz}, intensities(p, 0, 2), mobility(p, 0, 2)),
                chunkRow(2, p[2].mz, new double[] {p[3].mz - p[2].mz}, intensities(p, 2, 4), mobility(p, 2, 4)),
                chunkRow(4, p[4].mz, new double[] {p[5].mz - p[4].mz}, intensities(p, 4, 6), mobility(p, 4, 6)));

        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), false, Map.of())) {
            SpectrumArrayStore.Arrays two = store.get(2);
            assertExact("m/z of 2", new double[] {p[0].mz, p[0].mz + (p[1].mz - p[0].mz), p[2].mz,
                    p[2].mz + (p[3].mz - p[2].mz)}, two.mz());
            assertExact("ion mobility of 2", mobility(p, 0, 4), two.extras().get(IM));
            assertExact("ion mobility of 4", mobility(p, 4, 6), store.get(4).extras().get(IM));
        }
    }

    /**
     * An extra dropped in the second row group of a spectrum is dropped for the whole spectrum, values read in the
     * first row group included, with one WARNING; m/z and intensity are kept, and the next spectrum has it again.
     */
    @Test
    void anExtraDroppedInTheSecondRowGroupOfASpectrumIsDroppedForTheSpectrum() {
        Peak[] p = Peak.firstOfDiaPasefRefSpectrum2(6);
        Path file = writeRowGroups(CHUNK, CHUNK_INDEX, 1,
                chunkRow(2, p[0].mz, new double[] {p[1].mz - p[0].mz}, intensities(p, 0, 2), mobility(p, 0, 2)),
                chunkRow(2, p[2].mz, new double[] {p[3].mz - p[2].mz}, intensities(p, 2, 4), mobility(p, 2, 3)),
                chunkRow(4, p[4].mz, new double[] {p[5].mz - p[4].mz}, intensities(p, 4, 6), mobility(p, 4, 6)));

        List<LogRecord> records = ArrayIndexTest.captureLog(SpectrumArrayStore.class, () -> {
            try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), "spectra_peaks.parquet",
                    false, Map.of())) {
                SpectrumArrayStore.Arrays two = store.get(2);
                assertThat(two.mz()).hasSize(4);
                assertExact("intensity of 2", widened(intensities(p, 0, 4)), two.intensity());
                assertThat(two.extras()).isEmpty();
                assertExact("ion mobility of 4", mobility(p, 4, 6), store.get(4).extras().get(IM));
            }
        });
        assertThat(records).filteredOn(r -> r.getLevel() == Level.WARNING).singleElement()
                .satisfies(r -> assertThat(r.getMessage()).contains("extra array " + IM + " ", "spectrum_index 2",
                        "has 1 values in a chunk of 2 points"));
    }

    // ---- FN-2: an extra never blocks m/z and intensity, and an all-NaN extra stays absent -----------------------

    private static final MessageType NUMPRESS_NAMED_EXTRA = MessageTypeParser.parseMessageType("""
            message m {
              optional group chunk {
                optional int64 spectrum_index (INTEGER(64,false));
                optional double mz_chunk_start;
                optional group mz_chunk_values (LIST) { repeated group list { optional double element; } }
                optional binary chunk_encoding (STRING);
                optional group intensity (LIST) { repeated group list { optional float element; } }
                optional group mobility_numpress_quality (LIST) { repeated group list { optional double element; } }
              }
            }""");

    @Test
    void anExtraValueColumnWhoseNameContainsNumpressIsRead() {
        Peak[] p = Peak.firstOfDiaPasefRefSpectrum2(3);
        String json = index("chunk",
                entry("chunk.spectrum_index", "index", "MS:1003870", 0, null, null),
                entry("chunk.mz_chunk_start", "chunk_start", "MS:1000514", 1, null, null),
                entry("chunk.mz_chunk_values", "chunk_values", "MS:1000514", 1, null, null),
                entry("chunk.chunk_encoding", "chunk_encoding", "MS:1000514", 1, null, null),
                entry("chunk.intensity", "chunk_secondary", "MS:1000515", null, null, null),
                entry("chunk.mobility_numpress_quality", "chunk_secondary", IM, null, null, null));
        Group record = new SimpleGroupFactory(NUMPRESS_NAMED_EXTRA).newGroup();
        Group chunk = record.addGroup("chunk");
        chunk.add("spectrum_index", 2L);
        chunk.add("mz_chunk_start", p[0].mz);
        addDoubles(chunk, "mz_chunk_values", new double[] {p[1].mz - p[0].mz, p[2].mz - p[1].mz});
        chunk.add("chunk_encoding", "MS:1003089");
        Group list = chunk.addGroup("intensity");
        for (double v : intensities(p, 0, 3)) {
            list.addGroup("list").add("element", (float) v);
        }
        addDoubles(chunk, "mobility_numpress_quality", mobility(p, 0, 3));
        Path file = write(NUMPRESS_NAMED_EXTRA, json, record);

        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), false, Map.of())) {
            SpectrumArrayStore.Arrays two = store.get(2);
            assertExact("m/z", new double[] {p[0].mz, p[0].mz + (p[1].mz - p[0].mz),
                    p[0].mz + (p[1].mz - p[0].mz) + (p[2].mz - p[1].mz)}, two.mz());
            assertExact("intensity", widened(intensities(p, 0, 3)), two.intensity());
            assertThat(two.extras().keySet()).containsExactly(IM);
            assertExact(IM, mobility(p, 0, 3), two.extras().get(IM));
        }
    }

    @Test
    void anExtraWhoseOnlyValuesSitAtDroppedNullMarkedRowsIsAbsent() {
        Peak[] p = Peak.firstOfDiaPasefRefSpectrum2(2);
        Path file = write(POINT, POINT_INDEX,
                pointRow(2, p[0].mz, p[0].intensity, null, p[0].mobility),
                pointRow(2, null, null, p[0].mobility, null), // null-marked, its im_a cell the only number
                pointRow(2, p[1].mz, p[1].intensity, null, p[1].mobility));

        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), false, Map.of())) {
            SpectrumArrayStore.Arrays two = store.get(2);
            assertExact("m/z", new double[] {p[0].mz, p[1].mz}, two.mz());
            assertThat(two.extras().keySet()).containsExactly(IM + "@im_b");
        }
        // with the gaps filled the cell is kept, at the filled point
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), true, Map.of())) {
            SpectrumArrayStore.Arrays two = store.get(2);
            assertThat(two.mz()).hasSize(3);
            assertThat(two.extras().keySet()).containsExactly(IM, IM + "@im_b");
            assertExact(IM, new double[] {Double.NaN, p[0].mobility, Double.NaN}, two.extras().get(IM));
        }
    }

    @Test
    void finalizeArraysDropsAnExtraLeftWithOnlyNaN() {
        Map<String, double[]> extras = new LinkedHashMap<>();
        extras.put("a", new double[] {Double.NaN, 7, Double.NaN});
        extras.put("b", new double[] {1, 2, 3});

        SpectrumArrayStore.Arrays kept = NullFill.finalizeArrays(new double[] {100, Double.NaN, 102},
                new double[] {50, 0, 52}, extras, false);

        assertThat(kept.mz()).containsExactly(100, 102);
        assertThat(kept.extras().keySet()).containsExactly("b");
        assertThat(kept.extras().get("b")).containsExactly(1, 3);
    }

    // ---- fixtures and constructed members ----------------------------------------------------------------------

    /** One stored peak of diaPASEF.ref, read with parquet-java from its peaks member. */
    private record Peak(double mz, double intensity, double mobility) {

        /** The first {@code n} peaks of spectrum 2, in stored order. */
        static Peak[] firstOfDiaPasefRefSpectrum2(int n) {
            List<Peak> out = new ArrayList<>();
            try (MzPeakSource source = MzPeakSource.open(Fixtures.current(DIA_REF))) {
                String peaks = MzPeakManifest.fromSource(source).find(EntityType.SPECTRUM, DataKind.PEAKS)
                        .orElseThrow().name();
                ParquetGroups.forEach(source.inputFile(peaks), record -> {
                    Group point = record.getGroup("point", 0);
                    if (ParquetGroups.optLong(point, "spectrum_index") == 2 && out.size() < n) {
                        out.add(new Peak(ParquetGroups.optDouble(point, "mz"),
                                ParquetGroups.optDouble(point, "intensity"),
                                ParquetGroups.optDouble(point, "mean_inverse_reduced_ion_mobility")));
                    }
                });
            }
            assertThat(out).hasSize(n);
            return out.toArray(Peak[]::new);
        }
    }

    private static double[] intensities(Peak[] p, int from, int to) {
        return Arrays.stream(p, from, to).mapToDouble(Peak::intensity).toArray();
    }

    private static double[] mobility(Peak[] p, int from, int to) {
        return Arrays.stream(p, from, to).mapToDouble(Peak::mobility).toArray();
    }

    /** The values as the float column stores them. */
    private static double[] widened(double[] values) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (float) values[i];
        }
        return out;
    }

    private static byte[] numpressLinear(double[] values) {
        byte[] buffer = new byte[8 + values.length * 5];
        int n = MSNumpress.encodeLinear(values, values.length, buffer,
                MSNumpress.optimalLinearFixedPoint(values, values.length));
        return Arrays.copyOf(buffer, n);
    }

    /** A delta chunk row of {@link #CHUNK} with its {@code im} list. */
    private static Group chunkRow(long si, double start, double[] deltas, double[] intensity, double[] im) {
        Group record = new SimpleGroupFactory(CHUNK).newGroup();
        Group chunk = record.addGroup("chunk");
        chunk.add("spectrum_index", si);
        chunk.add("mz_chunk_start", start);
        addDoubles(chunk, "mz_chunk_values", deltas);
        chunk.add("chunk_encoding", "MS:1003089");
        Group list = chunk.addGroup("intensity");
        for (double v : intensity) {
            list.addGroup("list").add("element", (float) v);
        }
        addDoubles(chunk, "im", im);
        return record;
    }

    /** A row of {@link #POINT}; a null value leaves its cell null. */
    private static Group pointRow(long si, Double mz, Double intensity, Double imA, Double imB) {
        Group record = new SimpleGroupFactory(POINT).newGroup();
        Group point = record.addGroup("point");
        point.add("spectrum_index", si);
        if (mz != null) {
            point.add("mz", mz);
        }
        if (intensity != null) {
            point.add("intensity", intensity.floatValue());
        }
        if (imA != null) {
            point.add("im_a", imA);
        }
        if (imB != null) {
            point.add("im_b", imB.floatValue());
        }
        return record;
    }

    private static void addDoubles(Group parent, String field, double[] values) {
        Group list = parent.addGroup(field);
        for (double v : values) {
            list.addGroup("list").add("element", v);
        }
    }

    private static void addBytes(Group parent, String field, byte[] bytes) {
        Group list = parent.addGroup(field);
        for (byte b : bytes) {
            list.addGroup("list").add("element", b & 0xFF);
        }
    }

    private static String entry(String path, String format, String arrayType, Integer rank, String transform,
                                String priority) {
        return "{\"path\": \"" + path + "\", \"buffer_format\": \"" + format + "\", \"array_type\": \"" + arrayType
                + "\", \"sorting_rank\": " + rank + ", \"transform\": "
                + (transform == null ? "null" : "\"" + transform + "\"") + ", \"buffer_priority\": "
                + (priority == null ? "null" : "\"" + priority + "\"") + ", \"context\": \"spectrum\"}";
    }

    private static String index(String prefix, String... entries) {
        return "{\"prefix\": \"" + prefix + "\", \"entries\": [" + String.join(", ", entries) + "]}";
    }

    /** Writes {@code rows} with {@code json} as the spectrum array index footer, in one row group. */
    private Path write(MessageType schema, String json, Group... rows) {
        return writeRowGroups(schema, json, Integer.MAX_VALUE, rows);
    }

    /** {@link #write}, with at most {@code rowGroupRows} rows per row group. */
    private Path writeRowGroups(MessageType schema, String json, int rowGroupRows, Group... rows) {
        Path file = tmp.resolve("member-" + (written++) + ".parquet");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withExtraMetaData(Map.of(INDEX_KEY, json))
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withRowGroupRowCountLimit(rowGroupRows)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (Group row : rows) {
                writer.write(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    private static void assertExact(String what, double[] expected, double[] actual) {
        assertThat(actual).as("%s length", what).hasSize(expected.length);
        for (int k = 0; k < expected.length; k++) {
            assertThat(Double.compare(actual[k], expected[k]))
                    .as("%s at %d: expected %s but was %s", what, k, expected[k], actual[k])
                    .isZero();
        }
    }
}
