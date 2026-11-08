package org.mzpeak.mzml;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The validity gate for mzML that tests write (mzML design 12.2): {@link MzMLSchema} validation and, for an
 * {@code <indexedmzML>} document, the {@link IndexedMzMLVerifier}. Every test that writes mzML calls
 * {@link #valid(Path)} on every option variant it writes (plain, FLOAT32/64, no zlib, each Numpress combination).
 */
public final class MzMLAssert {

    private MzMLAssert() {
    }

    /**
     * Fails with an {@link AssertionError} that lists every schema problem and every index problem of {@code file};
     * returns normally when there are none.
     */
    public static void valid(Path file) {
        List<String> problems = new ArrayList<>();
        try {
            for (String p : MzMLSchema.validate(file)) {
                problems.add("schema: " + p);
            }
            if (isIndexed(file)) {
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

    private static boolean isIndexed(Path file) throws IOException {
        try {
            return MzMLSchema.rootElement(file).equals(MzMLSchema.INDEXED_ROOT);
        } catch (XMLStreamException e) {
            return false; // not well-formed: the schema problems already say so
        }
    }
}
