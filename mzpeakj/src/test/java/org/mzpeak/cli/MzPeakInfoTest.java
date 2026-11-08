package org.mzpeak.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakWriter;
import org.mzpeak.model.Activation;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The demo CLI on the HUPO-PSI fixtures, and on a small written dataset for the precursors no fixture has: one
 * without selected ion and one with a charge.
 */
class MzPeakInfoTest {

    private static final String USAGE = "Usage: MzPeakInfo <path-to-.mzpeak-dir-or-zip> [maxSpectra]";

    @Test
    void listsTheFirstFiveSpectraByDefault() {
        Path input = Fixtures.current("small.mzpeak");

        assertThat(output(input.toString())).containsExactly(
                "Dataset: " + input,
                "Spectra: 48",
                "MS levels: {1=14, 2=34}",
                "Chromatograms: 1",
                "  - TIC (MS:1000235) 48 points",
                "First 5 spectra:",
                "  #0  MS1  rt=0.0049  points=13589  peaks=0  PROFILE",
                "  #1  MS1  rt=0.0079  points=18177  peaks=0  PROFILE",
                "  #2  MS2  rt=0.0112  points=485  peaks=485  CENTROID  precursor=810.7894",
                "  #3  MS2  rt=0.0228  points=1006  peaks=1006  CENTROID  precursor=837.3446",
                "  #4  MS2  rt=0.0349  points=837  peaks=837  CENTROID  precursor=725.3621");
    }

    @Test
    void countsWavelengthSpectraAndListsAsManySpectraAsAsked() {
        Path input = Fixtures.current("has_uv.mzpeak");

        assertThat(output(input.toString(), "2")).containsExactly(
                "Dataset: " + input,
                "Spectra: 212",
                "MS levels: {1=212}",
                "Chromatograms: 2",
                "  - TIC (MS:1000235) 212 points",
                "  - DAD1 A: Sig=272,16 Ref=360,100 (MS:1000812) 526 points",
                "Wavelength (UV/DAD) spectra: 520",
                "First 2 spectra:",
                "  #0  MS1  rt=0.0105  points=115  peaks=115  CENTROID",
                "  #1  MS1  rt=0.0270  points=107  peaks=107  CENTROID");
    }

    @Test
    void listsNoMoreSpectraThanTheDatasetHas() {
        Path input = Fixtures.current("Example_Processed.img.mzpeak");

        assertThat(output(input.toString(), "100")).containsExactly(
                "Dataset: " + input,
                "Spectra: 9",
                "MS levels: {0=9}",
                "Chromatograms: 0",
                "First 9 spectra:",
                "  #0  MS0  rt=0.0000  points=2837  peaks=0  PROFILE",
                "  #1  MS0  rt=0.0000  points=4427  peaks=0  PROFILE",
                "  #2  MS0  rt=0.0000  points=4477  peaks=0  PROFILE",
                "  #3  MS0  rt=0.0000  points=4458  peaks=0  PROFILE",
                "  #4  MS0  rt=0.0000  points=4019  peaks=0  PROFILE",
                "  #5  MS0  rt=0.0000  points=3415  peaks=0  PROFILE",
                "  #6  MS0  rt=0.0000  points=3809  peaks=0  PROFILE",
                "  #7  MS0  rt=0.0000  points=4462  peaks=0  PROFILE",
                "  #8  MS0  rt=0.0000  points=4952  peaks=0  PROFILE");
    }

    @Test
    void printsThePrecursorChargeAndSkipsAPrecursorWithoutSelectedIon(@TempDir Path tmp) {
        Path input = tmp.resolve("precursors.mzpeak");
        MzPeakWriter.writeDirectory(input, List.of(
                spectrum(0, 1, 1.0, null, 100.0),
                spectrum(1, 2, 2.0, new Precursor(0L, "scan=1", null, List.of(), Activation.EMPTY), 200.0),
                spectrum(2, 2, 3.0, precursor(new SelectedIon(500.25, 2, null, List.of())), 250.0),
                spectrum(3, 2, 4.0, precursor(new SelectedIon(600.5, null, null, List.of())), 260.0)));

        assertThat(output(input.toString(), "10")).containsExactly(
                "Dataset: " + input,
                "Spectra: 4",
                "MS levels: {1=1, 2=3}",
                "Chromatograms: 0",
                "First 4 spectra:",
                "  #0  MS1  rt=1.0000  points=1  peaks=1  CENTROID",
                "  #1  MS2  rt=2.0000  points=1  peaks=1  CENTROID",
                "  #2  MS2  rt=3.0000  points=1  peaks=1  CENTROID  precursor=500.2500 z=2",
                "  #3  MS2  rt=4.0000  points=1  peaks=1  CENTROID  precursor=600.5000");
    }

    @Test
    void rejectsAMaxSpectraThatIsNotANumberBeforeOpeningTheDataset() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        assertThatThrownBy(() -> runMain(out, err, "does-not-exist.mzpeak", "many"))
                .isInstanceOf(NumberFormatException.class).hasMessageContaining("\"many\"");
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void runPrintsTheUsageToErrAndReturns2WithoutArguments() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        assertThat(MzPeakInfo.run(new String[0], printStream(out), printStream(err))).isEqualTo(2);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).isEqualTo(USAGE + System.lineSeparator());
    }

    @Test
    void mainPrintsWhatRunPrintsAndReturnsNormally() {
        String input = Fixtures.current("small.mzpeak").toString();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        runMain(out, err, input, "3");

        assertThat(out.toString(StandardCharsets.UTF_8).lines().toList())
                .hasSize(9).isEqualTo(output(input, "3"));
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void exitHandsOnlyANonZeroStatusToTheExiter() {
        List<Integer> statuses = new ArrayList<>();

        MzPeakInfo.exit(0, statuses::add);
        assertThat(statuses).isEmpty();

        MzPeakInfo.exit(2, statuses::add);
        assertThat(statuses).containsExactly(2);
    }

    @Test
    void mainPrintsTheUsageAndExitsWithStatus2WithoutArguments(@TempDir Path tmp) throws Exception {
        Exit exit = runInNewJvm(tmp);

        assertThat(exit.status()).isEqualTo(2);
        assertThat(exit.stdout()).isEmpty();
        assertThat(exit.stderr()).isEqualTo(USAGE + System.lineSeparator());
    }

    /** What {@code run(args, out, err)} prints to {@code out}, line by line; it returns 0 and prints nothing to err. */
    private static List<String> output(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThat(MzPeakInfo.run(args, printStream(out), printStream(err))).isEqualTo(0);
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
        return out.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private static PrintStream printStream(ByteArrayOutputStream buffer) {
        return new PrintStream(buffer, true, StandardCharsets.UTF_8);
    }

    /** Runs {@code main(args)} with standard output going to {@code out} and standard error to {@code err}. */
    private static void runMain(ByteArrayOutputStream out, ByteArrayOutputStream err, String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(printStream(out));
        System.setErr(printStream(err));
        try {
            MzPeakInfo.main(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Exit(int status, String stdout, String stderr) {
    }

    /**
     * Runs {@code main(args)} in a JVM of its own, since it may call System.exit. The new JVM gets the Java agents of
     * this one, so that a coverage agent counts what it runs as well.
     */
    private static Exit runInNewJvm(Path tmp, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String jvmArg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (jvmArg.startsWith("-javaagent:")) {
                command.add(jvmArg);
            }
        }
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), MzPeakInfo.class.getName()));
        command.addAll(List.of(args));
        Path stdout = Files.createTempFile(tmp, "stdout", ".txt");
        Path stderr = Files.createTempFile(tmp, "stderr", ".txt");
        Process process = new ProcessBuilder(command)
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            assertThat(process.waitFor(2, TimeUnit.MINUTES)).as("the JVM has ended").isTrue();
        } finally {
            process.destroyForcibly();
        }
        return new Exit(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
    }

    private static Precursor precursor(SelectedIon ion) {
        return new Precursor(0L, "scan=1", null, List.of(ion), Activation.EMPTY);
    }

    private static Spectrum spectrum(long index, int msLevel, double retentionTime, Precursor precursor, double mz) {
        SpectrumDescription description = new SpectrumDescription(index, "scan=" + (index + 1), msLevel,
                retentionTime, Polarity.POSITIVE, SignalContinuity.CENTROID, 0, 0, List.of(),
                precursor == null ? List.of() : List.of(precursor), List.of());
        return new Spectrum(description, new double[] {mz}, new double[] {10.0}, List.of());
    }
}
