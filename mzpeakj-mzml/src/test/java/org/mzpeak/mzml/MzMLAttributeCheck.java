package org.mzpeak.mzml;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Checks the attributes the mzML writer computes for every spectrum and chromatogram, streaming the document with
 * StAX and decoding the arrays itself with {@link Base64} and {@link Inflater}, not with jmzML (mzML design 12.2): its
 * {@code index} is its 0-based position in its list; the {@code encodedLength} of each of its binaryDataArrays is the
 * length of the trimmed text of its {@code <binary>}; its {@code defaultArrayLength} is the number of values of each
 * of its arrays without {@code arrayLength}, decoded by the array's data type and compression terms. An array under
 * another compression than zlib or none, or without a data type term, is not decoded. No other oracle checks these:
 * the schema accepts any non-negative integer, and the readers do not compare them with the arrays.
 */
public final class MzMLAttributeCheck {

    /** Bytes per value of each binary data type the check decodes. */
    private static final Map<String, Integer> DATA_TYPES = Map.of(
            "MS:1000519", 4, // 32-bit integer
            "MS:1000521", 4, // 32-bit float
            "MS:1000522", 8, // 64-bit integer
            "MS:1000523", 8  // 64-bit float
    );
    private static final String ZLIB = "MS:1000574";
    private static final String NO_COMPRESSION = "MS:1000576";

    private MzMLAttributeCheck() {
    }

    /**
     * The attributes of {@code file} that do not match, in document order, each as
     * {@code index I but position P @ spectrum [id]}, {@code encodedLength E but L Base64 characters @ spectrum [id]}
     * or {@code defaultArrayLength D but N values in an array @ chromatogram [id]}; an empty list when all match. A
     * document that is not well-formed is the one problem {@code not well-formed: …}.
     */
    public static List<String> problems(Path file) throws IOException {
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
            return List.of("not well-formed: " + e.getMessage());
        }
    }

    private static List<String> walk(XMLStreamReader r) throws XMLStreamException {
        List<String> problems = new ArrayList<>();
        Deque<String> path = new ArrayDeque<>();
        int[] positions = new int[2]; // spectra, chromatograms
        String owner = null;
        String defaultLength = null;
        String encodedLength = null;
        String arrayLength = null;
        List<String> terms = new ArrayList<>();
        String binary = "";
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = r.getLocalName();
                String parent = path.peek();
                if ((name.equals("spectrum") && "spectrumList".equals(parent))
                        || (name.equals("chromatogram") && "chromatogramList".equals(parent))) {
                    int position = positions[name.equals("spectrum") ? 0 : 1]++;
                    owner = name + " [" + r.getAttributeValue(null, "id") + "]";
                    defaultLength = r.getAttributeValue(null, "defaultArrayLength");
                    String index = r.getAttributeValue(null, "index");
                    if (!String.valueOf(position).equals(index)) {
                        problems.add("index " + index + " but position " + position + " @ " + owner);
                    }
                } else if (name.equals("binaryDataArray")) {
                    encodedLength = r.getAttributeValue(null, "encodedLength");
                    arrayLength = r.getAttributeValue(null, "arrayLength");
                    terms = new ArrayList<>();
                    binary = "";
                } else if (name.equals("cvParam") && "binaryDataArray".equals(parent)) {
                    terms.add(r.getAttributeValue(null, "accession"));
                } else if (name.equals("binary")) {
                    binary = r.getElementText().trim();
                    continue; // getElementText ends on the binary's END_ELEMENT, which is never pushed
                }
                path.push(name);
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if (path.pop().equals("binaryDataArray") && owner != null) {
                    if (encodedLength != null && !encodedLength.equals(String.valueOf(binary.length()))) {
                        problems.add("encodedLength " + encodedLength + " but " + binary.length()
                                + " Base64 characters @ " + owner);
                    }
                    Integer values = arrayLength == null ? values(binary, terms) : null;
                    if (values != null && !String.valueOf(values).equals(defaultLength)) {
                        problems.add("defaultArrayLength " + defaultLength + " but " + values
                                + " values in an array @ " + owner);
                    }
                }
            }
        }
        return List.copyOf(problems);
    }

    /**
     * The number of values of an array with the Base64 text {@code text} and the cvParam accessions {@code terms};
     * null when its data type or compression is not one the check decodes, or its bytes cannot be decoded.
     */
    private static Integer values(String text, List<String> terms) {
        Integer size = terms.stream().map(DATA_TYPES::get).filter(s -> s != null).findFirst().orElse(null);
        boolean zlib = terms.contains(ZLIB);
        if (size == null || (!zlib && !terms.contains(NO_COMPRESSION))) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (zlib && bytes.length > 0) {
            Inflater inflater = new Inflater();
            try {
                inflater.setInput(bytes);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[1 << 16];
                while (!inflater.finished()) {
                    int n = inflater.inflate(buffer);
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                        return null;
                    }
                    out.write(buffer, 0, n);
                }
                bytes = out.toByteArray();
            } catch (DataFormatException e) {
                return null;
            } finally {
                inflater.end();
            }
        }
        return bytes.length / size;
    }
}
