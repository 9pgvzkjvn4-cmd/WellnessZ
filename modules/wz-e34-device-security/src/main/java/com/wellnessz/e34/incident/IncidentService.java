package com.wellnessz.e34.incident;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e34.events.SecurityEventService;
import com.wellnessz.e34.identity.EndpointRepository;
import com.wellnessz.e34.support.E34Settings;
import com.wellnessz.security.audit.AuditService;

/**
 * Device-compromise incidents (contract §15.8): evidence preserved by reference (archive id + SHA-256 – raw messages are not
 * copied), alerts linked, timeline, and the drill record {@code isolationCompletedAt − detectedAt} against the isolation SLA.
 */
@Service
public class IncidentService {

    public static final Set<String> EVIDENCE_TYPES = Set.of("RAW_MESSAGES", "EVENTS", "HEARTBEATS");

    public record Evidence(String type, String sha256, String archiveId, String description, String addedBy, Instant addedAt) {
    }

    public record TimelineEntry(Instant at, String entry, String actor) {
    }

    /**
     * @param minutesToIsolate drill record (isolation completed − detected), {@code null} while not isolated
     * @param withinSla        isolation completed within {@code device.security.isolation-sla-minutes}
     */
    public record IncidentView(String incidentRef, String endpointId, String status, String summary, String openedBy, Instant openedAt,
                               Instant detectedAt, Instant isolationCompletedAt, Double minutesToIsolate, Boolean withinSla, int slaMinutes,
                               List<UUID> alertIds, List<Evidence> evidence, List<TimelineEntry> timeline, String closedBy, Instant closedAt,
                               String resolution) {
    }

    public record EvidenceInput(String type, String sha256, String archiveId, String description) {
    }

    private final JdbcTemplate jdbc;
    private final SecurityEventService events;
    private final AuditService audit;
    private final E34Settings settings;

    public IncidentService(JdbcTemplate jdbc, SecurityEventService events, AuditService audit, E34Settings settings) {
        this.jdbc = jdbc;
        this.events = events;
        this.audit = audit;
        this.settings = settings;
    }

    /** Called inside the isolation transaction: opens the incident with event evidence and the detection / isolation timeline. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String openForIsolation(String endpointId, List<UUID> alertIds, Instant detectedAt, Instant isolatedAt, String actor, String reasonCode) {
        String ref = nextRef(isolatedAt);
        jdbc.update("insert into devicesec.incident (incident_ref, endpoint_id, status, summary, opened_by, opened_at, detected_at, "
                + "isolation_completed_at) values (?,?,'OPEN',?,?,?,?,?)", ref, endpointId, "Endpoint isolated: " + reasonCode, actor,
                EndpointRepository.ts(isolatedAt), EndpointRepository.ts(detectedAt), EndpointRepository.ts(isolatedAt));
        for (UUID a : alertIds) {
            jdbc.update("insert into devicesec.incident_alert (incident_ref, alert_id) values (?,?) on conflict do nothing", ref, a);
        }
        SecurityEventService.Evidence ev = events.evidenceFor(endpointId, detectedAt.minus(24, ChronoUnit.HOURS));
        insertEvidence(ref, "EVENTS", ev.sha256(), null, "Security events of the endpoint (24 h before detection): " + ev.events(), actor, isolatedAt);
        timeline(ref, detectedAt, "Anomaly detected / isolation requested (" + reasonCode + ")", actor);
        timeline(ref, isolatedAt, "Endpoint isolated – authentication refused, gateway ISOLATE command and instance quarantine requested", actor);
        return ref;
    }

    @Transactional
    public IncidentView create(String actor, String endpointId, List<UUID> alertIds, List<EvidenceInput> evidence, String summary) {
        settings.requireEnabled();
        Integer n = jdbc.queryForObject("select count(*) from devicesec.endpoint where endpoint_id = ?", Integer.class, endpointId);
        if (n == null || n == 0) {
            throw ApiException.notFound("Endpoint");
        }
        Instant now = settings.now();
        Instant detected = now;
        for (UUID a : alertIds == null ? List.<UUID>of() : alertIds) {
            List<Instant> d = jdbc.query("select detected_at from devicesec.alert where id = ?", (rs, i) -> EndpointRepository.inst(rs, "detected_at"), a);
            if (d.isEmpty()) {
                throw ApiException.badRequest("validation_failed", "Unknown alert");
            }
            detected = d.get(0).isBefore(detected) ? d.get(0) : detected;
        }
        List<Instant> isolated = jdbc.query("select isolated_at from devicesec.endpoint where endpoint_id = ? and status = 'ISOLATED'",
                (rs, i) -> EndpointRepository.inst(rs, "isolated_at"), endpointId);
        String ref = nextRef(now);
        jdbc.update("insert into devicesec.incident (incident_ref, endpoint_id, status, summary, opened_by, opened_at, detected_at, "
                + "isolation_completed_at) values (?,?,'OPEN',?,?,?,?,?)", ref, endpointId, summary, actor, EndpointRepository.ts(now),
                EndpointRepository.ts(detected), EndpointRepository.ts(isolated.isEmpty() ? null : isolated.get(0)));
        for (UUID a : alertIds == null ? List.<UUID>of() : alertIds) {
            jdbc.update("insert into devicesec.incident_alert (incident_ref, alert_id) values (?,?) on conflict do nothing", ref, a);
        }
        SecurityEventService.Evidence ev = events.evidenceFor(endpointId, detected.minus(24, ChronoUnit.HOURS));
        insertEvidence(ref, "EVENTS", ev.sha256(), null, "Security events of the endpoint (24 h before detection): " + ev.events(), actor, now);
        for (EvidenceInput e : evidence == null ? List.<EvidenceInput>of() : evidence) {
            addEvidenceRow(ref, e, actor, now);
        }
        timeline(ref, now, "Incident opened", actor);
        audit.record("DEVICE_INCIDENT_OPEN", "DEVICE_SECURITY_INCIDENT", ref, AuditService.Outcome.SUCCESS, "alerts=" + (alertIds == null ? 0 : alertIds.size()));
        return get(ref);
    }

    @Transactional
    public IncidentView addEvidence(String ref, String actor, EvidenceInput e) {
        settings.requireEnabled();
        IncidentView v = get(ref);
        if (!"OPEN".equals(v.status())) {
            throw ApiException.conflict("incident_closed", "The incident is closed");
        }
        addEvidenceRow(ref, e, actor, settings.now());
        timeline(ref, settings.now(), "Evidence added (" + e.type() + ")", actor);
        return get(ref);
    }

    @Transactional
    public IncidentView close(String ref, String actor, String resolution) {
        settings.requireEnabled();
        IncidentView v = get(ref);
        if (!"OPEN".equals(v.status())) {
            throw ApiException.conflict("incident_closed", "The incident is already closed");
        }
        Instant now = settings.now();
        jdbc.update("update devicesec.incident set status = 'CLOSED', closed_by = ?, closed_at = ?, resolution = ?, version = version + 1 "
                + "where incident_ref = ?", actor, EndpointRepository.ts(now), resolution, ref);
        timeline(ref, now, "Incident closed", actor);
        audit.record("DEVICE_INCIDENT_CLOSE", "DEVICE_SECURITY_INCIDENT", ref, AuditService.Outcome.SUCCESS, "closed");
        return get(ref);
    }

    @Transactional(readOnly = true)
    public List<IncidentView> list(String status, String endpointId, int limit) {
        return jdbc.queryForList("select incident_ref from devicesec.incident where (?::varchar is null or status = ?) "
                + "and (?::varchar is null or endpoint_id = ?) order by opened_at desc limit ?", String.class, status, status, endpointId, endpointId,
                Math.min(Math.max(limit, 1), 200)).stream().map(this::get).toList();
    }

    @Transactional(readOnly = true)
    public IncidentView get(String ref) {
        int sla = settings.isolationSlaMinutes();
        List<IncidentView> rows = jdbc.query("select incident_ref, endpoint_id, status, summary, opened_by, opened_at, detected_at, "
                + "isolation_completed_at, closed_by, closed_at, resolution from devicesec.incident where incident_ref = ?", (rs, i) -> {
                    Instant detected = EndpointRepository.inst(rs, "detected_at");
                    Instant isolated = EndpointRepository.inst(rs, "isolation_completed_at");
                    Double minutes = isolated == null ? null : Duration.between(detected, isolated).toMillis() / 60000.0;
                    return new IncidentView(rs.getString("incident_ref"), rs.getString("endpoint_id"), rs.getString("status"), rs.getString("summary"),
                            rs.getString("opened_by"), EndpointRepository.inst(rs, "opened_at"), detected, isolated, minutes,
                            minutes == null ? null : minutes <= sla, sla, List.of(), List.of(), List.of(), rs.getString("closed_by"),
                            EndpointRepository.inst(rs, "closed_at"), rs.getString("resolution"));
                }, ref);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Incident");
        }
        IncidentView v = rows.get(0);
        List<UUID> alerts = jdbc.queryForList("select alert_id from devicesec.incident_alert where incident_ref = ? order by alert_id", UUID.class, ref);
        List<Evidence> evidence = jdbc.query("select type, sha256, archive_id, description, added_by, added_at from devicesec.incident_evidence "
                + "where incident_ref = ? order by added_at, id", (rs, i) -> new Evidence(rs.getString("type"), rs.getString("sha256"),
                rs.getString("archive_id"), rs.getString("description"), rs.getString("added_by"), EndpointRepository.inst(rs, "added_at")), ref);
        List<TimelineEntry> timeline = jdbc.query("select at, entry, actor from devicesec.incident_timeline where incident_ref = ? order by at, id",
                (rs, i) -> new TimelineEntry(EndpointRepository.inst(rs, "at"), rs.getString("entry"), rs.getString("actor")), ref);
        return new IncidentView(v.incidentRef(), v.endpointId(), v.status(), v.summary(), v.openedBy(), v.openedAt(), v.detectedAt(),
                v.isolationCompletedAt(), v.minutesToIsolate(), v.withinSla(), v.slaMinutes(), alerts, evidence, timeline, v.closedBy(), v.closedAt(),
                v.resolution());
    }

    private void addEvidenceRow(String ref, EvidenceInput e, String actor, Instant now) {
        if (e == null || e.type() == null || !EVIDENCE_TYPES.contains(e.type()) || e.sha256() == null || !e.sha256().matches("[0-9a-f]{64}")) {
            throw ApiException.badRequest("validation_failed", "Evidence needs a type and a SHA-256");
        }
        insertEvidence(ref, e.type(), e.sha256(), e.archiveId(), e.description(), actor, now);
    }

    private void insertEvidence(String ref, String type, String sha256, String archiveId, String description, String actor, Instant at) {
        jdbc.update("insert into devicesec.incident_evidence (id, incident_ref, type, sha256, archive_id, description, added_by, added_at) "
                + "values (?,?,?,?,?,?,?,?)", UUID.randomUUID(), ref, type, sha256, archiveId, description, actor, EndpointRepository.ts(at));
    }

    private void timeline(String ref, Instant at, String entry, String actor) {
        jdbc.update("insert into devicesec.incident_timeline (id, incident_ref, at, entry, actor) values (?,?,?,?,?)", UUID.randomUUID(), ref,
                EndpointRepository.ts(at), entry.length() > 300 ? entry.substring(0, 300) : entry, actor);
    }

    private String nextRef(Instant at) {
        Long seq = jdbc.queryForObject("select nextval('devicesec.incident_seq')", Long.class);
        long n = seq == null ? 0L : seq.longValue();
        return String.format("DSI-%d-%06d", at.atZone(ZoneOffset.UTC).getYear(), n);
    }
}
