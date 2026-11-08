package org.mzpeak.mzml;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The validity gate for mzML that tests write (mzML design 12.2): {@link MzMLSchema} validation, the
 * {@link MzMLCountCheck} and, for an {@code <indexedmzML>} document, the {@link IndexedMzMLVerifier}. Every test that
 * writes mzML calls {@link #valid(Path)} on every option variant it writes (plain, FLOAT32/64, no zlib, each Numpress
 * combination).
 */
public final class MzMLAssert {

    private MzMLAssert() {
    }

    /**
     * Fails with an {@link AssertionError} that lists every schema problem, every count problem and every index
     * problem of {@code file}; returns normally when there are none. A document whose root element cannot be read
     * has only its schema problems.
     */
    public static void valid(Path file) {
        List<String> problems = new ArrayList<>();
        try {
            for (String p : MzMLSchema.validate(file)) {
                problems.add("schema: " + p);
            }
            String root = root(file);
            if (root != null) {
                for (String p : MzMLCountCheck.problems(file)) {
                    problems.add("count: " + p);
                }
            }
            if (MzMLSchema.INDEXED_ROOT.equals(root)) {
                for (String p : IndexedMzMLVerifier.verify(file)) {
                    problems.add("index: " + p);
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
