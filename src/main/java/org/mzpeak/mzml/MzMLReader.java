package org.mzpeak.mzml;

import org.mzpeak.io.MzPeakException;
import org.mzpeak.io.MzPeakManifest;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Param;
import org.mzpeak.model.Spectrum;
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

import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads an mzML 1.1 document, bare or wrapped in {@code indexedmzML}, into mzPeakJ's model (mzML design 3, 5, 8.1).
 * The document is read with StAX: namespace-aware, elements matched by local name (so a document without the mzML
 * namespace reads the same), attributes without a namespace; DTDs and external entities are not supported, and a
 * DTD is rejected. The input index and checksum are not read.
 *
 * <p>{@link #header()} reads the document up to the run's {@code spectrumList} or {@code chromatogramList}, or the
 * end of the run: the cv list, the file description (contents, source files, contacts), samples, software, scan
 * settings, instrument configurations, data processings and the run (design 5.1). Ids and strings are kept
 * verbatim; param values are typed by {@link MzMLTerms#value}, and a {@code referenceableParamGroupRef} is replaced by
 * the group's params, before the element's own params (design 5.3). What the model cannot hold is counted in
 * {@link #warnings()} (design 6.1).
 *
 * <p>Invalid input is rejected with an {@link MzPeakException} naming the file, and the line and column where the
 * parser found the fault: malformed XML, a DTD, a root that is neither {@code mzML} nor {@code indexedmzML} around
 * {@code mzML}, an mzML version that does not start with {@code 1.1}, no {@code run}, a
 * {@code referenceableParamGroupRef} naming no group, a missing required attribute this reader needs, and an
 * {@code order} that is not an integer. A file that cannot be read gives an {@link MzPeakException} with the
 * {@link IOException} as its cause.
 */
public final class MzMLReader {

    // The losses of design 6.1 this reader counts, one line each in warnings().
    static final String B_1 = "B-1 referenceableParamGroups expanded inline";
    static final String B_3 = "B-3 mzML@id and mzML@accession lost";
    static final String B_5 = "B-5 instrumentConfiguration ids %s numbered by position";
    static final String B_6 = "B-6 instrumentConfiguration@scanSettingsRef lost";
    static final String B_7 = "B-7 run@sampleRef lost";
    static final String B_14_TYPE = "B-14 userParam values that do not match their type kept as text";
    static final String B_30 = "B-30 cvRef aliases replaced by the accession prefix";

    private static final String CONTACT_NAME = "MS:1000586";
    private static final String CONTACT_AFFILIATION = "MS:1000590";
    /** The component elements of a {@code componentList}, in the order the model lists them. */
    private static final List<String> COMPONENTS = List.of("source", "analyzer", "detector");

    private final Path mzml;
    private final Losses losses = new Losses();
    /** The params of each referenceableParamGroup, by id; filled by {@link #header()}. */
    private final Map<String, List<Param>> groups = new HashMap<>();
    /** Each referenceableParamGroup, by id, for the value texts of its cvParams; filled by {@link #header()}. */
    private final Map<String, Node> groupElements = new HashMap<>();
    private Header header;

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
            header = readHeader();
        }
        return header;
    }

    /** What was lost so far (design 6.1): one line per kind, {@code <kind> (<count>)}, in first-seen order. */
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
     * @param spectrumArrays        the extra array keys of the MS spectra, first seen first
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

    // ---- reading ---------------------------------------------------------------------------------------------------

    private Header readHeader() {
        try (InputStream in = Files.newInputStream(mzml)) {
            XMLStreamReader r = factory().createXMLStreamReader(in);
            try {
                return readHeader(r);
            } finally {
                r.close();
            }
        } catch (IOException e) {
            throw new MzPeakException("mzML " + mzml + ": cannot read: " + e.getMessage(), e);
        } catch (XMLStreamException e) {
            throw malformed(mzml, e);
        }
    }

    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
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

    /** {@code node}, just started, with its child elements read up to its end tag. */
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
        MsRun msRun = new MsRun(run.attr("id"), icIds.map().get(run.attr("defaultInstrumentConfigurationRef")),
                defaultDataProcessing, run.attr("defaultSourceFileRef"), run.attr("startTimeStamp"), params(run));
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
     * it) the affiliation, first in the order of {@link #params}; each is its cvParam's value text, verbatim, an empty
     * value null (design 2, 6.2). The other params stay params.
     */
    private Contact contact(Node contact) {
        List<Param> params = params(contact);
        List<Node> cvParams = new ArrayList<>();
        for (Node ref : contact.children("referenceableParamGroupRef")) {
            cvParams.addAll(groupElements.get(ref.attr("ref")).children("cvParam")); // params() checked the ref
        }
        cvParams.addAll(contact.children("cvParam"));
        List<Param> others = new ArrayList<>(params);
        Param.find(params, CONTACT_NAME).ifPresent(others::remove);
        Param.find(params, CONTACT_AFFILIATION).ifPresent(others::remove);
        return new Contact(text(cvParams, CONTACT_NAME), text(cvParams, CONTACT_AFFILIATION), others);
    }

    /** The value text of the first of {@code cvParams} with {@code accession}; null if there is none or it is empty. */
    private static String text(List<Node> cvParams, String accession) {
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
                case "userParam" -> own.add(new Param(child.attr("name"), null, value(child, child.attr("type")),
                        child.attr("unitAccession")));
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
            losses.note(B_30);
        }
        return new Param(cvParam.attr("name"), accession, value(cvParam, null), cvParam.attr("unitAccession"));
    }

    private Object value(Node param, String type) {
        MzMLTerms.Typed typed = MzMLTerms.value(param.attr("value"), type);
        if (typed.typeMismatch()) {
            losses.note(B_14_TYPE);
        }
        return typed.value();
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

    private MzPeakException error(Node node, String message) {
        return new MzPeakException(place(mzml, node.line, node.column) + message);
    }

    private MzPeakException error(Location at, String message) {
        return new MzPeakException(place(mzml, at.getLineNumber(), at.getColumnNumber()) + message);
    }

    private static String place(Path file, int line, int column) {
        return "mzML " + file + " line " + line + " column " + column + ": ";
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

    /** An element read whole: its local name, attributes without a namespace, child elements and position. */
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
