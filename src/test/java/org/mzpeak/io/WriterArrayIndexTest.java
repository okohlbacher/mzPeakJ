package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ms.numpress.MSNumpress;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.schema.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ArrayIndex.BufferFormat;
import org.mzpeak.io.ArrayIndex.Entry;
import org.mzpeak.io.ArrayIndex.Source;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * WR-2 (design §11.7; spec conformance.md:41, signal-data.md "The Array Index" and "Indexing"): every signal file the
 * writer writes records in its footer
 * <ul>
 *   <li>{@code <entity>_array_index}, one entry per column: the entity index at sorting rank 0 (MS:1003870, 64-bit
 *       integer), every column of the main axis at rank 1 with its unit, the intensity (MS:1000515, unit MS:1000131
 *       for spectra, one column per intensity unit for chromatograms (MZ-2) and null for wavelength spectra), the
 *       extra arrays of chromatograms (MZ-2) and, in the Numpress chunk layout, the linear m/z transform
 *       MS:1002312 and the SLOF (MS:1002314) or PIC (MS:1002313) intensity transform; every other entry is a 64-bit
 *       float;
 *   <li>{@code <entity>_count}, the entities of that type in the archive, and {@code <entity>_data_point_count},
 *       the points stored in that file, also for a file without rows;
 * </ul>
 * and spectra_metadata records {@code spectrum_count}. The reader resolves the written files through that index
 * (FOOTER plans), every column chunk carries statistics and a page index, and the main axis WR-14 re-sorts is
 * stored ascending under the rank-1 entry.
 *
 * <p>Written from the legacy {@code small.unpacked} (profile spectra_data, spectra_peaks, chromatograms) and
 * {@code has_uv} (centroid spectra_peaks only, chromatograms, wavelength spectra) fixtures in the point layout and
 * in both Numpress intensity encodings.
 */
class WriterArrayIndexTest {

    private static final String INDEX_ARRAY = "MS:1003870";
    private static final String INT64 = "MS:1000522";
    private static final String FLOAT64 = "MS:1000523";
    private static final String MZ = "MS:1000514";
    private static final String TIME = "MS:1000595";
    private static final String WAVELENGTH = "MS:1000617";
    private static final String INTENSITY = "MS:1000515";
    private static final String COUNTS = "MS:1000131";
    private static final String ABSORBANCE = "UO:0000269";
    private static final String MZ_UNIT = "MS:1000040";
    private static final String MINUTE = "UO:0000031";
    private static final String NANOMETER = "UO:0000018";

    private static final Options POINT = Options.defaults();
    private static final Options SLOF = Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS)
            .withNumpressIntensity(NumpressIntensityEncoding.SLOF);
    private static final Options PIC = Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS)
            .withNumpressIntensity(NumpressIntensityEncoding.PIC);

    /** The signal members by role. */
    private static final List<Role> ROLES = List.of(
            new Role(EntityType.SPECTRUM, DataKind.DATA_ARRAYS, SignalEntity.SPECTRUM),
            new Role(EntityType.SPECTRUM, DataKind.PEAKS, SignalEntity.SPECTRUM),
            new Role(EntityType.CHROMATOGRAM, DataKind.DATA_ARRAYS, SignalEntity.CHROMATOGRAM),
            new Role(EntityType.WAVELENGTH_SPECTRUM, DataKind.DATA_ARRAYS, SignalEntity.WAVELENGTH_SPECTRUM));

    private record Role(EntityType entity, DataKind kind, SignalEntity signal) {
    }

    /** Fixtures read once per class; the tests never modify a source in place. */
    private static final Map<String, ForwardBackwardCorpusTest.Source> SOURCES = new ConcurrentHashMap<>();

    static Stream<Arguments> writes() {
        return Stream.of(
                Arguments.of("small.unpacked.mzpeak", "point"),
                Arguments.of("small.unpacked.mzpeak", "SLOF"),
                Arguments.of("small.unpacked.mzpeak", "PIC"),
                Arguments.of("has_uv.mzpeak", "point"),
                Arguments.of("has_uv.mzpeak", "SLOF"));
    }

    // ---- (a), (b): the index of every signal file ---------------------------------------------------------------

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("writes")
    void everySignalFileHasTheArrayIndexOfItsColumns(String fixture, String encoding, @TempDir Path tmp) {
        Path out = write(fixture, encoding, tmp);
        List<String> checked = new ArrayList<>();
        try (MzPeakSource source = MzPeakSource.open(out)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            for (Role role : ROLES) {
                String name = manifest.find(role.entity(), role.kind()).map(MzPeakManifest.Entry::name).orElse(null);
                if (name == null) {
                    continue;
                }
                ParquetFooter footer = ParquetFooter.read(source.inputFile(name));
                assertThat(footer.keyValue()).as("%s footer", name).containsKey(role.signal().arrayIndexKey());
                ArrayIndex index = ArrayIndex.parse(footer.keyValue().get(role.signal().arrayIndexKey()),
                        role.signal());
                boolean chunked = role.signal() == SignalEntity.SPECTRUM && !encoding.equals("point");
                assertThat(index.prefix()).as(name).isEqualTo(chunked ? "chunk" : "point");
                assertThat(index.entries()).as("%s entries", name)
                        .containsExactlyElementsOf(role.signal() == SignalEntity.CHROMATOGRAM
                                ? chromatogramEntries(fixture) : expectedEntries(role.signal(), encoding));
                // rank 0 is the index, rank 1 the entity's main axis, and the intensity is listed
                assertThat(index.entries().get(0).format()).isEqualTo(BufferFormat.INDEX);
                assertThat(index.entries().get(0).sortingRank()).isZero();
                assertThat(index.entries()).filteredOn(e -> Integer.valueOf(1).equals(e.sortingRank()))
                        .isNotEmpty().allSatisfy(e -> assertThat(e.arrayType()).isEqualTo(role.signal().defaultAxis()));
                assertThat(index.entries()).extracting(Entry::arrayType).contains(INTENSITY);
                // every column of the prefix struct is described, so no column needs its name parsed
                assertThat(index.entries()).extracting(Entry::field).containsExactlyElementsOf(
                        footer.schema().getType(index.prefix()).asGroupType().getFields().stream()
                                .map(Type::getName).toList());
                assertThat(planSource(source, manifest, role, name)).as("%s plan", name).isEqualTo(Source.FOOTER);
                checked.add(name);
            }
        }
        assertThat(checked).as("signal members of %s", out).isNotEmpty();
    }

    /**
     * Spectrum intensity is in detector counts; chromatogram intensity is in one column per intensity unit of the
     * chromatograms (MZ-2), has_uv's TIC in MS:1000131 and its DAD chromatogram in UO:0000269; wavelength intensity has
     * no unit.
     */
    @Test
    void spectrumIntensityIsInCountsAndChromatogramIntensityInTheUnitOfEachChromatogram(@TempDir Path tmp) {
        Path out = write("has_uv.mzpeak", "point", tmp);

        assertThat(intensityUnits(out, EntityType.SPECTRUM, DataKind.PEAKS, SignalEntity.SPECTRUM))
                .containsExactly(COUNTS);
        assertThat(intensityUnits(out, EntityType.CHROMATOGRAM, DataKind.DATA_ARRAYS, SignalEntity.CHROMATOGRAM))
                .containsExactly(COUNTS, ABSORBANCE);
        assertThat(intensityUnits(out, EntityType.WAVELENGTH_SPECTRUM, DataKind.DATA_ARRAYS,
                SignalEntity.WAVELENGTH_SPECTRUM)).containsExactly((String) null);
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.chromatograms()).extracting(Chromatogram::intensityUnit)
                    .containsExactly(COUNTS, ABSORBANCE);
        }
    }

    // ---- (c): counts --------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("writes")
    void countsAreTheArchiveEntitiesAndThePointsOfEachFile(String fixture, String encoding, @TempDir Path tmp) {
        ForwardBackwardCorpusTest.Source input = source(fixture);
        Path out = write(fixture, encoding, tmp);
        try (MzPeakSource source = MzPeakSource.open(out)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            String metadata = manifest.find(EntityType.SPECTRUM, DataKind.METADATA).orElseThrow().name();
            assertThat(ParquetFooter.read(source.inputFile(metadata)).keyValue())
                    .containsEntry("spectrum_count", String.valueOf(input.spectra.size()));
            for (Role role : ROLES) {
                String name = manifest.find(role.entity(), role.kind()).map(MzPeakManifest.Entry::name).orElse(null);
                if (name == null) {
                    continue;
                }
                int entities = switch (role.signal()) {
                    case SPECTRUM -> input.spectra.size();
                    case CHROMATOGRAM -> input.chromatograms.size();
                    case WAVELENGTH_SPECTRUM -> input.wavelengthSpectra.size();
                };
                Map<String, String> keyValue = ParquetFooter.read(source.inputFile(name)).keyValue();
                assertThat(keyValue).as(name)
                        .containsEntry(role.signal().key() + "_count", String.valueOf(entities))
                        .containsEntry(role.signal().key() + "_data_point_count",
                                String.valueOf(storedPoints(out.resolve(name))));
            }
        }
    }

    /**
     * A file without rows still records its index and counts: the Numpress spectra_data of a dataset whose one
     * spectrum is a CENTROID peak copy (written to spectra_peaks only), and the chromatograms_data of a dataset whose
     * one chromatogram is empty.
     */
    @Test
    void aFileWithoutRowsHasTheIndexAndZeroPoints(@TempDir Path tmp) {
        Spectrum centroid = new Spectrum(description(3, SignalContinuity.CENTROID), new double[] {100, 200},
                new double[] {1, 2}, List.of(new CentroidPeak(100, 1, 0), new CentroidPeak(200, 2, 1)));
        Chromatogram empty = new Chromatogram(0, "TIC", "MS:1000235", new double[0], new double[0]);
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(centroid), List.of(empty), List.of(), List.of(), FileMetadata.EMPTY,
                SLOF);

        ParquetFooter data = ParquetFooter.read(new LocalInputFile(out.resolve("spectra_data.parquet")));
        assertThat(data.rowCount()).isZero();
        assertThat(ArrayIndex.parse(data.keyValue().get("spectrum_array_index"), SignalEntity.SPECTRUM).entries())
                .containsExactlyElementsOf(expectedEntries(SignalEntity.SPECTRUM, "SLOF"));
        assertThat(data.keyValue()).containsEntry("spectrum_count", "1")
                .containsEntry("spectrum_data_point_count", "0");
        ParquetFooter peaks = ParquetFooter.read(new LocalInputFile(out.resolve("spectra_peaks.parquet")));
        assertThat(peaks.keyValue()).containsEntry("spectrum_count", "1")
                .containsEntry("spectrum_data_point_count", "2");

        ParquetFooter chromatograms = ParquetFooter.read(
                new LocalInputFile(out.resolve("chromatograms_data.parquet")));
        assertThat(chromatograms.rowCount()).isZero();
        assertThat(ArrayIndex.parse(chromatograms.keyValue().get("chromatogram_array_index"),
                SignalEntity.CHROMATOGRAM).entries())
                .containsExactlyElementsOf(expectedEntries(SignalEntity.CHROMATOGRAM, "point"));
        assertThat(chromatograms.keyValue()).containsEntry("chromatogram_count", "1")
                .containsEntry("chromatogram_data_point_count", "0");
    }

    // ---- (e): statistics and offset index -----------------------------------------------------------------------

    /**
     * Every column chunk of every member is ZSTD-compressed and has statistics, with min/max whenever it holds a
     * value, and a page index (offset and column index; spec conformance.md requires one for the index and coordinate
     * columns). A column that holds no value (the empty {@code mz_chunk_values} lists) has only a null count.
     */
    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("writes")
    void everyColumnChunkHasStatisticsAndAPageIndex(String fixture, String encoding, @TempDir Path tmp)
            throws IOException {
        Path out = write(fixture, encoding, tmp);
        int columns = 0;
        try (Stream<Path> members = Files.list(out)) {
            for (Path member : members.filter(p -> p.toString().endsWith(".parquet")).sorted().toList()) {
                try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(member),
                        ParquetGroups.readOptions())) {
                    for (BlockMetaData block : reader.getFooter().getBlocks()) {
                        for (ColumnChunkMetaData column : block.getColumns()) {
                            String what = member.getFileName() + " " + column.getPath();
                            assertThat(column.getCodec()).as("%s codec", what).isEqualTo(CompressionCodecName.ZSTD);
                            Statistics<?> stats = column.getStatistics();
                            assertThat(stats).as("%s statistics", what).isNotNull();
                            assertThat(stats.isEmpty()).as("%s statistics empty", what).isFalse();
                            if (column.getValueCount() > stats.getNumNulls()) {
                                assertThat(stats.hasNonNullValue()).as("%s min/max", what).isTrue();
                                assertThat(stats.genericGetMin()).as("%s min", what).isNotNull();
                                assertThat(stats.genericGetMax()).as("%s max", what).isNotNull();
                            }
                            assertThat(reader.readOffsetIndex(column)).as("%s offset index", what).isNotNull();
                            assertThat(reader.readColumnIndex(column)).as("%s column index", what).isNotNull();
                            columns++;
                        }
                    }
                }
            }
        }
        assertThat(columns).isPositive();
    }

    // ---- (f): WR-14's re-sorted axis under the rank-1 entry -----------------------------------------------------

    @Test
    void reSortedAxisIsStoredAscendingUnderTheRankOneEntry(@TempDir Path tmp) {
        Spectrum reversed = new Spectrum(description(7, SignalContinuity.PROFILE), new double[] {5, 4, 3, 2, 1},
                new double[] {50, 40, 30, 20, 10}, List.of());

        Path point = tmp.resolve("point.mzpeak");
        MzPeakWriter.writeDirectory(point, List.of(reversed), List.of(), List.of(), List.of(), FileMetadata.EMPTY,
                POINT);
        ArrayIndex pointIndex = spectraDataIndex(point);
        Entry axis = rankOne(pointIndex, BufferFormat.POINT);
        assertThat(axis.path()).isEqualTo("point.mz");
        List<Double> stored = new ArrayList<>();
        ParquetGroups.forEach(point.resolve("spectra_data.parquet"),
                row -> stored.add(ParquetGroups.optDouble(ParquetGroups.optGroup(row, "point"), axis.field())));
        assertThat(stored).containsExactly(1.0, 2.0, 3.0, 4.0, 5.0);

        Path numpress = tmp.resolve("numpress.mzpeak");
        MzPeakWriter.writeDirectory(numpress, List.of(reversed), List.of(), List.of(), List.of(), FileMetadata.EMPTY,
                SLOF);
        ArrayIndex chunkIndex = spectraDataIndex(numpress);
        Entry linear = rankOne(chunkIndex, BufferFormat.CHUNK_TRANSFORM);
        assertThat(linear.transform()).isEqualTo(MSNumpress.ACC_NUMPRESS_LINEAR);
        Entry start = rankOne(chunkIndex, BufferFormat.CHUNK_START);
        List<Double> decoded = new ArrayList<>();
        ParquetGroups.forEach(numpress.resolve("spectra_data.parquet"), row -> {
            Group chunk = ParquetGroups.optGroup(row, "chunk");
            assertThat(ParquetGroups.optDouble(chunk, start.field())).isCloseTo(1.0, within(1e-5));
            byte[] bytes = ParquetGroups.byteList(chunk, linear.field());
            for (double v : MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, bytes, bytes.length)) {
                decoded.add(v);
            }
        });
        assertThat(decoded).hasSize(5);
        for (int i = 0; i < decoded.size(); i++) {
            assertThat(decoded.get(i)).as("m/z %d", i).isCloseTo(i + 1.0, within(1e-5));
        }
    }

    // ---- ArrayIndex.toJson --------------------------------------------------------------------------------------

    /**
     * {@link ArrayIndex#toJson} of every fixture index reads back equal, so the writer's JSON carries every component
     * the reader models (null priorities and ranks, transforms, units, array names and the legacy pin-era entries).
     */
    @Test
    void theJsonOfEveryFixtureIndexParsesBackEqual() {
        List<String> checked = new ArrayList<>();
        Stream.concat(Fixtures.CURRENT_NAMES.stream().map(Fixtures::current),
                Fixtures.LEGACY_NAMES.stream().map(Fixtures::legacy)).forEach(dataset -> {
            try (MzPeakSource source = MzPeakSource.open(dataset)) {
                MzPeakManifest manifest = MzPeakManifest.fromSource(source);
                for (Role role : ROLES) {
                    manifest.find(role.entity(), role.kind()).ifPresent(member -> {
                        String json = ParquetFooter.read(source.inputFile(member.name())).keyValue()
                                .get(role.signal().arrayIndexKey());
                        ArrayIndex index = ArrayIndex.parse(json, role.signal());
                        assertThat(ArrayIndex.parse(index.toJson(), role.signal()))
                                .as("%s %s", dataset.getFileName(), member.name()).isEqualTo(index);
                        checked.add(dataset.getFileName() + " " + member.name());
                    });
                }
            }
        });
        assertThat(checked).hasSizeGreaterThan(30);
    }

    /**
     * A written entry has exactly the keys the reference writes, which are the fields the HEAD Python reader's
     * {@code ArrayIndexEntry} accepts (it rejects any other key), absent values as JSON null.
     */
    @Test
    void writtenEntriesHaveTheReferenceKeys(@TempDir Path tmp) throws IOException {
        Path out = write("has_uv.mzpeak", "point", tmp);
        String json = ArrayIndexTest.footer(out, EntityType.CHROMATOGRAM, DataKind.DATA_ARRAYS).keyValue()
                .get("chromatogram_array_index");
        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.get("prefix").asText()).isEqualTo("point");
        // index, time, the intensity columns of MS:1000131 and UO:0000269, and the TIC's ms level (MZ-2)
        assertThat(root.get("entries")).hasSize(5);
        for (JsonNode entry : root.get("entries")) {
            List<String> keys = new ArrayList<>();
            entry.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).containsExactly("context", "path", "data_type", "array_type", "array_name", "unit",
                    "buffer_format", "transform", "data_processing_id", "buffer_priority", "sorting_rank");
        }
        assertThat(root.get("entries").get(0).get("unit").isNull()).isTrue();
        JsonNode absorbance = root.get("entries").get(3);
        assertThat(absorbance.get("sorting_rank").isNull()).isTrue();
        assertThat(absorbance.get("buffer_priority").isNull()).isTrue();
        assertThat(root.get("entries").get(0).get("buffer_format").asText()).isEqualTo("index");
        assertThat(root.get("entries").get(1).get("sorting_rank").asInt()).isEqualTo(1);
    }

    // ---- expected entries ---------------------------------------------------------------------------------------

    /** The entries the writer lists for a file of {@code entity} written in {@code encoding}, in column order. */
    static List<Entry> expectedEntries(SignalEntity entity, String encoding) {
        if (entity == SignalEntity.SPECTRUM && !encoding.equals("point")) {
            boolean pic = encoding.equals("PIC");
            return List.of(
                    index("chunk", entity),
                    axis("chunk", "mz_chunk_start", entity, BufferFormat.CHUNK_START, null),
                    axis("chunk", "mz_chunk_end", entity, BufferFormat.CHUNK_END, null),
                    axis("chunk", "mz_chunk_values", entity, BufferFormat.CHUNK_VALUES, null),
                    axis("chunk", "chunk_encoding", entity, BufferFormat.CHUNK_ENCODING, null),
                    axis("chunk", "mz_numpress_linear_bytes", entity, BufferFormat.CHUNK_TRANSFORM,
                            MSNumpress.ACC_NUMPRESS_LINEAR),
                    intensity("chunk", pic ? "intensity_numpress_pic_bytes" : "intensity_numpress_slof_bytes",
                            entity, BufferFormat.CHUNK_TRANSFORM,
                            pic ? MSNumpress.ACC_NUMPRESS_PIC : MSNumpress.ACC_NUMPRESS_SLOF));
        }
        String axisField = switch (entity) {
            case SPECTRUM -> "mz";
            case CHROMATOGRAM -> "time";
            case WAVELENGTH_SPECTRUM -> "wavelength";
        };
        return List.of(index("point", entity), axis("point", axisField, entity, BufferFormat.POINT, null),
                intensity("point", "intensity", entity, BufferFormat.POINT, null));
    }

    /**
     * The entries the writer lists for the chromatograms_data of the legacy {@code fixture}: the index, the time, the
     * intensity in MS:1000131 and, for has_uv, its absorbance column without buffer priority, then the TIC's
     * {@code ms level} array in UO:0000186.
     */
    private static List<Entry> chromatogramEntries(String fixture) {
        SignalEntity entity = SignalEntity.CHROMATOGRAM;
        List<Entry> entries = new ArrayList<>(List.of(index("point", entity),
                axis("point", "time", entity, BufferFormat.POINT, null),
                new Entry("point.intensity", "intensity", INTENSITY, FLOAT64, COUNTS, BufferFormat.POINT, null, null,
                        "primary", "intensity array", entity.key(), null)));
        if (fixture.startsWith("has_uv")) {
            entries.add(new Entry("point.intensity_UO_0000269", "intensity_UO_0000269", INTENSITY, FLOAT64,
                    ABSORBANCE, BufferFormat.POINT, null, null, null, "intensity array", entity.key(), null));
        }
        entries.add(new Entry("point.ms_level", "ms_level", "MS:1000786", FLOAT64, "UO:0000186", BufferFormat.POINT,
                null, null, "primary", "ms level", entity.key(), null));
        return entries;
    }

    private static Entry index(String prefix, SignalEntity entity) {
        return new Entry(prefix + "." + entity.indexField(), entity.indexField(), INDEX_ARRAY, INT64, null,
                BufferFormat.INDEX, null, 0, "primary", "index array", entity.key(), null);
    }

    private static Entry axis(String prefix, String field, SignalEntity entity, BufferFormat format,
                              String transform) {
        String type = switch (entity) {
            case SPECTRUM -> MZ;
            case CHROMATOGRAM -> TIME;
            case WAVELENGTH_SPECTRUM -> WAVELENGTH;
        };
        String unit = switch (entity) {
            case SPECTRUM -> MZ_UNIT;
            case CHROMATOGRAM -> MINUTE;
            case WAVELENGTH_SPECTRUM -> NANOMETER;
        };
        String name = switch (entity) {
            case SPECTRUM -> "m/z array";
            case CHROMATOGRAM -> "time array";
            case WAVELENGTH_SPECTRUM -> "wavelength array";
        };
        return new Entry(prefix + "." + field, field, type, FLOAT64, unit, format, transform, 1, "primary", name,
                entity.key(), null);
    }

    private static Entry intensity(String prefix, String field, SignalEntity entity, BufferFormat format,
                                   String transform) {
        return new Entry(prefix + "." + field, field, INTENSITY, FLOAT64,
                entity == SignalEntity.SPECTRUM ? COUNTS : null, format, transform, null, "primary",
                "intensity array", entity.key(), null);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static ForwardBackwardCorpusTest.Source source(String fixture) {
        return SOURCES.computeIfAbsent(fixture, f -> ForwardBackwardCorpusTest.readSource(Fixtures.legacy(f)));
    }

    private static Path write(String fixture, String encoding, Path tmp) {
        ForwardBackwardCorpusTest.Source s = source(fixture);
        Options options = switch (encoding) {
            case "point" -> POINT;
            case "SLOF" -> SLOF;
            case "PIC" -> PIC;
            default -> throw new IllegalArgumentException(encoding);
        };
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, options);
        return out;
    }

    /** The source of the plan the reader's store builds for the member {@code name} of {@code role}. */
    private static Source planSource(MzPeakSource source, MzPeakManifest manifest, Role role, String name) {
        return switch (role.signal()) {
            case SPECTRUM -> {
                try (SpectrumArrayStore store = SpectrumArrayStore.load(source.inputFile(name), false, Map.of())) {
                    yield store.plan().source();
                }
            }
            case CHROMATOGRAM -> ChromatogramStore.load(source, manifest).plan().source();
            case WAVELENGTH_SPECTRUM -> WavelengthSpectrumStore.load(source, manifest).plan().source();
        };
    }

    /** The units of the intensity entries in the index of the member listed under (entity, kind). */
    private static List<String> intensityUnits(Path dataset, EntityType entity, DataKind kind, SignalEntity signal) {
        ParquetFooter footer = ArrayIndexTest.footer(dataset, entity, kind);
        return ArrayIndex.parse(footer.keyValue().get(signal.arrayIndexKey()), signal).entries().stream()
                .filter(e -> INTENSITY.equals(e.arrayType())).map(Entry::unit).toList();
    }

    /**
     * The points stored in a signal file, counted from the rows: one per point row, and per chunk row the m/z its
     * Numpress linear bytes decode to.
     */
    private static long storedPoints(Path file) {
        long[] points = {0};
        ParquetGroups.forEach(file, row -> {
            Group chunk = ParquetGroups.optGroup(row, "chunk");
            if (chunk == null) {
                points[0]++;
            } else {
                byte[] bytes = ParquetGroups.byteList(chunk, "mz_numpress_linear_bytes");
                points[0] += MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, bytes, bytes.length).length;
            }
        });
        return points[0];
    }

    private static ArrayIndex spectraDataIndex(Path dataset) {
        ParquetFooter footer = ParquetFooter.read(new LocalInputFile(dataset.resolve("spectra_data.parquet")));
        String json = footer.keyValue().get(SignalEntity.SPECTRUM.arrayIndexKey());
        assertThat(json).as("spectrum_array_index of %s", dataset).isNotNull();
        return ArrayIndex.parse(json, SignalEntity.SPECTRUM);
    }

    private static Entry rankOne(ArrayIndex index, BufferFormat format) {
        return index.entries().stream()
                .filter(e -> Integer.valueOf(1).equals(e.sortingRank()) && e.format() == format)
                .findFirst().orElseThrow(() -> new AssertionError("no rank-1 " + format + " entry in " + index));
    }

    private static SpectrumDescription description(long index, SignalContinuity continuity) {
        return new SpectrumDescription(index, "synthetic=" + index, 1, 1.0, null, continuity, 0, 0, List.of(),
                List.of(), List.of());
    }
}
