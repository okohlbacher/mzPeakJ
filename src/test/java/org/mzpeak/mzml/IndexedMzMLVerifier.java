package org.mzpeak.mzml;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the index wrapper of an indexedmzML file (mzML 1.1 specification, appendix A; mzML1.1.2_idx.xsd) against
 * the bytes of the file, which the schema cannot do:
 * <ul>
 *   <li>every {@code <offset>} lands on {@code <spectrum } or {@code <chromatogram } (as its {@code <index>} names),
 *       and that start tag carries the {@code id} the offset's {@code idRef} names;
 *   <li>the offsets of each index are in document order (strictly increasing);
 *   <li>{@code <indexListOffset>} lands on {@code <indexList};
 *   <li>{@code indexList@count} equals the number of {@code <index>} elements, and no {@code <index>} is empty
 *       unless it is the only one;
 *   <li>the {@code index} attributes of the spectra, and of the chromatograms, run 0..n-1 in document order;
 *   <li>list counts match: {@code spectrumList@count} equals the number of spectra and the number of spectrum
 *       offsets, likewise for chromatograms;
 *   <li>{@code <fileChecksum>} holds the lowercase hex SHA-1 of the bytes from the start of the file to the end of
 *       the {@code <fileChecksum>} start tag.
 * </ul>
 * Offsets are byte offsets; the document must be in an ASCII-compatible encoding (UTF-8, ISO-8859-1, US-ASCII),
 * which every mzML writer uses.
 */
public final class IndexedMzMLVerifier {

    private static final String SPECTRUM = "spectrum";
    private static final String CHROMATOGRAM = "chromatogram";
    private static final Set<String> ASCII_COMPATIBLE = Set.of("UTF-8", "ISO-8859-1", "US-ASCII");
    private static final String CHECKSUM_TAG = "<fileChecksum>";
    private static final int MAX_START_TAG = 1 << 20;
    private static final Pattern ATTRIBUTE = Pattern.compile("([^\\s=/>]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");

    private IndexedMzMLVerifier() {
    }

    /** One {@code <spectrum>} or {@code <chromatogram>} of the document: its {@code index} and {@code id}. */
    private record Element(String index, String id) {
    }

    /** One {@code <offset>}: its {@code idRef} and its text. */
    private record Offset(String idRef, String value) {
    }

    /** What the StAX pass collects. */
    private static final class Structure {
        String root;
        String encoding;
        final Map<String, String> listCounts = new LinkedHashMap<>();
        final Map<String, List<Element>> elements =
                Map.of(SPECTRUM, new ArrayList<>(), CHROMATOGRAM, new ArrayList<>());
        boolean hasIndexList;
        String indexListCount;
        final List<String> indexNames = new ArrayList<>();
        final List<List<Offset>> indexOffsets = new ArrayList<>();
        String indexListOffset;
        String fileChecksum;
    }

    /**
     * The problems of {@code file}'s index wrapper, each naming the offset, count or checksum at fault; an empty list
     * when the index is consistent with the file.
     */
    public static List<String> verify(Path file) throws IOException {
        Structure s;
        try {
            s = scan(file);
        } catch (XMLStreamException e) {
            return List.of("not well-formed: " + e.getMessage());
        }
        if (!s.root.equals(MzMLSchema.INDEXED_ROOT)) {
            return List.of("root element <" + s.root + "> is not <" + MzMLSchema.INDEXED_ROOT + ">");
        }
        Charset charset = Charset.forName(s.encoding);
        if (!ASCII_COMPATIBLE.contains(charset.name())) {
            return List.of("encoding " + s.encoding + " is not ASCII-compatible; byte offsets are not checked");
        }
        List<String> problems = new ArrayList<>();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            checkIndexList(s, problems);
            for (String kind : List.of(SPECTRUM, CHROMATOGRAM)) {
                checkKind(kind, s, channel, charset, problems);
            }
            checkIndexListOffset(s, channel, problems);
            checkChecksum(s, channel, problems);
        }
        return problems;
    }

    private static void checkIndexList(Structure s, List<String> problems) {
        if (!s.hasIndexList) {
            problems.add("no <indexList>");
            return;
        }
        String expected = String.valueOf(s.indexNames.size());
        if (!expected.equals(s.indexListCount)) {
            problems.add("indexList count=\"" + s.indexListCount + "\" but " + expected + " <index> elements");
        }
        for (int i = 0; i < s.indexNames.size(); i++) {
            String name = s.indexNames.get(i);
            if (!SPECTRUM.equals(name) && !CHROMATOGRAM.equals(name)) {
                problems.add("<index name=\"" + name + "\"> is neither spectrum nor chromatogram");
            } else if (s.indexNames.indexOf(name) != i) {
                problems.add("<index name=\"" + name + "\"> occurs more than once");
            }
            if (s.indexOffsets.get(i).isEmpty() && s.indexNames.size() > 1) {
                problems.add("<index name=\"" + name + "\"> is empty but not the only <index>");
            }
        }
    }

    private static void checkKind(String kind, Structure s, FileChannel channel, Charset charset,
                                  List<String> problems) throws IOException {
        List<Element> elements = s.elements.get(kind);
        String listCount = s.listCounts.get(kind + "List");
        if (listCount != null && !listCount.equals(String.valueOf(elements.size()))) {
            problems.add(kind + "List count=\"" + listCount + "\" but " + elements.size() + " <" + kind
                    + "> elements");
        }
        for (int i = 0; i < elements.size(); i++) {
            if (!String.valueOf(i).equals(elements.get(i).index())) {
                problems.add("<" + kind + "> '" + elements.get(i).id() + "' at position " + i + " has index=\""
                        + elements.get(i).index() + "\"; index must run 0.." + (elements.size() - 1));
                break;
            }
        }
        int at = s.indexNames.indexOf(kind);
        List<Offset> offsets = at < 0 ? List.of() : s.indexOffsets.get(at);
        if (offsets.size() != elements.size() && s.hasIndexList) {
            problems.add(kind + " index has " + offsets.size() + " offsets for " + elements.size() + " <" + kind
                    + "> elements");
        }
        byte[] prefix = ("<" + kind + " ").getBytes(StandardCharsets.US_ASCII);
        long previous = -1;
        for (Offset offset : offsets) {
            String what = kind + " offset " + offset.value() + " for idRef '" + offset.idRef() + "'";
            long position;
            try {
                position = Long.parseLong(offset.value().trim());
            } catch (NumberFormatException e) {
                problems.add(what + " is not a number");
                continue;
            }
            if (position < 0 || position >= channel.size()) {
                problems.add(what + " is outside the file (" + channel.size() + " bytes)");
                continue;
            }
            if (position <= previous) {
                problems.add(what + " is not after the previous offset " + previous + " (offsets must follow "
                        + "document order)");
            }
            previous = position;
            byte[] tag = startTag(channel, position);
            if (!startsWith(tag, prefix)) {
                problems.add(what + " does not land on '<" + kind + " ' but on '" + excerpt(channel, position, charset)
                        + "'");
                continue;
            }
            String id = attribute(new String(tag, charset), "id");
            if (!offset.idRef().equals(id)) {
                problems.add(what + " lands on the " + kind + " with id '" + id + "'");
            }
        }
    }

    private static void checkIndexListOffset(Structure s, FileChannel channel, List<String> problems)
            throws IOException {
        if (s.indexListOffset == null) {
            problems.add("no <indexListOffset>");
            return;
        }
        String what = "indexListOffset " + s.indexListOffset;
        long position;
        try {
            position = Long.parseLong(s.indexListOffset.trim());
        } catch (NumberFormatException e) {
            problems.add(what + " is not a number");
            return;
        }
        if (position < 0 || position >= channel.size()) {
            problems.add(what + " is outside the file (" + channel.size() + " bytes)");
            return;
        }
        byte[] tag = startTag(channel, position);
        String text = new String(tag, StandardCharsets.ISO_8859_1);
        if (!text.matches("<indexList[\\s>][\\s\\S]*")) {
            problems.add(what + " does not land on '<indexList' but on '"
                    + excerpt(channel, position, StandardCharsets.ISO_8859_1) + "'");
        }
    }

    private static void checkChecksum(Structure s, FileChannel channel, List<String> problems) throws IOException {
        if (s.fileChecksum == null) {
            problems.add("no <fileChecksum>");
            return;
        }
        long end = lastIndexOf(channel, CHECKSUM_TAG.getBytes(StandardCharsets.US_ASCII));
        if (end < 0) {
            problems.add("no '" + CHECKSUM_TAG + "' in the bytes of the file");
            return;
        }
        String computed = sha1(channel, end + CHECKSUM_TAG.length());
        if (!computed.equals(s.fileChecksum)) {
            problems.add("fileChecksum: stored " + s.fileChecksum + ", computed " + computed
                    + " (SHA-1 of the bytes up to the end of '" + CHECKSUM_TAG + "')");
        }
    }

    // ---- StAX pass -----------------------------------------------------------------------------------------------

    private static Structure scan(Path file) throws IOException, XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        Structure s = new Structure();
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader r = factory.createXMLStreamReader(in);
            try {
                Deque<String> path = new ArrayDeque<>();
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.END_ELEMENT) {
                        path.pop();
                        continue;
                    }
                    if (event != XMLStreamConstants.START_ELEMENT) {
                        continue;
                    }
                    String name = r.getLocalName();
                    String parent = path.peek();
                    if (parent == null) {
                        s.root = name;
                        String declared = r.getCharacterEncodingScheme();
                        s.encoding = declared != null ? declared : r.getEncoding() != null ? r.getEncoding() : "UTF-8";
                        if (!name.equals(MzMLSchema.INDEXED_ROOT)) {
                            return s;
                        }
                    }
                    if (startElement(s, r, name, parent)) {
                        continue;
                    }
                    path.push(name);
                }
            } finally {
                r.close();
            }
        }
        return s;
    }

    /** Records one start element; true when it consumed the element up to its end tag. */
    private static boolean startElement(Structure s, XMLStreamReader r, String name, String parent)
            throws XMLStreamException {
        switch (name) {
            case "spectrumList", "chromatogramList" -> {
                if ("run".equals(parent)) {
                    s.listCounts.put(name, r.getAttributeValue(null, "count"));
                }
            }
            case SPECTRUM, CHROMATOGRAM -> {
                if ((name + "List").equals(parent)) {
                    s.elements.get(name).add(new Element(r.getAttributeValue(null, "index"),
                            r.getAttributeValue(null, "id")));
                }
            }
            case "indexList" -> {
                s.hasIndexList = true;
                s.indexListCount = r.getAttributeValue(null, "count");
            }
            case "index" -> {
                if ("indexList".equals(parent)) {
                    s.indexNames.add(r.getAttributeValue(null, "name"));
                    s.indexOffsets.add(new ArrayList<>());
                }
            }
            case "offset" -> {
                if ("index".equals(parent)) {
                    String idRef = r.getAttributeValue(null, "idRef");
                    s.indexOffsets.get(s.indexOffsets.size() - 1).add(new Offset(idRef, r.getElementText()));
                    return true;
                }
            }
            case "indexListOffset" -> {
                s.indexListOffset = r.getElementText();
                return true;
            }
            case "fileChecksum" -> {
                s.fileChecksum = r.getElementText();
                return true;
            }
            default -> {
            }
        }
        return false;
    }

    // ---- bytes ---------------------------------------------------------------------------------------------------

    /** The bytes from {@code position} to the first {@code >} outside quotes (inclusive), at most 1 MiB. */
    private static byte[] startTag(FileChannel channel, long position) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        byte quote = 0;
        long at = position;
        while (out.size() < MAX_START_TAG) {
            buffer.clear();
            int n = channel.read(buffer, at);
            if (n <= 0) {
                break;
            }
            at += n;
            for (int i = 0; i < n; i++) {
                byte b = buffer.get(i);
                out.write(b);
                if (quote != 0) {
                    quote = b == quote ? 0 : quote;
                } else if (b == '"' || b == '\'') {
                    quote = b;
                } else if (b == '>') {
                    return out.toByteArray();
                }
            }
        }
        return out.toByteArray();
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** Up to 40 bytes from {@code position}, for a problem message. */
    private static String excerpt(FileChannel channel, long position, Charset charset) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(40);
        channel.read(buffer, position);
        String text = new String(buffer.array(), 0, buffer.position(), charset);
        return text.replace("\n", "\\n").replace("\r", "\\r");
    }

    /** The value of attribute {@code name} in a start tag, unescaped and normalized as XML does; null if absent. */
    static String attribute(String startTag, String name) {
        Matcher m = ATTRIBUTE.matcher(startTag);
        while (m.find()) {
            if (m.group(1).equals(name)) {
                return unescape(m.group(3) != null ? m.group(3) : m.group(4));
            }
        }
        return null;
    }

    private static String unescape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                out.append(' ');
            } else if (c == '&') {
                int semi = raw.indexOf(';', i);
                String entity = semi < 0 ? "" : raw.substring(i + 1, semi);
                String value = switch (entity) {
                    case "lt" -> "<";
                    case "gt" -> ">";
                    case "amp" -> "&";
                    case "quot" -> "\"";
                    case "apos" -> "'";
                    default -> entity.startsWith("#x") ? Character.toString(Integer.parseInt(entity.substring(2), 16))
                            : entity.startsWith("#") ? Character.toString(Integer.parseInt(entity.substring(1)))
                            : null;
                };
                if (value == null) {
                    out.append(c);
                } else {
                    out.append(value);
                    i = semi;
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The position of the last occurrence of {@code needle} in the file, or -1. */
    private static long lastIndexOf(FileChannel channel, byte[] needle) throws IOException {
        int block = 1 << 16;
        long end = channel.size();
        while (end > 0) {
            long start = Math.max(0, end - block);
            ByteBuffer buffer = ByteBuffer.allocate((int) (Math.min(end + needle.length - 1, channel.size()) - start));
            channel.read(buffer, start);
            byte[] bytes = buffer.array();
            for (int i = bytes.length - needle.length; i >= 0; i--) {
                int j = 0;
                while (j < needle.length && bytes[i + j] == needle[j]) {
                    j++;
                }
                if (j == needle.length) {
                    return start + i;
                }
            }
            end = start;
        }
        return -1;
    }

    /** The lowercase hex SHA-1 of the first {@code length} bytes of the file. */
    private static String sha1(FileChannel channel, long length) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        ByteBuffer buffer = ByteBuffer.allocate(1 << 16);
        long at = 0;
        while (at < length) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), length - at));
            int n = channel.read(buffer, at);
            if (n <= 0) {
                break;
            }
            buffer.flip();
            digest.update(buffer);
            at += n;
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
