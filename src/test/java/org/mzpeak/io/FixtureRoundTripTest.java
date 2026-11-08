package org.mzpeak.io;

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
import org.mzpeak.model.CentroidPeak;
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
 * are not written yet (WR-11 writes them): every re-read spectrum has none, and the write's report carries exactly one
 * warning about them when the source has any, and none otherwise.
 */
class FixtureRoundTripTest {

    /** Numpress linear m/z tolerance for peaks, the comparator's tolerance for the m/z arrays. */
    private static final double NUMPRESS_PEAK_MZ_ABS_TOL = 1e-4;
    /** Numpress SLOF intensity tolerance, relative to {@code max(|x|, 1)} of the source value. */
    private static final double NUMPRESS_INTENSITY_REL_TOL = 1e-3;
    /** The fragment that identifies the report's warning about extra signal arrays. */
    private static final String EXTRAS_WARNING = "extra signal arrays";

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

        assertExtrasDroppedWithOneWarning(what, source, back, options(layout, metadata));
        assertThat(spectrumMetadataShape(out)).as("spectrum metadata layout of %s", what)
                .isEqualTo(metadata == MetadataLayout.SPLIT ? MetadataShape.SPLIT : MetadataShape.PACKED);
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
     * No re-read spectrum has an extra signal array, and the report of the write carries one warning about them, naming
     * every extra key of the source and the number of spectra that had one, if the source has any; otherwise none.
     */
    private static void assertExtrasDroppedWithOneWarning(String what, Source source, Source back, Options options) {
        for (Spectrum b : back.spectra) {
            assertThat(b.arrays()).as("%s spectrum %d extra arrays after the round trip", what, b.index()).isEmpty();
            assertThat(b.peakArrays()).as("%s spectrum %d extra peak arrays after the round trip", what, b.index())
                    .isEmpty();
        }
        Set<String> keys = new LinkedHashSet<>();
        int withExtras = 0;
        for (Spectrum s : source.spectra) {
            if (!s.arrays().isEmpty() || !s.peakArrays().isEmpty()) {
                withExtras++;
                keys.addAll(s.arrays().keySet());
                keys.addAll(s.peakArrays().keySet());
            }
        }
        List<String> warnings = WritePlanner.plan(source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, options).report().warnings().stream()
                .filter(w -> w.contains(EXTRAS_WARNING)).toList();
        if (withExtras == 0) {
            assertThat(warnings).as("%s: warnings about extra signal arrays", what).isEmpty();
            return;
        }
        String count = withExtras + (withExtras == 1 ? " spectrum " : " spectra ");
        assertThat(warnings).as("%s: warnings about extra signal arrays", what).singleElement().asString()
                .startsWith(count).contains(String.join(", ", keys));
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
