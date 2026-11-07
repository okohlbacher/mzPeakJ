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
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the row-group-aware streaming store on a deliberately multi-row-group file: spectra that span
 * block boundaries must still be reassembled correctly, matching the single-block read.
 */
class StreamingReaderTest {

    private static final Path FIXTURE = Path.of("src/test/resources/mzpeak/small.unpacked.mzpeak");
    private static final String ROW_GROUP_SIZE = "mzpeak.writer.rowGroupSize";

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
        String previous = System.getProperty(ROW_GROUP_SIZE);
        System.setProperty(ROW_GROUP_SIZE, "32768");
        try {
            MzPeakWriter.writeDirectory(out, spectra, chromatograms);
        } finally {
            restoreProperty(ROW_GROUP_SIZE, previous);
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
            assertReReadsExactly(r, spectra);
        }
    }

    /**
     * Looks up every source spectrum in {@code reread}, first in reverse index order and then forward, and
     * asserts bit-identical m/z and intensity arrays and an equal peak list each time. The reverse pass walks the
     * row groups against storage order, so lookups switch block runs backwards; the forward pass then re-reads
     * the same spectra after those switches.
     */
    static void assertReReadsExactly(MzPeakReader reread, List<Spectrum> source) {
        assertThat(reread.size()).isEqualTo(source.size());
        List<Spectrum> byIndex = new ArrayList<>(source);
        byIndex.sort(Comparator.comparingLong(Spectrum::index));
        for (int i = byIndex.size() - 1; i >= 0; i--) {
            assertSameAsSource(reread, byIndex.get(i), "reverse");
        }
        for (Spectrum s : byIndex) {
            assertSameAsSource(reread, s, "forward");
        }
    }

    private static void assertSameAsSource(MzPeakReader reread, Spectrum expected, String pass) {
        long idx = expected.index();
        Spectrum actual = reread.getSpectrum(idx).orElseThrow();
        String what = pass + " re-read of spectrum " + idx;
        LayoutEquivalenceTest.assertIdentical(what + " m/z", expected.mz(), actual.mz());
        LayoutEquivalenceTest.assertIdentical(what + " intensity", expected.intensity(), actual.intensity());
        assertThat(actual.peaks()).as(what + " peaks").isEqualTo(expected.peaks());
    }

    /** Puts a system property back to its value before the test; clears it only if it was unset. */
    static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
