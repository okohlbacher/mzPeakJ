package org.mzpeak.examples;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Spectrum;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ExtractXic}: the summed intensity per spectrum and the command line that writes it as CSV. */
class ExtractXicTest {

    private static final Path FIXTURE = Fixtures.current("small.mzpeak").toAbsolutePath();

    /** The base peak m/z of the first MS1 spectrum of the fixture. */
    private static final double BASE_PEAK_MZ = 810.4155;

    private static final String USAGE = "Usage: ExtractXic <in.mzpeak> <out.csv> --mz M "
            + "[--tol-ppm 20 | --tol-da 0.01] [--ms-level 1] [--rt-min M] [--rt-max M]";

    private static SpectrumFilter filter(String... args) {
        return SpectrumFilter.from(new ExampleArgs(args));
    }

    /** Retention time and the intensity of every point in [lo, hi] summed, for each spectrum of {@code msLevel}. */
    private static List<ExtractXic.XicPoint> expected(int msLevel, double lo, double hi) {
        List<ExtractXic.XicPoint> points = new ArrayList<>();
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            for (Spectrum s : reader) {
                if (s.msLevel() != msLevel) {
                    continue;
                }
                double[] mz = s.mz();
                double[] intensity = s.intensity();
                double sum = 0;
                for (int i = 0; i < mz.length; i++) {
                    if (mz[i] >= lo && mz[i] <= hi) {
                        sum += intensity[i];
                    }
                }
                points.add(new ExtractXic.XicPoint(s.description().retentionTime(), sum));
            }
        }
        return points;
    }

    private static List<String> csv(List<ExtractXic.XicPoint> points) {
        List<String> lines = new ArrayList<>();
        lines.add("retention_time,intensity");
        for (ExtractXic.XicPoint p : points) {
            lines.add(String.format(Locale.US, "%.6f,%.4f", p.retentionTime(), p.intensity()));
        }
        return lines;
    }

    @Test
    void sumsThePointsInsideTheWindowOfEachSelectedSpectrum() {
        List<ExtractXic.XicPoint> points = ExtractXic.compute(FIXTURE, BASE_PEAK_MZ, 0.01, filter("--ms-level", "1"));
        assertThat(points).hasSize(14).isEqualTo(expected(1, BASE_PEAK_MZ - 0.01, BASE_PEAK_MZ + 0.01));
        assertThat(points.get(0).intensity()).isGreaterThan(0.0);
    }

    @Test
    void windowAtTheTopOfTheRangeSumsUpToTheLastPoint() {
        List<ExtractXic.XicPoint> points = ExtractXic.compute(FIXTURE, 1999.9, 1.0, filter("--ms-level", "1"));
        assertThat(points).hasSize(14).isEqualTo(expected(1, 1999.9 - 1.0, 1999.9 + 1.0));
        assertThat(points).anyMatch(p -> p.intensity() > 0.0);
    }

    @Test
    void mainWritesMs1CsvWithTwentyPpmByDefault(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("xic.csv");
        ExtractXic.main(new String[]{FIXTURE.toString(), out.toString(), "--mz", "810.4155"});
        double tolDa = BASE_PEAK_MZ * 20 / 1.0e6;
        assertThat(Files.readAllLines(out))
                .hasSize(15)
                .isEqualTo(csv(expected(1, BASE_PEAK_MZ - tolDa, BASE_PEAK_MZ + tolDa)));
    }

    @Test
    void mainTakesTolDaOverTolPpmAndTheGivenMsLevel(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("xic.csv");
        ExtractXic.main(new String[]{FIXTURE.toString(), out.toString(), "--mz", "500", "--tol-ppm", "1",
                "--tol-da", "100", "--ms-level", "2"});
        assertThat(Files.readAllLines(out)).hasSize(35).isEqualTo(csv(expected(2, 400, 600)));
    }

    @Test
    void mainReportsAFailedCsvWrite(@TempDir Path tmp) {
        // the output path is an existing directory
        assertThatThrownBy(() -> ExtractXic.main(new String[]{FIXTURE.toString(), tmp.toString(), "--mz", "500"}))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Failed writing XIC to " + tmp)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void mainPrintsWhatItWrote(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("xic.csv");
        ForkedExampleMain.Result r = ForkedExampleMain.run(tmp, ExtractXic.class,
                FIXTURE.toString(), out.toString(), "--mz", "810.4155");
        assertThat(r.exitStatus()).isZero();
        assertThat(r.stdout().lines()).containsExactly(
                "Wrote XIC with 14 points (m/z 810.4155 +/- 0.0162 Da) to " + out);
        assertThat(Files.readAllLines(out)).hasSize(15);
    }

    @Test
    void mainWithoutOutputPrintsUsageAndExitsWithStatus2(@TempDir Path tmp) throws Exception {
        ForkedExampleMain.Result r = ForkedExampleMain.run(tmp, ExtractXic.class, FIXTURE.toString(),
                "--mz", "810.4155");
        assertThat(r.exitStatus()).isEqualTo(2);
        assertThat(r.stderr()).contains(USAGE);
        assertThat(r.stdout()).isEmpty();
    }

    @Test
    void mainWithoutMzPrintsUsageAndExitsWithStatus2(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("xic.csv");
        ForkedExampleMain.Result r = ForkedExampleMain.run(tmp, ExtractXic.class, FIXTURE.toString(),
                out.toString());
        assertThat(r.exitStatus()).isEqualTo(2);
        assertThat(r.stderr()).contains(USAGE);
        assertThat(r.stdout()).isEmpty();
        assertThat(out).doesNotExist();
    }
}
