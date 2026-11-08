package org.mzpeak.examples;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Spectrum;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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

    /** Exit status of {@code ExtractXic.run(args, out, err)} and what it printed to out and err. */
    private record Captured(int status, String out, String err) {
    }

    private static Captured runCaptured(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = ExtractXic.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Captured(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
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
    void runWritesTheCsvPrintsWhatItWroteAndReturns0(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("xic.csv");

        Captured r = runCaptured(FIXTURE.toString(), out.toString(), "--mz", "810.4155", "--tol-ppm", "10");

        double tolDa = BASE_PEAK_MZ * 10 / 1.0e6;
        assertThat(r.status()).isZero();
        assertThat(r.out()).isEqualTo(
                "Wrote XIC with 14 points (m/z 810.4155 +/- 0.0081 Da) to " + out + System.lineSeparator());
        assertThat(r.err()).isEmpty();
        assertThat(Files.readAllLines(out)).isEqualTo(csv(expected(1, BASE_PEAK_MZ - tolDa, BASE_PEAK_MZ + tolDa)));
    }

    @Test
    void runWithoutInputOutputOrMzPrintsUsageAndReturns2(@TempDir Path tmp) {
        Path out = tmp.resolve("xic.csv");
        for (String[] args : List.of(new String[0], new String[]{FIXTURE.toString(), "--mz", "810.4155"},
                new String[]{FIXTURE.toString(), out.toString(), "--tol-da", "0.1"})) {
            Captured r = runCaptured(args);

            assertThat(r.status()).isEqualTo(2);
            assertThat(r.out()).isEmpty();
            assertThat(r.err()).isEqualTo(USAGE + System.lineSeparator());
        }
        assertThat(out).doesNotExist();
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
        assertThat(r.stderr()).isEqualTo(USAGE + System.lineSeparator());
        assertThat(r.stdout()).isEmpty();
    }

    @Test
    void mainWithoutMzPrintsUsageAndExitsWithStatus2(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("xic.csv");
        ForkedExampleMain.Result r = ForkedExampleMain.run(tmp, ExtractXic.class, FIXTURE.toString(),
                out.toString());
        assertThat(r.exitStatus()).isEqualTo(2);
        assertThat(r.stderr()).isEqualTo(USAGE + System.lineSeparator());
        assertThat(r.stdout()).isEmpty();
        assertThat(out).doesNotExist();
    }
}
