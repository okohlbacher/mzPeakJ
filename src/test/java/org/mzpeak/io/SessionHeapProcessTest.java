package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.FileMetadata;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@link MzPeakWriter.Session} holds no spectrum's signal once it is added (MZ-11, design §7): a child JVM
 * ({@link ChildJvm}, with the build's coverage agent) with a 128 MiB heap writes 3,000 profile spectra of 6,400 points
 * each through {@link MzPeakWriter#openArchive}, generating each spectrum inside the loop and dropping it after
 * {@link MzPeakWriter.Session#addSpectrum}. The 19.2 million points are about 307 MB of doubles, more than twice the
 * heap, so a session that kept them would fail. The row-group size is 4 MiB, so the open row groups stay small. This
 * JVM then reads the archive back: 3,000 spectra of 6,400 points, of which the first, the middle and the last equal
 * the regenerated arrays.
 */
class SessionHeapProcessTest {

    private static final int SPECTRA = 3_000;
    private static final int POINTS = 6_400;

    @Test
    void aChildJvmWithA128MegabyteHeapWrites3000ProfileSpectraThroughASession(@TempDir Path tmp) throws Exception {
        Path out = Files.createDirectory(tmp.resolve("out"));
        Path archive = out.resolve("profile.mzpeak");
        ChildJvm.Result result = ChildJvm.run(tmp, List.of("-Xmx128m"), Child.class, archive.toString());
        assertThat(result.exitStatus()).as("exit status of the child; stderr:%n%s", result.stderr()).isZero();

        try (MzPeakReader reader = MzPeakReader.open(archive)) {
            assertThat(reader.size()).isEqualTo(SPECTRA);
            for (int index : new int[] {0, SPECTRA / 2 - 1, SPECTRA - 1}) {
                Spectrum expected = spectrum(index);
                Spectrum actual = reader.getSpectrumAt(index);
                assertThat(actual.index()).isEqualTo(index);
                assertThat(actual.mz()).as("m/z of spectrum %d", index).hasSize(POINTS).isEqualTo(expected.mz());
                assertThat(actual.intensity()).as("intensity of spectrum %d", index)
                        .isEqualTo(expected.intensity());
            }
        }
        try (var files = Files.list(out)) {
            assertThat(files).as("the archive's directory after the session").containsExactly(archive);
        }
    }

    /**
     * Profile spectrum {@code index}: 6,400 distinct points, ascending m/z from 100 in random steps of 0.01 to 0.11 and
     * intensities up to 1e6, both drawn from a {@link SplittableRandom} seeded with the index.
     */
    static Spectrum spectrum(int index) {
        SplittableRandom random = new SplittableRandom(index);
        double[] mz = new double[POINTS];
        double[] intensity = new double[POINTS];
        double next = 100;
        for (int i = 0; i < POINTS; i++) {
            next += 0.01 + random.nextDouble() * 0.1;
            mz[i] = next;
            intensity[i] = random.nextDouble() * 1e6;
        }
        SpectrumDescription description = new SpectrumDescription(index, "scan=" + (index + 1), 1, index / 60.0,
                null, SignalContinuity.PROFILE, POINTS, 0, List.of(), List.of(), List.of());
        return new Spectrum(description, mz, intensity, List.of());
    }

    /** The child: writes the spectra, one generated at a time, to the archive {@code args[0]}. */
    static final class Child {

        private Child() {
        }

        public static void main(String[] args) {
            Options options = Options.defaults().withRowGroupSize(4L << 20);
            try (MzPeakWriter.Session session = MzPeakWriter.openArchive(Path.of(args[0]), FileMetadata.EMPTY,
                    List.of(), Set.of(), options)) {
                for (int index = 0; index < SPECTRA; index++) {
                    session.addSpectrum(spectrum(index));
                }
                session.finish();
            }
        }
    }
}
