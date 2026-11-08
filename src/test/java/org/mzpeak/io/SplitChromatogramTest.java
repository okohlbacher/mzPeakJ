package org.mzpeak.io;

import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.GoldenMeta.Row;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Polarity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

/**
 * RD-8: chromatogram metadata in the split layout of the current fixtures (HUPO-PSI/mzPeak 2ee2589), read through
 * {@link ChromatogramMetadataDecoder} and {@link MzPeakReader}. Headers are checked against the raw pyarrow cells
 * ({@link GoldenMeta}, FX-2), time and intensity against the HEAD Python golden ({@link Golden}); the chromatogram
 * data member is decoded as before. The chromatogram precursors and selected-ions facets are not decoded.
 */
class SplitChromatogramTest {

    private static final String TIC = "TIC";
    private static final String BPC = "BPC";
    private static final String DAD1 = "DAD1 A: Sig=272,16 Ref=360,100";

    /**
     * small.numpress stores the TIC intensity as lossy MS-Numpress SLOF bytes; the Python reader rounds the decoded
     * values to float32, so they differ from mzPeakJ's doubles by up to float32's unit roundoff, 2^-24.
     */
    private static final Golden.Tolerance NUMPRESS = new Golden.Tolerance(Golden.Tolerance.DEFAULT.filledAbsDa(),
            Golden.Tolerance.DEFAULT.relExact(), Golden.Tolerance.DEFAULT.relFilled(), Math.scalb(1.0, -24));

    @TempDir
    Path tmp;

    static Stream<String> currentFixtures() {
        return Fixtures.CURRENT_NAMES.stream();
    }

    private static List<Chromatogram> chromatograms(String fixture) {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            return reader.chromatograms();
        }
    }

    /**
     * Compares {@code c} point by point, and by its summary line, with the golden of the chromatogram with its id:
     * time exactly, intensity exactly or within {@code tolerance}.
     */
    private static void assertMatchesGolden(String fixture, Chromatogram c, Golden.Tolerance tolerance) {
        Golden golden = Golden.load(fixture);
        Golden.FullArray expected = golden.full(Golden.Kind.C, c.id()).orElseThrow();
        assertThat(Golden.Comparator.compareFull(expected, c.time(), c.intensity(), tolerance))
                .as("%s %s", fixture, c.id()).isEmpty();
        assertThat(Golden.Comparator.compare(golden.line(Golden.Kind.C, c.id()).orElseThrow(),
                c.time(), c.intensity(), tolerance)).as("%s %s", fixture, c.id()).isEmpty();
    }

    /**
     * The chunked containers store the TIC time axis as a start value and deltas. mzPeakJ accumulates them point by
     * point, which reproduces the point layout's stored times exactly; the Python reader computes
     * {@code start + cumsum(deltas)}, which differs from them by 1 ulp at 3 of the 48 points. So the time axis
     * equals small.unpacked's exactly and the golden's within 1 ulp; intensities are compared with the golden as in
     * {@link #assertMatchesGolden}.
     */
    private static void assertMatchesChunkedGolden(String fixture, Chromatogram c, Golden.Tolerance tolerance) {
        Golden.FullArray expected = Golden.load(fixture).full(Golden.Kind.C, c.id()).orElseThrow();
        assertThat(c.time()).hasSameSizeAs(expected.x());
        assertThat(Golden.Comparator.compareFull(expected, expected.x(), c.intensity(), tolerance))
                .as("%s %s", fixture, c.id()).isEmpty();
        assertThat(c.time()).isEqualTo(chromatograms("small.unpacked.mzpeak").get(0).time());
        int offByOneUlp = 0;
        for (int i = 0; i < c.size(); i++) {
            double want = expected.x()[i];
            assertThat(c.time()[i]).as("%s %s time[%d]", fixture, c.id(), i).isCloseTo(want, within(Math.ulp(want)));
            if (c.time()[i] != want) {
                offByOneUlp++;
            }
        }
        assertThat(offByOneUlp).as("%s %s time values 1 ulp off the golden", fixture, c.id()).isEqualTo(3);
    }

    // ---- (a) small ------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"small.mzpeak", "small.chunked.mzpeak", "small.unpacked.mzpeak"})
    void smallHasOneTicOf48PointsAsTheGolden(String fixture) {
        List<Chromatogram> all = chromatograms(fixture);
        assertThat(all).hasSize(1);
        Chromatogram tic = all.get(0);
        assertThat(tic.index()).isZero();
        assertThat(tic.id()).isEqualTo(TIC);
        assertThat(tic.typeCurie()).isEqualTo("MS:1000235");
        assertThat(tic.size()).isEqualTo(48);
        if (fixture.equals("small.chunked.mzpeak")) {
            assertMatchesChunkedGolden(fixture, tic, Golden.Tolerance.DEFAULT);
        } else {
            assertMatchesGolden(fixture, tic, Golden.Tolerance.DEFAULT);
        }
    }

    @Test
    void smallNumpressTicIsWithinTheNumpressToleranceOfTheGolden() {
        List<Chromatogram> all = chromatograms("small.numpress.mzpeak");
        assertThat(all).hasSize(1);
        Chromatogram tic = all.get(0);
        assertThat(tic.id()).isEqualTo(TIC);
        assertThat(tic.typeCurie()).isEqualTo("MS:1000235");
        assertThat(tic.size()).isEqualTo(48);
        assertMatchesChunkedGolden("small.numpress.mzpeak", tic, NUMPRESS);
        // the golden holds mzPeakJ's decoded intensities rounded to float32
        double[] golden = Golden.load("small.numpress.mzpeak").full(Golden.Kind.C, TIC).orElseThrow().y();
        for (int i = 0; i < golden.length; i++) {
            assertThat((double) (float) tic.intensity()[i]).as("float32 intensity[%d]", i).isEqualTo(golden[i]);
        }
        // the lossy intensities stay within 1e-3 relative of the point-layout TIC
        Chromatogram point = chromatograms("small.unpacked.mzpeak").get(0);
        for (int i = 0; i < point.size(); i++) {
            double want = point.intensity()[i];
            assertThat(tic.intensity()[i]).as("intensity[%d]", i).isCloseTo(want, within(1e-3 * Math.abs(want)));
        }
    }

    // ---- (b) has_uv -----------------------------------------------------------------------------------------

    @Test
    void hasUvHasTheTicAndTheDad1AbsorbanceExactlyAsTheGolden() {
        List<Chromatogram> all = chromatograms("has_uv.mzpeak");
        assertThat(all).extracting(Chromatogram::index).containsExactly(0L, 1L);
        assertThat(all).extracting(Chromatogram::id).containsExactly(TIC, DAD1);
        assertThat(all).extracting(Chromatogram::typeCurie).containsExactly("MS:1000235", "MS:1000812");
        assertThat(all).extracting(Chromatogram::size).containsExactly(212, 526);
        assertThat(Arrays.stream(all.get(1).intensity()).sum()).isCloseTo(57.3587, within(1e-4));
        for (Chromatogram c : all) {
            assertMatchesGolden("has_uv.mzpeak", c, Golden.Tolerance.DEFAULT);
        }
    }

    // ---- (c) diaPASEF ---------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"diaPASEF.ref.mzpeak", "diaPASEF.grid.mzpeak"})
    void diaPasefHasTicAndBpcOf9PointsExactlyAsTheGolden(String fixture) {
        List<Chromatogram> all = chromatograms(fixture);
        assertThat(all).extracting(Chromatogram::index).containsExactly(0L, 1L);
        assertThat(all).extracting(Chromatogram::id).containsExactly(TIC, BPC);
        assertThat(all).extracting(Chromatogram::typeCurie).containsExactly("MS:1000235", "MS:1000628");
        assertThat(all).extracting(Chromatogram::size).containsExactly(9, 9);
        for (Chromatogram c : all) {
            assertMatchesGolden(fixture, c, Golden.Tolerance.DEFAULT);
        }
    }

    // ---- (d) headers against the raw cells ------------------------------------------------------------------

    /** Index, id and type of every chromatogram equal the cells of the member listed as (chromatogram, metadata). */
    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void headersEqualTheRawCells(String fixture) {
        List<Row> cells;
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current(fixture))) {
            Optional<Entry> entry = Facet.CHROMATOGRAM.entry(MzPeakManifest.fromSource(source));
            cells = entry.map(e -> GoldenMeta.load(fixture).rows(e.name())).orElse(List.of());
            if (entry.isPresent()) {
                assertThat(ChromatogramMetadataDecoder.decode(source.inputFile(entry.get().name()), entry.get()))
                        .extracting(ChromatogramMetadataDecoder.Header::index,
                                ChromatogramMetadataDecoder.Header::id,
                                ChromatogramMetadataDecoder.Header::typeCurie)
                        .containsExactlyElementsOf(cells.stream()
                                .map(r -> tuple(r.get("index"), r.get("id"), r.get("chromatogram_type")))
                                .toList());
            }
        }
        List<Chromatogram> all = chromatograms(fixture);
        assertThat(all).extracting(Chromatogram::index)
                .containsExactlyElementsOf(cells.stream().map(r -> (Long) r.get("index")).toList());
        assertThat(all).extracting(Chromatogram::id)
                .containsExactlyElementsOf(cells.stream().map(r -> (String) r.get("id")).toList());
        assertThat(all).extracting(Chromatogram::typeCurie)
                .containsExactlyElementsOf(cells.stream().map(r -> (String) r.get("chromatogram_type")).toList());
        if (fixture.startsWith("Example_Processed.img")) {
            assertThat(all).isEmpty();
        } else {
            assertThat(all).isNotEmpty();
        }
    }

    /** The legacy packed headers come out of the same decoder, through the pin-era column name of MS:1000626. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"small.mzpeak", "has_uv.mzpeak"})
    void legacyPackedHeadersResolveTheTypeThroughTheFallbackName(String fixture) {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.legacy(fixture))) {
            Entry entry = Facet.CHROMATOGRAM.entry(MzPeakManifest.fromSource(source)).orElseThrow();
            List<ChromatogramMetadataDecoder.Header> headers =
                    ChromatogramMetadataDecoder.decode(source.inputFile(entry.name()), entry);
            assertThat(headers).isNotEmpty();
            assertThat(headers.get(0)).isEqualTo(header(0, TIC, "MS:1000235"));
        }
    }

    // ---- constructed split tables ---------------------------------------------------------------------------

    /** The type column is found by its accession in the member's column_mapping, whatever its name. */
    @Test
    void theTypeColumnIsResolvedByAccessionThroughTheColumnMapping() {
        MessageType schema = splitTable("kind");
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        write("chromatograms_metadata.parquet", schema,
                rows.newGroup().append("index", 0L).append("id", "TIC").append("kind", "MS:1000235"));
        Entry entry = new Entry("chromatograms_metadata.parquet", "chromatogram", "metadata",
                List.of(new ColumnMapping("chromatogram type", "kind", "MS:1000626", null, true)), List.of(), null);
        try (MzPeakSource source = MzPeakSource.open(tmp)) {
            assertThat(ChromatogramMetadataDecoder.decode(source.inputFile(entry.name()), entry))
                    .containsExactly(header(0, TIC, "MS:1000235"));
        }
    }

    /** A repeated index keeps its first position and takes the last row's values, as in the packed layout. */
    @Test
    void aRepeatedIndexKeepsItsFirstPositionAndTheLastValues() {
        MessageType schema = splitTable("chromatogram_type");
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        write("chromatograms_metadata.parquet", schema,
                rows.newGroup().append("index", 1L).append("id", "first").append("chromatogram_type", "MS:1000235"),
                rows.newGroup().append("index", 0L).append("id", "BPC").append("chromatogram_type", "MS:1000628"),
                rows.newGroup().append("index", 1L).append("id", "last"));
        Entry entry = new Entry("chromatograms_metadata.parquet", "chromatogram", "metadata");
        try (MzPeakSource source = MzPeakSource.open(tmp)) {
            assertThat(ChromatogramMetadataDecoder.decode(source.inputFile(entry.name()), entry)).containsExactly(
                    header(1, "last", null),
                    header(0, BPC, "MS:1000628"));
        }
    }

    /** A split metadata member given directly as an InputFile is decoded too; its facets are not needed. */
    @Test
    void aSplitMemberGivenDirectlyIsDecoded() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current("has_uv.mzpeak"))) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            String metadata = Facet.CHROMATOGRAM.entry(manifest).orElseThrow().name();
            String data = manifest.find("chromatogram", "data_arrays").orElseThrow().name();
            List<Chromatogram> all = ChromatogramStore.load(source.inputFile(metadata), source.inputFile(data)).all();
            assertThat(all).extracting(Chromatogram::id).containsExactly(TIC, DAD1);
            assertThat(all).extracting(Chromatogram::typeCurie).containsExactly("MS:1000235", "MS:1000812");
            assertThat(all).extracting(Chromatogram::size).containsExactly(212, 526);
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    /** The header of a chromatogram of unknown polarity, without data processing reference and without Params. */
    private static ChromatogramMetadataDecoder.Header header(long index, String id, String typeCurie) {
        return new ChromatogramMetadataDecoder.Header(index, id, typeCurie, Polarity.UNKNOWN, null, List.of());
    }

    /** A split chromatogram table with {@code index}, {@code id} and the type column {@code typeColumn}. */
    private static MessageType splitTable(String typeColumn) {
        return Types.buildMessage()
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("index")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("id")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named(typeColumn)
                .named("arrow_schema");
    }

    /** Writes {@code rows} as the Parquet member {@code name} of the temporary dataset directory. */
    private void write(String name, MessageType schema, Group... rows) {
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(tmp.resolve(name)))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
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
    }
}
