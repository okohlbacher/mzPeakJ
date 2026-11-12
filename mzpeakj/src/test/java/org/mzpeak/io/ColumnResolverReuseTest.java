package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.model.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One {@link ColumnResolver} serves every row of a member: its answers do not depend on earlier calls. Resolution,
 * mappings, term markers and child scopes give the same result when asked again, whatever was asked in between; the
 * leftovers of a row depend on that row's cells, on its own type's fields and order and on the {@code consumed} set
 * of the call only. Constructed split and packed scan scopes.
 */
class ColumnResolverReuseTest {

    private static final String MEMBER = "constructed.parquet";

    private static final MessageType SCAN = MessageTypeParser.parseMessageType("message arrow_schema {\n"
            + "  optional int64 source_index (INTEGER(64,false));\n"
            + "  optional double scan_start_time;\n"
            + "  optional double opt_X_1_energy;\n"
            + "  optional binary opt_X_1_unit (STRING);\n"
            + "  optional binary opt_marker (STRING);\n"
            + "  optional boolean opt_flag;\n"
            + "  optional int32 extra_count;\n"
            + "  optional binary ion_mobility_type (STRING);\n"
            + "  optional double ion_mobility_value;\n"
            + "  optional group scan_windows (LIST) {\n"
            + "    repeated group list {\n"
            + "      optional group element {\n"
            + "        optional double scan_window_lower_limit;\n"
            + "        optional double scan_window_upper_limit;\n"
            + "        optional int64 opt_w;\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"
            + "}");

    /** The same table read with a projection that has fewer fields, in another order. */
    private static final MessageType PROJECTION = MessageTypeParser.parseMessageType("message arrow_schema {\n"
            + "  optional int64 source_index (INTEGER(64,false));\n"
            + "  optional int32 extra_count;\n"
            + "  optional double scan_start_time;\n"
            + "}");

    private static final Entry ENTRY = new Entry(MEMBER, "spectrum", "scans", List.of(
            new ColumnMapping("energy", "opt_X_1_energy", "X:1", null, null, "opt_X_1_unit"),
            new ColumnMapping("scan type", "opt_marker", "MS:1000579", null, true),
            new ColumnMapping("flag", "opt_flag", "MS:1000128", null, true)), List.of(), null);

    private static final Param START_1 = new Param("scan start time", "MS:1000016", 1.5, "UO:0000031");
    private static final Param ENERGY_0 = new Param("energy", "X:1", 30.0, "UO:0000266");
    private static final Param MARKER_0 = new Param(null, "MS:1000580", null, null);
    private static final Param FLAG = new Param("flag", "MS:1000128", null, null);
    private static final Param COUNT_0 = new Param("extra_count", null, 7L, null);
    private static final Param IM_TYPE_0 = new Param("ion_mobility_type", null, "MS:1002815", null);
    private static final Param IM_VALUE_0 = new Param("ion_mobility_value", null, 1.1, null);

    private static Group row0() {
        Group row = new SimpleGroupFactory(SCAN).newGroup()
                .append("source_index", 0L)
                .append("scan_start_time", 1.5)
                .append("opt_X_1_energy", 30.0)
                .append("opt_X_1_unit", "UO:0000266")
                .append("opt_marker", "MS:1000580")
                .append("opt_flag", true)
                .append("extra_count", 7)
                .append("ion_mobility_type", "MS:1002815")
                .append("ion_mobility_value", 1.1);
        Group list = row.addGroup("scan_windows");
        list.addGroup("list").addGroup("element").append("scan_window_lower_limit", 100.0)
                .append("scan_window_upper_limit", 900.0).append("opt_w", 5L);
        return row;
    }

    private static Group row1() {
        Group row = new SimpleGroupFactory(SCAN).newGroup()
                .append("source_index", 1L)
                .append("scan_start_time", 2.5)
                .append("opt_X_1_energy", 35.0)
                .append("opt_X_1_unit", "UO:0000010")
                .append("opt_flag", false);
        Group list = row.addGroup("scan_windows");
        list.addGroup("list").addGroup("element").append("scan_window_lower_limit", 200.0)
                .append("scan_window_upper_limit", 800.0);
        list.addGroup("list").addGroup("element").append("scan_window_lower_limit", 300.0)
                .append("scan_window_upper_limit", 700.0).append("opt_w", 6L);
        return row;
    }

    private static ColumnResolver split() {
        return ColumnResolver.split(SCAN, ENTRY, Facet.SPECTRUM_SCAN);
    }

    // ---- resolution ------------------------------------------------------------------------------------------

    /** Every lookup gives the same answer again, whatever the lookups in between asked. */
    @Test
    void lookupsGiveTheSameAnswerWhenAskedAgain() {
        ColumnResolver columns = split();
        for (int round = 0; round < 3; round++) {
            assertThat(columns.resolve("MS:1000016")).as("round %d", round).isEqualTo("scan_start_time");
            assertThat(columns.resolve("X:1")).isEqualTo("opt_X_1_energy");
            assertThat(columns.resolve("Z:9", "nope", "extra_count")).isEqualTo("extra_count");
            assertThat(columns.resolve("Z:9", "nope")).isNull();
            assertThat(columns.resolve("Z:9")).isNull();
            assertThat(columns.resolve("Z:9", "extra_count")).isEqualTo("extra_count");
            assertThat(columns.resolve("Z:9", null, "extra_count")).isEqualTo("extra_count");
            assertThat(columns.resolve("Z:9", "opt_marker")).as("mapped to another term").isNull();
            assertThat(columns.resolve("MS:1000579", "extra_count")).isEqualTo("opt_marker");
            assertThat(columns.resolve("Y:2", "opt_marker", "extra_count")).isEqualTo("extra_count");
            assertThatThrownBy(() -> columns.resolve(null)).isInstanceOf(NullPointerException.class)
                    .hasMessage("accession");

            assertThat(columns.resolveName("nope", "ion_mobility_value")).isEqualTo("ion_mobility_value");
            assertThat(columns.resolveName("ion_mobility_type", "ion_mobility_value")).isEqualTo("ion_mobility_type");
            assertThat(columns.resolveName("nope")).isNull();
            assertThat(columns.resolveName(null, "extra_count")).isEqualTo("extra_count");
            assertThat(columns.resolveName()).isNull();

            assertThat(columns.mapping("opt_marker"))
                    .contains(new ColumnMapping("scan type", "opt_marker", "MS:1000579", null, true));
            assertThat(columns.mapping("scan_start_time")).get()
                    .extracting(ColumnMapping::name, ColumnMapping::path, ColumnMapping::accession,
                            ColumnMapping::unit)
                    .containsExactly("scan start time", "scan_start_time", "MS:1000016", "UO:0000031");
            assertThat(columns.mapping("extra_count")).isEmpty();
            assertThat(columns.mapping(null)).isEqualTo(Optional.empty());
            assertThat(columns.termMarker("opt_marker")).isTrue();
            assertThat(columns.termMarker("opt_flag")).isTrue();
            assertThat(columns.termMarker("scan_start_time")).isFalse();
            assertThat(columns.termMarker("extra_count")).isFalse();
            assertThat(columns.termMarker(null)).isFalse();
        }
    }

    /** A caller that reuses its array of names gets the answer for the names the array holds at the call. */
    @Test
    void aReusedNamesArrayIsReadAnew() {
        ColumnResolver columns = split();
        String[] names = {"nope", "extra_count"};
        assertThat(columns.resolveName(names)).isEqualTo("extra_count");
        assertThat(columns.resolve("Z:9", names)).isEqualTo("extra_count");
        names[1] = "scan_start_time";
        assertThat(columns.resolveName(names)).isEqualTo("scan_start_time");
        assertThat(columns.resolve("Z:9", names)).isEqualTo("scan_start_time");
        names[1] = "missing";
        assertThat(columns.resolveName(names)).isNull();
        assertThat(columns.resolve("Z:9", names)).isNull();
    }

    /** Child scopes resolve alike when asked again; a field that is no scope throws every time. */
    @Test
    void childScopesResolveAlikeAndBadFieldsThrowEveryTime() {
        ColumnResolver columns = split();
        for (int round = 0; round < 3; round++) {
            ColumnResolver windows = columns.child("scan_windows");
            assertThat(windows.resolve("MS:1000501")).as("round %d", round).isEqualTo("scan_window_lower_limit");
            assertThat(windows.path("opt_w")).isEqualTo("scan_windows.opt_w");
            assertThat(windows.member()).isEqualTo(MEMBER);
            assertThatThrownBy(() -> columns.child("extra_count")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(MEMBER + ": extra_count is not a struct or a list of structs in arrow_schema");
            assertThatThrownBy(() -> columns.child("absent")).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(MEMBER + ": absent is not a struct or a list of structs in arrow_schema");
        }
    }

    // ---- leftovers -------------------------------------------------------------------------------------------

    /**
     * The leftovers of one resolver follow each call's row and consumed set: the unit column is consumed, a FALSE
     * marker and null cells give nothing, and the per-row unit is the row's own.
     */
    @Test
    void leftoversFollowEachCallsRowAndConsumedSet() {
        ColumnResolver columns = split();
        Group row0 = row0();
        Group row1 = row1();
        List<Param> all0 = List.of(START_1, ENERGY_0, MARKER_0, FLAG, COUNT_0, IM_TYPE_0, IM_VALUE_0);
        for (int round = 0; round < 2; round++) {
            assertThat(columns.leftovers(row0, Set.of())).as("round %d", round).containsExactlyElementsOf(all0);
            assertThat(columns.leftovers(row0, Set.of("scan_start_time", "ion_mobility_type",
                    "ion_mobility_value"))).containsExactly(ENERGY_0, MARKER_0, FLAG, COUNT_0);
            assertThat(columns.leftovers(row1, Set.of())).containsExactly(
                    new Param("scan start time", "MS:1000016", 2.5, "UO:0000031"),
                    new Param("energy", "X:1", 35.0, "UO:0000010"));
            assertThat(columns.leftovers(row1, Set.of("opt_X_1_energy"))).containsExactly(
                    new Param("scan start time", "MS:1000016", 2.5, "UO:0000031"));
            assertThat(columns.leftoversInBothLayouts(row0, Set.of("opt_marker", "opt_flag")))
                    .containsExactly(START_1, ENERGY_0, COUNT_0, IM_TYPE_0, IM_VALUE_0);
            assertThat(columns.leftovers(row0, Set.of())).containsExactlyElementsOf(all0);
        }
    }

    /** A row of another type (a projection) gives the leftovers of its own fields in its own order. */
    @Test
    void leftoversFollowTheRowsOwnType() {
        ColumnResolver columns = split();
        Group projected = new SimpleGroupFactory(PROJECTION).newGroup()
                .append("source_index", 0L).append("extra_count", 7).append("scan_start_time", 1.5);
        for (int round = 0; round < 2; round++) {
            assertThat(columns.leftovers(row0(), Set.of("ion_mobility_type", "ion_mobility_value")))
                    .as("round %d", round).containsExactly(START_1, ENERGY_0, MARKER_0, FLAG, COUNT_0);
            assertThat(columns.leftovers(projected, Set.of())).containsExactly(COUNT_0, START_1);
            assertThat(columns.leftovers(projected, Set.of("extra_count"))).containsExactly(START_1);
        }
    }

    /** The element rows of different rows share their scope and give their own cells and leftovers. */
    @Test
    void listElementsOfDifferentRowsReadTheirOwnCells() {
        ColumnResolver columns = split();
        Set<String> limits = Set.of("scan_window_lower_limit", "scan_window_upper_limit");
        for (int round = 0; round < 2; round++) {
            List<FacetRow> w0 = new FacetRow(Facet.SPECTRUM_SCAN, row0(), columns).children("scan_windows");
            List<FacetRow> w1 = new FacetRow(Facet.SPECTRUM_SCAN, row1(), columns).children("scan_windows");
            assertThat(w0).extracting(w -> w.columns().doubleValue(w.group(), w.columns().resolve("MS:1000500")))
                    .as("round %d", round).containsExactly(900.0);
            assertThat(w1).extracting(w -> w.columns().doubleValue(w.group(), w.columns().resolve("MS:1000501")))
                    .containsExactly(200.0, 300.0);
            assertThat(w0.get(0).columns().leftovers(w0.get(0).group(), limits))
                    .containsExactly(new Param("opt_w", null, 5L, null));
            assertThat(w1.get(0).columns().leftovers(w1.get(0).group(), limits)).isEmpty();
            assertThat(w1.get(1).columns().leftovers(w1.get(1).group(), Set.of("scan_window_upper_limit")))
                    .containsExactly(new Param("scan window lower limit", "MS:1000501", 300.0, "MS:1000040"),
                            new Param("opt_w", null, 6L, null));
        }
    }

    /** A packed scope has no split leftovers; in both layouts it maps through ColumnTable, per call. */
    @Test
    void packedLeftoversFollowEachCall() {
        ColumnResolver packed = ColumnResolver.packed(SCAN, Facet.SPECTRUM_SCAN, MEMBER);
        Group row0 = row0();
        for (int round = 0; round < 2; round++) {
            assertThat(packed.leftovers(row0, Set.of())).as("round %d", round).isEmpty();
            assertThat(packed.leftoversInBothLayouts(row0, Set.of("ion_mobility_type", "ion_mobility_value")))
                    .containsExactly(START_1,
                            new Param("opt_X_1_energy", null, 30.0, null),
                            new Param("opt_X_1_unit", null, "UO:0000266", null),
                            new Param("opt_marker", null, "MS:1000580", null),
                            new Param("opt_flag", null, true, null),
                            COUNT_0);
            assertThat(packed.leftoversInBothLayouts(row0, Set.of("scan_start_time", "opt_X_1_energy",
                    "opt_X_1_unit", "opt_marker", "opt_flag", "extra_count")))
                    .containsExactly(IM_TYPE_0, IM_VALUE_0);
        }
    }

    // ---- typed ion mobility ----------------------------------------------------------------------------------

    /** The typed ion mobility of each row is its own, and a failing row leaves the next rows unaffected. */
    @Test
    void typedIonMobilityFollowsEachRow() {
        ColumnResolver columns = split();
        Group noType = new SimpleGroupFactory(SCAN).newGroup().append("source_index", 2L)
                .append("ion_mobility_value", 0.9);
        Param typed = new Param("inverse reduced ion mobility drift time", "MS:1002815", 1.1, "MS:1002814");
        List<Param> none = List.of();
        for (int round = 0; round < 2; round++) {
            assertThat(IonMobilityParams.withTypedIonMobility(new FacetRow(Facet.SPECTRUM_SCAN, row0(), columns),
                    none)).as("round %d", round).containsExactly(typed);
            assertThat(IonMobilityParams.withTypedIonMobility(new FacetRow(Facet.SPECTRUM_SCAN, row1(), columns),
                    none)).isSameAs(none);
            assertThatThrownBy(() -> IonMobilityParams.withTypedIonMobility(
                    new FacetRow(Facet.SPECTRUM_SCAN, noType, columns), none))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(MEMBER + ": SPECTRUM_SCAN row with source_index 2 has ion_mobility_value 0.9 but no "
                            + "ion_mobility_type");
        }
    }
}
