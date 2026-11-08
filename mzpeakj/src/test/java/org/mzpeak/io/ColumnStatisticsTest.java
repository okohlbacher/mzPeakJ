package org.mzpeak.io;

import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ColumnStatistics.RowGroup;
import org.mzpeak.io.MetadataColumns.Resolved;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.DoubleRange;
import org.mzpeak.model.Param;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AP-4: {@link MzPeakReader#columnStatistics} reads the Parquet row-group statistics of a metadata column found by
 * role and accession in both layouts (design section 10), {@link MzPeakReader#observedMzRange()} takes the MS:1000528
 * minimum and the MS:1000527 maximum from them, and {@link MzPeakReader#observedTimeRange()} the finite retention
 * times held in memory.
 *
 * <p>Oracles: the values of the reference tests (Rust reader.rs test_read_mz_range, Python test_reader.py:47-51) for
 * small, the footer statistics of the other fixtures as parquet-java reads them, and the MS:1000528/MS:1000527 Params
 * of every current spectrum (RD-6). Constructed members cover the packed CURIE-prefix fallback, several row groups,
 * missing statistics, missing or all-null observed m/z columns and INT64 columns, which no fixture has.
 */
class ColumnStatisticsTest {

    private static final String LOWEST_MZ = "MS:1000528";
    private static final String HIGHEST_MZ = "MS:1000527";
    private static final String SCAN_START_TIME = "MS:1000016";
    private static final String FILTER_STRING = "MS:1000512";
    private static final String UNKNOWN = "MS:9999999";
    private static final String MZ_ARRAY = "MS:1000514";
    private static final String INTENSITY_ARRAY = "MS:1000515";
    /** Made-up terms of the INT64 columns of {@link #writeInt64Metadata}. */
    private static final String SIGNED_INT64 = "MS:9999001";
    private static final String UNSIGNED_INT64 = "MS:9999002";

    /** The reference value of every small dataset, current and legacy (Rust and Python tests). */
    private static final DoubleRange SMALL_MZ = new DoubleRange(162.24594116210938, 2000.0099466203774);
    private static final String LEGACY_SCAN_START_TIME = "scan.MS_1000016_scan_start_time_unit_UO_0000031";
    /**
     * The lowest and highest observed m/z of the rows of {@link #writeSplitMetadata}: null in the middle row group,
     * the smallest lowest and the largest highest value in the last one.
     */
    private static final Double[][] SPLIT_MZ =
            {{300.0, 900.0}, {250.0, 950.0}, {null, null}, {null, null}, {200.0, 1000.0}};

    @TempDir
    Path tmp;

    static Stream<String> smallFixtures() {
        return Stream.of("small.mzpeak", "small.chunked.mzpeak", "small.numpress.mzpeak", "small.unpacked.mzpeak");
    }

    static Stream<String> currentFixtures() {
        return Fixtures.CURRENT_NAMES.stream();
    }

    // ---- (a), (b) observed m/z range -----------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void currentSmallObservedMzRangeIsTheReferenceValue(String fixture) {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            assertThat(reader.observedMzRange()).contains(SMALL_MZ);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void legacySmallObservedMzRangeIsTheReferenceValue(String fixture) {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.legacy(fixture))) {
            assertThat(reader.observedMzRange()).contains(SMALL_MZ);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "has_uv.mzpeak,                101.05783081054688, 961.9844970703125",
            "diaPASEF.ref.mzpeak,          95.04345834591794,  1704.976900971801",
            "diaPASEF.grid.mzpeak,         95.04345834591794,  1704.976900971801",
            "Example_Processed.img.mzpeak, 100.08333587646484, 799.9166870117188"
    })
    void otherCurrentObservedMzRanges(String fixture, double low, double high) {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            assertThat(reader.observedMzRange()).contains(new DoubleRange(low, high));
        }
    }

    @Test
    void legacyHasUvObservedMzRangeEqualsTheCurrentOne() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.legacy("has_uv.mzpeak"))) {
            assertThat(reader.observedMzRange()).contains(new DoubleRange(101.05783081054688, 961.9844970703125));
        }
    }

    // ---- (c) scan start time statistics --------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void currentSmallScanStartTimeStatisticsComeFromTheScansMember(String fixture) {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            String scans = reader.manifest().find(EntityType.SPECTRUM, DataKind.SCANS).orElseThrow().name();
            ColumnStatistics stats =
                    reader.columnStatistics(EntityType.SPECTRUM, DataKind.SCANS, SCAN_START_TIME).orElseThrow();
            assertThat(stats.member()).isEqualTo(scans);
            assertThat(stats.column()).isEqualTo("scan_start_time");
            assertThat(stats.accession()).isEqualTo(SCAN_START_TIME);
            assertThat(stats.rowGroups()).containsExactly(new RowGroup(48, 0L, 0.004935, 0.487236666667));
            assertThat(stats.complete()).isTrue();
            assertThat(stats.min()).hasValue(0.004935);
            assertThat(stats.max()).hasValue(0.487236666667);
        }
    }

    /** The packed layout keeps the scan start time as FLOAT in the scan struct of the metadata member. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void legacySmallScanStartTimeStatisticsComeFromThePackedScanStruct(String fixture) {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.legacy(fixture))) {
            String metadata = reader.manifest().find(EntityType.SPECTRUM, DataKind.METADATA).orElseThrow().name();
            assertThat(reader.manifest().find(EntityType.SPECTRUM, DataKind.SCANS)).isEmpty();
            ColumnStatistics stats =
                    reader.columnStatistics(EntityType.SPECTRUM, DataKind.SCANS, SCAN_START_TIME).orElseThrow();
            assertThat(stats.member()).isEqualTo(metadata);
            assertThat(stats.column()).isEqualTo(LEGACY_SCAN_START_TIME);
            assertThat(stats.rowGroups()).containsExactly(
                    new RowGroup(48, 0L, 0.004935000091791153, 0.4872366786003113));
            assertThat(stats.complete()).isTrue();
            assertThat(stats.min()).hasValue(0.004935000091791153);
            assertThat(stats.max()).hasValue(0.4872366786003113);
        }
    }

    /** The m/z columns resolve through the column mapping in split, through the pin-era names in packed. */
    @Test
    void theObservedMzColumnsResolveInBothLayouts() {
        try (MzPeakReader current = MzPeakReader.open(Fixtures.current("small.mzpeak"));
             MzPeakReader legacy = MzPeakReader.open(Fixtures.legacy("small.mzpeak"))) {
            assertThat(current.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, LOWEST_MZ))
                    .map(ColumnStatistics::column).contains("lowest_observed_mz");
            assertThat(current.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, HIGHEST_MZ))
                    .map(ColumnStatistics::column).contains("highest_observed_mz");
            assertThat(legacy.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, LOWEST_MZ))
                    .map(ColumnStatistics::column).contains("spectrum.MS_1000528_lowest_observed_mz_unit_MS_1000040");
            ColumnStatistics highest =
                    legacy.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, HIGHEST_MZ).orElseThrow();
            assertThat(highest.column()).isEqualTo("spectrum.MS_1000527_highest_observed_mz_unit_MS_1000040");
            assertThat(highest.rowGroups()).containsExactly(
                    new RowGroup(48, 0L, 1123.1192626953125, 2000.0099466203774));
        }
    }

    /** Wavelength spectra: their own scans member in split, the scan struct of their metadata member in packed. */
    @Test
    void wavelengthColumnsResolveInBothLayouts() {
        try (MzPeakReader current = MzPeakReader.open(Fixtures.current("has_uv.mzpeak"));
             MzPeakReader legacy = MzPeakReader.open(Fixtures.legacy("has_uv.mzpeak"))) {
            ColumnStatistics lowest = current.columnStatistics(EntityType.WAVELENGTH_SPECTRUM, DataKind.METADATA,
                    "MS:1000619").orElseThrow();
            assertThat(lowest.column()).isEqualTo("lowest_observed_wavelength");
            assertThat(lowest.rowGroups()).containsExactly(new RowGroup(520, 0L, 210.0, 210.0));
            ColumnStatistics times = current.columnStatistics(EntityType.WAVELENGTH_SPECTRUM, DataKind.SCANS,
                    SCAN_START_TIME).orElseThrow();
            assertThat(times.member()).isEqualTo(current.manifest()
                    .find(EntityType.WAVELENGTH_SPECTRUM, DataKind.SCANS).orElseThrow().name());
            assertThat(times.rowGroups()).containsExactly(new RowGroup(520, 0L, 0.001833333333, 3.461833333333));

            String member = legacy.manifest().find(EntityType.WAVELENGTH_SPECTRUM, DataKind.METADATA).orElseThrow()
                    .name();
            ColumnStatistics packedLowest = legacy.columnStatistics(EntityType.WAVELENGTH_SPECTRUM,
                    DataKind.METADATA, "MS:1000619").orElseThrow();
            assertThat(packedLowest.member()).isEqualTo(member);
            assertThat(packedLowest.column()).isEqualTo("spectrum.MS_1000619_lowest_observed_wavelength_unit_UO_0000018");
            assertThat(packedLowest.rowGroups()).containsExactly(new RowGroup(520, 0L, 210.0, 210.0));
            ColumnStatistics packedTimes = legacy.columnStatistics(EntityType.WAVELENGTH_SPECTRUM, DataKind.SCANS,
                    SCAN_START_TIME).orElseThrow();
            assertThat(packedTimes.member()).isEqualTo(member);
            assertThat(packedTimes.column()).isEqualTo(LEGACY_SCAN_START_TIME);
        }
    }

    // ---- (d) what gives no statistics ----------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void unknownTermsStringColumnsAndSignalMembersGiveNoStatistics(String fixture) {
        for (Path dataset : List.of(Fixtures.current(fixture), Fixtures.legacy(fixture))) {
            try (MzPeakReader reader = MzPeakReader.open(dataset)) {
                assertThat(reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, UNKNOWN)).isEmpty();
                assertThat(reader.columnStatistics(EntityType.SPECTRUM, DataKind.SCANS, UNKNOWN)).isEmpty();
                assertThat(reader.columnStatistics(EntityType.SPECTRUM, DataKind.SCANS, FILTER_STRING)).isEmpty();
                assertThat(reader.columnStatistics(EntityType.SPECTRUM, DataKind.DATA_ARRAYS, MZ_ARRAY)).isEmpty();
                assertThat(reader.columnStatistics(EntityType.SPECTRUM, DataKind.DATA_ARRAYS, INTENSITY_ARRAY))
                        .isEmpty();
                assertThat(reader.columnStatistics(EntityType.SPECTRUM, DataKind.PEAKS, MZ_ARRAY)).isEmpty();
                assertThat(reader.columnStatistics(EntityType.SPECTRUM, DataKind.PRODUCTS, SCAN_START_TIME))
                        .isEmpty();
                assertThat(reader.columnStatistics(EntityType.PROVENANCE, DataKind.METADATA, SCAN_START_TIME))
                        .isEmpty();
            }
        }
    }

    /** The filter string column is found; it gives no statistics because it holds strings. */
    @Test
    void theFilterStringColumnResolvesButIsNotNumeric() {
        try (MzPeakSource current = MzPeakSource.open(Fixtures.current("small.mzpeak"));
             MzPeakSource legacy = MzPeakSource.open(Fixtures.legacy("small.mzpeak"))) {
            MzPeakManifest currentManifest = MzPeakManifest.fromSource(current);
            assertThat(MetadataColumns.resolve(currentManifest, current, EntityType.SPECTRUM, DataKind.SCANS,
                    FILTER_STRING)).contains(new Resolved(
                    currentManifest.find(EntityType.SPECTRUM, DataKind.SCANS).orElseThrow().name(), "filter_string"));
            assertThat(MetadataColumns.statistics(currentManifest, current, EntityType.SPECTRUM, DataKind.SCANS,
                    FILTER_STRING)).isEmpty();
            MzPeakManifest legacyManifest = MzPeakManifest.fromSource(legacy);
            assertThat(MetadataColumns.resolve(legacyManifest, legacy, EntityType.SPECTRUM, DataKind.SCANS,
                    FILTER_STRING)).contains(new Resolved(
                    legacyManifest.find(EntityType.SPECTRUM, DataKind.METADATA).orElseThrow().name(),
                    "scan.MS_1000512_filter_string"));
            assertThat(MetadataColumns.statistics(legacyManifest, legacy, EntityType.SPECTRUM, DataKind.SCANS,
                    FILTER_STRING)).isEmpty();
        }
    }

    // ---- (e), (f) observed ranges against the metadata -----------------------------------------------------------

    @Test
    void currentSmallObservedTimeRangeSpansItsScanStartTimes() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            assertThat(reader.observedTimeRange()).contains(new DoubleRange(0.004935, 0.487236666667));
        }
    }

    /**
     * The observed m/z range is the smallest MS:1000528 and the largest MS:1000527 Param of the spectra, and the
     * observed time range the smallest and largest finite retention time.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void observedRangesEqualTheExtremesOfTheSpectrumMetadata(String fixture) {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            List<SpectrumDescription> spectra = reader.metadata();
            OptionalDouble lowest = paramValues(spectra, LOWEST_MZ).min();
            OptionalDouble highest = paramValues(spectra, HIGHEST_MZ).max();
            assertThat(lowest).as("MS:1000528 Params of %s", fixture).isPresent();
            assertThat(highest).as("MS:1000527 Params of %s", fixture).isPresent();
            assertThat(reader.observedMzRange())
                    .contains(new DoubleRange(lowest.getAsDouble(), highest.getAsDouble()));

            OptionalDouble first = spectra.stream().mapToDouble(SpectrumDescription::retentionTime)
                    .filter(Double::isFinite).min();
            OptionalDouble last = spectra.stream().mapToDouble(SpectrumDescription::retentionTime)
                    .filter(Double::isFinite).max();
            assertThat(reader.observedTimeRange())
                    .contains(new DoubleRange(first.getAsDouble(), last.getAsDouble()));
        }
    }

    private static DoubleStream paramValues(List<SpectrumDescription> spectra, String accession) {
        return spectra.stream().flatMap(d -> d.parameters().stream())
                .filter(p -> accession.equals(p.accession()))
                .map(Param::value)
                .mapToDouble(v -> ((Number) v).doubleValue());
    }

    // ---- constructed members -------------------------------------------------------------------------------------

    /**
     * In the packed layout a column the resolver does not know is found by the CURIE prefix of its accession, when
     * exactly one field of the struct has it; two fields with the prefix make it ambiguous.
     */
    @Test
    void packedColumnsAreAlsoFoundByAUniqueCuriePrefix() {
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional group spectrum {\n"
                + "    optional int64 index (INTEGER(64,false));\n"
                + "    optional double MS_1000528_lowest_observed_m_z;\n"
                + "    optional float MS_1000527_highest_observed_m_z;\n"
                + "    optional double MS_1000504_first;\n"
                + "    optional double MS_1000504_second;\n"
                + "  }\n"
                + "}");
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);
        Function<Object[], Group> row = v -> {
            Group g = factory.newGroup();
            Group s = g.addGroup("spectrum");
            s.append("index", (Long) v[0]).append("MS_1000528_lowest_observed_m_z", (Double) v[1])
                    .append("MS_1000527_highest_observed_m_z", (Float) v[2])
                    .append("MS_1000504_first", 1.0).append("MS_1000504_second", 2.0);
            return g;
        };
        write("spectra_metadata.parquet", schema, Integer.MAX_VALUE, true,
                row.apply(new Object[] {0L, 150.5, 1500.25f}), row.apply(new Object[] {1L, 120.25, 1800.5f}));
        manifest("{\"name\": \"spectra_metadata.parquet\", \"entity_type\": \"spectrum\", \"data_kind\": \"metadata\"}");

        try (MzPeakSource src = MzPeakSource.open(tmp)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(src);
            assertThat(MetadataColumns.resolve(manifest, src, EntityType.SPECTRUM, DataKind.METADATA, LOWEST_MZ))
                    .contains(new Resolved("spectra_metadata.parquet", "spectrum.MS_1000528_lowest_observed_m_z"));
            assertThat(MetadataColumns.resolve(manifest, src, EntityType.SPECTRUM, DataKind.METADATA, "MS:1000504"))
                    .isEmpty();
        }
        try (MzPeakReader reader = MzPeakReader.open(tmp)) {
            ColumnStatistics highest =
                    reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, HIGHEST_MZ).orElseThrow();
            assertThat(highest.column()).isEqualTo("spectrum.MS_1000527_highest_observed_m_z");
            assertThat(highest.rowGroups()).containsExactly(new RowGroup(2, 0L, 1500.25, 1800.5));
            assertThat(reader.observedMzRange()).contains(new DoubleRange(120.25, 1800.5));
        }
    }

    /**
     * The split layout has no prefix fallback: an unmapped column is not taken for a term. Nor does its metadata
     * member stand in for a facet member the manifest does not list, even with a column of the facet's name.
     */
    @Test
    void splitColumnsAreNotFoundByPrefix() {
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional int64 index (INTEGER(64,false));\n"
                + "  optional double MS_1000528_lowest_observed_m_z;\n"
                + "  optional double scan_start_time;\n"
                + "}");
        write("spectra_metadata.parquet", schema, Integer.MAX_VALUE, true,
                new SimpleGroupFactory(schema).newGroup().append("index", 0L)
                        .append("MS_1000528_lowest_observed_m_z", 150.5).append("scan_start_time", 1.5));
        manifest("{\"name\": \"spectra_metadata.parquet\", \"entity_type\": \"spectrum\", \"data_kind\": \"metadata\"}");

        try (MzPeakSource src = MzPeakSource.open(tmp)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(src);
            assertThat(MetadataColumns.resolve(manifest, src, EntityType.SPECTRUM, DataKind.METADATA, LOWEST_MZ))
                    .isEmpty();
            assertThat(MetadataColumns.resolve(manifest, src, EntityType.SPECTRUM, DataKind.SCANS, SCAN_START_TIME))
                    .isEmpty();
        }
    }

    /**
     * Statistics span the row groups: the minimum of their minima, the maximum of their maxima, which lie in different
     * row groups here. A row group of nulls records no bounds and leaves the statistics complete.
     */
    @Test
    void statisticsSpanSeveralRowGroups() {
        writeSplitMetadata(SPLIT_MZ, true);
        try (MzPeakReader reader = MzPeakReader.open(tmp)) {
            ColumnStatistics lowest =
                    reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, LOWEST_MZ).orElseThrow();
            assertThat(lowest.column()).isEqualTo("lowest_observed_mz");
            assertThat(lowest.rowGroups()).containsExactly(
                    new RowGroup(2, 0L, 250.0, 300.0),
                    new RowGroup(2, 2L, null, null),
                    new RowGroup(1, 0L, 200.0, 200.0));
            assertThat(lowest.complete()).isTrue();
            assertThat(lowest.min()).hasValue(200.0);
            assertThat(lowest.max()).hasValue(300.0);
            ColumnStatistics highest =
                    reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, HIGHEST_MZ).orElseThrow();
            assertThat(highest.rowGroups()).containsExactly(
                    new RowGroup(2, 0L, 900.0, 950.0),
                    new RowGroup(2, 2L, null, null),
                    new RowGroup(1, 0L, 1000.0, 1000.0));
            assertThat(highest.complete()).isTrue();
            assertThat(highest.min()).hasValue(900.0);
            assertThat(highest.max()).hasValue(1000.0);
            assertThat(reader.observedMzRange()).contains(new DoubleRange(200.0, 1000.0));
            // uint32 statistics are read without sign extension
            ColumnStatistics points =
                    reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, "MS:1003060").orElseThrow();
            assertThat(points.min()).hasValue(10.0);
            assertThat(points.max()).hasValue(4_000_000_000.0);
        }
    }

    /** Without statistics in the footer, the statistics are incomplete and there is no observed m/z range. */
    @Test
    void missingStatisticsLeaveTheObservedMzRangeEmpty() {
        writeSplitMetadata(SPLIT_MZ, false);
        try (MzPeakReader reader = MzPeakReader.open(tmp)) {
            ColumnStatistics lowest =
                    reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, LOWEST_MZ).orElseThrow();
            assertThat(lowest.rowGroups()).hasSize(3)
                    .allSatisfy(g -> assertThat(g.min()).isNull());
            assertThat(lowest.complete()).isFalse();
            assertThat(lowest.min()).isEmpty();
            assertThat(reader.observedMzRange()).isEmpty();
            assertThat(reader.observedTimeRange()).contains(new DoubleRange(1.0, 5.0));
        }
    }

    /**
     * A single row group without bounds makes a column's statistics incomplete although the other row groups record a
     * minimum and a maximum, and then there is no observed m/z range. Here the last row group of one observed m/z
     * column holds only a NaN, whose bounds parquet-java drops while it keeps the null count; the other column stays
     * complete.
     */
    @ParameterizedTest(name = "{0} incomplete")
    @CsvSource({"MS:1000528, MS:1000527", "MS:1000527, MS:1000528"})
    void oneUnboundedRowGroupOfEitherObservedMzColumnLeavesTheObservedMzRangeEmpty(String incomplete,
                                                                                  String complete) {
        Double[][] mz = new Double[SPLIT_MZ.length][];
        for (int i = 0; i < mz.length; i++) {
            mz[i] = SPLIT_MZ[i].clone();
        }
        mz[mz.length - 1][LOWEST_MZ.equals(incomplete) ? 0 : 1] = Double.NaN;
        writeSplitMetadata(mz, true);
        try (MzPeakReader reader = MzPeakReader.open(tmp)) {
            ColumnStatistics partial =
                    reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, incomplete).orElseThrow();
            assertThat(partial.rowGroups()).hasSize(3);
            assertThat(partial.rowGroups().get(0).min()).isNotNull();
            assertThat(partial.rowGroups().get(1)).isEqualTo(new RowGroup(2, 2L, null, null));
            assertThat(partial.rowGroups().get(2)).isEqualTo(new RowGroup(1, 0L, null, null));
            assertThat(partial.complete()).isFalse();
            assertThat(partial.min()).isPresent();
            assertThat(partial.max()).isPresent();
            ColumnStatistics other =
                    reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, complete).orElseThrow();
            assertThat(other.complete()).isTrue();
            assertThat(other.min()).isPresent();
            assertThat(other.max()).isPresent();
            assertThat(reader.observedMzRange()).isEmpty();
        }
    }

    /** Without a column for one of the two terms there is no observed m/z range, whichever term it is. */
    @ParameterizedTest(name = "{0} missing")
    @CsvSource({"MS:1000528, MS:1000527", "MS:1000527, MS:1000528"})
    void eitherObservedMzColumnMissingLeavesTheObservedMzRangeEmpty(String missing, String present) {
        writeSplitMetadata(SPLIT_MZ, true, missing);
        try (MzPeakReader reader = MzPeakReader.open(tmp)) {
            assertThat(reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, missing)).isEmpty();
            ColumnStatistics other =
                    reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, present).orElseThrow();
            assertThat(other.column()).isEqualTo(LOWEST_MZ.equals(present) ? "lowest_observed_mz"
                    : "highest_observed_mz");
            assertThat(other.complete()).isTrue();
            assertThat(other.min()).isPresent();
            assertThat(other.max()).isPresent();
            assertThat(reader.observedMzRange()).isEmpty();
        }
    }

    /**
     * A column of only nulls has complete statistics without a minimum or a maximum, and then there is no observed
     * m/z range, whichever of the two columns it is; the other column is bounded.
     */
    @ParameterizedTest(name = "{0} all null")
    @CsvSource({"MS:1000528, MS:1000527", "MS:1000527, MS:1000528"})
    void eitherObservedMzColumnOfOnlyNullsLeavesTheObservedMzRangeEmpty(String nulls, String bounded) {
        int column = LOWEST_MZ.equals(nulls) ? 0 : 1;
        Double[][] mz = new Double[SPLIT_MZ.length][];
        for (int i = 0; i < mz.length; i++) {
            mz[i] = SPLIT_MZ[i].clone();
            mz[i][column] = null;
        }
        writeSplitMetadata(mz, true);
        try (MzPeakReader reader = MzPeakReader.open(tmp)) {
            ColumnStatistics empty =
                    reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, nulls).orElseThrow();
            assertThat(empty.rowGroups()).containsExactly(
                    new RowGroup(2, 2L, null, null),
                    new RowGroup(2, 2L, null, null),
                    new RowGroup(1, 1L, null, null));
            assertThat(empty.complete()).isTrue();
            assertThat(empty.min()).isEmpty();
            assertThat(empty.max()).isEmpty();
            ColumnStatistics other =
                    reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, bounded).orElseThrow();
            assertThat(other.complete()).isTrue();
            assertThat(other.min()).isPresent();
            assertThat(other.max()).isPresent();
            assertThat(reader.observedMzRange()).isEmpty();
        }
    }

    /**
     * INT64 statistics keep their value: a signed column its sign, an INTEGER(64,false) column below 2<sup>63</sup>
     * its unsigned value.
     */
    /**
     * An INT32 column annotated as a DATE (MC5) and a repeated INT64 field (MC9) hold no statistics a term can use,
     * though their primitive types are numeric.
     */
    @Test
    void aDateColumnAndARepeatedFieldGiveNoStatistics() {
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional int64 index (INTEGER(64,false));\n"
                + "  optional double time;\n"
                + "  optional int32 acquisition_date (DATE);\n"
                + "  repeated int64 counts;\n"
                + "}");
        write("spectra_metadata.parquet", schema, Integer.MAX_VALUE, true,
                new SimpleGroupFactory(schema).newGroup().append("index", 0L).append("time", 1.0)
                        .append("acquisition_date", 19000).append("counts", 3L).append("counts", 4L));
        manifest("{\"name\": \"spectra_metadata.parquet\", \"entity_type\": \"spectrum\", \"data_kind\": \"metadata\","
                + " \"column_mapping\": ["
                + "{\"name\": \"acquisition date\", \"path\": \"acquisition_date\", \"accession\": \"MS:9999003\","
                + " \"unit\": null},"
                + "{\"name\": \"counts\", \"path\": \"counts\", \"accession\": \"MS:9999004\", \"unit\": null}]}");

        try (MzPeakReader reader = MzPeakReader.open(tmp)) {
            assertThat(reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, "MS:9999003")).isEmpty();
            assertThat(reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, "MS:9999004")).isEmpty();
        }
        try (MzPeakSource src = MzPeakSource.open(tmp)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(src);
            assertThat(MetadataColumns.resolve(manifest, src, EntityType.SPECTRUM, DataKind.METADATA, "MS:9999003"))
                    .isPresent();
            assertThat(MetadataColumns.resolve(manifest, src, EntityType.SPECTRUM, DataKind.METADATA, "MS:9999004"))
                    .isEmpty();
        }
    }

    /** A smallest lowest observed m/z above the largest highest one is reported, not returned as a range. */
    @Test
    void anInvertedObservedMzRangeThrows() {
        writeSplitMetadata(new Double[][] {{500.0, 300.0}, {600.0, 400.0}, {null, null}, {null, null},
                {700.0, 350.0}}, true);
        try (MzPeakReader reader = MzPeakReader.open(tmp)) {
            assertThatThrownBy(reader::observedMzRange)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("spectra_metadata.parquet: the smallest lowest observed m/z 500.0 exceeds the largest"
                            + " highest observed m/z 400.0");
        }
    }

    @Test
    void int64StatisticsAreReadSignedOrUnsigned() {
        writeInt64Metadata(new long[] {3_000_000_000L, -5_000_000_000L, 7L},
                new long[] {1L << 40, 1L, 1L << 62});
        try (MzPeakReader reader = MzPeakReader.open(tmp)) {
            ColumnStatistics signed =
                    reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, SIGNED_INT64).orElseThrow();
            assertThat(signed.column()).isEqualTo("signed_count");
            assertThat(signed.rowGroups()).containsExactly(new RowGroup(3, 0L, -5_000_000_000.0, 3_000_000_000.0));
            ColumnStatistics unsigned =
                    reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, UNSIGNED_INT64).orElseThrow();
            assertThat(unsigned.column()).isEqualTo("unsigned_count");
            assertThat(unsigned.rowGroups()).containsExactly(new RowGroup(3, 0L, 1.0, 0x1p62));
        }
    }

    /**
     * An INTEGER(64,false) statistic with the high bit set is beyond the supported range and rejected; the same bits
     * in a signed INT64 column are a negative value. Read through {@link MetadataColumns#statistics} on its own, so
     * that the reader's metadata decoding cannot fail first.
     */
    @Test
    void aUint64StatisticWithTheHighBitSetIsRejected() {
        writeInt64Metadata(new long[] {5L, -1L}, new long[] {5L, -1L});
        try (MzPeakSource src = MzPeakSource.open(tmp)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(src);
            assertThat(MetadataColumns.statistics(manifest, src, EntityType.SPECTRUM, DataKind.METADATA, SIGNED_INT64))
                    .map(ColumnStatistics::rowGroups).contains(List.of(new RowGroup(2, 0L, -1.0, 5.0)));
            assertThatThrownBy(() -> MetadataColumns.statistics(manifest, src, EntityType.SPECTRUM,
                    DataKind.METADATA, UNSIGNED_INT64))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageContaining("unsigned_count")
                    .hasMessageContaining("maximum 18446744073709551615");
        }
    }

    // ---- the record and the reader contract ----------------------------------------------------------------------

    @Test
    void completeAcceptsRowGroupsWithoutRowsOrWithOnlyNulls() {
        RowGroup bounded = new RowGroup(3, 1L, 5.0, 7.0);
        assertThat(stats(bounded, new RowGroup(0, null, null, null), new RowGroup(4, 4L, null, null)).complete())
                .isTrue();
        assertThat(stats(bounded, new RowGroup(4, 3L, null, null)).complete()).isFalse();
        assertThat(stats(bounded, new RowGroup(4, null, null, null)).complete()).isFalse();
        assertThat(stats().complete()).isTrue();
        assertThat(stats().min()).isEmpty();
        assertThat(stats().max()).isEmpty();
        ColumnStatistics partial = stats(new RowGroup(2, 0L, -1.5, 3.0), new RowGroup(2, null, null, null),
                new RowGroup(2, 0L, 0.5, 9.0));
        assertThat(partial.min()).hasValue(-1.5);
        assertThat(partial.max()).hasValue(9.0);
        // the extremes come from the row groups that hold them, wherever these are
        ColumnStatistics middle = stats(new RowGroup(2, 0L, 0.5, 4.0), new RowGroup(2, 0L, -1.5, 9.0),
                new RowGroup(2, 0L, 1.0, 3.0));
        assertThat(middle.min()).hasValue(-1.5);
        assertThat(middle.max()).hasValue(9.0);
        ColumnStatistics last = stats(new RowGroup(2, 0L, 0.5, 9.0), new RowGroup(2, 0L, -1.5, 3.0));
        assertThat(last.min()).hasValue(-1.5);
        assertThat(last.max()).hasValue(9.0);
    }

    @Test
    void invalidRowGroupsAreRejected() {
        assertThatThrownBy(() -> new RowGroup(-1, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RowGroup(1, -1L, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RowGroup(1, 0L, 1.0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RowGroup(1, 0L, Double.NaN, 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RowGroup(1, 0L, 2.0, 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ColumnStatistics("m", "c", null, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    /** A NaN maximum is rejected as a NaN minimum is. */
    @Test
    void aNaNMaximumIsRejected() {
        assertThatThrownBy(() -> new RowGroup(1, 0L, 1.0, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid bounds: [1.0, NaN]");
    }

    @Test
    void nullArgumentsAndAClosedReaderAreRejected() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            assertThatThrownBy(() -> reader.columnStatistics(null, DataKind.METADATA, LOWEST_MZ))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> reader.columnStatistics(EntityType.SPECTRUM, null, LOWEST_MZ))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, null))
                    .isInstanceOf(NullPointerException.class);
            reader.close();
            assertThatThrownBy(() -> reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, LOWEST_MZ))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(reader::observedMzRange).isInstanceOf(IllegalStateException.class);
            // the retention times are held in memory
            assertThat(reader.observedTimeRange()).contains(new DoubleRange(0.004935, 0.487236666667));
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static ColumnStatistics stats(RowGroup... rowGroups) {
        return new ColumnStatistics("member", "column", LOWEST_MZ, List.of(rowGroups));
    }

    /**
     * A split spectrum metadata member of 5 rows in row groups of 2, 2 and 1 rows, whose lowest and highest observed
     * m/z are the pairs of {@code mz} ({@code null} for a null cell), with a manifest that maps its columns.
     */
    private void writeSplitMetadata(Double[][] mz, boolean statistics) {
        writeSplitMetadata(mz, statistics, null);
    }

    /**
     * {@link #writeSplitMetadata(Double[][], boolean)} without the observed m/z column of the accession
     * {@code omitted} ({@code null} for none): it is neither written nor mapped, as the column table would otherwise
     * find it by its name.
     */
    private void writeSplitMetadata(Double[][] mz, boolean statistics, String omitted) {
        boolean lowest = !LOWEST_MZ.equals(omitted);
        boolean highest = !HIGHEST_MZ.equals(omitted);
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional int64 index (INTEGER(64,false));\n"
                + "  optional double time;\n"
                + (lowest ? "  optional double lowest_observed_mz;\n" : "")
                + (highest ? "  optional double highest_observed_mz;\n" : "")
                + "  optional int32 number_of_data_points (INTEGER(32,false));\n"
                + "}");
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);
        int[] points = {10, (int) 4_000_000_000L, 20, 30, 40};
        Group[] rows = new Group[mz.length];
        for (int i = 0; i < mz.length; i++) {
            Group g = factory.newGroup().append("index", (long) i).append("time", i + 1.0)
                    .append("number_of_data_points", points[i]);
            if (lowest && mz[i][0] != null) {
                g.append("lowest_observed_mz", mz[i][0]);
            }
            if (highest && mz[i][1] != null) {
                g.append("highest_observed_mz", mz[i][1]);
            }
            rows[i] = g;
        }
        write("spectra_metadata.parquet", schema, 2, statistics, rows);
        manifest("{\"name\": \"spectra_metadata.parquet\", \"entity_type\": \"spectrum\", \"data_kind\": \"metadata\","
                + " \"column_mapping\": ["
                + (lowest ? "{\"name\": \"lowest observed m/z\", \"path\": \"lowest_observed_mz\","
                + " \"accession\": \"MS:1000528\", \"unit\": \"MS:1000040\"}," : "")
                + (highest ? "{\"name\": \"highest observed m/z\", \"path\": \"highest_observed_mz\","
                + " \"accession\": \"MS:1000527\", \"unit\": \"MS:1000040\"}," : "")
                + "{\"name\": \"number of data points\", \"path\": \"number_of_data_points\","
                + " \"accession\": \"MS:1003060\", \"unit\": null}]}");
    }

    /**
     * A split spectrum metadata member of one row group whose INT64 column {@code signed_count} holds {@code signed}
     * and whose INTEGER(64,false) column {@code unsigned_count} holds {@code unsigned}, mapped to the made-up terms
     * {@link #SIGNED_INT64} and {@link #UNSIGNED_INT64}.
     */
    private void writeInt64Metadata(long[] signed, long[] unsigned) {
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional int64 index (INTEGER(64,false));\n"
                + "  optional double time;\n"
                + "  optional int64 signed_count;\n"
                + "  optional int64 unsigned_count (INTEGER(64,false));\n"
                + "}");
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);
        Group[] rows = new Group[signed.length];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = factory.newGroup().append("index", (long) i).append("time", i + 1.0)
                    .append("signed_count", signed[i]).append("unsigned_count", unsigned[i]);
        }
        write("spectra_metadata.parquet", schema, Integer.MAX_VALUE, true, rows);
        manifest("{\"name\": \"spectra_metadata.parquet\", \"entity_type\": \"spectrum\", \"data_kind\": \"metadata\","
                + " \"column_mapping\": ["
                + "{\"name\": \"signed count\", \"path\": \"signed_count\", \"accession\": \"" + SIGNED_INT64 + "\","
                + " \"unit\": null},"
                + "{\"name\": \"unsigned count\", \"path\": \"unsigned_count\", \"accession\": \"" + UNSIGNED_INT64
                + "\", \"unit\": null}]}");
    }

    private void manifest(String entry) {
        try {
            Files.writeString(tmp.resolve(TestSources.MANIFEST), "{\"files\": [" + entry + "], \"metadata\": {}}",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(String name, MessageType schema, int rowGroupRows, boolean statistics, Group... rows) {
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(tmp.resolve(name)))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .withRowGroupRowCountLimit(rowGroupRows)
                .withStatisticsEnabled(statistics)
                .build()) {
            for (Group row : rows) {
                writer.write(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
