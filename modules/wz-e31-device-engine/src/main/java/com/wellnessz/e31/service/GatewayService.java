package com.wellnessz.e31.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceEndpointPrincipal;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.casekernel.device.DeviceWorklistSource;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.driver.DriverRuntime;
import com.wellnessz.e31.store.MessageStore;
import com.wellnessz.e31.support.EngineException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gateway / Device Host agent support (contract sections 7 and 16): pulled configuration ({@code GatewayConfig}), heartbeat
 * with queued commands, command results, minimised worklist push (E31-HLR-08) and secret references bound to the endpoint's
 * instances.
 */
@Service("e31GatewayService")
public class GatewayService {

    private final DeviceSpis spis;
    private final DriverRuntime drivers;
    private final MessageStore messages;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public GatewayService(DeviceSpis spis, DriverRuntime drivers, MessageStore messages, JdbcTemplate jdbc, JsonMapper json, Clock clock) {
        this.spis = spis;
        this.drivers = drivers;
        this.messages = messages;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    List<DeviceCatalogueLookup.DeviceInstance> instances(DeviceEndpointPrincipal p) {
        List<DeviceCatalogueLookup.DeviceInstance> out = new ArrayList<>(spis.catalogue().instancesForEndpoint(p.endpointId(), null));
        for (String id : p.deviceInstanceIds()) {
            if (out.stream().noneMatch(i -> i.deviceInstanceId().equals(id))) {
                spis.catalogue().instance(id).ifPresent(out::add);
            }
        }
        return out.stream().filter(i -> Objects.equals(i.tenantCode() == null ? "GROUP" : i.tenantCode(), p.tenantCode() == null ? "GROUP" : p.tenantCode()))
                .toList();
    }

    public int configVersion(DeviceEndpointPrincipal p) {
        return instances(p).stream().mapToInt(DeviceCatalogueLookup.DeviceInstance::configVersion).max().orElse(0);
    }

    /** {@code GatewayConfig} – secrets only as references, resolved on demand. */
    public Map<String, Object> config(DeviceEndpointPrincipal p) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (DeviceCatalogueLookup.DeviceInstance i : instances(p)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceInstanceId", i.deviceInstanceId());
            m.put("option", i.option() == null ? null : i.option().code());
            m.put("driverPackageId", i.driverPackageId());
            m.put("driverVersion", i.driverVersion());
            m.put("status", i.status());
            m.put("parameters", i.parameters());
            try {
                DriverPackage pkg = drivers.forInstance(i, null);
                JsonNode protocol = pkg.protocol();
                if (protocol != null) {
                    if (protocol.has("framing")) {
                        m.put("framing", json.treeToValue(protocol.get("framing"), Map.class));
                    }
                    if (protocol.has("mllpPort")) {
                        m.put("listener", Map.of("protocol", "MLLP", "port", protocol.get("mllpPort").asInt(), "tls", protocol.path("tls").asString("REQUIRED")));
                    }
                    if (protocol.has("pattern")) {
                        m.put("watch", Map.of("pattern", protocol.get("pattern").asString(), "stabilitySeconds", 5,
                                "encoding", protocol.path("encoding").asString("UTF-8")));
                    }
                    if (protocol.has("service")) {
                        m.put("ble", Map.of("service", protocol.get("service").asString(), "characteristics",
                                List.of(protocol.path("characteristic").asString(""))));
                    }
                    if (protocol.has("query")) {
                        Map<String, Object> db = new LinkedHashMap<>();
                        db.put("dialect", protocol.path("dialect").asString(null));
                        db.put("query", protocol.get("query").asString());
                        db.put("watermarkColumn", protocol.path("watermarkColumn").asString(null));
                        db.put("credentialRef", i.parameters().getOrDefault("dbCredentialRef", null));
                        m.put("db", db);
                    }
                }
                m.put("worklist", Map.of("mode", pkg.capabilities().worklistPush()));
            } catch (EngineException | ApiException e) {
                m.put("driverStatus", e instanceof EngineException ee ? ee.code() : ((ApiException) e).code());
            }
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("endpointId", p.endpointId());
        out.put("configVersion", list.isEmpty() ? 0 : configVersion(p));
        out.put("role", p.kind() == DeviceCodes.EndpointKind.DEVICE_HOST_AGENT ? "DEVICE_HOST_AGENT" : "GATEWAY");
        out.put("heartbeatSeconds", 60);
        out.put("instances", list);
        out.put("queue", Map.of("maxBytes", 2L * 1024 * 1024 * 1024, "retentionHours", 72));
        out.put("ntpServers", List.of());
        out.put("logLevel", "INFO");
        return out;
    }

    /** Heartbeat: gateway state, device last-seen; returns pending commands (marked SENT) and the current config version. */
    public Map<String, Object> heartbeat(DeviceEndpointPrincipal p, JsonNode hb) {
        Instant now = clock.instant();
        JsonNode queue = hb.path("queue");
        Integer depth = queue.has("depth") ? queue.get("depth").asInt() : null;
        Integer capacity = queue.has("capacityPct") ? queue.get("capacityPct").asInt() : null;
        Instant oldest = queue.hasNonNull("oldestCapturedAt") ? Instant.parse(queue.get("oldestCapturedAt").asString()) : null;
        int n = jdbc.update("""
                update deviceint.gateway_state set gateway_version = ?, config_version = ?, ntp_offset_ms = ?, queue_depth = ?, queue_capacity_pct = ?,
                    oldest_captured_at = ?, disk_free_pct = ?, hardening_status = ?, last_heartbeat_at = ? where endpoint_id = ?""",
                text(hb, "gatewayVersion"), hb.has("configVersion") ? hb.get("configVersion").asInt() : null,
                hb.has("ntpOffsetMillis") ? hb.get("ntpOffsetMillis").asLong() : null, depth, capacity, oldest == null ? null : Timestamp.from(oldest),
                hb.has("diskFreePct") ? hb.get("diskFreePct").asInt() : null, hb.path("hardening").path("status").asString(null), Timestamp.from(now),
                p.endpointId());
        if (n == 0) {
            jdbc.update("""
                    insert into deviceint.gateway_state (endpoint_id, tenant_code, site_code, gateway_version, config_version, ntp_offset_ms, queue_depth,
                        queue_capacity_pct, oldest_captured_at, disk_free_pct, hardening_status, last_heartbeat_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    p.endpointId(), p.tenantCode() == null ? "GROUP" : p.tenantCode(), p.siteCode(), text(hb, "gatewayVersion"),
                    hb.has("configVersion") ? hb.get("configVersion").asInt() : null, hb.has("ntpOffsetMillis") ? hb.get("ntpOffsetMillis").asLong() : null,
                    depth, capacity, oldest == null ? null : Timestamp.from(oldest), hb.has("diskFreePct") ? hb.get("diskFreePct").asInt() : null,
                    hb.path("hardening").path("status").asString(null), Timestamp.from(now));
        }
        List<String> allowed = instances(p).stream().map(DeviceCatalogueLookup.DeviceInstance::deviceInstanceId).toList();
        for (JsonNode d : hb.path("devices")) {
            String id = d.path("deviceInstanceId").asString("");
            if (!allowed.contains(id)) {
                continue;
            }
            Instant seen = d.hasNonNull("lastSeenAt") ? Instant.parse(d.get("lastSeenAt").asString()) : now;
            int u = jdbc.update("update deviceint.connector_state set last_seen_at = greatest(coalesce(last_seen_at, ?), ?), device_state = ?, updated_at = ? where device_instance_id = ?",
                    Timestamp.from(seen), Timestamp.from(seen), d.path("state").asString(null), Timestamp.from(now), id);
            if (u == 0) {
                jdbc.update("""
                        insert into deviceint.connector_state (device_instance_id, tenant_code, endpoint_id, site_code, last_seen_at, device_state,
                            endpoint_status, updated_at) values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?) on conflict (device_instance_id) do nothing""",
                        id, p.tenantCode() == null ? "GROUP" : p.tenantCode(), p.endpointId(), p.siteCode(), Timestamp.from(seen),
                        d.path("state").asString(null), Timestamp.from(now));
            }
        }
        List<Map<String, Object>> commands = new ArrayList<>();
        for (Map<String, Object> c : jdbc.queryForList("select id, type, device_instance_id, payload from deviceint.gateway_command where endpoint_id = ? and status = 'PENDING' order by created_at",
                p.endpointId())) {
            Map<String, Object> cmd = new LinkedHashMap<>();
            cmd.put("commandId", c.get("id"));
            cmd.put("type", c.get("type"));
            if (c.get("device_instance_id") != null) {
                cmd.put("deviceInstanceId", c.get("device_instance_id"));
            }
            if (c.get("payload") != null) {
                cmd.put("payload", json.readTree((String) c.get("payload")));
            }
            commands.add(cmd);
            jdbc.update("update deviceint.gateway_command set status = 'SENT', sent_at = ? where id = ?", Timestamp.from(now), c.get("id"));
        }
        int current = configVersion(p);
        if (hb.has("configVersion") && hb.get("configVersion").asInt() < current && commands.stream().noneMatch(c -> "RELOAD_CONFIG".equals(c.get("type")))) {
            commands.add(Map.of("commandId", UUID.randomUUID(), "type", "RELOAD_CONFIG"));
        }
        return Map.of("serverTime", now, "configVersion", current, "commands", commands);
    }

    public UUID queueCommand(String endpointId, String type, String deviceInstanceId, Map<String, Object> payload, String actor) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into deviceint.gateway_command (id, endpoint_id, type, device_instance_id, payload, status, created_by, created_at)
                values (?, ?, ?, ?, ?, 'PENDING', ?, ?)""", id, endpointId, type, deviceInstanceId, payload == null ? null : json.writeValueAsString(payload),
                actor, Timestamp.from(clock.instant()));
        return id;
    }

    public void commandResult(DeviceEndpointPrincipal p, UUID commandId, String status, String detail) {
        if (!"DONE".equals(status) && !"FAILED".equals(status)) {
            throw ApiException.badRequest("validation_failed", "status must be DONE or FAILED");
        }
        int n = jdbc.update("update deviceint.gateway_command set status = ?, detail = ?, completed_at = ? where id = ? and endpoint_id = ?", status,
                detail == null ? null : detail.substring(0, Math.min(500, detail.length())), Timestamp.from(clock.instant()), commandId, p.endpointId());
        if (n == 0) {
            throw ApiException.notFound("Command");
        }
    }

    /**
     * Minimised worklist for push to the device / vendor software (E31-HLR-08): only for instances whose driver declares a
     * worklist capability; date of birth and sex only when the driver declares it needs them ({@code protocol.worklistFields}).
     */
    public List<Map<String, Object>> worklist(DeviceEndpointPrincipal p, String deviceInstanceId, LocalDate date) {
        DeviceCatalogueLookup.DeviceInstance i = instances(p).stream().filter(x -> x.deviceInstanceId().equals(deviceInstanceId)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "device_not_authorised_for_endpoint", "The endpoint may not use this device"));
        DriverPackage pkg = drivers.forInstance(i, null);
        if ("NONE".equals(pkg.capabilities().worklistPush())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "worklist_not_supported", "The device driver declares no worklist capability");
        }
        List<String> needed = new ArrayList<>();
        if (pkg.protocol() != null && pkg.protocol().has("worklistFields")) {
            pkg.protocol().get("worklistFields").forEach(n -> needed.add(n.asString()));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (DeviceCodes.Category c : i.categories()) {
            for (DeviceWorklistSource s : spis.worklists()) {
                for (DeviceWorklistSource.WorklistEntry e : s.worklist(i.siteCode(), date, c)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("orderRef", e.orderRef());
                    m.put("employeeId", e.employeeId());
                    m.put("displayName", e.displayName());
                    if (e.badgeId() != null && needed.contains("badgeId")) {
                        m.put("badgeId", e.badgeId());
                    }
                    if (e.dateOfBirth() != null && needed.contains("dateOfBirth")) {
                        m.put("dateOfBirth", e.dateOfBirth());
                    }
                    if (e.sex() != null && needed.contains("sex")) {
                        m.put("sex", e.sex());
                    }
                    m.put("scheduledAt", e.scheduledAt());
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** Secret reference bound to one of the endpoint's instances (vendor database credentials of the Device Host agent). */
    public Map<String, Object> secret(DeviceEndpointPrincipal p, String reference) {
        boolean bound = instances(p).stream().anyMatch(i -> i.parameters().containsValue(reference));
        if (!bound || !(reference.startsWith("devsec:") || reference.startsWith("vault:"))) {
            throw ApiException.notFound("Secret reference");
        }
        Optional<char[]> value = spis.secrets().resolve(reference, "VENDOR_DB_READ");
        char[] v = value.orElseThrow(() -> ApiException.notFound("Secret reference"));
        try {
            return Map.of("reference", reference, "value", new String(v));
        } finally {
            java.util.Arrays.fill(v, '\0');
        }
    }

    static String text(JsonNode n, String member) {
        return n.hasNonNull(member) ? n.get(member).asString() : null;
    }

}
