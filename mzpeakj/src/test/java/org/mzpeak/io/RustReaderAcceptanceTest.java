package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.InteropOutputs.Output;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakReader.SignalMode;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.model.Activation;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in: split writes read by the Rust reference reader (mzPeak-reference 2ee2589) through {@code rdump}, a small
 * program on its public API that prints every spectrum, chromatogram and wavelength spectrum as a JSON line (a
 * {@code panic} field where reading one panicked) and their arrays to a little-endian float64 {@code .bin} file.
 * Its source is {@code tools/rdump} (see its README.md); the CI job {@code rust-reader} builds it and runs this test.
 * Runs only with {@code -Dmzpeak.rust.rdump=<path to the rdump binary>}; locally:
 * <pre>
 *   cargo build --release --locked --manifest-path tools/rdump/Cargo.toml
 *   ./mvnw -pl mzpeakj test -Dtest=RustReaderAcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Djacoco.skip=true -Dmzpeak.rust.rdump=$PWD/tools/rdump/target/release/rdump
 * </pre>
 * Without the {@code ARROW:schema} hint and the auxiliary array columns the Rust reader panicked on every wavelength
 * spectrum ("list array"); without the collision energy promotion it reported an integer, unitless energy as 0.
 *
 * <p>The known issues of the Rust reader that this test expects are listed, with their ids, in
 * {@code docs/upstream-issues.md}.
 */
@EnabledIfSystemProperty(named = "mzpeak.rust.rdump", matches = ".+")
class RustReaderAcceptanceTest {

    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    static Stream<Path> hasUv() {
        return Stream.of(Fixtures.current("has_uv.mzpeak"), Fixtures.legacy("has_uv.mzpeak"));
    }

    /**
     * has_uv, read and written split as an archive: Rust reads all 212 spectra and all 520 wavelength spectra
     * without a panic, with the ids, wavelengths and intensities the mzPeakJ reader reads from the fixture.
     */
    @ParameterizedTest
    @MethodSource("hasUv")
    void rustReadsEveryWavelengthSpectrumOfAWrittenHasUv(Path fixture) throws Exception {
        Source source = ForwardBackwardCorpusTest.readSource(fixture);
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeArchive(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, SPLIT);

        Dump dump = rdump(out);
        assertThat(dump.header().path("n_wavelength").asInt()).isEqualTo(520);
        assertThat(dump.records("S")).hasSize(212).allSatisfy(r -> assertThat(r.has("panic")).as("%s", r).isFalse());
        List<JsonNode> waves = dump.records("W");
        assertThat(waves).hasSize(520);
        for (int i = 0; i < waves.size(); i++) {
            JsonNode w = waves.get(i);
            WavelengthSpectrum expected = source.wavelengthSpectra.get(i);
            assertThat(w.has("panic") || w.has("none")).as("wavelength spectrum %d: %s", i, w).isFalse();
            assertThat(w.path("id").asText()).isEqualTo(expected.id());
            assertThat(dump.array(w.path("arrays").path("WavelengthArray"))).as("wavelength of %d", i)
                    .isEqualTo(expected.wavelength());
            assertThat(dump.array(w.path("arrays").path("IntensityArray"))).as("intensity of %d", i)
                    .isEqualTo(expected.intensity());
        }
    }

    /** An integer collision energy without unit, as mzML writers give it, reaches Rust as 35 eV, not 0. */
    @Test
    void rustReadsAnIntegerUnitlessCollisionEnergy() throws Exception {
        Source small = ForwardBackwardCorpusTest.readSource(Fixtures.current("small.unpacked.mzpeak"));
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        int target = -1;
        for (int i = 0; i < spectra.size() && target < 0; i++) {
            if (!spectra.get(i).description().precursors().isEmpty()) {
                target = i;
            }
        }
        Spectrum s = spectra.get(target);
        SpectrumDescription d = s.description();
        Precursor p = d.primaryPrecursor();
        Precursor changed = new Precursor(p.precursorIndex(), p.precursorId(), p.isolationWindow(), p.selectedIons(),
                new Activation(List.of(new Param("collision-induced dissociation", "MS:1000133", null, null),
                        new Param("collision energy", "MS:1000045", 35L, null))));
        spectra.set(target, new Spectrum(new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(),
                d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(),
                List.of(changed), d.parameters()), s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays()));
        Path out = tmp.resolve("energy.mzpeak");
        MzPeakWriter.writeArchive(out, spectra, small.chromatograms, small.wavelengthSpectra, small.scanSettings,
                small.fileMetadata, SPLIT);

        Dump dump = rdump(out);
        JsonNode record = dump.records("S").stream().filter(r -> d.id().equals(r.path("id").asText()))
                .findFirst().orElseThrow();
        JsonNode precursor = record.path("prec").get(0);
        assertThat(precursor.path("energy").asDouble()).isEqualTo(35.0);
        assertThat(precursor.path("methods").get(0).asText()).isEqualTo("CollisionInducedDissociation");
    }

    /**
     * D-13 (V-24): diaPASEF.ref written split as an archive, its ion mobility in the {@code ion_mobility_value} and
     * {@code ion_mobility_type} columns of the scans and selected ions: Rust reads each spectrum's scan and selected
     * ion ion mobility with the source's value, and none for spectrum 0's scan.
     */
    @Test
    void rustReadsTheIonMobilityColumns() throws Exception {
        Source dia = ForwardBackwardCorpusTest.readSource(Fixtures.current("diaPASEF.ref.mzpeak"));
        Path out = tmp.resolve("ion-mobility.mzpeak");
        MzPeakWriter.writeArchive(out, dia.spectra, dia.chromatograms, dia.wavelengthSpectra, dia.scanSettings,
                dia.fileMetadata, SPLIT);

        Dump dump = rdump(out);
        List<JsonNode> records = dump.records("S");
        assertThat(records).hasSize(dia.spectra.size());
        int scans = 0;
        int ions = 0;
        for (int i = 0; i < records.size(); i++) {
            JsonNode r = records.get(i);
            SpectrumDescription d = dia.spectra.get(i).description();
            String at = "spectrum " + i;
            assertThat(r.has("panic") || r.has("none")).as("%s: %s", at, r).isFalse();
            assertThat(r.path("id").asText()).as(at).isEqualTo(d.id());
            OptionalDouble scan = d.scans().get(0).ionMobilityValue();
            if (scan.isPresent()) {
                scans++;
                assertThat(r.path("scan_im").asDouble()).as("%s scan_im", at).isEqualTo(scan.getAsDouble());
            } else {
                assertThat(r.path("scan_im").isNull()).as("%s scan_im %s", at, r.path("scan_im")).isTrue();
            }
            List<SelectedIon> expected = d.precursors().stream().flatMap(p -> p.selectedIons().stream()).toList();
            List<JsonNode> read = new ArrayList<>();
            r.path("prec").forEach(p -> p.path("ions").forEach(read::add));
            assertThat(read).as("%s selected ions", at).hasSameSizeAs(expected);
            for (int k = 0; k < read.size(); k++) {
                ions++;
                assertThat(read.get(k).path("im").asDouble()).as("%s ion %d im", at, k)
                        .isEqualTo(expected.get(k).ionMobilityValue().orElseThrow());
            }
        }
        assertThat(scans).as("scans with ion mobility").isEqualTo(8);
        assertThat(ions).as("selected ions with ion mobility").isEqualTo(8);
    }

    /**
     * D-12b (V-22): small.unpacked with its chromatogram in seconds, written split as an archive: Rust reads the
     * chromatogram with its time array in seconds and the times as written.
     */
    @Test
    void rustReadsAChromatogramInSeconds() throws Exception {
        Source small = ForwardBackwardCorpusTest.readSource(Fixtures.current("small.unpacked.mzpeak"));
        Chromatogram seconds = WriterChromatogramTimeUnitTest.secondsChromatogram(tmp);
        Path out = tmp.resolve("seconds.mzpeak");
        MzPeakWriter.writeArchive(out, small.spectra, List.of(seconds), small.wavelengthSpectra, small.scanSettings,
                small.fileMetadata, SPLIT);

        Dump dump = rdump(out);
        assertThat(dump.records("C")).singleElement().satisfies(c -> {
            assertThat(c.has("panic") || c.has("none")).as("%s", c).isFalse();
            assertThat(c.path("id").asText()).isEqualTo(seconds.id());
            JsonNode time = c.path("arrays").path("TimeArray");
            assertThat(time.path("unit").asText()).as("%s", c).isEqualTo("Second");
            assertThat(dump.array(time)).isEqualTo(seconds.time());
            assertThat(dump.array(c.path("arrays").path("IntensityArray"))).isEqualTo(seconds.intensity());
        });
    }

    /**
     * Every output of {@link InteropOutputs#write}, all in the split layout (legacy and current small.unpacked,
     * has_uv, Example_Processed.img and diaPASEF.ref as archive and directory, the Numpress SLOF and PIC archives of
     * current small, its MS2 subset and its archive without scans), and the archive of current small.unpacked with
     * its chromatogram without type (none of the others has one: Example_Processed.img has no chromatograms): rdump
     * exits 0, opens it, and its {@code HEADER} counts equal the written ones. For a dataset without chromatograms
     * the Rust reader counts the TIC and BPC it can derive from the spectra, 2, and reads neither through
     * {@code get_chromatogram} (record with {@code none}); its {@code encoded_tic}/{@code encoded_bpc} read them.
     *
     * <p>The known issues of the Rust reader ({@code docs/upstream-issues.md}) are expected exactly where mzPeakJ's
     * read of the same output says they apply, and each must occur at least once over all outputs, so that a fixed
     * one is noticed and the list updated:
     * <ul>
     *   <li>R-1: a spectrum record has {@code peaks_panic} exactly for the spectra that the output's peaks member has
     *       no rows for and whose index lies between the first and the last index it has rows for (the reader finds
     *       the row group by its index range, then panics when the index is not in it); before the first and after
     *       the last, the peak list is empty. No other spectrum record has {@code peaks_panic};
     *   <li>R-2: a chromatogram record has a top-level {@code panic} exactly for the chromatograms without type;
     *   <li>R-3: every wavelength spectrum record has {@code rt} 0.0.
     * </ul>
     * A dataset without peaks member gives {@code peaks_err} "Spectrum peak data entry not found" for every spectrum,
     * the reader's answer for peaks that are not there; with a peaks member, no record has {@code peaks_err}. Anything
     * else unreadable fails the test: a spectrum record with {@code none} or {@code panic}, a chromatogram or
     * wavelength spectrum record with {@code none} (but for the derived TIC and BPC above), a TIC/BPC record with
     * {@code error} or {@code panic}.
     *
     * <p>Every spectrum's MS level and retention time are compared with the spectrum written: the MS level equal; the
     * retention time, Rust's {@code start_time}, bit-equal to the start time of the spectrum's first scan, in minutes,
     * or to its retention time if it has no scan (the output without scans, to which the writer adds one scan holding
     * the retention time). Not to the retention time throughout, which is the spectrum's {@code time} first: in legacy
     * small.unpacked the scan start times differ from the spectrum times beyond the 7th significant digit
     * (0.004935000091791153, the widened float nearest to 0.004935, against 0.004935).
     *
     * <p>The values of every spectrum's data arrays and peaks and of every chromatogram's and wavelength spectrum's
     * arrays are compared with mzPeakJ's read: point counts equal; m/z within {@value #MZ_TOLERANCE} (R-6, Rust
     * fills null-marked m/z less precisely); times, wavelengths and intensities bit-equal, since both readers decode
     * the same stored doubles or floats. Numpress m/z (linear) and PIC intensities are bit-equal too, their decoders
     * use integer and IEEE {@code +}/{@code ×} arithmetic only. A SLOF intensity {@code r} only has to be within
     * {@code 2 × Math.ulp(j + 1.0)} of mzPeakJ's {@code j}: SLOF decodes {@code exp(s / fixedPoint) − 1}, Java's
     * {@code Math.exp} and Rust's {@code f64::exp} (the platform libm) may differ by one ulp of the exp result, and
     * subtracting 1 adds at most one more rounding (the reason the SLOF digests of the other tests are
     * platform-independent). The intensity of a peak in a peak list is a 32-bit float in the Rust API
     * ({@code mzpeaks::CentroidPeak}), so it is compared with the float nearest to mzPeakJ's value (SLOF: to the
     * values within the tolerance).
     */
    @Test
    void rustReadsEverySplitInteropOutput() throws Exception {
        List<Output> outputs = new ArrayList<>(InteropOutputs.write(Files.createDirectories(tmp.resolve("outputs"))));
        Path source = Fixtures.current("small.unpacked.mzpeak");
        Source small = ForwardBackwardCorpusTest.readSource(source);
        List<Chromatogram> untyped = new ArrayList<>();
        for (Chromatogram c : small.chromatograms) {
            untyped.add(new Chromatogram(c.index(), c.id(), null, c.time(), c.intensity(), c.intensityUnit(),
                    c.arrays(), c.polarity(), c.dataProcessingRef(), c.parameters(), c.precursors(), c.timeUnit()));
        }
        Output untypedOutput = new Output("current-small.unpacked-untyped-chromatogram",
                tmp.resolve("untyped.mzpeak"), source, InteropOutputs.Kind.ARCHIVE, small.spectra, untyped,
                small.wavelengthSpectra);
        MzPeakWriter.writeArchive(untypedOutput.path(), small.spectra, untyped, small.wavelengthSpectra,
                small.scanSettings, small.fileMetadata, SPLIT);
        outputs.add(untypedOutput);
        Set<String> issues = new TreeSet<>();
        for (Output output : outputs) {
            checkOutput(output, issues);
        }
        assertThat(issues).as("known issues of docs/upstream-issues.md seen; when one is gone, update the list")
                .containsExactly("R-1", "R-2", "R-3");
    }

    /** How a value Rust read is compared with mzPeakJ's. */
    private enum Match {
        /** Equal bits. */
        EXACT,
        /** Within {@link #MZ_TOLERANCE}. */
        MZ,
        /** Within two ulps of {@code j + 1}, for a SLOF intensity {@code j}. */
        SLOF
    }

    /** The absolute m/z tolerance of a non-Numpress m/z, which Rust may have filled for a null-marked point (R-6). */
    private static final double MZ_TOLERANCE = 1e-4;

    private void checkOutput(Output output, Set<String> issues) throws IOException, InterruptedException {
        Dump dump = rdump(output.path());
        JsonNode header = dump.header();
        assertThat(header.path("n_spectra").asInt()).as("%s n_spectra", output).isEqualTo(output.spectra().size());
        boolean derivedChromatograms = output.chromatograms().isEmpty();
        assertThat(header.path("n_chromatograms").asInt()).as("%s n_chromatograms", output)
                .isEqualTo(derivedChromatograms ? 2 : output.chromatograms().size());
        assertThat(header.path("n_wavelength").asInt()).as("%s n_wavelength", output)
                .isEqualTo(output.wavelengthSpectra().size());
        Match mz = output.numpress() ? Match.EXACT : Match.MZ;
        Match intensity = output.kind() == InteropOutputs.Kind.NUMPRESS_SLOF ? Match.SLOF : Match.EXACT;
        try (MzPeakReader both = MzPeakReader.open(output.path());
             MzPeakReader profile = MzPeakReader.open(output.path(),
                     MzPeakReader.Options.defaults().withSignalMode(SignalMode.PROFILE));
             MzPeakReader centroid = MzPeakReader.open(output.path(),
                     MzPeakReader.Options.defaults().withSignalMode(SignalMode.CENTROID))) {
            boolean peaksMember = both.manifest().find(EntityType.SPECTRUM, DataKind.PEAKS).isPresent();
            int firstWithPeaks = -1;
            int lastWithPeaks = -1;
            for (int i = 0; peaksMember && i < centroid.size(); i++) {
                if (centroid.getSpectrumAt(i).pointCount() > 0) {
                    firstWithPeaks = firstWithPeaks < 0 ? i : firstWithPeaks;
                    lastWithPeaks = i;
                }
            }
            List<JsonNode> spectra = dump.records("S");
            assertThat(spectra).as("%s spectrum records", output).hasSize(both.size());
            for (int i = 0; i < spectra.size(); i++) {
                JsonNode r = spectra.get(i);
                String at = output + " spectrum " + i;
                assertThat(r.has("none") || r.has("panic")).as("%s: %s", at, r.path("panic")).isFalse();
                Spectrum expected = both.getSpectrumAt(i);
                assertThat(r.path("index").asInt()).as(at).isEqualTo(i);
                assertThat(r.path("id").asText()).as(at).isEqualTo(expected.description().id());
                // MS level and retention time against the spectrum written, so that a writer that loses them fails
                SpectrumDescription d = output.spectra().get(i).description();
                assertThat(r.path("ms_level").asInt()).as("%s: ms_level", at).isEqualTo(d.msLevel());
                // start_time() is the start time of the first scan, in minutes; the writer gives a spectrum
                // without scans one scan with its retention time
                double rt = d.scans().isEmpty() ? d.retentionTime() : d.scans().get(0).startTime();
                assertThat(r.path("rt").isNumber()).as("%s: rt %s", at, r.path("rt")).isTrue();
                assertValues(at + " rt", new double[] {r.path("rt").asDouble()}, new double[] {rt}, Match.EXACT);
                assertThat(r.path("peaks_err").asText(null)).as(at)
                        .isEqualTo(peaksMember ? null : "Spectrum peak data entry not found");
                // R-1: get_spectrum_peaks_for panics for a spectrum without rows in the peaks member, inside the
                // index range of its rows
                boolean r1 = peaksMember && centroid.getSpectrumAt(i).pointCount() == 0 && i > firstWithPeaks
                        && i < lastWithPeaks;
                assertThat(r.has("peaks_panic")).as("%s: R-1 peaks_panic", at).isEqualTo(r1);
                if (r1) {
                    issues.add("R-1");
                }
                Spectrum points = profile.getSpectrumAt(i);
                JsonNode arrays = r.path("arrays");
                assertThat(arrays.has("MZArray")).as("%s: data arrays", at).isEqualTo(points.pointCount() > 0);
                if (points.pointCount() > 0) {
                    assertValues(at + " m/z", dump.array(arrays.path("MZArray")), points.mz(), mz);
                    assertValues(at + " intensity", dump.array(arrays.path("IntensityArray")), points.intensity(),
                            intensity);
                }
                assertThat(r.has("peaks")).as("%s: peaks", at).isEqualTo(peaksMember && !r1);
                for (String key : List.of("spec_peaks", "peaks")) {
                    if (r.has(key)) {
                        JsonNode peaks = r.path(key);
                        if (key.equals("peaks")) {
                            assertThat(peaks.path("level").asText()).as("%s: %s", at, key).isEqualTo("centroid");
                        }
                        List<CentroidPeak> list = expected.peaks();
                        double[] peakMz = list.stream().mapToDouble(CentroidPeak::mz).toArray();
                        double[] peakIntensity = list.stream().mapToDouble(CentroidPeak::intensity).toArray();
                        assertValues(at + " " + key + " m/z", dump.array(peaks, "mz_off"), peakMz, mz);
                        // a peak's intensity is an f32 in mzpeaks' CentroidPeak
                        assertValues(at + " " + key + " intensity", dump.array(peaks, "int_off"), peakIntensity,
                                intensity, true);
                    }
                }
            }

            List<Chromatogram> chromatograms = both.chromatograms();
            List<JsonNode> cRecords = dump.records("C");
            if (derivedChromatograms) {
                // the derived TIC and BPC, which get_chromatogram does not read
                assertThat(chromatograms).as(output.name()).isEmpty();
                assertThat(cRecords).as("%s chromatogram records", output).hasSize(2)
                        .allSatisfy(c -> assertThat(c.path("none").asBoolean()).as("%s", c).isTrue());
                cRecords = List.of();
            }
            assertThat(cRecords).as("%s chromatogram records", output).hasSize(chromatograms.size());
            for (int i = 0; i < cRecords.size(); i++) {
                JsonNode c = cRecords.get(i);
                Chromatogram expected = chromatograms.get(i);
                String at = output + " chromatogram " + i;
                assertThat(c.has("none")).as(at).isFalse();
                // R-2: a chromatogram with a null type panics
                assertThat(c.has("panic")).as("%s: R-2 panic %s", at, c.path("panic"))
                        .isEqualTo(expected.typeCurie() == null);
                if (expected.typeCurie() == null) {
                    issues.add("R-2");
                    continue;
                }
                assertThat(c.path("id").asText()).as(at).isEqualTo(expected.id());
                assertValues(at + " time", dump.array(c.path("arrays").path("TimeArray")), expected.time(),
                        Match.EXACT);
                assertValues(at + " intensity", dump.array(c.path("arrays").path("IntensityArray")),
                        expected.intensity(), Match.EXACT);
            }

            assertThat(dump.records("T")).as("%s TIC and BPC records", output).hasSize(2);
            for (JsonNode t : dump.records("T")) {
                assertThat(t.has("error") || t.has("panic")).as("%s %s: %s%s", output, t.path("which"),
                        t.path("error"), t.path("panic")).isFalse();
            }

            List<WavelengthSpectrum> wavelengthSpectra = both.wavelengthSpectra();
            List<JsonNode> wRecords = dump.records("W");
            assertThat(wRecords).as("%s wavelength spectrum records", output).hasSize(wavelengthSpectra.size());
            for (int i = 0; i < wRecords.size(); i++) {
                JsonNode w = wRecords.get(i);
                WavelengthSpectrum expected = wavelengthSpectra.get(i);
                String at = output + " wavelength spectrum " + i;
                assertThat(w.has("none") || w.has("panic")).as("%s: %s", at, w.path("panic")).isFalse();
                assertThat(w.path("id").asText()).as(at).isEqualTo(expected.id());
                // R-3: the retention time of a wavelength spectrum reads as 0.0
                assertThat(w.path("rt").isNumber()).as("%s: rt %s", at, w.path("rt")).isTrue();
                assertThat(w.path("rt").asDouble()).as("%s: R-3 rt", at).isEqualTo(0.0);
                if (expected.time() != 0.0) {
                    issues.add("R-3");
                }
                assertValues(at + " wavelength", dump.array(w.path("arrays").path("WavelengthArray")),
                        expected.wavelength(), Match.EXACT);
                assertValues(at + " intensity", dump.array(w.path("arrays").path("IntensityArray")),
                        expected.intensity(), Match.EXACT);
            }
        }
    }

    /** Fails at the first value of {@code rust} that does not match the one of {@code java} at the same position. */
    private static void assertValues(String what, double[] rust, double[] java, Match match) {
        assertValues(what, rust, java, match, false);
    }

    /**
     * Like {@link #assertValues(String, double[], double[], Match)}; with {@code f32}, {@code rust} holds 32-bit
     * floats and matches the float nearest to a value that matches.
     */
    private static void assertValues(String what, double[] rust, double[] java, Match match, boolean f32) {
        assertThat(rust.length).as("%s: number of values", what).isEqualTo(java.length);
        for (int k = 0; k < rust.length; k++) {
            double r = rust[k];
            double j = java[k];
            double slof = 2 * Math.ulp(j + 1.0);
            boolean ok = switch (match) {
                case EXACT -> Double.doubleToLongBits(r) == Double.doubleToLongBits(f32 ? (float) j : j);
                case MZ -> Math.abs(r - j) <= MZ_TOLERANCE;
                case SLOF -> f32 ? (float) (j - slof) <= r && r <= (float) (j + slof) : Math.abs(r - j) <= slof;
            };
            if (!ok) {
                throw new AssertionError(what + "[" + k + "]: Rust " + r + ", mzPeakJ " + j + " (" + match + ")");
            }
        }
    }

    // ---- rdump ----------------------------------------------------------------------------------------------------

    /** The lines of an rdump output by kind, and its array file. */
    private record Dump(Map<String, List<JsonNode>> byKind, double[] bin) {

        JsonNode header() {
            return records("HEADER").get(0);
        }

        List<JsonNode> records(String kind) {
            return byKind.getOrDefault(kind, List.of());
        }

        /** The values an {@code {n, off}} array entry points to. */
        double[] array(JsonNode entry) {
            return array(entry, "off");
        }

        /** The values a peak list entry {@code {n, mz_off, int_off}} points to with {@code offset}. */
        double[] array(JsonNode entry, String offset) {
            int off = entry.path(offset).asInt();
            int n = entry.path("n").asInt();
            double[] out = new double[n];
            System.arraycopy(bin, off, out, 0, n);
            return out;
        }
    }

    private Dump rdump(Path dataset) throws IOException, InterruptedException {
        Path prefix = tmp.resolve("dump");
        Process process = new ProcessBuilder(System.getProperty("mzpeak.rust.rdump"), dataset.toString(),
                prefix.toString()).redirectErrorStream(true).redirectOutput(tmp.resolve("rdump.log").toFile()).start();
        assertThat(process.waitFor(10, TimeUnit.MINUTES)).as("rdump finished").isTrue();
        assertThat(process.exitValue()).as("rdump exit, log: %s", Files.readString(tmp.resolve("rdump.log")))
                .isZero();
        Map<String, List<JsonNode>> byKind = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(prefix + ".jsonl"))) {
            JsonNode node = MAPPER.readTree(line);
            byKind.computeIfAbsent(node.path("kind").asText(), k -> new ArrayList<>()).add(node);
        }
        assertThat(byKind).as("rdump opened the dataset").doesNotContainKeys("OPEN_ERROR", "OPEN_PANIC");
        ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(Path.of(prefix + ".bin"))).order(ByteOrder.LITTLE_ENDIAN);
        double[] bin = new double[bytes.remaining() / 8];
        bytes.asDoubleBuffer().get(bin);
        return new Dump(byKind, bin);
    }
}
