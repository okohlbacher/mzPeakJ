package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ms.numpress.MSNumpress;
import org.apache.parquet.example.data.Group;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.WriterSnapshotTest.Snapshot;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Param;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

/**
 * Pins the parts of {@link MzPeakWriter} that WR-0b moves into package-private components and that
 * {@link WriterSnapshotTest} does not reach with its two legacy fixtures: the scan-settings serializer and member
 * list of the manifest, the typed ion-mobility and imaging-position scan columns, the derived summary columns of
 * empty and array-only centroid spectra, the {@code mzpeak.writer.rowGroupSize} property, the Numpress archive
 * overloads and the temp-directory handling of the archive writers.
 *
 * <p>Inputs are the legacy {@code small.unpacked} and {@code has_uv} fixtures, read with
 * {@link ForwardBackwardCorpusTest#readSource}, with a few spectra or scan settings added or edited.
 */
class WriterComponentsCharacterizationTest {

    private static final Path SMALL = Fixtures.legacy("small.unpacked.mzpeak");
    private static final Path HAS_UV = Fixtures.legacy("has_uv.mzpeak");
    private static final String ROW_GROUP_SIZE = "mzpeak.writer.rowGroupSize";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- manifest -----------------------------------------------------------------------------------------------

    @Test
    void scanSettingsAreSerializedIntoTheManifestByValueType(@TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(SMALL);
        List<ScanSettings> settings = List.of(
                new ScanSettings("settings-1", List.of(
                        new Param("max count of pixel x", "IMS:1000042", 3L, null),
                        new Param("max count of pixel y", "IMS:1000043", 2, null),
                        new Param("pixel size x", "IMS:1000046", 50.0, "UO:0000017"),
                        new Param("flag", null, true, null),
                        new Param("label", null, "top down", null),
                        new Param("ratio", null, 1.5f, null),
                        new Param(null, "IMS:1000401", null, "UO:0000010"))),
                new ScanSettings(null, List.of()));
        String expected = "[{\"id\":\"settings-1\",\"parameters\":["
                + "{\"name\":\"max count of pixel x\",\"accession\":\"IMS:1000042\",\"value\":3},"
                + "{\"name\":\"max count of pixel y\",\"accession\":\"IMS:1000043\",\"value\":2},"
                + "{\"name\":\"pixel size x\",\"accession\":\"IMS:1000046\",\"value\":50.0,"
                + "\"unit_accession\":\"UO:0000017\"},"
                + "{\"name\":\"flag\",\"value\":true},"
                + "{\"name\":\"label\",\"value\":\"top down\"},"
                + "{\"name\":\"ratio\",\"value\":\"1.5\"},"
                + "{\"accession\":\"IMS:1000401\",\"unit_accession\":\"UO:0000010\"}]},"
                + "{\"parameters\":[]}]";

        Path point = tmp.resolve("point.mzpeak");
        MzPeakWriter.writeDirectory(point, s.spectra, s.chromatograms, List.of(), settings, s.fileMetadata);
        Path numpress = tmp.resolve("numpress.mzpeak");
        MzPeakWriter.writeDirectoryNumpress(numpress, s.spectra, s.chromatograms, List.of(), settings,
                s.fileMetadata, MzPeakWriter.NumpressIntensityEncoding.PIC);

        assertThat(manifest(point).path("metadata").path("scan_settings_list").toString()).isEqualTo(expected);
        assertThat(manifest(numpress).path("metadata").path("scan_settings_list").toString()).isEqualTo(expected);

        Path none = tmp.resolve("null-settings.mzpeak");
        MzPeakWriter.writeDirectory(none, s.spectra, s.chromatograms, List.of(), null, s.fileMetadata);
        assertThat(manifest(none).path("metadata").path("scan_settings_list").toString()).isEqualTo("[]");
    }

    @Test
    void theManifestListsOnlyTheMembersWritten(@TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(SMALL);
        // The centroid spectra of small.unpacked, each with its peak list only (the reader also fills the arrays).
        List<Spectrum> peaksOnly = s.spectra.stream()
                .filter(x -> x.description().signalContinuity() == SignalContinuity.CENTROID)
                .map(x -> new Spectrum(x.description(), new double[0], new double[0], x.peaks()))
                .collect(Collectors.toList());
        assertThat(peaksOnly).as("small.unpacked centroid spectra").hasSize(34)
                .allSatisfy(x -> assertThat(x.peaks()).isNotEmpty());

        Path point = tmp.resolve("point.mzpeak");
        MzPeakWriter.writeDirectory(point, peaksOnly, List.of(), List.of(), List.of(), FileMetadata.EMPTY);
        assertThat(manifestFiles(point)).containsExactly(
                "spectra_metadata.parquet|spectrum|metadata",
                "spectra_peaks.parquet|spectrum|peaks");
        assertThat(memberNames(point)).containsExactly(
                "mzpeak_index.json", "spectra_metadata.parquet", "spectra_peaks.parquet");
        assertThat(fieldNames(manifest(point).path("metadata")))
                .startsWith("version", "cv_list", "scan_settings_list");

        // Numpress always writes spectra_data, here without a single row.
        Path numpress = tmp.resolve("numpress.mzpeak");
        MzPeakWriter.writeDirectoryNumpress(numpress, peaksOnly, List.of(), List.of(), List.of(), FileMetadata.EMPTY,
                MzPeakWriter.NumpressIntensityEncoding.SLOF);
        assertThat(manifestFiles(numpress)).containsExactly(
                "spectra_metadata.parquet|spectrum|metadata",
                "spectra_data.parquet|spectrum|data arrays",
                "spectra_peaks.parquet|spectrum|peaks");
        assertThat(rows(numpress.resolve("spectra_data.parquet"))).isEmpty();

        Path chromatogramsOnly = tmp.resolve("chromatograms-only.mzpeak");
        MzPeakWriter.writeDirectory(chromatogramsOnly, List.of(), s.chromatograms, List.of(), List.of(),
                FileMetadata.EMPTY);
        assertThat(manifestFiles(chromatogramsOnly)).containsExactly(
                "spectra_metadata.parquet|spectrum|metadata",
                "chromatograms_metadata.parquet|chromatogram|metadata",
                "chromatograms_data.parquet|chromatogram|data arrays");
        assertThat(rows(chromatogramsOnly.resolve("spectra_metadata.parquet"))).isEmpty();
    }

    // ---- packed spectrum metadata and point/chunk rows ----------------------------------------------------------

    /**
     * Spectrum 0 gains an ion-mobility param and an imaging position on its first scan; spectrum 48 is a CENTROID
     * spectrum carried in its arrays only (no peak list, no scans); spectrum 49 is empty.
     */
    @Test
    void typedScanColumnsAndDerivedColumnsOfEditedSpectra(@TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> spectra = new ArrayList<>(s.spectra);
        assertThat(spectra).hasSize(48);
        Spectrum first = spectra.get(0);
        ScanEvent scan = first.description().scans().get(0);
        List<Param> scanParams = new ArrayList<>(scan.parameters());
        scanParams.add(new Param("ion mobility drift time", "MS:1002476", 12.5, "UO:0000028"));
        scanParams.add(new Param("position x", "IMS:1000050", 3L, null));
        scanParams.add(new Param("position y", "IMS:1000051", 4L, null));
        ScanEvent edited = new ScanEvent(scan.startTime(), scan.injectionTime(), scan.filterString(),
                scan.presetScanConfiguration(), scan.instrumentConfigurationRef(), scan.spectrumReference(),
                scan.scanWindows(), scanParams);
        spectra.set(0, withScans(first, List.of(edited)));
        spectra.add(new Spectrum(description(48, SignalContinuity.CENTROID, 1.5),
                new double[] {100.0, 200.0, 150.0}, new double[] {5.0, 1.0, 7.0}, List.of()));
        spectra.add(new Spectrum(description(49, SignalContinuity.PROFILE, Double.NaN),
                new double[0], new double[0], List.of()));

        Path point = tmp.resolve("point.mzpeak");
        MzPeakWriter.writeDirectory(point, spectra, s.chromatograms, List.of(), List.of(), s.fileMetadata);
        Path numpress = tmp.resolve("numpress.mzpeak");
        MzPeakWriter.writeDirectoryNumpress(numpress, spectra, s.chromatograms, List.of(), List.of(),
                s.fileMetadata, MzPeakWriter.NumpressIntensityEncoding.PIC);

        for (Path out : List.of(point, numpress)) {
            Map<Long, Group> meta = metadataRows(out.resolve("spectra_metadata.parquet"));
            assertThat(meta).hasSize(50);

            Group scan0 = ParquetGroups.optGroup(meta.get(0L), "scan");
            assertThat(ParquetGroups.optDouble(scan0, "ion_mobility_value")).isEqualTo(12.5);
            assertThat(ParquetGroups.optString(scan0, "ion_mobility_type")).isEqualTo("MS:1002476");
            assertThat(ParquetGroups.optInt(scan0, "IMS_1000050_position_x")).isEqualTo(3);
            assertThat(ParquetGroups.optInt(scan0, "IMS_1000051_position_y")).isEqualTo(4);
            assertThat(ParquetGroups.readParams(scan0, "parameters")).containsAll(scanParams.subList(
                    scanParams.size() - 3, scanParams.size()));

            Group centroid = ParquetGroups.optGroup(meta.get(48L), "spectrum");
            assertThat(ParquetGroups.optString(centroid, "MS_1000525_spectrum_representation"))
                    .isEqualTo("MS:1000127");
            assertThat(ParquetGroups.optString(centroid, "MS_1000559_spectrum_type")).isEqualTo("MS:1000579");
            assertThat(ParquetGroups.has(centroid, "MS_1000465_scan_polarity")).isFalse();
            assertThat(ParquetGroups.optDouble(centroid, "time")).isEqualTo(1.5);
            assertThat(ParquetGroups.optDouble(centroid, "MS_1000528_lowest_observed_mz_unit_MS_1000040"))
                    .isEqualTo(100.0);
            assertThat(ParquetGroups.optDouble(centroid, "MS_1000527_highest_observed_mz_unit_MS_1000040"))
                    .isEqualTo(200.0);
            assertThat(ParquetGroups.optDouble(centroid, "MS_1000504_base_peak_mz_unit_MS_1000040"))
                    .isEqualTo(150.0);
            assertThat(ParquetGroups.optDouble(centroid, "MS_1000505_base_peak_intensity_unit_MS_1000131"))
                    .isEqualTo(7.0);
            assertThat(ParquetGroups.optDouble(centroid, "MS_1000285_total_ion_current_unit_MS_1000131"))
                    .isEqualTo(13.0);
            assertThat(ParquetGroups.optLong(centroid, "MS_1003060_number_of_data_points")).isEqualTo(3L);
            assertThat(ParquetGroups.has(centroid, "MS_1003059_number_of_peaks")).isFalse();
            Group centroidScan = ParquetGroups.optGroup(meta.get(48L), "scan");
            assertThat(fieldsPresent(centroidScan))
                    .containsExactly("source_index", "scan_index", "MS_1000016_scan_start_time_unit_UO_0000031");
            assertThat(ParquetGroups.optLong(centroidScan, "scan_index")).isEqualTo(48L);
            assertThat(ParquetGroups.optDouble(centroidScan, "MS_1000016_scan_start_time_unit_UO_0000031"))
                    .isEqualTo(1.5);
            assertThat(ParquetGroups.has(meta.get(48L), "precursor")).isFalse();
            assertThat(ParquetGroups.has(meta.get(48L), "selected_ion")).isFalse();

            Group empty = ParquetGroups.optGroup(meta.get(49L), "spectrum");
            assertThat(fieldsPresent(empty)).containsExactly("index", "id", "MS_1000511_ms_level",
                    "MS_1000525_spectrum_representation", "MS_1000559_spectrum_type");
            assertThat(fieldsPresent(ParquetGroups.optGroup(meta.get(49L), "scan")))
                    .containsExactly("source_index", "scan_index");

            assertThat(indexValues(out.resolve("spectra_peaks.parquet"), out == point ? "point" : "chunk"))
                    .doesNotContain(48L, 49L);
        }

        List<Group> pointRows = rows(point.resolve("spectra_data.parquet"));
        List<double[]> centroidPoints = new ArrayList<>();
        for (Group row : pointRows) {
            Group p = ParquetGroups.optGroup(row, "point");
            long index = ParquetGroups.optLong(p, "spectrum_index");
            assertThat(index).isNotEqualTo(49L);
            if (index == 48L) {
                centroidPoints.add(new double[] {ParquetGroups.optDouble(p, "mz"),
                        ParquetGroups.optDouble(p, "intensity")});
            }
        }
        assertThat(centroidPoints).containsExactly(
                new double[] {100.0, 5.0}, new double[] {200.0, 1.0}, new double[] {150.0, 7.0});

        List<Group> chunks = new ArrayList<>();
        for (Group row : rows(numpress.resolve("spectra_data.parquet"))) {
            Group chunk = ParquetGroups.optGroup(row, "chunk");
            long index = ParquetGroups.optLong(chunk, "spectrum_index");
            assertThat(index).isNotEqualTo(49L);
            if (index == 48L) {
                chunks.add(chunk);
            }
        }
        assertThat(chunks).hasSize(1);
        Group chunk = chunks.get(0);
        assertThat(ParquetGroups.optDouble(chunk, "mz_chunk_start")).isEqualTo(100.0);
        assertThat(ParquetGroups.optDouble(chunk, "mz_chunk_end")).as("last value, not the maximum").isEqualTo(150.0);
        assertThat(ParquetGroups.optString(chunk, "chunk_encoding")).isEqualTo("MS:1002312");
        assertThat(fieldsPresent(chunk)).containsExactly("spectrum_index", "mz_chunk_start", "mz_chunk_end",
                "chunk_encoding", "mz_numpress_linear_bytes", "intensity_numpress_pic_bytes");
        byte[] mzBytes = ParquetGroups.byteList(chunk, "mz_numpress_linear_bytes");
        assertThat(MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, mzBytes, mzBytes.length))
                .containsExactly(new double[] {100.0, 200.0, 150.0}, offset(1e-5));
    }

    // ---- row-group size -----------------------------------------------------------------------------------------

    /**
     * The property applies to every member, the footer-carrying spectra_metadata included; only the row-group
     * count changes. Members below parquet-java's first size check (100 rows) stay in one row group.
     */
    @Test
    void rowGroupSizePropertySplitsEveryLargeMember(@TempDir Path tmp) {
        Source uv = ForwardBackwardCorpusTest.readSource(HAS_UV);
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        Consumer<Path> pointWrite = out -> MzPeakWriter.writeDirectory(out, uv.spectra, uv.chromatograms,
                uv.wavelengthSpectra, uv.scanSettings, uv.fileMetadata);
        Consumer<Path> numpressWrite = out -> MzPeakWriter.writeDirectoryNumpress(out, small.spectra,
                small.chromatograms, small.wavelengthSpectra, small.scanSettings, small.fileMetadata,
                MzPeakWriter.NumpressIntensityEncoding.SLOF);

        Map<String, String> pointGroups = compareWithSmallRowGroups(tmp.resolve("point"), pointWrite, "4096");
        Map<String, String> numpressGroups = compareWithSmallRowGroups(tmp.resolve("numpress"), numpressWrite,
                "32768");

        assertThat(pointGroups).isEqualTo(new TreeMap<>(Map.of(
                "chromatograms_data.parquet", "row_groups=5",
                "chromatograms_metadata.parquet", "row_groups=1",
                "spectra_data.parquet", "row_groups=144",
                "spectra_metadata.parquet", "row_groups=3",
                "spectra_peaks.parquet", "row_groups=144",
                "wavelength_spectra_data.parquet", "row_groups=296",
                "wavelength_spectra_metadata.parquet", "row_groups=6")));
        assertThat(numpressGroups).isEqualTo(new TreeMap<>(Map.of(
                "chromatograms_data.parquet", "row_groups=1",
                "chromatograms_metadata.parquet", "row_groups=1",
                "spectra_data.parquet", "row_groups=6",
                "spectra_metadata.parquet", "row_groups=1",
                "spectra_peaks.parquet", "row_groups=1")));
    }

    // ---- archives -----------------------------------------------------------------------------------------------

    @Test
    void numpressArchiveHoldsTheSlofDirectoryMembersInNameOrder(@TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(SMALL);
        Path dir = tmp.resolve("dir.mzpeak");
        MzPeakWriter.writeDirectoryNumpress(dir, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, MzPeakWriter.NumpressIntensityEncoding.SLOF);
        Path parent = Files.createDirectories(tmp.resolve("archives"));
        Path archive = parent.resolve("out.mzpeak");
        MzPeakWriter.writeArchiveNumpress(archive, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata);

        Snapshot fromDirectory = WriterSnapshotTest.snapshot(dir, false);
        Snapshot fromArchive = WriterSnapshotTest.snapshot(archive, true);
        assertThat(fromArchive.members()).isEqualTo(fromDirectory.members());
        // The index is the last entry (WR-1).
        assertThat(fromArchive.zipEntries()).containsExactly(
                "chromatograms_data.parquet:STORED",
                "chromatograms_metadata.parquet:STORED",
                "spectra_data.parquet:STORED",
                "spectra_metadata.parquet:STORED",
                "spectra_peaks.parquet:STORED",
                "mzpeak_index.json:STORED");
        assertThat(memberNames(parent)).as("the archive's directory after the write").containsExactly("out.mzpeak");

        // Writing again replaces the archive.
        MzPeakWriter.writeArchiveNumpress(archive, s.spectra.subList(0, 3), List.of());
        assertThat(WriterSnapshotTest.snapshot(archive, true).zipEntries()).containsExactly(
                "spectra_data.parquet:STORED",
                "spectra_metadata.parquet:STORED",
                "spectra_peaks.parquet:STORED",
                "mzpeak_index.json:STORED");
        assertThat(memberNames(parent)).containsExactly("out.mzpeak");
    }

    @Test
    void aRejectedArchiveWriteLeavesNothingBehind(@TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> duplicate = new ArrayList<>(s.spectra);
        duplicate.add(duplicate.get(17));

        Path pointParent = Files.createDirectories(tmp.resolve("point"));
        assertThatThrownBy(() -> MzPeakWriter.writeArchive(pointParent.resolve("out.mzpeak"), duplicate,
                s.chromatograms, s.wavelengthSpectra, s.scanSettings, s.fileMetadata))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("duplicate spectrum index: 17 (mzPeak indices are unique)");
        assertThat(memberNames(pointParent)).isEmpty();

        Path numpressParent = Files.createDirectories(tmp.resolve("numpress"));
        assertThatThrownBy(() -> MzPeakWriter.writeArchiveNumpress(numpressParent.resolve("out.mzpeak"), duplicate,
                s.chromatograms, s.wavelengthSpectra, s.scanSettings, s.fileMetadata))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("duplicate spectrum index: 17 (mzPeak indices are unique)");
        assertThat(memberNames(numpressParent)).isEmpty();
    }

    @Test
    void aFailedMoveIntoPlaceIsWrappedAndCleanedUp(@TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(SMALL);
        Path parent = Files.createDirectories(tmp.resolve("parent"));
        Path archive = Files.createDirectories(parent.resolve("out.mzpeak"));
        Files.writeString(archive.resolve("keep.txt"), "keep");

        assertThatThrownBy(() -> MzPeakWriter.writeArchive(archive, s.spectra, s.chromatograms))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("Failed to write mzPeak archive " + archive)
                .hasCauseInstanceOf(DirectoryNotEmptyException.class);
        assertThat(memberNames(parent)).containsExactly("out.mzpeak");
        assertThat(memberNames(archive)).containsExactly("keep.txt");
    }

    @Test
    void aDirectoryWriteOntoAFileIsWrapped(@TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(SMALL);
        Path file = Files.writeString(tmp.resolve("occupied.mzpeak"), "x");

        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(file, s.spectra, s.chromatograms))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("Failed to write mzPeak dataset to " + file)
                .hasCauseInstanceOf(FileAlreadyExistsException.class);
        assertThatThrownBy(() -> MzPeakWriter.writeDirectoryNumpress(file, s.spectra, s.chromatograms))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("Failed to write Numpress mzPeak dataset to " + file)
                .hasCauseInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(file)).isEqualTo("x");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /**
     * Writes once with the default row groups and once with {@code size}, asserts that every member keeps its rows,
     * schema, key-values and records, and returns the row-group part of each member's detail for the small write.
     */
    private static Map<String, String> compareWithSmallRowGroups(Path dir, Consumer<Path> write, String size) {
        assertThat(System.getProperty(ROW_GROUP_SIZE)).as("%s before the test", ROW_GROUP_SIZE).isNull();
        Path plain = dir.resolve("default.mzpeak");
        write.accept(plain);
        Path split = dir.resolve("split.mzpeak");
        System.setProperty(ROW_GROUP_SIZE, size);
        try {
            write.accept(split);
        } finally {
            System.clearProperty(ROW_GROUP_SIZE);
        }
        Map<String, ContentDigest> before = WriterSnapshotTest.snapshot(plain, false).members();
        Map<String, ContentDigest> after = WriterSnapshotTest.snapshot(split, false).members();
        assertThat(after.keySet()).isEqualTo(before.keySet());
        Map<String, String> rowGroups = new TreeMap<>();
        for (Map.Entry<String, ContentDigest> e : after.entrySet()) {
            String name = e.getKey();
            if (name.equals("mzpeak_index.json")) {
                // Only the checksums of the re-grouped members differ (WR-1).
                assertThat(WriterManifestConformanceTest.indexWithoutChecksums(split))
                        .isEqualTo(WriterManifestConformanceTest.indexWithoutChecksums(plain));
                continue;
            }
            assertThat(withoutRowGroups(e.getValue().detail())).as("detail of %s", name)
                    .isEqualTo(withoutRowGroups(before.get(name).detail()));
            assertThat(before.get(name).detail()).as("default detail of %s", name).startsWith("row_groups=1 ");
            rowGroups.put(name, e.getValue().detail().substring(0, e.getValue().detail().indexOf(' ')));
        }
        return rowGroups;
    }

    private static String withoutRowGroups(String detail) {
        return detail.replaceFirst("^row_groups=[0-9]+ ", "");
    }

    private static SpectrumDescription description(long index, SignalContinuity continuity, double rt) {
        return new SpectrumDescription(index, "synthetic=" + index, 1, rt, null, continuity, 0, 0, List.of(),
                List.of(), List.of());
    }

    private static Spectrum withScans(Spectrum s, List<ScanEvent> scans) {
        SpectrumDescription d = s.description();
        return new Spectrum(new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), scans, d.precursors(),
                d.parameters()), s.mz(), s.intensity(), s.peaks());
    }

    private static JsonNode manifest(Path dir) throws IOException {
        return MAPPER.readTree(Files.readAllBytes(dir.resolve("mzpeak_index.json")));
    }

    private static List<String> manifestFiles(Path dir) throws IOException {
        List<String> files = new ArrayList<>();
        for (JsonNode f : manifest(dir).path("files")) {
            files.add(f.path("name").asText() + "|" + f.path("entity_type").asText() + "|"
                    + f.path("data_kind").asText());
        }
        return files;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> memberNames(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString()).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Group> rows(Path file) {
        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(file, rows::add);
        return rows;
    }

    private static Map<Long, Group> metadataRows(Path file) {
        Map<Long, Group> rows = new LinkedHashMap<>();
        for (Group row : rows(file)) {
            rows.put(ParquetGroups.optLong(ParquetGroups.optGroup(row, "spectrum"), "index"), row);
        }
        return rows;
    }

    private static List<Long> indexValues(Path file, String group) {
        List<Long> values = new ArrayList<>();
        for (Group row : rows(file)) {
            values.add(ParquetGroups.optLong(ParquetGroups.optGroup(row, group), "spectrum_index"));
        }
        return values;
    }

    /** The names of the fields that carry a value in {@code group}, in schema order. */
    private static List<String> fieldsPresent(Group group) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < group.getType().getFieldCount(); i++) {
            if (group.getFieldRepetitionCount(i) > 0) {
                names.add(group.getType().getFieldName(i));
            }
        }
        return names;
    }
}
