package org.mzpeak.mzml.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.mzml.MzMLFixtures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design D-3: {@code mzpeakj-mzml-<version>-all.jar} as built by {@code package}, run by failsafe after it. The jar
 * converts tiny.pwiz.1.1.mzML to mzPeak and back to mzML with {@code java -jar} in a child JVM (the test JVM's
 * {@code java}, no JVM options), without a {@code restricted method} warning on JDK 22 and newer; its manifest names the
 * main class and enables native access; it holds only the 81 fastutil classes jmzML needs; and its size stays below
 * {@link #MAX_SIZE}.
 */
class ExecutableJarIT {

    /** Path of the jar, set by failsafe in the converter's POM. */
    private static final String JAR_PROPERTY = "mzpeak.mzml.all.jar";
    private static final long TIMEOUT_SECONDS = 300;
    private static final String NL = System.lineSeparator();
    /**
     * S x 1.05 rounded up to the next 100,000 bytes, where S = 44,712,659 bytes is the jar built on 2026-11-11 from
     * agent-team/v012 at 8df4b13 (after V-2 and V-3) with this unit's changes, on OpenJDK 27.0.1 (61,438,477 bytes
     * there without them).
     */
    private static final long MAX_SIZE = 47_000_000;
    /** The closure of it.unimi.dsi.fastutil.chars.CharOpenHashSet in fastutil 6.5.1 (filter in the POM). */
    private static final int FASTUTIL_CLASSES = 81;

    @TempDir
    Path dir;

    @Test
    void theJarConvertsMzMLToMzPeakAndBack() throws IOException, InterruptedException {
        Path tiny = MzMLFixtures.extract(MzMLFixtures.TINY, dir);
        Path mzpeak = dir.resolve("tiny.mzpeak");
        Path mzml = dir.resolve("tiny.mzML");

        Result toMzPeak = javaJar(tiny.toString(), mzpeak.toString());
        Result toMzML = javaJar(mzpeak.toString(), mzml.toString());

        assertThat(toMzPeak.exitStatus()).as(toMzPeak.stderr()).isZero();
        assertThat(toMzPeak.stdout())
                .isEqualTo("wrote " + mzpeak + ": 4 spectra, 0 wavelength spectra, 2 chromatograms" + NL);
        assertThat(toMzML.exitStatus()).as(toMzML.stderr()).isZero();
        assertThat(toMzML.stdout())
                .isEqualTo("wrote " + mzml + ": 4 spectra, 0 wavelength spectra, 2 chromatograms" + NL);
        assertThat(Files.readString(mzml, StandardCharsets.UTF_8)).contains("<mzML", "</mzML>");
        if (Runtime.version().feature() >= 22) {
            assertThat(toMzPeak.stderr()).doesNotContain("restricted method");
            assertThat(toMzML.stderr()).doesNotContain("restricted method");
        }
    }

    @Test
    void theManifestNamesTheMainClassAndEnablesNativeAccess() throws IOException {
        try (JarFile jar = new JarFile(jar().toFile())) {
            Attributes attributes = jar.getManifest().getMainAttributes();

            assertThat(attributes.getValue(Attributes.Name.MAIN_CLASS)).isEqualTo(MzPeakConvert.class.getName());
            assertThat(attributes.getValue("Enable-Native-Access")).isEqualTo("ALL-UNNAMED");
        }
    }

    @Test
    void theJarHoldsOnlyTheFastutilClassesJmzMLNeeds() throws IOException {
        List<String> fastutil = new ArrayList<>();
        try (JarFile jar = new JarFile(jar().toFile())) {
            for (JarEntry entry : (Iterable<JarEntry>) jar.stream()::iterator) {
                if (entry.getName().startsWith("it/unimi/dsi/fastutil/") && entry.getName().endsWith(".class")) {
                    fastutil.add(entry.getName());
                }
            }
        }

        assertThat(fastutil).hasSize(FASTUTIL_CLASSES)
                .contains("it/unimi/dsi/fastutil/chars/CharOpenHashSet.class");
    }

    @Test
    void theJarStaysBelowItsSizeBound() throws IOException {
        assertThat(Files.size(jar())).isLessThan(MAX_SIZE);
    }

    private record Result(int exitStatus, String stdout, String stderr) {
    }

    private static Path jar() {
        String name = System.getProperty(JAR_PROPERTY);
        assertThat(name).as("system property " + JAR_PROPERTY + " (set by failsafe)").isNotNull();
        Path jar = Path.of(name);
        assertThat(jar).as("the -all jar; run through `mvn verify`").isRegularFile();
        return jar;
    }

    /** Runs {@code java -jar <jar> args} with this JVM's {@code java}; stdout and stderr go through files. */
    private Result javaJar(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-jar");
        command.add(jar().toString());
        command.addAll(List.of(args));
        Path stdout = Files.createTempFile(dir, "stdout", ".txt");
        Path stderr = Files.createTempFile(dir, "stderr", ".txt");
        Process process = new ProcessBuilder(command)
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("timed out after " + TIMEOUT_SECONDS + " s: " + command);
        }
        return new Result(process.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8),
                Files.readString(stderr, StandardCharsets.UTF_8));
    }
}
