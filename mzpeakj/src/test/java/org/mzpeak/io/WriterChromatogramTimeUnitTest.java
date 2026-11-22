package org.mzpeak.io;

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
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mzpeak.io.MemberRewriter.rewriteInPlace;

/**
 * W3 (D13-1): the writer stores chromatogram times in minutes (chromatograms.md: "the units MUST be minutes").
 * chromatograms_data declares its time column in UO:0000031, in both metadata layouts, by a batch write and by a
 * session. A chromatogram in seconds, milliseconds or hours is converted once with {@link Chromatogram#withTimeUnit},
 * so chromatograms of different units are written together; one in any other unit is refused before anything of it
 * is written. Until W3 the writer wrote the given unit and refused chromatograms of different units (D-12b, V-22).
 */
class WriterChromatogramTimeUnitTest {

    private static final Path SMALL_UNPACKED = Fixtures.current("small.unpacked.mzpeak");
    private static final String CHROMATOGRAMS_DATA = "chromatograms_data.parquet";
    private static final String TIME_ARRAY = "MS:1000595";
    private static final String MINUTE = "UO:0000031";
    private static final String SECOND = "UO:0000010";
    private static final String MILLISECOND = "UO:0000028";
    private static final String HOUR = "UO:0000032";
    private static final String UNKNOWN = "UO:0000099";
    private static final String REFUSED = "chromatogram 3: time unit UO:0000099 cannot be converted to minutes";

    @TempDir
    Path tmp;

    /**
     * A copy of current small.unpacked whose chromatogram times are stored in seconds, read and written by a batch
     * write and by a session: chromatograms_data declares the time column in UO:0000031, and the chromatogram reads
     * back in UO:0000031 with the bits of its times converted by {@link Chromatogram#withTimeUnit}. Until W3 it was
     * declared and read in seconds.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(MetadataLayout.class)
    void aChromatogramInSecondsIsWrittenInMinutes(MetadataLayout layout) throws IOException {
        Chromatogram seconds = secondsChromatogram(tmp);
        assertThat(seconds.timeUnit()).isEqualTo(SECOND);
        Chromatogram minutes = seconds.withTimeUnit(MINUTE);
        Options options = Options.defaults().withMetadataLayout(layout);
        Path batch = tmp.resolve("batch.mzpeak");
        MzPeakWriter.writeDirectory(batch, List.of(), List.of(seconds), List.of(), List.of(), FileMetadata.EMPTY,
                options);
        Path session = tmp.resolve("session.mzpeak");
        try (Session s = MzPeakWriter.openDirectory(session, FileMetadata.EMPTY, List.of(), Set.of(), options)) {
            s.addChromatogram(seconds);
            s.finish();
        }
        for (Path written : List.of(batch, session)) {
            assertThat(declaredTimeUnit(written)).as("%s time column", written).isEqualTo(MINUTE);
            try (MzPeakReader back = MzPeakReader.open(written)) {
                Chromatogram c = back.chromatograms().get(0);
                assertThat(c.timeUnit()).as("%s", written).isEqualTo(MINUTE);
                assertThat(bits(c.time())).as("%s time", written).containsExactly(bits(minutes.time()));
                assertThat(bits(c.intensity())).as("%s intensity", written)
                        .containsExactly(bits(seconds.intensity()));
            }
        }
    }

    /** Minute chromatograms, the only unit of the fixtures, are still declared in minutes. */
    @Test
    void aChromatogramInMinutesIsWrittenInMinutes() throws IOException {
        Chromatogram minutes = chromatogram(0, MINUTE, List.of());
        Path out = tmp.resolve("minutes.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(), List.of(minutes));
        assertThat(declaredTimeUnit(out)).isEqualTo(MINUTE);
    }

    /**
     * Chromatograms in minutes, written by a batch write and by a session in both metadata layouts, are declared in
     * minutes and read back in minutes with the bits of the times and intensities they were written with, in index
     * order.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(MetadataLayout.class)
    void chromatogramsInMinutesAreWrittenBitForBit(MetadataLayout layout) throws IOException {
        List<Chromatogram> minutes = List.of(chromatogram(0, MINUTE, List.of()), chromatogram(1, MINUTE, List.of()));
        Options options = Options.defaults().withMetadataLayout(layout);
        Path batch = tmp.resolve("batch.mzpeak");
        MzPeakWriter.writeDirectory(batch, List.of(), minutes, List.of(), List.of(), FileMetadata.EMPTY, options);
        Path session = tmp.resolve("session.mzpeak");
        try (Session s = MzPeakWriter.openDirectory(session, FileMetadata.EMPTY, List.of(), Set.of(), options)) {
            minutes.forEach(s::addChromatogram);
            s.finish();
        }
        for (Path written : List.of(batch, session)) {
            assertThat(declaredTimeUnit(written)).as("%s time column", written).isEqualTo(MINUTE);
            try (MzPeakReader back = MzPeakReader.open(written)) {
                List<Chromatogram> read = back.chromatograms();
                assertThat(read).extracting(Chromatogram::timeUnit).containsExactly(MINUTE, MINUTE);
                for (int i = 0; i < minutes.size(); i++) {
                    assertThat(bits(read.get(i).time())).as("%s time %d", written, i)
                            .containsExactly(bits(minutes.get(i).time()));
                    assertThat(bits(read.get(i).intensity())).as("%s intensity %d", written, i)
                            .containsExactly(bits(minutes.get(i).intensity()));
                }
            }
        }
    }

    /** Times in each unit the writer converts, and the minutes it stores for them. */
    static Stream<Arguments> units() {
        return Stream.of(
                Arguments.of(SECOND, new double[] {30, 60}, new double[] {0.5, 1.0}),
                Arguments.of(MILLISECOND, new double[] {30_000, 60_000}, new double[] {0.5, 1.0}),
                Arguments.of(HOUR, new double[] {0.5}, new double[] {30.0}));
    }

    /**
     * A TIC with its times in seconds ({@code [30, 60]}), milliseconds ({@code [30000, 60000]}) or hours
     * ({@code [0.5]}) is written in minutes ({@code [0.5, 1.0]}, {@code [30.0]}), its time column declared in
     * UO:0000031, by a batch write and by a session; the caller's TIC keeps its unit and times.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("units")
    void aTicIsWrittenInMinutes(String unit, double[] time, double[] minutes) throws IOException {
        Chromatogram tic = new Chromatogram(0, "TIC", "MS:1000235", time, new double[time.length], null, Map.of(),
                Polarity.UNKNOWN, null, List.of(), List.of(), unit);
        for (Path written : writtenBothWays(List.of(tic))) {
            assertThat(declaredTimeUnit(written)).as("%s time column", written).isEqualTo(MINUTE);
            try (MzPeakReader back = MzPeakReader.open(written)) {
                Chromatogram read = back.chromatograms().get(0);
                assertThat(read.timeUnit()).as("%s", written).isEqualTo(MINUTE);
                assertThat(read.time()).as("%s time", written).containsExactly(minutes);
            }
        }
        assertThat(tic.timeUnit()).isEqualTo(unit);
        assertThat(tic.time()).containsExactly(time);
    }

    /**
     * Chromatograms of four units in one batch write, in both metadata layouts, all land in minutes; the caller's
     * chromatograms keep their units.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(MetadataLayout.class)
    void chromatogramsOfDifferentUnitsInOneBatchAreAllWrittenInMinutes(MetadataLayout layout) throws IOException {
        List<Chromatogram> mixed = List.of(timed(0, MINUTE, 1.5), timed(1, SECOND, 90), timed(2, MILLISECOND, 90_000),
                timed(3, HOUR, 0.025));
        Path archive = tmp.resolve("mixed.mzpeak");
        MzPeakWriter.writeArchive(archive, List.of(), mixed, List.of(), List.of(), FileMetadata.EMPTY,
                Options.defaults().withMetadataLayout(layout));
        try (MzPeakReader back = MzPeakReader.open(archive)) {
            List<Chromatogram> read = back.chromatograms();
            assertThat(read).extracting(Chromatogram::timeUnit).containsExactly(MINUTE, MINUTE, MINUTE, MINUTE);
            for (Chromatogram c : read) {
                assertThat(c.time()).as("chromatogram %d", c.index()).containsExactly(1.5);
            }
        }
        assertThat(mixed).extracting(Chromatogram::timeUnit).containsExactly(MINUTE, SECOND, MILLISECOND, HOUR);
    }

    /** A session that is given a chromatogram in seconds, then one in minutes, writes both in minutes. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(MetadataLayout.class)
    void aSessionWritesChromatogramsInSecondsAndMinutesInMinutes(MetadataLayout layout) throws IOException {
        Path session = tmp.resolve("session.mzpeak");
        try (Session s = MzPeakWriter.openDirectory(session, FileMetadata.EMPTY, List.of(), Set.of(),
                Options.defaults().withMetadataLayout(layout))) {
            s.addChromatogram(timed(0, SECOND, 30, 60));
            s.addChromatogram(timed(1, MINUTE, 0.25, 0.75));
            s.finish();
        }
        assertThat(declaredTimeUnit(session)).isEqualTo(MINUTE);
        try (MzPeakReader back = MzPeakReader.open(session)) {
            List<Chromatogram> read = back.chromatograms();
            assertThat(read).extracting(Chromatogram::timeUnit).containsExactly(MINUTE, MINUTE);
            assertThat(read.get(0).time()).containsExactly(0.5, 1.0);
            assertThat(read.get(1).time()).containsExactly(0.25, 0.75);
        }
    }

    /**
     * A batch write of a chromatogram whose time unit cannot be converted to minutes is refused before anything is
     * written, naming the chromatogram and the unit.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(MetadataLayout.class)
    void anUnconvertibleTimeUnitIsRefusedByABatchWrite(MetadataLayout layout) {
        List<Chromatogram> chromatograms = List.of(timed(0, MINUTE, 1), timed(3, UNKNOWN, 1));
        Path archive = tmp.resolve("batch.mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.writeArchive(archive, List.of(), chromatograms, List.of(), List.of(),
                FileMetadata.EMPTY, Options.defaults().withMetadataLayout(layout)))
                .isInstanceOf(MzPeakException.class)
                .hasMessage(REFUSED);
        assertThat(archive).doesNotExist();
        Path directory = tmp.resolve("batch-dir.mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(directory, List.of(), chromatograms))
                .isInstanceOf(MzPeakException.class)
                .hasMessage(REFUSED);
        assertThat(directory).doesNotExist();
    }

    /**
     * A session refuses a chromatogram whose time unit cannot be converted to minutes at the call; the chromatogram
     * added before it is written, the refused one is not.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(MetadataLayout.class)
    void aSessionRefusesAnUnconvertibleTimeUnitAtAdd(MetadataLayout layout) throws IOException {
        Path archive = tmp.resolve("session.mzpeak");
        try (Session s = MzPeakWriter.openArchive(archive, FileMetadata.EMPTY, List.of(), Set.of(),
                Options.defaults().withMetadataLayout(layout))) {
            s.addChromatogram(timed(0, SECOND, 60));
            assertThatThrownBy(() -> s.addChromatogram(timed(3, UNKNOWN, 1)))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(REFUSED);
            s.finish();
        }
        try (MzPeakReader back = MzPeakReader.open(archive)) {
            assertThat(back.chromatograms()).singleElement().satisfies(c -> {
                assertThat(c.id()).isEqualTo("c0");
                assertThat(c.timeUnit()).isEqualTo(MINUTE);
                assertThat(c.time()).containsExactly(1.0);
            });
        }
    }

    /**
     * A seconds chromatogram whose precursor names another chromatogram, written together with that one under new
     * indices (so the writer copies it with the renumbered precursor), reads back in minutes with its times converted
     * and the renumbered precursor, by a batch write and by a session.
     */
    @Test
    void aChromatogramWithARenumberedPrecursorIsWrittenInMinutes() throws IOException {
        Chromatogram parent = chromatogram(5, SECOND, List.of());
        Chromatogram child = chromatogram(9, SECOND, List.of(new Precursor(5L, null, null, List.of(), null)));
        for (Path written : writtenBothWays(List.of(parent, child))) {
            try (MzPeakReader back = MzPeakReader.open(written)) {
                List<Chromatogram> read = back.chromatograms();
                assertThat(read).extracting(Chromatogram::timeUnit).as("%s", written).containsExactly(MINUTE, MINUTE);
                assertThat(bits(read.get(0).time())).containsExactly(bits(parent.withTimeUnit(MINUTE).time()));
                assertThat(bits(read.get(1).time())).containsExactly(bits(child.withTimeUnit(MINUTE).time()));
                assertThat(read.get(1).precursors()).singleElement()
                        .satisfies(p -> assertThat(p.precursorIndex()).isEqualTo(0L));
            }
        }
    }

    /**
     * A seconds chromatogram whose precursor names a chromatogram that is not written reads back in minutes with its
     * times converted and a precursor without index, by a batch write and by a session.
     */
    @Test
    void aChromatogramWhosePrecursorIsNotWrittenIsWrittenInMinutes() throws IOException {
        Chromatogram child = chromatogram(9, SECOND, List.of(new Precursor(5L, "parent", null, List.of(), null)));
        for (Path written : writtenBothWays(List.of(child))) {
            try (MzPeakReader back = MzPeakReader.open(written)) {
                Chromatogram read = back.chromatograms().get(0);
                assertThat(read.timeUnit()).as("%s", written).isEqualTo(MINUTE);
                assertThat(bits(read.time())).containsExactly(bits(child.withTimeUnit(MINUTE).time()));
                assertThat(read.precursors()).singleElement().satisfies(p -> {
                    assertThat(p.precursorIndex()).isNull();
                    assertThat(p.precursorId()).isEqualTo("parent");
                });
            }
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /**
     * The chromatogram of a copy, made under {@code tmp}, of current small.unpacked whose chromatograms_data stores
     * the times in seconds (the fixture's times × 60). The opt-in reader checks write it too.
     */
    static Chromatogram secondsChromatogram(Path tmp) throws IOException {
        Path dir = MemberRewrite.copy(SMALL_UNPACKED, tmp.resolve("seconds"));
        rewriteInPlace(dir.resolve(CHROMATOGRAMS_DATA), SignalEntity.CHROMATOGRAM,
                Map.of("point.time", new Edit(SECOND, v -> v * 60)));
        try (MzPeakReader reader = MzPeakReader.open(dir)) {
            return reader.chromatograms().get(0);
        }
    }

    /** {@code chromatograms} written in the split layout by a batch write and by a session, in that order. */
    private List<Path> writtenBothWays(List<Chromatogram> chromatograms) {
        Path batch = tmp.resolve("batch.mzpeak");
        MzPeakWriter.writeDirectory(batch, List.of(), chromatograms);
        Path session = tmp.resolve("session.mzpeak");
        try (Session s = MzPeakWriter.openDirectory(session, FileMetadata.EMPTY, List.of(), Set.of(),
                Options.defaults())) {
            for (Chromatogram c : chromatograms) {
                s.addChromatogram(c);
            }
            s.finish();
        }
        return List.of(batch, session);
    }

    /** A chromatogram of {@code index} with three points whose times are in {@code unit}. */
    private static Chromatogram chromatogram(long index, String unit, List<Precursor> precursors) {
        return new Chromatogram(index, "c" + index, null, new double[] {index + 0.25, index + 0.5, index + 0.1 * 9},
                new double[] {1, 2, 3}, null, Map.of(), Polarity.UNKNOWN, null, List.of(), precursors, unit);
    }

    /** A chromatogram of {@code index} with the times {@code time} in {@code unit}. */
    private static Chromatogram timed(long index, String unit, double... time) {
        return new Chromatogram(index, "c" + index, null, time, new double[time.length], null, Map.of(),
                Polarity.UNKNOWN, null, List.of(), List.of(), unit);
    }

    /** The unit the chromatograms_data of the dataset {@code dir} declares for its time column. */
    private static String declaredTimeUnit(Path dir) {
        ParquetFooter footer = ParquetFooter.read(new LocalInputFile(dir.resolve(CHROMATOGRAMS_DATA)));
        List<String> units = new ArrayList<>();
        for (ArrayIndex.Entry e : ArrayIndex.parse(footer.keyValue().get("chromatogram_array_index"),
                SignalEntity.CHROMATOGRAM).entries()) {
            if (e.arrayType().equals(TIME_ARRAY)) {
                units.add(e.unit());
            }
        }
        assertThat(units).as("time columns").hasSize(1);
        return units.get(0);
    }

    private static long[] bits(double[] values) {
        long[] out = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = Double.doubleToRawLongBits(values[i]);
        }
        return out;
    }
}
