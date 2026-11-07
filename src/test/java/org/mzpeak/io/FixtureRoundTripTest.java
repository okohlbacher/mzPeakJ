package org.mzpeak.io;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Layout;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Spectrum;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Writer safety net for the default build: the forward-backward field diff of {@link ForwardBackwardCorpusTest}
 * run on the vendored HUPO-PSI fixtures instead of the external corpus, without the validator.
 *
 * <p>Each fixture is read fully, written as a single-file archive in the point and in the Numpress layout with
 * the 6-argument {@link MzPeakWriter#writeArchive}/{@link MzPeakWriter#writeArchiveNumpress}, re-read, and
 * compared field by field with {@link ForwardBackwardCorpusTest#compareRoundTrip}. The comparison is NaN-aware,
 * so a value the writer drops (re-read as NaN) is a diff. The corpus harness tolerates diffs tagged
 * {@code KNOWN_LIMIT} (documented deferred limitations), but the vendored fixtures must produce none, so every
 * diff fails here.
 *
 * <p>That comparison checks peak lists only by count and Numpress intensities only by their sum. The point
 * layout stores peaks losslessly, so for it every re-read peak list must also equal the source exactly. Numpress
 * is lossy, so for it every peak and every intensity value is compared within a tolerance: peak m/z within 1e-4
 * absolute, peak and array intensities within 1e-3 relative to {@code max(|x|, 1)}. SLOF quantizes
 * {@code ln(x + 1)} per chunk, so its error stays near {@code 0.5 * ln(max + 1) / 65535} relative to
 * {@code x + 1}; the fixtures stay about 7x below the tolerance.
 */
class FixtureRoundTripTest {

    private static final String BASE = "src/test/resources/mzpeak/";

    /** Numpress linear m/z tolerance for peaks, the comparator's tolerance for the m/z arrays. */
    private static final double NUMPRESS_PEAK_MZ_ABS_TOL = 1e-4;
    /** Numpress SLOF intensity tolerance, relative to {@code max(|x|, 1)} of the source value. */
    private static final double NUMPRESS_INTENSITY_REL_TOL = 1e-3;

    private static final List<String> FIXTURES = List.of(
            "small.unpacked.mzpeak",
            "small.mzpeak",
            "small.chunked.mzpeak",
            "small.numpress.mzpeak",
            "has_uv.mzpeak");

    static Stream<Arguments> fixturesAndLayouts() {
        return FIXTURES.stream()
                .flatMap(f -> Stream.of(Layout.values()).map(layout -> Arguments.of(f, layout)));
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("fixturesAndLayouts")
    void writeThenReadMatchesSource(String fixture, Layout layout, @TempDir Path tmp) {
        Path src = Path.of(BASE + fixture);
        Source source = ForwardBackwardCorpusTest.readSource(src);
        assertThat(source.spectra).as("spectra read from %s", fixture).isNotEmpty();

        // Same rule as the corpus harness: the point layout requires finite m/z. No fixture triggers it.
        boolean sourceHasNonFiniteMz = source.spectra.stream()
                .anyMatch(s -> { for (double v : s.mz()) if (!Double.isFinite(v)) return true; return false; });
        assumeThat(layout == Layout.POINT && sourceHasNonFiniteMz)
                .as("point layout cannot store non-finite m/z")
                .isFalse();

        Path out = tmp.resolve("output-" + layout.name().toLowerCase(Locale.ROOT) + ".mzpeak");
        if (layout == Layout.POINT) {
            MzPeakWriter.writeArchive(out, source.spectra, source.chromatograms,
                    source.wavelengthSpectra, source.scanSettings, source.fileMetadata);
        } else {
            MzPeakWriter.writeArchiveNumpress(out, source.spectra, source.chromatograms,
                    source.wavelengthSpectra, source.scanSettings, source.fileMetadata);
        }

        List<String> diffs = new ArrayList<>();
        ForwardBackwardCorpusTest.compareRoundTrip(src, source, out, layout, diffs);
        assertThat(diffs).as("fidelity diffs for %s written as %s", fixture, layout).isEmpty();

        if (layout == Layout.POINT) {
            try (MzPeakReader r = MzPeakReader.open(out)) {
                for (Spectrum a : source.spectra) {
                    Spectrum b = r.getSpectrum(a.index()).orElseThrow();
                    assertThat(b.peaks())
                            .as("%s spectrum %d peaks after point-layout round trip", fixture, a.index())
                            .isEqualTo(a.peaks());
                }
            }
        } else {
            try (MzPeakReader r = MzPeakReader.open(out)) {
                for (Spectrum a : source.spectra) {
                    Spectrum b = r.getSpectrum(a.index()).orElseThrow();
                    assertNumpressValuesClose(fixture, a, b);
                }
            }
        }
    }

    /**
     * Per-value check of a Numpress re-read against its source: the peak list peak by peak, and the intensity
     * array element by element (the comparator already checks the m/z array element by element). A NaN on
     * either side fails.
     */
    private static void assertNumpressValuesClose(String fixture, Spectrum a, Spectrum b) {
        List<CentroidPeak> pa = a.peaks();
        List<CentroidPeak> pb = b.peaks();
        assertThat(pb).as("%s spectrum %d peaks after Numpress round trip", fixture, a.index()).hasSameSizeAs(pa);
        for (int k = 0; k < pa.size(); k++) {
            CentroidPeak p = pa.get(k);
            CentroidPeak q = pb.get(k);
            if (!(Math.abs(q.mz() - p.mz()) <= NUMPRESS_PEAK_MZ_ABS_TOL)) {
                fail("%s spectrum %d peak %d m/z: src=%s out=%s (abs tol %s)",
                        fixture, a.index(), k, p.mz(), q.mz(), NUMPRESS_PEAK_MZ_ABS_TOL);
            }
            if (!intensityClose(p.intensity(), q.intensity())) {
                fail("%s spectrum %d peak %d intensity: src=%s out=%s (rel tol %s)",
                        fixture, a.index(), k, p.intensity(), q.intensity(), NUMPRESS_INTENSITY_REL_TOL);
            }
        }

        double[] intensity = a.intensity();
        double[] outIntensity = b.intensity();
        assertThat(outIntensity).as("%s spectrum %d intensity after Numpress round trip", fixture, a.index())
                .hasSameSizeAs(intensity);
        for (int k = 0; k < intensity.length; k++) {
            if (!intensityClose(intensity[k], outIntensity[k])) {
                fail("%s spectrum %d intensity[%d]: src=%s out=%s (rel tol %s)",
                        fixture, a.index(), k, intensity[k], outIntensity[k], NUMPRESS_INTENSITY_REL_TOL);
            }
        }
    }

    /** Within {@link #NUMPRESS_INTENSITY_REL_TOL} of {@code max(|expected|, 1)}; false if either side is NaN. */
    private static boolean intensityClose(double expected, double actual) {
        return Math.abs(actual - expected) <= NUMPRESS_INTENSITY_REL_TOL * Math.max(Math.abs(expected), 1.0);
    }
}
