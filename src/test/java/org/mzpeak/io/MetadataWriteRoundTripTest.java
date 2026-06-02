package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.meta.FileMetadata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Reads the fixture's footer metadata, writes it back out, and verifies it round-trips. */
class MetadataWriteRoundTripTest {

    private static final Path FIXTURE = Path.of("src/test/resources/mzpeak/small.mzpeak");

    @Test
    void footerMetadataRoundTrips(@TempDir Path tmp) {
        List<Spectrum> spectra = new ArrayList<>();
        List<Chromatogram> chromatograms;
        FileMetadata original;
        try (MzPeakReader r = MzPeakReader.open(FIXTURE)) {
            for (Spectrum s : r) {
                spectra.add(s);
            }
            chromatograms = new ArrayList<>(r.chromatograms());
            original = r.fileMetadata();
        }

        Path out = tmp.resolve("with-meta.mzpeak");
        MzPeakWriter.writeArchive(out, spectra, chromatograms, original);

        try (MzPeakReader r = MzPeakReader.open(out)) {
            FileMetadata m = r.fileMetadata();
            assertThat(m.run().id()).isEqualTo("small");
            assertThat(m.run().defaultInstrumentId()).isEqualTo(0);
            assertThat(m.software()).extracting(FileMetadata.Software::displayName)
                    .contains("Xcalibur", "ProteoWizard software");
            assertThat(m.instrumentConfigurations()).hasSize(2);
            assertThat(m.instrumentConfigurations().get(0).components()).hasSize(3);
            assertThat(m.fileDescription().sourceFiles())
                    .extracting(FileMetadata.SourceFile::name).contains("small.RAW");
        }
    }
}
