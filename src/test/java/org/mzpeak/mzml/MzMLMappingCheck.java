package org.mzpeak.mzml;

import org.mzpeak.model.CvTerms;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks six MUST rules of the PSI mapping file ({@code ms-mapping.xml}, as OpenMS ships it) on an mzML document,
 * streaming it with StAX. The cvParams an element gets through {@code referenceableParamGroupRef} count as its own.
 * <ul>
 *   <li>{@code spectrum_must}: every spectrum carries exactly one spectrum type ({@link #SPECTRUM_TYPES}) and exactly
 *       one spectrum representation ({@link #REPRESENTATIONS});
 *   <li>{@code scan_must}: every {@code scanList} carries exactly one spectra combination ({@link #COMBINATIONS});
 *   <li>{@code precursor_activation_must}: every spectrum precursor's {@code activation} carries at least one
 *       dissociation method ({@link CvTerms#DISSOCIATION_METHODS}); the rule does not cover chromatogram
 *       precursors;
 *   <li>{@code binarydataarray_must}: every {@code binaryDataArray}, of a spectrum or a chromatogram, carries exactly
 *       one array type ({@link #ARRAY_TYPES}), one binary data type ({@link #DATA_TYPES}) and one compression
 *       ({@link #COMPRESSIONS}); the mapping file names the two cases {@code spectrum_binarydataarray_must} and
 *       {@code chromatogram_binarydataarray_must};
 *   <li>{@code instrumentconfiguration_must}: every {@code instrumentConfiguration} carries exactly one instrument
 *       model ({@link #instrumentModels()}, from {@code mzml/ms-instrument-models.txt});
 *   <li>{@code chromatogram_must}: every chromatogram carries exactly one chromatogram type
 *       ({@link #CHROMATOGRAM_TYPES}).
 * </ul>
 * "Exactly one" follows the mapping file's {@code isRepeatable="false"}; the dissociation method is repeatable. The
 * mapping file also accepts the parent terms MS:1000525, MS:1000044 and MS:1000031 themselves ({@code useTerm="true"});
 * these term lists, and so this check, do not.
 *
 * <p>Every term list, here and in {@code ms-instrument-models.txt}, follows one rule (mzML design 12.2): the
 * non-obsolete transitive is_a descendants of the rule's parent term in psi-ms 4.2.11 (mzdata v0.68.0
 * {@code cv/psi-ms.obo.gz}), in accession order, without the parent term itself. An obsolete term therefore satisfies
 * no rule.
 */
public final class MzMLMappingCheck {

    /** Spectrum types: descendants of MS:1000559 'spectrum type' (18, of which MS:1000343 and MS:1000620 obsolete). */
    public static final Set<String> SPECTRUM_TYPES = ordered(
            "MS:1000294", // mass spectrum
            "MS:1000322", // charge inversion mass spectrum
            "MS:1000325", // constant neutral gain spectrum
            "MS:1000326", // constant neutral loss spectrum
            "MS:1000328", // e/2 mass spectrum
            "MS:1000341", // precursor ion spectrum
            "MS:1000579", // MS1 spectrum
            "MS:1000580", // MSn spectrum
            "MS:1000581", // CRM spectrum
            "MS:1000582", // SIM spectrum
            "MS:1000583", // SRM spectrum
            "MS:1000789", // enhanced multiply charged spectrum
            "MS:1000790", // time-delayed fragmentation spectrum
            "MS:1000804", // electromagnetic radiation spectrum
            "MS:1000805", // emission spectrum
            "MS:1000806"  // absorption spectrum
    );

    /** Spectrum representations: descendants of MS:1000525 'spectrum representation' (2). */
    public static final Set<String> REPRESENTATIONS = ordered(
            "MS:1000127", // centroid spectrum
            "MS:1000128"  // profile spectrum
    );

    /** Spectra combinations: descendants of MS:1000570 'spectra combination' (4). */
    public static final Set<String> COMBINATIONS = ordered(
            "MS:1000571", // sum of spectra
            "MS:1000573", // median of spectra
            "MS:1000575", // mean of spectra
            "MS:1000795"  // no combination
    );

    /** Binary data arrays: descendants of MS:1000513 'binary data array' (31). */
    public static final Set<String> ARRAY_TYPES = ordered(
            "MS:1000514", // m/z array
            "MS:1000515", // intensity array
            "MS:1000516", // charge array
            "MS:1000517", // signal to noise array
            "MS:1000595", // time array
            "MS:1000617", // wavelength array
            "MS:1000786", // non-standard data array
            "MS:1000820", // flow rate array
            "MS:1000821", // pressure array
            "MS:1000822", // temperature array
            "MS:1002477", // mean ion mobility drift time array
            "MS:1002478", // mean charge array
            "MS:1002529", // resolution array
            "MS:1002530", // baseline array
            "MS:1002742", // noise array
            "MS:1002743", // sampled noise m/z array
            "MS:1002744", // sampled noise intensity array
            "MS:1002745", // sampled noise baseline array
            "MS:1002816", // mean ion mobility array
            "MS:1002893", // ion mobility array
            "MS:1003006", // mean inverse reduced ion mobility array
            "MS:1003007", // raw ion mobility array
            "MS:1003008", // raw inverse reduced ion mobility array
            "MS:1003143", // mass array
            "MS:1003153", // raw ion mobility drift time array
            "MS:1003154", // deconvoluted ion mobility array
            "MS:1003155", // deconvoluted inverse reduced ion mobility array
            "MS:1003156", // deconvoluted ion mobility drift time array
            "MS:1003157", // scanning quadrupole position lower bound m/z array
            "MS:1003158", // scanning quadrupole position upper bound m/z array
            "MS:1003870"  // index array
    );

    /** Binary data types: descendants of MS:1000518 'binary data type' (6, of which MS:1000520 obsolete). */
    public static final Set<String> DATA_TYPES = ordered(
            "MS:1000519", // 32-bit integer
            "MS:1000521", // 32-bit float
            "MS:1000522", // 64-bit integer
            "MS:1000523", // 64-bit float
            "MS:1001479"  // null-terminated ASCII string
    );

    /** Compressions: descendants of MS:1000572 'binary data compression type' (18). */
    public static final Set<String> COMPRESSIONS = ordered(
            "MS:1000574", // zlib compression
            "MS:1000576", // no compression
            "MS:1002312", // MS-Numpress linear prediction compression
            "MS:1002313", // MS-Numpress positive integer compression
            "MS:1002314", // MS-Numpress short logged float compression
            "MS:1002746", // MS-Numpress linear prediction compression followed by zlib compression
            "MS:1002747", // MS-Numpress positive integer compression followed by zlib compression
            "MS:1002748", // MS-Numpress short logged float compression followed by zlib compression
            "MS:1003088", // truncation and zlib compression
            "MS:1003089", // truncation, delta prediction and zlib compression
            "MS:1003090", // truncation, linear prediction and zlib compression
            "MS:1003780", // zstd compression
            "MS:1003781", // byte-shuffled zstd compression
            "MS:1003782", // dictionary-encoded zstd compression
            "MS:1003783", // MS-Numpress linear prediction compression followed by zstd compression
            "MS:1003784", // MS-Numpress positive integer compression followed by zstd compression
            "MS:1003785", // MS-Numpress short logged float compression followed by zstd compression
            "MS:1003826"  // coordinate grid encoding
    );

    /** Chromatogram types: descendants of MS:1000626 'chromatogram type' (15, of which MS:1001474 obsolete). */
    public static final Set<String> CHROMATOGRAM_TYPES = ordered(
            "MS:1000235", // total ion current chromatogram
            "MS:1000627", // selected ion current chromatogram
            "MS:1000628", // basepeak chromatogram
            "MS:1000810", // ion current chromatogram
            "MS:1000811", // electromagnetic radiation chromatogram
            "MS:1000812", // absorption chromatogram
            "MS:1000813", // emission chromatogram
            "MS:1001472", // selected ion monitoring chromatogram
            "MS:1001473", // selected reaction monitoring chromatogram
            "MS:1002715", // temperature chromatogram
            "MS:1003019", // pressure chromatogram
            "MS:1003020", // flow rate chromatogram
            "MS:4000025", // precursor ion current chromatogram
            "MS:4000104"  // total ion currents (is_a MS:1000235)
    );

    /** The rule names this check reports. */
    public static final String SPECTRUM_MUST = "spectrum_must";
    public static final String SCAN_MUST = "scan_must";
    public static final String PRECURSOR_ACTIVATION_MUST = "precursor_activation_must";
    public static final String BINARYDATAARRAY_MUST = "binarydataarray_must";
    public static final String INSTRUMENTCONFIGURATION_MUST = "instrumentconfiguration_must";
    public static final String CHROMATOGRAM_MUST = "chromatogram_must";

    private static final String MODELS_RESOURCE = "ms-instrument-models.txt";
    private static Set<String> instrumentModels;

    private MzMLMappingCheck() {
    }

    /**
     * The instrument models: descendants of MS:1000031 'instrument model' (502, of which MS:1000870 obsolete), read
     * from the test resource {@code mzml/ms-instrument-models.txt} (501 accessions after its {@code #} header).
     * Unmodifiable, in accession order.
     */
    public static synchronized Set<String> instrumentModels() {
        if (instrumentModels == null) {
            Set<String> out = new LinkedHashSet<>();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(MzMLFixtures.open(MODELS_RESOURCE),
                    StandardCharsets.US_ASCII))) {
                for (String line = in.readLine(); line != null; line = in.readLine()) {
                    if (!line.isBlank() && !line.startsWith("#")) {
                        out.add(line.trim());
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            instrumentModels = Collections.unmodifiableSet(out);
        }
        return instrumentModels;
    }

    /**
     * The violations of the six rules in {@code file}, in document order, each as {@code rule @ id}: the id of the
     * spectrum (for {@code spectrum_must}, {@code scan_must}, {@code precursor_activation_must} and the arrays of a
     * spectrum), chromatogram (for {@code chromatogram_must} and its arrays) or instrument configuration. An empty
     * list when the document satisfies all six.
     */
    public static List<String> violations(Path file) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader r = factory.createXMLStreamReader(in);
            try {
                return walk(r);
            } finally {
                r.close();
            }
        } catch (XMLStreamException e) {
            throw new IOException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /** One open element: its name and, for an element a rule applies to, the accessions of its cvParams. */
    private static final class Frame {
        final String name;
        final List<String> accessions;

        Frame(String name, boolean collect) {
            this.name = name;
            this.accessions = collect ? new ArrayList<>() : null;
        }
    }

    private static List<String> walk(XMLStreamReader r) throws XMLStreamException {
        List<String> violations = new ArrayList<>();
        Map<String, List<String>> groups = new HashMap<>();
        Deque<Frame> path = new ArrayDeque<>();
        String groupId = null;
        String owner = null;
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = r.getLocalName();
                Frame parent = path.peek();
                String parentName = parent == null ? null : parent.name;
                if (name.equals("cvParam") && parent != null && parent.accessions != null) {
                    parent.accessions.add(r.getAttributeValue(null, "accession"));
                } else if (name.equals("referenceableParamGroupRef") && parent != null && parent.accessions != null) {
                    parent.accessions.addAll(groups.getOrDefault(r.getAttributeValue(null, "ref"), List.of()));
                }
                boolean collect = switch (name) {
                    case "referenceableParamGroup" -> {
                        groupId = r.getAttributeValue(null, "id");
                        yield "referenceableParamGroupList".equals(parentName);
                    }
                    case "spectrum", "chromatogram", "instrumentConfiguration" -> {
                        boolean listed = (name + "List").equals(parentName);
                        if (listed) {
                            owner = r.getAttributeValue(null, "id");
                        }
                        yield listed;
                    }
                    case "scanList" -> "spectrum".equals(parentName);
                    case "activation" -> "precursor".equals(parentName) && grandparentIs(path, "precursorList");
                    case "binaryDataArray" -> "binaryDataArrayList".equals(parentName);
                    default -> false;
                };
                path.push(new Frame(name, collect));
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                Frame frame = path.pop();
                if (frame.accessions == null) {
                    continue;
                }
                List<String> a = frame.accessions;
                boolean ok = switch (frame.name) {
                    case "referenceableParamGroup" -> {
                        groups.put(groupId, a);
                        yield true;
                    }
                    case "spectrum" -> count(a, SPECTRUM_TYPES) == 1 && count(a, REPRESENTATIONS) == 1;
                    case "scanList" -> count(a, COMBINATIONS) == 1;
                    case "activation" -> count(a, CvTerms.DISSOCIATION_METHODS.keySet()) >= 1;
                    case "binaryDataArray" -> count(a, ARRAY_TYPES) == 1 && count(a, DATA_TYPES) == 1
                            && count(a, COMPRESSIONS) == 1;
                    case "instrumentConfiguration" -> count(a, instrumentModels()) == 1;
                    default -> count(a, CHROMATOGRAM_TYPES) == 1; // chromatogram
                };
                if (!ok) {
                    violations.add(rule(frame.name) + " @ " + owner);
                }
            }
        }
        return violations;
    }

    private static boolean grandparentIs(Deque<Frame> path, String name) {
        var it = path.iterator();
        it.next();
        return it.hasNext() && it.next().name.equals(name);
    }

    private static String rule(String element) {
        return switch (element) {
            case "spectrum" -> SPECTRUM_MUST;
            case "scanList" -> SCAN_MUST;
            case "activation" -> PRECURSOR_ACTIVATION_MUST;
            case "binaryDataArray" -> BINARYDATAARRAY_MUST;
            case "instrumentConfiguration" -> INSTRUMENTCONFIGURATION_MUST;
            default -> CHROMATOGRAM_MUST;
        };
    }

    /** How many of {@code accessions} (with repeats) are in {@code terms}. */
    private static int count(List<String> accessions, Set<String> terms) {
        int n = 0;
        for (String accession : accessions) {
            if (terms.contains(accession)) {
                n++;
            }
        }
        return n;
    }

    private static Set<String> ordered(String... accessions) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(accessions)));
    }
}
