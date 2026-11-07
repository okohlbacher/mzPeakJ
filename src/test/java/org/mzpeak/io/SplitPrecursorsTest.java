package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.GoldenMeta.Row;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Activation;
import org.mzpeak.model.CvTerms;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RD-7: precursors and selected ions of the spectrum metadata, in both layouts.
 *
 * <ul>
 *   <li>Activation Params come, in this order, from the dissociation_method term marker (MS:1000044) as
 *       {@code Param(name, cell)}, named from {@link CvTerms#DISSOCIATION_METHODS} since RD-11, the
 *       collision_energy column (MS:1000045) as 'collision energy' with the mapping's unit, the activation-scope
 *       leftovers, and the activation {@code parameters} list, whose entries yield to a column with the same
 *       accession.
 *   <li>Selected ions carry their typed ion mobility (both layouts) and their leftovers (split).
 *   <li>Precursor-scope and isolation-window leftovers have no place in the model: they are logged at DEBUG and
 *       dropped.
 *   <li>A selected ion joins the first precursor of its spectrum with an equal {@code precursor_index}, null equal
 *       to null, and falls back to the spectrum's first precursor.
 * </ul>
 *
 * Oracles: legacy small (HUPO-PSI/mzPeak 29e59b2), the FX-2 cells of the 8 current fixtures, and rows constructed
 * from current small.unpacked (split) and legacy small.unpacked (packed).
 */
class SplitPrecursorsTest {

    private static final String DIA_REF = "diaPASEF.ref.mzpeak";
    private static final String SMALL_UNPACKED = "small.unpacked.mzpeak";

    private static final String CID = "MS:1000133";
    /** The dissociation_method term-marker Param of a CID cell. */
    private static final Param CID_MARKER = new Param("collision-induced dissociation", CID, null, null);
    private static final String COLLISION_ENERGY = "MS:1000045";
    private static final String ELECTRONVOLT = "UO:0000266";
    private static final String DEFAULT_ION_MOBILITY_TYPE = "MS:1002476";

    static Stream<String> smallFixtures() {
        return Stream.of("small.mzpeak", "small.chunked.mzpeak", "small.numpress.mzpeak", SMALL_UNPACKED);
    }

    // ---- (a), (b) current small against legacy ------------------------------------------------------------------

    /**
     * The 34 MS2 spectra of current small have one precursor with one selected ion, equal to legacy. The current
     * activation Params come from the term-marker and collision-energy columns; the marker is named from
     * {@link CvTerms#DISSOCIATION_METHODS} (RD-11), so they equal the legacy parameters list.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void currentSmallPrecursorsEqualLegacyAndCarryTheirActivationColumns(String fixture) {
        try (MzPeakReader current = MzPeakReader.open(Fixtures.current(fixture));
             MzPeakReader legacy = MzPeakReader.open(Fixtures.legacy(fixture))) {
            assertThat(current.size()).isEqualTo(48);
            assertThat(legacy.size()).isEqualTo(48);
            int msn = 0;
            for (int i = 0; i < 48; i++) {
                SpectrumDescription c = current.metadataAt(i);
                SpectrumDescription l = legacy.metadataAt(i);
                String where = fixture + " spectrum " + c.index();
                assertThat(c.index()).isEqualTo(l.index());
                assertThat(c.precursors()).as(where).hasSameSizeAs(l.precursors());
                if (l.precursors().isEmpty()) {
                    continue;
                }
                msn++;
                assertThat(c.precursors()).as(where).hasSize(1);
                Precursor cp = c.precursors().get(0);
                Precursor lp = l.precursors().get(0);
                assertThat(cp.precursorIndex()).as(where + " precursor index").isEqualTo(lp.precursorIndex());
                assertThat(cp.precursorId()).as(where + " precursor id").isEqualTo(lp.precursorId());
                assertThat(cp.isolationWindow()).as(where + " isolation window").isEqualTo(lp.isolationWindow());

                assertThat(cp.selectedIons()).as(where + " selected ions").hasSize(1);
                assertThat(lp.selectedIons()).as(where + " legacy selected ions").hasSize(1);
                SelectedIon ci = cp.primaryIon();
                SelectedIon li = lp.primaryIon();
                assertThat(ci.mz()).as(where + " ion m/z").isEqualTo(li.mz());
                assertThat(ci.charge()).as(where + " ion charge").isEqualTo(li.charge());
                assertThat(ci.intensity()).as(where + " ion intensity").isEqualTo(li.intensity());
                assertThat(ci.parameters()).as(where + " ion params").isEmpty();

                Activation a = cp.activation();
                assertThat(a.parameters()).as(where + " activation").containsExactly(
                        CID_MARKER,
                        new Param("collision energy", COLLISION_ENERGY, 35.0, ELECTRONVOLT))
                        .isEqualTo(lp.activation().parameters());
                assertThat(a.methods()).extracting(Param::accession).as(where + " methods")
                        .containsExactly(CID)
                        .isEqualTo(lp.activation().methods().stream().map(Param::accession).toList());
                assertThat(a.collisionEnergy()).as(where + " collision energy")
                        .hasValue(35.0).isEqualTo(lp.activation().collisionEnergy());
                assertNoFalseBoolean(where, cp);
            }
            assertThat(msn).isEqualTo(34);
        }
    }

    // ---- (c) diaPASEF.ref ----------------------------------------------------------------------------------------

    @Test
    void diaPasefRefSpectrum1HasItsPrecursorActivationAndIonMobility() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(DIA_REF))) {
            Precursor p = only(reader.getMetadata(1).orElseThrow().precursors());
            assertThat(p.precursorIndex()).isEqualTo(0L);
            assertThat(p.precursorId()).isEqualTo("merged=0 frame=1 startScan=1 endScan=927");
            assertThat(p.isolationWindow()).isEqualTo(new IsolationWindow(813.0, 13.0, 13.0));
            assertThat(p.activation().parameters()).containsExactly(
                    CID_MARKER,
                    new Param("collision energy", COLLISION_ENERGY, 41.572227478027344, ELECTRONVOLT));
            assertThat(p.activation().collisionEnergy()).hasValue(41.572227478027344);

            SelectedIon ion = only(p.selectedIons());
            assertThat(ion.mz()).isEqualTo(813.0);
            assertThat(ion.charge()).isNull();
            assertThat(ion.intensity()).isEqualTo(0.0);
            assertThat(ion.parameters()).containsExactly(
                    new Param("ion mobility value", "MS:1002815", 1.2517460582524271, null));

            for (long index = 1; index <= 8; index++) {
                SelectedIon each = only(only(reader.getMetadata(index).orElseThrow().precursors()).selectedIons());
                assertThat(each.parameters()).as("spectrum %d ion", index)
                        .singleElement().satisfies(q -> assertThat(q.accession()).isEqualTo("MS:1002815"));
            }
        }
    }

    // ---- (d), (e) the join, on constructed rows ------------------------------------------------------------------

    /**
     * Two precursors of spectrum 5 (its own, precursor_index 1, then spectrum 9's, precursor_index 8) and their
     * ions in the opposite order: each ion lands on the precursor with its precursor_index.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"split", "packed"})
    void twoPrecursorsWithDistinctPrecursorIndexKeepTheirOwnIon(String layout) {
        Rows rows = Rows.load(layout);
        Precursor p5 = rows.precursorOf(5);
        Precursor p9 = rows.precursorOf(9);
        assertThat(p5.precursorIndex()).isEqualTo(1L);
        assertThat(p9.precursorIndex()).isEqualTo(8L);

        List<Precursor> joined = rows.assemble(5,
                List.of(rows.precursor(5, 1L), rows.precursor(9, 8L)),
                List.of(rows.ion(9, 8L), rows.ion(5, 1L)));

        assertThat(joined).containsExactly(p5, p9);
    }

    /** An ion whose precursor_index no precursor of its spectrum has goes to the first one (Rust: the last). */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"split", "packed"})
    void anUnmatchedIonGoesToTheFirstPrecursor(String layout) {
        Rows rows = Rows.load(layout);
        Precursor p5 = rows.precursorOf(5);
        Precursor p9 = rows.precursorOf(9);

        List<Precursor> joined = rows.assemble(5,
                List.of(rows.precursor(5, 1L), rows.precursor(9, 8L)),
                List.of(rows.ion(9, 99L)));

        assertThat(joined).containsExactly(withIons(p5, p9.selectedIons()), withIons(p9, List.of()));
    }

    /** A null precursor_index joins a null one, not the first precursor, and the precursor keeps it null. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"split", "packed"})
    void aNullPrecursorIndexJoinsANullOne(String layout) {
        Rows rows = Rows.load(layout);
        Precursor p5 = rows.precursorOf(5);
        Precursor p9 = rows.precursorOf(9);

        List<Precursor> joined = rows.assemble(5,
                List.of(rows.precursor(5, 1L), rows.precursor(9, null)),
                List.of(rows.ion(9, null)));

        assertThat(joined).hasSize(2);
        assertThat(joined.get(0)).isEqualTo(withIons(p5, List.of()));
        assertThat(joined.get(1).precursorIndex()).isNull();
        assertThat(joined.get(1)).isEqualTo(new Precursor(null, p9.precursorId(), p9.isolationWindow(),
                p9.selectedIons(), p9.activation()));

        List<Precursor> alone = rows.assemble(5, List.of(rows.precursor(5, null)), List.of(rows.ion(5, null)));
        assertThat(alone).containsExactly(new Precursor(null, p5.precursorId(), p5.isolationWindow(),
                p5.selectedIons(), p5.activation()));
    }

    /** A packed selected ion with a typed ion mobility cell now carries it as a Param, as a split one does. */
    @Test
    void aPackedSelectedIonCarriesItsTypedIonMobility() {
        Rows rows = Rows.load("packed");
        SelectedIon ion5 = rows.precursorOf(5).primaryIon();
        assertThat(ion5.parameters()).isEmpty();

        FacetRow typed = rows.ion(5, 1L);
        typed.group().add("ion_mobility_value", 1.25);
        typed.group().add("ion_mobility_type", "MS:1002815");
        FacetRow untyped = rows.ion(5, 1L);
        untyped.group().add("ion_mobility_value", 0.75);

        assertThat(SelectedIonRowDecoder.decode(typed)).isEqualTo(new SelectedIon(ion5.mz(), ion5.charge(),
                ion5.intensity(), List.of(new Param("ion mobility value", "MS:1002815", 1.25, null))));
        assertThat(SelectedIonRowDecoder.decode(untyped).parameters()).containsExactly(
                new Param("ion mobility value", DEFAULT_ION_MOBILITY_TYPE, 0.75, null));
    }

    // ---- leftovers and the column-over-list rule, on constructed split rows --------------------------------------

    /**
     * Extra activation columns follow the marker and the energy in schema order: a true boolean marker as a bare
     * Param, a mapped value with its mapping's unit, an unmapped cell under its field name. The parameters list
     * comes last, without the entries whose accession a column gave.
     */
    @Test
    void activationLeftoversFollowTheColumnsAndAColumnWinsOverTheList() {
        ExtendedPrecursors ext = ExtendedPrecursors.load();
        FacetRow row = ext.precursor(5);
        Group activation = row.group().getGroup("activation", 0);
        activation.add("supplemental_cid", true);
        activation.add("supplemental_collision_energy", 12.5f);
        activation.add("opt_activation_note", "pulsed");
        addParam(activation, "collision energy", COLLISION_ENERGY, 99.0, ELECTRONVOLT);
        addParam(activation, "collision-induced dissociation", CID, null, null);
        addParam(activation, "supplemental collision energy", "MS:1002680", 1.0, ELECTRONVOLT);
        addParam(activation, "activation energy", "MS:1000509", 2.0, ELECTRONVOLT);
        addParam(activation, "free text", null, "kept", null);

        assertThat(PrecursorRowDecoder.decode(row).activation().parameters()).containsExactly(
                CID_MARKER,
                new Param("collision energy", COLLISION_ENERGY, 35.0, ELECTRONVOLT),
                new Param("supplemental collision-induced dissociation", "MS:1002679", null, null),
                new Param("supplemental collision energy", "MS:1002680", 12.5, ELECTRONVOLT),
                new Param("opt_activation_note", null, "pulsed", null),
                new Param("activation energy", "MS:1000509", 2.0, ELECTRONVOLT),
                new Param("free text", null, "kept", null));
    }

    /** A false boolean marker gives no Param, and a list entry is kept when its column is null. */
    @Test
    void aFalseMarkerGivesNothingAndANullColumnLeavesTheListEntry() {
        ExtendedPrecursors ext = ExtendedPrecursors.load();
        FacetRow row = ext.precursor(5, "collision_energy", "dissociation_method");
        Group activation = row.group().getGroup("activation", 0);
        activation.add("supplemental_cid", false);
        addParam(activation, "collision energy", COLLISION_ENERGY, 99.0, ELECTRONVOLT);

        List<Param> params = PrecursorRowDecoder.decode(row).activation().parameters();
        assertThat(params).containsExactly(new Param("collision energy", COLLISION_ENERGY, 99.0, ELECTRONVOLT));
        assertThat(PrecursorRowDecoder.decode(row).activation().collisionEnergy()).hasValue(99.0);
    }

    /**
     * RD-11: the term-marker Param takes its name from {@link CvTerms#DISSOCIATION_METHODS}. A CURIE the table does
     * not list, such as EThcD (MS:1002631, not a descendant of MS:1000044), keeps a null name and is no method.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "MS:1000598, electron transfer dissociation",
            "MS:1003246, ultraviolet photodissociation",
            "MS:1002631,",
            "XX:0000001,"
    })
    void theDissociationMethodMarkerIsNamedFromTheTable(String curie, String name) {
        ExtendedPrecursors ext = ExtendedPrecursors.load();
        FacetRow row = ext.precursor(5, "dissociation_method");
        row.group().getGroup("activation", 0).add("dissociation_method", curie);

        Activation a = PrecursorRowDecoder.decode(row).activation();
        Param marker = new Param(name, curie, null, null);
        assertThat(a.parameters()).containsExactly(marker,
                new Param("collision energy", COLLISION_ENERGY, 35.0, ELECTRONVOLT));
        assertThat(a.methods()).isEqualTo(name == null ? List.of() : List.of(marker));
    }

    /**
     * Precursor-scope and isolation-window leftovers have no place in the model: they leave the decoded precursor
     * as it was and are logged at DEBUG.
     */
    @Test
    void precursorAndIsolationWindowLeftoversAreLoggedAtDebugAndDropped() {
        ExtendedPrecursors ext = ExtendedPrecursors.load();
        FacetRow plain = ext.precursor(5);
        FacetRow row = ext.precursor(5);
        row.group().add("opt_precursor_note", "from the instrument");
        row.group().getGroup("isolation_window", 0).add("opt_window_width_ratio", 0.5);

        List<LogRecord> records = new ArrayList<>();
        Logger log = Logger.getLogger(PrecursorRowDecoder.class.getName());
        Level level = log.getLevel();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord r) {
                records.add(r);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        log.setLevel(Level.FINE);
        log.addHandler(capture);
        PrecursorRowDecoder.PrecursorPart decoded;
        PrecursorRowDecoder.PrecursorPart unextended;
        try {
            unextended = PrecursorRowDecoder.decode(plain);
            assertThat(records).isEmpty();
            decoded = PrecursorRowDecoder.decode(row);
        } finally {
            log.removeHandler(capture);
            log.setLevel(level);
        }

        assertThat(decoded).isEqualTo(unextended);
        assertThat(records).hasSize(2).allSatisfy(r -> assertThat(r.getLevel()).isEqualTo(Level.FINE));
        assertThat(records).extracting(LogRecord::getMessage)
                .anySatisfy(m -> assertThat(m).contains("spectra_metadata_precursors.parquet", "source_index 5",
                        "isolation_window", "opt_window_width_ratio", "0.5").doesNotContain("opt_precursor_note"))
                .anySatisfy(m -> assertThat(m).contains("spectra_metadata_precursors.parquet", "source_index 5",
                        "opt_precursor_note", "from the instrument").doesNotContain("opt_window_width_ratio"));
    }

    /**
     * A split selected ion: its parameters list, then its typed ion mobility, then its leftovers; the typed and
     * consumed columns never reappear as leftovers.
     */
    @Test
    void aSplitSelectedIonCarriesItsParamsTypedIonMobilityAndLeftovers() {
        Rows rows = Rows.load("split");
        SelectedIon ion5 = rows.precursorOf(5).primaryIon();
        GroupType schema = rows.ions.get(0).group().getType();
        List<Type> fields = new ArrayList<>(schema.getFields());
        fields.add(Types.optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                .named("opt_ion_note"));
        MessageType extended = new MessageType(schema.getName(), fields);
        ColumnResolver columns = ColumnResolver.split(extended, rows.ionEntry, Facet.SPECTRUM_SELECTED_ION);

        Group g = new SimpleGroup(extended);
        copy(rows.ionRow(5).group(), g, Set.of("ion_mobility_value", "ion_mobility_type"));
        g.add("ion_mobility_value", 0.9);
        g.add("opt_ion_note", "monoisotopic");
        addParam(g, "user note", null, "first", null);

        assertThat(SelectedIonRowDecoder.decode(new FacetRow(Facet.SPECTRUM_SELECTED_ION, g, columns)))
                .isEqualTo(new SelectedIon(ion5.mz(), ion5.charge(), ion5.intensity(), List.of(
                        new Param("user note", null, "first", null),
                        new Param("ion mobility value", DEFAULT_ION_MOBILITY_TYPE, 0.9, null),
                        new Param("opt_ion_note", null, "monoisotopic", null))));
    }

    // ---- (g) every activation and selected-ion cell of the 8 fixtures --------------------------------------------

    /**
     * Every non-null dissociation_method and collision_energy cell, every activation and selected-ion parameters
     * entry and every typed selected-ion ion mobility appears in the decoded Params with an equal value. Facet rows
     * are matched to spectra by source_index; the counts pin that the check is not vacuous.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "small.mzpeak,                 34, 34, 0",
            "small.chunked.mzpeak,         34, 34, 0",
            "small.numpress.mzpeak,        34, 34, 0",
            "small.unpacked.mzpeak,        34, 34, 0",
            "has_uv.mzpeak,                 0,  0, 0",
            "diaPASEF.ref.mzpeak,           8,  8, 8",
            "diaPASEF.grid.mzpeak,          8,  8, 8",
            "Example_Processed.img.mzpeak,  0,  0, 0"
    })
    void everyActivationAndSelectedIonCellAppearsInTheDecodedParams(String fixture, int methods, int energies,
                                                                       int ionMobilities) {
        GoldenMeta meta = GoldenMeta.load(fixture);
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            MzPeakManifest manifest = reader.manifest();
            Entry precursors = Facet.SPECTRUM_PRECURSOR.entry(manifest).orElse(null);
            Entry ions = Facet.SPECTRUM_SELECTED_ION.entry(manifest).orElse(null);
            Map<Long, List<Row>> precursorRows = bySourceIndex(precursors == null ? List.of()
                    : meta.rows(precursors.name()));
            Map<Long, List<Row>> ionRows = bySourceIndex(ions == null ? List.of() : meta.rows(ions.name()));

            int[] seen = new int[5];   // methods, energies, activation params, ion params, ion mobilities
            int precursorsSeen = 0;
            int ionsSeen = 0;
            for (SpectrumDescription d : reader.metadata()) {
                String where = fixture + " spectrum " + d.index();
                List<Row> pCells = precursorRows.getOrDefault(d.index(), List.of());
                if (!pCells.isEmpty()) {
                    assertThat(d.precursors()).as(where + " precursors").hasSameSizeAs(pCells);
                }
                for (int k = 0; k < pCells.size(); k++) {
                    Row cells = pCells.get(k);
                    Precursor p = d.precursors().get(k);
                    List<Param> act = p.activation().parameters();
                    Object method = cells.get(path(precursors, "MS:1000044"));
                    if (method != null) {
                        assertThat(act).as(where + " dissociation method").contains(new Param(
                                CvTerms.DISSOCIATION_METHODS.get((String) method), (String) method, null, null));
                        seen[0]++;
                    }
                    Object energy = cells.get(path(precursors, COLLISION_ENERGY));
                    if (energy != null) {
                        assertThat(act).as(where + " collision energy").contains(new Param("collision energy",
                                COLLISION_ENERGY, energy, precursors.columnForAccession(COLLISION_ENERGY)
                                .orElseThrow().unit()));
                        seen[1]++;
                    }
                    for (Object entry : cells.list("activation.parameters")) {
                        assertThat(act).as(where + " activation parameter").contains(param(entry));
                        seen[2]++;
                    }
                    assertNoFalseBoolean(where, p);
                    precursorsSeen++;
                }

                List<Row> iCells = ionRows.getOrDefault(d.index(), List.of());
                List<SelectedIon> decoded = d.precursors().stream().flatMap(p -> p.selectedIons().stream()).toList();
                assertThat(decoded).as(where + " selected ions").hasSameSizeAs(iCells);
                for (int k = 0; k < iCells.size(); k++) {
                    Row cells = iCells.get(k);
                    List<Param> params = decoded.get(k).parameters();
                    for (Object entry : cells.list("parameters")) {
                        assertThat(params).as(where + " selected-ion parameter").contains(param(entry));
                        seen[3]++;
                    }
                    Object mobility = cells.get("ion_mobility_value");
                    if (mobility != null) {
                        Object type = cells.get("ion_mobility_type");
                        String accession = type == null ? DEFAULT_ION_MOBILITY_TYPE : (String) type;
                        assertThat(params).as(where + " selected-ion ion mobility")
                                .anyMatch(q -> q.hasAccession(accession) && mobility.equals(q.value()));
                        seen[4]++;
                    }
                    ionsSeen++;
                }
            }
            assertThat(precursorsSeen).as("precursor rows").isEqualTo(precursorRows.values().stream()
                    .mapToInt(List::size).sum());
            assertThat(ionsSeen).as("selected-ion rows").isEqualTo(ionRows.values().stream()
                    .mapToInt(List::size).sum());
            assertThat(seen[0]).as("dissociation_method cells").isEqualTo(methods);
            assertThat(seen[1]).as("collision_energy cells").isEqualTo(energies);
            assertThat(seen[4]).as("selected-ion ion mobility cells").isEqualTo(ionMobilities);
        }
    }

    // ---- constructed rows ----------------------------------------------------------------------------------------

    /**
     * The spectrum, precursor and selected-ion rows of current small.unpacked (split) or legacy small.unpacked
     * (packed), as {@link MetadataTable} reads them, and the precursors the whole dataset decodes to.
     */
    private static final class Rows {
        final List<FacetRow> spectra = new ArrayList<>();
        final List<FacetRow> precursors = new ArrayList<>();
        final List<FacetRow> ions = new ArrayList<>();
        Entry ionEntry;
        List<SpectrumDescription> decoded;

        static Rows load(String layout) {
            boolean split = layout.equals("split");
            Path dataset = split ? Fixtures.current(SMALL_UNPACKED) : Fixtures.legacy(SMALL_UNPACKED);
            Rows rows = new Rows();
            try (MzPeakSource source = MzPeakSource.open(dataset)) {
                MzPeakManifest manifest = MzPeakManifest.fromSource(source);
                Entry metadata = Facet.SPECTRUM.entry(manifest).orElseThrow();
                Entry precursors = split ? Facet.SPECTRUM_PRECURSOR.entry(manifest).orElseThrow() : metadata;
                rows.ionEntry = split ? Facet.SPECTRUM_SELECTED_ION.entry(manifest).orElseThrow() : metadata;
                MetadataTable.forEachRow(source, metadata, Facet.SPECTRUM, rows.spectra::add);
                MetadataTable.forEachRow(source, precursors, Facet.SPECTRUM_PRECURSOR, rows.precursors::add);
                MetadataTable.forEachRow(source, rows.ionEntry, Facet.SPECTRUM_SELECTED_ION, rows.ions::add);
                rows.decoded = SpectrumMetadataDecoder.decode(source, manifest).descriptions();
            }
            assertThat(rows.spectra).hasSize(48);
            assertThat(rows.precursors).hasSize(34);
            assertThat(rows.ions).hasSize(34);
            return rows;
        }

        /** The one precursor the dataset decodes for spectrum {@code index}. */
        Precursor precursorOf(long index) {
            SpectrumDescription d = decoded.get((int) index);
            assertThat(d.index()).isEqualTo(index);
            return only(d.precursors());
        }

        /** A copy of the precursor row of spectrum {@code of}, keyed to spectrum 5 with {@code precursorIndex}. */
        FacetRow precursor(long of, Long precursorIndex) {
            return rekeyed(find(precursors, of), precursorIndex);
        }

        /** A copy of the selected-ion row of spectrum {@code of}, keyed to spectrum 5 with {@code precursorIndex}. */
        FacetRow ion(long of, Long precursorIndex) {
            return rekeyed(find(ions, of), precursorIndex);
        }

        FacetRow ionRow(long of) {
            return find(ions, of);
        }

        /** The precursors that spectrum {@code index} gets from its row and the given facet rows. */
        List<Precursor> assemble(long index, List<FacetRow> precursorRows, List<FacetRow> ionRows) {
            SpectrumFacetAssembler assembler = new SpectrumFacetAssembler();
            FacetRow spectrum = spectra.stream().filter(r -> r.key() == index).findFirst().orElseThrow();
            assembler.accept(spectrum.key(), spectrum);
            for (FacetRow r : precursorRows) {
                assembler.accept(r.key(), r);
            }
            for (FacetRow r : ionRows) {
                assembler.accept(r.key(), r);
            }
            List<SpectrumDescription> out = assembler.build().descriptions();
            assertThat(out).hasSize(1);
            return out.get(0).precursors();
        }

        private static FacetRow find(List<FacetRow> rows, long sourceIndex) {
            return rows.stream().filter(r -> r.key() == sourceIndex).findFirst().orElseThrow();
        }

        private static FacetRow rekeyed(FacetRow row, Long precursorIndex) {
            Group copy = new SimpleGroup(row.group().getType());
            copy(row.group(), copy, Set.of("source_index", "precursor_index"));
            copy.add("source_index", 5L);
            if (precursorIndex != null) {
                copy.add("precursor_index", precursorIndex);
            }
            return new FacetRow(row.facet(), copy, row.columns());
        }
    }

    /**
     * Current small.unpacked's precursor table with extra columns: {@code opt_precursor_note} (string, unmapped) at
     * precursor scope; {@code opt_window_width_ratio} (double, unmapped) in isolation_window; and, in activation,
     * {@code supplemental_cid} (boolean term marker of MS:1002679), {@code supplemental_collision_energy} (float,
     * MS:1002680 in eV) and {@code opt_activation_note} (string, unmapped).
     */
    private static final class ExtendedPrecursors {
        MessageType schema;
        ColumnResolver columns;
        List<Group> rows = new ArrayList<>();

        static ExtendedPrecursors load() {
            ExtendedPrecursors ext = new ExtendedPrecursors();
            try (MzPeakSource source = MzPeakSource.open(Fixtures.current(SMALL_UNPACKED))) {
                Entry entry = Facet.SPECTRUM_PRECURSOR.entry(MzPeakManifest.fromSource(source)).orElseThrow();
                MessageType table = ParquetFooter.read(source.inputFile(entry.name())).schema();
                List<Type> fields = new ArrayList<>();
                for (Type t : table.getFields()) {
                    if (t.getName().equals("isolation_window")) {
                        fields.add(append(t.asGroupType(),
                                Types.optional(PrimitiveTypeName.DOUBLE).named("opt_window_width_ratio")));
                    } else if (t.getName().equals("activation")) {
                        fields.add(append(t.asGroupType(),
                                Types.optional(PrimitiveTypeName.BOOLEAN).named("supplemental_cid"),
                                Types.optional(PrimitiveTypeName.FLOAT).named("supplemental_collision_energy"),
                                Types.optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                                        .named("opt_activation_note")));
                    } else {
                        fields.add(t);
                    }
                }
                fields.add(Types.optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                        .named("opt_precursor_note"));
                ext.schema = new MessageType(table.getName(), fields);
                List<ColumnMapping> mapping = new ArrayList<>(entry.columnMapping());
                mapping.add(new ColumnMapping("supplemental collision-induced dissociation",
                        "activation.supplemental_cid", "MS:1002679", null, true));
                mapping.add(new ColumnMapping("supplemental collision energy",
                        "activation.supplemental_collision_energy", "MS:1002680", ELECTRONVOLT, null));
                ext.columns = ColumnResolver.split(ext.schema, new Entry(entry.name(), entry.entityType(),
                        entry.dataKind(), mapping, entry.parameters(), entry.checksum()), Facet.SPECTRUM_PRECURSOR);
                ParquetGroups.forEach(source.inputFile(entry.name()), ext.rows::add);
            }
            return ext;
        }

        /** A copy of the precursor row of spectrum {@code sourceIndex}, without the activation fields {@code omit}. */
        FacetRow precursor(long sourceIndex, String... omit) {
            Group from = rows.stream().filter(g -> g.getLong("source_index", 0) == sourceIndex).findFirst()
                    .orElseThrow();
            Group g = new SimpleGroup(schema);
            copy(from, g, Set.of("activation"));
            Group activation = g.addGroup("activation");
            copy(from.getGroup("activation", 0), activation, Set.of(omit));
            return new FacetRow(Facet.SPECTRUM_PRECURSOR, g, columns);
        }

        private static GroupType append(GroupType group, Type... extra) {
            List<Type> fields = new ArrayList<>(group.getFields());
            fields.addAll(List.of(extra));
            return group.withNewFields(fields);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static <T> T only(List<T> items) {
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    private static Precursor withIons(Precursor p, List<SelectedIon> ions) {
        return new Precursor(p.precursorIndex(), p.precursorId(), p.isolationWindow(), ions, p.activation());
    }

    private static void assertNoFalseBoolean(String where, Precursor p) {
        assertThat(p.activation().parameters()).as(where + " activation booleans")
                .noneMatch(q -> Boolean.FALSE.equals(q.value()));
        for (SelectedIon ion : p.selectedIons()) {
            assertThat(ion.parameters()).as(where + " selected-ion booleans")
                    .noneMatch(q -> Boolean.FALSE.equals(q.value()));
        }
    }

    /** The path {@code entry}'s column_mapping gives for {@code accession}. */
    private static String path(Entry entry, String accession) {
        return entry.columnForAccession(accession).map(ColumnMapping::path)
                .orElseThrow(() -> new AssertionError(entry.name() + " maps no column to " + accession));
    }

    /** A {@code parameters} list entry of the FX-2 cells as the Param it decodes to: the first non-null arm wins. */
    private static Param param(Object entry) {
        Map<?, ?> cell = (Map<?, ?>) entry;
        Map<?, ?> value = (Map<?, ?>) cell.get("value");
        Object v = null;
        if (value != null) {
            for (String arm : List.of("integer", "float", "string", "boolean")) {
                if (value.get(arm) != null) {
                    v = value.get(arm);
                    break;
                }
            }
        }
        return new Param((String) cell.get("name"), (String) cell.get("accession"), v, (String) cell.get("unit"));
    }

    private static Map<Long, List<Row>> bySourceIndex(List<Row> rows) {
        Map<Long, List<Row>> out = new LinkedHashMap<>();
        for (Row row : rows) {
            Object key = row.get("source_index");
            assertThat(key).as("%s row %d source_index", row.member(), row.row()).isInstanceOf(Long.class);
            out.computeIfAbsent((Long) key, k -> new ArrayList<>()).add(row);
        }
        return out;
    }

    /** Appends {@code Param(name, accession, value, unit)} to the {@code parameters} list of {@code struct}. */
    private static void addParam(Group struct, String name, String accession, Object value, String unit) {
        Group item = struct.getGroup("parameters", 0).addGroup("list").addGroup("item");
        Group v = item.addGroup("value");
        if (value instanceof Long l) {
            v.add("integer", l);
        } else if (value instanceof Double d) {
            v.add("float", d);
        } else if (value instanceof String s) {
            v.add("string", s);
        } else if (value instanceof Boolean b) {
            v.add("boolean", b);
        }
        if (accession != null) {
            item.add("accession", accession);
        }
        if (name != null) {
            item.add("name", name);
        }
        if (unit != null) {
            item.add("unit", unit);
        }
    }

    /** Copies every value of {@code src} except the fields {@code omit} into {@code dst} by name, recursively. */
    private static void copy(Group src, Group dst, Set<String> omit) {
        GroupType type = src.getType();
        for (int f = 0; f < type.getFieldCount(); f++) {
            Type field = type.getType(f);
            String name = field.getName();
            if (omit.contains(name)) {
                continue;
            }
            for (int i = 0; i < src.getFieldRepetitionCount(f); i++) {
                if (!field.isPrimitive()) {
                    copy(src.getGroup(f, i), dst.addGroup(name), Set.of());
                    continue;
                }
                PrimitiveTypeName primitive = field.asPrimitiveType().getPrimitiveTypeName();
                switch (primitive) {
                    case INT32 -> dst.add(name, src.getInteger(f, i));
                    case INT64 -> dst.add(name, src.getLong(f, i));
                    case FLOAT -> dst.add(name, src.getFloat(f, i));
                    case DOUBLE -> dst.add(name, src.getDouble(f, i));
                    case BOOLEAN -> dst.add(name, src.getBoolean(f, i));
                    case BINARY -> dst.add(name, src.getBinary(f, i));
                    default -> throw new AssertionError("unexpected " + primitive + " field " + name);
                }
            }
        }
    }
}
