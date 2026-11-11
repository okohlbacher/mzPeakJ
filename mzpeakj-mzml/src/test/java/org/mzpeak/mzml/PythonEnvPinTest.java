package org.mzpeak.mzml;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-5: the environment of the opt-in JZ-11 oracle ({@link PythonMzMLReaderTest}) is pinned. Runs in the default build
 * and needs no Python: it reads {@code src/test/resources/mzml/python/uv-requirements.txt}, the lock compiled with
 * hashes from {@code uv-requirements.in}, and the command {@link PythonMzMLReaderTest} runs.
 */
class PythonEnvPinTest {

    private static final Pattern PIN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*==[A-Za-z0-9.+!-]+");
    private static final Pattern HASH = Pattern.compile("--hash=sha256:[0-9a-f]{64}");

    @Test
    void everyRequirementOfTheLockIsAnExactVersionWithHashes() throws IOException {
        List<String> requirements = requirements("uv-requirements.txt");

        assertThat(requirements).isNotEmpty();
        for (String requirement : requirements) {
            String[] tokens = requirement.split("\\s+");
            assertThat(tokens[0]).as(requirement).matches(PIN);
            assertThat(tokens.length).as(requirement).isGreaterThan(1);
            for (int i = 1; i < tokens.length; i++) {
                assertThat(tokens[i]).as(requirement).matches(HASH);
            }
        }
    }

    @Test
    void theLockPinsTheTopLevelPackages() throws IOException {
        List<String> pins = new ArrayList<>();
        requirements("uv-requirements.txt").forEach(r -> pins.add(r.split("\\s+")[0]));

        assertThat(pins).contains("pyteomics==5.0.2", "psims==1.4.0");
        for (String name : List.of("pynumpress==", "numpy==", "lxml==")) {
            assertThat(pins).as(name).anySatisfy(p -> assertThat(p).startsWith(name));
        }
        for (String input : requirements("uv-requirements.in")) {
            String name = input.split("==")[0];
            assertThat(pins).as(input).anySatisfy(p -> assertThat(p).startsWith(name + "=="));
            if (input.contains("==")) {
                assertThat(pins).as(input).contains(input);
            }
        }
    }

    @Test
    void theOracleRunsOnTheGoldenCPython() {
        List<String> command = PythonMzMLReaderTest.command("uv");

        assertThat(command).containsSubsequence("run", "--no-project")
                .containsSequence("--python", "3.13.12")
                .containsSequence("--with-requirements",
                        PythonMzMLReaderTest.resource("uv-requirements.txt").toString())
                .endsWith("python", "-I", PythonMzMLReaderTest.resource("read_mzml.py").toString());
    }

    /** The requirements of the resource {@code name}: comments dropped, continuation lines joined, blanks skipped. */
    private static List<String> requirements(String name) throws IOException {
        List<String> requirements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : Files.readAllLines(PythonMzMLReaderTest.resource(name), StandardCharsets.UTF_8)) {
            String text = line.strip();
            if (text.startsWith("#")) {
                continue;
            }
            boolean continued = text.endsWith("\\");
            current.append(' ').append(continued ? text.substring(0, text.length() - 1) : text);
            if (!continued) {
                String requirement = current.toString().strip();
                if (!requirement.isEmpty()) {
                    requirements.add(requirement);
                }
                current.setLength(0);
            }
        }
        assertThat(current.toString().strip()).as("%s ends in a continuation", name).isEmpty();
        return requirements;
    }
}
