package org.mzpeak.io;

import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.schema.ColumnOrder;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every column of every member mzPeakJ writes is in the type-defined order, as the reference writes it, although
 * parquet-java 1.18 orders FLOAT and DOUBLE columns by IEEE 754 total order unless told otherwise.
 *
 * <p>Oracle: current has_uv (spectra, chromatograms and wavelength spectra), whose DOUBLE columns the reference
 * writes in the type-defined order.
 */
class WriterColumnOrderTest {

    private static final Path HAS_UV = Fixtures.current("has_uv.mzpeak");

    @ParameterizedTest
    @EnumSource(MetadataLayout.class)
    void everyWrittenColumnIsInTheTypeDefinedOrder(MetadataLayout layout, @TempDir Path tmp) throws IOException {
        Source source = ForwardBackwardCorpusTest.readSource(HAS_UV);
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, Options.defaults().withMetadataLayout(layout));

        List<Path> members;
        try (Stream<Path> files = Files.list(out)) {
            members = files.filter(p -> p.getFileName().toString().endsWith(".parquet")).sorted().toList();
        }
        assertThat(members).isNotEmpty();
        int doubles = 0;
        for (Path member : members) {
            for (ColumnDescriptor column : ParquetFooter.read(new LocalInputFile(member)).schema().getColumns()) {
                assertThat(column.getPrimitiveType().columnOrder()).as("%s %s", member.getFileName(), column)
                        .isEqualTo(ColumnOrder.typeDefined());
                if (column.getPrimitiveType().getPrimitiveTypeName() == PrimitiveTypeName.DOUBLE) {
                    doubles++;
                }
            }
        }
        assertThat(doubles).isPositive();
    }
}
