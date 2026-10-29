package com.wellnessz.e06.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.model.Roles;
import com.wellnessz.e06.lta.LtaService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E06-F04 long-term absence pathway: OH work list and detail, milestones, contacts, conferences; HR stage; workspace status. */
@RestController
@RequestMapping("/api/v1")
public class AbsenceLtaController {

    private final LtaService lta;
    private final CurrentUserProvider users;

    public AbsenceLtaController(LtaService lta, CurrentUserProvider users) {
        this.lta = lta;
        this.users = users;
    }

    @GetMapping("/absence/lta")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<LtaService.Pathway> list(@RequestParam(required = false) @Pattern(regexp = "ACTIVE|CLOSED") String status) {
        return lta.list(users.require(), status);
    }

    @GetMapping("/absence/lta/{caseId}")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public LtaService.PathwayDetail detail(@PathVariable UUID caseId) {
        return lta.detail(users.require(), caseId);
    }

    public record NoteRequest(@Size(max = 2000) String note) {
    }

    @PostMapping("/absence/lta/{caseId}/milestones/{id}/complete")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public LtaService.PathwayDetail complete(@PathVariable UUID caseId, @PathVariable UUID id,
                                             @Valid @RequestBody(required = false) NoteRequest r) {
        return lta.completeMilestone(users.require(), caseId, id, r == null ? null : r.note());
    }

    public record SkipRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    @PostMapping("/absence/lta/{caseId}/milestones/{id}/skip")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public LtaService.PathwayDetail skip(@PathVariable UUID caseId, @PathVariable UUID id, @Valid @RequestBody SkipRequest r) {
        return lta.skipMilestone(users.require(), caseId, id, r.reason());
    }

    public record LtaContactRequest(@NotBlank @Pattern(regexp = Patterns.CHANNEL) String channel,
                                    @NotBlank @Pattern(regexp = Patterns.CONTACT_OUTCOME) String outcome, @Size(max = 2000) String note,
                                    LocalDate nextContactDue) {
    }

    @PostMapping("/absence/lta/{caseId}/contacts")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public LtaService.PathwayDetail contact(@PathVariable UUID caseId, @Valid @RequestBody LtaContactRequest r) {
        return lta.addContact(users.require(), caseId, r.channel(), r.outcome(), r.note(), r.nextContactDue());
    }

    public record ConferenceRequest(@NotBlank @Pattern(regexp = Patterns.DATE_OR_DATETIME) String scheduledFor,
                                    @NotEmpty @Size(max = 4) List<@Pattern(regexp = "EMPLOYEE|MANAGER|HR|OH") String> participants,
                                    @Size(max = 2000) String note) {
    }

    @PostMapping("/absence/lta/{caseId}/conference")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public LtaService.PathwayDetail conference(@PathVariable UUID caseId, @Valid @RequestBody ConferenceRequest r) {
        return lta.conference(users.require(), caseId, r.scheduledFor(), r.participants(), r.note());
    }

    public record HrStageRequest(@NotBlank @Pattern(regexp = Patterns.HR_STAGE) String stage, @Size(max = 1000) String note) {
    }

    @PostMapping("/workspace/absence/lta/{caseId}/hr-stage")
    @PreAuthorize("hasRole('HR')")
    public LtaService.WorkspacePathway hrStage(@PathVariable UUID caseId, @Valid @RequestBody HrStageRequest r) {
        return lta.hrStage(users.require(), caseId, r.stage(), r.note());
    }

    @GetMapping("/workspace/absence/lta")
    @PreAuthorize("hasAnyRole('HR','MANAGER')")
    public List<LtaService.WorkspacePathway> workspace() {
        return lta.workspace(users.require());
    }
}
