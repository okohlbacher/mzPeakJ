package org.mzpeak.io;

import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.ArrayIndex.BufferFormat;
import org.mzpeak.io.ArrayIndex.Entry;
import org.mzpeak.io.ArrayIndex.Source;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * RD-21: the {@code <entity>_array_index} footer parsed into {@link ArrayIndex}, checked against the footers of the
 * vendored HUPO-PSI fixtures (current set 2ee2589, legacy set 29e59b2), plus the INFERRED fallback for a missing or
 * malformed footer.
 */
class ArrayIndexTest {

    private static final MessageType POINT_SCHEMA = MessageTypeParser.parseMessageType("""
            message m {
              optional group point {
                optional int64 spectrum_index;
                optional double mz;
                optional float intensity;
              }
            }""");

    /** The footer of the member {@code manifest} lists under (entity, kind) in {@code dataset}. */
    static ParquetFooter footer(Path dataset, EntityType entity, DataKind kind) {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            String name = manifest.find(entity, kind).orElseThrow().name();
            return ParquetFooter.read(source.inputFile(name));
        }
    }

    static ArrayIndex index(Path dataset, EntityType entity, DataKind kind, SignalEntity signal) {
        ParquetFooter f = footer(dataset, entity, kind);
        return ArrayIndex.of(f.keyValue(), f.schema(), signal);
    }

    @Test
    void currentSmallSpectrumDataHasIndexAxisAndIntensityEntries() {
        ArrayIndex index = index(Fixtures.current("small.mzpeak"), EntityType.SPECTRUM, DataKind.DATA_ARRAYS,
                SignalEntity.SPECTRUM);

        assertThat(index.source()).isEqualTo(Source.FOOTER);
        assertThat(index.entity()).isEqualTo(SignalEntity.SPECTRUM);
        assertThat(index.prefix()).isEqualTo("point");
        assertThat(index.entries()).hasSize(3);
        assertThat(index.entries().get(0)).isEqualTo(new Entry("point.spectrum_index", "spectrum_index",
                "MS:1003870", "MS:1000522", null, BufferFormat.INDEX, null, 0, "primary", "index array", "spectrum",
                null));
        assertThat(index.entries().get(1)).isEqualTo(new Entry("point.mz", "mz", "MS:1000514", "MS:1000523",
                "MS:1000040", BufferFormat.POINT, "MS:1003902", 1, "primary", "m/z array", "spectrum", null));
        assertThat(index.entries().get(2)).isEqualTo(new Entry("point.intensity", "intensity", "MS:1000515",
                "MS:1000521", "MS:1000131", BufferFormat.POINT, "MS:1003901", null, "primary", "intensity array",
                "spectrum", null));
    }

    @Test
    void legacySmallMzIsRankZeroWithoutAnIndexEntry() {
        ArrayIndex index = index(Fixtures.legacy("small.mzpeak"), EntityType.SPECTRUM, DataKind.DATA_ARRAYS,
                SignalEntity.SPECTRUM);

        assertThat(index.source()).isEqualTo(Source.FOOTER);
        assertThat(index.entries()).extracting(Entry::path, Entry::format, Entry::sortingRank).containsExactly(
                tuple("point.mz", BufferFormat.POINT, 0),
                tuple("point.intensity", BufferFormat.POINT, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"current", "legacy"})
    void smallNumpressListsTheNumpressTransforms(String set) {
        Path dataset = set.equals("current") ? Fixtures.current("small.numpress.mzpeak")
                : Fixtures.legacy("small.numpress.mzpeak");
        ArrayIndex index = index(dataset, EntityType.SPECTRUM, DataKind.DATA_ARRAYS, SignalEntity.SPECTRUM);

        assertThat(index.prefix()).isEqualTo("chunk");
        assertThat(index.entries()).filteredOn(e -> e.format() == BufferFormat.CHUNK_TRANSFORM)
                .extracting(Entry::field, Entry::arrayType, Entry::transform).containsExactly(
                        tuple("mz_numpress_linear_bytes", "MS:1000514", "MS:1002312"),
                        tuple("intensity_numpress_slof_bytes", "MS:1000515", "MS:1002314"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"current", "legacy"})
    void hasUvAbsorbanceColumnCarriesItsUnit(String set) {
        Path dataset = set.equals("current") ? Fixtures.current("has_uv.mzpeak") : Fixtures.legacy("has_uv.mzpeak");
        ArrayIndex index = index(dataset, EntityType.CHROMATOGRAM, DataKind.DATA_ARRAYS, SignalEntity.CHROMATOGRAM);

        Entry absorbance = index.entries().stream().filter(e -> e.field().equals("intensity_f32_au"))
                .findFirst().orElseThrow();
        assertThat(absorbance.path()).isEqualTo("point.intensity_f32_au");
        assertThat(absorbance.arrayType()).isEqualTo("MS:1000515");
        assertThat(absorbance.unit()).isEqualTo("UO:0000269");
        assertThat(absorbance.bufferPriority()).isNull();
    }

    @Test
    void wavelengthIndexIsReadFromItsOwnFooterKey() {
        assertThat(SignalEntity.WAVELENGTH_SPECTRUM.arrayIndexKey()).isEqualTo("wavelength_spectrum_array_index");
        assertThat(SignalEntity.SPECTRUM.arrayIndexKey()).isEqualTo("spectrum_array_index");
        assertThat(SignalEntity.CHROMATOGRAM.arrayIndexKey()).isEqualTo("chromatogram_array_index");

        ParquetFooter f = footer(Fixtures.current("has_uv.mzpeak"), EntityType.WAVELENGTH_SPECTRUM,
                DataKind.DATA_ARRAYS);
        assertThat(f.keyValue()).containsKey("wavelength_spectrum_array_index")
                .doesNotContainKey("spectrum_array_index");
        ArrayIndex index = ArrayIndex.of(f.keyValue(), f.schema(), SignalEntity.WAVELENGTH_SPECTRUM);
        assertThat(index.source()).isEqualTo(Source.FOOTER);
        assertThat(index.entries()).extracting(Entry::path, Entry::arrayType, Entry::sortingRank).containsExactly(
                tuple("point.wavelength_spectrum_index", "MS:1003870", 0),
                tuple("point.wavelength", "MS:1000617", 1),
                tuple("point.intensity", "MS:1000515", null));
    }

    @Test
    void bufferFormatParsesLowerCasedNamesAndTheSecondaryChunkAlias() {
        assertThat(BufferFormat.parse("point")).isEqualTo(BufferFormat.POINT);
        assertThat(BufferFormat.parse("chunk_values")).isEqualTo(BufferFormat.CHUNK_VALUES);
        assertThat(BufferFormat.parse("chunk_secondary")).isEqualTo(BufferFormat.CHUNK_SECONDARY);
        assertThat(BufferFormat.parse("secondary_chunk")).isEqualTo(BufferFormat.CHUNK_SECONDARY);
        assertThat(BufferFormat.parse("Secondary_Chunk")).isEqualTo(BufferFormat.CHUNK_SECONDARY);
        assertThat(BufferFormat.parse("chunk_start")).isEqualTo(BufferFormat.CHUNK_START);
        assertThat(BufferFormat.parse("chunk_end")).isEqualTo(BufferFormat.CHUNK_END);
        assertThat(BufferFormat.parse("CHUNK_ENCODING")).isEqualTo(BufferFormat.CHUNK_ENCODING);
        assertThat(BufferFormat.parse("chunk_transform")).isEqualTo(BufferFormat.CHUNK_TRANSFORM);
        assertThat(BufferFormat.parse("Index")).isEqualTo(BufferFormat.INDEX);
        assertThat(BufferFormat.parse("chunked")).isEqualTo(BufferFormat.UNKNOWN);
        assertThat(BufferFormat.parse("")).isEqualTo(BufferFormat.UNKNOWN);
        assertThat(BufferFormat.parse(null)).isEqualTo(BufferFormat.UNKNOWN);
    }

    @Test
    void parseKeepsAbsentComponentsNullAndUnknownFormatsUnknown() {
        ArrayIndex index = ArrayIndex.parse("""
                {"prefix": "point", "entries": [
                  {"path": "point.mz", "array_type": "MS:1000514", "buffer_format": "point"},
                  {"path": "point.extra", "buffer_format": "bespoke", "sorting_rank": null, "unit": null}
                ]}""", SignalEntity.SPECTRUM);

        assertThat(index.source()).isEqualTo(Source.FOOTER);
        assertThat(index.entries()).containsExactly(
                new Entry("point.mz", "mz", "MS:1000514", null, null, BufferFormat.POINT, null, null, null, null,
                        null, null),
                new Entry("point.extra", "extra", null, null, null, BufferFormat.UNKNOWN, null, null, null, null,
                        null, null));
        assertThatThrownBy(() -> index.entries().add(index.entries().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{not json",
            "",
            "[]",
            "{\"entries\": []}",
            "{\"prefix\": 1, \"entries\": []}",
            "{\"prefix\": \"point\"}",
            "{\"prefix\": \"point\", \"entries\": {}}",
            "{\"prefix\": \"point\", \"entries\": [1]}",
            "{\"prefix\": \"point\", \"entries\": [{\"array_type\": \"MS:1000514\"}]}",
            "{\"prefix\": \"point\", \"entries\": [{\"path\": \"point.mz\", \"unit\": 7}]}",
            "{\"prefix\": \"point\", \"entries\": [{\"path\": \"point.mz\", \"sorting_rank\": \"1\"}]}",
            "{\"prefix\": \"point\", \"entries\": [{\"path\": \"point.mz\", \"sorting_rank\": 1.5}]}"})
    void malformedFooterThrowsOnParseAndFallsBackToInferredWithOneWarning(String json) {
        assertThatThrownBy(() -> ArrayIndex.parse(json, SignalEntity.SPECTRUM))
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("malformed spectrum_array_index footer: ");

        List<LogRecord> records = captureLog(ArrayIndex.class, () -> {
            ArrayIndex index = ArrayIndex.of(Map.of("spectrum_array_index", json), POINT_SCHEMA,
                    SignalEntity.SPECTRUM);
            assertThat(index).isEqualTo(ArrayIndex.infer(POINT_SCHEMA, SignalEntity.SPECTRUM));
            assertThat(index.source()).isEqualTo(Source.INFERRED);
        });
        assertThat(records).singleElement().satisfies(r -> {
            assertThat(r.getLevel()).isEqualTo(Level.WARNING);
            assertThat(r.getMessage()).contains("malformed spectrum_array_index footer", "by name");
        });
    }

    @Test
    void missingFooterGivesInferredWithoutWarning() {
        List<LogRecord> records = captureLog(ArrayIndex.class, () -> {
            assertThat(ArrayIndex.of(Map.of(), POINT_SCHEMA, SignalEntity.SPECTRUM).source())
                    .isEqualTo(Source.INFERRED);
            assertThat(ArrayIndex.of(null, POINT_SCHEMA, SignalEntity.SPECTRUM).source())
                    .isEqualTo(Source.INFERRED);
            // another entity's index is not this entity's
            assertThat(ArrayIndex.of(Map.of("chromatogram_array_index", "{}"), POINT_SCHEMA, SignalEntity.SPECTRUM)
                    .source()).isEqualTo(Source.INFERRED);
        });
        assertThat(records).filteredOn(r -> r.getLevel().intValue() >= Level.WARNING.intValue()).isEmpty();
    }

    @Test
    void inferListsTheColumnsTheSpectrumDecoderReadsInSchemaOrder() {
        MessageType chunk = MessageTypeParser.parseMessageType("""
                message m {
                  optional group chunk {
                    optional int64 spectrum_index;
                    optional double mz_chunk_start;
                    optional double mz_chunk_end;
                    optional binary chunk_encoding (STRING);
                    optional group mz_numpress_linear_bytes (LIST) { repeated group list { required int32 item; } }
                    optional group intensity_numpress_slof_bytes (LIST) { repeated group list { required int32 item; } }
                    optional group intensity_numpress_pic_bytes (LIST) { repeated group list { required int32 item; } }
                    optional group intensity (LIST) { repeated group list { optional float item; } }
                    optional group mz_grid { required binary grid_type (STRING); }
                    optional double mean_inverse_reduced_ion_mobility;
                  }
                }""");

        ArrayIndex index = ArrayIndex.infer(chunk, SignalEntity.SPECTRUM);

        assertThat(index.source()).isEqualTo(Source.INFERRED);
        assertThat(index.prefix()).isEqualTo("chunk");
        assertThat(index.entries())
                .extracting(Entry::path, Entry::arrayType, Entry::format, Entry::transform, Entry::sortingRank)
                .containsExactly(
                        tuple("chunk.spectrum_index", "MS:1003870", BufferFormat.INDEX, null, 0),
                        tuple("chunk.mz_chunk_start", "MS:1000514", BufferFormat.CHUNK_START, null, 1),
                        tuple("chunk.mz_chunk_end", "MS:1000514", BufferFormat.CHUNK_END, null, 1),
                        tuple("chunk.chunk_encoding", "MS:1000514", BufferFormat.CHUNK_ENCODING, null, 1),
                        tuple("chunk.mz_numpress_linear_bytes", "MS:1000514", BufferFormat.CHUNK_TRANSFORM,
                                "MS:1002312", 1),
                        tuple("chunk.intensity_numpress_slof_bytes", "MS:1000515", BufferFormat.CHUNK_TRANSFORM,
                                "MS:1002314", null),
                        tuple("chunk.intensity_numpress_pic_bytes", "MS:1000515", BufferFormat.CHUNK_TRANSFORM,
                                "MS:1002313", null),
                        tuple("chunk.intensity", "MS:1000515", BufferFormat.CHUNK_SECONDARY, null, null),
                        tuple("chunk.mz_grid", "MS:1000514", BufferFormat.CHUNK_TRANSFORM, "MS:1003826", 1));
        assertThat(index.entries()).allSatisfy(e -> {
            assertThat(e.unit()).isNull();
            assertThat(e.dataType()).isNull();
            assertThat(e.context()).isEqualTo("spectrum");
        });
    }

    @Test
    void inferListsTheChromatogramIntensityFallbackColumns() {
        MessageType point = MessageTypeParser.parseMessageType("""
                message m {
                  optional group point {
                    optional int64 chromatogram_index;
                    optional double time;
                    optional float intensity;
                    optional float intensity_f32_au;
                    optional binary intensity_label (STRING);
                    repeated double intensity_repeated;
                    optional int32 intensity_counts;
                    optional int64 ms_level;
                  }
                }""");
        assertThat(ArrayIndex.infer(point, SignalEntity.CHROMATOGRAM).entries())
                .extracting(Entry::field, Entry::arrayType, Entry::format).containsExactly(
                        tuple("chromatogram_index", "MS:1003870", BufferFormat.INDEX),
                        tuple("time", "MS:1000595", BufferFormat.POINT),
                        tuple("intensity", "MS:1000515", BufferFormat.POINT),
                        tuple("intensity_f32_au", "MS:1000515", BufferFormat.POINT),
                        tuple("intensity_counts", "MS:1000515", BufferFormat.POINT));
        // the spectrum point decoder has no intensity_* fallback
        assertThat(ArrayIndex.infer(point, SignalEntity.SPECTRUM).entries())
                .extracting(Entry::field).containsExactly("intensity");

        MessageType chunk = MessageTypeParser.parseMessageType("""
                message m {
                  optional group chunk {
                    optional int64 chromatogram_index;
                    optional double time_chunk_start;
                    optional double time_chunk_end;
                    optional group time_chunk_values (LIST) { repeated group list { optional double item; } }
                    optional binary chunk_encoding (STRING);
                    optional group ms_level (LIST) { repeated group list { optional int64 item; } }
                    optional group intensity_numpress_slof_bytes (LIST) { repeated group list { required int32 item; } }
                    optional group intensity_numpress_pic_bytes (LIST) { repeated group list { required int32 item; } }
                  }
                }""");
        ArrayIndex index = ArrayIndex.infer(chunk, SignalEntity.CHROMATOGRAM);
        assertThat(index.prefix()).isEqualTo("chunk");
        assertThat(index.entries()).extracting(Entry::field, Entry::format, Entry::transform).containsExactly(
                tuple("chromatogram_index", BufferFormat.INDEX, null),
                tuple("time_chunk_start", BufferFormat.CHUNK_START, null),
                tuple("time_chunk_end", BufferFormat.CHUNK_END, null),
                tuple("time_chunk_values", BufferFormat.CHUNK_VALUES, null),
                tuple("chunk_encoding", BufferFormat.CHUNK_ENCODING, null),
                tuple("intensity_numpress_slof_bytes", BufferFormat.CHUNK_TRANSFORM, "MS:1002314"),
                // FN-4: listed so that a chunk holding only PIC intensities fails instead of reading zeros
                tuple("intensity_numpress_pic_bytes", BufferFormat.CHUNK_TRANSFORM, "MS:1002313"));
    }

    @Test
    void inferListsTheLiteralChromatogramIntensityBeforeItsFallbacksWhateverTheSchemaOrder() {
        // ChromatogramStore reads the literal intensity first and the intensity_* columns, in schema order, only
        // when it is null (Q-U5); an inferred index must keep that priority
        MessageType point = MessageTypeParser.parseMessageType("""
                message m {
                  optional group point {
                    optional int64 chromatogram_index;
                    optional double intensity_f32_au;
                    optional double time;
                    optional int32 intensity_counts;
                    optional float intensity;
                  }
                }""");
        ArrayIndex index = ArrayIndex.infer(point, SignalEntity.CHROMATOGRAM);
        assertThat(index.entries()).extracting(Entry::field).containsExactly(
                "chromatogram_index", "time", "intensity", "intensity_f32_au", "intensity_counts");

        SignalPlan plan = SignalPlan.of(index, point, SignalEntity.CHROMATOGRAM);
        assertThat(plan.intensities()).extracting(SignalPlan.Column::path)
                .containsExactly("point.intensity", "point.intensity_f32_au", "point.intensity_counts");
        assertThat(plan.plainIntensity()).map(SignalPlan.Column::path).contains("point.intensity");
    }

    @Test
    void inferReadsWavelengthSpectraFromPointRowsOnly() {
        MessageType point = MessageTypeParser.parseMessageType("""
                message m {
                  optional group point {
                    optional int64 wavelength_spectrum_index;
                    optional float wavelength;
                    optional float intensity;
                  }
                }""");
        assertThat(ArrayIndex.infer(point, SignalEntity.WAVELENGTH_SPECTRUM).entries())
                .extracting(Entry::path, Entry::arrayType, Entry::sortingRank).containsExactly(
                        tuple("point.wavelength_spectrum_index", "MS:1003870", 0),
                        tuple("point.wavelength", "MS:1000617", 1),
                        tuple("point.intensity", "MS:1000515", null));

        MessageType chunk = MessageTypeParser.parseMessageType("""
                message m {
                  optional group chunk {
                    optional int64 wavelength_spectrum_index;
                    optional double wavelength_chunk_start;
                  }
                }""");
        ArrayIndex index = ArrayIndex.infer(chunk, SignalEntity.WAVELENGTH_SPECTRUM);
        assertThat(index.prefix()).isEqualTo("point");
        assertThat(index.entries()).isEmpty();
    }

    /** Runs {@code action} and returns what it logged through the logger of {@code owner}, at any level. */
    /** A sorting_rank of 2^31 does not fit an int and is malformed, not wrapped to -2^31 (A2). */
    @Test
    void aSortingRankOfTwoToThe31IsMalformed() {
        String json = "{\"prefix\": \"point\", \"entries\": [{\"path\": \"point.mz\","
                + " \"buffer_format\": \"point\", \"array_type\": \"MS:1000514\", \"sorting_rank\": 2147483648}]}";
        assertThatThrownBy(() -> ArrayIndex.parse(json, SignalEntity.SPECTRUM))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("malformed spectrum_array_index footer: entry 0 has a non-integer 'sorting_rank'");
        assertThat(ArrayIndex.parse(json.replace("2147483648", "2147483647"), SignalEntity.SPECTRUM).entries()
                .get(0).sortingRank()).isEqualTo(Integer.MAX_VALUE);
    }

    static List<LogRecord> captureLog(Class<?> owner, Runnable action) {
        List<LogRecord> records = new ArrayList<>();
        Logger log = Logger.getLogger(owner.getName());
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
        log.setLevel(Level.ALL);
        log.addHandler(capture);
        try {
            action.run();
        } finally {
            log.removeHandler(capture);
            log.setLevel(level);
        }
        return records;
    }
}
