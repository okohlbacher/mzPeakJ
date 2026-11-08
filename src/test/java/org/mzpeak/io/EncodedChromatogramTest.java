package org.mzpeak.io;

import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * AP-5: {@link MzPeakReader#encodedTic()} and {@link MzPeakReader#encodedBpc()} derive a TIC and a BPC from the
 * MS:1000285 and MS:1000505 columns of the spectrum metadata, paired with the retention times in index order, and
 * {@link MzPeakReader#tic()} and {@link MzPeakReader#bpc()} prefer a stored chromatogram of the type (design section
 * 10). The stored chromatograms stay as they are: there is no implicit index 0/1 fallback.
 *
 * <p>Oracles: the values of the reference tests (Rust reader.rs test_tic) and the spectrum metadata cells pyarrow
 * reads ({@link GoldenMeta}) for the current fixtures, the current values for the legacy (packed) ones, and the stored
 * chromatograms. Constructed members cover null values, non-finite retention times, several row groups, rows out of
 * index order, placeholder rows and integer columns, which no fixture has.
 */
class EncodedChromatogramTest {

    private static final String TIC = "MS:1000235";
    private static final String BPC = "MS:1000628";
    private static final String TOTAL_ION_CURRENT = "MS:1000285";
    private static final String BASE_PEAK_INTENSITY = "MS:1000505";

    @TempDir
    Path tmp;

    static Stream<String> smallFixtures() {
        return Stream.of("small.mzpeak", "small.chunked.mzpeak", "small.numpress.mzpeak", "small.unpacked.mzpeak");
    }

    static Stream<String> currentFixtures() {
        return Fixtures.CURRENT_NAMES.stream();
    }

    // ---- (a) current small -------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void currentSmallEncodedTicPairsTheTotalIonCurrentWithTheRetentionTimes(String fixture) {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            Chromatogram tic = reader.encodedTic().orElseThrow();
            assertThat(tic.index()).isZero();
            assertThat(tic.id()).isEqualTo("TIC");
            assertThat(tic.typeCurie()).isEqualTo(TIC);
            assertThat(tic.intensityUnit()).isNull();
            assertThat(tic.arrays()).isEmpty();
            assertThat(tic.size()).isEqualTo(48);
            assertThat(tic.time()).containsExactly(retentionTimes(reader));
            assertThat(Arrays.copyOf(tic.intensity(), 3)).containsExactly(71263168.0, 12901252.0, 586279.0);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void currentSmallEncodedBpcPairsTheBasePeakIntensityWithTheRetentionTimes(String fixture) {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            Chromatogram bpc = reader.encodedBpc().orElseThrow();
            assertThat(bpc.index()).isEqualTo(1);
            assertThat(bpc.id()).isEqualTo("BPC");
            assertThat(bpc.typeCurie()).isEqualTo(BPC);
            assertThat(bpc.intensityUnit()).isNull();
            assertThat(bpc.arrays()).isEmpty();
            assertThat(bpc.size()).isEqualTo(48);
            assertThat(bpc.time()).containsExactly(retentionTimes(reader));
            assertThat(Arrays.copyOf(bpc.intensity(), 3)).containsExactly(1471224.875, 183838.71875, 161140.859375);
        }
    }

    /** On every current fixture the intensities are the column's cells, as pyarrow reads them, in index order. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void encodedIntensitiesAreTheSpectrumMetadataCells(String fixture) {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            assertThat(reader.metadata()).allMatch(d -> Double.isFinite(d.retentionTime()));
            assertThat(reader.encodedTic().orElseThrow().intensity())
                    .containsExactly(cells(reader, fixture, TOTAL_ION_CURRENT));
            assertThat(reader.encodedBpc().orElseThrow().intensity())
                    .containsExactly(cells(reader, fixture, BASE_PEAK_INTENSITY));
        }
    }

    // ---- (b) legacy packed ---------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void legacyPackedValuesEqualTheCurrentOnes(String fixture) {
        try (MzPeakReader current = MzPeakReader.open(Fixtures.current(fixture));
             MzPeakReader legacy = MzPeakReader.open(Fixtures.legacy(fixture))) {
            Chromatogram tic = legacy.encodedTic().orElseThrow();
            assertThat(tic.intensity()).containsExactly(current.encodedTic().orElseThrow().intensity());
            assertThat(tic.time()).containsExactly(retentionTimes(legacy));
            Chromatogram bpc = legacy.encodedBpc().orElseThrow();
            assertThat(bpc.intensity()).containsExactly(current.encodedBpc().orElseThrow().intensity());
            assertThat(bpc.time()).containsExactly(retentionTimes(legacy));
        }
    }

    @Test
    void legacyHasUvValuesEqualTheCurrentOnes() {
        try (MzPeakReader current = MzPeakReader.open(Fixtures.current("has_uv.mzpeak"));
             MzPeakReader legacy = MzPeakReader.open(Fixtures.legacy("has_uv.mzpeak"))) {
            assertThat(legacy.encodedTic().orElseThrow().intensity())
                    .containsExactly(current.encodedTic().orElseThrow().intensity());
            assertThat(legacy.encodedBpc().orElseThrow().intensity())
                    .containsExactly(current.encodedBpc().orElseThrow().intensity());
        }
    }

    // ---- (c) tic() and bpc() -------------------------------------------------------------------------------------

    /** small stores a TIC, which differs from the encoded one, and no BPC. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("smallFixtures")
    void smallTicIsTheStoredOneAndItsBpcTheEncodedOne(String fixture) {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            Chromatogram stored = reader.getChromatogramById("TIC").orElseThrow();
            assertThat(stored.typeCurie()).isEqualTo(TIC);
            Chromatogram tic = reader.tic().orElseThrow();
            assertThat(tic).isSameAs(stored);
            assertThat(tic.intensity()).isNotEqualTo(reader.encodedTic().orElseThrow().intensity());

            assertThat(reader.chromatograms()).noneMatch(c -> BPC.equals(c.typeCurie()));
            assertSameValues(reader.bpc().orElseThrow(), reader.encodedBpc().orElseThrow());
        }
    }

    /** The stored TIC starts at 15245068.0, the encoded one at 71263168.0 (small.numpress stores it lossily). */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"small.mzpeak", "small.chunked.mzpeak", "small.unpacked.mzpeak"})
    void smallStoredTicStartsAtItsStoredValue(String fixture) {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            assertThat(reader.tic().orElseThrow().intensity()[0]).isEqualTo(15245068.0);
            assertThat(reader.encodedTic().orElseThrow().intensity()[0]).isEqualTo(71263168.0);
        }
    }

    /** diaPASEF.ref stores a TIC and a BPC; the stored BPC differs from the encoded one. */
    @Test
    void diaPasefBpcIsTheStoredOne() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("diaPASEF.ref.mzpeak"))) {
            Chromatogram stored = reader.getChromatogramById("BPC").orElseThrow();
            assertThat(stored.typeCurie()).isEqualTo(BPC);
            Chromatogram bpc = reader.bpc().orElseThrow();
            assertThat(bpc).isSameAs(stored);
            assertThat(bpc.intensity()).isNotEqualTo(reader.encodedBpc().orElseThrow().intensity());
            assertThat(reader.tic().orElseThrow()).isSameAs(reader.getChromatogramById("TIC").orElseThrow());
        }
    }

    /** Example_Processed.img stores no chromatogram: tic() and bpc() are the encoded ones. */
    @Test
    void imagingTicAndBpcAreTheEncodedOnes() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("Example_Processed.img.mzpeak"))) {
            Chromatogram tic = reader.tic().orElseThrow();
            assertSameValues(tic, reader.encodedTic().orElseThrow());
            assertThat(tic.size()).isEqualTo(9);
            assertThat(tic.intensity()[0]).isEqualTo(121.85041046142578);
            assertSameValues(reader.bpc().orElseThrow(), reader.encodedBpc().orElseThrow());
        }
    }

    // ---- (d) has_uv: the encoded TIC matches the stored one ------------------------------------------------------

    @Test
    void hasUvEncodedTicMatchesTheStoredOne() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("has_uv.mzpeak"))) {
            Chromatogram stored = reader.tic().orElseThrow();
            assertThat(stored).isSameAs(reader.getChromatogramById("TIC").orElseThrow());
            Chromatogram encoded = reader.encodedTic().orElseThrow();
            assertThat(stored.size()).isEqualTo(212);
            assertThat(encoded.size()).isEqualTo(212);
            for (int i = 0; i < 212; i++) {
                assertThat(encoded.intensity()[i]).as("point %d", i)
                        .isCloseTo(stored.intensity()[i], within(1e-6 * Math.abs(stored.intensity()[i])));
            }
            // has_uv stores no BPC; its second chromatogram is DAD1, not a BPC
            assertThat(reader.chromatograms()).noneMatch(c -> BPC.equals(c.typeCurie()));
            assertSameValues(reader.bpc().orElseThrow(), reader.encodedBpc().orElseThrow());
        }
    }

    // ---- (e) the stored chromatograms are unchanged --------------------------------------------------------------

    @Test
    void storedChromatogramLookupsHaveNoImplicitFallback() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("Example_Processed.img.mzpeak"))) {
            assertThat(reader.chromatograms()).isEmpty();
            assertThat(reader.getChromatogramById("TIC")).isEmpty();
            assertThat(reader.getChromatogramById("BPC")).isEmpty();
            assertThat(reader.getChromatogram(0)).isEmpty();
            assertThat(reader.getChromatogram(1)).isEmpty();
        }
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            assertThat(reader.chromatograms()).hasSize(1);
            assertThat(reader.getChromatogramById("BPC")).isEmpty();
            assertThat(reader.getChromatogram(1)).isEmpty();
        }
    }

    // ---- constructed members -------------------------------------------------------------------------------------

    /**
     * Split: points come in index order although the rows do not, across several row groups; a null value and a
     * non-finite retention time drop the point; an integer column is widened. Without a column there is no encoded
     * chromatogram, and then none from tic() either.
     */
    @Test
    void splitRowsWithoutValueOrFiniteTimeGiveNoPoint() {
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional int64 index (INTEGER(64,false));\n"
                + "  optional double time;\n"
                + "  optional float total_ion_current;\n"
                + "  optional int32 number_of_data_points (INTEGER(32,false));\n"
                + "}");
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);
        // index, time, total ion current (null: none), number of data points
        Object[][] rows = {
                {3L, 4.0, 40.5f, 30},
                {0L, 1.0, 10.5f, 0},
                {4L, Double.NaN, 50.5f, 40},
                {1L, 2.0, null, (int) 4_000_000_000L},
                {2L, 3.0, 30.5f, 20},
                {5L, Double.POSITIVE_INFINITY, 60.5f, 50}};
        List<Group> groups = new ArrayList<>();
        for (Object[] r : rows) {
            Group g = factory.newGroup().append("index", (Long) r[0]).append("time", (Double) r[1])
                    .append("number_of_data_points", (Integer) r[3]);
            if (r[2] != null) {
                g.append("total_ion_current", (Float) r[2]);
            }
            groups.add(g);
        }
        write(schema, 2, groups);
        manifest("{\"name\": \"spectra_metadata.parquet\", \"entity_type\": \"spectrum\", \"data_kind\": \"metadata\","
                + " \"column_mapping\": ["
                + "{\"name\": \"total ion current\", \"path\": \"total_ion_current\", \"accession\": \"MS:1000285\","
                + " \"unit\": \"MS:1000131\"},"
                + "{\"name\": \"number of data points\", \"path\": \"number_of_data_points\","
                + " \"accession\": \"MS:1003060\", \"unit\": null}]}");

        try (MzPeakReader reader = MzPeakReader.open(tmp)) {
            Chromatogram tic = reader.encodedTic().orElseThrow();
            assertThat(tic.time()).containsExactly(1.0, 3.0, 4.0);
            assertThat(tic.intensity()).containsExactly(10.5, 30.5, 40.5);
            assertThat(reader.tic()).isPresent();
            assertThat(reader.encodedBpc()).isEmpty();
            assertThat(reader.bpc()).isEmpty();
        }
        try (MzPeakSource src = MzPeakSource.open(tmp)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(src);
            // uint32 values are not sign-extended; every row has a value, also those without a finite time
            assertThat(MetadataColumns.valuesByIndex(manifest, src, EntityType.SPECTRUM, "MS:1003060"))
                    .contains(Map.of(3L, 30.0, 0L, 0.0, 4L, 40.0, 1L, 4_000_000_000.0, 2L, 20.0, 5L, 50.0));
            assertThat(MetadataColumns.valuesByIndex(manifest, src, EntityType.SPECTRUM, TOTAL_ION_CURRENT)
                    .orElseThrow()).containsOnlyKeys(3L, 0L, 4L, 2L, 5L);
            assertThat(MetadataColumns.valuesByIndex(manifest, src, EntityType.SPECTRUM, BASE_PEAK_INTENSITY))
                    .isEmpty();
            assertThat(MetadataColumns.valuesByIndex(manifest, src, EntityType.CHROMATOGRAM, TOTAL_ION_CURRENT))
                    .isEmpty();
        }
    }

    /**
     * Packed: the values come from the spectrum struct, keyed by {@code spectrum.index}; rows with a null struct or
     * a null index are placeholders and skipped.
     */
    @Test
    void packedPlaceholderRowsAreSkipped() {
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional group spectrum {\n"
                + "    optional int64 index (INTEGER(64,false));\n"
                + "    optional double time;\n"
                + "    optional double MS_1000285_total_ion_current_unit_MS_1000131;\n"
                + "    optional int64 MS_1000505_base_peak_intensity_unit_MS_1000131;\n"
                + "  }\n"
                + "}");
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);
        List<Group> groups = new ArrayList<>();
        groups.add(packedRow(factory, 1L, 2.0, 20.0, 200L));
        groups.add(factory.newGroup());
        groups.add(packedRow(factory, null, 9.0, 90.0, 900L));
        groups.add(packedRow(factory, 0L, 1.0, 10.0, 100L));
        write(schema, Integer.MAX_VALUE, groups);
        manifest("{\"name\": \"spectra_metadata.parquet\", \"entity_type\": \"spectrum\", \"data_kind\": \"metadata\"}");

        try (MzPeakReader reader = MzPeakReader.open(tmp)) {
            assertThat(reader.size()).isEqualTo(2);
            Chromatogram tic = reader.encodedTic().orElseThrow();
            assertThat(tic.time()).containsExactly(1.0, 2.0);
            assertThat(tic.intensity()).containsExactly(10.0, 20.0);
            Chromatogram bpc = reader.encodedBpc().orElseThrow();
            assertThat(bpc.time()).containsExactly(1.0, 2.0);
            assertThat(bpc.intensity()).containsExactly(100.0, 200.0);
        }
    }

    /** A duplicate index is rejected, as the spectrum metadata decoding rejects it. */
    @Test
    void aDuplicateIndexIsRejected() {
        MessageType schema = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional int64 index (INTEGER(64,false));\n"
                + "  optional double total_ion_current;\n"
                + "}");
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);
        write(schema, Integer.MAX_VALUE, List.of(
                factory.newGroup().append("index", 0L).append("total_ion_current", 1.0),
                factory.newGroup().append("index", 0L)));
        manifest("{\"name\": \"spectra_metadata.parquet\", \"entity_type\": \"spectrum\", \"data_kind\": \"metadata\"}");
        try (MzPeakSource src = MzPeakSource.open(tmp)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(src);
            assertThatThrownBy(() -> MetadataColumns.valuesByIndex(manifest, src, EntityType.SPECTRUM,
                    TOTAL_ION_CURRENT))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageContaining("spectra_metadata.parquet")
                    .hasMessageContaining("duplicate index 0");
        }
    }

    @Test
    void aClosedReaderIsRejected() {
        MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"));
        reader.close();
        assertThatThrownBy(reader::encodedTic).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(reader::encodedBpc).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(reader::tic).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(reader::bpc).isInstanceOf(IllegalStateException.class);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** The retention times of the reader's spectra, in index order. */
    private static double[] retentionTimes(MzPeakReader reader) {
        return reader.metadata().stream().mapToDouble(SpectrumDescription::retentionTime).toArray();
    }

    /**
     * The cells of the spectrum metadata column mapped to {@code accession}, as pyarrow reads them, in index order.
     */
    private static double[] cells(MzPeakReader reader, String fixture, String accession) {
        MzPeakManifest.Entry entry = reader.manifest().find(EntityType.SPECTRUM, DataKind.METADATA).orElseThrow();
        String path = entry.columnMapping().stream().filter(m -> accession.equals(m.accession()))
                .map(ColumnMapping::path).findFirst().orElseThrow();
        return GoldenMeta.load(fixture).rows(entry.name()).stream()
                .sorted((a, b) -> Long.compare((Long) a.get("index"), (Long) b.get("index")))
                .mapToDouble(r -> ((Number) r.get(path)).doubleValue())
                .toArray();
    }

    private static void assertSameValues(Chromatogram actual, Chromatogram expected) {
        assertThat(actual.index()).isEqualTo(expected.index());
        assertThat(actual.id()).isEqualTo(expected.id());
        assertThat(actual.typeCurie()).isEqualTo(expected.typeCurie());
        assertThat(actual.time()).containsExactly(expected.time());
        assertThat(actual.intensity()).containsExactly(expected.intensity());
        assertThat(actual.intensityUnit()).isEqualTo(expected.intensityUnit());
        assertThat(actual.arrays()).isEqualTo(expected.arrays());
    }

    private static Group packedRow(SimpleGroupFactory factory, Long index, double time, double tic, long basePeak) {
        Group g = factory.newGroup();
        Group s = g.addGroup("spectrum");
        if (index != null) {
            s.append("index", index);
        }
        s.append("time", time).append("MS_1000285_total_ion_current_unit_MS_1000131", tic)
                .append("MS_1000505_base_peak_intensity_unit_MS_1000131", basePeak);
        return g;
    }

    private void manifest(String entry) {
        try {
            Files.writeString(tmp.resolve(TestSources.MANIFEST), "{\"files\": [" + entry + "], \"metadata\": {}}",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(MessageType schema, int rowGroupRows, List<Group> rows) {
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(
                        new LocalOutputFile(tmp.resolve("spectra_metadata.parquet")))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .withRowGroupRowCountLimit(rowGroupRows)
                .build()) {
            for (Group row : rows) {
                writer.write(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
