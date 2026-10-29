package com.wellnessz.e04.referral;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Report chase rules of the daily {@code referral-chase} job: a referral whose provider report is overdue is chased (at
 * most once per interval); once {@code maxChases} chases went unanswered it is escalated to the referring clinician once.
 */
public final class ChaseRules {

    /** Statuses in which the provider still owes the report. */
    public static final Set<String> AWAITING_REPORT = Set.of("SENT", "ACCEPTED", "SCHEDULED", "SEEN");

    public enum Action { NONE, CHASE, ESCALATE }

    private ChaseRules() {
    }

    public static Action decide(String status, Instant reportDueAt, Instant reportReceivedAt, int chaseCount, Instant lastChasedAt,
                                boolean escalated, Instant now, int maxChases, int intervalDays) {
        if (!AWAITING_REPORT.contains(status) || reportReceivedAt != null || reportDueAt == null || !now.isAfter(reportDueAt)) {
            return Action.NONE;
        }
        if (lastChasedAt != null && now.isBefore(lastChasedAt.plus(Duration.ofDays(Math.max(1, intervalDays))))) {
            return Action.NONE;
        }
        if (chaseCount >= maxChases) {
            return escalated ? Action.NONE : Action.ESCALATE;
        }
        return Action.CHASE;
    }

    /** Overdue flag shown on the worklist. */
    public static boolean overdue(String status, Instant reportDueAt, Instant reportReceivedAt, Instant now) {
        return AWAITING_REPORT.contains(status) && reportReceivedAt == null && reportDueAt != null && now.isAfter(reportDueAt);
    }
}
