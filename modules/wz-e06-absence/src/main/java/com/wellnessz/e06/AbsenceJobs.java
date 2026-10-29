package com.wellnessz.e06;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wellnessz.e06.feed.AbsenceFeedService;
import com.wellnessz.e06.lta.LtaService;
import com.wellnessz.e06.rules.AbsenceTriggerEngine;
import com.wellnessz.integration.JobRunGuard;

/**
 * Nightly E06 jobs, each once per day across all instances ({@link JobRunGuard}): {@code absence-feed} (daily pull through
 * the feed connectors), {@code absence-triggers} (re-evaluates the trigger rules, e.g. durations crossing a threshold) and
 * {@code absence-lta} (overdue milestones and alerts to the case owner).
 */
@Component
public class AbsenceJobs {

    private static final Logger log = LoggerFactory.getLogger(AbsenceJobs.class);

    private final JobRunGuard guard;
    private final AbsenceFeedService feed;
    private final AbsenceTriggerEngine triggers;
    private final LtaService lta;
    private final Clock clock;

    public AbsenceJobs(JobRunGuard guard, AbsenceFeedService feed, AbsenceTriggerEngine triggers, LtaService lta, Clock clock) {
        this.guard = guard;
        this.feed = feed;
        this.triggers = triggers;
        this.lta = lta;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    @Scheduled(cron = "${wellnessz.absence.feed-cron:0 40 2 * * *}", zone = "UTC")
    public void feed() {
        if (guard.tryStart("absence-feed", today().toString())) {
            AbsenceFeedService.FeedRunView run = feed.pull("absence-feed");
            log.info("E06 absence feed pull: received={} matched={} unmatched={}", run.received(), run.matched(), run.unmatched());
        }
    }

    @Scheduled(cron = "${wellnessz.absence.triggers-cron:0 0 3 * * *}", zone = "UTC")
    public void triggers() {
        LocalDate today = today();
        if (guard.tryStart("absence-triggers", today.toString())) {
            AbsenceTriggerEngine.Run run = triggers.evaluateAll(today);
            log.info("E06 absence triggers: employees={} raised={}", run.employees(), run.raised());
        }
    }

    @Scheduled(cron = "${wellnessz.absence.lta-cron:0 10 3 * * *}", zone = "UTC")
    public void lta() {
        LocalDate today = today();
        if (guard.tryStart("absence-lta", today.toString())) {
            LtaService.OverdueRun run = lta.processOverdue(today);
            log.info("E06 long-term absence milestones: overdue={} notified={}", run.markedOverdue(), run.notified());
        }
    }
}
