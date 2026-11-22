package org.mzpeak.io;

import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.model.Spectrum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C3 (X-4): members may be empty, and a row group may hold no rows. {@link SpectrumArrayStore} skips row groups of
 * 0 rows: an empty {@code spectra_peaks.parquet} (one row group of 0 rows) reads no peaks, and an empty row group
 * between the two parts of a spectrum does not cut it short. Both members are committed under {@code crafted/} (see
 * {@code crafted/README.md}) and replace the member of a copy of {@code small.unpacked.mzpeak} whose manifest
 * checksum is updated.
 */
class EmptyRowGroupTest {

    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");

    private static final Path CRAFTED = Fixtures.CURRENT.resolve("crafted");

    /** One row group of 0 rows, with the peaks member's schema and footer key-values. */
    private static final Path EMPTY_PEAKS = CRAFTED.resolve("small.spectra_peaks.empty-rowgroup.parquet");

    /** Spectrum 0's first 6000 points, an empty row group, the rest of spectrum 0 and spectra 1.. after it. */
    private static final Path SPLIT_DATA = CRAFTED.resolve("small.spectra_data.split-empty.parquet");

    @Test
    void anEmptyPeaksMemberReadsNoPeaks(@TempDir Path tmp) throws IOException {
        Path copy = replaced(tmp, "spectra_peaks.parquet", EMPTY_PEAKS);

        try (MzPeakReader expected = MzPeakReader.open(SMALL);
             MzPeakReader actual = MzPeakReader.open(copy)) {
            assertThat(actual.size()).isEqualTo(48);
            for (int i = 0; i < 48; i++) {
                assertThat(actual.getSpectrumAt(i).peaks()).as("spectrum %d peaks", i).isEmpty();
            }
            assertThat(expected.getSpectrumAt(2).peaks()).hasSize(485); // the fixture's peaks are not empty
            Spectrum e = expected.getSpectrumAt(0);
            Spectrum a = actual.getSpectrumAt(0);
            assertThat(a.mz()).hasSize(13589);
            LayoutEquivalenceTest.assertIdentical("spectrum 0 m/z", e.mz(), a.mz());
            LayoutEquivalenceTest.assertIdentical("spectrum 0 intensity", e.intensity(), a.intensity());
        }
    }

    @Test
    void anEmptyRowGroupWithinASpectrumDoesNotTruncateIt(@TempDir Path tmp) throws IOException {
        Path copy = replaced(tmp, "spectra_data.parquet", SPLIT_DATA);

        try (MzPeakReader expected = MzPeakReader.open(SMALL);
             MzPeakReader actual = MzPeakReader.open(copy)) {
            assertThat(actual.size()).isEqualTo(48);
            // spectrum 1 first: it lies in row group 2 only, spectrum 0 in row groups 0 and 2
            Spectrum second = actual.getSpectrumAt(1);
            assertThat(second.mz()).hasSize(18177);
            Spectrum first = actual.getSpectrumAt(0);
            assertThat(first.mz()).hasSize(13589);
            assertThat(first.intensity()).hasSize(13589);
            for (int i = 0; i < 48; i++) {
                Spectrum e = expected.getSpectrumAt(i);
                Spectrum a = actual.getSpectrumAt(i);
                LayoutEquivalenceTest.assertIdentical("spectrum " + i + " m/z", e.mz(), a.mz());
                LayoutEquivalenceTest.assertIdentical("spectrum " + i + " intensity", e.intensity(), a.intensity());
                assertThat(a.peaks()).as("spectrum %d peaks", i).isEqualTo(e.peaks());
            }
        }
    }

    /** The store finds no spectrum in a member whose only row group is empty, and one in a split member. */
    @Test
    void theStoreSkipsEmptyRowGroups() {
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(EMPTY_PEAKS), false, Map.of())) {
            assertThat(store.contains(0)).isFalse();
            assertThat(store.contains(2)).isFalse();
            assertThat(store.get(2)).isNull();
        }
        try (SpectrumArrayStore store = SpectrumArrayStore.load(new LocalInputFile(SPLIT_DATA), false, Map.of());
             SpectrumArrayStore fixture = SpectrumArrayStore.load(
                     new LocalInputFile(SMALL.resolve("spectra_data.parquet")), false, Map.of())) {
            assertThat(store.contains(0)).isTrue();
            assertThat(store.contains(42)).isTrue();
            assertThat(store.contains(43)).isFalse();
            for (long i : new long[] {0, 1, 42}) {
                LayoutEquivalenceTest.assertIdentical("spectrum " + i + " m/z", fixture.get(i).mz(),
                        store.get(i).mz());
            }
        }
    }

    /** A copy of small.unpacked whose member {@code name} is {@code crafted}, its manifest checksum updated. */
    private static Path replaced(Path tmp, String name, Path crafted) throws IOException {
        Path copy = MemberRewrite.copy(SMALL, tmp);
        Files.copy(crafted, copy.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        CodecReadTest.updateChecksum(copy, name);
        assertThat(MzPeakReader.verifyIntegrity(copy).valid()).isTrue();
        return copy;
    }
}
