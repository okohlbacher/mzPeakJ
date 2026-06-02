package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies in-place reading of STORED (uncompressed) members of a single-file {@code .mzpeak} ZIP, including
 * the streaming row-group path reading directly from a slice of the archive (no whole-member buffering).
 */
class ZipInPlaceTest {

    private static final Path FIXTURE = Path.of("src/test/resources/mzpeak/small.unpacked.mzpeak");

    @Test
    void streamsFromStoredZipAcrossRowGroups(@TempDir Path tmp) {
        List<Spectrum> spectra = new ArrayList<>();
        List<Chromatogram> chromatograms;
        try (MzPeakReader r = MzPeakReader.open(FIXTURE)) {
            for (Spectrum s : r) {
                spectra.add(s);
            }
            chromatograms = new ArrayList<>(r.chromatograms());
        }

        Path archive = tmp.resolve("multirg.mzpeak");
        System.setProperty("mzpeak.writer.rowGroupSize", "32768"); // many row groups inside the STORED members
        try {
            MzPeakWriter.writeArchive(archive, spectra, chromatograms);
        } finally {
            System.clearProperty("mzpeak.writer.rowGroupSize");
        }

        // reads STORED members in place (FileSliceInputFile) + only the row groups covering each spectrum
        try (MzPeakReader r = MzPeakReader.open(archive)) {
            assertThat(r.size()).isEqualTo(48);
            assertThat(r.getSpectrum(0).orElseThrow().pointCount()).isEqualTo(13589); // profile, spans blocks
            assertThat(r.getSpectrum(25).orElseThrow().peaks()).hasSize(789);
            assertThat(r.getSpectrum(2).orElseThrow().description().primaryPrecursor().primaryIon().mz())
                    .isGreaterThan(0.0);
        }
    }
}
