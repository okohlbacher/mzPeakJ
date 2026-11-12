package org.mzpeak.io;

import java.util.List;
import java.util.Objects;

/**
 * The result of {@link MzPeakReader#verifyIntegrity()} (RD-19): one {@link MemberResult} per manifest entry, in
 * manifest order, comparing the SHA-512 checksum the manifest declares for each member with the checksum of the
 * member's bytes.
 *
 * <p>The {@link #outcome()} is {@link Outcome#INVALID} if any member is {@link Status#MISMATCH} or
 * {@link Status#MISSING_MEMBER}; else {@link Outcome#UNVERIFIABLE} if any member is {@link Status#MISSING_CHECKSUM};
 * else {@link Outcome#VALID}. Unlike the reference implementations, which give up as soon as one checksum is missing,
 * every member is checked.
 *
 * @param outcome the outcome over all members
 * @param members one result per manifest entry, in manifest order; unmodifiable
 */
public record IntegrityReport(Outcome outcome, List<MemberResult> members) {

    /** The outcome over all members of a dataset. */
    public enum Outcome {
        /** Every member is present and matches its checksum. */
        VALID,
        /** A member is missing or does not match its checksum. */
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
        MISSING_MEMBER
    }

    /**
     * The result for one manifest entry.
     *
     * @param name     the member name the manifest lists
     * @param status   the result
     * @param expected the checksum the manifest declares, or {@code null} if it declares none
     * @param actual   the SHA-512 of the member's bytes (128 lowercase hex characters), or {@code null} for
     *                 {@link Status#MISSING_MEMBER}
     */
    public record MemberResult(String name, Status status, String expected, String actual) {

        /** @throws NullPointerException if {@code name} or {@code status} is {@code null} */
        public MemberResult {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(status, "status");
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

    /** The members that are {@link Status#MISMATCH} or {@link Status#MISSING_MEMBER}, in manifest order. */
    public List<MemberResult> failures() {
        return members.stream()
                .filter(m -> m.status() == Status.MISMATCH || m.status() == Status.MISSING_MEMBER)
                .toList();
    }

    /** The report over {@code members}, with the outcome they imply. */
    static IntegrityReport of(List<MemberResult> members) {
        boolean failed = false;
        boolean unchecked = false;
        for (MemberResult m : members) {
            failed |= m.status() == Status.MISMATCH || m.status() == Status.MISSING_MEMBER;
            unchecked |= m.status() == Status.MISSING_CHECKSUM;
        }
        Outcome outcome = failed ? Outcome.INVALID : unchecked ? Outcome.UNVERIFIABLE : Outcome.VALID;
        return new IntegrityReport(outcome, members);
    }
}
