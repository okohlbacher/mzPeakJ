package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.WriterSnapshotTest.Snapshot;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what each short {@link MzPeakWriter} overload writes, before WR-0c routes all 14 public overloads through one
 * private path: every short overload writes exactly what its full form writes with the defaults filled in (no
 * wavelength spectra, no scan settings, {@link FileMetadata#EMPTY}, Numpress SLOF), and a {@code null} Numpress
 * intensity encoding writes SLOF.
 *
 * <p>Outputs are compared with {@link WriterSnapshotTest#snapshot}, member digests plus the ZIP entry order of an
 * archive, on the legacy {@code small.unpacked} fixture read with {@link ForwardBackwardCorpusTest#readSource}.
 */
class WriterOverloadsCharacterizationTest {

    private static final Path SMALL = Fixtures.legacy("small.unpacked.mzpeak");
    private static final FileMetadata EMPTY = FileMetadata.EMPTY;

    static Stream<Arguments> shortOverloads() {
        return Stream.of(
                Arguments.of("writeDirectory(spectra)", false,
                        write((out, s) -> MzPeakWriter.writeDirectory(out, s.spectra)),
                        write((out, s) -> MzPeakWriter.writeDirectory(out, s.spectra, List.of(), List.of(), List.of(),
                                EMPTY))),
                Arguments.of("writeDirectory(spectra, chromatograms)", false,
                        write((out, s) -> MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms)),
                        write((out, s) -> MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, List.of(),
                                List.of(), EMPTY))),
                Arguments.of("writeDirectory(spectra, chromatograms, fileMetadata)", false,
                        write((out, s) -> MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms,
                                s.fileMetadata)),
                        write((out, s) -> MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, List.of(),
                                List.of(), s.fileMetadata))),
                Arguments.of("writeArchive(spectra, chromatograms)", true,
                        write((out, s) -> MzPeakWriter.writeArchive(out, s.spectra, s.chromatograms)),
                        write((out, s) -> MzPeakWriter.writeArchive(out, s.spectra, s.chromatograms, List.of(),
                                List.of(), EMPTY))),
                Arguments.of("writeArchive(spectra, chromatograms, fileMetadata)", true,
                        write((out, s) -> MzPeakWriter.writeArchive(out, s.spectra, s.chromatograms,
                                s.fileMetadata)),
                        write((out, s) -> MzPeakWriter.writeArchive(out, s.spectra, s.chromatograms, List.of(),
                                List.of(), s.fileMetadata))),
                Arguments.of("writeDirectoryNumpress(spectra, chromatograms)", false,
                        write((out, s) -> MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms)),
                        write((out, s) -> MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms,
                                List.of(), List.of(), EMPTY, NumpressIntensityEncoding.SLOF))),
                Arguments.of("writeDirectoryNumpress(spectra, chromatograms, fileMetadata)", false,
                        write((out, s) -> MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms,
                                s.fileMetadata)),
                        write((out, s) -> MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms,
                                List.of(), List.of(), s.fileMetadata, NumpressIntensityEncoding.SLOF))),
                Arguments.of("writeDirectoryNumpress(spectra, chromatograms, fileMetadata, PIC)", false,
                        write((out, s) -> MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms,
                                s.fileMetadata, NumpressIntensityEncoding.PIC)),
                        write((out, s) -> MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms,
                                List.of(), List.of(), s.fileMetadata, NumpressIntensityEncoding.PIC))),
                Arguments.of("writeArchiveNumpress(spectra, chromatograms)", true,
                        write((out, s) -> MzPeakWriter.writeArchiveNumpress(out, s.spectra, s.chromatograms)),
                        write((out, s) -> MzPeakWriter.writeArchiveNumpress(out, s.spectra, s.chromatograms,
                                List.of(), List.of(), EMPTY))),
                Arguments.of("writeArchiveNumpress(spectra, chromatograms, fileMetadata)", true,
                        write((out, s) -> MzPeakWriter.writeArchiveNumpress(out, s.spectra, s.chromatograms,
                                s.fileMetadata)),
                        write((out, s) -> MzPeakWriter.writeArchiveNumpress(out, s.spectra, s.chromatograms,
                                List.of(), List.of(), s.fileMetadata))),
                Arguments.of("writeDirectoryNumpress(spectra, chromatograms, fileMetadata, null)", false,
                        write((out, s) -> MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms,
                                s.fileMetadata, null)),
                        write((out, s) -> MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms,
                                List.of(), List.of(), s.fileMetadata, NumpressIntensityEncoding.SLOF))),
                Arguments.of("writeDirectoryNumpress(full form, null)", false,
                        write((out, s) -> MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms,
                                s.wavelengthSpectra, s.scanSettings, s.fileMetadata, null)),
                        write((out, s) -> MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms,
                                s.wavelengthSpectra, s.scanSettings, s.fileMetadata, NumpressIntensityEncoding.SLOF))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shortOverloads")
    void aShortOverloadWritesWhatItsFullFormWrites(String label, boolean archive, BiConsumer<Path, Source> shortForm,
                                                    BiConsumer<Path, Source> fullForm, @TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(SMALL);

        Snapshot expected = snapshot(tmp.resolve("full"), archive, fullForm, s);
        Snapshot actual = snapshot(tmp.resolve("short"), archive, shortForm, s);

        assertThat(actual).as("%s against its full form", label).isEqualTo(expected);
        assertThat(actual.members()).as("members written by %s", label).containsKey("spectra_metadata.parquet");
        assertThat(actual.zipEntries().isEmpty()).as("%s wrote an archive", label).isEqualTo(!archive);
    }

    /** The defaults the short overloads fill in each change the output of {@code small.unpacked}. */
    @Test
    void eachFilledInDefaultChangesTheOutput(@TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(SMALL);

        Snapshot point = snapshot(tmp.resolve("point"), false, (out, x) -> MzPeakWriter.writeDirectory(out,
                x.spectra, x.chromatograms, List.of(), List.of(), x.fileMetadata), s);
        Snapshot noMetadata = snapshot(tmp.resolve("no-metadata"), false, (out, x) -> MzPeakWriter.writeDirectory(
                out, x.spectra, x.chromatograms, List.of(), List.of(), EMPTY), s);
        Snapshot noChromatograms = snapshot(tmp.resolve("no-chromatograms"), false, (out, x) ->
                MzPeakWriter.writeDirectory(out, x.spectra, List.of(), List.of(), List.of(), x.fileMetadata), s);
        Snapshot slof = snapshot(tmp.resolve("slof"), false, (out, x) -> MzPeakWriter.writeDirectoryNumpress(out,
                x.spectra, x.chromatograms, List.of(), List.of(), x.fileMetadata, NumpressIntensityEncoding.SLOF), s);
        Snapshot pic = snapshot(tmp.resolve("pic"), false, (out, x) -> MzPeakWriter.writeDirectoryNumpress(out,
                x.spectra, x.chromatograms, List.of(), List.of(), x.fileMetadata, NumpressIntensityEncoding.PIC), s);

        assertThat(noMetadata.members().get("spectra_metadata.parquet"))
                .isNotEqualTo(point.members().get("spectra_metadata.parquet"));
        assertThat(noMetadata.members().get("mzpeak_index.json")).isNotEqualTo(point.members().get("mzpeak_index.json"));
        assertThat(noChromatograms.members()).doesNotContainKey("chromatograms_data.parquet");
        assertThat(point.members()).containsKey("chromatograms_data.parquet");
        assertThat(slof.members().get("spectra_data.parquet")).isNotEqualTo(point.members().get("spectra_data.parquet"));
        assertThat(pic.members().get("spectra_data.parquet")).isNotEqualTo(slof.members().get("spectra_data.parquet"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Lets the argument table name its lambdas without a cast. */
    private static BiConsumer<Path, Source> write(BiConsumer<Path, Source> write) {
        return write;
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
}
