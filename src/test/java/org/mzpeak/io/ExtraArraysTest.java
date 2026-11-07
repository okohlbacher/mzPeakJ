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
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.SignalPlan.Column;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RD-14: the extra signal arrays of a spectrum signal member, read by {@link SpectrumArrayStore} parallel to the m/z.
 *
 * <ul>
 *   <li>small.chunked's chunk secondary intensity is an intensity, not an extra.
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

    // ---- (c) a chunk secondary intensity -------------------------------------------------------------------------

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
        Path file = tmp.resolve("member-" + (written++) + ".parquet");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withExtraMetaData(Map.of(INDEX_KEY, json))
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
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
