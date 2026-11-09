package org.mzpeak.mzml;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Checks every {@code count} attribute of an mzML document, plain or indexed, streaming it with StAX (mzML design
 * 12.2, MJ-4): the count must equal the number of the element's child elements other than {@code cvParam},
 * {@code userParam} and {@code referenceableParamGroupRef}, so {@code scanList} counts its scans,
 * {@code componentList} its components and {@code indexList} its indexes. No other oracle checks the counts that the
 * mzML writer sets by hand: the schema accepts any non-negative count and {@code MzMLReader} ignores counts.
 */
public final class MzMLCountCheck {

    /** The child elements a count does not count. */
    static final Set<String> PARAMS = Set.of("cvParam", "userParam", "referenceableParamGroupRef");

    private MzMLCountCheck() {
    }

    /**
     * The outcome of a check.
     *
     * @param checked  how many {@code count} attributes the document has, each checked
     * @param problems one per count that does not match, as {@link MzMLCountCheck#problems(Path)} describes it
     */
    public record Result(int checked, List<String> problems) {
    }

    /**
     * The counts of {@code file} that do not match, in the order their elements end, each as
     * {@code count C but K children @ element} followed, inside a spectrum or chromatogram, by its id in brackets
     * ({@code count 2 but 1 children @ scanList [scan=19]}); an empty list when every count matches. A document that
     * is not well-formed is the one problem {@code not well-formed: …}.
     */
    public static List<String> problems(Path file) throws IOException {
        return check(file).problems();
    }

    /** The {@link #problems(Path)} of {@code file} and how many count attributes it has. */
    public static Result check(Path file) throws IOException {
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
            return new Result(0, List.of("not well-formed: " + e.getMessage()));
        }
    }

    /** One open element: its name, its count (null without one), its children so far and its spectrum's id. */
    private static final class Frame {
        final String name;
        final String count;
        final String owner;
        int children;

        Frame(String name, String count, String owner) {
            this.name = name;
            this.count = count;
            this.owner = owner;
        }
    }

    private static Result walk(XMLStreamReader r) throws XMLStreamException {
        List<String> problems = new ArrayList<>();
        Deque<Frame> path = new ArrayDeque<>();
        int checked = 0;
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = r.getLocalName();
                Frame parent = path.peek();
                if (parent != null && !PARAMS.contains(name)) {
                    parent.children++;
                }
                String owner = name.equals("spectrum") || name.equals("chromatogram")
                        ? r.getAttributeValue(null, "id") : parent == null ? null : parent.owner;
                String count = r.getAttributeValue(null, "count");
                if (count != null) {
                    checked++;
                }
                path.push(new Frame(name, count, owner));
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                Frame frame = path.pop();
                if (frame.count != null && !matches(frame.count, frame.children)) {
                    problems.add("count " + frame.count + " but " + frame.children + " children @ " + frame.name
                            + (frame.owner == null ? "" : " [" + frame.owner + "]"));
                }
            }
        }
        return new Result(checked, List.copyOf(problems));
    }

    /** Whether {@code count}, an xs:nonNegativeInteger (whitespace and leading zeros allowed), is {@code n}. */
    private static boolean matches(String count, int n) {
        try {
            return Long.parseLong(count.trim()) == n;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
