package com.wellnessz.e08.claim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.wellnessz.e08.model.ClaimEnums.ClaimStatus;

/** Claim workflow transitions and the nurse case manager choice. */
class ClaimRulesTest {

    @Test
    void forwardPathIsAllowed() {
        assertThat(ClaimWorkflow.allowed(ClaimStatus.NOTIFIED, ClaimStatus.OPEN)).isTrue();
        assertThat(ClaimWorkflow.allowed(ClaimStatus.OPEN, ClaimStatus.UNDER_REVIEW)).isTrue();
        assertThat(ClaimWorkflow.allowed(ClaimStatus.UNDER_REVIEW, ClaimStatus.SETTLED)).isTrue();
        assertThat(ClaimWorkflow.allowed(ClaimStatus.SETTLED, ClaimStatus.CLOSED)).isTrue();
    }

    @Test
    void litigationOnlyFromOpenOrUnderReview() {
        assertThat(ClaimWorkflow.allowed(ClaimStatus.OPEN, ClaimStatus.LITIGATED)).isTrue();
        assertThat(ClaimWorkflow.allowed(ClaimStatus.UNDER_REVIEW, ClaimStatus.LITIGATED)).isTrue();
        assertThat(ClaimWorkflow.allowed(ClaimStatus.NOTIFIED, ClaimStatus.LITIGATED)).isFalse();
        assertThat(ClaimWorkflow.allowed(ClaimStatus.SETTLED, ClaimStatus.LITIGATED)).isFalse();
        assertThat(ClaimWorkflow.allowed(ClaimStatus.CLOSED, ClaimStatus.LITIGATED)).isFalse();
        assertThat(ClaimWorkflow.allowed(ClaimStatus.LITIGATED, ClaimStatus.SETTLED)).isTrue();
    }

    @Test
    void skippingStepsIsInvalid() {
        assertThat(ClaimWorkflow.allowed(ClaimStatus.NOTIFIED, ClaimStatus.SETTLED)).isFalse();
        assertThat(ClaimWorkflow.allowed(ClaimStatus.OPEN, ClaimStatus.SETTLED)).isFalse();
        assertThat(ClaimWorkflow.allowed(ClaimStatus.OPEN, ClaimStatus.OPEN)).isFalse();
        assertThat(ClaimWorkflow.allowed(ClaimStatus.CLOSED, ClaimStatus.SETTLED)).isFalse();
    }

    @Test
    void reopenNeedsAReason() {
        assertThat(ClaimWorkflow.allowed(ClaimStatus.CLOSED, ClaimStatus.OPEN)).isTrue();
        assertThat(ClaimWorkflow.reasonRequired(ClaimStatus.CLOSED, ClaimStatus.OPEN)).isTrue();
        assertThat(ClaimWorkflow.reasonRequired(ClaimStatus.NOTIFIED, ClaimStatus.CLOSED)).isTrue();
        assertThat(ClaimWorkflow.reasonRequired(ClaimStatus.SETTLED, ClaimStatus.CLOSED)).isFalse();
        assertThat(ClaimWorkflow.reasonRequired(ClaimStatus.NOTIFIED, ClaimStatus.OPEN)).isFalse();
    }

    // ------------------------------------------------------------------ nurse assignment

    static NurseAssignment.Candidate staff(String user, int cap, boolean available, String... roles) {
        return new NurseAssignment.Candidate(user, Set.of(roles), cap, available);
    }

    @Test
    void caseOwnerWithNursingRoleKeepsTheClaim() {
        NurseAssignment.Candidate owner = staff("nurse1", 30, true, "OH_CLINICIAN");
        var choice = NurseAssignment.choose(owner, List.of(owner, staff("cm1", 30, true, "CASE_MANAGER")), Map.of("nurse1", 29L));
        assertThat(choice).hasValueSatisfying(c -> {
            assertThat(c.username()).isEqualTo("nurse1");
            assertThat(c.caseOwner()).isTrue();
        });
    }

    @Test
    void ownerWithoutNursingRoleFallsBackToLeastLoadedCaseManagerWithinCap() {
        NurseAssignment.Candidate owner = staff("physician1", 40, true, "OH_PHYSICIAN");
        List<NurseAssignment.Candidate> pool = List.of(owner, staff("cm1", 10, true, "CASE_MANAGER"), staff("cm2", 10, true, "CASE_MANAGER"),
                staff("cm3", 5, true, "CASE_MANAGER"), staff("cm4", 30, false, "CASE_MANAGER"), staff("nurse2", 30, true, "OH_CLINICIAN"));
        var choice = NurseAssignment.choose(owner, pool, Map.of("cm1", 7L, "cm2", 4L, "cm3", 5L, "cm4", 0L, "nurse2", 0L));
        // cm3 is at its cap, cm4 is out of office, nurse2 is not a case manager → cm2 (least loaded)
        assertThat(choice).hasValueSatisfying(c -> {
            assertThat(c.username()).isEqualTo("cm2");
            assertThat(c.caseOwner()).isFalse();
        });
    }

    @Test
    void unavailableOwnerAndTiesAreHandledDeterministically() {
        NurseAssignment.Candidate owner = staff("nurse1", 30, false, "OH_CLINICIAN");
        var choice = NurseAssignment.choose(owner, List.of(staff("zeta", 10, true, "CASE_MANAGER"), staff("alpha", 10, true, "CASE_MANAGER")),
                Map.of());
        assertThat(choice).hasValueSatisfying(c -> assertThat(c.username()).isEqualTo("alpha"));
    }

    @Test
    void nobodyWithinCapLeavesTheClaimUnassigned() {
        var choice = NurseAssignment.choose(null, List.of(staff("cm1", 2, true, "CASE_MANAGER")), Map.of("cm1", 2L));
        assertThat(choice).isEmpty();
    }
}
