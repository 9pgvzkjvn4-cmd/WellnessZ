package com.wellnessz.e01.reconciliation;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.casekernel.model.CaseViews;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUserProvider;

@RestController
@RequestMapping("/api/v1/intake")
public class IntakeOpsController {

    static final String OH = "hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN')";
    static final String OH_OR_HSE = "hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN','HSE_OFFICER')";

    private final ReconciliationService service;
    private final CurrentUserProvider users;
    private final AuditService audit;

    public IntakeOpsController(ReconciliationService service, CurrentUserProvider users, AuditService audit) {
        this.service = service;
        this.users = users;
        this.audit = audit;
    }

    public record AcceptRequest(@Pattern(regexp = "[A-Za-z0-9\\-]{1,64}") String employeeId) {
    }

    public record DismissRequest(@NotBlank String reasonCode, @Size(max = 500) String note) {
    }

    @GetMapping("/suggestions")
    @PreAuthorize(OH)
    public List<ReconciliationService.Row> suggestions() {
        return service.suggestions();
    }

    @PostMapping("/suggestions/{incidentId}/accept")
    @PreAuthorize(OH)
    public ReconciliationService.Accepted accept(@PathVariable UUID incidentId, @Valid @RequestBody(required = false) AcceptRequest r) {
        return service.accept(users.require(), incidentId, r == null ? null : r.employeeId());
    }

    @PostMapping("/suggestions/{incidentId}/dismiss")
    @PreAuthorize(OH)
    public ReconciliationService.Row dismiss(@PathVariable UUID incidentId, @Valid @RequestBody DismissRequest r) {
        return service.dismiss(users.require(), incidentId, r.reasonCode(), r.note());
    }

    @GetMapping("/reconciliation")
    @PreAuthorize(OH_OR_HSE)
    public List<ReconciliationService.Row> reconciliation(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Pattern(regexp = "[A-Z_]{1,20}") String outcome,
            @RequestParam(required = false) @Pattern(regexp = "[A-Z0-9]{1,20}") String unit) {
        return service.reconciliation(from, to, outcome, unit);
    }

    @GetMapping(value = "/reconciliation/export", produces = "text/csv")
    @PreAuthorize(OH_OR_HSE)
    public ResponseEntity<String> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        audit.record("INTAKE_RECONCILIATION_EXPORT", "EHS_INCIDENT", null, AuditService.Outcome.SUCCESS, null);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"incident-reconciliation.csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.toCsv(service.reconciliation(from, to, null, null)));
    }

    @GetMapping("/reconciliation/exceptions")
    @PreAuthorize("hasAnyRole('HSE_OFFICER','NURSING_HEAD','OH_ADMIN','CMO')")
    public List<ReconciliationService.Row> exceptions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.exceptions(from, to);
    }

    @GetMapping("/incidents/{incident}/cases")
    @PreAuthorize(OH_OR_HSE)
    public List<CaseViews.StatusOnly> casesOfIncident(@PathVariable @Pattern(regexp = "[A-Za-z0-9._/\\-]{1,100}") String incident) {
        audit.record("INCIDENT_CASES_READ", "EHS_INCIDENT", incident, AuditService.Outcome.SUCCESS, null);
        return service.casesOfIncident(incident);
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('NURSING_HEAD','OH_ADMIN','CMO')")
    public ReconciliationService.Dashboard dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Pattern(regexp = "[A-Z0-9]{1,20}") String subsidiary) {
        return service.dashboard(from, to, subsidiary);
    }
}
