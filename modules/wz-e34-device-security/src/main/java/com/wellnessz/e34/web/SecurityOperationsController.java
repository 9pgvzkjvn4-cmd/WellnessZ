package com.wellnessz.e34.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e34.anomaly.AlertService;
import com.wellnessz.e34.anomaly.AnomalyRuleRepository;
import com.wellnessz.e34.events.SecurityEventService;
import com.wellnessz.e34.events.StoredSecurityEvent;
import com.wellnessz.e34.incident.IncidentService;
import com.wellnessz.e34.support.E34Settings;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUserProvider;

/**
 * Detection and response (contract §15.8 / §15.9): security events and chain verification, anomaly rules, alerts, incidents.
 */
@RestController
@RequestMapping("/api/v1/device-security")
public class SecurityOperationsController {

    public record RuleUpdate(@NotNull @Pattern(regexp = "INFO|LOW|MEDIUM|HIGH|CRITICAL") String severity, @NotNull Boolean enabled,
                             @NotNull @Min(1) @Max(100_000) Integer threshold, @NotNull @Min(1) @Max(1440) Integer windowMinutes,
                             @NotNull @Pattern(regexp = "ALERT|REJECT_ALERT|THROTTLE|ISOLATE|NOTIFY") String action,
                             @Min(1) @Max(100_000) Integer isolateThreshold) {
    }

    public record CloseRequest(@NotBlank @Size(max = 500) String resolution) {
    }

    public record IncidentRequest(@NotNull @Pattern(regexp = Spel.ENDPOINT_ID) String endpointId, @Size(max = 50) List<UUID> alertIds,
                                  @Size(max = 50) List<@Valid EvidenceRequest> evidence, @Size(max = 500) String summary) {
    }

    public record EvidenceRequest(@NotNull @Pattern(regexp = "RAW_MESSAGES|EVENTS|HEARTBEATS") String type,
                                  @NotNull @Pattern(regexp = "[0-9a-f]{64}") String sha256, @Size(max = 100) String archiveId,
                                  @Size(max = 300) String description) {
    }

    private final SecurityEventService events;
    private final AnomalyRuleRepository rules;
    private final AlertService alerts;
    private final IncidentService incidents;
    private final AuditService audit;
    private final E34Settings settings;
    private final CurrentUserProvider users;

    public SecurityOperationsController(SecurityEventService events, AnomalyRuleRepository rules, AlertService alerts, IncidentService incidents,
                                        AuditService audit, E34Settings settings, CurrentUserProvider users) {
        this.events = events;
        this.rules = rules;
        this.alerts = alerts;
        this.incidents = incidents;
        this.audit = audit;
        this.settings = settings;
        this.users = users;
    }

    // ---------- events (SECURITY_OPS, AUDITOR)

    @GetMapping("/events")
    @PreAuthorize(Spel.EVENTS_READ)
    public List<StoredSecurityEvent> events(@RequestParam(required = false) @Pattern(regexp = "[A-Z][A-Z0-9_]{2,59}") String type,
                                            @RequestParam(required = false) @Pattern(regexp = Spel.ENDPOINT_ID) String endpointId,
                                            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                            @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit) {
        settings.requireEnabled();
        List<StoredSecurityEvent> out = events.query(type, endpointId, from, to, limit);
        audit.record("DEVICE_SECURITY_EVENTS_READ", "DEVICE_SECURITY_EVENT", "query", AuditService.Outcome.SUCCESS, "returned=" + out.size());
        return out;
    }

    @GetMapping("/events/verify")
    @PreAuthorize(Spel.EVENTS_READ)
    public SecurityEventService.ChainVerification verify() {
        settings.requireEnabled();
        return events.verify();
    }

    // ---------- anomaly rules and alerts (SECURITY_OPS)

    @GetMapping("/anomaly-rules")
    @PreAuthorize(Spel.SECOPS)
    public List<AnomalyRuleRepository.AnomalyRule> rules() {
        settings.requireEnabled();
        return rules.all();
    }

    @PutMapping("/anomaly-rules/{code}")
    @PreAuthorize(Spel.SECOPS)
    public AnomalyRuleRepository.AnomalyRule updateRule(@PathVariable @Pattern(regexp = Spel.CODE) String code, @Valid @RequestBody RuleUpdate r) {
        settings.requireEnabled();
        if ("ISOLATE".equals(r.action()) && r.isolateThreshold() != null && r.isolateThreshold() < r.threshold()) {
            throw ApiException.badRequest("validation_failed", "The isolation threshold cannot be below the alert threshold");
        }
        if (rules.update(code, r.severity(), r.enabled(), r.threshold(), r.windowMinutes(), r.action(), r.isolateThreshold(),
                users.require().username()) == 0) {
            throw ApiException.notFound("Anomaly rule");
        }
        audit.record("DEVICE_ANOMALY_RULE_UPDATE", "DEVICE_ANOMALY_RULE", code, AuditService.Outcome.SUCCESS,
                r.action() + " threshold=" + r.threshold() + " enabled=" + r.enabled());
        return rules.get(code).orElseThrow();
    }

    @GetMapping("/alerts")
    @PreAuthorize(Spel.SECOPS)
    public List<AlertService.AlertView> alerts(@RequestParam(required = false) @Pattern(regexp = "OPEN|ACKNOWLEDGED|CLOSED") String status,
                                               @RequestParam(required = false) @Pattern(regexp = Spel.ENDPOINT_ID) String endpointId,
                                               @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit) {
        settings.requireEnabled();
        return alerts.list(status, endpointId, limit);
    }

    @PostMapping("/alerts/{id}/acknowledge")
    @PreAuthorize(Spel.SECOPS)
    public AlertService.AlertView acknowledge(@PathVariable UUID id) {
        return alerts.acknowledge(id, users.require().username());
    }

    @PostMapping("/alerts/{id}/close")
    @PreAuthorize(Spel.SECOPS)
    public AlertService.AlertView close(@PathVariable UUID id, @Valid @RequestBody CloseRequest r) {
        return alerts.close(id, users.require().username(), r.resolution());
    }

    // ---------- incidents (compromise playbook)

    @PostMapping("/incidents")
    @PreAuthorize(Spel.SECOPS)
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentService.IncidentView openIncident(@Valid @RequestBody IncidentRequest r) {
        return incidents.create(users.require().username(), r.endpointId(), r.alertIds(), r.evidence() == null ? List.of()
                : r.evidence().stream().map(e -> new IncidentService.EvidenceInput(e.type(), e.sha256(), e.archiveId(), e.description())).toList(),
                r.summary());
    }

    @GetMapping("/incidents")
    @PreAuthorize(Spel.SECOPS)
    public List<IncidentService.IncidentView> incidents(@RequestParam(required = false) @Pattern(regexp = "OPEN|CLOSED") String status,
                                                        @RequestParam(required = false) @Pattern(regexp = Spel.ENDPOINT_ID) String endpointId,
                                                        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        settings.requireEnabled();
        return incidents.list(status, endpointId, limit);
    }

    @GetMapping("/incidents/{ref}")
    @PreAuthorize(Spel.SECOPS)
    public IncidentService.IncidentView incident(@PathVariable @Pattern(regexp = "DSI-[0-9]{4}-[0-9]{6,}") String ref) {
        settings.requireEnabled();
        return incidents.get(ref);
    }

    @PostMapping("/incidents/{ref}/evidence")
    @PreAuthorize(Spel.SECOPS)
    public IncidentService.IncidentView addEvidence(@PathVariable @Pattern(regexp = "DSI-[0-9]{4}-[0-9]{6,}") String ref,
                                                    @Valid @RequestBody EvidenceRequest e) {
        return incidents.addEvidence(ref, users.require().username(), new IncidentService.EvidenceInput(e.type(), e.sha256(), e.archiveId(),
                e.description()));
    }

    @PostMapping("/incidents/{ref}/close")
    @PreAuthorize(Spel.SECOPS)
    public IncidentService.IncidentView closeIncident(@PathVariable @Pattern(regexp = "DSI-[0-9]{4}-[0-9]{6,}") String ref,
                                                      @Valid @RequestBody CloseRequest r) {
        return incidents.close(ref, users.require().username(), r.resolution());
    }
}
