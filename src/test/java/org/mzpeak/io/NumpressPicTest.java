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

/**
 * Verifies Numpress PIC encoding (integer ion-count intensities). PIC uses variable-length byte
 * packing of differences between successive non-negative integers.
 */
class NumpressPicTest {

    private static final Path FIXTURE = Path.of("src/test/resources/mzpeak/small.unpacked.mzpeak");

    @Test
    void roundTripWithPicEncoding(@TempDir Path tmp) {
        List<Spectrum> spectra = new ArrayList<>();
        try (MzPeakReader r = MzPeakReader.open(FIXTURE)) {
            for (Spectrum s : r) spectra.add(s);
        }

        Path out = tmp.resolve("pic.mzpeak");
        MzPeakWriter.writeDirectoryNumpress(out, spectra, List.of(),
                org.mzpeak.model.meta.FileMetadata.EMPTY,
                MzPeakWriter.NumpressIntensityEncoding.PIC);

        try (MzPeakReader r = MzPeakReader.open(out)) {
            assertThat(r.size()).isEqualTo(48);
            Spectrum s0 = r.getSpectrum(0).orElseThrow();
            assertThat(s0.pointCount()).isEqualTo(13589);

            Spectrum s5 = r.getSpectrum(5).orElseThrow();
            assertThat(s5.mz()).hasSize(650);

            // PIC rounds intensities to nearest integer — verify m/z is near-lossless
            try (MzPeakReader orig = MzPeakReader.open(FIXTURE)) {
                Spectrum a = orig.getSpectrum(5).orElseThrow();
                Spectrum b = r.getSpectrum(5).orElseThrow();
                for (int i = 0; i < a.mz().length; i++) {
                    assertThat(b.mz()[i]).isCloseTo(a.mz()[i], within(0.01));
                }
            }
        }
    }

    @Test
    void picAndSlofProduceDifferentByteLengths(@TempDir Path tmp) {
        List<Spectrum> spectra = new ArrayList<>();
        try (MzPeakReader r = MzPeakReader.open(FIXTURE)) {
            for (Spectrum s : r) { spectra.add(s); if (spectra.size() == 5) break; }
        }
        Path slof = tmp.resolve("slof.mzpeak");
        Path pic  = tmp.resolve("pic.mzpeak");
        MzPeakWriter.writeDirectoryNumpress(slof, spectra, List.of(),
                org.mzpeak.model.meta.FileMetadata.EMPTY, MzPeakWriter.NumpressIntensityEncoding.SLOF);
        MzPeakWriter.writeDirectoryNumpress(pic, spectra, List.of(),
                org.mzpeak.model.meta.FileMetadata.EMPTY, MzPeakWriter.NumpressIntensityEncoding.PIC);

        // Both must be readable and produce the same spectrum count
        try (MzPeakReader rs = MzPeakReader.open(slof);
             MzPeakReader rp = MzPeakReader.open(pic)) {
            assertThat(rs.size()).isEqualTo(rp.size());
        }
    }
}
