package org.mzpeak.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * X-5: the entity key of a signal member is the first present {@code index} entry of array type MS:1003870
 * (signal-data layout, Indexing and Parallel time index). Another {@code index} entry, such as the parallel time index
 * MS:1000595, is ignored with a DEBUG log and never keys the rows.
 *
 * <p>The members are the fixtures' own spectra_data rows (small.unpacked, point; small.chunked, chunk), rewritten
 * through parquet-java's Group writer with a float64 {@code <prefix>.spectrum_time} column holding each row's
 * spectrum time in minutes and an array-index entry {buffer_format: index, array_type: MS:1000595, unit: UO:0000031,
 * data_type: MS:1000523} listed before the spectrum_index entry. Every spectrum reads as from the unmodified fixture.
 */
class SignalPlanTimeIndexTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SPECTRA_DATA = "spectra_data.parquet";
    private static final String TIME_FIELD = "spectrum_time";

    @TempDir
    Path tmp;

    @Test
    void aPointTimeIndexListedFirstDoesNotKeyTheSpectra() throws IOException {
        Path original = Fixtures.current("small.unpacked.mzpeak");
        Path dir = Files.createDirectories(tmp.resolve("small.unpacked.mzpeak"));
        try (Stream<Path> files = Files.list(original)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Files.copy(f, dir.resolve(f.getFileName()));
            }
        }
        assertTimeIndexIgnored(original, dir, "point");
    }

    @Test
    void aChunkTimeIndexListedFirstDoesNotKeyTheSpectra() throws IOException {
        Path original = Fixtures.current("small.chunked.mzpeak");
        Path dir = Files.createDirectories(tmp.resolve("small.chunked.mzpeak"));
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(original))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Files.copy(zip, dir.resolve(entry.getName()));
            }
        }
        assertTimeIndexIgnored(original, dir, "chunk");
    }

    private static void assertTimeIndexIgnored(Path original, Path dir, String prefix) throws IOException {
        Map<Long, Double> times = new HashMap<>();
        try (MzPeakReader reader = MzPeakReader.open(original)) {
            for (SpectrumDescription d : reader.metadata()) {
                times.put(d.index(), d.retentionTime());
            }
        }
        Path member = dir.resolve(SPECTRA_DATA);
        Path out = member.resolveSibling(SPECTRA_DATA + ".rewritten");
        rewrite(member, out, prefix, times);
        Files.move(out, member, StandardCopyOption.REPLACE_EXISTING);

        ParquetFooter footer = ParquetFooter.read(new LocalInputFile(member));
        ArrayIndex index = ArrayIndex.of(footer.keyValue(), footer.schema(), SignalEntity.SPECTRUM);
        assertThat(index.source()).isEqualTo(ArrayIndex.Source.FOOTER);
        assertThat(index.entries().get(0).path()).isEqualTo(prefix + "." + TIME_FIELD);
        assertThat(index.entries().get(0).arrayType()).isEqualTo("MS:1000595");
        SignalPlan[] plan = new SignalPlan[1];
        List<LogRecord> records = ArrayIndexTest.captureLog(SignalPlan.class,
                () -> plan[0] = SignalPlan.of(index, footer.schema(), SignalEntity.SPECTRUM));
        assertThat(plan[0].indexField()).isEqualTo("spectrum_index");
        assertThat(plan[0].extras()).doesNotContainKey("MS:1000595");
        assertThat(records).filteredOn(r -> r.getLevel() == Level.FINE).extracting(LogRecord::getMessage)
                .contains("spectrum_array_index (FOOTER) lists the index entry " + prefix + "." + TIME_FIELD
                        + " (MS:1000595), which is not the spectrum key MS:1003870; it is ignored");

        try (MzPeakReader expected = MzPeakReader.open(original); MzPeakReader actual = MzPeakReader.open(dir)) {
            assertThat(actual.getSpectrum(0).orElseThrow().pointCount()).isEqualTo(13589);
            assertThat(actual.getSpectrum(1).orElseThrow().pointCount()).isEqualTo(18177);
            Iterator<Spectrum> a = actual.iterator();
            int n = 0;
            for (Spectrum e : expected) {
                assertThat(a.hasNext()).isTrue();
                Spectrum s = a.next();
                assertThat(s.index()).isEqualTo(e.index());
                assertThat(s.mz()).as("m/z of spectrum %s", e.index()).isEqualTo(e.mz());
                assertThat(s.intensity()).as("intensity of spectrum %s", e.index()).isEqualTo(e.intensity());
                assertThat(s.arrays().keySet()).isEqualTo(e.arrays().keySet());
                n++;
            }
            assertThat(a.hasNext()).isFalse();
            assertThat(n).isEqualTo(times.size()).isGreaterThan(1);
        }
    }

    /**
     * Writes the rows of {@code member} to {@code out}, the footer key-value metadata kept: the {@code prefix} struct
     * gains a float64 {@code spectrum_time} holding the time of its row's spectrum, and the spectrum array index
     * lists it, as a MS:1000595 index entry, before the spectrum_index entry.
     */
    private static void rewrite(Path member, Path out, String prefix, Map<Long, Double> times) throws IOException {
        InputFile in = new LocalInputFile(member);
        ParquetFooter footer = ParquetFooter.read(in);
        Map<String, String> keyValue = new LinkedHashMap<>(footer.keyValue());
        keyValue.remove("writer.model.name");
        keyValue.put(SignalEntity.SPECTRUM.arrayIndexKey(),
                withTimeEntry(keyValue.get(SignalEntity.SPECTRUM.arrayIndexKey()), prefix));
        List<Type> fields = new ArrayList<>();
        for (Type field : footer.schema().getFields()) {
            if (field.getName().equals(prefix)) {
                GroupType group = field.asGroupType();
                List<Type> inner = new ArrayList<>(group.getFields());
                inner.add(Types.optional(PrimitiveTypeName.DOUBLE).named(TIME_FIELD));
                fields.add(group.withNewFields(inner));
            } else {
                fields.add(field);
            }
        }
        MessageType schema = new MessageType(footer.schema().getName(), fields);
        int[] timed = {0};
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(out))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withExtraMetaData(keyValue)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            ParquetGroups.forEach(in, row -> {
                Group copy = new SimpleGroup(schema);
                copy(row, copy);
                for (int i = 0; i < copy.getFieldRepetitionCount(prefix); i++) {
                    Group struct = copy.getGroup(prefix, i);
                    long key = struct.getLong("spectrum_index", 0);
                    struct.add(TIME_FIELD, times.get(key));
                    timed[0]++;
                }
                try {
                    writer.write(copy);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        assertThat(timed[0]).as("timed rows").isPositive();
    }

    /** The array index JSON with a parallel time index entry inserted before the spectrum_index entry. */
    private static String withTimeEntry(String json, String prefix) throws IOException {
        ObjectNode root = (ObjectNode) MAPPER.readTree(json);
        assertThat(root.get("prefix").asText()).isEqualTo(prefix);
        ArrayNode entries = (ArrayNode) root.get("entries");
        int at = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).get("path").asText().equals(prefix + ".spectrum_index")) {
                at = i;
                break;
            }
        }
        assertThat(at).as("spectrum_index entry").isNotNegative();
        ObjectNode time = MAPPER.createObjectNode();
        time.put("context", "spectrum");
        time.put("path", prefix + "." + TIME_FIELD);
        time.put("data_type", "MS:1000523");
        time.put("array_type", "MS:1000595");
        time.put("array_name", "time array");
        time.put("unit", "UO:0000031");
        time.put("buffer_format", "index");
        time.putNull("buffer_priority");
        time.putNull("sorting_rank");
        entries.insert(at, time);
        return MAPPER.writeValueAsString(root);
    }

    /** Copies every value of {@code src} into {@code dst}, recursively. */
    private static void copy(Group src, Group dst) {
        GroupType type = src.getType();
        for (int f = 0; f < type.getFieldCount(); f++) {
            Type field = type.getType(f);
            for (int i = 0; i < src.getFieldRepetitionCount(f); i++) {
                if (!field.isPrimitive()) {
                    copy(src.getGroup(f, i), dst.addGroup(f));
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
