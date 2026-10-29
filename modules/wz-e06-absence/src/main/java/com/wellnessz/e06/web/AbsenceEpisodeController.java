package com.wellnessz.e06.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.model.Roles;
import com.wellnessz.e06.episode.EpisodeService;
import com.wellnessz.e06.episode.EpisodeViews;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E06-F01 absence history: OH (full), HR / managers in scope (status only), the employee (own), team view. */
@RestController
@RequestMapping("/api/v1")
public class AbsenceEpisodeController {

    private final EpisodeService episodes;
    private final CurrentUserProvider users;

    public AbsenceEpisodeController(EpisodeService episodes, CurrentUserProvider users) {
        this.episodes = episodes;
        this.users = users;
    }

    @GetMapping("/absence/employees/{employeeId}/episodes")
    @PreAuthorize(Roles.OH_TEAM_SPEL + " or hasAnyRole('HR','MANAGER')")
    public List<EpisodeViews.EpisodeView> episodes(@PathVariable @Pattern(regexp = Patterns.EMP) String employeeId,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return episodes.forEmployee(users.require(), employeeId, from, to);
    }

    @GetMapping("/absence/employees/{employeeId}/summary")
    @PreAuthorize(Roles.OH_TEAM_SPEL + " or hasRole('HR')")
    public EpisodeService.Summary summary(@PathVariable @Pattern(regexp = Patterns.EMP) String employeeId) {
        return episodes.summary(users.require(), employeeId);
    }

    public record LinkCaseRequest(@NotNull UUID caseId) {
    }

    @PostMapping("/absence/episodes/{id}/link-case")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public EpisodeViews.EpisodeView linkCase(@PathVariable UUID id, @Valid @RequestBody LinkCaseRequest r) {
        return episodes.linkCase(users.require(), id, r.caseId());
    }

    @GetMapping("/me/absence/episodes")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public List<EpisodeViews.EpisodeView> mine() {
        return episodes.mine(users.require());
    }

    @GetMapping("/workspace/absence/team")
    @PreAuthorize("hasAnyRole('MANAGER','HR')")
    public List<EpisodeService.TeamMember> team() {
        return episodes.team(users.require());
    }
}
