package org.mzpeak.mzml;

import org.mzpeak.io.MzPeakException;
import org.mzpeak.io.MzPeakManifest.CvEntry;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.CvTerms;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.ComponentType;
import org.mzpeak.model.meta.FileMetadata.MsRun;
import org.mzpeak.model.meta.ScanSettings;
import uk.ac.ebi.jmzml.model.mzml.AnalyzerComponent;
import uk.ac.ebi.jmzml.model.mzml.BinaryDataArray;
import uk.ac.ebi.jmzml.model.mzml.BinaryDataArrayList;
import uk.ac.ebi.jmzml.model.mzml.CV;
import uk.ac.ebi.jmzml.model.mzml.CVList;
import uk.ac.ebi.jmzml.model.mzml.CVParam;
import uk.ac.ebi.jmzml.model.mzml.ChromatogramList;
import uk.ac.ebi.jmzml.model.mzml.Component;
import uk.ac.ebi.jmzml.model.mzml.ComponentList;
import uk.ac.ebi.jmzml.model.mzml.DataProcessing;
import uk.ac.ebi.jmzml.model.mzml.DataProcessingList;
import uk.ac.ebi.jmzml.model.mzml.DetectorComponent;
import uk.ac.ebi.jmzml.model.mzml.FileDescription;
import uk.ac.ebi.jmzml.model.mzml.InstrumentConfiguration;
import uk.ac.ebi.jmzml.model.mzml.InstrumentConfigurationList;
import uk.ac.ebi.jmzml.model.mzml.MzML;
import uk.ac.ebi.jmzml.model.mzml.ParamGroup;
import uk.ac.ebi.jmzml.model.mzml.ProcessingMethod;
import uk.ac.ebi.jmzml.model.mzml.Run;
import uk.ac.ebi.jmzml.model.mzml.Sample;
import uk.ac.ebi.jmzml.model.mzml.SampleList;
import uk.ac.ebi.jmzml.model.mzml.ScanSettingsList;
import uk.ac.ebi.jmzml.model.mzml.SelectedIonList;
import uk.ac.ebi.jmzml.model.mzml.Software;
import uk.ac.ebi.jmzml.model.mzml.SoftwareList;
import uk.ac.ebi.jmzml.model.mzml.SourceComponent;
import uk.ac.ebi.jmzml.model.mzml.SourceFile;
import uk.ac.ebi.jmzml.model.mzml.SourceFileList;
import uk.ac.ebi.jmzml.model.mzml.SourceFileRef;
import uk.ac.ebi.jmzml.model.mzml.SourceFileRefList;
import uk.ac.ebi.jmzml.model.mzml.SpectrumList;
import uk.ac.ebi.jmzml.model.mzml.TargetList;
import uk.ac.ebi.jmzml.model.mzml.UserParam;
import uk.ac.ebi.jmzml.xml.io.MzMLMarshaller;

import javax.xml.bind.DatatypeConverter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Writes mzPeakJ's model as plain mzML 1.1.0 through jmzML 1.7.11 as is (mzML design 3, 5, 8.1, 5.11): {@link #open}
 * allocates every id of the document and builds jmzML's {@code MzML} tree of the header and the run, and
 * {@link #finish()} adds the chromatograms and marshals the whole tree with jmzML's {@code MzMLMarshaller} to a temp
 * file next to the target, which it then moves to the target without replacing anything there. Nothing is written
 * before {@code finish()}; the whole document is held in memory until then. Every writer notes the limit lines
 * {@link #LIMITS} (design 6.0), and what the mzML cannot carry as it is is counted in {@link #warnings()} (design 6.2).
 *
 * <p><b>Ids</b> (design 5.2) come from one registry, allocated in this order: the instrument configurations by rule
 * IC ({@code IC<n+1>}, {@code IC_<n>}); the CVs of the cv list; the other header ids in document order (source files,
 * samples, software, scan settings, data processings, run), each made an NCName by rule NC (A-2) and given the first
 * free suffix {@code _2}, {@code _3}, ... when taken (A-21, for CVs A-24), a null or empty id a name of its kind
 * (A-21); the entries mzML requires and the model lacks (design 5.10: software {@code mzPeakJ}, data processing
 * {@code mzpeakj_conversion}, instrument configuration {@code IC1}, run {@code mzpeakj_run}; A-8); then the ids of
 * the spectra, wavelength spectra and chromatograms, in one set: a spectrum id that is null, empty, does not match the
 * schema's pattern or is taken, and a chromatogram id that is null, empty or taken, is written {@code index=<i>} (A-3),
 * each decided after the id is made XML-safe. References are written through the id of what they name; one that names
 * nothing is dropped, or redirected to the first entry where mzML requires it (A-18), and an instrument configuration
 * without software reference is given the first software (A-25).
 *
 * <p><b>CVs</b> (design 5.11): the cv list is that of the contents, an entry without id dropped and a missing full
 * name or URI completed from {@link CvEntry#known()}, else with the prefix and {@code unknown}; then MS, UO and every
 * other prefix an accession or unit of the contents uses, as {@link CvEntry#known()} declares it (A-17). Every
 * {@code cvRef} and {@code unitCvRef} is the id of its prefix's CV.
 *
 * <p><b>Values</b> (design 5.3): params are written in list order, cvParams before userParams, a value formatted by
 * {@link MzMLTerms#format}, a cvParam without name named by {@link MzMLTerms#name}, else by its accession. Every
 * string handed to jmzML is first made XML-safe (C0 and C1 controls, U+FFFE, U+FFFF and unpaired surrogates replaced
 * by U+FFFD, A-28); values with tab, LF or CR (read back as spaces, A-27) and values with a character outside ASCII
 * (jmzML-based readers read them as U+FFFD, since jmzML writes no encoding declaration; A-29) are counted. A run start
 * time that is no xs:dateTime is dropped (A-10); a valid one is written through jmzML's {@code CalendarAdapter}, which
 * gives a time without zone the JVM's default time zone and keeps at most milliseconds (A-26). URIs are repaired by
 * rule URI (A-22).
 *
 * <p><b>Arrays</b> (design 5.7) are encoded by jmzML as 64-bit floats, zlib-compressed unless the writer was opened
 * without zlib; an empty array is never compressed (jmzML's encoder does not return on one). A key of
 * {@link CvTerms#ARRAY_TYPES} is written as that array type, every other key as MS:1000786 'non-standard data array'
 * with the key as its value; each with its unit from {@link CvTerms#DEFAULT_ARRAY_UNITS}, a chromatogram's intensity
 * with its {@code intensityUnit}.
 *
 * <p><b>Chromatograms</b> (design 5.8) keep their first precursor (A-11), whose spectrum reference is written as an
 * {@code externalSpectrumID} with the run's default source file, and whose {@code precursorIndex} is not written
 * (A-19). The chromatogramList's default data processing is that of every chromatogram when they all name the same,
 * else the spectrumList's; a chromatogram names its own when it differs from the list's.
 *
 * <p><b>Side effect of jmzML.</b> Every call of {@code MzMLMarshaller.marshall}, so every {@link #finish()} that
 * writes, sets the JVM-wide system property {@code javax.xml.stream.XMLOutputFactory} to
 * {@code com.sun.xml.internal.stream.XMLOutputFactoryImpl}, for every later user of the StAX output factory in the JVM.
 */
public final class MzMLWriter implements AutoCloseable {

    /**
     * The limit lines every writer notes (design 6.0), before its loss lines:
     * <ul>
     *   <li>L-8: the output is plain mzML, without index, offsets or checksum;
     *   <li>L-9: the whole mzML document is held in memory before it is written.
     * </ul>
     */
    public static final List<String> LIMITS = List.of(
            "L-8 the output is plain mzML: no index, no offsets, no checksum",
            "L-9 the whole mzML document is held in memory before it is written: about 0.75 bytes of heap per byte"
                    + " of the arrays' Base64 text plus 13 KB per spectrum");

    // The losses of design 6.2 this writer counts, one line each in warnings().
    static final String A_2 = "A-2 ids that are not NCNames encoded by rule NC";
    static final String A_3 = "A-3 spectrum and chromatogram ids that are null, empty, malformed or taken written as"
            + " index=<i>";
    static final String A_8 = "A-8 required content synthesized: %s";
    static final String A_10 = "A-10 run start times that are no xs:dateTime dropped";
    static final String A_11 = "A-11 chromatograms with more than one precursor keep the first";
    static final String A_12_UNKNOWN = "A-12 instrument components of type UNKNOWN dropped";
    static final String A_12_LIST = "A-12 component lists without a source, analyzer or detector dropped";
    static final String A_17 = "A-17 CVs in use but not in the cv list declared";
    static final String A_18 = "A-18 refs that name nothing: %s";
    static final String A_19 = "A-19 chromatogram precursor indexes not written";
    static final String A_21 = "A-21 header ids renamed";
    static final String A_22 = "A-22 invalid xs:anyURI values percent-encoded";
    static final String A_24 = "A-24 CV ids renamed because the id was taken";
    static final String A_25 = "A-25 instrument configurations without software reference given the first software";
    static final String A_26 = "A-26 run start time rewritten by jmzML's CalendarAdapter: %s -> %s";
    static final String A_27 = "A-27 values with tab, LF or CR, which XML readers read back as spaces";
    static final String A_28 = "A-28 values with characters XML cannot carry or C1 controls, replaced by U+FFFD";
    static final String A_29 = "A-29 values with characters outside ASCII, which jmzML-based readers read as U+FFFD";
    // Warned by design 5.2 and 5.3, without a loss number.
    static final String CV_WITHOUT_ID = "cv list entries without id dropped";
    static final String CV_COMPLETED = "CVs without fullName or URI completed from the known CVs, else with the prefix"
            + " and 'unknown'";
    static final String PARAM_SKIPPED = "params with neither name nor accession skipped";
    static final String NAMED_BY_ACCESSION = "cvParams without name and of an unknown term named by their accession";

    private static final String NON_STANDARD_ARRAY = "MS:1000786";
    private static final String TIME_ARRAY = "MS:1000595";
    private static final String INTENSITY_ARRAY = "MS:1000515";
    private static final String MZ = "MS:1000040";
    private static final Map<Polarity, String> POLARITY_TERMS =
            Map.of(Polarity.POSITIVE, "MS:1000130", Polarity.NEGATIVE, "MS:1000129");
    /** The component kinds mzML holds, in its order, with jmzML's class of each. */
    private static final Map<ComponentType, Supplier<Component>> COMPONENTS = components();
    private static final Map<String, CvEntry> KNOWN = known();
    private static final Pattern TAB_LF_CR = Pattern.compile("[\t\n\r]");
    private static final Pattern NON_ASCII = Pattern.compile("[^\\x00-\\x7F]");

    private final Path target;
    private final Contents contents;
    private final boolean zlib;
    private final Losses losses = new Losses();
    /** The document; null once {@link #finish()} or {@link #close()} ran. */
    private MzML mzml;

    // The id registry (design 5.2): the xs:ID namespace, spectrum and chromatogram ids, and the maps refs go through.
    private final Set<String> xmlIds = new HashSet<>();
    private final Set<String> entityIds = new HashSet<>();
    private final Map<Integer, String> instrumentIds = new HashMap<>();
    /** Each declared CV by its prefix (the first of a repeated prefix); MS for jmzML's array terms. */
    private final Map<String, CV> cvs = new HashMap<>();
    private Map<String, String> sourceFileIds;
    private Map<String, String> softwareIds;
    private Map<String, String> dataProcessingIds;
    private String firstSoftware;
    /** The run's default source file as written, null if none is written. */
    private String defaultSourceFile;
    /** The spectrumList's default data processing as written (design 5.1). */
    private String spectrumListDefault;
    private List<String> chromatogramIds;

    /**
     * What an mzML document holds besides its spectra, and the ids of the spectra {@link MzMLWriter} will be given.
     * The lists are copied; the id lists may hold null.
     *
     * @param fileMetadata          the file description, instrument configurations, data processings, samples,
     *                              software and run
     * @param scanSettings          the scan settings, with their source file refs and targets
     * @param cvList                the CVs to declare, besides those the contents use (design 5.11)
     * @param spectrumIds           the ids of the MS spectra, in the order they will be written
     * @param wavelengthSpectrumIds the ids of the wavelength spectra, in the order they will be written
     * @param chromatograms         the chromatograms
     */
    public record Contents(FileMetadata fileMetadata, List<ScanSettings> scanSettings, List<CvEntry> cvList,
                           List<String> spectrumIds, List<String> wavelengthSpectrumIds,
                           List<Chromatogram> chromatograms) {

        /**
         * @throws NullPointerException if a component is null, or {@code scanSettings}, {@code cvList} or
         *                              {@code chromatograms} holds null
         */
        public Contents {
            Objects.requireNonNull(fileMetadata, "fileMetadata");
            scanSettings = List.copyOf(scanSettings);
            cvList = List.copyOf(cvList);
            spectrumIds = Collections.unmodifiableList(new ArrayList<>(spectrumIds));
            wavelengthSpectrumIds = Collections.unmodifiableList(new ArrayList<>(wavelengthSpectrumIds));
            chromatograms = List.copyOf(chromatograms);
        }
    }

    private MzMLWriter(Path target, Contents contents, boolean zlib) {
        this.target = target;
        this.contents = contents;
        this.zlib = zlib;
        LIMITS.forEach(losses::limit);
        build();
    }

    /**
     * A writer of {@code contents} to the mzML file {@code target}, zlib-compressed if {@code zlib}: every id is
     * allocated and the header and run of the document are built; nothing is written yet.
     *
     * @throws MzPeakException      if {@code target} exists
     * @throws NullPointerException if an argument is null
     */
    public static MzMLWriter open(Path target, Contents contents, boolean zlib) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(contents, "contents");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new MzPeakException("mzML " + target + ": the target exists; it is never replaced");
        }
        return new MzMLWriter(target, contents, zlib);
    }

    /**
     * Adds the chromatograms and writes the document: marshalled by jmzML to a temp file in the target's directory,
     * which is then moved to the target without replacing anything there (design 5.11). The writer is finished
     * afterwards, also when this fails.
     *
     * @throws IllegalStateException if fewer spectra and wavelength spectra were written than the contents announced
     *                               (nothing is written then), or the writer is finished or closed
     * @throws MzPeakException       naming the target, if the document cannot be written or moved there, among others
     *                               when the target exists by now; the temp file is deleted
     */
    public void finish() {
        MzML document = document();
        mzml = null;
        int announced = contents.spectrumIds().size() + contents.wavelengthSpectrumIds().size();
        int written = document.getRun().getSpectrumList().getSpectrum().size();
        if (written < announced) {
            throw new IllegalStateException("mzML " + target + ": " + written + " spectra written, " + announced
                    + " announced; nothing is written");
        }
        chromatograms(document.getRun());
        Path temp = target.toAbsolutePath().resolveSibling("." + target.getFileName() + "." + UUID.randomUUID()
                + ".tmp");
        try {
            try (Writer w = new BufferedWriter(new OutputStreamWriter(
                    Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW), UTF_8))) {
                new MzMLMarshaller().marshall(document, w);
            }
            Files.move(temp, target);
        } catch (IllegalStateException | IOException e) {
            temp.toFile().delete(); // best effort; it does not exist when it could not be created
            throw new MzPeakException("mzML " + target + ": cannot be written: " + e, e);
        }
    }

    /** Drops the document without writing anything, unless {@link #finish()} ran; a second call does nothing. */
    @Override
    public void close() {
        mzml = null;
    }

    /**
     * What was lost so far: the limit lines {@link #LIMITS}, then one line per kind of loss, {@code <kind> (<count>)},
     * in first-seen order (design 6.2).
     */
    public List<String> warnings() {
        return losses.lines();
    }

    private MzML document() {
        if (mzml == null) {
            throw new IllegalStateException("mzML " + target + ": the writer is finished or closed");
        }
        return mzml;
    }

    // ---- open: the id registry and the header (design 5.1, 5.2, 5.10, 5.11) --------------------------------------

    private void build() {
        FileMetadata m = contents.fileMetadata();
        MsRun run = Objects.requireNonNullElse(m.run(), new MsRun(null, null, null, null, null, List.of()));
        FileMetadata.FileDescription description = Objects.requireNonNullElse(m.fileDescription(),
                new FileMetadata.FileDescription(List.of(), List.of()));

        // 1. instrument configurations by rule IC, fixed first
        List<String> instruments = new ArrayList<>();
        for (FileMetadata.InstrumentConfiguration ic : m.instrumentConfigurations()) {
            String id = unique(MzMLTerms.icId(ic.id()), A_21);
            instruments.add(id);
            instrumentIds.putIfAbsent(ic.id(), id);
        }
        if (instruments.isEmpty()) {
            instruments.add(unique("IC1", A_21));
            note(String.format(A_8, "instrument configuration IC1"));
        }
        // 2. CVs
        CVList cvList = cvList(prefixes(m, description, run));
        // 3. the other header ids in document order
        List<String> sourceFiles = ids(description.sourceFiles(), FileMetadata.SourceFile::id, "sf_");
        sourceFileIds = refMap(description.sourceFiles(), FileMetadata.SourceFile::id, sourceFiles);
        List<String> samples = ids(m.samples(), FileMetadata.Sample::id, "sample_");
        List<String> software = ids(m.software(), FileMetadata.Software::id, "software_");
        softwareIds = refMap(m.software(), FileMetadata.Software::id, software);
        List<String> scanSettings = ids(contents.scanSettings(), ScanSettings::id, "scan_settings_");
        List<String> processing = ids(m.dataProcessingMethods(), FileMetadata.DataProcessing::id, "dp_");
        dataProcessingIds = refMap(m.dataProcessingMethods(), FileMetadata.DataProcessing::id, processing);
        String runId = present(run.id()) ? ids(List.of(run), MsRun::id, "run").get(0) : null;
        // 4. what mzML requires and the model lacks
        if (software.isEmpty()) {
            software.add(unique("mzPeakJ", A_21));
            note(String.format(A_8, "software mzPeakJ"));
        }
        firstSoftware = software.get(0);
        if (processing.isEmpty()) {
            processing.add(unique("mzpeakj_conversion", A_21));
            note(String.format(A_8, "data processing mzpeakj_conversion"));
        }
        if (runId == null) {
            runId = unique("mzpeakj_run", A_21);
            note(String.format(A_8, "run id mzpeakj_run"));
        }
        // 5. spectrum and chromatogram ids, in one set; the spectra are written by write (JZ-6)
        entityIds(contents.spectrumIds(), 0, true);
        entityIds(contents.wavelengthSpectrumIds(), contents.spectrumIds().size(), true);
        chromatogramIds = entityIds(contents.chromatograms().stream().map(Chromatogram::id).toList(), 0, false);

        mzml = new MzML();
        mzml.setVersion("1.1.0");
        mzml.setCvList(cvList);
        mzml.setFileDescription(fileDescription(description, sourceFiles));
        if (!samples.isEmpty()) {
            SampleList list = new SampleList();
            for (int i = 0; i < samples.size(); i++) {
                FileMetadata.Sample s = m.samples().get(i);
                Sample x = new Sample();
                x.setId(text(samples.get(i)));
                x.setName(text(s.name()));
                params(x, s.parameters());
                list.getSample().add(x);
            }
            list.setCount(samples.size());
            mzml.setSampleList(list);
        }
        mzml.setSoftwareList(softwareList(m.software(), software));
        if (!scanSettings.isEmpty()) {
            mzml.setScanSettingsList(scanSettingsList(scanSettings));
        }
        mzml.setInstrumentConfigurationList(instrumentList(m.instrumentConfigurations(), instruments));
        mzml.setDataProcessingList(dataProcessingList(m.dataProcessingMethods(), processing));
        mzml.setRun(run(run, runId, instruments.get(0), processing.get(0)));
    }

    /**
     * The CV prefixes of every accession and unit the contents put into the document, in first-seen order: those of
     * the header params, the scan settings and the chromatograms (their type, intensity unit, params and first
     * precursor). Array keys are not accessions: a key outside {@link CvTerms#ARRAY_TYPES} is written as MS:1000786.
     */
    private Set<String> prefixes(FileMetadata m, FileMetadata.FileDescription d, MsRun run) {
        Set<String> used = new LinkedHashSet<>();
        Consumer<List<Param>> add = params -> params.forEach(p -> {
            used.add(prefix(p.accession()));
            used.add(prefix(p.unit()));
        });
        add.accept(d.contents());
        d.sourceFiles().forEach(f -> add.accept(f.parameters()));
        d.contacts().forEach(c -> add.accept(c.parameters()));
        m.samples().forEach(s -> add.accept(s.parameters()));
        m.software().forEach(s -> add.accept(s.parameters()));
        contents.scanSettings().forEach(s -> {
            add.accept(s.parameters());
            s.targets().forEach(add);
        });
        m.instrumentConfigurations().forEach(ic -> {
            add.accept(ic.parameters());
            ic.components().forEach(c -> add.accept(c.parameters()));
        });
        m.dataProcessingMethods().forEach(dp -> dp.methods().forEach(pm -> add.accept(pm.parameters())));
        add.accept(run.parameters());
        for (Chromatogram c : contents.chromatograms()) {
            add.accept(List.of(new Param(null, c.typeCurie(), null, c.intensityUnit())));
            add.accept(c.parameters());
            c.precursors().stream().limit(1).forEach(p -> {
                if (p.isolationWindow() != null) {
                    add.accept(p.isolationWindow().parameters());
                }
                p.selectedIons().forEach(ion -> add.accept(ion.parameters()));
                add.accept(p.activation().parameters());
            });
        }
        used.remove(null);
        return used;
    }

    /**
     * Step 2 of the registry: the cv list of the contents (entries without id dropped), then MS, UO and the other
     * prefixes in {@code used} that it lacks, in the order of {@link CvEntry#known()} and then as first used (A-17);
     * each with its id (rule NC, then the first free suffix, A-24) and a missing full name or URI completed.
     */
    private CVList cvList(Set<String> used) {
        List<CvEntry> entries = new ArrayList<>();
        for (CvEntry cv : contents.cvList()) {
            if (present(cv.id())) {
                entries.add(cv);
            } else {
                note(CV_WITHOUT_ID);
            }
        }
        int given = entries.size();
        Set<String> missing = new LinkedHashSet<>(List.of("MS", "UO"));
        missing.addAll(used);
        entries.forEach(cv -> missing.remove(cv.id()));
        for (CvEntry known : CvEntry.known()) {
            if (missing.remove(known.id())) {
                entries.add(known);
            }
        }
        missing.forEach(prefix -> entries.add(new CvEntry(prefix, null, null, null)));
        note(A_17, entries.size() - given);
        CVList list = new CVList();
        for (CvEntry entry : entries) {
            CvEntry fallback = KNOWN.getOrDefault(entry.id(), new CvEntry(entry.id(), entry.id(), "unknown", null));
            note(CV_COMPLETED, entry.fullName() == null || entry.uri() == null ? 1 : 0);
            CV cv = new CV();
            String id = MzMLTerms.ncName(entry.id());
            note(A_2, id.equals(entry.id()) ? 0 : 1);
            cv.setId(text(unique(id, A_24)));
            cv.setFullName(text(Objects.requireNonNullElse(entry.fullName(), fallback.fullName())));
            cv.setVersion(text(entry.version()));
            cv.setURI(uri(Objects.requireNonNullElse(entry.uri(), fallback.uri())));
            cvs.putIfAbsent(entry.id(), cv);
            list.getCv().add(cv);
        }
        list.setCount(entries.size());
        return list;
    }

    /**
     * Step 3 of the registry for one kind of header element: each id made an NCName (A-2), a null or empty one named
     * {@code fallback<i>} (A-21), then the first free suffix (A-21). The ids in list order, modifiable.
     */
    private <T> List<String> ids(List<T> items, Function<T, String> id, String fallback) {
        List<String> written = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String original = id.apply(items.get(i));
            String candidate;
            if (present(original)) {
                candidate = MzMLTerms.ncName(original);
                note(A_2, candidate.equals(original) ? 0 : 1);
            } else {
                candidate = fallback + i;
                note(A_21);
            }
            written.add(unique(candidate, A_21));
        }
        return written;
    }

    /** The written id of each present original id of {@code items}, the first of a repeated one. */
    private static <T> Map<String, String> refMap(List<T> items, Function<T, String> id, List<String> written) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            String original = id.apply(items.get(i));
            if (present(original)) {
                map.putIfAbsent(original, written.get(i));
            }
        }
        return map;
    }

    /** {@code candidate}, else the first free suffix of it, which is noted as {@code kind}; taken in the xs:ID set. */
    private String unique(String candidate, String kind) {
        String id = MzMLTerms.uniqueId(candidate, xmlIds);
        note(kind, id.equals(candidate) ? 0 : 1);
        return id;
    }

    /**
     * Step 5 of the registry: the ids of spectra (with the schema's pattern) or chromatograms, the first at mzML index
     * {@code first}, each made XML-safe; one that is null, empty, breaks the pattern or is taken becomes
     * {@code index=<i>} (A-3).
     */
    private List<String> entityIds(List<String> originals, int first, boolean pattern) {
        List<String> written = new ArrayList<>();
        for (int i = 0; i < originals.size(); i++) {
            String id = null;
            if (present(originals.get(i))) {
                MzMLTerms.Repaired safe = MzMLTerms.xmlSafe(originals.get(i));
                if ((!pattern || MzMLTerms.spectrumIdValid(safe.value())) && entityIds.add(safe.value())) {
                    id = safe.value();
                    note(A_28, safe.repaired() ? 1 : 0);
                }
            }
            if (id == null) {
                id = MzMLTerms.repairedId(first + i, entityIds);
                note(A_3);
            }
            written.add(id);
        }
        return written;
    }

    private FileDescription fileDescription(FileMetadata.FileDescription d, List<String> ids) {
        FileDescription x = new FileDescription();
        x.setFileContent(new ParamGroup());
        params(x.getFileContent(), d.contents());
        if (!ids.isEmpty()) {
            SourceFileList list = new SourceFileList();
            for (int i = 0; i < ids.size(); i++) {
                FileMetadata.SourceFile f = d.sourceFiles().get(i);
                SourceFile file = new SourceFile();
                file.setId(text(ids.get(i)));
                file.setName(text(Objects.requireNonNullElse(f.name(), "")));
                file.setLocation(uri(Objects.requireNonNullElse(f.location(), "")));
                params(file, f.parameters());
                list.getSourceFile().add(file);
            }
            list.setCount(ids.size());
            x.setSourceFileList(list);
        }
        for (FileMetadata.Contact c : d.contacts()) {
            ParamGroup contact = new ParamGroup();
            if (c.name() != null) {
                param(contact, new Param(null, "MS:1000586", c.name(), null));
            }
            if (c.affiliation() != null) {
                param(contact, new Param(null, "MS:1000590", c.affiliation(), null));
            }
            params(contact, c.parameters());
            x.getContact().add(contact);
        }
        return x;
    }

    private SoftwareList softwareList(List<FileMetadata.Software> model, List<String> ids) {
        SoftwareList list = new SoftwareList();
        for (int i = 0; i < ids.size(); i++) {
            Software x = new Software();
            x.setId(text(ids.get(i)));
            if (model.isEmpty()) {
                x.setVersion(text("unknown"));
                param(x, new Param(null, "MS:1000799", "mzPeakJ", null));
            } else {
                x.setVersion(text(Objects.requireNonNullElse(model.get(i).version(), "")));
                params(x, model.get(i).parameters());
            }
            list.getSoftware().add(x);
        }
        list.setCount(ids.size());
        return list;
    }

    private ScanSettingsList scanSettingsList(List<String> ids) {
        ScanSettingsList list = new ScanSettingsList();
        for (int i = 0; i < ids.size(); i++) {
            ScanSettings s = contents.scanSettings().get(i);
            uk.ac.ebi.jmzml.model.mzml.ScanSettings x = new uk.ac.ebi.jmzml.model.mzml.ScanSettings();
            x.setId(text(ids.get(i)));
            params(x, s.parameters());
            SourceFileRefList refs = new SourceFileRefList();
            for (String ref : s.sourceFileRefs()) {
                String id = ref(sourceFileIds, ref, "scanSettings sourceFileRef dropped");
                if (id != null) {
                    SourceFileRef r = new SourceFileRef();
                    r.setRef(text(id));
                    refs.getSourceFileRef().add(r);
                }
            }
            if (!refs.getSourceFileRef().isEmpty()) {
                refs.setCount(refs.getSourceFileRef().size());
                x.setSourceFileRefList(refs);
            }
            if (!s.targets().isEmpty()) {
                TargetList targets = new TargetList();
                for (List<Param> target : s.targets()) {
                    ParamGroup t = new ParamGroup();
                    params(t, target);
                    targets.getTarget().add(t);
                }
                targets.setCount(s.targets().size());
                x.setTargetList(targets);
            }
            list.getScanSettings().add(x);
        }
        list.setCount(ids.size());
        return list;
    }

    /**
     * The instrument configurations (a synthesized one without params when the model has none): components grouped
     * as mzML holds them, sources, analyzers, detectors, those of type UNKNOWN dropped (A-12), a list lacking one of
     * the three dropped (A-12); the software reference written, a missing one as the first software's (A-25).
     */
    private InstrumentConfigurationList instrumentList(List<FileMetadata.InstrumentConfiguration> model,
                                                       List<String> ids) {
        InstrumentConfigurationList list = new InstrumentConfigurationList();
        for (int i = 0; i < ids.size(); i++) {
            InstrumentConfiguration x = new InstrumentConfiguration();
            x.setId(text(ids.get(i)));
            String software = null;
            if (!model.isEmpty()) {
                FileMetadata.InstrumentConfiguration ic = model.get(i);
                params(x, ic.parameters());
                x.setComponentList(components(ic.components()));
                software = ref(softwareIds, ic.softwareReference(),
                        "instrumentConfiguration softwareRef redirected to the first software");
                note(A_25, ic.softwareReference() == null ? 1 : 0);
            }
            x.setSoftwareRef(text(Objects.requireNonNullElse(software, firstSoftware)));
            list.getInstrumentConfiguration().add(x);
        }
        list.setCount(ids.size());
        return list;
    }

    private ComponentList components(List<FileMetadata.Component> model) {
        ComponentList list = new ComponentList();
        boolean complete = true;
        for (Map.Entry<ComponentType, Supplier<Component>> kind : COMPONENTS.entrySet()) {
            int before = list.getComponents().size();
            for (FileMetadata.Component c : model) {
                if (c.componentType() == kind.getKey()) {
                    Component x = kind.getValue().get();
                    x.setOrder(c.order());
                    params(x, c.parameters());
                    list.getComponents().add(x);
                }
            }
            complete &= list.getComponents().size() > before;
        }
        note(A_12_UNKNOWN, model.size() - list.getComponents().size());
        if (list.getComponents().isEmpty()) {
            return null;
        }
        if (!complete) {
            note(A_12_LIST);
            return null;
        }
        list.setCount(list.getComponents().size());
        return list;
    }

    /** The data processings (a synthesized one when the model has none); one without methods gets MS:1000544. */
    private DataProcessingList dataProcessingList(List<FileMetadata.DataProcessing> model, List<String> ids) {
        DataProcessingList list = new DataProcessingList();
        for (int i = 0; i < ids.size(); i++) {
            DataProcessing x = new DataProcessing();
            x.setId(text(ids.get(i)));
            List<FileMetadata.ProcessingMethod> methods = model.isEmpty() ? List.of() : model.get(i).methods();
            for (FileMetadata.ProcessingMethod method : methods) {
                ProcessingMethod pm = new ProcessingMethod();
                pm.setOrder(method.order());
                String software = ref(softwareIds, method.softwareReference(),
                        "processingMethod softwareRef redirected to the first software");
                if (method.softwareReference() == null) {
                    note(String.format(A_8, "processingMethod softwareRef (the first software)"));
                }
                pm.setSoftwareRef(text(Objects.requireNonNullElse(software, firstSoftware)));
                params(pm, method.parameters());
                x.getProcessingMethod().add(pm);
            }
            if (methods.isEmpty()) {
                if (!model.isEmpty()) {
                    note(String.format(A_8, "processingMethod MS:1000544 of a data processing without methods"));
                }
                ProcessingMethod pm = new ProcessingMethod();
                pm.setOrder(0);
                pm.setSoftwareRef(text(firstSoftware));
                param(pm, new Param(null, "MS:1000544", null, null));
                x.getProcessingMethod().add(pm);
            }
            list.getDataProcessing().add(x);
        }
        list.setCount(ids.size());
        return list;
    }

    /**
     * The run: its id, default instrument configuration (else the first, A-8 or A-18), default source file (dropped
     * when it names none, A-18), start time (A-10, A-26) and params, and an empty spectrumList whose default data
     * processing is the run's, else the first (A-8 or A-18).
     */
    private Run run(MsRun model, String id, String firstInstrument, String firstProcessing) {
        Run run = new Run();
        run.setId(text(id));
        String instrument = instrumentIds.get(model.defaultInstrumentId());
        if (instrument == null) {
            note(model.defaultInstrumentId() == null
                    ? String.format(A_8, "run defaultInstrumentConfigurationRef (the first instrument configuration)")
                    : String.format(A_18, "run defaultInstrumentConfigurationRef redirected to the first instrument"
                    + " configuration"));
        }
        run.setDefaultInstrumentConfigurationRef(text(Objects.requireNonNullElse(instrument, firstInstrument)));
        defaultSourceFile = ref(sourceFileIds, model.defaultSourceFileId(), "run defaultSourceFileRef dropped");
        run.setDefaultSourceFileRef(text(defaultSourceFile));
        run.setStartTimeStamp(startTime(model.startTime()));
        params(run, model.parameters());
        String processing = ref(dataProcessingIds, model.defaultDataProcessingId(),
                "spectrumList defaultDataProcessingRef redirected to the first data processing");
        if (model.defaultDataProcessingId() == null) {
            note(String.format(A_8, "spectrumList defaultDataProcessingRef (the first data processing)"));
        }
        spectrumListDefault = Objects.requireNonNullElse(processing, firstProcessing);
        SpectrumList spectra = new SpectrumList();
        spectra.setCount(0);
        spectra.setDefaultDataProcessingRef(text(spectrumListDefault));
        run.setSpectrumList(spectra);
        return run;
    }

    /**
     * The run start time as jmzML will write it: null for none and for text that is no xs:dateTime (A-10); a time
     * jmzML's {@code CalendarAdapter} writes differently, which {@code DatatypeConverter} shows, is noted (A-26).
     */
    private Calendar startTime(String text) {
        if (text == null) {
            return null;
        }
        if (!MzMLTerms.dateTimeValid(text)) {
            note(A_10);
            return null;
        }
        Calendar time = DatatypeConverter.parseDateTime(text);
        String written = DatatypeConverter.printDateTime(time);
        if (!written.equals(text)) {
            note(String.format(A_26, text, written));
        }
        return time;
    }

    // ---- finish: the chromatograms (design 5.1, 5.8) -------------------------------------------------------------

    /**
     * The chromatogramList, none without chromatograms: its default data processing is the one every chromatogram
     * names, else the spectrumList's; a chromatogram whose own (else the spectrumList's) differs names it.
     */
    private void chromatograms(Run run) {
        List<Chromatogram> model = contents.chromatograms();
        if (model.isEmpty()) {
            return;
        }
        List<String> refs = new ArrayList<>();
        for (Chromatogram c : model) {
            refs.add(ref(dataProcessingIds, c.dataProcessingRef(), "chromatogram dataProcessingRef dropped"));
        }
        String common = refs.get(0);
        for (String ref : refs) {
            common = Objects.equals(ref, common) ? common : null;
        }
        String listDefault = Objects.requireNonNullElse(common, spectrumListDefault);
        ChromatogramList list = new ChromatogramList();
        for (int i = 0; i < model.size(); i++) {
            list.getChromatogram().add(chromatogram(i, model.get(i),
                    Objects.requireNonNullElse(refs.get(i), spectrumListDefault), listDefault));
        }
        list.setCount(model.size());
        list.setDefaultDataProcessingRef(text(listDefault));
        run.setChromatogramList(list);
    }

    private uk.ac.ebi.jmzml.model.mzml.Chromatogram chromatogram(int index, Chromatogram c, String processing,
                                                                 String listDefault) {
        uk.ac.ebi.jmzml.model.mzml.Chromatogram x = new uk.ac.ebi.jmzml.model.mzml.Chromatogram();
        x.setId(text(chromatogramIds.get(index)));
        x.setIndex(index);
        x.setDefaultArrayLength(c.time().length);
        if (!processing.equals(listDefault)) {
            x.setDataProcessingRef(text(processing));
        }
        if (c.typeCurie() != null) {
            param(x, new Param(null, c.typeCurie(), null, null));
        }
        polarity(x, c.polarity());
        params(x, c.parameters());
        if (!c.precursors().isEmpty()) {
            note(A_11, c.precursors().size() > 1 ? 1 : 0);
            Precursor p = c.precursors().get(0);
            note(A_19, p.precursorIndex() == null ? 0 : 1);
            x.setPrecursor(precursor(p));
        }
        BinaryDataArrayList arrays = new BinaryDataArrayList();
        arrays.getBinaryDataArray().add(array(TIME_ARRAY, c.time(), CvTerms.DEFAULT_ARRAY_UNITS.get(TIME_ARRAY)));
        arrays.getBinaryDataArray().add(array(INTENSITY_ARRAY, c.intensity(), c.intensityUnit()));
        c.arrays().forEach((key, values) -> arrays.getBinaryDataArray()
                .add(array(key, values, CvTerms.DEFAULT_ARRAY_UNITS.get(key))));
        arrays.setCount(arrays.getBinaryDataArray().size());
        x.setBinaryDataArrayList(arrays);
        return x;
    }

    // ---- precursors (design 5.2, 5.6) ----------------------------------------------------------------------------

    /**
     * A precursor: its spectrum reference as an {@code externalSpectrumID} with the run's default source file; the
     * isolation window (omitted when it holds nothing), the selected ions (the list omitted when empty), the
     * activation (always), each with its promoted terms first.
     */
    private uk.ac.ebi.jmzml.model.mzml.Precursor precursor(Precursor p) {
        uk.ac.ebi.jmzml.model.mzml.Precursor x = new uk.ac.ebi.jmzml.model.mzml.Precursor();
        if (p.precursorId() != null) {
            x.setExternalSpectrumID(text(p.precursorId()));
            x.setSourceFileRef(text(defaultSourceFile));
        }
        IsolationWindow w = p.isolationWindow();
        if (w != null) {
            ParamGroup window = new ParamGroup();
            scalar(window, "MS:1000827", w.targetMz(), MZ);
            scalar(window, "MS:1000828", w.lowerOffset(), MZ);
            scalar(window, "MS:1000829", w.upperOffset(), MZ);
            params(window, w.parameters());
            if (!window.getCvParam().isEmpty() || !window.getUserParam().isEmpty()) {
                x.setIsolationWindow(window);
            }
        }
        if (!p.selectedIons().isEmpty()) {
            SelectedIonList ions = new SelectedIonList();
            for (SelectedIon ion : p.selectedIons()) {
                ParamGroup g = new ParamGroup();
                scalar(g, CvTerms.SELECTED_ION_MZ, Double.isNaN(ion.mz()) ? null : ion.mz(), MZ);
                scalar(g, CvTerms.CHARGE_STATE, ion.charge(), null);
                scalar(g, CvTerms.SELECTED_ION_INTENSITY, ion.intensity(), "MS:1000131");
                params(g, ordered(ion.parameters(), q -> CvTerms.ION_MOBILITY_TYPES.contains(q.accession())));
                ions.getSelectedIon().add(g);
            }
            ions.setCount(p.selectedIons().size());
            x.setSelectedIonList(ions);
        }
        ParamGroup activation = new ParamGroup();
        params(activation, ordered(p.activation().parameters(),
                q -> CvTerms.DISSOCIATION_METHODS.containsKey(q.accession()),
                q -> CvTerms.COLLISION_ENERGY.equals(q.accession())));
        x.setActivation(activation);
        return x;
    }

    /** {@code params} with those that pass the first test first, then the second's, ..., then the rest; stable. */
    @SafeVarargs
    private static List<Param> ordered(List<Param> params, Predicate<Param>... first) {
        List<Param> rest = new ArrayList<>(params);
        List<Param> out = new ArrayList<>();
        for (Predicate<Param> test : first) {
            for (Iterator<Param> it = rest.iterator(); it.hasNext(); ) {
                Param p = it.next();
                if (test.test(p)) {
                    out.add(p);
                    it.remove();
                }
            }
        }
        out.addAll(rest);
        return out;
    }

    // ---- arrays (design 5.7) -------------------------------------------------------------------------------------

    /**
     * One array, encoded by jmzML as 64-bit floats, zlib-compressed unless empty (guard 1: jmzML's encoder never
     * returns on an empty array with zlib); then its type, or MS:1000786 named {@code key}, with {@code unit}.
     * {@code arrayLength} is never written; {@code encodedLength} is the Base64 length of the encoded bytes.
     */
    private BinaryDataArray array(String key, double[] values, String unit) {
        BinaryDataArray a = new BinaryDataArray();
        a.set64BitFloatArrayAsBinaryData(values, zlib && values.length > 0, cvs.get("MS"));
        boolean typed = CvTerms.ARRAY_TYPES.containsKey(key);
        param(a, new Param(null, typed ? key : NON_STANDARD_ARRAY, typed ? null : key, unit));
        a.setEncodedLength(4 * ((a.getBinary().length + 2) / 3));
        return a;
    }

    // ---- params and text (design 5.3) ----------------------------------------------------------------------------

    private void params(ParamGroup group, List<Param> params) {
        params.forEach(p -> param(group, p));
    }

    /** A cvParam for a param with accession, else a userParam for one with name, else nothing (warned). */
    private void param(ParamGroup group, Param p) {
        if (present(p.accession())) {
            CVParam c = new CVParam();
            c.setCvRef(text(cvs.get(prefix(p.accession())).getId()));
            c.setAccession(text(p.accession()));
            c.setValue(text(MzMLTerms.format(p.value())));
            String name = p.name() != null ? p.name() : MzMLTerms.name(p.accession());
            if (name == null) {
                note(NAMED_BY_ACCESSION);
            }
            c.setName(text(Objects.requireNonNullElse(name, p.accession())));
            unit(p.unit(), c::setUnitAccession, c::setUnitName, c::setUnitCvRef);
            group.getCvParam().add(c);
        } else if (p.name() != null) {
            UserParam u = new UserParam();
            u.setName(text(p.name()));
            u.setType(text(MzMLTerms.userParamType(p.value())));
            u.setValue(text(MzMLTerms.format(p.value())));
            unit(p.unit(), u::setUnitAccession, u::setUnitName, u::setUnitCvRef);
            group.getUserParam().add(u);
        } else {
            note(PARAM_SKIPPED);
        }
    }

    /** A promoted value as a cvParam named by {@link MzMLTerms}, none for null. */
    private void scalar(ParamGroup group, String accession, Number value, String unit) {
        if (value != null) {
            param(group, new Param(null, accession, value, unit));
        }
    }

    private void polarity(ParamGroup group, Polarity polarity) {
        String term = POLARITY_TERMS.get(polarity);
        if (term != null) {
            param(group, new Param(null, term, null, null));
        }
    }

    private void unit(String unit, Consumer<String> accession, Consumer<String> name, Consumer<String> cvRef) {
        if (present(unit)) {
            accession.accept(text(unit));
            name.accept(text(MzMLTerms.name(unit)));
            cvRef.accept(text(cvs.get(prefix(unit)).getId()));
        }
    }

    /** The written id {@code ref} names in {@code ids}: null for null and for one that names nothing (A-18). */
    private String ref(Map<String, String> ids, String ref, String what) {
        if (ref == null) {
            return null;
        }
        String id = ids.get(ref);
        if (id == null) {
            note(String.format(A_18, what));
        }
        return id;
    }

    /**
     * The text of every string handed to jmzML: XML-safe (A-28), counted when it holds tab, LF or CR (A-27) or, after
     * that repair, a character outside ASCII (A-29); null for null.
     */
    private String text(String value) {
        if (value == null) {
            return null;
        }
        MzMLTerms.Repaired safe = MzMLTerms.xmlSafe(value);
        note(A_28, safe.repaired() ? 1 : 0);
        note(A_27, TAB_LF_CR.matcher(safe.value()).find() ? 1 : 0);
        note(A_29, NON_ASCII.matcher(safe.value()).find() ? 1 : 0);
        return safe.value();
    }

    /** An xs:anyURI value: {@link #text}, then rule URI (A-22). */
    private String uri(String value) {
        MzMLTerms.Repaired uri = MzMLTerms.anyUri(text(value));
        note(A_22, uri.repaired() ? 1 : 0);
        return uri.value();
    }

    /** The CV prefix of a CURIE: the text before its first colon, else the whole; null for null and empty. */
    private static String prefix(String curie) {
        if (!present(curie)) {
            return null;
        }
        int colon = curie.indexOf(':');
        return colon > 0 ? curie.substring(0, colon) : curie;
    }

    /** Whether {@code s} is neither null nor empty (an empty id is treated as none, design 5.2). */
    private static boolean present(String s) {
        return s != null && !s.isEmpty();
    }

    private void note(String kind) {
        losses.note(kind);
    }

    private void note(String kind, long n) {
        losses.note(kind, n);
    }

    private static Map<String, CvEntry> known() {
        Map<String, CvEntry> known = new HashMap<>();
        CvEntry.known().forEach(cv -> known.put(cv.id(), cv));
        return known;
    }

    private static Map<ComponentType, Supplier<Component>> components() {
        Map<ComponentType, Supplier<Component>> kinds = new LinkedHashMap<>();
        kinds.put(ComponentType.ION_SOURCE, SourceComponent::new);
        kinds.put(ComponentType.ANALYZER, AnalyzerComponent::new);
        kinds.put(ComponentType.DETECTOR, DetectorComponent::new);
        return Collections.unmodifiableMap(kinds);
    }
}
