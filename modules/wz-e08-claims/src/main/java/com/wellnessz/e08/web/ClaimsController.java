package com.wellnessz.e08.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
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

import com.wellnessz.e08.claim.ClaimService;
import com.wellnessz.e08.claim.ClaimViews;
import com.wellnessz.e08.claim.NurseAssignmentService;
import com.wellnessz.e08.cost.CostService;
import com.wellnessz.e08.support.Money;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E08-F01 claim record & lifecycle, the case header badge, the HR status view and the nurse-assignment KPI (E08-F04). */
@RestController("e08ClaimsController")
@RequestMapping("/api/v1")
public class ClaimsController {

    private final ClaimService claims;
    private final NurseAssignmentService nurses;
    private final CostService costs;
    private final CurrentUserProvider users;

    public ClaimsController(ClaimService claims, NurseAssignmentService nurses, CostService costs, CurrentUserProvider users) {
        this.claims = claims;
        this.nurses = nurses;
        this.costs = costs;
        this.users = users;
    }

    public record CreateBody(@NotBlank @Pattern(regexp = Patterns.CLAIM_TYPE) String claimType, @NotBlank @Pattern(regexp = Patterns.CODE) String payerCode,
                             @Pattern(regexp = "|" + Patterns.POLICY) String policyNumber, @Size(max = 200) String adjusterName,
                             @Pattern(regexp = "|" + Patterns.EMAIL) String adjusterEmail, @Pattern(regexp = "|" + Patterns.USERNAME) String adjusterUsername,
                             boolean lostTime, LocalDate incidentDate, @Size(max = 4000) String notes) {
    }

    @PostMapping("/cases/{caseId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR','CASE_MANAGER')")
    public ClaimViews.ClaimView create(@PathVariable UUID caseId, @Valid @RequestBody CreateBody b) {
        return claims.create(users.require(), caseId, new ClaimService.CreateCommand(b.claimType(), b.payerCode(), b.policyNumber(), b.adjusterName(),
                b.adjusterEmail(), b.adjusterUsername(), b.lostTime(), b.incidentDate(), b.notes()));
    }

    @GetMapping("/claims")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR','NURSING_HEAD','LEGAL_COUNSEL','CMO','CASE_MANAGER')")
    public List<ClaimViews.ClaimView> list(@RequestParam(required = false) @Pattern(regexp = Patterns.CLAIM_STATUS) String status,
                                           @RequestParam(required = false) @Pattern(regexp = Patterns.CLAIM_TYPE) String type,
                                           @RequestParam(required = false) Boolean lostTime,
                                           @RequestParam(required = false) @Pattern(regexp = Patterns.CODE) String payerCode,
                                           @RequestParam(required = false) @Size(max = 100) String q) {
        return claims.list(users.require(), status, type, lostTime, payerCode, q);
    }

    @GetMapping("/claims/{id}")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR','LEGAL_COUNSEL'," + Patterns.OH + ")")
    public ClaimViews.ClaimDetail get(@PathVariable UUID id) {
        return claims.get(users.require(), id);
    }

    public record UpdateBody(@Pattern(regexp = Patterns.CODE) String payerCode, @Pattern(regexp = "|" + Patterns.POLICY) String policyNumber,
                             @Size(max = 200) String adjusterName, @Pattern(regexp = "|" + Patterns.EMAIL) String adjusterEmail,
                             @Pattern(regexp = "|" + Patterns.USERNAME) String adjusterUsername, Boolean lostTime,
                             @Size(max = 6) Map<@Pattern(regexp = Patterns.CATEGORY) String, Money> reserves, Money paidTotal) {
    }

    @PutMapping("/claims/{id}")
    @PreAuthorize("hasRole('CLAIMS_COORDINATOR')")
    public ClaimViews.ClaimView update(@PathVariable UUID id, @Valid @RequestBody UpdateBody b) {
        return claims.update(users.require(), id, new ClaimService.UpdateCommand(b.payerCode(), b.policyNumber(), b.adjusterName(), b.adjusterEmail(),
                b.adjusterUsername(), b.lostTime(), b.reserves(), b.paidTotal()));
    }

    public record StatusBody(@NotBlank @Pattern(regexp = Patterns.CLAIM_STATUS) String status, @Size(max = 1000) String reason) {
    }

    @PostMapping("/claims/{id}/status")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR','LEGAL_COUNSEL')")
    public ClaimViews.ClaimView status(@PathVariable UUID id, @Valid @RequestBody StatusBody b) {
        return claims.transition(users.require(), id, b.status(), b.reason());
    }

    @GetMapping("/cases/{caseId}/claim-status")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR'," + Patterns.OH + ")")
    public ResponseEntity<ClaimViews.CaseClaimStatus> caseStatus(@PathVariable UUID caseId) {
        return claims.caseStatus(users.require(), caseId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    public record SubrogationBody(@NotBlank @Size(max = 200) String thirdPartyName,
                                  @NotBlank @Pattern(regexp = "CONTRACTOR|VEHICLE_OWNER|OTHER") String thirdPartyType,
                                  @Size(max = 200) String thirdPartyInsurer, @Size(max = 100) String reference,
                                  @NotBlank @Size(max = 2000) String description) {
    }

    @PostMapping("/claims/{id}/subrogation")
    @PreAuthorize("hasRole('CLAIMS_COORDINATOR')")
    public ClaimViews.ClaimView subrogation(@PathVariable UUID id, @Valid @RequestBody SubrogationBody b) {
        return claims.subrogation(users.require(), id, new ClaimService.SubrogationCommand(b.thirdPartyName(), b.thirdPartyType(),
                b.thirdPartyInsurer(), b.reference(), b.description()));
    }

    @GetMapping("/claims/reconciliation")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR','FINANCE')")
    public List<CostService.ReconciliationRow> reconciliation(@RequestParam(required = false) @Pattern(regexp = Patterns.MONTH) String month) {
        return costs.reconciliation(month);
    }

    @GetMapping("/workspace/claims")
    @PreAuthorize("hasRole('HR')")
    public List<ClaimViews.WorkspaceClaim> workspace() {
        return claims.workspace(users.require());
    }

    @GetMapping("/claims/nurse-assignment")
    @PreAuthorize("hasAnyRole('NURSING_HEAD','CLAIMS_COORDINATOR')")
    public NurseAssignmentService.AssignmentKpi nurseAssignment(@RequestParam(required = false) LocalDate from,
                                                                @RequestParam(required = false) LocalDate to) {
        return nurses.kpi(from, to);
    }
}
