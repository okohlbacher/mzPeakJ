package org.mzpeak.mzml;

import org.mzpeak.io.MzPeakException;
import org.mzpeak.io.MzPeakManifest.CvEntry;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.io.MzPeakWriter;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.DataProcessing;
import org.mzpeak.model.meta.FileMetadata.FileDescription;
import org.mzpeak.model.meta.FileMetadata.MsRun;
import org.mzpeak.model.meta.FileMetadata.SourceFile;
import org.mzpeak.model.meta.ScanSettings;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Converts mzML to mzPeak and mzPeak to mzML (mzML design 3, 8.1), each in one pass over its input.
 *
 * <p>{@link #toMzPeak} reads the mzML through {@link MzMLReader}: its header and survey first, so that what the model
 * cannot carry is rejected before the target is created, then every spectrum, wavelength spectrum and chromatogram
 * into a {@link MzPeakWriter.Session} opened with the header's file metadata, scan settings and cv list and the
 * survey's extra array keys. Before a spectrum is added, the selected ions of each of its precursors that shares its
 * precursor index (its parent) with an earlier precursor of the spectrum are moved to the first of them, which keeps
 * its own first; every isolation window and activation stays (design 5.6, B-19). It counts what the mzPeak read
 * changes (design 6.1): extra arrays of spectra and chromatograms without a value other than NaN, empty ones included,
 * which it drops (B-26); spectra with a NaN m/z, which it interpolates (B-34); non-finite values of file-level params,
 * which come back as the Strings {@code Infinity}, {@code -Infinity} or {@code NaN} (B-35); and chromatograms without
 * points, whose intensity unit it drops (B-36). A failure closes the session, which removes the target and the temp
 * files it created.
 *
 * <p>{@link #toMzML} refuses an existing target before it opens the input (Q66), then reads the mzPeak through
 * {@link MzPeakReader} with its default options (profile points reconstructed, both signal members) and writes it
 * through {@link MzMLWriter}. It removes what the mzPeak writer adds where the run names no source file or data
 * processing and the file has none (design 5.10): a source file {@code mzpeakj_source} named {@code unknown} at
 * {@code file:///unknown} without params, and a data processing {@code mzpeakj_export} without methods, and the
 * run's default that names a removed one. The writer's cv list is the dataset's, followed by an entry for each CV
 * prefix that the params of the spectra (their scans, precursors, selected ions and activations included) and of the
 * wavelength spectra use, as accession or unit, and the dataset's list lacks: that of {@link CvEntry#known()} in its
 * order, then the prefix alone, which the writer completes (design 5.11).
 *
 * <p>Each {@link Result} carries the warnings of its direction: for {@code toMzPeak} the reader's limit and loss lines,
 * then the conversion's own; for {@code toMzML} the writer's.
 */
public final class MzMLConversion {

    // The losses of design 6.1 toMzPeak counts itself, one line each after the reader's.
    static final String B_19 = "B-19 selected ions moved to the first precursor of their spectrum with the same parent";
    static final String B_26 = "B-26 extra arrays without a value other than NaN, which the mzPeak read drops";
    static final String B_34 = "B-34 spectra with a NaN m/z, which the mzPeak read interpolates";
    static final String B_35 = "B-35 non-finite file-level param values, which the mzPeak read gives as the Strings"
            + " Infinity, -Infinity or NaN";
    static final String B_36 = "B-36 chromatograms without points, whose intensity unit the mzPeak read drops";

    /** The source file the mzPeak writer adds when the run and the file description name none (design 5.10). */
    private static final SourceFile SOURCE_STUB = new SourceFile("mzpeakj_source", "unknown", "file:///unknown",
            List.of());
    /** The data processing the mzPeak writer adds when the run names none and the file has none (design 5.10). */
    private static final DataProcessing PROCESSING_STUB = new DataProcessing("mzpeakj_export", List.of());

    private MzMLConversion() {
    }

    /**
     * What a conversion wrote, and what it lost.
     *
     * @param spectra           the number of MS spectra written
     * @param wavelengthSpectra the number of wavelength spectra written
     * @param chromatograms     the number of chromatograms written
     * @param warnings          the limit lines, then one line per kind of loss with its count (design 6, 10);
     *                          unmodifiable
     */
    public record Result(long spectra, long wavelengthSpectra, long chromatograms, List<String> warnings) {

        /** @throws NullPointerException if {@code warnings} is or holds null */
        public Result {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * Converts the mzML file {@code mzml} into the mzPeak dataset {@code target}: an unpacked directory if
     * {@code directory}, else a ZIP archive, written with {@code options} and the mzML's cv list.
     *
     * @throws MzPeakException      if the mzML cannot be read or holds what the model cannot carry (as
     *                              {@link MzMLReader} states), before the target is created; if {@code target} exists
     *                              (for an unpacked directory: and is not an empty directory); or if a spectrum or the
     *                              dataset cannot be written, after which nothing the conversion wrote remains
     * @throws NullPointerException if an argument is null
     */
    public static Result toMzPeak(Path mzml, Path target, boolean directory, MzPeakWriter.Options options) {
        Objects.requireNonNull(mzml, "mzml");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        MzMLReader reader = new MzMLReader(mzml);
        MzMLReader.Header header = reader.header();
        MzMLReader.Survey survey = reader.survey();
        Losses losses = new Losses();
        losses.note(B_35, nonFinite(header.fileMetadata(), header.scanSettings()));
        MzPeakWriter.Options withCvList = options.withCvList(header.cvList());
        Adding adding;
        try (MzPeakWriter.Session session = directory
                ? MzPeakWriter.openDirectory(target, header.fileMetadata(), header.scanSettings(),
                survey.spectrumArrays(), withCvList)
                : MzPeakWriter.openArchive(target, header.fileMetadata(), header.scanSettings(),
                survey.spectrumArrays(), withCvList)) {
            adding = new Adding(session, losses);
            reader.read(adding);
            session.finish();
        }
        List<String> warnings = new ArrayList<>(reader.warnings());
        warnings.addAll(losses.lines());
        return new Result(adding.spectra, adding.wavelengthSpectra, adding.chromatograms, warnings);
    }

    /**
     * Converts the mzPeak dataset {@code mzpeak} (a directory or a ZIP archive) into the plain mzML file
     * {@code target}, its arrays zlib-compressed if {@code zlib}.
     *
     * @throws MzPeakException      if {@code target} exists, before the input is opened; if the dataset cannot be
     *                              read; or if the mzML cannot be written, which leaves no target and no temp file
     * @throws NullPointerException if an argument is null
     */
    public static Result toMzML(Path mzpeak, Path target, boolean zlib) {
        Objects.requireNonNull(mzpeak, "mzpeak");
        Objects.requireNonNull(target, "target");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new MzPeakException("mzML " + target + ": the target exists; it is never replaced");
        }
        try (MzPeakReader reader = MzPeakReader.open(mzpeak)) {
            List<SpectrumDescription> spectra = reader.metadata();
            List<WavelengthSpectrum> wavelengthSpectra = reader.wavelengthSpectra();
            List<Chromatogram> chromatograms = reader.chromatograms();
            MzMLWriter.Contents contents = new MzMLWriter.Contents(withoutStubs(reader.fileMetadata()),
                    reader.scanSettings(), cvList(reader.manifest().cvList(), spectra, wavelengthSpectra),
                    spectra.stream().map(SpectrumDescription::id).toList(),
                    wavelengthSpectra.stream().map(WavelengthSpectrum::id).toList(), chromatograms);
            try (MzMLWriter writer = MzMLWriter.open(target, contents, zlib)) {
                for (int i = 0; i < spectra.size(); i++) {
                    writer.write(reader.getSpectrumAt(i));
                }
                wavelengthSpectra.forEach(writer::write);
                writer.finish();
                return new Result(spectra.size(), wavelengthSpectra.size(), chromatograms.size(), writer.warnings());
            }
        }
    }

    // ---- mzML -> mzPeak (design 5.6, 6.1) --------------------------------------------------------------------------

    /** Adds what the reader hands over to the session, counting the losses of the mzPeak read on the way. */
    private static final class Adding implements MzMLReader.Visitor {

        private final MzPeakWriter.Session session;
        private final Losses losses;
        long spectra;
        long wavelengthSpectra;
        long chromatograms;

        Adding(MzPeakWriter.Session session, Losses losses) {
            this.session = session;
            this.losses = losses;
        }

        @Override
        public void spectrum(Spectrum spectrum) {
            losses.note(B_34, Arrays.stream(spectrum.mz()).anyMatch(Double::isNaN) ? 1 : 0);
            losses.note(B_26, nanOnly(spectrum.arrays()));
            session.addSpectrum(sharedParentsMoved(spectrum));
            spectra++;
        }

        @Override
        public void wavelengthSpectrum(WavelengthSpectrum spectrum) {
            session.addWavelengthSpectrum(spectrum);
            wavelengthSpectra++;
        }

        @Override
        public void chromatogram(Chromatogram chromatogram) {
            losses.note(B_26, nanOnly(chromatogram.arrays()));
            losses.note(B_36, chromatogram.size() == 0 && chromatogram.intensityUnit() != null ? 1 : 0);
            session.addChromatogram(chromatogram);
            chromatograms++;
        }

        /**
         * {@code spectrum} with the selected ions of each precursor that has the precursor index of an earlier one
         * (null equal to null) appended to the first such precursor's, and none left on it (design 5.6, B-19); the
         * spectrum itself when no ion moves.
         */
        private Spectrum sharedParentsMoved(Spectrum spectrum) {
            SpectrumDescription d = spectrum.description();
            List<Precursor> precursors = new ArrayList<>(d.precursors());
            Map<Long, Integer> firsts = new HashMap<>();
            long moved = 0;
            for (int i = 0; i < precursors.size(); i++) {
                Precursor p = precursors.get(i);
                Integer first = firsts.putIfAbsent(p.precursorIndex(), i);
                if (first != null && !p.selectedIons().isEmpty()) {
                    Precursor f = precursors.get(first);
                    List<SelectedIon> ions = new ArrayList<>(f.selectedIons());
                    ions.addAll(p.selectedIons());
                    precursors.set(first, new Precursor(f.precursorIndex(), f.precursorId(), f.isolationWindow(), ions,
                            f.activation()));
                    precursors.set(i, new Precursor(p.precursorIndex(), p.precursorId(), p.isolationWindow(),
                            List.of(), p.activation()));
                    moved += p.selectedIons().size();
                }
            }
            if (moved == 0) {
                return spectrum;
            }
            losses.note(B_19, moved);
            SpectrumDescription description = new SpectrumDescription(d.index(), d.id(), d.msLevel(),
                    d.retentionTime(), d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(),
                    d.scans(), precursors, d.parameters());
            return new Spectrum(description, spectrum.mz(), spectrum.intensity(), spectrum.peaks(), spectrum.arrays(),
                    spectrum.peakArrays());
        }
    }

    /** The number of {@code arrays} without a value other than NaN, empty ones included (B-26). */
    private static long nanOnly(Map<String, double[]> arrays) {
        return arrays.values().stream().filter(values -> Arrays.stream(values).allMatch(Double::isNaN)).count();
    }

    /**
     * The number of non-finite Double values among the params of the file description (contents, source files,
     * contacts), samples, software, scan settings and their targets, instrument configurations and their components,
     * data processing methods and the run of an mzML header (B-35).
     */
    private static long nonFinite(FileMetadata m, List<ScanSettings> scanSettings) {
        List<List<Param>> lists = new ArrayList<>();
        FileDescription d = m.fileDescription();
        lists.add(d.contents());
        d.sourceFiles().forEach(f -> lists.add(f.parameters()));
        d.contacts().forEach(c -> lists.add(c.parameters()));
        m.samples().forEach(s -> lists.add(s.parameters()));
        m.software().forEach(s -> lists.add(s.parameters()));
        scanSettings.forEach(s -> {
            lists.add(s.parameters());
            lists.addAll(s.targets());
        });
        m.instrumentConfigurations().forEach(ic -> {
            lists.add(ic.parameters());
            ic.components().forEach(c -> lists.add(c.parameters()));
        });
        m.dataProcessingMethods().forEach(dp -> dp.methods().forEach(pm -> lists.add(pm.parameters())));
        lists.add(m.run().parameters());
        return lists.stream().flatMap(List::stream)
                .filter(p -> p.value() instanceof Double v && !Double.isFinite(v)).count();
    }

    // ---- mzPeak -> mzML (design 5.10, 5.11) ------------------------------------------------------------------------

    /**
     * {@code m} without the source file and data processing stubs of the mzPeak writer, and without a run default
     * that names a removed one (design 5.10).
     */
    private static FileMetadata withoutStubs(FileMetadata m) {
        FileDescription d = m.fileDescription();
        List<SourceFile> sources = d == null ? List.of() : d.sourceFiles();
        List<SourceFile> keptSources = sources.stream().filter(f -> !SOURCE_STUB.equals(f)).toList();
        List<DataProcessing> keptProcessing = m.dataProcessingMethods().stream()
                .filter(dp -> !PROCESSING_STUB.equals(dp)).toList();
        boolean sourceRemoved = keptSources.size() < sources.size();
        boolean processingRemoved = keptProcessing.size() < m.dataProcessingMethods().size();
        MsRun r = m.run();
        MsRun run = r == null ? null : new MsRun(r.id(), r.defaultInstrumentId(),
                processingRemoved && PROCESSING_STUB.id().equals(r.defaultDataProcessingId())
                        ? null : r.defaultDataProcessingId(),
                sourceRemoved && SOURCE_STUB.id().equals(r.defaultSourceFileId()) ? null : r.defaultSourceFileId(),
                r.startTime(), r.parameters());
        return new FileMetadata(d == null ? null : new FileDescription(d.contents(), keptSources, d.contacts()),
                m.instrumentConfigurations(), keptProcessing, m.samples(), m.software(), run);
    }

    /**
     * {@code declared} followed by an entry for each CV prefix the params of {@code spectra} (with their scans,
     * isolation windows, selected ions and activations) and of {@code wavelengthSpectra} use, as accession or unit,
     * that {@code declared} lacks: the entry of {@link CvEntry#known()}, in its order, then the prefix alone, in the
     * order first used (design 5.11, 8.1).
     */
    private static List<CvEntry> cvList(List<CvEntry> declared, List<SpectrumDescription> spectra,
                                        List<WavelengthSpectrum> wavelengthSpectra) {
        List<List<Param>> lists = new ArrayList<>();
        for (SpectrumDescription d : spectra) {
            lists.add(d.parameters());
            d.scans().forEach(scan -> lists.add(scan.parameters()));
            for (Precursor p : d.precursors()) {
                if (p.isolationWindow() != null) {
                    lists.add(p.isolationWindow().parameters());
                }
                p.selectedIons().forEach(ion -> lists.add(ion.parameters()));
                lists.add(p.activation().parameters());
            }
        }
        wavelengthSpectra.forEach(w -> lists.add(w.parameters()));
        Set<String> used = new LinkedHashSet<>();
        for (List<Param> params : lists) {
            for (Param p : params) {
                used.add(prefix(p.accession()));
                used.add(prefix(p.unit()));
            }
        }
        used.remove("");
        declared.forEach(cv -> used.remove(cv.id()));
        List<CvEntry> list = new ArrayList<>(declared);
        for (CvEntry known : CvEntry.known()) {
            if (used.remove(known.id())) {
                list.add(known);
            }
        }
        used.forEach(prefix -> list.add(new CvEntry(prefix, null, null, null)));
        return list;
    }

    /**
     * The CV prefix of {@code curie} as {@link MzMLWriter} takes it: up to its first colon, all of it without one; the
     * empty string for none.
     */
    private static String prefix(String curie) {
        String s = Objects.requireNonNullElse(curie, "");
        int colon = s.indexOf(':');
        return colon > 0 ? s.substring(0, colon) : s;
    }
}
