package com.wellnessz.e08.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Deadline calculation per form (seeded definitions), reminders, LATE and the on-time KPI. */
class ReportDeadlinesTest {

    static final ReportDeadlines.Rule GB_F2508 = new ReportDeadlines.Rule(360, 240, "INCIDENT");
    static final ReportDeadlines.Rule AE_LABOUR = new ReportDeadlines.Rule(48, null, "INCIDENT");
    static final ReportDeadlines.Rule INSURER_FNOL = new ReportDeadlines.Rule(72, null, "NOTIFIED");

    static final Instant INCIDENT = Instant.parse("2026-10-01T06:40:00Z");
    static final Instant NOTIFIED = Instant.parse("2026-10-03T09:00:00Z");

    @Test
    void ukOverSevenDayInjuryHasFifteenDaysFromTheIncident() {
        assertThat(ReportDeadlines.dueAt(GB_F2508, INCIDENT, NOTIFIED, false)).isEqualTo(INCIDENT.plus(Duration.ofDays(15)));
    }

    @Test
    void ukSpecifiedInjuryOrFatalityHasTenDays() {
        assertThat(ReportDeadlines.dueAt(GB_F2508, INCIDENT, NOTIFIED, true)).isEqualTo(INCIDENT.plus(Duration.ofDays(10)));
    }

    @Test
    void uaeNoticeIsDueWithin48HoursOfTheInjury() {
        assertThat(ReportDeadlines.dueAt(AE_LABOUR, INCIDENT, NOTIFIED, false)).isEqualTo(Instant.parse("2026-10-03T06:40:00Z"));
        // no shorter deadline defined: the specified-injury flag does not change it
        assertThat(ReportDeadlines.dueAt(AE_LABOUR, INCIDENT, NOTIFIED, true)).isEqualTo(Instant.parse("2026-10-03T06:40:00Z"));
    }

    @Test
    void insurerFnolRunsFromClaimNotification() {
        assertThat(ReportDeadlines.dueAt(INSURER_FNOL, INCIDENT, NOTIFIED, false)).isEqualTo(Instant.parse("2026-10-06T09:00:00Z"));
    }

    @Test
    void statutoryFormsFallBackToNotificationWithoutIncidentTime() {
        assertThat(ReportDeadlines.dueAt(AE_LABOUR, null, NOTIFIED, false)).isEqualTo(NOTIFIED.plus(Duration.ofHours(48)));
    }

    @Test
    void reminderWindowAndLate() {
        Instant due = Instant.parse("2026-10-10T12:00:00Z");
        assertThat(ReportDeadlines.reminderDue(due, due.minus(Duration.ofHours(25)), 24)).isFalse();
        assertThat(ReportDeadlines.reminderDue(due, due.minus(Duration.ofHours(23)), 24)).isTrue();
        assertThat(ReportDeadlines.reminderDue(due, due.plusSeconds(1), 24)).isFalse();
        assertThat(ReportDeadlines.overdue(due, due)).isFalse();
        assertThat(ReportDeadlines.overdue(due, due.plusSeconds(1))).isTrue();
        assertThat(ReportDeadlines.hoursLeft(due, due.minus(Duration.ofMinutes(90)))).isEqualTo(1);
        assertThat(ReportDeadlines.hoursLeft(due, due.plus(Duration.ofMinutes(30)))).isEqualTo(-1);
    }

    @Test
    void onTimeSubmissionPercentage() {
        Instant due = Instant.parse("2026-10-10T12:00:00Z");
        assertThat(ReportDeadlines.onTimePct(List.of())).isNull();
        assertThat(ReportDeadlines.onTimePct(List.of(new ReportDeadlines.Outcome(due, due.minusSeconds(60)),
                new ReportDeadlines.Outcome(due, due), new ReportDeadlines.Outcome(due, due.plusSeconds(3600))))).isEqualTo(66.7);
    }
}
