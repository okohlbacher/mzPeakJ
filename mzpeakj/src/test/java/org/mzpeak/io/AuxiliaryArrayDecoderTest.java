package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.AuxiliaryArray;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Param;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Auxiliary arrays without {@code items} (U1): mzpeak-convert 0.17.2 writes the {@code auxiliary_arrays} struct with
 * no {@code items} field, so {@link AuxiliaryArrayDecoder} derives the count from the data, the stored length or for
 * zlib the inflated length over the width of the data type, and skips with a WARNING an item whose count it cannot
 * derive. The fixture is {@code crafted/small.chromatograms_metadata.aux-without-items.parquet} (see
 * {@code crafted/README.md}), small.unpacked's chromatogram member re-encoded with pyarrow: chromatogram 0 holds
 * MS:1000786 {@code ms level} of the 64-bit integers 1, 2, 1 stored as is and MS:1000517 of the 64-bit floats 1.5, 2.5,
 * 3.5, 4.5 zlib-compressed. The remaining cases are crafted rows of a struct without {@code items}.
 */
class AuxiliaryArrayDecoderTest {

    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final Path WITHOUT_ITEMS = Fixtures.CURRENT.resolve("crafted")
            .resolve("small.chromatograms_metadata.aux-without-items.parquet");
    private static final String CHROMATOGRAMS = "chromatograms_metadata.parquet";
    private static final String INT64 = "MS:1000522";
    private static final String INT32 = "MS:1000519";
    private static final String FLOAT32 = "MS:1000521";
    private static final String FLOAT64 = "MS:1000523";
    private static final String NONE = "MS:1000576";
    private static final String ZLIB = "MS:1000574";
    private static final Param MS_LEVEL = new Param("non-standard data array", "MS:1000786", "ms level", "UO:0000186");
    private static final Param SIGNAL_TO_NOISE = new Param("signal to noise array", "MS:1000517", null, null);

    /** An {@code auxiliary_arrays} column whose struct has no {@code items}, as mzpeak-convert 0.17.2 writes it. */
    private static final MessageType ROW = MessageTypeParser.parseMessageType("""
            message m {
              optional group auxiliary_arrays (LIST) {
                repeated group list {
                  optional group item {
                    optional group data (LIST) {
                      repeated group list {
                        required int32 item (INTEGER(8,false));
                      }
                    }
                    optional group name {
                      optional binary accession (STRING);
                      optional binary name (STRING);
                    }
                    optional binary data_type (STRING);
                    optional binary compression (STRING);
                  }
                }
              }
            }""");

    @TempDir
    Path tmp;

    // ---- the crafted fixture ------------------------------------------------------------------------------------

    /** Both arrays of chromatogram 0 read with the count derived from their stored and their inflated length. */
    @Test
    void anItemWithoutItemsReadsWithADerivedCount() throws IOException {
        List<AuxiliaryArray> arrays;
        try (MzPeakReader reader = MzPeakReader.open(withoutItems())) {
            List<Chromatogram> chromatograms = reader.chromatograms();
            assertThat(chromatograms).hasSize(1);
            assertThat(chromatograms.get(0).time()).hasSize(48);
            arrays = chromatograms.get(0).auxiliaryArrays();
        }

        assertThat(arrays).hasSize(2);
        AuxiliaryArray plain = arrays.get(0);
        assertThat(plain.name()).isEqualTo(MS_LEVEL);
        assertThat(plain.dataType()).isEqualTo(INT64);
        assertThat(plain.compression()).isEqualTo(NONE);
        assertThat(plain.unit()).isEqualTo("UO:0000186");
        assertThat(plain.items()).isEqualTo(3);
        assertThat(plain.doubles()).containsExactly(1.0, 2.0, 1.0);
        AuxiliaryArray zlib = arrays.get(1);
        assertThat(zlib.name()).isEqualTo(SIGNAL_TO_NOISE);
        assertThat(zlib.compression()).isEqualTo(ZLIB);
        assertThat(zlib.data()).hasSizeLessThan(4 * Double.BYTES);
        assertThat(zlib.items()).isEqualTo(4);
        assertThat(zlib.doubles()).containsExactly(1.5, 2.5, 3.5, 4.5);
    }

    /** Rewritten split, the arrays carry their derived counts in {@code items} and read back equal. */
    @Test
    void aRewriteWritesTheDerivedCounts() throws IOException {
        Source source = ForwardBackwardCorpusTest.readSource(withoutItems());
        List<AuxiliaryArray> read = source.chromatograms.get(0).auxiliaryArrays();
        Path out = tmp.resolve("rewritten.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata,
                Options.defaults().withMetadataLayout(MetadataLayout.SPLIT));

        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(out.resolve(CHROMATOGRAMS), rows::add);
        assertThat(rows).hasSize(1);
        List<Group> items = ParquetGroups.groupList(rows.get(0), "auxiliary_arrays");
        assertThat(items).hasSize(2);
        assertThat(ParquetGroups.optLong(items.get(0), "items")).isEqualTo(3L);
        assertThat(ParquetGroups.optLong(items.get(1), "items")).isEqualTo(4L);
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            List<AuxiliaryArray> back = reader.chromatograms().get(0).auxiliaryArrays();
            assertThat(back).hasSize(2);
            for (int i = 0; i < 2; i++) {
                // record equality takes data by reference
                assertThat(back.get(i)).usingRecursiveComparison().isEqualTo(read.get(i));
            }
        }
    }

    // ---- crafted rows -------------------------------------------------------------------------------------------

    /** The 32-bit types count 4 bytes a value, the 64-bit types 8; an empty array has 0 items. */
    @Test
    void theWidthFollowsTheDataType() {
        assertThat(decoded(item(new byte[8], FLOAT32, NONE)).items()).isEqualTo(2);
        assertThat(decoded(item(new byte[8], INT32, NONE)).items()).isEqualTo(2);
        assertThat(decoded(item(new byte[16], FLOAT64, NONE)).items()).isEqualTo(2);
        assertThat(decoded(item(new byte[0], INT64, NONE)).items()).isZero();
        assertThat(decoded(item(AuxiliaryArrayWriteTest.zlib(new byte[12]), FLOAT32, ZLIB)).items()).isEqualTo(3);
    }

    /** Data that are not a whole number of values are rejected naming member, entity and item, stored or inflated. */
    @Test
    void aLengthThatIsNotAMultipleOfTheWidthIsRejected() {
        for (Group row : List.of(item(new byte[12], FLOAT64, NONE),
                item(AuxiliaryArrayWriteTest.zlib(new byte[6]), INT32, ZLIB))) {
            assertThatThrownBy(() -> AuxiliaryArrayDecoder.decode(row, CHROMATOGRAMS, "chromatogram 7"))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith(CHROMATOGRAMS + ": auxiliary array 0 of chromatogram 7 has no items")
                    .hasMessageContaining("bytes of data are not a whole number of values of");
        }
    }

    /**
     * An item whose count cannot be derived is skipped with a WARNING naming member, entity and item: a data type
     * without a fixed width, an unknown compression, corrupt zlib data, more bytes than the limit; the other items
     * of the entity are read.
     */
    @Test
    void anItemWhoseCountCannotBeDerivedIsSkippedWithAWarning() {
        assertSkipped(item(new byte[8], "MS:1001479", NONE), Integer.MAX_VALUE - 1, "data type MS:1001479");
        assertSkipped(item(new byte[8], FLOAT64, "MS:1002312"), Integer.MAX_VALUE - 1, "compression MS:1002312");
        assertSkipped(item(new byte[] {1, 2, 3}, FLOAT64, ZLIB), Integer.MAX_VALUE - 1, "cannot be inflated");
        assertSkipped(item(new byte[24], FLOAT64, NONE), 16, "more than 16 bytes");
        assertSkipped(item(AuxiliaryArrayWriteTest.zlib(new byte[20000]), FLOAT64, ZLIB), 16000,
                "more than 16000 bytes");
        // at the limit the count is derived
        assertThat(AuxiliaryArrayDecoder.decode(item(new byte[16], FLOAT64, NONE), CHROMATOGRAMS, "chromatogram 7",
                16)).singleElement().extracting(AuxiliaryArray::items).isEqualTo(2L);
        assertThat(AuxiliaryArrayDecoder.decode(item(AuxiliaryArrayWriteTest.zlib(new byte[16000]), FLOAT64,
                ZLIB), CHROMATOGRAMS, "chromatogram 7", 16000)).singleElement().extracting(AuxiliaryArray::items)
                .isEqualTo(2000L);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private void assertSkipped(Group row, int maxBytes, String reason) {
        Group second = row.getGroup("auxiliary_arrays", 0).addGroup("list").addGroup("item");
        fill(second, new byte[8], FLOAT64, NONE);
        List<List<AuxiliaryArray>> decoded = new ArrayList<>();
        List<LogRecord> log = ArrayIndexTest.captureLog(AuxiliaryArrayDecoder.class,
                () -> decoded.add(AuxiliaryArrayDecoder.decode(row, CHROMATOGRAMS, "chromatogram 7", maxBytes)));
        assertThat(decoded.get(0)).singleElement().extracting(AuxiliaryArray::items).isEqualTo(1L);
        assertThat(log).singleElement().satisfies(r -> {
            assertThat(r.getLevel()).isEqualTo(Level.WARNING);
            assertThat(r.getMessage()).startsWith(CHROMATOGRAMS + ": auxiliary array 0 of chromatogram 7 has no"
                    + " items, and its count cannot be derived (").contains(reason).endsWith("the array is skipped");
        });
    }

    private static AuxiliaryArray decoded(Group row) {
        List<AuxiliaryArray> arrays = AuxiliaryArrayDecoder.decode(row, CHROMATOGRAMS, "chromatogram 7");
        assertThat(arrays).hasSize(1);
        return arrays.get(0);
    }

    /** A row of {@link #ROW} with one item of {@code data}, without {@code items}. */
    private static Group item(byte[] data, String dataType, String compression) {
        Group row = new SimpleGroupFactory(ROW).newGroup();
        fill(row.addGroup("auxiliary_arrays").addGroup("list").addGroup("item"), data, dataType, compression);
        return row;
    }

    private static void fill(Group item, byte[] data, String dataType, String compression) {
        Group bytes = item.addGroup("data");
        for (byte b : data) {
            bytes.addGroup("list").append("item", b & 0xff);
        }
        item.addGroup("name").append("accession", SIGNAL_TO_NOISE.accession()).append("name", SIGNAL_TO_NOISE.name());
        item.append("data_type", dataType).append("compression", compression);
    }

    /** A copy of small.unpacked whose chromatogram member is the crafted one, its manifest checksum updated. */
    private Path withoutItems() throws IOException {
        Path copy = MemberRewrite.copy(SMALL, tmp.resolve("source"));
        Files.copy(WITHOUT_ITEMS, copy.resolve(CHROMATOGRAMS), StandardCopyOption.REPLACE_EXISTING);
        CodecReadTest.updateChecksum(copy, CHROMATOGRAMS);
        assertThat(MzPeakReader.verifyIntegrity(copy).valid()).isTrue();
        return copy;
    }
}
