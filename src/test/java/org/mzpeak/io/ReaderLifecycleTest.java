package org.mzpeak.io;

import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;
import org.junit.jupiter.api.Test;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.model.Spectrum;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FN-6: the lifecycle of {@link MzPeakReader}. After {@link MzPeakReader#close()} the methods that read the dataset
 * throw {@link IllegalStateException} instead of returning empty data (small.mzpeak spectrum 0 had 13589 points before
 * close and 0 after, and its one chromatogram was gone); close releases both spectrum signal readers and the source
 * even when one of them fails, also with an {@link Error}; and
 * {@link MzPeakReader#open(MzPeakSource, MzPeakReader.Options)} closes the source on an {@link Error} too, keeping a
 * close failure as suppressed. A failure thrown twice as the same instance is thrown once, never suppressed onto
 * itself. The sources are {@link TestSources#memory} copies of the current small.mzpeak, wrapped to count and fail
 * closes.
 */
class ReaderLifecycleTest {

    private static final String SMALL = "small.mzpeak";

    @Test
    void readsAfterCloseThrowInsteadOfReturningEmptyData() {
        MzPeakReader reader = MzPeakReader.open(Fixtures.current(SMALL));
        assertThat(reader.getSpectrumAt(0).mz()).hasSize(13589);
        assertThat(reader.chromatograms()).hasSize(1);
        Iterator<Spectrum> spectra = reader.iterator();
        reader.close();

        assertThatThrownBy(() -> reader.getSpectrumAt(0)).isInstanceOf(IllegalStateException.class)
                .hasMessage("the reader is closed");
        assertThatThrownBy(() -> reader.getSpectrum(0)).isInstanceOf(IllegalStateException.class);
        assertThat(spectra.hasNext()).isTrue();
        assertThatThrownBy(spectra::next).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reader.iterator().next()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(reader::chromatograms).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reader.getChromatogram(0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(reader::wavelengthSpectra).isInstanceOf(IllegalStateException.class);
        // what the reader holds in memory stays available
        assertThat(reader.size()).isPositive();
        assertThat(reader.metadataAt(0).index()).isZero();
        assertThat(reader.getSpectrum(-1)).isEmpty();
        reader.close();
    }

    @Test
    void cachedFileMetadataStaysAvailableAfterClose() {
        MzPeakReader reader = MzPeakReader.open(Fixtures.current(SMALL));
        var metadata = reader.fileMetadata();
        reader.close();
        assertThat(reader.fileMetadata()).isSameAs(metadata);
    }

    @Test
    void aFailingDataMemberCloseStillClosesThePeaksMemberAndTheSource() {
        TestSources.Memory memory = TestSources.memory(Fixtures.current(SMALL));
        String data;
        String peaks;
        try (MzPeakSource probe = TestSources.memory(Fixtures.current(SMALL))) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(probe);
            data = manifest.find(EntityType.SPECTRUM, DataKind.DATA_ARRAYS).orElseThrow().name();
            peaks = manifest.find(EntityType.SPECTRUM, DataKind.PEAKS).orElseThrow().name();
        }
        ClosingSource source = new ClosingSource(memory, Map.of(data, "data close failed", peaks, ""));
        MzPeakReader reader = MzPeakReader.open(source);
        Spectrum zero = reader.getSpectrumAt(0); // opens both signal members
        assertThat(zero.mz()).isNotEmpty();
        assertThat(source.opened(data)).isPositive();
        assertThat(source.opened(peaks)).isPositive();

        assertThatThrownBy(reader::close)
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("data close failed");
        assertThat(source.closedStreams(peaks)).isEqualTo(source.opened(peaks));
        assertThat(source.closes()).isEqualTo(1);

        reader.close(); // only the first call has an effect
        assertThat(source.closes()).isEqualTo(1);
    }

    @Test
    void laterCloseFailuresAreAddedAsSuppressed() {
        TestSources.Memory memory = TestSources.memory(Fixtures.current(SMALL));
        String data;
        String peaks;
        try (MzPeakSource probe = TestSources.memory(Fixtures.current(SMALL))) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(probe);
            data = manifest.find(EntityType.SPECTRUM, DataKind.DATA_ARRAYS).orElseThrow().name();
            peaks = manifest.find(EntityType.SPECTRUM, DataKind.PEAKS).orElseThrow().name();
        }
        ClosingSource source = new ClosingSource(memory, Map.of(data, "data close failed",
                peaks, "peaks close failed"));
        source.failClose = true;
        MzPeakReader reader = MzPeakReader.open(source);
        reader.getSpectrumAt(0);

        assertThatThrownBy(reader::close)
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("data close failed")
                .satisfies(e -> assertThat(e.getSuppressed()).hasSize(2)
                        .satisfiesExactly(
                                peaksFailure -> assertThat(peaksFailure).hasRootCauseMessage("peaks close failed"),
                                sourceFailure -> assertThat(sourceFailure).hasMessage("source close failed")));
        assertThat(source.closes()).isEqualTo(1);
    }

    @Test
    void anErrorWhileOpeningClosesTheSourceOnce() {
        ClosingSource source = new ClosingSource(TestSources.memory(Fixtures.current(SMALL)), Map.of());
        source.manifestFailure = new AssertionError("manifest read failed");

        assertThatThrownBy(() -> MzPeakReader.open(source))
                .isSameAs(source.manifestFailure);
        assertThat(source.closes()).isEqualTo(1);
    }

    @Test
    void aCloseFailureWhileOpeningIsSuppressedUnderTheOriginalFailure() {
        ClosingSource source = new ClosingSource(TestSources.memory(Fixtures.current(SMALL)), Map.of());
        source.manifestFailure = new MzPeakException("manifest read failed");
        source.failClose = true;

        assertThatThrownBy(() -> MzPeakReader.open(source))
                .isSameAs(source.manifestFailure)
                .satisfies(e -> assertThat(e.getSuppressed()).singleElement()
                        .satisfies(s -> assertThat(s).hasMessage("source close failed")));
        assertThat(source.closes()).isEqualTo(1);
    }

    @Test
    void anErrorClosingTheDataMemberStillClosesThePeaksMemberAndTheSourceOnce() {
        String[] members = signalMembers();
        String data = members[0];
        String peaks = members[1];
        AssertionError failure = new AssertionError("data close error");
        ClosingSource source = new ClosingSource(TestSources.memory(Fixtures.current(SMALL)), Map.of());
        source.streamCloseThrows.put(data, failure);
        MzPeakReader reader = MzPeakReader.open(source);
        reader.getSpectrumAt(0); // opens both signal members
        assertThat(source.opened(data)).isPositive();
        assertThat(source.opened(peaks)).isPositive();

        assertThatThrownBy(reader::close)
                .isSameAs(failure)
                .satisfies(e -> assertThat(e.getSuppressed()).isEmpty());
        assertThat(source.closedStreams(data)).isEqualTo(source.opened(data));
        assertThat(source.closedStreams(peaks)).isEqualTo(source.opened(peaks));
        assertThat(source.closes()).isEqualTo(1);

        reader.close(); // only the first call has an effect
        assertThat(source.closes()).isEqualTo(1);
        assertThatThrownBy(() -> reader.getSpectrumAt(0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void oneInstanceThrownByTheManifestReadAndTheSourceCloseIsRethrownByOpen() {
        MzPeakException failure = new MzPeakException("backend failed");
        ClosingSource source = new ClosingSource(TestSources.memory(Fixtures.current(SMALL)), Map.of());
        source.manifestFailure = failure;
        source.closeFailure = failure;

        assertThatThrownBy(() -> MzPeakReader.open(source))
                .isSameAs(failure)
                .satisfies(e -> assertThat(e.getSuppressed()).isEmpty());
        assertThat(source.closes()).isEqualTo(1);
    }

    @Test
    void oneInstanceThrownClosingBothSignalMembersIsThrownOnceAndTheSourceIsClosed() {
        String[] members = signalMembers();
        MzPeakException failure = new MzPeakException("shared close failure");
        ClosingSource source = new ClosingSource(TestSources.memory(Fixtures.current(SMALL)), Map.of());
        source.streamCloseThrows.put(members[0], failure);
        source.streamCloseThrows.put(members[1], failure);
        MzPeakReader reader = MzPeakReader.open(source);
        reader.getSpectrumAt(0);

        assertThatThrownBy(reader::close)
                .isSameAs(failure)
                .satisfies(e -> assertThat(e.getSuppressed()).isEmpty());
        assertThat(source.closedStreams(members[0])).isEqualTo(source.opened(members[0])).isPositive();
        assertThat(source.closedStreams(members[1])).isEqualTo(source.opened(members[1])).isPositive();
        assertThat(source.closes()).isEqualTo(1);
    }

    /** The names of the spectrum data-arrays and peaks members of small.mzpeak. */
    private static String[] signalMembers() {
        try (MzPeakSource probe = TestSources.memory(Fixtures.current(SMALL))) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(probe);
            return new String[] {
                    manifest.find(EntityType.SPECTRUM, DataKind.DATA_ARRAYS).orElseThrow().name(),
                    manifest.find(EntityType.SPECTRUM, DataKind.PEAKS).orElseThrow().name()};
        }
    }

    /**
     * {@code delegate}, counting its closes and the streams of each member opened and closed. A member listed in
     * {@code streamCloseFailures} with a non-empty message fails each stream close with that message, and a member
     * in {@link #streamCloseThrows} throws that instance from each stream close; {@link #manifestFailure} is thrown by
     * {@link #readManifestBytes()}, and {@link #failClose} (a new exception) or {@link #closeFailure} (that instance)
     * fails {@link #close()} after closing the delegate.
     */
    private static final class ClosingSource implements MzPeakSource {

        private final MzPeakSource delegate;
        private final Map<String, String> streamCloseFailures;
        private final Map<String, AtomicInteger> opened = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> closed = new ConcurrentHashMap<>();
        private final AtomicInteger closes = new AtomicInteger();
        final Map<String, Throwable> streamCloseThrows = new ConcurrentHashMap<>();
        Throwable manifestFailure;
        boolean failClose;
        Throwable closeFailure;

        ClosingSource(MzPeakSource delegate, Map<String, String> streamCloseFailures) {
            this.delegate = delegate;
            this.streamCloseFailures = streamCloseFailures;
        }

        int opened(String member) {
            return opened.getOrDefault(member, new AtomicInteger()).get();
        }

        int closedStreams(String member) {
            return closed.getOrDefault(member, new AtomicInteger()).get();
        }

        int closes() {
            return closes.get();
        }

        @Override
        public byte[] readManifestBytes() {
            if (manifestFailure instanceof RuntimeException e) {
                throw e;
            }
            if (manifestFailure instanceof Error e) {
                throw e;
            }
            return delegate.readManifestBytes();
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
                    opened.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
                    return new ClosingStream(file.newStream(), name);
                }
            };
        }

        @Override
        public String describe() {
            return delegate.describe();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            delegate.close();
            if (failClose) {
                throw new MzPeakException("source close failed");
            }
            if (closeFailure != null) {
                throw unchecked(closeFailure);
            }
        }

        /** {@code t} itself, typed for {@code throw}: a {@link RuntimeException} or an {@link Error}. */
        private static RuntimeException unchecked(Throwable t) {
            if (t instanceof RuntimeException e) {
                return e;
            }
            if (t instanceof Error e) {
                throw e;
            }
            throw new AssertionError("not unchecked: " + t);
        }

        private final class ClosingStream extends SeekableInputStream {

            private final SeekableInputStream in;
            private final String member;

            ClosingStream(SeekableInputStream in, String member) {
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
                return in.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return in.read(b, off, len);
            }

            @Override
            public void readFully(byte[] bytes) throws IOException {
                in.readFully(bytes);
            }

            @Override
            public void readFully(byte[] bytes, int start, int len) throws IOException {
                in.readFully(bytes, start, len);
            }

            @Override
            public int read(ByteBuffer buf) throws IOException {
                return in.read(buf);
            }

            @Override
            public void readFully(ByteBuffer buf) throws IOException {
                in.readFully(buf);
            }

            @Override
            public void close() throws IOException {
                in.close();
                closed.computeIfAbsent(member, k -> new AtomicInteger()).incrementAndGet();
                Throwable thrown = streamCloseThrows.get(member);
                if (thrown != null) {
                    throw unchecked(thrown);
                }
                String failure = streamCloseFailures.get(member);
                if (failure != null && !failure.isEmpty()) {
                    throw new IOException(failure);
                }
            }
        }
    }
}
