package com.wellnessz.e09;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wellnessz.e09.cism.CismService;
import com.wellnessz.e09.peer.PeerSupportService;
import com.wellnessz.integration.JobRunGuard;

/**
 * E09 scheduled jobs, each once per day across all instances ({@link JobRunGuard}): {@code cism-follow-ups} – 1- and 4-week
 * critical-incident follow-ups ({@code cism.follow-up}); {@code peer-callback-purge} – peer-support callback phone numbers are
 * purged after the retention period (default 30 days).
 */
@Component
public class MentalHealthJobs {

    private static final Logger log = LoggerFactory.getLogger(MentalHealthJobs.class);

    private final JobRunGuard guard;
    private final CismService cism;
    private final PeerSupportService peers;
    private final Clock clock;

    public MentalHealthJobs(JobRunGuard guard, CismService cism, PeerSupportService peers, Clock clock) {
        this.guard = guard;
        this.cism = cism;
        this.peers = peers;
        this.clock = clock;
    }

    @Scheduled(cron = "${wellnessz.mentalhealth.cism-follow-ups-cron:0 20 8 * * *}", zone = "UTC")
    public void cismFollowUps() {
        if (guard.tryStart("cism-follow-ups", today())) {
            log.info("E09 CISM follow-ups sent={}", cism.sendFollowUps());
        }
    }

    @Scheduled(cron = "${wellnessz.mentalhealth.peer-callback-purge-cron:0 40 1 * * *}", zone = "UTC")
    public void peerCallbackPurge() {
        if (guard.tryStart("peer-callback-purge", today())) {
            log.info("E09 peer-support callback numbers purged={}", peers.purgeCallbackPhones());
        }
    }

    private String today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
    }
}
