package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.Session;
import org.mzpeak.model.Activation;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.FileMetadata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W7 (D13-18): the split layout replicates the MSn selected-ion chain (spec spectra.md, spectra_metadata_precursors:
 * "When higher MSn is done, the writer MUST replicate the chain of selected ions from previous MS levels using the
 * same source_index but each previous MS level's precursor_index"). A spectrum whose precursor names a written parent
 * with precursors of its own is written with the parent's precursors appended after its own, recursively, unless it
 * already lists a precursor with that precursor_index.
 *
 * <p>The spectra are small.unpacked's first three with descriptions rewritten: MS1 index 0; MS2 index 1 from 0
 * selecting 500.0; MS3 index 2 from 1 selecting 300.0.
 */
class MsnPrecursorChainTest {

    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
    private static final Options PACKED = Options.defaults().withMetadataLayout(MetadataLayout.PACKED);

    private static final Precursor FROM_MS1 = new Precursor(0L, "scan=1", new IsolationWindow(500.0, 1.0, 1.5),
            List.of(new SelectedIon(500.0, 2, 1000.0, List.of())),
            new Activation(List.of(new Param("beam-type collision-induced dissociation", "MS:1000422", null, null),
                    new Param("collision energy", "MS:1000045", 28.0, "UO:0000266"))));
    private static final Precursor FROM_MS2 = new Precursor(1L, "scan=2", new IsolationWindow(300.0, 0.5, 0.5),
            List.of(new SelectedIon(300.0, 1, 200.0, List.of())),
            new Activation(List.of(new Param("collision-induced dissociation", "MS:1000133", null, null),
                    new Param("collision energy", "MS:1000045", 35.0, "UO:0000266"))));
    private static final Precursor FROM_MS3 = new Precursor(2L, "scan=3", new IsolationWindow(150.0, 0.5, 0.5),
            List.of(new SelectedIon(150.0, 1, null, List.of())), Activation.EMPTY);

    @TempDir
    Path tmp;

    /** Spectrum 2 is written with (1, 300.0) then its parent's (0, 500.0), with the parent's window and activation. */
    @Test
    void anMs3SpectrumCarriesTheSelectedIonOfItsMs2Parent() {
        List<Spectrum> spectra = chain();
        Path out = tmp.resolve("chain.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, List.of(), List.of(), null, FileMetadata.EMPTY, SPLIT);

        assertThat(precursors(out, 2)).containsExactly(FROM_MS2, FROM_MS1);
        assertThat(precursors(out, 1)).containsExactly(FROM_MS1);
        assertThat(precursors(out, 0)).isEmpty();
        // The caller's spectra are not modified.
        assertThat(spectra.get(2).description().precursors()).containsExactly(FROM_MS2);
    }

    /** An MS4 spectrum gets the whole chain, in descending MS level: (2, 150.0), (1, 300.0), (0, 500.0). */
    @Test
    void theChainIsReplicatedRecursively() {
        List<Spectrum> spectra = new ArrayList<>(chain());
        spectra.add(derived(source().get(0), 3, 4, List.of(FROM_MS3)));
        Path out = tmp.resolve("ms4.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, List.of(), List.of(), null, FileMetadata.EMPTY, SPLIT);

        assertThat(precursors(out, 3)).containsExactly(FROM_MS3, FROM_MS2, FROM_MS1);
        assertThat(precursors(out, 2)).containsExactly(FROM_MS2, FROM_MS1);
    }

    /** A spectrum that already lists a precursor with each precursor_index of the chain is written unchanged. */
    @Test
    void aSpectrumAlreadyListingTheChainIsWrittenUnchanged() {
        List<Spectrum> spectra = new ArrayList<>(chain());
        Precursor ownOfMs1 = new Precursor(0L, "scan=1", null,
                List.of(new SelectedIon(501.0, 3, null, List.of())), Activation.EMPTY);
        spectra.set(2, derived(source().get(2), 2, 3, List.of(FROM_MS2, ownOfMs1)));
        Path out = tmp.resolve("listed.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, List.of(), List.of(), null, FileMetadata.EMPTY, SPLIT);

        assertThat(precursors(out, 2)).containsExactly(FROM_MS2, ownOfMs1);
    }

    /** Without its MS2 parent the MS3 spectrum has no chain: its precursor_index is null and nothing is appended. */
    @Test
    void aParentThatIsNotWrittenGivesNoChain() {
        List<Spectrum> spectra = chain();
        Path out = tmp.resolve("orphan.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(spectra.get(0), spectra.get(2)), List.of(), List.of(), null,
                FileMetadata.EMPTY, SPLIT);

        assertThat(precursors(out, 1)).containsExactly(withIndex(FROM_MS2, null));
    }

    /**
     * Without the MS1 grandparent the MS2 parent's precursor is written with precursor_index null, and so is its copy
     * in the MS3 spectrum's chain; the MS2 and MS3 spectra are renumbered 0 and 1.
     */
    @Test
    void aGrandparentThatIsNotWrittenIsChainedWithANullPrecursorIndex() {
        List<Spectrum> spectra = chain();
        Path out = tmp.resolve("grandparent.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(spectra.get(1), spectra.get(2)), List.of(), List.of(), null,
                FileMetadata.EMPTY, SPLIT);

        assertThat(precursors(out, 0)).containsExactly(withIndex(FROM_MS1, null));
        assertThat(precursors(out, 1)).containsExactly(withIndex(FROM_MS2, 0L), withIndex(FROM_MS1, null));
    }

    /** A session replicates the chain as the batch write does, a parent added after its child included. */
    @Test
    void aSessionReplicatesTheChain() {
        List<Spectrum> spectra = new ArrayList<>(chain());
        spectra.add(derived(source().get(0), 3, 4, List.of(FROM_MS3)));
        Path out = tmp.resolve("session.mzpeak");
        try (Session session = MzPeakWriter.openDirectory(out, FileMetadata.EMPTY, List.of(), Set.of(), SPLIT)) {
            for (Spectrum s : spectra) {
                session.addSpectrum(s);
            }
            session.finish();
        }
        assertThat(precursors(out, 3)).containsExactly(FROM_MS3, FROM_MS2, FROM_MS1);
        assertThat(precursors(out, 2)).containsExactly(FROM_MS2, FROM_MS1);
        assertThat(precursors(out, 1)).containsExactly(FROM_MS1);

        // Spectrum 0 names spectrum 1, added after it, which names spectrum 2.
        List<Spectrum> source = source();
        Path forward = tmp.resolve("forward.mzpeak");
        try (Session session = MzPeakWriter.openDirectory(forward, FileMetadata.EMPTY, List.of(), Set.of(), SPLIT)) {
            session.addSpectrum(derived(source.get(0), 0, 3, List.of(FROM_MS2)));
            session.addSpectrum(derived(source.get(1), 1, 2, List.of(withIndex(FROM_MS1, 2L))));
            session.addSpectrum(derived(source.get(2), 2, 1, List.of()));
            session.finish();
        }
        assertThat(precursors(forward, 0)).containsExactly(FROM_MS2, withIndex(FROM_MS1, 2L));
    }

    /** The packed layout writes the primary precursor only, which is the spectrum's own. */
    @Test
    void thePackedLayoutWritesTheOwnPrimaryPrecursor() {
        Path out = tmp.resolve("packed.mzpeak");
        MzPeakWriter.writeDirectory(out, chain(), List.of(), List.of(), null, FileMetadata.EMPTY, PACKED);

        assertThat(precursors(out, 2)).extracting(Precursor::precursorIndex).containsExactly(1L);
        assertThat(precursors(out, 2).get(0).primaryIon().mz()).isEqualTo(300.0);
    }

    /** MS1 index 0; MS2 index 1 from 0 selecting 500.0; MS3 index 2 from 1 selecting 300.0. */
    private static List<Spectrum> chain() {
        List<Spectrum> source = source();
        return List.of(derived(source.get(0), 0, 1, List.of()),
                derived(source.get(1), 1, 2, List.of(FROM_MS1)),
                derived(source.get(2), 2, 3, List.of(FROM_MS2)));
    }

    /** small.unpacked's first three spectra. */
    private static List<Spectrum> source() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.unpacked.mzpeak"))) {
            return List.of(reader.getSpectrumAt(0), reader.getSpectrumAt(1), reader.getSpectrumAt(2));
        }
    }

    /** {@code s} with {@code index}, {@code msLevel} and {@code precursors}, its signal and other metadata kept. */
    private static Spectrum derived(Spectrum s, long index, int msLevel, List<Precursor> precursors) {
        SpectrumDescription d = s.description();
        SpectrumDescription changed = new SpectrumDescription(index, "scan=" + (index + 1), msLevel,
                d.retentionTime(), d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(),
                d.scans(), precursors, d.parameters());
        return new Spectrum(changed, s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
    }

    private static Precursor withIndex(Precursor p, Long precursorIndex) {
        return new Precursor(precursorIndex, p.precursorId(), p.isolationWindow(), p.selectedIons(), p.activation());
    }

    private static List<Precursor> precursors(Path out, int index) {
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            return reader.getSpectrumAt(index).description().precursors();
        }
    }
}
