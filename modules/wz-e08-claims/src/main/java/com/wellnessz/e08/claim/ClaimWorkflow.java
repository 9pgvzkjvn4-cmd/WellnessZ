package com.wellnessz.e08.claim;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.wellnessz.e08.model.ClaimEnums.ClaimStatus;

/**
 * Claim lifecycle (contract §4): NOTIFIED → OPEN → UNDER_REVIEW → SETTLED → CLOSED; LITIGATED from OPEN / UNDER_REVIEW
 * (and on to SETTLED / CLOSED once resolved); CLOSED → OPEN is a reopen and needs a reason. A notified or reviewed claim may
 * be closed directly when it is withdrawn or declined – also with a reason.
 */
public final class ClaimWorkflow {

    private static final Map<ClaimStatus, Set<ClaimStatus>> ALLOWED = Map.of(
            ClaimStatus.NOTIFIED, EnumSet.of(ClaimStatus.OPEN, ClaimStatus.CLOSED),
            ClaimStatus.OPEN, EnumSet.of(ClaimStatus.UNDER_REVIEW, ClaimStatus.LITIGATED),
            ClaimStatus.UNDER_REVIEW, EnumSet.of(ClaimStatus.SETTLED, ClaimStatus.LITIGATED, ClaimStatus.CLOSED),
            ClaimStatus.LITIGATED, EnumSet.of(ClaimStatus.SETTLED, ClaimStatus.CLOSED),
            ClaimStatus.SETTLED, EnumSet.of(ClaimStatus.CLOSED),
            ClaimStatus.CLOSED, EnumSet.of(ClaimStatus.OPEN));

    private ClaimWorkflow() {
    }

    public static boolean allowed(ClaimStatus from, ClaimStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** A reason is mandatory for a reopen and for closing a claim that was never settled (withdrawn / declined). */
    public static boolean reasonRequired(ClaimStatus from, ClaimStatus to) {
        return (from == ClaimStatus.CLOSED && to == ClaimStatus.OPEN)
                || (to == ClaimStatus.CLOSED && (from == ClaimStatus.NOTIFIED || from == ClaimStatus.UNDER_REVIEW || from == ClaimStatus.LITIGATED));
    }

    public static Set<ClaimStatus> next(ClaimStatus from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }
}
