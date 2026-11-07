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
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.Chromatogram;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MetadataTable#forEachRow} in both layouts: split rows against the raw pyarrow cells of {@link GoldenMeta}
 * (FX-2), packed rows against today's decoders, and the key rules on constructed tables. Members are found by role.
 */
class MetadataTableTest {

    @TempDir
    Path tmp;

    static Stream<String> currentFixtures() {
        return Fixtures.CURRENT_NAMES.stream();
    }

    static Stream<String> legacyFixtures() {
        return Fixtures.LEGACY_NAMES.stream();
    }

    static Stream<String> smallFixtures() {
        return Stream.of("small.mzpeak", "small.chunked.mzpeak", "small.numpress.mzpeak", "small.unpacked.mzpeak");
    }

    // ---- split layout ----------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void currentChromatogramsMetadataGivesOneRowWithKeyZero(String fixture) {
        try (MzPeakSource src = MzPeakSource.open(Fixtures.current(fixture))) {
            Entry entry = Facet.CHROMATOGRAM.entry(MzPeakManifest.fromSource(src)).orElseThrow();
            List<FacetRow> rows = rows(src, entry, Facet.CHROMATOGRAM);
            Row cells = GoldenMeta.load(fixture).rows(entry.name()).get(0);

            assertThat(rows).hasSize(1);
            FacetRow row = rows.get(0);
            assertThat(row.facet()).isEqualTo(Facet.CHROMATOGRAM);
            assertThat(row.key()).isEqualTo(0L).isEqualTo(cells.get("index"));
            assertThat(row.columns().stringValue(row.group(), "id")).isEqualTo(cells.get("id")).isEqualTo("TIC");
            String type = row.columns().resolve("MS:1000626");
            assertThat(type).isEqualTo("chromatogram_type");
            assertThat(row.columns().termMarker(type)).isTrue();
            assertThat(row.columns().stringValue(row.group(), type)).isEqualTo(cells.get("chromatogram_type"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void everyFacetMemberYieldsItsRowsWithTheirKeyCells(String fixture) {
        GoldenMeta meta = GoldenMeta.load(fixture);
        int members = 0;
        try (MzPeakSource src = MzPeakSource.open(Fixtures.current(fixture))) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(src);
            for (Facet facet : Facet.values()) {
                Optional<Entry> entry = facet.entry(manifest);
                if (entry.isEmpty()) {
                    continue;
                }
                members++;
                String key = facet.keyColumns().get(0);
                List<Object> expected = meta.rows(entry.get().name()).stream().map(r -> r.get(key)).toList();
                List<Object> keys = new ArrayList<>();
                MetadataTable.forEachRow(src, entry.get(), facet, r -> {
                    assertThat(r.facet()).isEqualTo(facet);
                    keys.add(r.key());
                });
                assertThat(keys).as("%s %s", fixture, facet).isEqualTo(expected).doesNotContainNull();
            }
        }
        assertThat(members).as("facet members of %s", fixture).isGreaterThanOrEqualTo(4);
    }

    @Test
    void aFacetWithoutSourceIndexThrows() {
        MessageType schema = Types.buildMessage()
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("scan_index")
                .optional(PrimitiveTypeName.DOUBLE).named("scan_start_time")
                .named("arrow_schema");
        Group row = new SimpleGroupFactory(schema).newGroup().append("scan_index", 0L).append("scan_start_time", 1.5);
        write("keyless_scans.parquet", schema, row);
        Entry entry = new Entry("keyless_scans.parquet", "spectrum", "scans");
        try (MzPeakSource src = MzPeakSource.open(tmp)) {
            assertThatThrownBy(() -> MetadataTable.forEachRow(src, entry, Facet.SPECTRUM_SCAN, r -> { }))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageContaining("keyless_scans.parquet")
                    .hasMessageContaining("source_index")
                    .hasMessageContaining("[scan_index, scan_start_time]");
        }
    }

    @Test
    void aNullSplitKeyThrowsAndSpectrumIndexStandsInForSourceIndex() {
        MessageType schema = scans("source_index");
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);
        write("null_key_scans.parquet", schema,
                factory.newGroup().append("source_index", 0L).append("scan_start_time", 1.5),
                factory.newGroup().append("scan_start_time", 2.5));
        MessageType aliased = scans("spectrum_index");
        SimpleGroupFactory aliasedFactory = new SimpleGroupFactory(aliased);
        write("aliased_scans.parquet", aliased,
                aliasedFactory.newGroup().append("spectrum_index", 4L).append("scan_start_time", 1.5),
                aliasedFactory.newGroup().append("spectrum_index", 7L).append("scan_start_time", 2.5));
        try (MzPeakSource src = MzPeakSource.open(tmp)) {
            List<Long> seen = new ArrayList<>();
            assertThatThrownBy(() -> MetadataTable.forEachRow(src,
                    new Entry("null_key_scans.parquet", "spectrum", "scans"), Facet.SPECTRUM_SCAN,
                    r -> seen.add(r.key())))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageContaining("null_key_scans.parquet row 1")
                    .hasMessageContaining("source_index is null");
            assertThat(seen).containsExactly(0L);

            List<FacetRow> rows = rows(src, new Entry("aliased_scans.parquet", "spectrum", "scans"),
                    Facet.SPECTRUM_SCAN);
            assertThat(rows).extracting(FacetRow::key).containsExactly(4L, 7L);
            assertThat(rows).extracting(r -> r.columns().doubleValue(r.group(), r.columns().resolve("MS:1000016")))
                    .containsExactly(1.5, 2.5);
            assertThat(rows.get(0).optKey("scan_index")).isNull();
        }
    }

    @Test
    void aKeyWithTheHighBitSetThrows() {
        MessageType schema = scans("source_index");
        write("high_bit_scans.parquet", schema, new SimpleGroupFactory(schema).newGroup()
                .append("source_index", -1L).append("scan_start_time", 1.5));
        try (MzPeakSource src = MzPeakSource.open(tmp)) {
            assertThatThrownBy(() -> MetadataTable.forEachRow(src,
                    new Entry("high_bit_scans.parquet", "spectrum", "scans"), Facet.SPECTRUM_SCAN, r -> { }))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageContaining("high_bit_scans.parquet")
                    .hasMessageContaining("source_index")
                    .hasMessageContaining("18446744073709551615");
        }
    }

    @Test
    void aMemberWithNeitherShapeAndNoRowsYieldsNothing() {
        MessageType schema = Types.buildMessage()
                .optional(PrimitiveTypeName.DOUBLE).named("scan_start_time")
                .named("arrow_schema");
        write("empty_scans.parquet", schema);
        try (MzPeakSource src = MzPeakSource.open(tmp)) {
            assertThat(rows(src, new Entry("empty_scans.parquet", "spectrum", "scans"), Facet.SPECTRUM_SCAN))
                    .isEmpty();
        }
    }

    // ---- packed layout ---------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("legacyFixtures")
    void legacyPackedChromatogramsGiveTodaysIds(String fixture) {
        try (MzPeakSource src = MzPeakSource.open(Fixtures.legacy(fixture))) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(src);
            List<Chromatogram> today = ChromatogramStore.load(src, manifest).all();
            List<FacetRow> rows = rows(src, Facet.CHROMATOGRAM.entry(manifest).orElseThrow(), Facet.CHROMATOGRAM);

            assertThat(today).isNotEmpty();
            assertThat(rows).extracting(FacetRow::key).containsExactlyElementsOf(
                    today.stream().map(Chromatogram::index).toList());
            assertThat(rows).extracting(r -> r.columns().stringValue(r.group(), "id")).containsExactlyElementsOf(
                    today.stream().map(Chromatogram::id).toList());
            assertThat(rows).extracting(r -> r.columns().stringValue(r.group(), r.columns().resolve("MS:1000626")))
                    .containsExactlyElementsOf(today.stream().map(Chromatogram::typeCurie).toList());
        }
    }

    @Test
    void legacyPackedFacetsSkipPlaceholderRows() {
        try (MzPeakSource src = MzPeakSource.open(Fixtures.legacy("small.unpacked.mzpeak"))) {
            Entry entry = Facet.SPECTRUM.entry(MzPeakManifest.fromSource(src)).orElseThrow();
            for (Facet facet : List.of(Facet.SPECTRUM, Facet.SPECTRUM_SCAN, Facet.SPECTRUM_PRECURSOR,
                    Facet.SPECTRUM_SELECTED_ION)) {
                String key = facet.keyColumns().get(0);
                List<Long> expected = new ArrayList<>();
                int placeholders = 0;
                for (Group row : groups(src, entry)) {
                    Group struct = ParquetGroups.optGroup(row, facet.packedStruct());
                    Long k = struct == null ? null : ParquetGroups.optLong(struct, key);
                    if (k == null) {
                        placeholders++;
                    } else {
                        expected.add(k);
                    }
                }
                List<FacetRow> rows = rows(src, entry, facet);
                assertThat(rows).as("%s", facet).extracting(FacetRow::key).containsExactlyElementsOf(expected);
                assertThat(rows).allSatisfy(r -> assertThat(r.group().getType().getName())
                        .isEqualTo(facet.packedStruct()));
                assertThat(expected.size() + placeholders).isEqualTo(48);
                if (facet == Facet.SPECTRUM_PRECURSOR || facet == Facet.SPECTRUM_SELECTED_ION) {
                    assertThat(rows).as("%s", facet).hasSize(34);
                    assertThat(placeholders).isEqualTo(14);
                } else {
                    assertThat(rows).as("%s", facet).hasSize(48);
                }
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static List<FacetRow> rows(MzPeakSource src, Entry entry, Facet facet) {
        List<FacetRow> rows = new ArrayList<>();
        MetadataTable.forEachRow(src, entry, facet, rows::add);
        return rows;
    }

    private static List<Group> groups(MzPeakSource src, Entry entry) {
        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(src.inputFile(entry.name()), rows::add);
        return rows;
    }

    /** A split scans table keyed by {@code key}. */
    private static MessageType scans(String key) {
        return Types.buildMessage()
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named(key)
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("scan_index")
                .optional(PrimitiveTypeName.DOUBLE).named("scan_start_time")
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
