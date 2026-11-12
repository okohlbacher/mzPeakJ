package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-9b (design §3.10): a column_mapping whose {@code unit} is a JSON array names a sibling column holding the unit
 * per row (Rust {@code PathOrCURIE}): a string CURIE (what the Rust writer writes) or a struct
 * {@code {cv_id: uint8, accession: uint32}} (what the Rust reader's {@code CURIEStructArray} reads; cv_id 1 is MS, 2
 * is UO). The unit column is consumed and gives no Param of its own.
 *
 * <p>Fixture: copies of current small.unpacked. In {@code spectra_metadata_precursors} the collision energy moves from
 * {@code activation.collision_energy} into {@code activation.opt_MS_1000045_collision_energy}, mapped with
 * {@code unit: ["activation", "opt_collision_energy_unit"]}; every other cell is the fixture's. In
 * {@code spectra_metadata} an {@code opt_MS_1000826_elution_time} column is added with the unit column
 * {@code opt_elution_time_unit}.
 */
class UnitColumnTest {

    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final String PRECURSORS = "spectra_metadata_precursors.parquet";
    private static final String SPECTRA = "spectra_metadata.parquet";
    private static final String COLLISION_ENERGY = "MS:1000045";
    private static final String ELECTRONVOLT = "UO:0000266";
    private static final String ENERGY = "opt_MS_1000045_collision_energy";
    private static final String ENERGY_UNIT = "opt_collision_energy_unit";
    private static final String ELUTION_TIME = "MS:1000826";
    private static final String TIME = "opt_MS_1000826_elution_time";
    private static final String TIME_UNIT = "opt_elution_time_unit";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Type STRING_UNIT = Types.optional(PrimitiveTypeName.BINARY)
            .as(LogicalTypeAnnotation.stringType()).named(ENERGY_UNIT);
    private static final Type STRUCT_UNIT = curieStruct(ENERGY_UNIT);

    // ---- collision energy (activation scope) ---------------------------------------------------------------------

    /**
     * Every precursor's collision energy reads with the unit of its row's string cell, UO:0000266, and the fixture's
     * value; the activation Params are the fixture's (whose mapping gives UO:0000266), the unit column giving none.
     */
    @Test
    void collisionEnergyReadsItsUnitFromAStringUnitColumn(@TempDir Path tmp) throws IOException {
        Path copy = precursorsWithUnitColumn(tmp, STRING_UNIT, (activation, k) -> activation.add(ENERGY_UNIT,
                ELECTRONVOLT));
        assertSameActivationsAsTheFixture(copy);
    }

    /** The struct form {@code {cv_id: 2, accession: 266}} reads as UO:0000266 too. */
    @Test
    void collisionEnergyReadsItsUnitFromAStructUnitColumn(@TempDir Path tmp) throws IOException {
        Path copy = precursorsWithUnitColumn(tmp, STRUCT_UNIT, (activation, k) -> activation.addGroup(ENERGY_UNIT)
                .append("cv_id", 2).append("accession", 266));
        assertSameActivationsAsTheFixture(copy);
    }

    /** A null unit cell gives the collision energy no unit. */
    @Test
    void aNullUnitCellGivesNoUnit(@TempDir Path tmp) throws IOException {
        Path copy = precursorsWithUnitColumn(tmp, STRING_UNIT, (activation, k) -> {
            if (k != 0) {
                activation.add(ENERGY_UNIT, ELECTRONVOLT);
            }
        });
        long first = ParquetGroups.optLong(MemberRewrite.read(copy.resolve(PRECURSORS)).rows.get(0), "source_index");
        try (MzPeakReader reader = MzPeakReader.open(copy)) {
            List<Param> energies = collisionEnergies(reader.getMetadata(first).orElseThrow());
            assertThat(energies).containsExactly(new Param("collision energy", COLLISION_ENERGY, 35.0, null));
        }
    }

    /** A null struct cell gives the collision energy no unit too; the other rows read UO:0000266. */
    @Test
    void aNullStructUnitCellGivesNoUnit(@TempDir Path tmp) throws IOException {
        Path copy = precursorsWithUnitColumn(tmp, STRUCT_UNIT, (activation, k) -> {
            if (k != 0) {
                activation.addGroup(ENERGY_UNIT).append("cv_id", 2).append("accession", 266);
            }
        });
        List<Group> rows = MemberRewrite.read(copy.resolve(PRECURSORS)).rows;
        long first = ParquetGroups.optLong(rows.get(0), "source_index");
        long second = ParquetGroups.optLong(rows.get(1), "source_index");
        assertThat(second).isNotEqualTo(first);
        try (MzPeakReader reader = MzPeakReader.open(copy)) {
            assertThat(collisionEnergies(reader.getMetadata(first).orElseThrow()))
                    .containsExactly(new Param("collision energy", COLLISION_ENERGY, 35.0, null));
            assertThat(collisionEnergies(reader.getMetadata(second).orElseThrow()))
                    .extracting(Param::unit).containsExactly(ELECTRONVOLT);
        }
    }

    /** A struct cell with a cv_id other than 1 (MS) or 2 (UO) is refused, as the Rust reader does. */
    @Test
    void aStructUnitWithAnotherCvIdIsRefused(@TempDir Path tmp) throws IOException {
        Path copy = precursorsWithUnitColumn(tmp, STRUCT_UNIT, (activation, k) -> activation.addGroup(ENERGY_UNIT)
                .append("cv_id", 3).append("accession", 266));
        assertRefused(copy, PRECURSORS + ": unit column activation." + ENERGY_UNIT
                + " has cv_id 3; supported are 1 (MS) and 2 (UO)");
    }

    /** A struct cell without a cv_id is refused like another cv_id. */
    @Test
    void aStructUnitWithoutACvIdIsRefused(@TempDir Path tmp) throws IOException {
        Path copy = precursorsWithUnitColumn(tmp, STRUCT_UNIT, (activation, k) -> activation.addGroup(ENERGY_UNIT)
                .append("accession", 266));
        assertRefused(copy, PRECURSORS + ": unit column activation." + ENERGY_UNIT
                + " has cv_id null; supported are 1 (MS) and 2 (UO)");
    }

    /** A struct cell without an accession is refused. */
    @Test
    void aStructUnitWithoutAnAccessionIsRefused(@TempDir Path tmp) throws IOException {
        Path copy = precursorsWithUnitColumn(tmp, STRUCT_UNIT, (activation, k) -> activation.addGroup(ENERGY_UNIT)
                .append("cv_id", 2));
        assertRefused(copy, PRECURSORS + ": unit column activation." + ENERGY_UNIT + " has no accession");
    }

    /** A unit path naming no column of the scope is refused (the Rust reader logs it and reads no unit). */
    @Test
    void aUnitPathNamingNoColumnIsRefused(@TempDir Path tmp) throws IOException {
        Path copy = precursorsWithUnitColumn(tmp, STRING_UNIT, (activation, k) -> activation.add(ENERGY_UNIT,
                ELECTRONVOLT));
        ObjectNode index = index(copy);
        mapping(index, PRECURSORS, COLLISION_ENERGY).putArray("unit").add("activation").add("opt_missing_unit");
        writeIndex(copy, index);
        assertRefused(copy, PRECURSORS + ": unit column activation.opt_missing_unit does not exist");
    }

    // ---- a spectrum-level leftover ------------------------------------------------------------------------------

    /**
     * A spectrum-level opt_ column with a unit column reads its unit per row: two rows with different units read
     * back distinct, a null unit cell gives no unit. The unit column gives no Param; every other Param is the
     * fixture's.
     */
    @Test
    void aSpectrumLeftoverReadsItsUnitPerRow(@TempDir Path tmp) throws IOException {
        Type unit = Types.optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named(TIME_UNIT);
        Path copy = spectraWithElutionTime(tmp, unit, (g, index) -> {
            if (index == 0) {
                g.append(TIME, 12.5).append(TIME_UNIT, "UO:0000010");
            } else if (index == 1) {
                g.append(TIME, 0.25).append(TIME_UNIT, "UO:0000031");
            } else if (index == 2) {
                g.add(TIME, 7.0);
            }
        });
        try (MzPeakReader original = MzPeakReader.open(SMALL); MzPeakReader edited = MzPeakReader.open(copy)) {
            assertThat(edited.size()).isEqualTo(48);
            for (long index = 0; index < edited.size(); index++) {
                List<Param> expected = new ArrayList<>(original.getMetadata(index).orElseThrow().parameters());
                if (index == 0) {
                    expected.add(new Param("elution time", ELUTION_TIME, 12.5, "UO:0000010"));
                } else if (index == 1) {
                    expected.add(new Param("elution time", ELUTION_TIME, 0.25, "UO:0000031"));
                } else if (index == 2) {
                    expected.add(new Param("elution time", ELUTION_TIME, 7.0, null));
                }
                assertThat(edited.getMetadata(index).orElseThrow().parameters()).as("spectrum %d", index)
                        .containsExactlyElementsOf(expected);
            }
        }
    }

    /**
     * The struct form at the spectrum level: cv_id 1 reads as an MS CURIE; another cv_id is refused with the row's
     * index.
     */
    @Test
    void aSpectrumLeftoverReadsAStructUnitAndRefusesAnotherCvIdWithTheRow(@TempDir Path tmp) throws IOException {
        Path copy = spectraWithElutionTime(tmp, curieStruct(TIME_UNIT), (g, index) -> {
            if (index == 0) {
                g.add(TIME, 12.5);
                g.addGroup(TIME_UNIT).append("cv_id", 1).append("accession", 40);
            }
        });
        try (MzPeakReader reader = MzPeakReader.open(copy)) {
            assertThat(reader.getMetadata(0).orElseThrow().parameters())
                    .contains(new Param("elution time", ELUTION_TIME, 12.5, "MS:0000040"))
                    .noneMatch(p -> TIME_UNIT.equals(p.name()));
        }

        Path refused = spectraWithElutionTime(tmp.resolve("refused"), curieStruct(TIME_UNIT), (g, index) -> {
            if (index == 1) {
                g.add(TIME, 0.25);
                g.addGroup(TIME_UNIT).append("cv_id", 3).append("accession", 31);
            }
        });
        assertRefused(refused, SPECTRA + ": unit column " + TIME_UNIT
                + " row 1 has cv_id 3; supported are 1 (MS) and 2 (UO)");
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** The activations of every spectrum of {@code copy} equal the fixture's; every collision energy is in eV. */
    private static void assertSameActivationsAsTheFixture(Path copy) {
        try (MzPeakReader original = MzPeakReader.open(SMALL); MzPeakReader edited = MzPeakReader.open(copy)) {
            assertThat(edited.size()).isEqualTo(original.size()).isEqualTo(48);
            int energies = 0;
            for (long index = 0; index < original.size(); index++) {
                SpectrumDescription before = original.getMetadata(index).orElseThrow();
                SpectrumDescription after = edited.getMetadata(index).orElseThrow();
                assertThat(after.precursors()).as("spectrum %d precursors", index)
                        .hasSameSizeAs(before.precursors());
                for (int p = 0; p < before.precursors().size(); p++) {
                    assertThat(after.precursors().get(p).activation().parameters())
                            .as("spectrum %d precursor %d", index, p)
                            .containsExactlyElementsOf(before.precursors().get(p).activation().parameters());
                }
                for (Param energy : collisionEnergies(after)) {
                    assertThat(energy.unit()).isEqualTo(ELECTRONVOLT);
                    assertThat(energy.value()).isInstanceOf(Double.class);
                    energies++;
                }
            }
            assertThat(energies).isEqualTo(34);
        }
    }

    /** The collision energy Params of every precursor of {@code d}. */
    private static List<Param> collisionEnergies(SpectrumDescription d) {
        List<Param> out = new ArrayList<>();
        for (Precursor p : d.precursors()) {
            for (Param param : p.activation().parameters()) {
                if (param.hasAccession(COLLISION_ENERGY)) {
                    out.add(param);
                }
            }
        }
        return out;
    }

    /** Reading the metadata of {@code dataset} throws an {@link MzPeakException} with {@code message}. */
    private static void assertRefused(Path dataset, String message) {
        assertThatThrownBy(() -> {
            try (MzPeakReader reader = MzPeakReader.open(dataset)) {
                reader.metadata();
            }
        }).isInstanceOf(MzPeakException.class).hasMessage(message);
    }

    /**
     * A copy of small.unpacked whose precursors member has the collision energy in {@link #ENERGY} and the unit
     * column {@code unit} after it in the activation struct, filled by {@code unitCell} with the activation group
     * and the row number; its mapping takes the unit from the column.
     */
    private static Path precursorsWithUnitColumn(Path tmp, Type unit, BiConsumer<Group, Integer> unitCell)
            throws IOException {
        Path copy = MemberRewrite.copy(SMALL, tmp);
        Path file = copy.resolve(PRECURSORS);
        MemberRewrite.Member m = MemberRewrite.read(file);
        GroupType activation = m.schema.getType("activation").asGroupType();
        List<Type> activationFields = new ArrayList<>();
        for (Type t : activation.getFields()) {
            if (t.getName().equals("collision_energy")) {
                PrimitiveType p = t.asPrimitiveType();
                activationFields.add(Types.primitive(p.getPrimitiveTypeName(), p.getRepetition())
                        .as(p.getLogicalTypeAnnotation()).named(ENERGY));
                activationFields.add(unit);
            } else {
                activationFields.add(t);
            }
        }
        List<Type> fields = new ArrayList<>();
        for (Type t : m.schema.getFields()) {
            fields.add(t.getName().equals("activation") ? activation.withNewFields(activationFields) : t);
        }
        MessageType schema = new MessageType(m.schema.getName(), fields);
        List<Group> rows = new ArrayList<>();
        for (int k = 0; k < m.rows.size(); k++) {
            Group row = m.rows.get(k);
            Group out = new SimpleGroup(schema);
            MemberRewrite.copyInto(row, out, "activation");
            Group from = row.getGroup("activation", 0);
            Group to = out.addGroup("activation");
            MemberRewrite.copyInto(from, to, "collision_energy");
            to.add(ENERGY, from.getFloat("collision_energy", 0));
            unitCell.accept(to, k);
            rows.add(out);
        }
        assertThat(rows).hasSize(34);
        m.schema = schema;
        m.rows.clear();
        m.rows.addAll(rows);
        MemberRewrite.write(m, file);

        ObjectNode index = index(copy);
        ObjectNode energy = mapping(index, PRECURSORS, COLLISION_ENERGY);
        assertThat(energy.path("path").asText()).isEqualTo("activation.collision_energy");
        assertThat(energy.path("unit").asText()).isEqualTo(ELECTRONVOLT);
        energy.put("path", "activation." + ENERGY);
        energy.putArray("unit").add("activation").add(ENERGY_UNIT);
        writeIndex(copy, index);
        return copy;
    }

    /**
     * A copy of small.unpacked (in a new directory under {@code tmp}) whose spectra member has the columns
     * {@link #TIME} (double) and {@code unit} appended, filled by {@code cells} with the row and its index, and
     * mapped as elution time (MS:1000826) with the unit from the column.
     */
    private static Path spectraWithElutionTime(Path tmp, Type unit, BiConsumer<Group, Long> cells)
            throws IOException {
        Path copy = MemberRewrite.copy(SMALL, Files.createDirectories(tmp));
        Path file = copy.resolve(SPECTRA);
        MemberRewrite.Member m = MemberRewrite.read(file);
        List<Type> fields = new ArrayList<>(m.schema.getFields());
        fields.add(Types.optional(PrimitiveTypeName.DOUBLE).named(TIME));
        fields.add(unit);
        MessageType schema = new MessageType(m.schema.getName(), fields);
        List<Group> rows = new ArrayList<>();
        for (Group row : m.rows) {
            rows.add(MemberRewrite.copyRow(row, schema, null, g -> {
                cells.accept(g, ParquetGroups.optLong(row, "index"));
                return g;
            }));
        }
        m.schema = schema;
        m.rows.clear();
        m.rows.addAll(rows);
        MemberRewrite.write(m, file);

        ObjectNode index = index(copy);
        ArrayNode mappings = (ArrayNode) entry(index, SPECTRA).path("column_mapping");
        ObjectNode time = mappings.addObject();
        time.put("name", "elution time");
        time.put("path", TIME);
        time.put("accession", ELUTION_TIME);
        time.putArray("unit").add(TIME_UNIT);
        writeIndex(copy, index);
        return copy;
    }

    /** A struct {@code {cv_id: uint8, accession: uint32}} unit column named {@code name}. */
    private static Type curieStruct(String name) {
        return Types.optionalGroup()
                .addField(Types.optional(PrimitiveTypeName.INT32).as(LogicalTypeAnnotation.intType(8, false))
                        .named("cv_id"))
                .addField(Types.optional(PrimitiveTypeName.INT32).as(LogicalTypeAnnotation.intType(32, false))
                        .named("accession"))
                .named(name);
    }

    private static ObjectNode index(Path dataset) throws IOException {
        return (ObjectNode) JSON.readTree(dataset.resolve("mzpeak_index.json").toFile());
    }

    private static void writeIndex(Path dataset, ObjectNode index) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(dataset.resolve("mzpeak_index.json").toFile(), index);
    }

    private static JsonNode entry(ObjectNode index, String name) {
        for (JsonNode file : index.path("files")) {
            if (file.path("name").asText().equals(name)) {
                return file;
            }
        }
        throw new AssertionError("no manifest entry " + name);
    }

    private static ObjectNode mapping(ObjectNode index, String name, String accession) {
        for (JsonNode m : entry(index, name).path("column_mapping")) {
            if (m.path("accession").asText().equals(accession)) {
                return (ObjectNode) m;
            }
        }
        throw new AssertionError("no mapping of " + accession + " in " + name);
    }
}
