package com.wellnessz.e04.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

import com.wellnessz.documents.DocumentService;
import com.wellnessz.e04.plan.PlanService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E04-F01 treatment plans, follow-ups, variance alerts and the employee's plain-language plan. */
@RestController
@RequestMapping("/api/v1")
public class TreatmentPlanController {

    private final PlanService plans;
    private final CurrentUserProvider users;

    public TreatmentPlanController(PlanService plans, CurrentUserProvider users) {
        this.plans = plans;
        this.users = users;
    }

    @GetMapping("/treatment/plan-templates")
    @PreAuthorize(Spel.OH_TEAM)
    public List<PlanService.TemplateView> templates() {
        return plans.templates();
    }

    @PostMapping("/cases/{caseId}/treatment-plans")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLINICIANS)
    public PlanService.PlanView create(@PathVariable UUID caseId, @RequestParam(defaultValue = "false") boolean activate,
                                       @Valid @RequestBody PlanService.PlanCommand cmd) {
        return plans.create(users.require(), caseId, cmd, activate);
    }

    @GetMapping("/cases/{caseId}/treatment-plans")
    @PreAuthorize(Spel.OH_TEAM)
    public List<PlanService.PlanView> list(@PathVariable UUID caseId) {
        return plans.list(users.require(), caseId);
    }

    @PutMapping("/treatment-plans/{id}")
    @PreAuthorize(Spel.CLINICIANS)
    public PlanService.PlanView update(@PathVariable UUID id, @Valid @RequestBody PlanService.PlanCommand cmd) {
        return plans.update(users.require(), id, cmd);
    }

    public record StatusRequest(@NotBlank @Pattern(regexp = "ACTIVE|COMPLETED|CANCELLED") String status, @Size(max = 2000) String note) {
    }

    @PostMapping("/treatment-plans/{id}/status")
    @PreAuthorize(Spel.CLINICIANS)
    public PlanService.PlanView status(@PathVariable UUID id, @Valid @RequestBody StatusRequest r) {
        return plans.status(users.require(), id, r.status(), r.note());
    }

    public record FollowUpStatusRequest(@NotBlank @Pattern(regexp = "DONE|MISSED|CANCELLED") String status, @Size(max = 2000) String note) {
    }

    @PostMapping("/treatment-plans/{id}/follow-ups/{followUpId}")
    @PreAuthorize(Spel.CLINICIANS)
    public PlanService.PlanView followUp(@PathVariable UUID id, @PathVariable UUID followUpId, @Valid @RequestBody FollowUpStatusRequest r) {
        return plans.followUpStatus(users.require(), id, followUpId, r.status(), r.note());
    }

    @PostMapping("/treatment-plans/{id}/print")
    @PreAuthorize(Spel.CLINICIANS)
    public DocumentService.DocumentView print(@PathVariable UUID id) {
        return plans.print(users.require(), id);
    }

    @GetMapping("/treatment/variance-alerts")
    @PreAuthorize(Spel.OH_TEAM)
    public List<PlanService.AlertView> alerts(@RequestParam(required = false) @Pattern(regexp = "OPEN|ACKNOWLEDGED|ESCALATED|CLOSED") String status) {
        return plans.alerts(users.require(), status);
    }

    public record NoteRequest(@Size(max = 2000) String note) {
    }

    @PostMapping("/treatment/variance-alerts/{id}/acknowledge")
    @PreAuthorize(Spel.OH_TEAM)
    public PlanService.AlertView acknowledge(@PathVariable UUID id, @Valid @RequestBody(required = false) NoteRequest r) {
        return plans.actOnAlert(users.require(), id, false, r == null ? null : r.note());
    }

    @PostMapping("/treatment/variance-alerts/{id}/escalate")
    @PreAuthorize(Spel.OH_TEAM)
    public PlanService.AlertView escalate(@PathVariable UUID id, @Valid @RequestBody(required = false) NoteRequest r) {
        return plans.actOnAlert(users.require(), id, true, r == null ? null : r.note());
    }

    // ------------------------------------------------------------------ employee

    @GetMapping("/me/treatment-plans")
    @PreAuthorize(Spel.EMPLOYEE)
    public List<PlanService.MyPlan> mine() {
        return plans.mine(users.require());
    }

    public record ResponseRequest(@NotBlank @Pattern(regexp = "CONFIRM|DECLINE") String response, @Size(max = 500) String reason) {
    }

    @PostMapping("/me/treatment-plans/{id}/follow-ups/{followUpId}/response")
    @PreAuthorize(Spel.EMPLOYEE)
    public PlanService.MyFollowUp respond(@PathVariable UUID id, @PathVariable UUID followUpId, @Valid @RequestBody ResponseRequest r) {
        return plans.respond(users.require(), id, followUpId, r.response(), r.reason());
    }
}
