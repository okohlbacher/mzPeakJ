package org.mzpeak.examples;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ExtractSpectra}: the filtered copy as archive or directory, and its command line. */
class ExtractSpectraTest {

    private static final Path FIXTURE = Fixtures.current("small.unpacked.mzpeak").toAbsolutePath();

    private static final String USAGE = "Usage: ExtractSpectra <in.mzpeak> <out.mzpeak> "
            + "[--ms-level 1,2] [--rt-min M] [--rt-max M]";

    private static SpectrumFilter filter(String... args) {
        return SpectrumFilter.from(new ExampleArgs(args));
    }

    /** The spectra of {@code dataset} that {@code filter} accepts, in file order. */
    private static List<Spectrum> spectra(Path dataset, SpectrumFilter filter) {
        List<Spectrum> kept = new ArrayList<>();
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            for (Spectrum s : reader) {
                if (filter.accept(s.description())) {
                    kept.add(s);
                }
            }
        }
        return kept;
    }

    /** Asserts that {@code output} holds the fixture's spectra that {@code filter} accepts, and its chromatogram. */
    private static void assertCopy(Path output, SpectrumFilter filter) {
        List<Spectrum> source = spectra(FIXTURE, filter);
        List<Spectrum> copy = spectra(output, filter());
        assertThat(copy).hasSameSizeAs(source);
        for (int i = 0; i < copy.size(); i++) {
            SpectrumDescription expected = source.get(i).description();
            SpectrumDescription actual = copy.get(i).description();
            assertThat(actual.id()).isEqualTo(expected.id());
            assertThat(actual.msLevel()).isEqualTo(expected.msLevel());
            assertThat(actual.retentionTime()).isEqualTo(expected.retentionTime());
            assertThat(copy.get(i).mz()).containsExactly(source.get(i).mz());
            assertThat(copy.get(i).intensity()).containsExactly(source.get(i).intensity());
        }
        try (MzPeakReader in = MzPeakReader.open(FIXTURE); MzPeakReader out = MzPeakReader.open(output)) {
            assertThat(out.chromatograms()).hasSize(1);
            assertThat(out.chromatograms()).map(Chromatogram::id)
                    .containsExactlyElementsOf(in.chromatograms().stream().map(Chromatogram::id).toList());
        }
    }

    @Test
    void outputNamedMzpeakIsWrittenAsSingleFileArchive(@TempDir Path tmp) {
        Path out = tmp.resolve("ms2.mzpeak");
        assertThat(ExtractSpectra.run(FIXTURE, out, filter("--ms-level", "2"))).isEqualTo(34);
        assertThat(out).isRegularFile();
        assertCopy(out, filter("--ms-level", "2"));
    }

    @Test
    void otherOutputNameIsWrittenAsUnpackedDirectory(@TempDir Path tmp) {
        Path out = tmp.resolve("ms1-subset");
        assertThat(ExtractSpectra.run(FIXTURE, out, filter("--ms-level", "1"))).isEqualTo(14);
        assertThat(out).isDirectory();
        assertThat(out.resolve("mzpeak_index.json")).isRegularFile();
        assertCopy(out, filter("--ms-level", "1"));
    }

    @Test
    void mainWritesTheFilteredSpectra(@TempDir Path tmp) {
        Path out = tmp.resolve("early.mzpeak");
        ExtractSpectra.main(new String[]{FIXTURE.toString(), out.toString(), "--rt-max", "0.2"});
        List<Spectrum> expected = spectra(FIXTURE, filter("--rt-max", "0.2"));
        assertThat(expected).isNotEmpty().hasSizeLessThan(48);
        assertThat(out).isRegularFile();
        assertCopy(out, filter("--rt-max", "0.2"));
    }

    @Test
    void mainPrintsWhatItWrote(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("ms2.mzpeak");
        ForkedExampleMain.Result r = ForkedExampleMain.run(tmp, ExtractSpectra.class,
                FIXTURE.toString(), out.toString(), "--ms-level", "2");
        assertThat(r.exitStatus()).isZero();
        assertThat(r.stdout().lines()).containsExactly(
                "Wrote 34 spectra (ms-level=[2] rt=[-Infinity,Infinity]) to " + out);
        assertCopy(out, filter("--ms-level", "2"));
    }

    @Test
    void mainWithoutOutputPrintsUsageAndExitsWithStatus2(@TempDir Path tmp) throws Exception {
        ForkedExampleMain.Result r = ForkedExampleMain.run(tmp, ExtractSpectra.class, FIXTURE.toString(),
                "--ms-level", "2");
        assertThat(r.exitStatus()).isEqualTo(2);
        assertThat(r.stderr()).contains(USAGE);
        assertThat(r.stdout()).isEmpty();
        try (var files = Files.list(tmp)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .allMatch(name -> name.startsWith("stdout") || name.startsWith("stderr"));
        }
    }
}
