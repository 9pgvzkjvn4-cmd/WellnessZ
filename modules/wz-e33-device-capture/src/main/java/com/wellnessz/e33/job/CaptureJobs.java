package com.wellnessz.e33.job;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wellnessz.e33.service.CaptureSessionService;
import com.wellnessz.e33.service.CaptureSupport;
import com.wellnessz.e33.service.KioskService;
import com.wellnessz.integration.JobRunGuard;

/**
 * {@code device-capture-expiry}: every five minutes (one replica per run through {@link JobRunGuard}) expires capture sessions
 * that waited longer than {@code device.capture.session-timeout-minutes} and ends kiosk sessions idle for 3 minutes (privacy
 * screen). Sessions are also expired lazily when read, so a missed run delays nothing the user sees. Logs counts only.
 */
@Component
public class CaptureJobs {

    private static final Logger log = LoggerFactory.getLogger(CaptureJobs.class);

    private final JobRunGuard guard;
    private final CaptureSessionService sessions;
    private final KioskService kiosk;
    private final CaptureSupport support;
    private final Clock clock;

    public CaptureJobs(JobRunGuard guard, CaptureSessionService sessions, KioskService kiosk, CaptureSupport support, Clock clock) {
        this.guard = guard;
        this.sessions = sessions;
        this.kiosk = kiosk;
        this.support = support;
        this.clock = clock;
    }

    @Scheduled(cron = "${wellnessz.device-capture.expiry-cron:30 */5 * * * *}", zone = "UTC")
    public void expiryJob() {
        if (!support.enabled()) {
            return;
        }
        Instant minute = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        if (guard.tryStart("device-capture-expiry", minute.toString())) {
            int expired = sessions.expireDue();
            int ended = kiosk.endIdle();
            if (expired + ended > 0) {
                log.info("device-capture-expiry: expired={} kioskEnded={}", expired, ended);
            }
        }
    }
}
