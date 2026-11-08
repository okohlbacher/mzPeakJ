package org.mzpeak.io;

import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;
import org.mzpeak.io.parquet.ByteArrayInputFile;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * {@link MzPeakSource} implementations for tests of the source SPI ({@link MzPeakReader#open(MzPeakSource)}).
 *
 * <ul>
 *   <li>{@link #memory(Path)}: every member of a dataset in memory, served through {@link ByteArrayInputFile}. It
 *       reads directories with {@link Files} and ZIPs with {@link ZipFile}, not with the reader's own sources.
 *   <li>{@link #recording(MzPeakSource)}: logs the member names the reader asks for and counts the closes.
 *   <li>{@link #counting(MzPeakSource)}: counts the bytes read from each member's streams.
 * </ul>
 *
 * {@link #content(MzPeakReader)} renders what a reader decodes, to compare readers of different sources or options.
 */
final class TestSources {

    /** The manifest member name. */
    static final String MANIFEST = "mzpeak_index.json";

    private TestSources() {
    }

    /** Every file of the dataset at {@code dataset}, an unpacked directory or a ZIP, read into memory. */
    static Memory memory(Path dataset) {
        Map<String, byte[]> members = new TreeMap<>();
        try {
            if (Files.isDirectory(dataset)) {
                try (Stream<Path> files = Files.walk(dataset)) {
                    for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                        String name = dataset.relativize(file).toString().replace(file.getFileSystem()
                                .getSeparator(), "/");
                        members.put(name, Files.readAllBytes(file));
                    }
                }
            } else {
                try (ZipFile zip = new ZipFile(dataset.toFile())) {
                    for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                        ZipEntry entry = e.nextElement();
                        if (!entry.isDirectory()) {
                            try (InputStream in = zip.getInputStream(entry)) {
                                members.put(entry.getName(), in.readAllBytes());
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + dataset + " into memory", e);
        }
        return new Memory("memory:" + dataset, members);
    }

    /** A source of the given members, the manifest included under {@link #MANIFEST}. */
    static Memory memory(String description, Map<String, byte[]> members) {
        return new Memory(description, new TreeMap<>(members));
    }

    /** {@code delegate}, logging the members asked for and the closes. */
    static Recording recording(MzPeakSource delegate) {
        return new Recording(delegate);
    }

    /** {@code delegate}, counting the bytes read from each member. */
    static Counting counting(MzPeakSource delegate) {
        return new Counting(delegate);
    }

    /**
     * Everything {@code reader} decodes, one line per item, for comparing two readers of the same dataset: the
     * manifest entries and metadata, file metadata, scan settings, then per spectrum its description, m/z, intensity,
     * peaks, {@link Spectrum#arrays()} and {@link Spectrum#peakArrays()}, then the chromatograms and the wavelength
     * spectra. Records appear as their {@code toString()} (exact within one JVM), arrays as their length and a SHA-256
     * over their raw IEEE-754 bits, maps of arrays in iteration order. A read that throws appears as the exception's
     * class and message, so readers that fail alike compare equal.
     */
    static List<String> content(MzPeakReader reader) {
        List<String> lines = new ArrayList<>();
        lines.add("files " + reader.manifest().files());
        lines.add("metadata " + reader.manifest().metadataJson());
        lines.add(outcome("fileMetadata", () -> String.valueOf(reader.fileMetadata())));
        lines.add(outcome("scanSettings", () -> reader.scanSettings() + " imaging=" + reader.isImaging()));
        lines.add("size " + reader.size());
        for (int i = 0; i < reader.size(); i++) {
            int ordinal = i;
            lines.add("description " + reader.metadataAt(ordinal));
            lines.add(outcome("spectrum " + ordinal, () -> {
                Spectrum s = reader.getSpectrumAt(ordinal);
                StringBuilder peaks = new StringBuilder();
                for (CentroidPeak p : s.peaks()) {
                    peaks.append(p.index()).append(':').append(Double.doubleToRawLongBits(p.mz())).append(':')
                            .append(Double.doubleToRawLongBits(p.intensity())).append(';');
                }
                return s.description().index() + " mz=" + digest(s.mz()) + " intensity=" + digest(s.intensity())
                        + " peaks=" + s.peaks().size() + ":" + sha256(peaks.toString().getBytes(StandardCharsets.UTF_8))
                        + " arrays=" + digest(s.arrays()) + " peakArrays=" + digest(s.peakArrays());
            }));
        }
        lines.add(outcome("chromatograms", () -> {
            StringBuilder out = new StringBuilder();
            for (Chromatogram c : reader.chromatograms()) {
                out.append('\n').append(c.index()).append('|').append(c.id()).append('|').append(c.typeCurie())
                        .append('|').append(c.intensityUnit()).append(" time=").append(digest(c.time()))
                        .append(" intensity=").append(digest(c.intensity())).append(" arrays=")
                        .append(digest(c.arrays()));
            }
            return out.toString();
        }));
        lines.add(outcome("wavelengthSpectra", () -> {
            StringBuilder out = new StringBuilder();
            for (WavelengthSpectrum w : reader.wavelengthSpectra()) {
                out.append('\n').append(w.index()).append('|').append(w.id()).append('|').append(w.time())
                        .append('|').append(w.lambdaMax()).append(" wavelength=").append(digest(w.wavelength()))
                        .append(" intensity=").append(digest(w.intensity()));
            }
            return out.toString();
        }));
        return lines;
    }

    private static String outcome(String label, Supplier<String> read) {
        try {
            return label + " " + read.get();
        } catch (RuntimeException e) {
            return label + " throws " + e.getClass().getName() + ": " + e.getMessage();
        }
    }

    private static String digest(Map<String, double[]> arrays) {
        StringBuilder out = new StringBuilder("{");
        arrays.forEach((key, values) -> out.append(key).append('=').append(digest(values)).append(';'));
        return out.append('}').toString();
    }

    private static String digest(double[] values) {
        ByteBuffer bits = ByteBuffer.allocate(Long.BYTES * values.length);
        for (double v : values) {
            bits.putLong(Double.doubleToRawLongBits(v));
        }
        return values.length + ":" + sha256(bits.array());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Members held as byte arrays. Every method but {@link #close()} throws once it is closed. */
    static final class Memory implements MzPeakSource {

        private final String description;
        private final Map<String, byte[]> members;
        private volatile boolean closed;

        private Memory(String description, Map<String, byte[]> members) {
            this.description = description;
            this.members = members;
        }

        /** The member names, sorted. */
        List<String> names() {
            return List.copyOf(members.keySet());
        }

        /** The bytes of member {@code name}, not copied. */
        byte[] member(String name) {
            return bytes(name);
        }

        @Override
        public byte[] readManifestBytes() {
            return bytes(MANIFEST).clone();
        }

        @Override
        public InputFile inputFile(String name) {
            return new ByteArrayInputFile(bytes(name));
        }

        private byte[] bytes(String name) {
            if (closed) {
                throw new IllegalStateException(description + " is closed");
            }
            byte[] bytes = members.get(name);
            if (bytes == null) {
                throw new MzPeakException(description + " has no member " + name);
            }
            return bytes;
        }

        @Override
        public String describe() {
            return description;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** Logs the member names passed to {@link #inputFile(String)}, in call order, and counts the closes. */
    static final class Recording implements MzPeakSource {

        private final MzPeakSource delegate;
        private final List<String> opened = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger manifestReads = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        private Recording(MzPeakSource delegate) {
            this.delegate = delegate;
        }

        /** The member names asked for so far, in call order, repeats included. */
        List<String> opened() {
            synchronized (opened) {
                return List.copyOf(opened);
            }
        }

        /** How often {@link #readManifestBytes()} was called. */
        int manifestReads() {
            return manifestReads.get();
        }

        /** How often {@link #close()} was called. */
        int closeCount() {
            return closes.get();
        }

        @Override
        public byte[] readManifestBytes() {
            manifestReads.incrementAndGet();
            return delegate.readManifestBytes();
        }

        @Override
        public InputFile inputFile(String name) {
            opened.add(name);
            return delegate.inputFile(name);
        }

        @Override
        public String describe() {
            return delegate.describe();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            delegate.close();
        }
    }

    /**
     * Counts the bytes read from the streams of each member's {@link InputFile}s: every byte a read method returns,
     * once per read. Seeks are not counted.
     */
    static final class Counting implements MzPeakSource {

        private final MzPeakSource delegate;
        private final Map<String, Long> bytesRead = new ConcurrentHashMap<>();

        private Counting(MzPeakSource delegate) {
            this.delegate = delegate;
        }

        /** The bytes read so far from {@code member}, 0 if it was never read. */
        long bytesRead(String member) {
            return bytesRead.getOrDefault(member, 0L);
        }

        /** The bytes read so far per member, for the members read at least once, sorted by name. */
        Map<String, Long> bytesRead() {
            return new TreeMap<>(bytesRead);
        }

        /** The bytes read so far from all members. */
        long totalBytesRead() {
            return bytesRead.values().stream().mapToLong(Long::longValue).sum();
        }

        private void count(String member, long n) {
            if (n > 0) {
                bytesRead.merge(member, n, Long::sum);
            }
        }

        @Override
        public byte[] readManifestBytes() {
            byte[] bytes = delegate.readManifestBytes();
            count(MANIFEST, bytes.length);
            return bytes;
        }

        @Override
        public InputFile inputFile(String name) {
            InputFile file = delegate.inputFile(name);
            return new InputFile() {
                @Override
                public long getLength() throws IOException {
                    return file.getLength();
                }

                @Override
                public SeekableInputStream newStream() throws IOException {
                    return new CountingStream(file.newStream(), name);
                }
            };
        }

        @Override
        public String describe() {
            return delegate.describe();
        }

        @Override
        public void close() {
            delegate.close();
        }

        private final class CountingStream extends SeekableInputStream {

            private final SeekableInputStream in;
            private final String member;

            CountingStream(SeekableInputStream in, String member) {
                this.in = in;
                this.member = member;
            }

            @Override
            public long getPos() throws IOException {
                return in.getPos();
            }

            @Override
            public void seek(long newPos) throws IOException {
                in.seek(newPos);
            }

            @Override
            public int read() throws IOException {
                int b = in.read();
                if (b >= 0) {
                    count(member, 1);
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = in.read(b, off, len);
                count(member, n);
                return n;
            }

            @Override
            public void readFully(byte[] bytes) throws IOException {
                in.readFully(bytes);
                count(member, bytes.length);
            }

            @Override
            public void readFully(byte[] bytes, int start, int len) throws IOException {
                in.readFully(bytes, start, len);
                count(member, len);
            }

            @Override
            public int read(ByteBuffer buf) throws IOException {
                int n = in.read(buf);
                count(member, n);
                return n;
            }

            @Override
            public void readFully(ByteBuffer buf) throws IOException {
                int n = buf.remaining();
                in.readFully(buf);
                count(member, n);
            }

            @Override
            public void close() throws IOException {
                in.close();
            }
        }
    }
}
