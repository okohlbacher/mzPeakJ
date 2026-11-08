package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what {@link MzPeakWriter} writes, member by member, before the writer is split and changed (WR-0b, WR-0c and
 * later). The round-trip tests only check what mzPeakJ reads back; this snapshot also catches drift in manifest keys,
 * Parquet schemas and types, footer key-values and ZIP entry order.
 *
 * <p>Each case reads a fixture with {@link ForwardBackwardCorpusTest#readSource}, writes it with one of the
 * full-form overloads (wavelength spectra, scan settings and file metadata included), and digests every member of the
 * output with {@link ContentDigest}, which ignores ZSTD bytes and ZIP timestamps. The archive case also records the
 * ZIP entries in file order with their method. The pinned line of the manifest digests it with every member checksum
 * masked ({@link #maskedIndex}). The expected lines live in
 * {@code src/test/resources/writer-snapshots/<fixture>.<overload>.tsv} for a legacy fixture and in
 * {@code current.<fixture>.<overload>.tsv} for a current one. A current fixture is read through the split layout, so
 * its spectra carry the leftover Params of that layout, imaging positions and scan settings with units, which the
 * legacy fixtures lack (WR-5a). On a mismatch the test prints the full actual TSV and writes it to
 * {@code target/writer-snapshots/}; a unit that changes writer output on purpose copies it over the expected file and
 * explains the change.
 */
class WriterSnapshotTest {

    private static final Path SNAPSHOTS = Path.of("src/test/resources/writer-snapshots");
    private static final Path ACTUAL = Path.of("target", "writer-snapshots");
    private static final String INDEX = "mzpeak_index.json";
    private static final String ROW_GROUP_SIZE = "mzpeak.writer.rowGroupSize";
    private static final String DATA = "spectra_data.parquet";
    private static final String HEADER = "member\tkind\tdetail\tsha256";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** A member checksum in the manifest; group 1 is the key and the opening quote of the value. */
    private static final Pattern CHECKSUM = Pattern.compile("(\"checksum\"\\s*:\\s*\")[0-9a-f]{128}\"");

    /** Fixtures read once per class, keyed by set and name; the tests never modify a source in place. */
    private static final Map<String, Source> SOURCES = new ConcurrentHashMap<>();

    /** The fixture set a case reads its source from. */
    enum FixtureSet {
        LEGACY("legacy", ""),
        CURRENT("current", "current.");

        final String label;
        /** Prefix of the snapshot file name. */
        final String filePrefix;

        FixtureSet(String label, String filePrefix) {
            this.label = label;
            this.filePrefix = filePrefix;
        }

        Path path(String fixture) {
            return this == LEGACY ? Fixtures.legacy(fixture) : Fixtures.current(fixture);
        }
    }

    /** The full-form writer overloads under test. */
    enum Overload {
        WRITE_DIRECTORY("writeDirectory", false, (out, s) -> MzPeakWriter.writeDirectory(out, s.spectra,
                s.chromatograms, s.wavelengthSpectra, s.scanSettings, s.fileMetadata)),
        WRITE_DIRECTORY_NUMPRESS_SLOF("writeDirectoryNumpress-SLOF", false, (out, s) ->
                MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms, s.wavelengthSpectra,
                        s.scanSettings, s.fileMetadata, MzPeakWriter.NumpressIntensityEncoding.SLOF)),
        WRITE_DIRECTORY_NUMPRESS_PIC("writeDirectoryNumpress-PIC", false, (out, s) ->
                MzPeakWriter.writeDirectoryNumpress(out, s.spectra, s.chromatograms, s.wavelengthSpectra,
                        s.scanSettings, s.fileMetadata, MzPeakWriter.NumpressIntensityEncoding.PIC)),
        WRITE_ARCHIVE("writeArchive", true, (out, s) -> MzPeakWriter.writeArchive(out, s.spectra,
                s.chromatograms, s.wavelengthSpectra, s.scanSettings, s.fileMetadata));

        final String label;
        final boolean archive;
        final BiConsumer<Path, Source> write;

        Overload(String label, boolean archive, BiConsumer<Path, Source> write) {
            this.label = label;
            this.archive = archive;
            this.write = write;
        }
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(FixtureSet.LEGACY, "small.unpacked.mzpeak", Overload.WRITE_DIRECTORY),
                Arguments.of(FixtureSet.LEGACY, "small.unpacked.mzpeak", Overload.WRITE_DIRECTORY_NUMPRESS_SLOF),
                Arguments.of(FixtureSet.LEGACY, "small.unpacked.mzpeak", Overload.WRITE_DIRECTORY_NUMPRESS_PIC),
                Arguments.of(FixtureSet.LEGACY, "has_uv.mzpeak", Overload.WRITE_DIRECTORY),
                Arguments.of(FixtureSet.LEGACY, "has_uv.mzpeak", Overload.WRITE_ARCHIVE),
                Arguments.of(FixtureSet.CURRENT, "small.unpacked.mzpeak", Overload.WRITE_DIRECTORY),
                Arguments.of(FixtureSet.CURRENT, "Example_Processed.img.mzpeak", Overload.WRITE_DIRECTORY));
    }

    @ParameterizedTest(name = "{0} {1} via {2}")
    @MethodSource("cases")
    void writtenMembersMatchTheSnapshot(FixtureSet set, String fixture, Overload overload, @TempDir Path tmp)
            throws IOException {
        assertThat(System.getProperty(ROW_GROUP_SIZE)).as("%s must be unset: it changes the row groups", ROW_GROUP_SIZE)
                .isNull();
        Path out = write(fixture, overload, source(set, fixture), tmp.resolve("out.mzpeak"));
        Snapshot actual = snapshot(out, overload.archive);
        assertThat(actual.members().keySet()).as("members of %s %s via %s", set.label, fixture, overload.label)
                .containsExactlyInAnyOrderElementsOf(manifestMembers(out));

        String name = set.filePrefix + fixture.substring(0, fixture.length() - ".mzpeak".length()) + "."
                + overload.label + ".tsv";
        List<String> actualLines = tsv(set, fixture, overload, actual, maskedIndex(out));
        Path expectedFile = SNAPSHOTS.resolve(name);
        List<String> expectedLines = Files.isRegularFile(expectedFile)
                ? Files.readAllLines(expectedFile, StandardCharsets.UTF_8) : List.of();
        if (!actualLines.equals(expectedLines)) {
            Files.createDirectories(ACTUAL);
            Files.write(ACTUAL.resolve(name), actualLines, StandardCharsets.UTF_8);
        }
        assertThat(actualLines)
                .as("writer snapshot %s; actual TSV (also in %s):%n%s", expectedFile, ACTUAL.resolve(name),
                        String.join("\n", actualLines))
                .containsExactlyElementsOf(expectedLines);
    }

    @Test
    void writingTheSameInputTwiceGivesEqualDigests(@TempDir Path tmp) {
        Source source = source("has_uv.mzpeak");
        Snapshot first = snapshot(write("has_uv.mzpeak", Overload.WRITE_ARCHIVE, source, tmp.resolve("first.mzpeak")),
                true);
        Snapshot second = snapshot(write("has_uv.mzpeak", Overload.WRITE_ARCHIVE, source, tmp.resolve("second.mzpeak")),
                true);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void aDirectoryAndAnArchiveOfTheSameInputDigestAlike(@TempDir Path tmp) {
        Source source = source("has_uv.mzpeak");
        Snapshot directory = snapshot(write("has_uv.mzpeak", Overload.WRITE_DIRECTORY, source, tmp.resolve("dir")),
                false);
        Snapshot archive = snapshot(write("has_uv.mzpeak", Overload.WRITE_ARCHIVE, source, tmp.resolve("zip")), true);
        assertThat(archive.members()).isEqualTo(directory.members());
        assertThat(archive.zipEntries()).isNotEmpty();
    }

    @Test
    void oneChangedIntensityChangesTheSpectraDataDigest(@TempDir Path tmp) {
        Source original = source("small.unpacked.mzpeak");
        Source changed = copyOf(original);
        // A profile spectrum keeps its arrays in spectra_data; design §11.5 moves only CENTROID peak copies.
        int victim = 0;
        while (changed.spectra.get(victim).pointCount() == 0
                || changed.spectra.get(victim).description().signalContinuity() != SignalContinuity.PROFILE) {
            victim++;
        }
        Spectrum s = changed.spectra.get(victim);
        double[] intensity = s.intensity().clone();
        int point = intensity.length / 2;
        intensity[point] = Math.nextUp(intensity[point]);
        changed.spectra.set(victim, new Spectrum(s.description(), s.mz(), intensity, s.peaks()));

        Map<String, ContentDigest> before = snapshot(
                write("small.unpacked.mzpeak", Overload.WRITE_DIRECTORY, original, tmp.resolve("before")), false)
                .members();
        Map<String, ContentDigest> after = snapshot(
                write("small.unpacked.mzpeak", Overload.WRITE_DIRECTORY, changed, tmp.resolve("after")), false)
                .members();

        assertThat(after.keySet()).isEqualTo(before.keySet());
        assertThat(after.get(DATA).sha256()).as("digest of %s", DATA).isNotEqualTo(before.get(DATA).sha256());
        // Rows, schema and footer stay; only the records part of the detail moves.
        assertThat(after.get(DATA).detail()).isNotEqualTo(before.get(DATA).detail());
        assertThat(withoutRecords(after.get(DATA).detail())).isEqualTo(withoutRecords(before.get(DATA).detail()));
        for (String member : List.of("spectra_peaks.parquet", "chromatograms_metadata.parquet",
                "chromatograms_data.parquet")) {
            assertThat(after.get(member)).as("digest of %s", member).isEqualTo(before.get(member));
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Every member's digest, keyed by name in name order, and for an archive its entries in file order. */
    record Snapshot(Map<String, ContentDigest> members, List<String> zipEntries) {
    }

    private static Source source(String fixture) {
        return source(FixtureSet.LEGACY, fixture);
    }

    private static Source source(FixtureSet set, String fixture) {
        return SOURCES.computeIfAbsent(set.label + "/" + fixture,
                k -> ForwardBackwardCorpusTest.readSource(set.path(fixture)));
    }

    private static String withoutRecords(String detail) {
        return detail.replaceAll(" records=[0-9a-f]+", "");
    }

    private static Source copyOf(Source source) {
        Source copy = new Source();
        copy.spectra.addAll(source.spectra);
        copy.chromatograms = source.chromatograms;
        copy.wavelengthSpectra = source.wavelengthSpectra;
        copy.scanSettings = source.scanSettings;
        copy.fileMetadata = source.fileMetadata;
        return copy;
    }

    private static Path write(String fixture, Overload overload, Source source, Path out) {
        overload.write.accept(out, source);
        assertThat(overload.archive ? Files.isRegularFile(out) : Files.isDirectory(out))
                .as("%s via %s wrote %s", fixture, overload.label, out).isTrue();
        return out;
    }

    static Snapshot snapshot(Path out, boolean archive) {
        List<String> zipEntries = archive ? zipEntries(out) : List.of();
        TreeSet<String> names = new TreeSet<>();
        if (archive) {
            zipEntries.forEach(e -> names.add(e.substring(0, e.lastIndexOf(':'))));
        } else {
            try (Stream<Path> files = Files.list(out)) {
                files.forEach(f -> {
                    assertThat(f).as("member of %s", out).isRegularFile();
                    names.add(f.getFileName().toString());
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        Map<String, ContentDigest> members = new LinkedHashMap<>();
        try (MzPeakSource source = MzPeakSource.open(out)) {
            for (String name : names) {
                members.put(name, ContentDigest.of(source, name));
            }
        }
        return new Snapshot(members, zipEntries);
    }

    /** The archive's entries as {@code name:METHOD} in file order, checked against the central directory. */
    private static List<String> zipEntries(Path archive) {
        List<String> local = new ArrayList<>();
        List<String> central = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(archive))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                local.add(e.getName() + ":" + method(e));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            zip.stream().forEach(e -> central.add(e.getName() + ":" + method(e)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(central).as("central directory of %s", archive).isEqualTo(local);
        return local;
    }

    private static String method(ZipEntry entry) {
        switch (entry.getMethod()) {
            case ZipEntry.STORED:
                return "STORED";
            case ZipEntry.DEFLATED:
                return "DEFLATED";
            default:
                return Integer.toString(entry.getMethod());
        }
    }

    /** The member names the manifest lists, plus the manifest itself. */
    private static List<String> manifestMembers(Path out) throws IOException {
        List<String> names = new ArrayList<>();
        try (MzPeakSource source = MzPeakSource.open(out)) {
            for (JsonNode f : MAPPER.readTree(source.readManifestBytes()).path("files")) {
                names.add(f.path("name").asText());
            }
        }
        names.add(INDEX);
        return names;
    }

    /**
     * The manifest's digest over its raw bytes with every {@code checksum} value replaced by 128 zeros; the detail
     * also counts the masked values. The checksums cannot be pinned: parquet-java writes the encodings of each column
     * chunk in the iteration order of a {@code HashSet} of enum constants, which changes from one JVM run to the
     * next, and with it the footer bytes and the SHA-512 of every Parquet member. The Parquet members are pinned by
     * their content digests, and {@link WriterManifestConformanceTest} checks every checksum against its member.
     */
    private static ContentDigest maskedIndex(Path out) {
        byte[] bytes;
        try (MzPeakSource source = MzPeakSource.open(out)) {
            bytes = source.readManifestBytes();
        }
        Matcher m = CHECKSUM.matcher(new String(bytes, StandardCharsets.UTF_8));
        StringBuilder masked = new StringBuilder();
        int count = 0;
        while (m.find()) {
            m.appendReplacement(masked, "$1" + "0".repeat(128) + "\"");
            count++;
        }
        m.appendTail(masked);
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] digest = sha256.digest(masked.toString().getBytes(StandardCharsets.UTF_8));
        return new ContentDigest(INDEX, "bytes", "size=" + bytes.length + " masked_checksums=" + count,
                HexFormat.of().formatHex(digest));
    }

    private static List<String> tsv(FixtureSet set, String fixture, Overload overload, Snapshot snapshot,
                                    ContentDigest index) {
        List<String> lines = new ArrayList<>();
        lines.add("# WriterSnapshotTest (WR-0a): " + set.label + " " + fixture + " written by MzPeakWriter."
                + overload.label.replace('-', ' '));
        lines.add(HEADER);
        snapshot.members().values().forEach(d -> lines.add(d.member().equals(INDEX) ? index.tsvLine() : d.tsvLine()));
        if (overload.archive) {
            lines.add("(zip entries)\tzip\t" + String.join(",", snapshot.zipEntries()) + "\t-");
        }
        return lines;
    }
}
