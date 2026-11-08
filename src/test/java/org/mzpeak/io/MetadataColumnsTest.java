package org.mzpeak.io;

import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.Encoding;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.io.SeekableInputStream;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ColumnStatistics.RowGroup;
import org.mzpeak.io.GoldenMeta.Row;
import org.mzpeak.io.MetadataColumns.Resolved;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MetadataColumns}: the paths of {@link MzPeakReader#columnStatistics} and {@link MetadataColumns#valuesByIndex}
 * that {@link ColumnStatisticsTest} does not take. Signed integer statistics, and columns that are found but hold a
 * list or strings, are read from the fixtures; members without a usable shape, struct or index, a row group without
 * the column's chunk and members that fail to open are constructed, as no fixture has them.
 */
class MetadataColumnsTest {

    private static final String SCAN_POLARITY = "MS:1000465";
    private static final String SPECTRUM_REPRESENTATION = "MS:1000525";
    private static final String DELTA_MODEL = "MS:1003821";
    private static final String MS_LEVEL = "MS:1000511";
    private static final String LOWEST_MZ = "MS:1000528";
    private static final String BASE_PEAK_INTENSITY = "MS:1000505";
    private static final String SCAN_START_TIME = "MS:1000016";
    private static final String ISOLATION_WINDOW_TARGET = "MS:1000827";

    @TempDir
    Path tmp;

    // ---- fixtures --------------------------------------------------------------------------------------------

    /**
     * The scan polarity of the imaging example is -1 in every row, in a signed INTEGER(8,true) column: its statistics
     * keep the sign, where an unsigned reading would give 4294967295.
     */
    @Test
    void aSignedIntegerColumnKeepsTheSignOfItsStatistics() {
        String fixture = "Example_Processed.img.mzpeak";
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            String member = reader.manifest().find(EntityType.SPECTRUM, DataKind.METADATA).orElseThrow().name();
            List<Row> cells = GoldenMeta.load(fixture).rows(member);
            assertThat(cells).hasSize(9).allSatisfy(r -> assertThat(r.get("scan_polarity")).isEqualTo(-1L));

            ColumnStatistics polarity =
                    reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, SCAN_POLARITY).orElseThrow();
            assertThat(polarity).isEqualTo(new ColumnStatistics(member, "scan_polarity", SCAN_POLARITY,
                    List.of(new RowGroup(9, 0L, -1.0, -1.0))));
        }
    }

    /**
     * A term whose column holds a list (the delta model) or strings (the spectrum representation) resolves in the
     * resolver but gives no statistics and no values: a list column is no column of the scope, a string column no
     * numeric one.
     */
    @Test
    void aListColumnAndAStringColumnGiveNoStatisticsAndNoValues() {
        try (MzPeakSource current = MzPeakSource.open(Fixtures.current("small.mzpeak"));
             MzPeakSource legacy = MzPeakSource.open(Fixtures.legacy("small.mzpeak"))) {
            MzPeakManifest currentManifest = MzPeakManifest.fromSource(current);
            String member = currentManifest.find(EntityType.SPECTRUM, DataKind.METADATA).orElseThrow().name();
            assertThat(ColumnResolver.split(ParquetFooter.read(current.inputFile(member)).schema(),
                    currentManifest.find(EntityType.SPECTRUM, DataKind.METADATA).orElseThrow(), Facet.SPECTRUM)
                    .resolve(DELTA_MODEL)).isEqualTo("polynomial_delta_coordinate_interpolation");
            assertThat(MetadataColumns.resolve(currentManifest, current, EntityType.SPECTRUM, DataKind.METADATA,
                    DELTA_MODEL)).isEmpty();
            assertThat(MetadataColumns.statistics(currentManifest, current, EntityType.SPECTRUM, DataKind.METADATA,
                    DELTA_MODEL)).isEmpty();

            assertThat(MetadataColumns.resolve(currentManifest, current, EntityType.SPECTRUM, DataKind.METADATA,
                    SPECTRUM_REPRESENTATION)).contains(new Resolved(member, "spectrum_representation"));
            assertThat(MetadataColumns.valuesByIndex(currentManifest, current, EntityType.SPECTRUM,
                    SPECTRUM_REPRESENTATION)).isEmpty();

            MzPeakManifest legacyManifest = MzPeakManifest.fromSource(legacy);
            assertThat(MetadataColumns.resolve(legacyManifest, legacy, EntityType.SPECTRUM, DataKind.METADATA,
                    SPECTRUM_REPRESENTATION)).contains(new Resolved(
                    legacyManifest.find(EntityType.SPECTRUM, DataKind.METADATA).orElseThrow().name(),
                    "spectrum.MS_1000525_spectrum_representation"));
            assertThat(MetadataColumns.valuesByIndex(legacyManifest, legacy, EntityType.SPECTRUM,
                    SPECTRUM_REPRESENTATION)).isEmpty();
        }
    }

    /**
     * A member that fails to open is reported with its name and the cause: when its footer is read, and when its rows
     * are read after the footer was.
     */
    @Test
    void aMemberThatFailsToOpenIsReported() {
        Path dataset = Fixtures.current("small.mzpeak");
        String member;
        try (MzPeakSource src = MzPeakSource.open(dataset)) {
            member = MzPeakManifest.fromSource(src).find(EntityType.SPECTRUM, DataKind.METADATA).orElseThrow().name();
        }
        try (MzPeakSource src = failingOpen(MzPeakSource.open(dataset), member, 0)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(src);
            assertThatThrownBy(() -> MetadataColumns.statistics(manifest, src, EntityType.SPECTRUM, DataKind.METADATA,
                    LOWEST_MZ))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessage("Failed reading Parquet footer of " + member)
                    .hasRootCauseMessage("open 1 of " + member + " fails");
        }
        try (MzPeakSource src = failingOpen(MzPeakSource.open(dataset), member, 1)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(src);
            assertThatThrownBy(() -> MetadataColumns.valuesByIndex(manifest, src, EntityType.SPECTRUM,
                    BASE_PEAK_INTENSITY))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessage("Failed reading Parquet member " + member)
                    .hasRootCauseMessage("open 2 of " + member + " fails");
        }
        // the source that fails on no open reads the values
        try (MzPeakSource src = failingOpen(MzPeakSource.open(dataset), member, Integer.MAX_VALUE)) {
            assertThat(MetadataColumns.valuesByIndex(MzPeakManifest.fromSource(src), src, EntityType.SPECTRUM,
                    BASE_PEAK_INTENSITY)).hasValueSatisfying(values -> assertThat(values).hasSize(48));
        }
    }

    // ---- constructed members ---------------------------------------------------------------------------------

    /**
     * A packed spectrum struct without a primitive {@code index} holds only placeholder rows, so the values of its
     * columns are an empty map, though the column is found.
     */
    @Test
    void aPackedStructWithoutAPrimitiveIndexHasNoValues() {
        for (String index : List.of("", "    optional group index {\n      optional int64 value;\n    }\n")) {
            MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                    + "  optional group spectrum {\n"
                    + index
                    + "    optional float MS_1000505_base_peak_intensity_unit_MS_1000131;\n"
                    + "  }\n"
                    + "}");
            Group row = new SimpleGroupFactory(schema).newGroup();
            row.addGroup("spectrum").append("MS_1000505_base_peak_intensity_unit_MS_1000131", 25.5f);
            write("spectra_metadata.parquet", schema, row);
            manifest(metadataEntry());

            try (MzPeakSource src = MzPeakSource.open(tmp)) {
                MzPeakManifest manifest = MzPeakManifest.fromSource(src);
                assertThat(MetadataColumns.resolve(manifest, src, EntityType.SPECTRUM, DataKind.METADATA,
                        BASE_PEAK_INTENSITY)).as("index %s", index).contains(new Resolved("spectra_metadata.parquet",
                        "spectrum.MS_1000505_base_peak_intensity_unit_MS_1000131"));
                assertThat(MetadataColumns.valuesByIndex(manifest, src, EntityType.SPECTRUM, BASE_PEAK_INTENSITY))
                        .as("index %s", index).contains(Map.of());
            }
        }
    }

    /**
     * A packed member holds no column of a facet whose struct it lacks, or has as a scalar column; the facets it has
     * are found.
     */
    @Test
    void aPackedMemberWithoutTheFacetsStructHasNoColumnOfTheFacet() {
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional group spectrum {\n"
                + "    optional int64 index (INTEGER(64,false));\n"
                + "    optional int32 MS_1000511_ms_level;\n"
                + "  }\n"
                + "  optional double scan;\n"
                + "}");
        Group row = new SimpleGroupFactory(schema).newGroup().append("scan", 1.5);
        row.addGroup("spectrum").append("index", 0L).append("MS_1000511_ms_level", 2);
        write("spectra_metadata.parquet", schema, row);
        manifest(metadataEntry());

        try (MzPeakSource src = MzPeakSource.open(tmp)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(src);
            assertThat(MetadataColumns.resolve(manifest, src, EntityType.SPECTRUM, DataKind.METADATA, MS_LEVEL))
                    .contains(new Resolved("spectra_metadata.parquet", "spectrum.MS_1000511_ms_level"));
            assertThat(MetadataColumns.resolve(manifest, src, EntityType.SPECTRUM, DataKind.SCANS, SCAN_START_TIME))
                    .as("a scalar 'scan'").isEmpty();
            assertThat(MetadataColumns.resolve(manifest, src, EntityType.SPECTRUM, DataKind.PRECURSORS,
                    ISOLATION_WINDOW_TARGET)).as("no 'precursor'").isEmpty();
            assertThat(MetadataColumns.statistics(manifest, src, EntityType.SPECTRUM, DataKind.SCANS,
                    SCAN_START_TIME)).isEmpty();
        }
    }

    /** A member without rows and with neither metadata shape holds no column, where one with rows throws. */
    @Test
    void aMemberWithNeitherShapeAndNoRowsHasNoColumn() {
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional double lowest_observed_mz;\n"
                + "}");
        write("spectra_metadata.parquet", schema);
        manifest(metadataEntry());

        try (MzPeakSource src = MzPeakSource.open(tmp)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(src);
            assertThat(MetadataColumns.resolve(manifest, src, EntityType.SPECTRUM, DataKind.METADATA, LOWEST_MZ))
                    .isEmpty();
            assertThat(MetadataColumns.statistics(manifest, src, EntityType.SPECTRUM, DataKind.METADATA, LOWEST_MZ))
                    .isEmpty();
            assertThat(MetadataColumns.valuesByIndex(manifest, src, EntityType.SPECTRUM, LOWEST_MZ)).isEmpty();
        }

        write("spectra_metadata.parquet", schema, new SimpleGroupFactory(schema).newGroup()
                .append("lowest_observed_mz", 150.5));
        try (MzPeakSource src = MzPeakSource.open(tmp)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(src);
            assertThatThrownBy(() -> MetadataColumns.resolve(manifest, src, EntityType.SPECTRUM, DataKind.METADATA,
                    LOWEST_MZ))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("spectra_metadata.parquet has 1 rows but neither the packed shape");
        }
    }

    /**
     * A row group whose footer lists no chunk of the column records neither a null count nor bounds, and leaves the
     * statistics incomplete. parquet-java's writers always write every chunk; the low-level writer here leaves the
     * column's out.
     */
    @Test
    void aRowGroupWithoutTheColumnsChunkHasNoStatistics() throws IOException {
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional int64 index (INTEGER(64,false));\n"
                + "  optional double lowest_observed_mz;\n"
                + "}");
        ColumnDescriptor index = schema.getColumnDescription(new String[] {"index"});
        Statistics<?> indexStatistics = Statistics.createStats(index.getPrimitiveType());
        indexStatistics.updateStats(0L);
        // a v1 data page of one non-null INT64: the definition levels, RLE with a 4-byte length (one run of one 1),
        // then the PLAIN value 0
        byte[] page = {2, 0, 0, 0, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0};
        ParquetFileWriter writer = new ParquetFileWriter(new LocalOutputFile(tmp.resolve("spectra_metadata.parquet")),
                schema, ParquetFileWriter.Mode.CREATE, ParquetWriter.DEFAULT_BLOCK_SIZE,
                ParquetWriter.MAX_PADDING_SIZE_DEFAULT, 64, Integer.MAX_VALUE, false);
        writer.start();
        writer.startBlock(1);
        writer.startColumn(index, 1, CompressionCodecName.UNCOMPRESSED);
        writer.writeDataPage(1, page.length, BytesInput.from(page), indexStatistics, 1, Encoding.RLE, Encoding.RLE,
                Encoding.PLAIN);
        writer.endColumn();
        writer.endBlock();
        writer.end(new HashMap<>());
        manifest(metadataEntry());

        try (MzPeakSource src = MzPeakSource.open(tmp)) {
            ColumnStatistics lowest = MetadataColumns.statistics(MzPeakManifest.fromSource(src), src,
                    EntityType.SPECTRUM, DataKind.METADATA, LOWEST_MZ).orElseThrow();
            assertThat(lowest).isEqualTo(new ColumnStatistics("spectra_metadata.parquet", "lowest_observed_mz",
                    LOWEST_MZ, List.of(new RowGroup(1, null, null, null))));
            assertThat(lowest.complete()).isFalse();
            assertThat(lowest.min()).isEmpty();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /** The manifest entry of the spectrum metadata member, without a column mapping. */
    private static String metadataEntry() {
        return "{\"name\": \"spectra_metadata.parquet\", \"entity_type\": \"spectrum\", \"data_kind\": \"metadata\"}";
    }

    /**
     * {@code delegate}, whose member {@code member} opens {@code opens} times and then fails to, with an
     * {@link IOException} that counts the opens.
     */
    private static MzPeakSource failingOpen(MzPeakSource delegate, String member, int opens) {
        AtomicInteger count = new AtomicInteger();
        return new MzPeakSource() {
            @Override
            public byte[] readManifestBytes() {
                return delegate.readManifestBytes();
            }

            @Override
            public InputFile inputFile(String name) {
                InputFile file = delegate.inputFile(name);
                if (!name.equals(member)) {
                    return file;
                }
                return new InputFile() {
                    @Override
                    public long getLength() throws IOException {
                        return file.getLength();
                    }

                    @Override
                    public SeekableInputStream newStream() throws IOException {
                        int open = count.incrementAndGet();
                        if (open > opens) {
                            throw new IOException("open " + open + " of " + member + " fails");
                        }
                        return file.newStream();
                    }
                };
            }

            @Override
            public String describe() {
                return delegate.describe();
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
    }

    private void manifest(String entry) {
        try {
            Files.writeString(tmp.resolve(TestSources.MANIFEST), "{\"files\": [" + entry + "], \"metadata\": {}}",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

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
