package org.mzpeak.io;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Layout;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.model.Spectrum;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Writer safety net for the default build: the forward-backward field diff of {@link ForwardBackwardCorpusTest}
 * run on the vendored HUPO-PSI fixtures instead of the external corpus, without the validator.
 *
 * <p>Each fixture is read fully, written as a single-file archive in the point and in the Numpress layout with
 * the 6-argument {@link MzPeakWriter#writeArchive}/{@link MzPeakWriter#writeArchiveNumpress}, re-read, and
 * compared field by field with {@link ForwardBackwardCorpusTest#compareRoundTrip}. The comparison is NaN-aware,
 * so a value the writer drops (re-read as NaN) is a diff. Diffs tagged {@code KNOWN_LIMIT} are documented
 * deferred limitations and do not fail; no fixture produces one today.
 *
 * <p>That comparison checks peak lists only by count. The point layout stores peaks losslessly, so for it every
 * re-read peak list must also equal the source exactly. Numpress peaks are lossy and are not compared by value.
 */
class FixtureRoundTripTest {

    private static final String BASE = "src/test/resources/mzpeak/";

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
        List<String> hardDiffs = diffs.stream().filter(d -> !d.startsWith("KNOWN_LIMIT ")).toList();
        assertThat(hardDiffs)
                .as("fidelity diffs for %s written as %s (all diffs:%n%s)", fixture, layout, String.join("\n", diffs))
                .isEmpty();

        if (layout == Layout.POINT) {
            try (MzPeakReader r = MzPeakReader.open(out)) {
                for (Spectrum a : source.spectra) {
                    Spectrum b = r.getSpectrum(a.index()).orElseThrow();
                    assertThat(b.peaks())
                            .as("%s spectrum %d peaks after point-layout round trip", fixture, a.index())
                            .isEqualTo(a.peaks());
                }
            }
        }
    }
}
