package org.mzpeak.examples;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.io.MzPeakWriter;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ConvertToDta}: one Sequest {@code .dta} file per MSn spectrum, and its command line. */
class ConvertToDtaTest {

    private static final Path FIXTURE = Fixtures.current("small.mzpeak").toAbsolutePath();

    private static final double PROTON = 1.007276466812;

    private static final String USAGE = "Usage: ConvertToDta <in.mzpeak> <outDir> "
            + "[--ms-level 2] [--rt-min M] [--rt-max M] [--default-charge 2]";

    private static SpectrumFilter filter(String... args) {
        return SpectrumFilter.from(new ExampleArgs(args));
    }

    private static List<String> fileNames(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** The MS2 spectra of the fixture whose retention time is at most {@code rtMax}. */
    private static List<Spectrum> fixtureMs2(double rtMax) {
        List<Spectrum> ms2 = new ArrayList<>();
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            for (Spectrum s : reader) {
                if (s.msLevel() == 2 && s.description().retentionTime() <= rtMax) {
                    ms2.add(s);
                }
            }
        }
        return ms2;
    }

    /** The .dta text of a fixture MS2 spectrum (no precursor charge, so {@code charge}) from its peak list. */
    private static String dta(Spectrum s, int charge) {
        double mz = s.description().primaryPrecursor().primaryIon().mz();
        StringBuilder text = new StringBuilder(String.format(Locale.US, "%.5f %d%n",
                mz * charge - (charge - 1) * PROTON, charge));
        for (CentroidPeak p : s.peaks()) {
            text.append(String.format(Locale.US, "%.5f %.4f%n", p.mz(), p.intensity()));
        }
        return text.toString();
    }

    private static String scan(Spectrum s) {
        String id = s.description().id();
        return id.substring(id.indexOf("scan=") + "scan=".length());
    }

    private static SpectrumDescription ms(long index, String id, int msLevel, double rt, Precursor precursor) {
        return new SpectrumDescription(index, id, msLevel, rt, Polarity.fromCode(1), SignalContinuity.PROFILE, 0, 0,
                List.of(), precursor == null ? List.of() : List.of(precursor), List.of());
    }

    private static Precursor precursor(SelectedIon... ions) {
        return new Precursor(0L, "scan=10", null, List.of(ions), null);
    }

    @Test
    void writesOneFilePerMs2SpectrumFromItsPeakList(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("dta");
        assertThat(ConvertToDta.run(FIXTURE, out, filter(), 2)).isEqualTo(34);
        List<Spectrum> ms2 = fixtureMs2(Double.POSITIVE_INFINITY);
        assertThat(fileNames(out)).hasSize(34)
                .containsExactlyInAnyOrderElementsOf(ms2.stream()
                        .map(s -> "small." + scan(s) + "." + scan(s) + ".2.dta").toList());
        for (Spectrum s : ms2) {
            Path file = out.resolve("small." + scan(s) + "." + scan(s) + ".2.dta");
            assertThat(Files.readString(file)).isEqualTo(dta(s, 2));
        }
        // the first MS2 spectrum: scan 3, precursor m/z 810.789428710938 without charge, 485 peaks
        List<String> first = Files.readAllLines(out.resolve("small.3.3.2.dta"));
        assertThat(first).hasSize(486);
        assertThat(first.get(0)).isEqualTo("1620.57158 2");
        assertThat(first.get(1)).isEqualTo("231.38884 26.5451");
        assertThat(first.get(485)).isEqualTo("1560.71985 22.9731");
    }

    @Test
    void skipsMs2SpectraOutsideTheFilter(@TempDir Path tmp) throws IOException {
        List<Spectrum> early = fixtureMs2(0.2);
        assertThat(early).isNotEmpty().hasSizeLessThan(34);
        assertThat(ConvertToDta.run(FIXTURE, tmp, filter("--rt-max", "0.2"), 5)).isEqualTo(early.size());
        assertThat(fileNames(tmp)).containsExactlyInAnyOrderElementsOf(early.stream()
                .map(s -> "small." + scan(s) + "." + scan(s) + ".5.dta").toList());
        Spectrum s = early.get(0);
        assertThat(Files.readString(tmp.resolve("small." + scan(s) + "." + scan(s) + ".5.dta"))).isEqualTo(dta(s, 5));
    }

    /**
     * No fixture has an MSn spectrum without precursor, selected ion, peak list or retention time, a precursor charge,
     * or a dataset name without a dot, so this writes one that has all of them.
     */
    @Test
    void handlesMissingPrecursorsChargesAndPeakLists(@TempDir Path tmp) throws IOException {
        Path input = tmp.resolve("constructed");
        MzPeakWriter.writeDirectory(input, List.of(
                new Spectrum(ms(0, "scan=10", 1, 1.0, null),
                        new double[]{100, 200}, new double[]{1, 2}, List.of()),
                // MS2 without precursor: skipped
                new Spectrum(ms(1, "scan=11", 2, 1.1, null),
                        new double[]{100, 200}, new double[]{1, 2}, List.of()),
                // precursor without selected ion: skipped
                new Spectrum(ms(2, "scan=12", 2, 1.2, precursor()),
                        new double[]{100, 200}, new double[]{1, 2}, List.of()),
                // charge 3 and no peak list: the profile points are written
                new Spectrum(ms(3, "scan=13", 2, 1.3, precursor(new SelectedIon(500.25, 3, null, List.of()))),
                        new double[]{150.5, 250.25}, new double[]{10.5, 20.25}, List.of()),
                // no charge: the default charge; no scan number in the id: the index; no retention time: kept
                new Spectrum(ms(4, "frobnicate", 2, Double.NaN,
                        precursor(new SelectedIon(600.5, null, null, List.of()))),
                        new double[]{110, 120}, new double[]{5, 6},
                        List.of(new CentroidPeak(110, 5, 0), new CentroidPeak(120, 6, 1))),
                // outside the retention time window: skipped
                new Spectrum(ms(5, "scan=15", 2, 9.0, precursor(new SelectedIon(700.5, 2, null, List.of()))),
                        new double[]{100, 200}, new double[]{1, 2}, List.of())));
        Path out = tmp.resolve("out");

        assertThat(ConvertToDta.run(input, out, filter("--rt-max", "5"), 4)).isEqualTo(2);

        assertThat(fileNames(out)).containsExactly("constructed.13.13.3.dta", "constructed.4.4.4.dta");
        // 500.25 * 3 - 2 * proton and 600.5 * 4 - 3 * proton
        assertThat(Files.readAllLines(out.resolve("constructed.13.13.3.dta")))
                .containsExactly("1498.73545 3", "150.50000 10.5000", "250.25000 20.2500");
        assertThat(Files.readAllLines(out.resolve("constructed.4.4.4.dta")))
                .containsExactly("2398.97817 4", "110.00000 5.0000", "120.00000 6.0000");
    }

    @Test
    void reportsTheOutputDirectoryWhenWritingFails(@TempDir Path tmp) throws IOException {
        Path notADirectory = Files.writeString(tmp.resolve("taken"), "x");
        assertThatThrownBy(() -> ConvertToDta.run(FIXTURE, notADirectory, filter(), 2))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Failed writing DTA files to " + notADirectory)
                .hasCauseInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(notADirectory)).isEqualTo("x");
    }

    @Test
    void mainWritesWithTheGivenDefaultCharge(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("dta");
        ConvertToDta.main(new String[]{FIXTURE.toString(), out.toString(), "--ms-level", "2", "--default-charge", "3"});
        List<Spectrum> ms2 = fixtureMs2(Double.POSITIVE_INFINITY);
        assertThat(fileNames(out)).hasSize(34).allMatch(name -> name.endsWith(".3.dta"));
        assertThat(Files.readString(out.resolve("small.3.3.3.dta"))).isEqualTo(dta(ms2.get(0), 3));
    }

    @Test
    void mainPrintsWhatItWrote(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("dta");
        ForkedExampleMain.Result r = ForkedExampleMain.run(tmp, ConvertToDta.class, FIXTURE.toString(),
                out.toString());
        assertThat(r.exitStatus()).isZero();
        assertThat(r.stdout().lines()).containsExactly("Wrote 34 .dta files to " + out);
        assertThat(fileNames(out)).hasSize(34).allMatch(name -> name.endsWith(".2.dta"));
    }

    @Test
    void mainWithoutOutputDirectoryPrintsUsageAndExitsWithStatus2(@TempDir Path tmp) throws Exception {
        ForkedExampleMain.Result r = ForkedExampleMain.run(tmp, ConvertToDta.class, FIXTURE.toString());
        assertThat(r.exitStatus()).isEqualTo(2);
        assertThat(r.stderr()).contains(USAGE);
        assertThat(r.stdout()).isEmpty();
    }
}
