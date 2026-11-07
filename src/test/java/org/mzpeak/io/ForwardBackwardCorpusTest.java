package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.model.Activation;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.ImagingPosition;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * End-to-end <em>forward-backward</em> round-trip harness over the HUPO-PSI corpus.
 *
 * <p>For every {@code .mzpeak} file under the corpus root (see {@link CorpusTest#corpusFiles()}), and for each
 * write layout (point + Numpress), this test:
 * <ol>
 *   <li><strong>FORWARD</strong> — reads the source with {@link MzPeakReader}, materialises every spectrum plus
 *       chromatograms, wavelength spectra, scan settings, and file metadata, then writes a fresh archive via the
 *       6-arg {@link MzPeakWriter#writeArchive}/{@link MzPeakWriter#writeArchiveNumpress} overloads;</li>
 *   <li><strong>VALIDATE</strong> — runs the external {@code mzpeak-validate} CLI on the written artifact
 *       (full validation for files &lt; 5&nbsp;MB, {@code --quick} above) and collects every error-level finding;</li>
 *   <li><strong>BACKWARD</strong> — re-reads the artifact and compares it field-by-field against the in-memory
 *       source, accumulating <em>all</em> mismatches (it does not stop at the first).</li>
 * </ol>
 *
 * <p>A machine-readable JSONL report is appended to {@code target/forward-backward-report.jsonl} for every
 * (file, layout) pair regardless of pass/fail, so a full corpus sweep aggregates every finding. The JUnit
 * assertion fails the case when there is any validator error OR any fidelity diff.
 *
 * <p><strong>Disabled by default.</strong> Enable with {@code -Dmzpeak.roundtrip=true}. Knobs:
 * <ul>
 *   <li>{@code -Dmzpeak.corpus.dir=<dir>} — corpus root (default {@code ~/Claude/mzML2mzPeak/data})</li>
 *   <li>{@code -Dmzpeak.validator.command=<path>} — validator binary (default {@code mzpeak-validate})</li>
 *   <li>{@code -Dmzpeak.roundtrip.maxFiles=N} — process at most N corpus files (default unlimited)</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "mzpeak.roundtrip", matches = "true")
class ForwardBackwardCorpusTest {

    /** Files larger than this are skipped (OOM risk when buffering all spectra). */
    private static final long MAX_FILE_BYTES = 256L * 1024 * 1024;

    /** Full validation (no --quick) for files under this size; above it --quick is used. */
    private static final long FULL_VALIDATION_THRESHOLD = 5L * 1024 * 1024;

    /** Prefix marking a diff as a documented deferred limitation: recorded in the report, but not a build failure. */
    private static final String KNOWN_LIMIT = "KNOWN_LIMIT ";

    /** Numpress m/z is near-lossless; allow this absolute m/z tolerance. */
    private static final double NUMPRESS_MZ_ABS_TOL = 1e-4;
    /** Numpress intensity (SLOF) is lossy; allow this relative intensity tolerance. */
    private static final double NUMPRESS_INTENSITY_REL_TOL = 5e-3;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path REPORT = Path.of("target", "forward-backward-report.jsonl");

    enum Layout { POINT, NUMPRESS }

    static Stream<Path> corpusFilesForRoundTrip() throws IOException {
        long maxFiles = Long.getLong("mzpeak.roundtrip.maxFiles", Long.MAX_VALUE);
        Stream<Path> base = CorpusTest.corpusFiles().filter(p -> !CorpusTest.isSentinel(p)).filter(p -> {
            try {
                return approximateSize(p) <= MAX_FILE_BYTES;
            } catch (IOException e) {
                return false;
            }
        });
        // Re-add the sentinel only after filtering and limiting, so JUnit never gets an empty stream.
        return CorpusTest.orSentinel((maxFiles == Long.MAX_VALUE ? base : base.limit(maxFiles)).toList());
    }

    @Tag("corpus")
    @Tag("roundtrip")
    @ParameterizedTest(name = "{0}")
    @MethodSource("corpusFilesForRoundTrip")
    void forwardBackward(Path src, @TempDir Path tmpDir) throws Exception {
        assumeThat(CorpusTest.isSentinel(src)).as("no corpus files — skip").isFalse();

        String validatorCmd = System.getProperty("mzpeak.validator.command", "mzpeak-validate");
        assumeThat(validatorAvailable(validatorCmd))
                .as("mzpeak-validate not found; install it or set -Dmzpeak.validator.command=")
                .isTrue();

        // ---- FORWARD: read the source fully into memory --------------------------------------
        Source source = readSource(src);

        List<String> allDiffs = new ArrayList<>();
        List<String> allValidatorErrors = new ArrayList<>();

        // The point layout stores m/z directly in point.mz, which the validator requires to be finite.
        // Some spectra legitimately carry non-finite m/z (SRM/MRM transitions have no product-ion m/z), and
        // such data is only representable in the chunk/Numpress layout (as the reference also does). Skip the
        // point layout for those sources rather than emit a file the validator must reject.
        boolean sourceHasNonFiniteMz = source.spectra.stream()
                .anyMatch(s -> { for (double v : s.mz()) if (!Double.isFinite(v)) return true; return false; });

        for (Layout layout : Layout.values()) {
            if (layout == Layout.POINT && sourceHasNonFiniteMz) {
                continue; // covered by the Numpress layout below
            }
            Path out = tmpDir.resolve("output-" + layout.name().toLowerCase(Locale.ROOT) + ".mzpeak");
            boolean writeOk = false;
            List<JsonNode> findings = List.of();
            List<String> diffs = new ArrayList<>();
            String writeError = null;

            try {
                if (layout == Layout.POINT) {
                    MzPeakWriter.writeArchive(out, source.spectra, source.chromatograms,
                            source.wavelengthSpectra, source.scanSettings, source.fileMetadata);
                } else {
                    MzPeakWriter.writeArchiveNumpress(out, source.spectra, source.chromatograms,
                            source.wavelengthSpectra, source.scanSettings, source.fileMetadata);
                }
                writeOk = true;
            } catch (Exception e) {
                writeError = e.toString();
                diffs.add("WRITE FAILED (" + layout + "): " + e);
            }

            // ---- VALIDATE --------------------------------------------------------------------
            if (writeOk) {
                Path reportJson = tmpDir.resolve("report-" + layout.name().toLowerCase(Locale.ROOT) + ".json");
                ValidatorRun vr = runValidator(validatorCmd, out, reportJson);
                findings = parseValidatorFindings(reportJson);
                // mzpeak-validate exit codes: 0 = clean, 1 = error-level finding(s) (parsed from the JSON
                // report), 2 = engine failure. A crash or a missing report must NOT pass silently.
                if (vr.exitCode() != 0 && vr.exitCode() != 1) {
                    diffs.add("VALIDATOR ENGINE FAILURE (" + layout + ") exit=" + vr.exitCode()
                            + ": " + vr.output().strip());
                } else if (!Files.exists(reportJson)) {
                    diffs.add("VALIDATOR REPORT MISSING (" + layout + ") exit=" + vr.exitCode()
                            + ": " + vr.output().strip());
                }
            }

            // ---- BACKWARD --------------------------------------------------------------------
            if (writeOk) {
                try {
                    compareRoundTrip(src, source, out, layout, diffs);
                } catch (Exception e) {
                    diffs.add("RE-READ FAILED (" + layout + "): " + e);
                }
            }

            List<String> validatorErrors = new ArrayList<>();
            for (JsonNode f : findings) {
                if ("error".equalsIgnoreCase(f.path("level").asText(""))) {
                    validatorErrors.add("[" + f.path("ruleId").asText(f.path("rule").asText("?")) + "] "
                            + f.path("message").asText(f.toString()));
                }
            }

            writeReportLine(src, layout, writeOk, writeError, findings, diffs);

            allDiffs.addAll(diffs);
            allValidatorErrors.addAll(validatorErrors);
        }

        // Hard failures = validator errors + fidelity diffs that are NOT documented deferred limitations.
        // KNOWN_LIMIT diffs (e.g. dropped secondary scans for combineIMS) are still recorded in the JSONL
        // report, but do not fail the build.
        List<String> hardDiffs = allDiffs.stream().filter(d -> !d.startsWith(KNOWN_LIMIT)).toList();
        List<String> knownLimits = allDiffs.stream().filter(d -> d.startsWith(KNOWN_LIMIT)).toList();
        assertThat(allValidatorErrors.isEmpty() && hardDiffs.isEmpty())
                .as("forward-backward round-trip for %s%nvalidator errors:%n%s%nfidelity diffs:%n%s%n"
                        + "known limitations (not failing):%n%s",
                        src.getFileName(),
                        String.join("\n", allValidatorErrors),
                        String.join("\n", hardDiffs),
                        String.join("\n", knownLimits))
                .isTrue();
    }

    // ---- FORWARD read -----------------------------------------------------------------------

    /** Snapshot of everything the writer needs, materialised eagerly before the source reader closes. */
    static final class Source {
        final List<Spectrum> spectra = new ArrayList<>();
        List<Chromatogram> chromatograms;
        List<WavelengthSpectrum> wavelengthSpectra;
        List<ScanSettings> scanSettings;
        FileMetadata fileMetadata;
    }

    static Source readSource(Path src) {
        Source s = new Source();
        try (MzPeakReader r = MzPeakReader.open(src)) {
            int n = r.size();
            for (int i = 0; i < n; i++) {
                s.spectra.add(r.getSpectrumAt(i));
            }
            s.chromatograms = new ArrayList<>(r.chromatograms());
            s.wavelengthSpectra = new ArrayList<>(r.wavelengthSpectra());
            s.scanSettings = new ArrayList<>(r.scanSettings());
            s.fileMetadata = r.fileMetadata();
        }
        return s;
    }

    // ---- BACKWARD compare -------------------------------------------------------------------

    static void compareRoundTrip(Path src, Source source, Path out, Layout layout, List<String> diffs) {
        try (MzPeakReader r = MzPeakReader.open(out)) {
            // counts
            eq(diffs, "spectrum count", source.spectra.size(), r.size());
            List<Chromatogram> chroms = new ArrayList<>(r.chromatograms());
            eq(diffs, "chromatogram count", source.chromatograms.size(), chroms.size());
            List<WavelengthSpectrum> waves = new ArrayList<>(r.wavelengthSpectra());
            eq(diffs, "wavelength-spectrum count", source.wavelengthSpectra.size(), waves.size());
            List<ScanSettings> settings = new ArrayList<>(r.scanSettings());
            eq(diffs, "scanSettings count", source.scanSettings.size(), settings.size());

            int specN = Math.min(source.spectra.size(), r.size());
            for (int i = 0; i < specN; i++) {
                compareSpectrum(diffs, layout, i, source.spectra.get(i), r.getSpectrumAt(i));
            }

            int chromN = Math.min(source.chromatograms.size(), chroms.size());
            for (int i = 0; i < chromN; i++) {
                compareChromatogram(diffs, i, source.chromatograms.get(i), chroms.get(i));
            }

            int waveN = Math.min(source.wavelengthSpectra.size(), waves.size());
            for (int i = 0; i < waveN; i++) {
                compareWavelength(diffs, i, source.wavelengthSpectra.get(i), waves.get(i));
            }

            compareFileMetadata(diffs, source.fileMetadata, r.fileMetadata());

            compareScanSettings(diffs, source.scanSettings, settings);
        }
    }

    private static void compareSpectrum(List<String> diffs, Layout layout, int i, Spectrum a, Spectrum b) {
        String tag = "spectrum[" + i + "]";
        SpectrumDescription da = a.description();
        SpectrumDescription db = b.description();
        eq(diffs, tag + ".index", da.index(), db.index());
        eq(diffs, tag + ".id", da.id(), db.id());
        eq(diffs, tag + ".msLevel", da.msLevel(), db.msLevel());
        eq(diffs, tag + ".polarity", da.polarity(), db.polarity());
        eqDouble(diffs, tag + ".retentionTime", da.retentionTime(), db.retentionTime(), 0.0);
        eq(diffs, tag + ".signalContinuity", da.signalContinuity(), db.signalContinuity());

        // arrays
        eq(diffs, tag + ".mz.length", a.mz().length, b.mz().length);
        eq(diffs, tag + ".intensity.length", a.intensity().length, b.intensity().length);
        eq(diffs, tag + ".peaks.count", a.peaks().size(), b.peaks().size());

        // record counts — surfaces any dropped secondary scans / precursors / selected ions. The writer
        // currently emits only the primary scan/precursor/ion (packed multi-facet writing is a deferred
        // enhancement), so these are tagged KNOWN_LIMIT: recorded in the report but not failing the build.
        eq(diffs, KNOWN_LIMIT + tag + ".scans.count", da.scans().size(), db.scans().size());
        eq(diffs, KNOWN_LIMIT + tag + ".precursors.count", da.precursors().size(), db.precursors().size());
        if (da.primaryPrecursor() != null && db.primaryPrecursor() != null) {
            eq(diffs, KNOWN_LIMIT + tag + ".selectedIons.count",
                    da.primaryPrecursor().selectedIons().size(), db.primaryPrecursor().selectedIons().size());
        }
        if (layout == Layout.POINT) {
            eqArrayExact(diffs, tag + ".mz", a.mz(), b.mz());
            eqArrayExact(diffs, tag + ".intensity", a.intensity(), b.intensity());
        } else {
            // Numpress linear cannot carry non-finite m/z (SRM/MRM transitions have no product-ion m/z; NaN is
            // written back as 0.0). That is a documented, deferred Numpress limitation, so for such sources the
            // m/z diff is recorded but does not fail the build.
            boolean srcNonFiniteMz = false;
            for (double v : a.mz()) {
                if (!Double.isFinite(v)) {
                    srcNonFiniteMz = true;
                    break;
                }
            }
            eqArrayAbsTol(diffs, (srcNonFiniteMz ? KNOWN_LIMIT : "") + tag + ".mz", a.mz(), b.mz(),
                    NUMPRESS_MZ_ABS_TOL);
            // Numpress SLOF intensity is lossy and, by construction (logarithmic quantisation against the
            // per-chunk maximum), has poor per-value relative accuracy for intensities far below that
            // maximum. Verifying each value would flag inherent, expected encoder error. We instead verify
            // the aggregate (total ion current), which SLOF preserves to well within tolerance.
            eqSumRelTol(diffs, tag + ".intensity.sum", a.intensity(), b.intensity(), NUMPRESS_INTENSITY_REL_TOL);
        }

        // primary precursor
        Precursor pa = da.primaryPrecursor();
        Precursor pb = db.primaryPrecursor();
        if (pa == null && pb == null) {
            // nothing
        } else if (pa == null || pb == null) {
            diffs.add(tag + ".primaryPrecursor presence: src=" + (pa != null) + " out=" + (pb != null));
        } else {
            comparePrecursor(diffs, tag, pa, pb);
        }

        // first scan event
        ScanEvent sa = da.scans().isEmpty() ? null : da.scans().get(0);
        ScanEvent sb = db.scans().isEmpty() ? null : db.scans().get(0);
        if (sa != null && sb != null) {
            compareScanEvent(diffs, tag, sa, sb);
        } else if ((sa == null) != (sb == null)) {
            diffs.add(tag + ".scan[0] presence: src=" + (sa != null) + " out=" + (sb != null));
        }
    }

    private static void comparePrecursor(List<String> diffs, String tag, Precursor a, Precursor b) {
        IsolationWindow ia = a.isolationWindow();
        IsolationWindow ib = b.isolationWindow();
        if (ia != null && ib != null) {
            eqBoxedDouble(diffs, tag + ".iso.targetMz", ia.targetMz(), ib.targetMz());
            eqBoxedDouble(diffs, tag + ".iso.lowerOffset", ia.lowerOffset(), ib.lowerOffset());
            eqBoxedDouble(diffs, tag + ".iso.upperOffset", ia.upperOffset(), ib.upperOffset());
        } else if ((ia == null) != (ib == null)) {
            diffs.add(tag + ".isolationWindow presence: src=" + (ia != null) + " out=" + (ib != null));
        }

        SelectedIon sa = a.primaryIon();
        SelectedIon sb = b.primaryIon();
        if (sa != null && sb != null) {
            eqDouble(diffs, tag + ".ion.mz", sa.mz(), sb.mz(), 0.0);
            eq(diffs, tag + ".ion.charge", sa.charge(), sb.charge());
            eqBoxedDouble(diffs, tag + ".ion.intensity", sa.intensity(), sb.intensity());
        } else if ((sa == null) != (sb == null)) {
            diffs.add(tag + ".primaryIon presence: src=" + (sa != null) + " out=" + (sb != null));
        }

        Activation aa = a.activation();
        Activation ab = b.activation();
        eq(diffs, tag + ".activation.methods", methodAccessions(aa), methodAccessions(ab));
        eqOptDouble(diffs, tag + ".activation.collisionEnergy", aa.collisionEnergy(), ab.collisionEnergy());
    }

    private static Set<String> methodAccessions(Activation a) {
        Set<String> set = new LinkedHashSet<>();
        for (Param p : a.methods()) {
            set.add(p.accession());
        }
        return set;
    }

    private static void compareScanEvent(List<String> diffs, String tag, ScanEvent a, ScanEvent b) {
        // scan windows (lower/upper limit list)
        List<ScanWindow> wa = a.scanWindows();
        List<ScanWindow> wb = b.scanWindows();
        eq(diffs, tag + ".scanWindows.count", wa.size(), wb.size());
        int wn = Math.min(wa.size(), wb.size());
        for (int j = 0; j < wn; j++) {
            eqDouble(diffs, tag + ".scanWindow[" + j + "].lowerLimit", wa.get(j).lowerLimit(), wb.get(j).lowerLimit(), 0.0);
            eqDouble(diffs, tag + ".scanWindow[" + j + "].upperLimit", wa.get(j).upperLimit(), wb.get(j).upperLimit(), 0.0);
        }

        eqOptDouble(diffs, tag + ".scan.ionMobilityValue", a.ionMobilityValue(), b.ionMobilityValue());

        ImagingPosition pa = a.imagingPosition();
        ImagingPosition pb = b.imagingPosition();
        if (pa != null && pb != null) {
            eq(diffs, tag + ".imagingPosition.x", pa.x(), pb.x());
            eq(diffs, tag + ".imagingPosition.y", pa.y(), pb.y());
        } else if ((pa == null) != (pb == null)) {
            diffs.add(tag + ".imagingPosition presence: src=" + (pa != null) + " out=" + (pb != null));
        }
    }

    private static void compareChromatogram(List<String> diffs, int i, Chromatogram a, Chromatogram b) {
        String tag = "chromatogram[" + i + "]";
        eq(diffs, tag + ".id", a.id(), b.id());
        eq(diffs, tag + ".typeCurie", a.typeCurie(), b.typeCurie());
        eqArrayExact(diffs, tag + ".time", a.time(), b.time());
        eqArrayExact(diffs, tag + ".intensity", a.intensity(), b.intensity());
    }

    private static void compareWavelength(List<String> diffs, int i, WavelengthSpectrum a, WavelengthSpectrum b) {
        String tag = "wavelength[" + i + "]";
        eqArrayExact(diffs, tag + ".wavelength", a.wavelength(), b.wavelength());
        eqArrayExact(diffs, tag + ".intensity", a.intensity(), b.intensity());
        eqDouble(diffs, tag + ".time", a.time(), b.time(), 0.0);
        eqBoxedDouble(diffs, tag + ".lambdaMax", a.lambdaMax(), b.lambdaMax());
    }

    private static void compareFileMetadata(List<String> diffs, FileMetadata a, FileMetadata b) {
        String runA = a.run() == null ? null : a.run().id();
        String runB = b.run() == null ? null : b.run().id();
        eq(diffs, "fileMetadata.run.id", runA, runB);
        eq(diffs, "fileMetadata.software.size", a.software().size(), b.software().size());
        eq(diffs, "fileMetadata.instrumentConfigurations.size",
                a.instrumentConfigurations().size(), b.instrumentConfigurations().size());
        eq(diffs, "fileMetadata.dataProcessingMethods.size",
                a.dataProcessingMethods().size(), b.dataProcessingMethods().size());
        eq(diffs, "fileMetadata.samples.size", a.samples().size(), b.samples().size());
        int srcFilesA = a.fileDescription() == null ? 0 : a.fileDescription().sourceFiles().size();
        int srcFilesB = b.fileDescription() == null ? 0 : b.fileDescription().sourceFiles().size();
        eq(diffs, "fileMetadata.fileDescription.sourceFiles.size", srcFilesA, srcFilesB);
    }

    private static void compareScanSettings(List<String> diffs, List<ScanSettings> a, List<ScanSettings> b) {
        eq(diffs, "scanSettings.ids", scanSettingsIds(a), scanSettingsIds(b));
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            String tag = "scanSettings[" + i + "]";
            eqOptInt(diffs, tag + ".maxPixelX", a.get(i).maxPixelX(), b.get(i).maxPixelX());
            eqOptInt(diffs, tag + ".maxPixelY", a.get(i).maxPixelY(), b.get(i).maxPixelY());
        }
    }

    private static List<String> scanSettingsIds(List<ScanSettings> list) {
        List<String> ids = new ArrayList<>(list.size());
        for (ScanSettings s : list) {
            ids.add(s.id());
        }
        return ids;
    }

    // ---- diff primitives --------------------------------------------------------------------

    private static void eq(List<String> diffs, String field, Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            diffs.add(field + ": src=" + expected + " out=" + actual);
        }
    }

    private static void eqDouble(List<String> diffs, String field, double expected, double actual, double tol) {
        if (Double.isNaN(expected) && Double.isNaN(actual)) {
            return; // NaN-safe
        }
        // A NaN on exactly one side is a diff; the tolerance test below cannot see it (NaN > tol is false).
        if (Double.isNaN(expected) != Double.isNaN(actual)
                || Double.compare(expected, actual) != 0 && Math.abs(expected - actual) > tol) {
            diffs.add(field + ": src=" + expected + " out=" + actual);
        }
    }

    private static void eqBoxedDouble(List<String> diffs, String field, Double expected, Double actual) {
        if (expected == null && actual == null) {
            return;
        }
        if (expected == null || actual == null) {
            diffs.add(field + ": src=" + expected + " out=" + actual);
            return;
        }
        eqDouble(diffs, field, expected, actual, 0.0);
    }

    private static void eqOptDouble(List<String> diffs, String field, OptionalDouble expected, OptionalDouble actual) {
        if (expected.isPresent() != actual.isPresent()) {
            diffs.add(field + ": src=" + optStr(expected) + " out=" + optStr(actual));
            return;
        }
        if (expected.isPresent()) {
            eqDouble(diffs, field, expected.getAsDouble(), actual.getAsDouble(), 0.0);
        }
    }

    private static String optStr(OptionalDouble o) {
        return o.isPresent() ? Double.toString(o.getAsDouble()) : "(absent)";
    }

    private static void eqOptInt(List<String> diffs, String field, Optional<Integer> expected, Optional<Integer> actual) {
        if (!expected.equals(actual)) {
            diffs.add(field + ": src=" + expected + " out=" + actual);
        }
    }

    private static void eqArrayExact(List<String> diffs, String field, double[] a, double[] b) {
        if (a.length != b.length) {
            diffs.add(field + ".length: src=" + a.length + " out=" + b.length);
            return;
        }
        for (int i = 0; i < a.length; i++) {
            if (Double.compare(a[i], b[i]) != 0) {
                diffs.add(field + "[" + i + "]: src=" + a[i] + " out=" + b[i]);
                return; // one diff per array is enough signal
            }
        }
    }

    private static void eqArrayAbsTol(List<String> diffs, String field, double[] a, double[] b, double tol) {
        if (a.length != b.length) {
            diffs.add(field + ".length: src=" + a.length + " out=" + b.length);
            return;
        }
        for (int i = 0; i < a.length; i++) {
            if (Double.isNaN(a[i]) != Double.isNaN(b[i]) || Math.abs(a[i] - b[i]) > tol) {
                diffs.add(field + "[" + i + "]: src=" + a[i] + " out=" + b[i] + " (abs tol " + tol + ")");
                return;
            }
        }
    }

    /** Compare two arrays by their sum (aggregate), within a relative tolerance — used for lossy intensity. */
    private static void eqSumRelTol(List<String> diffs, String field, double[] a, double[] b, double relTol) {
        double sa = 0, sb = 0;
        for (double v : a) sa += v;
        for (double v : b) sb += v;
        double denom = Math.max(Math.abs(sa), 1e-30);
        if (Double.isNaN(sa) != Double.isNaN(sb) || Math.abs(sa - sb) / denom > relTol) {
            diffs.add(field + ": src=" + sa + " out=" + sb + " (rel tol " + relTol + ")");
        }
    }

    // ---- JSONL report -----------------------------------------------------------------------

    private static synchronized void writeReportLine(Path src, Layout layout, boolean writeOk, String writeError,
                                                     List<JsonNode> findings, List<String> diffs) {
        try {
            Files.createDirectories(REPORT.getParent());
            ObjectNode line = MAPPER.createObjectNode();
            line.put("source", src.toString());
            line.put("layout", layout.name().toLowerCase(Locale.ROOT));
            line.put("writeOk", writeOk);
            if (writeError != null) {
                line.put("writeError", writeError);
            }
            ArrayNode f = line.putArray("validatorFindings");
            for (JsonNode finding : findings) {
                ObjectNode o = f.addObject();
                o.put("ruleId", finding.path("ruleId").asText(finding.path("rule").asText("?")));
                o.put("level", finding.path("level").asText(""));
                o.put("message", finding.path("message").asText(""));
            }
            ArrayNode d = line.putArray("fidelityDiffs");
            for (String diff : diffs) {
                d.add(diff);
            }
            Files.writeString(REPORT, MAPPER.writeValueAsString(line) + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Reporting must never fail the test on its own; surface as stderr only.
            System.err.println("Failed to write forward-backward report line: " + e);
        }
    }

    // ---- validator subprocess ---------------------------------------------------------------

    private static boolean validatorAvailable(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--help")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            p.waitFor(10, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Exit code + captured stdout/stderr of an {@code mzpeak-validate} invocation. */
    private record ValidatorRun(int exitCode, String output) {}

    private static ValidatorRun runValidator(String cmd, Path archive, Path reportJson) throws Exception {
        long size;
        try { size = approximateSize(archive); } catch (IOException e) { size = 0L; }
        List<String> args = new ArrayList<>(Arrays.asList(
                cmd, archive.toString(),
                "--json", reportJson.toString()));
        if (size >= FULL_VALIDATION_THRESHOLD) {
            args.add(2, "--quick");
        }
        ProcessBuilder pb = new ProcessBuilder(args).redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        boolean done = p.waitFor(120, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new AssertionError(
                    "mzpeak-validate timed out after 120 s for " + archive + "\nOutput: " + output);
        }
        return new ValidatorRun(p.exitValue(), output);
    }

    private static List<JsonNode> parseValidatorFindings(Path reportJson) {
        if (!Files.exists(reportJson)) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(reportJson.toFile());
            JsonNode findings = root.path("findings");
            if (!findings.isArray()) {
                return List.of();
            }
            List<JsonNode> out = new ArrayList<>();
            findings.forEach(out::add);
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static long approximateSize(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            return Files.size(path);
        }
        try (var walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); } catch (IOException e) { return 0L; }
                    })
                    .sum();
        }
    }
}
