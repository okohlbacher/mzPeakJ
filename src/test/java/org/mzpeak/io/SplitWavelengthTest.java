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
import org.mzpeak.io.GoldenMeta.Row;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * RD-9: wavelength-spectrum (UV/DAD) metadata in both layouts, read through {@link WavelengthMetadataDecoder} and
 * {@link MzPeakReader}. has_uv is the only fixture with wavelength spectra: its current member (HUPO-PSI/mzPeak
 * 2ee2589) is split, its legacy member (29e59b2) packed. Headers are checked against the raw pyarrow cells
 * ({@link GoldenMeta}, FX-2), wavelength and intensity against the W lines of the HEAD Python golden
 * ({@link Golden}); the data member is decoded as before. The scans facet is not decoded.
 */
class SplitWavelengthTest {

    private static final String HAS_UV = "has_uv.mzpeak";
    private static final String ID0 = "merged=212 row=0";
    private static final double TIME0 = 0.001833333333;

    /** The SHA-256 of the canonical wavelength headers of legacy has_uv, pinned by CH-1. */
    private static final String LEGACY_HAS_UV_WAVELENGTH_SHA256 =
            "e8f510ac303a494f08d39a68917aa79fecd7d3b9d9df371f1af913f74f08aefb";

    @TempDir
    Path tmp;

    static Stream<String> currentFixtures() {
        return Fixtures.CURRENT_NAMES.stream();
    }

    private static List<WavelengthSpectrum> wavelengthSpectra(Path dataset) {
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            return reader.wavelengthSpectra();
        }
    }

    // ---- (a) has_uv through the reader ----------------------------------------------------------------------

    /** The counts and spectrum 0 that the Python reader test (test_reader.py, test_load_uv_data) reads. */
    @Test
    void hasUvHas520SpectraOf49920PointsAndSpectrum0() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(HAS_UV))) {
            List<WavelengthSpectrum> all = reader.wavelengthSpectra();
            assertThat(all).hasSize(520);
            assertThat(all).extracting(WavelengthSpectrum::index)
                    .containsExactlyElementsOf(LongStream.range(0, 520).boxed().toList());
            assertThat(all.stream().mapToLong(WavelengthSpectrum::size).sum()).isEqualTo(49920);
            assertThat(all).extracting(WavelengthSpectrum::size).containsOnly(96);

            WavelengthSpectrum w0 = reader.getWavelengthSpectrum(0).orElseThrow();
            assertThat(w0).isSameAs(all.get(0));
            assertThat(w0.id()).isEqualTo(ID0);
            assertThat(w0.time()).isEqualTo(TIME0);
            assertThat(w0.lambdaMax()).isEqualTo(374.0);
            assertThat(w0.size()).isEqualTo(96);
            assertThat(reader.getWavelengthSpectrum(520)).isEmpty();
        }
    }

    /** Every wavelength spectrum of has_uv passes its W line of the golden; there is one line per spectrum. */
    @Test
    void everyHasUvSpectrumPassesItsWLine() {
        Golden golden = Golden.load(HAS_UV);
        List<WavelengthSpectrum> all = wavelengthSpectra(Fixtures.current(HAS_UV));
        assertThat(all).extracting(w -> Long.toString(w.index()))
                .containsExactlyElementsOf(golden.lines(Golden.Kind.W).stream().map(Golden.Line::key).toList());
        for (WavelengthSpectrum w : all) {
            Golden.Line line = golden.line(Golden.Kind.W, Long.toString(w.index())).orElseThrow();
            assertThat(Golden.Comparator.compare(line, w.wavelength(), w.intensity(), Golden.Tolerance.DEFAULT))
                    .as("W %d", w.index()).isEmpty();
        }
    }

    /**
     * The split current has_uv and the packed legacy has_uv give the same wavelength spectra, value for value; the
     * current headers therefore also have the canonical digest that CH-1 pins for the legacy file.
     */
    @Test
    void currentAndLegacyHasUvGiveTheSameSpectra() {
        List<WavelengthSpectrum> current = wavelengthSpectra(Fixtures.current(HAS_UV));
        List<WavelengthSpectrum> legacy = wavelengthSpectra(Fixtures.legacy(HAS_UV));
        assertThat(current).hasSameSizeAs(legacy);
        for (int i = 0; i < current.size(); i++) {
            WavelengthSpectrum c = current.get(i);
            WavelengthSpectrum l = legacy.get(i);
            assertThat(tuple(c.index(), c.id(), c.time(), c.lambdaMax()))
                    .as("header %d", i).isEqualTo(tuple(l.index(), l.id(), l.time(), l.lambdaMax()));
            assertThat(c.wavelength()).as("wavelength %d", i).isEqualTo(l.wavelength());
            assertThat(c.intensity()).as("intensity %d", i).isEqualTo(l.intensity());
        }
        assertThat(Canonical.sha256(Canonical.lines(current, Canonical::describe)))
                .isEqualTo(LEGACY_HAS_UV_WAVELENGTH_SHA256);
    }

    // ---- (b) headers against the raw cells ------------------------------------------------------------------

    /**
     * Index, id, time and lambda max of every wavelength spectrum equal the cells of the member listed as
     * (wavelength_spectrum, metadata), and its size the number_of_data_points cell.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void headersEqualTheRawCells(String fixture) {
        List<Row> cells;
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current(fixture))) {
            Optional<Entry> entry = Facet.WAVELENGTH_SPECTRUM.entry(MzPeakManifest.fromSource(source));
            cells = entry.map(e -> GoldenMeta.load(fixture).rows(e.name())).orElse(List.of());
            if (entry.isPresent()) {
                assertThat(WavelengthMetadataDecoder.decode(source.inputFile(entry.get().name()), entry.get()))
                        .containsExactlyElementsOf(cells.stream()
                                .map(r -> new WavelengthMetadataDecoder.Header((Long) r.get("index"),
                                        (String) r.get("id"), timeCell(r), (Double) r.get("lambda_max")))
                                .toList());
            }
        }
        List<WavelengthSpectrum> all = wavelengthSpectra(Fixtures.current(fixture));
        assertThat(all).extracting(WavelengthSpectrum::index, WavelengthSpectrum::id, WavelengthSpectrum::time,
                        WavelengthSpectrum::lambdaMax, w -> (long) w.size())
                .containsExactlyElementsOf(cells.stream()
                        .map(r -> tuple(r.get("index"), r.get("id"), timeCell(r), r.get("lambda_max"),
                                r.get("number_of_data_points")))
                        .toList());
        if (fixture.equals(HAS_UV)) {
            assertThat(all).hasSize(520);
        } else {
            assertThat(all).isEmpty();
        }
    }

    /** The legacy packed headers come out of the same decoder, through the pin-era column name of MS:1003812. */
    @Test
    void legacyPackedHeadersResolveLambdaMaxThroughTheFallbackName() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.legacy(HAS_UV))) {
            Entry entry = Facet.WAVELENGTH_SPECTRUM.entry(MzPeakManifest.fromSource(source)).orElseThrow();
            List<WavelengthMetadataDecoder.Header> headers =
                    WavelengthMetadataDecoder.decode(source.inputFile(entry.name()), entry);
            assertThat(headers).hasSize(520);
            assertThat(headers.get(0)).isEqualTo(new WavelengthMetadataDecoder.Header(0, ID0, TIME0, 374.0));
        }
    }

    // ---- constructed split tables ---------------------------------------------------------------------------

    /** The lambda max column is found by its accession in the member's column_mapping, whatever its name. */
    @Test
    void lambdaMaxIsResolvedByAccessionThroughTheColumnMapping() {
        MessageType schema = splitTable("peak_wavelength");
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        write(schema, rows.newGroup().append("index", 0L).append("id", ID0).append("time", TIME0)
                .append("peak_wavelength", 374.0));
        Entry entry = new Entry("wavelength_spectra_metadata.parquet", "wavelength_spectrum", "metadata",
                List.of(new ColumnMapping("lambda max", "peak_wavelength", "MS:1003812", "UO:0000018", null)),
                List.of(), null);
        try (MzPeakSource source = MzPeakSource.open(tmp)) {
            assertThat(WavelengthMetadataDecoder.decode(source.inputFile(entry.name()), entry))
                    .containsExactly(new WavelengthMetadataDecoder.Header(0, ID0, TIME0, 374.0));
        }
    }

    /**
     * A repeated index keeps its first position and takes the last row's values, as in the packed layout; a null
     * time reads as NaN and a null lambda max as null.
     */
    @Test
    void aRepeatedIndexKeepsItsFirstPositionAndTheLastValues() {
        MessageType schema = splitTable("lambda_max");
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        write(schema,
                rows.newGroup().append("index", 1L).append("id", "first").append("time", 0.5)
                        .append("lambda_max", 250.0),
                rows.newGroup().append("index", 0L).append("id", ID0).append("time", TIME0)
                        .append("lambda_max", 374.0),
                rows.newGroup().append("index", 1L).append("id", "last"));
        Entry entry = new Entry("wavelength_spectra_metadata.parquet", "wavelength_spectrum", "metadata");
        try (MzPeakSource source = MzPeakSource.open(tmp)) {
            assertThat(WavelengthMetadataDecoder.decode(source.inputFile(entry.name()), entry)).containsExactly(
                    new WavelengthMetadataDecoder.Header(1, "last", Double.NaN, null),
                    new WavelengthMetadataDecoder.Header(0, ID0, TIME0, 374.0));
        }
    }

    /** A split metadata member given directly as an InputFile is decoded too; its scans facet is not needed. */
    @Test
    void aSplitMemberGivenDirectlyIsDecoded() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current(HAS_UV))) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            String metadata = Facet.WAVELENGTH_SPECTRUM.entry(manifest).orElseThrow().name();
            String data = manifest.find("wavelength_spectrum", "data_arrays").orElseThrow().name();
            List<WavelengthSpectrum> all =
                    WavelengthSpectrumStore.load(source.inputFile(metadata), source.inputFile(data)).all();
            assertThat(all).hasSize(520);
            assertThat(all.get(0).id()).isEqualTo(ID0);
            assertThat(all.get(0).time()).isEqualTo(TIME0);
            assertThat(all.get(0).lambdaMax()).isEqualTo(374.0);
            assertThat(all).extracting(WavelengthSpectrum::size).containsOnly(96);
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    /** The time cell of {@code row} as the decoder reads it: a null cell is NaN. */
    private static double timeCell(Row row) {
        Double time = (Double) row.get("time");
        return time == null ? Double.NaN : time;
    }

    /** A split wavelength-spectrum table with {@code index}, {@code id}, {@code time} and {@code lambdaColumn}. */
    private static MessageType splitTable(String lambdaColumn) {
        return Types.buildMessage()
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("index")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("id")
                .optional(PrimitiveTypeName.DOUBLE).named("time")
                .optional(PrimitiveTypeName.DOUBLE).named(lambdaColumn)
                .named("arrow_schema");
    }

    /** Writes {@code rows} as the member wavelength_spectra_metadata.parquet of the temporary dataset directory. */
    private void write(MessageType schema, Group... rows) {
        Path file = tmp.resolve("wavelength_spectra_metadata.parquet");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
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
