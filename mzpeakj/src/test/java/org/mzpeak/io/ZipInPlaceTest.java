package org.mzpeak.io;

import org.apache.parquet.hadoop.ParquetFileReader;
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
 * Verifies in-place reading of STORED (uncompressed) members of a single-file {@code .mzpeak} ZIP, including
 * the streaming row-group path reading directly from a slice of the archive (no whole-member buffering).
 * Every spectrum read back from the archive must equal the source spectrum by value.
 */
class ZipInPlaceTest {

    private static final Path FIXTURE = Fixtures.legacy("small.unpacked.mzpeak");
    private static final String ROW_GROUP_SIZE = "mzpeak.writer.rowGroupSize";

    @Test
    void streamsFromStoredZipAcrossRowGroups(@TempDir Path tmp) throws Exception {
        List<Spectrum> spectra = new ArrayList<>();
        List<Chromatogram> chromatograms;
        try (MzPeakReader r = MzPeakReader.open(FIXTURE)) {
            for (Spectrum s : r) {
                spectra.add(s);
            }
            chromatograms = new ArrayList<>(r.chromatograms());
        }

        Path archive = tmp.resolve("multirg.mzpeak");
        String previous = System.getProperty(ROW_GROUP_SIZE);
        System.setProperty(ROW_GROUP_SIZE, "32768"); // many row groups inside the STORED members
        try {
            MzPeakWriter.writeArchive(archive, spectra, chromatograms);
        } finally {
            StreamingReaderTest.restoreProperty(ROW_GROUP_SIZE, previous);
        }

        // sanity: the archived data member really has multiple row groups
        try (MzPeakSource source = MzPeakSource.open(archive);
             ParquetFileReader pfr = ParquetFileReader.open(
                     source.inputFile("spectra_data.parquet"), ParquetGroups.readOptions())) {
            assertThat(pfr.getRowGroups().size()).isGreaterThan(1);
        }

        // reads STORED members in place (FileSliceInputFile) + only the row groups covering each spectrum
        try (MzPeakReader r = MzPeakReader.open(archive)) {
            assertThat(r.size()).isEqualTo(48);
            assertThat(r.getSpectrum(0).orElseThrow().pointCount()).isEqualTo(13589); // profile, spans blocks
            assertThat(r.getSpectrum(25).orElseThrow().peaks()).hasSize(789);
            assertThat(r.getSpectrum(2).orElseThrow().description().primaryPrecursor().primaryIon().mz())
                    .isGreaterThan(0.0);
            StreamingReaderTest.assertReReadsExactly(r, spectra);
        }
    }
}
