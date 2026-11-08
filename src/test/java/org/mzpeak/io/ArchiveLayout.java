package org.mzpeak.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What two archive writes of the same input must agree on even where their Parquet bytes may differ
 * ({@link DeterminismProcessTest}): the ZIP entries in file order, read raw from the local headers and checked
 * against the central directory, and a digest of each member's content. A Parquet member is digested with
 * {@link ContentDigest}; the manifest by its SHA-256 with every member checksum masked, since each checksum is the
 * SHA-512 of a Parquet member's bytes.
 *
 * @param entries the entries in file order
 * @param digests the content digest of each member, in entry order
 */
record ArchiveLayout(List<Entry> entries, Map<String, String> digests) {

    private static final int LOCAL_HEADER = 0x04034b50;
    private static final int LOCAL_HEADER_LENGTH = 30;
    /** General-purpose flag bit 3: sizes and CRC follow the data in a data descriptor. */
    private static final int DATA_DESCRIPTOR = 1 << 3;
    /** A member checksum in the manifest; group 1 is the key and the opening quote of the value. */
    private static final Pattern CHECKSUM = Pattern.compile("(\"checksum\"\\s*:\\s*\")[0-9a-f]{128}\"");

    /**
     * One entry: the fields of its local header but the CRC, the length of its extra field in the central directory,
     * and its time as {@link ZipFile} reads it.
     *
     * @param dosDate the raw MS-DOS date field
     * @param dosTime the raw MS-DOS time field
     */
    record Entry(String name, int flags, int method, int dosDate, int dosTime, long compressedSize, long size,
                 int localExtraLength, int centralExtraLength, LocalDateTime timeLocal) {
    }

    /** The layout of {@code archive}. */
    static ArchiveLayout of(Path archive) {
        List<Entry> entries = entries(archive);
        Map<String, String> digests = new LinkedHashMap<>();
        try (MzPeakSource source = MzPeakSource.open(archive)) {
            for (Entry entry : entries) {
                digests.put(entry.name(), entry.name().equals(ManifestWriter.INDEX)
                        ? maskedManifest(source.readManifestBytes())
                        : ContentDigest.of(source, entry.name()).tsvLine());
            }
        }
        return new ArchiveLayout(entries, digests);
    }

    /**
     * The entries of {@code archive} in file order. The local headers are walked from the start of the file, which
     * works for archives without data descriptors and zip64 sizes, as {@link ArchivePacker} writes them for members
     * under 4 GiB; the central directory must list the same names in the same order.
     */
    static List<Entry> entries(Path archive) {
        List<Entry> entries = new ArrayList<>();
        try (FileChannel channel = FileChannel.open(archive, StandardOpenOption.READ);
             ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> central = zip.entries();
            ByteBuffer header = ByteBuffer.allocate(LOCAL_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
            long position = 0;
            while (true) {
                header.clear();
                channel.read(header, position);
                if (header.position() < 4 || header.getInt(0) != LOCAL_HEADER) {
                    break;
                }
                int flags = Short.toUnsignedInt(header.getShort(6));
                assertThat(flags & DATA_DESCRIPTOR).as("data descriptor flag at offset %d", position).isZero();
                long compressedSize = Integer.toUnsignedLong(header.getInt(18));
                int nameLength = Short.toUnsignedInt(header.getShort(26));
                int extraLength = Short.toUnsignedInt(header.getShort(28));
                ByteBuffer name = ByteBuffer.allocate(nameLength);
                channel.read(name, position + LOCAL_HEADER_LENGTH);
                String entryName = new String(name.array(), StandardCharsets.UTF_8);
                assertThat(central.hasMoreElements()).as("central directory entry for %s", entryName).isTrue();
                ZipEntry inCentral = central.nextElement();
                assertThat(inCentral.getName()).as("central directory order").isEqualTo(entryName);
                byte[] centralExtra = inCentral.getExtra();
                entries.add(new Entry(entryName, flags, Short.toUnsignedInt(header.getShort(8)),
                        Short.toUnsignedInt(header.getShort(12)), Short.toUnsignedInt(header.getShort(10)),
                        compressedSize, Integer.toUnsignedLong(header.getInt(22)), extraLength,
                        centralExtra == null ? 0 : centralExtra.length, inCentral.getTimeLocal()));
                position += LOCAL_HEADER_LENGTH + nameLength + extraLength + compressedSize;
            }
            assertThat(central.hasMoreElements()).as("central directory entries without a local header").isFalse();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the entries of " + archive, e);
        }
        return entries;
    }

    /** The SHA-256 of the manifest with every checksum replaced by 128 zeros, and the count of masked values. */
    private static String maskedManifest(byte[] manifest) {
        Matcher m = CHECKSUM.matcher(new String(manifest, StandardCharsets.UTF_8));
        StringBuilder masked = new StringBuilder();
        int count = 0;
        while (m.find()) {
            m.appendReplacement(masked, "$1" + "0".repeat(128) + "\"");
            count++;
        }
        m.appendTail(masked);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(masked.toString().getBytes(StandardCharsets.UTF_8));
            return "size=" + manifest.length + " masked_checksums=" + count + " " + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
