package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.ManifestWriter.Dialect;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What mzPeakJ writes and what {@link ContentDigest} makes of it do not depend on the default locale or the
 * {@code line.separator} of the JVM (design §3.14, F-4). A child JVM ({@link ChildJvm}, running {@link Child}) under
 * {@code tr_TR} with CRLF line separators does the same work as this JVM, and their outputs are compared.
 *
 * <p>Raw Parquet bytes are never compared across JVMs: their footers vary from one JVM run to the next
 * ({@link DeterminismProcessTest}). The archive's members are compared by their {@link ContentDigest} and the
 * manifest by its digest with the member checksums masked ({@link WriterSnapshotTest#maskedIndex}); the manifest's
 * bytes are compared on fixed input, the members of a fixture whose checksums its own manifest stores.
 */
class ContentDigestPortabilityTest {

    private static final String FIXTURE = "small.unpacked.mzpeak";
    /** The pinned digests of the same write as a directory; an archive's members digest alike. */
    private static final Path SNAPSHOT = Path.of("src/test/resources/writer-snapshots",
            "current.small.unpacked.writeDirectory.tsv");
    private static final List<String> TURKISH_CRLF = List.of("-Duser.language=tr", "-Duser.country=TR",
            "-Dline.separator=\r\n");
    /** The first stdout line of the child: its default locale and its line separator, escaped. */
    private static final String CHILD_ENVIRONMENT = "locale=tr_TR line.separator=\\r\\n";

    @Test
    void anArchiveWrittenUnderTurkishLocaleAndCrlfDigestsAsHere(@TempDir Path tmp) throws Exception {
        Path source = Fixtures.current(FIXTURE).toAbsolutePath();
        ChildJvm.Result child = ChildJvm.run(tmp, TURKISH_CRLF, Child.class, "digests", source.toString(),
                tmp.resolve("child.mzpeak").toString());
        List<String> childLines = output(child);

        List<String> here = digests(source, tmp.resolve("parent.mzpeak"));
        List<String> pinned = Files.readAllLines(SNAPSHOT, StandardCharsets.UTF_8);
        assertThat(here).as("digests written here against the snapshot %s", SNAPSHOT)
                .containsExactlyElementsOf(pinned.subList(2, pinned.size()));
        assertThat(childLines).as("digests written under tr_TR with CRLF").containsExactlyElementsOf(here);
    }

    @Test
    void theManifestIsWrittenWithLineFeedsUnderTurkishLocaleAndCrlf(@TempDir Path tmp) throws Exception {
        Path source = Fixtures.current(FIXTURE).toAbsolutePath();
        ChildJvm.Result child = ChildJvm.run(tmp, TURKISH_CRLF, Child.class, "manifest", source.toString(),
                tmp.resolve("child").toString());
        List<String> childLines = output(child);
        assertThat(childLines).hasSize(1);
        byte[] childBytes = Base64.getDecoder().decode(childLines.get(0));

        byte[] here = manifest(source, tmp.resolve("parent"));
        assertThat(new String(here, StandardCharsets.UTF_8)).as("manifest written here").contains("\n")
                .doesNotContain("\r");
        assertThat(new String(childBytes, StandardCharsets.UTF_8)).as("manifest written under tr_TR with CRLF")
                .doesNotContain("\r");
        assertThat(childBytes).as("manifest written under tr_TR with CRLF against the one written here")
                .isEqualTo(here);
        // The input is fixed: the fixture's members, so every written checksum is the one its manifest stores.
        try (MzPeakSource written = MzPeakSource.open(tmp.resolve("parent"));
             MzPeakSource fixture = MzPeakSource.open(source)) {
            List<String> stored = checksums(MzPeakManifest.fromSource(fixture));
            assertThat(stored).isNotEmpty().doesNotContainNull();
            assertThat(checksums(MzPeakManifest.fromSource(written))).isEqualTo(stored);
        }
    }

    /** The child's stdout lines after its environment line; the child must have succeeded in tr_TR with CRLF. */
    private static List<String> output(ChildJvm.Result child) {
        assertThat(child.exitStatus()).as("exit status of the child; stderr:%n%s", child.stderr()).isZero();
        List<String> lines = child.stdout().lines().toList();
        assertThat(child.stdout()).as("child stdout ends with CRLF").endsWith("\r\n");
        assertThat(lines).first().isEqualTo(CHILD_ENVIRONMENT);
        return lines.subList(1, lines.size());
    }

    private static List<String> checksums(MzPeakManifest manifest) {
        List<String> checksums = new ArrayList<>();
        for (Entry entry : manifest.files()) {
            checksums.add(entry.checksum());
        }
        return checksums;
    }

    /**
     * Writes {@code source} as the archive {@code archive} and returns the {@link ContentDigest} TSV line of every
     * member in name order, the manifest's with its checksums masked.
     */
    static List<String> digests(Path source, Path archive) {
        Source s = ForwardBackwardCorpusTest.readSource(source);
        MzPeakWriter.writeArchive(archive, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata);
        List<String> lines = new ArrayList<>();
        for (ContentDigest digest : WriterSnapshotTest.snapshot(archive, true).members().values()) {
            lines.add(digest.member().equals(ManifestWriter.INDEX)
                    ? WriterSnapshotTest.maskedIndex(archive).tsvLine() : digest.tsvLine());
        }
        return lines;
    }

    /**
     * Copies the members of {@code source} into the new directory {@code directory} and writes their manifest there
     * with {@link ManifestWriter}: the entries of the source's manifest and its file metadata, in the current
     * dialect. Returns the manifest's bytes.
     */
    static byte[] manifest(Path source, Path directory) throws IOException {
        List<WrittenMember> members = new ArrayList<>();
        try (MzPeakSource in = MzPeakSource.open(source)) {
            for (Entry entry : MzPeakManifest.fromSource(in).files()) {
                members.add(new WrittenMember(entry.name(), entry.entity(), entry.kind(), entry.columnMapping()));
            }
        }
        FileMetadata fileMetadata;
        try (MzPeakReader reader = MzPeakReader.open(source)) {
            fileMetadata = reader.fileMetadata();
        }
        Files.createDirectory(directory);
        try (Stream<Path> files = Files.list(source)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!file.getFileName().toString().equals(ManifestWriter.INDEX)) {
                    Files.copy(file, directory.resolve(file.getFileName().toString()));
                }
            }
        }
        WritePlan plan = WritePlanner.plan(List.of(), List.of(), List.of(), List.of(), fileMetadata,
                MzPeakWriter.Options.defaults());
        ManifestWriter.writeManifest(directory, members, plan, Dialect.CURRENT);
        return Files.readAllBytes(directory.resolve(ManifestWriter.INDEX));
    }

    /**
     * The child's entry point. Prints its default locale and line separator, then for {@code digests <dataset>
     * <archive>} the lines of {@link #digests}, for {@code manifest <dataset> <directory>} the base64 of the bytes of
     * {@link #manifest}. A failure ends the child with its stack trace on stderr and a non-zero exit status.
     */
    static final class Child {

        private Child() {
        }

        public static void main(String[] args) throws IOException {
            System.out.println("locale=" + Locale.getDefault() + " line.separator="
                    + System.lineSeparator().replace("\r", "\\r").replace("\n", "\\n"));
            Path source = Path.of(args[1]);
            Path out = Path.of(args[2]);
            switch (args[0]) {
                case "digests" -> digests(source, out).forEach(System.out::println);
                case "manifest" -> System.out.println(Base64.getEncoder().encodeToString(manifest(source, out)));
                default -> throw new IllegalArgumentException("unknown command " + args[0]);
            }
        }
    }
}
