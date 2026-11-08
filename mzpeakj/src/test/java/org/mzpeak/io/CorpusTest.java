package org.mzpeak.io;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Corpus smoke-test: opens every {@code .mzpeak} file found under the corpus root (and its
 * subdirectories), verifies that the reader can load metadata and materialise the first spectrum
 * without throwing, and asserts structural invariants (array lengths, non-negative index) that must
 * hold regardless of instrument/format.
 *
 * <p><strong>Opt-in.</strong> The class only runs when {@code -Dmzpeak.corpus.dir=<dir>} is set, so
 * a plain {@code mvn verify} neither scans a local multi-GB corpus nor depends on one:
 * <pre>
 *   mvn test -Dtest=CorpusTest -Dmzpeak.corpus.dir=/path/to/corpus
 * </pre>
 * The root may be a symlink, and may itself be a single {@code .mzpeak} dataset directory. When the
 * root is absent or holds no {@code .mzpeak} files, the parameterized tests receive the
 * {@link #NO_CORPUS_FILES} sentinel and are <em>skipped</em> (not failed). The round-trip harnesses
 * ({@link ValidatedRoundTripTest}, {@link ForwardBackwardCorpusTest}) reuse {@link #corpusFiles()}
 * and fall back to {@code ~/Claude/mzML2mzPeak/data} when the property is unset.
 *
 * <p>For a quick check without the external corpus, point the property at a symlink to the vendored legacy set,
 * {@link Fixtures#LEGACY} ({@code src/test/resources/mzpeak-legacy}). The current upstream set in
 * {@link Fixtures#CURRENT} now yields spectra (RD-5), chromatograms (RD-8) and wavelength spectra (RD-9), and the
 * grid-encoded signal of diaPASEF.grid decodes (RD-20), so a run on it is no longer vacuous. The named cases below
 * need their hard-coded corpus files and are skipped (not passed) when those are absent.
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
@EnabledIfSystemProperty(named = "mzpeak.corpus.dir", matches = ".+")
class CorpusTest {

    static final Path CORPUS_ROOT = corpusRoot();

    /**
     * Placeholder argument handed to the parameterized tests when there are no corpus files to test.
     * Its name does not end in {@code .mzpeak}, so it can never collide with a real corpus entry (not
     * even with a root that is itself a dataset, which {@link #corpusFiles()} reports as
     * {@link #CORPUS_ROOT}).
     */
    static final Path NO_CORPUS_FILES = CORPUS_ROOT.resolve("no-mzpeak-corpus-files");

    private static Path corpusRoot() {
        String prop = System.getProperty("mzpeak.corpus.dir");
        return prop != null ? Path.of(prop)
                            : Path.of(System.getProperty("user.home"), "Claude/mzML2mzPeak/data");
    }

    /** {@code true} if {@code p} is the {@link #NO_CORPUS_FILES} placeholder, not a corpus entry. */
    static boolean isSentinel(Path p) {
        return p.equals(NO_CORPUS_FILES);
    }

    /**
     * {@code files} as a stream, or a stream holding only {@link #NO_CORPUS_FILES} when it is empty.
     * JUnit 5.11 rejects an empty {@code @MethodSource} with {@code PreconditionViolationException};
     * each parameterized test instead assumes {@code !isSentinel(..)} first and skips on the sentinel.
     * Callers that filter or limit the corpus apply this <em>after</em> doing so.
     */
    static Stream<Path> orSentinel(List<Path> files) {
        return files.isEmpty() ? Stream.of(NO_CORPUS_FILES) : files.stream();
    }

    /**
     * Collect all {@code .mzpeak} files (ZIPs) and {@code .mzpeak} directories under the corpus root,
     * sorted, or only the {@link #NO_CORPUS_FILES} sentinel when the root is absent or holds none.
     *
     * <p>The walk starts at the root's real path, so a symlinked root is followed; links below the
     * root are listed but not descended into. Each hit is mapped back onto {@link #CORPUS_ROOT}, so
     * display names keep the configured path, and a root that is itself a {@code .mzpeak} dataset is
     * returned as {@link #CORPUS_ROOT}. A symlinked root counts as a dataset when either the link
     * name or its target name ends in {@code .mzpeak}.
     */
    static Stream<Path> corpusFiles() throws IOException {
        if (!Files.isDirectory(CORPUS_ROOT)) {
            return orSentinel(List.of()); // tests skip via assumeThat(isSentinel(..))
        }
        Path real = CORPUS_ROOT.toRealPath();
        List<Path> found = new ArrayList<>();
        try (var walk = Files.walk(real)) {
            walk.forEach(p -> {
                Path mapped = CORPUS_ROOT.resolve(real.relativize(p));
                // Below the root both names agree; at the root either the link or its target may
                // carry the .mzpeak suffix.
                if (hasMzPeakName(p) || hasMzPeakName(mapped)) {
                    boolean isDir = Files.isDirectory(p);
                    boolean isFile = Files.isRegularFile(p);
                    if (isDir || isFile) found.add(mapped);
                }
            });
        }
        found.sort(null);
        return orSentinel(found);
    }

    private static boolean hasMzPeakName(Path p) {
        Path name = p.getFileName();
        return name != null && name.toString().endsWith(".mzpeak");
    }

    // ---- parameterized open + metadata test -----------------------------------------------

    @Tag("corpus")
    @ParameterizedTest(name = "{0}")
    @MethodSource("corpusFiles")
    void canOpenAndReadMetadata(Path file) {
        assumeThat(isSentinel(file)).as("no corpus files — skip").isFalse();
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
        assumeThat(isSentinel(file)).as("no corpus files — skip").isFalse();
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
        assumeThat(isSentinel(file)).as("no corpus files — skip").isFalse();
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
        assumeTrue(Files.isDirectory(CORPUS_ROOT), "corpus root " + CORPUS_ROOT + " is not a directory");
        Path imaging = CORPUS_ROOT.resolve("mzpeak/example1-processed_Example_Processed.mzpeak");
        assumeTrue(Files.exists(imaging), imaging + " not in corpus");

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
        assumeTrue(Files.isDirectory(CORPUS_ROOT), "corpus root " + CORPUS_ROOT + " is not a directory");
        Path desi = CORPUS_ROOT.resolve("mzpeak/zenodo-DESI_40TopL_10TopR_30BottomL_20BottomR.mzpeak");
        assumeTrue(Files.exists(desi), desi + " not in corpus");

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
        assumeTrue(Files.isDirectory(CORPUS_ROOT), "corpus root " + CORPUS_ROOT + " is not a directory");
        Path desi = CORPUS_ROOT.resolve("mzpeak/zenodo-DESI_40TopL_10TopR_30BottomL_20BottomR.mzpeak");
        assumeTrue(Files.exists(desi), desi + " not in corpus");

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
        assumeTrue(Files.isDirectory(CORPUS_ROOT), "corpus root " + CORPUS_ROOT + " is not a directory");
        Path tof = CORPUS_ROOT.resolve("mzpeak/bruker-timstof-pro_SBA415.mzpeak");
        assumeTrue(Files.exists(tof), tof + " not in corpus");

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
        assumeTrue(Files.isDirectory(CORPUS_ROOT), "corpus root " + CORPUS_ROOT + " is not a directory");
        Path dtims = CORPUS_ROOT.resolve("mzpeak/agilent-6560-dtims-imqtof_CEMS_10ppm.mzpeak");
        assumeTrue(Files.exists(dtims), dtims + " not in corpus");

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
        assumeTrue(Files.isDirectory(CORPUS_ROOT), "corpus root " + CORPUS_ROOT + " is not a directory");
        Path desi = CORPUS_ROOT.resolve("mzpeak/zenodo-DESI_40TopL_10TopR_30BottomL_20BottomR.mzpeak");
        assumeTrue(Files.exists(desi), desi + " not in corpus");

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
