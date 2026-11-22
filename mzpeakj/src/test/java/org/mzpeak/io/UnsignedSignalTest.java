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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * X-6 (R4): signal values in an unsigned integer column are read without sign extension. A uint32 intensity of
 * 3,000,000,000 reads 3.0E9 (0.12.0 read it as the signed int32 -1,294,967,296), a uint64 intensity of 2^63 + 2048
 * reads 9.223372036854778E18, in the point layout (a scalar leaf) and in the chunked layout (the elements of a list);
 * a signed int32 of -5 still reads -5.0 in both.
 *
 * <p>The members are the fixtures' own: current small.unpacked's {@code spectra_data} with its {@code point.intensity}
 * column, and current small.chunked's with its {@code chunk.intensity} list, rewritten through parquet-java's Group
 * writer as an integer column of the given type: every value rounded to the nearest integer, the first value of the
 * member (spectrum 0's first point, or the first element of spectrum 0's first chunk) replaced by the value under
 * test. Every other column and footer key is kept.
 */
class UnsignedSignalTest {

    private static final Path SMALL_UNPACKED = Fixtures.current("small.unpacked.mzpeak");
    private static final Path SMALL_CHUNKED = Fixtures.current("small.chunked.mzpeak");
    private static final String SPECTRA_DATA = "spectra_data.parquet";

    private static final LogicalTypeAnnotation UINT32 = LogicalTypeAnnotation.intType(32, false);
    private static final LogicalTypeAnnotation UINT64 = LogicalTypeAnnotation.intType(64, false);
    private static final LogicalTypeAnnotation INT32 = LogicalTypeAnnotation.intType(32, true);

    /** 3,000,000,000: above Integer.MAX_VALUE, so a signed read of its uint32 bits is -1,294,967,296. */
    private static final long THREE_BILLION = 3_000_000_000L;
    /** 2^63 + 2048: the bits of a negative long; 2048 is the spacing of doubles at 2^63, so it is exact. */
    private static final long TWO_TO_63_PLUS_2048 = Long.MIN_VALUE + 2048;

    @TempDir
    Path tmp;

    @Test
    void aUint32PointIntensityReadsUnsigned() throws IOException {
        assertFirstIntensity(pointCopy(), "point.intensity", PrimitiveTypeName.INT32, UINT32,
                THREE_BILLION, 3.0E9);
    }

    @Test
    void aUint64PointIntensityReadsUnsigned() throws IOException {
        assertFirstIntensity(pointCopy(), "point.intensity", PrimitiveTypeName.INT64, UINT64,
                TWO_TO_63_PLUS_2048, 9.223372036854778E18);
    }

    @Test
    void aSignedInt32PointIntensityStillReadsSigned() throws IOException {
        assertFirstIntensity(pointCopy(), "point.intensity", PrimitiveTypeName.INT32, INT32, -5, -5.0);
    }

    @Test
    void aUint32ChunkIntensityListReadsUnsigned() throws IOException {
        assertFirstIntensity(chunkedCopy(), "chunk.intensity", PrimitiveTypeName.INT32, UINT32,
                THREE_BILLION, 3.0E9);
    }

    @Test
    void aUint64ChunkIntensityListReadsUnsigned() throws IOException {
        assertFirstIntensity(chunkedCopy(), "chunk.intensity", PrimitiveTypeName.INT64, UINT64,
                TWO_TO_63_PLUS_2048, 9.223372036854778E18);
    }

    @Test
    void aSignedInt32ChunkIntensityListStillReadsSigned() throws IOException {
        assertFirstIntensity(chunkedCopy(), "chunk.intensity", PrimitiveTypeName.INT32, INT32, -5, -5.0);
    }

    /**
     * Rewrites {@code column} of the dataset {@code dir}'s spectra_data as a {@code type} column with
     * {@code annotation}, the first value {@code first}; spectrum 0 then reads {@code expected} at point 0 and the
     * rounded intensities of the unchanged dataset at every other point, with the same m/z.
     */
    private static void assertFirstIntensity(Path dir, String column, PrimitiveTypeName type,
                                             LogicalTypeAnnotation annotation, long first, double expected)
            throws IOException {
        Spectrum0 original;
        try (MzPeakReader reader = MzPeakReader.open(dir)) {
            var s = reader.getSpectrum(0).orElseThrow();
            original = new Spectrum0(s.mz(), s.intensity());
        }
        assertThat(original.intensity()).hasSizeGreaterThan(1);
        rewriteInPlace(dir.resolve(SPECTRA_DATA), column, type, annotation, first);

        try (MzPeakReader reader = MzPeakReader.open(dir)) {
            var s = reader.getSpectrum(0).orElseThrow();
            double[] want = new double[original.intensity().length];
            want[0] = expected;
            for (int i = 1; i < want.length; i++) {
                want[i] = Math.round(original.intensity()[i]);
            }
            assertThat(s.intensity()).containsExactly(want);
            assertThat(s.mz()).containsExactly(original.mz());
        }
    }

    private record Spectrum0(double[] mz, double[] intensity) {
    }

    /** A copy of current small.unpacked. */
    private Path pointCopy() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("small.unpacked.mzpeak"));
        try (Stream<Path> files = Files.list(SMALL_UNPACKED)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Files.copy(f, dir.resolve(f.getFileName().toString()));
            }
        }
        return dir;
    }

    /** Current small.chunked, extracted into a directory. */
    private Path chunkedCopy() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("small.chunked.mzpeak"));
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(SMALL_CHUNKED))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Files.copy(zip, dir.resolve(entry.getName()));
            }
        }
        return dir;
    }

    /**
     * Writes the rows of {@code member} back in its place with parquet-java's Group API, the footer key-value
     * metadata kept: every floating-point leaf at or below the dotted path {@code column} becomes a {@code type} leaf
     * with {@code annotation} holding the rounded stored value, the first such cell {@code first}.
     */
    private static void rewriteInPlace(Path member, String column, PrimitiveTypeName type,
                                       LogicalTypeAnnotation annotation, long first) throws IOException {
        Path out = member.resolveSibling(member.getFileName() + ".rewritten");
        InputFile in = new LocalInputFile(member);
        ParquetFooter footer = ParquetFooter.read(in);
        Map<String, String> keyValue = new LinkedHashMap<>(footer.keyValue());
        keyValue.remove("writer.model.name");
        MessageType schema = new MessageType(footer.schema().getName(),
                replaced(footer.schema(), column.split("\\."), 0, type, annotation).getFields());
        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(in, rows::add);
        long[] edited = {0};
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
                copy(row, copy, "", column, first, edited);
                writer.write(copy);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(edited[0]).as("rewritten cells of %s", column).isGreaterThan(1);
        Files.move(out, member, StandardCopyOption.REPLACE_EXISTING);
    }

    /** {@code group} with every leaf at or below {@code path} (from {@code depth} on) replaced by a {@code type} leaf. */
    private static GroupType replaced(GroupType group, String[] path, int depth, PrimitiveTypeName type,
                                      LogicalTypeAnnotation annotation) {
        List<Type> fields = new ArrayList<>();
        for (Type field : group.getFields()) {
            if (depth < path.length && !field.getName().equals(path[depth])) {
                fields.add(field);
            } else if (!field.isPrimitive()) {
                fields.add(replaced(field.asGroupType(), path, depth + 1, type, annotation));
            } else {
                PrimitiveType old = field.asPrimitiveType();
                assertThat(old.getPrimitiveTypeName()).isIn(PrimitiveTypeName.FLOAT, PrimitiveTypeName.DOUBLE);
                fields.add(Types.primitive(type, old.getRepetition()).as(annotation).named(old.getName()));
            }
        }
        return group.withNewFields(fields);
    }

    /** Copies every value of {@code src} into {@code dst}, recursively; the cells at or below {@code column} rounded. */
    private static void copy(Group src, Group dst, String path, String column, long first, long[] edited) {
        GroupType type = src.getType();
        GroupType dstType = dst.getType();
        for (int f = 0; f < type.getFieldCount(); f++) {
            Type field = type.getType(f);
            String fieldPath = path.isEmpty() ? field.getName() : path + "." + field.getName();
            boolean target = fieldPath.equals(column) || fieldPath.startsWith(column + ".");
            for (int i = 0; i < src.getFieldRepetitionCount(f); i++) {
                if (!field.isPrimitive()) {
                    copy(src.getGroup(f, i), dst.addGroup(f), fieldPath, column, first, edited);
                    continue;
                }
                PrimitiveTypeName primitive = field.asPrimitiveType().getPrimitiveTypeName();
                if (target) {
                    double stored = primitive == PrimitiveTypeName.FLOAT ? src.getFloat(f, i) : src.getDouble(f, i);
                    long value = edited[0]++ == 0 ? first : Math.round(stored);
                    if (dstType.getType(f).asPrimitiveType().getPrimitiveTypeName() == PrimitiveTypeName.INT32) {
                        dst.add(f, (int) value);
                    } else {
                        dst.add(f, value);
                    }
                    continue;
                }
                switch (primitive) {
                    case INT32 -> dst.add(f, src.getInteger(f, i));
                    case INT64 -> dst.add(f, src.getLong(f, i));
                    case FLOAT -> dst.add(f, src.getFloat(f, i));
                    case DOUBLE -> dst.add(f, src.getDouble(f, i));
                    case BOOLEAN -> dst.add(f, src.getBoolean(f, i));
                    case BINARY, FIXED_LEN_BYTE_ARRAY -> dst.add(f, src.getBinary(f, i));
                    default -> throw new AssertionError("unexpected " + primitive + " field " + fieldPath);
                }
            }
        }
    }
}
