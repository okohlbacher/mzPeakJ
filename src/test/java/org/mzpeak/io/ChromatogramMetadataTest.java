package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.GoldenMeta.Row;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.Activation;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MZ-5: {@link Chromatogram#polarity()}, {@link Chromatogram#dataProcessingRef()}, {@link Chromatogram#parameters()}
 * and {@link Chromatogram#precursors()}. mzML chromatograms carry a polarity, Params, a data-processing reference and,
 * for SRM, a precursor; the chromatogram decoder used to read only the index, id and type, and the split writer wrote
 * a null {@code scan_polarity} and an empty {@code parameters} list. The reader now takes {@code scan_polarity}
 * through {@link Polarity#fromCode} (0 is unknown), {@code data_processing_ref}, else Rust's
 * {@code data_processing_id}, and the {@code parameters} list followed, in the split layout, by the leftover columns
 * whose accession is new; precursors stay empty (MZ-6). The split writer writes the polarity, the reference (spec
 * spelling, design Q18) and the Params, and warns once about precursors; the packed writer, whose schema is frozen,
 * writes none of them and warns once. The cv-list prefix scan covers the Params (design §7).
 *
 * <p>Oracle: the vendored HUPO-PSI fixtures; the raw cells of the current ones through {@link GoldenMeta}.
 */
class ChromatogramMetadataTest {

    private static final String HAS_UV = "has_uv.mzpeak";
    private static final String METADATA = "chromatograms_metadata.parquet";
    private static final String PRECURSORS = "chromatograms_metadata_precursors.parquet";
    private static final String DATA_PROCESSING = "pwiz_Reader_conversion";
    private static final Param SOURCE_PARAM = new Param("source", null, "has_uv", null);
    private static final Param MS_LEVEL = new Param("ms level", "MS:1000511", 1L, null);
    /** Part of the warning about what the packed layout does not write of chromatograms. */
    private static final String PACKED_WARNING = "or precursors of a chromatogram";
    /** Part of the warning about the chromatogram precursors, which no layout writes yet. */
    private static final String PRECURSOR_WARNING = "which are not written yet";
    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
    private static final Options PACKED = Options.defaults().withMetadataLayout(MetadataLayout.PACKED);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    // ---- (a) written in the split layout and read again --------------------------------------------------------

    /**
     * has_uv's TIC with polarity POSITIVE, the data processing {@code pwiz_Reader_conversion} and two Params, a user
     * param and an MS:1000511 cv param: a split write reads back equal, without a warning. The row holds
     * {@code scan_polarity} 1, the reference and the Params as list entries; the DAD chromatogram has none of them.
     */
    @Test
    void aSplitWriteKeepsThePolarityTheDataProcessingRefAndTheParams() {
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current(HAS_UV));
        Chromatogram tic = withMetadata(source.chromatograms.get(0), Polarity.POSITIVE, DATA_PROCESSING,
                List.of(SOURCE_PARAM, MS_LEVEL), List.of());
        Chromatogram dad = source.chromatograms.get(1);
        Path out = tmp.resolve("split.mzpeak");
        List<String> warnings = chromatogramWarnings(() -> MzPeakWriter.writeDirectory(out, source.spectra,
                List.of(tic, dad), source.wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT));
        assertThat(warnings).isEmpty();

        List<Chromatogram> back = chromatograms(out);
        assertThat(back).hasSize(2);
        assertChromatogramEqual(tic, back.get(0));
        assertChromatogramEqual(dad, back.get(1));
        assertThat(back.get(0).polarity()).isEqualTo(Polarity.POSITIVE);
        assertThat(back.get(0).dataProcessingRef()).isEqualTo(DATA_PROCESSING);
        assertThat(back.get(0).parameters()).containsExactly(SOURCE_PARAM, MS_LEVEL);
        assertThat(back.get(0).parameters().get(1).value()).isInstanceOf(Long.class);
        assertThat(back.get(1).polarity()).isEqualTo(Polarity.UNKNOWN);
        assertThat(back.get(1).dataProcessingRef()).isNull();
        assertThat(back.get(1).parameters()).isEmpty();

        List<Group> rows = rows(out.resolve(METADATA));
        assertThat(rows.get(0).getType().getFields()).extracting(t -> t.getName()).containsExactly("index", "id",
                "scan_polarity", "chromatogram_type", "data_processing_ref", "number_of_data_points", "parameters");
        assertThat(rows.get(0).getInteger("scan_polarity", 0)).isEqualTo(1);
        assertThat(rows.get(0).getString("data_processing_ref", 0)).isEqualTo(DATA_PROCESSING);
        assertThat(ParquetGroups.readParams(rows.get(0), "parameters")).containsExactly(SOURCE_PARAM, MS_LEVEL);
        assertThat(ParquetGroups.has(rows.get(1), "scan_polarity")).isFalse();
        assertThat(ParquetGroups.has(rows.get(1), "data_processing_ref")).isFalse();
        assertThat(ParquetGroups.readParams(rows.get(1), "parameters")).isEmpty();
    }

    /**
     * A negative polarity is written as -1 and an unknown one as null; without a data processing reference the
     * member has no {@code data_processing_ref} column, as before MZ-5.
     */
    @Test
    void aNegativePolarityIsMinusOneAndNoReferenceMeansNoColumn() {
        List<Chromatogram> chromatograms = List.of(
                new Chromatogram(0, "TIC", "MS:1000235", new double[] {1.0}, new double[] {10.0}, null, Map.of(),
                        Polarity.NEGATIVE, null, List.of(), List.of()),
                new Chromatogram(1, "BPC", "MS:1000628", new double[] {1.0}, new double[] {5.0}));
        Path out = tmp.resolve("negative.mzpeak");
        writeChromatograms(out, chromatograms, SPLIT);

        List<Group> rows = rows(out.resolve(METADATA));
        assertThat(rows.get(0).getType().getFields()).extracting(t -> t.getName()).containsExactly("index", "id",
                "scan_polarity", "chromatogram_type", "number_of_data_points", "parameters");
        assertThat(rows.get(0).getInteger("scan_polarity", 0)).isEqualTo(-1);
        assertThat(ParquetGroups.has(rows.get(1), "scan_polarity")).isFalse();
        assertThat(chromatograms(out)).extracting(Chromatogram::polarity)
                .containsExactly(Polarity.NEGATIVE, Polarity.UNKNOWN);
    }

    // ---- (b) the fixtures --------------------------------------------------------------------------------------

    /**
     * The chromatograms of current has_uv, small and diaPASEF.ref read polarity UNKNOWN, no data processing
     * reference and no Params, since their {@code scan_polarity} cells are 0, their {@code data_processing_id} cells
     * null and their {@code parameters} lists empty; they have no other Param-bearing column.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"has_uv.mzpeak", "small.mzpeak", "diaPASEF.ref.mzpeak"})
    void theCurrentFixturesReadUnknownPolarityNoReferenceAndNoParams(String fixture) {
        Entry entry;
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current(fixture))) {
            entry = Facet.CHROMATOGRAM.entry(MzPeakManifest.fromSource(source)).orElseThrow();
        }
        List<Row> cells = GoldenMeta.load(fixture).rows(entry.name());
        assertThat(cells).isNotEmpty().allSatisfy(row -> {
            assertThat(row.get("scan_polarity")).isEqualTo(0L);
            assertThat(row.get("data_processing_id")).isNull();
            assertThat(row.list("parameters")).isEmpty();
            assertThat(row.values().keySet()).containsExactly("index", "id", "scan_polarity", "chromatogram_type",
                    "data_processing_id", "number_of_data_points", "parameters", "number_of_auxiliary_arrays");
        });

        List<Chromatogram> all = chromatograms(Fixtures.current(fixture));
        assertThat(all).hasSameSizeAs(cells).allSatisfy(ChromatogramMetadataTest::assertDefaults);
    }

    /** The legacy packed structs have the polarity, reference and Params columns too, all null or empty. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"has_uv.mzpeak", "small.mzpeak"})
    void theLegacyFixturesReadUnknownPolarityNoReferenceAndNoParams(String fixture) {
        List<Chromatogram> all = chromatograms(Fixtures.legacy(fixture));
        assertThat(all).isNotEmpty().allSatisfy(ChromatogramMetadataTest::assertDefaults);
    }

    // ---- (c) the packed layout ---------------------------------------------------------------------------------

    /**
     * The packed chromatogram schema is frozen with index, id, type and point count: a packed write of (a) warns once,
     * with the counts, and the TIC reads back with polarity UNKNOWN, no reference and no Params.
     */
    @Test
    void aPackedWriteWarnsOnceAndReadsBackTheDefaults() {
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current(HAS_UV));
        Chromatogram tic = withMetadata(source.chromatograms.get(0), Polarity.POSITIVE, DATA_PROCESSING,
                List.of(SOURCE_PARAM, MS_LEVEL), List.of());
        Path out = tmp.resolve("packed.mzpeak");
        List<String> warnings = chromatogramWarnings(() -> MzPeakWriter.writeDirectory(out, source.spectra,
                List.of(tic, source.chromatograms.get(1)), source.wavelengthSpectra, source.scanSettings,
                source.fileMetadata, PACKED));
        assertThat(warnings).containsExactly("the packed metadata layout writes no polarity, data processing"
                + " reference, parameters or precursors of a chromatogram: 1 polarity, 1 data processing reference,"
                + " 2 parameters and 0 precursors are not written");

        List<Chromatogram> back = chromatograms(out);
        assertThat(back).hasSize(2).allSatisfy(ChromatogramMetadataTest::assertDefaults);
        assertThat(back.get(0).time()).isEqualTo(tic.time());
        assertThat(back.get(0).intensity()).isEqualTo(tic.intensity());
    }

    /** The packed warning counts every kind, in the singular or plural, and is not given when nothing is lost. */
    @Test
    void thePackedWarningCountsEachKind() {
        Precursor precursor = new Precursor(null, null, null, List.of(), null);
        Chromatogram negative = chromatogram(0, Polarity.NEGATIVE, null, List.of(), List.of());
        Chromatogram referenced = chromatogram(1, null, "dp1", List.of(SOURCE_PARAM), List.of());
        Chromatogram srm = chromatogram(2, null, null, List.of(), List.of(precursor, precursor));
        Chromatogram plain = chromatogram(3, null, null, List.of(), List.of());
        assertThat(planWarnings(List.of(negative, referenced, srm, plain), PACKED)).containsExactly(
                "the packed metadata layout writes no polarity, data processing reference, parameters or precursors"
                        + " of a chromatogram: 1 polarity, 1 data processing reference, 1 parameter and 2 precursors"
                        + " are not written");
        assertThat(planWarnings(List.of(negative, chromatogram(1, Polarity.POSITIVE, "dp1", null, null)), PACKED))
                .containsExactly("the packed metadata layout writes no polarity, data processing reference,"
                        + " parameters or precursors of a chromatogram: 2 polarities, 1 data processing reference,"
                        + " 0 parameters and 0 precursors are not written");
        assertThat(planWarnings(List.of(srm), PACKED)).singleElement().asString()
                .endsWith(": 0 polarities, 0 data processing references, 0 parameters and 2 precursors are not"
                        + " written");
        assertThat(planWarnings(List.of(plain), PACKED)).isEmpty();
        assertThat(planWarnings(List.of(), PACKED)).isEmpty();
    }

    // ---- (d) the model and the precursors ----------------------------------------------------------------------

    @Test
    void theOldConstructorsGiveTheDefaults() {
        double[] time = {1.0, 2.0};
        double[] intensity = {10.0, 20.0};
        Chromatogram five = new Chromatogram(0, "TIC", "MS:1000235", time, intensity);
        Chromatogram seven = new Chromatogram(0, "TIC", "MS:1000235", time, intensity, null, Map.of());
        Chromatogram nulls = new Chromatogram(0, "TIC", "MS:1000235", time, intensity, null, null, null, null, null,
                null);
        for (Chromatogram c : List.of(five, seven, nulls)) {
            assertDefaults(c);
            assertThat(c.time()).isSameAs(time);
            assertThat(c.intensityUnit()).isNull();
            assertThat(c.arrays()).isEmpty();
        }
        assertThat(five).isEqualTo(seven).isEqualTo(nulls).hasSameHashCodeAs(nulls);
    }

    @Test
    void recordEqualityCoversTheNewComponents() {
        double[] time = {1.0};
        double[] intensity = {2.0};
        Precursor precursor = new Precursor(null, "scan=1", null, List.of(), null);
        Chromatogram c = new Chromatogram(0, "SRM", "MS:1001473", time, intensity, null, Map.of(),
                Polarity.POSITIVE, "dp", List.of(SOURCE_PARAM), List.of(precursor));
        Chromatogram same = new Chromatogram(0, "SRM", "MS:1001473", time, intensity, null, Map.of(),
                Polarity.POSITIVE, "dp", List.of(SOURCE_PARAM), List.of(precursor));
        assertThat(c).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(c).isNotEqualTo(new Chromatogram(0, "SRM", "MS:1001473", time, intensity, null, Map.of(),
                Polarity.NEGATIVE, "dp", List.of(SOURCE_PARAM), List.of(precursor)));
        assertThat(c).isNotEqualTo(new Chromatogram(0, "SRM", "MS:1001473", time, intensity, null, Map.of(),
                Polarity.POSITIVE, null, List.of(SOURCE_PARAM), List.of(precursor)));
        assertThat(c).isNotEqualTo(new Chromatogram(0, "SRM", "MS:1001473", time, intensity, null, Map.of(),
                Polarity.POSITIVE, "dp", List.of(MS_LEVEL), List.of(precursor)));
        assertThat(c).isNotEqualTo(new Chromatogram(0, "SRM", "MS:1001473", time, intensity, null, Map.of(),
                Polarity.POSITIVE, "dp", List.of(SOURCE_PARAM), List.of()));
        assertThat(c).isNotEqualTo(new Chromatogram(0, "SRM", "MS:1001473", time, intensity));
        assertThat(c.toString()).contains("polarity=POSITIVE", "dataProcessingRef=dp", "precursorId=scan=1");
    }

    @Test
    void theListsAreCopiedAndUnmodifiable() {
        Precursor precursor = new Precursor(null, "scan=1", null, List.of(), null);
        List<Param> params = new ArrayList<>(List.of(SOURCE_PARAM));
        List<Precursor> precursors = new ArrayList<>(List.of(precursor));
        Chromatogram c = chromatogram(0, Polarity.POSITIVE, null, params, precursors);
        params.add(MS_LEVEL);
        precursors.clear();
        assertThat(c.parameters()).containsExactly(SOURCE_PARAM);
        assertThat(c.precursors()).containsExactly(precursor);
        assertThatThrownBy(() -> c.parameters().add(MS_LEVEL)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> c.precursors().clear()).isInstanceOf(UnsupportedOperationException.class);
        List<Param> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> chromatogram(0, null, null, withNull, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * A chromatogram precursor (SRM: Q1 isolation window, selected ion, activation) is not written yet: a split
     * write warns once, with the count, writes no precursor row and reads the chromatogram back without it, its
     * other new components kept.
     */
    @Test
    void aChromatogramWithAPrecursorWarnsOnceInSplitAndReadsBackWithoutIt() {
        Precursor precursor = new Precursor(null, "controllerType=0 controllerNumber=1 scan=1",
                new IsolationWindow(445.3, 0.5, 0.5),
                List.of(new SelectedIon(445.3, 1, null, List.of())),
                new Activation(List.of(new Param("collision-induced dissociation", "MS:1000133", null, null))));
        Chromatogram srm = new Chromatogram(0, "SRM SIC 445.3,300.1", "MS:1001473", new double[] {0.5, 1.0},
                new double[] {100.0, 200.0}, null, Map.of(), Polarity.POSITIVE, null, List.of(MS_LEVEL),
                List.of(precursor));
        Path out = tmp.resolve("srm.mzpeak");
        List<String> warnings = chromatogramWarnings(() -> writeChromatograms(out, List.of(srm), SPLIT));
        assertThat(warnings).containsExactly("1 chromatogram carries precursors (1 in all), which are not written"
                + " yet");

        assertThat(rows(out.resolve(PRECURSORS))).isEmpty();
        Chromatogram back = chromatograms(out).get(0);
        assertThat(back.precursors()).isEmpty();
        assertChromatogramEqual(withMetadata(srm, srm.polarity(), null, srm.parameters(), List.of()), back);
    }

    /** The split warning is in the plural for several chromatograms and is not given without precursors. */
    @Test
    void theSplitPrecursorWarningCountsInThePlural() {
        Precursor precursor = new Precursor(null, null, null, List.of(), null);
        Chromatogram one = chromatogram(0, null, null, List.of(), List.of(precursor));
        Chromatogram two = chromatogram(1, null, null, List.of(), List.of(precursor, precursor));
        Chromatogram full = chromatogram(2, Polarity.POSITIVE, "dp", List.of(SOURCE_PARAM), List.of());
        assertThat(planWarnings(List.of(one, two, full), SPLIT))
                .containsExactly("2 chromatograms carry precursors (3 in all), which are not written yet");
        assertThat(planWarnings(List.of(full), SPLIT)).isEmpty();
    }

    // ---- (f) the cv-list prefix scan ---------------------------------------------------------------------------

    /**
     * A BTO accession, which has_uv uses nowhere else, on a chromatogram param of a split write is declared in
     * metadata.cv_list; a write without it does not declare BTO.
     */
    @Test
    void aCvUsedOnlyInAChromatogramParamIsDeclared() throws IOException {
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current(HAS_UV));
        Path plain = tmp.resolve("plain.mzpeak");
        MzPeakWriter.writeDirectory(plain, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, SPLIT);
        assertThat(cvIds(plain)).doesNotContain("BTO");

        Param brain = new Param("brain", "BTO:0000142", null, null);
        Chromatogram tic = withMetadata(source.chromatograms.get(0), Polarity.UNKNOWN, null, List.of(brain),
                List.of());
        Path out = tmp.resolve("bto.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, List.of(tic, source.chromatograms.get(1)),
                source.wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT);

        assertThat(cvIds(out)).contains("BTO");
        assertThat(WriterCvListTest.assertEveryWrittenPrefixIsDeclared(out)).containsEntry("BTO", "BTO:0000142");
        assertThat(chromatograms(out).get(0).parameters()).containsExactly(brain);
    }

    // ---- constructed tables ------------------------------------------------------------------------------------

    /** {@code scan_polarity} 1, -1, 0 and null give POSITIVE, NEGATIVE, UNKNOWN and UNKNOWN. */
    @Test
    void thePolarityCodesOfASplitTable() {
        MessageType schema = Types.buildMessage()
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("index")
                .optional(PrimitiveTypeName.INT32).as(LogicalTypeAnnotation.intType(8, true)).named("scan_polarity")
                .named("arrow_schema");
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        write(schema, rows.newGroup().append("index", 0L).append("scan_polarity", 1),
                rows.newGroup().append("index", 1L).append("scan_polarity", -1),
                rows.newGroup().append("index", 2L).append("scan_polarity", 0),
                rows.newGroup().append("index", 3L));
        assertThat(decode(new Entry(METADATA, "chromatogram", "metadata")))
                .extracting(ChromatogramMetadataDecoder.Header::polarity)
                .containsExactly(Polarity.POSITIVE, Polarity.NEGATIVE, Polarity.UNKNOWN, Polarity.UNKNOWN);
    }

    /**
     * The reference is the {@code data_processing_ref} cell, else the {@code data_processing_id} cell, as Rust
     * spells the column; neither is a Param.
     */
    @Test
    void theDataProcessingRefThenTheRustSpelling() {
        MessageType schema = Types.buildMessage()
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("index")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                .named("data_processing_ref")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("data_processing_id")
                .named("arrow_schema");
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        write(schema,
                rows.newGroup().append("index", 0L).append("data_processing_ref", "spec")
                        .append("data_processing_id", "rust"),
                rows.newGroup().append("index", 1L).append("data_processing_id", "rust"),
                rows.newGroup().append("index", 2L).append("data_processing_ref", "spec"),
                rows.newGroup().append("index", 3L));
        List<ChromatogramMetadataDecoder.Header> headers = decode(new Entry(METADATA, "chromatogram", "metadata"));
        assertThat(headers).extracting(ChromatogramMetadataDecoder.Header::dataProcessingRef)
                .containsExactly("spec", "rust", "spec", null);
        assertThat(headers).allSatisfy(h -> assertThat(h.parameters()).isEmpty());
    }

    /**
     * The merge rule on a constructed split row: the list (a user param included) first, then the leftovers in
     * column order. A leftover whose accession the list or an earlier leftover has is dropped; an unmapped column has
     * no accession and is kept. The id, type, polarity and point count columns are header fields, not Params.
     */
    @Test
    void leftoversFollowTheListAndRepeatNoAccession() {
        MessageType schema = Types.buildMessage()
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("index")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("id")
                .optional(PrimitiveTypeName.INT32).as(LogicalTypeAnnotation.intType(8, true)).named("scan_polarity")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("chromatogram_type")
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false))
                .named("number_of_data_points")
                .optional(PrimitiveTypeName.INT32).named("level")
                .optional(PrimitiveTypeName.DOUBLE).named("tic_a")
                .optional(PrimitiveTypeName.DOUBLE).named("tic_b")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("comment")
                .addField(ParquetOutput.paramListField())
                .named("arrow_schema");
        Param listedLevel = new Param("ms level", "MS:1000511", 2L, null);
        Group row = new SimpleGroupFactory(schema).newGroup().append("index", 0L).append("id", "TIC")
                .append("scan_polarity", -1).append("chromatogram_type", "MS:1000235")
                .append("number_of_data_points", 3L).append("level", 1).append("tic_a", 1.5).append("tic_b", 2.5)
                .append("comment", "note");
        ParquetOutput.writeParams(row, List.of(SOURCE_PARAM, listedLevel));
        write(schema, row);
        Entry entry = new Entry(METADATA, "chromatogram", "metadata", List.of(
                new ColumnMapping("ms level", "level", "MS:1000511", null, null),
                new ColumnMapping("total ion current", "tic_a", "MS:1000285", "MS:1000131", null),
                new ColumnMapping("total ion current", "tic_b", "MS:1000285", "MS:1000131", null)),
                List.of(), null);
        assertThat(decode(entry)).containsExactly(new ChromatogramMetadataDecoder.Header(0, "TIC", "MS:1000235",
                Polarity.NEGATIVE, null, List.of(SOURCE_PARAM, listedLevel,
                        new Param("total ion current", "MS:1000285", 1.5, "MS:1000131"),
                        new Param("comment", null, "note", null))));
    }

    /**
     * The pin-era packed struct gives its polarity through the fallback name {@code MS_1000465_scan_polarity}, its
     * {@code data_processing_ref} and its {@code parameters} list; the packed layout has no leftovers.
     */
    @Test
    void aPackedStructGivesItsPolarityReferenceAndParams() {
        MessageType schema = Types.buildMessage()
                .addField(Types.optionalGroup()
                        .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("index")
                        .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("id")
                        .optional(PrimitiveTypeName.INT32).as(LogicalTypeAnnotation.intType(8, true))
                        .named("MS_1000465_scan_polarity")
                        .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                        .named("MS_1000626_chromatogram_type")
                        .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                        .named("data_processing_ref")
                        .optional(PrimitiveTypeName.DOUBLE).named("comment_value")
                        .addField(ParquetOutput.paramListField())
                        .named("chromatogram"))
                .named("arrow_schema");
        Group row = new SimpleGroupFactory(schema).newGroup();
        Group chromatogram = row.addGroup("chromatogram").append("index", 0L).append("id", "TIC")
                .append("MS_1000465_scan_polarity", 1).append("MS_1000626_chromatogram_type", "MS:1000235")
                .append("data_processing_ref", "dp1").append("comment_value", 2.0);
        ParquetOutput.writeParams(chromatogram, List.of(SOURCE_PARAM));
        write(schema, row);
        assertThat(decode(new Entry(METADATA, "chromatogram", "metadata"))).containsExactly(
                new ChromatogramMetadataDecoder.Header(0, "TIC", "MS:1000235", Polarity.POSITIVE, "dp1",
                        List.of(SOURCE_PARAM)));
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /** Every component of {@code b} equals that of {@code a}; the arrays by value. */
    private static void assertChromatogramEqual(Chromatogram a, Chromatogram b) {
        assertThat(b.index()).as("%s index", a.id()).isEqualTo(a.index());
        assertThat(b.id()).isEqualTo(a.id());
        assertThat(b.typeCurie()).as("%s type", a.id()).isEqualTo(a.typeCurie());
        assertThat(b.time()).as("%s time", a.id()).isEqualTo(a.time());
        assertThat(b.intensity()).as("%s intensity", a.id()).isEqualTo(a.intensity());
        assertThat(b.intensityUnit()).as("%s unit", a.id()).isEqualTo(a.intensityUnit());
        assertThat(b.arrays().keySet()).as("%s array keys", a.id()).containsExactlyElementsOf(a.arrays().keySet());
        a.arrays().forEach((key, values) -> assertThat(b.arrays().get(key)).as("%s %s", a.id(), key)
                .isEqualTo(values));
        assertThat(b.polarity()).as("%s polarity", a.id()).isEqualTo(a.polarity());
        assertThat(b.dataProcessingRef()).as("%s data processing ref", a.id()).isEqualTo(a.dataProcessingRef());
        assertThat(b.parameters()).as("%s parameters", a.id()).isEqualTo(a.parameters());
        assertThat(b.precursors()).as("%s precursors", a.id()).isEqualTo(a.precursors());
    }

    /** {@code c} has unknown polarity, no data processing reference, no Params and no precursors. */
    private static void assertDefaults(Chromatogram c) {
        assertThat(c.polarity()).as("%s polarity", c.id()).isEqualTo(Polarity.UNKNOWN);
        assertThat(c.dataProcessingRef()).as("%s data processing ref", c.id()).isNull();
        assertThat(c.parameters()).as("%s parameters", c.id()).isEmpty();
        assertThat(c.precursors()).as("%s precursors", c.id()).isEmpty();
    }

    /** {@code c} with the given polarity, reference, Params and precursors. */
    private static Chromatogram withMetadata(Chromatogram c, Polarity polarity, String dataProcessingRef,
                                             List<Param> parameters, List<Precursor> precursors) {
        return new Chromatogram(c.index(), c.id(), c.typeCurie(), c.time(), c.intensity(), c.intensityUnit(),
                c.arrays(), polarity, dataProcessingRef, parameters, precursors);
    }

    /** A one-point TIC numbered {@code index} with the given polarity, reference, Params and precursors. */
    private static Chromatogram chromatogram(long index, Polarity polarity, String dataProcessingRef,
                                             List<Param> parameters, List<Precursor> precursors) {
        return new Chromatogram(index, "c" + index, "MS:1000235", new double[] {1.0}, new double[] {2.0}, null,
                Map.of(), polarity, dataProcessingRef, parameters, precursors);
    }

    private static List<Chromatogram> chromatograms(Path dataset) {
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            return reader.chromatograms();
        }
    }

    /** Writes {@code chromatograms} with one MS1 spectrum of current small to {@code out}. */
    private static void writeChromatograms(Path out, List<Chromatogram> chromatograms, Options options) {
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current("small.mzpeak"));
        MzPeakWriter.writeDirectory(out, source.spectra.subList(0, 1), chromatograms, List.of(), List.of(),
                FileMetadata.EMPTY, options);
    }

    /** The WARNING messages about chromatograms that {@link MzPeakWriter} logs while {@code write} runs. */
    private static List<String> chromatogramWarnings(Runnable write) {
        return ArrayIndexTest.captureLog(MzPeakWriter.class, write).stream()
                .filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage)
                .filter(m -> m.contains(PACKED_WARNING) || m.contains(PRECURSOR_WARNING)).toList();
    }

    /** The planner's warnings about chromatograms for a write of {@code chromatograms} alone. */
    private static List<String> planWarnings(List<Chromatogram> chromatograms, Options options) {
        return WritePlanner.plan(List.of(), chromatograms, List.of(), List.of(), FileMetadata.EMPTY, options)
                .report().warnings().stream()
                .filter(m -> m.contains(PACKED_WARNING) || m.contains(PRECURSOR_WARNING)).toList();
    }

    private static List<Group> rows(Path member) {
        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(member, rows::add);
        return rows;
    }

    /** The ids of the written dataset's metadata.cv_list, in order. */
    private static List<String> cvIds(Path dataset) throws IOException {
        JsonNode index = MAPPER.readTree(Files.readAllBytes(dataset.resolve(ManifestWriter.INDEX)));
        List<String> ids = new ArrayList<>();
        for (JsonNode cv : index.path("metadata").path("cv_list")) {
            ids.add(cv.path("id").asText());
        }
        return ids;
    }

    /** The headers of the member {@link #METADATA} of the temporary dataset directory, listed as {@code entry}. */
    private List<ChromatogramMetadataDecoder.Header> decode(Entry entry) {
        try (MzPeakSource source = MzPeakSource.open(tmp)) {
            return ChromatogramMetadataDecoder.decode(source.inputFile(METADATA), entry);
        }
    }

    /** Writes {@code rows} as the member {@link #METADATA} of the temporary dataset directory. */
    private void write(MessageType schema, Group... rows) {
        Path file = tmp.resolve(METADATA);
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
    }
}
