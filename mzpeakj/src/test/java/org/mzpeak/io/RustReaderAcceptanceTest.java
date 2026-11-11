package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.model.Activation;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in: split writes read by the Rust reference reader (mzPeak-reference 2ee2589) through {@code rdump}, a small
 * program on its public API that prints every spectrum, chromatogram and wavelength spectrum as a JSON line (a
 * {@code panic} field where reading one panicked) and their arrays to a little-endian float64 {@code .bin} file.
 * Runs only with {@code -Dmzpeak.rust.rdump=<path to the rdump binary>}:
 * <pre>
 *   mvn -pl mzpeakj test -Dtest=RustReaderAcceptanceTest -Dmzpeak.rust.rdump=/path/to/rdump
 * </pre>
 * Without the {@code ARROW:schema} hint and the auxiliary array columns the Rust reader panicked on every wavelength
 * spectrum ("list array"); without the collision energy promotion it reported an integer, unitless energy as 0.
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
            int off = entry.path("off").asInt();
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
