package org.mzpeak.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The SHA-512 checksum the manifest declares for a dataset member: 128 lowercase hex characters over the member's
 * bytes, as the reference computes it ({@code validation::checksum_stream}).
 */
final class Sha512 {

    private static final int BUFFER_SIZE = 1 << 16;

    private Sha512() {
    }

    /** The SHA-512 of everything {@code in} yields up to its end, in lowercase hex. The stream is not closed. */
    static String hex(InputStream in) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[BUFFER_SIZE];
        for (int n = in.read(buffer); n != -1; n = in.read(buffer)) {
            digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** The SHA-512 of the bytes of {@code file}, in lowercase hex. */
    static String hex(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return hex(in);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-512.
            throw new IllegalStateException("SHA-512 is not available", e);
        }
    }
}
