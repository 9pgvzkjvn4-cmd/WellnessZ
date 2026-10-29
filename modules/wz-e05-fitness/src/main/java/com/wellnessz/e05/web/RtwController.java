package com.wellnessz.e05.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.model.Roles;
import com.wellnessz.e05.rtw.JobOfferService;
import com.wellnessz.e05.rtw.RtwService;
import com.wellnessz.e05.support.RestrictionRef;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E05-F03 return-to-work plans, reviews and alternative-duty job offers. */
@RestController
@RequestMapping("/api/v1")
public class RtwController {

    private final RtwService rtw;
    private final JobOfferService offers;
    private final CurrentUserProvider users;

    public RtwController(RtwService rtw, JobOfferService offers, CurrentUserProvider users) {
        this.rtw = rtw;
        this.offers = offers;
        this.users = users;
    }

    // ------------------------------------------------------------------ plans

    public record PhaseRequest(@NotNull @Min(1) @Max(52) Integer fromWeek, @NotNull @Min(1) @Max(52) Integer toWeek,
                               @NotNull @DecimalMin("0.5") @DecimalMax("24") BigDecimal hoursPerDay, @NotBlank @Size(max = 1000) String duties,
                               @Size(max = 20) @Valid List<RestrictionRef.Input> restrictions) {
    }

    public record RtwPlanRequest(@NotNull LocalDate startDate, @NotEmpty @Size(max = 12) List<@Valid PhaseRequest> phases,
                                 @Size(max = 20) List<@NotNull LocalDate> reviewDates, @NotBlank @Size(max = 1000) String goal) {
        RtwService.PlanCommand command() {
            return new RtwService.PlanCommand(startDate, phases.stream().map(p -> new RtwService.PhaseInput(p.fromWeek(), p.toWeek(),
                    p.hoursPerDay(), p.duties(), p.restrictions())).toList(), reviewDates, goal);
        }
    }

    @PostMapping("/cases/{id}/rtw-plans")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public RtwService.PlanView create(@PathVariable UUID id, @Valid @RequestBody RtwPlanRequest r) {
        return rtw.create(users.require(), id, r.command());
    }

    @PutMapping("/rtw-plans/{id}")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public RtwService.PlanView update(@PathVariable UUID id, @Valid @RequestBody RtwPlanRequest r) {
        return rtw.update(users.require(), id, r.command());
    }

    @PostMapping("/rtw-plans/{id}/activate")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public RtwService.PlanView activate(@PathVariable UUID id) {
        return rtw.activate(users.require(), id);
    }

    @GetMapping("/cases/{id}/rtw-plans")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<RtwService.PlanView> plans(@PathVariable UUID id) {
        return rtw.forCase(users.require(), id);
    }

    @GetMapping("/me/rtw-plans")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public List<RtwService.PlanView> myPlans() {
        return rtw.mine(users.require());
    }

    public record EmployeeAckRequest(@NotNull Boolean agree, @Size(max = 1000) String comment) {
    }

    @PostMapping("/me/rtw-plans/{id}/acknowledge")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public RtwService.PlanView employeeAck(@PathVariable UUID id, @Valid @RequestBody EmployeeAckRequest r) {
        return rtw.employeeAcknowledge(users.require(), id, r.agree(), r.comment());
    }

    @GetMapping("/workspace/rtw-plans")
    @PreAuthorize("hasAnyRole('MANAGER','HR')")
    public List<RtwService.PlanView> workspacePlans() {
        return rtw.workspace(users.require());
    }

    public record ManagerAckRequest(@Size(max = 1000) String comment) {
    }

    @PostMapping("/rtw-plans/{id}/manager-acknowledge")
    @PreAuthorize("hasRole('MANAGER')")
    public RtwService.PlanView managerAck(@PathVariable UUID id, @Valid @RequestBody(required = false) ManagerAckRequest r) {
        return rtw.managerAcknowledge(users.require(), id, r == null ? null : r.comment());
    }

    public record ReviewRequest(@NotBlank @Pattern(regexp = "ON_TRACK|ADJUST|EXTEND|COMPLETE") String outcome, @Size(max = 1000) String note) {
    }

    @PostMapping("/rtw-plans/{id}/reviews/{reviewId}/complete")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public RtwService.PlanView completeReview(@PathVariable UUID id, @PathVariable UUID reviewId, @Valid @RequestBody ReviewRequest r) {
        return rtw.completeReview(users.require(), id, reviewId, r.outcome(), r.note());
    }

    @GetMapping("/rtw/reviews/overdue")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<RtwService.OverdueReview> overdue() {
        return rtw.overdue(users.require());
    }

    // ------------------------------------------------------------------ job offers

    public record JobOfferRequest(@Pattern(regexp = "[A-Z0-9_]{1,64}") String jobCode, @NotBlank @Size(max = 200) String jobTitle,
                                  @NotBlank @Size(max = 2000) String duties, @NotBlank @Size(max = 200) String location,
                                  @NotBlank @Size(max = 100) String shift,
                                  @NotNull @DecimalMin("0.5") @DecimalMax("60") BigDecimal hoursPerWeek,
                                  @NotBlank @Pattern(regexp = "SAME_PAY|PROTECTED_PAY|ADJUSTED_PAY") String payBasis,
                                  @Size(max = 500) String payNote, @Size(max = 20) @Valid List<RestrictionRef.Input> restrictions,
                                  @NotNull LocalDate startDate, LocalDate endDate, @NotBlank @Size(max = 200) String contactName,
                                  @Email @Size(max = 200) String contactEmail, @NotNull LocalDate respondBy) {
    }

    @PostMapping("/cases/{id}/job-offers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('HR')")
    public JobOfferService.OfferView issue(@PathVariable UUID id, @Valid @RequestBody JobOfferRequest r) {
        return offers.issue(users.require(), id, new JobOfferService.OfferCommand(r.jobCode(), r.jobTitle(), r.duties(), r.location(),
                r.shift(), r.hoursPerWeek(), r.payBasis(), r.payNote(), r.restrictions(), r.startDate(), r.endDate(), r.contactName(),
                r.contactEmail(), r.respondBy()));
    }

    @GetMapping("/cases/{id}/job-offers")
    @PreAuthorize("hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN','HR')")
    public List<JobOfferService.OfferView> caseOffers(@PathVariable UUID id) {
        return offers.forCase(users.require(), id);
    }

    @GetMapping("/me/job-offers")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public List<JobOfferService.OfferView> myOffers() {
        return offers.mine(users.require());
    }

    public record OfferResponse(@NotNull Boolean accept, @Size(max = 1000) String comment) {
    }

    @PostMapping("/me/job-offers/{id}/respond")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public JobOfferService.OfferView respond(@PathVariable UUID id, @Valid @RequestBody OfferResponse r) {
        return offers.respond(users.require(), id, r.accept(), r.comment());
    }

    @PostMapping("/job-offers/{id}/withdraw")
    @PreAuthorize("hasRole('HR')")
    public JobOfferService.OfferView withdraw(@PathVariable UUID id) {
        return offers.withdraw(users.require(), id);
    }
}
