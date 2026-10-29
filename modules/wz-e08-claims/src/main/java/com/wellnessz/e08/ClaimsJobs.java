package com.wellnessz.e08;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wellnessz.e08.report.ReportService;
import com.wellnessz.integration.JobRunGuard;

/**
 * E08 scheduled jobs, each once per run key across all instances ({@link JobRunGuard}): {@code claims-report-deadlines}
 * (hourly) – first reports due within the reminder window are reminded ({@code claim.report-due}), overdue ones become LATE
 * and are escalated to the claims mailbox.
 */
@Component
public class ClaimsJobs {

    private static final Logger log = LoggerFactory.getLogger(ClaimsJobs.class);

    private final JobRunGuard guard;
    private final ReportService reports;
    private final Clock clock;

    public ClaimsJobs(JobRunGuard guard, ReportService reports, Clock clock) {
        this.guard = guard;
        this.reports = reports;
        this.clock = clock;
    }

    @Scheduled(cron = "${wellnessz.claims.report-deadlines-cron:0 5 * * * *}", zone = "UTC")
    public void reportDeadlines() {
        String hour = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.HOURS).toString();
        if (guard.tryStart("claims-report-deadlines", hour)) {
            ReportService.DeadlineRun run = reports.processDeadlines();
            log.info("E08 report deadlines: reminded={} late={}", run.reminded(), run.late());
        }
    }
}
