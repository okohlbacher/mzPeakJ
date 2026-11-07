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
 * <p>Spectrum, precursor_index, chromatogram and wavelength-spectrum indices are stored as UINT64, so a negative
 * one is rejected before anything is written, and so is a duplicate spectrum, chromatogram or wavelength-spectrum
 * index: the reader refuses to open a file with a duplicate spectrum.index and merges chromatogram and wavelength
 * rows that share an index. The reader finds a spectrum's data rows through the index range of each row group,
 * so spectra may be passed in any order but are written in index order; the caller's list is never modified.
 */
class WriterIndexContractTest {

    private static final String BASE = "src/test/resources/mzpeak/";
    private static final Path UNPACKED = Path.of(BASE + "small.unpacked.mzpeak");
    private static final Path HAS_UV = Path.of(BASE + "has_uv.mzpeak");
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

    /**
     * Index 47 first, then 0..46. Written in that order, the first row group spans the whole index range, the
     * reader looks for most spectra in that row group only, and reads them back empty. The input list is
     * immutable, so the writer has to sort a copy.
     */
    @ParameterizedTest
    @EnumSource(Layout.class)
    void spectraOutOfIndexOrderAreWrittenInIndexOrder(Layout layout, @TempDir Path tmp) throws IOException {
        Source s = ForwardBackwardCorpusTest.readSource(UNPACKED);
        int last = s.spectra.size() - 1;
        List<Spectrum> lastFirst = new ArrayList<>();
        lastFirst.add(s.spectra.get(last));
        lastFirst.addAll(s.spectra.subList(0, last));
        assertThat(lastFirst.get(0).index()).as("index of the first spectrum passed").isEqualTo(47);
        assertThat(lastFirst.get(0).peaks()).as("peaks of the first spectrum passed").hasSize(636);
        Path dir = tmp.resolve("last-first.mzpeak");

        writeWithSmallRowGroups(layout, dir, List.copyOf(lastFirst), s);

        Path data = dir.resolve("spectra_data.parquet");
        assertMultipleRowGroups(data);
        try (MzPeakReader r = MzPeakReader.open(dir)) {
            StreamingReaderTest.assertReReadsExactly(r, expectedReRead(layout, s, tmp));
        }
        assertThat(indexColumn(dir.resolve("spectra_metadata.parquet"), "spectrum", "index"))
                .as("spectrum.index in spectra_metadata.parquet row order").isSorted();
        assertThat(indexColumn(data, layout == Layout.POINT ? "point" : "chunk", "spectrum_index"))
                .as("spectrum_index in spectra_data.parquet row order").isSorted();
        assertThat(indexColumn(dir.resolve("spectra_peaks.parquet"), layout == Layout.POINT ? "point" : "chunk",
                "spectrum_index"))
                .as("spectrum_index in spectra_peaks.parquet row order").isSorted();
    }

    // ---- unique indices ---------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Layout.class)
    void duplicateSpectrumIndexIsRejectedBeforeWriting(Layout layout, @TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(UNPACKED);
        assertThat(s.spectra.get(17).index()).isEqualTo(17);
        Spectrum twin = withIndex(s.spectra.get(30), 17);
        // Next to the original in otherwise sorted input, and at the end, where only sorting brings them together.
        List<Spectrum> adjacent = new ArrayList<>(s.spectra);
        adjacent.add(18, twin);
        List<Spectrum> appended = new ArrayList<>(s.spectra);
        appended.add(twin);

        Path adjacentDir = tmp.resolve("duplicate-adjacent.mzpeak");
        assertRejectedBeforeWriting(adjacentDir,
                () -> write(layout, adjacentDir, adjacent, s.chromatograms, s.wavelengthSpectra, s),
                "duplicate spectrum index: 17");
        Path appendedDir = tmp.resolve("duplicate-appended.mzpeak");
        assertRejectedBeforeWriting(appendedDir,
                () -> write(layout, appendedDir, appended, s.chromatograms, s.wavelengthSpectra, s),
                "duplicate spectrum index: 17");
    }

    @ParameterizedTest
    @EnumSource(Layout.class)
    void duplicateChromatogramIndexIsRejectedBeforeWriting(Layout layout, @TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(UNPACKED);
        Chromatogram tic = s.chromatograms.get(0);
        List<Chromatogram> chromatograms = List.of(
                new Chromatogram(7, tic.id(), tic.typeCurie(), tic.time(), tic.intensity()),
                new Chromatogram(7, tic.id() + "-copy", tic.typeCurie(), tic.time(), tic.intensity()));
        Path dir = tmp.resolve("duplicate-chromatogram.mzpeak");

        assertRejectedBeforeWriting(dir, () -> write(layout, dir, s.spectra, chromatograms, s.wavelengthSpectra, s),
                "duplicate chromatogram index: 7");
    }

    @ParameterizedTest
    @EnumSource(Layout.class)
    void duplicateWavelengthSpectrumIndexIsRejectedBeforeWriting(Layout layout, @TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(HAS_UV);
        List<WavelengthSpectrum> waves = new ArrayList<>(s.wavelengthSpectra);
        assertThat(waves).as("has_uv wavelength spectra").hasSizeGreaterThan(123);
        WavelengthSpectrum w = waves.get(123);
        waves.add(w);
        Path dir = tmp.resolve("duplicate-wavelength.mzpeak");

        assertRejectedBeforeWriting(dir, () -> write(layout, dir, s.spectra, s.chromatograms, waves, s),
                "duplicate wavelength spectrum index: " + w.index());
    }

    @ParameterizedTest
    @EnumSource(Layout.class)
    void negativeWavelengthSpectrumIndexIsRejectedBeforeWriting(Layout layout, @TempDir Path tmp) {
        Source s = ForwardBackwardCorpusTest.readSource(HAS_UV);
        List<WavelengthSpectrum> waves = new ArrayList<>(s.wavelengthSpectra);
        WavelengthSpectrum w = waves.get(0);
        waves.set(0, new WavelengthSpectrum(-1, w.id(), w.time(), w.lambdaMax(), w.wavelength(), w.intensity()));
        Path dir = tmp.resolve("negative-wavelength.mzpeak");

        assertRejectedBeforeWriting(dir, () -> write(layout, dir, s.spectra, s.chromatograms, waves, s),
                "wavelength spectrum index must be non-negative (mzPeak indices are uint64): -1");
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

    /** The values of {@code group.field} in the file's row order. */
    private static List<Long> indexColumn(Path file, String group, String field) {
        List<Long> values = new ArrayList<>();
        ParquetGroups.forEach(file,
                row -> values.add(ParquetGroups.optLong(ParquetGroups.optGroup(row, group), field)));
        return values;
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
