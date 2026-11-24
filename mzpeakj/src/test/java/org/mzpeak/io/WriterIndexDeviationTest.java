package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.Session;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression pins of design deviation D13-10 (W12): the writer keeps renumbering the spectra it writes 0..n-1 in index
 * order instead of rejecting gaps, and writes {@code precursor_index} null, keeping {@code precursor_id}, for a parent
 * it does not write instead of rejecting the reference (spectra.md §Spectrum precursor metadata: "If file does not
 * contain the precursor spectrum, the {@code precursor_index} MUST be {@code null}"). Rejecting either would break
 * writing a subset of what the reader returns, such as the MS2-only subset of small. Duplicate indices are rejected,
 * and so is a session precursor_index that names no spectrum of the session. If the maintainer decides to reject gaps
 * or unwritten parents, this class is what changes.
 *
 * <p>Oracle: current small, 48 spectra numbered 0..47, 34 of them MS2 with one precursor whose precursor_index names
 * a written MS1 spectrum.
 */
class WriterIndexDeviationTest {

    private static final Path SMALL = Fixtures.current("small.mzpeak");

    /** Spectra 0 and 2 of small, a gap at 1, are written as 0 and 1 with their ids. */
    @ParameterizedTest
    @EnumSource(MetadataLayout.class)
    void aGapInTheInputIndicesIsWrittenAsContiguousIndices(MetadataLayout layout, @TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> gap = List.of(s.spectra.get(0), s.spectra.get(2));
        assertThat(gap).extracting(Spectrum::index).containsExactly(0L, 2L);

        Source back = writeAndRead(tmp.resolve("gap.mzpeak"), gap, s, layout);

        assertThat(back.spectra).extracting(Spectrum::index).containsExactly(0L, 1L);
        assertThat(back.spectra).extracting(sp -> sp.description().id())
                .containsExactly(gap.get(0).description().id(), gap.get(1).description().id());
    }

    /** A precursor_index 77, naming no written spectrum of small's 48, is written as null with its precursor_id. */
    @ParameterizedTest
    @EnumSource(MetadataLayout.class)
    void aPrecursorIndexNamingNoWrittenSpectrumIsWrittenAsNullWithItsIdKept(MetadataLayout layout,
                                                                            @TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> spectra = new ArrayList<>(s.spectra);
        int msn = firstMs2(spectra);
        Precursor original = spectra.get(msn).description().primaryPrecursor();
        assertThat(original.precursorId()).as("precursor_id of spectrum %d", msn).isNotNull();
        spectra.set(msn, withSpectrum(spectra.get(msn), msn, List.of(withPrecursorIndex(original, 77L))));

        Source back = writeAndRead(tmp.resolve("p77.mzpeak"), spectra, s, layout);

        assertThat(back.spectra).hasSize(48);
        assertThat(back.spectra.get(msn).description().precursors()).singleElement().satisfies(p -> {
            assertThat(p.precursorIndex()).as("precursor_index").isNull();
            assertThat(p.precursorId()).as("precursor_id").isEqualTo(original.precursorId());
        });
        assertThat(spectra.get(msn).description().primaryPrecursor().precursorIndex())
                .as("caller's precursor_index").isEqualTo(77L);
    }

    /** A duplicate index is not renumbered away: it is rejected before anything is written. */
    @Test
    void aDuplicateSpectrumIndexIsRejected(@TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> spectra = List.of(s.spectra.get(0), withSpectrum(s.spectra.get(1), 0, List.of()));
        Path out = tmp.resolve("duplicate.mzpeak");

        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(out, spectra, List.of(), List.of(), s.scanSettings,
                s.fileMetadata, Options.defaults()))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("duplicate spectrum index: 0 (mzPeak indices are unique)");
        assertThat(out).doesNotExist();
    }

    /**
     * The 34 MS2 spectra of small without their MS1 parents are written as 0..33, each precursor_index null and each
     * precursor_id kept.
     */
    @ParameterizedTest
    @EnumSource(MetadataLayout.class)
    void theMs2OnlySubsetOfSmallWrites34Spectra(MetadataLayout layout, @TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> ms2 = s.spectra.stream().filter(sp -> sp.description().msLevel() == 2).toList();
        assertThat(ms2).hasSize(34);

        Source back = writeAndRead(tmp.resolve("ms2.mzpeak"), ms2, s, layout);

        assertThat(back.spectra).extracting(Spectrum::index)
                .isEqualTo(LongStream.range(0, 34).boxed().toList());
        for (int i = 0; i < ms2.size(); i++) {
            Precursor source = ms2.get(i).description().primaryPrecursor();
            assertThat(source.precursorIndex()).as("input precursor_index of spectrum %d", i).isNotNull();
            assertThat(back.spectra.get(i).description().precursors()).as("precursors of spectrum %d", i)
                    .singleElement().satisfies(p -> {
                        assertThat(p.precursorIndex()).isNull();
                        assertThat(p.precursorId()).isEqualTo(source.precursorId());
                    });
        }
    }

    /**
     * Unlike a batch write, a session rejects a precursor_index that names no spectrum of the session, at the finish
     * ({@code WritePlanner.requireWrittenParents}), which leaves nothing behind.
     */
    @Test
    void aSessionPrecursorNamingNoSessionSpectrumIsRejected(@TempDir Path tmp) throws Exception {
        Source s = ForwardBackwardCorpusTest.readSource(SMALL);
        int msn = firstMs2(s.spectra);
        Precursor original = s.spectra.get(msn).description().primaryPrecursor();
        Path out = tmp.resolve("session.mzpeak");
        try (Session session = MzPeakWriter.openDirectory(out, s.fileMetadata, s.scanSettings, Set.of(),
                Options.defaults())) {
            session.addSpectrum(s.spectra.get(0));
            session.addSpectrum(withSpectrum(s.spectra.get(msn), 1, List.of(withPrecursorIndex(original, 77L))));
            assertThatThrownBy(session::finish)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("spectrum 1: precursor_index 77 names no spectrum of the 2 written; a"
                            + " precursor_index is the index of a spectrum of the dataset");
        }
        try (var entries = Files.list(tmp)) {
            assertThat(entries).isEmpty();
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static Source writeAndRead(Path out, List<Spectrum> spectra, Source s, MetadataLayout layout) {
        MzPeakWriter.writeDirectory(out, spectra, List.of(), List.of(), s.scanSettings, s.fileMetadata,
                Options.defaults().withMetadataLayout(layout));
        return ForwardBackwardCorpusTest.readSource(out);
    }

    /** The position of the first MS2 spectrum with a precursor. */
    private static int firstMs2(List<Spectrum> spectra) {
        for (int i = 0; i < spectra.size(); i++) {
            Spectrum sp = spectra.get(i);
            if (sp.description().msLevel() == 2 && !sp.description().precursors().isEmpty()) {
                return i;
            }
        }
        throw new AssertionError("small has an MS2 spectrum");
    }

    /** {@code s} as spectrum {@code index} with {@code precursors}; everything else is kept. */
    private static Spectrum withSpectrum(Spectrum s, long index, List<Precursor> precursors) {
        SpectrumDescription d = s.description();
        return new Spectrum(new SpectrumDescription(index, d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(), precursors,
                d.parameters(), d.dataProcessingRef(), d.products()), s.mz(), s.intensity(), s.peaks(), s.arrays(),
                s.peakArrays(), s.intensityUnit(), s.peakIntensityUnit());
    }

    private static Precursor withPrecursorIndex(Precursor p, Long precursorIndex) {
        return new Precursor(precursorIndex, p.precursorId(), p.isolationWindow(), p.selectedIons(), p.activation(),
                p.msLevel());
    }
}
