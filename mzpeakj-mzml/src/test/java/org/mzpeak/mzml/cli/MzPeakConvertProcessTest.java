package org.mzpeak.mzml.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ChildJvm;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.mzml.MzMLFixtures;
import org.mzpeak.mzml.MzMLReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JZ-10: {@link MzPeakConvert#main} in a child JVM started through the library's {@link ChildJvm}, which passes this
 * JVM's JaCoCo agent on (mzML design 8.2, 9, 12.7): the exit codes 0, 1 and 2 of a conversion, a failure and a usage
 * error, and the library's own warnings, here the re-sort of diaPASEF's m/z arrays (WR-14, B-22), as one
 * {@code warning: } line each unless {@code java.util.logging.SimpleFormatter.format} is set.
 */
class MzPeakConvertProcessTest {

    private static final String NL = System.lineSeparator();
    private static final String RESORT = "spectrum \\d+: main axis not ascending; re-sorted \\d+ rows";

    @TempDir
    Path dir;

    @Test
    void aConversionExitsWithZero() throws IOException, InterruptedException {
        Path tiny = MzMLFixtures.extract(MzMLFixtures.TINY, dir);
        Path target = dir.resolve("tiny.mzpeak");

        ChildJvm.Result r = main(List.of(), tiny.toString(), target.toString());

        assertThat(r.exitStatus()).as(r.stderr()).isZero();
        assertThat(r.stdout()).isEqualTo("wrote " + target + ": 4 spectra, 0 wavelength spectra, 2 chromatograms" + NL);
        assertThat(r.stderr().lines()).startsWith("warning: " + MzMLReader.LIMITS.get(0))
                .allMatch(line -> line.startsWith("warning: "));
        try (MzPeakReader reader = MzPeakReader.open(target)) {
            assertThat(reader.size()).isEqualTo(4);
        }
    }

    @Test
    void aUsageErrorExitsWithTwo() throws IOException, InterruptedException {
        ChildJvm.Result r = main(List.of(), "--no-zlib");

        assertThat(r.exitStatus()).isEqualTo(2);
        assertThat(r.stdout()).isEmpty();
        assertThat(r.stderr()).isEqualTo("error: missing <input> and <output>" + NL + MzPeakConvert.USAGE + NL);
    }

    @Test
    void aFailedConversionExitsWithOne() throws IOException, InterruptedException {
        Path missing = dir.resolve("missing.mzML");
        Path target = dir.resolve("missing.mzpeak");

        ChildJvm.Result r = main(List.of(), missing.toString(), target.toString());

        assertThat(r.exitStatus()).isEqualTo(1);
        assertThat(r.stdout()).isEmpty();
        assertThat(r.stderr()).isEqualTo("error: mzML " + missing + ": not a readable regular file" + NL);
        assertThat(target).doesNotExist();
    }

    /** diaPASEF's m/z arrays are in scan order; the library re-sorts each and warns in one line per spectrum. */
    @Test
    void theLibrarysWarningsAreOneLineEach() throws IOException, InterruptedException {
        Path dia = MzMLFixtures.extract(MzMLFixtures.DIAPASEF, dir);
        Path target = dir.resolve("dia.mzpeak");

        ChildJvm.Result r = main(List.of(), dia.toString(), target.toString());

        assertThat(r.exitStatus()).as(r.stderr()).isZero();
        assertThat(r.stdout()).isEqualTo("wrote " + target + ": 9 spectra, 0 wavelength spectra, 2 chromatograms" + NL);
        assertThat(r.stderr().lines()).allMatch(line -> line.startsWith("warning: "))
                .anyMatch(line -> line.matches("warning: " + RESORT))
                .contains("warning: " + MzMLReader.LIMITS.get(0));
    }

    /** A format the user gives is kept. */
    @Test
    void aGivenLogFormatIsKept() throws IOException, InterruptedException {
        Path dia = MzMLFixtures.extract(MzMLFixtures.DIAPASEF, dir);

        ChildJvm.Result r = main(List.of("-Djava.util.logging.SimpleFormatter.format=given: %5$s%n"), dia.toString(),
                dir.resolve("dia.mzpeak").toString());

        assertThat(r.exitStatus()).as(r.stderr()).isZero();
        assertThat(r.stderr().lines()).anyMatch(line -> line.matches("given: " + RESORT))
                .noneMatch(line -> line.matches("warning: " + RESORT))
                .contains("warning: " + MzMLReader.LIMITS.get(0));
    }

    private ChildJvm.Result main(List<String> jvmOptions, String... args) throws IOException, InterruptedException {
        return ChildJvm.run(Files.createDirectories(dir.resolve("logs")), jvmOptions, MzPeakConvert.class, args);
    }
}
