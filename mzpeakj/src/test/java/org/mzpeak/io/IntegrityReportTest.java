package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.IntegrityReport.MemberResult;
import org.mzpeak.io.IntegrityReport.Outcome;
import org.mzpeak.io.IntegrityReport.Status;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link IntegrityReport} and {@link MemberResult} on their own: the outcome each {@link Status} implies, the
 * {@link IntegrityReport#failures()}, and the 4-argument {@link MemberResult} constructor kept beside the
 * {@link MemberResult#reason()} that R9 adds. {@link VerifyIntegrityTest} covers the reports of real datasets.
 */
class IntegrityReportTest {

    private static final MemberResult MATCH = new MemberResult("a", Status.MATCH, "x", "x");
    private static final MemberResult MISMATCH = new MemberResult("b", Status.MISMATCH, "x", "y");
    private static final MemberResult NO_CHECKSUM = new MemberResult("c", Status.MISSING_CHECKSUM, null, "z");
    private static final MemberResult MISSING = new MemberResult("d", Status.MISSING_MEMBER, "x", null);
    private static final MemberResult REJECTED =
            new MemberResult("e", Status.REJECTED_MEMBER, "x", null, "member e is encrypted");

    @Test
    void theFourArgumentConstructorHasNoReason() {
        MemberResult four = new MemberResult("a", Status.MISMATCH, "x", "y");

        assertThat(four.reason()).isNull();
        assertThat(four).isEqualTo(new MemberResult("a", Status.MISMATCH, "x", "y", null));
        assertThat(four).isNotEqualTo(new MemberResult("a", Status.MISMATCH, "x", "y", "why"));
        assertThatThrownBy(() -> new MemberResult(null, Status.MATCH, null, null, "why"))
                .isInstanceOf(NullPointerException.class).hasMessage("name");
        assertThatThrownBy(() -> new MemberResult("a", null, null, null, "why"))
                .isInstanceOf(NullPointerException.class).hasMessage("status");
    }

    @Test
    void aRejectedMemberCarriesItsReason() {
        assertThat(REJECTED.status()).isEqualTo(Status.REJECTED_MEMBER);
        assertThat(REJECTED.reason()).isEqualTo("member e is encrypted");
        assertThat(REJECTED.actual()).isNull();
        assertThat(REJECTED.expected()).isEqualTo("x");
    }

    @Test
    void aRejectedMemberAloneMakesTheReportInvalid() {
        IntegrityReport report = IntegrityReport.of(List.of(MATCH, REJECTED));

        assertThat(report.outcome()).isEqualTo(Outcome.INVALID);
        assertThat(report.valid()).isFalse();
        assertThat(report.failures()).containsExactly(REJECTED);
    }

    @Test
    void aRejectedMemberOutweighsAMissingChecksum() {
        IntegrityReport report = IntegrityReport.of(List.of(NO_CHECKSUM, REJECTED));

        assertThat(report.outcome()).isEqualTo(Outcome.INVALID);
        assertThat(report.failures()).containsExactly(REJECTED);
    }

    @Test
    void theFailuresAreTheMismatchedMissingAndRejectedMembersInOrder() {
        IntegrityReport report = IntegrityReport.of(List.of(REJECTED, MATCH, MISSING, NO_CHECKSUM, MISMATCH));

        assertThat(report.outcome()).isEqualTo(Outcome.INVALID);
        assertThat(report.failures()).containsExactly(REJECTED, MISSING, MISMATCH);
    }

    @Test
    void theOutcomesWithoutFailures() {
        assertThat(IntegrityReport.of(List.of(MATCH)).outcome()).isEqualTo(Outcome.VALID);
        assertThat(IntegrityReport.of(List.of(MATCH, NO_CHECKSUM)).outcome()).isEqualTo(Outcome.UNVERIFIABLE);
        assertThat(IntegrityReport.of(List.of()).outcome()).isEqualTo(Outcome.VALID);
    }
}
