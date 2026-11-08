package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Activation;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * WR-8: the indices the writer gives spectra, chromatograms and wavelength spectra, and the precursor_index it writes
 * for each precursor (design §11.5). Every index column of every Parquet member is read back through
 * {@link ParquetGroups}, and the dataset through {@link MzPeakReader}, in both metadata layouts and both signal
 * encodings.
 *
 * <p>The writer numbers each entity 0..n-1 in index order, as the spec requires (spectra.md:91-93,
 * chromatograms.md:40), and maps each precursor_index through the spectrum numbering, null when the parent spectrum is
 * not written. Input numbered 0..n-1 in index order is written as given. In the split layout it refuses, before any
 * I/O, a spectrum whose precursors share a written key {@code (source_index, precursor_index)} when a precursor after
 * the first of them carries selected ions: both readers would attach those ions to the first.
 *
 * <p>Oracles: current small (48 spectra, 34 MS2 spectra with one precursor and one selected ion each, whose
 * precursor_index names a written MS1 spectrum; one chromatogram) and current has_uv (212 spectra, 2 chromatograms,
 * 520 wavelength spectra), all numbered 0..n-1, subsets and shifted copies of them, and spectra constructed from
 * current small with two precursors.
 */
class WriterContiguousIndexTest {

    private static final Path SMALL = Fixtures.current("small.mzpeak");
    private static final Path HAS_UV = Fixtures.current("has_uv.mzpeak");

    /** The columns that hold an entity index: its own, or a reference to a spectrum's or to its own entity's. */
    private static final Set<String> INDEX_COLUMNS = Set.of("index", "source_index", "spectrum_index",
            "chromatogram_index", "wavelength_spectrum_index", "precursor_index");

    /** Fixtures read once per class; the tests never modify a source in place. */
    private static final Map<Path, Source> SOURCES = new ConcurrentHashMap<>();

    static Stream<Arguments> layouts() {
        List<Arguments> out = new ArrayList<>();
        for (MetadataLayout layout : MetadataLayout.values()) {
            for (SignalEncoding encoding : SignalEncoding.values()) {
                out.add(Arguments.of(layout, encoding));
            }
        }
        return out.stream();
    }

    // ---- input numbered 0..n-1 --------------------------------------------------------------------------------

    /** Input numbered 0..n-1 in index order, whose precursors name written spectra, is written as given. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("layouts")
    void contiguousInputIsWrittenWithTheCallersIndices(MetadataLayout layout, SignalEncoding encoding,
                                                       @TempDir Path tmp) {
        for (Path fixture : List.of(SMALL, HAS_UV)) {
            Source s = source(fixture);
            Path out = write(tmp.resolve(fixture.getFileName()), s.spectra, s.chromatograms, s.wavelengthSpectra, s,
                    options(layout, encoding));

            assertIndexColumnsNumberFromZero(out, s.spectra.size(), s.chromatograms.size(),
                    s.wavelengthSpectra.size());
            Source back = ForwardBackwardCorpusTest.readSource(out);
            assertThat(indices(back.spectra)).as("%s spectrum indices", fixture.getFileName())
                    .isEqualTo(indices(s.spectra));
            assertThat(precursorIndices(back.spectra)).as("%s precursor_index of every spectrum",
                    fixture.getFileName()).isEqualTo(precursorIndices(s.spectra));
            assertThat(back.chromatograms).extracting(Chromatogram::index, Chromatogram::id)
                    .as("%s chromatograms", fixture.getFileName())
                    .isEqualTo(s.chromatograms.stream().map(c -> tuple(c.index(), c.id())).toList());
            assertThat(back.wavelengthSpectra).extracting(WavelengthSpectrum::index, WavelengthSpectrum::id)
                    .as("%s wavelength spectra", fixture.getFileName())
                    .isEqualTo(s.wavelengthSpectra.stream().map(w -> tuple(w.index(), w.id())).toList());
        }
        assertThat(precursorIndices(source(SMALL).spectra)).as("small has precursors").anySatisfy(
                p -> assertThat(p).isNotEmpty().doesNotContainNull());
    }

    /**
     * A spectrum with two precursors that name two different written parents, each with its own isolation window,
     * activation and selected ion, reads back with every ion on its own precursor.
     */
    @Test
    void aTwoPrecursorSpectrumWrittenWithItsParentsKeepsEachIonOnItsOwnPrecursor(@TempDir Path tmp) {
        Source small = source(SMALL);
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        Spectrum msn = spectra.get(9);
        Precursor original = msn.description().primaryPrecursor();
        assertThat(original.precursorIndex()).as("parent of spectrum 9").isEqualTo(8L);
        Spectrum changed = withPrecursors(msn, List.of(original, secondPrecursor(spectra.get(1), true)));
        spectra.set(9, changed);

        Path out = write(tmp.resolve("all.mzpeak"), spectra, small.chromatograms, small.wavelengthSpectra, small,
                options(MetadataLayout.SPLIT, SignalEncoding.POINT));

        Source back = ForwardBackwardCorpusTest.readSource(out);
        DescriptionAssert.assertEqual("small with a two-precursor spectrum 9", descriptions(spectra),
                descriptions(back.spectra));
        List<Precursor> precursors = back.spectra.get(9).description().precursors();
        assertThat(precursors).extracting(Precursor::precursorIndex).containsExactly(8L, 1L);
        assertThat(precursors.get(0).selectedIons()).containsExactlyElementsOf(original.selectedIons());
        assertThat(precursors.get(1).selectedIons()).containsExactly(SECOND_ION);
    }

    // ---- subsets and sparse input -------------------------------------------------------------------------------

    /**
     * The 34 MS2 spectra of current small without their MS1 parents, as ExtractSpectra writes them: numbered 0..33,
     * every precursor_index null, ids, arrays and selected ions kept. The caller's spectra are not changed.
     */
    @ParameterizedTest
    @EnumSource(MetadataLayout.class)
    void onlyTheMs2SpectraAreNumberedFromZeroWithoutPrecursorIndex(MetadataLayout layout, @TempDir Path tmp) {
        Source small = source(SMALL);
        List<Spectrum> ms2 = small.spectra.stream().filter(s -> s.description().msLevel() == 2).toList();
        assertThat(ms2).hasSize(34);
        assertThat(ms2.get(0).index()).isEqualTo(2);

        Path out = write(tmp.resolve("ms2.mzpeak"), ms2, small.chromatograms, List.of(), small,
                options(layout, SignalEncoding.POINT));

        assertIndexColumnsNumberFromZero(out, 34, small.chromatograms.size(), 0);
        Source back = ForwardBackwardCorpusTest.readSource(out);
        assertThat(indices(back.spectra)).isEqualTo(LongStream.range(0, 34).boxed().toList());
        for (int i = 0; i < ms2.size(); i++) {
            Spectrum expected = ms2.get(i);
            Spectrum actual = back.spectra.get(i);
            String what = "spectrum " + i + " (index " + expected.index() + " in the input)";
            assertThat(actual.description().id()).as(what + " id").isEqualTo(expected.description().id());
            LayoutEquivalenceTest.assertIdentical(what + " m/z", expected.mz(), actual.mz());
            LayoutEquivalenceTest.assertIdentical(what + " intensity", expected.intensity(), actual.intensity());
            assertThat(actual.peaks()).as(what + " peaks").isEqualTo(expected.peaks());
            Precursor source = expected.description().primaryPrecursor();
            assertThat(actual.description().precursors()).as(what + " precursors").singleElement()
                    .satisfies(p -> {
                        assertThat(p.precursorIndex()).as(what + " precursor_index").isNull();
                        assertThat(p.precursorId()).as(what + " precursor_id").isEqualTo(source.precursorId());
                        assertThat(p.selectedIons()).as(what + " selected ions")
                                .isEqualTo(source.selectedIons());
                    });
        }
        DescriptionAssert.assertEqual("MS2 spectra of small", expectedDescriptions(ms2), descriptions(back.spectra));
        assertThat(ms2.get(0).description().primaryPrecursor().precursorIndex()).as("caller's precursor_index")
                .isEqualTo(1L);
    }

    /**
     * has_uv with every spectrum and wavelength spectrum index shifted by 1000 and its chromatograms numbered 9 and 5,
     * in that order: every member numbers each entity 0..n-1, and the chromatograms are written in index order.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("layouts")
    void shiftedAndSparseIndicesAreNumberedFromZeroInEveryMember(MetadataLayout layout, SignalEncoding encoding,
                                                                 @TempDir Path tmp) {
        Source uv = source(HAS_UV);
        List<Spectrum> spectra = uv.spectra.stream().map(s -> shifted(s, 1000)).toList();
        Chromatogram tic = uv.chromatograms.get(0);
        Chromatogram dad = uv.chromatograms.get(1);
        List<Chromatogram> chromatograms = List.of(withIndex(tic, 9), withIndex(dad, 5));
        List<WavelengthSpectrum> waves = uv.wavelengthSpectra.stream().map(w -> new WavelengthSpectrum(
                w.index() + 1000, w.id(), w.time(), w.lambdaMax(), w.wavelength(), w.intensity())).toList();

        Path out = write(tmp.resolve("shifted.mzpeak"), spectra, chromatograms, waves, uv, options(layout, encoding));

        assertIndexColumnsNumberFromZero(out, 212, 2, 520);
        Source back = ForwardBackwardCorpusTest.readSource(out);
        assertThat(back.spectra).extracting(Spectrum::index, s -> s.description().id())
                .isEqualTo(uv.spectra.stream().map(s -> tuple(s.index(), s.description().id())).toList());
        assertThat(back.chromatograms).extracting(Chromatogram::index, Chromatogram::id)
                .containsExactly(tuple(0L, dad.id()), tuple(1L, tic.id()));
        LayoutEquivalenceTest.assertIdentical("chromatogram 0 time", dad.time(), back.chromatograms.get(0).time());
        LayoutEquivalenceTest.assertIdentical("chromatogram 0 intensity", dad.intensity(),
                back.chromatograms.get(0).intensity());
        LayoutEquivalenceTest.assertIdentical("chromatogram 1 time", tic.time(), back.chromatograms.get(1).time());
        LayoutEquivalenceTest.assertIdentical("chromatogram 1 intensity", tic.intensity(),
                back.chromatograms.get(1).intensity());
        assertThat(back.wavelengthSpectra).extracting(WavelengthSpectrum::index, WavelengthSpectrum::id)
                .isEqualTo(uv.wavelengthSpectra.stream().map(w -> tuple(w.index(), w.id())).toList());
    }

    /**
     * small with every spectrum index and precursor_index shifted by 1000: every precursor keeps its parent, under
     * the parent's new index.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("layouts")
    void shiftedSpectraKeepTheirParents(MetadataLayout layout, SignalEncoding encoding, @TempDir Path tmp) {
        Source small = source(SMALL);
        List<Spectrum> spectra = small.spectra.stream().map(s -> shifted(s, 1000)).toList();
        assertThat(spectra.get(2).description().primaryPrecursor().precursorIndex()).isEqualTo(1001L);

        Path out = write(tmp.resolve("shifted.mzpeak"), spectra, List.of(withIndex(small.chromatograms.get(0), 9)),
                List.of(), small, options(layout, encoding));

        assertIndexColumnsNumberFromZero(out, 48, 1, 0);
        Source back = ForwardBackwardCorpusTest.readSource(out);
        assertThat(indices(back.spectra)).isEqualTo(indices(small.spectra));
        assertThat(precursorIndices(back.spectra)).isEqualTo(precursorIndices(small.spectra));
        assertThat(back.chromatograms).extracting(Chromatogram::index).containsExactly(0L);
    }

    // ---- several precursors -------------------------------------------------------------------------------------

    /**
     * Without its parents, both precursors of the constructed spectrum 9 get the key (source_index, null), and both
     * readers would attach the selected ion of the second to the first: SPLIT refuses the input before any I/O, as
     * a directory and as an archive. PACKED writes the primary precursor only and is not checked.
     */
    @Test
    void aTwoPrecursorSpectrumWithoutItsParentsIsRejectedBeforeAnyIoInSplit(@TempDir Path tmp) throws IOException {
        Source small = source(SMALL);
        List<Spectrum> ms2 = ms2WithTwoPrecursors(small, true);
        Precursor original = small.spectra.get(9).description().primaryPrecursor();
        String secondId = small.spectra.get(1).description().id();
        Options split = options(MetadataLayout.SPLIT, SignalEncoding.POINT);
        String message = "spectrum 9: precursors 0 (precursor_id '" + original.precursorId() + "') and 1"
                + " (precursor_id '" + secondId + "') share the written key (source_index 5, precursor_index null),"
                + " so readers would attach the selected ions of precursor 1 to precursor 0; write their parent"
                + " spectra (index 8 and 1) as well, so that each precursor keeps a precursor_index of its own";

        Path dir = tmp.resolve("split.mzpeak");
        assertThatThrownBy(() -> write(dir, ms2, small.chromatograms, List.of(), small, split))
                .isInstanceOf(MzPeakException.class).hasMessage(message);
        assertThat(dir).as("output directory after a rejected write").doesNotExist();

        Path parent = Files.createDirectories(tmp.resolve("archive"));
        Path archive = parent.resolve("split.mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.writeArchive(archive, ms2, small.chromatograms, List.of(),
                small.scanSettings, small.fileMetadata, split))
                .isInstanceOf(MzPeakException.class).hasMessage(message);
        try (Stream<Path> entries = Files.list(parent)) {
            assertThat(entries).as("entries next to %s after a rejected write", archive).isEmpty();
        }

        Path packed = write(tmp.resolve("packed.mzpeak"), ms2, small.chromatograms, List.of(), small,
                options(MetadataLayout.PACKED, SignalEncoding.POINT));
        Source back = ForwardBackwardCorpusTest.readSource(packed);
        assertThat(back.spectra).hasSize(34);
        assertThat(back.spectra.get(5).description().id()).isEqualTo(small.spectra.get(9).description().id());
        assertThat(back.spectra.get(5).description().precursors()).as("PACKED precursors of spectrum 9")
                .containsExactly(new Precursor(null, original.precursorId(), original.isolationWindow(),
                        original.selectedIons(), original.activation()));
    }

    /**
     * The check is on the written key, whatever made it shared: two precursors of spectrum 9 that name the same
     * written parent, both with a selected ion, are refused in SPLIT too, with a remedy that does not suggest writing
     * the parent. The packed layout writes the primary precursor only.
     */
    @Test
    void precursorsNamingOneWrittenParentAreRejectedInSplitWhenALaterOneCarriesIons(@TempDir Path tmp) {
        Source small = source(SMALL);
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        Precursor original = spectra.get(9).description().primaryPrecursor();
        Precursor sameParent = new Precursor(8L, null, null, List.of(SECOND_ION), Activation.EMPTY);
        spectra.set(9, withPrecursors(spectra.get(9), List.of(original, sameParent)));

        Path dir = tmp.resolve("split.mzpeak");
        assertThatThrownBy(() -> write(dir, spectra, List.of(), List.of(), small,
                options(MetadataLayout.SPLIT, SignalEncoding.POINT)))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("spectrum 9: precursors 0 (precursor_id '" + original.precursorId() + "') and 1 (no"
                        + " precursor_id) share the written key (source_index 9, precursor_index 8), so readers would"
                        + " attach the selected ions of precursor 1 to precursor 0; give each precursor of the"
                        + " spectrum a precursor_index of its own");
        assertThat(dir).as("output directory after a rejected write").doesNotExist();

        Path packed = write(tmp.resolve("packed.mzpeak"), spectra, List.of(), List.of(), small,
                options(MetadataLayout.PACKED, SignalEncoding.POINT));
        assertThat(ForwardBackwardCorpusTest.readSource(packed).spectra.get(9).description().precursors())
                .containsExactly(original);
    }

    /**
     * Spectra 1, 8 and the constructed spectrum 9, written as 0, 1 and 2: the precursors of spectrum 9 name their
     * parents under the new indices, and each keeps its own window, activation and selected ion.
     */
    @Test
    void aTwoPrecursorSpectrumWrittenWithItsParentsIsRemappedAndKeepsEachIonOnItsOwnPrecursor(@TempDir Path tmp) {
        Source small = source(SMALL);
        Spectrum msn = small.spectra.get(9);
        Precursor original = msn.description().primaryPrecursor();
        List<Spectrum> spectra = List.of(small.spectra.get(1), small.spectra.get(8),
                withPrecursors(msn, List.of(original, secondPrecursor(small.spectra.get(1), true))));

        Path out = write(tmp.resolve("parents.mzpeak"), spectra, List.of(), List.of(), small,
                options(MetadataLayout.SPLIT, SignalEncoding.POINT));

        Source back = ForwardBackwardCorpusTest.readSource(out);
        DescriptionAssert.assertEqual("spectra 1, 8 and 9", expectedDescriptions(spectra),
                descriptions(back.spectra));
        List<Precursor> precursors = back.spectra.get(2).description().precursors();
        assertThat(precursors).extracting(Precursor::precursorIndex).containsExactly(1L, 0L);
        assertThat(precursors.get(0).selectedIons()).containsExactlyElementsOf(original.selectedIons());
        assertThat(precursors.get(0).isolationWindow()).isEqualTo(original.isolationWindow());
        assertThat(precursors.get(1).selectedIons()).containsExactly(SECOND_ION);
        assertThat(precursors.get(1).isolationWindow()).isEqualTo(new IsolationWindow(401.75, 0.5, 0.75));
        assertThat(precursors.get(1).activation()).isEqualTo(secondPrecursor(msn, true).activation());
    }

    /**
     * When only the first precursor of the constructed spectrum 9 carries a selected ion, the shared key loses
     * nothing: SPLIT writes the MS2 spectra without their parents, and they read back equal.
     */
    @Test
    void aSharedKeyIsWrittenWhenOnlyTheFirstPrecursorCarriesIons(@TempDir Path tmp) {
        Source small = source(SMALL);
        List<Spectrum> ms2 = ms2WithTwoPrecursors(small, false);

        Path out = write(tmp.resolve("first-only.mzpeak"), ms2, List.of(), List.of(), small,
                options(MetadataLayout.SPLIT, SignalEncoding.POINT));

        Source back = ForwardBackwardCorpusTest.readSource(out);
        DescriptionAssert.assertEqual("MS2 spectra of small", expectedDescriptions(ms2), descriptions(back.spectra));
        List<Precursor> precursors = back.spectra.get(5).description().precursors();
        assertThat(precursors).extracting(Precursor::precursorIndex).containsExactly(null, null);
        assertThat(precursors.get(0).selectedIons()).hasSize(1);
        assertThat(precursors.get(1).selectedIons()).isEmpty();
        assertThat(precursors.get(1).isolationWindow()).isEqualTo(new IsolationWindow(401.75, 0.5, 0.75));
    }

    // ---- constructed input --------------------------------------------------------------------------------------

    /**
     * The 34 MS2 spectra of {@code small}, spectrum 9 with a second precursor isolated from spectrum 1
     * ({@link #secondPrecursor}), with a selected ion if {@code secondHasIon}.
     */
    private static List<Spectrum> ms2WithTwoPrecursors(Source small, boolean secondHasIon) {
        Spectrum msn = small.spectra.get(9);
        Precursor original = msn.description().primaryPrecursor();
        assertThat(original.selectedIons()).as("selected ions of spectrum 9").hasSize(1);
        List<Spectrum> out = new ArrayList<>();
        for (Spectrum s : small.spectra) {
            if (s == msn) {
                out.add(withPrecursors(s, List.of(original, secondPrecursor(small.spectra.get(1), secondHasIon))));
            } else if (s.description().msLevel() == 2) {
                out.add(s);
            }
        }
        assertThat(out).hasSize(34);
        assertThat(out.get(5).index()).isEqualTo(9);
        return out;
    }

    /** The selected ion of {@link #secondPrecursor}. */
    private static final SelectedIon SECOND_ION = new SelectedIon(401.5, 2, 1500.0, List.of());

    /**
     * A precursor isolated from {@code parent}, with an isolation window, an activation and, if {@code withIon}, the
     * selected ion {@link #SECOND_ION}; all of them differ from those of current small's precursors.
     */
    private static Precursor secondPrecursor(Spectrum parent, boolean withIon) {
        return new Precursor(parent.index(), parent.description().id(), new IsolationWindow(401.75, 0.5, 0.75),
                withIon ? List.of(SECOND_ION) : List.of(),
                new Activation(List.of(new Param("beam-type collision-induced dissociation", "MS:1000422", null,
                        null), new Param("collision energy", "MS:1000045", 28.0, "UO:0000266"))));
    }

    /** {@code s} with {@code precursors} in place of its own; everything else is kept. */
    private static Spectrum withPrecursors(Spectrum s, List<Precursor> precursors) {
        return withDescription(s, renumbered(s.description(), s.index(), precursors));
    }

    /** {@code s} with its index and the precursor_index of each precursor that has one increased by {@code by}. */
    private static Spectrum shifted(Spectrum s, long by) {
        List<Precursor> precursors = new ArrayList<>();
        for (Precursor p : s.description().precursors()) {
            precursors.add(withPrecursorIndex(p, p.precursorIndex() == null ? null : p.precursorIndex() + by));
        }
        return withDescription(s, renumbered(s.description(), s.index() + by, precursors));
    }

    private static Spectrum withDescription(Spectrum s, SpectrumDescription d) {
        return new Spectrum(d, s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
    }

    /** {@code d} with {@code index} and {@code precursors}; everything else is kept. */
    private static SpectrumDescription renumbered(SpectrumDescription d, long index, List<Precursor> precursors) {
        return new SpectrumDescription(index, d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(), precursors,
                d.parameters());
    }

    private static Precursor withPrecursorIndex(Precursor p, Long precursorIndex) {
        return new Precursor(precursorIndex, p.precursorId(), p.isolationWindow(), p.selectedIons(), p.activation());
    }

    private static Chromatogram withIndex(Chromatogram c, long index) {
        return new Chromatogram(index, c.id(), c.typeCurie(), c.time(), c.intensity(), c.intensityUnit(),
                c.arrays());
    }

    /**
     * The descriptions of {@code spectra}, given in index order, as written: the i-th numbered i, and each
     * precursor_index mapped to the new index of its parent, null when the parent is not among {@code spectra}.
     */
    private static List<SpectrumDescription> expectedDescriptions(List<Spectrum> spectra) {
        Map<Long, Long> written = new HashMap<>();
        for (int i = 0; i < spectra.size(); i++) {
            written.put(spectra.get(i).index(), (long) i);
        }
        List<SpectrumDescription> out = new ArrayList<>();
        for (int i = 0; i < spectra.size(); i++) {
            SpectrumDescription d = spectra.get(i).description();
            List<Precursor> precursors = new ArrayList<>();
            for (Precursor p : d.precursors()) {
                precursors.add(withPrecursorIndex(p, p.precursorIndex() == null ? null
                        : written.get(p.precursorIndex())));
            }
            out.add(renumbered(d, i, precursors));
        }
        return out;
    }

    // ---- assertions ---------------------------------------------------------------------------------------------

    /**
     * Asserts that in every Parquet member of {@code out} each {@code index} column holds 0..n-1 in row order, n the
     * number of entities of the member's entity type, and that each other index column ({@link #INDEX_COLUMNS})
     * holds only values below n, below the spectrum count for {@code precursor_index}. A member with rows must have
     * an index column.
     */
    private static void assertIndexColumnsNumberFromZero(Path out, int spectra, int chromatograms,
                                                         int wavelengthSpectra) {
        Map<EntityType, Integer> counts = Map.of(EntityType.SPECTRUM, spectra, EntityType.CHROMATOGRAM,
                chromatograms, EntityType.WAVELENGTH_SPECTRUM, wavelengthSpectra);
        int members = 0;
        try (MzPeakSource dataset = MzPeakSource.open(out)) {
            for (MzPeakManifest.Entry entry : MzPeakManifest.fromSource(dataset).files()) {
                if (!entry.name().endsWith(".parquet")) {
                    continue;
                }
                members++;
                int n = counts.get(entry.entity());
                Map<String, List<Long>> columns = new LinkedHashMap<>();
                int[] rows = {0};
                ParquetGroups.forEach(dataset.inputFile(entry.name()), row -> {
                    rows[0]++;
                    collectIndexColumns(row, "", columns);
                });
                if (rows[0] > 0) {
                    assertThat(columns).as("index columns of %s", entry.name()).isNotEmpty();
                }
                for (Map.Entry<String, List<Long>> column : columns.entrySet()) {
                    String path = column.getKey();
                    String leaf = path.substring(path.lastIndexOf('.') + 1);
                    String what = entry.name() + " " + path;
                    if (leaf.equals("index")) {
                        assertThat(column.getValue()).as(what)
                                .isEqualTo(LongStream.range(0, n).boxed().toList());
                    } else {
                        long bound = leaf.equals("precursor_index") ? spectra : n;
                        assertThat(column.getValue()).as(what).allSatisfy(v -> assertThat(v).isBetween(0L,
                                bound - 1));
                    }
                }
            }
        }
        assertThat(members).as("Parquet members of %s", out).isPositive();
    }

    /**
     * Adds the non-null values of the {@link #INDEX_COLUMNS} of {@code group} and its nested structs (not lists) to
     * {@code columns}, keyed by their dotted path.
     */
    private static void collectIndexColumns(Group group, String prefix, Map<String, List<Long>> columns) {
        GroupType type = group.getType();
        for (Type field : type.getFields()) {
            String name = field.getName();
            if (!ParquetGroups.has(group, name)) {
                continue;
            }
            if (field.isPrimitive()) {
                if (INDEX_COLUMNS.contains(name)) {
                    columns.computeIfAbsent(prefix + name, k -> new ArrayList<>())
                            .add(ParquetGroups.optLong(group, name));
                }
            } else if (field.getLogicalTypeAnnotation() == null) {
                collectIndexColumns(group.getGroup(name, 0), prefix + name + ".", columns);
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static Options options(MetadataLayout layout, SignalEncoding encoding) {
        return Options.defaults().withMetadataLayout(layout).withSignalEncoding(encoding);
    }

    private static Path write(Path out, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                              List<WavelengthSpectrum> wavelengthSpectra, Source s, Options options) {
        MzPeakWriter.writeDirectory(out, spectra, chromatograms, wavelengthSpectra, s.scanSettings, s.fileMetadata,
                options);
        return out;
    }

    private static Source source(Path fixture) {
        return SOURCES.computeIfAbsent(fixture, ForwardBackwardCorpusTest::readSource);
    }

    private static List<Long> indices(List<Spectrum> spectra) {
        return spectra.stream().map(Spectrum::index).toList();
    }

    /** The precursor_index of every precursor of every spectrum, a list per spectrum. */
    private static List<List<Long>> precursorIndices(List<Spectrum> spectra) {
        List<List<Long>> out = new ArrayList<>();
        for (Spectrum s : spectra) {
            List<Long> keys = new ArrayList<>();
            for (Precursor p : s.description().precursors()) {
                keys.add(p.precursorIndex());
            }
            out.add(keys);
        }
        return out;
    }

    private static List<SpectrumDescription> descriptions(List<Spectrum> spectra) {
        return spectra.stream().map(Spectrum::description).toList();
    }
}
