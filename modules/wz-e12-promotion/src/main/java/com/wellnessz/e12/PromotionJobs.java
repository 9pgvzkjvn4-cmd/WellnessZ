package com.wellnessz.e12;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wellnessz.e12.ergonomics.ErgonomicsService;
import com.wellnessz.e12.screening.ScreeningService;
import com.wellnessz.integration.JobRunGuard;

/**
 * E12 jobs, each run once per key across all instances ({@link JobRunGuard}): {@code ergonomic-actions} (daily
 * {@code ergonomic.action-due} reminders for actions due soon or overdue) and {@code screening-follow-up} (hourly safety net
 * that opens the follow-up case of any abnormal screening result still without one – the 48-hour rule).
 */
@Component
public class PromotionJobs {

    private static final Logger log = LoggerFactory.getLogger(PromotionJobs.class);

    private final JobRunGuard guard;
    private final ErgonomicsService ergonomics;
    private final ScreeningService screening;
    private final Clock clock;

    public PromotionJobs(JobRunGuard guard, ErgonomicsService ergonomics, ScreeningService screening, Clock clock) {
        this.guard = guard;
        this.ergonomics = ergonomics;
        this.screening = screening;
        this.clock = clock;
    }

    @Scheduled(cron = "${wellnessz.promotion.ergonomic-job-cron:0 20 2 * * *}", zone = "UTC")
    public void ergonomicActionsJob() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (guard.tryStart("ergonomic-actions", today.toString())) {
            log.info("ergonomic-actions: reminders={}", ergonomics.remind(today));
        }
    }

    @Scheduled(cron = "${wellnessz.promotion.follow-up-job-cron:0 15 * * * *}", zone = "UTC")
    public void screeningFollowUpJob() {
        LocalDateTime hour = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).truncatedTo(ChronoUnit.HOURS);
        if (guard.tryStart("screening-follow-up", hour.toString())) {
            log.info("screening-follow-up: opened={}", screening.sweepFollowUps());
        }
    }
}
