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
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link WavelengthSpectrumStore} paths that current has_uv, the only fixture with wavelength spectra, does not take:
 * a manifest that lists the wavelength spectrum metadata member but no data member, and a data member whose
 * {@code point} column is a scalar instead of a struct. Each case reads has_uv from memory with an edited manifest
 * or a member written with parquet-java into a {@link TempDir}.
 */
class WavelengthSpectrumStoreTest {

    private static final String HAS_UV = "has_uv.mzpeak";
    private static final String DATA = "wavelength_spectra_data.parquet";

    @TempDir
    Path tmp;

    /** Without the data member there is no store: the reader has no wavelength spectra, as without both members. */
    @Test
    void aManifestWithTheMetadataMemberButNoDataMemberHasNoWavelengthSpectra() {
        try (MzPeakReader full = MzPeakReader.open(Fixtures.current(HAS_UV))) {
            assertThat(full.wavelengthSpectra()).hasSize(520);
            assertThat(full.getWavelengthSpectrum(0)).isPresent();
        }
        MzPeakSource source = MzPeakReaderEdgeCasesTest.edited(Fixtures.current(HAS_UV),
                MzPeakReaderEdgeCasesTest.withoutMember(DATA), Map.of());
        try (MzPeakReader reader = MzPeakReader.open(source)) {
            assertThat(reader.manifest().find(EntityType.WAVELENGTH_SPECTRUM, DataKind.METADATA)).isPresent();
            assertThat(reader.manifest().find(EntityType.WAVELENGTH_SPECTRUM, DataKind.DATA_ARRAYS)).isEmpty();
            assertThat(reader.wavelengthSpectra()).isEmpty();
            assertThat(reader.getWavelengthSpectrum(0)).isEmpty();
        }
    }

    /** A data member whose {@code point} is a scalar column cannot be read and throws, naming the member. */
    @Test
    void aDataMemberWhosePointColumnIsAScalarThrows() throws IOException {
        MessageType schema = MessageTypeParser.parseMessageType("message m { optional int64 point; }");
        Path file = tmp.resolve(DATA);
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            writer.write(new SimpleGroupFactory(schema).newGroup().append("point", 0L));
        }
        MzPeakSource source = MzPeakReaderEdgeCasesTest.edited(Fixtures.current(HAS_UV), index -> { },
                Map.of(DATA, Files.readAllBytes(file)));
        try (MzPeakReader reader = MzPeakReader.open(source)) {
            assertThatThrownBy(reader::wavelengthSpectra)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(DATA + " has no 'point' struct, so its wavelength spectra cannot be read");
        }
    }
}
