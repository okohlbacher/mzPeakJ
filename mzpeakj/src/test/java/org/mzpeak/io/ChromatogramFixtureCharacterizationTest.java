package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.example.data.Group;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Chromatogram;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how the chromatograms of every vendored fixture read, with and without {@code reconstructProfile}, before the
 * chunk rows of chromatograms are decoded by {@link ChunkRowDecoder} (C4, X-10): the time, intensity and extra arrays
 * and the units of every chromatogram are pinned bit-exactly by a SHA-256 digest of the reader's output. The times
 * and intensities of these fixtures are stored values or sums of stored values, which every platform computes alike.
 * The small.numpress chromatograms are SLOF-decoded (a {@code Math.exp}), so they are not pinned by a digest but
 * compared with the 0.12.0 algorithm, run here on the stored rows: the time is the chunk start followed by the running
 * sum of the deltas, the intensity the SLOF payload decoded by {@link MSNumpress#decode}.
 */
class ChromatogramFixtureCharacterizationTest {

    /** The small.* TIC with its ms level extra: the point and the chunked fixtures read alike. */
    private static final String SMALL = "4e58408cb02fecb4a5e65ead5e716b6e0a49bea8fdad02b8119f4ba482f142ea";
    private static final String HAS_UV = "9290d038d1b77d834c64ad69f094b67164363ca1276230337b9a97bf0e54c495";
    private static final String DIA_PASEF = "ea8d6478d3d326b595046dba76ba6f84ea83514319fa7718ed0d7e863e8a7860";
    /** No chromatograms. */
    private static final String NONE = "03a9b09b35d993ff9a6f031da89b66b46158eae60751c9fe1c424101d2f1cc3c";

    /** SHA-256 of {@link #describe} per fixture, the same with and without reconstructProfile. */
    private static final Map<String, String> DIGESTS = Map.ofEntries(
            Map.entry("mzpeak/small.mzpeak", SMALL),
            Map.entry("mzpeak/small.chunked.mzpeak", SMALL),
            Map.entry("mzpeak/small.unpacked.mzpeak", SMALL),
            Map.entry("mzpeak/has_uv.mzpeak", HAS_UV),
            Map.entry("mzpeak/diaPASEF.ref.mzpeak", DIA_PASEF),
            Map.entry("mzpeak/diaPASEF.grid.mzpeak", DIA_PASEF),
            Map.entry("mzpeak/Example_Processed.img.mzpeak", NONE),
            Map.entry("mzpeak-legacy/small.mzpeak", SMALL),
            Map.entry("mzpeak-legacy/small.chunked.mzpeak", SMALL),
            Map.entry("mzpeak-legacy/small.unpacked.mzpeak", SMALL),
            Map.entry("mzpeak-legacy/has_uv.mzpeak", HAS_UV));

    static Stream<Arguments> pinned() {
        return DIGESTS.keySet().stream().sorted()
                .flatMap(f -> Stream.of(Arguments.of(f, true), Arguments.of(f, false)));
    }

    /** Every fixture except small.numpress reads its chromatograms as pinned. */
    @ParameterizedTest(name = "{0} reconstructProfile={1}")
    @MethodSource("pinned")
    void everyFixtureReadsItsChromatogramsAsPinned(String fixture, boolean reconstructProfile) {
        List<Chromatogram> chromatograms = read(path(fixture), reconstructProfile);
        assertThat(digest(describe(chromatograms))).as(fixture).isEqualTo(DIGESTS.get(fixture));
    }

    static Stream<Arguments> numpress() {
        return Stream.of("mzpeak/small.numpress.mzpeak", "mzpeak-legacy/small.numpress.mzpeak")
                .flatMap(f -> Stream.of(Arguments.of(f, true), Arguments.of(f, false)));
    }

    /**
     * small.numpress (delta time, SLOF intensity) reads every chromatogram as the 0.12.0 algorithm decodes its stored
     * rows: equal time and intensity arrays, its {@code ms level} extra as stored, and the units of small.chunked.
     */
    @ParameterizedTest(name = "{0} reconstructProfile={1}")
    @MethodSource("numpress")
    void smallNumpressReadsAsTheReleasedAlgorithm(String fixture, boolean reconstructProfile) {
        List<Chromatogram> chromatograms = read(path(fixture), reconstructProfile);
        List<double[]> expected = new ArrayList<>();
        try (MzPeakSource source = MzPeakSource.open(path(fixture))) {
            ParquetGroups.forEach(source.inputFile("chromatograms_data.parquet"), row -> {
                Group chunk = row.getGroup("chunk", 0);
                double[] deltas = ParquetGroups.doubleListNullable(chunk, "time_chunk_values");
                double[] time = new double[deltas.length + 1];
                time[0] = chunk.getDouble("time_chunk_start", 0);
                for (int i = 0; i < deltas.length; i++) {
                    time[i + 1] = time[i] + deltas[i];
                }
                byte[] slof = ParquetGroups.byteList(chunk, "intensity_numpress_slof_bytes");
                expected.add(time);
                expected.add(MSNumpress.decode(MSNumpress.ACC_NUMPRESS_SLOF, slof, slof.length));
                expected.add(ParquetGroups.doubleListNullable(chunk, "ms_level"));
            });
        }
        assertThat(chromatograms).hasSize(1);
        Chromatogram tic = chromatograms.get(0);
        assertThat(expected).hasSize(3);
        assertThat(tic.time()).hasSize(48).isEqualTo(expected.get(0));
        assertThat(tic.intensity()).isEqualTo(expected.get(1));
        assertThat(tic.arrays().keySet()).containsExactly("ms level");
        assertThat(tic.arrays().get("ms level")).isEqualTo(expected.get(2));
        assertThat(tic.timeUnit()).isEqualTo("UO:0000031");
        assertThat(tic.intensityUnit()).isEqualTo("MS:1000131");
    }

    private static Path path(String fixture) {
        String[] parts = fixture.split("/", 2);
        return parts[0].equals("mzpeak") ? Fixtures.current(parts[1]) : Fixtures.legacy(parts[1]);
    }

    private static List<Chromatogram> read(Path path, boolean reconstructProfile) {
        try (MzPeakReader r = MzPeakReader.open(path,
                MzPeakReader.Options.defaults().withReconstructProfile(reconstructProfile))) {
            return r.chromatograms();
        }
    }

    /** Every chromatogram's index, id, units, and the bits of its time, intensity and extra arrays. */
    static byte[] describe(List<Chromatogram> chromatograms) {
        List<byte[]> parts = new ArrayList<>();
        parts.add(("n=" + chromatograms.size()).getBytes(StandardCharsets.UTF_8));
        for (Chromatogram c : chromatograms) {
            parts.add(("|" + c.index() + "|" + c.id() + "|" + c.timeUnit() + "|" + c.intensityUnit() + "|"
                    + c.arrays().keySet()).getBytes(StandardCharsets.UTF_8));
            parts.add(bits(c.time()));
            parts.add(bits(c.intensity()));
            for (double[] extra : c.arrays().values()) {
                parts.add(bits(extra));
            }
        }
        int n = parts.stream().mapToInt(p -> p.length).sum();
        ByteBuffer out = ByteBuffer.allocate(n);
        parts.forEach(out::put);
        return out.array();
    }

    private static byte[] bits(double[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(4 + values.length * 8);
        buffer.putInt(values.length);
        for (double v : values) {
            buffer.putLong(Double.doubleToRawLongBits(v));
        }
        return buffer.array();
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
