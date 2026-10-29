package com.wellnessz.e08.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

import com.wellnessz.e08.collab.CollaborationService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E08-F04 nurse case manager ↔ adjuster collaboration: shared tasks, secure messages, RTW status and the adjuster portal. */
@RestController("e08ClaimCollaborationController")
@RequestMapping("/api/v1")
public class ClaimCollaborationController {

    private static final String PARTICIPANTS = "hasAnyRole('CLAIMS_COORDINATOR','TPA_ADJUSTER'," + Patterns.OH + ")";

    private final CollaborationService collab;
    private final CurrentUserProvider users;

    public ClaimCollaborationController(CollaborationService collab, CurrentUserProvider users) {
        this.collab = collab;
        this.users = users;
    }

    @GetMapping("/claims/{id}/tasks")
    @PreAuthorize(PARTICIPANTS)
    public List<CollaborationService.TaskView> tasks(@PathVariable UUID id) {
        return collab.tasks(users.require(), id);
    }

    public record TaskBody(@NotBlank @Size(max = 200) String title, @NotNull LocalDate dueDate, @NotBlank @Pattern(regexp = Patterns.ASSIGNEE) String assigneeRole) {
    }

    @PostMapping("/claims/{id}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(PARTICIPANTS)
    public CollaborationService.TaskView createTask(@PathVariable UUID id, @Valid @RequestBody TaskBody b) {
        return collab.createTask(users.require(), id, b.title(), b.dueDate(), b.assigneeRole());
    }

    @PostMapping("/claims/tasks/{taskId}/complete")
    @PreAuthorize(PARTICIPANTS)
    public CollaborationService.TaskView complete(@PathVariable UUID taskId) {
        return collab.completeTask(users.require(), taskId);
    }

    @GetMapping("/claims/{id}/messages")
    @PreAuthorize(PARTICIPANTS)
    public List<CollaborationService.MessageView> messages(@PathVariable UUID id) {
        return collab.messages(users.require(), id);
    }

    public record MessageBody(@NotBlank @Size(max = 2000) String text) {
    }

    @PostMapping("/claims/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(PARTICIPANTS)
    public CollaborationService.MessageView post(@PathVariable UUID id, @Valid @RequestBody MessageBody b) {
        return collab.post(users.require(), id, b.text());
    }

    @GetMapping("/claims/{id}/rtw")
    @PreAuthorize(PARTICIPANTS)
    public CollaborationService.RtwView rtw(@PathVariable UUID id) {
        return collab.rtw(users.require(), id);
    }

    @GetMapping("/partner/claims")
    @PreAuthorize("hasRole('TPA_ADJUSTER')")
    public List<CollaborationService.PartnerClaim> partnerClaims(@RequestParam(required = false) @Pattern(regexp = Patterns.CLAIM_STATUS) String status) {
        return collab.partnerClaims(users.require(), status);
    }

    @GetMapping("/partner/claims/{id}")
    @PreAuthorize("hasRole('TPA_ADJUSTER')")
    public CollaborationService.PartnerClaimDetail partnerClaim(@PathVariable UUID id) {
        return collab.partnerClaim(users.require(), id);
    }
}
