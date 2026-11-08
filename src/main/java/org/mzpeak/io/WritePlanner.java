package org.mzpeak.io;

import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.WritePlan.Entry;
import org.mzpeak.io.WritePlan.SignalArrays;
import org.mzpeak.io.WritePlan.SpectrumEntry;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.ScanSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plans a {@link MzPeakWriter} write without any I/O: enforces the reader's index contract, puts the spectra in
 * index order, routes each spectrum's payload to spectra_data and spectra_peaks, puts each entity's rows in main-axis
 * order, decides which members are written and serializes the footer key-values once. A contract violation throws
 * {@link MzPeakException} before any file or temp directory exists.
 */
final class WritePlanner {

    private WritePlanner() {
    }

    /** Plan the write of the caller's lists, which are never modified; {@code scanSettings} may be {@code null}. */
    static WritePlan plan(List<Spectrum> spectra, List<Chromatogram> chromatograms,
                          List<WavelengthSpectrum> wavelengthSpectra, List<ScanSettings> scanSettings,
                          FileMetadata fileMetadata, Options options) {
        List<Spectrum> ordered = validateAndOrder(spectra, chromatograms, wavelengthSpectra,
                options.metadataLayout());
        WriteReport report = new WriteReport();
        List<SpectrumEntry> spectrumEntries = new ArrayList<>(ordered.size());
        // Numpress always writes spectra_data, if need be without a single row; point layout only when it has rows.
        boolean writesSpectraData = options.signalEncoding() == SignalEncoding.NUMPRESS;
        boolean writesSpectraPeaks = false;
        for (Spectrum s : ordered) {
            // mz()/intensity() go to exactly one of the two files whenever they are not empty.
            SpectrumEntry entry = disposition(s)
                    .withArrays(mainAxisOrder("spectrum", s.index(), s.mz(), s.intensity(), report));
            writesSpectraData |= entry.data();
            writesSpectraPeaks |= entry.peaks();
            spectrumEntries.add(entry);
        }
        List<Entry<Chromatogram>> chromatogramEntries = new ArrayList<>(chromatograms.size());
        for (Chromatogram c : chromatograms) {
            chromatogramEntries.add(new Entry<>(c, c.index(),
                    mainAxisOrder("chromatogram", c.index(), c.time(), c.intensity(), report)));
        }
        List<Entry<WavelengthSpectrum>> wavelengthEntries = new ArrayList<>(wavelengthSpectra.size());
        for (WavelengthSpectrum w : wavelengthSpectra) {
            wavelengthEntries.add(new Entry<>(w, w.index(),
                    mainAxisOrder("wavelength spectrum", w.index(), w.wavelength(), w.intensity(), report)));
        }
        return new WritePlan(options, Collections.unmodifiableList(spectrumEntries),
                Collections.unmodifiableList(chromatogramEntries), Collections.unmodifiableList(wavelengthEntries),
                writesSpectraData, writesSpectraPeaks, scanSettings,
                Collections.unmodifiableMap(FooterMetadataWriter.serialize(fileMetadata)), report);
    }

    // Disposition (design §11.5). Spec spectra.md:7-9 and 40-46 (d7bb25b): profile data goes to spectra_data and
    // centroid data to spectra_peaks, always; centroid spectra MUST NOT be written to spectra_data. The reference
    // writer (base.rs:1016-1058) writes both files only for a PROFILE spectrum that also has peaks, and sends the
    // raw arrays of a CENTROID or UNKNOWN spectrum to the peaks writer. A spectrum read from mzPeak carries its
    // centroids twice, in mz()/intensity() and in peaks(), so a CENTROID spectrum whose arrays are that copy, or
    // that has arrays but no peak list (the SRM shape), writes its arrays to spectra_peaks only. Every other
    // spectrum writes its arrays to spectra_data and its peak list to spectra_peaks: UNKNOWN and null continuity
    // stay in spectra_data, and a CENTROID spectrum whose arrays are not a peak copy keeps both files instead of
    // losing its arrays (both deviate from the reference). So does a CENTROID spectrum with a non-finite m/z
    // (deviation from §11.5): the reader builds a peak from every spectra_peaks row, and such a row does not come
    // back as stored (the null fill interpolates a NaN m/z, and Numpress linear cannot encode NaN or an infinity),
    // so in spectra_peaks it would read back as an invented peak.

    /** Where the rows of {@code s} go, by the disposition table of design §11.5. */
    static SpectrumEntry disposition(Spectrum s) {
        boolean centroid = s.description().signalContinuity() == SignalContinuity.CENTROID;
        if (centroid && allFinite(s.mz()) && (s.peaks().isEmpty() || isPeakCopy(s))) {
            return new SpectrumEntry(s, s.index(), false, s.mz().length > 0, true);
        }
        return new SpectrumEntry(s, s.index(), s.mz().length > 0, !s.peaks().isEmpty(), false);
    }

    private static boolean allFinite(double[] values) {
        for (double v : values) {
            if (!Double.isFinite(v)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the arrays of {@code s} are a copy of its peak list: its continuity is CENTROID, every peak's
     * {@link CentroidPeak#index()} names a distinct row of {@code mz()}/{@code intensity()} holding exactly the
     * peak's values, and every row no peak names has a non-finite m/z (the rows {@link Spectrum} drops from
     * {@code peaks()}). The match goes through {@code index()} because {@code peaks()} is sorted by m/z.
     * {@link #disposition} still writes a peak copy with such a row to both files.
     */
    static boolean isPeakCopy(Spectrum s) {
        if (s.description().signalContinuity() != SignalContinuity.CENTROID) {
            return false;
        }
        double[] mz = s.mz();
        double[] intensity = s.intensity();
        boolean[] matched = new boolean[mz.length];
        for (CentroidPeak p : s.peaks()) {
            int row = p.index();
            if (row < 0 || row >= mz.length || matched[row] || Double.compare(mz[row], p.mz()) != 0
                    || Double.compare(intensity[row], p.intensity()) != 0) {
                return false;
            }
            matched[row] = true;
        }
        for (int row = 0; row < mz.length; row++) {
            if (!matched[row] && Double.isFinite(mz[row])) {
                return false;
            }
        }
        return true;
    }

    // Main-axis order (design §11.5, WR-14). Spec signal-data.md:26-34: an array with a sorting_rank is sorted, and a
    // writer MUST re-sort a ranked array that is not. The main axis of every signal file (m/z, chromatogram time,
    // wavelength) is such an array, declared at sorting_rank 1 from WR-2 on. Re-sorting keeps every row, where
    // rejecting the write would fail on the caller's data. peaks() needs no check: Spectrum sorts it by m/z.

    /**
     * The axis {@code x} and the {@code intensity} of the {@code entity} with {@code index} in the order they are
     * written: the caller's arrays as given when the finite values of {@code x} do not decrease (ties allowed,
     * non-finite rows stay in place), otherwise copies re-sorted by one stable permutation that orders the rows by
     * {@link Double#compare} (NaN last), with one warning in {@code report}. The caller's arrays are never modified.
     */
    private static SignalArrays mainAxisOrder(String entity, long index, double[] x, double[] intensity,
                                              WriteReport report) {
        if (finiteValuesAscend(x)) {
            return SignalArrays.asGiven(x, intensity);
        }
        Integer[] rows = new Integer[x.length];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = i;
        }
        Arrays.sort(rows, (a, b) -> Double.compare(x[a], x[b])); // Arrays.sort of objects is stable
        int[] permutation = new int[rows.length];
        for (int i = 0; i < rows.length; i++) {
            permutation[i] = rows[i];
        }
        report.warn(entity + " " + index + ": main axis not ascending; re-sorted " + x.length + " rows");
        return SignalArrays.permuted(x, intensity, permutation);
    }

    /** Whether the finite values of {@code x}, in order, do not decrease; non-finite values are skipped. */
    private static boolean finiteValuesAscend(double[] x) {
        double previous = Double.NEGATIVE_INFINITY;
        for (double v : x) {
            if (Double.isFinite(v)) {
                if (v < previous) {
                    return false;
                }
                previous = v;
            }
        }
        return true;
    }

    /**
     * Enforce the reader's index contract before anything is written. Spectrum, precursor_index, chromatogram
     * and wavelength-spectrum indices must be non-negative (they are stored as uint64), and spectrum, chromatogram
     * and wavelength-spectrum indices must be unique. The precursor_index checked is that of every precursor
     * {@code layout} writes: the primary precursor's for {@link MetadataLayout#PACKED}, every precursor's for
     * {@link MetadataLayout#SPLIT}. The reader finds a spectrum's data rows through the index range of each row
     * group, so spectra are written in index order: this returns the caller's list when it is already in index
     * order, and otherwise a stable-sorted copy. The caller's list is never modified.
     */
    private static List<Spectrum> validateAndOrder(List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                                   List<WavelengthSpectrum> wavelengthSpectra,
                                                   MetadataLayout layout) {
        boolean inIndexOrder = true;
        long previous = -1;
        for (Spectrum s : spectra) {
            requireNonNegative(s.index(), "spectrum index");
            if (layout == MetadataLayout.SPLIT) {
                for (Precursor p : s.description().precursors()) {
                    requireNonNegativePrecursorIndex(p);
                }
            } else {
                requireNonNegativePrecursorIndex(s.description().primaryPrecursor());
            }
            inIndexOrder &= s.index() >= previous;
            previous = s.index();
        }
        Set<Long> seen = new HashSet<>();
        for (Chromatogram c : chromatograms) {
            requireNonNegative(c.index(), "chromatogram index");
            requireUnique(seen, c.index(), "chromatogram index");
        }
        seen.clear();
        for (WavelengthSpectrum w : wavelengthSpectra) {
            requireNonNegative(w.index(), "wavelength spectrum index");
            requireUnique(seen, w.index(), "wavelength spectrum index");
        }
        List<Spectrum> ordered = spectra;
        if (!inIndexOrder) {
            ordered = new ArrayList<>(spectra);
            ordered.sort(Comparator.comparingLong(Spectrum::index)); // List.sort is stable
        }
        previous = -1;
        for (Spectrum s : ordered) {
            if (s.index() == previous) {
                throw new MzPeakException("duplicate spectrum index: " + s.index() + " (mzPeak indices are unique)");
            }
            previous = s.index();
        }
        return ordered;
    }

    private static void requireNonNegative(long value, String what) {
        if (value < 0) {
            throw new MzPeakException(what + " must be non-negative (mzPeak indices are uint64): " + value);
        }
    }

    private static void requireNonNegativePrecursorIndex(Precursor p) {
        if (p != null && p.precursorIndex() != null) {
            requireNonNegative(p.precursorIndex(), "precursor_index");
        }
    }

    private static void requireUnique(Set<Long> seen, long value, String what) {
        if (!seen.add(value)) {
            throw new MzPeakException("duplicate " + what + ": " + value + " (mzPeak indices are unique)");
        }
    }
}
