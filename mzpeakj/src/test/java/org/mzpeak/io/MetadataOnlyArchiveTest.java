package org.mzpeak.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.model.Chromatogram;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RD-17 (D-15): a dataset whose manifest lists no spectrum entries (no spectrum metadata, scans, precursors,
 * selected ions, data arrays or peaks member), such as one holding only chromatograms, opens with no spectra; the
 * methods that read the spectrum metadata return empty. A member the manifest lists but the dataset lacks still
 * throws. The datasets are {@code @TempDir} copies of current {@code small.unpacked.mzpeak} with an edited
 * manifest. Before RD-17 {@link MzPeakReader#open} threw "Manifest has no spectrum metadata file".
 */
class MetadataOnlyArchiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final String INDEX = "mzpeak_index.json";
    private static final int SMALL_SPECTRA = 48;

    @TempDir
    Path tmp;

    // ---- (a) a dataset without spectrum entries opens -------------------------------------

    @Test
    void aDatasetWithoutSpectrumEntriesOpens() throws IOException {
        Path copy = withoutSpectrumEntries(tmp.resolve("small.unpacked.mzpeak"));

        try (MzPeakReader reader = MzPeakReader.open(copy)) {
            assertOpensWithoutSpectra(reader);
        }
    }

    @Test
    void aDatasetWithoutSpectrumEntriesOpensFromASource() throws IOException {
        Path copy = withoutSpectrumEntries(tmp.resolve("small.unpacked.mzpeak"));

        try (MzPeakReader reader = MzPeakReader.open(MzPeakSource.open(copy))) {
            assertOpensWithoutSpectra(reader);
        }
    }

    // ---- (b) the methods that read the spectrum metadata return empty ---------------------

    @Test
    void theSpectrumMetadataMethodsReturnEmpty() throws IOException {
        Path copy = withoutSpectrumEntries(tmp.resolve("small.unpacked.mzpeak"));

        try (MzPeakReader reader = MzPeakReader.open(copy)) {
            assertSpectrumMetadataMethodsEmpty(reader);
        }
    }

    @Test
    void documentsTheManifestLacksResolveFromAnEmptyFooter() throws IOException {
        Path copy = withoutSpectrumEntries(tmp.resolve("small.unpacked.mzpeak"), "sample_list", "scan_settings_list");

        try (MzPeakReader reader = MzPeakReader.open(copy)) {
            assertThat(reader.fileMetadata().samples()).isEmpty();
            assertThat(reader.fileMetadata().run().id()).isEqualTo("small");
            assertThat(reader.scanSettings()).isEmpty();
        }
        // the same documents come from the spectrum metadata footer when the member is listed
        Path full = copyWithManifest(tmp.resolve("full.mzpeak"), Set.of(), Set.of("sample_list", "scan_settings_list"));
        try (MzPeakReader reader = MzPeakReader.open(full)) {
            assertThat(reader.fileMetadata().samples()).isNotEmpty();
        }
    }

    // ---- (c) a listed but missing member still throws -------------------------------------

    @ParameterizedTest(name = "deleted {0}")
    @ValueSource(strings = {"spectra_metadata.parquet", "spectra_metadata_scans.parquet"})
    void aListedButDeletedSpectrumMemberThrows(String member) throws IOException {
        Path copy = copyWithManifest(tmp.resolve("small.unpacked.mzpeak"), Set.of(), Set.of());
        Files.delete(copy.resolve(member));

        assertThatThrownBy(() -> MzPeakReader.open(copy)).isInstanceOf(UncheckedIOException.class)
                .rootCause().isInstanceOf(FileNotFoundException.class).hasMessageContaining(member);
    }

    @Test
    void aListedButDeletedChromatogramMemberThrowsWithoutSpectrumEntries() throws IOException {
        Path copy = withoutSpectrumEntries(tmp.resolve("small.unpacked.mzpeak"));
        Files.delete(copy.resolve("chromatograms_data.parquet"));

        try (MzPeakReader reader = MzPeakReader.open(copy)) {
            assertThat(reader.size()).isZero();
            assertThatThrownBy(reader::tic).isInstanceOf(UncheckedIOException.class)
                    .rootCause().isInstanceOf(FileNotFoundException.class)
                    .hasMessageContaining("chromatograms_data.parquet");
        }
    }

    // ---- (d) the same as a STORED ZIP -----------------------------------------------------

    @Test
    void theSameAsAStoredZipOpensAlike() throws IOException {
        Path copy = withoutSpectrumEntries(tmp.resolve("small.unpacked.mzpeak"));
        Path zip = tmp.resolve("small.mzpeak");
        ArchivePacker.pack(copy, zip, false);
        assertThat(Files.isRegularFile(zip)).isTrue();

        try (MzPeakReader reader = MzPeakReader.open(zip)) {
            assertOpensWithoutSpectra(reader);
            assertSpectrumMetadataMethodsEmpty(reader);
        }
    }

    @Test
    void aStoredZipWithAListedButAbsentMemberThrows() throws IOException {
        Path copy = copyWithManifest(tmp.resolve("small.unpacked.mzpeak"), Set.of(), Set.of());
        Files.delete(copy.resolve("spectra_metadata.parquet"));
        Path zip = tmp.resolve("small.mzpeak");
        ArchivePacker.pack(copy, zip, false);

        assertThatThrownBy(() -> MzPeakReader.open(zip)).isInstanceOf(MzPeakException.class)
                .hasMessageContaining("spectra_metadata.parquet");
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static void assertOpensWithoutSpectra(MzPeakReader reader) {
        assertThat(reader.size()).isZero();
        Iterator<?> spectra = reader.iterator();
        assertThat(spectra.hasNext()).isFalse();
        assertThat(reader.manifest().find(EntityType.SPECTRUM, DataKind.METADATA)).isEmpty();
        Chromatogram tic = reader.tic().orElseThrow();
        assertThat(tic.typeCurie()).isEqualTo("MS:1000235");
        assertThat(tic.time()).hasSize(SMALL_SPECTRA);
        assertThat(tic.intensity()).hasSize(SMALL_SPECTRA);
        assertThat(reader.fileMetadata().run().id()).isEqualTo("small");
    }

    private static void assertSpectrumMetadataMethodsEmpty(MzPeakReader reader) {
        assertThat(reader.observedMzRange()).isEmpty();
        assertThat(reader.observedTimeRange()).isEmpty();
        assertThat(reader.encodedTic()).isEmpty();
        assertThat(reader.encodedBpc()).isEmpty();
        assertThat(reader.columnStatistics(EntityType.SPECTRUM, DataKind.METADATA, "MS:1000528")).isEmpty();
        assertThat(reader.columnStatistics(EntityType.SPECTRUM, DataKind.SCANS, "MS:1000016")).isEmpty();
    }

    /** A copy of small.unpacked without its spectrum entries and their members. */
    private static Path withoutSpectrumEntries(Path copy, String... droppedDocuments) throws IOException {
        return copyWithManifest(copy, Set.of("spectrum"), Set.of(droppedDocuments));
    }

    /**
     * A copy of small.unpacked whose manifest lists no entry of an entity type in {@code droppedEntities} (their
     * members are not copied) and whose {@code metadata} lacks the documents {@code droppedDocuments}.
     */
    private static Path copyWithManifest(Path copy, Set<String> droppedEntities, Set<String> droppedDocuments)
            throws IOException {
        ObjectNode index = (ObjectNode) MAPPER.readTree(SMALL.resolve(INDEX).toFile());
        ArrayNode files = (ArrayNode) index.get("files");
        ArrayNode kept = MAPPER.createArrayNode();
        Set<String> dropped = new HashSet<>();
        files.forEach(entry -> {
            if (droppedEntities.contains(entry.get("entity_type").asText())) {
                dropped.add(entry.get("name").asText());
            } else {
                kept.add(entry);
            }
        });
        index.set("files", kept);
        ((ObjectNode) index.get("metadata")).remove(droppedDocuments);
        Files.createDirectories(copy);
        try (Stream<Path> members = Files.list(SMALL)) {
            for (Path f : (Iterable<Path>) members::iterator) {
                String name = f.getFileName().toString();
                if (!name.equals(INDEX) && !dropped.contains(name)) {
                    Files.copy(f, copy.resolve(name));
                }
            }
        }
        Files.writeString(copy.resolve(INDEX), MAPPER.writeValueAsString(index), StandardCharsets.UTF_8);
        return copy;
    }
}
