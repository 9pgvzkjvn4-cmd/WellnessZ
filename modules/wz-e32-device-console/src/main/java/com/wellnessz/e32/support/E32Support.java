package com.wellnessz.e32.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceSecurityEventPublisher;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.casekernel.device.DeviceTenantDirectory;
import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.notification.Channel;
import com.wellnessz.notification.Priority;
import com.wellnessz.notification.NotificationService;
import com.wellnessz.security.audit.AuditService;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared infrastructure of the console: JSON, clock, identifiers, audit, append-only change history, content-free
 * notifications and PHI-free security events. Nothing here touches person data.
 */
@Component("e32E32Support")
public class E32Support {

    public static final String MODULE = "E32";
    private static final Logger log = LoggerFactory.getLogger(E32Support.class);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Object>> LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final AuditService audit;
    private final NotificationService notifications;
    private final ObjectProvider<DeviceSpis> spis;
    private final ModuleRegistry modules;
    private final String environment;
    private final String ohPortal;
    private final String tenantResidency;
    private final List<String> cloudAllowedHosts;

    public E32Support(JdbcTemplate jdbc, JsonMapper json, Clock clock, AuditService audit, NotificationService notifications,
                      ObjectProvider<DeviceSpis> spis, ModuleRegistry modules,
                      @Value("${wellnessz.device.console.environment:dev}") String environment,
                      @Value("${wellnessz.notification.oh-workspace-url:https://oh.wellnessz.local}") String ohPortal,
                      @Value("${wellnessz.device.console.tenant-residency:UK}") String tenantResidency,
                      @Value("${device.cloud.allowed-hosts:}") List<String> cloudAllowedHosts) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.audit = audit;
        this.notifications = notifications;
        this.spis = spis;
        this.modules = modules;
        this.environment = environment;
        this.ohPortal = ohPortal;
        this.tenantResidency = tenantResidency;
        this.cloudAllowedHosts = List.copyOf(cloudAllowedHosts);
    }

    // ------------------------------------------------------------------ infrastructure accessors

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** Resolved lazily: DeviceSpis collects the console's own SPI beans (status listener), so a constructor reference would cycle. */
    public DeviceSpis spis() {
        return spis.getObject();
    }

    public Instant now() {
        return clock.instant();
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    public String environment() {
        return environment;
    }

    public String tenantResidency() {
        return tenantResidency;
    }

    /** Data residency of a tenant from the engine's tenant directory (E31), else the configured default. */
    public String tenantResidency(String tenantCode) {
        return spis().tenants().tenant(DeviceTenantDirectory.normalise(tenantCode)).map(DeviceTenantDirectory.Tenant::residencyRegion)
                .filter(r -> r != null && !r.isBlank()).orElse(tenantResidency);
    }

    /** A known tenant (E31 {@code /device-engine/tenants}); unknown codes → 400 {@code unknown_tenant}. */
    public DeviceTenantDirectory.Tenant tenant(String tenantCode) {
        String code = DeviceTenantDirectory.normalise(tenantCode);
        return spis().tenants().tenant(code).orElseThrow(() -> ApiException.badRequest("unknown_tenant", "Unknown tenant " + code));
    }

    /** Whether a catalogue row owned by {@code ownerTenant} ({@code null} = shared) is visible to {@code tenantCode}. */
    public boolean visible(String tenantCode, String ownerTenant) {
        String code = DeviceTenantDirectory.normalise(tenantCode);
        return spis().tenants().tenant(code).map(t -> t.sees(ownerTenant)).orElse(false);
    }

    public List<String> cloudAllowedHosts() {
        return cloudAllowedHosts;
    }

    public void requireEnabled() {
        modules.requireEnabled(MODULE);
    }

    public boolean enabled() {
        return modules.isEnabled(MODULE);
    }

    // ------------------------------------------------------------------ JSON

    public String write(Object o) {
        return json.writeValueAsString(o);
    }

    public Map<String, Object> readMap(String s) {
        if (s == null || s.isBlank()) {
            return new LinkedHashMap<>();
        }
        return json.readValue(s, MAP);
    }

    public List<Object> readList(String s) {
        if (s == null || s.isBlank()) {
            return new java.util.ArrayList<>();
        }
        return json.readValue(s, LIST);
    }

    /** Converts any request record / object into a plain JSON tree. */
    public Map<String, Object> toMap(Object o) {
        return o == null ? new LinkedHashMap<>() : readMap(write(o));
    }

    // ------------------------------------------------------------------ identifiers (constant SQL per sequence)

    public String nextConfigurationId() {
        return "CFG-%06d".formatted(seq(jdbc.queryForObject("select nextval('devicecat.configuration_seq')", Long.class)));
    }

    public String nextInstanceId() {
        return "DEV-%06d".formatted(seq(jdbc.queryForObject("select nextval('devicecat.instance_seq')", Long.class)));
    }

    public String nextCertificationId() {
        return "DCR-%d-%06d".formatted(Year.now(clock).getValue(),
                seq(jdbc.queryForObject("select nextval('devicecat.certification_seq')", Long.class)));
    }

    public String nextModelRequestRef() {
        return "DMR-%d-%06d".formatted(Year.now(clock).getValue(),
                seq(jdbc.queryForObject("select nextval('devicecat.model_request_seq')", Long.class)));
    }

    public String nextLocalModelCode() {
        return "WZ-%06d".formatted(seq(jdbc.queryForObject("select nextval('devicecat.local_model_seq')", Long.class)));
    }

    private static long seq(Long v) {
        if (v == null) {
            throw new IllegalStateException("sequence returned no value");
        }
        return v;
    }

    // ------------------------------------------------------------------ audit and history

    public void audit(String action, String resourceType, String resourceId) {
        audit.record(action, resourceType, resourceId, AuditService.Outcome.SUCCESS, null);
    }

    public void audit(String action, String resourceType, String resourceId, String reason) {
        audit.record(action, resourceType, resourceId, AuditService.Outcome.SUCCESS, reason);
    }

    public void auditDenied(String action, String resourceType, String resourceId, String reason) {
        audit.record(action, resourceType, resourceId, AuditService.Outcome.DENIED, reason);
    }

    /** Appends to the change history (diff carries references, never secret values or person data). */
    public void history(String entityType, String entityId, Integer version, String actor, String action, Integer step, Object diff,
                        String approvalRef) {
        jdbc.update("""
                insert into devicecat.change_history (id, entity_type, entity_id, version, actor, action, step, diff, approval_ref, at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), entityType, entityId, version, actor, action, step, diff == null ? null : write(diff), approvalRef,
                java.sql.Timestamp.from(now()));
    }

    // ------------------------------------------------------------------ security events (PHI-free)

    public void securityEvent(String type, DeviceCodes.Severity severity, String endpointId, String deviceInstanceId, String siteCode,
                              String actor, String reasonCode, Map<String, String> attributes) {
        try {
            spis().securityEvents().publish(new DeviceSecurityEventPublisher.DeviceSecurityEvent(type, severity, endpointId, deviceInstanceId,
                    siteCode, actor, MODULE, reasonCode, attributes, now()));
        } catch (RuntimeException e) {
            log.warn("Device security event {} could not be published: {}", type, e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ content-free notifications

    public Map<String, String> params(String reference, String site) {
        Map<String, String> p = new LinkedHashMap<>();
        if (reference != null) {
            p.put("reference", reference);
        }
        p.put("site", site == null ? "-" : site);
        return p;
    }

    /** Staff members holding a role (masterdata.staff_member) – used for the approver queue notification. */
    public void notifyRole(String role, String template, Map<String, String> params, String exceptUser) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select username, email, roles from masterdata.staff_member where active and email is not null");
        for (Map<String, Object> r : rows) {
            String username = Docs.str(r.get("username"));
            Set<String> roles = new LinkedHashSet<>(Docs.csv(Docs.str(r.get("roles"))));
            if (roles.contains(role) && !username.equals(exceptUser)) {
                send(username, Docs.str(r.get("email")), template, params, Priority.NORMAL);
            }
        }
    }

    public void notifyUser(String username, String template, Map<String, String> params) {
        if (username == null) {
            return;
        }
        List<String> emails = jdbc.queryForList(
                "select email from masterdata.staff_member where username = ? and active and email is not null", String.class, username);
        if (!emails.isEmpty()) {
            send(username, emails.get(0), template, params, Priority.NORMAL);
        }
    }

    /** On-duty administrator of a site (E32-F05 alert routing); falls back to every active DEVICE_ADMIN. */
    public void notifyOnDuty(String siteCode, String template, Map<String, String> params, boolean urgent) {
        java.sql.Timestamp ts = java.sql.Timestamp.from(now());
        List<String> users = jdbc.queryForList(
                "select username from devicecat.on_duty where (site_code = ? or site_code = '*') and from_at <= ? and to_at > ? order by from_at",
                String.class, siteCode == null ? "*" : siteCode, ts, ts);
        if (users.isEmpty()) {
            notifyRole("DEVICE_ADMIN", template, params, null);
            return;
        }
        for (String u : new LinkedHashSet<>(users)) {
            List<String> emails = jdbc.queryForList(
                    "select email from masterdata.staff_member where username = ? and active and email is not null", String.class, u);
            if (!emails.isEmpty()) {
                send(u, emails.get(0), template, params, urgent ? Priority.URGENT : Priority.NORMAL);
            }
        }
    }

    private void send(String recipient, String address, String template, Map<String, String> params, Priority priority) {
        Map<String, String> p = new LinkedHashMap<>(params);
        p.putIfAbsent("portalLink", ohPortal + "/#/devices");
        try {
            notifications.enqueue(recipient, address, Channel.EMAIL, priority, template, p, ZoneOffset.UTC);
        } catch (RuntimeException e) {
            log.warn("Notification {} not queued: {}", template, e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ errors

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
}
