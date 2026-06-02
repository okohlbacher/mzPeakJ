package org.mzpeak.io;

import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the row-group-aware streaming store on a deliberately multi-row-group file: spectra that span
 * block boundaries must still be reassembled correctly, matching the single-block read.
 */
class StreamingReaderTest {

    private static final Path FIXTURE = Path.of("src/test/resources/mzpeak/small.unpacked.mzpeak");

    @Test
    void readsCorrectlyAcrossManyRowGroups(@TempDir Path tmp) throws Exception {
        List<Spectrum> spectra = new ArrayList<>();
        List<Chromatogram> chromatograms;
        try (MzPeakReader r = MzPeakReader.open(FIXTURE)) {
            for (Spectrum s : r) {
                spectra.add(s);
            }
            chromatograms = new ArrayList<>(r.chromatograms());
        }

        Path out = tmp.resolve("multirg.mzpeak");
        // 32 KiB row groups -> the ~218k-point data file is split into many blocks.
        System.setProperty("mzpeak.writer.rowGroupSize", "32768");
        try {
            MzPeakWriter.writeDirectory(out, spectra, chromatograms);
        } finally {
            System.clearProperty("mzpeak.writer.rowGroupSize");
        }

        // sanity: the data file really has multiple row groups
        try (ParquetFileReader pfr = ParquetFileReader.open(
                new LocalInputFile(out.resolve("spectra_data.parquet")), ParquetGroups.readOptions())) {
            assertThat(pfr.getRowGroups().size()).isGreaterThan(1);
        }

        // streaming read reassembles spectra spanning block boundaries
        try (MzPeakReader r = MzPeakReader.open(out)) {
            assertThat(r.size()).isEqualTo(48);
            assertThat(r.getSpectrum(0).orElseThrow().pointCount()).isEqualTo(13589); // MS1 profile, spans blocks
            assertThat(r.getSpectrum(5).orElseThrow().peaks()).hasSize(650);
            assertThat(r.getSpectrum(25).orElseThrow().peaks()).hasSize(789);
            // out-of-order access still works (cache handles block-run changes)
            assertThat(r.getSpectrum(1).orElseThrow().pointCount()).isEqualTo(18177);
        }
    }
}
