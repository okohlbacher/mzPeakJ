package org.mzpeak.mzml;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.MzPeakWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TimeZone;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JZ-9: determinism (mzML design 11, 12.4). Every conversion of the fixpoint chains of {@link MzMLFixpointTest} is run
 * twice in-process, from the same input to two targets: the two mzML files are byte-identical and the two mzPeak
 * archives have equal content digests per member ({@link MzMLFixpointTest#digests}), and both runs report the same
 * warnings and counts. The chains: for the 13 mzPeak fixtures toMzML (zlib), toMzPeak, toMzML, toMzPeak, toMzML; for
 * the 4 vendored mzML toMzPeak, toMzML, toMzPeak, toMzML. Under the default time zone UTC, restored after each test.
 */
class MzMLDeterminismTest {

    @TempDir
    Path dir;

    private TimeZone zone;
    private int step;

    @BeforeEach
    void utc() {
        zone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(zone);
    }

    static Stream<Arguments> mzPeakFixtures() {
        return MzMLFixpointTest.mzPeakFixtures();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mzPeakFixtures")
    void everyConversionOfTheMzPeakChainIsDeterministic(String name, Path fixture, boolean legacy) throws IOException {
        Path m1 = toMzML(name, fixture);
        Path p2 = toMzPeak(name, m1);
        Path m2 = toMzML(name, p2);
        Path p3 = toMzPeak(name, m2);
        toMzML(name, p3);
        assertThat(step).isEqualTo(5);
    }

    static Stream<String> mzMLFixtures() {
        return MzMLFixpointTest.mzMLFixtures();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mzMLFixtures")
    void everyConversionOfTheMzMLChainIsDeterministic(String name) throws IOException {
        Path source = MzMLFixtures.extract(name, Files.createDirectories(dir.resolve("source")));
        Path q1 = toMzPeak(name, source);
        Path m1 = toMzML(name, q1);
        Path q2 = toMzPeak(name, m1);
        toMzML(name, q2);
        assertThat(step).isEqualTo(4);
    }

    /** {@code input} converted to mzML (zlib) twice; asserts the two are the same and returns the first. */
    private Path toMzML(String name, Path input) throws IOException {
        Path[] targets = targets(".mzML");
        MzMLConversion.Result first = MzMLConversion.toMzML(input, targets[0], true);
        MzMLConversion.Result second = MzMLConversion.toMzML(input, targets[1], true);
        String what = name + " step " + step + " toMzML";
        assertThat(second).as(what).isEqualTo(first);
        assertThat(Files.readAllBytes(targets[1])).as(what).isEqualTo(Files.readAllBytes(targets[0]));
        return targets[0];
    }

    /** {@code input} converted to an mzPeak archive twice; asserts the two are the same and returns the first. */
    private Path toMzPeak(String name, Path input) {
        Path[] targets = targets(".mzpeak");
        MzMLConversion.Result first = MzMLConversion.toMzPeak(input, targets[0], false,
                MzPeakWriter.Options.defaults());
        MzMLConversion.Result second = MzMLConversion.toMzPeak(input, targets[1], false,
                MzPeakWriter.Options.defaults());
        String what = name + " step " + step + " toMzPeak";
        assertThat(second).as(what).isEqualTo(first);
        assertThat(MzMLFixpointTest.digests(targets[1])).as(what).isEqualTo(MzMLFixpointTest.digests(targets[0]));
        return targets[0];
    }

    /** Two fresh targets of the next step, {@code <step>a<suffix>} and {@code <step>b<suffix>}. */
    private Path[] targets(String suffix) {
        step++;
        return new Path[] {dir.resolve(step + "a" + suffix), dir.resolve(step + "b" + suffix)};
    }
}
