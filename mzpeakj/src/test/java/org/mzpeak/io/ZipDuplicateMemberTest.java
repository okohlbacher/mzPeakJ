package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.model.Spectrum;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RD-16: a ZIP archive whose central directory names a member more than once is read with the last entry of that
 * name, as the ZIP specification has it, and {@link ZipIndex} logs one WARNING per duplicated name. The archives are
 * STORED ZIPs written here from the legacy {@code small.unpacked.mzpeak} members, with the duplicate written first.
 */
class ZipDuplicateMemberTest {

    private static final Path UNPACKED = Fixtures.legacy("small.unpacked.mzpeak");
    private static final String INDEX = "mzpeak_index.json";
    private static final String DATA = "spectra_data.parquet";
    private static final int SPECTRA = 48;

    @Test
    void lastIndexWinsThroughOpenAndTheZipSpi(@TempDir Path tmp) throws IOException {
        byte[] withoutSpectra = manifestWithoutSpectrumEntries();
        Path archive = archive(tmp.resolve("duplicate-index.mzpeak"), List.of(entry(INDEX, withoutSpectra)));

        List<LogRecord> viaOpen = warnings(() -> spectrumCount(MzPeakSource.open(archive)), SPECTRA);
        assertThat(viaOpen).extracting(LogRecord::getMessage)
                .containsExactly(archive + ": member '" + INDEX + "' occurs 2 times; using the last");

        List<LogRecord> viaSpi = warnings(() -> spectrumCount(MzPeakSource.zip(archive.toString(),
                RandomAccessBytes.of(open(archive)))), SPECTRA);
        assertThat(viaSpi).extracting(LogRecord::getMessage)
                .containsExactly(archive + ": member '" + INDEX + "' occurs 2 times; using the last");
    }

    @Test
    void oneWarningPerDuplicatedName(@TempDir Path tmp) throws IOException {
        byte[] index = Files.readAllBytes(UNPACKED.resolve(INDEX));
        byte[] data = Files.readAllBytes(UNPACKED.resolve(DATA));
        Path archive = archive(tmp.resolve("duplicates.mzpeak"), List.of(entry(INDEX, manifestWithoutSpectrumEntries()),
                entry(DATA, Arrays.copyOf(data, 100)), entry(INDEX, index)));

        List<LogRecord> logged = warnings(() -> spectrumCount(MzPeakSource.open(archive)), SPECTRA);
        assertThat(logged).extracting(LogRecord::getMessage).containsExactlyInAnyOrder(
                archive + ": member '" + INDEX + "' occurs 3 times; using the last",
                archive + ": member '" + DATA + "' occurs 2 times; using the last");
    }

    @Test
    void truncatedFirstDataCopyIsIgnored(@TempDir Path tmp) throws IOException {
        byte[] data = Files.readAllBytes(UNPACKED.resolve(DATA));
        Path archive = archive(tmp.resolve("duplicate-data.mzpeak"),
                List.of(entry(DATA, Arrays.copyOf(data, data.length / 2))));

        List<LogRecord> logged = ArrayIndexTest.captureLog(ZipIndex.class, () -> {
            try (MzPeakReader zipped = MzPeakReader.open(archive);
                 MzPeakReader unpacked = MzPeakReader.open(UNPACKED)) {
                assertThat(zipped.size()).isEqualTo(SPECTRA);
                for (int i = 0; i < SPECTRA; i++) {
                    Spectrum expected = unpacked.getSpectrumAt(i);
                    Spectrum actual = zipped.getSpectrumAt(i);
                    assertThat(actual.mz()).as("m/z of spectrum %d", i).isEqualTo(expected.mz());
                    assertThat(actual.intensity()).as("intensity of spectrum %d", i).isEqualTo(expected.intensity());
                }
            }
        });
        assertThat(logged).filteredOn(r -> r.getLevel() == Level.WARNING).extracting(LogRecord::getMessage)
                .containsExactly(archive + ": member '" + DATA + "' occurs 2 times; using the last");
    }

    @Test
    void archiveWithoutDuplicatesLogsNothing(@TempDir Path tmp) throws IOException {
        Path archive = archive(tmp.resolve("plain.mzpeak"), List.of());
        assertThat(warnings(() -> spectrumCount(MzPeakSource.open(archive)), SPECTRA)).isEmpty();
    }

    /** The WARNING records of {@link ZipIndex} while {@code count} runs, after checking the spectra it counted. */
    private static List<LogRecord> warnings(Supplier<Integer> count, int expected) {
        int[] counted = new int[1];
        List<LogRecord> records = ArrayIndexTest.captureLog(ZipIndex.class, () -> counted[0] = count.get());
        assertThat(counted[0]).isEqualTo(expected);
        return records.stream().filter(r -> r.getLevel() == Level.WARNING).toList();
    }

    private static int spectrumCount(MzPeakSource source) {
        try (MzPeakReader reader = MzPeakReader.open(source)) {
            return reader.size();
        }
    }

    private static FileChannel open(Path path) {
        try {
            return FileChannel.open(path, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The legacy index without its spectrum entries: a valid manifest that cannot open the spectra. */
    private static byte[] manifestWithoutSpectrumEntries() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(UNPACKED.resolve(INDEX).toFile());
        ArrayNode kept = mapper.createArrayNode();
        for (JsonNode file : root.get("files")) {
            if (!file.get("entity_type").asText().equals("spectrum")) {
                kept.add(file);
            }
        }
        assertThat(kept.size()).isLessThan(root.get("files").size());
        root.set("files", kept);
        return mapper.writeValueAsBytes(root);
    }

    private static Map.Entry<String, byte[]> entry(String name, byte[] data) {
        return new SimpleEntry<>(name, data);
    }

    /**
     * A STORED ZIP of {@code duplicates} followed by every legacy unpacked member (the index last), written by hand
     * because {@link java.util.zip.ZipOutputStream} refuses duplicate names.
     */
    private static Path archive(Path archive, List<Map.Entry<String, byte[]>> duplicates) throws IOException {
        List<Map.Entry<String, byte[]>> members = new ArrayList<>(duplicates);
        try (Stream<Path> files = Files.list(UNPACKED)) {
            for (Path f : files.filter(f -> !f.getFileName().toString().equals(INDEX)).sorted().toList()) {
                members.add(entry(f.getFileName().toString(), Files.readAllBytes(f)));
            }
        }
        members.add(entry(INDEX, Files.readAllBytes(UNPACKED.resolve(INDEX))));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> m : members) {
            byte[] name = m.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] data = m.getValue();
            CRC32 crc = new CRC32();
            crc.update(data);
            int offset = out.size();
            ByteBuffer local = le(30 + name.length).putInt(0x04034b50).putShort((short) 20).putShort((short) 0)
                    .putShort((short) 0).putInt(0).putInt((int) crc.getValue()).putInt(data.length)
                    .putInt(data.length).putShort((short) name.length).putShort((short) 0).put(name);
            out.write(local.array());
            out.write(data);
            ByteBuffer record = le(46 + name.length).putInt(0x02014b50).putShort((short) 20).putShort((short) 20)
                    .putShort((short) 0).putShort((short) 0).putInt(0).putInt((int) crc.getValue())
                    .putInt(data.length).putInt(data.length).putShort((short) name.length).putShort((short) 0)
                    .putShort((short) 0).putShort((short) 0).putShort((short) 0).putInt(0).putInt(offset).put(name);
            central.write(record.array());
        }
        int cdOffset = out.size();
        central.writeTo(out);
        ByteBuffer eocd = le(22).putInt(0x06054b50).putShort((short) 0).putShort((short) 0)
                .putShort((short) members.size()).putShort((short) members.size()).putInt(central.size())
                .putInt(cdOffset).putShort((short) 0);
        out.write(eocd.array());
        return Files.write(archive, out.toByteArray());
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
}
