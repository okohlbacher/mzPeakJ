package org.mzpeak.examples;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Spectrum;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the example tools end-to-end against the vendored fixture. */
class ExamplesTest {

    private static final Path FIXTURE = Path.of("src/test/resources/mzpeak/small.unpacked.mzpeak");

    private static SpectrumFilter filter(String... args) {
        return SpectrumFilter.from(new ExampleArgs(args));
    }

    @Test
    void extractSpectraWritesFilteredSubset(@TempDir Path tmp) {
        Path out = tmp.resolve("ms2.mzpeak");
        int written = ExtractSpectra.run(FIXTURE, out, filter("--ms-level", "2"));
        assertThat(written).isEqualTo(34);
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.size()).isEqualTo(34);
            assertThat(reader.metadata()).allMatch(d -> d.msLevel() == 2);
        }
    }

    @Test
    void convertToDtaWritesOnePerMsn(@TempDir Path tmp) throws IOException {
        int written = ConvertToDta.run(FIXTURE, tmp, filter("--ms-level", "2"), 2);
        assertThat(written).isEqualTo(34);
        try (var files = Files.list(tmp)) {
            List<Path> dta = files.filter(p -> p.toString().endsWith(".dta")).toList();
            assertThat(dta).hasSize(34);
            // first line of a .dta is "<MH+> <charge>"
            String firstLine = Files.readAllLines(dta.get(0)).get(0);
            assertThat(firstLine).matches("\\d+\\.\\d+ \\d+");
        }
    }

    @Test
    void extractXicSumsTargetAcrossMs1() {
        // target the base peak of MS1 spectrum 0 so the XIC has a real signal there
        double targetMz;
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            Spectrum s0 = reader.getSpectrum(0).orElseThrow();
            int argmax = 0;
            for (int i = 1; i < s0.intensity().length; i++) {
                if (s0.intensity()[i] > s0.intensity()[argmax]) {
                    argmax = i;
                }
            }
            targetMz = s0.mz()[argmax];
        }
        var points = ExtractXic.compute(FIXTURE, targetMz, 0.01, filter("--ms-level", "1"));
        assertThat(points).hasSize(14); // 14 MS1 spectra
        assertThat(points.get(0).intensity()).isGreaterThan(0.0);
    }

    @Test
    void fileInfoPrintsOpenMsStyleSummary() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        MzPeakFileInfo.run(FIXTURE, true, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        String out = buffer.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("Number of spectra: 48");
        assertThat(out).contains("MS levels: [1, 2]");
        assertThat(out).contains("level 1: 14");
        assertThat(out).contains("level 2: 34");
        assertThat(out).contains("Number of chromatograms: 1");
        assertThat(out).contains("total ion current");
        assertThat(out).contains("Peak type per MS level");
        assertThat(out).contains("median:"); // statistics (-s) mode
    }
}
