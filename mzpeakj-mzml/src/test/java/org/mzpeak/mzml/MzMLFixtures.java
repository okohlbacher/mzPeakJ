package org.mzpeak.mzml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The vendored mzML inputs in {@code src/test/resources/mzml/} (MZ-12; sources, commits and checksums in
 * {@code UPSTREAM.txt} there, licences in NOTICE). {@link #TINY} is stored raw, the other three gzipped
 * ({@code gzip -9n}); {@link #extract(String, Path)} writes any of them, uncompressed, into a directory such as a
 * JUnit {@code @TempDir}. The fixtures are read from the test class path.
 */
public final class MzMLFixtures {

    /** ProteoWizard {@code example_data/tiny.pwiz.1.1.mzML}: 4 spectra, 2 chromatograms, ISO-8859-1, stored raw. */
    public static final String TINY = "tiny.pwiz.1.1.mzML";
    /** HUPO-PSI/mzPeak 2ee2589 {@code small.mzML}: 48 spectra; its stored SHA-1 is stale. */
    public static final String SMALL = "small.mzML";
    /** HUPO-PSI/mzPeak 2ee2589 {@code test/data/}: 212 MS and 520 UV spectra. */
    public static final String TOFSULFAS = "TOFsulfasMS4GHzDualMode+DADSpectra+UVSignal272-NoProfile.mzML";
    /** mzdata {@code test/data/diaPASEF.mzML}: 9 spectra with ion mobility arrays. */
    public static final String DIAPASEF = "diaPASEF.mzML";

    /** The four fixtures, in the order above. */
    public static final List<String> NAMES = List.of(TINY, SMALL, TOFSULFAS, DIAPASEF);

    /** The lowercase hex SHA-256 of each uncompressed fixture, pinned from its source (UPSTREAM.txt). */
    public static final Map<String, String> SHA256 = Map.of(
            TINY, "711ac14b666f14817c208bd4d39b738e96ac827574c4639d8f8f6eebbfde9c83",
            SMALL, "f7c03b94e64aa42011877b53c512e24ee702c3f66a02125ce50977d1efe9ac5f",
            TOFSULFAS, "dc3e26d2f67d1e647e6d6ebc415f5e883c5aea2747f574333ad5f8e888bcc8ed",
            DIAPASEF, "67652af39e65bf4cacd331dcb4ad041d0a930dae4ae248b93527b61c98f2a2ee");

    /** Class-path folder of the fixtures. */
    static final String RESOURCE_DIR = "/mzml/";

    private MzMLFixtures() {
    }

    /**
     * Writes the fixture {@code name}, one of {@link #NAMES}, uncompressed to {@code dir/name}, replacing a file of
     * that name, and returns its path.
     */
    public static Path extract(String name, Path dir) throws IOException {
        if (!NAMES.contains(name)) {
            throw new IllegalArgumentException("no mzML fixture " + name + "; known: " + NAMES);
        }
        boolean raw = name.equals(TINY);
        Path out = dir.resolve(name);
        try (InputStream in = open(raw ? name : name + ".gz")) {
            Files.copy(raw ? in : new GZIPInputStream(in), out, StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }

    /** The lowercase hex SHA-256 of a file's bytes. */
    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 16];
                for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                    digest.update(buffer, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Opens the class-path resource {@code /mzml/<path>}. */
    static InputStream open(String path) {
        InputStream in = MzMLFixtures.class.getResourceAsStream(RESOURCE_DIR + path);
        if (in == null) {
            throw new UncheckedIOException(new IOException("test resource " + RESOURCE_DIR + path + " not found"));
        }
        return in;
    }
}
