package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Layout;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.WriterSnapshotTest.FixtureSet;
import org.mzpeak.model.Activation;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Writer safety net for the default build: every vendored HUPO-PSI fixture is read fully, written as a single-file
 * archive, read back and compared with its source (design §11.9, WR-7).
 *
 * <p>The cases are both fixture sets ({@link Fixtures#LEGACY} and {@link Fixtures#CURRENT}) in the point and in the
 * Numpress layout, written in the split metadata layout by the 6-argument {@link MzPeakWriter#writeArchive} and
 * {@link MzPeakWriter#writeArchiveNumpress} with their default options, plus the legacy set in both layouts written
 * in the packed metadata layout with options that name it. The metadata layout of the written spectrum metadata is
 * checked against the member's schema, so the split cases fail unless the default overloads write the split layout.
 *
 * <p>Each re-read is compared with its source three ways:
 * <ul>
 *   <li>the forward-backward field diff of {@link ForwardBackwardCorpusTest#compareRoundTrip}, run on the vendored
 *       fixtures instead of the external corpus. It is NaN-aware, so a value the writer drops (re-read as NaN) is a
 *       diff; the corpus harness tolerates diffs tagged {@code KNOWN_LIMIT}, but every diff fails here;
 *   <li>{@link DescriptionAssert} over every spectrum description, scans, precursors and selected ions included;
 *   <li>the peak lists. The point layout stores peaks losslessly, so every re-read peak list must equal the source.
 *       Numpress is lossy, so every peak and every intensity value is compared within a tolerance: peak m/z within
 *       1e-4 absolute, peak and array intensities within 1e-3 relative to {@code max(|x|, 1)}. SLOF quantizes
 *       {@code ln(x + 1)} per chunk, so its error stays near {@code 0.5 * ln(max + 1) / 65535} relative to
 *       {@code x + 1}; the fixtures stay about 7x below the tolerance.
 * </ul>
 *
 * <p>Extra signal arrays ({@link Spectrum#arrays()}, {@link Spectrum#peakArrays()}, such as diaPASEF's ion mobility)
 * are written (MZ-1): every re-read spectrum has the extra arrays of its source, value for value in both layouts,
 * since Numpress leaves them uncompressed, and the write's report carries no warning about them. The extra arrays of
 * chromatograms ({@link Chromatogram#arrays()}, the TIC's {@code ms level}) and their intensity unit
 * ({@link Chromatogram#intensityUnit()}) are not written yet (WR-11): every re-read chromatogram has none, and the
 * report carries one warning each, in both metadata layouts. The packed layout warns once about the scans, precursors
 * and selected ions it drops.
 */
class FixtureRoundTripTest {

    /** Numpress linear m/z tolerance for peaks, the comparator's tolerance for the m/z arrays. */
    private static final double NUMPRESS_PEAK_MZ_ABS_TOL = 1e-4;
    /** Numpress SLOF intensity tolerance, relative to {@code max(|x|, 1)} of the source value. */
    private static final double NUMPRESS_INTENSITY_REL_TOL = 1e-3;
    /** The fragment of the report's former warning about extra signal arrays, which MZ-1 removed. */
    private static final String EXTRAS_WARNING = "extra signal arrays";
    /** The fragment that identifies the report's warning about the extra arrays of chromatograms. */
    private static final String CHROMATOGRAM_ARRAYS_WARNING = "; their time and intensity are written";
    /** The fragment that identifies the report's warning about the intensity unit of chromatograms. */
    private static final String CHROMATOGRAM_UNIT_WARNING = " an intensity unit (";
    /** The fragment that identifies the report's warning about the facets the packed layout drops. */
    private static final String PACKED_WARNING = "the packed metadata layout";

    /** Fixtures read once per class, keyed by set and name; the tests never modify a source in place. */
    private static final Map<String, Source> SOURCES = new ConcurrentHashMap<>();

    static Stream<Arguments> cases() {
        List<Arguments> cases = new ArrayList<>();
        for (String fixture : Fixtures.LEGACY_NAMES) {
            for (Layout layout : Layout.values()) {
                cases.add(Arguments.of(FixtureSet.LEGACY, fixture, layout, MetadataLayout.SPLIT));
            }
        }
        for (String fixture : Fixtures.CURRENT_NAMES) {
            for (Layout layout : Layout.values()) {
                cases.add(Arguments.of(FixtureSet.CURRENT, fixture, layout, MetadataLayout.SPLIT));
            }
        }
        for (String fixture : Fixtures.LEGACY_NAMES) {
            for (Layout layout : Layout.values()) {
                cases.add(Arguments.of(FixtureSet.LEGACY, fixture, layout, MetadataLayout.PACKED));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1} as {2} {3}")
    @MethodSource("cases")
    void writeThenReadMatchesSource(FixtureSet set, String fixture, Layout layout, MetadataLayout metadata,
                                    @TempDir Path tmp) {
        Path src = set.path(fixture);
        Source source = source(set, fixture);
        String what = set.label + " " + fixture + " as " + layout + " " + metadata;
        assertThat(source.spectra).as("spectra read from %s", what).isNotEmpty();

        // Same rule as the corpus harness: the point layout requires finite m/z. No fixture triggers it.
        boolean sourceHasNonFiniteMz = source.spectra.stream()
                .anyMatch(s -> { for (double v : s.mz()) if (!Double.isFinite(v)) return true; return false; });
        assumeThat(layout == Layout.POINT && sourceHasNonFiniteMz)
                .as("point layout cannot store non-finite m/z")
                .isFalse();

        Path out = tmp.resolve("output-" + layout.name().toLowerCase(Locale.ROOT) + ".mzpeak");
        write(out, source, layout, metadata);

        List<String> diffs = new ArrayList<>();
        ForwardBackwardCorpusTest.compareRoundTrip(src, source, out, layout, diffs);
        assertThat(diffs).as("fidelity diffs for %s", what).isEmpty();

        Source back = ForwardBackwardCorpusTest.readSource(out);
        DescriptionAssert.assertEqual(what, descriptions(source), descriptions(back));
        for (int i = 0; i < source.spectra.size(); i++) {
            Spectrum a = source.spectra.get(i);
            Spectrum b = back.spectra.get(i);
            if (layout == Layout.POINT) {
                assertThat(b.peaks()).as("%s spectrum %d peaks", what, a.index()).isEqualTo(a.peaks());
            } else {
                assertNumpressValuesClose(what, a, b);
            }
        }

        assertExtrasKeptWithoutWarning(what, source, back, options(layout, metadata));
        assertChromatogramExtrasDroppedWithOneWarningEach(what, fixture, source, back, options(layout, metadata));
        assertThat(spectrumMetadataShape(out)).as("spectrum metadata layout of %s", what)
                .isEqualTo(metadata == MetadataLayout.SPLIT ? MetadataShape.SPLIT : MetadataShape.PACKED);
    }

    static Stream<Arguments> chromatogramCases() {
        List<Arguments> cases = new ArrayList<>();
        for (MetadataLayout metadata : MetadataLayout.values()) {
            for (String fixture : Fixtures.LEGACY_NAMES) {
                cases.add(Arguments.of(FixtureSet.LEGACY, fixture, metadata));
            }
            for (String fixture : Fixtures.CURRENT_NAMES) {
                cases.add(Arguments.of(FixtureSet.CURRENT, fixture, metadata));
            }
        }
        return cases.stream();
    }

    /**
     * Every fixture in both metadata layouts, the current set in the packed layout too: the chromatograms read back
     * with their time and intensity, without extra arrays and without an intensity unit, and the report carries one
     * warning for the extra arrays and one for the intensity units when the source has any.
     */
    @ParameterizedTest(name = "{0} {1} as {2}")
    @MethodSource("chromatogramCases")
    void chromatogramArraysAndIntensityUnitAreDroppedWithOneWarningEach(FixtureSet set, String fixture,
                                                                       MetadataLayout metadata, @TempDir Path tmp) {
        Source source = source(set, fixture);
        String what = set.label + " " + fixture + " as " + metadata;
        Options options = options(Layout.POINT, metadata);
        Path out = tmp.resolve("output.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, options);

        Source back = ForwardBackwardCorpusTest.readSource(out);
        assertThat(back.chromatograms).as("%s chromatograms", what).hasSameSizeAs(source.chromatograms);
        for (int i = 0; i < source.chromatograms.size(); i++) {
            Chromatogram a = source.chromatograms.get(i);
            Chromatogram b = back.chromatograms.get(i);
            LayoutEquivalenceTest.assertIdentical(what + " chromatogram " + i + " time", a.time(), b.time());
            LayoutEquivalenceTest.assertIdentical(what + " chromatogram " + i + " intensity", a.intensity(),
                    b.intensity());
        }
        assertChromatogramExtrasDroppedWithOneWarningEach(what, fixture, source, back, options);
    }

    /**
     * A spectrum with three scans and two precursors, the primary one with two selected ions and the other with one:
     * the packed layout writes its first scan and the primary precursor with its first selected ion, and the report
     * says once what it drops. The split layout writes all of it and warns about none; the unchanged fixture warns in
     * neither layout.
     */
    @Test
    void thePackedLayoutWarnsOnceAboutTheScansPrecursorsAndSelectedIonsItDrops(@TempDir Path tmp) {
        Source small = source(FixtureSet.CURRENT, "small.mzpeak");
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        Spectrum msn = spectra.get(9);
        SpectrumDescription d = msn.description();
        Precursor primary = d.primaryPrecursor();
        assertThat(d.scans()).as("scans of spectrum 9").hasSize(1);
        assertThat(primary.precursorIndex()).as("parent of spectrum 9").isEqualTo(8L);
        assertThat(primary.selectedIons()).as("selected ions of spectrum 9").hasSize(1);
        SelectedIon extra = new SelectedIon(401.5, 2, 1500.0, List.of());
        ScanEvent first = d.scans().get(0);
        ScanEvent later = new ScanEvent(first.startTime() + 0.001, null, null, null, null, null, List.of(),
                List.of());
        List<Precursor> precursors = List.of(
                new Precursor(primary.precursorIndex(), primary.precursorId(), primary.isolationWindow(),
                        List.of(primary.selectedIons().get(0), extra), primary.activation()),
                new Precursor(1L, spectra.get(1).description().id(), null, List.of(extra), Activation.EMPTY));
        spectra.set(9, new Spectrum(new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(),
                d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(),
                List.of(first, later, later), precursors, d.parameters()), msn.mz(), msn.intensity(), msn.peaks(),
                msn.arrays(), msn.peakArrays()));

        Options packed = Options.defaults().withMetadataLayout(MetadataLayout.PACKED);
        assertThat(packedWarnings(spectra, small, packed)).containsExactly("the packed metadata layout writes only"
                + " the first scan, the primary precursor and its first selected ion of a spectrum: 2 later scans,"
                + " 1 later precursor and 2 other selected ions are not written");
        assertThat(packedWarnings(spectra, small, Options.defaults())).isEmpty();
        assertThat(packedWarnings(small.spectra, small, packed)).isEmpty();
        assertThat(packedWarnings(small.spectra, small, Options.defaults())).isEmpty();

        Path out = tmp.resolve("packed.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, List.of(), List.of(), List.of(), small.fileMetadata, packed);
        SpectrumDescription back = ForwardBackwardCorpusTest.readSource(out).spectra.get(9).description();
        assertThat(back.scans()).as("scans written in the packed layout").hasSize(1);
        assertThat(back.precursors()).as("precursors written in the packed layout").singleElement()
                .satisfies(p -> assertThat(p.selectedIons()).containsExactly(primary.selectedIons().get(0)));
    }

    /** The report's warnings about the packed layout for a write of {@code spectra} with {@code options}. */
    private static List<String> packedWarnings(List<Spectrum> spectra, Source s, Options options) {
        return WritePlanner.plan(spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings, s.fileMetadata,
                options).report().warnings().stream().filter(w -> w.contains(PACKED_WARNING)).toList();
    }

    /**
     * Writes {@code source} as an archive: the split layout through the 6-argument public overloads with their default
     * options, the packed layout through the options form naming it.
     */
    private static void write(Path out, Source s, Layout layout, MetadataLayout metadata) {
        if (metadata == MetadataLayout.PACKED) {
            MzPeakWriter.writeArchive(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                    s.fileMetadata, options(layout, metadata));
        } else if (layout == Layout.POINT) {
            MzPeakWriter.writeArchive(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                    s.fileMetadata);
        } else {
            MzPeakWriter.writeArchiveNumpress(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                    s.fileMetadata);
        }
    }

    /** The options {@link #write} writes with; the split cases use the defaults of the public overloads. */
    private static Options options(Layout layout, MetadataLayout metadata) {
        Options options = layout == Layout.POINT ? Options.defaults()
                : Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS);
        return metadata == MetadataLayout.PACKED ? options.withMetadataLayout(MetadataLayout.PACKED) : options;
    }

    /**
     * Every re-read spectrum has the extra signal arrays of its source under the same keys, in the same order and
     * with equal values ({@link Double#compare}), its peak arrays too, and the report of the write carries no warning
     * about extra signal arrays. diaPASEF.ref and diaPASEF.grid have an ion mobility array in every spectrum.
     */
    private static void assertExtrasKeptWithoutWarning(String what, Source source, Source back, Options options) {
        int withExtras = 0;
        for (int i = 0; i < source.spectra.size(); i++) {
            Spectrum a = source.spectra.get(i);
            Spectrum b = back.spectra.get(i);
            assertSameArrays(what + " spectrum " + i + " extra arrays", a.arrays(), b.arrays());
            assertSameArrays(what + " spectrum " + i + " extra peak arrays", a.peakArrays(), b.peakArrays());
            withExtras += a.arrays().isEmpty() && a.peakArrays().isEmpty() ? 0 : 1;
        }
        if (what.contains("diaPASEF")) {
            assertThat(withExtras).as("%s: spectra with an extra array", what).isEqualTo(source.spectra.size());
        }
        List<String> warnings = WritePlanner.plan(source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, options).report().warnings().stream()
                .filter(w -> w.contains(EXTRAS_WARNING)).toList();
        assertThat(warnings).as("%s: warnings about extra signal arrays", what).isEmpty();
    }

    /** {@code actual} has the keys of {@code expected} in its order, each with an equal array. */
    private static void assertSameArrays(String what, Map<String, double[]> expected, Map<String, double[]> actual) {
        assertThat(actual.keySet()).as("%s keys", what).containsExactlyElementsOf(expected.keySet());
        expected.forEach((key, values) -> assertThat(actual.get(key)).as("%s %s", what, key).isEqualTo(values));
    }

    /**
     * No re-read chromatogram has an extra array or an intensity unit, and the report of the write carries one
     * warning about the extra arrays, naming every key and the number of chromatograms that had one, and one about
     * the intensity units, naming every unit and the number of chromatograms that declared one, each only when the
     * source has any. The small* and has_uv fixtures get the exact warnings, diaPASEF none about chromatograms.
     */
    private static void assertChromatogramExtrasDroppedWithOneWarningEach(String what, String fixture, Source source,
                                                                          Source back, Options options) {
        for (Chromatogram b : back.chromatograms) {
            assertThat(b.arrays()).as("%s chromatogram %d extra arrays after the round trip", what, b.index())
                    .isEmpty();
            assertThat(b.intensityUnit()).as("%s chromatogram %d intensity unit after the round trip", what,
                    b.index()).isNull();
        }
        Set<String> keys = new LinkedHashSet<>();
        Set<String> units = new LinkedHashSet<>();
        int withArrays = 0;
        int withUnit = 0;
        for (Chromatogram c : source.chromatograms) {
            if (!c.arrays().isEmpty()) {
                withArrays++;
                keys.addAll(c.arrays().keySet());
            }
            if (c.intensityUnit() != null) {
                withUnit++;
                units.add(c.intensityUnit());
            }
        }
        List<String> warnings = WritePlanner.plan(source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, options).report().warnings();
        List<String> arrays = warnings.stream().filter(w -> w.contains(CHROMATOGRAM_ARRAYS_WARNING)).toList();
        List<String> unit = warnings.stream().filter(w -> w.contains(CHROMATOGRAM_UNIT_WARNING)).toList();
        if (withArrays == 0) {
            assertThat(arrays).as("%s: warnings about extra chromatogram arrays", what).isEmpty();
        } else {
            assertThat(arrays).as("%s: warnings about extra chromatogram arrays", what).singleElement().asString()
                    .startsWith(withArrays + (withArrays == 1 ? " chromatogram " : " chromatograms "))
                    .contains("(" + String.join(", ", keys) + ")");
        }
        if (withUnit == 0) {
            assertThat(unit).as("%s: warnings about chromatogram intensity units", what).isEmpty();
        } else {
            assertThat(unit).as("%s: warnings about chromatogram intensity units", what).singleElement().asString()
                    .startsWith(withUnit + (withUnit == 1 ? " chromatogram " : " chromatograms "))
                    .contains("(" + String.join(", ", units) + ")");
        }
        String arraysWarning = "1 chromatogram carries extra arrays (ms level), which are not written; their time"
                + " and intensity are written";
        if (fixture.startsWith("small")) {
            assertThat(arrays).as("%s", what).containsExactly(arraysWarning);
            assertThat(unit).as("%s", what).containsExactly(
                    "1 chromatogram declares an intensity unit (MS:1000131), which is not written");
        } else if (fixture.startsWith("has_uv")) {
            assertThat(arrays).as("%s", what).containsExactly(arraysWarning);
            assertThat(unit).as("%s", what).containsExactly(
                    "2 chromatograms declare an intensity unit (MS:1000131, UO:0000269), which is not written");
        } else if (fixture.startsWith("diaPASEF")) {
            assertThat(warnings).as("%s: warnings about chromatograms", what)
                    .noneMatch(w -> w.contains("chromatogram"));
        }
    }

    /**
     * Per-value check of a Numpress re-read against its source: the peak list peak by peak, and the intensity
     * array element by element (the comparator already checks the m/z array element by element). A NaN on
     * either side fails.
     */
    private static void assertNumpressValuesClose(String what, Spectrum a, Spectrum b) {
        List<CentroidPeak> pa = a.peaks();
        List<CentroidPeak> pb = b.peaks();
        assertThat(pb).as("%s spectrum %d peaks after Numpress round trip", what, a.index()).hasSameSizeAs(pa);
        for (int k = 0; k < pa.size(); k++) {
            CentroidPeak p = pa.get(k);
            CentroidPeak q = pb.get(k);
            if (!(Math.abs(q.mz() - p.mz()) <= NUMPRESS_PEAK_MZ_ABS_TOL)) {
                fail("%s spectrum %d peak %d m/z: src=%s out=%s (abs tol %s)",
                        what, a.index(), k, p.mz(), q.mz(), NUMPRESS_PEAK_MZ_ABS_TOL);
            }
            if (!intensityClose(p.intensity(), q.intensity())) {
                fail("%s spectrum %d peak %d intensity: src=%s out=%s (rel tol %s)",
                        what, a.index(), k, p.intensity(), q.intensity(), NUMPRESS_INTENSITY_REL_TOL);
            }
        }

        double[] intensity = a.intensity();
        double[] outIntensity = b.intensity();
        assertThat(outIntensity).as("%s spectrum %d intensity after Numpress round trip", what, a.index())
                .hasSameSizeAs(intensity);
        for (int k = 0; k < intensity.length; k++) {
            if (!intensityClose(intensity[k], outIntensity[k])) {
                fail("%s spectrum %d intensity[%d]: src=%s out=%s (rel tol %s)",
                        what, a.index(), k, intensity[k], outIntensity[k], NUMPRESS_INTENSITY_REL_TOL);
            }
        }
    }

    /** Within {@link #NUMPRESS_INTENSITY_REL_TOL} of {@code max(|expected|, 1)}; false if either side is NaN. */
    private static boolean intensityClose(double expected, double actual) {
        return Math.abs(actual - expected) <= NUMPRESS_INTENSITY_REL_TOL * Math.max(Math.abs(expected), 1.0);
    }

    private static Source source(FixtureSet set, String fixture) {
        return SOURCES.computeIfAbsent(set.label + "/" + fixture,
                k -> ForwardBackwardCorpusTest.readSource(set.path(fixture)));
    }

    private static List<SpectrumDescription> descriptions(Source s) {
        List<SpectrumDescription> out = new ArrayList<>(s.spectra.size());
        for (Spectrum spectrum : s.spectra) {
            out.add(spectrum.description());
        }
        return out;
    }

    /** The shape of the spectrum metadata member of a written dataset, found by role. */
    private static MetadataShape spectrumMetadataShape(Path out) {
        try (MzPeakSource dataset = MzPeakSource.open(out)) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(dataset);
            String member = manifest.find(EntityType.SPECTRUM, DataKind.METADATA).orElseThrow().name();
            return MetadataShape.detect(ParquetFooter.read(dataset.inputFile(member)), "spectrum", "index", member);
        }
    }
}
