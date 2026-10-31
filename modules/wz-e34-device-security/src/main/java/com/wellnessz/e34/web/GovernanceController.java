package com.wellnessz.e34.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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

import com.wellnessz.e34.governance.GovernanceService;
import com.wellnessz.security.identity.CurrentUserProvider;

/**
 * Governance (contract §15.7, §15.10–15.12): secure life-cycle, penetration test, IEC 80001-1 agreement, DPIA, compensating
 * controls, test-pack runs, access report and go-live readiness. SECURITY_OPS maintains, AUDITOR reads, the DPO approves the DPIA.
 */
@RestController
@RequestMapping("/api/v1/device-security")
public class GovernanceController {

    public record ThreatModelRequest(@NotBlank @Size(max = 60) String adapterOrOption, @NotBlank @Size(max = 30) String version,
                                     @NotNull LocalDate reviewedOn, @Size(max = 200) String documentRef) {
    }

    public record SbomRequest(@NotBlank @Size(max = 40) String release, @NotBlank @Size(max = 200) String documentId,
                              @NotNull @Pattern(regexp = "CycloneDX|SPDX") String format, @Pattern(regexp = "[0-9a-f]{64}") String sha256,
                              LocalDate generatedOn) {
    }

    public record VulnerabilityRequest(@NotNull @Pattern(regexp = "[A-Za-z0-9._:\\-]{1,60}") String id, @NotBlank @Size(max = 200) String component,
                                       @NotNull @Pattern(regexp = "CRITICAL|HIGH|MEDIUM|LOW") String severity, LocalDate foundOn,
                                       @Size(max = 500) String note) {
    }

    public record StatusUpdate(@NotNull @Pattern(regexp = "OPEN|FIXED|ACCEPTED") String status, @Size(max = 500) String note) {
    }

    public record FindingRequest(@NotNull @Pattern(regexp = "[A-Za-z0-9._:\\-]{1,40}") String ref,
                                 @NotNull @Pattern(regexp = "CRITICAL|HIGH|MEDIUM|LOW|INFO") String severity, @NotBlank @Size(max = 300) String title,
                                 @Pattern(regexp = "OPEN|FIXED|ACCEPTED") String status, @Size(max = 100) String engagement, LocalDate foundOn) {
    }

    public record AgreementRequest(@NotBlank @Size(max = 30) String version, @NotEmpty @Size(max = 20) List<GovernanceService.Party> parties,
                                   LocalDate signedOn, @NotBlank @Size(max = 30) String reviewCadence) {
    }

    public record ControlRequest(@NotNull @Pattern(regexp = "[A-Z0-9\\-]{2,40}") String code, @NotBlank @Size(max = 200) String title,
                                 @NotBlank @Size(max = 1000) String description, @NotBlank @Size(max = 200) String appliesTo,
                                 @NotBlank @Size(max = 200) String owner, @NotNull LocalDate reviewOn) {
    }

    public record RunRequest(@NotBlank @Size(max = 30) String version, @NotNull @Pattern(regexp = "[A-Za-z0-9_\\-]{1,30}") String environment,
                             @NotEmpty @Size(max = 200) List<GovernanceService.TestResult> results) {
    }

    private final GovernanceService governance;
    private final CurrentUserProvider users;

    public GovernanceController(GovernanceService governance, CurrentUserProvider users) {
        this.governance = governance;
        this.users = users;
    }

    // ---------- secure life-cycle

    @GetMapping("/sdlc")
    @PreAuthorize(Spel.EVENTS_READ)
    public GovernanceService.Sdlc sdlc() {
        return governance.sdlc();
    }

    @PostMapping("/sdlc/threat-models")
    @PreAuthorize(Spel.SECOPS)
    @ResponseStatus(HttpStatus.CREATED)
    public GovernanceService.ThreatModel threatModel(@Valid @RequestBody ThreatModelRequest r) {
        return governance.addThreatModel(users.require().username(), r.adapterOrOption(), r.version(), r.reviewedOn(), r.documentRef());
    }

    @PutMapping("/sdlc/sbom")
    @PreAuthorize(Spel.SECOPS)
    public GovernanceService.Sbom sbom(@Valid @RequestBody SbomRequest r) {
        return governance.recordSbom(users.require().username(), r.release(), r.documentId(), r.format(), r.sha256(), r.generatedOn());
    }

    @PostMapping("/sdlc/vulnerabilities")
    @PreAuthorize(Spel.SECOPS)
    @ResponseStatus(HttpStatus.CREATED)
    public GovernanceService.Vulnerability vulnerability(@Valid @RequestBody VulnerabilityRequest r) {
        return governance.recordVulnerability(users.require().username(), r.id(), r.component(), r.severity(), r.foundOn(), r.note());
    }

    @PutMapping("/sdlc/vulnerabilities/{id}")
    @PreAuthorize(Spel.SECOPS)
    public GovernanceService.Vulnerability updateVulnerability(@PathVariable @Pattern(regexp = "[A-Za-z0-9._:\\-]{1,60}") String id,
                                                               @Valid @RequestBody StatusUpdate r) {
        return governance.updateVulnerability(users.require().username(), id, r.status(), r.note());
    }

    // ---------- penetration test and IEC 80001-1

    @GetMapping("/pentest/findings")
    @PreAuthorize(Spel.EVENTS_READ)
    public List<GovernanceService.PentestFinding> findings() {
        return governance.findings();
    }

    @PostMapping("/pentest/findings")
    @PreAuthorize(Spel.SECOPS)
    @ResponseStatus(HttpStatus.CREATED)
    public GovernanceService.PentestFinding finding(@Valid @RequestBody FindingRequest r) {
        return governance.recordFinding(users.require().username(), r.ref(), r.severity(), r.title(), r.status(), r.engagement(), r.foundOn());
    }

    @GetMapping("/responsibility-agreement")
    @PreAuthorize(Spel.EVENTS_READ)
    public GovernanceService.Agreement agreement() {
        return governance.agreement();
    }

    @PutMapping("/responsibility-agreement")
    @PreAuthorize(Spel.SECOPS)
    public GovernanceService.Agreement updateAgreement(@Valid @RequestBody AgreementRequest r) {
        return governance.updateAgreement(users.require().username(), r.version(), r.parties(), r.signedOn(), r.reviewCadence());
    }

    @GetMapping("/go-live-readiness")
    @PreAuthorize(Spel.EVENTS_READ)
    public GovernanceService.Readiness readiness() {
        return governance.goLiveReadiness();
    }

    // ---------- privacy (DPIA)

    @GetMapping("/privacy/dpia")
    @PreAuthorize(Spel.DPIA_READ)
    public GovernanceService.Dpia dpia() {
        return governance.dpia();
    }

    @PostMapping("/privacy/dpia/approve")
    @PreAuthorize(Spel.DPO)
    public GovernanceService.Dpia approveDpia() {
        return governance.approveDpia(users.require().username());
    }

    // ---------- vendor posture: compensating controls

    @GetMapping("/compensating-controls")
    @PreAuthorize(Spel.SECOPS)
    public List<GovernanceService.CompensatingControl> controls() {
        return governance.controls();
    }

    @PostMapping("/compensating-controls")
    @PreAuthorize(Spel.SECOPS)
    @ResponseStatus(HttpStatus.CREATED)
    public GovernanceService.CompensatingControl addControl(@Valid @RequestBody ControlRequest r) {
        return governance.addControl(users.require().username(), r.code(), r.title(), r.description(), r.appliesTo(), r.owner(), r.reviewOn());
    }

    // ---------- security test pack runs

    @PostMapping("/test-pack/runs")
    @PreAuthorize(Spel.SECOPS)
    @ResponseStatus(HttpStatus.CREATED)
    public GovernanceService.TestRun recordRun(@Valid @RequestBody RunRequest r) {
        return governance.recordRun(users.require().username(), r.version(), r.environment(), r.results());
    }

    @GetMapping("/test-pack/runs")
    @PreAuthorize(Spel.EVENTS_READ)
    public List<GovernanceService.TestRun> runs(@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return governance.runs(limit);
    }

    // ---------- access report

    @GetMapping("/reports/access")
    @PreAuthorize(Spel.EVENTS_READ)
    public List<GovernanceService.AccessRow> access(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return governance.accessReport(from, to);
    }
}
