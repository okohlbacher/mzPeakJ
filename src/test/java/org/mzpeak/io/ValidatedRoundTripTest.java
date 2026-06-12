package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Round-trip validation: reads each corpus file, writes it back with {@link MzPeakWriter}, and
 * passes the output through the {@code mzpeak-validate} Python validator CLI.
 *
 * <p><strong>Disabled by default.</strong> Enable with {@code -Dmzpeak.roundtrip=true}. The
 * corpus directory must also be present (same location as {@link CorpusTest}). Validator command
 * defaults to {@code mzpeak-validate} and can be overridden with
 * {@code -Dmzpeak.validator.command=<path>}.
 *
 * <p>Files larger than 256 MB are skipped to avoid OOM when accumulating all spectra for the
 * write step.
 *
 * <p>To run locally:
 * <pre>
 *   pip install git+https://github.com/okohlbacher/mzPeakValidator.git
 *   mvn test -Dmzpeak.roundtrip=true [-Dmzpeak.corpus.dir=/path/to/corpus]
 * </pre>
 */
@EnabledIfSystemProperty(named = "mzpeak.roundtrip", matches = "true")
class ValidatedRoundTripTest {

    /** Files larger than this are skipped (OOM risk when buffering all spectra). */
    private static final long MAX_FILE_BYTES = 256L * 1024 * 1024;

    /** Full validation (no --quick) for files under this size; above it --quick is used. */
    private static final long FULL_VALIDATION_THRESHOLD = 5L * 1024 * 1024;

    static Stream<Path> corpusFilesForRoundTrip() throws IOException {
        return CorpusTest.corpusFiles().filter(p -> {
            try {
                return approximateSize(p) <= MAX_FILE_BYTES;
            } catch (IOException e) {
                return false;
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpusFilesForRoundTrip")
    void roundTrip_validatesClean(Path src, @TempDir Path tmpDir) throws Exception {
        String validatorCmd = System.getProperty("mzpeak.validator.command", "mzpeak-validate");
        assumeThat(validatorAvailable(validatorCmd))
                .as("mzpeak-validate not found; install it or set -Dmzpeak.validator.command=")
                .isTrue();

        // 1. Read source — buffer spectra + chromatograms for write step
        List<Spectrum> spectra = new ArrayList<>();
        List<Chromatogram> chromatograms;
        FileMetadata meta;
        long firstNonEmptyIndex = -1;
        double[] firstMz = null;
        double[] firstIntensity = null;
        int srcCount;
        try (MzPeakReader r = MzPeakReader.open(src)) {
            srcCount = r.size();
            for (Spectrum s : r) {
                spectra.add(s);
                if (firstNonEmptyIndex < 0 && s.mz().length > 0) {
                    // Snapshot the first non-empty spectrum's arrays before the reader closes.
                    // Note: IMS files may have no data for spectrum 0 (chunks start at a later index).
                    firstNonEmptyIndex = s.index();
                    firstMz = s.mz().clone();
                    firstIntensity = s.intensity().clone();
                }
            }
            chromatograms = new ArrayList<>(r.chromatograms());
            meta = r.fileMetadata();
        }

        // 2. Write to a directory inside tmpDir
        Path outDir = tmpDir.resolve("output.mzpeak");
        MzPeakWriter.writeDirectory(outDir, spectra, chromatograms, meta);

        // 3. Validate the written output
        Path reportJson = tmpDir.resolve("report.json");
        int exit = runValidator(validatorCmd, outDir, reportJson);
        String errors = exit != 0 ? parseValidatorErrors(reportJson) : "";
        assertThat(exit)
                .as("mzpeak-validate exit code for %s\n%s", src.getFileName(), errors)
                .isEqualTo(0);

        // 4. Re-read and verify round-trip fidelity
        try (MzPeakReader r2 = MzPeakReader.open(outDir)) {
            assertThat(r2.size())
                    .as("spectrum count preserved for %s", src.getFileName())
                    .isEqualTo(srcCount);
            assertThat(r2.chromatograms().size())
                    .as("chromatogram count preserved for %s", src.getFileName())
                    .isEqualTo(chromatograms.size());
            if (srcCount > 0 && firstNonEmptyIndex >= 0) {
                Spectrum first = r2.getSpectrum(firstNonEmptyIndex).orElseThrow();
                assertThat(first.mz())
                        .as("spectrum[%d] m/z round-trips exactly for %s",
                                firstNonEmptyIndex, src.getFileName())
                        .isEqualTo(firstMz);
                assertThat(first.intensity())
                        .as("spectrum[%d] intensity round-trips exactly for %s",
                                firstNonEmptyIndex, src.getFileName())
                        .isEqualTo(firstIntensity);
            }
        }
    }

    // ---- subprocess + validator helpers --------------------------------------------------

    private static boolean validatorAvailable(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--help")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            p.waitFor(10, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int runValidator(String cmd, Path archive, Path reportJson) throws Exception {
        long size;
        try { size = approximateSize(archive); } catch (IOException e) { size = 0L; }
        List<String> args = new ArrayList<>(Arrays.asList(
                cmd, archive.toString(),
                "--json", reportJson.toString()));
        if (size >= FULL_VALIDATION_THRESHOLD) {
            // Insert --quick before --json for large files
            args.add(2, "--quick");
        }
        ProcessBuilder pb = new ProcessBuilder(args).redirectErrorStream(true);
        Process p = pb.start();
        // Drain stdout/stderr to prevent the child from blocking on a full pipe buffer
        String output = new String(p.getInputStream().readAllBytes());
        boolean done = p.waitFor(120, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new AssertionError(
                    "mzpeak-validate timed out after 120 s for " + archive + "\nOutput: " + output);
        }
        return p.exitValue();
    }

    private static String parseValidatorErrors(Path reportJson) {
        if (!Files.exists(reportJson)) return "(no report file written)";
        try {
            JsonNode root = new ObjectMapper().readTree(reportJson.toFile());
            JsonNode findings = root.path("findings");
            if (!findings.isArray()) return root.toPrettyString();
            StringBuilder sb = new StringBuilder();
            for (JsonNode f : findings) {
                // Validator uses "level" field (not "severity"); ruleId identifies the rule.
                if ("error".equalsIgnoreCase(f.path("level").asText(""))) {
                    sb.append("  [").append(f.path("ruleId").asText(f.path("rule").asText("?"))).append("] ")
                      .append(f.path("message").asText(f.toString())).append('\n');
                }
            }
            return sb.isEmpty() ? "(verdict=" + root.path("verdict").asText() + ")" : sb.toString();
        } catch (Exception e) {
            return "(failed to parse report: " + e.getMessage() + ")";
        }
    }

    private static long approximateSize(Path path) throws IOException {
        if (Files.isRegularFile(path)) return Files.size(path);
        try (var walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); } catch (IOException e) { return 0L; }
                    })
                    .sum();
        }
    }
}
