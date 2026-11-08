package org.mzpeak.io;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the {@code main} method of a class on the test class path in a child JVM. The child gets this JVM's class
 * path and its {@code -javaagent} and {@code --enable-native-access} options (the coverage agent of the build, so that
 * the child's coverage reaches the report, and the zstd-jni opt-in), then the caller's JVM options, and the test's
 * working directory. Its stdout and stderr go to files in a directory of the caller.
 */
public final class ChildJvm {

    private static final long TIMEOUT_SECONDS = 300;

    /** Exit status and the complete stdout and stderr of one run. */
    public record Result(int exitStatus, String stdout, String stderr) {
    }

    /** A started child; {@link #await} waits for it. */
    public static final class Run {
        private final List<String> command;
        private final Process process;
        private final Path stdout;
        private final Path stderr;

        private Run(List<String> command, Process process, Path stdout, Path stderr) {
            this.command = command;
            this.process = process;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        /** Waits for the child to end; throws if it runs longer than the timeout, after killing it. */
        public Result await() throws IOException, InterruptedException {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("timed out after " + TIMEOUT_SECONDS + " s: " + command);
            }
            return new Result(process.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8),
                    Files.readString(stderr, StandardCharsets.UTF_8));
        }
    }

    private ChildJvm() {
    }

    /** Starts {@code main.main(args)} with {@code jvmOptions}; stdout and stderr go through files in {@code logs}. */
    public static Run start(Path logs, List<String> jvmOptions, Class<?> main, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String option : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (option.startsWith("-javaagent:") || option.startsWith("--enable-native-access=")) {
                command.add(option);
            }
        }
        command.addAll(jvmOptions);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(main.getName());
        command.addAll(List.of(args));
        Path stdout = Files.createTempFile(logs, "stdout", ".txt");
        Path stderr = Files.createTempFile(logs, "stderr", ".txt");
        Process process = new ProcessBuilder(command)
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        return new Run(command, process, stdout, stderr);
    }

    /** Runs {@code main.main(args)} with {@code jvmOptions} and waits for it. */
    public static Result run(Path logs, List<String> jvmOptions, Class<?> main, String... args)
            throws IOException, InterruptedException {
        return start(logs, jvmOptions, main, args).await();
    }
}
