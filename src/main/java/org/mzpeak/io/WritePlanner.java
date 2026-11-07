package org.mzpeak.io;

import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.WritePlan.Entry;
import org.mzpeak.io.WritePlan.SpectrumEntry;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.ScanSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plans a {@link MzPeakWriter} write without any I/O: enforces the reader's index contract, puts the spectra in
 * index order, routes each spectrum's payload to spectra_data and spectra_peaks, decides which members are written
 * and serializes the footer key-values once. A contract violation throws {@link MzPeakException} before any file or
 * temp directory exists.
 */
final class WritePlanner {

    private WritePlanner() {
    }

    /** Plan the write of the caller's lists, which are never modified; {@code scanSettings} may be {@code null}. */
    static WritePlan plan(List<Spectrum> spectra, List<Chromatogram> chromatograms,
                          List<WavelengthSpectrum> wavelengthSpectra, List<ScanSettings> scanSettings,
                          FileMetadata fileMetadata, Options options) {
        List<Spectrum> ordered = validateAndOrder(spectra, chromatograms, wavelengthSpectra);
        List<SpectrumEntry> spectrumEntries = new ArrayList<>(ordered.size());
        // Numpress always writes spectra_data, if need be without a single row; point layout only when it has rows.
        boolean writesSpectraData = options.signalEncoding() == SignalEncoding.NUMPRESS;
        boolean writesSpectraPeaks = false;
        for (Spectrum s : ordered) {
            SpectrumEntry entry = new SpectrumEntry(s, s.index(), profileRows(s) > 0, peakRows(s) > 0);
            writesSpectraData |= entry.data();
            writesSpectraPeaks |= entry.peaks();
            spectrumEntries.add(entry);
        }
        List<Entry<Chromatogram>> chromatogramEntries = new ArrayList<>(chromatograms.size());
        for (Chromatogram c : chromatograms) {
            chromatogramEntries.add(new Entry<>(c, c.index()));
        }
        List<Entry<WavelengthSpectrum>> wavelengthEntries = new ArrayList<>(wavelengthSpectra.size());
        for (WavelengthSpectrum w : wavelengthSpectra) {
            wavelengthEntries.add(new Entry<>(w, w.index()));
        }
        return new WritePlan(options, Collections.unmodifiableList(spectrumEntries),
                Collections.unmodifiableList(chromatogramEntries), Collections.unmodifiableList(wavelengthEntries),
                writesSpectraData, writesSpectraPeaks, scanSettings,
                Collections.unmodifiableMap(FooterMetadataWriter.serialize(fileMetadata)), new WriteReport());
    }

    // Facet routing is by *container*, not by signal continuity: the mz/intensity point arrays go to
    // spectra_data and the explicit centroid peak list goes to spectra_peaks. This preserves the source
    // layout exactly — including centroid spectra whose centroids are stored in spectra_data (e.g. SRM/MRM
    // transitions, which the reference also stores there), which a continuity-based rule would wrongly
    // flip into spectra_peaks on write.

    /** Rows this spectrum contributes to {@code spectra_data} (the point/profile arrays). */
    static int profileRows(Spectrum s) {
        return s.mz().length;
    }

    /** Rows this spectrum contributes to {@code spectra_peaks} (the explicit centroid peak list). */
    static int peakRows(Spectrum s) {
        return s.peaks().size();
    }

    /**
     * Enforce the reader's index contract before anything is written. Spectrum, precursor_index, chromatogram
     * and wavelength-spectrum indices must be non-negative (they are stored as uint64), and spectrum, chromatogram
     * and wavelength-spectrum indices must be unique. The reader finds a spectrum's data rows through the index
     * range of each row group, so spectra are written in index order: this returns the caller's list when it is
     * already in index order, and otherwise a stable-sorted copy. The caller's list is never modified.
     */
    private static List<Spectrum> validateAndOrder(List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                                   List<WavelengthSpectrum> wavelengthSpectra) {
        boolean inIndexOrder = true;
        long previous = -1;
        for (Spectrum s : spectra) {
            requireNonNegative(s.index(), "spectrum index");
            Precursor p = s.description().primaryPrecursor();
            if (p != null && p.precursorIndex() != null) {
                requireNonNegative(p.precursorIndex(), "precursor_index");
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

    private static void requireUnique(Set<Long> seen, long value, String what) {
        if (!seen.add(value)) {
            throw new MzPeakException("duplicate " + what + ": " + value + " (mzPeak indices are unique)");
        }
    }
}
