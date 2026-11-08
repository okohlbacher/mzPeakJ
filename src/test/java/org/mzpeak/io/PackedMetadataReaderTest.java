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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A packed spectrum metadata member whose {@code scan} column is a scalar instead of the scan struct: no row has a
 * scan struct, so {@link PackedMetadataReader} reads every spectrum without scans and the rest as before. The member
 * is the legacy small.unpacked spectrum metadata member (HUPO-PSI/mzPeak 29e59b2), rewritten with an always-null INT32
 * {@code scan} column in place of its scan struct; no fixture has such a column.
 */
class PackedMetadataReaderTest {

    @TempDir
    Path tmp;

    @Test
    void aScalarFacetColumnGivesSpectraWithoutThatFacet() throws IOException {
        InputFile member;
        List<Group> rows = new ArrayList<>();
        SpectrumMetadataDecoder.Decoded fixture;
        MessageType schema;
        try (MzPeakSource source = MzPeakSource.open(Fixtures.legacy("small.unpacked.mzpeak"))) {
            member = source.inputFile(MzPeakManifest.fromSource(source)
                    .find(EntityType.SPECTRUM, DataKind.METADATA).orElseThrow().name());
            schema = ParquetFooter.read(member).schema();
            ParquetGroups.forEach(member, rows::add);
            fixture = SpectrumMetadataDecoder.decode(member);
        }
        assertThat(schema.getType("scan").isPrimitive()).isFalse();
        assertThat(fixture.descriptions()).hasSize(48).allSatisfy(d -> assertThat(d.scans()).hasSize(1));

        List<Type> fields = new ArrayList<>();
        for (Type field : schema.getFields()) {
            fields.add(field.getName().equals("scan") ? Types.optional(PrimitiveTypeName.INT32).named("scan") : field);
        }
        MessageType scalarScan = new MessageType(schema.getName(), fields);
        Path file = tmp.resolve("spectra_metadata.parquet");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(scalarScan)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (Group row : rows) {
                Group copy = new SimpleGroup(scalarScan);
                copy(row, copy, "scan");
                writer.write(copy);
            }
        }

        SpectrumMetadataDecoder.Decoded decoded = SpectrumMetadataDecoder.decode(new LocalInputFile(file));

        List<SpectrumDescription> expected = new ArrayList<>();
        for (SpectrumDescription d : fixture.descriptions()) {
            expected.add(new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                    d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), List.of(), d.precursors(),
                    d.parameters()));
        }
        assertThat(decoded.descriptions()).containsExactlyElementsOf(expected);
        assertThat(decoded.deltaModels()).containsOnlyKeys(fixture.deltaModels().keySet());
        fixture.deltaModels().forEach((index, model) ->
                assertThat(decoded.deltaModels().get(index)).as("delta model of %d", index).containsExactly(model));
    }

    /** Copies every value of {@code src} but the top-level field {@code omit} into {@code dst}, recursively. */
    private static void copy(Group src, Group dst, String omit) {
        GroupType type = src.getType();
        for (int f = 0; f < type.getFieldCount(); f++) {
            Type field = type.getType(f);
            String name = field.getName();
            if (name.equals(omit)) {
                continue;
            }
            for (int i = 0; i < src.getFieldRepetitionCount(f); i++) {
                if (!field.isPrimitive()) {
                    copy(src.getGroup(f, i), dst.addGroup(name), null);
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
}
