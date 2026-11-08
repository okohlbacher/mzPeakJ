package org.mzpeak.io;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The two vendored HUPO-PSI example sets, in the test resources of the library module {@code mzpeakj}. Their
 * root is the system property {@code mzpeak.test.resources} (the converter module's surefire configuration points
 * it at the library's), else {@code src/test/resources}, relative to the module directory, the working directory of
 * the Maven test run. When that directory has no {@code mzpeak/}, as in a test that an IDE starts in the converter
 * module without the property, the root is {@code ../mzpeakj/src/test/resources}.
 *
 * <ul>
 *   <li>{@link #CURRENT}: the 8 examples of HUPO-PSI/mzPeak 2ee2589 (split metadata facets, array index,
 *       SHA-512 checksums), byte-identical to upstream. The reader reads their spectrum metadata (RD-5),
 *       chromatogram metadata (RD-8) and wavelength-spectrum metadata (RD-9), and decodes the grid-encoded signal
 *       of diaPASEF.grid (RD-20).
 *   <li>{@link #LEGACY}: the 5 examples of HUPO-PSI/mzPeak 29e59b2 (packed metadata layout). They guard the
 *       packed layout that mzPeakJ 0.10.3 and earlier write and the existing tests run on.
 * </ul>
 *
 * Each directory holds an {@code UPSTREAM_COMMIT.txt} naming its commit.
 */
public final class Fixtures {

    /** Root of the library's test resources; see the class comment. */
    private static final Path ROOT = root();

    /** Directory of the current upstream examples (HUPO-PSI/mzPeak 2ee2589). */
    public static final Path CURRENT = ROOT.resolve("mzpeak");

    /** Directory of the pinned packed-layout examples (HUPO-PSI/mzPeak 29e59b2). */
    public static final Path LEGACY = ROOT.resolve("mzpeak-legacy");

    /** The datasets in {@link #CURRENT}: 7 single-file ZIPs and one unpacked directory. */
    public static final List<String> CURRENT_NAMES = List.of(
            "small.mzpeak",
            "small.chunked.mzpeak",
            "small.numpress.mzpeak",
            "small.unpacked.mzpeak",
            "has_uv.mzpeak",
            "diaPASEF.ref.mzpeak",
            "diaPASEF.grid.mzpeak",
            "Example_Processed.img.mzpeak");

    /** The datasets in {@link #LEGACY}: 4 single-file ZIPs and one unpacked directory. */
    public static final List<String> LEGACY_NAMES = List.of(
            "small.mzpeak",
            "small.chunked.mzpeak",
            "small.numpress.mzpeak",
            "small.unpacked.mzpeak",
            "has_uv.mzpeak");

    private Fixtures() {
    }

    private static Path root() {
        Path root = Path.of(System.getProperty("mzpeak.test.resources", "src/test/resources"));
        return Files.isDirectory(root.resolve("mzpeak")) ? root : Path.of("../mzpeakj/src/test/resources");
    }

    /** The current dataset {@code name}, one of {@link #CURRENT_NAMES}. */
    public static Path current(String name) {
        return resolve(CURRENT, CURRENT_NAMES, name);
    }

    /** The legacy dataset {@code name}, one of {@link #LEGACY_NAMES}. */
    public static Path legacy(String name) {
        return resolve(LEGACY, LEGACY_NAMES, name);
    }

    private static Path resolve(Path dir, List<String> names, String name) {
        if (!names.contains(name)) {
            throw new IllegalArgumentException("no fixture " + name + " in " + dir + "; known: " + names);
        }
        return dir.resolve(name);
    }
}
