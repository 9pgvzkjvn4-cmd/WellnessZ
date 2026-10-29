package com.wellnessz.e07;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wellnessz.e07.compliance.ComplianceService;
import com.wellnessz.e07.crew.CrewCertificateService;
import com.wellnessz.e07.due.DueService;
import com.wellnessz.e07.immunisation.ImmunisationService;
import com.wellnessz.e07.preemployment.PreEmploymentService;
import com.wellnessz.e07.programme.EnrolmentEngine;
import com.wellnessz.integration.JobRunGuard;

/**
 * Nightly E07 jobs, each once per day across all instances ({@link JobRunGuard}): {@code surveillance-due} (enrolment
 * recalculation, due events, ignored-invitation follow-up, immunisation reminders, compliance snapshot),
 * {@code crew-certificates} (90 / 60 / 30-day alerts, expiry → roster UNFIT) and {@code pre-employment-purge}.
 */
@Component
public class SurveillanceJobs {

    private static final Logger log = LoggerFactory.getLogger(SurveillanceJobs.class);

    private final JobRunGuard guard;
    private final EnrolmentEngine engine;
    private final DueService due;
    private final ImmunisationService immunisation;
    private final ComplianceService compliance;
    private final CrewCertificateService crew;
    private final PreEmploymentService preEmployment;
    private final Clock clock;

    public SurveillanceJobs(JobRunGuard guard, EnrolmentEngine engine, DueService due, ImmunisationService immunisation,
                            ComplianceService compliance, CrewCertificateService crew, PreEmploymentService preEmployment, Clock clock) {
        this.guard = guard;
        this.engine = engine;
        this.due = due;
        this.immunisation = immunisation;
        this.compliance = compliance;
        this.crew = crew;
        this.preEmployment = preEmployment;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    @Scheduled(cron = "${wellnessz.surveillance.due-job-cron:0 40 1 * * *}", zone = "UTC")
    public void dueJob() {
        LocalDate today = today();
        if (guard.tryStart("surveillance-due", today.toString())) {
            runDue(today);
        }
    }

    /** All steps of {@code surveillance-due} for {@code today} (also used by tests). */
    public void runDue(LocalDate today) {
        EnrolmentEngine.RecalcResult r = engine.recalculate(null);
        DueService.DueRun d = due.processDue(today);
        int reminders = immunisation.remind(today);
        int snapshot = compliance.snapshot(today);
        log.info("surveillance-due: enrolled={} updated={} removed={}; due events={} reinvited={} escalated={}; immunisation reminders={}; "
                + "snapshot rows={}", r.enrolled(), r.updated(), r.removed(), d.dueEvents(), d.reinvited(), d.escalated(), reminders, snapshot);
    }

    @Scheduled(cron = "${wellnessz.surveillance.crew-job-cron:0 50 1 * * *}", zone = "UTC")
    public void crewJob() {
        LocalDate today = today();
        if (guard.tryStart("crew-certificates", today.toString())) {
            CrewCertificateService.CrewRun r = crew.nightly(today);
            log.info("crew-certificates: alerts={} expired={}", r.alerts(), r.expired());
        }
    }

    @Scheduled(cron = "${wellnessz.surveillance.purge-job-cron:0 10 3 * * *}", zone = "UTC")
    public void purgeJob() {
        LocalDate today = today();
        if (guard.tryStart("pre-employment-purge", today.toString())) {
            log.info("pre-employment-purge: purged={}", preEmployment.purge(today));
        }
    }
}
