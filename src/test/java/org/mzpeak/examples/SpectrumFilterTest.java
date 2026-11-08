package org.mzpeak.examples;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link SpectrumFilter}: option parsing, MS-level and retention-time selection, and its description. */
class SpectrumFilterTest {

    private static final Path FIXTURE = Fixtures.current("small.mzpeak");

    private static SpectrumFilter filter(String... args) {
        return SpectrumFilter.from(new ExampleArgs(args));
    }

    private static List<SpectrumDescription> fixtureSpectra() {
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            return reader.metadata();
        }
    }

    /** Every fixture spectrum has a retention time, so the one without is constructed. */
    private static SpectrumDescription withoutRetentionTime(int msLevel) {
        return new SpectrumDescription(0, "scan=1", msLevel, Double.NaN, Polarity.fromCode(1),
                SignalContinuity.CENTROID, 0, 0, List.of(), List.of(), List.of());
    }

    @Test
    void withoutOptionsAcceptsEverySpectrum() {
        SpectrumFilter f = filter();
        assertThat(f.msLevels()).isEmpty();
        assertThat(f.rtMin()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(f.rtMax()).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(f.describe()).isEqualTo("ms-level=any rt=[-Infinity,Infinity]");
        List<SpectrumDescription> spectra = fixtureSpectra();
        assertThat(spectra).hasSize(48).allMatch(f::accept);
    }

    @Test
    void msLevelListSkipsBlankPartsAndTrimsTheOthers() {
        SpectrumFilter f = filter("--ms-level=2,, 1 ,");
        assertThat(f.msLevels()).containsExactly(2, 1);
        assertThat(f.describe()).isEqualTo("ms-level=[2, 1] rt=[-Infinity,Infinity]");
    }

    @Test
    void repeatedMsLevelOptionsAreCombined() {
        assertThat(filter("--ms-level", "1", "--ms-level", "2").msLevels()).containsExactly(1, 2);
    }

    @Test
    void msLevelSelectsOnlyTheListedLevels() {
        List<SpectrumDescription> spectra = fixtureSpectra();
        assertThat(spectra.stream().filter(filter("--ms-level", "2")::accept))
                .hasSize(34).allMatch(d -> d.msLevel() == 2);
        assertThat(spectra.stream().filter(filter("--ms-level", "1")::accept))
                .hasSize(14).allMatch(d -> d.msLevel() == 1);
        assertThat(spectra.stream().filter(filter("--ms-level", "3")::accept)).isEmpty();
    }

    @Test
    void retentionTimeWindowIncludesBothBounds() {
        SpectrumDescription d = fixtureSpectra().get(5);
        double rt = d.retentionTime();
        String at = Double.toString(rt);
        SpectrumFilter exact = filter("--rt-min", at, "--rt-max", at);
        assertThat(exact.rtMin()).isEqualTo(rt);
        assertThat(exact.rtMax()).isEqualTo(rt);
        assertThat(exact.describe()).isEqualTo("ms-level=any rt=[" + at + "," + at + "]");
        assertThat(exact.accept(d)).isTrue();
        assertThat(fixtureSpectra().stream().filter(exact::accept)).containsExactly(d);
        // just above the minimum or just below the maximum leaves the spectrum out
        assertThat(filter("--rt-min", Double.toString(Math.nextUp(rt))).accept(d)).isFalse();
        assertThat(filter("--rt-max", Double.toString(Math.nextDown(rt))).accept(d)).isFalse();
    }

    @Test
    void retentionTimeWindowSelectsTheSpectraInside() {
        List<SpectrumDescription> spectra = fixtureSpectra();
        SpectrumFilter f = filter("--rt-min", "0.1", "--rt-max", "0.3");
        List<SpectrumDescription> kept = spectra.stream().filter(f::accept).toList();
        assertThat(kept).isNotEmpty().hasSizeLessThan(spectra.size())
                .allMatch(d -> d.retentionTime() >= 0.1 && d.retentionTime() <= 0.3);
        assertThat(spectra.stream().filter(d -> !kept.contains(d)))
                .allMatch(d -> d.retentionTime() < 0.1 || d.retentionTime() > 0.3);
    }

    @Test
    void spectrumWithoutRetentionTimePassesAnyWindowButNotAnotherLevel() {
        SpectrumFilter window = filter("--rt-min", "5", "--rt-max", "6");
        assertThat(window.accept(withoutRetentionTime(1))).isTrue();
        SpectrumFilter ms2Window = filter("--ms-level", "2", "--rt-min", "5", "--rt-max", "6");
        assertThat(ms2Window.accept(withoutRetentionTime(2))).isTrue();
        assertThat(ms2Window.accept(withoutRetentionTime(1))).isFalse();
    }
}
