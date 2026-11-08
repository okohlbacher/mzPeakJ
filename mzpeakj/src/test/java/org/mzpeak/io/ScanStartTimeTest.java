package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.GoldenMeta.Row;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Scan start time (MS:1000016) is converted to minutes from the unit of its column mapping, as the Rust reader does
 * ({@code visit_scan_start_time}), and a spectrum without a {@code time} cell takes its retention time from its
 * scans. Each case reads a {@link TempDir} copy of current small.unpacked whose manifest is edited or whose spectrum
 * member is rewritten; the oracle is the FX-2 cells ({@link GoldenMeta}) of the unedited members. All three cases
 * fail on 1e400ed, which read the raw cell whatever its unit and gave a NaN retention time without {@code time}.
 */
class ScanStartTimeTest {

    private static final String SMALL_UNPACKED = "small.unpacked.mzpeak";
    private static final String SCANS = "spectra_metadata_scans.parquet";
    private static final String SPECTRA = "spectra_metadata.parquet";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    @Test
    void aStartTimeInSecondsIsReadInMinutes() throws IOException {
        Path dir = copy();
        editStartTimeMapping(dir, m -> m.put("unit", "UO:0000010"));
        Map<Long, List<Row>> scans = scanCells();
        Map<Long, Row> spectra = spectrumCells();
        int checked = 0;
        try (MzPeakReader reader = MzPeakReader.open(dir)) {
            assertThat(reader.size()).isEqualTo(48);
            for (SpectrumDescription d : reader.metadata()) {
                List<Row> rows = scans.get(d.index());
                assertThat(d.scans()).as("scans of %d", d.index()).hasSameSizeAs(rows);
                for (int i = 0; i < rows.size(); i++) {
                    double cell = number(rows.get(i).get("scan_start_time"));
                    assertThat(d.scans().get(i).startTime()).as("start time of %d", d.index()).isEqualTo(cell / 60);
                    checked++;
                }
                assertThat(d.retentionTime()).as("the spectrum's own time is kept")
                        .isEqualTo(number(spectra.get(d.index()).get("time")));
            }
        }
        assertThat(checked).isEqualTo(48);
    }

    @Test
    void aStartTimeUnitThatIsNoTimeUnitThrows() throws IOException {
        Path dir = copy();
        editStartTimeMapping(dir, m -> m.put("unit", "UO:0000040"));
        assertThatThrownBy(() -> MzPeakReader.open(dir))
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith(SCANS + ": scan start time column scan_start_time has the unit UO:0000040;");
    }

    @Test
    void aStartTimeUnitGivenByAUnitColumnThrows() throws IOException {
        Path dir = copy();
        editStartTimeMapping(dir, m -> m.putArray("unit").add("scan_start_time_unit"));
        assertThatThrownBy(() -> MzPeakReader.open(dir))
                .isInstanceOf(MzPeakException.class)
                .hasMessage(SCANS + ": scan start time column scan_start_time takes its unit from the column"
                        + " scan_start_time_unit, which is not supported");
    }

    /** Without the spectrum's {@code time} column, the retention time is its scan's start time (Rust's source). */
    @Test
    void withoutASpectrumTimeTheRetentionTimeIsTheScanStartTime() throws IOException {
        Path dir = copy();
        dropColumn(dir.resolve(SPECTRA), "time");
        Map<Long, List<Row>> scans = scanCells();
        try (MzPeakReader reader = MzPeakReader.open(dir)) {
            assertThat(reader.size()).isEqualTo(48);
            for (SpectrumDescription d : reader.metadata()) {
                List<Row> rows = scans.get(d.index());
                assertThat(rows).as("scans of %d", d.index()).hasSize(1);
                double start = number(rows.get(0).get("scan_start_time"));
                assertThat(d.retentionTime()).as("retention time of %d", d.index()).isEqualTo(start);
                assertThat(reader.getSpectrumByTime(start)).map(Spectrum::index).contains(d.index());
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /** Copies current small.unpacked into the temporary directory. */
    private Path copy() throws IOException {
        Path dataset = Fixtures.current(SMALL_UNPACKED);
        Path dir = Files.createDirectories(tmp.resolve(SMALL_UNPACKED));
        try (Stream<Path> files = Files.list(dataset)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Files.copy(f, dir.resolve(f.getFileName().toString()));
            }
        }
        return dir;
    }

    /** Applies {@code edit} to the one column_mapping item of the scans member whose accession is MS:1000016. */
    private static void editStartTimeMapping(Path dir, Consumer<ObjectNode> edit) throws IOException {
        Path indexFile = dir.resolve("mzpeak_index.json");
        ObjectNode index = (ObjectNode) MAPPER.readTree(Files.readAllBytes(indexFile));
        int edited = 0;
        for (JsonNode file : index.path("files")) {
            for (JsonNode m : file.path("column_mapping")) {
                if (SCANS.equals(file.path("name").asText()) && "MS:1000016".equals(m.path("accession").asText())) {
                    assertThat(m.path("unit").asText()).isEqualTo("UO:0000031");
                    edit.accept((ObjectNode) m);
                    edited++;
                }
            }
        }
        assertThat(edited).isEqualTo(1);
        Files.write(indexFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(index));
    }

    /** The FX-2 scan rows of current small.unpacked by source_index, in row order. */
    private static Map<Long, List<Row>> scanCells() {
        Map<Long, List<Row>> out = new LinkedHashMap<>();
        for (Row r : GoldenMeta.load(SMALL_UNPACKED).rows(SCANS)) {
            out.computeIfAbsent((Long) r.get("source_index"), k -> new ArrayList<>()).add(r);
        }
        assertThat(out).hasSize(48);
        return out;
    }

    /** The FX-2 spectrum rows of current small.unpacked by index. */
    private static Map<Long, Row> spectrumCells() {
        Map<Long, Row> out = new LinkedHashMap<>();
        for (Row r : GoldenMeta.load(SMALL_UNPACKED).rows(SPECTRA)) {
            out.put((Long) r.get("index"), r);
        }
        assertThat(out).hasSize(48);
        return out;
    }

    private static double number(Object cell) {
        assertThat(cell).isInstanceOf(Number.class);
        return ((Number) cell).doubleValue();
    }

    /**
     * Rewrites the Parquet file {@code member} without its top-level column {@code column}. The rewritten file has no
     * key-value metadata, which these cases do not read.
     */
    private static void dropColumn(Path member, String column) throws IOException {
        InputFile input = new LocalInputFile(member);
        MessageType schema = ParquetFooter.read(input).schema();
        List<Type> fields = new ArrayList<>(schema.getFields());
        assertThat(fields.removeIf(t -> t.getName().equals(column))).as("%s has %s", member, column).isTrue();
        MessageType reduced = new MessageType(schema.getName(), fields);
        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(input, rows::add);
        Path rewritten = member.resolveSibling(member.getFileName() + ".rewritten");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(rewritten))
                .withConf(new PlainParquetConfiguration())
                .withType(reduced)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (Group row : rows) {
                Group copy = new SimpleGroup(reduced);
                copy(row, copy, column);
                writer.write(copy);
            }
        }
        Files.move(rewritten, member, StandardCopyOption.REPLACE_EXISTING);
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
