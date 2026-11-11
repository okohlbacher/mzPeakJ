package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Activation;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WR-5c: the split writer moves a Param to its standardized column when the reader rebuilds it equal apart from its
 * name (design §11.8), removes it from its {@code parameters} list, and derives the summary columns and
 * {@code spectrum_type} only when the description has none:
 * <ul>
 *   <li>the five summary columns of spectra_metadata (a finite Double with the column's unit);
 *   <li>{@code spectrum_type} (a value-less, unit-less MS:1000559 descendant);
 *   <li>the opt_IMS position columns of spectra_metadata_scans (a Long in [0, 2^31), no unit), written only when some
 *       scan has such a position;
 *   <li>{@code activation.dissociation_method} (a value-less, unit-less MS:1000044 descendant) and
 *       {@code activation.collision_energy} (a Long or finite Double in UO:0000266 or without unit, as mzML writers
 *       give it; it reads back as a Double in UO:0000266).
 * </ul>
 * Only the first Param that qualifies for a column is promoted, and none whose accession another Param of the same
 * list carries, so no accession is ever both a column and a parameter of one scope.
 *
 * <p>Oracles: current small.unpacked (summary Params, spectrum types and CID activations from its split columns),
 * Example_Processed.img (imaging positions), legacy small.unpacked (its stored summaries and spectrum types, V-17),
 * their manifests, and spectra constructed from current small.unpacked.
 */
class SplitWriterPromotionTest {

    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SPECTRA = "spectra_metadata.parquet";
    private static final String SCANS = "spectra_metadata_scans.parquet";
    private static final String PRECURSORS = "spectra_metadata_precursors.parquet";
    private static final String SELECTED_IONS = "spectra_metadata_selected_ions.parquet";

    private static final String TIC = "MS:1000285";
    private static final String MS1_SPECTRUM = "MS:1000579";
    private static final String MSN_SPECTRUM = "MS:1000580";
    private static final String POSITION_X = "IMS:1000050";
    private static final String POSITION_Y = "IMS:1000051";
    private static final String X_COLUMN = "opt_IMS_1000050_position_x";
    private static final String Y_COLUMN = "opt_IMS_1000051_position_y";
    private static final String COLLISION_ENERGY = "MS:1000045";
    private static final String ELECTRONVOLT = "UO:0000266";
    private static final String PERCENT = "UO:0000187";

    /** The summary columns, by accession, in schema order, with their units. */
    private static final Map<String, String> SUMMARY_COLUMNS = new LinkedHashMap<>();
    private static final Map<String, String> SUMMARY_UNITS = Map.of(
            "MS:1000528", "MS:1000040", "MS:1000527", "MS:1000040", "MS:1000504", "MS:1000040",
            "MS:1000505", "MS:1000131", TIC, "MS:1000131");

    static {
        SUMMARY_COLUMNS.put("MS:1000528", "lowest_observed_mz");
        SUMMARY_COLUMNS.put("MS:1000527", "highest_observed_mz");
        SUMMARY_COLUMNS.put("MS:1000504", "base_peak_mz");
        SUMMARY_COLUMNS.put("MS:1000505", "base_peak_intensity");
        SUMMARY_COLUMNS.put(TIC, "total_ion_current");
    }

    /** Fixtures read once per class; the tests never modify a source in place. */
    private static final Map<Path, Source> SOURCES = new ConcurrentHashMap<>();

    static Stream<Path> fixtures() {
        return Stream.of(Fixtures.current("small.unpacked.mzpeak"), Fixtures.current("Example_Processed.img.mzpeak"),
                Fixtures.legacy("small.unpacked.mzpeak"));
    }

    /** (a), (d): every fixture round-trips equal, and no accession is both a column and a parameter of a scope. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void aSplitRoundTripIsEqualAndWritesNoAccessionTwice(Path fixture, @TempDir Path tmp) throws IOException {
        Source source = source(fixture);
        Path out = write(source.spectra, source, tmp.resolve("out.mzpeak"));

        DescriptionAssert.assertEqual(fixture.toString(), descriptions(source.spectra),
                descriptions(ForwardBackwardCorpusTest.readSource(out).spectra));
        assertNoAccessionTwice(out);
    }

    /** (c) and the spectrum columns: current small.unpacked's Params all move to their columns. */
    @Test
    void currentSmallWritesItsSummariesSpectrumTypesAndActivationsAsColumns(@TempDir Path tmp) throws IOException {
        Source source = source(Fixtures.current("small.unpacked.mzpeak"));
        Path out = write(source.spectra, source, tmp.resolve("out.mzpeak"));

        List<Group> spectrumRows = rows(out.resolve(SPECTRA));
        assertThat(spectrumRows).hasSameSizeAs(source.spectra);
        for (int i = 0; i < source.spectra.size(); i++) {
            SpectrumDescription d = source.spectra.get(i).description();
            Group row = spectrumRows.get(i);
            String where = "spectrum " + d.index();
            String type = d.msLevel() > 1 ? MSN_SPECTRUM : MS1_SPECTRUM;
            assertThat(Param.contains(d.parameters(), type)).as("%s: source spectrum type", where).isTrue();
            assertThat(ParquetGroups.optString(row, "spectrum_type")).as(where).isEqualTo(type);
            for (Map.Entry<String, String> column : SUMMARY_COLUMNS.entrySet()) {
                Param p = Param.find(d.parameters(), column.getKey()).orElseThrow();
                assertThat(ParquetGroups.optDouble(row, column.getValue())).as("%s: %s", where, column.getValue())
                        .isEqualTo(p.value());
            }
            // The six Params are all the source has, and none of them stays in the list.
            assertThat(d.parameters()).as(where).hasSize(6);
            assertThat(ParquetGroups.readParams(row, "parameters")).as("%s: parameters", where).isEmpty();
        }

        List<Group> precursorRows = rows(out.resolve(PRECURSORS));
        assertThat(precursorRows).hasSize(34).allSatisfy(row -> {
            Group activation = ParquetGroups.optGroup(row, "activation");
            assertThat(ParquetGroups.optString(activation, "dissociation_method")).isEqualTo("MS:1000133");
            assertThat(ParquetGroups.optDouble(activation, "collision_energy")).isEqualTo(35.0);
            assertThat(ParquetGroups.has(activation, "parameters")).isTrue();
            assertThat(ParquetGroups.readParams(activation, "parameters")).isEmpty();
        });
        MessageType precursors = schema(out, PRECURSORS);
        assertThat(leaf(precursors, "activation", "dissociation_method").getPrimitiveTypeName())
                .isEqualTo(PrimitiveTypeName.BINARY);
        assertThat(leaf(precursors, "activation", "collision_energy").getPrimitiveTypeName())
                .isEqualTo(PrimitiveTypeName.DOUBLE);
        assertThat(mapping(manifest(out), PRECURSORS))
                .isEqualTo(mapping(manifest(Fixtures.current("small.unpacked.mzpeak")), PRECURSORS));

        // No scan carries a position, so neither position column nor its mapping is written.
        assertThat(schema(out, SCANS).containsField(X_COLUMN)).isFalse();
        assertThat(schema(out, SCANS).containsField(Y_COLUMN)).isFalse();
        assertThat(mapping(manifest(out), SCANS).toString()).doesNotContain("IMS:");
    }

    /** (b): Example_Processed.img's positions are INT32 columns mapped as in the source, and no longer Params. */
    @Test
    void exampleProcessedImgWritesItsPositionsAsInt32Columns(@TempDir Path tmp) throws IOException {
        Path fixture = Fixtures.current("Example_Processed.img.mzpeak");
        Source source = source(fixture);
        Path out = write(source.spectra, source, tmp.resolve("out.mzpeak"));

        MessageType scans = schema(out, SCANS);
        for (String column : List.of(X_COLUMN, Y_COLUMN)) {
            PrimitiveType type = leaf(scans, column);
            assertThat(type.getPrimitiveTypeName()).as(column).isEqualTo(PrimitiveTypeName.INT32);
            assertThat(type.getLogicalTypeAnnotation()).as(column).isNull();
        }
        List<JsonNode> written = new ArrayList<>();
        for (JsonNode row : mapping(manifest(out), SCANS)) {
            if (row.path("accession").asText().startsWith("IMS:")) {
                written.add(row);
            }
        }
        List<JsonNode> expected = new ArrayList<>();
        for (JsonNode row : mapping(manifest(fixture), SCANS)) {
            if (row.path("accession").asText().startsWith("IMS:")) {
                expected.add(row);
            }
        }
        assertThat(expected).hasSize(2);
        assertThat(written).containsExactlyElementsOf(expected);

        List<Group> scanRows = rows(out.resolve(SCANS));
        assertThat(scanRows).hasSize(9);
        int i = 0;
        for (Spectrum s : source.spectra) {
            ScanEvent scan = s.description().scans().get(0);
            Group row = scanRows.get(i++);
            assertThat(ParquetGroups.optLong(row, X_COLUMN)).as("x of spectrum %d", s.index())
                    .isEqualTo(Param.find(scan.parameters(), POSITION_X).orElseThrow().value());
            assertThat(ParquetGroups.optLong(row, Y_COLUMN)).as("y of spectrum %d", s.index())
                    .isEqualTo(Param.find(scan.parameters(), POSITION_Y).orElseThrow().value());
            assertThat(ParquetGroups.readParams(row, "parameters")).as("scan params of spectrum %d", s.index())
                    .extracting(Param::accession).containsExactly("MS:1000093");
        }
    }

    /**
     * (e): legacy small.unpacked's spectrum Params are its stored spectrum type and summary values (V-17, D-9a), so
     * they are promoted, not derived; the same spectra without Params get derived summaries and spectrum types.
     */
    @Test
    void legacySmallPromotesItsStoredSummariesAndSpectrumTypes(@TempDir Path tmp) {
        Source source = source(Fixtures.legacy("small.unpacked.mzpeak"));
        Path out = write(source.spectra, source, tmp.resolve("out.mzpeak"));
        List<Spectrum> bare = new ArrayList<>();
        for (Spectrum s : source.spectra) {
            bare.add(withParams(s, List.of()));
        }
        Path derived = write(bare, source, tmp.resolve("derived.mzpeak"));

        List<Group> rows = rows(out.resolve(SPECTRA));
        List<Group> derivedRows = rows(derived.resolve(SPECTRA));
        for (int i = 0; i < source.spectra.size(); i++) {
            Spectrum s = source.spectra.get(i);
            List<Param> params = s.description().parameters();
            String where = "spectrum " + s.index();
            String type = s.description().msLevel() > 1 ? MSN_SPECTRUM : MS1_SPECTRUM;
            assertThat(params).as(where).hasSize(6).contains(new Param(null, type, null, null));
            Group row = rows.get(i);
            assertThat(ParquetGroups.optString(row, "spectrum_type")).as(where).isEqualTo(type);
            for (Map.Entry<String, String> column : SUMMARY_COLUMNS.entrySet()) {
                assertThat(ParquetGroups.optDouble(row, column.getValue())).as("%s: %s", where, column.getValue())
                        .isEqualTo(Param.find(params, column.getKey()).orElseThrow().value());
            }
            assertThat(ParquetGroups.readParams(row, "parameters")).as(where).isEmpty();

            Group derivedRow = derivedRows.get(i);
            assertThat(ParquetGroups.optString(derivedRow, "spectrum_type")).as(where).isEqualTo(type);
            assertSummary(derivedRow, summary(s));
            assertThat(ParquetGroups.readParams(derivedRow, "parameters")).as(where).isEmpty();
        }
    }

    /**
     * D-9a deviation (V-17, design §6 item 6): the spectrum Params of a legacy dataset hold its stored summary
     * values, which a split write promotes instead of deriving. Legacy small.unpacked with spectrum 0's stored
     * highest observed m/z rewritten to a stale 1500.0 writes and reads back 1500.0, not the value its arrays give;
     * the same spectrum with the five summary Params removed before writing still gets the derived values.
     */
    @Test
    void legacySplitWritePromotesStoredSummaries(@TempDir Path tmp) throws IOException {
        Path dataset = MemberRewrite.copy(Fixtures.legacy("small.unpacked.mzpeak"), tmp.resolve("in"));
        Path metadata = dataset.resolve(SPECTRA);
        MemberRewrite.Member member = MemberRewrite.read(metadata);
        Group first = member.rows.get(0);
        Group spectrum = first.getGroup("spectrum", 0);
        assertThat(ParquetGroups.optLong(spectrum, "index")).isEqualTo(0L);
        String highest = "MS_1000527_highest_observed_mz_unit_MS_1000040";
        assertThat(ParquetGroups.has(spectrum, highest)).isTrue();
        PrimitiveTypeName type = spectrum.getType().getType(highest).asPrimitiveType().getPrimitiveTypeName();
        member.rows.set(0, MemberRewrite.copyRow(first, member.schema, "spectrum", row -> {
            Group stale = row.addGroup("spectrum");
            MemberRewrite.copyInto(spectrum, stale, highest);
            if (type == PrimitiveTypeName.FLOAT) {
                stale.add(highest, 1500.0f);
            } else {
                stale.add(highest, 1500.0);
            }
            return row;
        }));
        MemberRewrite.write(member, metadata);

        Source source = ForwardBackwardCorpusTest.readSource(dataset);
        List<Spectrum> spectra = new ArrayList<>(source.spectra);
        Spectrum s = spectra.get(0);
        Double derived = summary(s).get("MS:1000527");
        assertThat(derived).isNotNull().isNotEqualTo(1500.0);
        assertThat(Param.find(s.description().parameters(), "MS:1000527")).contains(
                new Param("highest observed m/z", "MS:1000527", 1500.0, "MS:1000040"));

        Path out = write(spectra, source, tmp.resolve("out.mzpeak"));
        assertThat(ParquetGroups.optDouble(rows(out.resolve(SPECTRA)).get(0), "highest_observed_mz"))
                .isEqualTo(1500.0);
        assertThat(Param.find(ForwardBackwardCorpusTest.readSource(out).spectra.get(0).description().parameters(),
                "MS:1000527").orElseThrow().value()).isEqualTo(1500.0);

        List<Param> withoutSummaries = new ArrayList<>(s.description().parameters());
        withoutSummaries.removeIf(p -> SUMMARY_COLUMNS.containsKey(p.accession()));
        assertThat(withoutSummaries).hasSize(s.description().parameters().size() - 5);
        spectra.set(0, withParams(s, withoutSummaries));
        Path derivedOut = write(spectra, source, tmp.resolve("derived.mzpeak"));
        assertSummary(rows(derivedOut.resolve(SPECTRA)).get(0), summary(s));
    }

    /**
     * (e), (f) and the first-occurrence rule, on spectra constructed from current small.unpacked: a value is derived
     * only when the description has none of its group, a Param that does not qualify stays in its list, only the
     * first qualifying Param is promoted, and none whose accession the list carries twice.
     */
    @Test
    void derivationAndPromotionFollowTheParamsOfEachSpectrum(@TempDir Path tmp) throws IOException {
        Source small = source(Fixtures.current("small.unpacked.mzpeak"));
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        List<Integer> ms1 = positions(spectra, false);
        List<Integer> msn = positions(spectra, true);

        // (f) A TIC without unit stays a Param; with one summary Param present nothing is derived.
        Param unitlessTic = new Param("total ion current", TIC, 5.0, null);
        spectra.set(ms1.get(0), withParams(spectra.get(ms1.get(0)), List.of(unitlessTic)));
        // One summary value and a SIM spectrum type: both promoted, neither derived.
        Param basePeak = new Param("base peak m/z", "MS:1000504", 123.5, "MS:1000040");
        Param sim = new Param("SIM spectrum", "MS:1000582", null, null);
        spectra.set(ms1.get(1), withParams(spectra.get(ms1.get(1)), List.of(basePeak, sim)));
        // Two spectrum types: the first is promoted, the second stays. Two TICs: neither is promoted.
        Param massSpectrum = new Param("mass spectrum", "MS:1000294", null, null);
        Param tic5 = new Param("total ion current", TIC, 5.0, "MS:1000131");
        Param tic6 = new Param("total ion current", TIC, 6.0, "MS:1000131");
        spectra.set(ms1.get(2), withParams(spectra.get(ms1.get(2)), List.of(
                new Param(null, MS1_SPECTRUM, null, null), massSpectrum, tic5, tic6)));
        // A spectrum type with a value does not qualify, and its presence still prevents the derivation.
        Param valuedType = new Param("MSn spectrum", MSN_SPECTRUM, "x", null);
        spectra.set(msn.get(0), withParams(spectra.get(msn.get(0)), List.of(valuedType)));
        // No Params at all: everything is derived.
        spectra.set(msn.get(1), withParams(spectra.get(msn.get(1)), List.of()));

        // Activations: ETD then supplemental beam-type CID; a duplicated CID and an energy in percent.
        Param etd = new Param("electron transfer dissociation", "MS:1000598", null, null);
        Param supplemental = new Param("supplemental beam-type collision-induced dissociation", "MS:1002678", null,
                null);
        Param energy = new Param("collision energy", "MS:1000045", 30.0, "UO:0000266");
        spectra.set(msn.get(2), withActivation(spectra.get(msn.get(2)), List.of(etd, supplemental, energy)));
        Param cid = new Param("collision-induced dissociation", "MS:1000133", null, null);
        Param percentEnergy = new Param("collision energy", "MS:1000045", 35.0, PERCENT);
        spectra.set(msn.get(3), withActivation(spectra.get(msn.get(3)), List.of(cid, cid, percentEnergy)));

        // Positions that do not qualify: too large for INT32, and with a unit.
        Param farX = new Param("position x", POSITION_X, 3_000_000_000L, null);
        Param yWithUnit = new Param("position y", POSITION_Y, 7L, "UO:0000017");
        spectra.set(ms1.get(3), withScanParams(spectra.get(ms1.get(3)), List.of(farX, yWithUnit)));

        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, small.chromatograms, small.wavelengthSpectra, small.scanSettings,
                small.fileMetadata, SPLIT);

        Map<Long, Group> rows = byIndex(rows(out.resolve(SPECTRA)), "index");
        Group unitless = rows.get(spectra.get(ms1.get(0)).index());
        assertThat(ParquetGroups.readParams(unitless, "parameters")).containsExactly(unitlessTic);
        assertSummary(unitless, Map.of());
        assertThat(ParquetGroups.optString(unitless, "spectrum_type")).isEqualTo(MS1_SPECTRUM);

        Group promoted = rows.get(spectra.get(ms1.get(1)).index());
        assertThat(ParquetGroups.readParams(promoted, "parameters")).isEmpty();
        assertSummary(promoted, Map.of("MS:1000504", 123.5));
        assertThat(ParquetGroups.optString(promoted, "spectrum_type")).isEqualTo("MS:1000582");

        Group twice = rows.get(spectra.get(ms1.get(2)).index());
        assertThat(ParquetGroups.readParams(twice, "parameters")).containsExactly(massSpectrum, tic5, tic6);
        assertSummary(twice, Map.of());
        assertThat(ParquetGroups.optString(twice, "spectrum_type")).isEqualTo(MS1_SPECTRUM);

        Group valued = rows.get(spectra.get(msn.get(0)).index());
        assertThat(ParquetGroups.readParams(valued, "parameters")).containsExactly(valuedType);
        assertThat(ParquetGroups.optString(valued, "spectrum_type")).isNull();
        assertSummary(valued, summary(spectra.get(msn.get(0))));

        Group derived = rows.get(spectra.get(msn.get(1)).index());
        assertThat(ParquetGroups.readParams(derived, "parameters")).isEmpty();
        assertThat(ParquetGroups.optString(derived, "spectrum_type")).isEqualTo(MSN_SPECTRUM);
        assertSummary(derived, summary(spectra.get(msn.get(1))));

        Map<Long, Group> activations = new HashMap<>();
        for (Group row : rows(out.resolve(PRECURSORS))) {
            activations.put(row.getLong("source_index", 0), ParquetGroups.optGroup(row, "activation"));
        }
        Group etdRow = activations.get(spectra.get(msn.get(2)).index());
        assertThat(ParquetGroups.optString(etdRow, "dissociation_method")).isEqualTo("MS:1000598");
        assertThat(ParquetGroups.optDouble(etdRow, "collision_energy")).isEqualTo(30.0);
        assertThat(ParquetGroups.readParams(etdRow, "parameters")).containsExactly(supplemental);
        Group cidRow = activations.get(spectra.get(msn.get(3)).index());
        assertThat(ParquetGroups.optString(cidRow, "dissociation_method")).isNull();
        assertThat(ParquetGroups.optDouble(cidRow, "collision_energy")).isNull();
        assertThat(ParquetGroups.readParams(cidRow, "parameters")).containsExactly(cid, cid, percentEnergy);

        // No scan has a position that qualifies, so there is no position column.
        assertThat(schema(out, SCANS).containsField(X_COLUMN)).isFalse();
        assertThat(schema(out, SCANS).containsField(Y_COLUMN)).isFalse();
        int withPositions = 0;
        for (Group row : rows(out.resolve(SCANS))) {
            List<Param> params = ParquetGroups.readParams(row, "parameters");
            if (params.contains(farX)) {
                assertThat(params).contains(yWithUnit);
                withPositions++;
            }
        }
        assertThat(withPositions).isEqualTo(1);

        Source back = ForwardBackwardCorpusTest.readSource(out);
        DescriptionAssert.assertEqual("constructed spectra", descriptions(spectra), descriptions(back.spectra));
        assertThat(back.spectra.get(msn.get(2)).description().primaryPrecursor().activation().parameters())
                .containsExactly(etd, energy, supplemental);
        assertNoAccessionTwice(out);
    }

    /**
     * A summary value or a collision energy that is not finite does not qualify for its column
     * ({@code SplitMetadataWriter.doubleIn}): a NaN and an infinite total ion current and a NaN and an infinite
     * collision energy stay in their {@code parameters} lists, their columns stay null, nothing is derived next to
     * the summary Param, and the spectra read back equal.
     */
    @Test
    void nonFiniteSummaryValuesAndCollisionEnergiesStayParams(@TempDir Path tmp) throws IOException {
        Source small = source(Fixtures.current("small.unpacked.mzpeak"));
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        List<Integer> ms1 = positions(spectra, false);
        List<Integer> msn = positions(spectra, true);
        List<Param> tics = List.of(new Param("total ion current", TIC, Double.NaN, "MS:1000131"),
                new Param("total ion current", TIC, Double.POSITIVE_INFINITY, "MS:1000131"));
        Param cid = new Param("collision-induced dissociation", "MS:1000133", null, null);
        List<Param> energies = List.of(new Param("collision energy", "MS:1000045", Double.NaN, "UO:0000266"),
                new Param("collision energy", "MS:1000045", Double.NEGATIVE_INFINITY, "UO:0000266"));
        for (int k = 0; k < 2; k++) {
            spectra.set(ms1.get(k), withParams(spectra.get(ms1.get(k)), List.of(tics.get(k))));
            spectra.set(msn.get(k), withActivation(spectra.get(msn.get(k)), List.of(cid, energies.get(k))));
        }

        Path out = write(spectra, small, tmp.resolve("out.mzpeak"));

        Map<Long, Group> rows = byIndex(rows(out.resolve(SPECTRA)), "index");
        Map<Long, Group> activations = new HashMap<>();
        for (Group row : rows(out.resolve(PRECURSORS))) {
            activations.put(row.getLong("source_index", 0), ParquetGroups.optGroup(row, "activation"));
        }
        for (int k = 0; k < 2; k++) {
            Group spectrum = rows.get(spectra.get(ms1.get(k)).index());
            assertThat(ParquetGroups.readParams(spectrum, "parameters")).as("params of %s", tics.get(k))
                    .containsExactly(tics.get(k));
            assertSummary(spectrum, Map.of());
            Group activation = activations.get(spectra.get(msn.get(k)).index());
            assertThat(ParquetGroups.optString(activation, "dissociation_method")).isEqualTo("MS:1000133");
            assertThat(ParquetGroups.optDouble(activation, "collision_energy")).as("column of %s", energies.get(k))
                    .isNull();
            assertThat(ParquetGroups.readParams(activation, "parameters")).as("params of %s", energies.get(k))
                    .containsExactly(energies.get(k));
        }

        Source back = ForwardBackwardCorpusTest.readSource(out);
        DescriptionAssert.assertEqual("non-finite summaries and energies", descriptions(spectra),
                descriptions(back.spectra));
        for (int k = 0; k < 2; k++) {
            assertThat(back.spectra.get(ms1.get(k)).description().parameters()).contains(tics.get(k));
            assertThat(back.spectra.get(msn.get(k)).description().primaryPrecursor().activation().parameters())
                    .containsExactlyInAnyOrder(cid, energies.get(k));
        }
        assertNoAccessionTwice(out);
    }

    /**
     * mzML writers give the collision energy as an integer ({@code 35}) and omit its unit; MS:1000045 is defined in
     * electronvolt, so a Long or finite Double without unit or in electronvolt goes to
     * {@code activation.collision_energy} as a Double, leaves the {@code parameters} list and reads back as a Double
     * in electronvolt, which the Rust reader takes as the energy instead of 0.
     */
    @Test
    void integerAndUnitlessCollisionEnergiesGoToTheColumnInElectronvolt(@TempDir Path tmp) throws IOException {
        Source small = source(Fixtures.current("small.unpacked.mzpeak"));
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        List<Integer> msn = positions(spectra, true);
        Param cid = new Param("collision-induced dissociation", "MS:1000133", null, null);
        List<Param> energies = List.of(new Param("collision energy", COLLISION_ENERGY, 35L, null),
                new Param("collision energy", COLLISION_ENERGY, 30L, ELECTRONVOLT),
                new Param("collision energy", COLLISION_ENERGY, 27.5, null));
        List<Double> columns = List.of(35.0, 30.0, 27.5);
        for (int k = 0; k < energies.size(); k++) {
            spectra.set(msn.get(k), withActivation(spectra.get(msn.get(k)), List.of(cid, energies.get(k))));
        }

        Path out = write(spectra, small, tmp.resolve("out.mzpeak"));

        Map<Long, Group> activations = new HashMap<>();
        for (Group row : rows(out.resolve(PRECURSORS))) {
            activations.put(row.getLong("source_index", 0), ParquetGroups.optGroup(row, "activation"));
        }
        Source back = ForwardBackwardCorpusTest.readSource(out);
        for (int k = 0; k < energies.size(); k++) {
            Group activation = activations.get(spectra.get(msn.get(k)).index());
            assertThat(ParquetGroups.optString(activation, "dissociation_method")).isEqualTo("MS:1000133");
            assertThat(ParquetGroups.optDouble(activation, "collision_energy")).as("column of %s", energies.get(k))
                    .isEqualTo(columns.get(k));
            assertThat(ParquetGroups.readParams(activation, "parameters")).as("params of %s", energies.get(k))
                    .isEmpty();
            Activation read = back.spectra.get(msn.get(k)).description().primaryPrecursor().activation();
            assertThat(read.parameters()).as("read back %s", energies.get(k)).containsExactly(cid,
                    new Param("collision energy", COLLISION_ENERGY, columns.get(k), ELECTRONVOLT));
            assertThat(read.collisionEnergy()).hasValue(columns.get(k));
        }
        assertNoAccessionTwice(out);
    }

    /**
     * A collision energy in another unit than electronvolt, or whose value is no number, does not qualify for
     * {@code activation.collision_energy}: it stays in the {@code parameters} list unchanged, the column stays null,
     * and it reads back equal.
     */
    @Test
    void aCollisionEnergyInAnotherUnitOrWithoutANumberStaysAParam(@TempDir Path tmp) throws IOException {
        Source small = source(Fixtures.current("small.unpacked.mzpeak"));
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        List<Integer> msn = positions(spectra, true);
        Param cid = new Param("collision-induced dissociation", "MS:1000133", null, null);
        List<Param> energies = List.of(new Param("collision energy", COLLISION_ENERGY, 35L, PERCENT),
                new Param("collision energy", COLLISION_ENERGY, 35.0, PERCENT),
                new Param("collision energy", COLLISION_ENERGY, "35", null),
                new Param("collision energy", COLLISION_ENERGY, Double.NaN, null));
        for (int k = 0; k < energies.size(); k++) {
            spectra.set(msn.get(k), withActivation(spectra.get(msn.get(k)), List.of(cid, energies.get(k))));
        }

        Path out = write(spectra, small, tmp.resolve("out.mzpeak"));

        Map<Long, Group> activations = new HashMap<>();
        for (Group row : rows(out.resolve(PRECURSORS))) {
            activations.put(row.getLong("source_index", 0), ParquetGroups.optGroup(row, "activation"));
        }
        for (int k = 0; k < energies.size(); k++) {
            Group activation = activations.get(spectra.get(msn.get(k)).index());
            assertThat(ParquetGroups.optString(activation, "dissociation_method")).isEqualTo("MS:1000133");
            assertThat(ParquetGroups.optDouble(activation, "collision_energy")).as("column of %s", energies.get(k))
                    .isNull();
            assertThat(ParquetGroups.readParams(activation, "parameters")).as("params of %s", energies.get(k))
                    .containsExactly(energies.get(k));
        }
        Source back = ForwardBackwardCorpusTest.readSource(out);
        DescriptionAssert.assertEqual("energies that stay Params", descriptions(spectra), descriptions(back.spectra));
        for (int k = 0; k < energies.size(); k++) {
            assertThat(back.spectra.get(msn.get(k)).description().primaryPrecursor().activation().parameters())
                    .containsExactly(cid, energies.get(k));
        }
        assertNoAccessionTwice(out);
    }

    /**
     * Each position column is written when some scan has a position that qualifies for it: a negative y stays a
     * Param, and without another y there is no y column.
     */
    @Test
    void aPositionColumnIsWrittenOnlyWhenSomeScanHasAPositionThatQualifies(@TempDir Path tmp) throws IOException {
        Source small = source(Fixtures.current("small.unpacked.mzpeak"));
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        Param x = new Param("position x", POSITION_X, 4L, null);
        Param negativeY = new Param("position y", POSITION_Y, -1L, null);
        int target = positions(spectra, false).get(0);
        spectra.set(target, withScanParams(spectra.get(target), List.of(x, negativeY)));

        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, small.chromatograms, small.wavelengthSpectra, small.scanSettings,
                small.fileMetadata, SPLIT);

        MessageType scans = schema(out, SCANS);
        assertThat(leaf(scans, X_COLUMN).getPrimitiveTypeName()).isEqualTo(PrimitiveTypeName.INT32);
        assertThat(scans.containsField(Y_COLUMN)).isFalse();
        List<String> mapped = new ArrayList<>();
        for (JsonNode row : mapping(manifest(out), SCANS)) {
            mapped.add(row.path("path").asText());
        }
        assertThat(mapped).contains(X_COLUMN).doesNotContain(Y_COLUMN);
        for (Group row : rows(out.resolve(SCANS))) {
            if (row.getLong("source_index", 0) == spectra.get(target).index()) {
                assertThat(ParquetGroups.optLong(row, X_COLUMN)).isEqualTo(4L);
                assertThat(ParquetGroups.readParams(row, "parameters")).contains(negativeY).doesNotContain(x);
            } else {
                assertThat(ParquetGroups.optLong(row, X_COLUMN)).isNull();
            }
        }
        DescriptionAssert.assertEqual("constructed positions", descriptions(spectra),
                descriptions(ForwardBackwardCorpusTest.readSource(out).spectra));
        assertNoAccessionTwice(out);
    }

    /**
     * The spectrum types are the 16 descendants of MS:1000559 in psi-ms 4.2.11 (mzdata v0.68.0 cv/psi-ms.obo.gz, is_a
     * edges only, obsolete terms skipped), and the type derived from the MS level is MS1 up to level 1, else MSn.
     */
    @Test
    void theSpectrumTypesAreTheDescendantsOfSpectrumType() {
        assertThat(SpectrumSummary.SPECTRUM_TYPES).containsExactly(
                "MS:1000294", "MS:1000322", "MS:1000325", "MS:1000326", "MS:1000328", "MS:1000341", "MS:1000579",
                "MS:1000580", "MS:1000581", "MS:1000582", "MS:1000583", "MS:1000789", "MS:1000790", "MS:1000804",
                "MS:1000805", "MS:1000806");
        assertThat(SpectrumSummary.SPECTRUM_TYPES.contains(null)).isFalse();
        assertThat(SpectrumSummary.SPECTRUM_TYPES).doesNotContain("MS:1000559");
        assertThat(SpectrumSummary.spectrumType(0)).isEqualTo(MS1_SPECTRUM);
        assertThat(SpectrumSummary.spectrumType(1)).isEqualTo(MS1_SPECTRUM);
        assertThat(SpectrumSummary.spectrumType(2)).isEqualTo(MSN_SPECTRUM);
        assertThat(SpectrumSummary.spectrumType(3)).isEqualTo(MSN_SPECTRUM);
    }

    /**
     * Step 5: {@link DescriptionAssert} ignores a read-back summary value or MS1/MSn spectrum type only in a
     * spectrum's own Params, and only when the source has none of its group, where the writer derives it.
     */
    @Test
    void descriptionAssertIgnoresDerivedValuesOnlyWhereTheWriterDerivesThem() {
        Param tic = new Param("total ion current", TIC, 5.0, "MS:1000131");
        Param basePeak = new Param("base peak m/z", "MS:1000504", 400.0, "MS:1000040");
        Param ms1 = new Param(null, MS1_SPECTRUM, null, null);
        Param sim = new Param("SIM spectrum", "MS:1000582", null, null);

        // Nothing of either group in the source: derived values do not count.
        assertThat(DescriptionAssert.differences("s", spectrum(List.of(), List.of()),
                spectrum(List.of(basePeak, tic, ms1), List.of()))).isEmpty();
        // One summary value in the source: the writer derives none, so another one is a difference.
        assertThat(DescriptionAssert.differences("s", spectrum(List.of(tic, sim), List.of()),
                spectrum(List.of(tic, sim, basePeak), List.of()))).containsExactly(
                "s.parameters: missing [], unexpected [ParamKey[id=MS:1000504, value=400.0, unit=MS:1000040]]");
        // A spectrum type in the source: the writer derives none, so an MS1 spectrum type is a difference.
        assertThat(DescriptionAssert.differences("s", spectrum(List.of(sim), List.of()),
                spectrum(List.of(sim, ms1, tic), List.of()))).containsExactly(
                "s.parameters: missing [], unexpected [ParamKey[id=MS:1000579, value=null, unit=null]]");
        // Other lists ignore nothing.
        assertThat(DescriptionAssert.differences("s", spectrum(List.of(), List.of()),
                spectrum(List.of(), List.of(tic)))).containsExactly(
                "s.scans[0].parameters: missing [], unexpected [ParamKey[id=MS:1000285, value=5.0,"
                        + " unit=MS:1000131]]");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static SpectrumDescription spectrum(List<Param> params, List<Param> scanParams) {
        ScanEvent scan = new ScanEvent(1.0, null, null, null, null, null, List.of(), scanParams);
        return new SpectrumDescription(0, "scan=1", 1, 1.0, null, SignalContinuity.PROFILE, 3, 0, List.of(scan),
                List.of(), params);
    }

    /**
     * (d) Asserts that no row of the written split spectrum members has an accession both in a column of a scope and
     * in that scope's parameters list. A column's accession is its mapping's, or for a term marker its cell.
     */
    private static void assertNoAccessionTwice(Path out) throws IOException {
        JsonNode manifest = manifest(out);
        for (String member : List.of(SPECTRA, SCANS, PRECURSORS, SELECTED_IONS)) {
            Map<String, Map<String, JsonNode>> byScope = new HashMap<>();
            for (JsonNode row : mapping(manifest, member)) {
                String path = row.path("path").asText();
                int dot = path.lastIndexOf('.');
                String scope = dot < 0 ? "" : path.substring(0, dot);
                byScope.computeIfAbsent(scope, k -> new HashMap<>()).put(path.substring(dot + 1), row);
            }
            int checked = 0;
            List<Group> rows = rows(out.resolve(member));
            for (Group row : rows) {
                for (String scope : List.of("", "isolation_window", "activation")) {
                    Group g = scope.isEmpty() ? row : ParquetGroups.optGroup(row, scope);
                    if (g == null || !g.getType().containsField("parameters")) {
                        continue;
                    }
                    Set<String> columns = new HashSet<>();
                    for (Map.Entry<String, JsonNode> e : byScope.getOrDefault(scope, Map.of()).entrySet()) {
                        String field = e.getKey();
                        if (!g.getType().containsField(field) || !g.getType().getType(field).isPrimitive()
                                || !ParquetGroups.has(g, field)) {
                            continue;
                        }
                        boolean marker = e.getValue().path("term_marker").asBoolean(false);
                        columns.add(marker ? ParquetGroups.optString(g, field)
                                : e.getValue().path("accession").asText());
                    }
                    Set<String> params = new HashSet<>();
                    for (Param p : ParquetGroups.readParams(g, "parameters")) {
                        params.add(p.accession());
                    }
                    params.retainAll(columns);
                    assertThat(params).as("%s, scope '%s': accessions both a column and a parameter of row %s",
                            member, scope, row).isEmpty();
                    checked++;
                }
            }
            // Every row has at least one scope with a parameters list.
            assertThat(checked).as("%s: scopes checked", member).isGreaterThanOrEqualTo(rows.size());
        }
    }

    private static void assertSummary(Group row, Map<String, Double> expected) {
        for (Map.Entry<String, String> column : SUMMARY_COLUMNS.entrySet()) {
            assertThat(ParquetGroups.optDouble(row, column.getValue())).as(column.getValue())
                    .isEqualTo(expected.get(column.getKey()));
        }
    }

    /**
     * The summary values a writer derives from {@code s}'s payload, by accession: from its peaks when it is a
     * centroid spectrum with peaks, else from its arrays; none for an empty spectrum.
     */
    private static Map<String, Double> summary(Spectrum s) {
        List<double[]> points = new ArrayList<>();
        if (s.description().signalContinuity() == SignalContinuity.CENTROID && !s.peaks().isEmpty()) {
            for (CentroidPeak p : s.peaks()) {
                points.add(new double[] {p.mz(), p.intensity()});
            }
        } else {
            for (int i = 0; i < s.mz().length; i++) {
                points.add(new double[] {s.mz()[i], s.intensity()[i]});
            }
        }
        Map<String, Double> out = new HashMap<>();
        if (points.isEmpty()) {
            return out;
        }
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        double tic = 0;
        double[] base = null;
        for (double[] p : points) {
            lo = Math.min(lo, p[0]);
            hi = Math.max(hi, p[0]);
            tic += p[1];
            if (base == null || p[1] > base[1]) {
                base = p;
            }
        }
        out.put("MS:1000528", lo);
        out.put("MS:1000527", hi);
        out.put("MS:1000504", base[0]);
        out.put("MS:1000505", base[1]);
        out.put(TIC, tic);
        return out;
    }

    /** The positions in {@code spectra} of the MSn spectra (or MS1 spectra), in order. */
    private static List<Integer> positions(List<Spectrum> spectra, boolean msn) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < spectra.size(); i++) {
            if (!spectra.get(i).description().precursors().isEmpty() == msn) {
                out.add(i);
            }
        }
        return out;
    }

    private static Spectrum withParams(Spectrum s, List<Param> params) {
        SpectrumDescription d = s.description();
        return with(s, new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(), d.precursors(), params));
    }

    private static Spectrum withActivation(Spectrum s, List<Param> activation) {
        SpectrumDescription d = s.description();
        Precursor p = d.primaryPrecursor();
        Precursor changed = new Precursor(p.precursorIndex(), p.precursorId(), p.isolationWindow(),
                p.selectedIons(), new Activation(activation));
        return with(s, new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(), List.of(changed),
                d.parameters()));
    }

    /** {@code s} with {@code extra} appended to the Params of its first scan. */
    private static Spectrum withScanParams(Spectrum s, List<Param> extra) {
        SpectrumDescription d = s.description();
        ScanEvent e = d.scans().get(0);
        List<Param> params = new ArrayList<>(e.parameters());
        params.addAll(extra);
        List<ScanEvent> scans = new ArrayList<>(d.scans());
        scans.set(0, new ScanEvent(e.startTime(), e.injectionTime(), e.filterString(), e.presetScanConfiguration(),
                e.instrumentConfigurationRef(), e.spectrumReference(), e.scanWindows(), params));
        return with(s, new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), scans, d.precursors(),
                d.parameters()));
    }

    private static Spectrum with(Spectrum s, SpectrumDescription d) {
        return new Spectrum(d, s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
    }

    private static Source source(Path fixture) {
        return SOURCES.computeIfAbsent(fixture, ForwardBackwardCorpusTest::readSource);
    }

    private static Path write(List<Spectrum> spectra, Source s, Path out) {
        MzPeakWriter.writeDirectory(out, spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, SPLIT);
        return out;
    }

    private static List<SpectrumDescription> descriptions(List<Spectrum> spectra) {
        List<SpectrumDescription> out = new ArrayList<>(spectra.size());
        for (Spectrum s : spectra) {
            out.add(s.description());
        }
        return out;
    }

    private static JsonNode manifest(Path dataset) throws IOException {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            return MAPPER.readTree(source.readManifestBytes());
        }
    }

    /** The column_mapping of the manifest entry named {@code name}. */
    private static JsonNode mapping(JsonNode manifest, String name) {
        for (JsonNode e : manifest.path("files")) {
            if (name.equals(e.path("name").asText())) {
                return e.path("column_mapping");
            }
        }
        throw new AssertionError("no manifest entry " + name);
    }

    private static MessageType schema(Path out, String member) {
        try (MzPeakSource dataset = MzPeakSource.open(out)) {
            return ParquetFooter.read(dataset.inputFile(member)).schema();
        }
    }

    /** The primitive field at {@code path} of {@code schema}. */
    private static PrimitiveType leaf(MessageType schema, String... path) {
        assertThat(schema.containsPath(path)).as("%s has %s", schema.getName(), String.join(".", path)).isTrue();
        return schema.getType(path).asPrimitiveType();
    }

    private static Map<Long, Group> byIndex(List<Group> rows, String key) {
        Map<Long, Group> out = new HashMap<>();
        for (Group row : rows) {
            out.put(row.getLong(key, 0), row);
        }
        return out;
    }

    private static List<Group> rows(Path file) {
        List<Group> out = new ArrayList<>();
        ParquetGroups.forEach(file, out::add);
        return out;
    }
}
