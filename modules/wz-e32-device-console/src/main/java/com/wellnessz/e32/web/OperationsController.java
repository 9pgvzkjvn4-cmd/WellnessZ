package com.wellnessz.e32.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import com.wellnessz.e32.calibration.CalibrationService;
import com.wellnessz.e32.diagnostics.DiagnosticsService;
import com.wellnessz.e32.fleet.FleetService;
import com.wellnessz.e32.governance.GovernanceService;
import com.wellnessz.e32.request.ModelRequestService;
import com.wellnessz.security.identity.CurrentUserProvider;

/**
 * E32 operations: calibration / QC / lockout (§13.6), fleet and alerts (§13.7), governance and promotion (§13.4),
 * request-a-model (§13.9), diagnostics (§13.10) and the manager workspace counts.
 */
@RestController("e32OperationsController")
@RequestMapping("/api/v1")
public class OperationsController {

    private final CalibrationService calibration;
    private final FleetService fleet;
    private final GovernanceService governance;
    private final ModelRequestService requests;
    private final DiagnosticsService diagnostics;
    private final CurrentUserProvider users;

    public OperationsController(CalibrationService calibration, FleetService fleet, GovernanceService governance, ModelRequestService requests,
                                DiagnosticsService diagnostics, CurrentUserProvider users) {
        this.calibration = calibration;
        this.fleet = fleet;
        this.governance = governance;
        this.requests = requests;
        this.diagnostics = diagnostics;
        this.users = users;
    }

    // ------------------------------------------------------------------ calibration, QC and lockout

    @GetMapping("/device-calibration/schedules")
    @PreAuthorize(Spel.CALIBRATION_READ)
    public List<Map<String, Object>> schedules() {
        return calibration.schedules().stream().map(CalibrationService::scheduleView).toList();
    }

    @PutMapping("/device-calibration/schedules/{family}/{checkType}")
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> putSchedule(@PathVariable @Pattern(regexp = Spel.CODE) String family, @PathVariable @Pattern(regexp = Spel.CODE) String checkType,
                                           @RequestBody CalibrationService.ScheduleCommand cmd) {
        return calibration.putSchedule(users.require(), family, checkType, cmd);
    }

    @PostMapping("/device-calibration/checks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CALIBRATION_CHECK)
    public Map<String, Object> check(@RequestBody CalibrationService.CheckCommand cmd) {
        return calibration.recordCheck(users.require(), cmd);
    }

    @GetMapping("/device-calibration/status")
    @PreAuthorize(Spel.CALIBRATION_READ)
    public List<Map<String, Object>> status(@RequestParam(required = false) @Pattern(regexp = Spel.SITE) String siteCode,
                                            @RequestParam(required = false) @Pattern(regexp = "[A-Z0-9\\-]{2,60}") String stationCode) {
        return calibration.status(siteCode, stationCode);
    }

    @GetMapping("/device-calibration/instances/{id}/history")
    @PreAuthorize(Spel.CALIBRATION_READ)
    public Map<String, Object> history(@PathVariable @Pattern(regexp = Spel.INSTANCE_ID) String id) {
        return calibration.history(id);
    }

    @PostMapping("/device-calibration/instances/{id}/release")
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> release(@PathVariable @Pattern(regexp = Spel.INSTANCE_ID) String id) {
        return calibration.release(users.require(), id);
    }

    @GetMapping("/device-calibration/report")
    @PreAuthorize(Spel.CALIBRATION_REPORT)
    public ResponseEntity<?> report(@RequestParam(required = false) @Pattern(regexp = Spel.SITE) String siteCode,
                                    @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                    @RequestParam(required = false) @Pattern(regexp = "json|csv|pdf") String format) {
        CalibrationService.Report r = calibration.report(siteCode, from, to, format);
        if (r.body() == null) {
            return ResponseEntity.ok(r.json());
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(r.contentType() + ("text/csv".equals(r.contentType()) ? ";charset=UTF-8" : "")))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(r.body());
    }

    @GetMapping("/device-calibration/migration-report")
    @PreAuthorize(Spel.MIGRATION)
    public Map<String, Object> migrationReport() {
        return calibration.migrationReport();
    }

    @PostMapping("/device-calibration/migration")
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> migrate() {
        return governance.migrateRegister(users.require());
    }

    // ------------------------------------------------------------------ fleet

    @GetMapping("/device-fleet")
    @PreAuthorize(Spel.FLEET)
    public List<Map<String, Object>> fleet(@RequestParam(required = false) @Pattern(regexp = Spel.SITE) String siteCode,
                                           @RequestParam(required = false) @Pattern(regexp = Spel.CODE) String family,
                                           @RequestParam(required = false) @Pattern(regexp = Spel.CODE) String state) {
        return fleet.fleet(siteCode, family, state);
    }

    @GetMapping("/device-fleet/sites")
    @PreAuthorize(Spel.FLEET)
    public List<Map<String, Object>> sites() {
        return fleet.sites();
    }

    @GetMapping("/device-fleet/alerts")
    @PreAuthorize(Spel.FLEET)
    public List<Map<String, Object>> alerts(@RequestParam(required = false) @Pattern(regexp = Spel.CODE) String status) {
        return fleet.alerts(status);
    }

    @PostMapping("/device-fleet/alerts/{id}/acknowledge")
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> acknowledge(@PathVariable UUID id) {
        return fleet.acknowledge(users.require(), id);
    }

    @PostMapping("/device-fleet/alerts/{id}/ticket")
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> ticket(@PathVariable UUID id) {
        return fleet.ticket(users.require(), id);
    }

    @PutMapping("/device-fleet/instances/{id}/versions")
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> versions(@PathVariable @Pattern(regexp = Spel.INSTANCE_ID) String id, @RequestBody FleetService.VersionsCommand cmd) {
        return fleet.versions(users.require(), id, cmd);
    }

    @GetMapping("/device-fleet/instances/{id}/consumables")
    @PreAuthorize(Spel.CONSUMABLES)
    public List<Map<String, Object>> consumables(@PathVariable @Pattern(regexp = Spel.INSTANCE_ID) String id) {
        return fleet.consumables(id);
    }

    @PostMapping("/device-fleet/instances/{id}/consumables")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CONSUMABLES)
    public Map<String, Object> addConsumable(@PathVariable @Pattern(regexp = Spel.INSTANCE_ID) String id, @RequestBody FleetService.ConsumableCommand cmd) {
        return fleet.addConsumable(users.require(), id, cmd);
    }

    @GetMapping("/device-fleet/on-duty")
    @PreAuthorize(Spel.ADMIN)
    public List<Map<String, Object>> onDuty() {
        return fleet.onDuty();
    }

    @PutMapping("/device-fleet/on-duty")
    @PreAuthorize(Spel.ADMIN)
    public List<Map<String, Object>> putOnDuty(@RequestBody List<FleetService.OnDutyEntry> entries) {
        return fleet.putOnDuty(users.require(), entries);
    }

    @GetMapping("/workspace/device-fleet")
    @PreAuthorize(Spel.WORKSPACE)
    public Map<String, Object> workspace() {
        return fleet.workspace(users.require());
    }

    // ------------------------------------------------------------------ governance

    @GetMapping("/device-governance/approvals")
    @PreAuthorize(Spel.APPROVER)
    public List<Map<String, Object>> approvals(@RequestParam(required = false) @Pattern(regexp = Spel.CODE) String status) {
        return governance.approvals(status);
    }

    public record DecisionCommand(String note) {
    }

    @PostMapping("/device-governance/approvals/{id}/approve")
    @PreAuthorize(Spel.APPROVE)
    public Map<String, Object> approve(@PathVariable UUID id, @RequestBody(required = false) DecisionCommand cmd) {
        return governance.decide(users.require(), id, true, cmd == null ? null : cmd.note());
    }

    @PostMapping("/device-governance/approvals/{id}/reject")
    @PreAuthorize(Spel.APPROVER)
    public Map<String, Object> reject(@PathVariable UUID id, @RequestBody(required = false) DecisionCommand cmd) {
        return governance.decide(users.require(), id, false, cmd == null ? null : cmd.note());
    }

    @GetMapping("/device-governance/changes")
    @PreAuthorize(Spel.CHANGES)
    public List<Map<String, Object>> changes(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                             @RequestParam(required = false) @Pattern(regexp = Spel.CONFIG_ID) String configurationId) {
        return governance.changes(from, to, configurationId);
    }

    @GetMapping("/device-governance/export")
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> export(@RequestParam @Pattern(regexp = "CFG-[0-9]{6}(,CFG-[0-9]{6}){0,199}") String configurationIds) {
        return governance.export(users.require(), Arrays.asList(configurationIds.split(",")));
    }

    @PostMapping("/device-governance/imports")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> importExport(@RequestBody Map<String, Object> export) {
        return governance.importExport(users.require(), export);
    }

    @GetMapping("/device-governance/imports/{id}")
    @PreAuthorize(Spel.CHANGES)
    public Map<String, Object> importView(@PathVariable UUID id) {
        return governance.importView(id);
    }

    @PostMapping("/device-governance/imports/{id}/approve")
    @PreAuthorize(Spel.APPROVE)
    public Map<String, Object> approveImport(@PathVariable UUID id) {
        return governance.approveImport(users.require(), id);
    }

    @PostMapping("/device-governance/imports/{id}/reject")
    @PreAuthorize(Spel.DRIVER_CERTIFY)
    public Map<String, Object> rejectImport(@PathVariable UUID id) {
        return governance.rejectImport(users.require(), id);
    }

    @GetMapping("/device-governance/templates")
    @PreAuthorize(Spel.CHANGES)
    public List<Map<String, Object>> templates() {
        return governance.templates();
    }

    @GetMapping("/device-governance/templates/{id}")
    @PreAuthorize(Spel.CHANGES)
    public Map<String, Object> template(@PathVariable UUID id) {
        return governance.template(id);
    }

    @PostMapping("/device-governance/templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> createTemplate(@RequestBody GovernanceService.TemplateCommand cmd) {
        return governance.saveTemplate(users.require(), null, cmd);
    }

    @PutMapping("/device-governance/templates/{id}")
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> updateTemplate(@PathVariable UUID id, @RequestBody GovernanceService.TemplateCommand cmd) {
        return governance.saveTemplate(users.require(), id, cmd);
    }

    @PostMapping("/device-governance/templates/{id}/batch")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> batch(@PathVariable UUID id, @RequestBody GovernanceService.BatchCommand cmd) {
        return governance.batch(users.require(), id, cmd);
    }

    @GetMapping("/device-governance/batches/{id}")
    @PreAuthorize(Spel.CHANGES)
    public Map<String, Object> batchReport(@PathVariable UUID id) {
        return governance.batchReport(id);
    }

    // ------------------------------------------------------------------ request-a-new-model

    @PostMapping("/device-model-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> request(@RequestBody ModelRequestService.RequestCommand cmd) {
        return requests.submit(users.require(), cmd);
    }

    @GetMapping("/device-model-requests")
    @PreAuthorize(Spel.DEVICE_ROLES)
    public List<Map<String, Object>> requests(@RequestParam(required = false) @Pattern(regexp = Spel.CODE) String status) {
        return requests.list(status);
    }

    @GetMapping("/device-model-requests/kpi")
    @PreAuthorize(Spel.DEVICE_ROLES)
    public Map<String, Object> kpi() {
        return requests.kpi();
    }

    @GetMapping("/device-model-requests/{id}")
    @PreAuthorize(Spel.DEVICE_ROLES)
    public Map<String, Object> modelRequest(@PathVariable @Pattern(regexp = "[A-Za-z0-9\\-]{8,40}") String id) {
        return requests.get(id);
    }

    @PutMapping("/device-model-requests/{id}/scoping")
    @PreAuthorize(Spel.TESTER)
    public Map<String, Object> scoping(@PathVariable @Pattern(regexp = "[A-Za-z0-9\\-]{8,40}") String id, @RequestBody ModelRequestService.ScopingCommand cmd) {
        return requests.scope(users.require(), id, cmd);
    }

    // ------------------------------------------------------------------ diagnostics (masked)

    @GetMapping("/device-diagnostics/{id}/trace")
    @PreAuthorize(Spel.DIAGNOSTICS)
    public Map<String, Object> trace(@PathVariable @Pattern(regexp = Spel.INSTANCE_ID) String id,
                                     @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return diagnostics.trace(users.require(), id, limit);
    }

    @GetMapping("/device-diagnostics/{id}/health")
    @PreAuthorize(Spel.DIAGNOSTICS)
    public Map<String, Object> health(@PathVariable @Pattern(regexp = Spel.INSTANCE_ID) String id) {
        return diagnostics.health(id);
    }

    @PostMapping("/device-diagnostics/{id}/resend")
    @PreAuthorize(Spel.ADMIN)
    public Map<String, Object> resend(@PathVariable @Pattern(regexp = Spel.INSTANCE_ID) String id) {
        return diagnostics.resend(users.require(), id);
    }
}
