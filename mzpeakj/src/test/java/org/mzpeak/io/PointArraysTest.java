package org.mzpeak.io;

import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PointArrays#groupByIndex} for the plans and rows that {@link WavelengthSpectrumStore}, its only caller, never
 * passes it: a plan in chunk layout, a plan without a main-axis values column (the store rejects both before grouping),
 * and a row whose point struct is null. The plans are the one the store builds for the wavelength data member of the
 * current has_uv fixture, with the layout or the axis changed; the null struct needs a constructed member.
 */
class PointArraysTest {

    private static final MessageType POINT = MessageTypeParser.parseMessageType("""
            message m {
              optional group point {
                optional int64 wavelength_spectrum_index (INTEGER(64,false));
                optional double wavelength;
                optional float intensity;
              }
            }""");

    /** The data member name in errors. */
    private static final String MEMBER = "wavelength_spectra_data.parquet";

    @TempDir
    Path tmp;

    @Test
    void aChunkLayoutPlanGivesAnEmptyMapThoughTheMemberHasPointRows() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current("has_uv.mzpeak"))) {
            InputFile data = wavelengthData(source);
            SignalPlan plan = plan(data);
            assertThat(PointArrays.groupByIndex(data, plan, MEMBER)).as("the member's point rows").isNotEmpty();

            SignalPlan chunk = new SignalPlan(plan.entity(), plan.source(), SignalPlan.Layout.CHUNK, plan.prefix(),
                    plan.indexField(), plan.axis(), plan.intensities(), plan.extras());
            assertThat(PointArrays.groupByIndex(data, chunk, MEMBER)).isEmpty();
        }
    }

    @Test
    void aPlanWithoutWavelengthValuesReadsEveryWavelengthAsNaN() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current("has_uv.mzpeak"))) {
            InputFile data = wavelengthData(source);
            SignalPlan plan = plan(data);
            assertThat(plan.axis().values()).isNotNull();
            Map<Long, PointArrays.XY> stored = PointArrays.groupByIndex(data, plan, MEMBER);

            SignalPlan.Axis axis = plan.axis();
            SignalPlan noValues = new SignalPlan(plan.entity(), plan.source(), plan.layout(), plan.prefix(),
                    plan.indexField(), new SignalPlan.Axis(axis.arrayType(), null, axis.start(), axis.end(),
                    axis.encoding(), axis.transformFields()), plan.intensities(), plan.extras());
            Map<Long, PointArrays.XY> read = PointArrays.groupByIndex(data, noValues, MEMBER);

            assertThat(read.keySet()).containsExactlyElementsOf(stored.keySet());
            stored.forEach((index, xy) -> {
                assertThat(xy.x()).as("stored wavelengths of %s", index).isNotEmpty().doesNotContain(Double.NaN);
                assertThat(read.get(index).x()).as("wavelengths of %s", index).hasSameSizeAs(xy.x())
                        .containsOnly(Double.NaN);
                assertThat(read.get(index).intensity()).as("intensities of %s", index)
                        .containsExactly(xy.intensity());
            });
        }
    }

    @Test
    void aRowWithoutThePointStructIsSkipped() {
        SimpleGroupFactory rows = new SimpleGroupFactory(POINT);
        Path file = tmp.resolve("wavelength_spectra_data.parquet");
        write(file, point(rows, 0, 200.0, 1.5f), rows.newGroup(), point(rows, 0, 201.0, 2.5f), rows.newGroup(),
                point(rows, 1, 250.0, 3.5f));
        InputFile data = new LocalInputFile(file);

        Map<Long, PointArrays.XY> read = PointArrays.groupByIndex(data, plan(data), MEMBER);

        assertThat(read.keySet()).containsExactly(0L, 1L);
        assertThat(read.get(0L).x()).containsExactly(200.0, 201.0);
        assertThat(read.get(0L).intensity()).containsExactly(1.5, 2.5);
        assertThat(read.get(1L).x()).containsExactly(250.0);
        assertThat(read.get(1L).intensity()).containsExactly(3.5);
    }

    /** The wavelength spectrum data member of {@code source}, found by its manifest role. */
    private static InputFile wavelengthData(MzPeakSource source) {
        String name = MzPeakManifest.fromSource(source).find(EntityType.WAVELENGTH_SPECTRUM, DataKind.DATA_ARRAYS)
                .orElseThrow().name();
        return source.inputFile(name);
    }

    /** The plan {@link WavelengthSpectrumStore} builds for {@code data}. */
    private static SignalPlan plan(InputFile data) {
        ParquetFooter footer = ParquetFooter.read(data);
        return SignalPlan.of(ArrayIndex.of(footer.keyValue(), footer.schema(), SignalEntity.WAVELENGTH_SPECTRUM),
                footer.schema(), SignalEntity.WAVELENGTH_SPECTRUM);
    }

    private static Group point(SimpleGroupFactory rows, long index, double wavelength, float intensity) {
        Group row = rows.newGroup();
        row.addGroup("point").append("wavelength_spectrum_index", index).append("wavelength", wavelength)
                .append("intensity", intensity);
        return row;
    }

    private static void write(Path file, Group... rows) {
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(POINT)
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
