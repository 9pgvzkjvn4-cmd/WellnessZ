package com.wellnessz.casekernel.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseStatus;
import com.wellnessz.casekernel.model.CaseViews;
import com.wellnessz.casekernel.model.FitnessStatus;
import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.security.identity.CurrentUserProvider;

@RestController
@RequestMapping("/api/v1")
public class CaseController {

    private final CaseService service;
    private final CurrentUserProvider users;
    private final ModuleRegistry modules;

    public CaseController(CaseService service, CurrentUserProvider users, ModuleRegistry modules) {
        this.service = service;
        this.users = users;
        this.modules = modules;
    }

    public record SelfReferralRequest(@NotNull CaseCategory category, @NotBlank @Size(max = 4000) String description,
                                      @Size(max = 20) String preferredContact) {
    }

    public record TransitionRequest(@NotNull CaseStatus status) {
    }

    public record FitnessRequest(@NotNull FitnessStatus fitnessStatus, LocalDate restrictionUntil) {
    }

    @GetMapping("/cases")
    public List<Object> list(@RequestParam(defaultValue = "100") int limit) {
        return service.list(users.require(), limit);
    }

    @GetMapping("/cases/{id}")
    public Object get(@PathVariable UUID id) {
        return service.get(users.require(), id);
    }

    @GetMapping("/cases/{id}/status")
    public CaseViews.StatusOnly status(@PathVariable UUID id) {
        return service.status(users.require(), id);
    }

    @PostMapping("/cases/self-referral")
    @ResponseStatus(HttpStatus.CREATED)
    public CaseViews.Clinical selfReferral(@Valid @RequestBody SelfReferralRequest r) {
        return service.selfReferral(users.require(), r.category(), r.description());
    }

    @PostMapping("/cases/{id}/transition")
    public CaseViews.StatusOnly transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest r) {
        return service.transition(users.require(), id, r.status());
    }

    @PostMapping("/cases/{id}/fitness")
    public CaseViews.StatusOnly fitness(@PathVariable UUID id, @Valid @RequestBody FitnessRequest r) {
        return service.recordFitness(users.require(), id, r.fitnessStatus(), r.restrictionUntil());
    }

    @GetMapping("/modules")
    public List<ModuleRegistry.ModuleInfo> modules() {
        return modules.all();
    }
}
