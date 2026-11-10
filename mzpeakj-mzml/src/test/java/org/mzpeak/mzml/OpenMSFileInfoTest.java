package org.mzpeak.mzml;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakWriter;
import org.mzpeak.mzml.ModelAssert.Model;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * JZ-11, opt-in: OpenMS {@code FileInfo} as an external mzML validator and reader (mzML design 12.6). It shares no
 * code with jmzML. Runs only with {@code -Dmzpeak.openms.fileinfo=<FileInfo>}, the path of the OpenMS tool (not
 * {@code /opt/homebrew/bin/FileInfo}, which is leptonica's); without it every test is skipped. Every output of
 * direction A (the 13 mzPeak fixtures through {@link MzMLConversion#toMzML}) and of direction B (the 4 vendored mzML
 * through {@link MzMLConversion#toMzPeak} and back), each with zlib and without, is given to FileInfo with the
 * environment {@code OPENMS_DISABLE_UPDATE_CHECK=ON} and {@code LC_ALL=en_US.UTF-8}:
 * <ul>
 *   <li>{@code FileInfo -in <mzML> -v} prints 'Success - the file is valid!' (XSD 1.1.0). Its exit code is 0 either
 *       way, so the text is checked;
 *   <li>semantic validation (the PSI mapping rules and the CV): the output of small.mzML is semantically valid, like
 *       small.mzML itself, and the error lines of every B output are a subset of those FileInfo prints for its
 *       source (the converter passes such violations through, design 6.4; it never adds one). An A source is mzPeak,
 *       which FileInfo cannot read, so the distinct error lines of each A output are pinned in {@link #A_ERRORS}, each
 *       explained by the source's own content. Design 12.6 expected the outputs of the 8 small*.mzpeak fixtures to be
 *       semantically valid; they are not, for one reason the fixtures carry ({@link #PROCESSING_METHOD});
 *   <li>{@code FileInfo -in <mzML>} prints 'Number of spectra: N' and 'Number of chromatograms: M' equal to the
 *       numbers of MS plus wavelength spectra and of chromatograms of the source model (no line counts as 0).
 * </ul>
 */
class OpenMSFileInfoTest {

    private static final String PROPERTY = "mzpeak.openms.fileinfo";
    private static final long TIMEOUT_MINUTES = 10;
    private static final String VALID = "Success - the file is valid!";
    private static final String SEMANTICALLY_VALID = "Success - the file is semantically valid!";
    private static final Pattern SPECTRA = Pattern.compile("(?m)^Number of spectra: (\\d+)$");
    private static final Pattern CHROMATOGRAMS = Pattern.compile("(?m)^Number of chromatograms: (\\d+)$");

    /**
     * Every mzPeak fixture made by the Rust converter carries its own data processing {@code mzpeak_conversion1}
     * whose one processing method holds only the userParam 'conversion options', no CV term.
     */
    static final String PROCESSING_METHOD = "Error: Violated mapping rule 'processingmethod_must' at element"
            + " '/mzML/dataProcessingList/dataProcessing/processingMethod', 1 term(s) should be present, 0 found!";
    /** has_uv (from TOFsulfas): its fileContent repeats a term, as TOFsulfas' does. */
    private static final String FILE_CONTENT = "Error: Violated mapping rule 'filecontent_may' number of term repeats"
            + " at element '/mzML/fileDescription/fileContent'";
    /** has_uv (from TOFsulfas): source files without checksum and nativeID format terms, as in TOFsulfas. */
    private static final String SOURCE_FILE = "Error: Violated mapping rule 'sourcefile_must' at element"
            + " '/mzML/fileDescription/sourceFileList/sourceFile', 3 term(s) should be present, 1 found!";
    /** diaPASEF.ref and .grid: the ion mobility arrays, which the CV gives no binary data type (design 6.4). */
    private static final List<String> ION_MOBILITY = List.of(
            "Error: Binary data array of type 'MS:1003006 ! mean inverse reduced ion mobility array' cannot have the"
                    + " value type 'MS:1000523 ! 64-bit float'.",
            "Error: Value-type required, but not given (xsd:decimal): 'MS:1003006 - mean inverse reduced ion mobility"
                    + " array' value='' at element '/mzML/run/spectrumList/spectrum/binaryDataArrayList/binaryDataArray'",
            // the fixtures name MS:1002815 by its older name
            "Error: Name of CV term not correct: 'MS:1002815 - inverse reduced ion mobility drift time' should be"
                    + " 'inverse reduced ion mobility'",
            // the TIC and BPC of the fixtures have no intensity unit
            "Error: CV term must have a unit: MS:1000515 - intensity array");
    /** Example_Processed: params the fixture stores without value or unit. */
    private static final List<String> EXAMPLE_PROCESSED = List.of(
            "Error: Value-type required, but not given (xsd:string): 'MS:1000799 - custom unreleased software tool'"
                    + " value='' at element '/mzML/softwareList/software'",
            "Error: CV term must have a unit: MS:1000835 - matrix solution concentration",
            "Error: CV term must have a unit: MS:1000844 - focus diameter x",
            "Error: CV term must have a unit: MS:1000845 - focus diameter y",
            "Error: CV term must have a unit: MS:1000846 - pulse energy",
            "Error: CV term must have a unit: MS:1000847 - pulse duration",
            "Error: CV term must have a unit: MS:1000848 - attenuation");

    /** The distinct semantic error lines of the output of each A source, keyed as in {@link #outputs()}. */
    static final Map<String, Set<String>> A_ERRORS = aErrors();

    private static Map<String, Set<String>> aErrors() {
        Map<String, Set<String>> errors = new LinkedHashMap<>();
        for (String layout : List.of("current", "legacy")) {
            for (String name : List.of("small.mzpeak", "small.numpress.mzpeak", "small.unpacked.mzpeak",
                    "small.chunked.mzpeak")) {
                errors.put("A " + layout + " " + name, Set.of(PROCESSING_METHOD));
            }
            errors.put("A " + layout + " has_uv.mzpeak", Set.of(PROCESSING_METHOD, FILE_CONTENT, SOURCE_FILE));
        }
        for (String name : List.of("diaPASEF.ref.mzpeak", "diaPASEF.grid.mzpeak")) {
            Set<String> e = new TreeSet<>(ION_MOBILITY);
            e.add(PROCESSING_METHOD);
            errors.put("A current " + name, e);
        }
        Set<String> e = new TreeSet<>(EXAMPLE_PROCESSED);
        e.add(PROCESSING_METHOD);
        errors.put("A current Example_Processed.img.mzpeak", e);
        return errors;
    }

    private static Path fileInfo;

    @TempDir
    Path dir;

    @BeforeAll
    static void fileInfoIsGiven() {
        String property = System.getProperty(PROPERTY, "");
        assumeTrue(!property.isBlank(), "-D" + PROPERTY + "=<FileInfo> not set");
        fileInfo = Path.of(property);
        assertThat(fileInfo).as("-D%s", PROPERTY).isExecutable();
    }

    /** Every source, keyed 'A current|legacy name' or 'B name', with zlib and without. */
    static Stream<Arguments> outputs() {
        List<Arguments> cases = new ArrayList<>();
        for (boolean zlib : List.of(true, false)) {
            Fixtures.CURRENT_NAMES.forEach(n -> cases.add(arguments("A current " + n, zlib)));
            Fixtures.LEGACY_NAMES.forEach(n -> cases.add(arguments("A legacy " + n, zlib)));
            MzMLFixtures.NAMES.forEach(n -> cases.add(arguments("B " + n, zlib)));
        }
        assertThat(cases).hasSize(2 * (13 + 4));
        assertThat(A_ERRORS.keySet()).containsExactlyInAnyOrderElementsOf(cases.stream()
                .map(c -> (String) c.get()[0]).filter(s -> s.startsWith("A ")).collect(Collectors.toSet()));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, zlib {1}")
    @MethodSource("outputs")
    void fileInfoValidatesAndCountsTheOutput(String source, boolean zlib) throws IOException, InterruptedException {
        Path mzml = dir.resolve("out.mzML");
        Model model;
        Set<String> sourceErrors = null;
        if (source.startsWith("A ")) {
            String[] parts = source.split(" ", 3);
            Path fixture = parts[1].equals("current") ? Fixtures.current(parts[2]) : Fixtures.legacy(parts[2]);
            model = Model.load(fixture);
            MzMLConversion.toMzML(fixture, mzml, zlib);
        } else {
            Path input = MzMLFixtures.extract(source.substring(2), Files.createDirectories(dir.resolve("source")));
            String inputValidation = fileInfo("-in", input.toString(), "-v");
            assertThat(inputValidation).as(inputValidation).contains(VALID);
            sourceErrors = errors(inputValidation);
            model = MzMLFixpointTest.mzmlModel(input);
            Path mzpeak = dir.resolve("b.mzpeak");
            MzMLConversion.toMzPeak(input, mzpeak, false, MzPeakWriter.Options.defaults());
            MzMLConversion.toMzML(mzpeak, mzml, zlib);
        }
        MzMLAssert.valid(mzml);

        String validation = fileInfo("-in", mzml.toString(), "-v");
        assertThat(validation).as(validation).contains(VALID);
        Set<String> errors = errors(validation);
        boolean semantic = validation.contains(SEMANTICALLY_VALID);
        System.out.println("FileInfo " + source + ", zlib " + zlib + ": semantically valid " + semantic
                + errors.stream().map(l -> System.lineSeparator() + "  " + l).collect(Collectors.joining()));
        assertThat(semantic).as(validation).isEqualTo(errors.isEmpty());
        if (sourceErrors == null) {
            assertThat(errors).as(validation).containsExactlyInAnyOrderElementsOf(A_ERRORS.get(source));
        } else {
            assertThat(sourceErrors).as("the source's errors").containsAll(errors);
            if (source.equals("B " + MzMLFixtures.SMALL)) {
                assertThat(sourceErrors).isEmpty();
                assertThat(semantic).as(validation).isTrue();
            }
        }

        String info = fileInfo("-in", mzml.toString());
        assertThat(Map.of("spectra", count(SPECTRA, info), "chromatograms", count(CHROMATOGRAMS, info))).as(info)
                .isEqualTo(Map.of("spectra", (long) model.spectrumList().size() + model.wavelengthSpectra().size(),
                        "chromatograms", (long) model.chromatograms().size()));
    }

    /** The distinct error lines of a FileInfo validation. */
    private static Set<String> errors(String validation) {
        return validation.lines().filter(l -> l.startsWith("Error")).collect(Collectors.toCollection(TreeSet::new));
    }

    /** The number of the one line {@code pattern} matches in {@code info}; 0 when none does. */
    private static long count(Pattern pattern, String info) {
        Matcher m = pattern.matcher(info);
        if (!m.find()) {
            return 0;
        }
        long n = Long.parseLong(m.group(1));
        assertThat(m.find()).as("one '%s' line", pattern).isFalse();
        return n;
    }

    /** Runs FileInfo with {@code args} and returns its stdout and stderr together. */
    private String fileInfo(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(fileInfo.toString()));
        command.addAll(List.of(args));
        Path log = Files.createTempFile(dir, "fileinfo", ".txt");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        builder.environment().put("OPENMS_DISABLE_UPDATE_CHECK", "ON");
        builder.environment().put("LC_ALL", "en_US.UTF-8");
        Process process = builder.start();
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("timed out after " + TIMEOUT_MINUTES + " min: " + command);
        }
        return Files.readString(log, StandardCharsets.UTF_8);
    }
}
