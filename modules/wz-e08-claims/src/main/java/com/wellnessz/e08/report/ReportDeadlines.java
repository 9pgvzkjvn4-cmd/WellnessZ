package com.wellnessz.e08.report;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

/**
 * Deadline rules of the first-report forms (E08-F02): the clock starts at the incident (statutory forms) or at claim
 * notification (insurer FNOL); specified injuries / fatalities use the form's shorter deadline when it has one (GB F2508:
 * 10 days instead of 15 for an over-7-day injury). Reports are reminded {@code reminderHours} before the deadline and are
 * LATE once it has passed without a submission.
 */
public final class ReportDeadlines {

    public static final String ANCHOR_INCIDENT = "INCIDENT";

    public record Rule(int deadlineHours, Integer specifiedDeadlineHours, String anchor) {
    }

    /** Due date and on-time flag of a submitted report, for the on-time KPI. */
    public record Outcome(Instant dueAt, Instant submittedAt) {
    }

    private ReportDeadlines() {
    }

    public static Instant dueAt(Rule rule, Instant incidentAt, Instant notifiedAt, boolean specifiedOrFatal) {
        Instant anchor = ANCHOR_INCIDENT.equals(rule.anchor()) && incidentAt != null ? incidentAt : notifiedAt;
        int hours = specifiedOrFatal && rule.specifiedDeadlineHours() != null ? rule.specifiedDeadlineHours() : rule.deadlineHours();
        return anchor.plus(Duration.ofHours(hours));
    }

    /** Whole hours until the deadline (negative once overdue). */
    public static long hoursLeft(Instant dueAt, Instant now) {
        return Math.floorDiv(Duration.between(now, dueAt).toMinutes(), 60);
    }

    public static boolean reminderDue(Instant dueAt, Instant now, int reminderHours) {
        return !now.isBefore(dueAt.minus(Duration.ofHours(reminderHours))) && !now.isAfter(dueAt);
    }

    public static boolean overdue(Instant dueAt, Instant now) {
        return now.isAfter(dueAt);
    }

    /** Percentage of submitted reports submitted on or before their deadline (one decimal), {@code null} when none. */
    public static Double onTimePct(Collection<Outcome> submitted) {
        long total = submitted.stream().filter(o -> o.submittedAt() != null).count();
        if (total == 0) {
            return null;
        }
        long onTime = submitted.stream().filter(o -> o.submittedAt() != null && !o.submittedAt().isAfter(o.dueAt())).count();
        return Math.round(1000.0 * onTime / total) / 10.0;
    }
}
