package org.mzpeak.io;

import org.mzpeak.io.MzPeakManifest.CvEntry;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.WritePlan.Entry;
import org.mzpeak.io.WritePlan.ExtraColumn;
import org.mzpeak.io.WritePlan.IntensityColumn;
import org.mzpeak.io.WritePlan.SignalArrays;
import org.mzpeak.io.WritePlan.SpectrumEntry;
import org.mzpeak.io.WritePlan.SpectrumSignal;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.CvTerms;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.Component;
import org.mzpeak.model.meta.FileMetadata.DataProcessing;
import org.mzpeak.model.meta.FileMetadata.FileDescription;
import org.mzpeak.model.meta.FileMetadata.InstrumentConfiguration;
import org.mzpeak.model.meta.FileMetadata.MsRun;
import org.mzpeak.model.meta.FileMetadata.ProcessingMethod;
import org.mzpeak.model.meta.FileMetadata.Sample;
import org.mzpeak.model.meta.FileMetadata.Software;
import org.mzpeak.model.meta.FileMetadata.SourceFile;
import org.mzpeak.model.meta.ScanSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;

/**
 * Plans a {@link MzPeakWriter} write without any I/O: enforces the reader's index contract, puts every entity in
 * index order and numbers it 0..n-1, maps each precursor_index to the new index of its parent (a spectrum for a
 * spectrum's precursor, a chromatogram for a chromatogram's), refuses in the split layout precursors whose selected
 * ions readers would attach to another precursor, appends there the MSn selected-ion chain ({@link #withChain}),
 * routes each spectrum's payload to spectra_data and spectra_peaks, puts each entity's rows in main-axis order,
 * names the intensity and extra columns of the spectrum signal files and of chromatograms_data,
 * decides which members are written and serializes the footer key-values
 * once. A contract violation throws {@link MzPeakException} before any file or temp directory exists. What the packed
 * layout loses gets one warning in the report for each kind: the scans after a spectrum's first, the precursors after
 * its primary one and the selected ions other than the primary precursor's first (one warning), the data processing
 * references of spectra (another), the Params of wavelength spectra (a third), and the polarities, data processing
 * references, Params and precursors of chromatograms (a fourth). Each re-sorted main axis gets a warning of its own, and so do, in the split layout, the
 * scan settings without an id, which get one. A NaN in an axis (m/z, chromatogram time, wavelength) or an intensity
 * is refused as a contract violation in every encoding ({@link #requireNoNaN}), and with
 * {@link SignalEncoding#NUMPRESS} so is a value the Numpress codecs cannot encode. So is a CURIE whose CV prefix
 * {@code metadata.cv_list} would not declare, and a reference that names nothing of the file metadata
 * ({@link Declarations}, W12).
 *
 * <p>A {@link MzPeakWriter.Session} is planned one spectrum at a time ({@link #sessionSignal}) with the same checks,
 * its spectrum signal files get the intensity columns of the units and the extra columns of the keys it is opened
 * with ({@link #spectrumIntensities}, {@link #spectrumExtras}), and its
 * chromatograms and wavelength spectra are planned at its finish ({@link #planFinish}) as here.
 */
final class WritePlanner {

    /** MS:1000786, non-standard data array: the array type of an extra key that is not a CURIE. */
    private static final String NON_STANDARD_ARRAY = "MS:1000786";
    /** An extra key that is a CURIE, {@code PREFIX:local}; any other key names a non-standard data array. */
    private static final Pattern CURIE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*:[A-Za-z0-9_]+");
    /** The columns of the spectrum signal files in either encoding, which no extra column may take. */
    private static final Set<String> SPECTRUM_SIGNAL_COLUMNS = Set.of("spectrum_index", "mz", "intensity",
            "mz_chunk_start", "mz_chunk_end", "mz_chunk_values", "chunk_encoding", "mz_numpress_linear_bytes",
            "intensity_numpress_slof_bytes", "intensity_numpress_pic_bytes");
    /**
     * The columns of chromatograms_data other than its intensity columns, which neither an intensity column nor an
     * extra column may take.
     */
    private static final Set<String> CHROMATOGRAM_SIGNAL_COLUMNS = Set.of("chromatogram_index", "time");
    /** The column of the primary intensity of every signal file. */
    private static final String INTENSITY_COLUMN = "intensity";
    /** The column names signal-data.md:119-139 recommends, for the array types an extra column may have. */
    private static final Map<String, String> SHORT_NAMES = Map.ofEntries(
            Map.entry("MS:1000516", "charge"),
            Map.entry("MS:1000517", "signal_to_noise"),
            Map.entry("MS:1000595", "time"),
            Map.entry("MS:1000617", "wavelength"),
            Map.entry("MS:1002530", "baseline"),
            Map.entry("MS:1002529", "resolution"),
            Map.entry("MS:1002893", "ion_mobility"),
            Map.entry("MS:1003007", "raw_ion_mobility"),
            Map.entry("MS:1002816", "mean_ion_mobility"),
            Map.entry("MS:1003154", "deconvoluted_ion_mobility"),
            Map.entry("MS:1003008", "raw_inverse_reduced_ion_mobility"),
            Map.entry("MS:1003006", "mean_inverse_reduced_ion_mobility"),
            Map.entry("MS:1003155", "deconvoluted_inverse_reduced_ion_mobility"),
            Map.entry("MS:1003153", "raw_drift_time"),
            Map.entry("MS:1002477", "mean_drift_time"),
            Map.entry("MS:1003156", "deconvoluted_ion_mobility_drift_time"));

    private WritePlanner() {
    }

    /** Plan the write of the caller's lists, which are never modified; {@code scanSettings} may be {@code null}. */
    static WritePlan plan(List<Spectrum> spectra, List<Chromatogram> chromatograms,
                          List<WavelengthSpectrum> wavelengthSpectra, List<ScanSettings> scanSettings,
                          FileMetadata fileMetadata, Options options) {
        List<Chromatogram> inMinutes = inMinutes(chromatograms);
        List<Spectrum> ordered = validateAndOrder(spectra, inMinutes, wavelengthSpectra,
                options.metadataLayout());
        requireWritableScanSettings(scanSettings);
        Declarations declared = declarations(fileMetadata, scanSettings, options);
        ordered.forEach(declared::require);
        inMinutes.forEach(declared::require);
        wavelengthSpectra.forEach(declared::require);
        Map<Long, Long> writtenIndex = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            writtenIndex.put(ordered.get(i).index(), (long) i);
        }
        WriteReport report = new WriteReport();
        List<SpectrumEntry> spectrumEntries = new ArrayList<>(ordered.size());
        List<SpectrumSignal> spectrumSignals = new ArrayList<>(ordered.size());
        Set<String> extraKeys = new LinkedHashSet<>();
        Set<String> units = new LinkedHashSet<>();
        // What the packed layout drops: scans after the first, precursors after the primary one, selected ions other
        // than the primary precursor's first, and data processing references.
        int[] packedDrops = new int[4];
        List<List<Precursor>> written = new ArrayList<>(ordered.size());
        for (Spectrum s : ordered) {
            written.add(writtenPrecursors(s.description().precursors(), writtenIndex));
        }
        for (int i = 0; i < ordered.size(); i++) {
            Spectrum s = ordered.get(i);
            List<Precursor> precursors = written.get(i);
            if (options.metadataLayout() == MetadataLayout.SPLIT) {
                requireOwnKeys(Owner.SPECTRUM, s.index(), i, s.description().precursors(), precursors);
                precursors = withChain(precursors, written);
            } else {
                countPackedDrops(s, precursors, packedDrops);
            }
            addExtraKeys(s, extraKeys);
            // mz()/intensity() go to exactly one of the two files whenever they are not empty.
            SpectrumSignal signal = signal(s, disposition(s).renumbered(i, precursors), options, report);
            units.addAll(signal.intensityUnits());
            spectrumEntries.add(signal.entry());
            spectrumSignals.add(signal);
        }
        warnPackedDrops(packedDrops, report);
        // every column is named before the first row is written (signal-data.md l.138): the intensity columns of the
        // units the spectra write, then the extra columns, which take none of their names
        Set<String> columns = new HashSet<>(SPECTRUM_SIGNAL_COLUMNS);
        List<IntensityColumn> intensities = spectrumIntensities(units.isEmpty() ? Set.of(ArrayIndex.COUNTS_UNIT)
                : units, columns);
        List<ExtraColumn> extras = new ArrayList<>(extraKeys.size());
        for (String key : extraKeys) {
            extras.add(extraColumn(key, columns));
        }
        return complete(options, Collections.unmodifiableList(spectrumEntries),
                Collections.unmodifiableList(spectrumSignals), intensities, List.copyOf(extras), inMinutes,
                wavelengthSpectra, scanSettings,
                FooterMetadataWriter.serialize(fileMetadata, options.metadataLayout()), report);
    }

    /**
     * The plan of a {@link MzPeakWriter.Session} at its finish (design §7, MZ-11): its {@code spectra}, appended and
     * numbered 0..n-1 as added, with their signal already written and their precursors as given, the
     * {@code spectrumIntensities} and {@code spectrumExtras} of its spectrum signal files, and the chromatograms and
     * wavelength spectra added to it, which are checked, ordered and numbered as {@link #plan} does it. The plan has
     * no spectrum signals. {@code footer} is the session's serialized file metadata, and {@code report} holds the
     * warnings of the spectra added.
     */
    static WritePlan planFinish(Options options, List<SpectrumEntry> spectra,
                                List<IntensityColumn> spectrumIntensities, List<ExtraColumn> spectrumExtras,
                                List<Chromatogram> chromatograms, List<WavelengthSpectrum> wavelengthSpectra,
                                List<ScanSettings> scanSettings, Map<String, String> footer, WriteReport report) {
        validateOthers(chromatograms, wavelengthSpectra, options.metadataLayout());
        return complete(options, options.metadataLayout() == MetadataLayout.SPLIT ? withChains(spectra) : spectra,
                List.of(), spectrumIntensities, spectrumExtras, chromatograms, wavelengthSpectra, scanSettings, footer,
                report);
    }

    // MSn selected-ion chain (design D13-18, W7). Spec spectra.md (spectra_metadata_precursors): for higher MSn the
    // writer MUST replicate the chain of selected ions from previous MS levels with the same source_index but each
    // previous level's precursor_index, null when the file does not contain that spectrum, and SHOULD store the
    // precursors in descending MS level. The split layout appends to a spectrum's own precursors those of each written
    // parent, breadth first, so recursively, skipping a precursor whose precursor_index (null equal to null) the
    // spectrum already lists; that also ends a cycle. An appended precursor never shares the key of an earlier one, so
    // the check of requireOwnKeys holds for the chain. The packed layout writes the primary precursor only, which is
    // always the spectrum's own, so it gets no chain.

    /**
     * {@code spectra}, a session's spectra numbered 0..n-1 whose precursor_index each names one of them or is null,
     * each with its precursors as {@link #withChain} chains them: the caller's list when no spectrum gets a chain,
     * otherwise a copy with each such entry renumbered with its chain.
     */
    private static List<SpectrumEntry> withChains(List<SpectrumEntry> spectra) {
        List<List<Precursor>> own = new ArrayList<>(spectra.size());
        for (SpectrumEntry entry : spectra) {
            own.add(entry.precursors());
        }
        List<SpectrumEntry> out = null;
        for (int i = 0; i < spectra.size(); i++) {
            SpectrumEntry entry = spectra.get(i);
            List<Precursor> chained = withChain(entry.precursors(), own);
            if (chained != entry.precursors()) {
                if (out == null) {
                    out = new ArrayList<>(spectra);
                }
                out.set(i, entry.renumbered(entry.sourceIndex(), chained));
            }
        }
        return out == null ? spectra : Collections.unmodifiableList(out);
    }

    /**
     * {@code precursors}, as written, followed by the chain of selected ions: for each precursor in turn, those it
     * appends included, the precursors of the parent its precursor_index names, {@code written.get(parent)}, that
     * have a precursor_index none of the list has yet. {@code written} holds the precursors as written of every
     * spectrum, by its written index. The list given is returned when nothing is appended.
     */
    private static List<Precursor> withChain(List<Precursor> precursors, List<List<Precursor>> written) {
        List<Precursor> all = precursors;
        for (int i = 0; i < all.size(); i++) {
            Long parent = all.get(i).precursorIndex();
            if (parent == null) {
                continue;
            }
            for (Precursor p : written.get(Math.toIntExact(parent))) {
                if (firstWithKeyOrSize(all, p.precursorIndex()) == all.size()) {
                    if (all == precursors) {
                        all = new ArrayList<>(precursors);
                    }
                    all.add(p);
                }
            }
        }
        return all == precursors ? precursors : Collections.unmodifiableList(all);
    }

    /** The position of the first of {@code precursors} whose precursor_index equals {@code key}, else their size. */
    private static int firstWithKeyOrSize(List<Precursor> precursors, Long key) {
        int i = 0;
        while (i < precursors.size() && !Objects.equals(precursors.get(i).precursorIndex(), key)) {
            i++;
        }
        return i;
    }

    /**
     * The plan of the planned spectra with their signals, the chromatograms and wavelength spectra, which are already
     * checked by {@link #validateOthers} and are here ordered, numbered and checked further, and the scan settings,
     * which the split layout gives ids.
     */
    private static WritePlan complete(Options options, List<SpectrumEntry> spectra, List<SpectrumSignal> signals,
                                      List<IntensityColumn> spectrumIntensities, List<ExtraColumn> spectrumExtras,
                                      List<Chromatogram> chromatograms,
                                      List<WavelengthSpectrum> wavelengthSpectra, List<ScanSettings> scanSettings,
                                      Map<String, String> footer, WriteReport report) {
        List<Chromatogram> chromatogramOrder = inIndexOrder(chromatograms, Chromatogram::index);
        List<Entry<Chromatogram>> chromatogramEntries = new ArrayList<>(chromatogramOrder.size());
        Set<String> chromatogramColumns = new HashSet<>(CHROMATOGRAM_SIGNAL_COLUMNS);
        List<IntensityColumn> chromatogramIntensities = intensityColumns(chromatogramOrder, chromatogramColumns);
        Map<String, ExtraColumn> chromatogramExtras = new LinkedHashMap<>();
        Map<Long, Long> writtenChromatogramIndex = new HashMap<>();
        for (int i = 0; i < chromatogramOrder.size(); i++) {
            writtenChromatogramIndex.put(chromatogramOrder.get(i).index(), (long) i);
        }
        // What the packed layout, whose chromatogram schema is frozen, does not write of chromatograms.
        int[] chromatogramDrops = new int[4];
        for (int i = 0; i < chromatogramOrder.size(); i++) {
            Chromatogram c = withWrittenPrecursors(chromatogramOrder.get(i), i, writtenChromatogramIndex,
                    options.metadataLayout());
            addExtraColumns(c, chromatogramExtras, chromatogramColumns);
            chromatogramEntries.add(new Entry<>(c, i,
                    mainAxisOrder("chromatogram", c.index(), c.time(), c.intensity(), report)));
            countChromatogramDrops(c, chromatogramDrops);
        }
        if (options.metadataLayout() == MetadataLayout.PACKED) {
            warnPackedChromatogramDrops(chromatogramDrops, report);
        }
        List<WavelengthSpectrum> wavelengthOrder = inIndexOrder(wavelengthSpectra, WavelengthSpectrum::index);
        List<Entry<WavelengthSpectrum>> wavelengthEntries = new ArrayList<>(wavelengthOrder.size());
        // The packed wavelength schema is frozen without a parameters list: one warning for the Params it drops.
        int withParams = 0;
        int params = 0;
        for (int i = 0; i < wavelengthOrder.size(); i++) {
            WavelengthSpectrum w = wavelengthOrder.get(i);
            wavelengthEntries.add(new Entry<>(w, i,
                    mainAxisOrder("wavelength spectrum", w.index(), w.wavelength(), w.intensity(), report)));
            if (!w.parameters().isEmpty()) {
                withParams++;
                params += w.parameters().size();
            }
        }
        if (withParams > 0 && options.metadataLayout() == MetadataLayout.PACKED) {
            report.warn(counted(withParams, "wavelength spectrum carries", "wavelength spectra carry")
                    + " parameters (" + params + " in all), which the packed metadata layout does not write");
        }
        // spectra_data is always written, if need be without a single row (every spectrum centroid): both Rust
        // reference readers open it unconditionally (load_indices_from begins with spectrum_data()?).
        boolean writesSpectraData = true;
        return new WritePlan(options, spectra, signals, Collections.unmodifiableList(chromatogramEntries),
                Collections.unmodifiableList(wavelengthEntries), writesSpectraData, spectrumIntensities,
                spectrumExtras, chromatogramIntensities, List.copyOf(chromatogramExtras.values()),
                options.metadataLayout() == MetadataLayout.SPLIT ? withIds(scanSettings, report) : scanSettings,
                Collections.unmodifiableMap(footer), report);
    }

    // Session spectra (design §7, MZ-11). A session writes each spectrum as it is added, so the spectra come in
    // index order numbered 0..n-1, each written with its own index and its precursors as given, and the intensity and
    // extra columns of the spectrum signal files are declared when the session opens (signal-data.md l.138). A
    // spectrum is checked as the batch plan checks it, before anything of it is written; whether each precursor_index
    // names a spectrum of the dataset is known only at the finish.

    /**
     * The intensity columns of the spectrum signal files of a session opened with the spectrum intensity
     * {@code units} ({@link Options#spectrumIntensityUnits()}), named as {@link #plan} names the units of the spectra
     * it writes in that order.
     */
    static List<IntensityColumn> spectrumIntensities(Collection<String> units) {
        return spectrumIntensities(units, new HashSet<>(SPECTRUM_SIGNAL_COLUMNS));
    }

    /**
     * The extra columns of the spectrum signal files of a session opened with the spectrum array {@code keys} and
     * the {@code intensities} columns: one for each key, in their order, named as {@link #plan} names the keys it
     * meets in that order.
     *
     * @throws MzPeakException when a key is MS:1000514 or MS:1000515, which a spectrum holds in {@code mz()} and
     *                         {@code intensity()}
     */
    static List<ExtraColumn> spectrumExtras(Collection<String> keys, List<IntensityColumn> intensities) {
        Set<String> columns = new HashSet<>(SPECTRUM_SIGNAL_COLUMNS);
        spectrumIntensities(intensities.stream().map(IntensityColumn::unit).toList(), columns);
        List<ExtraColumn> out = new ArrayList<>(keys.size());
        for (String key : keys) {
            requireExtraSpectrumKey("spectrumArrays", key);
            out.add(extraColumn(key, columns));
        }
        return List.copyOf(out);
    }

    /**
     * The signal of {@code s}, the next spectrum of a session whose spectrum signal files have a column for each of
     * the {@code declared} spectrum arrays and the {@code intensities} columns, written with its own index and its
     * precursors as given. Its warnings go to
     * {@code report}; in the packed layout, what that layout does not write of it is added to {@code packedDrops}
     * ({@link #countPackedDrops}) once every check has passed.
     *
     * @throws MzPeakException when a key of its {@link Spectrum#arrays()} or {@link Spectrum#peakArrays()} is not
     *                         declared, when the unit of an intensity it writes ({@link SpectrumSignal#intensityUnit})
     *                         has no column, when a precursor_index that the layout writes is negative, or when it
     *                         breaks another rule of {@link #plan}: in the split layout, precursors that share a
     *                         precursor_index while a later one carries selected ions; peak indices that are no
     *                         position of its peak arrays; a NaN m/z or intensity; a value Numpress cannot encode
     */
    static SpectrumSignal sessionSignal(Spectrum s, Set<String> declared, List<IntensityColumn> intensities,
                                        Options options, WriteReport report, int[] packedDrops) {
        for (Map<String, double[]> arrays : List.of(s.arrays(), s.peakArrays())) {
            for (String key : arrays.keySet()) {
                if (!declared.contains(key)) {
                    throw new MzPeakException("spectrum " + s.index() + ": the array " + key + " is not one of the"
                            + " spectrum arrays the session was opened with " + declared);
                }
            }
        }
        List<Precursor> precursors = s.description().precursors();
        requireWritableParams(s);
        boolean split = options.metadataLayout() == MetadataLayout.SPLIT;
        if (split) {
            for (Precursor p : precursors) {
                requireNonNegativePrecursorIndex(p);
            }
            requireOwnKeys(Owner.SPECTRUM, s.index(), s.index(), precursors, precursors);
        } else {
            requireNonNegativePrecursorIndex(s.description().primaryPrecursor());
        }
        SpectrumSignal signal = signal(s, disposition(s), options, report);
        List<String> units = intensities.stream().map(IntensityColumn::unit).toList();
        for (String unit : signal.intensityUnits()) {
            if (!units.contains(unit)) {
                throw new MzPeakException("spectrum " + s.index() + ": its intensity unit " + unit + " is not one of"
                        + " the spectrum intensity units the session was opened with " + units + "; declare it with"
                        + " MzPeakWriter.Options.withSpectrumIntensityUnits");
            }
        }
        if (!split) {
            countPackedDrops(s, precursors, packedDrops);
        }
        return signal;
    }

    /**
     * Throws {@link MzPeakException} when a precursor_index of {@code spectra}, a session's spectra numbered
     * 0..n-1, that the {@code layout} writes (every precursor's in the split layout, the primary one's in the packed
     * layout) names no spectrum of them. {@link #plan} writes such a reference as null instead (design D13-10).
     */
    static void requireWrittenParents(List<SpectrumEntry> spectra, MetadataLayout layout) {
        for (SpectrumEntry entry : spectra) {
            List<Precursor> precursors = entry.precursors();
            int written = layout == MetadataLayout.SPLIT ? precursors.size() : Math.min(1, precursors.size());
            for (int i = 0; i < written; i++) {
                Long parent = precursors.get(i).precursorIndex();
                if (parent != null && parent >= spectra.size()) {
                    throw new MzPeakException("spectrum " + entry.sourceIndex() + ": precursor_index " + parent
                            + " names no spectrum of the " + spectra.size() + " written; a precursor_index is the"
                            + " index of a spectrum of the dataset");
                }
            }
        }
    }

    /**
     * The signal of {@code s}, whose {@code entry} says where its rows go and how it is written: its main axis in
     * the order it is written, whose re-sort gets a warning in {@code report}, after the checks that its peak
     * indices are positions of its peak arrays, that its m/z and intensities, of its peak list too, are not NaN, and,
     * with Numpress, that its values can be encoded.
     */
    private static SpectrumSignal signal(Spectrum s, SpectrumEntry entry, Options options, WriteReport report) {
        String owner = "spectrum " + s.index();
        requireNoNaN(owner, "m/z", s.mz());
        requireNoNaN(owner, "intensity", s.intensity());
        List<CentroidPeak> peaks = s.peaks();
        for (int i = 0; i < peaks.size(); i++) {
            if (Double.isNaN(peaks.get(i).intensity())) {
                throw nanValue(owner, "the intensity of the peak at position " + i + " (m/z " + peaks.get(i).mz()
                        + ")");
            }
        }
        SpectrumSignal signal = new SpectrumSignal(s, entry,
                mainAxisOrder("spectrum", s.index(), s.mz(), s.intensity(), report));
        if (entry.peaks() && !entry.peaksFromArrays() && !s.peakArrays().isEmpty()) {
            requirePeakPositions(s);
        }
        requireNumpressEncodable(signal, options);
        return signal;
    }

    /**
     * {@code scanSettings} as the split layout writes them, {@code null} when they are: each one without an id gets
     * {@code scan_settings_<position>}, with {@code _1}, {@code _2}, ... appended while another entry has that id,
     * and keeps its params, source file references and targets, and one warning in {@code report} names the positions
     * and the ids given. The current manifest needs an id: the reference's ScanSettings.id is a String
     * (param.rs:458-460), so the Rust reader rejects a null one. The caller's list is returned when every entry has
     * an id. The packed layout leaves the key out instead.
     */
    private static List<ScanSettings> withIds(List<ScanSettings> scanSettings, WriteReport report) {
        if (scanSettings == null || scanSettings.stream().allMatch(s -> s.id() != null)) {
            return scanSettings;
        }
        Set<String> taken = new HashSet<>();
        for (ScanSettings s : scanSettings) {
            if (s.id() != null) {
                taken.add(s.id());
            }
        }
        List<ScanSettings> out = new ArrayList<>(scanSettings.size());
        List<String> positions = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < scanSettings.size(); i++) {
            ScanSettings s = scanSettings.get(i);
            if (s.id() != null) {
                out.add(s);
                continue;
            }
            String id = "scan_settings_" + i;
            for (int suffix = 1; !taken.add(id); suffix++) {
                id = "scan_settings_" + i + "_" + suffix;
            }
            out.add(new ScanSettings(id, s.parameters(), s.sourceFileRefs(), s.targets()));
            positions.add(Integer.toString(i));
            ids.add(id);
        }
        report.warn("scan settings " + joined(positions) + (positions.size() == 1 ? " has" : " have")
                + " no id, which the current manifest requires; written as " + joined(ids));
        return Collections.unmodifiableList(out);
    }

    /**
     * Adds to {@code drops} what the packed layout does not write of {@code s}, whose precursors as written are
     * {@code precursors}: its scans after the first ({@code drops[0]}), its precursors after the primary one
     * ({@code drops[1]}), its selected ions other than the primary precursor's first ({@code drops[2]}) and whether it
     * has a data processing reference ({@code drops[3]}), for which the frozen packed schema has no column.
     */
    private static void countPackedDrops(Spectrum s, List<Precursor> precursors, int[] drops) {
        drops[0] += Math.max(0, s.description().scans().size() - 1);
        drops[1] += Math.max(0, precursors.size() - 1);
        int ions = 0;
        for (Precursor p : precursors) {
            ions += p.selectedIons().size();
        }
        drops[2] += precursors.isEmpty() || precursors.get(0).selectedIons().isEmpty() ? ions : ions - 1;
        if (s.description().dataProcessingRef() != null) {
            drops[3]++;
        }
    }

    /**
     * Records in {@code report} the one warning about the scans, precursors and selected ions the packed layout does
     * not write of the spectra counted in {@code drops} ({@link #countPackedDrops}), if any, and another about their
     * data processing references, if any.
     */
    static void warnPackedDrops(int[] drops, WriteReport report) {
        if (drops[0] + drops[1] + drops[2] > 0) {
            report.warn("the packed metadata layout writes only the first scan, the primary precursor and its first"
                    + " selected ion of a spectrum: " + counted(drops[0], "later scan", "later scans") + ", "
                    + counted(drops[1], "later precursor", "later precursors") + " and "
                    + counted(drops[2], "other selected ion", "other selected ions") + " are not written");
        }
        if (drops[3] > 0) {
            report.warn("the packed metadata layout writes no data processing reference of a spectrum: "
                    + counted(drops[3], "spectrum loses its reference", "spectra lose their references"));
        }
    }

    /**
     * Adds to {@code drops} what the packed layout does not write of {@code c}: its precursors ({@code drops[0]}),
     * whether its polarity is known ({@code drops[1]}), whether it has a data processing reference ({@code drops[2]})
     * and its Params ({@code drops[3]}).
     */
    private static void countChromatogramDrops(Chromatogram c, int[] drops) {
        drops[0] += c.precursors().size();
        if (c.polarity() != Polarity.UNKNOWN) {
            drops[1]++;
        }
        if (c.dataProcessingRef() != null) {
            drops[2]++;
        }
        drops[3] += c.parameters().size();
    }

    /**
     * Records in {@code report} the one warning about what the packed layout, whose chromatogram schema is frozen,
     * does not write of the chromatograms counted in {@code drops} ({@link #countChromatogramDrops}), if anything:
     * their polarities, data processing references, Params and precursors.
     */
    private static void warnPackedChromatogramDrops(int[] drops, WriteReport report) {
        if (drops[0] + drops[1] + drops[2] + drops[3] > 0) {
            report.warn("the packed metadata layout writes no polarity, data processing reference, parameters or"
                    + " precursors of a chromatogram: " + counted(drops[1], "polarity", "polarities") + ", "
                    + counted(drops[2], "data processing reference", "data processing references") + ", "
                    + counted(drops[3], "parameter", "parameters") + " and "
                    + counted(drops[0], "precursor", "precursors") + " are not written");
        }
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
    // back as stored (the null fill replaces or rejects a NaN m/z, and Numpress linear cannot encode NaN or infinity),
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
     * peak's values, extra values included, and every row no peak names has a non-finite m/z (the rows
     * {@link Spectrum} drops from {@code peaks()}). The match goes through {@code index()} because {@code peaks()} is
     * sorted by m/z. Extra values match when {@code arrays()} and {@code peakArrays()} have the same keys and, for
     * each key, the peak array's value at the peak's {@code index()} equals the array's value at that row, so a peak
     * copy loses no peak array when only its arrays are written. The intensity units match too, null as MS:1000131
     * ({@link SpectrumSignal#intensityUnit}), so that the peak list keeps its unit. {@link #disposition} still writes
     * a peak copy with such a row to both files.
     */
    static boolean isPeakCopy(Spectrum s) {
        if (s.description().signalContinuity() != SignalContinuity.CENTROID
                || !s.arrays().keySet().equals(s.peakArrays().keySet())
                || !orCounts(s.intensityUnit()).equals(orCounts(s.peakIntensityUnit()))) {
            return false;
        }
        double[] mz = s.mz();
        double[] intensity = s.intensity();
        boolean[] matched = new boolean[mz.length];
        for (CentroidPeak p : s.peaks()) {
            int row = p.index();
            if (row < 0 || row >= mz.length || matched[row] || Double.compare(mz[row], p.mz()) != 0
                    || Double.compare(intensity[row], p.intensity()) != 0 || !sameExtraValues(s, row)) {
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

    /** {@code unit}, or MS:1000131 for null. */
    private static String orCounts(String unit) {
        return unit == null ? ArrayIndex.COUNTS_UNIT : unit;
    }

    /**
     * Whether every peak array of {@code s} has a value at {@code row} equal to that of the array with its key, which
     * {@code arrays()} has; {@code row} is a row of {@code mz()}.
     */
    private static boolean sameExtraValues(Spectrum s, int row) {
        for (Map.Entry<String, double[]> peakArray : s.peakArrays().entrySet()) {
            double[] values = peakArray.getValue();
            if (row >= values.length || Double.compare(values[row], s.arrays().get(peakArray.getKey())[row]) != 0) {
                return false;
            }
        }
        return true;
    }

    // Extra arrays (design §11.7, WR-11; spec signal-data.md:119-139). spectra_data and spectra_peaks get one column
    // for each key of the written spectra's arrays() and peakArrays(), in first-seen order, with the spec's short
    // name where it recommends one. A spectrum's row holds the value of the map parallel to what the row writes, and
    // null where the spectrum has no such array; the reader reads a null cell as NaN and drops an all-NaN array.

    /**
     * Adds to {@code keys} every key of {@code s}'s arrays, then of its peak arrays, that it does not hold yet; each
     * gets an extra column once the intensity columns are named.
     *
     * @throws MzPeakException when a key is MS:1000514 or MS:1000515: a spectrum holds those arrays in
     *                         {@code mz()} and {@code intensity()}, and a reader would take such a column for the
     *                         main axis or the intensity
     */
    private static void addExtraKeys(Spectrum s, Set<String> keys) {
        for (Map<String, double[]> arrays : List.of(s.arrays(), s.peakArrays())) {
            for (String key : arrays.keySet()) {
                if (!keys.contains(key)) {
                    requireExtraSpectrumKey("spectrum " + s.index(), key);
                    keys.add(key);
                }
            }
        }
    }

    /**
     * Throws {@link MzPeakException}, its message starting with {@code owner}, when {@code key} is MS:1000514 or
     * MS:1000515, a main array of a spectrum.
     */
    private static void requireExtraSpectrumKey(String owner, String key) {
        if (key.equals(ArrayIndex.INTENSITY_ARRAY) || key.equals(SignalEntity.SPECTRUM.defaultAxis())) {
            throw new MzPeakException(owner + ": the extra array " + key + " (" + CvTerms.ARRAY_TYPES.get(key)
                    + ") is a main array of a spectrum, which mz() and intensity() hold; it cannot be written as an"
                    + " extra column");
        }
    }

    /**
     * The extra column of {@code key}, its name made unique among {@code columns} by appending {@code _2},
     * {@code _3}, ... and added to them. Its array type is the key when the key is a CURIE, else MS:1000786; its
     * array name the {@link CvTerms#ARRAY_TYPES} name of the key, else the key; its unit
     * {@link CvTerms#DEFAULT_ARRAY_UNITS} of the key, else null.
     */
    private static ExtraColumn extraColumn(String key, Set<String> columns) {
        return new ExtraColumn(key, uniqueColumn(columnName(key), columns),
                CURIE.matcher(key).matches() ? key : NON_STANDARD_ARRAY, CvTerms.ARRAY_TYPES.getOrDefault(key, key),
                CvTerms.DEFAULT_ARRAY_UNITS.get(key));
    }

    /**
     * {@code base} made unique among {@code columns} by appending {@code _2}, {@code _3}, ..., and added to them.
     */
    private static String uniqueColumn(String base, Set<String> columns) {
        String column = base;
        for (int n = 2; !columns.add(column); n++) {
            column = base + "_" + n;
        }
        return column;
    }

    /**
     * The column name of {@code key} before it is made unique: for a {@code <key>@<field>} key its field, for any
     * other key that is not a CURIE the key; for a CURIE the short name of signal-data.md, else its
     * {@link CvTerms#ARRAY_TYPES} name without {@code array}, else the CURIE in lower case. Every character other
     * than an ASCII letter or digit becomes {@code _}. A key that leaves no name, such as an empty one, gets the name
     * of a non-standard data array, {@code non_standard_data}.
     */
    private static String columnName(String key) {
        int at = key.lastIndexOf('@');
        String name;
        if (at >= 0) {
            name = key.substring(at + 1);
        } else if (!CURIE.matcher(key).matches()) {
            name = key;
        } else if (SHORT_NAMES.containsKey(key)) {
            return SHORT_NAMES.get(key);
        } else {
            name = CvTerms.ARRAY_TYPES.getOrDefault(key, key).replaceFirst(" array$", "").toLowerCase(Locale.ROOT);
        }
        String column = name.replaceAll("[^A-Za-z0-9]", "_");
        return column.isEmpty() ? "non_standard_data" : column;
    }

    // Chromatogram columns (design §11.7, WR-11; mzML design §5.8). chromatograms_data gets one intensity column for
    // each distinct intensityUnit, null included, so that each chromatogram's unit is the unit of the column its
    // values are read from (ChromatogramDataDecoder); the reference writes has_uv's absorbance chromatogram to a
    // second intensity column the same way. Its other arrays become extra columns as those of spectra do; a row
    // holds null where its chromatogram has no such array, and in every intensity column but its unit's.

    /**
     * The intensity columns of {@code chromatograms}, which are in index order: one for each distinct
     * {@link Chromatogram#intensityUnit()}, null included, in the order their units first occur
     * ({@link #intensityColumns(Collection, Set)}).
     */
    private static List<IntensityColumn> intensityColumns(List<Chromatogram> chromatograms, Set<String> columns) {
        Set<String> units = new LinkedHashSet<>();
        for (Chromatogram c : chromatograms) {
            units.add(c.intensityUnit());
        }
        if (units.isEmpty()) {
            return List.of();
        }
        return intensityColumns(units, columns);
    }

    // Spectrum intensity columns (design D13-20, R5; spec signal-data.md l.136-176). spectra_data and spectra_peaks
    // get one intensity column for each unit, named as those of chromatograms_data, so that a spectrum reads back the
    // unit of the column its values come from (SpectrumArrayStore). A spectrum's rows hold their intensity in the
    // column of its unit and null in the others; a spectrum without a unit is written in MS:1000131, as before.
    // In the Numpress chunk layout each is the transform column <column>_numpress_slof_bytes or _pic_bytes
    // (chunked-layout.md: <array_name>_<transform_name>_bytes).

    /**
     * The intensity columns of the spectrum signal files for the distinct, non-null {@code units}, in their order
     * ({@link #intensityColumns(Collection, Set)}); the names of the Numpress transform columns of each are added to
     * {@code columns} as well.
     */
    private static List<IntensityColumn> spectrumIntensities(Collection<String> units, Set<String> columns) {
        List<IntensityColumn> out = intensityColumns(units, columns);
        for (IntensityColumn intensity : out) {
            columns.add(NumpressSignalWriter.slofColumn(intensity));
            columns.add(NumpressSignalWriter.picColumn(intensity));
        }
        return out;
    }

    /**
     * The intensity columns for the distinct {@code units}, null among them, which are not empty. The column of
     * MS:1000131, else of the first unit, is {@code intensity} and primary, and comes first; the others follow in the
     * order of {@code units}. Each of them is named {@code intensity_} and its unit with every character other than an
     * ASCII letter or digit as {@code _} (of a CURIE, the colon), or {@code intensity_unitless} for null, made unique
     * among {@code columns}. Every name is added to {@code columns}.
     */
    private static List<IntensityColumn> intensityColumns(Collection<String> units, Set<String> columns) {
        String primary = units.contains(ArrayIndex.COUNTS_UNIT) ? ArrayIndex.COUNTS_UNIT : units.iterator().next();
        List<IntensityColumn> out = new ArrayList<>(units.size());
        columns.add(INTENSITY_COLUMN);
        out.add(new IntensityColumn(primary, INTENSITY_COLUMN));
        for (String unit : units) {
            if (!Objects.equals(unit, primary)) {
                String name = unit == null ? "unitless" : unit.replaceAll("[^A-Za-z0-9]", "_");
                out.add(new IntensityColumn(unit, uniqueColumn(INTENSITY_COLUMN + "_" + name, columns)));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Adds to {@code extras} a column for every key of {@code c}'s arrays that it does not hold yet, named so that it
     * takes none of {@code columns}, to which the name is added.
     *
     * @throws MzPeakException when a key is MS:1000595 or MS:1000515: a chromatogram holds those arrays in
     *                         {@code time()} and {@code intensity()}, and a reader would take such a column for the
     *                         main axis or an intensity
     */
    private static void addExtraColumns(Chromatogram c, Map<String, ExtraColumn> extras, Set<String> columns) {
        for (String key : c.arrays().keySet()) {
            if (!extras.containsKey(key)) {
                if (key.equals(ArrayIndex.INTENSITY_ARRAY) || key.equals(SignalEntity.CHROMATOGRAM.defaultAxis())) {
                    throw new MzPeakException("chromatogram " + c.index() + ": the extra array " + key + " ("
                            + CvTerms.ARRAY_TYPES.get(key) + ") is a main array of a chromatogram, which time() and"
                            + " intensity() hold; it cannot be written as an extra column");
                }
                extras.put(key, extraColumn(key, columns));
            }
        }
    }

    /**
     * Throws {@link MzPeakException} when a peak of {@code s}, whose {@code peaks()} are written with its non-empty
     * {@code peakArrays()}, has an {@link CentroidPeak#index()} that is not a position of those arrays.
     */
    private static void requirePeakPositions(Spectrum s) {
        int positions = s.peakArrays().values().iterator().next().length;
        for (CentroidPeak p : s.peaks()) {
            if (p.index() < 0 || p.index() >= positions) {
                throw new MzPeakException("spectrum " + s.index() + ": the peak at m/z " + p.mz() + " has index "
                        + p.index() + ", which is not a position of its peak arrays (" + positions + " values); a"
                        + " peak's index() is its position in the list the spectrum was built from");
            }
        }
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

    // Numpress values (design §5.7, MZ-23). The Numpress chunk layout holds one intensity transform column and no
    // plain list, so a chunk cannot fall back to unencoded values, and the codecs write what they cannot encode as
    // other values: Numpress linear stores m/z as fixed-point integers (NaN as 0, a first m/z of -0.038 reads back as
    // 42949.63), SLOF the logarithm of intensity + 1 (-0.5 reads back as 1.45e28, NaN as 0) and PIC the low 32 bits of
    // each rounded count. Numpress linear also stores the first two m/z of a chunk as unsigned 32-bit integers and
    // every later one as the signed 32-bit difference from its extrapolation 2 * previous - the one before, so at the
    // writer's fixed point 1e5 a first m/z of 50000 reads back as 7050.33 and [100, 200, 25000] as
    // [100, 200, -17949.67]. The m/z are checked in the chunks NumpressSignalWriter.chunkEnd splits them into; the m/z
    // of a sorted array that round to less than 2^31 always pass the difference check. ChunkCodecs reads PIC counts
    // unsigned, as the C++ reference does, but readers built on the reference Java decoder read a count from 2^31 on
    // as negative, so PIC is written only up to 2147483647 (design Q26). Such values are refused before anything is
    // written; the point layout writes them.

    /**
     * Throws {@link MzPeakException} when {@code options} write Numpress chunks and a value that {@code signal} writes
     * to one cannot be encoded: an m/z or intensity that is not finite or is below 0, an m/z whose fixed-point value
     * {@code (long) (mz * 1e5 + 0.5)} exceeds 4294967295, an m/z after the first two of a chunk whose fixed-point
     * difference from its extrapolation (2 * previous - the one before) is outside the signed 32-bit range, or with
     * {@link NumpressIntensityEncoding#PIC} an intensity whose rounded count {@code (long) (v + 0.5)} exceeds
     * 2147483647. The values checked are those the spectrum writes: its arrays, to spectra_data or as a peak copy to
     * spectra_peaks, and its peak list to spectra_peaks, in the chunks {@link NumpressSignalWriter#chunkEnd} splits
     * them into. The message names the spectrum's index, the array, the value, the member and the alternative.
     */
    static void requireNumpressEncodable(SpectrumSignal signal, Options options) {
        if (options.signalEncoding() != SignalEncoding.NUMPRESS) {
            return;
        }
        Spectrum s = signal.source();
        SpectrumEntry entry = signal.entry();
        boolean pic = options.numpressIntensity() == NumpressIntensityEncoding.PIC;
        if (entry.data() || entry.peaksFromArrays()) {
            NumpressRange.check(s.index(), signal.arrays().x(), signal.arrays().intensity(),
                    entry.data() ? "spectra_data" : "spectra_peaks", pic);
        }
        if (entry.peaks() && !entry.peaksFromArrays()) {
            List<CentroidPeak> peaks = s.peaks();
            double[] mz = new double[peaks.size()];
            double[] intensity = new double[mz.length];
            for (int i = 0; i < mz.length; i++) {
                mz[i] = peaks.get(i).mz();
                intensity[i] = peaks.get(i).intensity();
            }
            NumpressRange.check(s.index(), mz, intensity, "spectra_peaks", pic);
        }
    }

    // No NaN in a signal column (D13-14, W10). Spec conformance.md l.36: a conformant writer writes a page index for
    // every column, and parquet-java 1.18.2 writes no column index for a page that holds a NaN. A NaN extra cell is
    // written as null, which the reader reads as NaN again; an axis or intensity value has no such form (a null m/z
    // is a null-marked point, a null intensity reads as 0), so a NaN there is refused before anything is written,
    // in every encoding.

    /**
     * Throws {@link MzPeakException} naming {@code owner}, the {@code array} and the position when a value of
     * {@code values} is NaN.
     */
    private static void requireNoNaN(String owner, String array, double[] values) {
        for (int i = 0; i < values.length; i++) {
            if (Double.isNaN(values[i])) {
                throw nanValue(owner, "the " + array + " at position " + i);
            }
        }
    }

    private static MzPeakException nanValue(String owner, String what) {
        return new MzPeakException(owner + ": " + what + " is NaN; mzPeak writes no NaN m/z, time, wavelength or"
                + " intensity, since a Parquet page holding a NaN gets no column index (conformance.md requires a"
                + " page index for every column)");
    }

    // Renumbering (design §11.5, WR-8). Spec spectra.md:91-93 and chromatograms.md:40: the indices of each entity
    // run 0..n-1, and a spectrum's precursor_index names a spectrum of the file. The Rust reader indexes its count
    // vectors by spectrum index, and a filtered subset (ExtractSpectra) is sparse, so every entity is written as
    // 0..n-1 in the order of the caller's indices, and each precursor_index as the new index of its parent, null when
    // the parent is not written. Input numbered 0..n-1 in index order is written as given. A chromatogram's
    // precursor_index names a chromatogram (spec HEAD chromatograms.md:75-80; the Rust reader resolves a chromatogram
    // precursor against the chromatograms, visitor.rs:3108-3122), so it goes through the chromatogram numbering.
    // Design D13-10 keeps this renumbering and the null precursor_index, instead of rejecting gaps and references to
    // spectra not written, so that a subset of what the reader returns can be written (WriterIndexDeviationTest).

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
     * changes. The precursor_id is kept, and a precursor_index naming no written spectrum becomes null, as
     * spectra.md §Spectrum precursor metadata requires, rather than being rejected (design D13-10).
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
                        p.activation(), p.msLevel()));
            }
        }
        return out == null ? precursors : Collections.unmodifiableList(out);
    }

    /**
     * {@code c}, written with {@code index}, with its precursors as written: each precursor_index replaced by the
     * index {@code writtenIndex}, the chromatogram numbering, gives the chromatogram it names, or by null when that
     * chromatogram is not written ({@link #writtenPrecursors}). In the {@link MetadataLayout#SPLIT} layout they are
     * checked by {@link #requireOwnKeys}. A copy of {@code c} with those precursors is returned when one changes,
     * else {@code c} itself; the caller's chromatogram is never modified.
     */
    static Chromatogram withWrittenPrecursors(Chromatogram c, long index, Map<Long, Long> writtenIndex,
                                              MetadataLayout layout) {
        List<Precursor> precursors = writtenPrecursors(c.precursors(), writtenIndex);
        if (layout == MetadataLayout.SPLIT) {
            requireOwnKeys(Owner.CHROMATOGRAM, c.index(), index, c.precursors(), precursors);
        }
        if (precursors == c.precursors()) {
            return c;
        }
        return new Chromatogram(c.index(), c.id(), c.typeCurie(), c.time(), c.intensity(), c.intensityUnit(),
                c.arrays(), c.polarity(), c.dataProcessingRef(), c.parameters(), precursors, c.timeUnit(),
                c.products(), c.auxiliaryArrays());
    }

    // Attribution check (design §11.5, WR-8). The mzPeakJ reader attaches a selected ion to the first precursor of its
    // spectrum whose key (source_index, precursor_index) equals the ion's, null equal to null, whatever the row order
    // (PrecursorJoin). The Rust reference (metadata.rs:1156-1219) also needs a spectrum's selected ions sorted by that
    // key, which SplitMetadataWriter writes them in. When precursors of one spectrum share their written key, the
    // selected ions of every one after the first would silently move to the first. The split layout writes every
    // precursor and refuses exactly that input; a shared key whose later precursors carry no selected ion loses
    // nothing and is written. The typical cause is a multi-precursor spectrum written without its parents: every
    // precursor_index is null. The packed layout writes the primary precursor only and is not checked. The precursors
    // of a chromatogram are written and read the same way, keyed by the chromatogram's index, and are checked the same
    // way in the split layout; the packed layout writes none.

    /** The entity whose precursors {@link #requireOwnKeys} checks, as its messages name it. */
    private enum Owner {
        SPECTRUM("spectrum", "spectra"),
        CHROMATOGRAM("chromatogram", "chromatograms");

        private final String singular;
        private final String plural;

        Owner(String singular, String plural) {
            this.singular = singular;
            this.plural = plural;
        }
    }

    /**
     * Throws {@link MzPeakException} when a precursor of {@code precursors}, those {@code given} of the {@code owner}
     * with the caller's index {@code callerIndex} as written with {@code index}, carries selected ions and an earlier
     * one has its precursor_index (null equal to null).
     */
    private static void requireOwnKeys(Owner owner, long callerIndex, long index, List<Precursor> given,
                                       List<Precursor> precursors) {
        for (int i = 1; i < precursors.size(); i++) {
            Long key = precursors.get(i).precursorIndex();
            if (!precursors.get(i).selectedIons().isEmpty() && firstWithKey(precursors, key) < i) {
                throw new MzPeakException(sharedKeyMessage(owner, callerIndex, index, given, precursors, key));
            }
        }
    }

    /**
     * The position of the first of {@code precursors} whose precursor_index equals {@code key}, the precursor_index of
     * one of them.
     */
    private static int firstWithKey(List<Precursor> precursors, Long key) {
        int i = 0;
        while (!Objects.equals(precursors.get(i).precursorIndex(), key)) {
            i++;
        }
        return i;
    }

    /**
     * The message for the precursors of the {@code owner} with the caller's index {@code callerIndex} that share the
     * written precursor_index {@code key}: the owner's index, the positions and precursor ids of those precursors,
     * the precursors whose selected ions would move to the first of them, and a remedy. When each of them names a
     * different parent that is not written ({@code given} holds the caller's precursors), the remedy is to write the
     * parent spectra or chromatograms as well; otherwise it is to give each precursor a precursor_index of its own.
     */
    private static String sharedKeyMessage(Owner owner, long callerIndex, long index, List<Precursor> given,
                                           List<Precursor> precursors, Long key) {
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
                ? "write their parent " + owner.plural + " (index "
                + joined(parents.stream().map(String::valueOf).toList())
                + ") as well, so that each precursor keeps a precursor_index of its own"
                : "give each precursor of the " + owner.singular + " a precursor_index of its own";
        return owner.singular + " " + callerIndex + ": precursors " + joined(sharing) + " share the written key"
                + " (source_index " + index + ", precursor_index " + key + "), so readers would attach the selected"
                + " ions of precursor " + joined(moved) + " to precursor " + first + "; " + remedy;
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
     * {@code layout} writes: a spectrum's primary precursor's for {@link MetadataLayout#PACKED}, every precursor's of
     * a spectrum or chromatogram for {@link MetadataLayout#SPLIT}. The reader finds a spectrum's data rows through
     * the index range of each row group, so spectra are written in index order: this returns the caller's list when
     * it is already in index order, and otherwise a stable-sorted copy. The caller's list is never modified. Gaps
     * between the indices are not rejected but renumbered by {@link #plan} (design D13-10); duplicates are rejected.
     */
    private static List<Spectrum> validateAndOrder(List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                                   List<WavelengthSpectrum> wavelengthSpectra,
                                                   MetadataLayout layout) {
        boolean inIndexOrder = true;
        long previous = -1;
        for (Spectrum s : spectra) {
            requireNonNegative(s.index(), "spectrum index");
            requireWritableParams(s);
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
        validateOthers(chromatograms, wavelengthSpectra, layout);
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

    /**
     * The part of {@link #validateAndOrder} for chromatograms and wavelength spectra: their indices non-negative and
     * unique within each kind, no NaN time, wavelength or intensity ({@link #requireNoNaN}), and in the
     * {@link MetadataLayout#SPLIT} layout every chromatogram precursor_index non-negative.
     */
    private static void validateOthers(List<Chromatogram> chromatograms, List<WavelengthSpectrum> wavelengthSpectra,
                                       MetadataLayout layout) {
        Set<Long> seen = new HashSet<>();
        for (Chromatogram c : chromatograms) {
            requireNonNegative(c.index(), "chromatogram index");
            requireUnique(seen, c.index(), "chromatogram index");
            List<List<Param>> lists = new ArrayList<>(List.of(c.parameters()));
            addPrecursorParams(c.precursors(), lists);
            requireWritableParams(owner("chromatogram", c.index(), c.id()), lists);
            requireNoNaN("chromatogram " + c.index(), "time", c.time());
            requireNoNaN("chromatogram " + c.index(), "intensity", c.intensity());
            if (layout == MetadataLayout.SPLIT) {
                for (Precursor p : c.precursors()) {
                    requireNonNegativePrecursorIndex(p);
                }
            }
        }
        seen.clear();
        for (WavelengthSpectrum w : wavelengthSpectra) {
            requireNonNegative(w.index(), "wavelength spectrum index");
            requireUnique(seen, w.index(), "wavelength spectrum index");
            requireWritableParams(owner("wavelength spectrum", w.index(), w.id()), List.of(w.parameters()));
            requireNoNaN("wavelength spectrum " + w.index(), "wavelength", w.wavelength());
            requireNoNaN("wavelength spectrum " + w.index(), "intensity", w.intensity());
        }
    }

    /**
     * Throws {@link MzPeakException} when a Param of {@code s}, its own, its scans', its precursors' (isolation window,
     * activation) or its selected ions', has neither a name nor an accession, or a value of a type the writer cannot
     * write ({@link ParamValues#require}). A Param without name and accession names nothing: mzML requires a
     * userParam's name, and the Rust reference reader requires every Param of a {@code parameters} list to have a
     * name, so a spectrum with one would be unreadable there. Checked in both metadata layouts, before anything is
     * written.
     */
    private static void requireWritableParams(Spectrum s) {
        List<List<Param>> lists = new ArrayList<>(List.of(s.description().parameters()));
        for (ScanEvent scan : s.description().scans()) {
            lists.add(scan.parameters());
        }
        addPrecursorParams(s.description().precursors(), lists);
        requireWritableParams(owner("spectrum", s.index(), s.description().id()), lists);
    }

    /** Adds to {@code lists} the Params of each of {@code precursors}: isolation window, activation, ions. */
    private static void addPrecursorParams(List<Precursor> precursors, List<List<Param>> lists) {
        for (Precursor p : precursors) {
            if (p.isolationWindow() != null) {
                lists.add(p.isolationWindow().parameters());
            }
            lists.add(p.activation().parameters());
            for (SelectedIon ion : p.selectedIons()) {
                lists.add(ion.parameters());
            }
        }
    }

    /** See {@link #requireWritableParams(Spectrum)}; {@code owner} names the entity in the message. */
    private static void requireWritableParams(String owner, List<List<Param>> lists) {
        for (List<Param> params : lists) {
            for (Param p : params) {
                if (p.name() == null && p.accession() == null) {
                    throw new MzPeakException(owner + ": a parameter needs a name or an accession: " + p);
                }
            }
            ParamValues.require(owner, params);
        }
    }

    /**
     * Throws {@link MzPeakException} when a Param of {@code scanSettings}, which may be {@code null}, its own or its
     * targets', has a value of a type the writer cannot write ({@link ParamValues#require}), naming the scan settings
     * by position and id; checked by {@link #plan} and when a session opens, before anything is written.
     */
    static void requireWritableScanSettings(List<ScanSettings> scanSettings) {
        if (scanSettings == null) {
            return;
        }
        for (int i = 0; i < scanSettings.size(); i++) {
            ScanSettings s = scanSettings.get(i);
            String owner = owner("scan settings", i, s.id());
            ParamValues.require(owner, s.parameters());
            for (List<Param> target : s.targets()) {
                ParamValues.require(owner, target);
            }
        }
    }

    /** {@code kind index}, followed by {@code (id)} when the entity has an id. */
    private static String owner(String kind, long index, String id) {
        return kind + " " + index + (id == null ? "" : " (" + id + ")");
    }

    private static void requireNonNegative(long value, String what) {
        if (value < 0) {
            throw new MzPeakException(what + " must be non-negative (mzPeak indices are uint64): " + value);
        }
    }

    /** {@code chromatograms}, each {@link #inMinutes(Chromatogram) in minutes}, in the same order. */
    private static List<Chromatogram> inMinutes(List<Chromatogram> chromatograms) {
        List<Chromatogram> out = new ArrayList<>(chromatograms.size());
        for (Chromatogram c : chromatograms) {
            out.add(inMinutes(c));
        }
        return out;
    }

    /**
     * {@code c} with its times in minutes (UO:0000031), the unit chromatograms_data stores them in (W3, D13-1;
     * chromatograms.md: "the units MUST be minutes"): {@code c} itself when they are, else a copy converted once by
     * {@link Chromatogram#withTimeUnit} from second, millisecond or hour. Called by {@link #plan} and by a session as
     * each chromatogram is added, before anything of it is written; the caller's chromatogram is never modified.
     *
     * @throws MzPeakException when its {@link Chromatogram#timeUnit()} is another unit
     */
    static Chromatogram inMinutes(Chromatogram c) {
        try {
            return c.withTimeUnit(ArrayIndex.MINUTE_UNIT);
        } catch (IllegalArgumentException e) {
            throw new MzPeakException("chromatogram " + c.index() + ": time unit " + c.timeUnit()
                    + " cannot be converted to minutes", e);
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

    // Declared CV prefixes and references (design W12, D13-6, D13-9, D13-26). conformance.md requires a writer to
    // declare every CV prefix it uses in metadata.cv_list (l.24-25), and every non-null foreign key to resolve
    // (§Validation). A write is refused before anything is written when a CURIE (a value with text before a colon, as
    // KnownCvs.prefix takes it, the values ManifestWriter declares the CVs of) has a prefix metadata.cv_list would not
    // declare, or when an explicit reference names nothing the file metadata declares. A unit is a CURIE only when it
    // has the CURIE shape PREFIX:local (CURIE); any other param unit, such as "seconds" (C6) or "counts: per second", is
    // written as given (D13-26), but the intensity unit of a spectrum, its peaks or a chromatogram, which becomes the
    // unit of an array of the array index, must be a CURIE (array_index.json: unit pattern \S+:\S+); an extra-array
    // key that is not a CURIE is written as MS:1000786. A null run default is filled by FooterMetadataWriter, with a
    // stub and a WARNING when the file metadata has nothing to name. Without file metadata (null or FileMetadata.EMPTY) nothing is declared, so
    // every non-null reference is refused.

    /**
     * What the entities of a write may name (W12): the CV prefixes {@code metadata.cv_list} declares with the cv list
     * of the write's options ({@link KnownCvs#undeclared}), and the instrument configurations, data processings and
     * source files of its file metadata. Built by {@link WritePlanner#declarations}, which checks the file metadata
     * and the scan settings; {@link #require(Spectrum)}, {@link #require(Chromatogram)} and
     * {@link #require(WavelengthSpectrum)} check an entity. Each check throws {@link MzPeakException} naming the
     * entity, before anything of it is written.
     */
    static final class Declarations {

        private final List<CvEntry> cvList;
        /** The prefixes found declared so far. */
        private final Set<String> declaredPrefixes = new HashSet<>();
        private final List<Integer> configurations = new ArrayList<>();
        private final List<String> processing = new ArrayList<>();
        private final List<String> sourceFiles = new ArrayList<>();

        private Declarations(FileMetadata meta, Options options) {
            this.cvList = options.cvList();
            if (meta != null) {
                meta.instrumentConfigurations().forEach(ic -> configurations.add(ic.id()));
                meta.dataProcessingMethods().forEach(dp -> processing.add(dp.id()));
                if (meta.fileDescription() != null) {
                    meta.fileDescription().sourceFiles().forEach(sf -> sourceFiles.add(sf.id()));
                }
            }
        }

        /**
         * Throws {@link MzPeakException} when a CURIE of {@code s} has an undeclared prefix: the accession or unit of
         * a param of it, of its scans, or of its precursors' isolation windows, activations and selected ions, or a
         * key of its {@link Spectrum#arrays()} or {@link Spectrum#peakArrays()} that is a CURIE, or its intensity or
         * peak intensity unit; when its intensity or peak intensity unit is not a CURIE ({@link #requireArrayUnit});
         * or when its data processing or the instrument configuration of a scan names nothing the file metadata
         * declares.
         */
        void require(Spectrum s) {
            String owner = "spectrum " + s.index();
            SpectrumDescription d = s.description();
            requireArrayUnit(owner, "intensity unit", s.intensityUnit());
            requireArrayUnit(owner, "peak intensity unit", s.peakIntensityUnit());
            requireParams(owner, d.parameters());
            requireNamed(owner, "data_processing_id", d.dataProcessingRef(), processing, "data processing");
            for (int i = 0; i < d.scans().size(); i++) {
                ScanEvent scan = d.scans().get(i);
                requireParams(owner + ": scan " + i, scan.parameters());
                requireNamed(owner, "scan " + i + " instrument_configuration_id",
                        scan.instrumentConfigurationRef(), configurations, "instrument configuration");
            }
            requirePrecursors(owner, d.precursors());
            for (Map<String, double[]> arrays : List.of(s.arrays(), s.peakArrays())) {
                requireArrayKeys(owner, arrays.keySet());
            }
        }

        /**
         * Throws {@link MzPeakException} when a CURIE of {@code c} has an undeclared prefix: its type, its intensity
         * unit, the accession or unit of a param of it or of its precursors, or a key of its arrays that is a CURIE;
         * when its intensity unit is not a CURIE ({@link #requireArrayUnit}); or when its data processing names
         * nothing the file metadata declares.
         */
        void require(Chromatogram c) {
            String owner = "chromatogram " + c.index();
            requirePrefix(owner, "type", c.typeCurie());
            requireArrayUnit(owner, "intensity unit", c.intensityUnit());
            requireParams(owner, c.parameters());
            requireNamed(owner, "data_processing_id", c.dataProcessingRef(), processing, "data processing");
            requirePrecursors(owner, c.precursors());
            requireArrayKeys(owner, c.arrays().keySet());
        }

        /** Throws {@link MzPeakException} when the accession or unit of a param of {@code w} has an undeclared prefix. */
        void require(WavelengthSpectrum w) {
            requireParams("wavelength spectrum " + w.index(), w.parameters());
        }

        /**
         * Throws {@link MzPeakException} when a key of {@code keys}, the array keys of {@code owner}, is a CURIE whose
         * prefix is not declared.
         */
        void requireArrayKeys(String owner, Collection<String> keys) {
            for (String key : keys) {
                if (CURIE.matcher(key).matches()) {
                    requirePrefix(owner, "array", key);
                }
            }
        }

        /**
         * Throws {@link MzPeakException} when a unit of {@code units}, the spectrum intensity units of {@code owner},
         * is not a CURIE or has a prefix that is not declared ({@link #requireArrayUnit}).
         */
        void requireIntensityUnits(String owner, Collection<String> units) {
            for (String unit : units) {
                requireArrayUnit(owner, "spectrum intensity unit", unit);
            }
        }

        /** The checks of the params of each of {@code precursors} of {@code owner}. */
        private void requirePrecursors(String owner, List<Precursor> precursors) {
            for (int i = 0; i < precursors.size(); i++) {
                Precursor p = precursors.get(i);
                String precursor = owner + ": precursor " + i;
                if (p.isolationWindow() != null) {
                    requireParams(precursor + " isolation window", p.isolationWindow().parameters());
                }
                requireParams(precursor + " activation", p.activation().parameters());
                for (int j = 0; j < p.selectedIons().size(); j++) {
                    requireParams(precursor + " selected ion " + j, p.selectedIons().get(j).parameters());
                }
            }
        }

        /** The checks of the accession and unit of each of {@code params} of {@code owner}; {@code null} has none. */
        private void requireParams(String owner, List<Param> params) {
            for (Param p : params == null ? List.<Param>of() : params) {
                requirePrefix(owner, p, p.accession());
                requireUnit(owner, p, p.unit());
            }
        }

        /**
         * The check of {@code unit}, the unit of {@code what} of {@code owner}, when it has the CURIE shape
         * {@code PREFIX:local}; any other unit, {@code null} included, is written as given.
         */
        private void requireUnit(String owner, Object what, String unit) {
            if (unit != null && CURIE.matcher(unit).matches()) {
                requirePrefix(owner, what, unit);
            }
        }

        /**
         * The check of {@code unit}, the {@code what} of {@code owner}, which becomes the unit of an array of the array
         * index: {@code null}, or a CURIE ({@code PREFIX:local}, as array_index.json requires of the unit of an array)
         * whose prefix is declared. Unlike a param unit (D13-26), a unit that is not a CURIE, such as
         * {@code percent}, is refused.
         */
        private void requireArrayUnit(String owner, String what, String unit) {
            if (unit != null && !CURIE.matcher(unit).matches()) {
                throw new MzPeakException(owner + ": " + what + " " + unit + " is not a CURIE (PREFIX:local), which"
                        + " array_index.json requires of the unit of an array; give a unit term such as MS:1000131"
                        + " (number of detector counts) or MS:1000132 (percent of base peak), or null");
            }
            requireUnit(owner, what, unit);
        }

        /**
         * Throws {@link MzPeakException} naming {@code owner} and {@code what} (a param, or what of the owner
         * {@code curie} is) when {@code curie} has a prefix ({@link KnownCvs#prefix}) that {@code metadata.cv_list}
         * would not declare.
         */
        private void requirePrefix(String owner, Object what, String curie) {
            String prefix = KnownCvs.prefix(curie);
            if (prefix == null || declaredPrefixes.contains(prefix)) {
                return;
            }
            String why = KnownCvs.undeclared(prefix, cvList);
            if (why != null) {
                throw new MzPeakException(owner + ": " + what + " uses " + curie + ", whose CV prefix " + prefix
                        + " metadata.cv_list would not declare: " + why + "; conformance.md requires every CV prefix"
                        + " a file uses to be declared, so give its CV with id, full name, URI and version to"
                        + " MzPeakWriter.Options.withCvList");
            }
            declaredPrefixes.add(prefix);
        }

        /**
         * Throws {@link MzPeakException} when {@code reference}, the {@code what} of {@code owner}, is not
         * {@code null} and none of {@code declared}, the {@code kind}s the file metadata declares (none without file
         * metadata).
         */
        private <T> void requireNamed(String owner, String what, T reference, List<T> declared, String kind) {
            if (reference != null && !declared.contains(reference)) {
                throw new MzPeakException(owner + ": " + what + " " + reference + " names no " + kind
                        + " (declared: " + (declared.isEmpty() ? "none" : joinedIds(declared)) + ")");
            }
        }
    }

    /**
     * The {@link Declarations} of a write of {@code fileMetadata}, which may be {@code null}, and {@code scanSettings},
     * which may be {@code null}, with {@code options}, after checking them: the accession and unit of every param of
     * the file metadata and of the scan settings (their own and their targets') have a declared prefix; the software
     * reference of every instrument configuration and processing method, the run's default instrument configuration,
     * data processing and source file, and every source file reference of the scan settings name, when not
     * {@code null}, an entry the file metadata declares. Called by {@link #plan} and when a session opens.
     *
     * @throws MzPeakException naming the place, when a check fails
     */
    static Declarations declarations(FileMetadata fileMetadata, List<ScanSettings> scanSettings, Options options) {
        Declarations declared = new Declarations(fileMetadata, options);
        if (fileMetadata != null) {
            requireDeclared(fileMetadata, declared);
        }
        if (scanSettings != null) {
            for (int i = 0; i < scanSettings.size(); i++) {
                ScanSettings s = scanSettings.get(i);
                String owner = owner("scan settings", i, s.id());
                declared.requireParams(owner, s.parameters());
                for (int j = 0; j < s.targets().size(); j++) {
                    declared.requireParams(owner + ": target " + j, s.targets().get(j));
                }
                for (String ref : s.sourceFileRefs()) {
                    declared.requireNamed(owner, "source file reference", ref, declared.sourceFiles, "source file");
                }
            }
        }
        return declared;
    }

    /** The checks of {@link #declarations} of {@code meta}, a file metadata that is not {@code null}. */
    private static void requireDeclared(FileMetadata meta, Declarations declared) {
        List<String> software = new ArrayList<>();
        meta.software().forEach(s -> software.add(s.id()));
        String owner = "file metadata";
        FileDescription description = meta.fileDescription();
        if (description != null) {
            declared.requireParams(owner + ": file description", description.contents());
            for (SourceFile sf : description.sourceFiles()) {
                declared.requireParams(owner + ": source file " + sf.id(), sf.parameters());
            }
            for (int i = 0; i < description.contacts().size(); i++) {
                declared.requireParams(owner + ": contact " + i, description.contacts().get(i).parameters());
            }
        }
        for (InstrumentConfiguration ic : meta.instrumentConfigurations()) {
            String configuration = owner + ": instrument configuration " + ic.id();
            declared.requireParams(configuration, ic.parameters());
            for (Component c : ic.components()) {
                declared.requireParams(configuration + " component " + c.order(), c.parameters());
            }
            declared.requireNamed(configuration, "software_reference", ic.softwareReference(), software, "software");
        }
        for (Software s : meta.software()) {
            declared.requireParams(owner + ": software " + s.id(), s.parameters());
        }
        for (DataProcessing dp : meta.dataProcessingMethods()) {
            for (ProcessingMethod m : dp.methods()) {
                String method = owner + ": data processing " + dp.id() + " method " + m.order();
                declared.requireParams(method, m.parameters());
                declared.requireNamed(method, "software_reference", m.softwareReference(), software, "software");
            }
        }
        for (Sample s : meta.samples()) {
            declared.requireParams(owner + ": sample " + s.id(), s.parameters());
        }
        MsRun run = meta.run();
        if (run != null) {
            declared.requireParams(owner + ": run", run.parameters());
            declared.requireNamed(owner + ": run", "default_instrument_configuration_id", run.defaultInstrumentId(),
                    declared.configurations, "instrument configuration");
            declared.requireNamed(owner + ": run", "default_data_processing_id", run.defaultDataProcessingId(),
                    declared.processing, "data processing");
            declared.requireNamed(owner + ": run", "default_source_file_id", run.defaultSourceFileId(),
                    declared.sourceFiles, "source file");
        }
    }

    /** {@code ids} as {@code a, b, c}. */
    private static String joinedIds(List<?> ids) {
        List<String> parts = new ArrayList<>(ids.size());
        ids.forEach(id -> parts.add(String.valueOf(id)));
        return String.join(", ", parts);
    }
}
