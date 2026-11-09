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
import uk.ac.ebi.jmzml.model.mzml.CVParam;
import uk.ac.ebi.jmzml.model.mzml.MzMLObject;
import uk.ac.ebi.jmzml.model.mzml.ParamGroup;
import uk.ac.ebi.jmzml.model.mzml.PrecursorList;
import uk.ac.ebi.jmzml.model.mzml.Scan;
import uk.ac.ebi.jmzml.model.mzml.ScanList;
import uk.ac.ebi.jmzml.model.mzml.ScanWindowList;
import uk.ac.ebi.jmzml.model.mzml.SelectedIonList;
import uk.ac.ebi.jmzml.model.mzml.UserParam;
import uk.ac.ebi.jmzml.xml.io.MzMLObjectIterator;
import uk.ac.ebi.jmzml.xml.io.MzMLUnmarshaller;

import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.CharConversionException;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;

/**
 * Reads an mzML 1.1 document, bare or wrapped in {@code indexedmzML}, into mzPeakJ's model (mzML design 3, 5, 8.1).
 *
 * <p>{@link #header()} reads the document with StAX up to the run's {@code spectrumList} or {@code chromatogramList},
 * or the end of the run: namespace-aware, elements matched by local name (so a document without the mzML namespace
 * reads the same), attributes without a namespace; DTDs and external entities are not supported, and a DTD is
 * rejected. It reads the cv list, the file description (contents, source files, contacts), samples, software, scan
 * settings, instrument configurations, data processings and the run (design 5.1). Ids and strings are kept verbatim;
 * param values are typed by {@link MzMLTerms#value}, and a {@code referenceableParamGroupRef} is replaced by the
 * group's params, before the element's own params (design 5.3). What the model cannot hold is counted in
 * {@link #warnings()} (design 6.1). The input index and checksum are not read.
 *
 * <p>{@link #survey()} and {@link #read(Visitor)} read the run through jmzML 1.7.11 as is (design 3, 5.0, 5.4-5.9):
 * one {@code MzMLUnmarshaller} per reader, created by the first of them after the header, indexes the whole file and
 * hands out the spectra and then the chromatograms one at a time. {@code read} maps every spectrum, wavelength spectrum
 * and chromatogram to the model and hands it to a {@link Visitor}, with its arrays decoded by jmzML's codec;
 * {@code survey} runs the same mapping without decoding arrays. Both take every array through one classification by
 * its attributes and terms only, before any array of its element is decoded (design 8.1), so the extra array keys
 * {@code survey} declares are exactly those {@code read} emits. Params come in jmzML's order: an element's cvParams,
 * then those of its referenced groups, then its userParams and its groups' (design 5.3, Q41). Creating the
 * unmarshaller notes the limit lines {@link #LIMITS} (design 6.0).
 *
 * <p>Invalid input is rejected with an {@link MzPeakException} naming the file. The header names the line and column
 * where the parser found the fault: malformed XML, elements nested more than 100 deep, a DTD, a root that is neither
 * {@code mzML} nor {@code indexedmzML} around {@code mzML}, an mzML version that does not start with {@code 1.1}, no
 * {@code run}, a {@code referenceableParamGroupRef} naming no group, a missing required attribute this reader needs,
 * and an {@code order} that is not an integer. A file that cannot be read, a directory included, gives an
 * {@link MzPeakException} with the {@link IOException} as its cause. In the run (design 6.3, 10), what jmzML cannot
 * index (tags that do not match, no {@code mzML} root) fails with {@code mzML <file>: <message>}; everything else is
 * named by its element, {@code mzML <file>: spectrum #<n> (<id>): <message>} (or {@code chromatogram}), with
 * {@code n} its 0-based position and the id once jmzML returned the element, and an exception of jmzML, JAXB or the
 * model as its cause. {@code survey} also rejects what the model or jmzML cannot carry: a non-standard data array
 * (MS:1000786) whose name is the accession of an array type; an array compressed with MS:1002746, MS:1002747 or
 * MS:1002748 (MS-Numpress and zlib in one term, which jmzML cannot decode); a data type or compression jmzML cannot
 * decode (MS:1000520 16-bit float, zstd, none); in the first usable (typed, not ASCII) array of each main key of a
 * spectrum or chromatogram, the only one whose unit is checked, an m/z array in a unit other than MS:1000040, an
 * intensity array of a spectrum in a unit other than MS:1000131, a wavelength array in a unit other than UO:0000018
 * and a time array in a unit other than minute, second, millisecond or hour; a scan start time in a unit other than
 * those four; and m/z and intensity arrays (wavelength or time and intensity arrays) of different declared lengths. A
 * further array of a main key is dropped (B-29) without a unit check. The unit of an axis or intensity array is checked
 * even when the other one is missing and both would be dropped (B-9). {@code read} also rejects an array jmzML cannot
 * decode, an MS-Numpress PIC count of 2^31 or more (jmzML decodes it as a negative number) and main arrays that decode
 * to different lengths; declared lengths are not checked against the decoded ones (L-6).
 */
public final class MzMLReader {

    /**
     * The limit lines every reader notes when it first reads the run (design 6.0), before its loss lines:
     * <ul>
     *   <li>L-1: the whole input is indexed before the first element is read, and the index stays in memory; two
     *       file handles stay open until the garbage collector finalizes jmzML's objects;
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
    // Warned by design 5.4 and 5.5, without a loss number.
    static final String BOTH_POLARITIES = "spectra and chromatograms with both scan polarities read as polarity"
            + " unknown, the terms kept as params";
    static final String BOTH_REPRESENTATIONS = "spectra with both centroid and profile terms read as representation"
            + " unknown, the terms kept as params";
    static final String INJECTION_TIME_KEPT = "ion injection times not in milliseconds kept as scan params";

    private static final String CONTACT_NAME = "MS:1000586";
    private static final String CONTACT_AFFILIATION = "MS:1000590";
    /** The component elements of a {@code componentList}, in the order the model lists them. */
    private static final List<String> COMPONENTS = List.of("source", "analyzer", "detector");

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
     * The deepest element nesting read (JAXP's {@code maxElementDepth}); valid mzML nests about 12 deep, and deeper
     * nesting would overflow the stack of the recursive {@link #element} (JDK 17 and 21 set no limit by default).
     */
    private static final int MAX_ELEMENT_DEPTH = 100;
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
    /** The params of each referenceableParamGroup, by id; filled by {@link #header()}. */
    private final Map<String, List<Param>> groups = new HashMap<>();
    /** Each referenceableParamGroup, by id, for the value texts of its cvParams; filled by {@link #header()}. */
    private final Map<String, Node> groupElements = new HashMap<>();
    private Header header;
    /** The model id of each instrument configuration id (rule IC); filled by {@link #header()}. */
    private Map<String, Integer> icIds = Collections.emptyMap();
    /** jmzML's reader of the file and its index; created by the first pass over the run. */
    private MzMLUnmarshaller unmarshaller;
    private Survey survey;
    /** The MS ordinal of each MS spectrum id, the first of repeated ids; filled by {@link #survey()}. */
    private Map<String, Long> msOrdinals = Collections.emptyMap();
    /** The ids of the wavelength spectra; filled by {@link #survey()}. */
    private Set<String> wavelengthIds = Collections.emptySet();
    /** True while {@link #survey()} runs the mapping: arrays are not decoded. */
    private boolean surveying;
    /** The spectrumList's default data processing, and the next MS, wavelength and chromatogram index of a pass. */
    private String spectrumListDefault;
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
     * @throws MzPeakException if the document is invalid as stated in the class description, or cannot be read
     */
    public Header header() {
        if (header == null) {
            header = parse(this::readHeader);
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
     * What was lost so far: the limit lines {@link #LIMITS} once the run was first read (design 6.0), then one line per
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

    // ---- reading the header ----------------------------------------------------------------------------------------

    /** Reads the file through {@code body}; I/O and XML errors become {@link MzPeakException}s naming the file. */
    private <T> T parse(Body<T> body) {
        try (InputStream in = Files.newInputStream(mzml)) {
            XMLStreamReader r = factory().createXMLStreamReader(in);
            try {
                return body.read(r);
            } finally {
                r.close();
            }
        } catch (IOException e) {
            throw cannotRead(mzml, e);
        } catch (XMLStreamException e) {
            throw failure(mzml, e);
        }
    }

    /** What {@link #parse} does with the reader. */
    private interface Body<T> {
        T read(XMLStreamReader r) throws XMLStreamException;
    }

    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", MAX_ELEMENT_DEPTH);
        return factory;
    }

    private Header readHeader(XMLStreamReader r) throws XMLStreamException {
        nextTag(r);
        if (r.getLocalName().equals("indexedmzML")) {
            nextTag(r);
        }
        if (!r.isStartElement() || !r.getLocalName().equals("mzML")) {
            throw error(r.getLocation(), "not an mzML document: no <mzML> element at the root or inside <indexedmzML>");
        }
        Node mzML = startNode(r);
        String version = mzML.attr("version");
        if (!Objects.requireNonNullElse(version, "").startsWith("1.1")) {
            throw error(mzML, "mzML version " + version + " is not supported; version 1.1 is required");
        }
        for (String lost : List.of("id", "accession")) {
            if (mzML.attr(lost) != null) {
                losses.note(B_3);
            }
        }
        Map<String, Node> sections = new HashMap<>();
        while (nextTag(r) == XMLStreamConstants.START_ELEMENT && !r.getLocalName().equals("run")) {
            Node section = element(r, startNode(r));
            sections.put(section.name, section);
        }
        if (!r.isStartElement()) {
            throw error(r.getLocation(), "no <run> element");
        }
        Node run = startNode(r);
        String defaultDataProcessing = runHead(r, run);
        return map(sections, run, defaultDataProcessing);
    }

    /**
     * Reads the children of {@code run} up to its {@code spectrumList} or {@code chromatogramList}, or its end, and
     * returns the {@code spectrumList}'s default data processing. The {@code chromatogramList}'s default is no run
     * field: it applies per chromatogram (design 5.1).
     */
    private String runHead(XMLStreamReader r, Node run) throws XMLStreamException {
        while (nextTag(r) == XMLStreamConstants.START_ELEMENT) {
            Node child = startNode(r);
            if (child.name.equals("spectrumList")) {
                return child.attr("defaultDataProcessingRef");
            }
            if (child.name.equals("chromatogramList")) {
                return null;
            }
            run.children.add(element(r, child));
        }
        return null;
    }

    /** Advances to the next start or end tag, skipping text, comments and processing instructions. */
    private int nextTag(XMLStreamReader r) throws XMLStreamException {
        int event;
        do {
            event = r.next();
            if (event == XMLStreamConstants.DTD) {
                throw error(r.getLocation(), "a DTD is not supported");
            }
        } while (event != XMLStreamConstants.START_ELEMENT && event != XMLStreamConstants.END_ELEMENT);
        return event;
    }

    /** The element at the current start tag, without its children. */
    private static Node startNode(XMLStreamReader r) {
        Map<String, String> attributes = new HashMap<>();
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (Objects.toString(r.getAttributeNamespace(i), "").isEmpty()) {
                attributes.put(r.getAttributeLocalName(i), r.getAttributeValue(i));
            }
        }
        Location at = r.getLocation();
        return new Node(r.getLocalName(), attributes, new ArrayList<>(), at.getLineNumber(), at.getColumnNumber());
    }

    /** {@code node}, just started, with its child elements read up to its end tag; text is skipped. */
    private Node element(XMLStreamReader r, Node node) throws XMLStreamException {
        while (nextTag(r) == XMLStreamConstants.START_ELEMENT) {
            node.children.add(element(r, startNode(r)));
        }
        return node;
    }

    // ---- mapping (design 5.1) --------------------------------------------------------------------------------------

    private Header map(Map<String, Node> sections, Node run, String defaultDataProcessing) {
        List<Node> groupList = section(sections, "referenceableParamGroupList").children("referenceableParamGroup");
        for (Node group : groupList) {
            groups.put(group.attr("id"), params(group));
            groupElements.put(group.attr("id"), group);
        }
        losses.note(B_1, groupList.size());

        List<MzPeakManifest.CvEntry> cvList = section(sections, "cvList").children("cv").stream()
                .map(cv -> new MzPeakManifest.CvEntry(cv.attr("id"), cv.attr("fullName"), cv.attr("URI"),
                        cv.attr("version")))
                .toList();
        Node fileDescription = section(sections, "fileDescription");
        FileDescription description = new FileDescription(params(fileDescription.child("fileContent")),
                fileDescription.child("sourceFileList").children("sourceFile").stream().map(this::sourceFile).toList(),
                fileDescription.children("contact").stream().map(this::contact).toList());
        List<Sample> samples = section(sections, "sampleList").children("sample").stream()
                .map(s -> new Sample(s.attr("id"), s.attr("name"), params(s)))
                .toList();
        List<Software> software = section(sections, "softwareList").children("software").stream()
                .map(s -> new Software(s.attr("id"), emptyToNull(s.attr("version")), params(s)))
                .toList();
        List<ScanSettings> scanSettings = section(sections, "scanSettingsList").children("scanSettings").stream()
                .map(this::scanSettings)
                .toList();

        List<Node> icList = section(sections, "instrumentConfigurationList").children("instrumentConfiguration");
        List<String> icNames = icList.stream().map(ic -> required(ic, "id")).toList();
        MzMLTerms.IcIds icIds = MzMLTerms.icIds(icNames);
        if (!icIds.exact()) {
            losses.note(String.format(B_5, icNames));
        }
        this.icIds = icIds.map();
        List<InstrumentConfiguration> instruments = new ArrayList<>();
        for (int i = 0; i < icList.size(); i++) {
            instruments.add(instrumentConfiguration(icList.get(i), icIds.ids().get(i)));
        }
        List<DataProcessing> dataProcessing = section(sections, "dataProcessingList").children("dataProcessing")
                .stream()
                .map(dp -> new DataProcessing(dp.attr("id"), dp.children("processingMethod").stream()
                        .map(m -> new ProcessingMethod(integer(m, "order"), m.attr("softwareRef"), params(m)))
                        .toList()))
                .toList();

        if (run.attr("sampleRef") != null) {
            losses.note(B_7);
        }
        String defaultIc = run.attr("defaultInstrumentConfigurationRef");
        Integer defaultInstrument = icIds.map().get(defaultIc);
        if (defaultIc != null && defaultInstrument == null) {
            losses.note(B_39);
        }
        MsRun msRun = new MsRun(run.attr("id"), defaultInstrument, defaultDataProcessing,
                run.attr("defaultSourceFileRef"), run.attr("startTimeStamp"), params(run));
        FileMetadata fileMetadata = new FileMetadata(description, List.copyOf(instruments), dataProcessing, samples,
                software, msRun);
        return new Header(fileMetadata, scanSettings, cvList);
    }

    private static Node section(Map<String, Node> sections, String name) {
        return sections.getOrDefault(name, Node.NONE);
    }

    /** A source file; an empty name or location is null (design 5.1), else verbatim. */
    private SourceFile sourceFile(Node sourceFile) {
        return new SourceFile(sourceFile.attr("id"), emptyToNull(sourceFile.attr("name")),
                emptyToNull(sourceFile.attr("location")), params(sourceFile));
    }

    /**
     * A contact: the first MS:1000586 (contact name) gives the name, the first MS:1000590 (whatever name the file gives
     * it) the affiliation, first in the order of {@link #params(Node)}; each is its cvParam's value text, verbatim, an
     * empty value null (design 2, 6.2). The other params stay params.
     */
    private Contact contact(Node contact) {
        List<Param> params = params(contact);
        List<Param> others = new ArrayList<>(params);
        Param.find(params, CONTACT_NAME).ifPresent(others::remove);
        Param.find(params, CONTACT_AFFILIATION).ifPresent(others::remove);
        return new Contact(text(contact, CONTACT_NAME), text(contact, CONTACT_AFFILIATION), others);
    }

    /**
     * The value text of the first cvParam of {@code owner} with {@code accession}, verbatim, in the order of
     * {@link #params(Node)} (those of the referenced groups first); null if there is none or it is empty.
     * {@link #params(Node)} must have read {@code owner}, which checks its group refs and accessions.
     */
    private String text(Node owner, String accession) {
        List<Node> cvParams = new ArrayList<>();
        for (Node ref : owner.children("referenceableParamGroupRef")) {
            cvParams.addAll(groupElements.get(ref.attr("ref")).children("cvParam"));
        }
        cvParams.addAll(owner.children("cvParam"));
        for (Node cvParam : cvParams) {
            if (cvParam.attr("accession").equals(accession)) {
                return emptyToNull(cvParam.attr("value"));
            }
        }
        return null;
    }

    private ScanSettings scanSettings(Node scanSettings) {
        List<String> sourceFileRefs = scanSettings.child("sourceFileRefList").children("sourceFileRef").stream()
                .map(ref -> required(ref, "ref"))
                .toList();
        List<List<Param>> targets = scanSettings.child("targetList").children("target").stream()
                .map(this::params)
                .toList();
        return new ScanSettings(scanSettings.attr("id"), params(scanSettings), sourceFileRefs, targets);
    }

    /** An instrument configuration with model id {@code id} (rule IC); its components grouped by element. */
    private InstrumentConfiguration instrumentConfiguration(Node ic, int id) {
        if (ic.attr("scanSettingsRef") != null) {
            losses.note(B_6);
        }
        Node componentList = ic.child("componentList");
        List<Component> components = new ArrayList<>();
        for (String element : COMPONENTS) {
            for (Node component : componentList.children(element)) {
                components.add(new Component(ComponentType.parse(element), integer(component, "order"),
                        params(component)));
            }
        }
        return new InstrumentConfiguration(id, List.copyOf(components), ic.child("softwareRef").attr("ref"),
                params(ic));
    }

    // ---- params (design 5.3) ---------------------------------------------------------------------------------------

    /**
     * The params of {@code owner}: the params of each referenced group, in reference order, then its own cvParams
     * and userParams in document order. Other children are not params.
     */
    private List<Param> params(Node owner) {
        List<Param> params = new ArrayList<>();
        List<Param> own = new ArrayList<>();
        for (Node child : owner.children) {
            switch (child.name) {
                case "referenceableParamGroupRef" -> params.addAll(group(child));
                case "cvParam" -> own.add(cvParam(child));
                case "userParam" -> own.add(new Param(child.attr("name"), null,
                        value(child.attr("value"), child.attr("type")), child.attr("unitAccession")));
                default -> {
                }
            }
        }
        params.addAll(own);
        return List.copyOf(params);
    }

    private List<Param> group(Node ref) {
        List<Param> params = groups.get(ref.attr("ref"));
        if (params == null) {
            throw error(ref, "referenceableParamGroupRef '" + ref.attr("ref") + "' names no referenceableParamGroup");
        }
        return params;
    }

    /** A cvParam; a {@code cvRef} other than the accession's prefix is an alias, lost (B-30). */
    private Param cvParam(Node cvParam) {
        String accession = required(cvParam, "accession");
        if (!accession.startsWith(cvParam.attr("cvRef") + ":")) {
            note(B_30);
        }
        return new Param(cvParam.attr("name"), accession, value(cvParam.attr("value"), null),
                cvParam.attr("unitAccession"));
    }

    /**
     * The params of {@code owner} as jmzML gives them (design 5.3, Q41): its cvParams, then those of its referenced
     * groups, then its userParams and its groups'; none for no {@code owner}. A {@code cvRef} other than the
     * accession's prefix is an alias, lost (B-30).
     */
    private List<Param> params(ParamGroup owner) {
        List<Param> params = new ArrayList<>();
        if (owner != null) {
            for (CVParam p : owner.getCvParam()) {
                if (!p.getAccession().startsWith(p.getCvRef() + ":")) {
                    note(B_30);
                }
                params.add(new Param(p.getName(), p.getAccession(), value(p.getValue(), null), p.getUnitAccession()));
            }
            for (UserParam p : owner.getUserParam()) {
                params.add(new Param(p.getName(), null, value(p.getValue(), p.getType()), p.getUnitAccession()));
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
     * jmzML's reader of the file, created on the first call: it indexes the whole file (L-1) and notes the limit lines;
     * a failure while it builds the index names the file only (design 6.3).
     */
    private MzMLUnmarshaller unmarshaller() {
        if (unmarshaller == null) {
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
            String listDefault = u.getSingleElementAttributes("/run/chromatogramList").get("defaultDataProcessingRef");
            each("/run/chromatogramList/chromatogram", uk.ac.ebi.jmzml.model.mzml.Chromatogram.class, "chromatogram",
                    uk.ac.ebi.jmzml.model.mzml.Chromatogram::getId, c -> chromatogram(c, listDefault), visitor);
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

    // ---- attributes and errors -------------------------------------------------------------------------------------

    private String required(Node node, String attribute) {
        String value = node.attr(attribute);
        if (value == null) {
            throw error(node, "<" + node.name + "> has no " + attribute);
        }
        return value;
    }

    private int integer(Node node, String attribute) {
        String text = required(node, attribute);
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw error(node, "<" + node.name + "> " + attribute + " '" + text + "' is not an integer");
        }
    }

    private static String emptyToNull(String text) {
        return "".equals(text) ? null : text;
    }

    /** The reader's own rejection of the element a pass is at. */
    private MzPeakException error(String message) {
        return new MzPeakException(elementName + ": " + message);
    }

    private MzPeakException error(Node node, String message) {
        return new MzPeakException(place(mzml, node.line, node.column) + message);
    }

    private MzPeakException error(Location at, String message) {
        return new MzPeakException(place(mzml, at.getLineNumber(), at.getColumnNumber()) + message);
    }

    private static String place(Path file, int line, int column) {
        return "mzML " + file + " line " + line + " column " + column + ": ";
    }

    /** An I/O error reading {@code file}, with {@code e} as the cause. */
    private static MzPeakException cannotRead(Path file, IOException e) {
        return new MzPeakException("mzML " + file + ": cannot read: " + e.getMessage(), e);
    }

    /**
     * The parser's error as an {@link MzPeakException}: as {@link #cannotRead} when an {@link IOException} caused it
     * (its nested exception or cause; the parser reads the file as it goes, so a directory fails here), else as
     * {@link #malformed}. A {@link CharConversionException}, through which the JDK parser reports a byte sequence
     * that is invalid in the declared encoding, is a well-formedness error and so malformed.
     */
    static MzPeakException failure(Path file, XMLStreamException e) {
        Throwable io = e.getNestedException() instanceof IOException ? e.getNestedException() : e.getCause();
        return io instanceof IOException cause && !(cause instanceof CharConversionException)
                ? cannotRead(file, cause) : malformed(file, e);
    }

    /**
     * The parser's error as an {@link MzPeakException} naming the file and, when the parser gives it, the line and
     * column; the JDK parser's own {@code ParseError at [row,col]} prefix is left out.
     */
    static MzPeakException malformed(Path file, XMLStreamException e) {
        String message = String.valueOf(e.getMessage())
                .replaceFirst("^ParseError at \\[row,col]:\\[-?\\d+,-?\\d+]\\s*Message: ", "");
        Location at = e.getLocation();
        String place = at == null ? "mzML " + file + ": " : place(file, at.getLineNumber(), at.getColumnNumber());
        return new MzPeakException(place + message, e);
    }

    /** A header element read whole: its local name, attributes without a namespace, child elements and position. */
    private static final class Node {
        static final Node NONE = new Node("", Map.of(), List.of(), 0, 0);

        final String name;
        final Map<String, String> attributes;
        final List<Node> children;
        final int line;
        final int column;

        Node(String name, Map<String, String> attributes, List<Node> children, int line, int column) {
            this.name = name;
            this.attributes = attributes;
            this.children = children;
            this.line = line;
            this.column = column;
        }

        String attr(String attribute) {
            return attributes.get(attribute);
        }

        /** The first child element named {@code name}, else {@link #NONE}. */
        Node child(String name) {
            List<Node> named = children(name);
            return named.isEmpty() ? NONE : named.get(0);
        }

        List<Node> children(String name) {
            List<Node> named = new ArrayList<>();
            for (Node child : children) {
                if (child.name.equals(name)) {
                    named.add(child);
                }
            }
            return named;
        }
    }
}
