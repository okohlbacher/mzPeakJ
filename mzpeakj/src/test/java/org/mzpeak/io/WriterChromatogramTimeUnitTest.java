package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mzpeak.io.MemberRewriter.rewriteInPlace;

/**
 * D-12b (V-22): the writer writes the chromatogram time unit. chromatograms_data declares the time column in the
 * {@link Chromatogram#timeUnit()} of the written chromatograms, in both metadata layouts, by a batch write and by a
 * session, and the times are written as given. Chromatograms of different time units are refused before anything is
 * written: the writer converts nothing ({@link Chromatogram#withTimeUnit} does, when the caller asks).
 */
class WriterChromatogramTimeUnitTest {

    private static final Path SMALL_UNPACKED = Fixtures.current("small.unpacked.mzpeak");
    private static final String CHROMATOGRAMS_DATA = "chromatograms_data.parquet";
    private static final String TIME_ARRAY = "MS:1000595";
    private static final String MINUTE = "UO:0000031";
    private static final String SECOND = "UO:0000010";
    private static final String MIXED = "chromatograms 0 (UO:0000031) and 2 (UO:0000010) have different time units;"
            + " convert one with Chromatogram.withTimeUnit before writing";

    @TempDir
    Path tmp;

    /**
     * A copy of current small.unpacked whose chromatogram times are stored in seconds, read and written by a batch
     * write and by a session: chromatograms_data declares the time column in UO:0000010, and the chromatogram reads
     * back in UO:0000010 with the bits of the times it was written with. Before V-22 it was declared and read in
     * minutes.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(MetadataLayout.class)
    void aChromatogramInSecondsIsWrittenInSeconds(MetadataLayout layout) throws IOException {
        Chromatogram seconds = secondsChromatogram(tmp);
        assertThat(seconds.timeUnit()).isEqualTo(SECOND);
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
            assertThat(declaredTimeUnit(written)).as("%s time column", written).isEqualTo(SECOND);
            try (MzPeakReader back = MzPeakReader.open(written)) {
                Chromatogram c = back.chromatograms().get(0);
                assertThat(c.timeUnit()).as("%s", written).isEqualTo(SECOND);
                assertThat(bits(c.time())).as("%s time", written).containsExactly(bits(seconds.time()));
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
     * A time unit {@link Chromatogram#withTimeUnit} does not convert is written as given too, and the manifest
     * declares its CV, which nothing else of the dataset uses.
     */
    @Test
    void anotherTimeUnitIsWrittenAsGivenAndItsCvDeclared() throws IOException {
        String unit = "NCIT:C42535";
        Chromatogram c = chromatogram(0, unit, List.of());
        Path out = tmp.resolve("other.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(), List.of(c));
        assertThat(declaredTimeUnit(out)).isEqualTo(unit);
        JsonNode cvList = new ObjectMapper().readTree(out.resolve("mzpeak_index.json").toFile())
                .path("metadata").path("cv_list");
        List<String> ids = new ArrayList<>();
        cvList.forEach(cv -> ids.add(cv.path("id").asText()));
        assertThat(ids).containsExactly("MS", "UO", "NCIT");
        try (MzPeakReader back = MzPeakReader.open(out)) {
            Chromatogram read = back.chromatograms().get(0);
            assertThat(read.timeUnit()).isEqualTo(unit);
            assertThat(bits(read.time())).containsExactly(bits(c.time()));
        }
    }

    /** A batch write of chromatograms in minutes and seconds is refused before anything is written. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(MetadataLayout.class)
    void chromatogramsOfDifferentTimeUnitsAreRefusedByABatchWrite(MetadataLayout layout) {
        List<Chromatogram> mixed = List.of(chromatogram(0, MINUTE, List.of()), chromatogram(1, MINUTE, List.of()),
                chromatogram(2, SECOND, List.of()));
        Path archive = tmp.resolve("batch.mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.writeArchive(archive, List.of(), mixed, List.of(), List.of(),
                FileMetadata.EMPTY, Options.defaults().withMetadataLayout(layout)))
                .isInstanceOf(MzPeakException.class)
                .hasMessage(MIXED);
        assertThat(archive).doesNotExist();
        Path directory = tmp.resolve("batch-dir.mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(directory, List.of(), mixed))
                .isInstanceOf(MzPeakException.class)
                .hasMessage(MIXED);
        assertThat(directory).doesNotExist();
    }

    /**
     * A session refuses a chromatogram whose time unit differs from that of the first one added, at the call; the
     * chromatograms of the first unit are still accepted, and closing the session leaves no target.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(MetadataLayout.class)
    void aSessionRefusesAChromatogramOfAnotherTimeUnit(MetadataLayout layout) {
        Path archive = tmp.resolve("session.mzpeak");
        try (Session s = MzPeakWriter.openArchive(archive, FileMetadata.EMPTY, List.of(), Set.of(),
                Options.defaults().withMetadataLayout(layout))) {
            s.addChromatogram(chromatogram(0, MINUTE, List.of()));
            s.addChromatogram(chromatogram(1, MINUTE, List.of()));
            assertThatThrownBy(() -> s.addChromatogram(chromatogram(2, SECOND, List.of())))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(MIXED);
        }
        assertThat(archive).doesNotExist();
    }

    /**
     * A seconds chromatogram whose precursor names another chromatogram, written together with that one under new
     * indices (so the writer copies it with the renumbered precursor), reads back in seconds with its times and the
     * renumbered precursor, by a batch write and by a session.
     */
    @Test
    void aChromatogramWithARenumberedPrecursorKeepsItsTimeUnit() throws IOException {
        Chromatogram parent = chromatogram(5, SECOND, List.of());
        Chromatogram child = chromatogram(9, SECOND, List.of(new Precursor(5L, null, null, List.of(), null)));
        for (Path written : writtenBothWays(List.of(parent, child))) {
            try (MzPeakReader back = MzPeakReader.open(written)) {
                List<Chromatogram> read = back.chromatograms();
                assertThat(read).extracting(Chromatogram::timeUnit).as("%s", written).containsExactly(SECOND, SECOND);
                assertThat(bits(read.get(0).time())).containsExactly(bits(parent.time()));
                assertThat(bits(read.get(1).time())).containsExactly(bits(child.time()));
                assertThat(read.get(1).precursors()).singleElement()
                        .satisfies(p -> assertThat(p.precursorIndex()).isEqualTo(0L));
            }
        }
    }

    /**
     * A seconds chromatogram whose precursor names a chromatogram that is not written reads back in seconds with its
     * times and a precursor without index, by a batch write and by a session.
     */
    @Test
    void aChromatogramWhosePrecursorIsNotWrittenKeepsItsTimeUnit() throws IOException {
        Chromatogram child = chromatogram(9, SECOND, List.of(new Precursor(5L, "parent", null, List.of(), null)));
        for (Path written : writtenBothWays(List.of(child))) {
            try (MzPeakReader back = MzPeakReader.open(written)) {
                Chromatogram read = back.chromatograms().get(0);
                assertThat(read.timeUnit()).as("%s", written).isEqualTo(SECOND);
                assertThat(bits(read.time())).containsExactly(bits(child.time()));
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
