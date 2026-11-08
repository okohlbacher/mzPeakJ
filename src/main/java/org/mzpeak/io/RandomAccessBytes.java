package org.mzpeak.io;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;

/**
 * Positional read access to a fixed sequence of bytes: a local file, an object in remote storage, a byte array. It is
 * the storage SPI below {@link MzPeakSource#zip(String, RandomAccessBytes)} (a whole single-file {@code .mzpeak}
 * archive) and {@link MzPeakSource#unpacked(String, MzPeakSource.MemberOpener)} (one member of an unpacked dataset).
 *
 * <p>What a source expects of an implementation:
 * <ul>
 *   <li>The bytes do not change while they are read, and {@link #size()} does not change.
 *   <li>{@link #read(ByteBuffer, long)} keeps no position of its own and is safe for concurrent calls: a source
 *       shares one instance between all streams of all its members.
 *   <li>A source that takes ownership of an instance closes it when the source is closed.
 * </ul>
 */
public interface RandomAccessBytes extends Closeable {

    /** The number of bytes. */
    long size() throws IOException;

    /**
     * Reads bytes starting at {@code position} into {@code dst}, at most {@code dst.remaining()} of them, and advances
     * the buffer's position by the number read. A read may return fewer bytes than requested; callers repeat it. A
     * read that returns {@code 0} although {@code dst} has room breaks this contract: the sources fail instead of
     * repeating it.
     *
     * @return the number of bytes read, {@code 0} if {@code dst} has no room, otherwise {@code -1} if
     *         {@code position} is at or past {@link #size()}
     */
    int read(ByteBuffer dst, long position) throws IOException;

    /** Releases the underlying resource. Reads after {@code close()} fail. */
    @Override
    void close() throws IOException;

    /**
     * The bytes of {@code channel}, read with {@link FileChannel#read(ByteBuffer, long)}, which leaves the channel's
     * position alone. {@link #close()} closes the channel.
     */
    static RandomAccessBytes of(FileChannel channel) {
        Objects.requireNonNull(channel, "channel");
        return new RandomAccessBytes() {
            @Override
            public long size() throws IOException {
                return channel.size();
            }

            @Override
            public int read(ByteBuffer dst, long position) throws IOException {
                return channel.read(dst, position);
            }

            @Override
            public void close() throws IOException {
                channel.close();
            }
        };
    }
}
