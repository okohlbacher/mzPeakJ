package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.MemberRewriter.Edit;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.Session;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mzpeak.io.MemberRewriter.rewrite;
import static org.mzpeak.io.MemberRewriter.rewriteInPlace;

/**
 * D-12a (V-21): {@link Chromatogram#timeUnit()}. The reader keeps the stored chromatogram times and reports their
 * unit, the unit of the time values column, else of the chunk start column, else minute; {@link
 * Chromatogram#withTimeUnit} converts. Until V-22 and V-23 the writers convert every chromatogram to minutes, the
 * unit they declare, so a chromatogram in seconds is never written with a minute label.
 *
 * <p>Every vendored fixture stores its chromatogram times in minutes, so its chromatograms read the same values
 * before and after the reader stopped converting to minutes: the digests below were taken on the code before V-21.
 * The other cases rewrite a fixture member ({@link MemberRewriter}) with its times in another unit.
 */
class ChromatogramTimeUnitTest {

    // The digests of the chromatograms of each fixture, taken on the code before V-21.
    private static final String SMALL = "b0c18d39bc9630fe3de04717d552c57fc9a55b3b81769e38b61805b6c4a71857";
    private static final String NUMPRESS = "50e8f5d204e694638b98bef06d7e65f16b4921680805763dd7b32f0ef3d5c0fd";
    private static final String HAS_UV = "0683e1579beb11c7920f004dd1d02b0b81d7cbd352d24ab4d382f63de1b1d7a6";
    private static final String DIA_PASEF = "da9176a14d8c67605f9321d928d9da3620b74e00c2f71f60f7662157c91e341e";
    /** No chromatograms: the SHA-256 of nothing. */
    private static final String EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final Path SMALL_UNPACKED = Fixtures.current("small.unpacked.mzpeak");
    private static final Path SMALL_CHUNKED = Fixtures.current("small.chunked.mzpeak");
    private static final String CHROMATOGRAMS_DATA = "chromatograms_data.parquet";
    private static final String CHROMATOGRAMS_METADATA = "chromatograms_metadata.parquet";
    private static final String MINUTE = "UO:0000031";
    private static final String SECOND = "UO:0000010";
    private static final String MILLISECOND = "UO:0000028";
    private static final String HOUR = "UO:0000032";
    private static final double RELATIVE = 1e-12;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    // ---- (a) fixtures ------------------------------------------------------------------------------------------

    /**
     * Each fixture with chromatograms, the number it has and the SHA-256 of their index, id, time and intensity
     * values ({@link #digest}). The MS-Numpress SLOF intensities of small.numpress are hashed rounded to float32
     * ({@link SignalSnapshotTest#SLOF_FIXTURES}): their last bit depends on the platform's {@code Math.exp}.
     */
    static Stream<Arguments> fixtures() {
        return Stream.of(
                Arguments.of(Fixtures.current("small.mzpeak"), 1, SMALL),
                Arguments.of(Fixtures.current("small.chunked.mzpeak"), 1, SMALL),
                Arguments.of(Fixtures.current("small.numpress.mzpeak"), 1, NUMPRESS),
                Arguments.of(Fixtures.current("small.unpacked.mzpeak"), 1, SMALL),
                Arguments.of(Fixtures.current("has_uv.mzpeak"), 2, HAS_UV),
                Arguments.of(Fixtures.current("diaPASEF.ref.mzpeak"), 2, DIA_PASEF),
                Arguments.of(Fixtures.current("diaPASEF.grid.mzpeak"), 2, DIA_PASEF),
                Arguments.of(Fixtures.current("Example_Processed.img.mzpeak"), 0, EMPTY),
                Arguments.of(Fixtures.legacy("small.mzpeak"), 1, SMALL),
                Arguments.of(Fixtures.legacy("small.chunked.mzpeak"), 1, SMALL),
                Arguments.of(Fixtures.legacy("small.numpress.mzpeak"), 1, NUMPRESS),
                Arguments.of(Fixtures.legacy("small.unpacked.mzpeak"), 1, SMALL),
                Arguments.of(Fixtures.legacy("has_uv.mzpeak"), 2, HAS_UV));
    }

    /** (a) Every fixture's chromatograms read the values pinned before V-21, in minutes. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void everyFixtureReadsItsChromatogramsUnchanged(Path fixture, int count, String sha256) {
        try (MzPeakReader reader = MzPeakReader.open(fixture)) {
            List<Chromatogram> chromatograms = reader.chromatograms();
            assertThat(chromatograms).hasSize(count);
            boolean slof = SignalSnapshotTest.SLOF_FIXTURES.contains(fixture.getFileName().toString());
            assertThat(digest(chromatograms, slof)).isEqualTo(sha256);
            assertThat(chromatograms).allSatisfy(c -> assertThat(c.timeUnit()).isEqualTo(MINUTE));
        }
    }

    // ---- (b) a point layout in seconds -------------------------------------------------------------------------

    /**
     * (b) small.unpacked with its point-layout chromatogram times stored in seconds (the fixture's times × 60)
     * reads {@code timeUnit} UO:0000010 and the stored seconds; before V-21 it read minutes. Converted back to
     * minutes, they are the fixture's within a relative 1e-12.
     */
    @Test
    void aChromatogramStoredInSecondsReadsTheStoredSeconds() throws IOException {
        Path dir = secondsCopy();
        try (MzPeakReader original = MzPeakReader.open(SMALL_UNPACKED); MzPeakReader seconds = MzPeakReader.open(dir)) {
            Chromatogram minutes = original.chromatograms().get(0);
            Chromatogram read = seconds.chromatograms().get(0);
            assertThat(read.timeUnit()).isEqualTo(SECOND);
            assertThat(read.time()).containsExactly(scaled(minutes.time(), v -> v * 60));
            assertThat(read.intensity()).isEqualTo(minutes.intensity());
            assertThat(read.arrays().keySet()).isEqualTo(minutes.arrays().keySet());
            Chromatogram back = read.withTimeUnit(MINUTE);
            assertThat(back.timeUnit()).isEqualTo(MINUTE);
            assertClose(back.time(), minutes.time(), "time in minutes");
            assertThat(seconds.tic().orElseThrow().timeUnit()).isEqualTo(SECOND);
        }
    }

    // ---- (c) the chunk layout ----------------------------------------------------------------------------------

    /** Units of a chunk start column and how a time in minutes is stored in each. */
    static Stream<Arguments> startUnits() {
        return Stream.of(
                Arguments.of(SECOND, (DoubleUnaryOperator) v -> v * 60),
                Arguments.of(MILLISECOND, (DoubleUnaryOperator) v -> v * 60_000),
                Arguments.of(HOUR, (DoubleUnaryOperator) v -> v / 60));
    }

    /**
     * (c) small.chunked with its chunk start column in seconds (or milliseconds, hours) and its values in minutes:
     * the time unit is the values column's, minute, and the start is converted to it, so the times are the
     * fixture's within 1e-12.
     */
    @ParameterizedTest(name = "start in {0}")
    @MethodSource("startUnits")
    void aChunkStartInAnotherUnitIsConvertedToTheMinutesOfTheValues(String unit, DoubleUnaryOperator fromMinutes) {
        try (MzPeakSource source = MzPeakSource.open(SMALL_CHUNKED)) {
            InputFile metadata = source.inputFile(CHROMATOGRAMS_METADATA);
            InputFile data = source.inputFile(CHROMATOGRAMS_DATA);
            Path rewritten = tmp.resolve(CHROMATOGRAMS_DATA);
            Map<String, Edit> edits = new HashMap<>();
            edits.put("chunk.time_chunk_start", new Edit(unit, fromMinutes));
            edits.put("chunk.time_chunk_values", new Edit(MINUTE, v -> v));
            rewrite(data, rewritten, SignalEntity.CHROMATOGRAM, edits);

            Chromatogram expected = ChromatogramStore.load(metadata, data).all().get(0);
            Chromatogram actual = ChromatogramStore.load(metadata, new LocalInputFile(rewritten)).all().get(0);
            assertThat(actual.timeUnit()).isEqualTo(MINUTE);
            assertClose(actual.time(), expected.time(), "time");
            assertThat(actual.intensity()).isEqualTo(expected.intensity());
        }
    }

    /**
     * (c) The other way round: the chunk start in minutes and the values in seconds read in seconds, the start
     * multiplied by 60.
     */
    @Test
    void aChunkStartInMinutesIsConvertedToTheSecondsOfTheValues() {
        try (MzPeakSource source = MzPeakSource.open(SMALL_CHUNKED)) {
            InputFile metadata = source.inputFile(CHROMATOGRAMS_METADATA);
            InputFile data = source.inputFile(CHROMATOGRAMS_DATA);
            Path rewritten = tmp.resolve(CHROMATOGRAMS_DATA);
            Map<String, Edit> edits = new HashMap<>();
            edits.put("chunk.time_chunk_start", new Edit(MINUTE, v -> v));
            edits.put("chunk.time_chunk_values", new Edit(SECOND, v -> v * 60));
            rewrite(data, rewritten, SignalEntity.CHROMATOGRAM, edits);

            Chromatogram expected = ChromatogramStore.load(metadata, data).all().get(0);
            Chromatogram actual = ChromatogramStore.load(metadata, new LocalInputFile(rewritten)).all().get(0);
            assertThat(actual.timeUnit()).isEqualTo(SECOND);
            assertClose(actual.time(), scaled(expected.time(), v -> v * 60), "time in seconds");
        }
    }

    /**
     * (c) A chunked member whose array index names no time values column, its chunk start in seconds: the time unit
     * is the start column's, UO:0000010, and each chunk gives its stored start.
     */
    @Test
    void aChunkedMemberWithoutAValuesColumnTakesTheUnitOfTheStart() throws IOException {
        try (MzPeakSource source = MzPeakSource.open(SMALL_CHUNKED)) {
            InputFile metadata = source.inputFile(CHROMATOGRAMS_METADATA);
            InputFile data = source.inputFile(CHROMATOGRAMS_DATA);
            Path rewritten = tmp.resolve(CHROMATOGRAMS_DATA);
            rewrite(data, rewritten, SignalEntity.CHROMATOGRAM,
                    Map.of("chunk.time_chunk_start", new Edit(SECOND, v -> v * 60)));
            MemberRewrite.Member member = MemberRewrite.read(rewritten);
            member.keyValues.remove("writer.model.name");
            String key = SignalEntity.CHROMATOGRAM.arrayIndexKey();
            member.keyValues.put(key, withoutEntry(member.keyValues.get(key), "chunk.time_chunk_values"));
            MemberRewrite.write(member, rewritten);
            LocalInputFile edited = new LocalInputFile(rewritten);
            assertThat(plan(edited).axis().values()).isNull();
            assertThat(plan(edited).axis().start().unit()).isEqualTo(SECOND);

            Chromatogram expected = ChromatogramStore.load(metadata, data).all().get(0);
            Chromatogram actual = ChromatogramStore.load(metadata, edited).all().get(0);
            assertThat(actual.timeUnit()).isEqualTo(SECOND);
            List<Double> seconds = boxed(scaled(expected.time(), v -> v * 60));
            assertThat(actual.time()).isNotEmpty();
            for (double t : actual.time()) {
                assertThat(seconds).contains(t);
            }
        }
    }

    /** (c) Time values in a unit that is not converted, with a chunk start in minutes, throw naming the column. */
    @Test
    void aValuesColumnInAnUnknownUnitBesideAStartInMinutesIsRejected() {
        try (MzPeakSource source = MzPeakSource.open(SMALL_CHUNKED)) {
            InputFile metadata = source.inputFile(CHROMATOGRAMS_METADATA);
            Path rewritten = tmp.resolve(CHROMATOGRAMS_DATA);
            Map<String, Edit> edits = new HashMap<>();
            edits.put("chunk.time_chunk_start", new Edit(MINUTE, v -> v));
            edits.put("chunk.time_chunk_values", new Edit("UO:0000033", v -> v));
            rewrite(source.inputFile(CHROMATOGRAMS_DATA), rewritten, SignalEntity.CHROMATOGRAM, edits);

            assertThatThrownBy(() -> ChromatogramStore.load(metadata, new LocalInputFile(rewritten)))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("chromatogram data member: chromatogram time column chunk.time_chunk_values has the"
                            + " unit UO:0000033; supported are minute (UO:0000031), second (UO:0000010), millisecond"
                            + " (UO:0000028) and hour (UO:0000032)");
        }
    }

    /**
     * The time unit of a plan: of the values column, else of the start column, else minute; a column without a unit
     * gives minute, and a unit that is not converted is kept.
     */
    @Test
    void theTimeUnitOfAPlanFollowsOneRule() {
        try (MzPeakSource source = MzPeakSource.open(SMALL_CHUNKED)) {
            SignalPlan plan = plan(source.inputFile(CHROMATOGRAMS_DATA));
            SignalPlan.Axis axis = plan.axis();
            SignalPlan.Column start = axis.start();
            SignalPlan.Column values = axis.values();
            assertThat(ChromatogramDataDecoder.timeUnit(withAxis(plan, values, start))).isEqualTo(unit(values));
            assertThat(ChromatogramDataDecoder.timeUnit(withAxis(plan, null, start))).isEqualTo(unit(start));
            assertThat(ChromatogramDataDecoder.timeUnit(withAxis(plan, null, null))).isEqualTo(MINUTE);
            assertThat(ChromatogramDataDecoder.timeUnit(withAxis(plan, withUnit(values, null), start)))
                    .isEqualTo(MINUTE);
            assertThat(ChromatogramDataDecoder.timeUnit(withAxis(plan, withUnit(values, "UO:0000033"), start)))
                    .isEqualTo("UO:0000033");
        }
    }

    // ---- (d) the model -----------------------------------------------------------------------------------------

    /** (d) withTimeUnit converts among the four units with whole ratios, and the same unit gives {@code this}. */
    @Test
    void withTimeUnitConvertsAmongTheFourUnits() {
        Chromatogram minutes = chromatogram(new double[] {1.5, 3}, MINUTE);
        assertThat(minutes.withTimeUnit(MINUTE)).isSameAs(minutes);
        assertThat(minutes.withTimeUnit(SECOND).time()).containsExactly(90, 180);
        assertThat(minutes.withTimeUnit(MILLISECOND).time()).containsExactly(90_000, 180_000);
        assertThat(minutes.withTimeUnit(HOUR).time()).containsExactly(1.5 / 60, 3.0 / 60);
        assertThat(minutes.withTimeUnit(HOUR).timeUnit()).isEqualTo(HOUR);
        assertThat(chromatogram(new double[] {90}, SECOND).withTimeUnit(MINUTE).time()).containsExactly(1.5);
        assertThat(chromatogram(new double[] {1500}, MILLISECOND).withTimeUnit(SECOND).time()).containsExactly(1.5);
        assertThat(chromatogram(new double[] {2}, HOUR).withTimeUnit(MINUTE).time()).containsExactly(120);
        assertThat(minutes.time()).as("the source is not modified").containsExactly(1.5, 3);
    }

    /** (d) An unknown source or target unit throws, naming both. */
    @Test
    void withTimeUnitRejectsAnotherUnit() {
        assertThatThrownBy(() -> chromatogram(new double[] {1}, MINUTE).withTimeUnit("UO:0000033"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cannot convert the times of chromatogram 7 from UO:0000031 to UO:0000033; supported are"
                        + " minute (UO:0000031), second (UO:0000010), millisecond (UO:0000028) and hour"
                        + " (UO:0000032)");
        assertThatThrownBy(() -> chromatogram(new double[] {1}, "UO:0000033").withTimeUnit(SECOND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("cannot convert the times of chromatogram 7 from UO:0000033 to UO:0000010;");
        assertThatThrownBy(() -> chromatogram(new double[] {1}, MINUTE).withTimeUnit(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("cannot convert the times of chromatogram 7 from UO:0000031 to null;");
    }

    /** (d) withTimeUnit keeps every other component; equality includes timeUnit; null gives minute. */
    @Test
    void withTimeUnitKeepsTheOtherComponentsAndEqualityIncludesTheUnit() {
        Chromatogram seconds = chromatogram(new double[] {60, 120}, SECOND);
        Chromatogram minutes = seconds.withTimeUnit(MINUTE);
        assertThat(minutes.index()).isEqualTo(seconds.index());
        assertThat(minutes.id()).isEqualTo(seconds.id());
        assertThat(minutes.typeCurie()).isEqualTo(seconds.typeCurie());
        assertThat(minutes.intensity()).isSameAs(seconds.intensity());
        assertThat(minutes.intensityUnit()).isEqualTo(seconds.intensityUnit());
        assertThat(minutes.arrays()).isEqualTo(seconds.arrays());
        assertThat(minutes.polarity()).isEqualTo(seconds.polarity());
        assertThat(minutes.dataProcessingRef()).isEqualTo(seconds.dataProcessingRef());
        assertThat(minutes.parameters()).isEqualTo(seconds.parameters());
        assertThat(minutes.precursors()).isEqualTo(seconds.precursors());
        assertThat(minutes.time()).containsExactly(1, 2);

        Chromatogram other = new Chromatogram(seconds.index(), seconds.id(), seconds.typeCurie(), seconds.time(),
                seconds.intensity(), seconds.intensityUnit(), seconds.arrays(), seconds.polarity(),
                seconds.dataProcessingRef(), seconds.parameters(), seconds.precursors(), MILLISECOND);
        assertThat(other).isNotEqualTo(seconds);
        assertThat(other.withTimeUnit(MILLISECOND)).isSameAs(other);
        assertThat(new Chromatogram(seconds.index(), seconds.id(), seconds.typeCurie(), seconds.time(),
                seconds.intensity(), seconds.intensityUnit(), seconds.arrays(), seconds.polarity(),
                seconds.dataProcessingRef(), seconds.parameters(), seconds.precursors(), SECOND)).isEqualTo(seconds);
        assertThat(new Chromatogram(0, "c", null, null, null, null, null, null, null, null, null, null).timeUnit())
                .isEqualTo(MINUTE);
    }

    /** (d) The 5-, 7- and 11-argument constructors give minute. */
    @Test
    void theShorterConstructorsGiveMinute() {
        double[] time = {1};
        double[] intensity = {2};
        assertThat(new Chromatogram(0, "a", null, time, intensity).timeUnit()).isEqualTo(MINUTE);
        assertThat(new Chromatogram(0, "a", null, time, intensity, null, Map.of()).timeUnit()).isEqualTo(MINUTE);
        assertThat(new Chromatogram(0, "a", null, time, intensity, null, Map.of(), Polarity.POSITIVE, null,
                List.of(), List.of()).timeUnit()).isEqualTo(MINUTE);
    }

    // ---- (e) the writers at the V-21 head ----------------------------------------------------------------------

    /**
     * (e) The seconds copy of (b) read and written in both layouts, by a batch write and by a session, reads back in
     * minutes (UO:0000031, as the writer declares) with the fixture's minutes within 1e-12: converted, not
     * mislabelled.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(MetadataLayout.class)
    void aChromatogramInSecondsIsWrittenInMinutes(MetadataLayout layout) throws IOException {
        Path dir = secondsCopy();
        Options options = Options.defaults().withMetadataLayout(layout);
        Chromatogram fixture;
        Chromatogram seconds;
        try (MzPeakReader original = MzPeakReader.open(SMALL_UNPACKED); MzPeakReader reader = MzPeakReader.open(dir)) {
            fixture = original.chromatograms().get(0);
            seconds = reader.chromatograms().get(0);
        }
        assertThat(seconds.timeUnit()).isEqualTo(SECOND);
        Path batch = tmp.resolve("batch.mzpeak");
        MzPeakWriter.writeDirectory(batch, List.of(), List.of(seconds), List.of(), List.of(), FileMetadata.EMPTY,
                options);
        Path session = tmp.resolve("session.mzpeak");
        try (Session s = MzPeakWriter.openDirectory(session, FileMetadata.EMPTY, List.of(), Set.of(), options)) {
            s.addChromatogram(seconds);
            s.finish();
        }
        assertThat(seconds.time()).as("the caller's chromatogram is not modified")
                .containsExactly(scaled(fixture.time(), v -> v * 60));
        for (Path written : List.of(batch, session)) {
            try (MzPeakReader back = MzPeakReader.open(written)) {
                Chromatogram c = back.chromatograms().get(0);
                assertThat(c.timeUnit()).as("%s", written).isEqualTo(MINUTE);
                assertClose(c.time(), fixture.time(), written + " time");
                assertThat(c.intensity()).as("%s", written).isEqualTo(fixture.intensity());
            }
        }
    }

    /**
     * (e) A seconds chromatogram whose precursor names a chromatogram written under another index is written
     * (SPLIT) in minutes with the renumbered precursor; the package-private copy that remaps its precursors keeps
     * its time unit (the 12-argument copy).
     */
    @Test
    void aRemappedPrecursorKeepsTheTimeUnitOfItsCopy() {
        Chromatogram parent = new Chromatogram(5, "parent", null, new double[] {60}, new double[] {1}, null,
                Map.of(), Polarity.UNKNOWN, null, List.of(), List.of(), SECOND);
        Chromatogram child = new Chromatogram(9, "child", null, new double[] {120, 180}, new double[] {2, 3}, null,
                Map.of(), Polarity.UNKNOWN, null, List.of(),
                List.of(new Precursor(5L, null, null, List.of(), null)), SECOND);

        Chromatogram copy = WritePlanner.withWrittenPrecursors(child, 1, Map.of(5L, 0L, 9L, 1L),
                MetadataLayout.SPLIT);
        assertThat(copy).isNotSameAs(child);
        assertThat(copy.precursors().get(0).precursorIndex()).isEqualTo(0L);
        assertThat(copy.timeUnit()).isEqualTo(SECOND);
        assertThat(copy.time()).isSameAs(child.time());

        Path out = tmp.resolve("remapped.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(), List.of(parent, child));
        try (MzPeakReader back = MzPeakReader.open(out)) {
            List<Chromatogram> read = back.chromatograms();
            assertThat(read).extracting(Chromatogram::timeUnit).containsExactly(MINUTE, MINUTE);
            assertThat(read.get(0).time()).containsExactly(1);
            assertThat(read.get(1).time()).containsExactly(2, 3);
            assertThat(read.get(1).precursors().get(0).precursorIndex()).isEqualTo(0L);
        }
    }

    /** (e) A chromatogram in a unit the writers cannot convert to minutes is refused, by a batch and a session. */
    @Test
    void aChromatogramInAnUnknownUnitIsRefusedByTheWriters() {
        Chromatogram unknown = chromatogram(new double[] {1}, "UO:0000033");
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(tmp.resolve("batch.mzpeak"), List.of(),
                List.of(unknown)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("cannot convert the times of chromatogram 7 from UO:0000033 to UO:0000031;");
        try (Session s = MzPeakWriter.openDirectory(tmp.resolve("session.mzpeak"), FileMetadata.EMPTY, List.of(),
                Set.of(), Options.defaults())) {
            assertThatThrownBy(() -> s.addChromatogram(unknown))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("cannot convert the times of chromatogram 7 from UO:0000033 to");
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /** A copy of current small.unpacked whose chromatograms_data stores the times in seconds. */
    private Path secondsCopy() throws IOException {
        Path dir = MemberRewrite.copy(SMALL_UNPACKED, tmp.resolve("seconds"));
        rewriteInPlace(dir.resolve(CHROMATOGRAMS_DATA), SignalEntity.CHROMATOGRAM,
                Map.of("point.time", new Edit(SECOND, v -> v * 60)));
        return dir;
    }

    /** A chromatogram of index 7 with {@code time} in {@code unit}, Params and an extra array. */
    private static Chromatogram chromatogram(double[] time, String unit) {
        double[] intensity = new double[time.length];
        double[] level = new double[time.length];
        return new Chromatogram(7, "c", "MS:1000235", time, intensity, "MS:1000131", Map.of("ms level", level),
                Polarity.NEGATIVE, "dp", List.of(new Param("p", "MS:1000001", "v", null)),
                List.of(new Precursor(null, "scan=1", null, List.of(), null)), unit);
    }

    /** The signal plan of {@code member}, built as the reader builds it. */
    private static SignalPlan plan(InputFile member) {
        ParquetFooter footer = ParquetFooter.read(member);
        return SignalPlan.of(ArrayIndex.of(footer.keyValue(), footer.schema(), SignalEntity.CHROMATOGRAM),
                footer.schema(), SignalEntity.CHROMATOGRAM);
    }

    /** {@code plan} with the time values column {@code values} and the chunk start column {@code start}. */
    private static SignalPlan withAxis(SignalPlan plan, SignalPlan.Column values, SignalPlan.Column start) {
        SignalPlan.Axis axis = plan.axis();
        return new SignalPlan(plan.entity(), plan.source(), plan.layout(), plan.prefix(), plan.indexField(),
                new SignalPlan.Axis(axis.arrayType(), values, start, axis.end(), axis.encoding(),
                        axis.transformFields()), plan.intensities(), plan.extras());
    }

    /** {@code column} with the unit {@code unit}. */
    private static SignalPlan.Column withUnit(SignalPlan.Column column, String unit) {
        return new SignalPlan.Column(column.path(), column.field(), column.arrayType(), column.dataType(), unit,
                column.format(), column.transform());
    }

    /** The unit {@code column} gives a chromatogram: its own, minute when it has none. */
    private static String unit(SignalPlan.Column column) {
        return column.unit() == null ? MINUTE : column.unit();
    }

    /** The array index JSON {@code json} without the entry of {@code path}. */
    private static String withoutEntry(String json, String path) throws IOException {
        ObjectNode index = (ObjectNode) MAPPER.readTree(json);
        ArrayNode entries = (ArrayNode) index.path("entries");
        for (int i = 0; i < entries.size(); i++) {
            JsonNode entry = entries.get(i);
            if (entry.path("path").asText().equals(path)) {
                entries.remove(i);
                return MAPPER.writeValueAsString(index);
            }
        }
        throw new AssertionError("no array index entry " + path);
    }

    private static double[] scaled(double[] values, DoubleUnaryOperator f) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = f.applyAsDouble(values[i]);
        }
        return out;
    }

    private static List<Double> boxed(double[] values) {
        return java.util.Arrays.stream(values).boxed().toList();
    }

    /** Asserts that {@code actual[i]} differs from {@code expected[i]} by at most 1e-12 × |expected[i]|. */
    private static void assertClose(double[] actual, double[] expected, String what) {
        assertThat(actual).as(what).hasSameSizeAs(expected);
        assertThat(expected).as(what).isNotEmpty();
        for (int i = 0; i < expected.length; i++) {
            assertThat(Math.abs(actual[i] - expected[i])).as("%s[%d]: %s vs %s", what, i, actual[i], expected[i])
                    .isLessThanOrEqualTo(RELATIVE * Math.abs(expected[i]));
        }
    }

    /**
     * The SHA-256 of the index, id, time and intensity bits of each of {@code chromatograms}, in order; with
     * {@code slof} the intensities rounded to float32 ({@link SignalSnapshotTest.SignalDigest#float32}).
     */
    static String digest(List<Chromatogram> chromatograms, boolean slof) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        for (Chromatogram c : chromatograms) {
            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES * (2 + 2 * c.size()));
            buffer.putLong(c.index()).putLong(c.size());
            for (double t : c.time()) {
                buffer.putLong(Double.doubleToRawLongBits(t));
            }
            for (double v : slof ? SignalSnapshotTest.SignalDigest.float32(c.intensity()) : c.intensity()) {
                buffer.putLong(Double.doubleToRawLongBits(v));
            }
            sha.update(buffer.array());
            sha.update(String.valueOf(c.id()).getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(sha.digest());
    }
}
