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

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Converts mzML to mzPeak and mzPeak to mzML (mzML design 3, 8.1), each in one pass over its input.
 *
 * <p>{@link #toMzPeak} reads the mzML through {@link MzMLReader}: its header and survey first, so that what the model
 * cannot carry is rejected before the target is created, then every spectrum, wavelength spectrum and chromatogram
 * into a {@link MzPeakWriter.Session} opened with the header's file metadata, scan settings and cv list and the
 * survey's extra array keys. Before a spectrum is added, the selected ions of each of its precursors that shares its
 * precursor index (its parent) with an earlier precursor of the spectrum are moved to the first of them, which keeps
 * its own first; every isolation window and activation stays (design 5.6, B-19). Each chromatogram is added in its own
 * time unit, which the mzPeak writer converts to minutes (W3); the reader refuses a time unit that
 * {@link Chromatogram#withTimeUnit} cannot convert, so every chromatogram converts. It counts what the mzPeak read
 * changes (design 6.1): extra arrays of spectra and chromatograms without a value other than NaN, empty ones included,
 * which it drops (B-26); non-finite values of file-level params, which come back as the Strings {@code Infinity},
 * {@code -Infinity} or {@code NaN} (B-35); chromatograms without points, whose intensity unit it drops (B-36); and what
 * the mzPeak writer adds (B-24): a spectrum type to spectra without one, the five summary params to spectra with
 * points and none of them, a scan to spectra without scans, and a run default source file when the run names none. A
 * NaN m/z, time, wavelength or intensity fails the conversion with the writer's message, which names the spectrum,
 * chromatogram or wavelength spectrum and the position (W10). The mzML cv list goes to the session as far as
 * {@link MzPeakWriter.Options#withCvList} accepts it (W8, D13-15): it rejects an entry of a CV {@link CvEntry#known()}
 * lacks that has no version or a moving URI (a master branch, a CVS checkout and the like). The conversion leaves such
 * an entry out, with one warning, when no param accession or unit, array key, chromatogram type or unit of the mzML
 * uses its prefix, and else fails with the writer's message, the header before the target is created. A failure closes
 * the session, which removes the target and the temp files it created.
 *
 * <p>{@link #toMzML} refuses an existing target before it opens the input (Q66), then reads the mzPeak through
 * {@link MzPeakReader} with its default options (profile points reconstructed, both signal members) and writes it
 * through {@link MzMLWriter}. It removes what the mzPeak writer adds where the run names no source file or data
 * processing and the file has none (design 5.10): a source file {@code mzpeakj_source} named {@code unknown} at
 * {@code file:///unknown} without params, and a data processing {@code mzpeakj_export} without methods, and the
 * run's default that names a removed one. The writer is given the dataset's cv list and announced every CV prefix
 * that the params of the spectra (their scans, precursors, selected ions and activations included) and of the
 * wavelength spectra use, as accession or unit; it adds MS, UO and every prefix in use that the dataset's list lacks,
 * as {@link CvEntry#known()} declares it and in its order, then the prefix alone, and counts them as A-17 (design
 * 5.11, 6.2).
 *
 * <p>Each {@link Result} carries the warnings of its direction: for {@code toMzPeak} the reader's limit and loss lines,
 * then the conversion's own; for {@code toMzML} the writer's.
 */
public final class MzMLConversion {

    // The losses of design 6.1 toMzPeak counts itself, one line each after the reader's.
    static final String B_19 = "B-19 selected ions moved to the first precursor of their spectrum with the same parent";
    static final String B_24_TYPE = "B-24 spectrum types added by the mzPeak writer to spectra without one";
    static final String B_24_SUMMARY = "B-24 summary params (m/z range, base peak, total ion current) added by the"
            + " mzPeak writer to spectra with points and none of the five";
    static final String B_24_SCAN = "B-24 scans added by the mzPeak writer to spectra without scans";
    static final String B_24_SOURCE = "B-24 run defaultSourceFileRef added by the mzPeak writer (the first source"
            + " file, else a stub source file)";
    static final String B_26 = "B-26 extra arrays without a value other than NaN, which the mzPeak read drops";
    static final String B_35 = "B-35 non-finite file-level param values, which the mzPeak read gives as the Strings"
            + " Infinity, -Infinity or NaN";
    static final String B_36 = "B-36 chromatograms without points, whose intensity unit the mzPeak read drops";

    /** The source file the mzPeak writer adds when the run and the file description name none (design 5.10). */
    private static final SourceFile SOURCE_STUB = new SourceFile("mzpeakj_source", "unknown", "file:///unknown",
            List.of());
    /** The data processing the mzPeak writer adds when the run names none and the file has none (design 5.10). */
    private static final DataProcessing PROCESSING_STUB = new DataProcessing("mzpeakj_export", List.of());
    /** The summary params the mzPeak writer derives from the points when a spectrum has none of them (B-24). */
    private static final Set<String> SUMMARIES = Set.of("MS:1000528", "MS:1000527", "MS:1000504", "MS:1000505",
            "MS:1000285");

    private MzMLConversion() {
    }

    /**
     * What a conversion wrote, and what it lost.
     *
     * @param spectra           the number of MS spectra written
     * @param wavelengthSpectra the number of wavelength spectra written
     * @param chromatograms     the number of chromatograms written
     * @param warnings          the limit lines, then one line per kind of loss with its count (design 6, 10), then
     *                          for {@code toMzPeak} one line per mzML cv left out of the cv list (W8); unmodifiable
     */
    public record Result(long spectra, long wavelengthSpectra, long chromatograms, List<String> warnings) {

        /** @throws NullPointerException if {@code warnings} is or holds null */
        public Result {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * Converts the mzML file {@code mzml} into the mzPeak dataset {@code target}: an unpacked directory if
     * {@code directory}, else a ZIP archive, written with {@code options} and the mzML's cv list: the cv list of
     * {@code options} ({@link MzPeakWriter.Options#cvList()}) is replaced by the entries of the mzML's {@code cvList}
     * that {@link MzPeakWriter.Options#withCvList} accepts; an entry it rejects is left out, with a warning, if no
     * written CURIE uses its prefix (W8, D13-15).
     *
     * @throws MzPeakException      if the mzML cannot be read or holds what the model cannot carry (as
     *                              {@link MzMLReader} states), before the target is created; if {@code target} exists
     *                              (for an unpacked directory: and is not an empty directory); if a CURIE uses the
     *                              prefix of a cv list entry {@link MzPeakWriter.Options#withCvList} rejects; or if a
     *                              spectrum or the dataset cannot be written, after which nothing the conversion wrote
     *                              remains
     * @throws NullPointerException if an argument is null
     */
    public static Result toMzPeak(Path mzml, Path target, boolean directory, MzPeakWriter.Options options) {
        Objects.requireNonNull(mzml, "mzml");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        MzMLReader reader = new MzMLReader(mzml);
        MzMLReader.Header header = reader.header();
        MzMLReader.Survey survey = reader.survey();
        Cvs cvs = new Cvs(mzml, header.cvList());
        List<List<Param>> headerParams = headerParams(header.fileMetadata(), header.scanSettings());
        cvs.check("the file metadata or scan settings", headerParams, List.of());
        Losses losses = new Losses();
        losses.note(B_35, nonFinite(headerParams));
        losses.note(B_24_SOURCE, header.fileMetadata().run().defaultSourceFileId() == null ? 1 : 0);
        MzPeakWriter.Options withCvList = options.withCvList(cvs.kept);
        Adding adding;
        try (MzPeakWriter.Session session = directory
                ? MzPeakWriter.openDirectory(target, header.fileMetadata(), header.scanSettings(),
                survey.spectrumArrays(), withCvList)
                : MzPeakWriter.openArchive(target, header.fileMetadata(), header.scanSettings(),
                survey.spectrumArrays(), withCvList)) {
            adding = new Adding(session, losses, cvs);
            reader.read(adding);
            session.finish();
        }
        List<String> warnings = new ArrayList<>(reader.warnings());
        warnings.addAll(losses.lines());
        cvs.rejected.forEach((id, why) -> warnings.add("the mzML cv " + id + " is not declared in"
                + " metadata.cv_list, which no written CURIE needs, since " + why));
        return new Result(adding.spectra, adding.wavelengthSpectra, adding.chromatograms, warnings);
    }

    /**
     * Converts the mzPeak dataset {@code mzpeak} (a directory or a ZIP archive) into the plain mzML file
     * {@code target}, its arrays zlib-compressed if {@code zlib}.
     *
     * @throws MzPeakException      if {@code target} exists, before the input is opened; if the dataset cannot be
     *                              read, with a message that starts with {@code mzPeak <mzpeak>: } (also for an
     *                              {@link UncheckedIOException} of the reader); or if the mzML cannot be written,
     *                              which leaves no target and no temp file
     * @throws NullPointerException if an argument is null
     */
    public static Result toMzML(Path mzpeak, Path target, boolean zlib) {
        Objects.requireNonNull(mzpeak, "mzpeak");
        Objects.requireNonNull(target, "target");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new MzPeakException("mzML " + target + ": the target exists; it is never replaced");
        }
        try (MzPeakReader reader = read(mzpeak, () -> MzPeakReader.open(mzpeak))) {
            List<SpectrumDescription> spectra = read(mzpeak, reader::metadata);
            List<WavelengthSpectrum> wavelengthSpectra = read(mzpeak, reader::wavelengthSpectra);
            MzMLWriter.Contents contents = read(mzpeak, () -> new MzMLWriter.Contents(
                    withoutStubs(reader.fileMetadata()), reader.scanSettings(), reader.manifest().cvList(),
                    spectra.stream().map(SpectrumDescription::id).toList(),
                    wavelengthSpectra.stream().map(WavelengthSpectrum::id).toList(), reader.chromatograms()));
            try (MzMLWriter writer = MzMLWriter.open(target, contents, zlib,
                    spectrumPrefixes(spectra, wavelengthSpectra))) {
                for (int i = 0; i < spectra.size(); i++) {
                    int index = i;
                    writer.write(read(mzpeak, () -> reader.getSpectrumAt(index)));
                }
                wavelengthSpectra.forEach(writer::write);
                writer.finish();
                return new Result(spectra.size(), wavelengthSpectra.size(), contents.chromatograms().size(),
                        writer.warnings());
            }
        }
    }

    /**
     * What {@code read} reads from the mzPeak dataset {@code mzpeak}; a failure of the reader names the dataset, as the
     * writer's failures name the mzML target.
     */
    private static <T> T read(Path mzpeak, Supplier<T> read) {
        try {
            return read.get();
        } catch (MzPeakException | UncheckedIOException e) {
            throw new MzPeakException("mzPeak " + mzpeak + ": " + e.getMessage(), e);
        }
    }

    // ---- mzML -> mzPeak (design 5.6, 6.1) --------------------------------------------------------------------------

    /** Adds what the reader hands over to the session, counting the losses of the mzPeak read on the way. */
    private static final class Adding implements MzMLReader.Visitor {

        private final MzPeakWriter.Session session;
        private final Losses losses;
        private final Cvs cvs;
        long spectra;
        long wavelengthSpectra;
        long chromatograms;

        Adding(MzPeakWriter.Session session, Losses losses, Cvs cvs) {
            this.session = session;
            this.losses = losses;
            this.cvs = cvs;
        }

        @Override
        public void spectrum(Spectrum spectrum) {
            SpectrumDescription d = spectrum.description();
            List<String> curies = new ArrayList<>(spectrum.arrays().keySet());
            curies.addAll(spectrum.peakArrays().keySet());
            curies.addAll(Arrays.asList(spectrum.intensityUnit(), spectrum.peakIntensityUnit()));
            cvs.check("spectrum " + d.id(), descriptionParams(d), curies);
            losses.note(B_26, nanOnly(spectrum.arrays()));
            losses.note(B_24_TYPE, has(d.parameters(), MzMLTerms.SPECTRUM_TYPES.keySet()) ? 0 : 1);
            losses.note(B_24_SUMMARY, spectrum.mz().length == 0 || has(d.parameters(), SUMMARIES) ? 0 : 1);
            losses.note(B_24_SCAN, d.scans().isEmpty() ? 1 : 0);
            session.addSpectrum(sharedParentsMoved(spectrum));
            spectra++;
        }

        @Override
        public void wavelengthSpectrum(WavelengthSpectrum spectrum) {
            cvs.check("wavelength spectrum " + spectrum.id(), List.of(spectrum.parameters()), List.of());
            session.addWavelengthSpectrum(spectrum);
            wavelengthSpectra++;
        }

        @Override
        public void chromatogram(Chromatogram chromatogram) {
            List<List<Param>> params = new ArrayList<>(List.of(chromatogram.parameters()));
            params.addAll(precursorParams(chromatogram.precursors()));
            List<String> curies = new ArrayList<>(chromatogram.arrays().keySet());
            curies.addAll(Arrays.asList(chromatogram.typeCurie(), chromatogram.intensityUnit(),
                    chromatogram.timeUnit()));
            cvs.check("chromatogram " + chromatogram.id(), params, curies);
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
                            f.activation(), f.msLevel()));
                    precursors.set(i, new Precursor(p.precursorIndex(), p.precursorId(), p.isolationWindow(),
                            List.of(), p.activation(), p.msLevel()));
                    moved += p.selectedIons().size();
                }
            }
            if (moved == 0) {
                return spectrum;
            }
            losses.note(B_19, moved);
            SpectrumDescription description = new SpectrumDescription(d.index(), d.id(), d.msLevel(),
                    d.retentionTime(), d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(),
                    d.scans(), precursors, d.parameters(), d.dataProcessingRef(), d.products());
            return new Spectrum(description, spectrum.mz(), spectrum.intensity(), spectrum.peaks(), spectrum.arrays(),
                    spectrum.peakArrays(), spectrum.intensityUnit(), spectrum.peakIntensityUnit());
        }
    }

    /** Whether {@code params} hold a param with one of {@code accessions}; a userParam has none (null). */
    private static boolean has(List<Param> params, Collection<String> accessions) {
        return params.stream().anyMatch(p -> p.accession() != null && accessions.contains(p.accession()));
    }

    /** The number of {@code arrays} without a value other than NaN, empty ones included (B-26). */
    private static long nanOnly(Map<String, double[]> arrays) {
        return arrays.values().stream().filter(values -> Arrays.stream(values).allMatch(Double::isNaN)).count();
    }

    /**
     * The param lists of the file description (contents, source files, contacts), samples, software, scan settings
     * and their targets, instrument configurations and their components, data processing methods and the run of an
     * mzML header.
     */
    private static List<List<Param>> headerParams(FileMetadata m, List<ScanSettings> scanSettings) {
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
        return lists;
    }

    /** The number of non-finite Double values among {@code lists}, the {@link #headerParams} of a header (B-35). */
    private static long nonFinite(List<List<Param>> lists) {
        return lists.stream().flatMap(List::stream)
                .filter(p -> p.value() instanceof Double v && !Double.isFinite(v)).count();
    }

    /**
     * The param lists of {@code d}, its scans and its precursors (their isolation windows, selected ions and
     * activations).
     */
    private static List<List<Param>> descriptionParams(SpectrumDescription d) {
        List<List<Param>> lists = new ArrayList<>();
        lists.add(d.parameters());
        d.scans().forEach(scan -> lists.add(scan.parameters()));
        lists.addAll(precursorParams(d.precursors()));
        return lists;
    }

    /** The param lists of the isolation windows, selected ions and activations of {@code precursors}. */
    private static List<List<Param>> precursorParams(List<Precursor> precursors) {
        List<List<Param>> lists = new ArrayList<>();
        for (Precursor p : precursors) {
            if (p.isolationWindow() != null) {
                lists.add(p.isolationWindow().parameters());
            }
            p.selectedIons().forEach(ion -> lists.add(ion.parameters()));
            lists.add(p.activation().parameters());
        }
        return lists;
    }

    /**
     * The mzML cv list as the mzPeak writer takes it (W8, D13-15): every entry {@link MzPeakWriter.Options#withCvList}
     * accepts is {@link #kept}; the others, of CVs {@link CvEntry#known()} lacks, are {@link #rejected}, unless an
     * accepted entry has their id, and a written CURIE with their prefix fails the conversion.
     */
    private static final class Cvs {

        private final Path mzml;
        /** The entries the writer accepts, in the mzML's order. */
        final List<CvEntry> kept = new ArrayList<>();
        /** Why the writer rejects the entry of each id rejected, in the mzML's order. */
        final Map<String, String> rejected = new LinkedHashMap<>();

        Cvs(Path mzml, List<CvEntry> cvList) {
            this.mzml = mzml;
            for (CvEntry cv : cvList) {
                try {
                    MzPeakWriter.Options.defaults().withCvList(List.of(cv));
                    kept.add(cv);
                } catch (IllegalArgumentException e) {
                    rejected.putIfAbsent(cv.id(), e.getMessage());
                }
            }
            kept.forEach(cv -> rejected.remove(cv.id()));
        }

        /**
         * @throws MzPeakException naming the mzML, {@code what}, the CURIE and why its CV is rejected, if the
         *                         accession or unit of a param of {@code params} or a CURIE of {@code curies} has the
         *                         prefix of a rejected CV
         */
        void check(String what, List<List<Param>> params, List<String> curies) {
            List<String> all = new ArrayList<>(curies);
            params.forEach(list -> list.forEach(p -> all.addAll(Arrays.asList(p.accession(), p.unit()))));
            for (String curie : all) {
                int colon = curie == null ? -1 : curie.indexOf(':');
                String why = colon > 0 ? rejected.get(curie.substring(0, colon)) : null;
                if (why != null) {
                    throw new MzPeakException("mzML " + mzml + ": " + what + " uses " + curie + ", but " + why);
                }
            }
        }
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
     * The CV prefixes the params of {@code spectra} (with their scans, isolation windows, selected ions and
     * activations) and of {@code wavelengthSpectra} use, as accession or unit, in the order first used (design 5.11,
     * 8.1).
     */
    private static Set<String> spectrumPrefixes(List<SpectrumDescription> spectra,
                                                List<WavelengthSpectrum> wavelengthSpectra) {
        List<List<Param>> lists = new ArrayList<>();
        spectra.forEach(d -> lists.addAll(descriptionParams(d)));
        wavelengthSpectra.forEach(w -> lists.add(w.parameters()));
        Set<String> used = new LinkedHashSet<>();
        for (List<Param> params : lists) {
            for (Param p : params) {
                used.add(prefix(p.accession()));
                used.add(prefix(p.unit()));
            }
        }
        used.remove("");
        return used;
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
