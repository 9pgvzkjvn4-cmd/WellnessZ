package com.wellnessz.e32.fleet;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceDiagnostics;
import com.wellnessz.casekernel.device.DeviceEndpointProvisioning;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.events.DomainEvent;
import com.wellnessz.e32.calibration.CalibrationService;
import com.wellnessz.e32.catalogue.ModelStore;
import com.wellnessz.e32.config.Configuration;
import com.wellnessz.e32.config.ConfigurationStore;
import com.wellnessz.e32.driver.DriverStore;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.E32Support;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Fleet inventory, health and alerts (E32-HLR-04, E32-F05, contract §13.7): inventory by site / station with health from
 * {@link DeviceDiagnostics#health} and endpoint status from E34, site map counts, alerts (stale, failing, calibration due,
 * locked out, quarantined, driver incompatible / revoked) routed to the on-duty administrator with ITSM ticket hand-off
 * (outbox destination {@code itsm-tickets}), firmware / software versions, consumables and the on-duty rota. Counts only
 * for managers (workspace). No person data anywhere.
 */
@Service("e32FleetService")
public class FleetService {

    public static final Duration STALE_AFTER = Duration.ofDays(7);

    private final E32Support support;
    private final ConfigurationStore configurations;
    private final ModelStore models;
    private final DriverStore drivers;
    private final CalibrationService calibration;
    private final EventBus events;

    public FleetService(E32Support support, ConfigurationStore configurations, ModelStore models, DriverStore drivers, CalibrationService calibration,
                        EventBus events) {
        this.support = support;
        this.configurations = configurations;
        this.models = models;
        this.drivers = drivers;
        this.calibration = calibration;
        this.events = events;
    }

    /** ITSM ticket hand-off – flat, allow-listed fields only. */
    public record TicketEvent(String messageId, String ticketRef, String deviceInstanceId, String siteCode, String alertType, String severity,
                              String raisedAt) implements DomainEvent {
        @Override
        public String eventType() {
            return "device.fleet.ticket.v1";
        }

        @Override
        public String aggregateType() {
            return "DEVICE_ALERT";
        }

        @Override
        public String aggregateId() {
            return messageId;
        }
    }

    // ------------------------------------------------------------------ inventory

    @Transactional(readOnly = true)
    public List<Map<String, Object>> fleet(String siteCode, String family, String state) {
        support.requireEnabled();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Configuration c : configurations.liveAll()) {
            if (siteCode != null && !siteCode.equals(c.siteCode())) {
                continue;
            }
            ModelStore.ModelRow m = models.find(c.modelCode()).orElse(null);
            if (family != null && (m == null || !family.equals(m.family().name()))) {
                continue;
            }
            Map<String, Object> row = row(c, m);
            if (state != null && !state.equals(Docs.map(row.get("health")).get("state")) && !state.equals(row.get("status"))) {
                continue;
            }
            out.add(row);
        }
        return out;
    }

    Map<String, Object> row(Configuration c, ModelStore.ModelRow m) {
        Map<String, Object> health = health(c);
        Map<String, Object> cal = calibration.instanceStatus(c);
        List<Map<String, Object>> alerts = openAlerts(c.deviceInstanceId());
        return Docs.of("deviceInstanceId", c.deviceInstanceId(), "configurationId", c.configurationId(), "configVersion", c.version(),
                "modelCode", c.modelCode(), "model", m == null ? null : m.manufacturer() + " " + m.model(), "family", m == null ? null : m.family().name(),
                "siteCode", c.siteCode(), "stationCode", c.stationCode(), "status", c.status(), "health", health, "lastSeenAt", health.get("lastSeenAt"),
                "firmware", c.firmware(), "softwareVersion", c.softwareVersion(), "driverPackageId", c.driverPackageId(), "driverVersion", c.driverVersion(),
                "endpointId", c.endpointId(), "endpointStatus", endpointStatus(c), "calibration", cal.get("calibration"), "qc", cal.get("checks"),
                "lockedOut", cal.get("lockedOut"), "alerts", alerts);
    }

    private Map<String, Object> health(Configuration c) {
        if (Configuration.QUARANTINED.equals(c.status())) {
            return Docs.of("state", "ISOLATED");
        }
        try {
            DeviceDiagnostics.ConnectorHealth h = support.spis().diagnostics().health(c.deviceInstanceId());
            if (h == null) {
                return Docs.of("state", "UNKNOWN");
            }
            String state = h.state();
            if (h.lastSeenAt() != null && h.lastSeenAt().isBefore(support.now().minus(STALE_AFTER)) && !"ISOLATED".equals(state)) {
                state = "STALE";
            }
            return Docs.of("state", state, "lastSeenAt", h.lastSeenAt(), "messages24h", h.messages24h(), "errors24h", h.errors24h(),
                    "p95LatencyMillis", h.p95LatencyMillis(), "unmatchedOpen", h.unmatchedOpen(), "deadLetters", h.deadLetters());
        } catch (ApiException | IllegalStateException unavailable) {
            return Docs.of("state", "UNKNOWN", "reason", "engine diagnostics unavailable");
        }
    }

    private String endpointStatus(Configuration c) {
        if (c.endpointId() == null) {
            return null;
        }
        try {
            DeviceEndpointProvisioning.EndpointState s = support.spis().provisioning().state(c.endpointId());
            return s == null || s.status() == null ? "UNKNOWN" : s.status().name();
        } catch (ApiException unavailable) {
            return "UNKNOWN";
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> sites() {
        support.requireEnabled();
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (Configuration c : configurations.liveAll()) {
            int[] n = counts.computeIfAbsent(c.siteCode() == null ? "-" : c.siteCode(), k -> new int[7]);
            n[0]++;
            String state = Docs.str(health(c).get("state"));
            if ("HEALTHY".equals(state)) {
                n[1]++;
            } else if ("DEGRADED".equals(state)) {
                n[2]++;
            } else if ("SILENT".equals(state) || "STALE".equals(state)) {
                n[3]++;
            }
            if (Configuration.QUARANTINED.equals(c.status())) {
                n[4]++;
            }
            Map<String, Object> st = calibration.instanceStatus(c);
            boolean due = Docs.list(st.get("checks")).stream().map(Docs::map).anyMatch(x -> !"VALID".equals(x.get("verdict")));
            n[5] += due ? 1 : 0;
            n[6] += Docs.bool(st.get("lockedOut"), false) ? 1 : 0;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        counts.forEach((site, n) -> out.add(Docs.of("siteCode", site, "devices", n[0], "healthy", n[1], "degraded", n[2], "silent", n[3],
                "quarantined", n[4], "calibrationDue", n[5], "lockedOut", n[6])));
        return out;
    }

    /** Manager / nursing-head workspace: availability counts only (contract §13.7). */
    @Transactional(readOnly = true)
    public Map<String, Object> workspace(CurrentUser user) {
        support.requireEnabled();
        List<String> ownSites = List.of();
        if (user.hasRole("NURSING_HEAD") && !user.hasRole("MANAGER")) {
            List<String> rows = support.jdbc().queryForList("select site_codes from masterdata.staff_member where username = ?", String.class,
                    user.username());
            ownSites = rows.isEmpty() ? List.of() : Docs.csv(rows.get(0));
        }
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (Configuration c : configurations.liveAll()) {
            String site = c.siteCode() == null ? "-" : c.siteCode();
            if (!ownSites.isEmpty() && !ownSites.contains(site)) {
                continue;
            }
            int[] n = counts.computeIfAbsent(site, k -> new int[4]);
            n[0]++;
            boolean locked = Docs.bool(calibration.instanceStatus(c).get("lockedOut"), false);
            if (Configuration.PUBLISHED.equals(c.status()) && !locked) {
                n[1]++;
            }
            n[2] += locked ? 1 : 0;
            n[3] += openAlerts(c.deviceInstanceId()).size();
        }
        List<Map<String, Object>> sites = new ArrayList<>();
        counts.forEach((site, n) -> sites.add(Docs.of("siteCode", site, "devices", n[0], "available", n[1], "lockedOut", n[2], "alerts", n[3])));
        return Docs.of("sites", sites);
    }

    // ------------------------------------------------------------------ alerts

    private List<Map<String, Object>> openAlerts(String instanceId) {
        return support.jdbc().query("""
                select id, alert_type, level, raised_at, status from devicecat.fleet_alert where device_instance_id = ? and status <> 'CLOSED'
                order by raised_at desc""",
                (rs, i) -> Docs.of("alertId", rs.getObject("id", UUID.class).toString(), "type", rs.getString("alert_type"), "level", rs.getString("level"),
                        "raisedAt", rs.getTimestamp("raised_at").toInstant(), "status", rs.getString("status")), instanceId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> alerts(String status) {
        support.requireEnabled();
        return support.jdbc().query("""
                select id, device_instance_id, site_code, alert_type, level, detail, status, raised_at, acknowledged_by, acknowledged_at, ticket_ref
                from devicecat.fleet_alert order by raised_at desc limit 500""",
                (rs, i) -> Docs.of("alertId", rs.getObject("id", UUID.class).toString(), "deviceInstanceId", rs.getString("device_instance_id"),
                        "siteCode", rs.getString("site_code"), "type", rs.getString("alert_type"), "level", rs.getString("level"),
                        "detail", rs.getString("detail"), "status", rs.getString("status"), "raisedAt", rs.getTimestamp("raised_at").toInstant(),
                        "acknowledgedBy", rs.getString("acknowledged_by"),
                        "acknowledgedAt", rs.getTimestamp("acknowledged_at") == null ? null : rs.getTimestamp("acknowledged_at").toInstant(),
                        "ticketRef", rs.getString("ticket_ref")))
                .stream().filter(a -> status == null ? !"CLOSED".equals(a.get("status")) : status.equals(a.get("status"))).toList();
    }

    @Transactional
    public Map<String, Object> acknowledge(CurrentUser user, UUID id) {
        support.requireEnabled();
        if (support.jdbc().update("""
                update devicecat.fleet_alert set status = 'ACKNOWLEDGED', acknowledged_by = ?, acknowledged_at = ? where id = ? and status = 'OPEN'""",
                user.username(), Timestamp.from(support.now()), id) == 0) {
            throw ApiException.conflict("invalid_status", "The alert is not open");
        }
        return alert(id);
    }

    @Transactional
    public Map<String, Object> ticket(CurrentUser user, UUID id) {
        support.requireEnabled();
        Map<String, Object> a = alert(id);
        if (a.get("ticketRef") != null) {
            return a;
        }
        String ref = "ITSM-" + id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        events.publish(new TicketEvent(UUID.randomUUID().toString(), ref, Docs.str(a.get("deviceInstanceId")), Docs.str(a.get("siteCode")),
                Docs.str(a.get("type")), Docs.str(a.get("level")), String.valueOf(a.get("raisedAt"))), "itsm-tickets");
        support.jdbc().update("update devicecat.fleet_alert set ticket_ref = ? where id = ?", ref, id);
        support.audit("DEVICE_CONFIG_STEP", "DEVICE_ALERT", id.toString(), "ticket " + ref);
        return alert(id);
    }

    private Map<String, Object> alert(UUID id) {
        return alerts("OPEN").stream().filter(a -> id.toString().equals(a.get("alertId"))).findFirst()
                .or(() -> alerts("ACKNOWLEDGED").stream().filter(a -> id.toString().equals(a.get("alertId"))).findFirst())
                .or(() -> alerts("CLOSED").stream().filter(a -> id.toString().equals(a.get("alertId"))).findFirst())
                .orElseThrow(() -> ApiException.notFound("Alert"));
    }

    /** Raises an alert once (deduplicated while open) and notifies the on-duty administrator. */
    public boolean raise(Configuration c, String type, String level, String detail, String dedup) {
        int inserted = support.jdbc().update("""
                insert into devicecat.fleet_alert (id, device_instance_id, site_code, alert_type, level, detail, dedup_key, status, raised_at)
                values (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?) on conflict do nothing""",
                UUID.randomUUID(), c.deviceInstanceId(), c.siteCode(), type, level, detail, dedup, Timestamp.from(support.now()));
        if (inserted > 0) {
            Map<String, String> p = support.params(c.deviceInstanceId(), c.siteCode());
            p.put("level", level);
            support.notifyOnDuty(c.siteCode(), "device.connector-alert", p, "HIGH".equals(level) || "CRITICAL".equals(level));
        }
        return inserted > 0;
    }

    /** Hourly: stale (> 7 days unseen) and failing connectors. */
    @Transactional
    public int healthSweep() {
        int raised = 0;
        for (Configuration c : configurations.liveAll()) {
            if (!Configuration.PUBLISHED.equals(c.status())) {
                continue;
            }
            Map<String, Object> h = health(c);
            String state = Docs.str(h.get("state"));
            if ("STALE".equals(state) || "SILENT".equals(state)) {
                raised += raise(c, "STALE", "MEDIUM", "No data for more than 7 days", "STALE:" + c.deviceInstanceId()) ? 1 : 0;
            } else if ("DEGRADED".equals(state)) {
                raised += raise(c, "ERROR_RATE", "MEDIUM", "Connector degraded", "ERROR_RATE:" + c.deviceInstanceId()) ? 1 : 0;
            }
        }
        return raised;
    }

    // ------------------------------------------------------------------ versions, consumables, on duty

    public record VersionsCommand(String firmware, String softwareVersion) {
    }

    @Transactional
    public Map<String, Object> versions(CurrentUser user, String instanceId, VersionsCommand cmd) {
        support.requireEnabled();
        Configuration c = configurations.live(instanceId).orElseThrow(() -> ApiException.notFound("Device instance"));
        Map<String, Object> before = Docs.of("firmware", c.firmware(), "softwareVersion", c.softwareVersion());
        if (cmd.firmware() != null) {
            c.setFirmware(trim(cmd.firmware(), 60));
        }
        if (cmd.softwareVersion() != null) {
            c.setSoftwareVersion(trim(cmd.softwareVersion(), 100));
        }
        configurations.update(c, user.username());
        support.history("INSTANCE", instanceId, c.version(), user.username(), "VERSIONS", null,
                Docs.of("from", before, "to", Docs.of("firmware", c.firmware(), "softwareVersion", c.softwareVersion())), null);
        List<String> supported = drivers.find(c.driverPackageId(), c.driverVersion())
                .map(d -> Docs.strings(Docs.at(support.readMap(d.manifestJson()), "vendorSoftware.versions"))).orElse(List.of());
        boolean incompatible = c.softwareVersion() != null && !supported.isEmpty() && supported.stream().noneMatch(v -> c.softwareVersion().startsWith(v));
        if (incompatible) {
            raise(c, "DRIVER_INCOMPATIBLE", "HIGH", "Software " + c.softwareVersion() + " not supported by " + c.driverPackageId() + "@" + c.driverVersion(),
                    "DRIVER_INCOMPATIBLE:" + instanceId + ":" + c.softwareVersion());
        }
        return Docs.of("deviceInstanceId", instanceId, "firmware", c.firmware(), "softwareVersion", c.softwareVersion(), "compatible", !incompatible,
                "supportedSoftwareVersions", supported);
    }

    public record ConsumableCommand(String item, String lot, LocalDate expiry, Integer quantity) {
    }

    @Transactional
    public Map<String, Object> addConsumable(CurrentUser user, String instanceId, ConsumableCommand cmd) {
        support.requireEnabled();
        configurations.live(instanceId).orElseThrow(() -> ApiException.notFound("Device instance"));
        if (cmd.item() == null || cmd.item().isBlank() || cmd.quantity() == null || cmd.quantity() < 0) {
            throw ApiException.badRequest("validation_failed", "item and a non-negative quantity are required");
        }
        support.jdbc().update("""
                insert into devicecat.consumable (id, device_instance_id, item, lot, expiry, quantity, recorded_by, recorded_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), instanceId, trim(cmd.item(), 200), trim(cmd.lot(), 60),
                cmd.expiry() == null ? null : java.sql.Date.valueOf(cmd.expiry()), cmd.quantity(), user.username(), Timestamp.from(support.now()));
        return Docs.of("deviceInstanceId", instanceId, "items", consumables(instanceId));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> consumables(String instanceId) {
        LocalDate today = support.today();
        return support.jdbc().query("""
                select item, lot, expiry, quantity, recorded_by, recorded_at from devicecat.consumable where device_instance_id = ? order by recorded_at desc""",
                (rs, i) -> {
                    LocalDate exp = rs.getDate("expiry") == null ? null : rs.getDate("expiry").toLocalDate();
                    return Docs.of("item", rs.getString("item"), "lot", rs.getString("lot"), "expiry", exp, "quantity", rs.getInt("quantity"),
                            "expired", exp != null && exp.isBefore(today), "recordedBy", rs.getString("recorded_by"),
                            "recordedAt", rs.getTimestamp("recorded_at").toInstant());
                }, instanceId);
    }

    public record OnDutyEntry(String siteCode, String username, Instant from, Instant to) {
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> onDuty() {
        return support.jdbc().query("select site_code, username, from_at, to_at from devicecat.on_duty order by site_code, from_at",
                (rs, i) -> Docs.of("siteCode", rs.getString("site_code"), "username", rs.getString("username"),
                        "from", rs.getTimestamp("from_at").toInstant(), "to", rs.getTimestamp("to_at").toInstant()));
    }

    @Transactional
    public List<Map<String, Object>> putOnDuty(CurrentUser user, List<OnDutyEntry> entries) {
        support.requireEnabled();
        for (OnDutyEntry e : entries) {
            if (e.siteCode() == null || e.username() == null || e.from() == null || e.to() == null || !e.to().isAfter(e.from())) {
                throw ApiException.badRequest("validation_failed", "each entry needs siteCode, username and a from < to period");
            }
        }
        support.jdbc().update("delete from devicecat.on_duty");
        for (OnDutyEntry e : entries) {
            support.jdbc().update("insert into devicecat.on_duty (id, site_code, username, from_at, to_at, updated_by, updated_at) values (?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), trim(e.siteCode(), 40), trim(e.username(), 200), Timestamp.from(e.from()), Timestamp.from(e.to()), user.username(),
                    Timestamp.from(support.now()));
        }
        return onDuty();
    }

    private static String trim(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
