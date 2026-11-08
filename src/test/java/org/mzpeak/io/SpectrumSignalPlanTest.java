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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ArrayIndex.Source;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.SignalPlan.Column;
import org.mzpeak.io.SignalPlan.Layout;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RD-13: {@link SpectrumArrayStore} resolves the columns of a spectrum signal member through the member's
 * {@link SignalPlan}. The plan comes from the array index footer on every spectrum data and peaks member of both
 * fixture sets (current 2ee2589, legacy 29e59b2) and is inferred for mzPeakJ output, which has no array index. Members
 * whose footer names other columns than the conventional ones decode through the plan alone: the index statistics,
 * point rows, and delta and Numpress chunk rows. {@link SignalSnapshotTest} (CH-2) shows that the fixture output is
 * unchanged.
 *
 * <p>The constructed members are written with parquet-java into a {@link TempDir}; their values are the first peaks of
 * spectra 2 and 4 of the current small.mzpeak peaks member and row 0 of the current small.numpress data member.
 */
class SpectrumSignalPlanTest {

    private static final String INDEX_KEY = SignalEntity.SPECTRUM.arrayIndexKey();

    @TempDir
    Path tmp;

    private int written;

    // ---- (b) fixtures and mzPeakJ output -----------------------------------------------------------------------

    static Stream<Arguments> spectrumMembers() {
        List<Arguments> out = new ArrayList<>();
        for (String name : Fixtures.CURRENT_NAMES) {
            addMembers(out, "current " + name, Fixtures.current(name));
        }
        for (String name : Fixtures.LEGACY_NAMES) {
            addMembers(out, "legacy " + name, Fixtures.legacy(name));
        }
        return out.stream();
    }

    private static void addMembers(List<Arguments> out, String label, Path dataset) {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            for (DataKind kind : List.of(DataKind.DATA_ARRAYS, DataKind.PEAKS)) {
                manifest.find(EntityType.SPECTRUM, kind).ifPresent(e -> out.add(Arguments.of(
                        label + " " + e.name(), dataset, kind)));
            }
        }
    }

    @Test
    void everyFixtureHasBothSpectrumMembers() {
        assertThat(spectrumMembers()).hasSize(8 * 2 + 5 * 2);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("spectrumMembers")
    void fixtureMembersAreResolvedThroughTheirFooterIndex(String member, Path dataset, DataKind kind) {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            String name = MzPeakManifest.fromSource(source).find(EntityType.SPECTRUM, kind).orElseThrow().name();
            ParquetFooter footer = ParquetFooter.read(source.inputFile(name));
            assertThat(footer.keyValue()).containsKey(INDEX_KEY);
            try (SpectrumArrayStore store = SpectrumArrayStore.load(source.inputFile(name), false, Map.of())) {
                SignalPlan plan = store.plan();
                assertThat(plan.source()).isEqualTo(Source.FOOTER);
                assertThat(plan.entity()).isEqualTo(SignalEntity.SPECTRUM);
                assertThat(footer.topLevelFields()).containsExactly(plan.prefix());
                assertThat(plan.indexField()).isEqualTo("spectrum_index");
                assertThat(plan.axis().arrayType()).isEqualTo("MS:1000514");
            }
        }
    }

    @Test
    void mzPeakJOutputIsResolvedThroughAnInferredIndex() {
        List<Spectrum> spectra = new ArrayList<>();
        List<Chromatogram> chromatograms;
        List<WavelengthSpectrum> wavelength;
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.legacy("has_uv.mzpeak"))) {
            reader.iterator().forEachRemaining(spectra::add);
            chromatograms = reader.chromatograms();
            wavelength = reader.wavelengthSpectra();
        }
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

        assertInferred(point, Layout.POINT, "point");
        assertInferred(numpress, Layout.CHUNK, "chunk");
    }

    private static void assertInferred(Path dataset, Layout layout, String prefix) {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            for (DataKind kind : List.of(DataKind.DATA_ARRAYS, DataKind.PEAKS)) {
                String name = manifest.find(EntityType.SPECTRUM, kind).orElseThrow().name();
                assertThat(ParquetFooter.read(source.inputFile(name)).keyValue()).doesNotContainKey(INDEX_KEY);
                try (SpectrumArrayStore store = SpectrumArrayStore.load(source.inputFile(name), false, Map.of())) {
                    SignalPlan plan = store.plan();
                    assertThat(plan.source()).as("%s %s", dataset.getFileName(), name).isEqualTo(Source.INFERRED);
                    assertThat(plan.layout()).isEqualTo(layout);
                    assertThat(plan.prefix()).isEqualTo(prefix);
                    assertThat(plan.indexField()).isEqualTo("spectrum_index");
                }
            }
        }
    }

    @Test
    void aMalformedFooterIndexFallsBackToTheColumnNames() {
        double[][] p = peaks(2);
        Path file = write(CONVENTIONAL_POINT, "not json", 100,
                pointRow(CONVENTIONAL_POINT, "spectrum_index", "mz", "intensity", 2, p[0]),
                pointRow(CONVENTIONAL_POINT, "spectrum_index", "mz", "intensity", 2, p[1]));
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), false, Map.of())) {
            assertThat(store.plan().source()).isEqualTo(Source.INFERRED);
            assertExact("m/z", new double[] {p[0][0], p[1][0]}, store.get(2).mz());
            assertExact("intensity", new double[] {p[0][1], p[1][1]}, store.get(2).intensity());
        }
    }

    // ---- columns resolved through the plan ---------------------------------------------------------------------

    private static final MessageType RENAMED_POINT = MessageTypeParser.parseMessageType("""
            message m {
              optional group point {
                optional int64 scan_idx (INTEGER(64,false));
                optional double mass;
                optional float signal;
              }
            }""");

    private static final String RENAMED_POINT_INDEX = index("point",
            entry("point.scan_idx", "index", "MS:1003870", 0, null),
            entry("point.mass", "point", "MS:1000514", 1, null),
            entry("point.signal", "point", "MS:1000515", null, null));

    @Test
    void pointRowsAndIndexStatisticsUseTheColumnsTheFooterNames() {
        double[][] two = peaks(2);
        double[][] four = peaks(4);
        // two rows per row group: [2,2] [2,4] [4,4]
        Path file = write(RENAMED_POINT, RENAMED_POINT_INDEX, 2,
                pointRow(RENAMED_POINT, "scan_idx", "mass", "signal", 2, two[0]),
                pointRow(RENAMED_POINT, "scan_idx", "mass", "signal", 2, two[1]),
                pointRow(RENAMED_POINT, "scan_idx", "mass", "signal", 2, two[2]),
                pointRow(RENAMED_POINT, "scan_idx", "mass", "signal", 4, four[0]),
                pointRow(RENAMED_POINT, "scan_idx", "mass", "signal", 4, four[1]));
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), false, Map.of())) {
            SignalPlan plan = store.plan();
            assertThat(plan.source()).isEqualTo(Source.FOOTER);
            assertThat(plan.layout()).isEqualTo(Layout.POINT);
            assertThat(plan.indexField()).isEqualTo("scan_idx");
            assertThat(plan.axis().values().field()).isEqualTo("mass");
            assertThat(plan.plainIntensity()).map(Column::field).contains("signal");

            // the statistics of point.scan_idx bound the row groups
            assertThat(store.contains(1)).isFalse();
            assertThat(store.contains(3)).isTrue();
            assertThat(store.contains(5)).isFalse();
            assertExact("m/z of 2", new double[] {two[0][0], two[1][0], two[2][0]}, store.get(2).mz());
            assertExact("intensity of 2", new double[] {two[0][1], two[1][1], two[2][1]}, store.get(2).intensity());
            assertExact("m/z of 4", new double[] {four[0][0], four[1][0]}, store.get(4).mz());
            assertExact("intensity of 4", new double[] {four[0][1], four[1][1]}, store.get(4).intensity());
            assertThat(store.get(3)).isNull();
        }
    }

    private static final MessageType RENAMED_CHUNK = MessageTypeParser.parseMessageType("""
            message m {
              optional group chunk {
                optional int64 scan_idx (INTEGER(64,false));
                optional double mass_start;
                optional group mass_values (LIST) { repeated group list { optional double element; } }
                optional binary enc (STRING);
                optional group signal (LIST) { repeated group list { optional double element; } }
                optional group mass_numpress_lin (LIST) {
                  repeated group list { required int32 element (INTEGER(8,false)); }
                }
                optional group signal_numpress_slof (LIST) {
                  repeated group list { required int32 element (INTEGER(8,false)); }
                }
                optional group signal_numpress_pic (LIST) {
                  repeated group list { required int32 element (INTEGER(8,false)); }
                }
              }
            }""");

    private static final String RENAMED_CHUNK_INDEX = index("chunk",
            entry("chunk.scan_idx", "index", "MS:1003870", 0, null),
            entry("chunk.mass_start", "chunk_start", "MS:1000514", 1, null),
            entry("chunk.mass_values", "chunk_values", "MS:1000514", 1, null),
            entry("chunk.enc", "chunk_encoding", "MS:1000514", 1, null),
            entry("chunk.mass_numpress_lin", "chunk_transform", "MS:1000514", 1, MSNumpress.ACC_NUMPRESS_LINEAR),
            entry("chunk.signal", "chunk_secondary", "MS:1000515", null, null),
            entry("chunk.signal_numpress_slof", "chunk_transform", "MS:1000515", null, MSNumpress.ACC_NUMPRESS_SLOF),
            entry("chunk.signal_numpress_pic", "chunk_transform", "MS:1000515", null, MSNumpress.ACC_NUMPRESS_PIC));

    @Test
    void chunkRowsAreDecodedFromTheColumnsTheFooterNames() {
        Row0 r = Row0.load();
        // spectrum 0: a delta chunk of the first 5 points, chunk start emitted as a point
        double[] deltas = new double[4];
        for (int i = 0; i < deltas.length; i++) {
            deltas[i] = r.mz[i + 1] - r.mz[i];
        }
        double[] intensity = java.util.Arrays.copyOf(r.intensity, 5);
        Group deltaRecord = chunkRecord(0);
        Group delta = deltaRecord.getGroup("chunk", 0);
        delta.add("mass_start", r.mz[0]);
        addDoubles(delta, "mass_values", deltas);
        delta.add("enc", "MS:1003089");
        addDoubles(delta, "signal", intensity);
        // spectrum 1: row 0's Numpress payloads, in columns whose names contain "numpress" but are not the usual ones
        Group numpressRecord = chunkRecord(1);
        Group numpress = numpressRecord.getGroup("chunk", 0);
        numpress.add("mass_start", r.mz[0]);
        numpress.add("enc", "MS:1002312");
        addBytes(numpress, "mass_numpress_lin", r.mzLinear);
        addBytes(numpress, "signal_numpress_slof", r.slof);

        Path file = write(RENAMED_CHUNK, RENAMED_CHUNK_INDEX, 100, deltaRecord, numpressRecord);
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), false, Map.of())) {
            assertThat(store.plan().source()).isEqualTo(Source.FOOTER);
            assertThat(store.plan().layout()).isEqualTo(Layout.CHUNK);

            double[] mz = new double[5];
            mz[0] = r.mz[0];
            double last = r.mz[0];
            for (int i = 0; i < deltas.length; i++) {
                last += deltas[i];
                mz[i + 1] = last;
            }
            SpectrumArrayStore.Arrays first = store.get(0);
            assertExact("delta m/z", mz, first.mz());
            assertExact("delta intensity", intensity, first.intensity());
            SpectrumArrayStore.Arrays second = store.get(1);
            assertExact("Numpress m/z", r.mz, second.mz());
            assertExact("Numpress intensity", r.intensity, second.intensity());
        }
    }

    /**
     * More chunk rows in renamed columns (RD-13 note 0): an absolute encoding in the renamed encoding column, a renamed
     * PIC column, and a renamed plain list next to Numpress m/z, each read through the plan alone.
     */
    @Test
    void renamedEncodingPicAndPlainListColumnsAreReadThroughThePlan() {
        Row0 r = Row0.load();
        // spectrum 2: MS:1000576 in the renamed encoding column, absolute values after a chunk start point
        Group absoluteRecord = chunkRecord(2);
        Group absolute = absoluteRecord.getGroup("chunk", 0);
        absolute.add("mass_start", r.mz[0]);
        addDoubles(absolute, "mass_values", new double[] {r.mz[1], r.mz[2], r.mz[3]});
        absolute.add("enc", "MS:1000576");
        addDoubles(absolute, "signal", java.util.Arrays.copyOf(r.intensity, 4));
        // spectrum 3: Numpress m/z and PIC intensity in renamed columns
        byte[] pic = pic(r.intensity);
        Group picRecord = chunkRecord(3);
        Group picChunk = picRecord.getGroup("chunk", 0);
        picChunk.add("mass_start", r.mz[0]);
        picChunk.add("enc", "MS:1002312");
        addBytes(picChunk, "mass_numpress_lin", r.mzLinear);
        addBytes(picChunk, "signal_numpress_pic", pic);
        // spectrum 4: Numpress m/z next to the renamed plain intensity list (mixed format)
        Group mixedRecord = chunkRecord(4);
        Group mixed = mixedRecord.getGroup("chunk", 0);
        mixed.add("mass_start", r.mz[0]);
        mixed.add("enc", "MS:1002312");
        addBytes(mixed, "mass_numpress_lin", r.mzLinear);
        addDoubles(mixed, "signal", r.intensity);

        Path file = write(RENAMED_CHUNK, RENAMED_CHUNK_INDEX, 100, absoluteRecord, picRecord, mixedRecord);
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), false, Map.of())) {
            assertExact("absolute m/z", java.util.Arrays.copyOf(r.mz, 4), store.get(2).mz());
            assertExact("absolute intensity", java.util.Arrays.copyOf(r.intensity, 4), store.get(2).intensity());
            assertExact("PIC m/z", r.mz, store.get(3).mz());
            assertExact("PIC intensity", MSNumpress.decode(MSNumpress.ACC_NUMPRESS_PIC, pic, pic.length),
                    store.get(3).intensity());
            assertExact("mixed m/z", r.mz, store.get(4).mz());
            assertExact("mixed intensity", r.intensity, store.get(4).intensity());
        }
    }

    /** An unknown CURIE in the renamed encoding column throws, as in the conventional one. */
    @Test
    void anUnknownCurieInTheRenamedEncodingColumnThrows() {
        Row0 r = Row0.load();
        Group record = chunkRecord(2);
        Group chunk = record.getGroup("chunk", 0);
        chunk.add("mass_start", r.mz[0]);
        addDoubles(chunk, "mass_values", new double[] {0.5});
        chunk.add("enc", "MS:9999999");
        addDoubles(chunk, "signal", new double[] {1.0, 2.0});
        Path file = write(RENAMED_CHUNK, RENAMED_CHUNK_INDEX, 100, record);
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), false, Map.of())) {
            assertThatThrownBy(() -> store.get(2))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("unsupported chunk encoding MS:9999999 for spectrum_index 2");
        }
    }

    /** {@code values} encoded as Numpress PIC. */
    private static byte[] pic(double[] values) {
        byte[] buffer = new byte[8 + values.length * 5];
        int length = MSNumpress.encodePic(values, values.length, buffer);
        return java.util.Arrays.copyOf(buffer, length);
    }

    @Test
    void loadFailsWhenTheFooterNamesTwoMzValueColumns() {
        MessageType schema = MessageTypeParser.parseMessageType("""
                message m {
                  optional group point {
                    optional int64 spectrum_index (INTEGER(64,false));
                    optional double mz;
                    optional double mz_b;
                    optional float intensity;
                  }
                }""");
        String json = index("point",
                entry("point.spectrum_index", "index", "MS:1003870", 0, null),
                entry("point.mz", "point", "MS:1000514", 1, null),
                entry("point.mz_b", "point", "MS:1000514", null, null),
                entry("point.intensity", "point", "MS:1000515", null, null));
        Path file = write(schema, json, 100);
        assertThatThrownBy(() -> SpectrumArrayStore.load(new LocalInputFile(file), false, Map.of()))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("point.mz and point.mz_b");
    }

    // ---- FN-2: the intensity column per row, and plans without m/z or intensity -----------------------------------

    private static final MessageType TWO_INTENSITY_POINT = MessageTypeParser.parseMessageType("""
            message m {
              optional group point {
                optional int64 spectrum_index (INTEGER(64,false));
                optional double mz;
                optional double intensity_f64;
                optional float intensity;
              }
            }""");

    @Test
    void aPointRowReadsTheFirstNonNullIntensityColumn() {
        String json = index("point",
                entry("point.spectrum_index", "index", "MS:1003870", 0, null),
                entry("point.mz", "point", "MS:1000514", 1, null),
                entry("point.intensity_f64", "point", "MS:1000515", null, null),
                entry("point.intensity", "point", "MS:1000515", null, null));
        Group first = new SimpleGroupFactory(TWO_INTENSITY_POINT).newGroup();
        first.addGroup("point").append("spectrum_index", 3L).append("mz", 100.0).append("intensity", 42f);
        Group second = new SimpleGroupFactory(TWO_INTENSITY_POINT).newGroup();
        second.addGroup("point").append("spectrum_index", 3L).append("mz", 101.0).append("intensity", 43f);
        // a row whose earlier column has the value takes it
        Group third = new SimpleGroupFactory(TWO_INTENSITY_POINT).newGroup();
        third.addGroup("point").append("spectrum_index", 3L).append("mz", 102.0).append("intensity_f64", 7.5)
                .append("intensity", 44f);
        Path file = write(TWO_INTENSITY_POINT, json, 100, first, second, third);
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), false, Map.of())) {
            assertExact("m/z", new double[] {100, 101, 102}, store.get(3).mz());
            assertExact("intensity", new double[] {42, 43, 7.5}, store.get(3).intensity());
        }
    }

    private static final MessageType TWO_INTENSITY_CHUNK = MessageTypeParser.parseMessageType("""
            message m {
              optional group chunk {
                optional int64 spectrum_index (INTEGER(64,false));
                optional double mz_chunk_start;
                optional group mz_chunk_values (LIST) { repeated group list { optional double element; } }
                optional binary chunk_encoding (STRING);
                optional group intensity_f64 (LIST) { repeated group list { optional double element; } }
                optional group intensity (LIST) { repeated group list { optional double element; } }
              }
            }""");

    @Test
    void aChunkRowReadsTheFirstNonNullIntensityList() {
        String json = index("chunk",
                entry("chunk.spectrum_index", "index", "MS:1003870", 0, null),
                entry("chunk.mz_chunk_start", "chunk_start", "MS:1000514", 1, null),
                entry("chunk.mz_chunk_values", "chunk_values", "MS:1000514", 1, null),
                entry("chunk.chunk_encoding", "chunk_encoding", "MS:1000514", 1, null),
                entry("chunk.intensity_f64", "chunk_secondary", "MS:1000515", null, null),
                entry("chunk.intensity", "chunk_secondary", "MS:1000515", null, null));
        Group first = new SimpleGroupFactory(TWO_INTENSITY_CHUNK).newGroup();
        Group a = first.addGroup("chunk");
        a.add("spectrum_index", 3L);
        a.add("mz_chunk_start", 100.0);
        addDoubles(a, "mz_chunk_values", new double[] {1});
        a.add("chunk_encoding", "MS:1003089");
        addDoubles(a, "intensity", new double[] {42, 43});
        Group second = new SimpleGroupFactory(TWO_INTENSITY_CHUNK).newGroup();
        Group b = second.addGroup("chunk");
        b.add("spectrum_index", 3L);
        b.add("mz_chunk_start", 110.0);
        addDoubles(b, "mz_chunk_values", new double[] {1});
        b.add("chunk_encoding", "MS:1003089");
        addDoubles(b, "intensity_f64", new double[] {7.5, 8.5});
        addDoubles(b, "intensity", new double[] {44, 45});
        Path file = write(TWO_INTENSITY_CHUNK, json, 100, first, second);
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), false, Map.of())) {
            assertExact("m/z", new double[] {100, 101, 110, 111}, store.get(3).mz());
            assertExact("intensity", new double[] {42, 43, 7.5, 8.5}, store.get(3).intensity());
        }
    }

    @Test
    void aPointPlanWithoutAnIntensityColumnThrows() {
        String json = index("point",
                entry("point.spectrum_index", "index", "MS:1003870", 0, null),
                entry("point.mz", "point", "MS:1000514", 1, null));
        Path file = write(CONVENTIONAL_POINT, json, 100,
                pointRow(CONVENTIONAL_POINT, "spectrum_index", "mz", "intensity", 2, new double[] {100, 42}));
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), "spectra_peaks.parquet",
                false, Map.of())) {
            assertThatThrownBy(() -> store.get(2))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("spectra_peaks.parquet: the signal plan names no value-bearing intensity column, so"
                            + " the point rows of spectrum_index 2 cannot be read");
        }
    }

    @Test
    void aPointPlanWithoutAnMzValuesColumnThrows() {
        String json = index("point",
                entry("point.spectrum_index", "index", "MS:1003870", 0, null),
                entry("point.intensity", "point", "MS:1000515", null, null));
        Path file = write(CONVENTIONAL_POINT, json, 100,
                pointRow(CONVENTIONAL_POINT, "spectrum_index", "mz", "intensity", 2, new double[] {100, 42}));
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), "spectra_data.parquet",
                false, Map.of())) {
            assertThatThrownBy(() -> store.get(2))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("spectra_data.parquet: the signal plan names no m/z values column, so the point rows"
                            + " of spectrum_index 2 cannot be read");
        }
    }

    @Test
    void aChunkRowWithIntensitiesButNeitherMzValuesNorStartThrows() {
        // the footer names no m/z column at all: the single intensity would read as a point at m/z 0
        String json = index("chunk",
                entry("chunk.spectrum_index", "index", "MS:1003870", 0, null),
                entry("chunk.chunk_encoding", "chunk_encoding", "MS:1000514", 1, null),
                entry("chunk.intensity", "chunk_secondary", "MS:1000515", null, null));
        Group record = new SimpleGroupFactory(TWO_INTENSITY_CHUNK).newGroup();
        Group c = record.addGroup("chunk");
        c.add("spectrum_index", 3L);
        c.add("mz_chunk_start", 100.0);
        c.add("chunk_encoding", "MS:1003089");
        addDoubles(c, "intensity", new double[] {42});
        Path file = write(TWO_INTENSITY_CHUNK, json, 100, record);
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(file), false, Map.of())) {
            assertThatThrownBy(() -> store.get(3))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("chunk row of spectrum_index 3 carries 1 intensities, but the signal plan names"
                            + " neither an m/z values nor an m/z chunk start column");
        }
    }

    // ---- fixtures and constructed members ----------------------------------------------------------------------

    /** The first 3 peaks of spectrum {@code index} of the current small.mzpeak peaks member, as {m/z, intensity}. */
    private static double[][] peaks(long index) {
        List<double[]> out = new ArrayList<>();
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current("small.mzpeak"))) {
            String peaks = MzPeakManifest.fromSource(source).find(EntityType.SPECTRUM, DataKind.PEAKS)
                    .orElseThrow().name();
            ParquetGroups.forEach(source.inputFile(peaks), record -> {
                Group point = record.getGroup("point", 0);
                if (ParquetGroups.optLong(point, "spectrum_index") == index && out.size() < 3) {
                    out.add(new double[] {ParquetGroups.optDouble(point, "mz"),
                            ParquetGroups.optDouble(point, "intensity")});
                }
            });
        }
        assertThat(out).as("peaks of spectrum %s", index).hasSize(3);
        return out.toArray(double[][]::new);
    }

    /** Row 0 of the current small.numpress spectrum data member, with its Numpress payloads decoded. */
    private record Row0(byte[] mzLinear, byte[] slof, double[] mz, double[] intensity) {

        static Row0 load() {
            try (MzPeakSource source = MzPeakSource.open(Fixtures.current("small.numpress.mzpeak"))) {
                String data = MzPeakManifest.fromSource(source).find(EntityType.SPECTRUM, DataKind.DATA_ARRAYS)
                        .orElseThrow().name();
                Group[] first = new Group[1];
                ParquetGroups.forEach(source.inputFile(data), record -> {
                    if (first[0] == null) {
                        first[0] = record.getGroup("chunk", 0);
                    }
                });
                byte[] linear = ParquetGroups.byteList(first[0], "mz_numpress_linear_bytes");
                byte[] slof = ParquetGroups.byteList(first[0], "intensity_numpress_slof_bytes");
                Row0 row = new Row0(linear, slof,
                        MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, linear, linear.length),
                        MSNumpress.decode(MSNumpress.ACC_NUMPRESS_SLOF, slof, slof.length));
                assertThat(row.mz()).hasSameSizeAs(row.intensity()).hasSizeGreaterThan(100);
                return row;
            }
        }
    }

    private static final MessageType CONVENTIONAL_POINT = MessageTypeParser.parseMessageType("""
            message m {
              optional group point {
                optional int64 spectrum_index (INTEGER(64,false));
                optional double mz;
                optional float intensity;
              }
            }""");

    private static Group pointRow(MessageType schema, String index, String mz, String intensity, long si,
                                  double[] value) {
        Group record = new SimpleGroupFactory(schema).newGroup();
        Group point = record.addGroup("point");
        point.add(index, si);
        point.add(mz, value[0]);
        point.add(intensity, (float) value[1]);
        return record;
    }

    /** A record of {@link #RENAMED_CHUNK} for spectrum {@code si}; the caller fills the rest of its chunk struct. */
    private static Group chunkRecord(long si) {
        Group record = new SimpleGroupFactory(RENAMED_CHUNK).newGroup();
        record.addGroup("chunk").add("scan_idx", si);
        return record;
    }

    private static void addDoubles(Group parent, String field, double[] values) {
        Group list = parent.addGroup(field);
        for (double v : values) {
            list.addGroup("list").add("element", v);
        }
    }

    private static void addBytes(Group parent, String field, byte[] bytes) {
        Group list = parent.addGroup(field);
        for (byte b : bytes) {
            list.addGroup("list").add("element", b & 0xFF);
        }
    }

    private static String entry(String path, String format, String arrayType, Integer rank, String transform) {
        return "{\"path\": \"" + path + "\", \"buffer_format\": \"" + format + "\", \"array_type\": \"" + arrayType
                + "\", \"sorting_rank\": " + rank + ", \"transform\": "
                + (transform == null ? "null" : "\"" + transform + "\"") + ", \"context\": \"spectrum\"}";
    }

    private static String index(String prefix, String... entries) {
        return "{\"prefix\": \"" + prefix + "\", \"entries\": [" + String.join(", ", entries) + "]}";
    }

    /**
     * Writes {@code rows} with {@code json} as the spectrum array index footer, at most {@code rowGroupRows} rows per
     * row group.
     */
    private Path write(MessageType schema, String json, int rowGroupRows, Group... rows) {
        Path file = tmp.resolve("member-" + (written++) + ".parquet");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withExtraMetaData(Map.of(INDEX_KEY, json))
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withRowGroupRowCountLimit(rowGroupRows)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (Group row : rows) {
                writer.write(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    private static void assertExact(String what, double[] expected, double[] actual) {
        assertThat(actual).as("%s length", what).hasSize(expected.length);
        for (int k = 0; k < expected.length; k++) {
            assertThat(Double.compare(actual[k], expected[k]))
                    .as("%s at %d: expected %s but was %s", what, k, expected[k], actual[k])
                    .isZero();
        }
    }
}
