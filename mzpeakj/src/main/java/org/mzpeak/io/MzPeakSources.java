package org.mzpeak.io;

import org.apache.parquet.io.SeekableInputStream;
import org.mzpeak.io.IntegrityReport.MemberResult;
import org.mzpeak.io.IntegrityReport.Status;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks of an {@link MzPeakSource}'s members that the public interface does not offer: whether a member exists,
 * whether a ZIP member is stored in a way mzPeak forbids (R9), and the checksums of
 * {@link MzPeakReader#verifyIntegrity()} (RD-19).
 */
final class MzPeakSources {

    /** Whether a source has a member. */
    enum MemberPresence {
        /** The member exists; reading it may still fail, e.g. for a directory or an unreadable file. */
        PRESENT,
        /** The source has no such member. */
        ABSENT,
        /** The source cannot tell without reading the member. */
        UNKNOWN
    }

    private MzPeakSources() {
    }

    /**
     * Whether {@code source} has the member {@code name}: looked up in the central directory of a ZIP archive, in the
     * file system for a directory (without following links), or opened through the {@link MzPeakSource.MemberOpener}
     * of an unpacked source, which keeps the member open for the read that follows. Other implementations give
     * {@link MemberPresence#UNKNOWN}.
     *
     * @throws MzPeakException if the name escapes the dataset or the member cannot be opened
     */
    static MemberPresence presence(MzPeakSource source, String name) {
        boolean present;
        if (source instanceof ZipSource zip) {
            present = zip.contains(name);
        } else if (source instanceof DirectorySource directory) {
            present = directory.contains(name);
        } else if (source instanceof UnpackedSource unpacked) {
            present = unpacked.contains(name);
        } else {
            return MemberPresence.UNKNOWN;
        }
        return present ? MemberPresence.PRESENT : MemberPresence.ABSENT;
    }

    /**
     * Why the member {@code name} of {@code source} is not read (R9): a ZIP member that is encrypted or not STORED.
     * {@code null} for a member that can be read, a member the archive does not have, and other sources.
     */
    static String rejection(MzPeakSource source, String name) {
        return source instanceof ZipSource zip ? zip.rejection(name) : null;
    }

    /**
     * Rejects the first member {@code manifest} lists that {@link #rejection} gives a reason for.
     *
     * @throws MzPeakException with that reason
     */
    static void checkListedMembers(MzPeakSource source, MzPeakManifest manifest) {
        for (MzPeakManifest.Entry entry : manifest.files()) {
            String reason = rejection(source, entry.name());
            if (reason != null) {
                throw new MzPeakException(reason);
            }
        }
    }

    /**
     * One result per entry of {@code manifest}, in manifest order: {@link Status#MISSING_MEMBER} for a member that
     * {@link #presence} finds {@link MemberPresence#ABSENT}, {@link Status#REJECTED_MEMBER} with the reason for one
     * that {@link #rejection} rejects, else the SHA-512 of the member's bytes compared, ignoring case, with the
     * declared checksum.
     *
     * @throws MzPeakException if a member that is neither absent nor rejected cannot be read
     */
    static IntegrityReport verify(MzPeakSource source, MzPeakManifest manifest) {
        List<MemberResult> results = new ArrayList<>();
        for (MzPeakManifest.Entry entry : manifest.files()) {
            String name = entry.name();
            String expected = entry.checksum();
            if (presence(source, name) == MemberPresence.ABSENT) {
                results.add(new MemberResult(name, Status.MISSING_MEMBER, expected, null));
                continue;
            }
            String reason = rejection(source, name);
            if (reason != null) {
                results.add(new MemberResult(name, Status.REJECTED_MEMBER, expected, null, reason));
                continue;
            }
            String actual = sha512(source, name);
            Status status = expected == null ? Status.MISSING_CHECKSUM
                    : expected.equalsIgnoreCase(actual) ? Status.MATCH : Status.MISMATCH;
            results.add(new MemberResult(name, status, expected, actual));
        }
        return IntegrityReport.of(results);
    }

    /** The SHA-512 of the member {@code name} of {@code source}, in lowercase hex. */
    static String sha512(MzPeakSource source, String name) {
        try (SeekableInputStream in = source.inputFile(name).newStream()) {
            return Sha512.hex(in);
        } catch (IOException e) {
            throw new MzPeakException("Failed to read member " + name + " of " + source.describe(), e);
        }
    }
}
