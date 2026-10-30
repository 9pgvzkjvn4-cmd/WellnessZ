package com.wellnessz.e10.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.e10.collection.CollectionService;
import com.wellnessz.e10.mro.MroService;
import com.wellnessz.e10.pathway.RtdService;
import com.wellnessz.e10.support.TestingViews.CustodyView;
import com.wellnessz.e10.support.TestingViews.DutyStatusView;
import com.wellnessz.e10.support.TestingViews.ForCauseView;
import com.wellnessz.e10.support.TestingViews.MroDecisionView;
import com.wellnessz.e10.support.TestingViews.MroQueueItem;
import com.wellnessz.e10.support.TestingViews.MyNotice;
import com.wellnessz.e10.support.TestingViews.Requirement;
import com.wellnessz.e10.support.TestingViews.RtdPlanView;
import com.wellnessz.e10.testing.ScheduleService;
import com.wellnessz.e10.trigger.TriggerService;
import com.wellnessz.security.identity.CurrentUserProvider;

/**
 * E10-F02 for-cause requests and the requirement work list, E10-F03 collection / chain of custody / refusal / MRO review,
 * E10-F04 return-to-duty plans and the status-only duty list for HR and crew scheduling, and the employee's own notices.
 */
@RestController("e10OperationsController")
@RequestMapping("/api/v1")
public class TestingOperationsController {

    private final TriggerService triggers;
    private final CollectionService collection;
    private final MroService mro;
    private final RtdService rtd;
    private final ScheduleService schedule;
    private final ModuleRegistry modules;
    private final CurrentUserProvider users;

    public TestingOperationsController(TriggerService triggers, CollectionService collection, MroService mro, RtdService rtd, ScheduleService schedule,
                                       ModuleRegistry modules, CurrentUserProvider users) {
        this.triggers = triggers;
        this.collection = collection;
        this.mro = mro;
        this.rtd = rtd;
        this.schedule = schedule;
        this.modules = modules;
        this.users = users;
    }

    // ------------------------------------------------------------------ for-cause (manager workspace)

    public record ForCauseBody(@NotBlank @Pattern(regexp = Patterns.EMPLOYEE_ID) String employeeId, @NotNull Instant observedAt,
                               @NotBlank @Size(max = 200) String location,
                               @NotEmpty @Size(max = 8) List<@NotBlank @Pattern(regexp = Patterns.OBSERVATION) String> observations,
                               @Size(max = 2000) String description, @NotBlank @Size(max = 200) String secondObserverName) {
    }

    @PostMapping("/workspace/testing/for-cause")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MANAGER','SAFETY_MANAGER')")
    public ForCauseView forCause(@Valid @RequestBody ForCauseBody b) {
        modules.requireEnabled("E10");
        return triggers.forCause(users.require(), new TriggerService.ForCauseCommand(b.employeeId(), b.observedAt(), b.location().strip(),
                b.observations(), b.description(), b.secondObserverName()));
    }

    @GetMapping("/workspace/testing/for-cause")
    @PreAuthorize("hasAnyRole('MANAGER','SAFETY_MANAGER')")
    public List<ForCauseView> myForCause() {
        return triggers.myForCause(users.require());
    }

    @GetMapping("/workspace/testing/status")
    @PreAuthorize("hasAnyRole('HR','CREW_SCHEDULING')")
    public List<DutyStatusView> dutyStatus() {
        return rtd.dutyStatus(users.require());
    }

    // ------------------------------------------------------------------ requirements & collection

    @GetMapping("/testing/requirements")
    @PreAuthorize("hasAnyRole('DA_PROGRAMME_MANAGER','COLLECTOR','MRO')")
    public List<Requirement> requirements(@RequestParam(required = false) @Pattern(regexp = Patterns.TEST_STATUS) String status) {
        modules.requireEnabled("E10");
        return triggers.requirements(users.require(), status);
    }

    public record CollectionBody(@NotBlank @Pattern(regexp = Patterns.SPECIMEN_ID) String specimenId,
                                 @NotBlank @Pattern(regexp = Patterns.SPECIMEN_TYPE) String specimenType, @NotNull Instant collectedAt,
                                 @NotBlank @Pattern(regexp = Patterns.SITE) String siteCode, Boolean temperatureInRange,
                                 @NotNull Boolean sealIntact, @NotNull Boolean donorIdentityChecked, @NotNull Boolean splitSpecimen,
                                 @DecimalMin("0") @DecimalMax("1") BigDecimal breathScreenResult, @Size(max = 1000) String remarks) {
    }

    @PostMapping("/testing/tests/{id}/collection")
    @PreAuthorize("hasRole('COLLECTOR')")
    public Requirement collect(@PathVariable UUID id, @Valid @RequestBody CollectionBody b) {
        return collection.collect(users.require(), id, new CollectionService.CollectionCommand(b.specimenId(), b.specimenType(), b.collectedAt(),
                b.siteCode(), b.temperatureInRange(), b.sealIntact(), b.donorIdentityChecked(), b.splitSpecimen(), b.breathScreenResult(), b.remarks()));
    }

    public record CustodyBody(@NotBlank @Pattern(regexp = Patterns.CUSTODY_EVENT) String event, @NotNull Instant at,
                              @NotBlank @Size(max = 200) String by) {
    }

    @PostMapping("/testing/specimens/{specimenId}/custody")
    @PreAuthorize("hasAnyRole('COLLECTOR','INTEGRATION')")
    public List<CustodyView> custody(@PathVariable @Pattern(regexp = Patterns.SPECIMEN_ID) String specimenId, @Valid @RequestBody CustodyBody b) {
        return collection.custody(users.require(), specimenId, new CollectionService.CustodyCommand(b.event(), b.at(), b.by()));
    }

    @GetMapping("/testing/specimens/{specimenId}/custody")
    @PreAuthorize("hasAnyRole('COLLECTOR','MRO','DA_PROGRAMME_MANAGER')")
    public List<CustodyView> custodyEvents(@PathVariable @Pattern(regexp = Patterns.SPECIMEN_ID) String specimenId) {
        return collection.custodyEvents(specimenId);
    }

    public record RefusalBody(@NotBlank @Pattern(regexp = Patterns.REFUSAL) String reason, @Size(max = 2000) String observations) {
    }

    @PostMapping("/testing/tests/{id}/refusal")
    @PreAuthorize("hasRole('COLLECTOR')")
    public Requirement refusal(@PathVariable UUID id, @Valid @RequestBody RefusalBody b) {
        return collection.refusal(users.require(), id, b.reason(), b.observations());
    }

    // ------------------------------------------------------------------ MRO

    @GetMapping("/testing/mro/queue")
    @PreAuthorize("hasAnyRole('MRO','OH_PHYSICIAN')")
    public List<MroQueueItem> queue() {
        return mro.queue(users.require());
    }

    public record DecisionBody(@NotBlank @Pattern(regexp = Patterns.VERDICT) String verdict, Boolean prescriptionVerified,
                               @Size(max = 4000) String interviewNote, Boolean splitSpecimenRequested) {
    }

    @PostMapping("/testing/tests/{id}/mro-decision")
    @PreAuthorize("hasAnyRole('MRO','OH_PHYSICIAN')")
    public MroDecisionView decide(@PathVariable UUID id, @Valid @RequestBody DecisionBody b) {
        return mro.decide(users.require(), id, new MroService.DecisionCommand(b.verdict(), b.prescriptionVerified(), b.interviewNote(),
                b.splitSpecimenRequested()));
    }

    @PostMapping("/testing/tests/{id}/split-request")
    @PreAuthorize("hasAnyRole('MRO','OH_PHYSICIAN')")
    public MroDecisionView split(@PathVariable UUID id) {
        return mro.splitRequest(users.require(), id);
    }

    // ------------------------------------------------------------------ return to duty

    @GetMapping("/testing/rtd-plans")
    @PreAuthorize("hasAnyRole('MRO','OH_PHYSICIAN')")
    public List<RtdPlanView> plans(@RequestParam(required = false) @Pattern(regexp = Patterns.PLAN_STATUS) String status) {
        return rtd.list(status);
    }

    @GetMapping("/testing/rtd-plans/{id}")
    @PreAuthorize("hasAnyRole('MRO','OH_PHYSICIAN')")
    public RtdPlanView plan(@PathVariable UUID id) {
        return rtd.get(id);
    }

    public record PlanBody(@NotBlank @Pattern(regexp = Patterns.EMPLOYEE_ID) String employeeId, UUID caseId,
                           @NotBlank @Size(max = 200) String evaluator, @Size(max = 300) String treatmentProvider,
                           @NotNull @Min(0) @Max(60) Integer followUpTests, @NotNull @Min(1) @Max(60) Integer followUpMonths) {
    }

    @PostMapping("/testing/rtd-plans")
    @PreAuthorize("hasAnyRole('MRO','OH_PHYSICIAN')")
    public RtdPlanView definePlan(@Valid @RequestBody PlanBody b) {
        return rtd.define(users.require(), new RtdService.PlanCommand(b.employeeId(), b.caseId(), b.evaluator(), b.treatmentProvider(),
                b.followUpTests(), b.followUpMonths()));
    }

    public record EvaluationBody(@NotBlank @Pattern(regexp = Patterns.EVALUATION) String outcome, @Size(max = 4000) String note) {
    }

    @PostMapping("/testing/rtd-plans/{id}/evaluation")
    @PreAuthorize("hasAnyRole('MRO','OH_PHYSICIAN')")
    public RtdPlanView evaluate(@PathVariable UUID id, @Valid @RequestBody EvaluationBody b) {
        return rtd.evaluate(users.require(), id, b.outcome(), b.note());
    }

    // ------------------------------------------------------------------ employee

    @GetMapping("/me/testing")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public List<MyNotice> mine() {
        return schedule.mine(users.require());
    }
}
