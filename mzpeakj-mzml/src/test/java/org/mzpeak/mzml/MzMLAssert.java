package org.mzpeak.mzml;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The validity gate for mzML that tests write (mzML design 12.2), for plain mzML only: the root element is
 * {@code <mzML>} (the converter writes plain mzML only), {@link MzMLSchema} validation ({@code mzML1.1.0.xsd} for the
 * plain root) and the {@link MzMLCountCheck}. Every test that writes mzML calls {@link #valid(Path)} on every zlib
 * variant it writes.
 */
public final class MzMLAssert {

    private MzMLAssert() {
    }

    /**
     * Fails with an {@link AssertionError} that lists every problem of {@code file}: a root element other than
     * {@code <mzML>}, every schema problem and every count problem; returns normally when there are none. A document
     * whose root element cannot be read has only its schema problems.
     */
    public static void valid(Path file) {
        List<String> problems = new ArrayList<>();
        try {
            String root = root(file);
            if (root != null && !MzMLSchema.PLAIN_ROOT.equals(root)) {
                problems.add("root " + root + ": the converter writes plain mzML only");
            }
            for (String p : MzMLSchema.validate(file)) {
                problems.add("schema: " + p);
            }
            if (root != null) {
                for (String p : MzMLCountCheck.problems(file)) {
                    problems.add("count: " + p);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!problems.isEmpty()) {
            throw new AssertionError(file + " is not valid mzML; " + problems.size() + " problem(s):\n  "
                    + String.join("\n  ", problems));
        }
    }

    /** The local name of the root element of {@code file}; null when the document is not well-formed up to it. */
    private static String root(Path file) throws IOException {
        try {
            return MzMLSchema.rootElement(file);
        } catch (XMLStreamException e) {
            return null; // not well-formed: the schema problems already say so
        }
    }
}
