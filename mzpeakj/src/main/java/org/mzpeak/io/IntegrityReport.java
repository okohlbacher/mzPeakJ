package org.mzpeak.io;

import java.util.List;
import java.util.Objects;

/**
 * The result of {@link MzPeakReader#verifyIntegrity()} (RD-19): one {@link MemberResult} per manifest entry, in
 * manifest order, comparing the SHA-512 checksum the manifest declares for each member with the checksum of the
 * member's bytes.
 *
 * <p>The {@link #outcome()} is {@link Outcome#INVALID} if any member is {@link Status#MISMATCH},
 * {@link Status#MISSING_MEMBER} or {@link Status#REJECTED_MEMBER}; else {@link Outcome#UNVERIFIABLE} if any member is
 * {@link Status#MISSING_CHECKSUM}; else {@link Outcome#VALID}. Unlike the reference implementations, which give up as
 * soon as one checksum is missing, every member is checked.
 *
 * @param outcome the outcome over all members
 * @param members one result per manifest entry, in manifest order; unmodifiable
 */
public record IntegrityReport(Outcome outcome, List<MemberResult> members) {

    /** The outcome over all members of a dataset. */
    public enum Outcome {
        /** Every member is present and matches its checksum. */
        VALID,
        /** A member is missing, rejected or does not match its checksum. */
        INVALID,
        /** Every member is present and none mismatches, but at least one has no checksum to compare with. */
        UNVERIFIABLE
    }

    /** The result for one member. */
    public enum Status {
        /** The member's checksum equals the one the manifest declares. */
        MATCH,
        /** The member's checksum differs from the one the manifest declares. */
        MISMATCH,
        /** The manifest declares no checksum for the member. */
        MISSING_CHECKSUM,
        /** The dataset has no such member. */
        MISSING_MEMBER,
        /**
         * The member is stored in a way mzPeak forbids and is not read: a ZIP member that is compressed or encrypted
         * (R9). The {@link MemberResult#reason()} says which.
         */
        REJECTED_MEMBER
    }

    /**
     * The result for one manifest entry.
     *
     * @param name     the member name the manifest lists
     * @param status   the result
     * @param expected the checksum the manifest declares, or {@code null} if it declares none
     * @param actual   the SHA-512 of the member's bytes (128 lowercase hex characters), or {@code null} for
     *                 {@link Status#MISSING_MEMBER} and {@link Status#REJECTED_MEMBER}
     * @param reason   why the member is {@link Status#REJECTED_MEMBER}, or {@code null} for the other statuses
     */
    public record MemberResult(String name, Status status, String expected, String actual, String reason) {

        /** @throws NullPointerException if {@code name} or {@code status} is {@code null} */
        public MemberResult {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(status, "status");
        }

        /**
         * A result without a {@linkplain #reason() reason}.
         *
         * @throws NullPointerException if {@code name} or {@code status} is {@code null}
         */
        public MemberResult(String name, Status status, String expected, String actual) {
            this(name, status, expected, actual, null);
        }
    }

    /**
     * @throws NullPointerException if {@code outcome}, {@code members} or one of the members is {@code null}
     */
    public IntegrityReport {
        Objects.requireNonNull(outcome, "outcome");
        members = List.copyOf(members);
    }

    /** Whether the {@link #outcome()} is {@link Outcome#VALID}. */
    public boolean valid() {
        return outcome == Outcome.VALID;
    }

    /**
     * The members that are {@link Status#MISMATCH}, {@link Status#MISSING_MEMBER} or {@link Status#REJECTED_MEMBER},
     * in manifest order.
     */
    public List<MemberResult> failures() {
        return members.stream().filter(m -> failed(m.status())).toList();
    }

    /** The report over {@code members}, with the outcome they imply. */
    static IntegrityReport of(List<MemberResult> members) {
        boolean failed = false;
        boolean unchecked = false;
        for (MemberResult m : members) {
            failed |= failed(m.status());
            unchecked |= m.status() == Status.MISSING_CHECKSUM;
        }
        Outcome outcome = failed ? Outcome.INVALID : unchecked ? Outcome.UNVERIFIABLE : Outcome.VALID;
        return new IntegrityReport(outcome, members);
    }

    /** Whether a member of {@code status} makes the report {@link Outcome#INVALID}. */
    private static boolean failed(Status status) {
        return status == Status.MISMATCH || status == Status.MISSING_MEMBER || status == Status.REJECTED_MEMBER;
    }
}
