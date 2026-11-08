package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Sha512} (WR-1): 128 lowercase hex characters, the FIPS 180-2 values for the standard inputs, every byte up to
 * the end of a stream that delivers short reads, the stream left open, and the failure on a platform without SHA-512.
 */
class Sha512Test {

    private static final String ABC = "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
            + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f";
    private static final String EMPTY = "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce"
            + "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e";

    @Test
    void theStandardInputsGiveTheFipsValues() throws IOException {
        assertThat(Sha512.hex(new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)))).isEqualTo(ABC);
        assertThat(Sha512.hex(new ByteArrayInputStream(new byte[0]))).isEqualTo(EMPTY);
    }

    @Test
    void aStreamIsReadToItsEndThroughShortReadsAndLeftOpen() throws IOException {
        byte[] bytes = new byte[200_003];
        new Random(42).nextBytes(bytes);
        ShortReads in = new ShortReads(bytes, 7);

        String hex = Sha512.hex(in);

        assertThat(hex).matches("^[0-9a-f]{128}$").isEqualTo(reference(bytes));
        assertThat(in.closed).as("closed").isFalse();
        assertThat(in.read()).as("next byte after the checksum").isEqualTo(-1);
    }

    @Test
    void aFileGivesTheChecksumOfItsBytes(@TempDir Path tmp) throws IOException {
        byte[] bytes = new byte[70_000];
        new Random(7).nextBytes(bytes);
        Path file = Files.write(tmp.resolve("member.parquet"), bytes);
        Path abc = Files.write(tmp.resolve("abc"), "abc".getBytes(StandardCharsets.US_ASCII));

        assertThat(Sha512.hex(file)).isEqualTo(reference(bytes));
        assertThat(Sha512.hex(abc)).isEqualTo(ABC);
    }

    /**
     * With the security providers that offer SHA-512 removed for the duration of the call, the checksum fails
     * unchecked, before the stream is read. The providers are put back at their positions afterwards.
     */
    @Test
    void withoutAProviderOfSha512TheChecksumFails() throws IOException {
        List<Provider> before = List.of(Security.getProviders());
        Provider[] offering = Security.getProviders("MessageDigest.SHA-512");
        assertThat(offering).isNotEmpty();
        ShortReads in = new ShortReads("abc".getBytes(StandardCharsets.US_ASCII), 7);
        try {
            for (Provider p : offering) {
                Security.removeProvider(p.getName());
            }
            assertThatThrownBy(() -> Sha512.hex(in))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("SHA-512 is not available")
                    .hasCauseInstanceOf(NoSuchAlgorithmException.class);
        } finally {
            for (Provider p : offering) {
                Security.insertProviderAt(p, before.indexOf(p) + 1);
            }
        }
        assertThat(Security.getProviders()).containsExactlyElementsOf(before);
        assertThat(in.read()).as("first byte, unread").isEqualTo('a');
        assertThat(Sha512.hex(new ByteArrayInputStream(new byte[0]))).isEqualTo(EMPTY);
    }

    private static String reference(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A stream that delivers at most {@code chunk} bytes per read and records whether it was closed. */
    private static final class ShortReads extends InputStream {
        private final byte[] bytes;
        private final int chunk;
        private int position;
        boolean closed;

        ShortReads(byte[] bytes, int chunk) {
            this.bytes = bytes;
            this.chunk = chunk;
        }

        @Override
        public int read() {
            return position < bytes.length ? bytes[position++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            if (position >= bytes.length) {
                return -1;
            }
            int n = Math.min(Math.min(len, chunk), bytes.length - position);
            System.arraycopy(bytes, position, b, off, n);
            position += n;
            return n;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
