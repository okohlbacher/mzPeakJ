package org.mzpeak.examples;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.io.MzPeakWriter;
import org.mzpeak.model.Activation;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.Component;
import org.mzpeak.model.meta.FileMetadata.ComponentType;
import org.mzpeak.model.meta.FileMetadata.FileDescription;
import org.mzpeak.model.meta.FileMetadata.InstrumentConfiguration;
import org.mzpeak.model.meta.FileMetadata.MsRun;
import org.mzpeak.model.meta.FileMetadata.Software;
import org.mzpeak.model.meta.FileMetadata.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The FileInfo example on the HUPO-PSI fixtures, and on small written datasets for what no fixture has: an MS level
 * with mixed peak types or without any value, charged and ion-less precursors, an unnamed dissociation method, an
 * ion current or untyped chromatogram, and sparse or empty file metadata.
 */
class MzPeakFileInfoTest {

    private static final String USAGE = "Usage: MzPeakFileInfo <in.mzpeak> [-s]   (-s adds intensity statistics)";

    @Test
    void summarizesAnArchiveWithoutStatistics() {
        Path input = Fixtures.current("small.mzpeak");

        assertThat(summary(input, false)).containsExactly(
                "File name: " + input,
                "File type: mzPeak (single-file archive)",
                "",
                "Number of spectra: 48",
                "MS levels: [1, 2]",
                "Number of spectra per MS level:",
                "  level 1: 14",
                "  level 2: 34",
                "Number of peaks (spectra): 243054",
                "",
                "Ranges:",
                "  retention time: 0.0049 .. 0.4872",
                "  mass-to-charge: 162.2459 .. 2000.0001",
                "  intensity:      0.0000 .. 2637890.7500",
                "",
                "Per MS level ranges:",
                "  MS level 1:",
                "    retention time: 0.0049 .. 0.4332",
                "    mass-to-charge: 200.0680 .. 2000.0001",
                "    intensity:      0.0000 .. 2637890.7500",
                "  MS level 2:",
                "    retention time: 0.0112 .. 0.4872",
                "    mass-to-charge: 162.2459 .. 1743.3612",
                "    intensity:      1.1795 .. 298265.9375",
                "",
                "Peak type per MS level:",
                "  level 1: profile",
                "  level 2: centroid",
                "",
                "Precursor charge distribution:",
                "  unknown: 34",
                "",
                "Activation methods:",
                "  collision-induced dissociation: 34",
                "",
                "Number of chromatograms: 1",
                "Number of chromatographic peaks: 48",
                "Number of chromatograms per type:",
                "  MS:1000235 (total ion current): 1",
                "",
                "Run: small  (start 2005-07-20T19:44:22Z)",
                "Software:",
                "  - Xcalibur 1.1 Beta 7",
                "  - ProteoWizard software 3.0.23307",
                "  - custom unreleased software tool 0.1.0",
                "Instrument configurations:",
                "  config 0:",
                "    ion source: electrospray ionization, electrospray inlet",
                "    analyzer: fourier transform ion cyclotron resonance mass spectrometer",
                "    detector: inductive detector",
                "  config 1:",
                "    ion source: electrospray ionization, electrospray inlet",
                "    analyzer: radial ejection linear ion trap",
                "    detector: electron multiplier",
                "Source files:",
                "  - small.RAW");
    }

    @Test
    void summarizesImagingDataWithoutMs1PrecursorsOrChromatograms() {
        Path input = Fixtures.current("Example_Processed.img.mzpeak");

        assertThat(summary(input, true)).containsExactly(
                "File name: " + input,
                "File type: mzPeak (single-file archive)",
                "",
                "Number of spectra: 9",
                "MS levels: [0]",
                "Number of spectra per MS level:",
                "  level 0: 9",
                "Number of peaks (spectra): 36856",
                "",
                "Ranges:",
                "  retention time: 0.0000 .. 0.0000",
                "  mass-to-charge: 100.5000 .. 799.9167",
                "  intensity:      0.0000 .. 9.2446",
                "",
                "Per MS level ranges:",
                "  MS level 0:",
                "    retention time: 0.0000 .. 0.0000",
                "    mass-to-charge: 100.5000 .. 799.9167",
                "    intensity:      0.0000 .. 9.2446",
                "",
                "Peak type per MS level:",
                "  level 0: profile",
                "",
                "Number of chromatograms: 0",
                "Number of chromatographic peaks: 0",
                "",
                "Run: Experiment01",
                "Software:",
                "  - Xcalibur 2.2",
                "  - custom unreleased software tool 1.1 beta",
                "  - custom unreleased software tool 0.1.0",
                "Instrument configurations:",
                "  config 0:",
                "    ion source: electrospray ionization, nanospray inlet, focus diameter x, focus diameter y, pulse"
                        + " energy, pulse duration, attenuation, gas laser, dried droplet MALDI matrix preparation,"
                        + " matrix solution concentration, matrix solution",
                "    analyzer: ion trap, accuracy",
                "    detector: electron multiplier, transient recorder",
                "Source files:",
                "  - Example.raw",
                "",
                "-- Statistics --",
                "MS1 peak intensity distribution:",
                "  (no values)");
    }

    @Test
    void countsWavelengthSpectraAndNamesAbsorptionChromatograms() {
        List<String> lines = summary(Fixtures.current("has_uv.mzpeak"), true);

        assertThat(lines).containsSequence(
                "Number of chromatograms: 2",
                "Number of chromatographic peaks: 738",
                "Number of chromatograms per type:",
                "  MS:1000235 (total ion current): 1",
                "  MS:1000812 (absorption chromatogram): 1",
                "Number of wavelength (UV/DAD) spectra: 520",
                "");
        assertThat(lines).containsSequence(
                "-- Statistics --",
                "MS1 peak intensity distribution:",
                "  min:            200.0000",
                "  lower quartile: 250.4470",
                "  median:         374.1292",
                "  upper quartile: 759.0965",
                "  max:            113672.4609",
                "  mean:           1580.1261",
                "  count:          24310");
    }

    @Test
    void namesBasePeakChromatograms() {
        assertThat(summary(Fixtures.current("diaPASEF.ref.mzpeak"), false)).containsSequence(
                "Number of chromatograms per type:",
                "  MS:1000235 (total ion current): 1",
                "  MS:1000628 (basepeak chromatogram): 1",
                "",
                "Run: test/data/diaPASEF.d  (start 2019-08-24T18:27:02.345+02:00)");
    }

    @Test
    void summarizesWhatNoFixtureHas(@TempDir Path tmp) {
        Path input = writeEdgeCases(tmp.resolve("edge.mzpeak"));

        assertThat(summary(input, true)).containsExactly(
                "File name: " + input,
                "File type: mzPeak (unpacked directory)",
                "",
                "Number of spectra: 6",
                "MS levels: [1, 2, 3]",
                "Number of spectra per MS level:",
                "  level 1: 2",
                "  level 2: 3",
                "  level 3: 1",
                "Number of peaks (spectra): 4",
                "",
                "Ranges:",
                "  retention time: 1.5000 .. 3.0000",
                "  mass-to-charge: 100.0000 .. 300.0000",
                "  intensity:      5.0000 .. 10.0000",
                "",
                "Per MS level ranges:",
                "  MS level 1:",
                "    retention time: 1.5000 .. 1.5000",
                "    mass-to-charge: 100.0000 .. 100.0000",
                "    intensity:      10.0000 .. 10.0000",
                "  MS level 2:",
                "    retention time: 2.0000 .. 3.0000",
                "    mass-to-charge: 200.0000 .. 300.0000",
                "    intensity:      5.0000 .. 7.0000",
                "  MS level 3:",
                "    retention time: (none)",
                "    mass-to-charge: (none)",
                "    intensity:      (none)",
                "",
                "Peak type per MS level:",
                "  level 1: unknown/mixed",
                "  level 2: centroid",
                "  level 3: centroid",
                "",
                "Precursor charge distribution:",
                "  charge 2: 1",
                "  unknown: 3",
                "",
                "Activation methods:",
                "  MS:1000133: 1",
                "  MS:1000422: 1",
                "",
                "Number of chromatograms: 2",
                "Number of chromatographic peaks: 3",
                "Number of chromatograms per type:",
                "  MS:1000810 (ion current chromatogram): 1",
                "  unknown (?): 1",
                "",
                "Run: ",
                "Software:",
                "  - mzpeakj-test",
                "Instrument configurations:",
                "  config 0:",
                "    component: ?",
                "    analyzer: orbitrap, mass resolution  (resolution 60000)",
                "Source files:",
                "  - ",
                "",
                "-- Statistics --",
                "MS1 peak intensity distribution:",
                "  min:            10.0000",
                "  lower quartile: 10.0000",
                "  median:         10.0000",
                "  upper quartile: 10.0000",
                "  max:            10.0000",
                "  mean:           10.0000",
                "  count:          1");
    }

    static Stream<FileMetadata> metadataWithoutRunSoftwareInstrumentsOrSourceFiles() {
        return Stream.of(FileMetadata.EMPTY,
                new FileMetadata(new FileDescription(List.of(), List.of()), List.of(), List.of(), List.of(), List.of(),
                        null));
    }

    @ParameterizedTest
    @MethodSource("metadataWithoutRunSoftwareInstrumentsOrSourceFiles")
    void reportsMissingFileMetadata(FileMetadata metadata, @TempDir Path tmp) {
        Path input = tmp.resolve("bare.mzpeak");
        MzPeakWriter.writeDirectory(input, List.of(spectrum(0, 1, 1.0, SignalContinuity.CENTROID, null,
                new double[] {100.0}, new double[] {10.0})), List.of(), metadata);
        try (MzPeakReader reader = MzPeakReader.open(input)) {
            // what the dataset reads back as: no file description, or one without source files
            assertThat(reader.fileMetadata().fileDescription()).isEqualTo(metadata.fileDescription());
        }

        assertThat(summary(input, false)).containsExactly(
                "File name: " + input,
                "File type: mzPeak (unpacked directory)",
                "",
                "Number of spectra: 1",
                "MS levels: [1]",
                "Number of spectra per MS level:",
                "  level 1: 1",
                "Number of peaks (spectra): 1",
                "",
                "Ranges:",
                "  retention time: 1.0000 .. 1.0000",
                "  mass-to-charge: 100.0000 .. 100.0000",
                "  intensity:      10.0000 .. 10.0000",
                "",
                "Per MS level ranges:",
                "  MS level 1:",
                "    retention time: 1.0000 .. 1.0000",
                "    mass-to-charge: 100.0000 .. 100.0000",
                "    intensity:      10.0000 .. 10.0000",
                "",
                "Peak type per MS level:",
                "  level 1: centroid",
                "",
                "Number of chromatograms: 0",
                "Number of chromatographic peaks: 0",
                "",
                "Instrument configurations: (none in footer)");
    }

    @Test
    void runSummarizesTheFirstInputAndReadsDashSOrStatisticsAsTheStatisticsSwitch() {
        Path input = Fixtures.current("Example_Processed.img.mzpeak");
        String plain = String.join(System.lineSeparator(), summary(input, false)) + System.lineSeparator();
        String withStatistics = String.join(System.lineSeparator(), summary(input, true)) + System.lineSeparator();

        assertThat(runOutput(input.toString())).isEqualTo(plain);
        assertThat(runOutput(input.toString(), "other.mzpeak")).isEqualTo(plain);
        assertThat(runOutput(input.toString(), "-s")).isEqualTo(withStatistics);
        assertThat(runOutput("-s", input.toString())).isEqualTo(withStatistics);
        assertThat(runOutput(input.toString(), "--statistics")).isEqualTo(withStatistics);
    }

    @Test
    void runPrintsTheUsageToErrAndReturns2WithoutInput() {
        for (String[] args : List.of(new String[0], new String[] {"-s"}, new String[] {"--statistics"})) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();

            assertThat(MzPeakFileInfo.run(args, printStream(out), printStream(err))).isEqualTo(2);
            assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
            assertThat(err.toString(StandardCharsets.UTF_8)).isEqualTo(USAGE + System.lineSeparator());
        }
    }

    @Test
    void mainPrintsWhatRunPrintsAndReturnsNormally() {
        String input = Fixtures.current("Example_Processed.img.mzpeak").toString();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(printStream(out));
        System.setErr(printStream(err));
        try {
            MzPeakFileInfo.main(new String[] {input, "-s"});
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("-- Statistics --").isEqualTo(runOutput(input, "-s"));
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void mainPrintsTheUsageAndExitsWithStatus2WithoutInput(@TempDir Path tmp) throws Exception {
        for (String[] args : List.of(new String[0], new String[] {"-s"}, new String[] {"--statistics"})) {
            ForkedExampleMain.Result exit = ForkedExampleMain.run(tmp, MzPeakFileInfo.class, args);

            assertThat(exit.exitStatus()).isEqualTo(2);
            assertThat(exit.stdout()).isEmpty();
            assertThat(exit.stderr()).isEqualTo(USAGE + System.lineSeparator());
        }
    }

    /** The summary of {@code input}, line by line. */
    private static List<String> summary(Path input, boolean statistics) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        MzPeakFileInfo.run(input, statistics, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        return buffer.toString(StandardCharsets.UTF_8).lines().toList();
    }

    /** What {@code run(args, out, err)} prints to {@code out}; it returns 0 and prints nothing to {@code err}. */
    private static String runOutput(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThat(MzPeakFileInfo.run(args, printStream(out), printStream(err))).isEqualTo(0);
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
        return out.toString(StandardCharsets.UTF_8);
    }

    private static PrintStream printStream(ByteArrayOutputStream buffer) {
        return new PrintStream(buffer, true, StandardCharsets.UTF_8);
    }

    /**
     * Six spectra and two chromatograms with what no fixture has. MS1: one profile spectrum without retention time and
     * with the only MS1 point, one empty centroid spectrum. MS2: a precursor without selected ion and activation, a
     * charged precursor whose dissociation methods (CID, HCD) have no name, and no precursor. MS3: an uncharged
     * precursor, no retention time and no points. Then an ion current and an untyped chromatogram, and file metadata
     * with an unnamed run, a software without version, an unknown component without named parameters, a resolution
     * and a source file without name. Written in the packed layout, which keeps the methods nameless.
     */
    private static Path writeEdgeCases(Path dir) {
        Precursor withoutIon = new Precursor(0L, "scan=1", null, List.of(), Activation.EMPTY);
        Precursor charged = new Precursor(1L, "scan=2", null, List.of(new SelectedIon(500.25, 2, null, List.of())),
                new Activation(List.of(new Param(null, "MS:1000133", null, null),
                        new Param(null, "MS:1000422", null, null))));
        Precursor uncharged = new Precursor(3L, "scan=4", null, List.of(new SelectedIon(250.0, null, null, List.of())),
                Activation.EMPTY);
        List<Spectrum> spectra = List.of(
                spectrum(0, 1, Double.NaN, SignalContinuity.PROFILE, null, new double[] {100.0}, new double[] {10.0}),
                spectrum(1, 1, 1.5, SignalContinuity.CENTROID, null, new double[0], new double[0]),
                spectrum(2, 2, 2.0, SignalContinuity.CENTROID, withoutIon,
                        new double[] {200.0, 300.0}, new double[] {5.0, 6.0}),
                spectrum(3, 2, 2.5, SignalContinuity.CENTROID, charged, new double[] {250.0}, new double[] {7.0}),
                spectrum(4, 2, 3.0, SignalContinuity.CENTROID, null, new double[0], new double[0]),
                spectrum(5, 3, Double.NaN, SignalContinuity.CENTROID, uncharged, new double[0], new double[0]));
        List<Chromatogram> chromatograms = List.of(
                new Chromatogram(0, "XIC 250", "MS:1000810", new double[] {2.0, 2.5}, new double[] {5.0, 7.0}),
                new Chromatogram(1, "custom", null, new double[] {1.5}, new double[] {1.0}));
        FileMetadata metadata = new FileMetadata(
                new FileDescription(List.of(), List.of(new SourceFile("source", null, "file:///run.raw", List.of()))),
                List.of(new InstrumentConfiguration(0, List.of(
                        new Component(ComponentType.UNKNOWN, 1, List.of(
                                new Param(null, "MS:1000008", null, null), new Param(" ", "MS:1000073", null, null))),
                        new Component(ComponentType.ANALYZER, 2, List.of(
                                new Param("orbitrap", "MS:1000484", null, null),
                                new Param("mass resolution", "MS:1000028", 60000L, null)))),
                        null, List.of())),
                List.of(), List.of(),
                List.of(new Software("mzpeakj-test", null, List.of())),
                new MsRun(null, null, null, null, null, List.of()));
        // The packed layout writes Params as given, so both methods above read back without a name; the split
        // writer would name them (the Rust reader needs a name).
        MzPeakWriter.writeDirectory(dir, spectra, chromatograms, List.of(), List.of(), metadata,
                MzPeakWriter.Options.defaults().withMetadataLayout(MzPeakWriter.MetadataLayout.PACKED));
        return dir;
    }

    private static Spectrum spectrum(long index, int msLevel, double retentionTime, SignalContinuity continuity,
                                     Precursor precursor, double[] mz, double[] intensity) {
        SpectrumDescription description = new SpectrumDescription(index, "scan=" + (index + 1), msLevel,
                retentionTime, Polarity.POSITIVE, continuity, 0, 0, List.of(),
                precursor == null ? List.of() : List.of(precursor), List.of());
        return new Spectrum(description, mz, intensity, List.of());
    }
}
