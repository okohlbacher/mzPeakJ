package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@link WritePlanner} decides for the inputs the other writer tests leave out: the warnings for the extra
 * arrays of several chromatograms, while a spectrum's extra peak arrays are written without one (MZ-1), the packed
 * layout's count of dropped selected ions when the primary precursor has none, the message for precursors that share
 * the written key without naming parents, and scan settings that are {@code null} in the split layout.
 *
 * <p>Oracle: legacy small.unpacked, changed where a test says so.
 */
class WritePlannerTest {

    private static final Path SMALL = Fixtures.legacy("small.unpacked.mzpeak");
    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
    private static final Options PACKED = Options.defaults().withMetadataLayout(MetadataLayout.PACKED);

    /**
     * One spectrum with an extra array in {@code peakArrays()} only, and two chromatograms with an extra array and an
     * intensity unit: one warning for each kind of chromatogram drop, in the plural, each naming every key and unit
     * once, and none for the spectrum (MZ-1). The dataset reads back with the spectrum's peak array, its values at
     * the peaks' positions, and without the chromatograms' arrays.
     */
    @Test
    void extraArraysOfSeveralChromatogramsGetOneWarningEachWhileASpectrumKeepsItsPeakArray(@TempDir Path tmp) {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        Spectrum centroid = spectra.get(2);
        assertThat(centroid.peaks()).isNotEmpty();
        double[] peakWidth = new double[centroid.peaks().size()];
        for (int i = 0; i < peakWidth.length; i++) {
            peakWidth[i] = 0.01 * (i + 1);
        }
        spectra.set(2, new Spectrum(centroid.description(), centroid.mz(), centroid.intensity(), centroid.peaks(),
                Map.of(), Map.of("peak width", peakWidth)));
        Chromatogram tic = small.chromatograms.get(0);
        assertThat(tic.arrays()).containsOnlyKeys("ms level");
        Chromatogram copy = new Chromatogram(1, "TIC copy", tic.typeCurie(), tic.time(), tic.intensity(),
                tic.intensityUnit(), tic.arrays());
        List<Chromatogram> chromatograms = List.of(tic, copy);

        List<String> warnings = WritePlanner.plan(spectra, chromatograms, List.of(), List.of(), small.fileMetadata,
                SPLIT).report().warnings();

        assertThat(warnings).containsExactly(
                "2 chromatograms carry extra arrays (ms level), which are not written; their time and intensity are"
                        + " written",
                "2 chromatograms declare an intensity unit (MS:1000131), which is not written");
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, chromatograms, List.of(), List.of(), small.fileMetadata, SPLIT);
        Source back = ForwardBackwardCorpusTest.readSource(out);
        Spectrum b = back.spectra.get(2);
        assertThat(b.peaks()).hasSameSizeAs(centroid.peaks());
        assertThat(b.peakArrays()).containsOnlyKeys("peak width");
        for (int i = 0; i < b.peaks().size(); i++) {
            assertThat(b.peakArrays().get("peak width")[b.peaks().get(i).index()]).as("peak %d", i)
                    .isEqualTo(peakWidth[centroid.peaks().get(i).index()]);
        }
        assertThat(back.chromatograms).extracting(Chromatogram::id).containsExactly("TIC", "TIC copy");
        assertThat(back.chromatograms).allSatisfy(c -> assertThat(c.arrays()).isEmpty());
    }

    /**
     * In the packed layout, a spectrum whose primary precursor has no selected ion and whose second precursor has
     * one: the warning counts that ion as not written along with the second precursor, and the spectrum reads back
     * with its primary precursor only.
     */
    @Test
    void thePackedLayoutCountsEveryIonAsDroppedWhenThePrimaryPrecursorHasNone(@TempDir Path tmp) {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        Precursor original = spectra.get(2).description().primaryPrecursor();
        Precursor withoutIons = new Precursor(original.precursorIndex(), original.precursorId(),
                original.isolationWindow(), List.of(), original.activation());
        spectra.set(2, withPrecursors(spectra.get(2), List.of(withoutIons, original)));

        List<String> warnings = WritePlanner.plan(spectra, small.chromatograms, List.of(), List.of(),
                small.fileMetadata, PACKED).report().warnings();

        assertThat(warnings).filteredOn(w -> w.startsWith("the packed metadata layout")).containsExactly(
                "the packed metadata layout writes only the first scan, the primary precursor and its first selected"
                        + " ion of a spectrum: 0 later scans, 1 later precursor and 1 other selected ion are not"
                        + " written");
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, small.chromatograms, List.of(), List.of(), small.fileMetadata,
                PACKED);
        assertThat(ForwardBackwardCorpusTest.readSource(out).spectra.get(2).description().precursors())
                .containsExactly(withoutIons);
    }

    /**
     * Three precursors of spectrum 2 without precursor_index share the written key, the middle one without selected
     * ions: the split layout refuses the write before any I/O, the message lists all three as sharing and only the
     * last as losing its ions, and since they name no parents, it asks for a precursor_index of each.
     */
    @Test
    void precursorsSharingAKeyWithoutNamingParentsAreAskedForAKeyOfTheirOwn(@TempDir Path tmp) {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        Precursor original = spectra.get(2).description().primaryPrecursor();
        SelectedIon ion = original.primaryIon();
        Precursor first = new Precursor(null, "first", original.isolationWindow(), List.of(ion),
                original.activation());
        Precursor middle = new Precursor(null, null, original.isolationWindow(), List.of(), original.activation());
        Precursor last = new Precursor(null, "last", original.isolationWindow(),
                List.of(new SelectedIon(ion.mz() + 1, null, null, List.of())), original.activation());
        spectra.set(2, withPrecursors(spectra.get(2), List.of(first, middle, last)));

        Path out = tmp.resolve("out.mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(out, spectra, small.chromatograms, List.of(), List.of(),
                small.fileMetadata, SPLIT))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("spectrum 2: precursors 0 (precursor_id 'first'), 1 (no precursor_id) and 2 (precursor_id"
                        + " 'last') share the written key (source_index 2, precursor_index null), so readers would"
                        + " attach the selected ions of precursor 2 to precursor 0; give each precursor of the"
                        + " spectrum a precursor_index of its own");
        assertThat(out).doesNotExist();
    }

    /**
     * Scan settings may be {@code null}: the split layout plans them as {@code null} without a warning, and the
     * dataset reads back without scan settings, as one written with none.
     */
    @Test
    void nullScanSettingsAreWrittenAsNoneInTheSplitLayout(@TempDir Path tmp) {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);

        WritePlan plan = WritePlanner.plan(small.spectra, small.chromatograms, List.of(), null, small.fileMetadata,
                SPLIT);

        assertThat(plan.scanSettings()).isNull();
        assertThat(plan.report().warnings()).noneMatch(w -> w.startsWith("scan settings"));
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, small.spectra, small.chromatograms, List.of(), null, small.fileMetadata,
                SPLIT);
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.scanSettings()).isEmpty();
            assertThat(reader.size()).isEqualTo(small.spectra.size());
        }
    }

    private static Spectrum withPrecursors(Spectrum s, List<Precursor> precursors) {
        SpectrumDescription d = s.description();
        return new Spectrum(new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(), precursors,
                d.parameters()), s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
    }
}
