package com.wellnessz.e11;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wellnessz.e11.advisory.HealthAdvisoryService;
import com.wellnessz.e11.communicable.CommunicableService;
import com.wellnessz.e11.exposure.ExposurePathwayService;
import com.wellnessz.e11.publichealth.PublicHealthReportService;
import com.wellnessz.e11.travel.TravelService;
import com.wellnessz.integration.JobRunGuard;

/**
 * E11 jobs, each once per run key across all instances ({@link JobRunGuard}): {@code exposure-timers} (every 5 minutes – protocol
 * timers due within the alert window or overdue), {@code public-health-deadlines} (hourly), and the daily
 * {@code travelhealth-daily} run (contact check-in reminders, advisory re-targeting for newly rostered crew, travel
 * consultation reminders).
 */
@Component
public class TravelHealthJobs {

    private static final Logger log = LoggerFactory.getLogger(TravelHealthJobs.class);

    private final JobRunGuard guard;
    private final ExposurePathwayService exposures;
    private final PublicHealthReportService reports;
    private final CommunicableService communicable;
    private final HealthAdvisoryService advisories;
    private final TravelService travel;
    private final Clock clock;

    public TravelHealthJobs(JobRunGuard guard, ExposurePathwayService exposures, PublicHealthReportService reports, CommunicableService communicable,
                            HealthAdvisoryService advisories, TravelService travel, Clock clock) {
        this.guard = guard;
        this.exposures = exposures;
        this.reports = reports;
        this.communicable = communicable;
        this.advisories = advisories;
        this.travel = travel;
        this.clock = clock;
    }

    @Scheduled(cron = "${wellnessz.travelhealth.exposure-timer-cron:0 */5 * * * *}", zone = "UTC")
    public void exposureTimers() {
        Instant now = clock.instant();
        if (guard.tryStart("exposure-timers", Long.toString(now.getEpochSecond() / 300))) {
            log.info("exposure-timers: alerts={}", exposures.alertTimers(now));
        }
    }

    @Scheduled(cron = "${wellnessz.travelhealth.public-health-cron:0 7 * * * *}", zone = "UTC")
    public void publicHealthDeadlines() {
        Instant now = clock.instant();
        if (guard.tryStart("public-health-deadlines", Long.toString(now.getEpochSecond() / 3600))) {
            log.info("public-health-deadlines: alerts={}", reports.alertDeadlines(now));
        }
    }

    @Scheduled(cron = "${wellnessz.travelhealth.daily-cron:0 20 6 * * *}", zone = "UTC")
    public void daily() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (guard.tryStart("travelhealth-daily", today.toString())) {
            runDaily(today);
        }
    }

    /** All steps of {@code travelhealth-daily} (also used by tests). */
    public void runDaily(LocalDate today) {
        int checkIns = communicable.checkInReminders(today);
        int targeted = advisories.retarget(today);
        int reminders = travel.remindConsultations(today);
        log.info("travelhealth-daily: check-in reminders={} advisory targets added={} consultation reminders={}", checkIns, targeted, reminders);
    }
}
