package org.mzpeak.mzml;

import org.mzpeak.io.MzPeakException;
import org.mzpeak.io.MzPeakManifest;
import org.mzpeak.model.Activation;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.CvTerms;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.Component;
import org.mzpeak.model.meta.FileMetadata.ComponentType;
import org.mzpeak.model.meta.FileMetadata.Contact;
import org.mzpeak.model.meta.FileMetadata.DataProcessing;
import org.mzpeak.model.meta.FileMetadata.FileDescription;
import org.mzpeak.model.meta.FileMetadata.InstrumentConfiguration;
import org.mzpeak.model.meta.FileMetadata.MsRun;
import org.mzpeak.model.meta.FileMetadata.ProcessingMethod;
import org.mzpeak.model.meta.FileMetadata.Sample;
import org.mzpeak.model.meta.FileMetadata.Software;
import org.mzpeak.model.meta.FileMetadata.SourceFile;
import org.mzpeak.model.meta.ScanSettings;
import uk.ac.ebi.jmzml.model.mzml.BinaryDataArray;
import uk.ac.ebi.jmzml.model.mzml.BinaryDataArrayList;
import uk.ac.ebi.jmzml.model.mzml.CVList;
import uk.ac.ebi.jmzml.model.mzml.CVParam;
import uk.ac.ebi.jmzml.model.mzml.ComponentList;
import uk.ac.ebi.jmzml.model.mzml.DataProcessingList;
import uk.ac.ebi.jmzml.model.mzml.InstrumentConfigurationList;
import uk.ac.ebi.jmzml.model.mzml.MzMLObject;
import uk.ac.ebi.jmzml.model.mzml.ParamGroup;
import uk.ac.ebi.jmzml.model.mzml.PrecursorList;
import uk.ac.ebi.jmzml.model.mzml.ReferenceableParamGroup;
import uk.ac.ebi.jmzml.model.mzml.ReferenceableParamGroupList;
import uk.ac.ebi.jmzml.model.mzml.SampleList;
import uk.ac.ebi.jmzml.model.mzml.Scan;
import uk.ac.ebi.jmzml.model.mzml.ScanList;
import uk.ac.ebi.jmzml.model.mzml.ScanSettingsList;
import uk.ac.ebi.jmzml.model.mzml.ScanWindowList;
import uk.ac.ebi.jmzml.model.mzml.SelectedIonList;
import uk.ac.ebi.jmzml.model.mzml.SoftwareList;
import uk.ac.ebi.jmzml.model.mzml.SourceFileList;
import uk.ac.ebi.jmzml.model.mzml.SourceFileRef;
import uk.ac.ebi.jmzml.model.mzml.SourceFileRefList;
import uk.ac.ebi.jmzml.model.mzml.TargetList;
import uk.ac.ebi.jmzml.model.mzml.UserParam;
import uk.ac.ebi.jmzml.xml.io.MzMLObjectIterator;
import uk.ac.ebi.jmzml.xml.io.MzMLUnmarshaller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;

/**
 * Reads an mzML 1.1 document, bare or wrapped in {@code indexedmzML}, into mzPeakJ's model through jmzML 1.7.11 as is
 * (mzML design 3, 5, 8.1): one {@code MzMLUnmarshaller} per reader, created by the first call of any method, indexes
 * the whole file and notes the limit lines {@link #LIMITS} (design 6.0). Params come in jmzML's order: an element's
 * cvParams, then those of its referenced groups, then its userParams and its groups' (design 5.3, Q41); values are
 * typed by {@link MzMLTerms#value}. What the model cannot hold is counted in {@link #warnings()} (design 6.1). The
 * input index and checksum are not read.
 *
 * <p>{@link #header()} unmarshals the cv list, the file description (contents, source files, contacts), samples,
 * software, scan settings, instrument configurations and data processings, and takes the attributes of the run and of
 * its spectrumList and chromatogramList as written in their start tags (design 5.0, 5.1): a character or entity
 * reference in them is not resolved, and a value containing {@code &} is counted (B-40). A chromatogram without a data
 * processing of its own takes the {@code chromatogramList}'s default. The run's params are not read (L-2). Ids are kept
 * as jmzML gives them.
 *
 * <p>{@link #survey()} and {@link #read(Visitor)} take the spectra and then the chromatograms one at a time (design
 * 5.4-5.9). {@code read} maps every spectrum, wavelength spectrum and chromatogram to the model and hands it to a
 * {@link Visitor}, with its arrays decoded by jmzML's codec; {@code survey} runs the same mapping without decoding
 * arrays. Both take every array through one classification by its attributes and terms only, before any array of its
 * element is decoded (design 8.1), so the extra array keys {@code survey} declares are exactly those {@code read}
 * emits.
 *
 * <p>Invalid input is rejected with an {@link MzPeakException} naming the file, without line or column (design 6.3,
 * 10; L-6). Before jmzML is called: a file name ending in {@code .gz} in any case (jmzML 1.7.11 cannot read gzip) and
 * a path that is not a readable regular file. What jmzML cannot index (tags that do not match, no {@code mzML} root,
 * a UTF-16 or UTF-32 document, for which jmzML reports an "Invalid ID in xml") fails with
 * {@code mzML <file>: <message>}. {@code header} rejects an mzML version that does not start with
 * {@code 1.1} and a document without {@code run}; a failure while a header list is read or mapped (a
 * {@code referenceableParamGroupRef} naming no group, an attribute that is no integer) is
 * {@code mzML <file>: <list>: <message>}. In the run, everything is named by its element,
 * {@code mzML <file>: spectrum #<n> (<id>): <message>} (or {@code chromatogram}), with {@code n} its 0-based position
 * and the id once jmzML returned the element, and an exception of jmzML, JAXB or the model as its cause.
 * {@code survey} also rejects what the model or jmzML cannot carry: a non-standard data array (MS:1000786) whose name
 * is the accession of an array type; an array compressed with MS:1002746, MS:1002747 or MS:1002748 (MS-Numpress and
 * zlib in one term, which jmzML cannot decode); a data type or compression jmzML cannot decode (MS:1000520 16-bit
 * float, zstd, none), unless a term jmzML knows is present too: then jmzML decodes by that term, and the other is
 * neither checked nor warned about (a second data type term counts as a lost array param, B-21); in the first usable
 * (typed, not ASCII) array of each main key of a spectrum or chromatogram, the only one whose unit is checked, an m/z
 * array in a unit other than MS:1000040, an intensity array of a spectrum in a unit other than MS:1000131, a
 * wavelength array in a unit other than UO:0000018 and a time array in a unit other than minute, second, millisecond
 * or hour; a scan start time in a unit other than those four; and m/z and intensity arrays (wavelength or time and
 * intensity arrays) of different declared lengths. A further array of a main key is dropped (B-29) without a unit
 * check. The unit of an axis or intensity array is checked even when the other one is missing and both would be
 * dropped (B-9). {@code read} also rejects an array jmzML cannot decode, among them an MS-Numpress linear array with
 * an empty or header-only payload and an empty SLOF payload (jmzML's decoder throws NegativeArraySizeException), an
 * MS-Numpress PIC count of 2^31 or more (jmzML decodes it as a negative number) and main arrays that decode to
 * different lengths; declared lengths are not checked against the decoded ones (L-6). An empty array under no
 * compression, zlib or MS-Numpress PIC, or an SLOF payload that is its header only, is read as an empty array.
 */
public final class MzMLReader {

    /**
     * The limit lines every reader notes when it opens the file (design 6.0), before its loss lines:
     * <ul>
     *   <li>L-1: the whole input is indexed before the first element is read, and the index stays in memory; two
     *       file handles stay open until the garbage collector finalizes jmzML's objects;
     *   <li>L-2: run-level params are not read;
     *   <li>L-3: C1 control characters in the input are read as U+FFFD;
     *   <li>L-4: userParam units are not read;
     *   <li>L-5: a file whose XML declaration names no encoding is read as US-ASCII;
     *   <li>L-6: the input is taken as jmzML and JAXB read it (lenient XML and Base64, zlib payloads with a preset
     *       dictionary or without bound, declared array lengths unchecked, missing integers read as 0, no line or
     *       column in errors, xxindex's tag stack on stdout).
     * </ul>
     */
    public static final List<String> LIMITS = List.of(
            "L-1 the whole input is indexed before the first element is read, and the index stays in memory while the"
                    + " reader lives (about 0.3-0.5 KB per spectrum; a byte scan of the whole file at about 60 MB/s);"
                    + " the reader keeps two file handles on the input open until the garbage collector finalizes"
                    + " jmzML's objects (never with finalization disabled), so on Windows the input cannot be moved or"
                    + " deleted meanwhile",
            "L-2 run-level params (cvParam, userParam, referenceableParamGroupRef directly under run) are not read",
            "L-3 C1 control characters U+0080-U+009F in the input are read as U+FFFD",
            "L-4 userParam units (unitAccession, unitName, unitCvRef) are not read",
            "L-5 a file whose XML declaration names no encoding is read as US-ASCII: each byte >= 0x80 becomes U+FFFD",
            "L-6 the input is taken as jmzML and JAXB read it: elements the schema does not allow are skipped; of two"
                    + " singleton children the last is kept; Base64 is decoded leniently (characters outside the"
                    + " alphabet skipped, an unpadded final group dropped, misplaced padding decoded into other bytes);"
                    + " a truncated zlib payload gives a shorter array; a zlib payload with a preset dictionary is"
                    + " never decoded (the conversion does not end) and a zlib payload is inflated without bound (a zip"
                    + " bomb ends in OutOfMemoryError); declared array lengths are not checked against the decoded"
                    + " ones; a missing integer attribute reads as 0; refs are not whitespace-collapsed; errors carry"
                    + " no line or column, and an error found while the index is built names no element; xxindex"
                    + " prints its tag stack to stdout when tags do not match");

    // The losses of design 6.1 this reader counts, one line each in warnings().
    static final String B_1 = "B-1 referenceableParamGroups expanded inline";
    static final String B_3 = "B-3 mzML@id and mzML@accession lost";
    static final String B_5 = "B-5 instrumentConfiguration ids %s numbered by position";
    static final String B_6 = "B-6 instrumentConfiguration@scanSettingsRef lost";
    static final String B_7 = "B-7 run@sampleRef lost";
    static final String B_9 = "B-9 arrays of spectra and chromatograms without an axis or intensity array dropped";
    static final String B_10 = "B-10 spectrum@%s lost";
    static final String B_11 = "B-11 MS spectra after wavelength spectra, their interleaving lost";
    static final String B_13 = "B-13 scanList params other than the combination term moved to the spectrum";
    static final String B_14_TYPE = "B-14 userParam values that do not match their type kept as text";
    static final String B_15 = "B-15 times converted to minutes and drift times to milliseconds";
    static final String B_16 = "B-16 sourceFileRef of scans and precursors lost";
    static final String B_17_WINDOW = "B-17 scan windows without both limits in m/z dropped";
    static final String B_17_PARAMS = "B-17 scan window params other than the limits lost";
    static final String B_18 = "B-18 selected ions without an m/z in unit MS:1000040, which the mzPeak read drops";
    static final String B_20 = "B-20 spectrum productList lost";
    static final String B_21_PROCESSING = "B-21 lost array dataProcessingRef";
    static final String B_21_PARAMS = "B-21 lost array params other than array type, data type and compression";
    static final String B_21_UNIT = "B-21 lost declared unit of an array without default unit";
    static final String B_21_UNIT_DROP = "B-21 dropped array in a unit other than its default";
    static final String B_21_LENGTH = "B-21 dropped array whose declared length differs from its axis array's";
    static final String B_21_ASCII = "B-21 dropped null-terminated ASCII array";
    static final String B_21_UNTYPED = "B-21 dropped array without array type";
    static final String B_23 = "B-23 wavelength spectrum content other than the first scan's start time lost";
    static final String B_27 = "B-27 chromatogram products dropped";
    static final String B_28 = "B-28 extra arrays of wavelength spectra dropped";
    static final String B_29 = "B-29 second arrays of a type already present dropped";
    static final String B_30 = "B-30 cvRef aliases replaced by the accession prefix";
    static final String B_31 = "B-31 64-bit integer array values rounded to the nearest double";
    static final String B_33 = "B-33 spectrum references whose kind (local or external) changes on the way back";
    static final String B_39 = "B-39 instrument configuration refs that name no instrument configuration read as null";
    static final String B_40 = "B-40 run, spectrumList and chromatogramList attribute values containing & kept as"
            + " written";
    // Warned by design 5.4 and 5.5, without a loss number.
    static final String BOTH_POLARITIES = "spectra and chromatograms with both scan polarities read as polarity"
            + " unknown, the terms kept as params";
    static final String BOTH_REPRESENTATIONS = "spectra with both centroid and profile terms read as representation"
            + " unknown, the terms kept as params";
    static final String INJECTION_TIME_KEPT = "ion injection times not in milliseconds kept as scan params";

    private static final String CONTACT_NAME = "MS:1000586";
    private static final String CONTACT_AFFILIATION = "MS:1000590";

    private static final String MZ_ARRAY = "MS:1000514";
    private static final String INTENSITY_ARRAY = "MS:1000515";
    private static final String NON_STANDARD_ARRAY = "MS:1000786";
    /** 16-bit float, a data type jmzML cannot decode (its {@code getPrecision()} rejects it). */
    private static final String FLOAT16 = "MS:1000520";
    /** MS-Numpress positive integer compression, whose counts of 2^31 and above jmzML decodes negative. */
    private static final String PIC = "MS:1002313";
    /** The terms for MS-Numpress followed by zlib in one term, which jmzML cannot decode (guard 2). */
    private static final Set<String> NUMPRESS_ZLIB = Set.of("MS:1002746", "MS:1002747", "MS:1002748");
    private static final String MZ = "MS:1000040";
    private static final String NANOMETER = "UO:0000018";
    private static final String MINUTE = "UO:0000031";
    private static final String MILLISECOND = "UO:0000028";
    /** The spectrum types that make a spectrum without arrays a wavelength spectrum (design 5.4); null-tolerant. */
    private static final Set<String> WAVELENGTH_TYPES =
            Collections.unmodifiableSet(new HashSet<>(List.of("MS:1000804", "MS:1000805", "MS:1000806")));
    /** The spectrum attributes the model has no field for (B-10), in the order they are noted. */
    private static final List<String> SPECTRUM_LOST = List.of("spotID", "sourceFileRef", "dataProcessingRef");
    /** A conversion that leaves the values as they are. */
    private static final DoubleUnaryOperator AS_IS = v -> v;
    /**
     * Per model unit that values in other units are converted to (design 5.5, 5.7): times to minutes from seconds,
     * milliseconds and hours; drift times to milliseconds from seconds and minutes.
     */
    private static final Map<String, Map<String, DoubleUnaryOperator>> CONVERSIONS = Map.of(
            MINUTE, Map.of("UO:0000010", v -> v / 60, MILLISECOND, v -> v / 60_000, "UO:0000032", v -> v * 60),
            MILLISECOND, Map.of("UO:0000010", v -> v * 1000, MINUTE, v -> v * 60_000));

    private final Path mzml;
    private final Losses losses = new Losses();
    /** Where losses are noted: {@link #losses}, except while {@link #survey()} runs, which notes into a dropped one. */
    private Losses noted = losses;
    private Header header;
    /** The model id of each instrument configuration id (rule IC); filled by {@link #header()}. */
    private Map<String, Integer> icIds = Collections.emptyMap();
    /** jmzML's reader of the file and its index; created by the first call of any method. */
    private MzMLUnmarshaller unmarshaller;
    private Survey survey;
    /** The MS ordinal of each MS spectrum id, the first of repeated ids; filled by {@link #survey()}. */
    private Map<String, Long> msOrdinals = Collections.emptyMap();
    /** The ids of the wavelength spectra; filled by {@link #survey()}. */
    private Set<String> wavelengthIds = Collections.emptySet();
    /** True while {@link #survey()} runs the mapping: arrays are not decoded. */
    private boolean surveying;
    /** The list defaults of data processing, and the next MS, wavelength and chromatogram index of a pass. */
    private String spectrumListDefault;
    private String chromatogramListDefault;
    private long msIndex;
    private long wavelengthIndex;
    private long chromatogramIndex;
    /** Names the element a pass is at in errors: {@code mzML <file>: spectrum #<n> (<id>)}. */
    private String elementName;

    /**
     * A reader of the mzML file {@code mzml}; nothing is read before the first call.
     *
     * @throws NullPointerException if {@code mzml} is null
     */
    public MzMLReader(Path mzml) {
        this.mzml = Objects.requireNonNull(mzml, "mzml");
    }

    /**
     * The file-level content of the document, read on the first call and cached.
     *
     * @throws MzPeakException if the file cannot be opened or the document is invalid, as stated in the class
     *                         description
     */
    public Header header() {
        if (header == null) {
            header = readHeader(unmarshaller());
        }
        return header;
    }

    /**
     * What the run holds, found in one pass over the spectra and then the chromatograms that maps them as
     * {@link #read(Visitor)} does but decodes no array; read on the first call and cached. It notes no loss:
     * {@link #read(Visitor)} does. The header is read first.
     *
     * @throws MzPeakException if the document is invalid or holds what the model or jmzML cannot carry, as stated in
     *                         the class description, or cannot be read
     */
    public Survey survey() {
        if (survey == null) {
            header();
            Collector collector = new Collector();
            surveying = true;
            noted = new Losses();
            try {
                pass(collector);
            } finally {
                surveying = false;
                noted = losses;
            }
            Map<String, Long> ordinals = new HashMap<>();
            for (int i = 0; i < collector.spectrumIds.size(); i++) {
                ordinals.putIfAbsent(collector.spectrumIds.get(i), (long) i);
            }
            msOrdinals = ordinals;
            wavelengthIds = new HashSet<>(collector.wavelengthIds);
            survey = new Survey(List.copyOf(collector.spectrumIds), List.copyOf(collector.wavelengthIds),
                    Collections.unmodifiableSet(collector.arrays), collector.chromatograms);
        }
        return survey;
    }

    /**
     * Reads the run in one pass, the spectra and then the chromatograms, each in document order, and hands each MS
     * spectrum, wavelength spectrum and chromatogram to {@code visitor} as it is read (design 5.4-5.9), with its arrays
     * decoded; a precursor's spectrum reference is resolved through {@link #survey()}, which runs first if it has not.
     * What the model cannot hold is counted in {@link #warnings()}, on every call. An exception the visitor throws ends
     * the pass and propagates as it is.
     *
     * @throws MzPeakException      as {@link #survey()} does, for an array jmzML cannot decode, an MS-Numpress PIC
     *                              count of 2^31 or more, and main arrays that decode to different lengths
     * @throws NullPointerException if {@code visitor} is null
     */
    public void read(Visitor visitor) {
        Objects.requireNonNull(visitor, "visitor");
        survey();
        pass(visitor);
    }

    /**
     * What was lost so far: the limit lines {@link #LIMITS} once the file was opened (design 6.0), then one line per
     * kind of loss, {@code <kind> (<count>)}, in first-seen order (design 6.1).
     */
    public List<String> warnings() {
        return losses.lines();
    }

    /**
     * The file-level content of an mzML document.
     *
     * @param fileMetadata the file description, instrument configurations, data processings, samples, software and
     *                     run; the run's default data processing is the {@code spectrumList} default
     * @param scanSettings the scan settings, with their source file refs and targets
     * @param cvList       the {@code cvList} entries in document order, as given
     */
    public record Header(FileMetadata fileMetadata, List<ScanSettings> scanSettings,
                         List<MzPeakManifest.CvEntry> cvList) {
    }

    /**
     * What a full pass over the run finds without decoding arrays.
     *
     * @param spectrumIds           the ids of the MS spectra, in document order
     * @param wavelengthSpectrumIds the ids of the wavelength spectra, in document order
     * @param spectrumArrays        the extra array keys of the MS spectra, first seen first: the keys of
     *                              {@link Spectrum#arrays()} over the spectra {@link #read(Visitor)} gives
     * @param chromatograms         the number of chromatograms
     */
    public record Survey(List<String> spectrumIds, List<String> wavelengthSpectrumIds, Set<String> spectrumArrays,
                         int chromatograms) {
    }

    /** Receives the run's content in document order. */
    public interface Visitor {
        /** An MS spectrum; its index is its ordinal among the MS spectra; it has no peak list. */
        void spectrum(Spectrum spectrum);

        /** A wavelength spectrum; its index is its ordinal among the wavelength spectra. */
        void wavelengthSpectrum(WavelengthSpectrum spectrum);

        /** A chromatogram; its index is its ordinal. */
        void chromatogram(Chromatogram chromatogram);
    }

    // ---- opening the file and reading the header (design 5.0, 5.1, 6.3) --------------------------------------------

    /**
     * jmzML's reader of the file, created on the first call: a name ending in {@code .gz} and a path that is not a
     * readable regular file are rejected first; then jmzML indexes the whole file (L-1), and a failure while it builds
     * the index names the file only. Creating it notes the limit lines.
     */
    private MzMLUnmarshaller unmarshaller() {
        if (unmarshaller == null) {
            if (mzml.toString().toLowerCase(Locale.ROOT).endsWith(".gz")) {
                throw new MzPeakException("mzML " + mzml
                        + ": gzip-compressed mzML is not supported by jmzML 1.7.11 as is: gunzip it first");
            }
            if (!Files.isRegularFile(mzml) || !Files.isReadable(mzml)) {
                throw new MzPeakException("mzML " + mzml + ": not a readable regular file");
            }
            try {
                unmarshaller = new MzMLUnmarshaller(mzml.toFile(), false, null);
            } catch (RuntimeException e) {
                throw new MzPeakException("mzML " + mzml + ": " + e.getMessage(), e);
            }
            LIMITS.forEach(losses::limit);
        }
        return unmarshaller;
    }

    /**
     * The header as jmzML gives it (design 5.0, 5.1): the version and the run checked, the header lists unmarshalled
     * one by one, the run and list attributes as written in their start tags; the run's params are not read (L-2).
     */
    private Header readHeader(MzMLUnmarshaller u) {
        String version = u.getMzMLVersion();
        if (!Objects.requireNonNullElse(version, "").startsWith("1.1")) {
            throw new MzPeakException("mzML " + mzml + ": mzML version " + version
                    + " is not supported; version 1.1 is required");
        }
        if (u.getObjectCountForXpath("/run") < 1) {
            throw new MzPeakException("mzML " + mzml + ": no run");
        }
        for (String lost : Arrays.asList(u.getMzMLId(), u.getMzMLAccession())) {
            losses.note(B_3, lost == null ? 0 : 1);
        }
        losses.note(B_1, u.getObjectCountForXpath("/referenceableParamGroupList/referenceableParamGroup"));
        // read for their losses only: jmzML copies each group's params into the elements that reference it
        list("referenceableParamGroupList", ReferenceableParamGroupList.class, new ReferenceableParamGroupList(),
                groups -> groups.getReferenceableParamGroup().stream().map(this::groupParams).toList());
        List<MzPeakManifest.CvEntry> cvList = list("cvList", CVList.class, new CVList(), cvs -> cvs.getCv().stream()
                .map(cv -> new MzPeakManifest.CvEntry(cv.getId(), cv.getFullName(), cv.getURI(), cv.getVersion()))
                .toList());
        FileDescription description = list("fileDescription", uk.ac.ebi.jmzml.model.mzml.FileDescription.class,
                new uk.ac.ebi.jmzml.model.mzml.FileDescription(), this::fileDescription);
        List<Sample> samples = list("sampleList", SampleList.class, new SampleList(), l -> l.getSample().stream()
                .map(s -> new Sample(s.getId(), s.getName(), params(s))).toList());
        List<Software> software = list("softwareList", SoftwareList.class, new SoftwareList(), l -> l.getSoftware()
                .stream().map(s -> new Software(s.getId(), emptyToNull(s.getVersion()), params(s))).toList());
        List<ScanSettings> scanSettings = list("scanSettingsList", ScanSettingsList.class, new ScanSettingsList(),
                l -> l.getScanSettings().stream().map(this::scanSettings).toList());
        List<InstrumentConfiguration> instruments = list("instrumentConfigurationList",
                InstrumentConfigurationList.class, new InstrumentConfigurationList(), this::instrumentConfigurations);
        List<DataProcessing> dataProcessing = list("dataProcessingList", DataProcessingList.class,
                new DataProcessingList(), l -> l.getDataProcessing().stream().map(dp -> new DataProcessing(dp.getId(),
                        dp.getProcessingMethod().stream()
                                .map(m -> new ProcessingMethod(m.getOrder(), m.getSoftwareRef(), params(m)))
                                .toList())).toList());

        Map<String, String> run = u.getSingleElementAttributes("/run");
        Map<String, String> spectrumList = listAttributes(u, "/run/spectrumList");
        Map<String, String> chromatogramList = listAttributes(u, "/run/chromatogramList");
        for (Map<String, String> attributes : List.of(run, spectrumList, chromatogramList)) {
            losses.note(B_40, attributes.values().stream().filter(v -> v.contains("&")).count());
        }
        losses.note(B_7, run.get("sampleRef") == null ? 0 : 1);
        String defaultIc = run.get("defaultInstrumentConfigurationRef");
        Integer defaultInstrument = icIds.get(defaultIc);
        losses.note(B_39, defaultIc != null && defaultInstrument == null ? 1 : 0);
        chromatogramListDefault = chromatogramList.get("defaultDataProcessingRef");
        MsRun msRun = new MsRun(run.get("id"), defaultInstrument, spectrumList.get("defaultDataProcessingRef"),
                run.get("defaultSourceFileRef"), run.get("startTimeStamp"), List.of());
        return new Header(new FileMetadata(description, instruments, dataProcessing, samples, software, msRun),
                scanSettings, cvList);
    }

    /**
     * Maps jmzML's {@code type} at {@code /<name>} by {@code map}, or {@code empty} if the document has none; any
     * exception of jmzML, JAXB or the mapping names the list (design 8.1).
     */
    private <L extends MzMLObject, R> R list(String name, Class<L> type, L empty, Function<L, R> map) {
        try {
            L found = unmarshaller.unmarshalFromXpath("/" + name, type);
            return map.apply(found == null ? empty : found);
        } catch (RuntimeException e) {
            throw new MzPeakException("mzML " + mzml + ": " + name + ": " + e.getMessage(), e);
        }
    }

    /** The items of {@code list}, none for no list. */
    private static <L, E> List<E> items(L list, Function<L, List<E>> items) {
        return list == null ? List.of() : items.apply(list);
    }

    /**
     * The attributes of the list element at {@code xpath} as written in its start tag, also of an empty list; none if
     * the document has no such element, for which jmzML would throw.
     */
    private static Map<String, String> listAttributes(MzMLUnmarshaller u, String xpath) {
        return u.getObjectCountForXpath(xpath) > 0 ? u.getSingleElementAttributes(xpath) : Map.of();
    }

    /**
     * The params of a referenceableParamGroup, read once so that its aliases (B-30) and type mismatches (B-14) are
     * noted once, not for each element jmzML copies them into.
     */
    private List<Param> groupParams(ReferenceableParamGroup group) {
        ParamGroup params = new ParamGroup();
        params.getCvParam().addAll(group.getCvParam());
        params.getUserParam().addAll(group.getUserParam());
        return params(params);
    }

    /** The file description; an empty source file name or location is null (design 5.1), else verbatim. */
    private FileDescription fileDescription(uk.ac.ebi.jmzml.model.mzml.FileDescription d) {
        List<Param> contents = params(d.getFileContent());
        List<SourceFile> sourceFiles = items(d.getSourceFileList(), SourceFileList::getSourceFile).stream()
                .map(f -> new SourceFile(f.getId(), emptyToNull(f.getName()), emptyToNull(f.getLocation()), params(f)))
                .toList();
        return new FileDescription(contents, sourceFiles, d.getContact().stream().map(this::contact).toList());
    }

    /**
     * A contact: the first MS:1000586 (contact name) gives the name, the first MS:1000590 (whatever name the file gives
     * it) the affiliation, each its cvParam's value text by {@link #text}, so the contact's own before its groups'
     * (design 5.1, Q41). The other params stay params.
     */
    private Contact contact(ParamGroup contact) {
        List<Param> params = params(contact);
        List<Param> others = new ArrayList<>(params);
        Param.find(params, CONTACT_NAME).ifPresent(others::remove);
        Param.find(params, CONTACT_AFFILIATION).ifPresent(others::remove);
        return new Contact(text(contact, CONTACT_NAME), text(contact, CONTACT_AFFILIATION), others);
    }

    private ScanSettings scanSettings(uk.ac.ebi.jmzml.model.mzml.ScanSettings s) {
        List<String> sourceFileRefs = items(s.getSourceFileRefList(), SourceFileRefList::getSourceFileRef).stream()
                .map(SourceFileRef::getRef).toList();
        List<List<Param>> targets = items(s.getTargetList(), TargetList::getTarget).stream().map(this::params).toList();
        return new ScanSettings(s.getId(), params(s), sourceFileRefs, targets);
    }

    /** The instrument configurations, their model ids by rule IC (design 5.2), which the run refs map through. */
    private List<InstrumentConfiguration> instrumentConfigurations(InstrumentConfigurationList list) {
        List<uk.ac.ebi.jmzml.model.mzml.InstrumentConfiguration> ics = list.getInstrumentConfiguration();
        List<String> names = ics.stream().map(uk.ac.ebi.jmzml.model.mzml.InstrumentConfiguration::getId).toList();
        MzMLTerms.IcIds ids = MzMLTerms.icIds(names);
        if (!ids.exact()) {
            losses.note(String.format(B_5, names));
        }
        icIds = ids.map();
        List<InstrumentConfiguration> instruments = new ArrayList<>();
        for (int i = 0; i < ics.size(); i++) {
            uk.ac.ebi.jmzml.model.mzml.InstrumentConfiguration ic = ics.get(i);
            losses.note(B_6, ic.getScanSettingsRef() == null ? 0 : 1);
            ComponentList components = ic.getComponentList();
            List<Component> model = new ArrayList<>();
            components(model, ComponentType.ION_SOURCE, items(components, ComponentList::getSource));
            components(model, ComponentType.ANALYZER, items(components, ComponentList::getAnalyzer));
            components(model, ComponentType.DETECTOR, items(components, ComponentList::getDetector));
            instruments.add(new InstrumentConfiguration(ids.ids().get(i), List.copyOf(model), ic.getSoftwareRef(),
                    params(ic)));
        }
        return List.copyOf(instruments);
    }

    /** Adds {@code components} as components of {@code type} to {@code model}: jmzML holds each kind in a list. */
    private void components(List<Component> model, ComponentType type,
                            List<? extends uk.ac.ebi.jmzml.model.mzml.Component> components) {
        for (uk.ac.ebi.jmzml.model.mzml.Component component : components) {
            model.add(new Component(type, component.getOrder(), params(component)));
        }
    }

    // ---- params (design 5.3) ---------------------------------------------------------------------------------------

    /**
     * The params of {@code owner} as jmzML gives them (design 5.3, Q41): its cvParams, then those of its referenced
     * groups, then its userParams and its groups'; none for no {@code owner}. A {@code cvRef} other than the
     * accession's prefix is an alias, lost (B-30). A group's params were noted once, when {@link #header()} read the
     * group, so an alias (B-30) or type mismatch (B-14) among the copies jmzML puts into each referencing element is
     * not noted again.
     */
    private List<Param> params(ParamGroup owner) {
        List<Param> params = new ArrayList<>();
        if (owner != null) {
            for (CVParam p : owner.getCvParam()) {
                if (!p.isInferredFromReferenceableParamGroupRef() && !p.getAccession().startsWith(p.getCvRef() + ":")) {
                    note(B_30);
                }
                params.add(new Param(p.getName(), p.getAccession(), value(p.getValue(), null), p.getUnitAccession()));
            }
            for (UserParam p : owner.getUserParam()) {
                Object value = p.isInferredFromReferenceableParamGroupRef()
                        ? MzMLTerms.value(p.getValue(), p.getType()).value() : value(p.getValue(), p.getType());
                params.add(new Param(p.getName(), null, value, p.getUnitAccession()));
            }
        }
        return List.copyOf(params);
    }

    /**
     * The value text of the first cvParam of {@code owner} with {@code accession}, verbatim, in the order of
     * {@link #params(ParamGroup)} (its own before its groups'); null if there is none or it is empty.
     */
    private static String text(ParamGroup owner, String accession) {
        for (CVParam p : owner.getCvParam()) {
            if (p.getAccession().equals(accession)) {
                return emptyToNull(p.getValue());
            }
        }
        return null;
    }

    private Object value(String text, String type) {
        MzMLTerms.Typed typed = MzMLTerms.value(text, type);
        if (typed.typeMismatch()) {
            note(B_14_TYPE);
        }
        return typed.value();
    }

    /**
     * Removes the first param with {@code accession} from {@code params} and returns its value, if that is a number
     * and the param has the unit {@code unit} or none (a promoted scalar, design 5.5-5.9); else returns null and
     * leaves {@code params} as they are.
     */
    private static Double doubleParam(List<Param> params, String accession, String unit) {
        Param p = Param.find(params, accession).orElse(null);
        if (p != null && p.value() instanceof Number n && (p.unit() == null || p.unit().equals(unit))) {
            params.remove(p);
            return n.doubleValue();
        }
        return null;
    }

    /**
     * Removes the first param with {@code accession} from {@code params} and returns its value, if that is an integer
     * in int range; else returns null and leaves {@code params} as they are.
     */
    private static Integer intParam(List<Param> params, String accession) {
        Param p = Param.find(params, accession).orElse(null);
        if (p != null && p.value() instanceof Long n && n == n.intValue()) {
            params.remove(p);
            return n.intValue();
        }
        return null;
    }

    /**
     * The one of two exclusive terms {@code params} hold: {@code ifFirst} for {@code first}, {@code ifSecond} for
     * {@code second}, each removed from {@code params}; {@code neither} if they hold neither, or both, which is noted
     * as {@code both} and leaves both in {@code params}.
     */
    private <T> T exclusive(List<Param> params, String first, T ifFirst, String second, T ifSecond, T neither,
                            String both) {
        boolean hasFirst = Param.contains(params, first);
        boolean hasSecond = Param.contains(params, second);
        if (hasFirst && hasSecond) {
            note(both);
            return neither;
        }
        params.removeIf(p -> p.hasAccession(first) || p.hasAccession(second));
        return hasFirst ? ifFirst : hasSecond ? ifSecond : neither;
    }

    // ---- the run (design 5.0, 5.4-5.9) -----------------------------------------------------------------------------

    /**
     * One pass over the run: the spectra, then the chromatograms, each mapped and handed to {@code visitor}, with its
     * arrays decoded unless {@link #surveying}. A list element jmzML did not index is skipped.
     */
    private void pass(Visitor visitor) {
        MzMLUnmarshaller u = unmarshaller();
        spectrumListDefault = header().fileMetadata().run().defaultDataProcessingId();
        msIndex = 0;
        wavelengthIndex = 0;
        chromatogramIndex = 0;
        if (u.getObjectCountForXpath("/run/spectrumList") > 0) {
            each("/run/spectrumList/spectrum", uk.ac.ebi.jmzml.model.mzml.Spectrum.class, "spectrum",
                    uk.ac.ebi.jmzml.model.mzml.Spectrum::getId, this::spectrum, visitor);
        }
        if (u.getObjectCountForXpath("/run/chromatogramList") > 0) {
            each("/run/chromatogramList/chromatogram", uk.ac.ebi.jmzml.model.mzml.Chromatogram.class, "chromatogram",
                    uk.ac.ebi.jmzml.model.mzml.Chromatogram::getId, c -> chromatogram(c, chromatogramListDefault),
                    visitor);
        }
    }

    /**
     * Unmarshals the elements at {@code xpath} one at a time, maps each by {@code map} and hands the result to
     * {@code visitor}. A {@link RuntimeException} while jmzML unmarshals or the mapping runs becomes an
     * {@link MzPeakException} naming the element (design 8.1); the reader's own carry that name already. What the
     * visitor throws propagates as it is.
     */
    private <T extends MzMLObject> void each(String xpath, Class<T> type, String kind, Function<T, String> id,
                                             Function<T, Consumer<Visitor>> map, Visitor visitor) {
        MzMLObjectIterator<T> elements = unmarshaller.unmarshalCollectionFromXpath(xpath, type);
        for (int n = 0; elements.hasNext(); n++) {
            elementName = "mzML " + mzml + ": " + kind + " #" + n;
            Consumer<Visitor> mapped;
            try {
                T item = elements.next();
                elementName += " (" + id.apply(item) + ")";
                mapped = map.apply(item);
            } catch (MzPeakException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new MzPeakException(elementName + ": " + e.getMessage(), e);
            }
            mapped.accept(visitor);
        }
    }

    /**
     * A spectrum (design 5.4): a wavelength spectrum if it has a wavelength array and no m/z array, or no arrays and
     * a wavelength spectrum type; else an MS spectrum. Its params are its own, then those of its scanList.
     */
    private Consumer<Visitor> spectrum(uk.ac.ebi.jmzml.model.mzml.Spectrum s) {
        List<String> lost = Arrays.asList(s.getSpotID(), s.getSourceFileRef(), s.getDataProcessingRef());
        for (int i = 0; i < SPECTRUM_LOST.size(); i++) {
            note(String.format(B_10, SPECTRUM_LOST.get(i)), lost.get(i) == null ? 0 : 1);
        }
        note(B_20, s.getProductList() == null ? 0 : 1);
        List<Param> params = new ArrayList<>(params(s));
        ScanList scanList = s.getScanList();
        List<Param> listParams = params(scanList);
        note(B_13, listParams.stream().filter(p -> !MzMLTerms.SPECTRA_COMBINATION.containsKey(p.accession())).count());
        params.addAll(listParams);
        List<ScanEvent> scans = new ArrayList<>();
        if (scanList != null) {
            for (Scan scan : scanList.getScan()) {
                scans.add(scan(scan));
            }
        }
        List<Array> arrays = arrays(s.getBinaryDataArrayList(), s.getDefaultArrayLength());
        boolean wavelength = arrays.isEmpty()
                ? params.stream().anyMatch(p -> WAVELENGTH_TYPES.contains(p.accession()))
                : first(arrays, Kind.WAVELENGTH.axis) != null && first(arrays, MZ_ARRAY) == null;
        if (wavelength) {
            WavelengthSpectrum spectrum = wavelengthSpectrum(s, params, scans, arrays);
            return v -> v.wavelengthSpectrum(spectrum);
        }
        note(B_11, wavelengthIndex > 0 ? 1 : 0);
        Spectrum spectrum = msSpectrum(s, params, scans, arrays);
        return v -> v.spectrum(spectrum);
    }

    /**
     * An MS spectrum: ms level, polarity and representation promoted; no peak list; its array length counts as
     * {@code numberOfPeaks} for a centroid spectrum, else as {@code numberOfDataPoints}.
     */
    private Spectrum msSpectrum(uk.ac.ebi.jmzml.model.mzml.Spectrum s, List<Param> params, List<ScanEvent> scans,
                                List<Array> arrays) {
        Integer msLevel = intParam(params, CvTerms.MS_LEVEL);
        Polarity polarity = polarity(params);
        SignalContinuity continuity = exclusive(params, CvTerms.CENTROID_SPECTRUM, SignalContinuity.CENTROID,
                CvTerms.PROFILE_SPECTRUM, SignalContinuity.PROFILE, SignalContinuity.UNKNOWN, BOTH_REPRESENTATIONS);
        List<Precursor> precursors = new ArrayList<>();
        PrecursorList precursorList = s.getPrecursorList();
        if (precursorList != null) {
            for (uk.ac.ebi.jmzml.model.mzml.Precursor precursor : precursorList.getPrecursor()) {
                precursors.add(precursor(precursor, true));
            }
        }
        Signal signal = classify(Kind.MS, arrays);
        double[] mz = values(signal.axis());
        long n = mz.length;
        boolean centroid = continuity == SignalContinuity.CENTROID;
        SpectrumDescription description = new SpectrumDescription(msIndex++, s.getId(), msLevel == null ? 0 : msLevel,
                scans.isEmpty() ? Double.NaN : scans.get(0).startTime(), polarity, continuity, centroid ? 0 : n,
                centroid ? n : 0, scans, precursors, params);
        return new Spectrum(description, mz, values(signal.intensity()), null, values(signal.extras()), null);
    }

    /**
     * A wavelength spectrum (design 5.9): the first scan's start time; MS:1003812 promoted as lambda max in nm. Other
     * scan content, further scans and precursors are lost (B-23).
     */
    private WavelengthSpectrum wavelengthSpectrum(uk.ac.ebi.jmzml.model.mzml.Spectrum s, List<Param> params,
                                                  List<ScanEvent> scans, List<Array> arrays) {
        Double lambdaMax = doubleParam(params, "MS:1003812", NANOMETER);
        double time = scans.isEmpty() ? Double.NaN : scans.get(0).startTime();
        if (scans.size() > 1 || !scans.isEmpty()
                && !scans.get(0).equals(new ScanEvent(time, null, null, null, null, null, null, null))
                || s.getPrecursorList() != null) {
            note(B_23);
        }
        Signal signal = classify(Kind.WAVELENGTH, arrays);
        return new WavelengthSpectrum(wavelengthIndex++, s.getId(), time, lambdaMax, values(signal.axis()),
                values(signal.intensity()), params);
    }

    /**
     * A chromatogram (design 5.8): its type and polarity promoted; its data processing (its own, else the list's
     * {@code listDefault}) kept when it differs from the spectrumList's; its precursor; its product dropped (B-27); its
     * time in minutes and its intensity unit as given.
     */
    private Consumer<Visitor> chromatogram(uk.ac.ebi.jmzml.model.mzml.Chromatogram c, String listDefault) {
        List<Param> params = new ArrayList<>(params(c));
        Param type = params.stream().filter(p -> MzMLTerms.CHROMATOGRAM_TYPES.containsKey(p.accession())).findFirst()
                .orElse(null);
        params.remove(type);
        Polarity polarity = polarity(params);
        String processing = c.getDataProcessingRef() != null ? c.getDataProcessingRef() : listDefault;
        List<Precursor> precursors = new ArrayList<>();
        if (c.getPrecursor() != null) {
            precursors.add(precursor(c.getPrecursor(), false));
        }
        note(B_27, c.getProduct() == null ? 0 : 1);
        Signal signal = classify(Kind.CHROMATOGRAM, arrays(c.getBinaryDataArrayList(), c.getDefaultArrayLength()));
        Chromatogram chromatogram = new Chromatogram(chromatogramIndex++, c.getId(),
                type == null ? null : type.accession(), values(signal.axis()), values(signal.intensity()),
                signal.intensity() == null ? null : signal.intensity().array().unit(), values(signal.extras()),
                polarity, Objects.equals(processing, spectrumListDefault) ? null : processing, params, precursors);
        return v -> v.chromatogram(chromatogram);
    }

    private Polarity polarity(List<Param> params) {
        return exclusive(params, "MS:1000130", Polarity.POSITIVE, "MS:1000129", Polarity.NEGATIVE, Polarity.UNKNOWN,
                BOTH_POLARITIES);
    }

    /**
     * A scan (design 5.5): its start time in minutes (NaN if absent); injection time in ms, filter string (verbatim),
     * preset scan configuration and instrument configuration (rule IC; a ref naming none is null, B-39) promoted; its
     * windows with both limits in m/z.
     */
    private ScanEvent scan(Scan scan) {
        List<Param> params = new ArrayList<>(params(scan));
        Param start = Param.find(params, "MS:1000016").filter(p -> p.value() instanceof Number).orElse(null);
        double startTime = Double.NaN;
        if (start != null) {
            params.remove(start);
            DoubleUnaryOperator conversion = conversion(start.unit(), MINUTE);
            if (conversion == null) {
                throw error("scan start time (MS:1000016) in unit " + start.unit() + " cannot be converted to minutes ("
                        + MINUTE + ")");
            }
            note(B_15, conversion == AS_IS ? 0 : 1);
            startTime = conversion.applyAsDouble(((Number) start.value()).doubleValue());
        }
        Double injectionTime = doubleParam(params, "MS:1000927", MILLISECOND);
        note(INJECTION_TIME_KEPT, injectionTime == null && Param.contains(params, "MS:1000927") ? 1 : 0);
        String filter = text(scan, "MS:1000512");
        Param.find(params, "MS:1000512").ifPresent(params::remove);
        Integer preset = intParam(params, "MS:1000616");
        List<ScanWindow> windows = new ArrayList<>();
        ScanWindowList windowList = scan.getScanWindowList();
        if (windowList != null) {
            for (ParamGroup window : windowList.getScanWindow()) {
                List<Param> limits = new ArrayList<>(params(window));
                Double lower = doubleParam(limits, "MS:1000501", MZ);
                Double upper = doubleParam(limits, "MS:1000500", MZ);
                note(B_17_PARAMS, limits.size());
                if (lower == null || upper == null) {
                    note(B_17_WINDOW);
                } else {
                    windows.add(new ScanWindow(lower, upper));
                }
            }
        }
        String icRef = scan.getInstrumentConfigurationRef();
        Integer ic = icIds.get(icRef);
        note(B_39, icRef != null && ic == null ? 1 : 0);
        return new ScanEvent(startTime, injectionTime, filter, preset, ic,
                reference(scan.getSpectrumRef(), scan.getExternalSpectrumID(), scan.getSourceFileRef(), true), windows,
                params);
    }

    /**
     * A precursor of a spectrum or, with {@code ofSpectrum} false, of a chromatogram (design 5.2, 5.6, 5.8). A
     * spectrum precursor's {@code spectrumRef} gives its index through {@link #survey()}: the MS ordinal it names, or
     * null for a wavelength spectrum (B-33) or no spectrum. A chromatogram precursor has no index.
     */
    private Precursor precursor(uk.ac.ebi.jmzml.model.mzml.Precursor p, boolean ofSpectrum) {
        String id = reference(p.getSpectrumRef(), p.getExternalSpectrumID(), p.getSourceFileRef(), !ofSpectrum);
        Long index = null;
        if (ofSpectrum) {
            index = msOrdinals.get(p.getSpectrumRef());
            note(B_33, index == null && wavelengthIds.contains(p.getSpectrumRef()) ? 1 : 0);
        }
        ParamGroup w = p.getIsolationWindow();
        IsolationWindow window = null;
        if (w != null) {
            List<Param> params = new ArrayList<>(params(w));
            Double target = doubleParam(params, "MS:1000827", MZ);
            Double lower = doubleParam(params, "MS:1000828", MZ);
            Double upper = doubleParam(params, "MS:1000829", MZ);
            window = new IsolationWindow(target, lower, upper, params);
        }
        List<SelectedIon> ions = new ArrayList<>();
        SelectedIonList ionList = p.getSelectedIonList();
        if (ionList != null) {
            for (ParamGroup ion : ionList.getSelectedIon()) {
                List<Param> params = new ArrayList<>(params(ion));
                Double mz = doubleParam(params, CvTerms.SELECTED_ION_MZ, MZ);
                note(B_18, mz == null ? 1 : 0);
                Integer charge = intParam(params, CvTerms.CHARGE_STATE);
                Double intensity = doubleParam(params, CvTerms.SELECTED_ION_INTENSITY, "MS:1000131");
                ions.add(new SelectedIon(mz == null ? Double.NaN : mz, charge, intensity, params));
            }
        }
        return new Precursor(index, id, window, ions, new Activation(params(p.getActivation())));
    }

    /**
     * The {@code ref} ({@code spectrumRef}), else the {@code external} id (design 5.2); a {@code sourceFileRef} is
     * lost (B-16). With {@code locality}, an external id equal to a local spectrum id is noted: it comes back as a
     * {@code spectrumRef} (B-33).
     */
    private String reference(String ref, String external, String sourceFileRef, boolean locality) {
        note(B_16, sourceFileRef == null ? 0 : 1);
        if (locality && ref == null && (msOrdinals.containsKey(external) || wavelengthIds.contains(external))) {
            note(B_33);
        }
        return ref != null ? ref : external;
    }

    // ---- arrays (design 5.7, 8.1) ----------------------------------------------------------------------------------

    /** What a binaryDataArrayList belongs to, with the key of its axis array. */
    private enum Kind {
        MS(MZ_ARRAY), WAVELENGTH("MS:1000617"), CHROMATOGRAM("MS:1000595");

        final String axis;

        Kind(String axis) {
            this.axis = axis;
        }
    }

    /**
     * A binaryDataArray as its attributes and terms describe it, read before any array of its element is decoded.
     *
     * @param key         the array type, or for MS:1000786 the array's name; null if it has neither
     * @param unit        the unit of its array type term
     * @param ascii       whether it is a null-terminated ASCII string (MS:1001479)
     * @param length      the declared length: {@code arrayLength}, else the owner's {@code defaultArrayLength}
     * @param otherParams how many params it has besides array type, data type and compression
     */
    private record Array(BinaryDataArray array, String key, String unit, boolean ascii, int length,
                         int otherParams) {
    }

    /** An array kept, and the conversion of its values into the model's unit. */
    private record Kept(Array array, DoubleUnaryOperator conversion) {
    }

    /** The arrays kept of a spectrum or chromatogram: axis and intensity (both or neither) and the extras by key. */
    private record Signal(Kept axis, Kept intensity, Map<String, Kept> extras) {
    }

    /**
     * The arrays of {@code list} (none for no list), each with its declared length, {@code arrayLength} or else
     * {@code defaultLength}. Its params are read as every element's, so cvRef aliases (B-30) and userParam type
     * mismatches (B-14) are noted. Rejected here, for every array, before any is decoded: a non-standard data array
     * whose name is the accession of an array type, under whose key it would replace or displace that array; an array
     * compressed with MS-Numpress and zlib in one term (guard 2); a data type or compression jmzML cannot decode
     * (its {@code getPrecision()} or {@code needsUncompressing()} throws).
     */
    private List<Array> arrays(BinaryDataArrayList list, int defaultLength) {
        List<Array> arrays = new ArrayList<>();
        for (BinaryDataArray a : list == null ? List.<BinaryDataArray>of() : list.getBinaryDataArray()) {
            Param type = null;
            boolean typed = false;
            int other = 0;
            for (Param p : params(a)) {
                String accession = p.accession();
                if (type == null && CvTerms.ARRAY_TYPES.containsKey(accession)) {
                    type = p;
                } else if (!typed && (MzMLTerms.DATA_TYPES.containsKey(accession) || FLOAT16.equals(accession))) {
                    typed = true;
                } else if (!MzMLTerms.COMPRESSIONS.containsKey(accession)) {
                    other++;
                }
            }
            String key = type == null ? null : type.accession();
            if (NON_STANDARD_ARRAY.equals(key)) {
                key = text(a, NON_STANDARD_ARRAY); // the name as written, not as params(a) typed it
                if (CvTerms.ARRAY_TYPES.containsKey(key)) {
                    throw error("its non-standard data array (" + NON_STANDARD_ARRAY + ") is named '" + key
                            + "', the accession of an array type");
                }
            }
            for (CVParam p : a.getCvParam()) {
                if (NUMPRESS_ZLIB.contains(p.getAccession())) {
                    throw error("its " + key + " array is compressed with " + p.getAccession()
                            + " (MS-Numpress and zlib in one term), which jmzML 1.7.11 cannot decode");
                }
            }
            boolean ascii;
            try {
                ascii = a.getPrecision() == BinaryDataArray.Precision.NTSTRING;
                a.needsUncompressing();
            } catch (RuntimeException e) {
                throw undecodable(a, key, e);
            }
            int length = a.getArrayLength() != null ? a.getArrayLength() : defaultLength;
            arrays.add(new Array(a, key, type == null ? null : type.unit(), ascii, length, other));
        }
        return arrays;
    }

    /**
     * The one classification of an entity's arrays, which {@link #survey()} and {@link #read(Visitor)} share
     * (design 8.1): from attributes and terms only, it keeps the first axis and intensity array (in the model's unit,
     * else rejected; a chromatogram's intensity in any unit) and the extra arrays it can carry, and drops the rest:
     * arrays without type (B-21), ASCII arrays (B-21), all arrays when the axis or intensity array is missing (B-9),
     * a second array of a key (B-29), extras of wavelength spectra (B-28), extras in a unit that is not their key's
     * default and cannot be converted to it (B-21) and extras whose declared length differs from the axis array's
     * (B-21). An extra whose key has no default unit is kept without its unit (B-21). The first axis and intensity
     * array are checked for their unit before B-9 drops them: design 6.3 rejects them in a unit the model cannot carry
     * whether or not the other one is present, so an intensity-only spectrum with its intensity in MS:1000132 is
     * rejected, not dropped as B-9.
     *
     * @throws MzPeakException for axis and intensity arrays of different declared lengths, or a main array in a unit
     *                         the model cannot carry (design 6.3), with or without the other main array
     */
    private Signal classify(Kind kind, List<Array> arrays) {
        List<Array> usable = new ArrayList<>();
        for (Array a : arrays) {
            if (a.key() == null) {
                note(B_21_UNTYPED);
            } else if (a.ascii()) {
                note(B_21_ASCII);
            } else {
                usable.add(a);
            }
        }
        Array axis = first(usable, kind.axis);
        Array intensity = first(usable, INTENSITY_ARRAY);
        DoubleUnaryOperator axisConversion = axis == null ? null : mainConversion(axis);
        DoubleUnaryOperator intensityConversion = intensity == null || kind == Kind.CHROMATOGRAM ? AS_IS
                : mainConversion(intensity);
        Map<String, Kept> extras = new LinkedHashMap<>();
        if (axis == null || intensity == null) {
            note(B_9, usable.isEmpty() ? 0 : 1);
            return new Signal(null, null, extras);
        }
        if (axis.length() != intensity.length()) {
            throw error("its " + axis.key() + " and " + INTENSITY_ARRAY + " arrays declare different lengths, "
                    + axis.length() + " and " + intensity.length());
        }
        Kept axisKept = kept(axis, axisConversion);
        Kept intensityKept = kept(intensity, intensityConversion);
        for (Array a : usable) {
            if (a == axis || a == intensity) {
                continue;
            }
            String target = CvTerms.DEFAULT_ARRAY_UNITS.get(a.key());
            DoubleUnaryOperator conversion = target == null ? AS_IS : conversion(a.unit(), target);
            if (a.key().equals(kind.axis) || a.key().equals(INTENSITY_ARRAY) || extras.containsKey(a.key())) {
                note(B_29);
            } else if (kind == Kind.WAVELENGTH) {
                note(B_28);
            } else if (conversion == null) {
                note(B_21_UNIT_DROP);
            } else if (a.length() != axis.length()) {
                note(B_21_LENGTH);
            } else {
                note(B_21_UNIT, target == null && a.unit() != null ? 1 : 0);
                extras.put(a.key(), kept(a, conversion));
            }
        }
        return new Signal(axisKept, intensityKept, extras);
    }

    /** The first of {@code arrays} with {@code key}, else null. */
    private static Array first(List<Array> arrays, String key) {
        for (Array a : arrays) {
            if (key.equals(a.key())) {
                return a;
            }
        }
        return null;
    }

    /**
     * How the values of an axis or intensity array become values in the model's unit (design 5.7); a unit the model
     * cannot carry is rejected (design 6.3).
     */
    private DoubleUnaryOperator mainConversion(Array a) {
        String target = CvTerms.DEFAULT_ARRAY_UNITS.get(a.key());
        DoubleUnaryOperator conversion = conversion(a.unit(), target);
        if (conversion == null) {
            throw error("its " + a.key() + " array in unit " + a.unit() + " cannot be converted to " + target);
        }
        return conversion;
    }

    /** {@code a} kept, its params other than type, data type and compression and its dataProcessingRef lost. */
    private Kept kept(Array a, DoubleUnaryOperator conversion) {
        note(B_21_PROCESSING, a.array().getDataProcessingRef() == null ? 0 : 1);
        note(B_21_PARAMS, a.otherParams());
        return new Kept(a, conversion);
    }

    /**
     * How values in {@code unit} become values in the model's unit {@code target}: as they are for no unit or
     * {@code target}, a conversion of {@link #CONVERSIONS}, else null.
     */
    private static DoubleUnaryOperator conversion(String unit, String target) {
        if (unit == null || unit.equals(target)) {
            return AS_IS;
        }
        return CONVERSIONS.getOrDefault(target, Map.of()).get(unit);
    }

    /**
     * The values of a kept array, decoded by jmzML, 64-bit integers that do not survive as doubles counted (B-31),
     * converted (B-15); empty while {@link #surveying} and for no array. An MS-Numpress PIC count that jmzML decodes
     * as a negative number, which a count of 2^31 or more becomes, is rejected (guard 3).
     */
    private double[] values(Kept kept) {
        if (kept == null || surveying) {
            return new double[0];
        }
        Array a = kept.array();
        Number[] numbers;
        try {
            numbers = a.array().getBinaryDataAsNumberArray();
        } catch (RuntimeException e) {
            throw undecodable(a.array(), a.key(), e);
        }
        boolean pic = a.array().getCvParam().stream().anyMatch(p -> PIC.equals(p.getAccession()));
        double[] values = new double[numbers.length];
        long rounded = 0;
        for (int i = 0; i < values.length; i++) {
            double v = numbers[i].doubleValue();
            // 2^63, the double of Long.MAX_VALUE, is no long: (long) saturates it back to Long.MAX_VALUE
            if (numbers[i] instanceof Long n && (v == 0x1p63 || (long) v != n)) {
                rounded++;
            }
            if (pic && v < 0) {
                throw error("its " + a.key() + " array (MS-Numpress PIC, " + PIC + ") holds the count "
                        + ((long) v + (1L << 32)) + ", which jmzML 1.7.11 decodes as " + (long) v
                        + "; counts of 2^31 and above are not supported");
            }
            values[i] = v;
        }
        note(B_31, rounded);
        if (kept.conversion() != AS_IS) {
            note(B_15);
            for (int i = 0; i < values.length; i++) {
                values[i] = kept.conversion().applyAsDouble(values[i]);
            }
        }
        return values;
    }

    private Map<String, double[]> values(Map<String, Kept> extras) {
        Map<String, double[]> values = new LinkedHashMap<>();
        extras.forEach((key, kept) -> values.put(key, values(kept)));
        return values;
    }

    /** jmzML's failure to read or decode the array {@code a} with key {@code key}, naming the array and its terms. */
    private MzPeakException undecodable(BinaryDataArray a, String key, RuntimeException e) {
        return new MzPeakException(elementName + ": its " + key + " array with the terms "
                + a.getCvParam().stream().map(CVParam::getAccession).toList() + " cannot be decoded by jmzML 1.7.11: "
                + e.getMessage(), e);
    }

    private void note(String kind) {
        note(kind, 1);
    }

    /** Notes a loss into {@link #noted}. */
    private void note(String kind, long n) {
        noted.note(kind, n);
    }

    /** Collects the {@link Survey} of a pass. */
    private static final class Collector implements Visitor {
        final List<String> spectrumIds = new ArrayList<>();
        final List<String> wavelengthIds = new ArrayList<>();
        final Set<String> arrays = new LinkedHashSet<>();
        int chromatograms;

        @Override
        public void spectrum(Spectrum spectrum) {
            spectrumIds.add(spectrum.description().id());
            arrays.addAll(spectrum.arrays().keySet());
        }

        @Override
        public void wavelengthSpectrum(WavelengthSpectrum spectrum) {
            wavelengthIds.add(spectrum.id());
        }

        @Override
        public void chromatogram(Chromatogram chromatogram) {
            chromatograms++;
        }
    }

    private static String emptyToNull(String text) {
        return "".equals(text) ? null : text;
    }

    /** The reader's own rejection of the element a pass is at. */
    private MzPeakException error(String message) {
        return new MzPeakException(elementName + ": " + message);
    }
}
