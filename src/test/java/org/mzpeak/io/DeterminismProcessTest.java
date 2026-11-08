package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mzPeak archives do not depend on the JVM run, the time or the time zone that writes them (design §11, MZ-9).
 * Child JVMs ({@link ChildJvm}, running {@link ArchiveChildMain}) write or pack the same input, and their archives are
 * compared.
 *
 * <p>Packing fixed members gives byte-identical archives in every time zone. A writer's archives are compared by
 * their {@link ArchiveLayout} instead of their bytes (design Q16): the ZIP entries in order with their times, extra
 * fields and sizes, the {@link ContentDigest} of every Parquet member and the manifest with its checksums masked.
 * parquet-java 1.17.1 collects the encodings of each column chunk in a {@code HashSet} of enum constants
 * ({@code ParquetFileWriter.startColumn}) and writes them to the footer in its iteration order
 * ({@code ParquetMetadataConverter.toFormatEncodings}); enum constants hash by identity, so that order changes from
 * one JVM run to the next, and with it the footer bytes of a Parquet member, its CRC and its checksum in the manifest.
 * In the MZ-9 investigation 2 of 12 runs writing current small.unpacked differed so, and with the encodings of every
 * column chunk sorted, all 12 runs gave the same bytes for every member.
 */
class DeterminismProcessTest {

    private static final int RUNS = 6;
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"small.unpacked.mzpeak", "has_uv.mzpeak", "Example_Processed.img.mzpeak"})
    void sixJvmRunsWriteTheSameArchive(String fixture, @TempDir Path tmp) throws Exception {
        Path source = Fixtures.current(fixture).toAbsolutePath();
        List<ChildJvm.Run> runs = new ArrayList<>();
        List<Path> archives = new ArrayList<>();
        for (int run = 0; run < RUNS; run++) {
            Path archive = Files.createDirectory(tmp.resolve("run-" + run)).resolve(fixture);
            archives.add(archive);
            runs.add(ChildJvm.start(tmp, List.of(), ArchiveChildMain.class, "write", source.toString(),
                    archive.toString()));
        }
        awaitAll(runs);

        ArchiveLayout first = ArchiveLayout.of(archives.get(0));
        assertFixedTimesAndNoExtraFields(first);
        for (int run = 1; run < RUNS; run++) {
            assertThat(ArchiveLayout.of(archives.get(run))).as("layout of run %d against run 0", run)
                    .isEqualTo(first);
        }
    }

    @Test
    void packingTheSameMembersInThreeTimeZonesGivesIdenticalBytes(@TempDir Path tmp) throws Exception {
        Path members = Fixtures.current("small.unpacked.mzpeak").toAbsolutePath();
        List<String> zones = List.of("UTC", "Asia/Tokyo", "America/Chicago");
        List<Path> archives = new ArrayList<>();
        List<ChildJvm.Run> runs = new ArrayList<>();
        for (String zone : zones) {
            Path archive = Files.createDirectory(tmp.resolve(zone.replace('/', '-'))).resolve("small.mzpeak");
            archives.add(archive);
            runs.add(ChildJvm.start(tmp, List.of("-Duser.timezone=" + zone), ArchiveChildMain.class, "pack",
                    members.toString(), archive.toString()));
        }
        awaitAll(runs);

        assertFixedTimesAndNoExtraFields(ArchiveLayout.of(archives.get(0)));
        for (int i = 1; i < zones.size(); i++) {
            assertThat(Files.mismatch(archives.get(0), archives.get(i)))
                    .as("first byte at which the %s archive differs from the %s one", zones.get(i), zones.get(0))
                    .isEqualTo(-1L);
        }
    }

    @Test
    void writingInTwoTimeZonesGivesTheSameArchive(@TempDir Path tmp) throws Exception {
        Path source = Fixtures.current("small.unpacked.mzpeak").toAbsolutePath();
        List<String> zones = List.of("UTC", "Asia/Tokyo");
        List<Path> archives = new ArrayList<>();
        List<ChildJvm.Run> runs = new ArrayList<>();
        for (String zone : zones) {
            Path archive = Files.createDirectory(tmp.resolve(zone.replace('/', '-'))).resolve("small.mzpeak");
            archives.add(archive);
            runs.add(ChildJvm.start(tmp, List.of("-Duser.timezone=" + zone), ArchiveChildMain.class, "write",
                    source.toString(), archive.toString()));
        }
        awaitAll(runs);

        ArchiveLayout utc = ArchiveLayout.of(archives.get(0));
        assertFixedTimesAndNoExtraFields(utc);
        assertThat(ArchiveLayout.of(archives.get(1))).as("layout of the Asia/Tokyo archive against the UTC one")
                .isEqualTo(utc);
    }

    /** Waits for every child, then checks that each one succeeded. */
    private static void awaitAll(List<ChildJvm.Run> runs) throws IOException, InterruptedException {
        List<ChildJvm.Result> results = new ArrayList<>();
        for (ChildJvm.Run run : runs) {
            results.add(run.await());
        }
        for (int run = 0; run < results.size(); run++) {
            assertThat(results.get(run).exitStatus())
                    .as("exit status of child %d; stderr:%n%s", run, results.get(run).stderr()).isZero();
        }
    }

    private static void assertFixedTimesAndNoExtraFields(ArchiveLayout layout) {
        assertThat(layout.entries()).isNotEmpty();
        for (ArchiveLayout.Entry entry : layout.entries()) {
            assertThat(entry.timeLocal()).as("time of %s", entry.name()).isEqualTo(ENTRY_TIME);
            assertThat(entry.localExtraLength()).as("local extra field length of %s", entry.name()).isZero();
            assertThat(entry.centralExtraLength()).as("central extra field length of %s", entry.name()).isZero();
        }
    }
}
