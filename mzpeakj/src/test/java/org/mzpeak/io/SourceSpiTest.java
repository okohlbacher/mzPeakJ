package org.mzpeak.io;

import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.model.Spectrum;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AP-1: {@link MzPeakReader#open(MzPeakSource)} and {@link MzPeakReader#open(MzPeakSource, MzPeakReader.Options)}
 * read a dataset from any {@link MzPeakSource}, and the reader owns the source (design section 9.2).
 *
 * <p>Oracle: {@link MzPeakReader#open(Path)} on the same fixture. The in-memory source of {@link TestSources} reads
 * directories with {@link java.nio.file.Files} and ZIPs with {@link java.util.zip.ZipFile}, so it shares no
 * container code with the reader's own sources.
 */
class SourceSpiTest {

    static Stream<Arguments> memoryFixtures() {
        return Stream.of(
                Arguments.of(Fixtures.current("small.unpacked.mzpeak")),
                Arguments.of(Fixtures.current("has_uv.mzpeak")),
                Arguments.of(Fixtures.legacy("small.unpacked.mzpeak")),
                // a ZIP whose spectra carry an extra array (MS:1003006), so arrays() and peakArrays() are compared
                Arguments.of(Fixtures.current("diaPASEF.ref.mzpeak")));
    }

    @ParameterizedTest
    @MethodSource("memoryFixtures")
    void memorySourceReadsLikeThePath(Path fixture) {
        TestSources.Recording source = TestSources.recording(TestSources.memory(fixture));
        try (MzPeakReader fromPath = MzPeakReader.open(fixture);
             MzPeakReader fromMemory = MzPeakReader.open(source)) {
            List<String> expected = TestSources.content(fromPath);
            assertThat(TestSources.content(fromMemory)).containsExactlyElementsOf(expected);

            // the comparison covers decoded data, not only failures read alike
            assertThat(fromPath.size()).isPositive();
            assertThat(expected).noneMatch(line -> line.contains(" throws "));
            assertThat(fromMemory.options()).isEqualTo(MzPeakReader.Options.defaults());
            // the reader read the spectrum members through the source
            for (DataKind kind : List.of(DataKind.METADATA, DataKind.DATA_ARRAYS, DataKind.PEAKS)) {
                fromPath.manifest().find(EntityType.SPECTRUM, kind)
                        .ifPresent(entry -> assertThat(source.opened()).contains(entry.name()));
            }
        }
        assertThat(source.closeCount()).isEqualTo(1);
    }

    @Test
    void memorySourceCoversChromatogramsWavelengthsAndExtras() {
        try (MzPeakReader small = MzPeakReader.open(TestSources.memory(Fixtures.current("small.unpacked.mzpeak")));
             MzPeakReader uv = MzPeakReader.open(TestSources.memory(Fixtures.current("has_uv.mzpeak")));
             MzPeakReader dia = MzPeakReader.open(TestSources.memory(Fixtures.current("diaPASEF.ref.mzpeak")))) {
            assertThat(small.chromatograms()).isNotEmpty();
            assertThat(small.getSpectrumAt(0).mz()).isNotEmpty();
            assertThat(small.getSpectrum(5).orElseThrow().peaks()).isNotEmpty();
            assertThat(small.fileMetadata()).isNotNull();
            assertThat(uv.wavelengthSpectra()).isNotEmpty();
            Spectrum s0 = dia.getSpectrumAt(0);
            assertThat(s0.peakArrays()).containsOnlyKeys("MS:1003006");
            assertThat(s0.ionMobility()).isPresent();
        }
    }

    @Test
    void closeClosesTheSourceExactlyOnce() {
        TestSources.Recording source =
                TestSources.recording(TestSources.memory(Fixtures.current("small.unpacked.mzpeak")));
        MzPeakReader reader = MzPeakReader.open(source);
        reader.getSpectrumAt(0);
        reader.getSpectrum(5);
        reader.chromatograms();
        assertThat(source.closeCount()).isZero();

        reader.close();
        assertThat(source.closeCount()).isEqualTo(1);
        reader.close();
        assertThat(source.closeCount()).isEqualTo(1);
    }

    @Test
    void closeClosesTheSourceOnceWhenNothingWasRead() {
        TestSources.Recording source = TestSources.recording(MzPeakSource.open(Fixtures.legacy("small.mzpeak")));
        try (MzPeakReader reader = MzPeakReader.open(source, MzPeakReader.Options.defaults())) {
            assertThat(reader.size()).isEqualTo(48);
        }
        assertThat(source.closeCount()).isEqualTo(1);
    }

    @Test
    void malformedManifestThrowsAndClosesTheSourceOnce() {
        TestSources.Recording source = TestSources.recording(TestSources.memory("broken",
                Map.of(TestSources.MANIFEST, "{".getBytes(StandardCharsets.UTF_8))));
        assertThatThrownBy(() -> MzPeakReader.open(source))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("Failed to parse mzpeak_index.json in broken");
        assertThat(source.manifestReads()).isEqualTo(1);
        assertThat(source.closeCount()).isEqualTo(1);
    }

    @Test
    void missingMetadataMemberThrowsAndClosesTheSourceOnce() {
        TestSources.Memory full = TestSources.memory(Fixtures.legacy("small.unpacked.mzpeak"));
        Map<String, byte[]> members = new HashMap<>();
        for (String name : full.names()) {
            if (!name.equals("spectra_metadata.parquet")) {
                members.put(name, full.member(name));
            }
        }
        TestSources.Recording source = TestSources.recording(TestSources.memory("partial", members));
        assertThatThrownBy(() -> MzPeakReader.open(source))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("partial has no member spectra_metadata.parquet");
        assertThat(source.opened()).contains("spectra_metadata.parquet");
        assertThat(source.closeCount()).isEqualTo(1);
    }

    @Test
    void nullArgumentsThrowNullPointerException() {
        Path fixture = Fixtures.legacy("small.unpacked.mzpeak");
        assertThatThrownBy(() -> MzPeakReader.open((MzPeakSource) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MzPeakReader.open((MzPeakSource) null, MzPeakReader.Options.defaults()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MzPeakReader.open((Path) null, MzPeakReader.Options.defaults()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MzPeakReader.open(fixture, (MzPeakReader.Options) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MzPeakReader.open((Path) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MzPeakReader.open((Path) null, false))
                .isInstanceOf(NullPointerException.class);

        // the reader owns a source as soon as it is handed over: a null options still closes it
        TestSources.Recording source = TestSources.recording(TestSources.memory(fixture));
        assertThatThrownBy(() -> MzPeakReader.open(source, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(source.closeCount()).isEqualTo(1);
        assertThat(source.manifestReads()).isZero();
    }

    @Test
    void recordingLogsMembersInCallOrder() {
        TestSources.Recording source =
                TestSources.recording(TestSources.memory(Fixtures.legacy("small.unpacked.mzpeak")));
        try (MzPeakReader reader = MzPeakReader.open(source)) {
            assertThat(source.manifestReads()).isEqualTo(1);
            assertThat(source.opened()).contains("spectra_metadata.parquet")
                    .doesNotContain("spectra_data.parquet", "spectra_peaks.parquet");

            reader.getSpectrumAt(0); // signal members are opened lazily, on first use
            List<String> afterFirst = source.opened();
            assertThat(afterFirst).contains("spectra_data.parquet", "spectra_peaks.parquet");
            reader.getSpectrumAt(1); // and cached
            assertThat(source.opened()).isEqualTo(afterFirst);
        }
    }

    @Test
    void countingCountsTheBytesReadPerMember() throws IOException {
        Path fixture = Fixtures.legacy("small.unpacked.mzpeak");
        TestSources.Counting source = TestSources.counting(TestSources.memory(fixture));
        byte[] manifest = source.readManifestBytes();
        assertThat(source.bytesRead(TestSources.MANIFEST)).isEqualTo(manifest.length);

        InputFile file = source.inputFile("spectra_peaks.parquet");
        assertThat(source.bytesRead("spectra_peaks.parquet")).isZero();
        try (SeekableInputStream in = file.newStream()) {
            assertThat(in.read()).isEqualTo('P');
            byte[] three = new byte[3];
            in.readFully(three);
            assertThat(new String(three, StandardCharsets.US_ASCII)).isEqualTo("AR1");
            in.seek(file.getLength() - 4);
            ByteBuffer magic = ByteBuffer.allocate(4);
            in.readFully(magic);
            assertThat(new String(magic.array(), StandardCharsets.US_ASCII)).isEqualTo("PAR1");
            in.seek(0);
            assertThat(in.read(new byte[10], 2, 5)).isEqualTo(5);
            assertThat(in.read(ByteBuffer.allocate(6))).isEqualTo(6);
            in.readFully(new byte[2], 0, 2);
            in.seek(file.getLength());
            assertThat(in.read()).isEqualTo(-1);
            assertThat(in.read(new byte[4], 0, 4)).isEqualTo(-1);
        }
        assertThat(source.bytesRead("spectra_peaks.parquet")).isEqualTo(1 + 3 + 4 + 5 + 6 + 2);
        assertThat(source.bytesRead()).containsOnlyKeys(TestSources.MANIFEST, "spectra_peaks.parquet");
        assertThat(source.totalBytesRead()).isEqualTo(manifest.length + 21L);
    }

    @Test
    void countingSeesWhatTheReaderReads() {
        Path fixture = Fixtures.legacy("small.unpacked.mzpeak");
        TestSources.Counting source = TestSources.counting(TestSources.memory(fixture));
        try (MzPeakReader reader = MzPeakReader.open(source)) {
            assertThat(source.bytesRead("spectra_metadata.parquet")).isPositive();
            assertThat(source.bytesRead("spectra_data.parquet")).isZero();
            reader.getSpectrumAt(0);
            assertThat(source.bytesRead("spectra_data.parquet")).isPositive();
        }
    }

    @Test
    void sourceSpiAddsNoAbstractMethod() {
        List<String> abstractMethods = Arrays.stream(MzPeakSource.class.getMethods())
                .filter(m -> Modifier.isAbstract(m.getModifiers()))
                .map(SourceSpiTest::signature)
                .sorted()
                .collect(Collectors.toList());
        assertThat(abstractMethods).containsExactly(
                "close()", "describe()", "inputFile(String)", "readManifestBytes()");
    }

    private static String signature(Method m) {
        return m.getName() + Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName)
                .collect(Collectors.joining(",", "(", ")"));
    }
}
