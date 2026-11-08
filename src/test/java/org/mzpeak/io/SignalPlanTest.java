package org.mzpeak.io;

import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ArrayIndex.BufferFormat;
import org.mzpeak.io.ArrayIndex.Source;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.SignalPlan.Column;
import org.mzpeak.io.SignalPlan.Extra;
import org.mzpeak.io.SignalPlan.Layout;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

/**
 * RD-21: {@link SignalPlan}s derived from the array index footers of the vendored HUPO-PSI fixtures (current set
 * 2ee2589, legacy set 29e59b2), from the INFERRED index of the same schemas and of mzPeakJ output, and from
 * constructed footers for the duplicate-key and main-axis rules of design §8.1.
 */
class SignalPlanTest {

    /** The signal roles: spectrum data and peaks, chromatogram data, wavelength spectrum data. */
    private static final List<Role> ROLES = List.of(
            new Role(EntityType.SPECTRUM, DataKind.DATA_ARRAYS, SignalEntity.SPECTRUM),
            new Role(EntityType.SPECTRUM, DataKind.PEAKS, SignalEntity.SPECTRUM),
            new Role(EntityType.CHROMATOGRAM, DataKind.DATA_ARRAYS, SignalEntity.CHROMATOGRAM),
            new Role(EntityType.WAVELENGTH_SPECTRUM, DataKind.DATA_ARRAYS, SignalEntity.WAVELENGTH_SPECTRUM));

    private record Role(EntityType entity, DataKind kind, SignalEntity signal) {
    }

    @TempDir
    Path tmp;

    private static SignalPlan footerPlan(Path dataset, Role role) {
        ParquetFooter f = ArrayIndexTest.footer(dataset, role.entity(), role.kind());
        return SignalPlan.of(ArrayIndex.of(f.keyValue(), f.schema(), role.signal()), f.schema(), role.signal());
    }

    private static SignalPlan footerPlan(Path dataset, EntityType entity, DataKind kind) {
        return footerPlan(dataset, ROLES.stream().filter(r -> r.entity() == entity && r.kind() == kind)
                .findFirst().orElseThrow());
    }

    private static SignalPlan plan(String json, MessageType schema, SignalEntity entity) {
        return SignalPlan.of(ArrayIndex.parse(json, entity), schema, entity);
    }

    // ---- (b) fixture plans -------------------------------------------------------------------------

    @Test
    void smallChunkedIntensityIsTheChunkSecondaryList() {
        SignalPlan plan = footerPlan(Fixtures.current("small.chunked.mzpeak"), EntityType.SPECTRUM,
                DataKind.DATA_ARRAYS);

        assertThat(plan.source()).isEqualTo(Source.FOOTER);
        assertThat(plan.layout()).isEqualTo(Layout.CHUNK);
        assertThat(plan.prefix()).isEqualTo("chunk");
        assertThat(plan.indexField()).isEqualTo("spectrum_index");
        assertThat(plan.intensities()).extracting(Column::path, Column::format, Column::unit)
                .containsExactly(tuple("chunk.intensity", BufferFormat.CHUNK_SECONDARY, "MS:1000131"));
        assertThat(plan.plainIntensity()).map(Column::path).contains("chunk.intensity");
        assertThat(plan.numpressIntensity()).isEmpty();
        assertThat(plan.axis().arrayType()).isEqualTo("MS:1000514");
        assertThat(plan.axis().values().path()).isEqualTo("chunk.mz_chunk_values");
        assertThat(plan.axis().start().field()).isEqualTo("mz_chunk_start");
        assertThat(plan.axis().end().field()).isEqualTo("mz_chunk_end");
        assertThat(plan.axis().encoding().field()).isEqualTo("chunk_encoding");
        assertThat(plan.axis().transformFields()).isEmpty();
        assertThat(plan.extras()).isEmpty();
    }

    @Test
    void diaPasefRefPeaksCarryTheIonMobilityExtra() {
        SignalPlan plan = footerPlan(Fixtures.current("diaPASEF.ref.mzpeak"), EntityType.SPECTRUM, DataKind.PEAKS);

        assertThat(plan.layout()).isEqualTo(Layout.POINT);
        assertThat(plan.axis().values().path()).isEqualTo("point.mz");
        assertThat(plan.intensities()).extracting(Column::path).containsExactly("point.intensity");
        assertThat(plan.extras()).containsOnlyKeys("MS:1003006");
        Extra im = plan.extras().get("MS:1003006");
        assertThat(im.values().path()).isEqualTo("point.mean_inverse_reduced_ion_mobility");
        assertThat(im.values().format()).isEqualTo(BufferFormat.POINT);
        assertThat(im.unit()).isEqualTo("MS:1002814");
        assertThat(im.transformFields()).isEmpty();
    }

    @Test
    void diaPasefGridPeaksMapTheGridTransforms() {
        SignalPlan plan = footerPlan(Fixtures.current("diaPASEF.grid.mzpeak"), EntityType.SPECTRUM,
                DataKind.PEAKS);

        assertThat(plan.layout()).isEqualTo(Layout.CHUNK);
        assertThat(plan.axis().transformFields()).containsExactly(entry("MS:1003826", "mz_grid"));
        assertThat(plan.axis().values().field()).isEqualTo("mz_chunk_values");
        assertThat(plan.plainIntensity()).map(Column::path).contains("chunk.intensity");
        assertThat(plan.extras()).containsOnlyKeys("MS:1003006");
        Extra im = plan.extras().get("MS:1003006");
        assertThat(im.values()).isNull();
        assertThat(im.transformFields())
                .containsExactly(entry("MS:1003826", "mean_inverse_reduced_ion_mobility_grid"));
    }

    @Test
    void smallChunkedChromatogramCarriesTheMsLevelArrayUnderItsName() {
        SignalPlan plan = footerPlan(Fixtures.current("small.chunked.mzpeak"), EntityType.CHROMATOGRAM,
                DataKind.DATA_ARRAYS);

        assertThat(plan.axis().arrayType()).isEqualTo("MS:1000595");
        assertThat(plan.axis().values().path()).isEqualTo("chunk.time_chunk_values");
        assertThat(plan.extras()).containsOnlyKeys("ms level");
        Extra msLevel = plan.extras().get("ms level");
        assertThat(msLevel.values().path()).isEqualTo("chunk.ms_level");
        assertThat(msLevel.values().format()).isEqualTo(BufferFormat.CHUNK_SECONDARY);
        assertThat(msLevel.arrayType()).isEqualTo("MS:1000786");
        assertThat(msLevel.arrayName()).isEqualTo("ms level");
        assertThat(msLevel.unit()).isEqualTo("UO:0000186");
    }

    @Test
    void hasUvChromatogramCarriesTheMsLevelArrayAndBothIntensities() {
        SignalPlan plan = footerPlan(Fixtures.current("has_uv.mzpeak"), EntityType.CHROMATOGRAM,
                DataKind.DATA_ARRAYS);

        assertThat(plan.extras()).containsOnlyKeys("ms level");
        assertThat(plan.extras().get("ms level").values().path()).isEqualTo("point.ms_level");
        assertThat(plan.intensities()).extracting(Column::path, Column::unit).containsExactly(
                tuple("point.intensity", "MS:1000131"),
                tuple("point.intensity_f32_au", "UO:0000269"));
    }

    @Test
    void smallNumpressPlansNameTheNumpressColumns() {
        SignalPlan plan = footerPlan(Fixtures.current("small.numpress.mzpeak"), EntityType.SPECTRUM,
                DataKind.DATA_ARRAYS);
        assertThat(plan.axis().transformFields()).containsExactly(entry("MS:1002312", "mz_numpress_linear_bytes"));
        assertThat(plan.numpressIntensity()).map(Column::field).contains("intensity_numpress_slof_bytes");
        assertThat(plan.plainIntensity()).isEmpty();

        SignalPlan chromatogram = footerPlan(Fixtures.current("small.numpress.mzpeak"), EntityType.CHROMATOGRAM,
                DataKind.DATA_ARRAYS);
        assertThat(chromatogram.numpressIntensity()).map(Column::field).contains("intensity_numpress_slof_bytes");
        assertThat(chromatogram.extras()).containsOnlyKeys("ms level");
    }

    // ---- (c) FOOTER and INFERRED plans agree ---------------------------------------------------------

    static Stream<Arguments> signalMembers() {
        List<Arguments> out = new ArrayList<>();
        for (String name : Fixtures.CURRENT_NAMES) {
            addMembers(out, "current", name, Fixtures.current(name));
        }
        for (String name : Fixtures.LEGACY_NAMES) {
            addMembers(out, "legacy", name, Fixtures.legacy(name));
        }
        return out.stream();
    }

    private static void addMembers(List<Arguments> out, String set, String name, Path dataset) {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            for (Role role : ROLES) {
                manifest.find(role.entity(), role.kind()).ifPresent(e -> out.add(Arguments.of(
                        set + " " + name + " " + e.name(), dataset, role)));
            }
        }
    }

    @Test
    void everyFixtureHasTheExpectedSignalMembers() {
        assertThat(signalMembers()).hasSize(8 * 2 + 7 + 1 + 5 * 2 + 5 + 1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("signalMembers")
    void footerAndInferredPlansAgree(String member, Path dataset, Role role) {
        ParquetFooter f = ArrayIndexTest.footer(dataset, role.entity(), role.kind());
        SignalPlan footer = SignalPlan.of(ArrayIndex.of(f.keyValue(), f.schema(), role.signal()), f.schema(),
                role.signal());
        SignalPlan inferred = SignalPlan.of(ArrayIndex.infer(f.schema(), role.signal()), f.schema(), role.signal());

        assertThat(footer.source()).isEqualTo(Source.FOOTER);
        assertThat(inferred.source()).isEqualTo(Source.INFERRED);
        assertThat(inferred.layout()).isEqualTo(footer.layout());
        assertThat(inferred.prefix()).isEqualTo(footer.prefix());
        assertThat(inferred.indexField()).isEqualTo(footer.indexField()).isEqualTo(role.signal().indexField());
        assertThat(inferred.axis().arrayType()).isEqualTo(footer.axis().arrayType())
                .isEqualTo(role.signal().defaultAxis());
        assertThat(columnShape(inferred.axis().values())).isEqualTo(columnShape(footer.axis().values()))
                .isNotNull();
        assertThat(columnShape(inferred.axis().start())).isEqualTo(columnShape(footer.axis().start()));
        assertThat(columnShape(inferred.axis().end())).isEqualTo(columnShape(footer.axis().end()));
        assertThat(columnShape(inferred.axis().encoding())).isEqualTo(columnShape(footer.axis().encoding()));
        // Numpress (and the m/z grid) on the axis; the secondary ion-mobility grid is an extra, not compared
        assertThat(inferred.axis().transformFields()).isEqualTo(footer.axis().transformFields());
        assertThat(inferred.intensities().stream().map(SignalPlanTest::columnShape).toList())
                .isEqualTo(footer.intensities().stream().map(SignalPlanTest::columnShape).toList())
                .isNotEmpty();
        assertThat(inferred.numpressIntensity().map(Column::field))
                .isEqualTo(footer.numpressIntensity().map(Column::field));
        assertThat(inferred.plainIntensity().map(Column::field)).isEqualTo(footer.plainIntensity().map(Column::field));
    }

    /** Path and format of a column, or null: what the decoders key on (units and null-marking aside). */
    private static String columnShape(Column c) {
        return c == null ? null : c.path() + " " + c.format()
                + (c.format() == BufferFormat.CHUNK_TRANSFORM ? " " + c.transform() : "");
    }

    // ---- (d) mzPeakJ output ------------------------------------------------------------------------

    @Test
    void mzPeakJOutputGivesInferredPlans() {
        List<Spectrum> spectra = new ArrayList<>();
        List<Chromatogram> chromatograms;
        List<WavelengthSpectrum> wavelength;
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.legacy("has_uv.mzpeak"))) {
            reader.iterator().forEachRemaining(spectra::add);
            chromatograms = reader.chromatograms();
            wavelength = reader.wavelengthSpectra();
        }
        assertThat(spectra).isNotEmpty();
        assertThat(chromatograms).isNotEmpty();
        assertThat(wavelength).isNotEmpty();
        // WR-3 writes the CENTROID peak copies of has_uv to spectra_peaks only; a PROFILE spectrum keeps spectra_data.
        Spectrum last = spectra.get(spectra.size() - 1);
        spectra.add(new Spectrum(new SpectrumDescription(last.index() + 1, "profile", 1, 1.0, null,
                SignalContinuity.PROFILE, 0, 0, List.of(), List.of(), List.of()), last.mz(), last.intensity(),
                List.of()));
        Path point = tmp.resolve("point.mzpeak");
        Path numpress = tmp.resolve("numpress.mzpeak");
        MzPeakWriter.writeDirectory(point, spectra, chromatograms, wavelength, List.of(), FileMetadata.EMPTY);
        MzPeakWriter.writeDirectoryNumpress(numpress, spectra, chromatograms, wavelength, List.of(),
                FileMetadata.EMPTY, MzPeakWriter.NumpressIntensityEncoding.SLOF);

        Map<String, SignalPlan> pointPlans = plansOf(point);
        Map<String, SignalPlan> numpressPlans = plansOf(numpress);
        assertThat(pointPlans).containsOnlyKeys("SPECTRUM/DATA_ARRAYS", "SPECTRUM/PEAKS",
                "CHROMATOGRAM/DATA_ARRAYS", "WAVELENGTH_SPECTRUM/DATA_ARRAYS");
        assertThat(numpressPlans).containsOnlyKeys(pointPlans.keySet());
        Stream.concat(pointPlans.values().stream(), numpressPlans.values().stream()).forEach(p -> {
            assertThat(p.source()).isEqualTo(Source.INFERRED);
            assertThat(p.indexField()).isEqualTo(p.entity().indexField());
            assertThat(p.axis().arrayType()).isEqualTo(p.entity().defaultAxis());
            assertThat(p.extras()).isEmpty();
        });

        SignalPlan spectrumPoint = pointPlans.get("SPECTRUM/DATA_ARRAYS");
        assertThat(spectrumPoint.layout()).isEqualTo(Layout.POINT);
        assertThat(spectrumPoint.axis().values().path()).isEqualTo("point.mz");
        assertThat(spectrumPoint.plainIntensity()).map(Column::path).contains("point.intensity");
        assertThat(pointPlans.get("CHROMATOGRAM/DATA_ARRAYS").axis().values().path()).isEqualTo("point.time");
        assertThat(pointPlans.get("WAVELENGTH_SPECTRUM/DATA_ARRAYS").axis().values().path())
                .isEqualTo("point.wavelength");

        for (String role : List.of("SPECTRUM/DATA_ARRAYS", "SPECTRUM/PEAKS")) {
            SignalPlan chunk = numpressPlans.get(role);
            assertThat(chunk.layout()).as(role).isEqualTo(Layout.CHUNK);
            assertThat(chunk.axis().values()).as(role).isNull();
            assertThat(chunk.axis().start().field()).isEqualTo("mz_chunk_start");
            assertThat(chunk.axis().end().field()).isEqualTo("mz_chunk_end");
            assertThat(chunk.axis().encoding().field()).isEqualTo("chunk_encoding");
            assertThat(chunk.axis().transformFields())
                    .containsExactly(entry("MS:1002312", "mz_numpress_linear_bytes"));
            assertThat(chunk.intensities()).extracting(Column::field, Column::transform).containsExactly(
                    tuple("intensity_numpress_slof_bytes", "MS:1002314"),
                    tuple("intensity_numpress_pic_bytes", "MS:1002313"));
            assertThat(chunk.numpressIntensity()).map(Column::field).contains("intensity_numpress_slof_bytes");
            assertThat(chunk.plainIntensity()).isEmpty();
        }
    }

    /** The plan of every signal member of {@code dataset}, keyed by role. */
    private static Map<String, SignalPlan> plansOf(Path dataset) {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            return ROLES.stream()
                    .filter(r -> manifest.find(r.entity(), r.kind()).isPresent())
                    .collect(Collectors.toMap(r -> r.entity() + "/" + r.kind(), r -> {
                        ParquetFooter f = ParquetFooter.read(
                                source.inputFile(manifest.find(r.entity(), r.kind()).orElseThrow().name()));
                        assertThat(f.keyValue()).doesNotContainKey(r.signal().arrayIndexKey());
                        return SignalPlan.of(ArrayIndex.of(f.keyValue(), f.schema(), r.signal()), f.schema(),
                                r.signal());
                    }));
        }
    }

    // ---- (e) duplicate keys and the main axis, on constructed footers ------------------------------

    private static final MessageType IM_SCHEMA = MessageTypeParser.parseMessageType("""
            message m {
              optional group point {
                optional int64 spectrum_index;
                optional double mz;
                optional float intensity;
                optional double im_a;
                optional float im_b;
                optional int64 ms_level;
                optional double mz_a;
                optional double mz_b;
              }
            }""");

    /** One index entry; {@code priority} and {@code rank} are written as JSON null when null. */
    private static String indexEntry(String path, String format, String arrayType, String priority,
                                     Integer rank) {
        return "{\"path\": \"" + path + "\", \"buffer_format\": \"" + format + "\", \"array_type\": \""
                + arrayType + "\", \"buffer_priority\": " + (priority == null ? "null" : "\"" + priority + "\"")
                + ", \"sorting_rank\": " + rank + ", \"context\": \"spectrum\"}";
    }

    private static String index(String prefix, String... entries) {
        return "{\"prefix\": \"" + prefix + "\", \"entries\": [" + String.join(", ", entries) + "]}";
    }

    private static String pointIndex(String... extras) {
        List<String> entries = new ArrayList<>(List.of(
                indexEntry("point.spectrum_index", "index", "MS:1003870", "primary", 0),
                indexEntry("point.mz", "point", "MS:1000514", "primary", 1),
                indexEntry("point.intensity", "point", "MS:1000515", "primary", null)));
        entries.addAll(List.of(extras));
        return index("point", entries.toArray(String[]::new));
    }

    private static Map<String, String> extraFields(SignalPlan plan) {
        return plan.extras().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                e -> e.getValue().values().field(), (a, b) -> a, LinkedHashMap::new));
    }

    @Test
    void sameTypeExtrasGetThePlainKeyForThePrimaryAndQualifiedKeysOtherwise() {
        SignalPlan plan = plan(pointIndex(
                indexEntry("point.im_a", "point", "MS:1003006", "primary", null),
                indexEntry("point.im_b", "point", "MS:1003006", "secondary", null)), IM_SCHEMA, SignalEntity.SPECTRUM);

        assertThat(extraFields(plan)).containsExactly(entry("MS:1003006", "im_a"), entry("MS:1003006@im_b", "im_b"));
        assertThat(plan.extras().get("MS:1003006@im_b").key()).isEqualTo("MS:1003006@im_b");
        assertThat(plan.extras().values()).extracting(Extra::arrayType).containsOnly("MS:1003006");
        assertThat(plan.axis().values().field()).isEqualTo("mz");
        assertThat(plan.intensities()).extracting(Column::field).containsExactly("intensity");
    }

    @Test
    void swappingThePrioritiesSwapsTheKeys() {
        SignalPlan plan = plan(pointIndex(
                indexEntry("point.im_a", "point", "MS:1003006", "secondary", null),
                indexEntry("point.im_b", "point", "MS:1003006", "primary", null)), IM_SCHEMA, SignalEntity.SPECTRUM);

        assertThat(extraFields(plan)).containsExactly(entry("MS:1003006@im_a", "im_a"), entry("MS:1003006", "im_b"));
    }

    @Test
    void withoutAPrimaryTheFirstEntryGetsThePlainKey() {
        SignalPlan plan = plan(pointIndex(
                indexEntry("point.im_a", "point", "MS:1003006", null, null),
                indexEntry("point.im_b", "point", "MS:1003006", "secondary", null)), IM_SCHEMA, SignalEntity.SPECTRUM);

        assertThat(extraFields(plan)).containsExactly(entry("MS:1003006", "im_a"), entry("MS:1003006@im_b", "im_b"));
    }

    @Test
    void sameTypeChunkSecondaryExtrasAreQualifiedToo() {
        MessageType schema = MessageTypeParser.parseMessageType("""
                message m {
                  optional group chunk {
                    optional int64 spectrum_index;
                    optional double mz_chunk_start;
                    optional group mz_chunk_values (LIST) { repeated group list { optional double item; } }
                    optional group intensity (LIST) { repeated group list { optional float item; } }
                    optional group im_a (LIST) { repeated group list { optional double item; } }
                    optional group im_b (LIST) { repeated group list { optional float item; } }
                  }
                }""");
        SignalPlan plan = plan(index("chunk",
                indexEntry("chunk.spectrum_index", "index", "MS:1003870", "primary", 0),
                indexEntry("chunk.mz_chunk_start", "chunk_start", "MS:1000514", "primary", 1),
                indexEntry("chunk.mz_chunk_values", "chunk_values", "MS:1000514", "primary", 1),
                indexEntry("chunk.intensity", "secondary_chunk", "MS:1000515", "primary", null),
                indexEntry("chunk.im_a", "chunk_secondary", "MS:1003006", "secondary", null),
                indexEntry("chunk.im_b", "chunk_secondary", "MS:1003006", "primary", null)),
                schema, SignalEntity.SPECTRUM);

        assertThat(plan.layout()).isEqualTo(Layout.CHUNK);
        assertThat(plan.plainIntensity()).map(Column::format).contains(BufferFormat.CHUNK_SECONDARY);
        assertThat(extraFields(plan)).containsExactly(entry("MS:1003006@im_a", "im_a"), entry("MS:1003006", "im_b"));
    }

    @Test
    void twoValueBearingMainAxisEntriesThrowNamingBoth() {
        assertThatThrownBy(() -> plan(index("point",
                indexEntry("point.spectrum_index", "index", "MS:1003870", "primary", 0),
                indexEntry("point.mz_a", "point", "MS:1000514", "primary", 1),
                indexEntry("point.intensity", "point", "MS:1000515", "primary", null),
                indexEntry("point.mz_b", "point", "MS:1000514", "secondary", 1)), IM_SCHEMA, SignalEntity.SPECTRUM))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("point.mz_a")
                .hasMessageContaining("point.mz_b")
                .hasMessageContaining("MS:1000514");
    }

    @Test
    void aMainAxisOtherThanTheEntityDefaultThrows() {
        assertThatThrownBy(() -> plan(pointIndex(indexEntry("point.im_a", "point", "MS:1003006", "primary", 0)),
                IM_SCHEMA, SignalEntity.SPECTRUM))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("MS:1003006")
                .hasMessageContaining("MS:1000514");
        // a spectrum index read as a chromatogram one: m/z is not the time axis
        assertThatThrownBy(() -> SignalPlan.of(ArrayIndex.parse(pointIndex(), SignalEntity.CHROMATOGRAM),
                IM_SCHEMA, SignalEntity.CHROMATOGRAM))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("MS:1000595");
    }

    @Test
    void withoutRanksTheEntityDefaultIsTheMainAxisAndWithoutIndexEntryTheDefaultIndexField() {
        SignalPlan plan = plan(index("point",
                indexEntry("point.intensity", "point", "MS:1000515", "primary", null),
                indexEntry("point.mz", "point", "MS:1000514", "primary", null),
                indexEntry("point.im_a", "point", "MS:1003006", "primary", null)), IM_SCHEMA, SignalEntity.SPECTRUM);

        assertThat(plan.indexField()).isEqualTo("spectrum_index");
        assertThat(plan.axis().arrayType()).isEqualTo("MS:1000514");
        assertThat(plan.axis().values().field()).isEqualTo("mz");
        assertThat(plan.extras()).containsOnlyKeys("MS:1003006");
    }

    @Test
    void nonStandardArraysAreKeyedByArrayName() {
        String named = "{\"path\": \"point.ms_level\", \"buffer_format\": \"point\", \"array_type\": \"MS:1000786\","
                + " \"array_name\": \"ms level\", \"buffer_priority\": \"primary\"}";
        String unnamed = "{\"path\": \"point.im_a\", \"buffer_format\": \"point\", \"array_type\": \"MS:1000786\"}";

        assertThat(extraFields(plan(pointIndex(named, unnamed), IM_SCHEMA, SignalEntity.SPECTRUM)))
                .containsExactly(entry("ms level", "ms_level"), entry("MS:1000786", "im_a"));
    }

    @Test
    void anExtraChunkTransformJoinsTheArrayWithTheSameDataTypeAndUnit() {
        MessageType schema = MessageTypeParser.parseMessageType("""
                message m {
                  optional group chunk {
                    optional int64 spectrum_index;
                    optional group mz_chunk_values (LIST) { repeated group list { optional double item; } }
                    optional group intensity (LIST) { repeated group list { optional float item; } }
                    optional group im_a (LIST) { repeated group list { optional double item; } }
                    optional group im_b (LIST) { repeated group list { optional float item; } }
                    optional group im_b_grid { required binary grid_type (STRING); }
                  }
                }""");
        String imA = "{\"path\": \"chunk.im_a\", \"buffer_format\": \"chunk_secondary\","
                + " \"array_type\": \"MS:1003006\", \"data_type\": \"MS:1000523\", \"unit\": \"MS:1002814\","
                + " \"buffer_priority\": \"primary\"}";
        String imB = "{\"path\": \"chunk.im_b\", \"buffer_format\": \"chunk_secondary\","
                + " \"array_type\": \"MS:1003006\", \"data_type\": \"MS:1000521\", \"unit\": \"MS:1002814\","
                + " \"buffer_priority\": \"secondary\"}";
        String grid = "{\"path\": \"chunk.im_b_grid\", \"buffer_format\": \"chunk_transform\","
                + " \"array_type\": \"MS:1003006\", \"data_type\": \"MS:1000521\", \"unit\": \"MS:1002814\","
                + " \"transform\": \"MS:1003826\"}";

        SignalPlan plan = plan(index("chunk",
                indexEntry("chunk.spectrum_index", "index", "MS:1003870", "primary", 0),
                indexEntry("chunk.mz_chunk_values", "chunk_values", "MS:1000514", "primary", 1),
                indexEntry("chunk.intensity", "chunk_secondary", "MS:1000515", "primary", null),
                grid, imA, imB), schema, SignalEntity.SPECTRUM);

        assertThat(plan.extras()).containsOnlyKeys("MS:1003006@im_b", "MS:1003006");
        assertThat(plan.extras().get("MS:1003006@im_b").transformFields())
                .containsExactly(entry("MS:1003826", "im_b_grid"));
        assertThat(plan.extras().get("MS:1003006@im_b").values().field()).isEqualTo("im_b");
        assertThat(plan.extras().get("MS:1003006").transformFields()).isEmpty();
        assertThat(plan.extras().get("MS:1003006").values().field()).isEqualTo("im_a");
    }

    @Test
    void entriesWithoutAColumnAreSkippedWithAWarning() {
        List<LogRecord> records = ArrayIndexTest.captureLog(SignalPlan.class, () -> {
            SignalPlan plan = plan(pointIndex(
                    indexEntry("point.missing_im", "point", "MS:1003006", "primary", null),
                    indexEntry("point.im_a", "point", "MS:1003006", "secondary", null)),
                    IM_SCHEMA, SignalEntity.SPECTRUM);
            assertThat(extraFields(plan)).containsExactly(entry("MS:1003006", "im_a"));
        });
        assertThat(records).filteredOn(r -> r.getLevel() == Level.WARNING).singleElement()
                .satisfies(r -> assertThat(r.getMessage()).contains("spectrum_array_index", "point.missing_im"));
    }

    // ---- FN-1: footers that name missing core columns or disagree with their prefix -------------------

    private static final MessageType CHUNK_SCHEMA = MessageTypeParser.parseMessageType("""
            message m {
              optional group chunk {
                optional int64 spectrum_index;
                optional double mz_chunk_start;
                optional group mz_chunk_values (LIST) { repeated group list { optional double item; } }
                optional binary chunk_encoding (STRING);
                optional group intensity (LIST) { repeated group list { optional float item; } }
                optional group im_grid { required binary grid_type (STRING); }
              }
            }""");

    @Test
    void aFooterNamingAMissingMainAxisValuesColumnThrows() {
        assertThatThrownBy(() -> plan(index("point",
                indexEntry("point.spectrum_index", "index", "MS:1003870", "primary", 0),
                indexEntry("point.m_z", "point", "MS:1000514", "primary", 1),
                indexEntry("point.intensity", "point", "MS:1000515", "primary", null)),
                IM_SCHEMA, SignalEntity.SPECTRUM))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("spectrum_array_index (FOOTER) lists point.m_z (MS:1000514, point), which is not a column"
                        + " of the member");
    }

    @Test
    void aFooterNamingAMissingChunkEncodingColumnThrows() {
        // the member stores its encoding in chunk.chunk_encoding; without the throw an MS:1000576 chunk decodes as
        // deltas ([100, 201, 303] instead of [100, 101, 102])
        assertThatThrownBy(() -> plan(index("chunk",
                indexEntry("chunk.spectrum_index", "index", "MS:1003870", "primary", 0),
                indexEntry("chunk.mz_chunk_start", "chunk_start", "MS:1000514", "primary", 1),
                indexEntry("chunk.mz_chunk_values", "chunk_values", "MS:1000514", "primary", 1),
                indexEntry("chunk.encoding", "chunk_encoding", "MS:1000514", "primary", 1),
                indexEntry("chunk.intensity", "chunk_secondary", "MS:1000515", "primary", null)),
                CHUNK_SCHEMA, SignalEntity.SPECTRUM))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("spectrum_array_index (FOOTER) lists chunk.encoding (MS:1000514, chunk_encoding), which"
                        + " is not a column of the member");
    }

    @Test
    void aFooterNamingAMissingIntensityColumnThrows() {
        assertThatThrownBy(() -> plan(index("point",
                indexEntry("point.spectrum_index", "index", "MS:1003870", "primary", 0),
                indexEntry("point.mz", "point", "MS:1000514", "primary", 1),
                indexEntry("point.intensity_f64", "point", "MS:1000515", "primary", null)),
                IM_SCHEMA, SignalEntity.SPECTRUM))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("spectrum_array_index (FOOTER) lists point.intensity_f64 (MS:1000515, point), which is"
                        + " not a column of the member");
    }

    @Test
    void aChromatogramFooterNamingAMissingTimeColumnThrows() {
        MessageType schema = MessageTypeParser.parseMessageType("""
                message m {
                  optional group point {
                    optional int64 chromatogram_index;
                    optional double time;
                    optional float intensity;
                  }
                }""");
        assertThatThrownBy(() -> SignalPlan.of(ArrayIndex.parse(index("point",
                indexEntry("point.chromatogram_index", "index", "MS:1003870", "primary", 0),
                indexEntry("point.time_s", "point", "MS:1000595", "primary", 1),
                indexEntry("point.intensity", "point", "MS:1000515", "primary", null)), SignalEntity.CHROMATOGRAM),
                schema, SignalEntity.CHROMATOGRAM))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("chromatogram_array_index (FOOTER) lists point.time_s (MS:1000595, point), which is not"
                        + " a column of the member");
    }

    @Test
    void aPathOutsideThePrefixThrows() {
        assertThatThrownBy(() -> plan(pointIndex(indexEntry("chunk.im_a", "point", "MS:1003006", "primary", null)),
                IM_SCHEMA, SignalEntity.SPECTRUM))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("spectrum_array_index (FOOTER) lists chunk.im_a, which is not under its prefix point");
    }

    @Test
    void thePointPrefixWithAChunkFormatThrows() {
        String grid = "{\"path\": \"point.im_a\", \"buffer_format\": \"chunk_transform\","
                + " \"array_type\": \"MS:1003006\", \"transform\": \"MS:1003826\"}";
        assertThatThrownBy(() -> plan(pointIndex(grid), IM_SCHEMA, SignalEntity.SPECTRUM))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("spectrum_array_index (FOOTER) lists point.im_a with the chunk buffer format"
                        + " chunk_transform under the prefix point");
    }

    @Test
    void theChunkPrefixWithThePointFormatThrows() {
        assertThatThrownBy(() -> plan(index("chunk",
                indexEntry("chunk.spectrum_index", "index", "MS:1003870", "primary", 0),
                indexEntry("chunk.mz_chunk_values", "point", "MS:1000514", "primary", 1),
                indexEntry("chunk.intensity", "point", "MS:1000515", "primary", null)),
                CHUNK_SCHEMA, SignalEntity.SPECTRUM))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("spectrum_array_index (FOOTER) lists chunk.mz_chunk_values with the point buffer format"
                        + " under the prefix chunk");
    }

    @Test
    void aChunkTransformWithoutATransformCreatesNoExtra() {
        String grid = "{\"path\": \"chunk.im_grid\", \"buffer_format\": \"chunk_transform\","
                + " \"array_type\": \"MS:1003006\", \"transform\": null}";
        List<LogRecord> records = ArrayIndexTest.captureLog(SignalPlan.class, () -> {
            SignalPlan plan = plan(index("chunk",
                    indexEntry("chunk.spectrum_index", "index", "MS:1003870", "primary", 0),
                    indexEntry("chunk.mz_chunk_start", "chunk_start", "MS:1000514", "primary", 1),
                    indexEntry("chunk.mz_chunk_values", "chunk_values", "MS:1000514", "primary", 1),
                    indexEntry("chunk.intensity", "chunk_secondary", "MS:1000515", "primary", null),
                    grid), CHUNK_SCHEMA, SignalEntity.SPECTRUM);
            assertThat(plan.extras()).isEmpty();
        });
        assertThat(records).filteredOn(r -> r.getLevel() == Level.WARNING).singleElement()
                .satisfies(r -> assertThat(r.getMessage()).contains("chunk.im_grid", "names no transform"));
    }

    @Test
    void planCollectionsAreUnmodifiable() {
        SignalPlan plan = plan(pointIndex(indexEntry("point.im_a", "point", "MS:1003006", "primary", null)),
                IM_SCHEMA, SignalEntity.SPECTRUM);
        assertThatThrownBy(() -> plan.intensities().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.extras().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.axis().transformFields().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.extras().get("MS:1003006").transformFields().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
