package com.wellnessz.e34.events;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceSecurityEventPublisher;
import com.wellnessz.e34.events.E34Events.SiemSecurityEvent;
import com.wellnessz.e34.identity.EndpointRepository;
import com.wellnessz.integration.outbox.EventBus;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link DeviceSecurityEventPublisher} of E34 (E34-HLR-08, E34-F07): every event is minimised, appended to the hash-chained,
 * append-only {@code devicesec.security_event} table (writers serialised with an advisory lock, as the platform audit) and
 * forwarded to the SIEM through the outbox destination {@code siem-device-security} in the same transaction. The store runs in
 * its own transaction ({@code REQUIRES_NEW}): security evidence survives a rollback of the caller. After commit the anomaly
 * rules ({@link SecurityEventListener}) evaluate the event.
 */
@Service
public class SecurityEventService implements DeviceSecurityEventPublisher {

    private static final long CHAIN_LOCK_KEY = 0x5744_4445_5653_4543L; // "WDDEVSEC"
    private static final Logger log = LoggerFactory.getLogger(SecurityEventService.class);
    private static final TypeReference<TreeMap<String, String>> MAP = new TypeReference<>() { };
    static final String COLUMNS = "id, event_id, type, severity, endpoint_id, device_instance_id, site_code, zone, actor, source_module, reason_code, "
            + "remote_address, attributes, occurred_at, hash";

    private final JdbcTemplate jdbc;
    private final EventBus bus;
    private final JsonMapper json;
    private final TransactionTemplate requiresNew;
    private final ObjectProvider<SecurityEventListener> listeners;
    private final java.time.Clock clock;

    public SecurityEventService(JdbcTemplate jdbc, EventBus bus, JsonMapper json, PlatformTransactionManager tm,
                                ObjectProvider<SecurityEventListener> listeners, java.time.Clock clock) {
        this.jdbc = jdbc;
        this.bus = bus;
        this.json = json;
        this.requiresNew = new TransactionTemplate(tm);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.listeners = listeners;
        this.clock = clock;
    }

    @Override
    public void publish(DeviceSecurityEvent event) {
        record(event);
    }

    /** Convenience for E34 code. */
    public StoredSecurityEvent record(String type, DeviceCodes.Severity severity, String endpointId, String deviceInstanceId, String actor,
                                      String reasonCode, Map<String, String> attributes) {
        return record(new DeviceSecurityEvent(type, severity, endpointId, deviceInstanceId, null, actor, "E34", reasonCode, attributes,
                clock.instant()));
    }

    public StoredSecurityEvent record(DeviceSecurityEvent event) {
        StoredSecurityEvent stored = requiresNew.execute(s -> append(event));
        if (stored != null) {
            listeners.orderedStream().forEach(l -> {
                try {
                    l.onSecurityEvent(stored);
                } catch (RuntimeException e) {
                    log.warn("Security event listener failed for {}: {}", stored.type(), e.getClass().getSimpleName());
                }
            });
        }
        return stored;
    }

    private StoredSecurityEvent append(DeviceSecurityEvent e) {
        Map<String, String> attrs = SecurityEventChain.minimise(e.attributes());
        String type = SecurityEventChain.safeType(e.type());
        String severity = (e.severity() == null ? DeviceCodes.Severity.INFO : e.severity()).name();
        String module = SecurityEventChain.safeModule(e.sourceModule());
        String endpointId = cap(e.endpointId(), 64);
        String siteCode = cap(e.siteCode(), 20);
        String zone = null;
        if (endpointId != null) {
            List<String[]> ep = jdbc.query("select site_code, zone from devicesec.endpoint where endpoint_id = ?",
                    (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, endpointId);
            if (!ep.isEmpty()) {
                siteCode = siteCode == null ? ep.get(0)[0] : siteCode;
                zone = ep.get(0)[1];
            }
        }
        String remote = cap(attrs.get("remoteAddress"), 64);
        String attributesJson = json.writeValueAsString(attrs);
        Instant occurred = (e.occurredAt() == null ? clock.instant() : e.occurredAt()).truncatedTo(ChronoUnit.MILLIS);
        String actor = cap(e.actor() == null ? "system" : com.wellnessz.common.web.LogSanitizer.clean(e.actor()), 200);
        String reason = cap(e.reasonCode() == null ? null : e.reasonCode().replaceAll("[^A-Za-z0-9_.:\\-]", "_"), 60);
        String deviceInstanceId = cap(e.deviceInstanceId(), 64);
        String eventId = UUID.randomUUID().toString();

        jdbc.queryForList("select pg_advisory_xact_lock(?)", CHAIN_LOCK_KEY);
        List<String> last = jdbc.queryForList("select hash from devicesec.security_event order by id desc limit 1", String.class);
        String prev = last.isEmpty() ? SecurityEventChain.GENESIS : last.get(0);
        String hash = SecurityEventChain.link(prev, SecurityEventChain.canonical(eventId, type, severity, endpointId, deviceInstanceId, siteCode,
                zone, actor, module, reason, remote, attributesJson, occurred));
        Long id = jdbc.queryForObject("insert into devicesec.security_event (event_id, type, severity, endpoint_id, device_instance_id, site_code, "
                + "zone, actor, source_module, reason_code, remote_address, attributes, occurred_at, recorded_at, prev_hash, hash) "
                + "values (?::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) returning id", Long.class, eventId, type, severity, endpointId, deviceInstanceId,
                siteCode, zone, actor, module, reason, remote, attributesJson, EndpointRepository.ts(occurred), EndpointRepository.ts(clock.instant()),
                prev, hash);
        bus.publish(new SiemSecurityEvent(UUID.randomUUID().toString(), eventId, type, severity, endpointId, deviceInstanceId, siteCode, zone,
                module, reason, actor, remote, attrs.get("protocol"), attrs.get("port"), attrs.get("rate"), attrs.get("keyId"), hash,
                SiemSecurityEvent.TYPE, occurred), E34Events.SIEM_DESTINATION);
        return new StoredSecurityEvent(id == null ? 0 : id, eventId, type, severity, endpointId, deviceInstanceId, siteCode, zone, actor, module,
                reason, remote, attrs, occurred, hash);
    }

    /** Events by filter, newest first (SECURITY_OPS, AUDITOR). */
    public List<StoredSecurityEvent> query(String type, String endpointId, Instant from, Instant to, int limit) {
        return jdbc.query("select " + COLUMNS + " from devicesec.security_event where (?::varchar is null or type = ?) "
                + "and (?::varchar is null or endpoint_id = ?) and (?::timestamptz is null or occurred_at >= ?) "
                + "and (?::timestamptz is null or occurred_at < ?) order by id desc limit ?", this::map, type, type, endpointId, endpointId,
                EndpointRepository.ts(from), EndpointRepository.ts(from), EndpointRepository.ts(to), EndpointRepository.ts(to),
                Math.min(Math.max(limit, 1), 500));
    }

    /** Number of events of a type for a subject column in a window (anomaly rules). */
    public int count(String type, String subjectColumn, String subject, String reasonCode, Instant since) {
        Integer n = switch (subjectColumn) {
            case "endpoint_id" -> jdbc.queryForObject("select count(*) from devicesec.security_event where type = ? and endpoint_id = ? "
                    + "and (?::varchar is null or reason_code = ?) and occurred_at >= ?", Integer.class, type, subject, reasonCode, reasonCode,
                    EndpointRepository.ts(since));
            case "remote_address" -> jdbc.queryForObject("select count(*) from devicesec.security_event where type = ? and remote_address = ? "
                    + "and (?::varchar is null or reason_code = ?) and occurred_at >= ?", Integer.class, type, subject, reasonCode, reasonCode,
                    EndpointRepository.ts(since));
            case "device_instance_id" -> jdbc.queryForObject("select count(*) from devicesec.security_event where type = ? and device_instance_id = ? "
                    + "and (?::varchar is null or reason_code = ?) and occurred_at >= ?", Integer.class, type, subject, reasonCode, reasonCode,
                    EndpointRepository.ts(since));
            default -> throw new IllegalArgumentException("subject column");
        };
        return n == null ? 0 : n;
    }

    /** Count of events of a type whose attribute {@code connectorId} matches, in a window (webhook signature failures). */
    public int countByConnector(String type, String connectorId, Instant since) {
        Integer n = jdbc.queryForObject("select count(*) from devicesec.security_event where type = ? and occurred_at >= ? "
                + "and (device_instance_id = ? or attributes like ?)", Integer.class, type, EndpointRepository.ts(since), connectorId,
                "%\"connectorId\":\"" + connectorId.replace("\\", "").replace("\"", "").replace("%", "").replace("_", "\\_") + "\"%");
        return n == null ? 0 : n;
    }

    /** Re-computes the whole chain (contract §15.9 {@code GET /device-security/events/verify}). */
    public ChainVerification verify() {
        long[] checked = {0};
        long[] broken = {-1};
        String[] prev = {SecurityEventChain.GENESIS};
        jdbc.query("select id, event_id, type, severity, endpoint_id, device_instance_id, site_code, zone, actor, source_module, reason_code, "
                + "remote_address, attributes, occurred_at, prev_hash, hash from devicesec.security_event order by id", rs -> {
                    if (broken[0] >= 0) {
                        return;
                    }
                    checked[0]++;
                    String expected = SecurityEventChain.link(prev[0], SecurityEventChain.canonical(rs.getString("event_id"), rs.getString("type"),
                            rs.getString("severity"), rs.getString("endpoint_id"), rs.getString("device_instance_id"), rs.getString("site_code"),
                            rs.getString("zone"), rs.getString("actor"), rs.getString("source_module"), rs.getString("reason_code"),
                            rs.getString("remote_address"), rs.getString("attributes"), EndpointRepository.inst(rs, "occurred_at")));
                    if (!com.wellnessz.e34.support.TokenHasher.constantTimeEquals(prev[0], rs.getString("prev_hash"))
                            || !com.wellnessz.e34.support.TokenHasher.constantTimeEquals(expected, rs.getString("hash"))) {
                        broken[0] = rs.getLong("id");
                        return;
                    }
                    prev[0] = rs.getString("hash");
                });
        return new ChainVerification(broken[0] < 0, checked[0], broken[0] < 0 ? null : broken[0]);
    }

    /** SHA-256 over the chain hashes of an endpoint's events since {@code since} (incident evidence – hashes, not copies). */
    public Evidence evidenceFor(String endpointId, Instant since) {
        List<String> hashes = jdbc.queryForList("select hash from devicesec.security_event where endpoint_id = ? and occurred_at >= ? order by id",
                String.class, endpointId, EndpointRepository.ts(since));
        return new Evidence(com.wellnessz.e34.support.TokenHasher.sha256Hex(String.join("\n", hashes)), hashes.size());
    }

    public record Evidence(String sha256, int events) {
    }

    /** @param firstBrokenId id of the first event whose hash does not verify, {@code null} when intact */
    public record ChainVerification(boolean intact, long checked, Long firstBrokenId) {
    }

    StoredSecurityEvent map(ResultSet rs, int i) throws SQLException {
        Map<String, String> attrs;
        try {
            attrs = json.readValue(rs.getString("attributes"), MAP);
        } catch (RuntimeException ex) {
            attrs = Map.of();
        }
        return new StoredSecurityEvent(rs.getLong("id"), rs.getString("event_id"), rs.getString("type"), rs.getString("severity"),
                rs.getString("endpoint_id"), rs.getString("device_instance_id"), rs.getString("site_code"), rs.getString("zone"),
                rs.getString("actor"), rs.getString("source_module"), rs.getString("reason_code"), rs.getString("remote_address"), attrs,
                EndpointRepository.inst(rs, "occurred_at"), rs.getString("hash"));
    }

    private static String cap(String s, int max) {
        if (s == null) {
            return null;
        }
        String v = s.strip();
        if (v.isEmpty()) {
            return null;
        }
        return v.length() > max ? v.substring(0, max) : v;
    }
}
