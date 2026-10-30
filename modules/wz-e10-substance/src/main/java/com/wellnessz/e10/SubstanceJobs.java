package com.wellnessz.e10;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wellnessz.e10.testing.ScheduleService;
import com.wellnessz.integration.JobRunGuard;

/**
 * E10 scheduled jobs, each once per run key across all instances ({@link JobRunGuard}): {@code substance-follow-ups} (daily)
 * notifies the unannounced follow-up tests due today; {@code substance-windows} (hourly) closes requirements whose policy window
 * has passed without a collection (WINDOW_MISSED).
 */
@Component("e10SubstanceJobs")
public class SubstanceJobs {

    private static final Logger log = LoggerFactory.getLogger(SubstanceJobs.class);

    private final JobRunGuard guard;
    private final ScheduleService schedule;
    private final Clock clock;

    public SubstanceJobs(JobRunGuard guard, ScheduleService schedule, Clock clock) {
        this.guard = guard;
        this.schedule = schedule;
        this.clock = clock;
    }

    @Scheduled(cron = "${wellnessz.substance.follow-ups-cron:0 15 6 * * *}", zone = "UTC")
    public void followUps() {
        if (guard.tryStart("substance-follow-ups", LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString())) {
            log.info("E10 follow-up tests notified: {}", schedule.notifyDueFollowUps());
        }
    }

    @Scheduled(cron = "${wellnessz.substance.windows-cron:0 20 * * * *}", zone = "UTC")
    public void windows() {
        String hour = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.HOURS).toString();
        if (guard.tryStart("substance-windows", hour)) {
            log.info("E10 requirements closed (window missed): {}", schedule.closeMissedWindows());
        }
    }
}
