package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Round-trips the vendored fixture through the writer: read -> write -> read back, to a dir and a ZIP. */
class MzPeakWriterTest {

    private static final Path FIXTURE = Path.of("src/test/resources/mzpeak/small.unpacked.mzpeak");

    private record Loaded(List<Spectrum> spectra, List<Chromatogram> chromatograms) {
    }

    private static Loaded load() {
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            List<Spectrum> spectra = new ArrayList<>();
            for (Spectrum s : reader) {
                spectra.add(s);
            }
            return new Loaded(spectra, new ArrayList<>(reader.chromatograms()));
        }
    }

    private static void assertRoundTrip(MzPeakReader reader) {
        assertThat(reader.size()).isEqualTo(48);

        Spectrum s0 = reader.getSpectrum(0).orElseThrow();
        assertThat(s0.description().msLevel()).isEqualTo(1);
        assertThat(s0.pointCount()).isEqualTo(13589);

        assertThat(reader.getSpectrum(5).orElseThrow().peaks()).hasSize(650);
        assertThat(reader.getSpectrum(25).orElseThrow().peaks()).hasSize(789);

        Spectrum s2 = reader.getSpectrum(2).orElseThrow();
        assertThat(s2.description().primaryPrecursor().primaryIon().mz())
                .isCloseTo(810.789428710938, within(1e-4));

        Chromatogram tic = reader.getChromatogram(0).orElseThrow();
        assertThat(tic.id()).isEqualTo("TIC");
        assertThat(tic.size()).isEqualTo(48);
    }

    @Test
    void roundTripsThroughUnpackedDirectory(@TempDir Path tmp) {
        Loaded data = load();
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, data.spectra(), data.chromatograms());
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertRoundTrip(reader);
        }
    }

    @Test
    void roundTripsThroughStoredZipArchive(@TempDir Path tmp) {
        Loaded data = load();
        Path archive = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeArchive(archive, data.spectra(), data.chromatograms());
        try (MzPeakReader reader = MzPeakReader.open(archive)) {
            assertRoundTrip(reader);
        }
    }
}
