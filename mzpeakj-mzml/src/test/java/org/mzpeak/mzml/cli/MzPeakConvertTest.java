package org.mzpeak.mzml.cli;

import ms.numpress.MSNumpress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.ContentDigest;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakManifest;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.io.MzPeakSource;
import org.mzpeak.io.MzPeakWriter;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.mzml.MzMLAssert;
import org.mzpeak.mzml.MzMLConversion;
import org.mzpeak.mzml.MzMLFixtures;
import org.mzpeak.mzml.MzMLReader;
import org.mzpeak.mzml.MzMLWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * JZ-10: {@link MzPeakConvert#run} in this JVM, with its output in byte arrays (mzML design 8.2, 9). (a) tiny.mzML to
 * mzPeak; (b) small.mzpeak to mzML with and without zlib, (c) has_uv.mzpeak to mzML, each printing its counts and the
 * conversion's warnings and writing what {@link MzMLConversion} writes; (d) the mzPeak output options, each written as
 * {@link MzMLConversion#toMzPeak} writes with the {@link MzPeakWriter.Options} it names, a directory input and names
 * in upper case; (e) every usage error, exit 2 and nothing written; (f) failures, exit 1 and no output; (g)
 * {@code --help}. {@link MzPeakConvertProcessTest} runs {@code main} in a child JVM.
 */
class MzPeakConvertTest {

    private static final String NO_COMPRESSION =
            "<cvParam cvRef=\"MS\" accession=\"MS:1000576\" name=\"no compression\" value=\"\"/>";
    private static final String PIC_COMPRESSION = "<cvParam cvRef=\"MS\" accession=\"MS:1002313\""
            + " name=\"MS-Numpress positive integer compression\" value=\"\"/>";

    @TempDir
    Path dir;

    /** Exit code, stdout and stderr of one {@link MzPeakConvert#run}. */
    private record Run(int code, String out, String err) {

        List<String> errLines() {
            return err.lines().toList();
        }
    }

    // ---- (a) mzML -> mzPeak ---------------------------------------------------------------------------------------

    @Test
    void tinyToMzPeakPrintsItsCountsAndTheWarningsOfTheConversion() throws IOException {
        Path tiny = MzMLFixtures.extract(MzMLFixtures.TINY, dir);
        Path target = dir.resolve("x.mzpeak");

        Run r = run(tiny.toString(), target.toString());

        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out().lines()).containsExactly(
                "wrote " + target + ": 4 spectra, 0 wavelength spectra, 2 chromatograms");
        assertThat(r.errLines().subList(0, 6)).containsExactlyElementsOf(warnings(MzMLReader.LIMITS));
        MzMLConversion.Result reference = MzMLConversion.toMzPeak(tiny, dir.resolve("reference.mzpeak"), false,
                MzPeakWriter.Options.defaults());
        assertThat(r.errLines()).hasSizeGreaterThan(6).containsExactlyElementsOf(warnings(reference.warnings()));
        assertThat(Files.isRegularFile(target)).isTrue();
        assertThat(digests(target)).isEqualTo(digests(dir.resolve("reference.mzpeak")));
    }

    // ---- (b), (c) mzPeak -> mzML ----------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void smallMzPeakToMzMLWithAndWithoutZlib(boolean zlib) throws IOException {
        Path small = Fixtures.current("small.mzpeak");
        Path target = dir.resolve("x.mzML");

        Run r = zlib ? run(small.toString(), target.toString()) : run("--no-zlib", small.toString(), target.toString());

        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out().lines()).containsExactly(
                "wrote " + target + ": 48 spectra, 0 wavelength spectra, 1 chromatograms");
        assertThat(r.errLines().subList(0, 2)).containsExactlyElementsOf(warnings(MzMLWriter.LIMITS));
        Path reference = dir.resolve("reference.mzML");
        assertThat(r.errLines())
                .containsExactlyElementsOf(warnings(MzMLConversion.toMzML(small, reference, zlib).warnings()));
        MzMLAssert.valid(target);
        assertThat(Files.readAllBytes(target)).isEqualTo(Files.readAllBytes(reference));
        String mzml = Files.readString(target, UTF_8);
        assertThat(mzml.contains("accession=\"MS:1000574\"")).as("zlib compression").isEqualTo(zlib);
        assertThat(mzml.contains("accession=\"MS:1000576\"")).as("no compression").isEqualTo(!zlib);
    }

    @Test
    void hasUvToMzMLCountsItsWavelengthSpectra() throws IOException {
        Path target = dir.resolve("uv.mzML");

        Run r = run(Fixtures.current("has_uv.mzpeak").toString(), target.toString());

        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out().lines()).containsExactly(
                "wrote " + target + ": 212 spectra, 520 wavelength spectra, 2 chromatograms");
        assertThat(r.errLines()).startsWith(warnings(MzMLWriter.LIMITS).toArray(String[]::new))
                .allMatch(line -> line.startsWith("warning: "));
        MzMLAssert.valid(target);
    }

    // ---- (d) the mzPeak output options, a directory input, names in upper case ------------------------------------

    static Stream<Arguments> mzPeakOptions() {
        MzPeakWriter.Options defaults = MzPeakWriter.Options.defaults();
        MzPeakWriter.Options packed = defaults.withMetadataLayout(MetadataLayout.PACKED);
        MzPeakWriter.Options slof = defaults.withSignalEncoding(SignalEncoding.NUMPRESS);
        MzPeakWriter.Options pic = slof.withNumpressIntensity(NumpressIntensityEncoding.PIC);
        return Stream.of(
                arguments(List.of("--layout", "split"), defaults, packed),
                arguments(List.of("--layout", "packed"), packed, defaults),
                arguments(List.of("--numpress-slof"), slof, defaults),
                arguments(List.of("--numpress-pic"), pic, slof),
                arguments(List.of("--row-group-size", "1048576"), defaults.withRowGroupSize(1048576), null));
    }

    /**
     * Each option writes what {@link MzMLConversion#toMzPeak} writes with the options it names, which differ from
     * {@code other}'s output where tiny can show it (a row-group size cannot: tiny's members are one row group;
     * {@link #theRowGroupSizeSplitsSmallsMembersIntoRowGroups} shows it).
     */
    @ParameterizedTest
    @MethodSource("mzPeakOptions")
    void eachMzPeakOptionWritesWhatItsWriterOptionsWrite(List<String> options, MzPeakWriter.Options expected,
                                                       MzPeakWriter.Options other) throws IOException {
        Path tiny = MzMLFixtures.extract(MzMLFixtures.TINY, dir);
        Path target = dir.resolve("x.mzpeak");
        List<String> args = new ArrayList<>(options);
        args.addAll(List.of(tiny.toString(), target.toString()));

        Run r = run(args.toArray(String[]::new));

        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out().lines()).containsExactly(
                "wrote " + target + ": 4 spectra, 0 wavelength spectra, 2 chromatograms");
        Path reference = dir.resolve("reference.mzpeak");
        MzMLConversion.toMzPeak(tiny, reference, false, expected);
        assertThat(digests(target)).isEqualTo(digests(reference));
        if (other != null) {
            Path differs = dir.resolve("other.mzpeak");
            MzMLConversion.toMzPeak(tiny, differs, false, other);
            assertThat(digests(target)).isNotEqualTo(digests(differs));
        }
    }

    static Stream<Arguments> rowGroupSizes() {
        MzPeakWriter.Options defaults = MzPeakWriter.Options.defaults();
        MzPeakWriter.Options picPacked = defaults.withSignalEncoding(SignalEncoding.NUMPRESS)
                .withNumpressIntensity(NumpressIntensityEncoding.PIC).withMetadataLayout(MetadataLayout.PACKED);
        return Stream.of(
                arguments(List.of("--row-group-size", "65536"), defaults.withRowGroupSize(65536), defaults),
                arguments(List.of("--row-group-size", "65536", "--numpress-pic", "--layout", "packed"),
                        picPacked.withRowGroupSize(65536), picPacked));
    }

    /**
     * small's spectra_data is larger than 65536 bytes: {@code --row-group-size 65536} writes what
     * {@link MzMLConversion#toMzPeak} writes with that size, its spectra_data in more than one row group, unlike
     * {@code other}, the same options without a row-group size.
     */
    @ParameterizedTest
    @MethodSource("rowGroupSizes")
    void theRowGroupSizeSplitsSmallsMembersIntoRowGroups(List<String> options, MzPeakWriter.Options expected,
                                                        MzPeakWriter.Options other) throws IOException {
        Path small = MzMLFixtures.extract(MzMLFixtures.SMALL, dir);
        Path target = dir.resolve("x.mzpeak");
        List<String> args = new ArrayList<>(options);
        args.addAll(List.of(small.toString(), target.toString()));

        Run r = run(args.toArray(String[]::new));

        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out().lines()).containsExactly(
                "wrote " + target + ": 48 spectra, 0 wavelength spectra, 1 chromatograms");
        Path reference = dir.resolve("reference.mzpeak");
        MzMLConversion.toMzPeak(small, reference, false, expected);
        Path differs = dir.resolve("other.mzpeak");
        MzMLConversion.toMzPeak(small, differs, false, other);
        Map<String, ContentDigest> written = digests(target, 48);
        Map<String, ContentDigest> without = digests(differs, 48);
        assertThat(written).isEqualTo(digests(reference, 48)).isNotEqualTo(without);
        assertThat(rowGroups(written.get("spectra_data.parquet"))).isGreaterThan(1);
        assertThat(rowGroups(without.get("spectra_data.parquet"))).isEqualTo(1);
    }

    /** --directory writes an unpacked directory, which converts back to mzML as a directory input of any name. */
    @Test
    void directoryOutputAndDirectoryInput() throws IOException {
        Path tiny = MzMLFixtures.extract(MzMLFixtures.TINY, dir);
        Path unpacked = dir.resolve("x.mzpeak");

        Run r = run("--directory", tiny.toString(), unpacked.toString());

        assertThat(r.code()).as(r.err()).isZero();
        assertThat(r.out().lines()).containsExactly(
                "wrote " + unpacked + ": 4 spectra, 0 wavelength spectra, 2 chromatograms");
        assertThat(Files.isDirectory(unpacked)).isTrue();
        Path archive = dir.resolve("reference.mzpeak");
        MzMLConversion.toMzPeak(tiny, archive, false, MzPeakWriter.Options.defaults());
        assertThat(digests(unpacked)).isEqualTo(digests(archive));

        Path renamed = Files.move(unpacked, dir.resolve("unpacked"));
        Path back = dir.resolve("back.mzML");
        Run toMzML = run(renamed.toString(), back.toString());

        assertThat(toMzML.code()).as(toMzML.err()).isZero();
        assertThat(toMzML.out().lines()).containsExactly(
                "wrote " + back + ": 4 spectra, 0 wavelength spectra, 2 chromatograms");
        assertThat(toMzML.errLines()).startsWith(warnings(MzMLWriter.LIMITS).toArray(String[]::new));
        MzMLAssert.valid(back);
    }

    @Test
    void namesAreMatchedIgnoringCase() throws IOException {
        Path upper = Files.move(MzMLFixtures.extract(MzMLFixtures.TINY, dir), dir.resolve("CASE.MZML"));
        Path mzpeak = dir.resolve("CASE.MZPEAK");
        Path mzml = dir.resolve("BACK.mzml");

        Run toMzPeak = run(upper.toString(), mzpeak.toString());
        Run toMzML = run("--no-zlib", mzpeak.toString(), mzml.toString());

        assertThat(toMzPeak.code()).as(toMzPeak.err()).isZero();
        assertThat(toMzPeak.out().lines()).containsExactly(
                "wrote " + mzpeak + ": 4 spectra, 0 wavelength spectra, 2 chromatograms");
        assertThat(toMzML.code()).as(toMzML.err()).isZero();
        assertThat(toMzML.out().lines()).containsExactly(
                "wrote " + mzml + ": 4 spectra, 0 wavelength spectra, 2 chromatograms");
        MzMLAssert.valid(mzml);
    }

    // ---- (e) usage errors -----------------------------------------------------------------------------------------

    /** Arguments and message; in both, {@code $dir} stands for the test's directory. */
    static Stream<Arguments> usageErrors() {
        String tiny = "$dir/tiny.mzML";
        String small = "$dir/small.mzpeak";
        String mzpeak = "$dir/x.mzpeak";
        String mzml = "$dir/x.mzML";
        return Stream.of(
                arguments(List.of("--bogus", tiny, mzpeak), "unknown option --bogus"),
                arguments(List.of(tiny, "-x", mzpeak), "unknown option -x"),
                arguments(List.of(), "missing <input> and <output>"),
                arguments(List.of(tiny), "missing <output>"),
                arguments(List.of(tiny, mzpeak, "$dir/y.mzpeak"), "extra argument $dir/y.mzpeak"),
                arguments(List.of(tiny, mzpeak, "--layout"), "--layout needs a value"),
                arguments(List.of("--row-group-size"), "--row-group-size needs a value"),
                arguments(List.of("--directory", tiny, "--directory", mzpeak), "--directory is given twice"),
                arguments(List.of("--layout", "split", "--layout", "packed", tiny, mzpeak), "--layout is given twice"),
                arguments(List.of("--no-zlib", tiny, mzpeak), "--no-zlib does not apply to mzPeak output"),
                arguments(List.of("--directory", small, mzml), "--directory does not apply to mzML output"),
                arguments(List.of("--layout", "packed", small, mzml), "--layout does not apply to mzML output"),
                arguments(List.of("--numpress-slof", small, mzml), "--numpress-slof does not apply to mzML output"),
                arguments(List.of("--numpress-pic", small, mzml), "--numpress-pic does not apply to mzML output"),
                arguments(List.of("--no-zlib", "--row-group-size", "1048576", small, mzml),
                        "--row-group-size does not apply to mzML output"),
                arguments(List.of("--numpress-slof", "--numpress-pic", tiny, mzpeak),
                        "--numpress-slof and --numpress-pic exclude each other"),
                arguments(List.of("--layout", "Packed", tiny, mzpeak), "--layout takes split or packed, not Packed"),
                arguments(List.of("--row-group-size", "1MB", tiny, mzpeak),
                        "--row-group-size takes a positive number of bytes, not 1MB"),
                arguments(List.of("--row-group-size", "0", tiny, mzpeak),
                        "--row-group-size takes a positive number of bytes, not 0"),
                arguments(List.of("--row-group-size", "-4096", tiny, mzpeak),
                        "--row-group-size takes a positive number of bytes, not -4096"),
                arguments(List.of("$dir/tiny.mzML.gz", mzpeak),
                        "$dir/tiny.mzML.gz: gunzip it first: jmzML 1.7.11 cannot read gzip-compressed mzML"),
                arguments(List.of("$dir/TINY.MZML.GZ", mzpeak),
                        "$dir/TINY.MZML.GZ: gunzip it first: jmzML 1.7.11 cannot read gzip-compressed mzML"),
                arguments(List.of("$dir/notes.txt", mzpeak), "$dir/notes.txt: unknown input type: expected a name"
                        + " ending in .mzML or .mzpeak, or a directory"),
                arguments(List.of("$dir/missing", mzml), "$dir/missing: unknown input type: expected a name"
                        + " ending in .mzML or .mzpeak, or a directory"),
                arguments(List.of(tiny, mzml), "$dir/x.mzML: the output of an mzML input must end in .mzpeak"),
                arguments(List.of("--directory", tiny, "$dir/x"),
                        "$dir/x: the output of an mzML input must end in .mzpeak"),
                arguments(List.of(small, mzpeak), "$dir/x.mzpeak: the output of an mzPeak input must end in .mzML"),
                arguments(List.of(tiny, "$dir/exists.mzpeak"), "$dir/exists.mzpeak exists: remove it first"),
                arguments(List.of("--directory", tiny, "$dir/empty.mzpeak"),
                        "$dir/empty.mzpeak exists: remove it first"),
                arguments(List.of(small, "$dir/exists.mzML"), "$dir/exists.mzML exists: remove it first"));
    }

    @ParameterizedTest
    @MethodSource("usageErrors")
    void eachUsageErrorExitsWithTwoAndWritesNothing(List<String> args, String message) throws IOException {
        Files.move(MzMLFixtures.extract(MzMLFixtures.TINY, dir), dir.resolve("tiny.mzML"));
        Files.copy(Fixtures.current("small.mzpeak"), dir.resolve("small.mzpeak"));
        Files.writeString(dir.resolve("notes.txt"), "not a dataset");
        Files.writeString(dir.resolve("exists.mzpeak"), "kept");
        Files.writeString(dir.resolve("exists.mzML"), "kept");
        Files.createDirectory(dir.resolve("empty.mzpeak"));
        Map<String, String> before = listing(dir);

        Run r = run(args.stream().map(this::inDir).toArray(String[]::new));

        assertThat(r.code()).isEqualTo(2);
        assertThat(r.out()).isEmpty();
        assertThat(r.errLines()).containsExactly("error: " + inDir(message), MzPeakConvert.USAGE);
        assertThat(listing(dir)).isEqualTo(before);
    }

    // ---- (f) failures ---------------------------------------------------------------------------------------------

    /** Guard 3 of the reader fails the conversion after the session wrote spectra; no target and no temp file stay. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aPicCountOf3e9FailsWithGuard3sMessageAndLeavesNoOutput(boolean directory) throws IOException {
        double[] counts = new double[15];
        for (int i = 0; i < 14; i++) {
            counts[i] = 15 - i;
        }
        counts[14] = 3e9;
        byte[] buffer = new byte[5 * counts.length];
        String pic = Base64.getEncoder().encodeToString(
                Arrays.copyOf(buffer, MSNumpress.encodePic(counts, counts.length, buffer)));
        Path file = tinyWith("pic.mzML", s -> intensity19(s, PIC_COMPRESSION, pic));
        Path out = Files.createDirectory(dir.resolve("out"));
        Path target = out.resolve("pic.mzpeak");

        Run r = directory ? run("--directory", file.toString(), target.toString())
                : run(file.toString(), target.toString());

        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).isEmpty();
        assertThat(r.errLines()).containsExactly("error: mzML " + file + ": spectrum #0 (scan=19): its MS:1000515"
                + " array (MS-Numpress PIC, MS:1002313) holds the count 3000000000, which jmzML 1.7.11 decodes as"
                + " -1294967296; counts of 2^31 and above are not supported");
        assertThat(listing(out)).isEmpty();
    }

    /** Tags that do not match fail while jmzML builds its index; xxindex prints its own lines to stdout then. */
    @Test
    void aMisspeltEndTagFailsWithJmzmlsMessage() throws IOException {
        Path file = tinyWith("misspelt.mzML", s -> replaceOnce(s, "</scanList>\n          <precursorList",
                "</scanLis>\n          <precursorList"));
        Path target = dir.resolve("misspelt.mzpeak");

        Run r = run(file.toString(), target.toString());

        assertThat(r.code()).isEqualTo(1);
        assertThat(r.errLines()).containsExactly(
                "error: mzML " + file + ": Internal stack of XML tags was corrupted!");
        assertThat(target).doesNotExist();
    }

    /** --numpress-slof cannot encode a negative intensity (MZ-23): the session fails and removes what it wrote. */
    @Test
    void aValueTheNumpressCodecCannotEncodeFailsTheMzPeakOutput() throws IOException {
        double[] intensities = new double[15];
        for (int i = 0; i < 15; i++) {
            intensities[i] = 15 - i;
        }
        intensities[3] = -1;
        ByteBuffer bytes = ByteBuffer.allocate(8 * 15).order(ByteOrder.LITTLE_ENDIAN);
        Arrays.stream(intensities).forEach(bytes::putDouble);
        String binary = Base64.getEncoder().encodeToString(bytes.array());
        Path file = tinyWith("negative.mzML", s -> intensity19(s, NO_COMPRESSION, binary));
        Path out = Files.createDirectory(dir.resolve("out"));

        Run r = run("--numpress-slof", file.toString(), out.resolve("negative.mzpeak").toString());

        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).isEmpty();
        assertThat(r.errLines()).containsExactly("error: spectrum 0: intensity -1.0 in spectra_peaks cannot be encoded"
                + " with Numpress SLOF, which takes only finite intensities >= 0; use SignalEncoding.POINT");
        assertThat(listing(out)).isEmpty();
    }

    @Test
    void inputsThatCannotBeReadFail() throws IOException {
        Path mzml = dir.resolve("missing.mzML");
        Path mzpeak = dir.resolve("missing.mzpeak");

        Run fromMzML = run(mzml.toString(), dir.resolve("x.mzpeak").toString());
        Run fromMzPeak = run(mzpeak.toString(), dir.resolve("x.mzML").toString());

        assertThat(fromMzML.code()).isEqualTo(1);
        assertThat(fromMzML.errLines()).containsExactly("error: mzML " + mzml + ": not a readable regular file");
        assertThat(fromMzPeak.code()).isEqualTo(1);
        assertThat(fromMzPeak.errLines()).containsExactly("error: No such mzPeak dataset: " + mzpeak);
        assertThat(listing(dir)).isEmpty();
    }

    /** The library reports a member it cannot open as an UncheckedIOException, which fails the conversion. */
    @Test
    void anUncheckedIOExceptionOfTheLibraryFailsTheConversion() throws IOException {
        Path dataset = Files.createDirectory(dir.resolve("broken.mzpeak"));
        try (Stream<Path> members = Files.list(Fixtures.current("small.unpacked.mzpeak"))) {
            for (Path member : members.toList()) {
                Files.copy(member, dataset.resolve(member.getFileName().toString()));
            }
        }
        Files.delete(dataset.resolve("spectra_metadata.parquet"));
        Files.createDirectory(dataset.resolve("spectra_metadata.parquet"));
        Path target = dir.resolve("broken.mzML");

        Run r = run(dataset.toString(), target.toString());

        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).isEmpty();
        assertThat(r.errLines()).containsExactly("error: Failed reading Parquet footer");
        assertThat(target).doesNotExist();
    }

    /** Any other RuntimeException fails the conversion with its class and message, not a stack trace. */
    @Test
    void anUnexpectedRuntimeExceptionFailsTheConversionWithAnErrorLine() throws IOException {
        Path tiny = MzMLFixtures.extract(MzMLFixtures.TINY, dir);

        Run r = run(command -> {
            throw new IllegalArgumentException("boom");
        }, tiny.toString(), dir.resolve("x.mzpeak").toString());

        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).isEmpty();
        assertThat(r.errLines()).containsExactly("error: java.lang.IllegalArgumentException: boom");
    }

    /** An OutOfMemoryError fails the conversion with advice, not a stack trace. */
    @Test
    void runningOutOfMemoryFailsTheConversionWithAdvice() throws IOException {
        Path small = Fixtures.current("small.mzpeak");

        Run r = run(command -> {
            throw new OutOfMemoryError("Java heap space");
        }, small.toString(), dir.resolve("x.mzML").toString());

        assertThat(r.code()).isEqualTo(1);
        assertThat(r.out()).isEmpty();
        assertThat(r.errLines()).containsExactly("error: out of memory converting " + small
                + "; rerun with a larger -Xmx (see --help)");
    }

    // ---- (g) --help -----------------------------------------------------------------------------------------------

    @Test
    void helpPrintsTheUsageTheLimitsAndTheMemoryRule() {
        Run r = run("--help");

        assertThat(r.code()).isZero();
        assertThat(r.err()).isEmpty();
        List<String> lines = r.out().lines().toList();
        assertThat(lines.get(0)).isEqualTo(MzPeakConvert.USAGE);
        assertThat(lines).containsSubsequence(indented(MzMLReader.LIMITS))
                .containsSubsequence(indented(MzMLWriter.LIMITS))
                .contains("  -Xmx >= expected mzML size + 13 KB per spectrum + 1 GB",
                        "  about 2.5 KB per spectrum: -Xmx2g per 300,000 spectra");
        assertThat(String.join("\n", lines)).contains("--no-zlib", "--directory", "--layout split|packed",
                "--numpress-slof", "--numpress-pic", "--row-group-size <bytes>", "gunzip it first");
        assertThat(run("--bogus", "x", "--help")).isEqualTo(r);
    }

    /** main exits through this: System.exit only for a non-zero code (MzPeakConvertProcessTest runs main). */
    @Test
    void exitHandsOnlyANonZeroCodeToTheExiter() {
        List<Integer> codes = new ArrayList<>();

        MzPeakConvert.exit(0, codes::add);
        assertThat(codes).isEmpty();

        MzPeakConvert.exit(2, codes::add);
        assertThat(codes).containsExactly(2);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream o = new PrintStream(out, true, UTF_8); PrintStream e = new PrintStream(err, true, UTF_8)) {
            code = MzPeakConvert.run(args, o, e);
        }
        return new Run(code, out.toString(UTF_8), err.toString(UTF_8));
    }

    /** {@link #run(String...)} with {@code convert} in place of the conversion. */
    private static Run run(Function<MzPeakConvert.Command, MzMLConversion.Result> convert, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream o = new PrintStream(out, true, UTF_8); PrintStream e = new PrintStream(err, true, UTF_8)) {
            code = MzPeakConvert.run(args, o, e, convert);
        }
        return new Run(code, out.toString(UTF_8), err.toString(UTF_8));
    }

    private static List<String> warnings(List<String> lines) {
        return lines.stream().map(line -> "warning: " + line).toList();
    }

    private static List<String> indented(List<String> lines) {
        return lines.stream().map(line -> "  " + line).toList();
    }

    private String inDir(String s) {
        return s.replace("$dir", dir.toString());
    }

    /** The content digest of every member the manifest of {@code dataset} lists, by name; the dataset has 4 spectra. */
    private static Map<String, ContentDigest> digests(Path dataset) {
        return digests(dataset, 4);
    }

    /** The content digest of every member the manifest of {@code dataset} lists, by name; it has {@code spectra}. */
    private static Map<String, ContentDigest> digests(Path dataset, int spectra) {
        Map<String, ContentDigest> digests = new TreeMap<>();
        try (MzPeakReader reader = MzPeakReader.open(dataset); MzPeakSource source = MzPeakSource.open(dataset)) {
            assertThat(reader.size()).isEqualTo(spectra);
            for (MzPeakManifest.Entry member : reader.manifest().files()) {
                digests.put(member.name(), ContentDigest.of(source, member.name()));
            }
        }
        return digests;
    }

    /** The row-group count of a Parquet member, from its digest's detail ({@code row_groups=N rows=...}). */
    private static int rowGroups(ContentDigest member) {
        assertThat(member.detail()).startsWith("row_groups=");
        return Integer.parseInt(member.detail().substring("row_groups=".length(), member.detail().indexOf(' ')));
    }

    /** Every path below {@code root}, relative, with the content of each regular file (ISO-8859-1). */
    private static Map<String, String> listing(Path root) throws IOException {
        Map<String, String> listing = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.filter(p -> !p.equals(root)).toList()) {
                listing.put(root.relativize(p).toString(), Files.isRegularFile(p) ? Files.readString(p, ISO_8859_1)
                        : "<directory>");
            }
        }
        return listing;
    }

    /** tiny, edited by {@code edit}, as {@code name} in the test's directory. */
    private Path tinyWith(String name, UnaryOperator<String> edit) throws IOException {
        Path source = Files.createDirectories(dir.resolve("source"));
        String original = Files.readString(MzMLFixtures.extract(MzMLFixtures.TINY, source), ISO_8859_1);
        String edited = edit.apply(original);
        assertThat(edited).as("the edit changes the document").isNotEqualTo(original);
        Files.delete(source.resolve(MzMLFixtures.TINY));
        Files.delete(source);
        return Files.writeString(dir.resolve(name), edited, ISO_8859_1);
    }

    /**
     * {@code s} with the intensity array of scan=19 under the compression term {@code compression} in place of
     * {@link #NO_COMPRESSION}, holding the Base64 text {@code binary}.
     */
    private static String intensity19(String s, String compression, String binary) {
        int spectrum = s.indexOf("id=\"scan=19\"");
        int type = s.indexOf("accession=\"MS:1000515\"", spectrum);
        assertThat(type).as("scan=19 has an intensity array").isBetween(spectrum, s.indexOf("</spectrum>", spectrum));
        int start = s.lastIndexOf("<binaryDataArray ", type);
        int end = s.indexOf("</binaryDataArray>", type);
        String array = s.substring(start, end);
        String edited = replaceOnce(replaceOnce(array, "encodedLength=\"160\"",
                "encodedLength=\"" + binary.length() + "\""), NO_COMPRESSION, compression)
                .replaceFirst("<binary>[A-Za-z0-9+/=]*</binary>", "<binary>" + binary + "</binary>");
        return s.substring(0, start) + edited + s.substring(end);
    }

    /** {@code s} with its single occurrence of {@code target} replaced. */
    private static String replaceOnce(String s, String target, String replacement) {
        int at = s.indexOf(target);
        assertThat(at).as("'%s' occurs", target).isNotNegative();
        assertThat(s.indexOf(target, at + 1)).as("'%s' occurs once", target).isNegative();
        return s.substring(0, at) + replacement + s.substring(at + target.length());
    }
}
