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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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
 *   <li>every {@code <offset>} lands on a {@code <spectrum>} or {@code <chromatogram>} start tag (as its
 *       {@code <index>} names), and that start tag carries the {@code id} the offset's {@code idRef} names;
 *   <li>the offsets of each index are in document order (strictly increasing);
 *   <li>{@code <indexListOffset>} lands on the {@code <indexList>} start tag;
 *   <li>{@code indexList@count} equals the number of {@code <index>} elements, and no {@code <index>} is empty
 *       unless it is the only one;
 *   <li>the {@code index} attributes of the spectra, and of the chromatograms, run 0..n-1 in document order;
 *   <li>list counts match: {@code spectrumList@count} equals the number of spectra and the number of spectrum
 *       offsets, likewise for chromatograms;
 *   <li>{@code <fileChecksum>} holds the lowercase hex SHA-1 of the bytes from the start of the file to the end of
 *       the {@code <fileChecksum>} start tag.
 * </ul>
 * Offsets are byte offsets; the document must be in an ASCII-compatible encoding (UTF-8, ISO-8859-1, US-ASCII),
 * which every mzML writer uses. The start tags are the real ones (MJ-4): a pass over the bytes skips comments,
 * processing instructions, CDATA sections, declarations and quoted attribute values, so text that only looks like a
 * start tag satisfies no offset and no checksum range, and any XML whitespace may follow the element name
 * ({@code <spectrum\tindex=...>}, {@code <fileChecksum >}).
 */
public final class IndexedMzMLVerifier {

    private static final String SPECTRUM = "spectrum";
    private static final String CHROMATOGRAM = "chromatogram";
    private static final String INDEX_LIST = "indexList";
    private static final String FILE_CHECKSUM = "fileChecksum";
    private static final Set<String> ASCII_COMPATIBLE = Set.of("UTF-8", "ISO-8859-1", "US-ASCII");
    private static final Pattern ATTRIBUTE = Pattern.compile("([^\\s=/>]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
    private static final String NOT_A_START_TAG = " (not a start tag: it lies in a comment, CDATA section, processing"
            + " instruction or declaration)";

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

    /** What the pass over the bytes collects: the byte positions of the real start tags the index refers to. */
    private static final class StartTags {
        /** Per element name, the position of each {@code <spectrum>} or {@code <chromatogram>} tag and its id. */
        final Map<String, Map<Long, String>> ids = Map.of(SPECTRUM, new HashMap<>(), CHROMATOGRAM, new HashMap<>());
        final Set<Long> indexLists = new HashSet<>();
        /** The position after the {@code >} of the last {@code <fileChecksum>} start tag; -1 when there is none. */
        long fileChecksumEnd = -1;
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
        StartTags tags = startTags(file, charset);
        List<String> problems = new ArrayList<>();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            checkIndexList(s, problems);
            for (String kind : List.of(SPECTRUM, CHROMATOGRAM)) {
                checkKind(kind, s, tags, channel, charset, problems);
            }
            checkIndexListOffset(s, tags, channel, charset, problems);
            checkChecksum(s, tags, channel, problems);
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

    private static void checkKind(String kind, Structure s, StartTags tags, FileChannel channel, Charset charset,
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
        Map<Long, String> ids = tags.ids.get(kind);
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
            if (!ids.containsKey(position)) {
                problems.add(missed(what, kind, "<" + kind + " ", channel, position, charset));
                continue;
            }
            String id = ids.get(position);
            if (!offset.idRef().equals(id)) {
                problems.add(what + " lands on the " + kind + " with id '" + id + "'");
            }
        }
    }

    private static void checkIndexListOffset(Structure s, StartTags tags, FileChannel channel, Charset charset,
                                             List<String> problems) throws IOException {
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
        if (!tags.indexLists.contains(position)) {
            problems.add(missed(what, INDEX_LIST, "<" + INDEX_LIST, channel, position, charset));
        }
    }

    private static void checkChecksum(Structure s, StartTags tags, FileChannel channel, List<String> problems)
            throws IOException {
        if (s.fileChecksum == null) {
            problems.add("no <fileChecksum>");
            return;
        }
        if (tags.fileChecksumEnd < 0) {
            problems.add("no <fileChecksum> start tag in the bytes of the file");
            return;
        }
        String computed = sha1(channel, tags.fileChecksumEnd);
        if (!computed.equals(s.fileChecksum)) {
            problems.add("fileChecksum: stored " + s.fileChecksum + ", computed " + computed
                    + " (SHA-1 of the bytes up to the end of the <fileChecksum> start tag)");
        }
    }

    /**
     * The problem of an offset {@code what} that lands on no {@code name} start tag: the excerpt there and, when its
     * text looks like that start tag, the note that it lies in a comment, CDATA section, processing instruction or
     * declaration.
     */
    private static String missed(String what, String name, String expected, FileChannel channel, long position,
                                 Charset charset) throws IOException {
        String excerpt = excerpt(channel, position, charset);
        boolean looksLikeTheTag = excerpt.length() > name.length() + 1 && excerpt.startsWith("<" + name)
                && isTagNameEnd(excerpt.charAt(name.length() + 1));
        return what + " does not land on '" + expected + "' but on '" + excerpt + "'"
                + (looksLikeTheTag ? NOT_A_START_TAG : "");
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
            case INDEX_LIST -> {
                s.hasIndexList = true;
                s.indexListCount = r.getAttributeValue(null, "count");
            }
            case "index" -> {
                if (INDEX_LIST.equals(parent)) {
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
            case FILE_CHECKSUM -> {
                s.fileChecksum = r.getElementText();
                return true;
            }
            default -> {
            }
        }
        return false;
    }

    // ---- pass over the bytes -------------------------------------------------------------------------------------

    /** A file's bytes, read forward through a buffer, with the position of the next byte. */
    private static final class Bytes {
        private final InputStream in;
        private final byte[] buffer = new byte[1 << 16];
        private int length;
        private int at;
        /** The position of the byte the next {@link #next()} returns. */
        long position;

        Bytes(InputStream in) {
            this.in = in;
        }

        /** The next byte (0..255), or -1 at the end of the file. */
        int next() throws IOException {
            if (at == length) {
                length = Math.max(0, in.read(buffer));
                at = 0;
                if (length == 0) {
                    return -1;
                }
            }
            position++;
            return buffer[at++] & 0xff;
        }
    }

    /**
     * The real start tags of {@code file} that the index refers to, found by a pass over its bytes (in an
     * ASCII-compatible {@code charset}, every markup character is one byte and no byte of a multi-byte character
     * is one): a {@code <} in character data opens markup; comments, processing instructions, CDATA sections,
     * declarations and end tags are skipped to their end, and a start tag ends at the first {@code >} outside a
     * quoted attribute value. An element name ends at XML whitespace, {@code /} or {@code >}; a prefix is ignored.
     * The document is well-formed (the StAX pass read it).
     */
    private static StartTags startTags(Path file, Charset charset) throws IOException {
        StartTags tags = new StartTags();
        try (InputStream in = Files.newInputStream(file)) {
            Bytes b = new Bytes(in);
            int c = b.next();
            while (c >= 0) {
                if (c != '<') {
                    c = b.next();
                    continue;
                }
                long start = b.position - 1;
                c = b.next();
                if (c == '?') {
                    c = skipPast(b, "?>");
                } else if (c == '/') {
                    c = skipPast(b, ">");
                } else if (c == '!') {
                    c = markupDeclaration(b);
                } else {
                    c = startTag(b, start, c, charset, tags);
                }
            }
        }
        return tags;
    }

    /** Reads up to and including the first occurrence of {@code end}; returns the byte after it, or -1. */
    private static int skipPast(Bytes b, String end) throws IOException {
        int n = end.length();
        int[] last = new int[n]; // the last n bytes read, as a ring
        long count = 0;
        for (int c = b.next(); c >= 0; c = b.next()) {
            last[(int) (count++ % n)] = c;
            if (count >= n && endsWith(last, count, end)) {
                return b.next();
            }
        }
        return -1;
    }

    /** Whether the ring {@code last}, after {@code count} bytes, ends with {@code end}. */
    private static boolean endsWith(int[] last, long count, String end) {
        int n = end.length();
        for (int i = 0; i < n; i++) {
            if (last[(int) ((count - n + i) % n)] != end.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * After {@code <!}: a comment ({@code <!--} to {@code -->}), a CDATA section ({@code <![CDATA[} to {@code ]]>})
     * or a declaration such as {@code <!DOCTYPE …>} (to the first {@code >} outside quotes and brackets); returns the
     * byte after it, or -1.
     */
    private static int markupDeclaration(Bytes b) throws IOException {
        int c = b.next();
        if (c == '-') {
            b.next(); // the second '-' of '<!--'
            return skipPast(b, "-->");
        }
        if (c == '[') {
            for (int i = 0; i < "CDATA[".length(); i++) {
                b.next();
            }
            return skipPast(b, "]]>");
        }
        int quote = 0;
        int depth = 0;
        for (; c >= 0; c = b.next()) {
            if (quote != 0) {
                quote = c == quote ? 0 : quote;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == '>' && depth == 0) {
                return b.next();
            }
        }
        return -1;
    }

    /**
     * A start tag whose name begins with {@code c}, its {@code <} at {@code start}: records it when it is one the
     * index refers to, and returns the byte after its {@code >}, or -1.
     */
    private static int startTag(Bytes b, long start, int c, Charset charset, StartTags tags) throws IOException {
        ByteArrayOutputStream tag = new ByteArrayOutputStream();
        tag.write('<');
        while (c >= 0 && !isTagNameEnd((char) c)) {
            tag.write(c);
            c = b.next();
        }
        String qualified = new String(tag.toByteArray(), 1, tag.size() - 1, charset);
        String name = qualified.substring(qualified.indexOf(':') + 1);
        boolean keep = name.equals(SPECTRUM) || name.equals(CHROMATOGRAM);
        int quote = 0;
        for (; c >= 0; c = b.next()) {
            if (keep) {
                tag.write(c);
            }
            if (quote != 0) {
                quote = c == quote ? 0 : quote;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                break;
            }
        }
        if (c < 0) {
            return -1;
        }
        switch (name) {
            case SPECTRUM, CHROMATOGRAM -> tags.ids.get(name).put(start,
                    attribute(new String(tag.toByteArray(), charset), "id"));
            case INDEX_LIST -> tags.indexLists.add(start);
            case FILE_CHECKSUM -> tags.fileChecksumEnd = b.position;
            default -> {
            }
        }
        return b.next();
    }

    /** Whether {@code c} ends an element name: XML whitespace, {@code /} or {@code >}. */
    private static boolean isTagNameEnd(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '/' || c == '>';
    }

    // ---- bytes ---------------------------------------------------------------------------------------------------

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
