package org.mzpeak.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.MzPeakReader.Options;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RD-18 (D-15): {@link MzPeakReader#open} checks {@code metadata.version} of the manifest. A major version above 0
 * is refused unless {@link Options#withVersionCheck(boolean)} or {@code -Dmzpeak.reader.ignoreVersion=true} turns
 * the check off; a missing, non-string or unparseable version logs one WARNING and the dataset opens as 0.x. Edited
 * manifests are {@code @TempDir} copies of current {@code small.unpacked.mzpeak} with only {@code metadata.version}
 * changed. Before RD-18 every version opened silently.
 */
class FormatVersionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final String INDEX = "mzpeak_index.json";
    private static final String PROPERTY = "mzpeak.reader.ignoreVersion";
    private static final int SMALL_SPECTRA = 48;

    @TempDir
    Path tmp;

    // ---- (a) a newer major version is refused ----------------------------------------------

    @ParameterizedTest(name = "version = {0}")
    @ValueSource(strings = {"1.0.0", "2.13.7-rc.1+build.5", "10.0.0"})
    void aNewerMajorVersionIsRefused(String version) throws IOException {
        Path copy = copyWithVersion("\"" + version + "\"");

        List<LogRecord> records = captureReaderLog(() ->
                assertThatThrownBy(() -> MzPeakReader.open(copy)).isInstanceOf(MzPeakException.class)
                        .hasMessage(copy + ": mzPeak format version " + version + " is newer than the supported"
                                + " major version 0; open with Options.withVersionCheck(false) or"
                                + " -Dmzpeak.reader.ignoreVersion=true to try anyway"));
        assertThat(warnings(records)).isEmpty();
    }

    @Test
    void aNewerMajorVersionIsRefusedFromASourceAndTheSourceIsClosed() throws IOException {
        TestSources.Recording source = TestSources.recording(MzPeakSource.open(copyWithVersion("\"1.0.0\"")));

        assertThatThrownBy(() -> MzPeakReader.open(source, Options.defaults()))
                .isInstanceOf(MzPeakException.class).hasMessageContaining("mzPeak format version 1.0.0 is newer");
        assertThat(source.closeCount()).isEqualTo(1);
    }

    @Test
    void withoutTheCheckANewerMajorVersionOpens() throws IOException {
        Path copy = copyWithVersion("\"1.0.0\"");

        List<LogRecord> records = captureReaderLog(() -> {
            try (MzPeakReader reader = MzPeakReader.open(copy, Options.defaults().withVersionCheck(false))) {
                assertThat(reader.size()).isEqualTo(SMALL_SPECTRA);
                assertThat(reader.manifest().version()).isEqualTo("1.0.0");
                assertThat(reader.options().versionCheck()).isFalse();
                try (MzPeakReader original = MzPeakReader.open(SMALL)) {
                    // everything but the manifest's metadata line, which carries the edited version
                    List<String> read = TestSources.content(reader);
                    List<String> expected = TestSources.content(original);
                    assertThat(read.subList(2, read.size())).containsExactlyElementsOf(
                            expected.subList(2, expected.size()));
                }
            }
        });
        assertThat(warnings(records)).isEmpty();
    }

    @Test
    void withoutTheCheckAMissingVersionDoesNotWarn() {
        Path img = Fixtures.current("Example_Processed.img.mzpeak");

        List<LogRecord> records = captureReaderLog(() -> {
            try (MzPeakReader reader = MzPeakReader.open(img, Options.defaults().withVersionCheck(false))) {
                assertThat(reader.size()).isPositive();
            }
        });
        assertThat(warnings(records)).isEmpty();
    }

    @Test
    void theSystemPropertyTurnsTheCheckOffInDefaults() throws IOException {
        Path copy = copyWithVersion("\"1.0.0\"");
        String saved = System.getProperty(PROPERTY);
        try {
            System.setProperty(PROPERTY, "true");
            assertThat(Options.defaults().versionCheck()).isFalse();
            try (MzPeakReader reader = MzPeakReader.open(copy)) {
                assertThat(reader.size()).isEqualTo(SMALL_SPECTRA);
                assertThat(reader.options().versionCheck()).isFalse();
            }
            // an instance made earlier keeps its flag: the property is read when defaults() is called
            System.setProperty(PROPERTY, "false");
            assertThat(Options.defaults().versionCheck()).isTrue();
            assertThatThrownBy(() -> MzPeakReader.open(copy)).isInstanceOf(MzPeakException.class);
        } finally {
            System.setProperty(PROPERTY, saved == null ? "false" : saved);
        }
    }

    // ---- (b) a missing or unparseable version warns once and opens -------------------------

    @Test
    void aMissingVersionWarnsOnceAndOpens() {
        Path img = Fixtures.current("Example_Processed.img.mzpeak");

        List<LogRecord> records = captureReaderLog(() -> {
            try (MzPeakReader reader = MzPeakReader.open(img)) {
                assertThat(reader.manifest().version()).isNull();
                assertThat(reader.size()).isPositive();
            }
        });
        assertThat(warnings(records)).singleElement().extracting(LogRecord::getMessage)
                .isEqualTo(describe(img) + ": no parseable mzPeak format version (null); reading as 0.x");
    }

    @ParameterizedTest(name = "version = {0}")
    @CsvSource(delimiter = '|', value = {
            "'\"mzpeak-0.9\"'   | ('mzpeak-0.9')",
            "'\"0.9\"'          | ('0.9')",
            "'\"\"'             | ('')",
            "'\"v1.0.0\"'       | ('v1.0.0')",
            "'\"1.0.0.0\"'      | ('1.0.0.0')",
            "'\"1.0.0-\"'       | ('1.0.0-')",
            "'1'                | (null; JSON number)",
            "'null'             | (null; JSON null)",
            "'{}'               | (null; JSON object)",
            "'[\"1.0.0\"]'      | (null; JSON array)",
            "'true'             | (null; JSON boolean)"})
    void anUnparseableOrNonStringVersionWarnsOnceAndOpens(String version, String shown) throws IOException {
        Path copy = copyWithVersion(version);

        List<LogRecord> records = captureReaderLog(() -> {
            try (MzPeakReader reader = MzPeakReader.open(copy)) {
                assertThat(reader.size()).isEqualTo(SMALL_SPECTRA);
                assertThat(reader.options().versionCheck()).isTrue();
            }
        });
        assertThat(warnings(records)).singleElement().extracting(LogRecord::getMessage)
                .isEqualTo(copy + ": no parseable mzPeak format version " + shown + "; reading as 0.x");
    }

    @ParameterizedTest(name = "version = {0}")
    @ValueSource(strings = {"0.9.0", "0.10.3", "0.1.0-alpha", "0.9.0+build.7", "0.9.0-rc.1+sha.5114f85",
            "00.9.0"})
    void aMajorZeroVersionOpensWithoutWarning(String version) throws IOException {
        Path copy = copyWithVersion("\"" + version + "\"");

        List<LogRecord> records = captureReaderLog(() -> {
            try (MzPeakReader reader = MzPeakReader.open(copy)) {
                assertThat(reader.size()).isEqualTo(SMALL_SPECTRA);
            }
        });
        assertThat(warnings(records)).isEmpty();
    }

    // ---- (c) every fixture opens as before -------------------------------------------------

    static Stream<Path> fixtures() {
        return Stream.concat(Fixtures.CURRENT_NAMES.stream().map(Fixtures::current),
                Fixtures.LEGACY_NAMES.stream().map(Fixtures::legacy));
    }

    /**
     * The current fixtures carry {@code "0.9.0"} and open silently; Example_Processed.img and the legacy fixtures
     * carry no version and log the one WARNING. Either way the content is what the reader without the check reads.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void everyFixtureOpensAsBefore(Path fixture) {
        List<String> unchecked;
        try (MzPeakReader reader = MzPeakReader.open(fixture, Options.defaults().withVersionCheck(false))) {
            unchecked = TestSources.content(reader);
        }
        List<LogRecord> records = captureReaderLog(() -> {
            try (MzPeakReader reader = MzPeakReader.open(fixture)) {
                assertThat(TestSources.content(reader)).containsExactlyElementsOf(unchecked);
            }
        });

        boolean versioned = fixture.startsWith(Fixtures.CURRENT)
                && !fixture.getFileName().toString().equals("Example_Processed.img.mzpeak");
        if (versioned) {
            assertThat(warnings(records)).isEmpty();
        } else {
            assertThat(warnings(records)).singleElement().extracting(LogRecord::getMessage)
                    .isEqualTo(describe(fixture) + ": no parseable mzPeak format version (null); reading as 0.x");
        }
    }

    @Test
    void thirteenFixtures() {
        assertThat(fixtures()).hasSize(13);
    }

    // ---- Options -----------------------------------------------------------------------------

    @Test
    void theCheckIsOnByDefault() {
        assertThat(System.getProperty(PROPERTY)).isIn(null, "false");
        assertThat(Options.defaults().versionCheck()).isTrue();
        assertThat(Options.defaults().withVersionCheck(false).versionCheck()).isFalse();
        assertThat(Options.defaults().withVersionCheck(false).withVersionCheck(true))
                .isEqualTo(Options.defaults());
    }

    @Test
    void theFlagTakesPartInEqualityAndToString() {
        Options on = Options.defaults();
        Options off = on.withVersionCheck(false);

        assertThat(off).isNotEqualTo(on).isNotSameAs(on);
        assertThat(off.hashCode()).isNotEqualTo(on.hashCode());
        assertThat(off).isEqualTo(Options.defaults().withVersionCheck(false)).hasSameHashCodeAs(
                Options.defaults().withVersionCheck(false));
        assertThat(on.versionCheck()).isTrue();
        assertThat(on.toString()).contains("versionCheck=true");
        assertThat(off.toString()).contains("versionCheck=false");
        // the other withers keep the flag
        assertThat(off.withReconstructProfile(false).versionCheck()).isFalse();
        assertThat(off.withSignalMode(MzPeakReader.SignalMode.CENTROID).versionCheck()).isFalse();
        assertThat(off.withReconstructProfile(false).withSignalMode(MzPeakReader.SignalMode.PROFILE)
                .withVersionCheck(true)).isEqualTo(on.withReconstructProfile(false)
                .withSignalMode(MzPeakReader.SignalMode.PROFILE));
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static List<LogRecord> captureReaderLog(Runnable action) {
        return ArrayIndexTest.captureLog(MzPeakReader.class, action);
    }

    private static List<LogRecord> warnings(List<LogRecord> records) {
        return records.stream().filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue()).toList();
    }

    private static String describe(Path dataset) {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            return source.describe();
        }
    }

    /** A copy of current small.unpacked in the temporary directory, its {@code metadata.version} set to {@code json}. */
    private Path copyWithVersion(String json) throws IOException {
        ObjectNode index = (ObjectNode) MAPPER.readTree(SMALL.resolve(INDEX).toFile());
        ((ObjectNode) index.get("metadata")).set("version", MAPPER.readTree(json));
        Path copy = Files.createDirectories(tmp.resolve("small.unpacked.mzpeak"));
        try (Stream<Path> files = Files.list(SMALL)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (!f.getFileName().toString().equals(INDEX)) {
                    Files.copy(f, copy.resolve(f.getFileName().toString()));
                }
            }
        }
        Files.writeString(copy.resolve(INDEX), MAPPER.writeValueAsString(index), StandardCharsets.UTF_8);
        return copy;
    }
}
