package org.mzpeak.io;

import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * Plans a {@link MzPeakWriter} write without any I/O: enforces the reader's index contract, puts every entity in
 * index order and numbers it 0..n-1, maps each precursor_index to the new index of its parent, refuses in the split
 * layout precursors whose selected ions readers would attach to another precursor, routes each spectrum's payload to
 * spectra_data and spectra_peaks, puts each entity's rows in main-axis order, decides which members are written and
 * serializes the footer key-values once. A contract violation throws {@link MzPeakException} before any file or temp
 * directory exists. What the write loses gets one warning in the report for each kind: the extra signal arrays of
 * spectra, the extra arrays of chromatograms and their intensity units, none of which are written yet (WR-11), and, in
 * the packed layout, the scans after a spectrum's first, the precursors after its primary one and the selected ions
 * other than the primary precursor's first. Each re-sorted main axis gets a warning of its own.
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
        Map<Long, Long> writtenIndex = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            writtenIndex.put(ordered.get(i).index(), (long) i);
        }
        WriteReport report = new WriteReport();
        List<SpectrumEntry> spectrumEntries = new ArrayList<>(ordered.size());
        // spectra_data is always written, if need be without a single row (every spectrum centroid): both Rust
        // reference readers open it unconditionally (load_indices_from begins with spectrum_data()?).
        boolean writesSpectraData = true;
        boolean writesSpectraPeaks = false;
        Set<String> droppedExtras = new LinkedHashSet<>();
        int withDroppedExtras = 0;
        // What the packed layout drops: scans after the first, precursors after the primary one, and selected ions
        // other than the primary precursor's first.
        int[] packedDrops = new int[3];
        for (int i = 0; i < ordered.size(); i++) {
            Spectrum s = ordered.get(i);
            List<Precursor> precursors = writtenPrecursors(s.description().precursors(), writtenIndex);
            if (options.metadataLayout() == MetadataLayout.SPLIT) {
                requireOwnKeys(s, i, precursors);
            } else {
                countPackedDrops(s, precursors, packedDrops);
            }
            // mz()/intensity() go to exactly one of the two files whenever they are not empty.
            SpectrumEntry entry = disposition(s).renumbered(i, precursors)
                    .withArrays(mainAxisOrder("spectrum", s.index(), s.mz(), s.intensity(), report));
            writesSpectraPeaks |= entry.peaks();
            spectrumEntries.add(entry);
            if (!s.arrays().isEmpty() || !s.peakArrays().isEmpty()) {
                withDroppedExtras++;
                droppedExtras.addAll(s.arrays().keySet());
                droppedExtras.addAll(s.peakArrays().keySet());
            }
        }
        // Extra signal arrays (Spectrum.arrays(), peakArrays()) are not written yet: one warning for the whole write.
        if (withDroppedExtras > 0) {
            report.warn(withDroppedExtras + (withDroppedExtras == 1 ? " spectrum carries" : " spectra carry")
                    + " extra signal arrays (" + String.join(", ", droppedExtras) + "), which are not written; "
                    + "their m/z and intensity are written");
        }
        if (packedDrops[0] + packedDrops[1] + packedDrops[2] > 0) {
            report.warn("the packed metadata layout writes only the first scan, the primary precursor and its first"
                    + " selected ion of a spectrum: " + counted(packedDrops[0], "later scan", "later scans") + ", "
                    + counted(packedDrops[1], "later precursor", "later precursors") + " and "
                    + counted(packedDrops[2], "other selected ion", "other selected ions") + " are not written");
        }
        List<Chromatogram> chromatogramOrder = inIndexOrder(chromatograms, Chromatogram::index);
        List<Entry<Chromatogram>> chromatogramEntries = new ArrayList<>(chromatogramOrder.size());
        // Extra chromatogram arrays and the intensity unit are not written yet (WR-11): one warning for each.
        Set<String> droppedArrays = new LinkedHashSet<>();
        Set<String> droppedUnits = new LinkedHashSet<>();
        int withDroppedArrays = 0;
        int withDroppedUnit = 0;
        for (int i = 0; i < chromatogramOrder.size(); i++) {
            Chromatogram c = chromatogramOrder.get(i);
            chromatogramEntries.add(new Entry<>(c, i,
                    mainAxisOrder("chromatogram", c.index(), c.time(), c.intensity(), report)));
            if (!c.arrays().isEmpty()) {
                withDroppedArrays++;
                droppedArrays.addAll(c.arrays().keySet());
            }
            if (c.intensityUnit() != null) {
                withDroppedUnit++;
                droppedUnits.add(c.intensityUnit());
            }
        }
        if (withDroppedArrays > 0) {
            report.warn(withDroppedArrays + (withDroppedArrays == 1 ? " chromatogram carries" : " chromatograms carry")
                    + " extra arrays (" + String.join(", ", droppedArrays) + "), which are not written; "
                    + "their time and intensity are written");
        }
        if (withDroppedUnit > 0) {
            report.warn(withDroppedUnit + (withDroppedUnit == 1 ? " chromatogram declares" : " chromatograms declare")
                    + " an intensity unit (" + String.join(", ", droppedUnits) + "), which is not written");
        }
        List<WavelengthSpectrum> wavelengthOrder = inIndexOrder(wavelengthSpectra, WavelengthSpectrum::index);
        List<Entry<WavelengthSpectrum>> wavelengthEntries = new ArrayList<>(wavelengthOrder.size());
        for (int i = 0; i < wavelengthOrder.size(); i++) {
            WavelengthSpectrum w = wavelengthOrder.get(i);
            wavelengthEntries.add(new Entry<>(w, i,
                    mainAxisOrder("wavelength spectrum", w.index(), w.wavelength(), w.intensity(), report)));
        }
        return new WritePlan(options, Collections.unmodifiableList(spectrumEntries),
                Collections.unmodifiableList(chromatogramEntries), Collections.unmodifiableList(wavelengthEntries),
                writesSpectraData, writesSpectraPeaks, scanSettings,
                Collections.unmodifiableMap(FooterMetadataWriter.serialize(fileMetadata)), report);
    }

    /**
     * Adds to {@code drops} what the packed layout does not write of {@code s}, whose precursors as written are
     * {@code precursors}: its scans after the first ({@code drops[0]}), its precursors after the primary one
     * ({@code drops[1]}) and its selected ions other than the primary precursor's first ({@code drops[2]}).
     */
    private static void countPackedDrops(Spectrum s, List<Precursor> precursors, int[] drops) {
        drops[0] += Math.max(0, s.description().scans().size() - 1);
        drops[1] += Math.max(0, precursors.size() - 1);
        int ions = 0;
        for (Precursor p : precursors) {
            ions += p.selectedIons().size();
        }
        drops[2] += precursors.isEmpty() || precursors.get(0).selectedIons().isEmpty() ? ions : ions - 1;
    }

    /** {@code n} followed by {@code singular} or {@code plural}. */
    private static String counted(int n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
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

    // Renumbering (design §11.5, WR-8). Spec spectra.md:91-93 and chromatograms.md:40: the indices of each entity
    // run 0..n-1, and a precursor_index names a spectrum of the file. The Rust reader indexes its count vectors by
    // spectrum index, and a filtered subset (ExtractSpectra) is sparse, so every entity is written as 0..n-1 in the
    // order of the caller's indices, and each precursor_index as the new index of its parent, null when the parent
    // is not written. Input numbered 0..n-1 in index order is written as given.

    /**
     * {@code entities} in the order of their {@code index}: the caller's list when it is already in that order,
     * otherwise a stable-sorted copy. The caller's list is never modified.
     */
    private static <T> List<T> inIndexOrder(List<T> entities, ToLongFunction<T> index) {
        for (int i = 1; i < entities.size(); i++) {
            if (index.applyAsLong(entities.get(i)) < index.applyAsLong(entities.get(i - 1))) {
                List<T> sorted = new ArrayList<>(entities);
                sorted.sort(Comparator.comparingLong(index)); // List.sort is stable
                return sorted;
            }
        }
        return entities;
    }

    /**
     * {@code precursors} as they are written: each precursor_index replaced by the index {@code writtenIndex} gives
     * its parent, or by null when the parent is not written. A precursor whose precursor_index changes is replaced by
     * a new {@link Precursor}; the caller's are never modified, and the caller's list is returned when nothing
     * changes.
     */
    private static List<Precursor> writtenPrecursors(List<Precursor> precursors, Map<Long, Long> writtenIndex) {
        List<Precursor> out = null;
        for (int i = 0; i < precursors.size(); i++) {
            Precursor p = precursors.get(i);
            Long written = p.precursorIndex() == null ? null : writtenIndex.get(p.precursorIndex());
            if (!Objects.equals(written, p.precursorIndex())) {
                if (out == null) {
                    out = new ArrayList<>(precursors);
                }
                out.set(i, new Precursor(written, p.precursorId(), p.isolationWindow(), p.selectedIons(),
                        p.activation()));
            }
        }
        return out == null ? precursors : Collections.unmodifiableList(out);
    }

    // Attribution check (design §11.5, WR-8). The mzPeakJ reader attaches a selected ion to the first precursor of its
    // spectrum whose key (source_index, precursor_index) equals the ion's, null equal to null, whatever the row order
    // (PrecursorJoin). The Rust reference (metadata.rs:1156-1219) also needs a spectrum's selected ions sorted by that
    // key, which SplitMetadataWriter writes them in. When precursors of one spectrum share their written key, the
    // selected ions of every one after the first would silently move to the first. The split layout writes every
    // precursor and refuses exactly that input; a shared key whose later precursors carry no selected ion loses
    // nothing and is written. The typical cause is a multi-precursor spectrum written without its parents: every
    // precursor_index is null. The packed layout writes the primary precursor only and is not checked.

    /**
     * Throws {@link MzPeakException} when a precursor of {@code precursors}, those of {@code s} as written with
     * {@code index}, carries selected ions and an earlier one has its precursor_index (null equal to null).
     */
    private static void requireOwnKeys(Spectrum s, long index, List<Precursor> precursors) {
        for (int i = 1; i < precursors.size(); i++) {
            Long key = precursors.get(i).precursorIndex();
            if (!precursors.get(i).selectedIons().isEmpty() && firstWithKey(precursors, key) < i) {
                throw new MzPeakException(sharedKeyMessage(s, index, precursors, key));
            }
        }
    }

    /** The position of the first of {@code precursors} whose precursor_index equals {@code key}, else -1. */
    private static int firstWithKey(List<Precursor> precursors, Long key) {
        for (int i = 0; i < precursors.size(); i++) {
            if (Objects.equals(precursors.get(i).precursorIndex(), key)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The message for the precursors of {@code s} that share the written precursor_index {@code key}: the spectrum
     * index, the positions and precursor ids of those precursors, the precursors whose selected ions would move to
     * the first of them, and a remedy. When each of them names a different parent that is not written, the remedy is
     * to write the parent spectra as well; otherwise it is to give each precursor a precursor_index of its own.
     */
    private static String sharedKeyMessage(Spectrum s, long index, List<Precursor> precursors, Long key) {
        List<Precursor> given = s.description().precursors();
        List<String> sharing = new ArrayList<>();
        List<String> moved = new ArrayList<>();
        Set<Long> parents = new LinkedHashSet<>();
        boolean everyOneNamesAParent = true;
        int first = firstWithKey(precursors, key);
        for (int i = first; i < precursors.size(); i++) {
            Precursor p = precursors.get(i);
            if (!Objects.equals(p.precursorIndex(), key)) {
                continue;
            }
            sharing.add(i + (p.precursorId() == null ? " (no precursor_id)"
                    : " (precursor_id '" + p.precursorId() + "')"));
            if (i > first && !p.selectedIons().isEmpty()) {
                moved.add(Integer.toString(i));
            }
            Long parent = given.get(i).precursorIndex();
            everyOneNamesAParent &= parent != null && parents.add(parent);
        }
        String remedy = key == null && everyOneNamesAParent
                ? "write their parent spectra (index " + joined(parents.stream().map(String::valueOf).toList())
                + ") as well, so that each precursor keeps a precursor_index of its own"
                : "give each precursor of the spectrum a precursor_index of its own";
        return "spectrum " + s.index() + ": precursors " + joined(sharing) + " share the written key (source_index "
                + index + ", precursor_index " + key + "), so readers would attach the selected ions of precursor "
                + joined(moved) + " to precursor " + first + "; " + remedy;
    }

    /** {@code parts} as {@code a}, {@code a and b} or {@code a, b and c}. */
    private static String joined(List<String> parts) {
        int last = parts.size() - 1;
        return last == 0 ? parts.get(0) : String.join(", ", parts.subList(0, last)) + " and " + parts.get(last);
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
