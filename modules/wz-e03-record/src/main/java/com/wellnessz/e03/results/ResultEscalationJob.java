package com.wellnessz.e03.results;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wellnessz.integration.JobRunGuard;

/** Hourly: unacknowledged abnormal results older than 72 h are escalated to the CMO once (single run across instances). */
@Component
public class ResultEscalationJob {

    private static final Logger log = LoggerFactory.getLogger(ResultEscalationJob.class);
    static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").withZone(ZoneOffset.UTC);

    private final ResultService results;
    private final JobRunGuard guard;
    private final Clock clock;

    public ResultEscalationJob(ResultService results, JobRunGuard guard, Clock clock) {
        this.results = results;
        this.guard = guard;
        this.clock = clock;
    }

    @Scheduled(cron = "${wellnessz.record.overdue-cron:0 15 * * * *}")
    public void run() {
        if (guard.tryStart("e03.result-overdue", HOUR.format(clock.instant()))) {
            int escalated = results.escalateOverdue();
            if (escalated > 0) {
                log.info("Escalated {} overdue abnormal results to the CMO", escalated);
            }
        }
    }
}
