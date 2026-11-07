package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ArrayIndex.Source;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.SignalPlan.Column;
import org.mzpeak.io.SignalPlan.Layout;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

/**
 * RD-22: chromatogram and wavelength-spectrum signal columns, resolved through the {@link SignalPlan} of the data
 * member.
 *
 * <p>Wiring: {@link ChromatogramStore} and {@link WavelengthSpectrumStore} decode every such member of both fixture
 * sets with its FOOTER plan, and mzPeakJ output with an INFERRED one; has_uv's 'DAD1 A' values come from the
 * {@code intensity_f32_au} entry; constructed members whose array index names other columns than the literal ones
 * are read from the columns the index names.
 *
 * <p>Characterization, pinned on the code before RD-22 (agent-team/upstream-p1 5736e6d):
 * <ul>
 *   <li>the bits of every chromatogram and wavelength spectrum of the current set, read through
 *       {@link MzPeakReader} (CH-2 pins the legacy set);
 *   <li>mzPeakJ output reads back the arrays it was written from;
 *   <li>constructed members without an array index footer pin the decoding rules (Q-U5): a point row takes the
 *       literal {@code intensity}, else the first non-null numeric {@code intensity_*} column in schema order, else
 *       0, and a null time reads as NaN; a chunk row adds its time deltas to {@code time_chunk_start} whatever its
 *       {@code chunk_encoding} (a null delta adds 0), takes the plain {@code intensity} list, else the SLOF bytes,
 *       pads missing intensities with 0 and cuts surplus ones; rows without an index, and chunk rows without a
 *       start, are skipped; PIC bytes are not read; wavelength spectra read {@code wavelength} and the literal
 *       {@code intensity} only.
 * </ul>
 */
class SignalPlanWiringTest {

    /** Fixture and entity to the summary of its arrays, recorded on the base code (agent-team/upstream-p1 5736e6d). */
    private static final Map<String, String> CURRENT = Map.ofEntries(
            current("small.mzpeak", "chromatograms", "entries=1 absent=none values=96",
                    "316eab9cb4e2028177069ca8bade8660ed0b66fba3ccde716c924eab8baf98ee"),
            current("small.chunked.mzpeak", "chromatograms", "entries=1 absent=none values=96",
                    "316eab9cb4e2028177069ca8bade8660ed0b66fba3ccde716c924eab8baf98ee"),
            current("small.numpress.mzpeak", "chromatograms", "entries=1 absent=none values=96",
                    "20209b8732cb1e6a40e5fa49a44dd1e71c4fd273a5c33ccfe576b0fd5c4454e7"),
            current("small.unpacked.mzpeak", "chromatograms", "entries=1 absent=none values=96",
                    "316eab9cb4e2028177069ca8bade8660ed0b66fba3ccde716c924eab8baf98ee"),
            current("has_uv.mzpeak", "chromatograms", "entries=2 absent=none values=1476",
                    "7b80a16b9a131321b46839e48286b01ac0db3bff257228b20164d58629505bda"),
            current("has_uv.mzpeak", "wavelength_spectra", "entries=520 absent=none values=99840",
                    "9dc0c387ba84510932cc6426bb666b2f28b61b2a18cdd2e878902611f6471795"),
            current("diaPASEF.ref.mzpeak", "chromatograms", "entries=2 absent=none values=36",
                    "8a30e18791761bbc9e29e112b5578ac2c6fc2c63ff4d2da0810d8f68a3f3e075"),
            current("diaPASEF.grid.mzpeak", "chromatograms", "entries=2 absent=none values=36",
                    "8a30e18791761bbc9e29e112b5578ac2c6fc2c63ff4d2da0810d8f68a3f3e075"));

    /** The summary of a fixture without such entities. */
    private static final String NONE = "entries=0 absent=none values=0"
            + " sha256=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final MessageType CHROMATOGRAM_METADATA = MessageTypeParser.parseMessageType("""
            message m {
              optional int64 index (INTEGER(64,false));
              optional binary id (STRING);
            }""");

    private static final MessageType WAVELENGTH_METADATA = MessageTypeParser.parseMessageType("""
            message m {
              optional int64 index (INTEGER(64,false));
              optional binary id (STRING);
              optional double time;
            }""");

    @TempDir
    Path tmp;

    private static Map.Entry<String, String> current(String fixture, String entity, String summary, String sha256) {
        return Map.entry(fixture + " " + entity, summary + " sha256=" + sha256);
    }

    static Stream<String> currentFixtures() {
        return Fixtures.CURRENT_NAMES.stream();
    }

    // ---- wiring: fixtures and mzPeakJ output -------------------------------------------------------------------

    /** Both stores decode every chromatogram and wavelength-spectrum data member of both sets with its footer plan. */
    @Test
    void everyChromatogramAndWavelengthMemberOfBothSetsIsDecodedWithItsFooterPlan() {
        Map<String, Source> sources = new LinkedHashMap<>();
        for (String name : Fixtures.CURRENT_NAMES) {
            planSources("current " + name, Fixtures.current(name), sources);
        }
        for (String name : Fixtures.LEGACY_NAMES) {
            planSources("legacy " + name, Fixtures.legacy(name), sources);
        }
        // every fixture but Example_Processed.img has chromatograms; only has_uv has wavelength spectra
        assertThat(sources).hasSize((Fixtures.CURRENT_NAMES.size() - 1) + Fixtures.LEGACY_NAMES.size() + 2)
                .containsKeys("current has_uv.mzpeak wavelength_spectra", "legacy has_uv.mzpeak wavelength_spectra",
                        "current small.numpress.mzpeak chromatograms", "legacy small.chunked.mzpeak chromatograms");
        assertThat(sources.values()).containsOnly(Source.FOOTER);
    }

    /** The plan source of each store {@code dataset} has a data member for, keyed by {@code label} and entity. */
    private static void planSources(String label, Path dataset, Map<String, Source> out) {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            if (manifest.find(EntityType.CHROMATOGRAM, DataKind.DATA_ARRAYS).isPresent()) {
                ChromatogramStore store = ChromatogramStore.load(source, manifest);
                assertThat(store.all()).as(label).isNotEmpty();
                out.put(label + " chromatograms", store.plan().source());
            }
            if (manifest.find(EntityType.WAVELENGTH_SPECTRUM, DataKind.DATA_ARRAYS).isPresent()) {
                WavelengthSpectrumStore store = WavelengthSpectrumStore.load(source, manifest);
                assertThat(store.all()).as(label).isNotEmpty();
                out.put(label + " wavelength_spectra", store.plan().source());
                assertThat(store.plan().axis().values().path()).isEqualTo("point.wavelength");
                assertThat(store.plan().plainIntensity()).map(Column::path).contains("point.intensity");
            }
        }
    }

    static Stream<Path> hasUvInBothSets() {
        return Stream.of(Fixtures.current("has_uv.mzpeak"), Fixtures.legacy("has_uv.mzpeak"));
    }

    /**
     * The 'DAD1 A' trace has no value in the plan's first intensity entry (point.intensity, MS:1000131), so its
     * values are the cells of the second, point.intensity_f32_au in absorbance (UO:0000269); the TIC's are the
     * cells of the first.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("hasUvInBothSets")
    void hasUvDad1ValuesComeFromTheAbsorbanceEntry(Path dataset) {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            ChromatogramStore store = ChromatogramStore.load(source, manifest);
            SignalPlan plan = store.plan();
            assertThat(plan.source()).isEqualTo(Source.FOOTER);
            assertThat(plan.intensities()).extracting(Column::path, Column::unit).containsExactly(
                    tuple("point.intensity", "MS:1000131"), tuple("point.intensity_f32_au", "UO:0000269"));

            Column counts = plan.intensities().get(0);
            Column absorbance = plan.intensities().get(1);
            DoubleArrayBuilder tic = new DoubleArrayBuilder();
            DoubleArrayBuilder dad = new DoubleArrayBuilder();
            String data = manifest.find(EntityType.CHROMATOGRAM, DataKind.DATA_ARRAYS).orElseThrow().name();
            ParquetGroups.forEach(source.inputFile(data), row -> {
                Group point = ParquetGroups.optGroup(row, plan.prefix());
                Long index = ParquetGroups.optLong(point, plan.indexField());
                if (Long.valueOf(0L).equals(index)) {
                    tic.add(ParquetGroups.optDouble(point, counts.field()));
                } else if (Long.valueOf(1L).equals(index)) {
                    assertThat(ParquetGroups.optDouble(point, counts.field())).isNull();
                    dad.add(ParquetGroups.optDouble(point, absorbance.field()));
                }
            });
            assertThat(store.byIndex(0).orElseThrow().intensity()).hasSize(212).isEqualTo(tic.toArray());
            Chromatogram dad1 = store.byIndex(1).orElseThrow();
            assertThat(dad1.id()).startsWith("DAD1 A");
            assertThat(dad1.intensity()).hasSize(526).isEqualTo(dad.toArray());
        }
    }

    /** Members mzPeakJ writes have no array index footer and are decoded with INFERRED plans. */
    @Test
    void mzPeakJOutputIsDecodedWithInferredPlans() {
        Path written = tmp.resolve("written.mzpeak");
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.legacy("has_uv.mzpeak"))) {
            List<Spectrum> spectra = new ArrayList<>();
            reader.iterator().forEachRemaining(spectra::add);
            MzPeakWriter.writeDirectory(written, spectra, reader.chromatograms(), reader.wavelengthSpectra(),
                    List.of(), FileMetadata.EMPTY);
        }
        try (MzPeakSource source = MzPeakSource.open(written)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            ChromatogramStore chromatograms = ChromatogramStore.load(source, manifest);
            WavelengthSpectrumStore wavelength = WavelengthSpectrumStore.load(source, manifest);
            assertThat(chromatograms.all()).hasSize(2);
            assertThat(wavelength.all()).hasSize(520);

            assertThat(chromatograms.plan().source()).isEqualTo(Source.INFERRED);
            assertThat(chromatograms.plan().layout()).isEqualTo(Layout.POINT);
            assertThat(chromatograms.plan().axis().values().path()).isEqualTo("point.time");
            assertThat(chromatograms.plan().intensities()).extracting(Column::path).containsExactly("point.intensity");
            assertThat(wavelength.plan().source()).isEqualTo(Source.INFERRED);
            assertThat(wavelength.plan().axis().values().path()).isEqualTo("point.wavelength");
            assertThat(wavelength.plan().intensities()).extracting(Column::path).containsExactly("point.intensity");
        }
    }

    // ---- wiring: constructed members whose array index names other columns ------------------------------------

    /**
     * A point member is read from the columns its index names (index {@code trace}, time {@code rt}, intensities
     * {@code counts} then {@code absorbance}); the literal {@code time} and {@code intensity} columns it also has are
     * not part of the index and are not read.
     */
    @Test
    void pointRowsAreReadFromTheColumnsTheArrayIndexNames() {
        MessageType schema = MessageTypeParser.parseMessageType("""
                message m {
                  optional group point {
                    optional int64 trace (INTEGER(64,false));
                    optional double time;
                    optional float intensity;
                    optional double rt;
                    optional float counts;
                    optional float absorbance;
                  }
                }""");
        String index = arrayIndex("point",
                indexEntry("point.trace", "index", "MS:1003870", 0, null, null),
                indexEntry("point.rt", "point", "MS:1000595", 1, null, "UO:0000031"),
                indexEntry("point.counts", "point", "MS:1000515", null, null, "MS:1000131"),
                indexEntry("point.absorbance", "point", "MS:1000515", null, null, "UO:0000269"));
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        Path data = write("chromatograms_data.parquet", schema,
                Map.of(SignalEntity.CHROMATOGRAM.arrayIndexKey(), index),
                point(rows).append("trace", 0L).append("time", 98.0).append("intensity", 99f).append("rt", 1.0)
                        .append("counts", 5f).append("absorbance", 7f).parent,
                point(rows).append("trace", 0L).append("time", 97.0).append("intensity", 96f).append("rt", 2.0)
                        .append("absorbance", 0.5f).parent,
                point(rows).append("trace", 1L).append("time", 95.0).append("intensity", 94f).parent,
                point(rows).append("time", 93.0).append("rt", 3.0).append("counts", 1f).parent);

        ChromatogramStore store = chromatogramStore(data, 0, 1);

        assertThat(store.plan().source()).isEqualTo(Source.FOOTER);
        assertThat(store.plan().indexField()).isEqualTo("trace");
        assertArrays(store.all().get(0), new double[] {1.0, 2.0}, new double[] {5.0, 0.5});
        assertArrays(store.all().get(1), new double[] {Double.NaN}, new double[] {0.0});
    }

    /**
     * A chunk member is read from the columns its index names: the start {@code rt_start}, the deltas
     * {@code rt_deltas}, the intensity list {@code counts} and the SLOF bytes {@code counts_slof}; the literal
     * {@code time_chunk_start} and {@code intensity} columns are not read.
     */
    @Test
    void chunkRowsAreReadFromTheColumnsTheArrayIndexNames() {
        MessageType schema = MessageTypeParser.parseMessageType("""
                message m {
                  optional group chunk {
                    optional int64 trace (INTEGER(64,false));
                    optional double time_chunk_start;
                    optional group intensity (LIST) { repeated group list { optional float item; } }
                    optional double rt_start;
                    optional group rt_deltas (LIST) { repeated group list { optional double item; } }
                    optional group counts (LIST) { repeated group list { optional float item; } }
                    optional group counts_slof (LIST) {
                      repeated group list { required int32 item (INTEGER(8,false)); }
                    }
                  }
                }""");
        String index = arrayIndex("chunk",
                indexEntry("chunk.trace", "index", "MS:1003870", 0, null, null),
                indexEntry("chunk.rt_start", "chunk_start", "MS:1000595", 1, null, "UO:0000031"),
                indexEntry("chunk.rt_deltas", "chunk_values", "MS:1000595", 1, null, "UO:0000031"),
                indexEntry("chunk.counts", "chunk_secondary", "MS:1000515", null, null, "MS:1000131"),
                indexEntry("chunk.counts_slof", "chunk_transform", "MS:1000515", null, MSNumpress.ACC_NUMPRESS_SLOF,
                        "MS:1000131"));
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        byte[] slof = slof(100.0, 200.0);
        Group first = rows.newGroup();
        new Row(first, first.addGroup("chunk")).append("trace", 0L).append("time_chunk_start", 50.0)
                .list("intensity", 9.0, 9.0, 9.0).append("rt_start", 1.0).list("rt_deltas", 1.0, 1.0)
                .list("counts", 1.0, 2.0, 3.0);
        Group second = rows.newGroup();
        new Row(second, second.addGroup("chunk")).append("trace", 1L).append("time_chunk_start", 60.0)
                .list("intensity", 8.0, 8.0).append("rt_start", 5.0).list("rt_deltas", 0.5)
                .bytes("counts_slof", slof);
        Path data = write("chromatograms_data.parquet", schema,
                Map.of(SignalEntity.CHROMATOGRAM.arrayIndexKey(), index), first, second);

        ChromatogramStore store = chromatogramStore(data, 0, 1);

        assertThat(store.plan().layout()).isEqualTo(Layout.CHUNK);
        assertArrays(store.all().get(0), new double[] {1.0, 2.0, 3.0}, new double[] {1.0, 2.0, 3.0});
        assertArrays(store.all().get(1), new double[] {5.0, 5.5},
                MSNumpress.decode(MSNumpress.ACC_NUMPRESS_SLOF, slof, slof.length));
    }

    /** A wavelength member is read from the columns its index names, not the literal ones. */
    @Test
    void wavelengthRowsAreReadFromTheColumnsTheArrayIndexNames() {
        MessageType schema = MessageTypeParser.parseMessageType("""
                message m {
                  optional group point {
                    optional int64 spectrum (INTEGER(64,false));
                    optional float wavelength;
                    optional float intensity;
                    optional float nm;
                    optional float au;
                  }
                }""");
        String index = arrayIndex("point",
                indexEntry("point.spectrum", "index", "MS:1003870", 0, null, null),
                indexEntry("point.nm", "point", "MS:1000617", 1, null, "UO:0000018"),
                indexEntry("point.au", "point", "MS:1000515", null, null, "UO:0000269"));
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        Path data = write("wavelength_spectra_data.parquet", schema,
                Map.of(SignalEntity.WAVELENGTH_SPECTRUM.arrayIndexKey(), index),
                point(rows).append("spectrum", 0L).append("wavelength", 1f).append("intensity", 2f)
                        .append("nm", 200f).append("au", 0.25f).parent,
                point(rows).append("spectrum", 0L).append("wavelength", 3f).append("intensity", 4f)
                        .append("nm", 210f).parent);

        List<WavelengthSpectrum> all = wavelengthSpectra(data, 0);

        assertThat(all.get(0).wavelength()).containsExactly(200.0, 210.0);
        assertThat(all.get(0).intensity()).containsExactly(0.25, 0.0);
    }

    /** A malformed array index is ignored with a WARNING; the member is read by the column-name rules. */
    @Test
    void aMalformedArrayIndexFallsBackToTheColumnNames() {
        MessageType schema = MessageTypeParser.parseMessageType("""
                message m {
                  optional group point {
                    optional int64 chromatogram_index (INTEGER(64,false));
                    optional double time;
                    optional float intensity;
                  }
                }""");
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        Path data = write("chromatograms_data.parquet", schema,
                Map.of(SignalEntity.CHROMATOGRAM.arrayIndexKey(), "{\"entries\": []}"),
                point(rows).append("chromatogram_index", 0L).append("time", 1.0).append("intensity", 2f).parent);

        List<ChromatogramStore> store = new ArrayList<>();
        List<LogRecord> records = ArrayIndexTest.captureLog(ArrayIndex.class,
                () -> store.add(chromatogramStore(data, 0)));

        assertThat(records).filteredOn(r -> r.getLevel() == Level.WARNING).singleElement()
                .satisfies(r -> assertThat(r.getMessage()).contains("chromatogram_array_index"));
        assertThat(store.get(0).plan().source()).isEqualTo(Source.INFERRED);
        assertArrays(store.get(0).all().get(0), new double[] {1.0}, new double[] {2.0});
    }

    // ---- characterization: fixtures and mzPeakJ output ---------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void currentChromatogramAndWavelengthArraysAreUnchanged(String fixture) {
        SignalSnapshotTest.SignalDigest chromatograms = new SignalSnapshotTest.SignalDigest("");
        SignalSnapshotTest.SignalDigest wavelength = new SignalSnapshotTest.SignalDigest("");
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(fixture))) {
            for (Chromatogram c : reader.chromatograms()) {
                chromatograms.entry(c.index(), c.time(), c.intensity());
            }
            for (WavelengthSpectrum w : reader.wavelengthSpectra()) {
                wavelength.entry(w.index(), w.wavelength(), w.intensity());
            }
        }
        assertThat(chromatograms.summary()).as("%s chromatograms", fixture)
                .isEqualTo(CURRENT.getOrDefault(fixture + " chromatograms", NONE));
        assertThat(wavelength.summary()).as("%s wavelength spectra", fixture)
                .isEqualTo(CURRENT.getOrDefault(fixture + " wavelength_spectra", NONE));
    }

    @Test
    void mzPeakJOutputReadsBackTheWrittenArrays() {
        List<Spectrum> spectra = new ArrayList<>();
        List<Chromatogram> chromatograms;
        List<WavelengthSpectrum> wavelength;
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.legacy("has_uv.mzpeak"))) {
            reader.iterator().forEachRemaining(spectra::add);
            chromatograms = reader.chromatograms();
            wavelength = reader.wavelengthSpectra();
        }
        Path written = tmp.resolve("written.mzpeak");
        MzPeakWriter.writeDirectory(written, spectra, chromatograms, wavelength, List.of(), FileMetadata.EMPTY);

        try (MzPeakReader reader = MzPeakReader.open(written)) {
            List<Chromatogram> readBack = reader.chromatograms();
            assertThat(readBack).hasSameSizeAs(chromatograms).hasSize(2);
            for (int i = 0; i < chromatograms.size(); i++) {
                assertThat(readBack.get(i).index()).isEqualTo(chromatograms.get(i).index());
                assertThat(readBack.get(i).time()).as("time %d", i).isEqualTo(chromatograms.get(i).time());
                assertThat(readBack.get(i).intensity()).as("intensity %d", i)
                        .isEqualTo(chromatograms.get(i).intensity());
            }
            List<WavelengthSpectrum> wavelengthBack = reader.wavelengthSpectra();
            assertThat(wavelengthBack).hasSameSizeAs(wavelength).hasSize(520);
            for (int i = 0; i < wavelength.size(); i++) {
                assertThat(wavelengthBack.get(i).index()).isEqualTo(wavelength.get(i).index());
                assertThat(wavelengthBack.get(i).wavelength()).as("wavelength %d", i)
                        .isEqualTo(wavelength.get(i).wavelength());
                assertThat(wavelengthBack.get(i).intensity()).as("intensity %d", i)
                        .isEqualTo(wavelength.get(i).intensity());
            }
        }
    }

    // ---- characterization: constructed chromatogram members without an array index ----------------------------

    /**
     * Point rows: the literal intensity (last in schema order) wins; without it the first non-null numeric
     * {@code intensity_*} column in schema order, skipping the string and the repeated one; else 0. A null time is
     * NaN, a row without an index is skipped, a chromatogram without rows is empty and rows of an index without a
     * header are not returned.
     */
    @Test
    void pointRowsWithoutAnArrayIndex() {
        MessageType schema = MessageTypeParser.parseMessageType("""
                message m {
                  optional group point {
                    optional int64 chromatogram_index (INTEGER(64,false));
                    optional binary intensity_label (STRING);
                    optional double time;
                    optional int32 intensity_counts;
                    repeated double intensity_repeated;
                    optional double intensity_au;
                    optional float intensity;
                  }
                }""");
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        Path data = write("chromatograms_data.parquet", schema,
                point(rows).append("chromatogram_index", 1L).append("intensity_label", "x").append("time", 1.0)
                        .append("intensity_counts", 99).append("intensity_au", 98.0).append("intensity", 10f).parent,
                point(rows).append("chromatogram_index", 1L).append("intensity_label", "7")
                        .append("intensity_repeated", 3.0).append("intensity_au", 2.5).parent,
                point(rows).append("chromatogram_index", 0L).append("time", 0.5).append("intensity_counts", 4)
                        .append("intensity_au", 9.0).parent,
                point(rows).append("time", 9.0).append("intensity", 9f).parent,
                point(rows).append("chromatogram_index", 0L).append("intensity_label", "5").append("time", 0.75)
                        .append("intensity_repeated", 6.0).parent,
                point(rows).append("chromatogram_index", 1L).append("time", 3.0).append("intensity", 30f).parent,
                point(rows).append("chromatogram_index", 5L).append("time", 1.0).append("intensity", 1f).parent,
                rows.newGroup());

        List<Chromatogram> all = chromatograms(data, 0, 1, 2);

        assertThat(all).extracting(Chromatogram::id).containsExactly("c0", "c1", "c2");
        assertArrays(all.get(0), new double[] {0.5, 0.75}, new double[] {4.0, 0.0});
        assertArrays(all.get(1), new double[] {1.0, Double.NaN, 3.0}, new double[] {10.0, 2.5, 30.0});
        assertArrays(all.get(2), new double[0], new double[0]);
    }

    /**
     * Chunk rows: the time axis is {@code time_chunk_start} plus the running sum of the deltas whatever the
     * chunk_encoding, a null delta adds 0; intensities come from the plain list, else the SLOF bytes, are padded
     * with 0 and cut to the time axis; PIC bytes are not read. Rows without an index or a start are skipped; chunks
     * of one index are appended in row order.
     */
    @Test
    void chunkRowsWithoutAnArrayIndex() {
        MessageType schema = chunkSchema();
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        byte[] slof = slof(100.0, 200.0);
        Path data = write("chromatograms_data.parquet", schema,
                chunk(rows, 0L, 10.0, "MS:1003089").list("time_chunk_values", 1.0, null, 2.0)
                        .list("intensity", 1.0, null, 3.0).parent,
                chunk(rows, 1L, 5.0, null).list("time_chunk_values", 0.5)
                        .bytes("intensity_numpress_slof_bytes", slof).parent,
                chunk(rows, 0L, 20.0, null).list("time_chunk_values").list("intensity").parent,
                chunk(rows, 2L, null, null).list("time_chunk_values", 1.0).list("intensity", 1.0, 2.0).parent,
                chunk(rows, null, 1.0, null).list("time_chunk_values", 1.0).list("intensity", 1.0, 2.0).parent,
                chunk(rows, 3L, 1.0, "MS:1000576").list("time_chunk_values", 1.0, 1.0)
                        .list("intensity", 7.0, 8.0, 9.0, 10.0).parent,
                chunk(rows, 4L, 0.0, null).list("time_chunk_values", 2.0)
                        .bytes("intensity_numpress_slof_bytes", new byte[0]).parent,
                chunk(rows, 5L, 0.0, null).list("time_chunk_values", 1.0).list("intensity", 4.0, 5.0)
                        .bytes("intensity_numpress_slof_bytes", slof).parent,
                chunk(rows, 6L, 0.0, null).list("time_chunk_values", 1.0)
                        .bytes("intensity_numpress_pic_bytes", new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}).parent,
                chunk(rows, 7L, 0.0, null).list("intensity", 6.0).parent);

        List<Chromatogram> all = chromatograms(data, 0, 1, 2, 3, 4, 5, 6, 7);

        double[] decoded = MSNumpress.decode(MSNumpress.ACC_NUMPRESS_SLOF, slof, slof.length);
        assertThat(decoded).hasSize(2);
        assertThat(decoded[0]).isCloseTo(100.0, within(0.1));
        assertThat(decoded[1]).isCloseTo(200.0, within(0.1));
        assertArrays(all.get(0), new double[] {10.0, 11.0, 11.0, 13.0, 20.0}, new double[] {1.0, 0.0, 3.0, 0.0, 0.0});
        assertArrays(all.get(1), new double[] {5.0, 5.5}, decoded);
        assertArrays(all.get(2), new double[0], new double[0]);
        assertArrays(all.get(3), new double[] {1.0, 2.0, 3.0}, new double[] {7.0, 8.0, 9.0});
        assertArrays(all.get(4), new double[] {0.0, 2.0}, new double[] {0.0, 0.0});
        assertArrays(all.get(5), new double[] {0.0, 1.0}, new double[] {4.0, 5.0});
        assertArrays(all.get(6), new double[] {0.0, 1.0}, new double[] {0.0, 0.0});
        assertArrays(all.get(7), new double[] {0.0}, new double[] {6.0});
    }

    /** SLOF bytes that do not decode throw, naming the chromatogram index. */
    @Test
    void undecodableSlofBytesThrowNamingTheChromatogram() {
        MessageType schema = chunkSchema();
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        Path data = write("chromatograms_data.parquet", schema,
                chunk(rows, 3L, 0.0, null).list("time_chunk_values", 1.0)
                        .bytes("intensity_numpress_slof_bytes", new byte[] {1, 2, 3}).parent);

        assertThatThrownBy(() -> chromatograms(data, 3))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("Failed to Numpress-decode intensity for chromatogram_index 3")
                .hasCauseInstanceOf(NegativeArraySizeException.class);
    }

    // ---- characterization: constructed wavelength members without an array index ------------------------------

    /**
     * Point rows: {@code wavelength} and the literal {@code intensity}, without an {@code intensity_*} fallback; a
     * null wavelength is NaN, a null intensity 0; rows without an index are skipped.
     */
    @Test
    void wavelengthPointRowsWithoutAnArrayIndex() {
        MessageType schema = MessageTypeParser.parseMessageType("""
                message m {
                  optional group point {
                    optional int64 wavelength_spectrum_index (INTEGER(64,false));
                    optional float wavelength;
                    optional float intensity;
                    optional double intensity_au;
                  }
                }""");
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        Path data = write("wavelength_spectra_data.parquet", schema,
                point(rows).append("wavelength_spectrum_index", 0L).append("wavelength", 200f)
                        .append("intensity", 1f).parent,
                point(rows).append("wavelength_spectrum_index", 0L).append("intensity_au", 5.0).parent,
                point(rows).append("wavelength", 205f).append("intensity", 9f).parent,
                point(rows).append("wavelength_spectrum_index", 1L).append("wavelength", 210f)
                        .append("intensity", 2f).append("intensity_au", 7.0).parent,
                point(rows).append("wavelength_spectrum_index", 0L).append("wavelength", 220f)
                        .append("intensity", 3f).parent,
                point(rows).append("wavelength_spectrum_index", 9L).append("wavelength", 1f)
                        .append("intensity", 1f).parent);

        List<WavelengthSpectrum> all = wavelengthSpectra(data, 0, 1, 2);

        assertThat(all).extracting(WavelengthSpectrum::id).containsExactly("w0", "w1", "w2");
        assertThat(all.get(0).wavelength()).containsExactly(200.0, Double.NaN, 220.0);
        assertThat(all.get(0).intensity()).containsExactly(1.0, 0.0, 3.0);
        assertThat(all.get(1).wavelength()).containsExactly(210.0);
        assertThat(all.get(1).intensity()).containsExactly(2.0);
        assertThat(all.get(2).wavelength()).isEmpty();
        assertThat(all.get(2).intensity()).isEmpty();
    }

    /** Wavelength spectra are read from point rows only: a chunk member gives empty arrays. */
    @Test
    void aChunkWavelengthMemberGivesEmptyArrays() {
        MessageType schema = MessageTypeParser.parseMessageType("""
                message m {
                  optional group chunk {
                    optional int64 wavelength_spectrum_index (INTEGER(64,false));
                    optional double wavelength_chunk_start;
                    optional group wavelength_chunk_values (LIST) { repeated group list { optional double item; } }
                    optional group intensity (LIST) { repeated group list { optional float item; } }
                  }
                }""");
        SimpleGroupFactory rows = new SimpleGroupFactory(schema);
        Group row = rows.newGroup();
        new Row(row, row.addGroup("chunk")).append("wavelength_spectrum_index", 0L)
                .append("wavelength_chunk_start", 200.0).list("wavelength_chunk_values", 1.0)
                .list("intensity", 1.0, 2.0);
        Path data = write("wavelength_spectra_data.parquet", schema, row);

        List<WavelengthSpectrum> all = wavelengthSpectra(data, 0);

        assertThat(all).hasSize(1);
        assertThat(all.get(0).wavelength()).isEmpty();
        assertThat(all.get(0).intensity()).isEmpty();
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    /** The chromatograms of {@code data}, with one header {@code c<index>} per index, in the given order. */
    private List<Chromatogram> chromatograms(Path data, long... indices) {
        return chromatogramStore(data, indices).all();
    }

    /** The store of {@code data}, with one chromatogram header {@code c<index>} per index, in the given order. */
    private ChromatogramStore chromatogramStore(Path data, long... indices) {
        SimpleGroupFactory rows = new SimpleGroupFactory(CHROMATOGRAM_METADATA);
        Path metadata = write("chromatograms_metadata.parquet", CHROMATOGRAM_METADATA, Arrays.stream(indices)
                .mapToObj(i -> rows.newGroup().append("index", i).append("id", "c" + i)).toArray(Group[]::new));
        return ChromatogramStore.load(new LocalInputFile(metadata), new LocalInputFile(data));
    }

    /** The wavelength spectra of {@code data}, with one header {@code w<index>} per index, in the given order. */
    private List<WavelengthSpectrum> wavelengthSpectra(Path data, long... indices) {
        SimpleGroupFactory rows = new SimpleGroupFactory(WAVELENGTH_METADATA);
        Path metadata = write("wavelength_spectra_metadata.parquet", WAVELENGTH_METADATA, Arrays.stream(indices)
                .mapToObj(i -> rows.newGroup().append("index", i).append("id", "w" + i).append("time", 0.1 * i))
                .toArray(Group[]::new));
        return WavelengthSpectrumStore.load(new LocalInputFile(metadata), new LocalInputFile(data)).all();
    }

    private static void assertArrays(Chromatogram c, double[] time, double[] intensity) {
        assertThat(c.time()).as("%s time", c.id()).containsExactly(time);
        assertThat(c.intensity()).as("%s intensity", c.id()).containsExactly(intensity);
    }

    /** A chromatogram chunk schema with the time axis, the plain intensity list and both Numpress byte lists. */
    private static MessageType chunkSchema() {
        return MessageTypeParser.parseMessageType("""
                message m {
                  optional group chunk {
                    optional int64 chromatogram_index (INTEGER(64,false));
                    optional double time_chunk_start;
                    optional double time_chunk_end;
                    optional group time_chunk_values (LIST) { repeated group list { optional double item; } }
                    optional binary chunk_encoding (STRING);
                    optional group intensity (LIST) { repeated group list { optional float item; } }
                    optional group intensity_numpress_slof_bytes (LIST) {
                      repeated group list { required int32 item (INTEGER(8,false)); }
                    }
                    optional group intensity_numpress_pic_bytes (LIST) {
                      repeated group list { required int32 item (INTEGER(8,false)); }
                    }
                  }
                }""");
    }

    /** A new row with its {@code point} struct. */
    private static Row point(SimpleGroupFactory rows) {
        Group row = rows.newGroup();
        return new Row(row, row.addGroup("point"));
    }

    /** A new row with its {@code chunk} struct; null arguments leave their columns null. */
    private static Row chunk(SimpleGroupFactory rows, Long index, Double start, String encoding) {
        Group row = rows.newGroup();
        Row chunk = new Row(row, row.addGroup("chunk"));
        if (index != null) {
            chunk.append("chromatogram_index", index);
        }
        if (start != null) {
            chunk.append("time_chunk_start", start);
        }
        if (encoding != null) {
            chunk.append("chunk_encoding", encoding);
        }
        return chunk;
    }

    /** The prefix struct of a row under construction, and the row itself as {@link #parent}. */
    private static final class Row {
        final Group parent;
        final Group struct;

        Row(Group parent, Group struct) {
            this.parent = parent;
            this.struct = struct;
        }

        Row append(String field, long value) {
            struct.append(field, value);
            return this;
        }

        Row append(String field, int value) {
            struct.append(field, value);
            return this;
        }

        Row append(String field, double value) {
            struct.append(field, value);
            return this;
        }

        Row append(String field, float value) {
            struct.append(field, value);
            return this;
        }

        Row append(String field, String value) {
            struct.append(field, value);
            return this;
        }

        /** A list column of FLOAT or DOUBLE items; a null value is a null item. */
        Row list(String field, Double... values) {
            Group list = struct.addGroup(field);
            for (Double v : values) {
                Group element = list.addGroup("list");
                if (v == null) {
                    continue;
                }
                if (element.getType().getType("item").asPrimitiveType().getPrimitiveTypeName()
                        == PrimitiveTypeName.FLOAT) {
                    element.append("item", v.floatValue());
                } else {
                    element.append("item", v);
                }
            }
            return this;
        }

        /** A list column of uint8 items. */
        Row bytes(String field, byte[] values) {
            Group list = struct.addGroup(field);
            for (byte b : values) {
                list.addGroup("list").append("item", b & 0xff);
            }
            return this;
        }
    }

    /** MS-Numpress SLOF bytes of {@code values}. */
    private static byte[] slof(double... values) {
        byte[] buffer = new byte[8 + 2 * values.length];
        int n = MSNumpress.encodeSlof(values, values.length, buffer,
                MSNumpress.optimalSlofFixedPoint(values, values.length));
        return Arrays.copyOf(buffer, n);
    }

    /** One array index entry; null components are written as JSON null. */
    private static String indexEntry(String path, String format, String arrayType, Integer rank, String transform,
                                     String unit) {
        return "{\"path\": \"" + path + "\", \"buffer_format\": \"" + format + "\", \"array_type\": \"" + arrayType
                + "\", \"sorting_rank\": " + rank + ", \"transform\": " + quoted(transform) + ", \"unit\": "
                + quoted(unit) + "}";
    }

    private static String quoted(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    /** An array index footer value with {@code prefix} and {@code entries}. */
    private static String arrayIndex(String prefix, String... entries) {
        return "{\"prefix\": \"" + prefix + "\", \"entries\": [" + String.join(", ", entries) + "]}";
    }

    /** Writes {@code rows} as the Parquet file {@code name} of the temporary directory. */
    private Path write(String name, MessageType schema, Group... rows) {
        return write(name, schema, Map.of(), rows);
    }

    /** Writes {@code rows} as the Parquet file {@code name} of the temporary directory, with footer key-values. */
    private Path write(String name, MessageType schema, Map<String, String> footer, Group... rows) {
        Path file = tmp.resolve(name);
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .withExtraMetaData(footer)
                .build()) {
            for (Group row : rows) {
                writer.write(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
