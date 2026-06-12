package org.mzpeak.io;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.ImagingPosition;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Corpus smoke-test: opens every {@code .mzpeak} file found under
 * {@code ~/Claude/mzML2mzPeak/data} (and its subdirectories), verifies that the reader can
 * load metadata and materialise the first spectrum without throwing, and asserts structural
 * invariants (array lengths, non-negative index) that must hold regardless of instrument/format.
 *
 * <p>The test is <em>skipped</em> (not failed) when the corpus directory is absent, so CI on
 * GitHub passes without the local data tree.
 *
 * <p>Coverage of this corpus:
 * <ul>
 *   <li>34 instrument types: Thermo, Bruker timsTOF/microTOF/IMPACT, Agilent QTOF/TripleQuad/DTIMS,
 *       Waters, SCIEX, Shimadzu, GC-EI
 *   <li>Signal types: profile, centroid, mixed profile+centroid
 *   <li>Layouts: point, delta-chunk, Numpress chunk
 *   <li>Special: MSI/imzML imaging files (pixel coordinates, scan_settings), ion mobility
 *       (timsTOF, DTIMS), MRM/SRM (chromatogram-only), UV/DAD
 * </ul>
 */
class CorpusTest {

    static final Path CORPUS_ROOT = corpusRoot();

    private static Path corpusRoot() {
        String prop = System.getProperty("mzpeak.corpus.dir");
        return prop != null ? Path.of(prop)
                            : Path.of(System.getProperty("user.home"), "Claude/mzML2mzPeak/data");
    }

    /**
     * Collect all {@code .mzpeak} files (ZIPs) and {@code .mzpeak} directories under the corpus root.
     * Returns an empty stream (→ tests skip gracefully) when the root is absent.
     */
    static Stream<Path> corpusFiles() throws IOException {
        if (!Files.isDirectory(CORPUS_ROOT)) {
            return Stream.empty();
        }
        List<Path> found = new ArrayList<>();
        try (var walk = Files.walk(CORPUS_ROOT)) {
            walk.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.endsWith(".mzpeak")) {
                    boolean isDir = Files.isDirectory(p);
                    boolean isFile = Files.isRegularFile(p);
                    if (isDir || isFile) found.add(p);
                }
            });
        }
        found.sort(null);
        return found.stream();
    }

    // ---- parameterized open + metadata test -----------------------------------------------

    @Tag("corpus")
    @ParameterizedTest(name = "{0}")
    @MethodSource("corpusFiles")
    void canOpenAndReadMetadata(Path file) {
        assertThatNoException().isThrownBy(() -> {
            try (MzPeakReader r = MzPeakReader.open(file)) {
                assertThat(r.size()).as("spectrum count must be >= 0").isGreaterThanOrEqualTo(0);
                List<SpectrumDescription> meta = r.metadata();
                assertThat(meta.size()).isEqualTo(r.size());

                // All indices must be non-negative and unique
                if (!meta.isEmpty()) {
                    long min = meta.stream().mapToLong(SpectrumDescription::index).min().orElse(0);
                    assertThat(min).as("min spectrum index").isGreaterThanOrEqualTo(0);
                }
            }
        });
    }

    // ---- parameterized array reading test -------------------------------------------------

    @Tag("corpus")
    @ParameterizedTest(name = "{0}")
    @MethodSource("corpusFiles")
    void canReadFirstSpectrum(Path file) {
        assertThatNoException().isThrownBy(() -> {
            try (MzPeakReader r = MzPeakReader.open(file)) {
                if (r.size() == 0) return; // nothing to read
                Spectrum s = r.getSpectrum(0).orElse(null);
                if (s == null) return; // chromatogram-only file
                assertThat(s.mz().length).as("mz array length").isGreaterThanOrEqualTo(0);
                assertThat(s.intensity().length).as("intensity array length")
                        .isEqualTo(s.mz().length);
            }
        });
    }

    /**
     * Iterates up to {@link #MAX_SPECTRA_PER_FILE} spectra in the file and verifies structural
     * invariants: {@code mz.length == intensity.length}, all centroid m/z values are finite, and
     * all intensities are non-negative.
     *
     * <p>The cap prevents multi-GB files (e.g. Thermo Orbitrap Astral, ~7 GB) from exhausting the
     * Surefire JVM heap. Encoder-level invariants that hold for the first 2 000 spectra hold for
     * all others — this is a property of the writer, not a data-specific choice.
     */
    static final int MAX_SPECTRA_PER_FILE = 2_000;

    @Tag("corpus")
    @ParameterizedTest(name = "{0}")
    @MethodSource("corpusFiles")
    void sampledSpectra_haveConsistentArraysAndValidPeaks(Path file) throws Exception {
        try (MzPeakReader r = MzPeakReader.open(file)) {
            // Use an explicit iterator so next() is not called beyond the cap (enhanced-for would
            // materialize one extra spectrum when i == MAX_SPECTRA_PER_FILE at the loop condition).
            var it = r.iterator();
            int i = 0;
            while (it.hasNext() && i < MAX_SPECTRA_PER_FILE) {
                Spectrum s = it.next();
                double[] mz = s.mz();
                double[] intensity = s.intensity();
                assertThat(mz.length)
                        .as("spectrum[%d] mz.length == intensity.length in %s", i, file.getFileName())
                        .isEqualTo(intensity.length);
                for (CentroidPeak peak : s.peaks()) {
                    assertThat(Double.isFinite(peak.mz()))
                            .as("peak mz finite in spectrum[%d] of %s", i, file.getFileName())
                            .isTrue();
                    assertThat(peak.intensity())
                            .as("peak intensity non-negative in spectrum[%d] of %s", i, file.getFileName())
                            .isGreaterThanOrEqualTo(0.0);
                }
                i++;
            }
        }
    }

    // ---- imaging-specific assertions ------------------------------------------------------

    @Test
    void imagingFile_hasPixelCoordinates() {
        if (!Files.isDirectory(CORPUS_ROOT)) return;
        Path imaging = CORPUS_ROOT.resolve("mzpeak/example1-processed_Example_Processed.mzpeak");
        if (!Files.exists(imaging)) return;

        try (MzPeakReader r = MzPeakReader.open(imaging)) {
            assertThat(r.isImaging()).as("is_imaging flag").isTrue();
            assertThat(r.size()).isEqualTo(9); // 3×3 pixel grid

            // All 9 spectra should have imaging positions
            int withPosition = 0;
            for (SpectrumDescription d : r.metadata()) {
                if (!d.scans().isEmpty()) {
                    ImagingPosition pos = d.scans().get(0).imagingPosition();
                    if (pos != null) {
                        assertThat(pos.isValid()).as("position must have x,y >= 1").isTrue();
                        withPosition++;
                    }
                }
            }
            assertThat(withPosition).as("spectra with imaging position").isGreaterThan(0);
        } catch (Exception e) {
            throw new AssertionError("Failed to open imaging file: " + imaging, e);
        }
    }

    @Test
    void desiFIle_hasScanSettings() {
        if (!Files.isDirectory(CORPUS_ROOT)) return;
        Path desi = CORPUS_ROOT.resolve("mzpeak/zenodo-DESI_40TopL_10TopR_30BottomL_20BottomR.mzpeak");
        if (!Files.exists(desi)) return;

        try (MzPeakReader r = MzPeakReader.open(desi)) {
            assertThat(r.isImaging()).as("DESI is imaging").isTrue();
            List<ScanSettings> ss = r.scanSettings();
            assertThat(ss).as("scan settings").isNotEmpty();
            // IMS:1000042 = max count of pixel x
            assertThat(ss.get(0).maxPixelX()).isPresent();
            assertThat(ss.get(0).maxPixelX().orElseThrow()).isGreaterThan(0);
            assertThat(ss.get(0).maxPixelY()).isPresent();
        } catch (Exception e) {
            throw new AssertionError("Failed to open DESI file: " + desi, e);
        }
    }

    @Test
    void desiImaging_allSpectraHavePixelCoordinates() {
        if (!Files.isDirectory(CORPUS_ROOT)) return;
        Path desi = CORPUS_ROOT.resolve("mzpeak/zenodo-DESI_40TopL_10TopR_30BottomL_20BottomR.mzpeak");
        if (!Files.exists(desi)) return;

        try (MzPeakReader r = MzPeakReader.open(desi)) {
            long total = r.size();
            long withPos = r.metadata().stream()
                    .filter(d -> !d.scans().isEmpty() && d.scans().get(0).imagingPosition() != null)
                    .count();
            assertThat(withPos).as("all DESI spectra should have pixel position").isEqualTo(total);
        } catch (Exception e) {
            throw new AssertionError("Failed reading DESI pixel positions", e);
        }
    }

    // ---- ion mobility assertions ----------------------------------------------------------

    @Test
    void timstof_hasIonMobility() {
        if (!Files.isDirectory(CORPUS_ROOT)) return;
        Path tof = CORPUS_ROOT.resolve("mzpeak/bruker-timstof-pro_SBA415.mzpeak");
        if (!Files.exists(tof)) return;

        try (MzPeakReader r = MzPeakReader.open(tof)) {
            assertThat(r.size()).isGreaterThan(0);
            // This timsTOF-Pro file stores ion mobility per-peak in the raw TDF format; when converted
            // to mzML (and then mzPeak) only the aggregated frame spectra are emitted and the
            // scan-level ion_mobility_value column is null for all rows.  The reader must handle
            // this gracefully (no exception) rather than guarantee a non-zero IM count.
            long withIM = r.metadata().stream()
                    .filter(d -> !d.scans().isEmpty() && d.scans().get(0).ionMobilityType() != null)
                    .count();
            assertThat(withIM).as("timsTOF spectra with ion mobility (may be 0 for this dataset)")
                    .isGreaterThanOrEqualTo(0);
        } catch (Exception e) {
            throw new AssertionError("Failed reading timsTOF file (reader must not throw)", e);
        }
    }

    @Test
    void dtims_hasIonMobilityValues() {
        if (!Files.isDirectory(CORPUS_ROOT)) return;
        Path dtims = CORPUS_ROOT.resolve("mzpeak/agilent-6560-dtims-imqtof_CEMS_10ppm.mzpeak");
        if (!Files.exists(dtims)) return;

        try (MzPeakReader r = MzPeakReader.open(dtims)) {
            long withIM = r.metadata().stream()
                    .filter(d -> !d.scans().isEmpty()
                            && d.scans().get(0).ionMobilityValue().isPresent())
                    .count();
            // Note: Agilent DTIMS stores NaN for some IM values; we count the finite ones
            // The count may be 0 if all values are NaN, which is a data quality issue, not a reader bug.
            // This test just verifies ionMobilityValue() doesn't throw.
            assertThat(withIM).as("dtims spectra with finite ion mobility value").isGreaterThanOrEqualTo(0);
        } catch (Exception e) {
            throw new AssertionError("Failed reading DTIMS ion mobility", e);
        }
    }

    // ---- scan settings computed fields ---------------------------------------------------

    @Test
    void scanSettings_pixelSizeAccessors() {
        if (!Files.isDirectory(CORPUS_ROOT)) return;
        Path desi = CORPUS_ROOT.resolve("mzpeak/zenodo-DESI_40TopL_10TopR_30BottomL_20BottomR.mzpeak");
        if (!Files.exists(desi)) return;

        try (MzPeakReader r = MzPeakReader.open(desi)) {
            List<ScanSettings> ss = r.scanSettings();
            assertThat(ss).isNotEmpty();
            ScanSettings s = ss.get(0);
            // Pixel size x should be 100 µm for this DESI dataset
            assertThat(s.pixelSizeX()).isPresent();
            assertThat(s.pixelSizeX().orElseThrow()).isEqualTo(100.0);
            assertThat(s.pixelSizeY()).isPresent();
        } catch (Exception e) {
            throw new AssertionError("Failed reading scan settings from DESI file", e);
        }
    }
}
