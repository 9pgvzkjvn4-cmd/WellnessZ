package com.wellnessz.e32.job;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wellnessz.e32.calibration.CalibrationService;
import com.wellnessz.e32.catalogue.CatalogueService;
import com.wellnessz.e32.fleet.FleetService;
import com.wellnessz.e32.support.E32Support;
import com.wellnessz.integration.JobRunGuard;

/**
 * Scheduled console jobs, each once per run key across instances ({@link JobRunGuard}): hourly
 * {@code device-calibration-reminders} (lockout sweep + calibration / QC due reminders) and fleet health sweep (stale,
 * failing connectors); daily posture-exception expiry (re-blocks the model).
 */
@Component("e32E32Jobs")
public class E32Jobs {

    private static final Logger log = LoggerFactory.getLogger(E32Jobs.class);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").withZone(ZoneOffset.UTC);

    private final E32Support support;
    private final JobRunGuard guard;
    private final CalibrationService calibration;
    private final FleetService fleet;
    private final CatalogueService catalogue;

    public E32Jobs(E32Support support, JobRunGuard guard, CalibrationService calibration, FleetService fleet, CatalogueService catalogue) {
        this.support = support;
        this.guard = guard;
        this.calibration = calibration;
        this.fleet = fleet;
        this.catalogue = catalogue;
    }

    @Scheduled(cron = "${wellnessz.device.console.hourly-cron:0 7 * * * *}")
    public void hourly() {
        if (!support.enabled()) {
            return;
        }
        String key = HOUR.format(support.now());
        if (guard.tryStart("device-calibration-reminders", key)) {
            try {
                int n = calibration.sweep();
                log.info("device-calibration-reminders: {} reminders", n);
            } catch (RuntimeException e) {
                log.warn("device-calibration-reminders failed: {}", e.getClass().getSimpleName());
            }
        }
        if (guard.tryStart("device-fleet-health", key)) {
            try {
                fleet.healthSweep();
            } catch (RuntimeException e) {
                log.warn("device-fleet-health failed: {}", e.getClass().getSimpleName());
            }
        }
    }

    @Scheduled(cron = "${wellnessz.device.console.daily-cron:0 17 2 * * *}")
    public void daily() {
        if (!support.enabled()) {
            return;
        }
        if (guard.tryStart("device-posture-exception-expiry", support.today().toString())) {
            try {
                catalogue.expireExceptions();
            } catch (RuntimeException e) {
                log.warn("device-posture-exception-expiry failed: {}", e.getClass().getSimpleName());
            }
        }
    }
}
