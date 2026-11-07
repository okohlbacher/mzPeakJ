package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.schema.LogicalTypeAnnotation.IntLogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.GoldenMeta.Row;
import org.mzpeak.io.parquet.ParquetGroups;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The raw metadata cells ({@link GoldenMeta}) that {@code generate_golden.py --meta} dumped with pyarrow from the
 * current fixtures. Every member the manifest lists as metadata, scans, precursors or selected_ions must be in
 * the file with its Parquet row count, every top-level leaf cell must equal an independent parquet-java read of
 * the member, and the cells must hold the values the unit FX-2 names. Members are found by their manifest role.
 */
class GoldenMetaTest {

    private static final List<String> META_KINDS = List.of("metadata", "scans", "precursors", "selected_ions");
    private static final String DROPPED = "auxiliary_arrays";

    static Stream<String> currentFixtures() {
        return Fixtures.CURRENT_NAMES.stream();
    }

    // ---- the meta files --------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void headerNamesThePinnedPythonAndPyarrowAndTheCommand(String fixture) throws IOException {
        GoldenMeta meta = GoldenMeta.load(fixture);
        String commit = Files.readAllLines(Fixtures.CURRENT.resolve("UPSTREAM_COMMIT.txt")).get(0).trim();
        String pyarrow = Files.readAllLines(Golden.DIR.resolve("uv-requirements.txt")).stream()
                .filter(l -> l.startsWith("pyarrow==")).findFirst().orElseThrow();

        assertThat(meta.header())
                .containsEntry("python", "CPython " + readmePythonVersion())
                .containsEntry("packages", pyarrow)
                .containsEntry("command",
                        "generate_golden.py " + fixture + " --out golden --meta --reader-commit " + commit)
                .containsEntry("data-kinds", String.join(" ", META_KINDS))
                .containsEntry("dropped-columns", DROPPED)
                .containsEntry("columns", "member entity_type data_kind row values");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void everyMetadataMemberIsDumpedWithItsParquetRowCount(String fixture) throws IOException {
        GoldenMeta meta = GoldenMeta.load(fixture);
        List<String> listed = new ArrayList<>();
        try (MzPeakSource src = MzPeakSource.open(Fixtures.current(fixture))) {
            for (MzPeakManifest.Entry e : MzPeakManifest.fromSource(src).files()) {
                if (!META_KINDS.contains(e.dataKind())) {
                    continue;
                }
                listed.add(e.name());
                long count;
                try (ParquetFileReader reader = ParquetFileReader.open(src.inputFile(e.name()),
                        ParquetGroups.readOptions())) {
                    count = reader.getRecordCount();
                }
                List<Row> rows = meta.rows(e.name());
                assertThat((long) rows.size()).as("%s: rows of %s", fixture, e.name()).isEqualTo(count);
                assertThat(rows).as("%s: role of %s", fixture, e.name()).allSatisfy(r -> {
                    assertThat(r.entityType()).isEqualTo(e.entityType());
                    assertThat(r.dataKind()).isEqualTo(e.dataKind());
                });
            }
        }
        Set<String> dumped = new LinkedHashSet<>();
        meta.rows().forEach(r -> dumped.add(r.member()));
        assertThat(dumped).as("%s: dumped members in manifest order", fixture)
                .containsExactlyElementsOf(listed.stream().filter(m -> !meta.rows(m).isEmpty()).toList());
        assertThat(listed).as("%s: metadata members", fixture).hasSizeGreaterThanOrEqualTo(4);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void topLevelLeafCellsMatchAParquetJavaRead(String fixture) throws IOException {
        GoldenMeta meta = GoldenMeta.load(fixture);
        try (MzPeakSource src = MzPeakSource.open(Fixtures.current(fixture))) {
            for (MzPeakManifest.Entry e : MzPeakManifest.fromSource(src).files()) {
                if (!META_KINDS.contains(e.dataKind())) {
                    continue;
                }
                InputFile input = src.inputFile(e.name());
                MessageType schema;
                try (ParquetFileReader reader = ParquetFileReader.open(input, ParquetGroups.readOptions())) {
                    schema = reader.getFooter().getFileMetaData().getSchema();
                }
                List<String> columns = schema.getFields().stream().map(Type::getName)
                        .filter(n -> !n.equals(DROPPED)).toList();
                List<Row> rows = meta.rows(e.name());
                int[] next = {0};
                ParquetGroups.forEach(input, g -> {
                    assertThat(next[0]).as("%s %s: Parquet row %d in the meta file", fixture, e.name(), next[0])
                            .isLessThan(rows.size());
                    Row r = rows.get(next[0]++);
                    assertThat(r.values().keySet()).as("%s %s row %d columns", fixture, e.name(), r.row())
                            .containsExactlyElementsOf(columns);
                    for (Type t : schema.getFields()) {
                        if (t.isPrimitive()) {
                            assertThat(leaf(g, t.asPrimitiveType()))
                                    .as("%s %s row %d %s", fixture, e.name(), r.row(), t.getName())
                                    .isEqualTo(r.get(t.getName()));
                        }
                    }
                });
                assertThat(next[0]).as("%s %s rows read", fixture, e.name()).isEqualTo(rows.size());
            }
        }
    }

    @Test
    void metaFilesStayUnderTheSizeCap() throws IOException {
        long total = 0;
        for (String fixture : Fixtures.CURRENT_NAMES) {
            String name = fixture.substring(0, fixture.length() - ".mzpeak".length());
            total += Files.size(Golden.DIR.resolve(name + ".meta.jsonl"));
        }
        assertThat(total).isPositive().isLessThan(3_000_000L);
    }

    // ---- anchors -----------------------------------------------------------------------------------

    @Test
    void smallScanZeroStartsAtTheFirstScanTime() {
        Row r = rows("small.mzpeak", "spectrum", "scans").get(0);
        assertThat(r.get("source_index")).isEqualTo(0L);
        assertThat(r.get("scan_start_time")).isEqualTo(0.004935);
    }

    @Test
    void diaPasefRefScanOneCarriesItsInverseReducedIonMobility() {
        Row r = rows("diaPASEF.ref.mzpeak", "spectrum", "scans").get(1);
        assertThat(r.get("ion_mobility_value")).isEqualTo(1.2517460582524271);
        assertThat(r.get("ion_mobility_type")).isEqualTo("MS:1002815");
    }

    @Test
    void exampleProcessedImagingScanZeroIsAtPositionXOne() {
        Row r = rows("Example_Processed.img.mzpeak", "spectrum", "scans").get(0);
        assertThat(r.get("opt_IMS_1000050_position_x")).isEqualTo(1L);
    }

    @Test
    void everySmallPrecursorIsCollisionInducedDissociation() {
        List<Row> rows = rows("small.mzpeak", "spectrum", "precursors");
        assertThat(rows).hasSize(34)
                .allSatisfy(r -> assertThat(r.get("activation.dissociation_method")).isEqualTo("MS:1000133"));
    }

    // ---- the parser and the path accessors ---------------------------------------------------------

    @Test
    void dottedPathsWalkStructsAndListsAndRejectUnknownFields() {
        Row scan = rows("small.mzpeak", "spectrum", "scans").get(0);
        assertThat(scan.get("parameters.0.accession")).isEqualTo("MS:1000800");
        assertThat(scan.get("parameters.0.value.integer")).isEqualTo(100000L);
        assertThat(scan.get("parameters.0.value.float")).isNull();
        assertThat(scan.list("parameters.accession")).containsExactly("MS:1000800");
        assertThat(scan.list("scan_windows.scan_window_lower_limit")).containsExactly(200.0);
        assertThat(scan.list("scan_windows.scan_window_upper_limit")).containsExactly(2000.0);
        assertThat(scan.list("scan_start_time")).containsExactly(0.004935);
        assertThat(scan.get("ion_mobility_value")).isNull();

        assertThatThrownBy(() -> scan.get("no_such_column")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no field no_such_column");
        assertThatThrownBy(() -> scan.get("scan_windows.scan_window_lower_limit"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not an index");
        assertThatThrownBy(() -> scan.get("scan_windows.1")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an index");
        assertThatThrownBy(() -> scan.get("scan_start_time.unit")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("below a scalar");
        assertThatThrownBy(() -> scan.list("scan_windows.no_such_field"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no field no_such_field");

        List<Row> spectra = rows("small.mzpeak", "spectrum", "metadata");
        assertThat(spectra.get(0).list("polynomial_delta_coordinate_interpolation"))
                .hasSize(3).allMatch(Double.class::isInstance);
        assertThat(spectra.get(2).get("polynomial_delta_coordinate_interpolation")).isNull();
        assertThat(spectra.get(2).get("ms_level")).isEqualTo(2L);
    }

    @Test
    void numbersParseAsLongOrDoubleIncludingNonFiniteValues() throws IOException {
        String line = rawLine("small", "\"data_kind\":\"scans\",\"row\":0,");
        assertThat(line).contains("\"ion_injection_time\":68.22748565673828", "\"source_index\":0,");

        for (String token : List.of("NaN", "Infinity", "-Infinity")) {
            Row r = GoldenMeta.parseRow(line.replace("\"ion_injection_time\":68.22748565673828",
                    "\"ion_injection_time\":" + token));
            assertThat(r.get("ion_injection_time")).as(token).isEqualTo(Double.valueOf(token));
        }
        Row r = GoldenMeta.parseRow(line);
        assertThat(r.get("ion_injection_time")).isEqualTo((double) 68.22748565673828f);
        assertThat(r.get("preset_scan_configuration")).isEqualTo(1L);
        assertThat(r.get("scan_windows.0.scan_window_lower_limit")).isEqualTo(200.0);
        assertThat(r.values()).isUnmodifiable();
        assertThat((List<?>) r.get("scan_windows")).isUnmodifiable();

        assertThatThrownBy(() -> GoldenMeta.parseRow(line.replace("\"source_index\":0,",
                "\"source_index\":18446744073709551615,")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside the long range");
        assertThatThrownBy(() -> GoldenMeta.parseRow(line.replace("\"row\":0,", "")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fields");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** The meta rows of the member with role ({@code entityType}, {@code dataKind}) in {@code fixture}. */
    private static List<Row> rows(String fixture, String entityType, String dataKind) {
        try (MzPeakSource src = MzPeakSource.open(Fixtures.current(fixture))) {
            String member = MzPeakManifest.fromSource(src).find(entityType, dataKind).orElseThrow().name();
            return GoldenMeta.load(fixture).rows(member);
        }
    }

    /** The first line of {@code <name>.meta.jsonl} that contains {@code fragment}. */
    private static String rawLine(String name, String fragment) throws IOException {
        return Files.readAllLines(Golden.DIR.resolve(name + ".meta.jsonl")).stream()
                .filter(l -> l.contains(fragment)).findFirst().orElseThrow();
    }

    /**
     * A top-level leaf cell read with parquet-java through {@link ParquetGroups}, typed like {@link GoldenMeta}
     * values: integers as Long (unsigned 32-bit ones without sign extension), FLOAT and DOUBLE as Double.
     */
    private static Object leaf(Group g, PrimitiveType t) {
        String field = t.getName();
        if (!ParquetGroups.has(g, field)) {
            return null;
        }
        return switch (t.getPrimitiveTypeName()) {
            case BOOLEAN -> g.getBoolean(field, 0);
            case BINARY -> ParquetGroups.optString(g, field);
            case FLOAT, DOUBLE -> ParquetGroups.optDouble(g, field);
            case INT32 -> {
                boolean unsigned = t.getLogicalTypeAnnotation() instanceof IntLogicalTypeAnnotation i && !i.isSigned();
                yield unsigned ? Integer.toUnsignedLong(g.getInteger(field, 0)) : ParquetGroups.optLong(g, field);
            }
            case INT64 -> ParquetGroups.optLong(g, field);
            default -> throw new IllegalStateException("unexpected leaf " + t);
        };
    }

    private static String readmePythonVersion() throws IOException {
        Matcher m = Pattern.compile("CPython \\*\\*([0-9.]+)\\*\\*")
                .matcher(Files.readString(Golden.DIR.resolve("README.md")));
        assertThat(m.find()).isTrue();
        return m.group(1);
    }
}
