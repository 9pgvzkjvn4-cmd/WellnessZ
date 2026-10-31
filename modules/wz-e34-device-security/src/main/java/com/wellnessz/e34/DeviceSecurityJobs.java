package com.wellnessz.e34;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.e34.anomaly.AnomalyEngine;
import com.wellnessz.e34.events.SecurityEventService;
import com.wellnessz.e34.identity.EndpointRepository;
import com.wellnessz.e34.identity.TrafficMonitor;
import com.wellnessz.e34.support.E34Settings;
import com.wellnessz.e34.support.SecurityNotifications;
import com.wellnessz.integration.JobRunGuard;

/**
 * E34 jobs: isolation sweep (every 30 s – completes automatic isolations well inside the 15-minute playbook deadline), traffic
 * flush (every minute – rate baselines, last seen), certificate expiry (daily, {@code CERTIFICATE_EXPIRING} within the warning
 * period) and device-security retention (daily – traffic counters older than 8 days). Daily jobs run once across replicas
 * ({@link JobRunGuard}); the sweep is idempotent and row-locked.
 */
@Component
public class DeviceSecurityJobs {

    private static final Logger log = LoggerFactory.getLogger(DeviceSecurityJobs.class);

    private final AnomalyEngine engine;
    private final TrafficMonitor traffic;
    private final EndpointRepository endpoints;
    private final SecurityEventService events;
    private final SecurityNotifications notify;
    private final JobRunGuard guard;
    private final JdbcTemplate jdbc;
    private final E34Settings settings;

    public DeviceSecurityJobs(AnomalyEngine engine, TrafficMonitor traffic, EndpointRepository endpoints, SecurityEventService events,
                              SecurityNotifications notify, JobRunGuard guard, JdbcTemplate jdbc, E34Settings settings) {
        this.engine = engine;
        this.traffic = traffic;
        this.endpoints = endpoints;
        this.events = events;
        this.notify = notify;
        this.guard = guard;
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${wellnessz.device.security.isolation-sweep:PT30S}", initialDelayString = "PT20S")
    public void isolationSweep() {
        try {
            int n = engine.sweep();
            if (n > 0) {
                log.info("device-security isolation sweep: isolated={}", n);
            }
        } catch (RuntimeException e) {
            log.warn("device-security isolation sweep failed: {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${wellnessz.device.security.traffic-flush:PT60S}", initialDelayString = "PT60S")
    public void trafficFlush() {
        try {
            traffic.flush();
        } catch (RuntimeException e) {
            log.warn("device-security traffic flush failed: {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(cron = "${wellnessz.device.security.cert-expiry-cron:0 10 3 * * *}", zone = "UTC")
    public void certificateExpiryJob() {
        LocalDate today = LocalDate.ofInstant(settings.now(), ZoneOffset.UTC);
        if (settings.enabled() && guard.tryStart("device-cert-expiry", today.toString())) {
            log.info("device-cert-expiry: warned={}", certificateExpiry());
        }
    }

    /** Raises {@code CERTIFICATE_EXPIRING} for active endpoints whose certificate expires within the warning period. */
    public int certificateExpiry() {
        Instant now = settings.now();
        int days = settings.certExpiryWarningDays();
        int n = 0;
        for (EndpointRepository.Endpoint ep : endpoints.expiringCertificates(now.plus(days, ChronoUnit.DAYS))) {
            events.record("CERTIFICATE_EXPIRING", DeviceCodes.Severity.LOW, ep.endpointId(), null, "system", "certificate_expiring",
                    Map.of("certificateSerial", String.valueOf(ep.certSerial())));
            notify.certificateExpiring(ep.endpointId(), LocalDate.ofInstant(ep.certNotAfter(), ZoneOffset.UTC));
            n++;
        }
        return n;
    }

    @Scheduled(cron = "${wellnessz.device.security.retention-cron:0 40 3 * * *}", zone = "UTC")
    public void retentionJob() {
        LocalDate today = LocalDate.ofInstant(settings.now(), ZoneOffset.UTC);
        if (settings.enabled() && guard.tryStart("device-security-retention", today.toString())) {
            int purged = jdbc.update("delete from devicesec.traffic_minute where minute_start < ?",
                    EndpointRepository.ts(settings.now().minus(8, ChronoUnit.DAYS)));
            log.info("device-security-retention: trafficMinutesPurged={}", purged);
        }
    }
}
