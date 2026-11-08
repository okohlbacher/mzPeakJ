package org.mzpeak.examples;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the {@code main} method of an example tool in a child JVM, so that its {@code System.exit} ends only the
 * child. The child gets this JVM's class path and its {@code -javaagent} and {@code --enable-native-access}
 * options (the coverage agent of the build and the zstd-jni opt-in), and the test's working directory.
 */
final class ForkedExampleMain {

    private static final long TIMEOUT_SECONDS = 120;

    /** Exit status and the complete stdout and stderr of one run. */
    record Result(int exitStatus, String stdout, String stderr) {
    }

    private ForkedExampleMain() {
    }

    /** Runs {@code tool.main(args)}; stdout and stderr go through files in {@code tmp}. */
    static Result run(Path tmp, Class<?> tool, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String option : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (option.startsWith("-javaagent:") || option.startsWith("--enable-native-access=")) {
                command.add(option);
            }
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(tool.getName());
        command.addAll(List.of(args));
        Path stdout = Files.createTempFile(tmp, "stdout", ".txt");
        Path stderr = Files.createTempFile(tmp, "stderr", ".txt");
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
