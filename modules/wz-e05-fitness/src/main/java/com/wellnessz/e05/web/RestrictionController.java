package com.wellnessz.e05.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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

import com.wellnessz.common.model.Roles;
import com.wellnessz.e05.restriction.DutyService;
import com.wellnessz.e05.restriction.MatchingEngine;
import com.wellnessz.e05.restriction.RestrictionService;
import com.wellnessz.e05.support.RestrictionRef;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E05-F02 matching and modified-duty proposals; E05-F06 restriction list, extend / close, team view and dashboard. */
@RestController
@RequestMapping("/api/v1")
public class RestrictionController {

    private final DutyService duties;
    private final RestrictionService restrictions;
    private final CurrentUserProvider users;

    public RestrictionController(DutyService duties, RestrictionService restrictions, CurrentUserProvider users) {
        this.duties = duties;
        this.restrictions = restrictions;
        this.users = users;
    }

    // ------------------------------------------------------------------ E05-F02

    public record MatchRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9\\-]{1,64}") String employeeId,
                               @NotEmpty @Size(max = 20) @Valid List<RestrictionRef.Input> restrictions,
                               @Pattern(regexp = "[A-Z0-9_]{1,20}") String unit) {
    }

    @PostMapping("/fitness/match")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<MatchingEngine.Match> match(@Valid @RequestBody MatchRequest r) {
        return duties.match(users.require(), r.employeeId(), r.restrictions(), r.unit());
    }

    public record DutyProposalRequest(@NotBlank @Pattern(regexp = "[A-Z0-9_]{1,64}") String jobCode,
                                      @NotBlank @Size(max = 1000) String duties,
                                      @DecimalMin("0.5") @DecimalMax("24") BigDecimal hoursPerDay,
                                      @NotNull LocalDate startDate, LocalDate endDate) {
    }

    @PostMapping("/cases/{id}/duty-proposals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public DutyService.ProposalView propose(@PathVariable UUID id, @Valid @RequestBody DutyProposalRequest r) {
        return duties.propose(users.require(), id, new DutyService.ProposalCommand(r.jobCode(), r.duties(), r.hoursPerDay(), r.startDate(),
                r.endDate()));
    }

    @GetMapping("/cases/{id}/duty-proposals")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<DutyService.ProposalView> proposals(@PathVariable UUID id) {
        return duties.forCase(users.require(), id);
    }

    @GetMapping("/workspace/duty-proposals")
    @PreAuthorize("hasAnyRole('MANAGER','HR')")
    public List<DutyService.ProposalView> workspaceProposals(
            @RequestParam(defaultValue = "PROPOSED") @Pattern(regexp = "PROPOSED|ACCEPTED|DECLINED|ESCALATED_TO_HR|WITHDRAWN") String status) {
        return duties.workspace(users.require(), status);
    }

    public record DutyDecisionRequest(@NotNull Boolean accept,
                                      @Pattern(regexp = "NO_SUITABLE_WORK|OPERATIONAL|SAFETY|COST|OTHER") String reasonCode,
                                      @Size(max = 1000) String note) {
    }

    @PostMapping("/duty-proposals/{id}/decision")
    @PreAuthorize("hasRole('MANAGER')")
    public DutyService.ProposalView decide(@PathVariable UUID id, @Valid @RequestBody DutyDecisionRequest r) {
        return duties.decide(users.require(), id, r.accept(), r.reasonCode(), r.note());
    }

    // ------------------------------------------------------------------ E05-F06

    @GetMapping("/restrictions/active")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<RestrictionService.InstanceView> active(@RequestParam(required = false) @Pattern(regexp = "[A-Z0-9_]{1,64}") String unit,
                                                        @RequestParam(required = false) @Pattern(regexp = "ACTIVE|EXPIRING|LAPSED|CLOSED") String status) {
        return restrictions.active(users.require(), unit, status);
    }

    public record ExtendRequest(@NotNull LocalDate validUntil, @NotBlank @Size(max = 500) String reason) {
    }

    public record CloseRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @PostMapping("/restrictions/{id}/extend")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public RestrictionService.InstanceView extend(@PathVariable UUID id, @Valid @RequestBody ExtendRequest r) {
        return restrictions.extend(users.require(), id, r.validUntil(), r.reason());
    }

    @PostMapping("/restrictions/{id}/close")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public RestrictionService.InstanceView close(@PathVariable UUID id, @Valid @RequestBody CloseRequest r) {
        return restrictions.close(users.require(), id, r.reason());
    }

    @GetMapping("/workspace/team-restrictions")
    @PreAuthorize("hasAnyRole('MANAGER','HR')")
    public List<RestrictionService.TeamMember> team() {
        return restrictions.team(users.require());
    }

    @GetMapping("/restrictions/dashboard")
    @PreAuthorize("hasAnyRole('NURSING_HEAD','CMO')")
    public RestrictionService.Dashboard dashboard() {
        return restrictions.dashboard(users.require());
    }
}
