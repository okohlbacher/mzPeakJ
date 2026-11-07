package org.mzpeak.io;

import java.nio.file.Path;
import java.util.List;

/**
 * The two vendored HUPO-PSI example sets. Paths are relative to the project root, the working directory
 * of the Maven test run.
 *
 * <ul>
 *   <li>{@link #CURRENT}: the 8 examples of HUPO-PSI/mzPeak 2ee2589 (split metadata facets, array index,
 *       SHA-512 checksums), byte-identical to upstream. The reader reads their spectrum metadata; their
 *       chromatogram and wavelength-spectrum metadata are not supported yet.
 *   <li>{@link #LEGACY}: the 5 examples of HUPO-PSI/mzPeak 29e59b2 (packed metadata layout). They guard the
 *       packed layout that mzPeakJ 0.10.3 and earlier write and the existing tests run on.
 * </ul>
 *
 * Each directory holds an {@code UPSTREAM_COMMIT.txt} naming its commit.
 */
public final class Fixtures {

    /** Directory of the current upstream examples (HUPO-PSI/mzPeak 2ee2589). */
    public static final Path CURRENT = Path.of("src/test/resources/mzpeak");

    /** Directory of the pinned packed-layout examples (HUPO-PSI/mzPeak 29e59b2). */
    public static final Path LEGACY = Path.of("src/test/resources/mzpeak-legacy");

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
