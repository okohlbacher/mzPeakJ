package org.mzpeak.io;

import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterization of packed spectrum-metadata decoding through {@link SpectrumMetadataDecoder#decode(InputFile)},
 * pinned before RD-24 and RD-25 move and rebuild it. {@link LegacyMetadataSnapshotTest} pins what the legacy
 * fixtures decode to; this test pins the paths those fixtures do not reach.
 *
 * <p>Each case writes constructed packed rows and decodes them. Every struct is a copy of a struct of the legacy
 * small.unpacked spectrum metadata member (HUPO-PSI/mzPeak 29e59b2), found by manifest role, with the edits the case
 * names. The schema is that member's schema plus the {@code IMS_1000050_position_x} and
 * {@code IMS_1000051_position_y} scan columns, which the fixture does not have.
 *
 * <p>RD-7 changes the selected-ion join on purpose and RD-5 reads the split layout; they update the cases that pin
 * today's rule. RD-11 replaces the ion mobility term list, so an array term such as MS:1002477 no longer keeps the
 * typed ion mobility column out.
 */
class PackedSpectrumMetadataCharacterizationTest {

    private static final String SPECTRUM = "spectrum";
    private static final String SCAN = "scan";
    private static final String PRECURSOR = "precursor";
    private static final String SELECTED_ION = "selected_ion";
    private static final List<String> STRUCTS = List.of(SPECTRUM, SCAN, PRECURSOR, SELECTED_ION);

    private static final String POSITION_X = "IMS_1000050_position_x";
    private static final String POSITION_Y = "IMS_1000051_position_y";

    /** The fixture's MS1 spectra, the only ones with a non-empty mz_delta_model. */
    private static final Set<Long> MS1 = Set.of(0L, 1L, 7L, 8L, 14L, 15L, 21L, 22L, 28L, 29L, 34L, 35L, 41L, 42L);

    /** The fixture member's schema plus the two imaging position columns in the scan struct. */
    private static MessageType schema;
    /** The rows of the fixture member, in file order. */
    private static List<Group> fixtureRows;
    /** What the decoder makes of the fixture member. */
    private static SpectrumMetadataDecoder.Decoded fixture;

    @TempDir
    Path tmp;

    private int written;

    @BeforeAll
    static void loadFixture() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.legacy("small.unpacked.mzpeak"))) {
            InputFile member = source.inputFile(MzPeakManifest.fromSource(source)
                    .find(EntityType.SPECTRUM, DataKind.METADATA).orElseThrow().name());
            schema = withPositionColumns(ParquetFooter.read(member).schema());
            List<Group> rows = new ArrayList<>();
            ParquetGroups.forEach(member, rows::add);
            fixtureRows = List.copyOf(rows);
            fixture = SpectrumMetadataDecoder.decode(member);
        }
    }

    // ---- rows and the source_index join ----------------------------------------------------------------------

    @Test
    void copiedRowsDecodeLikeTheFixture() {
        assertThat(fixture.descriptions()).hasSize(48).extracting(SpectrumDescription::index)
                .containsExactlyElementsOf(LongStream.range(0, 48).boxed().toList());
        assertThat(fixture.deltaModels()).containsOnlyKeys(MS1);
        assertThat(fixture.deltaModels().get(0L)).hasSize(3);
        assertThat(fixture.deltaModels().get(1L)).hasSize(1);

        assertDecodesLikeTheFixture(decode(copiesOfAllRows()));
    }

    /** Spectra in reverse order, and each facet rotated by a different number of rows: the join ignores rows. */
    @Test
    void facetsJoinOnSourceIndexWhateverRowTheyAreIn() {
        int n = fixtureRows.size();
        List<Group> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Group row = newRow();
            putFromRow(row, SPECTRUM, n - 1 - i);
            putFromRow(row, SCAN, (i + 7) % n);
            putFromRow(row, PRECURSOR, (i + 13) % n);
            putFromRow(row, SELECTED_ION, (i + 29) % n);
            rows.add(row);
        }
        assertDecodesLikeTheFixture(decode(rows));
    }

    @Test
    void placeholderStructsAreSkipped() {
        List<Group> rows = copiesOfAllRows();
        rows.add(0, newRow());
        Group keyless = newRow();
        put(keyless, SPECTRUM, fixtureStruct(SPECTRUM, 0), "index");
        put(keyless, SCAN, fixtureStruct(SCAN, 5), "source_index");
        put(keyless, PRECURSOR, fixtureStruct(PRECURSOR, 5), "source_index");
        put(keyless, SELECTED_ION, fixtureStruct(SELECTED_ION, 5), "source_index");
        rows.add(20, keyless);
        rows.add(newRow());

        assertDecodesLikeTheFixture(decode(rows));
    }

    @Test
    void facetsOfAnAbsentSpectrumAreDropped() {
        List<Group> rows = copiesOfAllRows();
        Group orphans = newRow();
        rekeyed(orphans, SCAN, 5, 99);
        rekeyed(orphans, PRECURSOR, 5, 99);
        rekeyed(orphans, SELECTED_ION, 5, 99);
        rows.add(orphans);

        assertDecodesLikeTheFixture(decode(rows));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {SPECTRUM, SCAN, PRECURSOR, SELECTED_ION})
    void aKeyWithTheUint64HighBitSetFailsLoudly(String struct) {
        List<Group> rows = copiesOfAllRows();
        Group row = newRow();
        rekeyed(row, struct, 5, -1);
        rows.add(row);

        String key = SPECTRUM.equals(struct) ? "index" : "source_index";
        assertThatThrownBy(() -> decode(rows))
                .isInstanceOf(MzPeakException.class)
                .hasMessage(struct + "." + key + " exceeds supported range (uint64 high bit set): -1");
    }

    @Test
    void aDuplicateSpectrumIndexFailsLoudly() {
        List<Group> rows = copiesOfAllRows();
        Group row = newRow();
        rekeyed(row, SPECTRUM, 30, 17);
        rows.add(row);

        assertThatThrownBy(() -> decode(rows))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("duplicate spectrum.index in metadata: 17");
    }

    // ---- spectrum struct -------------------------------------------------------------------------------------

    @Test
    void absentSpectrumFieldsTakeTheirDefaults() {
        Group bare = newRow();
        bare.addGroup(SPECTRUM).add("index", 3L);
        Group emptyModel = newRow();
        Group spectrum = emptyModel.addGroup(SPECTRUM);
        spectrum.add("index", 4L);
        spectrum.addGroup("mz_delta_model");

        SpectrumMetadataDecoder.Decoded decoded = decode(List.of(emptyModel, bare));

        assertThat(Canonical.lines(decoded.descriptions(), Canonical::describe)).containsExactly(
                "3|␀|0|NaN|UNKNOWN|UNKNOWN|0|0|[]|[]|[]",
                "4|␀|0|NaN|UNKNOWN|UNKNOWN|0|0|[]|[]|[]");
        assertThat(decoded.deltaModels()).isEmpty();
    }

    /**
     * D-9a: a packed spectrum struct's unconsumed scalar columns are Params, as the split read gives them. For each
     * of the 48 spectra of legacy small.unpacked, the Params with one of the six accessions below are those of
     * current small.unpacked's spectrum with the same index: same name, accession and unit, and the value compared
     * as float, since the legacy base peak intensity and total ion current columns are FLOAT (float32) where the
     * current ones are DOUBLE (the other summary columns are DOUBLE in both).
     */
    @Test
    void packedSpectrumLeftoversMatchTheSplitRead() {
        Set<String> accessions = Set.of("MS:1000579", "MS:1000580", "MS:1000528", "MS:1000527", "MS:1000504",
                "MS:1000505", "MS:1000285");
        try (MzPeakReader legacy = MzPeakReader.open(Fixtures.legacy("small.unpacked.mzpeak"));
             MzPeakReader current = MzPeakReader.open(Fixtures.current("small.unpacked.mzpeak"))) {
            assertThat(legacy.size()).isEqualTo(48);
            for (int i = 0; i < 48; i++) {
                List<Param> expected = only(current.getMetadata(i).orElseThrow().parameters(), accessions);
                List<Param> actual = only(legacy.getMetadata(i).orElseThrow().parameters(), accessions);
                assertThat(expected).as("current spectrum %d", i).hasSize(6);
                assertThat(actual).as("legacy spectrum %d", i).hasSameSizeAs(expected);
                for (int k = 0; k < expected.size(); k++) {
                    Param e = expected.get(k);
                    Param a = actual.get(k);
                    assertThat(new Param(a.name(), a.accession(), null, a.unit())).as("spectrum %d param %d", i, k)
                            .isEqualTo(new Param(e.name(), e.accession(), null, e.unit()));
                    if (e.value() == null) {
                        assertThat(a.value()).as("spectrum %d %s", i, e.accession()).isNull();
                    } else {
                        assertThat(a.value()).as("spectrum %d %s", i, e.accession()).isInstanceOf(Double.class);
                        assertThat((float) (double) (Double) a.value()).as("spectrum %d %s", i, e.accession())
                                .isEqualTo((float) (double) (Double) e.value());
                    }
                }
            }
        }
    }

    /** The Params of {@code params} whose accession is one of {@code accessions}, in list order. */
    private static List<Param> only(List<Param> params, Set<String> accessions) {
        return params.stream().filter(p -> accessions.contains(p.accession())).toList();
    }

    // ---- scan struct -----------------------------------------------------------------------------------------

    @Test
    void scansKeepFileOrderStartTimeDefaultsToNaNAndAWindowNeedsBothLimits() {
        Group first = newRow();
        put(first, SPECTRUM, fixtureStruct(SPECTRUM, 0));
        Group edited = put(first, SCAN, fixtureStruct(SCAN, 0),
                "MS_1000016_scan_start_time_unit_UO_0000031", "scan_windows");
        Group windows = edited.addGroup("scan_windows");
        addWindow(windows, 150f, 1500f);
        addWindow(windows, 150f, null);
        addWindow(windows, null, 1500f);
        Group second = newRow();
        put(second, SCAN, fixtureStruct(SCAN, 0));

        List<ScanEvent> scans = only(decode(List.of(first, second))).scans();

        ScanEvent original = fixtureDescription(0).scans().get(0);
        assertThat(scans).hasSize(2);
        assertThat(Canonical.scan(scans.get(0))).isEqualTo(Canonical.scan(new ScanEvent(Double.NaN,
                original.injectionTime(), original.filterString(), original.presetScanConfiguration(),
                original.instrumentConfigurationRef(), original.spectrumReference(),
                List.of(new ScanWindow(150.0, 1500.0)), original.parameters())));
        assertThat(Canonical.scan(scans.get(1))).isEqualTo(Canonical.scan(original));
    }

    @Test
    void anIonMobilityColumnIsAppendedToTheScanParams() {
        String resolvingPower = "(mass resolving power|MS:1000800|Long:100000|␀)";
        assertThat(scanParams(scan -> { })).containsExactly(resolvingPower);
        assertThat(scanParams(scan -> {
            scan.add("ion_mobility_value", 1.25);
            scan.add("ion_mobility_type", "MS:1002815");
        })).containsExactly(resolvingPower,
                "(inverse reduced ion mobility drift time|MS:1002815|Double:1.25|MS:1002814)");
        // D-8: no type, or a blank one, is rejected
        assertThatThrownBy(() -> scanParams(scan -> scan.add("ion_mobility_value", 1.25)))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("SPECTRUM_SCAN row with source_index 0 has ion_mobility_value 1.25")
                .hasMessageContaining("no ion_mobility_type");
        assertThatThrownBy(() -> scanParams(scan -> {
            scan.add("ion_mobility_value", 1.25);
            scan.add("ion_mobility_type", " ");
        })).isInstanceOf(MzPeakException.class).hasMessageContaining("no ion_mobility_type");
        // a value that is not finite is dropped
        assertThat(scanParams(scan -> scan.add("ion_mobility_value", Double.NaN))).containsExactly(resolvingPower);
        assertThat(scanParams(scan -> scan.add("ion_mobility_value", Double.POSITIVE_INFINITY)))
                .containsExactly(resolvingPower);
        // an ion mobility param already present wins over the column
        assertThat(scanParams(scan -> {
            addParam(scan, "MS:1002476", "ion mobility drift time", 2.5);
            scan.add("ion_mobility_value", 1.25);
        })).containsExactly(resolvingPower, "(ion mobility drift time|MS:1002476|Double:2.5|␀)");
        // RD-11: MS:1002477 is an ion mobility array term, not an ion mobility type, so the column is appended
        assertThat(scanParams(scan -> {
            addParam(scan, "MS:1002477", "mean ion mobility drift time", 2.5);
            scan.add("ion_mobility_value", 1.25);
            scan.add("ion_mobility_type", "MS:1002476");
        })).containsExactly(resolvingPower, "(mean ion mobility drift time|MS:1002477|Double:2.5|␀)",
                "(ion mobility drift time|MS:1002476|Double:1.25|UO:0000028)");
    }

    @Test
    void imagingPositionColumnsAreAppendedToTheScanParams() {
        String resolvingPower = "(mass resolving power|MS:1000800|Long:100000|␀)";
        assertThat(scanParams(scan -> {
            scan.add(POSITION_Y, 4);
            scan.add(POSITION_X, 3);
            scan.add("ion_mobility_value", 1.25);
            scan.add("ion_mobility_type", "MS:1002476");
        })).containsExactly(resolvingPower,
                "(ion mobility drift time|MS:1002476|Double:1.25|UO:0000028)",
                "(position x|IMS:1000050|Long:3|␀)",
                "(position y|IMS:1000051|Long:4|␀)");
        // a position param already present wins over its column
        assertThat(scanParams(scan -> {
            addParam(scan, "IMS:1000050", "position x", 7.0);
            scan.add(POSITION_X, 3);
            scan.add(POSITION_Y, 4);
        })).containsExactly(resolvingPower,
                "(position x|IMS:1000050|Double:7.0|␀)",
                "(position y|IMS:1000051|Long:4|␀)");
    }

    // ---- precursor and selected_ion structs ------------------------------------------------------------------

    @Test
    void theFixturePrecursorOfSpectrum5() {
        assertThat(Canonical.lines(fixtureDescription(5).precursors(), Canonical::precursor)).containsExactly(
                "(1|controllerType=0 controllerNumber=1 scan=2"
                        + "|(558.8689575195312|1.0|1.0|[])"
                        + "|[(558.868957519531|null|202177.84375|[])]"
                        + "|([(collision-induced dissociation|MS:1000133|null|␀)"
                        + ";(collision energy|MS:1000045|Double:35.0|UO:0000266)]))");
    }

    /**
     * Asymmetric isolation-window offsets keep their sides. Every fixture window is symmetric (1.0, 1.0), so swapping
     * the lower and upper offsets would otherwise go unnoticed; SplitPrecursorsTest has the split case.
     */
    @Test
    void asymmetricIsolationWindowOffsetsKeepTheirSides() {
        String lower = "MS_1000828_isolation_window_lower_offset";
        String upper = "MS_1000829_isolation_window_upper_offset";
        Group fixturePrecursor = fixtureStruct(PRECURSOR, 5);
        Group row = newRow();
        put(row, SPECTRUM, fixtureStruct(SPECTRUM, 5));
        Group precursor = put(row, PRECURSOR, fixturePrecursor, "isolation_window");
        Group window = precursor.addGroup("isolation_window");
        copy(fixturePrecursor.getGroup("isolation_window", 0), window, Set.of(lower, upper));
        addNumber(window, lower, 0.5);
        addNumber(window, upper, 1.5);

        assertThat(only(decode(List.of(row))).primaryPrecursor().isolationWindow())
                .isEqualTo(new IsolationWindow(558.8689575195312, 0.5, 1.5));
    }

    @Test
    void selectedIonsWithoutAPrecursorFormAPrecursorlessPrecursor() {
        Group row = newRow();
        put(row, SPECTRUM, fixtureStruct(SPECTRUM, 5));
        put(row, SELECTED_ION, fixtureStruct(SELECTED_ION, 5));

        assertThat(precursorLines(List.of(row))).containsExactly(
                "(null|␀|null|[(558.868957519531|null|202177.84375|[])]|([]))");
    }

    @Test
    void aSelectedIonWithoutMzIsDropped() {
        String mz = "MS_1000744_selected_ion_mz_unit_MS_1000040";
        Group withPrecursor = newRow();
        put(withPrecursor, SPECTRUM, fixtureStruct(SPECTRUM, 5));
        put(withPrecursor, PRECURSOR, fixtureStruct(PRECURSOR, 5));
        put(withPrecursor, SELECTED_ION, fixtureStruct(SELECTED_ION, 5), mz);
        assertThat(precursorLines(List.of(withPrecursor))).containsExactly(
                "(1|controllerType=0 controllerNumber=1 scan=2|(558.8689575195312|1.0|1.0|[])|[]"
                        + "|([(collision-induced dissociation|MS:1000133|null|␀)"
                        + ";(collision energy|MS:1000045|Double:35.0|UO:0000266)]))");

        Group withoutPrecursor = newRow();
        put(withoutPrecursor, SPECTRUM, fixtureStruct(SPECTRUM, 5));
        put(withoutPrecursor, SELECTED_ION, fixtureStruct(SELECTED_ION, 5), mz);
        assertThat(precursorLines(List.of(withoutPrecursor))).containsExactly("(null|␀|null|[]|([]))");
    }

    /**
     * RD-7's rule: a selected ion goes to the first precursor of its spectrum with an equal precursor_index,
     * whatever the row order. (Before RD-7 every selected ion went to the spectrum's first precursor.)
     */
    @Test
    void eachSelectedIonGoesToThePrecursorWithItsPrecursorIndex() {
        Group first = newRow();
        put(first, SPECTRUM, fixtureStruct(SPECTRUM, 5));
        put(first, PRECURSOR, fixtureStruct(PRECURSOR, 5));
        rekeyed(first, SELECTED_ION, 9, 5);
        Group second = newRow();
        rekeyed(second, PRECURSOR, 9, 5);
        put(second, SELECTED_ION, fixtureStruct(SELECTED_ION, 5));

        Precursor precursor5 = fixtureDescription(5).primaryPrecursor();
        Precursor precursor9 = fixtureDescription(9).primaryPrecursor();
        assertThat(precursor5.precursorIndex()).isEqualTo(1L);
        assertThat(precursor9.precursorIndex()).isEqualTo(8L);
        assertThat(ParquetGroups.optLong(fixtureStruct(SELECTED_ION, 5), "precursor_index")).isEqualTo(1L);
        assertThat(ParquetGroups.optLong(fixtureStruct(SELECTED_ION, 9), "precursor_index")).isEqualTo(8L);

        List<Precursor> precursors = only(decode(List.of(first, second))).precursors();

        assertThat(Canonical.lines(precursors, Canonical::precursor))
                .containsExactly(Canonical.precursor(precursor5), Canonical.precursor(precursor9));
    }

    @Test
    void absentPrecursorFieldsAndStructs() {
        Group bare = newRow();
        put(bare, SPECTRUM, fixtureStruct(SPECTRUM, 5));
        put(bare, PRECURSOR, fixtureStruct(PRECURSOR, 5),
                "precursor_index", "precursor_id", "isolation_window", "activation");
        put(bare, SELECTED_ION, fixtureStruct(SELECTED_ION, 5));
        assertThat(precursorLines(List.of(bare))).containsExactly(
                "(null|␀|null|[(558.868957519531|null|202177.84375|[])]|([]))");

        Group emptyStructs = newRow();
        put(emptyStructs, SPECTRUM, fixtureStruct(SPECTRUM, 5));
        Group precursor = put(emptyStructs, PRECURSOR, fixtureStruct(PRECURSOR, 5), "isolation_window", "activation");
        precursor.addGroup("isolation_window");
        precursor.addGroup("activation");
        assertThat(precursorLines(List.of(emptyStructs))).containsExactly(
                "(1|controllerType=0 controllerNumber=1 scan=2|(null|null|null|[])|[]|([]))");
    }

    // ---- shape dispatch --------------------------------------------------------------------------------------

    @Test
    void aPackedMemberWithoutRowsDecodesToNothing() {
        SpectrumMetadataDecoder.Decoded decoded = decode(List.of());
        assertThat(decoded.descriptions()).isEmpty();
        assertThat(decoded.deltaModels()).isEmpty();
    }

    /** RD-5: the split layout is decoded through the manifest, which names its facet members; never without it. */
    @Test
    void theSplitLayoutIsDecodedThroughTheManifestOnly() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current("small.unpacked.mzpeak"))) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            String member = manifest.find(EntityType.SPECTRUM, DataKind.METADATA).orElseThrow().name();
            assertThatThrownBy(() -> SpectrumMetadataDecoder.decode(source.inputFile(member)))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("spectrum metadata member: the split metadata layout keeps its scans, precursors"
                            + " and selected ions in members of their own; decode it through the manifest");
            SpectrumMetadataDecoder.Decoded decoded = SpectrumMetadataDecoder.decode(source, manifest);
            assertThat(decoded.descriptions()).hasSize(48);
            assertThat(decoded.deltaModels()).containsOnlyKeys(MS1);
            assertThat(decoded.deltaModels().get(0L)).hasSize(3);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static void assertDecodesLikeTheFixture(SpectrumMetadataDecoder.Decoded decoded) {
        assertThat(Canonical.lines(decoded.descriptions(), Canonical::describe))
                .containsExactlyElementsOf(Canonical.lines(fixture.descriptions(), Canonical::describe));
        assertThat(decoded.deltaModels()).containsOnlyKeys(fixture.deltaModels().keySet());
        fixture.deltaModels().forEach((index, model) ->
                assertThat(decoded.deltaModels().get(index)).as("mz_delta_model of %s", index).isEqualTo(model));
    }

    private static SpectrumDescription fixtureDescription(long index) {
        SpectrumDescription d = fixture.descriptions().get((int) index);
        assertThat(d.index()).isEqualTo(index);
        return d;
    }

    private static SpectrumDescription only(SpectrumMetadataDecoder.Decoded decoded) {
        assertThat(decoded.descriptions()).hasSize(1);
        return decoded.descriptions().get(0);
    }

    /** The params of the one scan of spectrum 0, built from fixture scan 0 with {@code edit} applied. */
    private List<String> scanParams(Consumer<Group> edit) {
        Group row = newRow();
        put(row, SPECTRUM, fixtureStruct(SPECTRUM, 0));
        edit.accept(put(row, SCAN, fixtureStruct(SCAN, 0)));
        List<ScanEvent> scans = only(decode(List.of(row))).scans();
        assertThat(scans).hasSize(1);
        return Canonical.lines(scans.get(0).parameters(), Canonical::param);
    }

    /** The precursors of the one spectrum {@code rows} decode to, as canonical lines. */
    private List<String> precursorLines(List<Group> rows) {
        return Canonical.lines(only(decode(rows)).precursors(), Canonical::precursor);
    }

    /** Writes {@code rows} as a packed spectrum metadata member and decodes it. */
    private SpectrumMetadataDecoder.Decoded decode(List<Group> rows) {
        Path file = tmp.resolve("spectra_metadata-" + (written++) + ".parquet");
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
        return SpectrumMetadataDecoder.decode(new LocalInputFile(file));
    }

    private static Group newRow() {
        return new SimpleGroup(schema);
    }

    /** Copies of every fixture row, in file order. */
    private static List<Group> copiesOfAllRows() {
        List<Group> rows = new ArrayList<>();
        for (int i = 0; i < fixtureRows.size(); i++) {
            Group row = newRow();
            for (String struct : STRUCTS) {
                putFromRow(row, struct, i);
            }
            rows.add(row);
        }
        return rows;
    }

    /** Copies struct {@code name} of fixture row {@code fixtureRow} into {@code row}, if that struct is present. */
    private static void putFromRow(Group row, String name, int fixtureRow) {
        Group struct = ParquetGroups.optGroup(fixtureRows.get(fixtureRow), name);
        if (struct != null) {
            put(row, name, struct);
        }
    }

    /** Adds a copy of {@code from} without the fields {@code omit} as struct {@code name} of {@code row}. */
    private static Group put(Group row, String name, Group from, String... omit) {
        Group struct = row.addGroup(name);
        copy(from, struct, Set.of(omit));
        return struct;
    }

    /** Adds a copy of the fixture struct {@code name} keyed {@code from} to {@code row}, keyed {@code key}. */
    private static void rekeyed(Group row, String name, long from, long key) {
        String keyField = SPECTRUM.equals(name) ? "index" : "source_index";
        put(row, name, fixtureStruct(name, from), keyField).add(keyField, key);
    }

    /** The first fixture struct {@code name} keyed {@code key}: a spectrum by index, a facet by source_index. */
    private static Group fixtureStruct(String name, long key) {
        String keyField = SPECTRUM.equals(name) ? "index" : "source_index";
        for (Group row : fixtureRows) {
            Group struct = ParquetGroups.optGroup(row, name);
            if (struct != null && Long.valueOf(key).equals(ParquetGroups.optLong(struct, keyField))) {
                return struct;
            }
        }
        throw new AssertionError("fixture has no " + name + " with " + keyField + " " + key);
    }

    /** Copies every value of {@code src} except the fields {@code omit} into {@code dst}, recursively. */
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

    /** Adds {@code value} as the numeric field {@code field} of {@code g}, in the field's physical type. */
    private static void addNumber(Group g, String field, double value) {
        PrimitiveTypeName type = g.getType().getType(field).asPrimitiveType().getPrimitiveTypeName();
        switch (type) {
            case FLOAT -> g.add(field, (float) value);
            case DOUBLE -> g.add(field, value);
            default -> throw new AssertionError("unexpected " + type + " field " + field);
        }
    }

    private static void addWindow(Group windows, Float lower, Float upper) {
        Group window = windows.addGroup("list").addGroup("item");
        if (lower != null) {
            window.add("MS_1000501_scan_window_lower_limit_unit_MS_1000040", lower);
        }
        if (upper != null) {
            window.add("MS_1000500_scan_window_upper_limit_unit_MS_1000040", upper);
        }
    }

    /** Appends a float-valued param to the {@code parameters} list of {@code struct}, which must be present. */
    private static void addParam(Group struct, String accession, String name, double value) {
        Group item = struct.getGroup("parameters", 0).addGroup("list").addGroup("item");
        item.addGroup("value").add("float", value);
        item.add("accession", accession);
        item.add("name", name);
    }

    /** {@code fixture} with the two imaging position columns appended to its scan struct. */
    private static MessageType withPositionColumns(MessageType fixture) {
        GroupType scan = fixture.getType(SCAN).asGroupType();
        List<Type> scanFields = new ArrayList<>(scan.getFields());
        scanFields.add(Types.optional(PrimitiveTypeName.INT32).named(POSITION_X));
        scanFields.add(Types.optional(PrimitiveTypeName.INT32).named(POSITION_Y));
        List<Type> fields = new ArrayList<>(fixture.getFields());
        fields.set(fixture.getFieldIndex(SCAN), scan.withNewFields(scanFields));
        return new MessageType(fixture.getName(), fields);
    }
}
