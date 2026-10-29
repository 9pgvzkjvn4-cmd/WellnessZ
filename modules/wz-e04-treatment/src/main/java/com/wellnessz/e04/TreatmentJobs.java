package com.wellnessz.e04;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wellnessz.e04.plan.PlanService;
import com.wellnessz.e04.preauth.PreauthService;
import com.wellnessz.e04.provider.ProviderDirectoryService;
import com.wellnessz.e04.referral.EReferralService;
import com.wellnessz.integration.JobRunGuard;

/**
 * E04 jobs, each once per run key across all instances ({@link JobRunGuard}): nightly {@code treatment-variance}
 * (variance alerts, follow-up reminders, pre-authorisation expiry), daily {@code referral-chase} and monthly
 * {@code provider-scorecards} (1st of the month, previous month).
 */
@Component
public class TreatmentJobs {

    private static final Logger log = LoggerFactory.getLogger(TreatmentJobs.class);

    private final JobRunGuard guard;
    private final PlanService plans;
    private final EReferralService referrals;
    private final PreauthService preauth;
    private final ProviderDirectoryService providers;
    private final Clock clock;

    public TreatmentJobs(JobRunGuard guard, PlanService plans, EReferralService referrals, PreauthService preauth,
                         ProviderDirectoryService providers, Clock clock) {
        this.guard = guard;
        this.plans = plans;
        this.referrals = referrals;
        this.preauth = preauth;
        this.providers = providers;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    @Scheduled(cron = "${wellnessz.treatment.variance-job-cron:0 20 2 * * *}", zone = "UTC")
    public void varianceJob() {
        if (guard.tryStart("treatment-variance", today().toString())) {
            int raised = plans.runVariance();
            int reminded = plans.remindFollowUps();
            int expired = preauth.expire();
            log.info("treatment-variance: alerts={} follow-up reminders={} pre-authorisations expired={}", raised, reminded, expired);
        }
    }

    @Scheduled(cron = "${wellnessz.treatment.chase-job-cron:0 40 6 * * *}", zone = "UTC")
    public void chaseJob() {
        if (guard.tryStart("referral-chase", today().toString())) {
            EReferralService.ChaseRun r = referrals.chase();
            log.info("referral-chase: chased={} escalated={}", r.chased(), r.escalated());
        }
    }

    @Scheduled(cron = "${wellnessz.treatment.scorecard-job-cron:0 15 3 1 * *}", zone = "UTC")
    public void scorecardJob() {
        YearMonth month = YearMonth.from(today()).minusMonths(1);
        if (guard.tryStart("provider-scorecards", month.toString())) {
            log.info("provider-scorecards: month={} rows={}", month, providers.recompute(month));
        }
    }
}
