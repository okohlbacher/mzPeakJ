package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.WriterSnapshotTest.Snapshot;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The public {@link MzPeakWriter.Options} (WR-12): every one of the 14 legacy overloads writes exactly what the
 * Options form named in its Javadoc writes, the Options archive form writes Numpress PIC, the Numpress intensity is
 * ignored in point layout, and the row-group size comes from the option, then the {@code mzpeak.writer.rowGroupSize}
 * property, then the parquet-java default. The value semantics of {@link Options} are pinned as well.
 *
 * <p>Outputs are compared with {@link WriterSnapshotTest#snapshot}, member digests plus the ZIP entry order of an
 * archive, on the legacy {@code has_uv} fixture (spectra, chromatograms, wavelength spectra, scan settings and file
 * metadata) read with {@link ForwardBackwardCorpusTest#readSource}; the Numpress archive and the Numpress row-group
 * cases use the legacy {@code small.unpacked} fixture.
 */
class WriterOptionsTest {

    private static final Path HAS_UV = Fixtures.legacy("has_uv.mzpeak");
    private static final Path SMALL = Fixtures.legacy("small.unpacked.mzpeak");
    private static final String ROW_GROUP_SIZE = "mzpeak.writer.rowGroupSize";
    private static final String DATA = "spectra_data.parquet";
    private static final String INDEX = "mzpeak_index.json";
    private static final FileMetadata EMPTY = FileMetadata.EMPTY;
    private static final Options DEFAULTS = Options.defaults();
    private static final Options NUMPRESS = Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS);
    private static final Options NUMPRESS_PIC = NUMPRESS.withNumpressIntensity(NumpressIntensityEncoding.PIC);

    /** Fixtures read once per class; the tests never modify a source in place. */
    private static final Map<Path, Source> SOURCES = new ConcurrentHashMap<>();

    // ---- every legacy overload against its Options form -----------------------------------------------------

    static Stream<Arguments> legacyOverloads() {
        return Stream.of(
                Arguments.of("writeDirectory(spectra)", false,
                        write((out, s) -> MzPeakWriter.writeDirectory(out, s.spectra)),
                        write((out, s) -> MzPeakWriter.writeDirectory(out, s.spectra, List.of(), List.of(), List.of(),
                                EMPTY, DEFAULTS))),
                Arguments.of("writeDirectory(spectra, chromatograms)", false,
                        write((out, s) -> MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms)),
                        write((out, s) -> MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, List.of(),
                                List.of(), EMPTY, DEFAULTS))),
                Arguments.of("writeDirectory(spectra, chromatograms, fileMetadata)", false,
                        write((out, s) -> MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms,
                                s.fileMetadata)),
                        write((out, s) -> MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, List.of(),
                                List.of(), s.fileMetadata, DEFAULTS))),
                Arguments.of("writeDirectory(full form)", false,
                        write((out, s) -> MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms,
                                s.wavelengthSpectra, s.scanSettings, s.fileMetadata)),
                        write((out, s) -> MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms,
                                s.wavelengthSpectra, s.scanSettings, s.fileMetadata, DEFAULTS))),
                Arguments.of("writeArchive(spectra, chromatograms)", true,
                        write((out, s) -> MzPeakWriter.writeArchive(out, s.spectra, s.chromatograms)),
                        write((out, s) -> MzPeakWriter.writeArchive(out, s.spectra, s.chromatograms, List.of(),
                                List.of(), EMPTY, DEFAULTS))),
                Arguments.of("writeArchive(spectra, chromatograms, fileMetadata)", true,
                        write((out, s) -> MzPeakWriter.writeArchive(out, s.spectra, s.chromatograms,
                                s.fileMetadata)),
                        write((out, s) -> MzPeakWriter.writeArchive(out, s.spectra, s.chromatograms, List.of(),
                                List.of(), s.fileMetadata, DEFAULTS))),
                Arguments.of("writeArchive(full form)", true,
                        write((out, s) -> MzPeakWriter.writeArchive(out, s.spectra, s.chromatograms,
                                s.wavelengthSpectra, s.scanSettings, s.fileMetadata)),
                        write((out, s) -> MzPeakWriter.writeArchive(out, s.spectra, s.chromatograms,
                                s.wavelengthSpectra, s.scanSettings, s.fileMetadata, DEFAULTS))),
                Arguments.of("writeDirectoryNumpress(spectra, chromatograms)", false,
                        write((out, s) -> MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms)),
                        write((out, s) -> MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, List.of(),
                                List.of(), EMPTY, NUMPRESS))),
                Arguments.of("writeDirectoryNumpress(spectra, chromatograms, fileMetadata)", false,
                        write((out, s) -> MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms,
                                s.fileMetadata)),
                        write((out, s) -> MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, List.of(),
                                List.of(), s.fileMetadata, NUMPRESS))),
                Arguments.of("writeDirectoryNumpress(spectra, chromatograms, fileMetadata, PIC)", false,
                        write((out, s) -> MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms,
                                s.fileMetadata, NumpressIntensityEncoding.PIC)),
                        write((out, s) -> MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, List.of(),
                                List.of(), s.fileMetadata, NUMPRESS_PIC))),
                Arguments.of("writeDirectoryNumpress(full form, PIC)", false,
                        write((out, s) -> MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms,
                                s.wavelengthSpectra, s.scanSettings, s.fileMetadata, NumpressIntensityEncoding.PIC)),
                        write((out, s) -> MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms,
                                s.wavelengthSpectra, s.scanSettings, s.fileMetadata, NUMPRESS_PIC))),
                Arguments.of("writeArchiveNumpress(spectra, chromatograms)", true,
                        write((out, s) -> MzPeakWriter.writeArchiveNumpress(out, s.spectra, s.chromatograms)),
                        write((out, s) -> MzPeakWriter.writeArchive(out, s.spectra, s.chromatograms, List.of(),
                                List.of(), EMPTY, NUMPRESS))),
                Arguments.of("writeArchiveNumpress(spectra, chromatograms, fileMetadata)", true,
                        write((out, s) -> MzPeakWriter.writeArchiveNumpress(out, s.spectra, s.chromatograms,
                                s.fileMetadata)),
                        write((out, s) -> MzPeakWriter.writeArchive(out, s.spectra, s.chromatograms, List.of(),
                                List.of(), s.fileMetadata, NUMPRESS))),
                Arguments.of("writeArchiveNumpress(full form)", true,
                        write((out, s) -> MzPeakWriter.writeArchiveNumpress(out, s.spectra, s.chromatograms,
                                s.wavelengthSpectra, s.scanSettings, s.fileMetadata)),
                        write((out, s) -> MzPeakWriter.writeArchive(out, s.spectra, s.chromatograms,
                                s.wavelengthSpectra, s.scanSettings, s.fileMetadata, NUMPRESS))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("legacyOverloads")
    void aLegacyOverloadWritesWhatItsOptionsFormWrites(String label, boolean archive, BiConsumer<Path, Source> legacy,
                                                       BiConsumer<Path, Source> withOptions, @TempDir Path tmp) {
        Source s = source(HAS_UV);

        Snapshot expected = snapshot(tmp.resolve("options"), archive, withOptions, s);
        Snapshot actual = snapshot(tmp.resolve("legacy"), archive, legacy, s);

        assertThat(actual).as("%s against its Options form", label).isEqualTo(expected);
        assertThat(actual.members()).as("members written by %s", label).containsKeys("spectra_metadata.parquet", DATA);
        assertThat(actual.zipEntries().isEmpty()).as("%s wrote an archive", label).isEqualTo(!archive);
    }

    /** The settings the Options forms receive above each change the output, so the comparison can tell them apart. */
    @Test
    void eachSettingOfTheLegacyOverloadsChangesTheOutput(@TempDir Path tmp) {
        Source s = source(HAS_UV);

        Snapshot point = snapshot(tmp.resolve("point"), false, options(DEFAULTS), s);
        Snapshot slof = snapshot(tmp.resolve("slof"), false, options(NUMPRESS), s);
        Snapshot pic = snapshot(tmp.resolve("pic"), false, options(NUMPRESS_PIC), s);

        assertThat(slof.members().get(DATA)).isNotEqualTo(point.members().get(DATA));
        assertThat(pic.members().get(DATA)).isNotEqualTo(slof.members().get(DATA));
        assertThat(point.members()).containsKey("wavelength_spectra_data.parquet");
    }

    // ---- the Options forms ------------------------------------------------------------------------------------

    /** The archive form reaches Numpress PIC, which no legacy archive overload does. */
    @Test
    void aNumpressPicArchiveReadsBackLikeThePicDirectory(@TempDir Path tmp) {
        Source s = source(SMALL);
        Path directory = tmp.resolve("pic-dir.mzpeak");
        Path archive = tmp.resolve("pic.mzpeak");
        MzPeakWriter.writeDirectoryNumpress(directory, s.spectra, s.chromatograms, s.wavelengthSpectra,
                s.scanSettings, s.fileMetadata, NumpressIntensityEncoding.PIC);
        MzPeakWriter.writeArchive(archive, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, NUMPRESS_PIC);

        assertThat(Files.isRegularFile(archive)).as("%s is a single file", archive).isTrue();
        assertThat(WriterSnapshotTest.snapshot(archive, true).members())
                .isEqualTo(WriterSnapshotTest.snapshot(directory, false).members());

        int integral = 0;
        try (MzPeakReader fromDirectory = MzPeakReader.open(directory);
             MzPeakReader fromArchive = MzPeakReader.open(archive)) {
            assertThat(fromArchive.size()).isEqualTo(fromDirectory.size()).isEqualTo(s.spectra.size());
            for (int i = 0; i < fromDirectory.size(); i++) {
                Spectrum expected = fromDirectory.getSpectrumAt(i);
                Spectrum actual = fromArchive.getSpectrumAt(i);
                assertThat(actual.description()).as("description %d", i).isEqualTo(expected.description());
                assertThat(actual.mz()).as("m/z of spectrum %d", i).isEqualTo(expected.mz());
                assertThat(actual.intensity()).as("intensity of spectrum %d", i).isEqualTo(expected.intensity());
                assertThat(actual.peaks()).as("peaks of spectrum %d", i).isEqualTo(expected.peaks());
                for (double v : actual.intensity()) {
                    assertThat(v).as("PIC intensity of spectrum %d", i).isEqualTo(Math.rint(v));
                    integral++;
                }
            }
            assertThat(fromArchive.chromatograms()).hasSameSizeAs(fromDirectory.chromatograms());
        }
        assertThat(integral).as("intensities checked").isPositive();
    }

    @Test
    void theNumpressIntensityIsIgnoredInPointLayout(@TempDir Path tmp) {
        Source s = source(HAS_UV);

        Snapshot slof = snapshot(tmp.resolve("slof"), false, options(DEFAULTS), s);
        Snapshot pic = snapshot(tmp.resolve("pic"), false,
                options(DEFAULTS.withNumpressIntensity(NumpressIntensityEncoding.PIC)), s);

        assertThat(pic).isEqualTo(slof);
    }

    @Test
    void aNullOptionsIsRejectedBeforeAnythingIsWritten(@TempDir Path tmp) {
        Source s = source(SMALL);
        Path directory = tmp.resolve("dir.mzpeak");
        Path archive = tmp.resolve("out.mzpeak");

        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(directory, s.spectra, s.chromatograms,
                s.wavelengthSpectra, s.scanSettings, s.fileMetadata, null))
                .isInstanceOf(NullPointerException.class).hasMessage("options");
        assertThatThrownBy(() -> MzPeakWriter.writeArchive(archive, s.spectra, s.chromatograms,
                s.wavelengthSpectra, s.scanSettings, s.fileMetadata, null))
                .isInstanceOf(NullPointerException.class).hasMessage("options");
        assertThat(entries(tmp)).isEmpty();
    }

    // ---- row-group size ---------------------------------------------------------------------------------------

    /** Only the row-group count changes; members below parquet-java's first size check stay in one row group. */
    @Test
    void aRowGroupSizeOptionSplitsTheLargeMembers(@TempDir Path tmp) {
        assertPropertyUnset();
        Source s = source(HAS_UV);

        Snapshot plain = snapshot(tmp.resolve("default"), false, options(DEFAULTS), s);
        Snapshot split = snapshot(tmp.resolve("split"), false, options(DEFAULTS.withRowGroupSize(32768)), s);

        assertThat(split.members().keySet()).isEqualTo(plain.members().keySet());
        for (Map.Entry<String, ContentDigest> e : split.members().entrySet()) {
            String name = e.getKey();
            ContentDigest before = plain.members().get(name);
            if (name.equals(INDEX)) {
                assertThat(e.getValue()).isEqualTo(before);
                continue;
            }
            assertThat(rowGroups(before)).as("default row groups of %s", name).isEqualTo(1);
            assertThat(withoutRowGroups(e.getValue().detail())).as("detail of %s", name)
                    .isEqualTo(withoutRowGroups(before.detail()));
        }
        for (String name : List.of(DATA, "spectra_peaks.parquet", "wavelength_spectra_data.parquet")) {
            assertThat(rowGroups(split.members().get(name))).as("row groups of %s", name).isGreaterThan(1);
        }
        assertThat(rowGroups(split.members().get("chromatograms_metadata.parquet"))).isEqualTo(1);
    }

    /** The Numpress chunks get the option too: it writes exactly what the property of the same size writes. */
    @Test
    void aRowGroupSizeOptionSplitsTheNumpressChunksLikeTheProperty(@TempDir Path tmp) {
        assertPropertyUnset();
        Source s = source(SMALL);

        Snapshot plain = snapshot(tmp.resolve("default"), false, options(NUMPRESS), s);
        Snapshot option = snapshot(tmp.resolve("option"), false, options(NUMPRESS.withRowGroupSize(32768)), s);
        Snapshot property;
        System.setProperty(ROW_GROUP_SIZE, "32768");
        try {
            property = snapshot(tmp.resolve("property"), false, options(NUMPRESS), s);
        } finally {
            System.clearProperty(ROW_GROUP_SIZE);
        }

        assertThat(option).isEqualTo(property);
        assertThat(rowGroups(plain.members().get(DATA))).as("default row groups of %s", DATA).isEqualTo(1);
        assertThat(rowGroups(option.members().get(DATA))).as("row groups of %s", DATA).isEqualTo(6);
    }

    @Test
    void theOptionTakesPrecedenceOverTheProperty(@TempDir Path tmp) {
        assertPropertyUnset();
        Source s = source(HAS_UV);
        Options option = DEFAULTS.withRowGroupSize(32768);

        Snapshot optionOnly = snapshot(tmp.resolve("option"), false, options(option), s);
        Snapshot propertyOnly;
        Snapshot both;
        System.setProperty(ROW_GROUP_SIZE, "4096");
        try {
            propertyOnly = snapshot(tmp.resolve("property"), false, options(DEFAULTS), s);
            both = snapshot(tmp.resolve("both"), false, options(option), s);
        } finally {
            System.clearProperty(ROW_GROUP_SIZE);
        }

        assertThat(both).isEqualTo(optionOnly);
        assertThat(rowGroups(propertyOnly.members().get(DATA))).as("row groups with the property alone")
                .isGreaterThan(rowGroups(optionOnly.members().get(DATA)));
        assertThat(rowGroups(optionOnly.members().get(DATA))).as("row groups with the option").isGreaterThan(1);
    }

    @Test
    void rowGroupSizeIsTheOptionThenThePropertyThenNone() {
        assertPropertyUnset();
        assertThat(ParquetOutput.rowGroupSize(DEFAULTS)).isEmpty();
        assertThat(ParquetOutput.rowGroupSize(DEFAULTS.withRowGroupSize(32768))).hasValue(32768);
        System.setProperty(ROW_GROUP_SIZE, "4096");
        try {
            assertThat(ParquetOutput.rowGroupSize(DEFAULTS)).hasValue(4096);
            assertThat(ParquetOutput.rowGroupSize(DEFAULTS.withRowGroupSize(32768))).hasValue(32768);
        } finally {
            System.clearProperty(ROW_GROUP_SIZE);
        }
    }

    // ---- Options values ---------------------------------------------------------------------------------------

    @Test
    void defaultsArePointLayoutSlofAndNoRowGroupSize() {
        assertThat(DEFAULTS.signalEncoding()).isEqualTo(SignalEncoding.POINT);
        assertThat(DEFAULTS.numpressIntensity()).isEqualTo(NumpressIntensityEncoding.SLOF);
        assertThat(DEFAULTS.rowGroupSize()).isEqualTo(OptionalLong.empty());
        assertThat(Options.defaults()).isSameAs(DEFAULTS);
        assertThat(DEFAULTS).hasToString(
                "Options[signalEncoding=POINT, numpressIntensity=SLOF, rowGroupSize=OptionalLong.empty]");
    }

    @Test
    void withersReturnChangedCopiesAndLeaveTheReceiverAlone() {
        Options numpress = DEFAULTS.withSignalEncoding(SignalEncoding.NUMPRESS);
        Options pic = numpress.withNumpressIntensity(NumpressIntensityEncoding.PIC);
        Options sized = pic.withRowGroupSize(32768);

        assertThat(numpress.signalEncoding()).isEqualTo(SignalEncoding.NUMPRESS);
        assertThat(numpress.numpressIntensity()).isEqualTo(NumpressIntensityEncoding.SLOF);
        assertThat(pic.signalEncoding()).isEqualTo(SignalEncoding.NUMPRESS);
        assertThat(pic.numpressIntensity()).isEqualTo(NumpressIntensityEncoding.PIC);
        assertThat(pic.rowGroupSize()).isEmpty();
        assertThat(sized.rowGroupSize()).hasValue(32768);
        assertThat(sized.signalEncoding()).isEqualTo(SignalEncoding.NUMPRESS);
        assertThat(sized.numpressIntensity()).isEqualTo(NumpressIntensityEncoding.PIC);
        assertThat(sized).hasToString(
                "Options[signalEncoding=NUMPRESS, numpressIntensity=PIC, rowGroupSize=OptionalLong[32768]]");

        assertThat(DEFAULTS.signalEncoding()).isEqualTo(SignalEncoding.POINT);
        assertThat(DEFAULTS.numpressIntensity()).isEqualTo(NumpressIntensityEncoding.SLOF);
        assertThat(DEFAULTS.rowGroupSize()).isEmpty();
        assertThat(numpress.numpressIntensity()).isEqualTo(NumpressIntensityEncoding.SLOF);
        assertThat(pic.rowGroupSize()).isEmpty();
        assertThat(sized.withSignalEncoding(SignalEncoding.POINT)).isNotSameAs(sized);
        assertThat(sized.signalEncoding()).isEqualTo(SignalEncoding.NUMPRESS);
    }

    @Test
    void optionsAreComparedByValue() {
        Options a = DEFAULTS.withSignalEncoding(SignalEncoding.NUMPRESS).withRowGroupSize(4096);
        Options b = DEFAULTS.withRowGroupSize(4096).withSignalEncoding(SignalEncoding.NUMPRESS);

        assertThat(a).isNotSameAs(b).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(DEFAULTS.withSignalEncoding(SignalEncoding.POINT)).isEqualTo(DEFAULTS)
                .hasSameHashCodeAs(DEFAULTS);
        assertThat(a).isNotEqualTo(a.withRowGroupSize(8192));
        assertThat(a).isNotEqualTo(a.withNumpressIntensity(NumpressIntensityEncoding.PIC));
        assertThat(a).isNotEqualTo(a.withSignalEncoding(SignalEncoding.POINT));
        assertThat(DEFAULTS).isNotEqualTo(DEFAULTS.withRowGroupSize(4096));
        assertThat(DEFAULTS).isNotEqualTo(null).isNotEqualTo(DEFAULTS.toString());
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThatThrownBy(() -> DEFAULTS.withRowGroupSize(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("row-group size must be positive: 0");
        assertThatThrownBy(() -> DEFAULTS.withRowGroupSize(-1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("row-group size must be positive: -1");
        assertThatThrownBy(() -> DEFAULTS.withSignalEncoding(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("encoding");
        assertThatThrownBy(() -> DEFAULTS.withNumpressIntensity(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("encoding");
        assertThat(DEFAULTS.withRowGroupSize(1).rowGroupSize()).hasValue(1);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** Lets the argument table name its lambdas without a cast. */
    private static BiConsumer<Path, Source> write(BiConsumer<Path, Source> write) {
        return write;
    }

    /** The full directory form with {@code options}. */
    private static BiConsumer<Path, Source> options(Options options) {
        return (out, s) -> MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra,
                s.scanSettings, s.fileMetadata, options);
    }

    private static Source source(Path fixture) {
        return SOURCES.computeIfAbsent(fixture, ForwardBackwardCorpusTest::readSource);
    }

    private static Snapshot snapshot(Path dir, boolean archive, BiConsumer<Path, Source> write, Source s) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path out = dir.resolve("out.mzpeak");
        write.accept(out, s);
        return WriterSnapshotTest.snapshot(out, archive);
    }

    private static void assertPropertyUnset() {
        assertThat(System.getProperty(ROW_GROUP_SIZE)).as("%s before the test", ROW_GROUP_SIZE).isNull();
    }

    private static int rowGroups(ContentDigest digest) {
        String detail = digest.detail();
        assertThat(detail).as("detail of %s", digest.member()).startsWith("row_groups=");
        return Integer.parseInt(detail.substring("row_groups=".length(), detail.indexOf(' ')));
    }

    private static String withoutRowGroups(String detail) {
        return detail.replaceFirst("^row_groups=[0-9]+ ", "");
    }

    private static List<Path> entries(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
