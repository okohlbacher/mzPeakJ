package org.mzpeak.mzml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.ac.ebi.jmzml.xml.io.MzMLUnmarshaller;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The jmzML jar the converter is built and tested with is the published jmzML 1.7.11 (design D-2): its SHA-256 is
 * pinned here, next to the SHA-512 and SHA-1 the resolver checks from {@code .mvn/checksums}, so that a build which
 * bypasses {@code .mvn/maven.config} still fails on a changed jar. The jar is found through the {@code CodeSource} of
 * {@link MzMLUnmarshaller}: the test classpath holds the resolved jar, not the shaded one.
 */
class JmzmlArtifactTest {

    /** SHA-256 of uk.ac.ebi.jmzml:jmzml:jar:1.7.11 (SHA-1 e76db69fa04955b62e4a6d115e9f588bff451dd6). */
    static final String JMZML_SHA256 = "4811a2bafc06a94bc40ff5c538fd8ab76f184ade07cd39356161bfc5d212302c";

    @TempDir
    Path tmp;

    @Test
    void jmzmlJarOnTheClasspathIsThePinnedOne() throws Exception {
        Path jar = jarOf(MzMLUnmarshaller.class);

        assertThat(jar.getFileName()).hasToString("jmzml-1.7.11.jar");
        assertThat(Files.isRegularFile(jar)).isTrue();
        assertThat(sha256(jar)).isEqualTo(JMZML_SHA256);
        assertPinned(jar);
    }

    @Test
    void aJarWithOneFlippedByteFailsThePin() throws Exception {
        Path copy = tmp.resolve("jmzml-1.7.11.jar");
        byte[] bytes = Files.readAllBytes(jarOf(MzMLUnmarshaller.class));
        bytes[bytes.length / 2] ^= 0x01;
        Files.write(copy, bytes);

        assertThat(sha256(copy)).hasSize(64).isNotEqualTo(JMZML_SHA256);
        assertThatThrownBy(() -> assertPinned(copy))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(copy.toString())
                .hasMessageContaining(JMZML_SHA256);
    }

    /** Fails unless {@code jar}'s SHA-256 is {@link #JMZML_SHA256}. */
    static void assertPinned(Path jar) throws IOException, NoSuchAlgorithmException {
        String actual = sha256(jar);
        if (!actual.equals(JMZML_SHA256)) {
            throw new AssertionError("jmzML jar " + jar + " has SHA-256 " + actual + ", expected " + JMZML_SHA256);
        }
    }

    /** The file a class was loaded from. */
    static Path jarOf(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /** Lower-case hex SHA-256 of a file's content. */
    static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
