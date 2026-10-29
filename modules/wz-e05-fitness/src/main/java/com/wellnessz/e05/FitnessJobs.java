package com.wellnessz.e05;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wellnessz.e05.restriction.RestrictionService;
import com.wellnessz.e05.rtw.JobOfferService;
import com.wellnessz.e05.rtw.RtwService;
import com.wellnessz.integration.JobRunGuard;

/**
 * Daily E05 job (once per day across all instances via {@link JobRunGuard}): restriction expiry alerts (T−5) and lapsing,
 * RTW review reminders and escalation of reviews overdue by more than two days, expiry of unanswered job offers.
 */
@Component
public class FitnessJobs {

    private static final Logger log = LoggerFactory.getLogger(FitnessJobs.class);

    private final JobRunGuard guard;
    private final RestrictionService restrictions;
    private final RtwService rtw;
    private final JobOfferService offers;
    private final Clock clock;

    public FitnessJobs(JobRunGuard guard, RestrictionService restrictions, RtwService rtw, JobOfferService offers, Clock clock) {
        this.guard = guard;
        this.restrictions = restrictions;
        this.rtw = rtw;
        this.offers = offers;
        this.clock = clock;
    }

    @Scheduled(cron = "${wellnessz.fitness.daily-job-cron:0 20 2 * * *}", zone = "UTC")
    public void daily() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!guard.tryStart("e05-fitness-daily", today.toString())) {
            return;
        }
        run(today);
    }

    /** Runs all daily steps for {@code today} (also used by tests). */
    public void run(LocalDate today) {
        RestrictionService.ExpiryRun expiry = restrictions.processExpiry(today);
        RtwService.ReviewRun reviews = rtw.processReviews(today);
        int expired = offers.expire(today);
        log.info("E05 daily job: restrictions alerted={} lapsed={}; RTW reviews reminded={} escalated={}; offers expired={}",
                expiry.alerted(), expiry.lapsed(), reviews.reminded(), reviews.escalated(), expired);
    }
}
