package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.IntegrityReport.MemberResult;
import org.mzpeak.io.IntegrityReport.Outcome;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the storage of the members of a single-file archive affects reading: the current {@code small.mzpeak} rewritten
 * with {@link ZipOutputStream}, every member STORED unless a case gives it another method, an extra member written
 * before the manifest.
 */
class ZipMemberStorageTest {

    private static final Path SMALL = Fixtures.current("small.mzpeak");
    private static final String INDEX = "mzpeak_index.json";
    private static final String EXTRA = "extra.txt";

    @TempDir
    Path tmp;

    @Test
    void aRewrittenStoredArchiveReadsLikeTheFixture() throws IOException {
        Path archive = rewrite("stored.mzpeak", Map.of(), Map.of());

        assertReadsLikeTheFixture(archive);
        assertThat(MzPeakReader.verifyIntegrity(archive)).isEqualTo(MzPeakReader.verifyIntegrity(SMALL));
    }

    /** A member the manifest does not list is never read, whatever its method. */
    @Test
    void anUnlistedDeflatedMemberDoesNotFailTheOpen() throws IOException {
        Path archive = rewrite("extra.mzpeak", Map.of(EXTRA, ZipEntry.DEFLATED),
                Map.of(EXTRA, "not part of the dataset\n".repeat(20).getBytes(StandardCharsets.UTF_8)));
        try (FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ)) {
            Map<String, ZipIndex.Entry> index = ZipIndex.read(ch);
            assertThat(index.get(EXTRA).method()).isEqualTo(ZipIndex.DEFLATED);
            assertThat(index.get(INDEX).method()).isEqualTo(ZipIndex.STORED);
        }

        assertReadsLikeTheFixture(archive);
        IntegrityReport report = MzPeakReader.verifyIntegrity(archive);
        assertThat(report.outcome()).isEqualTo(Outcome.VALID);
        assertThat(report.members()).extracting(MemberResult::name).doesNotContain(EXTRA);
        assertThat(report).isEqualTo(MzPeakReader.verifyIntegrity(SMALL));
    }

    private static void assertReadsLikeTheFixture(Path archive) {
        try (MzPeakReader fixture = MzPeakReader.open(SMALL);
             MzPeakReader rewritten = MzPeakReader.open(archive)) {
            List<String> content = TestSources.content(fixture);
            assertThat(content).noneMatch(line -> line.contains(" throws "));
            assertThat(TestSources.content(rewritten)).containsExactlyElementsOf(content);
        }
    }

    /**
     * The members of {@link #SMALL} in archive order with {@code extra} inserted before the manifest, written to
     * {@code name} in the temporary directory; a member is STORED unless {@code methods} names another method.
     */
    Path rewrite(String name, Map<String, Integer> methods, Map<String, byte[]> extra) throws IOException {
        Map<String, byte[]> members = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(SMALL.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                try (InputStream in = zip.getInputStream(entry)) {
                    members.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        byte[] index = members.remove(INDEX);
        members.putAll(extra);
        members.put(INDEX, index);
        Path target = tmp.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            for (Map.Entry<String, byte[]> member : members.entrySet()) {
                byte[] data = member.getValue();
                ZipEntry e = new ZipEntry(member.getKey());
                int method = methods.getOrDefault(member.getKey(), ZipEntry.STORED);
                e.setMethod(method);
                if (method == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    e.setSize(data.length);
                    e.setCompressedSize(data.length);
                    e.setCrc(crc.getValue());
                }
                out.putNextEntry(e);
                out.write(data);
                out.closeEntry();
            }
        }
        return target;
    }
}
