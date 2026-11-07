package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.GoldenMeta.Row;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Param;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ColumnResolver}: accession lookup through the member's column_mapping, then {@link ColumnTable} and its
 * legacy fallbacks; term markers; unsigned reads; and the scalar-only leftovers of a split scope. Values are checked
 * against the raw pyarrow cells of {@link GoldenMeta} (FX-2). Members are found by role through {@link Facet#entry}.
 */
class ColumnResolverTest {

    /** The fields the spectrum row decoder consumes (design §5.2), as the leftovers test passes them. */
    private static final Set<String> SPECTRUM_CONSUMED = Set.of("ms_level", "time", "scan_polarity",
            "spectrum_representation", "number_of_data_points", "number_of_peaks");

    static Stream<String> smallFixtures() {
        return Stream.of("small.mzpeak", "small.chunked.mzpeak", "small.numpress.mzpeak", "small.unpacked.mzpeak");
    }

    // ---- current schemas -------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void spectrumColumnsResolveByAccessionAndReadTheirCells(String fixture) {
        Table t = current(fixture, Facet.SPECTRUM);
        List<Row> cells = GoldenMeta.load(fixture).rows(t.entry().name());
        assertThat(cells).hasSize(t.rows().size()).hasSize(48);
        for (Entry entry : List.of(t.entry(), bare(t.entry()))) {
            ColumnResolver columns = ColumnResolver.split(t.schema(), entry, Facet.SPECTRUM);
            assertThat(columns.resolve("MS:1000511")).as("%s", entry).isEqualTo("ms_level");
            assertThat(columns.resolve("MS:1003821")).isEqualTo("polynomial_delta_coordinate_interpolation");
            for (int i = 0; i < cells.size(); i++) {
                Group row = t.rows().get(i);
                assertThat(columns.longValue(row, columns.resolve("MS:1000511"))).as("row %d", i)
                        .isEqualTo(cells.get(i).get("ms_level")).isIn(1L, 2L);
                Object model = cells.get(i).get("polynomial_delta_coordinate_interpolation");
                double[] read = columns.doubleList(row, columns.resolve("MS:1003821"));
                if (model == null) {
                    assertThat(read).as("row %d", i).isEmpty();
                } else {
                    assertThat(boxed(read)).as("row %d", i).isEqualTo(model);
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void precursorStructsResolveTheIsolationWindowAndTheActivationMarker(String fixture) {
        Table t = current(fixture, Facet.SPECTRUM_PRECURSOR);
        List<Row> cells = GoldenMeta.load(fixture).rows(t.entry().name());
        assertThat(cells).hasSize(t.rows().size()).hasSize(34);
        for (Entry entry : List.of(t.entry(), bare(t.entry()))) {
            ColumnResolver columns = ColumnResolver.split(t.schema(), entry, Facet.SPECTRUM_PRECURSOR);
            assertThat(columns.resolve("MS:1000827")).as("not directly in the top scope").isNull();
            ColumnResolver window = columns.child("isolation_window");
            assertThat(window.resolve("MS:1000827")).as("%s", entry).isEqualTo("isolation_window_target");
            ColumnResolver activation = columns.child("activation");
            assertThat(activation.resolve("MS:1000044")).isEqualTo("dissociation_method");
            assertThat(activation.termMarker("dissociation_method")).isTrue();
            assertThat(activation.termMarker("collision_energy")).isFalse();
            for (int i = 0; i < cells.size(); i++) {
                FacetRow row = new FacetRow(Facet.SPECTRUM_PRECURSOR, t.rows().get(i), columns);
                FacetRow w = row.child("isolation_window");
                FacetRow a = row.child("activation");
                assertThat(w.columns().doubleValue(w.group(), w.columns().resolve("MS:1000827"))).as("row %d", i)
                        .isEqualTo(cells.get(i).get("isolation_window.isolation_window_target")).isNotNull();
                assertThat(a.columns().stringValue(a.group(), a.columns().resolve("MS:1000044"))).as("row %d", i)
                        .isEqualTo(cells.get(i).get("activation.dissociation_method")).isEqualTo("MS:1000133");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void scanWindowListElementsResolveTheirLimits(String fixture) {
        Table t = current(fixture, Facet.SPECTRUM_SCAN);
        List<Row> cells = GoldenMeta.load(fixture).rows(t.entry().name());
        for (Entry entry : List.of(t.entry(), bare(t.entry()))) {
            ColumnResolver columns = ColumnResolver.split(t.schema(), entry, Facet.SPECTRUM_SCAN);
            assertThat(columns.resolve("MS:1000016")).isEqualTo("scan_start_time");
            assertThat(columns.child("scan_windows").resolve("MS:1000501")).isEqualTo("scan_window_lower_limit");
            for (int i = 0; i < cells.size(); i++) {
                FacetRow row = new FacetRow(Facet.SPECTRUM_SCAN, t.rows().get(i), columns);
                List<Object> lower = new ArrayList<>();
                for (FacetRow w : row.children("scan_windows")) {
                    lower.add(w.columns().doubleValue(w.group(), w.columns().resolve("MS:1000501")));
                }
                assertThat(lower).as("row %d", i)
                        .isEqualTo(cells.get(i).list("scan_windows.scan_window_lower_limit")).hasSize(1);
                assertThat(columns.doubleValue(row.group(), columns.resolve("MS:1000016"))).as("row %d", i)
                        .isEqualTo(cells.get(i).get("scan_start_time"));
            }
        }
    }

    @Test
    void imagingPositionsResolveToTheOptColumns() {
        String fixture = "Example_Processed.img.mzpeak";
        Table t = current(fixture, Facet.SPECTRUM_SCAN);
        List<Row> cells = GoldenMeta.load(fixture).rows(t.entry().name());
        assertThat(cells).hasSize(t.rows().size()).hasSize(9);
        for (Entry entry : List.of(t.entry(), bare(t.entry()))) {
            ColumnResolver columns = ColumnResolver.split(t.schema(), entry, Facet.SPECTRUM_SCAN);
            assertThat(columns.resolve("IMS:1000050")).as("%s", entry).isEqualTo("opt_IMS_1000050_position_x");
            assertThat(columns.resolve("IMS:1000051")).isEqualTo("opt_IMS_1000051_position_y");
            for (int i = 0; i < cells.size(); i++) {
                Group row = t.rows().get(i);
                assertThat(columns.longValue(row, columns.resolve("IMS:1000050"))).as("row %d", i)
                        .isEqualTo(cells.get(i).get("opt_IMS_1000050_position_x")).isEqualTo(i % 3 + 1L);
                assertThat(columns.longValue(row, columns.resolve("IMS:1000051"))).as("row %d", i)
                        .isEqualTo(cells.get(i).get("opt_IMS_1000051_position_y")).isEqualTo(i / 3 + 1L);
            }
        }
    }

    // ---- packed legacy schemas -------------------------------------------------------------------------------

    @Test
    void packedLegacyStructsResolveThePinEraNamesAndHaveNoLeftovers() {
        Table legacy;
        try (MzPeakSource src = MzPeakSource.open(Fixtures.legacy("small.unpacked.mzpeak"))) {
            legacy = read(src, Facet.SPECTRUM.entry(MzPeakManifest.fromSource(src)).orElseThrow());
        }
        ColumnResolver spectrum = ColumnResolver.packed(legacy.schema().getType("spectrum").asGroupType(),
                Facet.SPECTRUM);
        ColumnResolver scan = ColumnResolver.packed(legacy.schema().getType("scan").asGroupType(),
                Facet.SPECTRUM_SCAN);
        assertThat(spectrum.resolve("MS:1000511")).isEqualTo("MS_1000511_ms_level");
        assertThat(spectrum.resolve("MS:1003821")).isNull();
        assertThat(spectrum.resolve("MS:1003820", "coordinate_spacing_model", "mz_delta_model"))
                .isEqualTo("mz_delta_model");
        assertThat(spectrum.termMarker("MS_1000525_spectrum_representation")).isTrue();
        assertThat(spectrum.mapping("MS_1000511_ms_level")).contains(
                new ColumnMapping("ms level", "MS_1000511_ms_level", "MS:1000511", null, false));
        assertThat(scan.resolve("MS:1000016")).isEqualTo("MS_1000016_scan_start_time_unit_UO_0000031");
        assertThat(scan.child("scan_windows").resolve("MS:1000501"))
                .isEqualTo("MS_1000501_scan_window_lower_limit_unit_MS_1000040");

        // the same spectra as the current small, whose ms_level cells are the oracle
        Map<Long, Object> msLevels = new HashMap<>();
        for (Row r : GoldenMeta.load("small.unpacked.mzpeak").rows("spectra_metadata.parquet")) {
            msLevels.put((Long) r.get("index"), r.get("ms_level"));
        }
        int spectra = 0;
        for (Group row : legacy.rows()) {
            Group s = ParquetGroups.optGroup(row, "spectrum");
            if (s != null) {
                spectra++;
                assertThat(spectrum.longValue(s, spectrum.resolve("MS:1000511")))
                        .isEqualTo(msLevels.get(spectrum.longValue(s, "index")));
                assertThat(spectrum.leftovers(s, Set.of())).isEmpty();
            }
            Group sc = ParquetGroups.optGroup(row, "scan");
            if (sc != null) {
                assertThat(scan.leftovers(sc, Set.of())).isEmpty();
            }
        }
        assertThat(spectra).isEqualTo(48);
    }

    // ---- leftovers -------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void smallSpectrumZeroLeftoversAreItsUnconsumedScalarCellsInSchemaOrder(String fixture) {
        Table t = current(fixture, Facet.SPECTRUM);
        Row cells = GoldenMeta.load(fixture).rows(t.entry().name()).get(0);
        Group row = t.rows().get(0);
        assertThat(cells.get("index")).isEqualTo(0L);
        assertThat(cells.get("spectrum_type")).isEqualTo("MS:1000579");

        List<Param> expected = List.of(
                new Param(null, "MS:1000579", null, null),
                new Param("lowest observed m/z", "MS:1000528", cells.get("lowest_observed_mz"), "MS:1000040"),
                new Param("highest observed m/z", "MS:1000527", cells.get("highest_observed_mz"), "MS:1000040"),
                new Param("base peak m/z", "MS:1000504", cells.get("base_peak_mz"), "MS:1000040"),
                new Param("base peak intensity", "MS:1000505", cells.get("base_peak_intensity"), "MS:1000131"),
                new Param("total ion current", "MS:1000285", cells.get("total_ion_current"), "MS:1000131"));
        assertThat(expected).allSatisfy(p -> assertThat(p.value() == null || p.value() instanceof Double).isTrue());

        ColumnResolver columns = ColumnResolver.split(t.schema(), t.entry(), Facet.SPECTRUM);
        assertThat(columns.leftovers(row, SPECTRUM_CONSUMED)).containsExactlyElementsOf(expected);
        // ColumnTable supplies the same identities when the manifest maps nothing
        assertThat(ColumnResolver.split(t.schema(), bare(t.entry()), Facet.SPECTRUM)
                .leftovers(row, SPECTRUM_CONSUMED)).containsExactlyElementsOf(expected);
        // and the term marker when the manifest omits the key
        Entry withoutMarker = withMapping(t.entry(), "spectrum_type",
                m -> new ColumnMapping(m.name(), m.path(), m.accession(), m.unit(), null));
        assertThat(withoutMarker.columnAtPath("spectrum_type").orElseThrow().termMarker()).isNull();
        assertThat(ColumnResolver.split(t.schema(), withoutMarker, Facet.SPECTRUM)
                .leftovers(row, SPECTRUM_CONSUMED)).containsExactlyElementsOf(expected);

        // with nothing consumed: the mapped and unmapped scalars too, still no structural column and no list
        List<Param> all = new ArrayList<>(List.of(
                new Param("ms level", "MS:1000511", 1L, null),
                new Param("time", null, cells.get("time"), null),
                new Param("scan polarity", "MS:1000465", 1L, null),
                new Param(null, "MS:1000128", null, null),
                expected.get(0), expected.get(1), expected.get(2),
                new Param("number of data points", "MS:1003060", cells.get("number_of_data_points"), null)));
        if (cells.get("number_of_peaks") != null) { // small.chunked counts the peaks of spectrum 0 too
            all.add(new Param("number of peaks", "MS:1003059", cells.get("number_of_peaks"), null));
        }
        all.addAll(expected.subList(3, 6));
        assertThat(cells.get("number_of_data_points")).isEqualTo(13589L);
        assertThat(columns.leftovers(row, Set.of())).containsExactlyElementsOf(all);
    }

    @Test
    void smallSpectrumZeroLeftoversHoldTheSummaryValues() {
        Table t = current("small.mzpeak", Facet.SPECTRUM);
        List<Param> leftovers = ColumnResolver.split(t.schema(), t.entry(), Facet.SPECTRUM)
                .leftovers(t.rows().get(0), SPECTRUM_CONSUMED);
        assertThat(leftovers).extracting(Param::value)
                .containsExactly(null, 200.00018816645024, 1999.9857293095915, 810.4154747204038, 1471224.875,
                        71263168.0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void structuralColumnsNeverBecomeLeftovers(String fixture) {
        Table t = current(fixture, Facet.SPECTRUM);
        List<Row> cells = GoldenMeta.load(fixture).rows(t.entry().name());
        ColumnResolver columns = ColumnResolver.split(t.schema(), t.entry(), Facet.SPECTRUM);
        Set<String> names = Set.of("index", "id", "data_processing_id", "parameters", "auxiliary_arrays",
                "number_of_auxiliary_arrays", "polynomial_delta_coordinate_interpolation");
        for (int i = 0; i < cells.size(); i++) {
            Group row = t.rows().get(i);
            assertThat(cells.get(i).get("number_of_auxiliary_arrays")).as("row %d", i).isEqualTo(0L);
            assertThat(cells.get(i).get("data_processing_id")).as("row %d", i).isNull();
            assertThat(ParquetGroups.has(row, "auxiliary_arrays")).as("row %d", i).isTrue();
            assertThat(ParquetGroups.groupList(row, "auxiliary_arrays")).as("row %d", i).isEmpty();
            // one Param per non-null scalar cell that is not structural
            long scalars = cells.get(i).values().entrySet().stream()
                    .filter(e -> e.getValue() != null && !(e.getValue() instanceof List)
                            && !(e.getValue() instanceof Map))
                    .filter(e -> !ColumnTable.structural(Facet.SPECTRUM).contains(e.getKey()))
                    .count();
            List<Param> leftovers = columns.leftovers(row, Set.of());
            assertThat(leftovers).as("row %d", i).hasSize((int) scalars)
                    .noneMatch(p -> p.name() != null && names.contains(p.name()));
        }
    }

    @Test
    void aBooleanTermMarkerGivesAParamOnlyWhenTrue() {
        MessageType schema = Types.buildMessage()
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("source_index")
                .optionalGroup()
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                        .named("dissociation_method")
                    .optional(PrimitiveTypeName.BOOLEAN).named("supplemental_cid")
                    .named("activation")
                .named("arrow_schema");
        Entry entry = new Entry("constructed_precursors.parquet", "spectrum", "precursors", List.of(
                new ColumnMapping("supplemental collision-induced dissociation", "activation.supplemental_cid",
                        "MS:1002679", null, true)), List.of(), null);
        ColumnResolver activation = ColumnResolver.split(schema, entry, Facet.SPECTRUM_PRECURSOR).child("activation");
        assertThat(activation.resolve("MS:1002679")).isEqualTo("supplemental_cid");
        assertThat(activation.termMarker("supplemental_cid")).isTrue();
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);

        Group present = factory.newGroup().append("source_index", 3L);
        present.addGroup("activation").append("dissociation_method", "MS:1000598").append("supplemental_cid", true);
        Group absent = factory.newGroup().append("source_index", 3L);
        absent.addGroup("activation").append("dissociation_method", "MS:1000598").append("supplemental_cid", false);
        Group unknown = factory.newGroup().append("source_index", 3L);
        unknown.addGroup("activation").append("dissociation_method", "MS:1000598");

        Param supplemental = new Param("supplemental collision-induced dissociation", "MS:1002679", null, null);
        assertThat(activation.leftovers(present.getGroup("activation", 0), Set.of()))
                .containsExactly(new Param(null, "MS:1000598", null, null), supplemental);
        assertThat(activation.leftovers(present.getGroup("activation", 0), Set.of("dissociation_method")))
                .containsExactly(supplemental);
        assertThat(activation.leftovers(absent.getGroup("activation", 0), Set.of("dissociation_method")))
                .isEmpty();
        assertThat(activation.leftovers(unknown.getGroup("activation", 0), Set.of("dissociation_method")))
                .isEmpty();
    }

    // ---- unsigned reads --------------------------------------------------------------------------------------

    @Test
    void unsignedIntegersAreNotSignExtendedAndAUint64HighBitThrows() {
        MessageType schema = Types.buildMessage()
                .optional(PrimitiveTypeName.INT32).as(LogicalTypeAnnotation.intType(32, false)).named("u32")
                .optional(PrimitiveTypeName.INT32).as(LogicalTypeAnnotation.intType(8, false)).named("u8")
                .optional(PrimitiveTypeName.INT32).as(LogicalTypeAnnotation.intType(8, true)).named("i8")
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("u64")
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("u64_max")
                .optional(PrimitiveTypeName.INT64).named("i64")
                .optional(PrimitiveTypeName.FLOAT).named("f32")
                .optional(PrimitiveTypeName.INT32).as(LogicalTypeAnnotation.intType(32, false)).named("missing")
                .named("arrow_schema");
        Group row = new SimpleGroupFactory(schema).newGroup()
                .append("u32", 0xFFFFFFFF)
                .append("u8", 255)
                .append("i8", -1)
                .append("u64", 0x8000000000000000L)
                .append("u64_max", Long.MAX_VALUE)
                .append("i64", -5L)
                .append("f32", 2.5f);
        ColumnResolver columns = ColumnResolver.split(schema, new Entry("constructed.parquet", "spectrum",
                "metadata"), Facet.SPECTRUM);

        assertThat(columns.longValue(row, "u32")).isEqualTo(4294967295L);
        assertThat(columns.doubleValue(row, "u32")).isEqualTo(4294967295.0);
        assertThat(columns.longValue(row, "u8")).isEqualTo(255L);
        assertThat(columns.longValue(row, "i8")).isEqualTo(-1L);
        assertThat(columns.intValue(row, "i8")).isEqualTo(-1);
        assertThat(columns.doubleValue(row, "i8")).isEqualTo(-1.0);
        assertThat(columns.longValue(row, "u64_max")).isEqualTo(Long.MAX_VALUE);
        assertThat(columns.longValue(row, "i64")).isEqualTo(-5L);
        assertThat(columns.longValue(row, "f32")).isEqualTo(2L);
        assertThat(columns.doubleValue(row, "f32")).isEqualTo(2.5);
        assertThat(columns.longValue(row, "missing")).isNull();
        assertThat(columns.longValue(row, "no_such_column")).isNull();
        assertThat(columns.longValue(row, null)).isNull();

        assertThatThrownBy(() -> columns.longValue(row, "u64")).isInstanceOf(MzPeakException.class)
                .hasMessageContaining("u64").hasMessageContaining("9223372036854775808")
                .hasMessageContaining("constructed.parquet");
        assertThatThrownBy(() -> columns.doubleValue(row, "u64")).isInstanceOf(MzPeakException.class)
                .hasMessageContaining("u64");
        assertThatThrownBy(() -> columns.leftovers(row, Set.of())).isInstanceOf(MzPeakException.class)
                .hasMessageContaining("u64");
        assertThatThrownBy(() -> columns.intValue(row, "u32")).isInstanceOf(MzPeakException.class)
                .hasMessageContaining("u32").hasMessageContaining("4294967295");
        assertThat(columns.leftovers(row, Set.of("u64"))).containsExactly(
                new Param("u32", null, 4294967295L, null),
                new Param("u8", null, 255L, null),
                new Param("i8", null, -1L, null),
                new Param("u64_max", null, Long.MAX_VALUE, null),
                new Param("i64", null, -5L, null),
                new Param("f32", null, 2.5, null));
    }

    // ---- resolution order ------------------------------------------------------------------------------------

    @Test
    void theMappingWinsThenColumnTableThenItsFallbacksThenTheExtraNames() {
        MessageType schema = Types.buildMessage()
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("index")
                .optional(PrimitiveTypeName.INT32).named("level")
                .optional(PrimitiveTypeName.INT32).named("ms_level")
                .optional(PrimitiveTypeName.INT32).named("MS_1000465_scan_polarity")
                .optional(PrimitiveTypeName.DOUBLE).named("median_delta")
                .named("arrow_schema");
        Entry mapped = new Entry("constructed.parquet", "spectrum", "metadata", List.of(
                new ColumnMapping("ms level", "no_such_column", "MS:1000511", null, null),
                new ColumnMapping("ms level", "level", "MS:1000511", null, null)), List.of(), null);
        ColumnResolver columns = ColumnResolver.split(schema, mapped, Facet.SPECTRUM);

        assertThat(columns.resolve("MS:1000511")).as("first mapping whose path is present").isEqualTo("level");
        assertThat(ColumnResolver.split(schema, bare(mapped), Facet.SPECTRUM).resolve("MS:1000511"))
                .as("ColumnTable path").isEqualTo("ms_level");
        assertThat(columns.resolve("MS:1000465")).as("ColumnTable fallback").isEqualTo("MS_1000465_scan_polarity");
        assertThat(columns.resolve("MS:1003820", "coordinate_spacing_model", "median_delta"))
                .as("extra names").isEqualTo("median_delta");
        assertThat(columns.resolve("MS:1003820", "coordinate_spacing_model")).isNull();
        assertThat(columns.resolveName("absent", "ms_level", "level")).isEqualTo("ms_level");
        assertThat(columns.resolveName("absent")).isNull();

        assertThat(columns.mapping("level")).contains(mapped.columnMapping().get(1));
        assertThat(columns.mapping("ms_level")).as("from ColumnTable")
                .contains(new ColumnMapping("ms level", "ms_level", "MS:1000511", null, false));
        assertThat(columns.mapping("median_delta")).isEmpty();
        assertThat(columns.mapping(null)).isEmpty();
        assertThat(columns.termMarker("median_delta")).isFalse();

        assertThatThrownBy(() -> columns.child("ms_level")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ms_level");
        assertThatThrownBy(() -> columns.child("activation")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activation");
    }

    /**
     * The {@link ColumnTable} path wins over its fallbacks, and both win over the extra names, whatever the schema
     * order; in both layouts.
     */
    @Test
    void theCanonicalPathWinsOverAFallbackAndBothWinOverTheExtraNames() {
        MessageType schema = Types.buildMessage()
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("index")
                .optional(PrimitiveTypeName.INT32).named("level")
                .optional(PrimitiveTypeName.INT32).named("MS_1000511_ms_level")
                .optional(PrimitiveTypeName.INT32).named("ms_level")
                .optional(PrimitiveTypeName.INT32).named("polarity")
                .optional(PrimitiveTypeName.INT32).named("MS_1000465_scan_polarity")
                .named("arrow_schema");
        for (ColumnResolver columns : List.of(
                ColumnResolver.split(schema, new Entry("constructed.parquet", "spectrum", "metadata"), Facet.SPECTRUM),
                ColumnResolver.packed(schema, Facet.SPECTRUM))) {
            assertThat(columns.resolve("MS:1000511")).as("path over fallback").isEqualTo("ms_level");
            assertThat(columns.resolve("MS:1000511", "level", "MS_1000511_ms_level")).as("path over extra names")
                    .isEqualTo("ms_level");
            assertThat(columns.resolve("MS:1000465", "polarity")).as("fallback over an extra name")
                    .isEqualTo("MS_1000465_scan_polarity");
        }
    }

    /**
     * A column that the column_mapping assigns to another term is not resolved under the canonical accession, so it
     * stays a leftover of its mapped term. Fails on 1e400ed, which read the column as MS:1000045 through ColumnTable
     * and counted it as consumed.
     */
    @Test
    void aColumnMappedToAnotherTermIsNotTakenForTheCanonicalAccession() {
        MessageType schema = Types.buildMessage()
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("source_index")
                .optionalGroup()
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                        .named("dissociation_method")
                    .optional(PrimitiveTypeName.DOUBLE).named("collision_energy")
                    .named("activation")
                .named("arrow_schema");
        Param supplemental = new Param("supplemental collision energy", "MS:1002680", 12.5, "UO:0000266");
        Entry entry = new Entry("constructed_precursors.parquet", "spectrum", "precursors", List.of(
                new ColumnMapping(supplemental.name(), "activation.collision_energy", "MS:1002680", "UO:0000266",
                        null)), List.of(), null);
        ColumnResolver columns = ColumnResolver.split(schema, entry, Facet.SPECTRUM_PRECURSOR);
        ColumnResolver activation = columns.child("activation");
        assertThat(activation.resolve("MS:1000045")).isNull();
        assertThat(activation.resolve("MS:1000045", "collision_energy")).as("nor as an extra name").isNull();
        assertThat(activation.resolve("MS:1002680")).isEqualTo("collision_energy");
        assertThat(activation.resolve("MS:1000044")).as("an unmapped column keeps its ColumnTable identity")
                .isEqualTo("dissociation_method");

        Group row = new SimpleGroupFactory(schema).newGroup().append("source_index", 3L);
        row.addGroup("activation").append("dissociation_method", "MS:1000133").append("collision_energy", 12.5);
        assertThat(PrecursorRowDecoder.decode(new FacetRow(Facet.SPECTRUM_PRECURSOR, row, columns)).activation()
                .parameters()).containsExactly(new Param(null, "MS:1000133", null, null), supplemental);
    }

    /**
     * A BOOLEAN term-marker column mapped to a child term (here CID) is not taken for the dissociation method
     * (MS:1000044, a CURIE column): it gives its own Param when true. Fails on 1e400ed with a ClassCastException.
     */
    @Test
    void aBooleanMarkerMappedToAChildTermIsNotReadAsTheDissociationMethod() {
        MessageType schema = Types.buildMessage()
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("source_index")
                .optionalGroup()
                    .optional(PrimitiveTypeName.BOOLEAN).named("dissociation_method")
                    .named("activation")
                .named("arrow_schema");
        Entry entry = new Entry("constructed_precursors.parquet", "spectrum", "precursors", List.of(
                new ColumnMapping("collision-induced dissociation", "activation.dissociation_method", "MS:1000133",
                        null, true)), List.of(), null);
        ColumnResolver columns = ColumnResolver.split(schema, entry, Facet.SPECTRUM_PRECURSOR);
        assertThat(columns.child("activation").resolve("MS:1000044")).isNull();
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);

        Group present = factory.newGroup().append("source_index", 3L);
        present.addGroup("activation").append("dissociation_method", true);
        Group absent = factory.newGroup().append("source_index", 3L);
        absent.addGroup("activation").append("dissociation_method", false);
        assertThat(PrecursorRowDecoder.decode(new FacetRow(Facet.SPECTRUM_PRECURSOR, present, columns)).activation()
                .parameters()).containsExactly(new Param("collision-induced dissociation", "MS:1000133", null, null));
        assertThat(PrecursorRowDecoder.decode(new FacetRow(Facet.SPECTRUM_PRECURSOR, absent, columns)).activation()
                .parameters()).isEmpty();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /** A member's manifest entry, Parquet schema and rows, read with parquet-java. */
    private record Table(Entry entry, MessageType schema, List<Group> rows) {
    }

    /** The member of {@code facet} in the current {@code fixture}, found by role. */
    private static Table current(String fixture, Facet facet) {
        try (MzPeakSource src = MzPeakSource.open(Fixtures.current(fixture))) {
            return read(src, facet.entry(MzPeakManifest.fromSource(src)).orElseThrow());
        }
    }

    private static Table read(MzPeakSource src, Entry entry) {
        InputFile input = src.inputFile(entry.name());
        MessageType schema = ParquetFooter.read(input).schema();
        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(input, rows::add);
        return new Table(entry, schema, rows);
    }

    /** {@code entry} as the 3-argument constructor gives it: no column mapping. */
    private static Entry bare(Entry entry) {
        return new Entry(entry.name(), entry.entityType(), entry.dataKind());
    }

    /** {@code entry} with the mapping at {@code path} replaced. */
    private static Entry withMapping(Entry entry, String path, UnaryOperator<ColumnMapping> change) {
        List<ColumnMapping> mapping = new ArrayList<>();
        for (ColumnMapping m : entry.columnMapping()) {
            mapping.add(path.equals(m.path()) ? change.apply(m) : m);
        }
        return new Entry(entry.name(), entry.entityType(), entry.dataKind(), mapping, entry.parameters(),
                entry.checksum());
    }

    private static List<Double> boxed(double[] values) {
        return Arrays.stream(values).boxed().toList();
    }
}
