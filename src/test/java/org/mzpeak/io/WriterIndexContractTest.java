package org.mzpeak.io;

import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.LocalInputFile;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Layout;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The writer's side of the reader's index contract, for both full-form directory writers. All other public
 * {@link MzPeakWriter} overloads delegate to these two.
 *
 * <p>Spectrum, precursor_index and chromatogram indices are stored as UINT64, so a negative one is rejected
 * before anything is written. Spectra may be passed in any order the reader can still look up by index; the
 * caller's list is never modified.
 */
class WriterIndexContractTest {

    private static final String BASE = "src/test/resources/mzpeak/";
    private static final Path UNPACKED = Path.of(BASE + "small.unpacked.mzpeak");
    private static final String ROW_GROUP_SIZE = "mzpeak.writer.rowGroupSize";

    // ---- non-negative indices ---------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Layout.class)
    void negativeSpectrumIndexIsRejectedBeforeWriting(Layout layout, @TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(UNPACKED);
        List<Spectrum> spectra = new ArrayList<>(s.spectra);
        spectra.set(0, withIndex(spectra.get(0), -1));
        Path dir = tmp.resolve("negative-spectrum.mzpeak");

        assertRejectedBeforeWriting(dir, () -> write(layout, dir, spectra, s.chromatograms, s.wavelengthSpectra, s),
                "spectrum index must be non-negative (mzPeak indices are uint64): -1");
    }

    @ParameterizedTest
    @EnumSource(Layout.class)
    void negativePrecursorIndexIsRejectedBeforeWriting(Layout layout, @TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(UNPACKED);
        List<Spectrum> spectra = new ArrayList<>(s.spectra);
        int msn = indexOfFirstWithPrecursor(spectra);
        spectra.set(msn, withPrecursorIndex(spectra.get(msn), -1L));
        Path dir = tmp.resolve("negative-precursor.mzpeak");

        assertRejectedBeforeWriting(dir, () -> write(layout, dir, spectra, s.chromatograms, s.wavelengthSpectra, s),
                "precursor_index must be non-negative (mzPeak indices are uint64): -1");
    }

    @ParameterizedTest
    @EnumSource(Layout.class)
    void negativeChromatogramIndexIsRejectedBeforeWriting(Layout layout, @TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(UNPACKED);
        Chromatogram c = s.chromatograms.get(0);
        List<Chromatogram> chromatograms = List.of(
                new Chromatogram(-1, c.id(), c.typeCurie(), c.time(), c.intensity()));
        Path dir = tmp.resolve("negative-chromatogram.mzpeak");

        assertRejectedBeforeWriting(dir, () -> write(layout, dir, s.spectra, chromatograms, s.wavelengthSpectra, s),
                "chromatogram index must be non-negative (mzPeak indices are uint64): -1");
    }

    // ---- spectrum order ---------------------------------------------------------------------------

    /**
     * A plain reversal keeps the index range of every row group disjoint from the others, so the reader finds
     * each spectrum even when the rows are not in index order.
     */
    @ParameterizedTest
    @EnumSource(Layout.class)
    void reversedSpectrumOrderRoundTripsAcrossRowGroups(Layout layout, @TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(UNPACKED);
        List<Spectrum> reversed = new ArrayList<>(s.spectra);
        Collections.reverse(reversed);
        List<Spectrum> before = List.copyOf(reversed);
        Path dir = tmp.resolve("reversed.mzpeak");

        writeWithSmallRowGroups(layout, dir, reversed, s);

        assertThat(reversed).as("caller's list after the write").containsExactlyElementsOf(before);
        assertMultipleRowGroups(dir.resolve("spectra_data.parquet"));
        try (MzPeakReader r = MzPeakReader.open(dir)) {
            StreamingReaderTest.assertReReadsExactly(r, expectedReRead(layout, s, tmp));
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static void write(Layout layout, Path dir, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                              List<WavelengthSpectrum> wavelengthSpectra, Source s) {
        if (layout == Layout.POINT) {
            MzPeakWriter.writeDirectory(dir, spectra, chromatograms, wavelengthSpectra, s.scanSettings,
                    s.fileMetadata);
        } else {
            MzPeakWriter.writeDirectoryNumpress(dir, spectra, chromatograms, wavelengthSpectra, s.scanSettings,
                    s.fileMetadata, MzPeakWriter.NumpressIntensityEncoding.SLOF);
        }
    }

    /** Writes with 32 KiB row groups, so spectra_data.parquet is split into several row groups. */
    private static void writeWithSmallRowGroups(Layout layout, Path dir, List<Spectrum> spectra, Source s) {
        String previous = System.getProperty(ROW_GROUP_SIZE);
        System.setProperty(ROW_GROUP_SIZE, "32768");
        try {
            write(layout, dir, spectra, s.chromatograms, s.wavelengthSpectra, s);
        } finally {
            StreamingReaderTest.restoreProperty(ROW_GROUP_SIZE, previous);
        }
    }

    /**
     * What a write of the source in index order reads back as: the source itself for the lossless point layout,
     * and a Numpress write of the sorted source re-read for the lossy Numpress layout.
     */
    private static List<Spectrum> expectedReRead(Layout layout, Source s, Path tmp) {
        if (layout == Layout.POINT) {
            return s.spectra;
        }
        Path reference = tmp.resolve("sorted-reference.mzpeak");
        write(layout, reference, s.spectra, s.chromatograms, s.wavelengthSpectra, s);
        return ForwardBackwardCorpusTest.readSource(reference).spectra;
    }

    private static void assertMultipleRowGroups(Path file) throws IOException {
        try (ParquetFileReader pfr = ParquetFileReader.open(new LocalInputFile(file), ParquetGroups.readOptions())) {
            assertThat(pfr.getRowGroups()).as("row groups of %s", file.getFileName()).hasSizeGreaterThan(1);
        }
    }

    private static void assertRejectedBeforeWriting(Path dir, ThrowingCallable write, String... messageParts) {
        assertThatThrownBy(write).isInstanceOf(MzPeakException.class).hasMessageContainingAll(messageParts);
        assertThat(dir).as("output directory after a rejected write").doesNotExist();
    }

    private static int indexOfFirstWithPrecursor(List<Spectrum> spectra) {
        for (int i = 0; i < spectra.size(); i++) {
            if (spectra.get(i).description().primaryPrecursor() != null) {
                return i;
            }
        }
        throw new AssertionError("fixture has no spectrum with a precursor");
    }

    private static Spectrum withIndex(Spectrum s, long index) {
        SpectrumDescription d = s.description();
        return new Spectrum(new SpectrumDescription(index, d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(), d.precursors(),
                d.parameters()), s.mz(), s.intensity(), s.peaks());
    }

    private static Spectrum withPrecursorIndex(Spectrum s, Long precursorIndex) {
        SpectrumDescription d = s.description();
        Precursor p = d.primaryPrecursor();
        List<Precursor> precursors = new ArrayList<>(d.precursors());
        precursors.set(0, new Precursor(precursorIndex, p.precursorId(), p.isolationWindow(), p.selectedIons(),
                p.activation()));
        return new Spectrum(new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(),
                d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(),
                precursors, d.parameters()), s.mz(), s.intensity(), s.peaks());
    }
}
