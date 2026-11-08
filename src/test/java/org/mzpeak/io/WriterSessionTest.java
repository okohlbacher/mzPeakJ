package org.mzpeak.io;

import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.Session;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.WriterSnapshotTest.FixtureSet;
import org.mzpeak.io.WriterSnapshotTest.Overload;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MzPeakWriter.Session} (MZ-11, design §7): a dataset written one spectrum at a time through
 * {@link MzPeakWriter#openArchive} or {@link MzPeakWriter#openDirectory} is the dataset the batch overloads write of
 * the same input; a spectrum that breaks the session's contract is refused before anything of it is written, and the
 * session goes on; the public factories refuse an existing archive and a directory that is not empty; a finish that
 * fails and a close without a finish leave no archive, no temp file and no member the session wrote.
 *
 * <p>Oracles: the batch output of every {@link WriterSnapshotTest} case, compared by {@link ContentDigest} (within one
 * JVM the same input gives the same bytes); current small.unpacked (48 spectra, MS1 profile and MS2 centroid, with
 * chromatograms) for the contract and the targets.
 */
class WriterSessionTest {

    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final String INDEX = ManifestWriter.INDEX;
    private static final String DATA = "spectra_data.parquet";
    private static final String PEAKS = "spectra_peaks.parquet";
    /** An extra array key: mean inverse reduced ion mobility. */
    private static final String ION_MOBILITY = "MS:1003006";

    /** Fixtures read once, keyed by path; the tests never modify a source in place. */
    private static final Map<Path, Source> SOURCES = new ConcurrentHashMap<>();

    // ---- the same dataset as the batch overloads ---------------------------------------------------------------

    /**
     * Each {@link WriterSnapshotTest} case written through a session, its spectra added in index order, its
     * chromatograms and wavelength spectra after them, digests as the batch overload of the case writes it: both
     * signal encodings, both metadata layouts, archives and directories, chromatograms and wavelength spectra.
     */
    @ParameterizedTest(name = "{0} {1} via {2}")
    @MethodSource("org.mzpeak.io.WriterSnapshotTest#cases")
    void aSessionWritesWhatTheBatchOverloadWrites(FixtureSet set, String fixture, Overload overload,
                                                  @TempDir Path tmp) {
        Source s = source(set.path(fixture));
        Path batch = tmp.resolve("batch.mzpeak");
        overload.write.accept(batch, s);
        Path session = tmp.resolve("session.mzpeak");
        writeSession(session, overload.archive, s, options(overload));

        WriterSnapshotTest.Snapshot expected = WriterSnapshotTest.snapshot(batch, overload.archive);
        assertThat(expected.members()).containsKeys(INDEX, DATA, "spectra_metadata.parquet");
        assertThat(WriterSnapshotTest.snapshot(session, overload.archive)).isEqualTo(expected);
    }

    /** Chromatograms and wavelength spectra may be added before, between and after the spectra. */
    @Test
    void chromatogramsAndWavelengthSpectraMayComeAtAnyPoint(@TempDir Path tmp) {
        Source s = source(Fixtures.legacy("has_uv.mzpeak"));
        assertThat(s.chromatograms).isNotEmpty();
        assertThat(s.wavelengthSpectra).hasSizeGreaterThan(2);
        Path batch = tmp.resolve("batch.mzpeak");
        MzPeakWriter.writeArchive(batch, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata);
        Path out = tmp.resolve("session.mzpeak");
        try (Session session = MzPeakWriter.openArchive(out, s.fileMetadata, s.scanSettings, arrayKeys(s.spectra),
                Options.defaults())) {
            s.chromatograms.forEach(session::addChromatogram);
            session.addWavelengthSpectrum(s.wavelengthSpectra.get(0));
            for (Spectrum spectrum : s.spectra) {
                session.addSpectrum(spectrum);
                if (spectrum.index() == 0) {
                    session.addWavelengthSpectrum(s.wavelengthSpectra.get(1));
                }
            }
            s.wavelengthSpectra.subList(2, s.wavelengthSpectra.size()).forEach(session::addWavelengthSpectrum);
            session.finish();
        }
        assertThat(WriterSnapshotTest.snapshot(out, true)).isEqualTo(WriterSnapshotTest.snapshot(batch, true));
    }

    /** spectra_data and spectra_peaks carry the batch's spectrum_count and spectrum_data_point_count. */
    @ParameterizedTest
    @EnumSource(SignalEncoding.class)
    void theSignalFootersCountWhatTheBatchCounts(SignalEncoding encoding, @TempDir Path tmp) {
        Source s = source(SMALL);
        Options options = Options.defaults().withSignalEncoding(encoding);
        Path batch = tmp.resolve("batch");
        MzPeakWriter.writeDirectory(batch, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, options);
        Path session = tmp.resolve("session");
        writeSession(session, false, s, options);

        long points = 0;
        for (String member : List.of(DATA, PEAKS)) {
            Map<String, String> expected = footer(batch.resolve(member));
            Map<String, String> actual = footer(session.resolve(member));
            assertThat(actual.get("spectrum_count")).as("spectrum_count of %s", member).isEqualTo("48")
                    .isEqualTo(expected.get("spectrum_count"));
            assertThat(actual.get("spectrum_data_point_count")).as("spectrum_data_point_count of %s", member)
                    .isNotNull().isEqualTo(expected.get("spectrum_data_point_count"));
            points += Long.parseLong(actual.get("spectrum_data_point_count"));
        }
        long expectedPoints = 0;
        for (Spectrum spectrum : s.spectra) {
            expectedPoints += spectrum.mz().length;
        }
        assertThat(points).isEqualTo(expectedPoints).isPositive();
    }

    // ---- the contract of addSpectrum ---------------------------------------------------------------------------

    @Test
    void aSpectrumOutOfIndexOrderIsRefusedAndTheSessionGoesOn(@TempDir Path tmp) {
        Source s = source(SMALL);
        List<Spectrum> first = s.spectra.subList(0, 3);
        Path out = tmp.resolve("out.mzpeak");
        try (Session session = MzPeakWriter.openArchive(out, s.fileMetadata, s.scanSettings, arrayKeys(first),
                Options.defaults())) {
            assertThatThrownBy(() -> session.addSpectrum(first.get(1)))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("expected spectrum index 0, got 1");
            session.addSpectrum(first.get(0));
            assertThatThrownBy(() -> session.addSpectrum(first.get(0)))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("expected spectrum index 1, got 0");
            assertThatThrownBy(() -> session.addSpectrum(first.get(2)))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("expected spectrum index 1, got 2");
            session.addSpectrum(first.get(1));
            session.addSpectrum(first.get(2));
            session.finish();
        }
        assertThat(WriterSnapshotTest.snapshot(out, true)).isEqualTo(batchArchive(tmp, first, s));
    }

    /** A key of arrays() or peakArrays() the session was not opened with is refused, naming the key. */
    @Test
    void anUndeclaredArrayIsRefusedAndNothingOfTheSpectrumIsWritten(@TempDir Path tmp) {
        Source s = source(SMALL);
        Spectrum plain = s.spectra.get(0);
        assertThat(plain.arrays()).isEmpty();
        Spectrum withArray = new Spectrum(plain.description(), plain.mz(), plain.intensity(), plain.peaks(),
                Map.of(ION_MOBILITY, new double[plain.mz().length]), Map.of());
        Spectrum centroid = centroidWithPeakArray(s);
        Path out = tmp.resolve("out.mzpeak");
        try (Session session = MzPeakWriter.openArchive(out, s.fileMetadata, s.scanSettings, Set.of(),
                Options.defaults())) {
            assertThatThrownBy(() -> session.addSpectrum(withArray))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("spectrum 0: the array MS:1003006 is not one of the spectrum arrays the session was"
                            + " opened with []");
            session.addSpectrum(plain);
            assertThatThrownBy(() -> session.addSpectrum(centroid))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("spectrum 1: the array MS:1003006 is not one of the spectrum arrays the session was"
                            + " opened with []");
            session.finish();
        }
        assertThat(WriterSnapshotTest.snapshot(out, true)).isEqualTo(batchArchive(tmp, List.of(plain), s));
    }

    /** A declared key that is a main array of a spectrum is refused before anything is created. */
    @Test
    void aMainArrayCannotBeDeclared(@TempDir Path tmp) {
        Path out = tmp.resolve("out.mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.openDirectory(out, FileMetadata.EMPTY, List.of(),
                new LinkedHashSet<>(List.of(ION_MOBILITY, "MS:1000515")), Options.defaults()))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("spectrumArrays: the extra array MS:1000515 (intensity array) is a main array of a"
                        + " spectrum, which mz() and intensity() hold; it cannot be written as an extra column");
        assertThat(out).doesNotExist();
        assertThatThrownBy(() -> MzPeakWriter.openArchive(out, FileMetadata.EMPTY, List.of(), Set.of("MS:1000514"),
                Options.defaults()))
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("spectrumArrays: the extra array MS:1000514 (m/z array) is a main array");
        assertThat(list(tmp)).isEmpty();
    }

    /**
     * The declared spectrum arrays become the extra columns of spectra_data and spectra_peaks in their order, named
     * as the batch overload names the keys it meets in that order, and are read back.
     */
    @Test
    void declaredArraysAreWrittenAsTheBatchWritesThem(@TempDir Path tmp) {
        Source s = source(SMALL);
        Spectrum profile = s.spectra.get(0);
        double[] mobility = new double[profile.mz().length];
        for (int i = 0; i < mobility.length; i++) {
            mobility[i] = 0.5 + i * 1e-3;
        }
        Spectrum withArray = new Spectrum(profile.description(), profile.mz(), profile.intensity(), profile.peaks(),
                Map.of(ION_MOBILITY, mobility), Map.of());
        Spectrum centroid = centroidWithPeakArray(s);
        List<Spectrum> spectra = List.of(withArray, centroid);
        Path out = tmp.resolve("out.mzpeak");
        try (Session session = MzPeakWriter.openArchive(out, s.fileMetadata, s.scanSettings,
                Set.of(ION_MOBILITY), Options.defaults())) {
            spectra.forEach(session::addSpectrum);
            session.finish();
        }
        assertThat(WriterSnapshotTest.snapshot(out, true)).isEqualTo(batchArchive(tmp, spectra, s));
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.getSpectrumAt(0).arrays().get(ION_MOBILITY)).isEqualTo(mobility);
            assertThat(reader.getSpectrumAt(1).peakArrays().get(ION_MOBILITY)).containsExactly(0.25, 0.75);
        }
    }

    /**
     * A negative precursor_index is refused when the spectrum is added (it is stored as uint64), and nothing of the
     * spectrum is written; in the packed layout only the primary precursor's counts, as only it is written.
     */
    @ParameterizedTest
    @EnumSource(MetadataLayout.class)
    void aNegativePrecursorIndexIsRefusedAndNothingOfTheSpectrumIsWritten(MetadataLayout layout,
                                                                          @TempDir Path tmp) {
        Source s = source(SMALL);
        Spectrum ms2 = firstMs2(s);
        Precursor original = ms2.description().primaryPrecursor();
        Precursor negative = withIndex(original, -1L);
        Spectrum refused = renumbered(ms2, 1, List.of(negative));
        List<Spectrum> written = List.of(s.spectra.get(0), renumbered(ms2, 1, List.of(withIndex(original, 0L))));
        Options options = Options.defaults().withMetadataLayout(layout);
        Path out = tmp.resolve("out.mzpeak");
        try (Session session = MzPeakWriter.openArchive(out, s.fileMetadata, s.scanSettings, Set.of(), options)) {
            session.addSpectrum(written.get(0));
            assertThatThrownBy(() -> session.addSpectrum(refused))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("precursor_index must be non-negative (mzPeak indices are uint64): -1");
            if (layout == MetadataLayout.PACKED) {
                // A later precursor is not written in the packed layout, so its index is not checked.
                Spectrum later = renumbered(ms2, 1, List.of(withIndex(original, 0L), negative));
                assertThatThrownBy(() -> session.addSpectrum(renumbered(ms2, 1, List.of(negative,
                        withIndex(original, 0L))))).isInstanceOf(MzPeakException.class);
                session.addSpectrum(later);
                session.finish();
                try (MzPeakReader reader = MzPeakReader.open(out)) {
                    assertThat(reader.size()).isEqualTo(2);
                    assertThat(reader.getSpectrumAt(1).description().precursors()).hasSize(1)
                            .first().extracting(Precursor::precursorIndex).isEqualTo(0L);
                }
                return;
            }
            session.addSpectrum(written.get(1));
            session.finish();
        }
        assertThat(WriterSnapshotTest.snapshot(out, true))
                .isEqualTo(batchArchive(tmp, written, s, options));
    }

    /**
     * In the split layout, precursors of a spectrum that share their precursor_index are refused when a later one
     * carries selected ions, as by the batch overloads.
     */
    @Test
    void precursorsSharingAnIndexWithSelectedIonsAreRefusedInTheSplitLayout(@TempDir Path tmp) {
        Source s = source(SMALL);
        Spectrum ms2 = firstMs2(s);
        Precursor original = ms2.description().primaryPrecursor();
        assertThat(original.selectedIons()).isNotEmpty();
        Spectrum sharing = renumbered(ms2, 0, List.of(withIndex(original, null), withIndex(original, null)));
        Path out = tmp.resolve("out.mzpeak");
        try (Session session = MzPeakWriter.openArchive(out, s.fileMetadata, s.scanSettings, Set.of(),
                Options.defaults())) {
            assertThatThrownBy(() -> session.addSpectrum(sharing))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("spectrum 0: precursors 0 (")
                    .hasMessageContaining("share the written key (source_index 0, precursor_index null)");
        }
        assertThat(list(tmp)).isEmpty();
    }

    /** With Numpress PIC, an intensity above 2147483647 is refused (MZ-23) and nothing is left by close(). */
    @Test
    void aNumpressPicSessionRefusesAnIntensityItCannotEncode(@TempDir Path tmp) {
        Source s = source(SMALL);
        Spectrum profile = s.spectra.get(0);
        double[] intensity = profile.intensity().clone();
        intensity[intensity.length / 2] = 3.0e9;
        Spectrum loud = new Spectrum(profile.description(), profile.mz(), intensity, profile.peaks());
        Path out = tmp.resolve("out.mzpeak");
        Options pic = Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS)
                .withNumpressIntensity(NumpressIntensityEncoding.PIC);
        try (Session session = MzPeakWriter.openArchive(out, s.fileMetadata, s.scanSettings, Set.of(), pic)) {
            assertThatThrownBy(() -> session.addSpectrum(loud))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("spectrum 0: intensity 3.0E9 in spectra_data cannot be encoded with"
                            + " Numpress PIC");
            assertThat(list(tmp)).as("the temp directory while the session is open").hasSize(1);
        }
        assertThat(list(tmp)).isEmpty();
    }

    /**
     * A re-sorted main axis is logged as a WARNING when the session finishes, once; a spectrum that is refused after
     * its axis was re-sorted leaves no warning.
     */
    @Test
    void theWarningsOfTheSpectraWrittenAreLoggedAtTheFinish(@TempDir Path tmp) {
        Source s = source(SMALL);
        Spectrum profile = s.spectra.get(0);
        Spectrum reversed = new Spectrum(profile.description(), reversed(profile.mz()),
                reversed(profile.intensity()), profile.peaks());
        double[] negative = reversed(profile.intensity());
        negative[0] = -1;
        Spectrum refused = new Spectrum(profile.description(), reversed(profile.mz()), negative, profile.peaks());
        Path out = tmp.resolve("out.mzpeak");
        Options numpress = Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS);
        try (Session session = MzPeakWriter.openArchive(out, s.fileMetadata, s.scanSettings, Set.of(), numpress)) {
            assertThatThrownBy(() -> session.addSpectrum(refused)).isInstanceOf(MzPeakException.class)
                    .hasMessageContaining("cannot be encoded with Numpress SLOF");
            session.addSpectrum(reversed);
            assertThat(warnings(session::finish)).containsExactly(
                    "spectrum 0: main axis not ascending; re-sorted " + profile.mz().length + " rows");
        }
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.size()).isEqualTo(1);
        }
    }

    /** The packed layout's warning about what it does not write covers the spectra added. */
    @Test
    void thePackedLayoutWarnsAboutWhatItDoesNotWrite(@TempDir Path tmp) {
        Source s = source(SMALL);
        Spectrum ms2 = firstMs2(s);
        Precursor original = ms2.description().primaryPrecursor();
        List<Spectrum> spectra = List.of(s.spectra.get(0),
                renumbered(ms2, 1, List.of(withIndex(original, 0L), withIndex(original, 0L))));
        Options packed = Options.defaults().withMetadataLayout(MetadataLayout.PACKED);
        Path out = tmp.resolve("out.mzpeak");
        try (Session session = MzPeakWriter.openArchive(out, s.fileMetadata, s.scanSettings, Set.of(), packed)) {
            spectra.forEach(session::addSpectrum);
            List<String> warnings = warnings(session::finish);
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0)).startsWith("the packed metadata layout writes only the first scan")
                    .contains("1 later precursor and ");
        }
        assertThat(WriterSnapshotTest.snapshot(out, true)).isEqualTo(batchArchive(tmp, spectra, s, packed));
    }

    @Test
    void nothingCanBeAddedOrFinishedAfterTheFinish(@TempDir Path tmp) {
        Source s = source(SMALL);
        Path out = tmp.resolve("out");
        Session session = MzPeakWriter.openDirectory(out, s.fileMetadata, s.scanSettings, Set.of(),
                Options.defaults());
        session.addSpectrum(s.spectra.get(0));
        session.finish();
        String message = "the mzPeak session for " + out + " is finished";
        assertThatThrownBy(() -> session.addSpectrum(s.spectra.get(1)))
                .isInstanceOf(IllegalStateException.class).hasMessage(message);
        assertThatThrownBy(() -> session.addChromatogram(s.chromatograms.get(0)))
                .isInstanceOf(IllegalStateException.class).hasMessage(message);
        assertThatThrownBy(() -> session.addWavelengthSpectrum(source(Fixtures.legacy("has_uv.mzpeak"))
                .wavelengthSpectra.get(0))).isInstanceOf(IllegalStateException.class).hasMessage(message);
        assertThatThrownBy(session::finish).isInstanceOf(IllegalStateException.class).hasMessage(message);
        session.close();
        session.close();
        assertThatThrownBy(() -> session.addSpectrum(s.spectra.get(1)))
                .isInstanceOf(IllegalStateException.class).hasMessage(message);
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.size()).isEqualTo(1);
        }
    }

    @Test
    void nothingCanBeAddedOrFinishedAfterTheClose(@TempDir Path tmp) {
        Source s = source(SMALL);
        Path out = tmp.resolve("out.mzpeak");
        Session session = MzPeakWriter.openArchive(out, s.fileMetadata, s.scanSettings, Set.of(),
                Options.defaults());
        session.addSpectrum(s.spectra.get(0));
        session.close();
        String message = "the mzPeak session for " + out + " is closed";
        assertThatThrownBy(() -> session.addSpectrum(s.spectra.get(1)))
                .isInstanceOf(IllegalStateException.class).hasMessage(message);
        assertThatThrownBy(() -> session.addChromatogram(s.chromatograms.get(0)))
                .isInstanceOf(IllegalStateException.class).hasMessage(message);
        assertThatThrownBy(() -> session.addWavelengthSpectrum(source(Fixtures.legacy("has_uv.mzpeak"))
                .wavelengthSpectra.get(0))).isInstanceOf(IllegalStateException.class).hasMessage(message);
        assertThatThrownBy(session::finish).isInstanceOf(IllegalStateException.class).hasMessage(message);
        session.close();
        assertThat(list(tmp)).isEmpty();
    }

    @Test
    void nullArgumentsAreRefused(@TempDir Path tmp) {
        Path out = tmp.resolve("out.mzpeak");
        Set<String> withNull = new LinkedHashSet<>();
        withNull.add(null);
        assertThatThrownBy(() -> MzPeakWriter.openArchive(out, FileMetadata.EMPTY, List.of(), withNull,
                Options.defaults())).isInstanceOf(NullPointerException.class).hasMessage("spectrumArrays key");
        assertThatThrownBy(() -> MzPeakWriter.openArchive(out, FileMetadata.EMPTY, List.of(), Set.of(), null))
                .isInstanceOf(NullPointerException.class).hasMessage("options");
        assertThat(list(tmp)).isEmpty();
        try (Session session = MzPeakWriter.openArchive(out, FileMetadata.EMPTY, List.of(), Set.of(),
                Options.defaults())) {
            assertThatThrownBy(() -> session.addSpectrum(null)).isInstanceOf(NullPointerException.class)
                    .hasMessage("spectrum");
            assertThatThrownBy(() -> session.addChromatogram(null)).isInstanceOf(NullPointerException.class)
                    .hasMessage("chromatogram");
        }
        assertThat(list(tmp)).isEmpty();
    }

    // ---- strict targets ----------------------------------------------------------------------------------------

    @Test
    void openDirectoryRefusesADirectoryHoldingAFileAndLeavesItUnchanged(@TempDir Path tmp) throws IOException {
        Path target = Files.createDirectory(tmp.resolve("target"));
        Files.writeString(target.resolve("note.txt"), "keep", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> MzPeakWriter.openDirectory(target, FileMetadata.EMPTY, List.of(), Set.of(),
                Options.defaults()))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("mzPeak session target " + target + " exists and is not an empty directory;"
                        + " openDirectory writes only into a new or empty directory");
        assertThat(list(target)).containsExactly(target.resolve("note.txt"));
        assertThat(target.resolve("note.txt")).hasContent("keep");
        assertThat(list(tmp)).containsExactly(target);
    }

    @Test
    void openDirectoryRefusesAFile(@TempDir Path tmp) throws IOException {
        Path target = Files.writeString(tmp.resolve("target"), "keep", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> MzPeakWriter.openDirectory(target, FileMetadata.EMPTY, List.of(), Set.of(),
                Options.defaults()))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("exists and is not an empty directory");
        assertThat(target).hasContent("keep");
    }

    @Test
    void openArchiveRefusesAnExistingFileAndLeavesItUnchanged(@TempDir Path tmp) throws IOException {
        Path target = tmp.resolve("existing.mzpeak");
        byte[] content = {1, 2, 3, 4, 5};
        Files.write(target, content);
        assertThatThrownBy(() -> MzPeakWriter.openArchive(target, FileMetadata.EMPTY, List.of(), Set.of(),
                Options.defaults()))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("mzPeak session target " + target + " already exists; openArchive writes only a new"
                        + " archive");
        assertThat(target).hasBinaryContent(content);
        assertThat(list(tmp)).containsExactly(target);
    }

    /** An archive that appears at the target while the session runs is not replaced: the finish fails instead. */
    @Test
    void anArchiveThatAppearsMeanwhileIsNotReplaced(@TempDir Path tmp) throws IOException {
        Source s = source(SMALL);
        Path target = tmp.resolve("out.mzpeak");
        try (Session session = MzPeakWriter.openArchive(target, s.fileMetadata, s.scanSettings, Set.of(),
                Options.defaults())) {
            session.addSpectrum(s.spectra.get(0));
            Files.writeString(target, "other", StandardCharsets.UTF_8);
            assertThatThrownBy(session::finish)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("Failed to write mzPeak archive " + target)
                    .hasCauseInstanceOf(FileAlreadyExistsException.class);
        }
        assertThat(target).hasContent("other");
        assertThat(list(tmp)).containsExactly(target);
    }

    /** A target directory that cannot be listed is refused. Root lists it all the same, and the session opens. */
    @Test
    void aDirectoryThatCannotBeListedIsRefused(@TempDir Path tmp) throws IOException {
        Path target = Files.createDirectory(tmp.resolve("target"));
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("-wx------"));
        try {
            if (Files.isReadable(target)) {
                MzPeakWriter.openDirectory(target, FileMetadata.EMPTY, List.of(), Set.of(), Options.defaults())
                        .close();
            } else {
                assertThatThrownBy(() -> MzPeakWriter.openDirectory(target, FileMetadata.EMPTY, List.of(), Set.of(),
                        Options.defaults()))
                        .isInstanceOf(MzPeakException.class)
                        .hasMessage("Cannot list mzPeak session target " + target)
                        .hasCauseInstanceOf(IOException.class);
            }
        } finally {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"));
        }
        assertThat(list(target)).isEmpty();
    }

    @Test
    void aDirectoryThatCannotBeCreatedFailsTheOpenAndLeavesNothing(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("file"), "keep", StandardCharsets.UTF_8);
        Path target = file.resolve("out");
        assertThatThrownBy(() -> MzPeakWriter.openDirectory(target, FileMetadata.EMPTY, List.of(), Set.of(),
                Options.defaults()))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("Failed to write mzPeak dataset to " + target);
        Path archive = tmp.resolve("missing").resolve("out.mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.openArchive(archive, FileMetadata.EMPTY, List.of(), Set.of(),
                Options.defaults()))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("Failed to write mzPeak archive " + archive)
                .hasCauseInstanceOf(NoSuchFileException.class);
        assertThat(list(tmp)).containsExactly(file);
        assertThat(file).hasContent("keep");
    }

    // ---- what a failed finish and a close without a finish leave -----------------------------------------------

    /** A precursor_index that names no spectrum of the dataset fails the finish, which then leaves nothing. */
    @Test
    void aPrecursorIndexNamingNoSpectrumFailsTheFinishAndLeavesNoArchive(@TempDir Path tmp) {
        Source s = source(SMALL);
        Spectrum ms2 = firstMs2(s);
        Precursor original = ms2.description().primaryPrecursor();
        Path out = tmp.resolve("out.mzpeak");
        try (Session session = MzPeakWriter.openArchive(out, s.fileMetadata, s.scanSettings, Set.of(),
                Options.defaults())) {
            session.addSpectrum(s.spectra.get(0));
            session.addSpectrum(renumbered(ms2, 1, List.of(withIndex(original, 2L))));
            assertThatThrownBy(session::finish)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("spectrum 1: precursor_index 2 names no spectrum of the 2 written; a precursor_index"
                            + " is the index of a spectrum of the dataset");
            assertThatThrownBy(() -> session.addSpectrum(s.spectra.get(2))).isInstanceOf(IllegalStateException.class)
                    .hasMessageEndingWith(" is closed");
        }
        assertThat(list(tmp)).isEmpty();
    }

    /**
     * A parent may come after the spectrum that names it, and a precursor without a precursor_index is written as
     * such; in the packed layout only the primary precursor's index must name a spectrum.
     */
    @ParameterizedTest
    @EnumSource(MetadataLayout.class)
    void aParentMayComeLaterAndOnlyWrittenPrecursorsAreChecked(MetadataLayout layout, @TempDir Path tmp) {
        Source s = source(SMALL);
        Spectrum ms2 = firstMs2(s);
        Precursor original = ms2.description().primaryPrecursor();
        Options options = Options.defaults().withMetadataLayout(layout);
        List<Precursor> precursors = layout == MetadataLayout.SPLIT
                ? List.of(withIndex(original, 1L), withoutIons(original))
                : List.of(withIndex(original, 1L), withIndex(original, 7L));
        List<Spectrum> spectra = List.of(renumbered(ms2, 0, precursors), renumbered(s.spectra.get(0), 1, List.of()));
        Path out = tmp.resolve("out.mzpeak");
        try (Session session = MzPeakWriter.openArchive(out, s.fileMetadata, s.scanSettings, Set.of(), options)) {
            spectra.forEach(session::addSpectrum);
            session.finish();
        }
        assertThat(WriterSnapshotTest.snapshot(out, true)).isEqualTo(batchArchive(tmp, spectra, s, options));
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.getSpectrumAt(0).description().primaryPrecursor().precursorIndex()).isEqualTo(1L);
        }

        Path failed = tmp.resolve("failed.mzpeak");
        try (Session session = MzPeakWriter.openArchive(failed, s.fileMetadata, s.scanSettings, Set.of(),
                options)) {
            session.addSpectrum(renumbered(ms2, 0, List.of(withIndex(original, 1L))));
            assertThatThrownBy(session::finish).isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("spectrum 0: precursor_index 1 names no spectrum of the 1 written");
        }
        assertThat(failed).doesNotExist();
    }

    /** A chromatogram that breaks the contract fails the finish, which then removes what the session wrote. */
    @Test
    void aDuplicateChromatogramIndexFailsTheFinishAndRemovesTheDirectory(@TempDir Path tmp) {
        Source s = source(SMALL);
        Path out = tmp.resolve("out");
        try (Session session = MzPeakWriter.openDirectory(out, s.fileMetadata, s.scanSettings, Set.of(),
                Options.defaults())) {
            session.addSpectrum(s.spectra.get(0));
            session.addChromatogram(s.chromatograms.get(0));
            session.addChromatogram(s.chromatograms.get(0));
            assertThat(out.resolve(DATA)).isRegularFile();
            assertThatThrownBy(session::finish)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("duplicate chromatogram index: " + s.chromatograms.get(0).index()
                            + " (mzPeak indices are unique)");
        }
        assertThat(list(tmp)).isEmpty();
    }

    /**
     * A member that cannot be written, here because a directory holding a file is in its place, fails the finish,
     * which then deletes the members the session wrote and keeps what it did not write, and with it the directory.
     */
    @Test
    void aMemberThatCannotBeWrittenFailsTheFinishWhichDeletesTheMembersWritten(@TempDir Path tmp)
            throws IOException {
        Source s = source(SMALL);
        Path out = tmp.resolve("out");
        Path obstacle = out.resolve("spectra_metadata.parquet");
        try (Session session = MzPeakWriter.openDirectory(out, s.fileMetadata, s.scanSettings, Set.of(),
                Options.defaults())) {
            for (Spectrum spectrum : s.spectra) {
                session.addSpectrum(spectrum);
            }
            Files.createDirectory(obstacle);
            Files.writeString(obstacle.resolve("note.txt"), "keep", StandardCharsets.UTF_8);
            assertThatThrownBy(session::finish)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("Failed to write mzPeak dataset to " + out)
                    .hasCauseInstanceOf(IOException.class);
        }
        assertThat(list(out)).containsExactly(obstacle);
        assertThat(list(obstacle)).containsExactly(obstacle.resolve("note.txt"));
    }

    @Test
    void closeWithoutFinishLeavesNoArchiveAndNoTempFile(@TempDir Path tmp) {
        Source s = source(SMALL);
        Path out = tmp.resolve("out.mzpeak");
        try (Session session = MzPeakWriter.openArchive(out, s.fileMetadata, s.scanSettings, arrayKeys(s.spectra),
                Options.defaults())) {
            for (Spectrum spectrum : s.spectra) {
                session.addSpectrum(spectrum);
            }
            assertThat(list(tmp)).as("the temp directory while the session is open").hasSize(1)
                    .allMatch(Files::isDirectory);
        }
        assertThat(list(tmp)).isEmpty();
    }

    @Test
    void closeWithoutFinishRemovesTheDirectoryTheSessionCreated(@TempDir Path tmp) {
        Source s = source(SMALL);
        Path out = tmp.resolve("parent").resolve("out");
        try (Session session = MzPeakWriter.openDirectory(out, s.fileMetadata, s.scanSettings, Set.of(),
                Options.defaults())) {
            for (Spectrum spectrum : s.spectra) {
                session.addSpectrum(spectrum);
            }
            assertThat(list(out)).extracting(p -> p.getFileName().toString()).containsExactlyInAnyOrder(DATA, PEAKS);
        }
        assertThat(out).doesNotExist();
        assertThat(out.getParent()).as("the parent the session created").isEmptyDirectory();
    }

    @Test
    void closeWithoutFinishEmptiesAPreExistingDirectoryAndKeepsIt(@TempDir Path tmp) throws IOException {
        Source s = source(SMALL);
        Path out = Files.createDirectory(tmp.resolve("out"));
        try (Session session = MzPeakWriter.openDirectory(out, s.fileMetadata, s.scanSettings, Set.of(),
                Options.defaults())) {
            session.addSpectrum(s.spectra.get(0));
        }
        assertThat(out).isEmptyDirectory();
    }

    /** A file put into the directory the session created is not deleted, and keeps the directory. */
    @Test
    void closeWithoutFinishKeepsAnotherFileAndItsDirectory(@TempDir Path tmp) throws IOException {
        Source s = source(SMALL);
        Path out = tmp.resolve("out");
        try (Session session = MzPeakWriter.openDirectory(out, s.fileMetadata, s.scanSettings, Set.of(),
                Options.defaults())) {
            session.addSpectrum(s.spectra.get(0));
            Files.writeString(out.resolve("note.txt"), "keep", StandardCharsets.UTF_8);
        }
        assertThat(list(out)).containsExactly(out.resolve("note.txt"));
    }

    /** A session into an existing empty directory leaves the dataset there when it finishes. */
    @Test
    void aSessionWritesIntoAnEmptyDirectory(@TempDir Path tmp) throws IOException {
        Source s = source(SMALL);
        Path out = Files.createDirectory(tmp.resolve("out"));
        writeSession(out, false, s, Options.defaults());
        assertThat(WriterSnapshotTest.snapshot(out, false)).isEqualTo(batchDirectory(tmp, s));
    }

    // ---- the batch overloads' replace mode ---------------------------------------------------------------------

    /**
     * A batch archive write that fails leaves an existing archive untouched and no temp file, and a batch directory
     * write that fails leaves the directory as it is.
     */
    @Test
    void aFailedBatchWriteLeavesAnExistingTargetAsItIs(@TempDir Path tmp) throws IOException {
        Source s = source(SMALL);
        Path archive = tmp.resolve("existing.mzpeak");
        Files.writeString(archive, "old", StandardCharsets.UTF_8);
        WritePlan plan = WritePlanner.plan(s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, Options.defaults());
        try (Session session = Session.replacing(archive, true, plan)) {
            session.append(plan.spectrumSignals().get(0));
        }
        assertThat(archive).hasContent("old");
        assertThat(list(tmp)).containsExactly(archive);

        Path directory = Files.createDirectory(tmp.resolve("directory"));
        Files.writeString(directory.resolve("note.txt"), "keep", StandardCharsets.UTF_8);
        try (Session session = Session.replacing(directory, false, plan)) {
            session.append(plan.spectrumSignals().get(0));
        }
        assertThat(list(directory)).extracting(p -> p.getFileName().toString())
                .containsExactlyInAnyOrder("note.txt", DATA);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static Source source(Path path) {
        return SOURCES.computeIfAbsent(path, ForwardBackwardCorpusTest::readSource);
    }

    /** The options of the batch overload of a {@link WriterSnapshotTest} case. */
    private static Options options(Overload overload) {
        Options split = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
        Options packed = Options.defaults().withMetadataLayout(MetadataLayout.PACKED);
        return switch (overload) {
            case WRITE_DIRECTORY, WRITE_ARCHIVE, WRITE_DIRECTORY_SPLIT, WRITE_ARCHIVE_SPLIT -> split;
            case WRITE_DIRECTORY_NUMPRESS_SLOF, WRITE_DIRECTORY_NUMPRESS_SPLIT, WRITE_ARCHIVE_NUMPRESS_SLOF_SPLIT ->
                    split.withSignalEncoding(SignalEncoding.NUMPRESS);
            case WRITE_DIRECTORY_NUMPRESS_PIC -> split.withSignalEncoding(SignalEncoding.NUMPRESS)
                    .withNumpressIntensity(NumpressIntensityEncoding.PIC);
            case WRITE_DIRECTORY_PACKED, WRITE_ARCHIVE_PACKED -> packed;
            case WRITE_DIRECTORY_NUMPRESS_SLOF_PACKED -> packed.withSignalEncoding(SignalEncoding.NUMPRESS);
            case WRITE_DIRECTORY_NUMPRESS_PIC_PACKED -> packed.withSignalEncoding(SignalEncoding.NUMPRESS)
                    .withNumpressIntensity(NumpressIntensityEncoding.PIC);
        };
    }

    /**
     * Writes {@code s} through a session: its spectra in order, each of which must carry its position as index, then
     * its chromatograms and wavelength spectra; the spectrum arrays are the keys of its spectra in first-seen order.
     */
    private static void writeSession(Path out, boolean archive, Source s, Options options) {
        for (int i = 0; i < s.spectra.size(); i++) {
            assertThat(s.spectra.get(i).index()).as("index of spectrum %d", i).isEqualTo(i);
        }
        Set<String> keys = arrayKeys(s.spectra);
        try (Session session = archive
                ? MzPeakWriter.openArchive(out, s.fileMetadata, s.scanSettings, keys, options)
                : MzPeakWriter.openDirectory(out, s.fileMetadata, s.scanSettings, keys, options)) {
            for (Spectrum spectrum : s.spectra) {
                session.addSpectrum(spectrum);
            }
            s.chromatograms.forEach(session::addChromatogram);
            s.wavelengthSpectra.forEach(session::addWavelengthSpectrum);
            session.finish();
        }
    }

    /** The keys of the spectra's arrays() and peakArrays(), in first-seen order, as the batch planner meets them. */
    private static Set<String> arrayKeys(List<Spectrum> spectra) {
        Set<String> keys = new LinkedHashSet<>();
        for (Spectrum s : spectra) {
            keys.addAll(s.arrays().keySet());
            keys.addAll(s.peakArrays().keySet());
        }
        return keys;
    }

    /** The snapshot of {@code spectra} with the metadata of {@code s}, written by the batch writeArchive. */
    private static WriterSnapshotTest.Snapshot batchArchive(Path tmp, List<Spectrum> spectra, Source s) {
        return batchArchive(tmp, spectra, s, Options.defaults());
    }

    private static WriterSnapshotTest.Snapshot batchArchive(Path tmp, List<Spectrum> spectra, Source s,
                                                            Options options) {
        Path batch = tmp.resolve("batch-" + System.nanoTime() + ".mzpeak");
        MzPeakWriter.writeArchive(batch, spectra, List.of(), List.of(), s.scanSettings, s.fileMetadata, options);
        return WriterSnapshotTest.snapshot(batch, true);
    }

    /** The snapshot of all of {@code s} written by the batch writeDirectory. */
    private static WriterSnapshotTest.Snapshot batchDirectory(Path tmp, Source s) {
        Path batch = tmp.resolve("batch-" + System.nanoTime());
        MzPeakWriter.writeDirectory(batch, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, Options.defaults());
        return WriterSnapshotTest.snapshot(batch, false);
    }

    private static Spectrum firstMs2(Source s) {
        return s.spectra.stream().filter(sp -> sp.description().msLevel() == 2
                && !sp.description().precursors().isEmpty()).findFirst().orElseThrow();
    }

    /** A centroid spectrum at index 1 with two peaks and the peak array {@link #ION_MOBILITY} [0.25, 0.75]. */
    private static Spectrum centroidWithPeakArray(Source s) {
        Spectrum ms2 = firstMs2(s);
        SpectrumDescription d = renumbered(ms2, 1, List.of()).description();
        return new Spectrum(new SpectrumDescription(d.index(), d.id(), 1, d.retentionTime(), d.polarity(),
                SignalContinuity.CENTROID, 0, 0, d.scans(), List.of(), d.parameters()), new double[0], new double[0],
                List.of(new CentroidPeak(100, 10, 0), new CentroidPeak(200, 20, 1)), Map.of(),
                Map.of(ION_MOBILITY, new double[] {0.25, 0.75}));
    }

    /** {@code s} as spectrum {@code index} with {@code precursors}. */
    private static Spectrum renumbered(Spectrum s, long index, List<Precursor> precursors) {
        SpectrumDescription d = s.description();
        return new Spectrum(new SpectrumDescription(index, d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(), precursors,
                d.parameters()), s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
    }

    private static Precursor withIndex(Precursor p, Long index) {
        return new Precursor(index, p.precursorId(), p.isolationWindow(), p.selectedIons(), p.activation());
    }

    /** {@code p} without selected ions and without a precursor_index. */
    private static Precursor withoutIons(Precursor p) {
        return new Precursor(null, p.precursorId(), p.isolationWindow(), List.of(), p.activation());
    }

    private static double[] reversed(double[] values) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[values.length - 1 - i];
        }
        return out;
    }

    /** The messages {@link MzPeakWriter} logs while {@code action} runs. */
    private static List<String> warnings(Runnable action) {
        return ArrayIndexTest.captureLog(MzPeakWriter.class, action).stream().map(LogRecord::getMessage).toList();
    }

    private static Map<String, String> footer(Path member) {
        return ParquetFooter.read(new LocalInputFile(member)).keyValue();
    }

    private static List<Path> list(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.collect(Collectors.toCollection(ArrayList::new));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
