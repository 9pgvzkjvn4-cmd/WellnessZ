package com.wellnessz.e31.job;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e31.pipeline.PipelineProcessor;
import com.wellnessz.e31.service.OperationsService;
import com.wellnessz.e31.store.MessageStore;
import com.wellnessz.e31.store.ObservationStore;
import com.wellnessz.e31.store.RawPayloadStore;
import com.wellnessz.integration.JobRunGuard;
import com.wellnessz.masterdata.StaffMember;
import com.wellnessz.masterdata.StaffMemberRepository;
import com.wellnessz.notification.Channel;
import com.wellnessz.notification.NotificationService;
import com.wellnessz.notification.Priority;

/**
 * Engine jobs: the pipeline worker (due retries and messages a crashed request left behind, route retries), attachment
 * housekeeping (unpaired PDFs → {@code attachment_unpaired}, unreferenced uploads purged after 24 h, webhook nonces), daily
 * retention ({@code device-retention}, {@link JobRunGuard}) and content-free connector / unmatched-queue alerts to the
 * device administrators.
 */
@Component("e31EngineJobs")
public class EngineJobs {

    private static final Logger log = LoggerFactory.getLogger(EngineJobs.class);

    private final PipelineProcessor processor;
    private final MessageStore messages;
    private final ObservationStore observations;
    private final RawPayloadStore raw;
    private final OperationsService ops;
    private final JobRunGuard guard;
    private final ModuleRegistry modules;
    private final StaffMemberRepository staff;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String portalLink;

    public EngineJobs(PipelineProcessor processor, MessageStore messages, ObservationStore observations, RawPayloadStore raw, OperationsService ops,
                      JobRunGuard guard, ModuleRegistry modules, StaffMemberRepository staff, NotificationService notifications, JdbcTemplate jdbc,
                      Clock clock, @Value("${wellnessz.notification.oh-workspace-url:https://oh.wellnessz.local}") String workspaceUrl) {
        this.processor = processor;
        this.messages = messages;
        this.observations = observations;
        this.raw = raw;
        this.ops = ops;
        this.guard = guard;
        this.modules = modules;
        this.staff = staff;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
        this.portalLink = workspaceUrl + "/#/devices/operations";
    }

    @Scheduled(fixedDelayString = "${wellnessz.device.worker.poll-interval-ms:5000}", initialDelayString = "${wellnessz.device.worker.initial-delay-ms:20000}")
    public void work() {
        if (!modules.isEnabled("E31")) {
            return;
        }
        int processed = drain();
        if (processed > 0) {
            log.debug("Device pipeline worker processed {} messages", processed);
        }
    }

    /** One worker pass: due messages, then route retries (back-off by attempt count). */
    public int drain() {
        int n = 0;
        for (UUID id : messages.due(100)) {
            processor.process(id, PipelineProcessor.Options.DEFAULT);
            n++;
        }
        Instant now = clock.instant();
        for (ObservationStore.ObservationRow o : observations.routePending(now.minusSeconds(5), 50)) {
            long wait = switch (Math.min(o.routeAttempts(), 5)) {
                case 0, 1 -> 5;
                case 2 -> 30;
                case 3 -> 120;
                case 4 -> 600;
                default -> 3600;
            };
            if (o.updatedAt().plusSeconds(wait).isBefore(now)) {
                processor.retryRoute(o.id());
                n++;
            }
        }
        return n;
    }

    @Scheduled(fixedDelayString = "${wellnessz.device.housekeeping-interval-ms:600000}", initialDelayString = "${wellnessz.device.worker.initial-delay-ms:20000}")
    public void housekeeping() {
        if (!modules.isEnabled("E31")) {
            return;
        }
        housekeep();
    }

    public void housekeep() {
        Instant now = clock.instant();
        for (MessageStore.MessageRow m : messages.unpairedOlderThan(now.minus(Duration.ofMinutes(10)))) {
            processor.tx().executeWithoutResult(s -> {
                messages.update(m.id(), "PARSE", "DEAD_LETTER", m.attempts() + 1, null, "attachment_unpaired", null, null);
                messages.deadLetter(m, null, "PARSE", "attachment_unpaired", "PDF export without its data file", m.attempts() + 1);
            });
        }
        raw.purgeUnreferenced(now.minus(Duration.ofHours(24)));
        jdbc.update("delete from deviceint.webhook_nonce where received_at < ?", Timestamp.from(now.minus(Duration.ofMinutes(10))));
    }

    /** {@code device-retention}: raw payloads past their purge date are emptied (hash kept), once per day across instances. */
    @Scheduled(cron = "${wellnessz.device.retention-cron:0 40 2 * * *}")
    public void retention() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!modules.isEnabled("E31") || !guard.tryStart("device-retention", today.toString())) {
            return;
        }
        int purged = raw.purgeExpired(today);
        log.info("device-retention: {} raw payloads purged under DEVICE_RAW_UNFILED", purged);
    }

    @Scheduled(fixedDelayString = "${wellnessz.device.alert-interval-ms:300000}", initialDelayString = "${wellnessz.device.worker.initial-delay-ms:20000}")
    public void alerts() {
        if (!modules.isEnabled("E31")) {
            return;
        }
        notifyAlerts();
    }

    /** Content-free notifications (reference, site, level only) to active device administrators, at most once per alert and hour. */
    public int notifyAlerts() {
        List<StaffMember> admins = staff.findByActiveTrueOrderByDisplayName().stream()
                .filter(s -> StaffMember.list(s.getRoles()).contains(Roles.DEVICE_ADMIN) && s.getEmail() != null).toList();
        if (admins.isEmpty()) {
            return 0;
        }
        String hour = clock.instant().truncatedTo(ChronoUnit.HOURS).toString();
        int sent = 0;
        for (OperationsService.Alert a : ops.alerts()) {
            String key = a.type() + "|" + (a.deviceInstanceId() == null ? a.siteCode() : a.deviceInstanceId());
            if (!guard.tryStart("device-alert", truncate(key + "|" + hour, 200))) {
                continue;
            }
            String template = "UNMATCHED_SLA".equals(a.type()) ? "device.unmatched-queue" : "QUEUE_CAPACITY".equals(a.type())
                    ? "device.gateway-backlog" : "device.connector-alert";
            Map<String, String> params = "UNMATCHED_SLA".equals(a.type())
                    ? Map.of("site", a.siteCode() == null ? "all sites" : a.siteCode(), "level", a.level(), "portalLink", portalLink)
                    : Map.of("reference", a.deviceInstanceId() == null ? "gateway" : a.deviceInstanceId(), "site", a.siteCode() == null ? "-" : a.siteCode(),
                            "level", a.level(), "portalLink", portalLink);
            for (StaffMember s : admins) {
                notifications.enqueue(s.getUsername(), s.getEmail(), Channel.EMAIL, "HIGH".equals(a.level()) ? Priority.URGENT : Priority.NORMAL, template,
                        params, ZoneOffset.UTC);
                sent++;
            }
        }
        return sent;
    }

    static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
