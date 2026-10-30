package com.wellnessz.e09.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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

import com.wellnessz.e09.casework.SupportCaseService;
import com.wellnessz.e09.casework.SupportViews;
import com.wellnessz.e09.model.MentalHealthEnums.Factor;
import com.wellnessz.e09.psychosocial.PsychosocialService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** Psychosocial risk register (contract §3.5) and the manager workspace (unit assessment, shared outcomes – §3.1). */
@RestController("e09PsychosocialController")
@RequestMapping("/api/v1")
public class PsychosocialController {

    private final PsychosocialService psychosocial;
    private final SupportCaseService cases;
    private final CurrentUserProvider users;

    public PsychosocialController(PsychosocialService psychosocial, SupportCaseService cases, CurrentUserProvider users) {
        this.psychosocial = psychosocial;
        this.cases = cases;
        this.users = users;
    }

    @GetMapping("/psychosocial/assessments")
    @PreAuthorize(Patterns.PSYCHOSOCIAL)
    public List<PsychosocialService.AssessmentView> list(@RequestParam(required = false) @Pattern(regexp = Patterns.UNIT) String unitCode) {
        return psychosocial.list(unitCode);
    }

    public record FactorBody(@NotBlank @Pattern(regexp = Patterns.FACTOR) String factor, @Min(1) @Max(5) int likelihood, @Min(1) @Max(5) int severity,
                             @Size(max = 1000) String controls) {
    }

    public record AssessmentBody(@NotBlank @Pattern(regexp = Patterns.UNIT) String unitCode, @NotNull LocalDate assessedOn,
                                 @NotEmpty @Size(max = 8) List<@Valid @NotNull FactorBody> factors, @NotNull LocalDate reviewDate) {
    }

    @PostMapping("/psychosocial/assessments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Patterns.PSYCHOSOCIAL)
    public PsychosocialService.AssessmentView create(@Valid @RequestBody AssessmentBody b) {
        return psychosocial.create(users.require(), new PsychosocialService.CreateCommand(b.unitCode(), b.assessedOn(), b.factors().stream()
                .map(f -> new PsychosocialService.FactorInput(Factor.valueOf(f.factor()), f.likelihood(), f.severity(), f.controls())).toList(), b.reviewDate()));
    }

    public record ActionBody(@NotBlank @Size(max = 300) String title, @NotBlank @Size(max = 200) String owner, @NotNull LocalDate dueDate,
                             @Pattern(regexp = "|" + Patterns.ACTION_STATUS) String status) {
        PsychosocialService.ActionCommand command() {
            return new PsychosocialService.ActionCommand(title, owner, dueDate, status == null || status.isEmpty() ? null : status);
        }
    }

    @PostMapping("/psychosocial/assessments/{id}/actions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Patterns.PSYCHOSOCIAL)
    public PsychosocialService.ActionView addAction(@PathVariable UUID id, @Valid @RequestBody ActionBody b) {
        return psychosocial.addAction(users.require(), id, b.command());
    }

    @PutMapping("/psychosocial/assessments/{id}/actions/{actionId}")
    @PreAuthorize(Patterns.PSYCHOSOCIAL)
    public PsychosocialService.ActionView updateAction(@PathVariable UUID id, @PathVariable UUID actionId, @Valid @RequestBody ActionBody b) {
        return psychosocial.updateAction(users.require(), id, actionId, b.command());
    }

    @GetMapping("/psychosocial/dashboard")
    @PreAuthorize(Patterns.PSYCHOSOCIAL)
    public List<PsychosocialService.UnitRow> dashboard() {
        return psychosocial.dashboard();
    }

    // ------------------------------------------------------------------ manager workspace

    @GetMapping("/workspace/psychosocial")
    @PreAuthorize("hasRole('MANAGER')")
    public PsychosocialService.ManagerView workspace() {
        return psychosocial.managerView(users.require());
    }

    @GetMapping("/workspace/psychosocial/shared-outcomes")
    @PreAuthorize("hasRole('MANAGER')")
    public List<SupportViews.SharedOutcome> sharedOutcomes() {
        return cases.sharedOutcomes(users.require());
    }
}
