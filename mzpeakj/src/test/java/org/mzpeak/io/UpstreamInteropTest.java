package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.Golden.Kind;
import org.mzpeak.io.Golden.Line;
import org.mzpeak.io.InteropOutputs.Output;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.CvTerms;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Gated interop test (WR-7, design §11.9): the HEAD Python reader reads what mzPeakJ writes by default.
 *
 * <p><strong>Disabled by default.</strong> Enable it with {@code -Dmzpeak.upstream.python=<dir>}, the {@code python/}
 * directory of a HUPO-PSI/mzPeak checkout at 2ee2589, the reader of the golden files (FX-1b). It needs {@code uv} on the
 * PATH, or {@code -Dmzpeak.upstream.uv=<path>}; without it the test is skipped. The directory is copied to a temp
 * directory, from which uv installs it, so the checkout is never written to. Every Python process runs in the pinned
 * environment of {@code src/test/resources/mzpeak/golden} (see its README.md),
 * {@code uv run --no-project --python 3.13.12 --with <copy> --with-requirements uv-requirements.txt python -I ...},
 * and is limited to 300 s.
 *
 * <pre>
 *   mvn test -Dtest=UpstreamInteropTest -Dmzpeak.upstream.python=../mzPeak-reference/python
 * </pre>
 *
 * <p>For each output of {@link InteropOutputs}:
 * <ul>
 *   <li>{@code generate_golden.py <output>} prints the summary lines of the Python reader's read, and they pass the
 *       golden rules ({@link Golden.Comparator}, {@link Golden.Tolerance#DEFAULT}) against mzPeakJ's read of the
 *       source: one S line per spectrum written, keyed 0..n-1; a P line for each spectrum with peaks; an X line for
 *       each extra array of a spectrum ({@link Spectrum#arrays()}) and of a peak list ({@link Spectrum#peakArrays()}),
 *       which the writer keeps (MZ-1), and for each extra array of a chromatogram ({@link Chromatogram#arrays()}, the
 *       TIC's {@code ms level}), which it keeps too (MZ-2); a C line per chromatogram and a W line per wavelength
 *       spectrum; and R lines without a null cell. Numpress is lossy, so for its spectra only the number of points is
 *       compared;
 *   <li>a probe script reports what the Python reader counts (spectra and their indices, precursors, selected ions,
 *       chromatograms, wavelength spectra) and {@code check_archive_integrity()}, which must be {@code (True, [])};
 *   <li>the counts of the unit's acceptance are pinned: 48 spectra, 34 precursors, 34 selected ions and one
 *       chromatogram of 48 points for small, 212 spectra, 2 chromatograms (the DAD chromatogram's absorbances
 *       summing to about 57.3587, from the column of its unit) and the wavelength facet for has_uv, 9
 *       spectra each for Example_Processed.img and diaPASEF.ref, 48 spectra for the Numpress archives and the archive
 *       without scans, and 34 spectra indexed 0..33 for the MS2 subset.
 * </ul>
 * An archive in the packed metadata layout is rejected by the Python reader. The archive with chromatogram precursors
 * of {@link ChromatogramPrecursorTest#writeSrmArchive} (MZ-6) is read by the Python reader, and its
 * chromatograms_metadata_precursors member by pyarrow.
 */
@EnabledIfSystemProperty(named = UpstreamInteropTest.PYTHON_PROPERTY, matches = ".+")
class UpstreamInteropTest {

    static final String PYTHON_PROPERTY = "mzpeak.upstream.python";
    static final String UV_PROPERTY = "mzpeak.upstream.uv";
    /** The CPython version the golden environment pins (golden/README.md). */
    private static final String PYTHON_VERSION = "3.13.12";
    private static final long TIMEOUT_SECONDS = 300;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Directory names never copied from the reader's directory. */
    private static final Set<String> NOT_COPIED = Set.of("__pycache__", ".venv", "build", "dist", ".git",
            ".pytest_cache");

    /** Counts and the integrity check of one dataset as the Python reader reports them. */
    private static final String PROBE = String.join("\n",
            "\"\"\"The HEAD Python reader's view of one dataset (mzPeakJ UpstreamInteropTest).\"\"\"",
            "import json",
            "import sys",
            "",
            "from mzpeak import MzPeakFile",
            "",
            "reader = MzPeakFile(sys.argv[1])",
            "integrity = reader.check_archive_integrity()",
            "chromatograms = reader.chromatograms",
            "wavelength = reader.wavelength_data",
            "print(json.dumps({",
            "    \"spectra\": len(reader),",
            "    \"spectrum_indices\": [int(i) for i in reader.spectra.index],",
            "    \"precursors\": len(reader.precursors),",
            "    \"selected_ions\": len(reader.selected_ions),",
            "    \"chromatograms\": 0 if chromatograms is None else len(chromatograms),",
            "    \"wavelength_spectra\": None if wavelength is None else len(wavelength),",
            "    \"integrity\": None if integrity is None",
            "    else [integrity[0], [entry.name for entry, _ in integrity[1]]],",
            "}))",
            "");

    /** D-12b (V-22): the arrays of chromatogram 0 of one dataset, as the Python reader reads them. */
    private static final String CHROMATOGRAM_ARRAYS_PROBE = String.join("\n",
            "\"\"\"Chromatogram 0's arrays (mzPeakJ UpstreamInteropTest, V-22).\"\"\"",
            "import json",
            "import sys",
            "",
            "import numpy as np",
            "from mzpeak import MzPeakFile",
            "",
            "reader = MzPeakFile(sys.argv[1])",
            "data = reader.chromatogram_data[0]",
            "print(json.dumps({str(k): [float(x) for x in v] for k, v in data.items() if isinstance(v, np.ndarray)}))",
            "");

    /** V-24: the ion mobility columns of the scans and selected-ion frames, in row order, a null cell as null. */
    private static final String ION_MOBILITY_PROBE = String.join("\n",
            "\"\"\"Ion mobility columns of one archive (mzPeakJ UpstreamInteropTest, V-24).\"\"\"",
            "import json",
            "import sys",
            "",
            "import pandas as pd",
            "from mzpeak import MzPeakFile",
            "",
            "reader = MzPeakFile(sys.argv[1])",
            "",
            "def cells(frame, column, convert):",
            "    return [None if pd.isna(v) else convert(v) for v in frame[column]]",
            "",
            "print(json.dumps({",
            "    \"scan_values\": cells(reader.scans, \"ion_mobility_value\", float),",
            "    \"scan_types\": cells(reader.scans, \"ion_mobility_type\", str),",
            "    \"ion_values\": cells(reader.selected_ions, \"ion_mobility_value\", float),",
            "    \"ion_types\": cells(reader.selected_ions, \"ion_mobility_type\", str),",
            "}))",
            "");

    /**
     * MZ-6: the Python reader's read of every spectrum of an archive and of its chromatograms (the metadata frames,
     * the precursor and selected-ion frames and the data arrays), the error of its {@code read_chromatogram}, if any,
     * and pyarrow's raw read of the chromatograms_metadata_precursors member.
     */
    private static final String CHROMATOGRAM_PRECURSOR_PROBE = String.join("\n",
            "\"\"\"Chromatogram precursors of one archive (mzPeakJ UpstreamInteropTest, MZ-6).\"\"\"",
            "import io",
            "import json",
            "import sys",
            "import zipfile",
            "",
            "import numpy as np",
            "import pandas as pd",
            "import pyarrow.parquet as pq",
            "from mzpeak import MzPeakFile",
            "",
            "reader = MzPeakFile(sys.argv[1])",
            "spectra = [reader[i] for i in range(len(reader))]",
            "metadata = reader.chromatogram_metadata",
            "points = []",
            "for i in reader.chromatograms.index:",
            "    data = reader.chromatogram_data[int(i)]",
            "    points.append(sorted({len(v) for v in data.values() if isinstance(v, np.ndarray)}))",
            "try:",
            "    reader.read_chromatogram(int(reader.chromatograms.index[0]))",
            "    error = None",
            "except Exception as e:",
            "    error = type(e).__name__ + ': ' + str(e)",
            "with zipfile.ZipFile(sys.argv[1]) as archive:",
            "    table = pq.read_table(io.BytesIO(archive.read('chromatograms_metadata_precursors.parquet')))",
            "windows = table.column('isolation_window').to_pylist()",
            "print(json.dumps({",
            "    \"spectra\": len(spectra),",
            "    \"chromatogram_ids\": [str(v) for v in reader.chromatograms['id']],",
            "    \"chromatogram_points\": points,",
            "    \"precursor_sources\": [int(v) for v in metadata.precursors.index],",
            "    \"precursor_ids\": [None if pd.isna(v) else str(v) for v in metadata.precursors['precursor_id']],",
            "    \"selected_ion_sources\": [int(v) for v in metadata.selected_ions.index],",
            "    \"read_chromatogram_error\": error,",
            "    \"precursor_rows\": table.num_rows,",
            "    \"targets\": [w['isolation_window_target'] for w in windows],",
            "}))",
            "");

    @TempDir
    static Path tmp;
    private static String uv;
    private static Path reader;
    private static Path probe;
    private static Path generator;
    private static Path requirements;
    private static Path outputDir;
    private static List<Output> outputs;

    @BeforeAll
    static void writeTheOutputsAndCopyTheReader() throws IOException {
        uv = System.getProperty(UV_PROPERTY, "uv");
        assumeTrue(uvRuns(uv), "uv not found; install it or set -D" + UV_PROPERTY + "=<path>");
        Path python = Path.of(System.getProperty(PYTHON_PROPERTY)).toAbsolutePath();
        assertThat(python.resolve("pyproject.toml")).as("-D%s must name the reader's python/ directory",
                PYTHON_PROPERTY).isRegularFile();
        assertThat(python.resolve("mzpeak").resolve("reader.py")).isRegularFile();
        reader = copyTree(python, tmp.resolve("reader").resolve("python"));
        probe = Files.writeString(tmp.resolve("probe.py"), PROBE, StandardCharsets.UTF_8);
        generator = Golden.DIR.resolve("generate_golden.py").toAbsolutePath();
        requirements = Golden.DIR.resolve("uv-requirements.txt").toAbsolutePath();
        outputDir = Files.createDirectories(tmp.resolve("outputs"));
        outputs = InteropOutputs.write(outputDir);
    }

    static Stream<Output> outputs() {
        return outputs.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("outputs")
    void thePythonReaderReadsTheOutputAsMzPeakJReadsItsSource(Output output) throws IOException {
        Result generated = python(generator, output.path(), output.name() + ".tsv");
        assertThat(generated.exit()).as("generate_golden.py on %s: %s", output, generated).isZero();
        Golden golden = Golden.loadSummary(generated.stdout());
        assertThat(goldenRules(output, golden)).as("%s against mzPeakJ's read of %s", output, output.source())
                .isEmpty();

        Result probed = python(probe, output.path(), output.name() + ".json");
        assertThat(probed.exit()).as("probe on %s: %s", output, probed).isZero();
        JsonNode counts = MAPPER.readTree(Files.readString(probed.stdout(), StandardCharsets.UTF_8));
        List<Spectrum> spectra = output.spectra();
        assertThat(counts.path("integrity").toString()).as("check_archive_integrity() of %s", output)
                .isEqualTo("[true,[]]");
        assertThat(counts.path("spectra").asInt()).as("spectra of %s", output).isEqualTo(spectra.size());
        assertThat(longs(counts.path("spectrum_indices"))).as("spectrum indices of %s", output)
                .isEqualTo(IntStream.range(0, spectra.size()).mapToObj(Long::valueOf).toList());
        assertThat(counts.path("precursors").asInt()).as("precursors of %s", output)
                .isEqualTo(spectra.stream().mapToInt(s -> s.description().precursors().size()).sum());
        assertThat(counts.path("selected_ions").asInt()).as("selected ions of %s", output)
                .isEqualTo(spectra.stream().flatMap(s -> s.description().precursors().stream())
                        .mapToInt(p -> p.selectedIons().size()).sum());
        assertThat(counts.path("chromatograms").asInt()).as("chromatograms of %s", output)
                .isEqualTo(output.chromatograms().size());
        JsonNode wavelength = counts.path("wavelength_spectra");
        if (output.wavelengthSpectra().isEmpty()) {
            assertThat(wavelength.isNull()).as("wavelength facet of %s", output).isTrue();
        } else {
            assertThat(wavelength.asInt()).as("wavelength spectra of %s", output)
                    .isEqualTo(output.wavelengthSpectra().size());
        }
        assertAcceptanceCounts(output, golden, counts);
    }

    /**
     * MZ-6 (g): the HEAD Python reader opens the archive of {@link ChromatogramPrecursorTest#writeSrmArchive}, has_uv
     * with the SRM chromatogram 'sic' and its two precursors, and reads every spectrum and the chromatogram: its
     * metadata row, its arrays, both precursor rows and the selected-ion row, keyed by its index 0. pyarrow reads the
     * archive's chromatograms_metadata_precursors member raw: two rows, with the isolation-window targets 456.7 and
     * 445.3.
     *
     * <p>The reader's {@code read_chromatogram}, which also attaches the selected ions to the precursors, fails on
     * this chromatogram, and the failure is pinned: its frames drop a column without a single value
     * ({@code _clean_frame}, reader.py:159-164), so when no precursor row of the member has a {@code precursor_index}
     * it gives all of an entity's selected ions to one list and assigns that to the frame of the entity's precursors,
     * which fails for two precursors (reader.py:420-424). Its spectra share that code.
     */
    @Test
    void thePythonReaderReadsChromatogramPrecursors() throws IOException {
        Path archive = Files.createDirectories(tmp.resolve("srm")).resolve("srm.mzpeak");
        Chromatogram sic = ChromatogramPrecursorTest.writeSrmArchive(archive);
        Path script = Files.writeString(tmp.resolve("chromatogram_precursors.py"), CHROMATOGRAM_PRECURSOR_PROBE,
                StandardCharsets.UTF_8);

        Result probed = python(script, archive, "srm.json");

        assertThat(probed.exit()).as("probe on %s: %s", archive, probed).isZero();
        JsonNode read = MAPPER.readTree(Files.readString(probed.stdout(), StandardCharsets.UTF_8));
        assertThat(read.path("spectra").asInt()).isEqualTo(
                ForwardBackwardCorpusTest.readSource(Fixtures.current("has_uv.mzpeak")).spectra.size());
        assertThat(read.path("chromatogram_ids").toString()).isEqualTo("[\"sic\"]");
        assertThat(read.path("chromatogram_points").toString()).isEqualTo("[[" + sic.size() + "]]");
        assertThat(read.path("precursor_sources").toString()).isEqualTo("[0,0]");
        assertThat(read.path("precursor_ids").toString()).isEqualTo("[\"scan=19\",null]");
        assertThat(read.path("selected_ion_sources").toString()).isEqualTo("[0]");
        assertThat(read.path("read_chromatogram_error").asText())
                .isEqualTo("ValueError: Length of values (1) does not match length of index (2)");
        assertThat(read.path("precursor_rows").asInt()).isEqualTo(2);
        assertThat(read.path("targets").toString()).isEqualTo("[456.7,445.3]");
    }

    /**
     * D-12b (V-22): small.unpacked with its chromatogram in seconds, written split as an archive: the Python reader
     * reads the stored times, the seconds as written, and the archive passes {@code check_archive_integrity()}.
     */
    @Test
    void thePythonReaderReadsTheStoredTimesOfAChromatogramInSeconds() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("seconds"));
        ForwardBackwardCorpusTest.Source small = ForwardBackwardCorpusTest.readSource(
                Fixtures.current("small.unpacked.mzpeak"));
        Chromatogram seconds = WriterChromatogramTimeUnitTest.secondsChromatogram(dir);
        Path archive = dir.resolve("seconds.mzpeak");
        MzPeakWriter.writeArchive(archive, small.spectra, List.of(seconds), small.wavelengthSpectra,
                small.scanSettings, small.fileMetadata, MzPeakWriter.Options.defaults());
        Path script = Files.writeString(tmp.resolve("chromatogram_arrays.py"), CHROMATOGRAM_ARRAYS_PROBE,
                StandardCharsets.UTF_8);

        Result probed = python(script, archive, "seconds.json");
        assertThat(probed.exit()).as("probe on %s: %s", archive, probed).isZero();
        JsonNode arrays = MAPPER.readTree(Files.readString(probed.stdout(), StandardCharsets.UTF_8));
        assertThat(doubles(arrays.path("time array"))).as("%s", arrays).isEqualTo(seconds.time());
        assertThat(doubles(arrays.path("intensity array"))).as("%s", arrays).isEqualTo(seconds.intensity());

        Result counted = python(probe, archive, "seconds-counts.json");
        assertThat(counted.exit()).as("probe on %s: %s", archive, counted).isZero();
        JsonNode counts = MAPPER.readTree(Files.readString(counted.stdout(), StandardCharsets.UTF_8));
        assertThat(counts.path("integrity").toString()).isEqualTo("[true,[]]");
        assertThat(counts.path("chromatograms").asInt()).isEqualTo(1);
    }

    /**
     * D-13 (V-24): diaPASEF.ref written split as an archive: the Python reader reads the {@code ion_mobility_value}
     * and {@code ion_mobility_type} columns of the scans and selected ions, in row order, with the values of the
     * source's ion mobility Params and MS:1002815, and nothing for spectrum 0's scan, which has none.
     */
    @Test
    void thePythonReaderReadsTheIonMobilityColumns() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("ion-mobility"));
        ForwardBackwardCorpusTest.Source dia = ForwardBackwardCorpusTest.readSource(
                Fixtures.current("diaPASEF.ref.mzpeak"));
        Path archive = dir.resolve("diaPASEF.ref.mzpeak");
        MzPeakWriter.writeArchive(archive, dia.spectra, dia.chromatograms, dia.wavelengthSpectra, dia.scanSettings,
                dia.fileMetadata, MzPeakWriter.Options.defaults());
        List<Double> scanValues = new ArrayList<>();
        List<String> scanTypes = new ArrayList<>();
        List<Double> ionValues = new ArrayList<>();
        List<String> ionTypes = new ArrayList<>();
        for (Spectrum s : dia.spectra) {
            for (ScanEvent scan : s.description().scans()) {
                boolean has = scan.ionMobilityValue().isPresent();
                scanValues.add(has ? scan.ionMobilityValue().getAsDouble() : null);
                scanTypes.add(has ? scan.ionMobilityType() : null);
            }
            for (Precursor p : s.description().precursors()) {
                for (SelectedIon ion : p.selectedIons()) {
                    boolean has = ion.ionMobilityValue().isPresent();
                    ionValues.add(has ? ion.ionMobilityValue().getAsDouble() : null);
                    ionTypes.add(has ? ion.parameters().stream().map(Param::accession)
                            .filter(CvTerms.ION_MOBILITY_TYPES::contains).findFirst().orElseThrow() : null);
                }
            }
        }
        assertThat(scanTypes).containsOnly(null, "MS:1002815").filteredOn(t -> t != null).hasSize(8);
        assertThat(ionTypes).containsOnly("MS:1002815").hasSize(8);
        Path script = Files.writeString(tmp.resolve("ion_mobility.py"), ION_MOBILITY_PROBE, StandardCharsets.UTF_8);

        Result probed = python(script, archive, "ion-mobility.json");
        assertThat(probed.exit()).as("probe on %s: %s", archive, probed).isZero();
        JsonNode read = MAPPER.readTree(Files.readString(probed.stdout(), StandardCharsets.UTF_8));
        assertThat(nullableDoubles(read.path("scan_values"))).as("%s", read).isEqualTo(scanValues);
        assertThat(nullableStrings(read.path("scan_types"))).as("%s", read).isEqualTo(scanTypes);
        assertThat(nullableDoubles(read.path("ion_values"))).as("%s", read).isEqualTo(ionValues);
        assertThat(nullableStrings(read.path("ion_types"))).as("%s", read).isEqualTo(ionTypes);
    }

    @Test
    void thePythonReaderRejectsThePackedLayout() throws IOException {
        Output packed = InteropOutputs.writePacked(Files.createDirectories(tmp.resolve("packed")));

        Result probed = python(probe, packed.path(), packed.name() + ".json");

        assertThat(probed.exit()).as("probe on %s", packed).isNotZero();
        assertThat(Files.readString(probed.stderr(), StandardCharsets.UTF_8))
                .contains("Old concatenated storage layout not supported");
    }

    // ---- the golden rules -----------------------------------------------------------------------------------------

    /**
     * The mismatches between the Python reader's summary lines of {@code output} and the arrays mzPeakJ read from its
     * source, and every missing, unexpected or misnumbered line.
     */
    private static List<String> goldenRules(Output output, Golden golden) {
        List<String> out = new ArrayList<>();
        Golden.Tolerance tolerance = Golden.Tolerance.DEFAULT;
        List<Spectrum> spectra = output.spectra();
        expectKeys(out, golden, Kind.S, IntStream.range(0, spectra.size()).mapToObj(Integer::toString).toList());
        List<String> withPeaks = new ArrayList<>();
        // the generator writes the X lines of every spectrum, then those of every peak list
        List<String> extras = new ArrayList<>();
        List<String> peakExtras = new ArrayList<>();
        for (int i = 0; i < spectra.size(); i++) {
            Spectrum s = spectra.get(i);
            String key = Integer.toString(i);
            golden.line(Kind.S, key).ifPresent(line -> spectrum(out, output, line, s.mz(), s.intensity()));
            for (Map.Entry<String, double[]> extra : s.arrays().entrySet()) {
                extras.add("S:" + key + " [" + extra.getKey() + "]");
                golden.line(Kind.X, "S:" + key, extra.getKey()).ifPresent(line ->
                        spectrum(out, output, line, s.mz(), extra.getValue()));
            }
            if (!s.peaks().isEmpty()) {
                withPeaks.add(key);
                golden.line(Kind.P, key).ifPresent(line -> spectrum(out, output, line, peakMz(s), peakIntensity(s)));
                for (String extra : s.peakArrays().keySet()) {
                    peakExtras.add("P:" + key + " [" + extra + "]");
                    golden.line(Kind.X, "P:" + key, extra).ifPresent(line ->
                            spectrum(out, output, line, peakMz(s), peakExtra(s, extra)));
                }
            }
            golden.line(Kind.R, key).ifPresent(line ->
                    out.addAll(Golden.Comparator.compare(line, s.mz(), s.intensity(), tolerance)));
        }
        expectKeys(out, golden, Kind.P, withPeaks);
        for (Line line : golden.lines(Kind.R)) {
            if (line.nFilled() != 0) {
                out.add("R " + line.key() + ": " + line.nFilled() + " null m/z cells written");
            }
        }
        expectKeys(out, golden, Kind.C, output.chromatograms().stream().map(Chromatogram::id).toList());
        List<String> chromatogramExtras = new ArrayList<>();
        for (Chromatogram c : output.chromatograms()) {
            golden.line(Kind.C, c.id()).ifPresent(line ->
                    out.addAll(Golden.Comparator.compare(line, c.time(), c.intensity(), tolerance)));
            for (Map.Entry<String, double[]> extra : c.arrays().entrySet()) {
                chromatogramExtras.add("C:" + c.id() + " [" + extra.getKey() + "]");
                golden.line(Kind.X, "C:" + c.id(), extra.getKey()).ifPresent(line ->
                        out.addAll(Golden.Comparator.compare(line, c.time(), extra.getValue(), tolerance)));
            }
        }
        List<WavelengthSpectrum> wavelength = output.wavelengthSpectra();
        expectKeys(out, golden, Kind.W, IntStream.range(0, wavelength.size()).mapToObj(Integer::toString).toList());
        for (int i = 0; i < wavelength.size(); i++) {
            WavelengthSpectrum w = wavelength.get(i);
            golden.line(Kind.W, Integer.toString(i)).ifPresent(line ->
                    out.addAll(Golden.Comparator.compare(line, w.wavelength(), w.intensity(), tolerance)));
        }
        // The extra arrays of spectra (MZ-1) and of chromatograms (MZ-2) are written; the generator writes the X
        // lines of the chromatograms after those of the peak lists.
        extras.addAll(peakExtras);
        extras.addAll(chromatogramExtras);
        List<String> xKeys = golden.lines(Kind.X).stream().map(l -> l.key() + " [" + l.arrayType() + "]").toList();
        if (!xKeys.equals(extras)) {
            out.add("X lines: expected " + extras + " but were " + xKeys);
        }
        return out;
    }

    /** A spectrum's or peak list's line: every rule for the point layout, the number of points for Numpress. */
    private static void spectrum(List<String> out, Output output, Line line, double[] mz, double[] intensity) {
        if (!output.numpress()) {
            out.addAll(Golden.Comparator.compare(line, mz, intensity, Golden.Tolerance.DEFAULT));
        } else if (line.n() != mz.length) {
            out.add(line.kind() + " " + line.key() + ": n expected " + mz.length + " but was " + line.n());
        }
    }

    private static void expectKeys(List<String> out, Golden golden, Kind kind, List<String> expected) {
        List<String> keys = golden.lines(kind).stream().map(Line::key).toList();
        if (!keys.equals(expected)) {
            out.add(kind + " lines: expected keys " + expected + " but were " + keys);
        }
    }

    /** The numbers WR-7's acceptance names for each output. */
    private static void assertAcceptanceCounts(Output output, Golden golden, JsonNode counts) {
        String fixture = output.source().getFileName().toString();
        int spectra = counts.path("spectra").asInt();
        switch (output.kind()) {
            case NUMPRESS_SLOF, NUMPRESS_PIC, SCANLESS -> assertThat(spectra).as("spectra of %s", output).isEqualTo(48);
            case MS2_SUBSET -> {
                assertThat(spectra).as("spectra of %s", output).isEqualTo(34);
                assertThat(longs(counts.path("spectrum_indices"))).as("indices of %s", output)
                        .isEqualTo(IntStream.range(0, 34).mapToObj(Long::valueOf).toList());
            }
            default -> {
                switch (fixture) {
                    case "small.unpacked.mzpeak" -> {
                        assertThat(spectra).as("spectra of %s", output).isEqualTo(48);
                        assertThat(counts.path("precursors").asInt()).as("precursors of %s", output).isEqualTo(34);
                        assertThat(counts.path("selected_ions").asInt()).as("ions of %s", output).isEqualTo(34);
                        assertThat(golden.lines(Kind.C)).as("chromatograms of %s", output).singleElement()
                                .extracting(Line::n).isEqualTo(48L);
                    }
                    case "has_uv.mzpeak" -> {
                        assertThat(spectra).as("spectra of %s", output).isEqualTo(212);
                        assertThat(counts.path("chromatograms").asInt()).as("chromatograms of %s", output)
                                .isEqualTo(2);
                        assertThat(golden.line(Kind.C, "DAD1 A: Sig=272,16 Ref=360,100")).as("DAD of %s", output)
                                .hasValueSatisfying(l -> assertThat(l.sumY()).isCloseTo(57.3587, within(1e-4)));
                        assertThat(counts.path("wavelength_spectra").isInt()).as("wavelength facet of %s", output)
                                .isTrue();
                        assertThat(golden.lines(Kind.W)).as("wavelength spectra of %s", output).isNotEmpty();
                    }
                    case "Example_Processed.img.mzpeak", "diaPASEF.ref.mzpeak" ->
                            assertThat(spectra).as("spectra of %s", output).isEqualTo(9);
                    default -> fail("no acceptance counts for " + output);
                }
            }
        }
    }

    // ---- processes ------------------------------------------------------------------------------------------------

    /** Exit code of a Python process and the files holding its standard output and standard error. */
    private record Result(int exit, Path stdout, Path stderr) {

        String stderr(int maxChars) {
            try {
                String text = Files.readString(stderr, StandardCharsets.UTF_8);
                return text.length() <= maxChars ? text : "..." + text.substring(text.length() - maxChars);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public String toString() {
            return "exit " + exit + ", stderr " + stderr(4000);
        }
    }

    /** Runs {@code script} on {@code dataset} in the pinned environment; output goes to {@code <outputName>} files. */
    private static Result python(Path script, Path dataset, String outputName) throws IOException {
        Path logs = Files.createDirectories(tmp.resolve("logs"));
        Path stdout = logs.resolve(outputName);
        Path stderr = logs.resolve(outputName + ".stderr");
        List<String> command = List.of(uv, "run", "--no-project", "--python", PYTHON_VERSION, "--with",
                reader.toString(), "--with-requirements", requirements.toString(), "python", "-I",
                script.toString(), dataset.toAbsolutePath().toString());
        Process process = new ProcessBuilder(command).directory(tmp.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("timed out after " + TIMEOUT_SECONDS + " s: " + String.join(" ", command));
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted: " + String.join(" ", command), e);
        }
        return new Result(process.exitValue(), stdout, stderr);
    }

    private static boolean uvRuns(String uv) {
        try {
            Process process = new ProcessBuilder(uv, "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Copies the directory tree {@code from} to {@code to}, without caches and build output. */
    private static Path copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path relative = from.relativize(p);
                boolean skipped = false;
                for (Path part : relative) {
                    String name = part.toString();
                    skipped |= NOT_COPIED.contains(name) || name.endsWith(".egg-info");
                }
                if (skipped) {
                    continue;
                }
                Path target = to.resolve(relative.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
        return to;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static List<Long> longs(JsonNode array) {
        List<Long> values = new ArrayList<>();
        for (JsonNode n : array) {
            values.add(n.asLong());
        }
        return values;
    }

    private static List<Double> nullableDoubles(JsonNode array) {
        List<Double> values = new ArrayList<>();
        for (JsonNode n : array) {
            values.add(n.isNull() ? null : n.asDouble());
        }
        return values;
    }

    private static List<String> nullableStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode n : array) {
            values.add(n.isNull() ? null : n.asText());
        }
        return values;
    }

    private static double[] doubles(JsonNode array) {
        double[] values = new double[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).asDouble();
        }
        return values;
    }

    private static double[] peakMz(Spectrum s) {
        return s.peaks().stream().mapToDouble(CentroidPeak::mz).toArray();
    }

    private static double[] peakIntensity(Spectrum s) {
        return s.peaks().stream().mapToDouble(CentroidPeak::intensity).toArray();
    }

    /** The peak array {@code key} of {@code s} in the order of {@link Spectrum#peaks()}. */
    private static double[] peakExtra(Spectrum s, String key) {
        double[] values = s.peakArrays().get(key);
        return s.peaks().stream().mapToDouble(p -> values[p.index()]).toArray();
    }
}
