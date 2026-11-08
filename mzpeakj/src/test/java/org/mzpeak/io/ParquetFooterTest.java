package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.parquet.ByteArrayInputFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ParquetFooter#read} of a member whose footer is not readable: the legacy spectrum metadata member with its
 * Thrift footer zeroed, behind its intact {@code PAR1} magic and footer length.
 */
class ParquetFooterTest {

    private static final Path UNPACKED = Fixtures.legacy("small.unpacked.mzpeak");
    private static final String METADATA = "spectra_metadata.parquet";
    private static final String NOT_THRIFT = "can not read class org.apache.parquet.format.FileMetaData:"
            + " Required field 'version' was not found in serialized data!";

    @Test
    void theFooterOfTheFixtureMember() throws IOException {
        ParquetFooter footer = ParquetFooter.read(new ByteArrayInputFile(
                Files.readAllBytes(UNPACKED.resolve(METADATA))));
        assertThat(footer.rowCount()).isPositive();
        assertThat(footer.topLevelFields()).isNotEmpty();
    }

    @Test
    void aFooterThatIsNotThrift(@TempDir Path tmp) throws IOException {
        byte[] member = zeroedFooter(Files.readAllBytes(UNPACKED.resolve(METADATA)));
        assertThatThrownBy(() -> ParquetFooter.read(new ByteArrayInputFile(member)))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Failed reading Parquet footer")
                .cause().isInstanceOf(IOException.class).hasMessageStartingWith(NOT_THRIFT);

        Path dir = Files.createDirectories(tmp.resolve("small.unpacked.mzpeak"));
        try (Stream<Path> files = Files.list(UNPACKED)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Files.copy(f, dir.resolve(f.getFileName().toString()));
            }
        }
        Files.write(dir.resolve(METADATA), member);
        assertThatThrownBy(() -> MzPeakReader.open(dir))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Failed reading Parquet footer")
                .cause().isInstanceOf(IOException.class).hasMessageStartingWith(NOT_THRIFT);
    }

    /** {@code parquet} with every byte of its Thrift footer set to zero, a struct that ends before its fields. */
    private static byte[] zeroedFooter(byte[] parquet) {
        int length = parquet.length;
        assertThat(new String(parquet, length - 4, 4, StandardCharsets.US_ASCII)).isEqualTo("PAR1");
        int footer = ByteBuffer.wrap(parquet).order(ByteOrder.LITTLE_ENDIAN).getInt(length - 8);
        assertThat(footer).isPositive().isLessThan(length - 12);
        byte[] out = parquet.clone();
        Arrays.fill(out, length - 8 - footer, length - 8, (byte) 0);
        return out;
    }
}
