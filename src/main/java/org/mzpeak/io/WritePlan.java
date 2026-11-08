package org.mzpeak.io;

import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.ScanSettings;

import java.util.List;
import java.util.Map;

/**
 * Everything {@link DatasetWriter} writes, decided by {@link WritePlanner} before any I/O: the entities in the order
 * they are written and the index each is written with, the precursors as written, the signal files each spectrum goes
 * to, the order of each entity's rows, which members exist, the footer key-values of spectra_metadata and the
 * manifest, and the report that collects the write's warnings. The entities of each kind are in the order of the
 * caller's indices and are written as 0..n-1 in that order (design §11.5, WR-8).
 *
 * @param options            the settings of the write
 * @param spectra            the spectra in index order
 * @param chromatograms      the chromatograms in index order
 * @param wavelengthSpectra  the wavelength spectra in index order
 * @param writesSpectraData  whether spectra_data is written
 * @param writesSpectraPeaks whether spectra_peaks is written
 * @param scanSettings       the scan settings for the manifest, possibly {@code null}: the caller's, in the split
 *                           layout with an id given to each one without
 * @param footer             the file metadata serialized once, for the spectra_metadata footer and the manifest
 * @param report             the warnings of the write
 */
record WritePlan(Options options, List<SpectrumEntry> spectra, List<Entry<Chromatogram>> chromatograms,
                 List<Entry<WavelengthSpectrum>> wavelengthSpectra, boolean writesSpectraData,
                 boolean writesSpectraPeaks, List<ScanSettings> scanSettings, Map<String, String> footer,
                 WriteReport report) {

    /**
     * A spectrum to write: the caller's spectrum, the index it is written with (the {@code source_index} of its facet
     * rows), its precursors as written, and where its rows go (design §11.5): {@code data} sends
     * {@code mz()}/{@code intensity()} to spectra_data; {@code peaks} sends to spectra_peaks either
     * {@code mz()}/{@code intensity()} ({@code peaksFromArrays}) or {@code peaks()}. {@code precursors} are those of
     * the caller's description, in its order, each with the precursor_index it is written with (WR-8).
     * {@code arrays} holds {@code mz()}/{@code intensity()} in the order they are written.
     */
    record SpectrumEntry(Spectrum source, long sourceIndex, List<Precursor> precursors, boolean data, boolean peaks,
                         boolean peaksFromArrays, SignalArrays arrays) {

        /**
         * An entry that writes the caller's precursors as given and {@code mz()}/{@code intensity()} in the caller's
         * order.
         */
        SpectrumEntry(Spectrum source, long sourceIndex, boolean data, boolean peaks, boolean peaksFromArrays) {
            this(source, sourceIndex, source.description().precursors(), data, peaks, peaksFromArrays,
                    SignalArrays.asGiven(source.mz(), source.intensity()));
        }

        /** This entry with {@code arrays} written in place of its own. */
        SpectrumEntry withArrays(SignalArrays arrays) {
            return new SpectrumEntry(source, sourceIndex, precursors, data, peaks, peaksFromArrays, arrays);
        }

        /** This entry written as {@code index}, with {@code precursors} written in place of its own. */
        SpectrumEntry renumbered(long index, List<Precursor> precursors) {
            return new SpectrumEntry(source, index, precursors, data, peaks, peaksFromArrays, arrays);
        }

        /** The first of {@link #precursors()}, the only one the packed layout writes, or {@code null}. */
        Precursor primaryPrecursor() {
            return precursors.isEmpty() ? null : precursors.get(0);
        }

        /** The rows this spectrum writes to spectra_data, its number_of_data_points. */
        int dataRows() {
            return data ? source.mz().length : 0;
        }

        /** The rows this spectrum writes to spectra_peaks, its number_of_peaks. */
        int peakRows() {
            if (!peaks) {
                return 0;
            }
            return peaksFromArrays ? source.mz().length : source.peaks().size();
        }
    }

    /**
     * A chromatogram or wavelength spectrum to write: the caller's object, the index it is written with, and its time
     * or wavelength axis with the intensity in the order they are written.
     */
    record Entry<T>(T source, long sourceIndex, SignalArrays arrays) {
    }

    /**
     * The main axis ({@code x}: m/z, chromatogram time or wavelength) and the intensity of one entity in the order
     * they are written (design §11.5, WR-14). They are the caller's arrays themselves, {@code permutation} null, when
     * the finite axis values do not decrease; otherwise new arrays, re-sorted by the axis, and {@code permutation[i]}
     * is the caller's row written at position {@code i}, for every other array parallel to the axis. The caller's
     * arrays are never modified.
     */
    record SignalArrays(double[] x, double[] intensity, int[] permutation) {

        /** The caller's arrays, written in the caller's order. */
        static SignalArrays asGiven(double[] x, double[] intensity) {
            return new SignalArrays(x, intensity, null);
        }

        /** Copies of the caller's arrays in the order of {@code permutation}. */
        static SignalArrays permuted(double[] x, double[] intensity, int[] permutation) {
            return new SignalArrays(permute(x, permutation), permute(intensity, permutation), permutation);
        }

        private static double[] permute(double[] values, int[] permutation) {
            double[] out = new double[permutation.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = values[permutation[i]];
            }
            return out;
        }
    }

    /**
     * The points spectra_data holds, or spectra_peaks for {@code peaksFile}: the rows every spectrum writes there
     * ({@link SpectrumEntry#dataRows()} or {@link SpectrumEntry#peakRows()}), summed.
     */
    long spectrumPoints(boolean peaksFile) {
        long points = 0;
        for (SpectrumEntry entry : spectra) {
            points += peaksFile ? entry.peakRows() : entry.dataRows();
        }
        return points;
    }

    /** Whether chromatograms_metadata and chromatograms_data are written. */
    boolean writesChromatograms() {
        return !chromatograms.isEmpty();
    }

    /** Whether wavelength_spectra_metadata and wavelength_spectra_data are written. */
    boolean writesWavelengthSpectra() {
        return !wavelengthSpectra.isEmpty();
    }
}
