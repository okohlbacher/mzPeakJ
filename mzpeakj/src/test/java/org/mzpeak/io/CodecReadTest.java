package org.mzpeak.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.cli.MzPeakInfo;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.TestCodecs;
import org.mzpeak.model.Spectrum;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * C1 (X-1): the mzPeak specification lets a writer compress columns with any codec Parquet lists, and mzPeakJ
 * reads them all. A copy of {@code small.unpacked.mzpeak} whose {@code spectra_data.parquet} and
 * {@code spectra_metadata.parquet} are written again through parquet-java with each codec reads the same 48
 * spectra as the fixture; BROTLI, which no Java library writes, comes from the committed
 * {@code crafted/small.spectra_metadata.brotli.parquet} (see {@code crafted/README.md}). Every copy has its
 * manifest checksums updated. A corrupt page fails naming its codec, and a copy whose every member is SNAPPY runs
 * the demo CLI in a child JVM that loads no native compression library and prints nothing to stderr.
 */
class CodecReadTest {

    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");

    private static final Path BROTLI_METADATA = Fixtures.CURRENT.resolve("crafted")
            .resolve("small.spectra_metadata.brotli.parquet");

    private static final List<String> REWRITTEN = List.of("spectra_data.parquet", "spectra_metadata.parquet");

    private static final String INDEX = "mzpeak_index.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Small pages, so that every column chunk of spectra_data holds several. */
    private static final int PAGE_SIZE = 64 * 1024;

    static Stream<TestCodecs> codecs() {
        return Stream.of(
                TestCodecs.snappy(),
                TestCodecs.lz4Raw(),
                TestCodecs.lz4Hadoop(4096),
                TestCodecs.lz4HadoopChunks(1000),
                TestCodecs.lz4Unframed(),
                TestCodecs.lzo(),
                TestCodecs.gzip(),
                TestCodecs.gzipMembers());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("codecs")
    void readsTheFixturesSpectra(TestCodecs codecs, @TempDir Path tmp) throws IOException {
        Path copy = MemberRewrite.copy(SMALL, tmp);
        for (String member : REWRITTEN) {
            rewrite(copy, member, codecs);
            assertThat(codecs(copy.resolve(member))).as(member).containsExactly(codecs.codec());
        }

        assertSameSpectra(copy);
    }

    @Test
    void readsTheBrotliMetadataMember(@TempDir Path tmp) throws IOException {
        Path copy = MemberRewrite.copy(SMALL, tmp);
        Files.copy(BROTLI_METADATA, copy.resolve("spectra_metadata.parquet"), StandardCopyOption.REPLACE_EXISTING);
        updateChecksum(copy, "spectra_metadata.parquet");

        assertThat(codecs(copy.resolve("spectra_metadata.parquet"))).containsExactly(CompressionCodecName.BROTLI);
        assertSameSpectra(copy);
    }

    @Test
    void aCorruptSnappyPageFailsNamingTheCodec(@TempDir Path tmp) throws IOException {
        TestCodecs snappy = TestCodecs.snappy();
        Path copy = MemberRewrite.copy(SMALL, tmp);
        // Snappy keeps its leading length varint; every tag after it is a copy reaching before the page start
        rewrite(copy, "spectra_data.parquet", new TestCodecs("corrupt-snappy", CompressionCodecName.SNAPPY,
                page -> corrupt(snappy.compress().apply(page))));

        try (MzPeakReader reader = MzPeakReader.open(copy)) {
            assertThat(reader.size()).isEqualTo(48);
            assertThatThrownBy(() -> reader.getSpectrumAt(0))
                    .satisfies(e -> assertThat(messages(e))
                            .anyMatch(m -> m.startsWith("SNAPPY decompression failed: ")));
        }
    }

    @Test
    void aCorruptLz4PageFailsNamingTheCodec(@TempDir Path tmp) throws IOException {
        TestCodecs lz4 = TestCodecs.lz4Unframed();
        Path copy = MemberRewrite.copy(SMALL, tmp);
        rewrite(copy, "spectra_data.parquet", new TestCodecs("corrupt-lz4", CompressionCodecName.LZ4,
                page -> corrupt(lz4.compress().apply(page))));

        try (MzPeakReader reader = MzPeakReader.open(copy)) {
            assertThatThrownBy(() -> reader.getSpectrumAt(0))
                    .satisfies(e -> assertThat(messages(e))
                            .anyMatch(m -> m.startsWith("LZ4 decompression failed: ")));
        }
    }

    /**
     * Every member SNAPPY: the CLI prints what it prints for the fixture, nothing goes to stderr (the JDK's one-time
     * {@code sun.misc.Unsafe} warning about aircompressor is turned off where the JDK knows the option, JDK 23 and
     * later), and no class of zstd-jni, snappy-java or Hadoop's native code loader is loaded, so no native library is.
     */
    @Test
    void theCliReadsAnAllSnappyCopyInAChildJvmWithoutNativeCode(@TempDir Path tmp) throws Exception {
        Path copy = MemberRewrite.copy(SMALL, tmp);
        List<String> members = new ArrayList<>();
        try (Stream<Path> files = Files.list(copy)) {
            files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".parquet")).sorted()
                    .forEach(members::add);
        }
        assertThat(members).hasSize(10);
        for (String member : members) {
            rewrite(copy, member, TestCodecs.snappy());
            assertThat(codecs(copy.resolve(member))).as(member).isSubsetOf(CompressionCodecName.SNAPPY);
        }
        assertThat(MzPeakReader.verifyIntegrity(copy).valid()).isTrue();
        Path classLog = tmp.resolve("classes.log");
        List<String> options = new ArrayList<>(List.of("-Xlog:class+load=info:file=" + classLog));
        if (Runtime.version().feature() >= 23) {
            options.add("--sun-misc-unsafe-memory-access=allow");
        }

        ChildJvm.Result child = ChildJvm.run(tmp, options, MzPeakInfo.class, copy.toString());

        assertThat(child.stderr()).isEmpty();
        assertThat(child.exitStatus()).isZero();
        // what MzPeakInfoTest pins for small.mzpeak, the same data
        assertThat(child.stdout().lines()).containsExactly(
                "Dataset: " + copy,
                "Spectra: 48",
                "MS levels: {1=14, 2=34}",
                "Chromatograms: 1",
                "  - TIC (MS:1000235) 48 points",
                "First 5 spectra:",
                "  #0  MS1  rt=0.0049  points=13589  peaks=0  PROFILE",
                "  #1  MS1  rt=0.0079  points=18177  peaks=0  PROFILE",
                "  #2  MS2  rt=0.0112  points=485  peaks=485  CENTROID  precursor=810.7894",
                "  #3  MS2  rt=0.0228  points=1006  peaks=1006  CENTROID  precursor=837.3446",
                "  #4  MS2  rt=0.0349  points=837  peaks=837  CENTROID  precursor=725.3621");
        String classes = Files.readString(classLog, StandardCharsets.UTF_8);
        assertThat(classes).contains("io.airlift.compress.snappy.SnappyDecompressor")
                .doesNotContain("com.github.luben.zstd")
                .doesNotContain("org.xerial.snappy")
                .doesNotContain("org.apache.hadoop.util.NativeCodeLoader");
    }

    /** The 48 spectra of {@code copy} equal the fixture's: arrays, peaks and descriptions. */
    private static void assertSameSpectra(Path copy) {
        assertThat(MzPeakReader.verifyIntegrity(copy).valid()).isTrue();
        try (MzPeakReader expected = MzPeakReader.open(SMALL);
             MzPeakReader actual = MzPeakReader.open(copy)) {
            assertThat(actual.size()).isEqualTo(48);
            Spectrum first = actual.getSpectrumAt(0);
            assertThat(first.mz()).hasSize(13589);
            assertThat(first.mz()[0]).isCloseTo(202.606575, within(1e-6));
            assertThat(actual.getSpectrumAt(1).mz()).hasSize(18177);
            for (int i = 0; i < 48; i++) {
                Spectrum e = expected.getSpectrumAt(i);
                Spectrum a = actual.getSpectrumAt(i);
                LayoutEquivalenceTest.assertIdentical("spectrum " + i + " m/z", e.mz(), a.mz());
                LayoutEquivalenceTest.assertIdentical("spectrum " + i + " intensity", e.intensity(), a.intensity());
                assertThat(a.peaks()).as("spectrum %d peaks", i).isEqualTo(e.peaks());
                assertThat(a.description()).as("spectrum %d description", i).isEqualTo(e.description());
            }
        }
    }

    /**
     * Writes the member {@code name} of the dataset copy {@code dataset} again through parquet-java with
     * {@code codecs}: same schema, rows and footer key-values (parquet-java writes its own
     * {@code writer.model.name}), pages of {@link #PAGE_SIZE}; the manifest checksum follows.
     */
    static void rewrite(Path dataset, String name, TestCodecs codecs) {
        Path member = dataset.resolve(name);
        Path rewritten = dataset.resolve(name + ".rewritten");
        ParquetFooter footer = ParquetFooter.read(new LocalInputFile(member));
        Map<String, String> keyValue = new LinkedHashMap<>(footer.keyValue());
        keyValue.remove("writer.model.name");
        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(member, rows::add);
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(rewritten))
                .withConf(new PlainParquetConfiguration())
                .withType(footer.schema())
                .withExtraMetaData(keyValue)
                .withCodecFactory(codecs)
                .withCompressionCodec(codecs.codec())
                .withPageSize(PAGE_SIZE)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (Group row : rows) {
                writer.write(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Files.move(rewritten, member, StandardCopyOption.REPLACE_EXISTING);
            updateChecksum(dataset, name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Sets the manifest checksum of the member {@code name} of {@code dataset} to the member's SHA-512. */
    private static void updateChecksum(Path dataset, String name) throws IOException {
        ObjectNode index = (ObjectNode) MAPPER.readTree(dataset.resolve(INDEX).toFile());
        int[] updated = {0};
        String checksum = Sha512.hex(dataset.resolve(name));
        index.get("files").forEach(e -> {
            if (e.get("name").asText().equals(name)) {
                ((ObjectNode) e).put("checksum", checksum);
                updated[0]++;
            }
        });
        assertThat(updated[0]).as("manifest entries of %s", name).isEqualTo(1);
        Files.writeString(dataset.resolve(INDEX), MAPPER.writeValueAsString(index), StandardCharsets.UTF_8);
    }

    /** The codecs of every column chunk of {@code member}. */
    static List<CompressionCodecName> codecs(Path member) throws IOException {
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(member),
                ParquetGroups.readOptions())) {
            return reader.getFooter().getBlocks().stream()
                    .map(BlockMetaData::getColumns)
                    .flatMap(List::stream)
                    .map(ColumnChunkMetaData::getCodec)
                    .distinct()
                    .toList();
        }
    }

    /** {@code page} with every byte after the first four replaced by {@code 0xFF}. */
    private static byte[] corrupt(byte[] page) {
        byte[] out = page.clone();
        Arrays.fill(out, Math.min(4, out.length), out.length, (byte) 0xFF);
        return out;
    }

    /** The messages of {@code e} and its causes. */
    private static List<String> messages(Throwable e) {
        List<String> out = new ArrayList<>();
        for (Throwable t = e; t != null; t = t.getCause()) {
            out.add(String.valueOf(t.getMessage()));
        }
        return out;
    }
}
