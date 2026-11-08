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
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.GoldenMeta.Row;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.Param;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MZ-4: {@link WavelengthSpectrum#parameters()}. has_uv's 520 UV spectra carry a spectrum type, a representation,
 * the lowest and highest observed wavelength and a spectrum title, which the reader used to drop and the writer used
 * to write as an empty list. The reader takes a row's {@code parameters} list first, then, in the split layout, the
 * leftover columns whose accession is not already there (a term marker as {@code Param(null, CURIE)}); in the packed
 * layout the struct's list. The split writer writes them into the {@code parameters} column; the packed writer, whose
 * schema is frozen, does not, and warns once. The cv-list prefix scan covers them (design §7).
 */
class WavelengthParamsTest {

    private static final String HAS_UV = "has_uv.mzpeak";
    private static final String METADATA = "wavelength_spectra_metadata.parquet";
    private static final String TITLE = "MS:1000796";
    private static final String TITLE0 = "TOFsulfasMS4GHzDualMode+DADSpectra+UVSignal272-NoProfile.213.213."
            + " File:\"TOFsulfasMS4GHzDualMode+DADSpectra+UVSignal272-NoProfile.d\", NativeID:\"merged=212 row=0\"";
    /** The warning a packed write of wavelength spectra with parameters logs. */
    private static final String PACKED_WARNING = "which the packed metadata layout does not write";
    /** The columns the decoder reads into the header, not into Params, besides the structural ones. */
    private static final Set<String> CONSUMED = Set.of("time", "lambda_max", "number_of_data_points");
    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
    private static final Options PACKED = Options.defaults().withMetadataLayout(MetadataLayout.PACKED);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    // ---- (a) and (b): has_uv through the reader -------------------------------------------------------------

    /**
     * Spectrum 0 of current has_uv: its four list entries, then the leftovers of the split columns, the
     * representation term marker and the base peak intensity and TIC (FLOAT columns, which the Rust writer derived
     * from the signal). The spectrum type, lowest and highest wavelength columns repeat list accessions and are
     * dropped, so no accession occurs twice.
     */
    @Test
    void hasUvSpectrum0CarriesItsListThenTheLeftoverColumns() {
        WavelengthSpectrum w0 = wavelengthSpectra(Fixtures.current(HAS_UV)).get(0);
        assertThat(w0.parameters()).containsExactly(
                new Param("electromagnetic radiation spectrum", "MS:1000804", null, null),
                new Param("lowest observed wavelength", "MS:1000619", 210.0, "UO:0000018"),
                new Param("highest observed wavelength", "MS:1000618", 400.0, "UO:0000018"),
                new Param("spectrum title", TITLE, TITLE0, null),
                new Param(null, "MS:1000128", null, null),
                new Param("base peak intensity", "MS:1000505", 0.09584426879882812, "MS:1000131"),
                new Param("total ion current", "MS:1000285", -1.1401176452636719, "MS:1000131"));
        // Both summary values are float32 cells, widened exactly.
        for (Param p : w0.parameters().subList(5, 7)) {
            double v = (Double) p.value();
            assertThat((double) (float) v).as(p.name()).isEqualTo(v);
        }
        assertThat(w0.parameters()).extracting(Param::accession).doesNotHaveDuplicates();
    }

    /**
     * Every wavelength spectrum of current has_uv has its title, and its Params are what the raw pyarrow cells
     * (FX-2) give by the rule: the list entries, then each leftover cell, in column order, whose accession is new.
     */
    @Test
    void everyHasUvSpectrumCarriesItsTitleAndTheParamsOfItsCells() {
        List<WavelengthSpectrum> all = wavelengthSpectra(Fixtures.current(HAS_UV));
        Entry entry;
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current(HAS_UV))) {
            entry = Facet.WAVELENGTH_SPECTRUM.entry(MzPeakManifest.fromSource(source)).orElseThrow();
        }
        List<Row> rows = GoldenMeta.load(HAS_UV).rows(entry.name());
        assertThat(all).hasSize(520).hasSameSizeAs(rows);
        for (int i = 0; i < all.size(); i++) {
            WavelengthSpectrum w = all.get(i);
            assertThat(w.parameters()).as("spectrum %d", i).containsExactlyElementsOf(expected(rows.get(i), entry));
            assertThat(w.parameters()).as("spectrum %d", i).filteredOn(p -> TITLE.equals(p.accession()))
                    .singleElement().satisfies(p -> {
                        assertThat(p.name()).isEqualTo("spectrum title");
                        assertThat((String) p.value()).endsWith("NativeID:\"" + w.id() + "\"");
                    });
            assertThat(w.parameters()).as("spectrum %d", i).extracting(Param::accession).doesNotHaveDuplicates();
        }
    }

    /** Legacy (packed) has_uv gives the struct's list only: the current spectra's Params without the leftovers. */
    @Test
    void legacyPackedHasUvGivesTheStructsParameters() {
        List<WavelengthSpectrum> current = wavelengthSpectra(Fixtures.current(HAS_UV));
        List<WavelengthSpectrum> legacy = wavelengthSpectra(Fixtures.legacy(HAS_UV));
        assertThat(legacy).hasSameSizeAs(current);
        for (int i = 0; i < legacy.size(); i++) {
            List<Param> c = current.get(i).parameters();
            assertThat(legacy.get(i).parameters()).as("spectrum %d", i).hasSize(4)
                    .containsExactlyElementsOf(c.subList(0, 4));
        }
    }

    // ---- (c): written and read again ------------------------------------------------------------------------

    /** The split writer writes every Param into the parameters column; reading it back gives them in order. */
    @Test
    void splitWriteKeepsEveryParamInOrder() {
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current(HAS_UV));
        Path out = tmp.resolve("split.mzpeak");
        List<LogRecord> warnings = packedWarnings(() -> MzPeakWriter.writeDirectory(out, source.spectra,
                source.chromatograms, source.wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT));
        assertThat(warnings).isEmpty();

        List<WavelengthSpectrum> back = wavelengthSpectra(out);
        assertThat(back).hasSameSizeAs(source.wavelengthSpectra);
        for (int i = 0; i < back.size(); i++) {
            List<Param> expected = source.wavelengthSpectra.get(i).parameters();
            assertThat(expected).as("source spectrum %d", i).hasSize(7);
            assertThat(back.get(i).parameters()).as("spectrum %d", i)
                    .containsExactlyInAnyOrderElementsOf(expected)
                    .isEqualTo(expected);
        }
        // The member itself: the Params are list entries, not columns; the row has no other Param-bearing column.
        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(out.resolve(METADATA), rows::add);
        assertThat(ParquetGroups.readParams(rows.get(0), "parameters"))
                .isEqualTo(source.wavelengthSpectra.get(0).parameters());
        assertThat(rows.get(0).getType().getFields()).extracting(t -> t.getName())
                .containsExactly("index", "id", "time", "number_of_data_points", "lambda_max", "parameters");
    }

    /** The packed schema has no parameters column: one warning with the counts, and the Params read back empty. */
    @Test
    void packedWriteWarnsOnceAndReadsBackEmptyParams() {
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current(HAS_UV));
        Path out = tmp.resolve("packed.mzpeak");
        List<LogRecord> warnings = packedWarnings(() -> MzPeakWriter.writeDirectory(out, source.spectra,
                source.chromatograms, source.wavelengthSpectra, source.scanSettings, source.fileMetadata, PACKED));
        assertThat(warnings).extracting(LogRecord::getMessage).containsExactly(
                "520 wavelength spectra carry parameters (3640 in all), which the packed metadata layout does not"
                        + " write");

        List<WavelengthSpectrum> back = wavelengthSpectra(out);
        assertThat(back).hasSize(520).allSatisfy(w -> assertThat(w.parameters()).isEmpty());
    }

    /** One wavelength spectrum with one Param: the warning is in the singular; none without Params. */
    @Test
    void packedWarningCountsInTheSingularAndNotWithoutParams() {
        WavelengthSpectrum bare = new WavelengthSpectrum(0, "w0", 0.5, null, new double[] {200.0},
                new double[] {1.0});
        WavelengthSpectrum titled = new WavelengthSpectrum(1, "w1", 0.6, null, new double[] {200.0},
                new double[] {2.0}, List.of(new Param("spectrum title", TITLE, "w1", null)));
        assertThat(planWarnings(List.of(bare, titled), PACKED)).containsExactly(
                "1 wavelength spectrum carries parameters (1 in all), which the packed metadata layout does not"
                        + " write");
        assertThat(planWarnings(List.of(bare), PACKED)).isEmpty();
        assertThat(planWarnings(List.of(bare, titled), SPLIT)).isEmpty();
    }

    // ---- (d): the model -------------------------------------------------------------------------------------

    @Test
    void theSixArgumentConstructorAndANullListGiveEmptyParams() {
        double[] wavelength = {200.0, 210.0};
        double[] intensity = {0.5, 0.25};
        WavelengthSpectrum six = new WavelengthSpectrum(3, "w3", 1.5, 254.0, wavelength, intensity);
        WavelengthSpectrum nullList = new WavelengthSpectrum(3, "w3", 1.5, 254.0, wavelength, intensity, null);
        assertThat(six.parameters()).isEmpty();
        assertThat(nullList.parameters()).isEmpty();
        assertThat(six).isEqualTo(nullList).hasSameHashCodeAs(nullList);
        assertThat(six.index()).isEqualTo(3);
        assertThat(six.lambdaMax()).isEqualTo(254.0);
        assertThat(six.wavelength()).isSameAs(wavelength);
    }

    @Test
    void theParamsAreCopiedAndUnmodifiableAndRecordEqualityCoversThem() {
        double[] wavelength = {200.0};
        double[] intensity = {0.5};
        Param title = new Param("spectrum title", TITLE, "t", null);
        List<Param> params = new ArrayList<>(List.of(title));
        WavelengthSpectrum w = new WavelengthSpectrum(0, "w0", 1.0, null, wavelength, intensity, params);
        params.add(new Param("other", null, 1L, null));
        assertThat(w.parameters()).containsExactly(title);
        assertThatThrownBy(() -> w.parameters().add(title)).isInstanceOf(UnsupportedOperationException.class);

        WavelengthSpectrum same = new WavelengthSpectrum(0, "w0", 1.0, null, wavelength, intensity, List.of(title));
        WavelengthSpectrum other = new WavelengthSpectrum(0, "w0", 1.0, null, wavelength, intensity,
                List.of(new Param("spectrum title", TITLE, "u", null)));
        WavelengthSpectrum none = new WavelengthSpectrum(0, "w0", 1.0, null, wavelength, intensity);
        assertThat(w).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(w).isNotEqualTo(other).isNotEqualTo(none);
    }

    // ---- (f): the cv-list prefix scan ------------------------------------------------------------------------

    /**
     * A BTO accession on has_uv's first wavelength spectrum, and an NCIT unit on its second, are declared in
     * metadata.cv_list of a split write; has_uv uses neither CV elsewhere, so a write without them declares neither.
     */
    @Test
    void aCvUsedOnlyInAWavelengthParamIsDeclared() throws IOException {
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current(HAS_UV));
        Path plain = tmp.resolve("plain.mzpeak");
        MzPeakWriter.writeDirectory(plain, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, SPLIT);
        assertThat(cvIds(plain)).doesNotContain("BTO", "NCIT");

        Param brain = new Param("brain", "BTO:0000142", null, null);
        Param mass = new Param("sample mass", "MS:1000004", 1.5, "NCIT:C48505");
        List<WavelengthSpectrum> waves = new ArrayList<>(source.wavelengthSpectra);
        waves.set(0, withParam(waves.get(0), brain));
        waves.set(1, withParam(waves.get(1), mass));
        Path out = tmp.resolve("bto.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, waves, source.scanSettings,
                source.fileMetadata, SPLIT);

        assertThat(cvIds(out)).contains("BTO", "NCIT");
        assertThat(WriterCvListTest.assertEveryWrittenPrefixIsDeclared(out)).containsEntry("BTO", "BTO:0000142")
                .containsEntry("NCIT", "NCIT:C48505");
        List<WavelengthSpectrum> back = wavelengthSpectra(out);
        assertThat(back.get(0).parameters()).endsWith(brain);
        assertThat(back.get(1).parameters()).endsWith(mass);
    }

    // ---- constructed split tables ---------------------------------------------------------------------------

    /**
     * The merge rule on a constructed split row: the list (a user param included) first, then the leftovers in
     * column order. A leftover whose accession the list or an earlier leftover has is dropped; an unmapped column has
     * no accession and is kept. The time, lambda max and point count columns are header fields, not Params.
     */
    @Test
    void leftoversFollowTheListAndRepeatNoAccession() {
        MessageType schema = Types.buildMessage()
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("index")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("id")
                .optional(PrimitiveTypeName.DOUBLE).named("time")
                .optional(PrimitiveTypeName.DOUBLE).named("lowest_observed_wavelength")
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false))
                .named("number_of_data_points")
                .optional(PrimitiveTypeName.DOUBLE).named("lambda_max")
                .optional(PrimitiveTypeName.DOUBLE).named("bpi_a")
                .optional(PrimitiveTypeName.DOUBLE).named("bpi_b")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("comment")
                .addField(ParquetOutput.paramListField())
                .named("arrow_schema");
        Param user = new Param("operator", null, "x", null);
        Param lowest = new Param("lowest observed wavelength", "MS:1000619", 190.0, "UO:0000018");
        Group row = new SimpleGroupFactory(schema).newGroup().append("index", 0L).append("id", "w0")
                .append("time", 0.5).append("lowest_observed_wavelength", 210.0)
                .append("number_of_data_points", 96L).append("lambda_max", 374.0).append("bpi_a", 1.5)
                .append("bpi_b", 2.5).append("comment", "note");
        ParquetOutput.writeParams(row, List.of(user, lowest));
        write(schema, row);
        Entry entry = new Entry(METADATA, "wavelength_spectrum", "metadata", List.of(
                new ColumnMapping("base peak intensity", "bpi_a", "MS:1000505", "MS:1000131", null),
                new ColumnMapping("base peak intensity", "bpi_b", "MS:1000505", "MS:1000131", null)),
                List.of(), null);
        try (MzPeakSource source = MzPeakSource.open(tmp)) {
            assertThat(WavelengthMetadataDecoder.decode(source.inputFile(METADATA), entry)).containsExactly(
                    new WavelengthMetadataDecoder.Header(0, "w0", 0.5, 374.0, List.of(user, lowest,
                            new Param("base peak intensity", "MS:1000505", 1.5, "MS:1000131"),
                            new Param("comment", null, "note", null))));
        }
    }

    /** A split row without a parameters list or leftover columns has no Params. */
    @Test
    void aBareSplitRowHasNoParams() {
        MessageType schema = Types.buildMessage()
                .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("index")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("id")
                .named("arrow_schema");
        write(schema, new SimpleGroupFactory(schema).newGroup().append("index", 0L).append("id", "w0"));
        Entry entry = new Entry(METADATA, "wavelength_spectrum", "metadata");
        try (MzPeakSource source = MzPeakSource.open(tmp)) {
            assertThat(WavelengthMetadataDecoder.decode(source.inputFile(METADATA), entry)).containsExactly(
                    new WavelengthMetadataDecoder.Header(0, "w0", Double.NaN, null, List.of()));
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private static List<WavelengthSpectrum> wavelengthSpectra(Path dataset) {
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            return reader.wavelengthSpectra();
        }
    }

    /**
     * The Params the raw cells of {@code row} give: the list entries, then each non-null, non-structural cell that
     * the decoder does not consume, in column order, unless its accession came before: a term marker as
     * {@code Param(null, cell)}, a mapped cell with the mapping's name, accession and unit.
     */
    private static List<Param> expected(Row row, Entry entry) {
        List<Param> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : row.list("parameters")) {
            Map<?, ?> cell = (Map<?, ?>) item;
            Map<?, ?> value = (Map<?, ?>) cell.get("value");
            Object v = null;
            for (String arm : List.of("integer", "float", "string", "boolean")) {
                if (v == null && value != null) {
                    v = value.get(arm);
                }
            }
            Param p = new Param((String) cell.get("name"), (String) cell.get("accession"), v,
                    (String) cell.get("unit"));
            out.add(p);
            seen.add(p.accession());
        }
        for (Map.Entry<String, Object> cell : row.values().entrySet()) {
            String field = cell.getKey();
            if (cell.getValue() == null || cell.getValue() instanceof List || cell.getValue() instanceof Map
                    || ColumnTable.structural(Facet.WAVELENGTH_SPECTRUM).contains(field)
                    || CONSUMED.contains(field)) {
                continue;
            }
            ColumnMapping m = entry.columnAtPath(field).orElseThrow();
            Param p = Boolean.TRUE.equals(m.termMarker())
                    ? new Param(null, (String) cell.getValue(), null, null)
                    : new Param(m.name(), m.accession(), cell.getValue(), m.unit());
            if (seen.add(p.accession())) {
                out.add(p);
            }
        }
        return out;
    }

    /** The WARNING records about wavelength-spectrum Params that {@link MzPeakWriter} logs while {@code write} runs. */
    private static List<LogRecord> packedWarnings(Runnable write) {
        return ArrayIndexTest.captureLog(MzPeakWriter.class, write).stream()
                .filter(r -> r.getLevel() == Level.WARNING && r.getMessage().contains(PACKED_WARNING)).toList();
    }

    /** The planner's warnings about wavelength-spectrum Params for a write of {@code waves} alone. */
    private static List<String> planWarnings(List<WavelengthSpectrum> waves, Options options) {
        return WritePlanner.plan(List.of(), List.of(), waves, List.of(), org.mzpeak.model.meta.FileMetadata.EMPTY,
                options).report().warnings().stream().filter(w -> w.contains(PACKED_WARNING)).toList();
    }

    private static WavelengthSpectrum withParam(WavelengthSpectrum w, Param param) {
        List<Param> params = new ArrayList<>(w.parameters());
        params.add(param);
        return new WavelengthSpectrum(w.index(), w.id(), w.time(), w.lambdaMax(), w.wavelength(), w.intensity(),
                params);
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
