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
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongUnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-1: the index key of a signal row (spectra_data, chromatograms_data, wavelength_spectra_data) is read by its column
 * type. An unsigned 8-, 16- or 32-bit key is not sign-extended, so the uint32 2^31 keys the entity of index 2^31; a
 * negative key in a signed column, and a uint64 key with the high bit set, are rejected naming the member, the column
 * type and the column. The members are the fixtures' own rows, rewritten through parquet-java's Group writer with
 * the index column's type and keys changed.
 */
class SignalIndexKeyTest {

    private static final Path SMALL_UNPACKED = Fixtures.current("small.unpacked.mzpeak");
    private static final Path HAS_UV = Fixtures.current("has_uv.mzpeak");
    private static final String SPECTRA_DATA = "spectra_data.parquet";
    private static final String CHROMATOGRAMS_DATA = "chromatograms_data.parquet";
    private static final String WAVELENGTH_DATA = "wavelength_spectra_data.parquet";
    private static final String WAVELENGTH_METADATA = "wavelength_spectra_metadata.parquet";

    /** 2^31: the smallest uint32 that a signed read of an INT32 makes negative. */
    private static final long TWO_TO_31 = 1L << 31;

    private static final LogicalTypeAnnotation UINT32 = LogicalTypeAnnotation.intType(32, false);
    private static final LogicalTypeAnnotation UINT64 = LogicalTypeAnnotation.intType(64, false);

    @TempDir
    Path tmp;

    // ---- spectra_data -----------------------------------------------------------------------------------------

    /** (a) A uint32 key of 2^31 or more keys its spectrum; it read as negative and was rejected before. */
    @Test
    void aUint32SpectrumIndexOf2To31ReadsItsSpectrum() throws IOException {
        Path data = rewritten(SMALL_UNPACKED.resolve(SPECTRA_DATA), "point.spectrum_index", PrimitiveTypeName.INT32,
                UINT32, k -> k + TWO_TO_31);

        SpectrumArrayStore.Arrays expected;
        try (SpectrumArrayStore original = spectra(SMALL_UNPACKED.resolve(SPECTRA_DATA))) {
            expected = original.get(0);
        }
        assertThat(expected.mz()).isNotEmpty();
        try (SpectrumArrayStore store = spectra(data)) {
            SpectrumArrayStore.Arrays actual = store.get(TWO_TO_31);
            assertThat(actual).isNotNull();
            assertThat(actual.mz()).isEqualTo(expected.mz());
            assertThat(actual.intensity()).isEqualTo(expected.intensity());
            assertThat(store.get(0)).isNull();
        }
    }

    /** (b) A negative key in a signed INT32 column is rejected, naming the member and the column type. */
    @Test
    void aNegativeSignedSpectrumIndexIsRejected() throws IOException {
        Path data = rewritten(SMALL_UNPACKED.resolve(SPECTRA_DATA), "point.spectrum_index", PrimitiveTypeName.INT32,
                null, k -> k - 1);

        try (SpectrumArrayStore store = spectra(data)) {
            assertThatThrownBy(() -> store.get(0))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(SPECTRA_DATA + ": int32 column spectrum_index holds the negative index -1");
        }
    }

    /** (c) A uint64 key with the high bit set is beyond the supported range; the message names the member. */
    @Test
    void aUint64SpectrumIndexWithTheHighBitSetIsRejected() throws IOException {
        Path data = rewritten(SMALL_UNPACKED.resolve(SPECTRA_DATA), "point.spectrum_index", PrimitiveTypeName.INT64,
                UINT64, k -> k + Long.MIN_VALUE);

        try (SpectrumArrayStore store = spectra(data)) {
            // the row-group statistics hold the keys as signed longs
            assertThatThrownBy(() -> store.get(Long.MIN_VALUE))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(SPECTRA_DATA + ": uint64 column spectrum_index holds 9223372036854775808, beyond the"
                            + " supported range (high bit set)");
        }
    }

    // ---- chromatograms_data -----------------------------------------------------------------------------------

    /** (d) A uint32 chromatogram key of 2^31 or more keys its chromatogram. */
    @Test
    void aUint32ChromatogramIndexOf2To31ReadsItsChromatogram() throws IOException {
        Path data = rewritten(SMALL_UNPACKED.resolve(CHROMATOGRAMS_DATA), "point.chromatogram_index",
                PrimitiveTypeName.INT32, UINT32, k -> k + TWO_TO_31);

        Map<Long, ChromatogramDataDecoder.Decoded> expected = chromatograms(SMALL_UNPACKED.resolve(CHROMATOGRAMS_DATA));
        Map<Long, ChromatogramDataDecoder.Decoded> actual = chromatograms(data);

        assertThat(expected).isNotEmpty();
        assertThat(actual).hasSameSizeAs(expected);
        for (Map.Entry<Long, ChromatogramDataDecoder.Decoded> e : expected.entrySet()) {
            ChromatogramDataDecoder.Decoded a = actual.get(e.getKey() + TWO_TO_31);
            assertThat(a).as("chromatogram %s", e.getKey()).isNotNull();
            assertThat(a.time()).isEqualTo(e.getValue().time());
            assertThat(a.intensity()).isEqualTo(e.getValue().intensity());
            assertThat(a.arrays().keySet()).isEqualTo(e.getValue().arrays().keySet());
        }
    }

    @Test
    void aNegativeSignedChromatogramIndexIsRejected() throws IOException {
        Path data = rewritten(SMALL_UNPACKED.resolve(CHROMATOGRAMS_DATA), "point.chromatogram_index",
                PrimitiveTypeName.INT32, null, k -> k - 1);

        assertThatThrownBy(() -> chromatograms(data))
                .isInstanceOf(MzPeakException.class)
                .hasMessage(CHROMATOGRAMS_DATA + ": int32 column chromatogram_index holds the negative index -1");
    }

    @Test
    void aUint64ChromatogramIndexWithTheHighBitSetIsRejected() throws IOException {
        Path data = rewritten(SMALL_UNPACKED.resolve(CHROMATOGRAMS_DATA), "point.chromatogram_index",
                PrimitiveTypeName.INT64, UINT64, k -> k + Long.MIN_VALUE);

        assertThatThrownBy(() -> chromatograms(data))
                .isInstanceOf(MzPeakException.class)
                .hasMessage(CHROMATOGRAMS_DATA + ": uint64 column chromatogram_index holds 9223372036854775808,"
                        + " beyond the supported range (high bit set)");
    }

    // ---- wavelength_spectra_data ------------------------------------------------------------------------------

    /**
     * (d) uint32 wavelength spectrum keys of 2^31 or more, in the data and the metadata member alike, read the
     * fixture's wavelength spectra through the reader; the data keys were rejected as out of range before.
     */
    @Test
    void aUint32WavelengthSpectrumIndexOf2To31ReadsItsSpectrum() throws IOException {
        Path dir = unzip(HAS_UV);
        rewriteInPlace(dir.resolve(WAVELENGTH_DATA), "point.wavelength_spectrum_index", PrimitiveTypeName.INT32,
                UINT32, k -> k + TWO_TO_31);
        rewriteInPlace(dir.resolve(WAVELENGTH_METADATA), "index", PrimitiveTypeName.INT32, UINT32,
                k -> k + TWO_TO_31);

        try (MzPeakReader original = MzPeakReader.open(HAS_UV); MzPeakReader edited = MzPeakReader.open(dir)) {
            List<WavelengthSpectrum> expected = original.wavelengthSpectra();
            List<WavelengthSpectrum> actual = edited.wavelengthSpectra();
            assertThat(expected).isNotEmpty();
            assertThat(actual).hasSameSizeAs(expected);
            WavelengthSpectrum first = expected.get(0);
            assertThat(first.index()).isZero();
            assertThat(first.wavelength()).isNotEmpty();
            WavelengthSpectrum shifted = actual.get(0);
            assertThat(shifted.index()).isEqualTo(TWO_TO_31);
            assertThat(shifted.id()).isEqualTo(first.id());
            assertThat(shifted.wavelength()).isEqualTo(first.wavelength());
            assertThat(shifted.intensity()).isEqualTo(first.intensity());
            WavelengthSpectrum last = expected.get(expected.size() - 1);
            assertThat(edited.getWavelengthSpectrum(last.index() + TWO_TO_31).orElseThrow().intensity())
                    .isEqualTo(last.intensity());
        }
    }

    @Test
    void aNegativeSignedWavelengthSpectrumIndexIsRejected() throws IOException {
        Path dir = unzip(HAS_UV);
        rewriteInPlace(dir.resolve(WAVELENGTH_DATA), "point.wavelength_spectrum_index", PrimitiveTypeName.INT32,
                null, k -> k - 1);

        try (MzPeakReader reader = MzPeakReader.open(dir)) {
            assertThatThrownBy(reader::wavelengthSpectra)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(WAVELENGTH_DATA + ": int32 column wavelength_spectrum_index holds the negative"
                            + " index -1");
        }
    }

    @Test
    void aUint64WavelengthSpectrumIndexWithTheHighBitSetIsRejected() throws IOException {
        Path dir = unzip(HAS_UV);
        rewriteInPlace(dir.resolve(WAVELENGTH_DATA), "point.wavelength_spectrum_index", PrimitiveTypeName.INT64,
                UINT64, k -> k + Long.MIN_VALUE);

        try (MzPeakReader reader = MzPeakReader.open(dir)) {
            assertThatThrownBy(reader::wavelengthSpectra)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(WAVELENGTH_DATA + ": uint64 column wavelength_spectrum_index holds"
                            + " 9223372036854775808, beyond the supported range (high bit set)");
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** The spectrum store of the member {@code data}, named by its file name as the reader names it. */
    private static SpectrumArrayStore spectra(Path data) {
        return SpectrumArrayStore.load(new LocalInputFile(data), data.getFileName().toString(), false, Map.of());
    }

    /** The decoded chromatograms of the member {@code data}, named by its file name, with the reader's plan. */
    private static Map<Long, ChromatogramDataDecoder.Decoded> chromatograms(Path data) {
        InputFile file = new LocalInputFile(data);
        ParquetFooter footer = ParquetFooter.read(file);
        SignalPlan plan = SignalPlan.of(ArrayIndex.of(footer.keyValue(), footer.schema(), SignalEntity.CHROMATOGRAM),
                footer.schema(), SignalEntity.CHROMATOGRAM);
        return ChromatogramDataDecoder.decode(file, data.getFileName().toString(), plan);
    }

    /** The entries of the ZIP {@code archive}, extracted into a new directory of the same name. */
    private Path unzip(Path archive) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve(archive.getFileName().toString()));
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Files.copy(zip, dir.resolve(entry.getName()));
            }
        }
        return dir;
    }

    /** {@link #rewritten} of {@code member}, replacing it. */
    private static void rewriteInPlace(Path member, String column, PrimitiveTypeName type,
                                       LogicalTypeAnnotation annotation, LongUnaryOperator keys) throws IOException {
        Path out = rewrite(member, member.resolveSibling(member.getFileName() + ".rewritten"), column, type,
                annotation, keys);
        Files.move(out, member, StandardCopyOption.REPLACE_EXISTING);
    }

    /** {@link #rewrite} of the member {@code member} into a member of the same name in a new temporary directory. */
    private Path rewritten(Path member, String column, PrimitiveTypeName type, LogicalTypeAnnotation annotation,
                           LongUnaryOperator keys) throws IOException {
        Path dir = Files.createTempDirectory(tmp, "member");
        return rewrite(member, dir.resolve(member.getFileName().toString()), column, type, annotation, keys);
    }

    /**
     * Writes the rows of {@code member} to {@code out} with parquet-java's Group API, the footer key-value metadata
     * kept: the INT64 column at the dotted path {@code column} becomes a {@code type} column with {@code annotation}
     * (none when {@code null}) holding {@code keys} of the stored values, every other column is copied as is.
     */
    private static Path rewrite(Path member, Path out, String column, PrimitiveTypeName type,
                                LogicalTypeAnnotation annotation, LongUnaryOperator keys) {
        InputFile in = new LocalInputFile(member);
        ParquetFooter footer = ParquetFooter.read(in);
        Map<String, String> keyValue = new LinkedHashMap<>(footer.keyValue());
        keyValue.remove("writer.model.name");
        MessageType schema = new MessageType(footer.schema().getName(),
                replaced(footer.schema(), column.split("\\."), 0, type, annotation).getFields());
        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(in, rows::add);
        int[] edited = {0};
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(out))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withExtraMetaData(keyValue)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (Group row : rows) {
                Group copy = new SimpleGroup(schema);
                copy(row, copy, "", column, keys, edited);
                writer.write(copy);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(edited[0]).as("rewritten keys of %s", column).isEqualTo(rows.size());
        return out;
    }

    /** {@code group} with the leaf at {@code path} (from {@code depth} on) replaced by a {@code type} leaf. */
    private static GroupType replaced(GroupType group, String[] path, int depth, PrimitiveTypeName type,
                                      LogicalTypeAnnotation annotation) {
        List<Type> fields = new ArrayList<>();
        for (Type field : group.getFields()) {
            if (!field.getName().equals(path[depth])) {
                fields.add(field);
            } else if (depth + 1 < path.length) {
                fields.add(replaced(field.asGroupType(), path, depth + 1, type, annotation));
            } else {
                PrimitiveType old = field.asPrimitiveType();
                assertThat(old.getPrimitiveTypeName()).isEqualTo(PrimitiveTypeName.INT64);
                fields.add(Types.primitive(type, old.getRepetition()).as(annotation).named(old.getName()));
            }
        }
        return group.withNewFields(fields);
    }

    /** Copies every value of {@code src} into {@code dst}, recursively; the cells at {@code column} through keys. */
    private static void copy(Group src, Group dst, String path, String column, LongUnaryOperator keys,
                             int[] edited) {
        GroupType type = src.getType();
        for (int f = 0; f < type.getFieldCount(); f++) {
            Type field = type.getType(f);
            String fieldPath = path.isEmpty() ? field.getName() : path + "." + field.getName();
            for (int i = 0; i < src.getFieldRepetitionCount(f); i++) {
                if (!field.isPrimitive()) {
                    copy(src.getGroup(f, i), dst.addGroup(f), fieldPath, column, keys, edited);
                } else if (fieldPath.equals(column)) {
                    long key = keys.applyAsLong(src.getLong(f, i));
                    if (dst.getType().getType(f).asPrimitiveType().getPrimitiveTypeName() == PrimitiveTypeName.INT32) {
                        dst.add(f, (int) key);
                    } else {
                        dst.add(f, key);
                    }
                    edited[0]++;
                } else {
                    switch (field.asPrimitiveType().getPrimitiveTypeName()) {
                        case INT32 -> dst.add(f, src.getInteger(f, i));
                        case INT64 -> dst.add(f, src.getLong(f, i));
                        case FLOAT -> dst.add(f, src.getFloat(f, i));
                        case DOUBLE -> dst.add(f, src.getDouble(f, i));
                        case BOOLEAN -> dst.add(f, src.getBoolean(f, i));
                        default -> dst.add(f, src.getBinary(f, i));
                    }
                }
            }
        }
    }
}
