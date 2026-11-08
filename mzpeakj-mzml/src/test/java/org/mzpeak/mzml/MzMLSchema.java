package org.mzpeak.mzml;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * XML Schema validation of an mzML document against the vendored PSI schemas ({@code src/test/resources/mzml/xsd/},
 * unmodified copies, NOTICE): {@code mzML1.1.2_idx.xsd} (which includes {@code mzML1.1.0.xsd}) for an
 * {@code <indexedmzML>} root, {@code mzML1.1.0.xsd} for an {@code <mzML>} root. The schemas are loaded from the test
 * class path with {@link XMLConstants#ACCESS_EXTERNAL_SCHEMA} limited to {@code file,jar:file}, so the include
 * resolves next to the including schema and nothing is fetched from the network; the validator itself may load no
 * external schema or DTD (the documents' {@code xsi:schemaLocation} hints are not followed).
 */
public final class MzMLSchema {

    /** Local name of the indexed root element. */
    public static final String INDEXED_ROOT = "indexedmzML";
    /** Local name of the plain root element. */
    public static final String PLAIN_ROOT = "mzML";

    private static final String ALLOWED_SCHEMA_PROTOCOLS = "file,jar:file";

    private static Schema indexed;
    private static Schema plain;

    private MzMLSchema() {
    }

    /**
     * Validates {@code file} against the schema its root element requires and returns every problem the validator
     * reports, each as {@code line L, column C: message}, in document order; an empty list for a valid document. A
     * root that is neither {@code indexedmzML} nor {@code mzML}, or a document that is not well-formed, is a problem.
     */
    public static List<String> validate(Path file) throws IOException {
        String root;
        try {
            root = rootElement(file);
        } catch (XMLStreamException e) {
            return List.of(e.getMessage());
        }
        Schema schema;
        if (root.equals(INDEXED_ROOT)) {
            schema = indexedSchema();
        } else if (root.equals(PLAIN_ROOT)) {
            schema = plainSchema();
        } else {
            return List.of("root element <" + root + "> is neither <" + INDEXED_ROOT + "> nor <" + PLAIN_ROOT + ">");
        }
        List<String> problems = new ArrayList<>();
        Validator validator = schema.newValidator();
        try {
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (SAXException e) {
            throw new IllegalStateException("cannot restrict external access of the validator", e);
        }
        validator.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException e) {
                // warnings do not make a document invalid
            }

            @Override
            public void error(SAXParseException e) {
                problems.add(describe(e));
            }

            @Override
            public void fatalError(SAXParseException e) throws SAXException {
                throw e;
            }
        });
        try {
            validator.validate(new StreamSource(file.toFile()));
        } catch (SAXParseException e) {
            problems.add(describe(e));
        } catch (SAXException e) {
            problems.add(e.getMessage());
        }
        return problems;
    }

    /** The local name of the root element of {@code file}. */
    public static String rootElement(Path file) throws IOException, XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            try {
                reader.nextTag();
                return reader.getLocalName();
            } finally {
                reader.close();
            }
        }
    }

    static synchronized Schema indexedSchema() {
        if (indexed == null) {
            indexed = load("mzML1.1.2_idx.xsd");
        }
        return indexed;
    }

    static synchronized Schema plainSchema() {
        if (plain == null) {
            plain = load("mzML1.1.0.xsd");
        }
        return plain;
    }

    private static Schema load(String xsd) {
        URL url = MzMLSchema.class.getResource(MzMLFixtures.RESOURCE_DIR + "xsd/" + xsd);
        if (url == null) {
            throw new IllegalStateException("schema " + xsd + " not on the test class path");
        }
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        try {
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, ALLOWED_SCHEMA_PROTOCOLS);
            return factory.newSchema(url);
        } catch (SAXException e) {
            throw new IllegalStateException("cannot load schema " + url, e);
        }
    }

    private static String describe(SAXParseException e) {
        return "line " + e.getLineNumber() + ", column " + e.getColumnNumber() + ": " + e.getMessage();
    }
}
