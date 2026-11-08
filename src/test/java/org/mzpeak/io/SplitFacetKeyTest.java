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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.GoldenMeta.Row;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The split scans facet joins its spectra on {@code source_index}, never on {@code scan_index}. Every fixture has
 * {@code scan_index == source_index}, so a join on the wrong key passes every other test; here a {@link TempDir} copy
 * of current small.unpacked has its scans member rewritten with {@code scan_index} reversed ({@code 47 - row}). The
 * oracle is the FX-2 cells ({@link GoldenMeta}) of the unedited scans member, grouped by {@code source_index}.
 */
class SplitFacetKeyTest {

    private static final String SMALL_UNPACKED = "small.unpacked.mzpeak";

    @TempDir
    Path tmp;

    @Test
    void scansJoinOnSourceIndexWhateverTheirScanIndex() throws IOException {
        Path dir = copy(Fixtures.current(SMALL_UNPACKED));
        String scansMember;
        try (MzPeakSource source = MzPeakSource.open(dir)) {
            Entry scans = Facet.SPECTRUM_SCAN.entry(MzPeakManifest.fromSource(source)).orElseThrow();
            scansMember = scans.name();
        }
        List<Row> cells = GoldenMeta.load(SMALL_UNPACKED).rows(scansMember);
        assertThat(cells).hasSize(48);
        for (Row r : cells) {
            assertThat(r.get("scan_index")).as("fixture row %d", r.row()).isEqualTo(r.get("source_index"));
        }
        reverseScanIndex(dir.resolve(scansMember));
        Map<Long, List<Row>> bySource = new LinkedHashMap<>();
        for (Row r : cells) {
            bySource.computeIfAbsent((Long) r.get("source_index"), k -> new ArrayList<>()).add(r);
        }

        int checked = 0;
        try (MzPeakReader reader = MzPeakReader.open(dir)) {
            assertThat(reader.size()).isEqualTo(48);
            for (SpectrumDescription d : reader.metadata()) {
                List<Row> expected = bySource.getOrDefault(d.index(), List.of());
                List<ScanEvent> scans = d.scans();
                assertThat(scans).as("scans of %d", d.index()).hasSameSizeAs(expected);
                for (int i = 0; i < scans.size(); i++) {
                    Row cell = expected.get(i);
                    assertThat(scans.get(i).startTime()).as("start time of %d", d.index())
                            .isEqualTo(((Number) cell.get("scan_start_time")).doubleValue());
                    assertThat(scans.get(i).filterString()).as("filter string of %d", d.index())
                            .isEqualTo(cell.get("filter_string")).isNotNull();
                    checked++;
                }
            }
        }
        assertThat(checked).isEqualTo(48);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private Path copy(Path dataset) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve(dataset.getFileName().toString()));
        try (Stream<Path> files = Files.list(dataset)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Files.copy(f, dir.resolve(f.getFileName().toString()));
            }
        }
        return dir;
    }

    /** Rewrites the scans member {@code member} with the scan_index of row {@code i} set to {@code 47 - i}. */
    private static void reverseScanIndex(Path member) throws IOException {
        InputFile input = new LocalInputFile(member);
        MessageType schema = ParquetFooter.read(input).schema();
        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(input, rows::add);
        assertThat(rows).hasSize(48);
        Path rewritten = member.resolveSibling(member.getFileName() + ".rewritten");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(rewritten))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (int i = 0; i < rows.size(); i++) {
                Group copy = new SimpleGroup(schema);
                copy(rows.get(i), copy, "scan_index");
                copy.add("scan_index", 47L - i);
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
