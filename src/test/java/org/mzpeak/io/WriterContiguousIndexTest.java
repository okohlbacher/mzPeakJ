package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * WR-8: the indices the writer gives spectra, chromatograms and wavelength spectra, and the precursor_index it writes
 * for each precursor (design §11.5). Every index column of every Parquet member is read back through
 * {@link ParquetGroups}, and the dataset through {@link MzPeakReader}, in both metadata layouts and both signal
 * encodings.
 *
 * <p>Oracles: current small (48 spectra, 34 MS2 spectra with one precursor and one selected ion each, whose
 * precursor_index names a written MS1 spectrum; one chromatogram) and current has_uv (212 spectra, 2 chromatograms,
 * 520 wavelength spectra), all numbered 0..n-1, and spectra constructed from current small with two precursors.
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

    // ---- constructed input --------------------------------------------------------------------------------------

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
        SpectrumDescription d = s.description();
        return new Spectrum(new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(), precursors,
                d.parameters()), s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
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
