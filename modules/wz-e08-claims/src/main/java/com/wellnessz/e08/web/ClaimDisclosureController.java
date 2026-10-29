package com.wellnessz.e08.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
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

import com.wellnessz.e08.disclosure.DisclosureSetService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E08-F03 consent-gated document exchange: disclosure sets, redaction, send / revoke and the DPO disclosure report. */
@RestController("e08ClaimDisclosureController")
@RequestMapping("/api/v1")
public class ClaimDisclosureController {

    private final DisclosureSetService sets;
    private final CurrentUserProvider users;

    public ClaimDisclosureController(DisclosureSetService sets, CurrentUserProvider users) {
        this.sets = sets;
        this.users = users;
    }

    /** Disclosure candidates of the claim's employee (titles and types, disclosable flag) for building a set. */
    @GetMapping("/claims/{id}/documents")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR'," + Patterns.OH + ")")
    public List<DisclosureSetService.CandidateView> candidates(@PathVariable UUID id) {
        return sets.candidates(users.require(), id);
    }

    @GetMapping("/claims/{id}/consents")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR'," + Patterns.OH + ")")
    public List<com.wellnessz.e08.support.InsurerConsent.ConsentView> consents(@PathVariable UUID id) {
        return sets.consents(users.require(), id);
    }

    public record CreateBody(@NotBlank @Pattern(regexp = Patterns.CODE) String recipientCode, @NotBlank @Size(max = 500) String purpose,
                             @NotEmpty @Size(max = 50) List<@NotNull UUID> documentIds) {
    }

    @PostMapping("/claims/{id}/disclosure-sets")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR'," + Patterns.OH + ")")
    public DisclosureSetService.SetView create(@PathVariable UUID id, @Valid @RequestBody CreateBody b) {
        return sets.create(users.require(), id, b.recipientCode(), b.purpose(), b.documentIds());
    }

    @GetMapping("/claims/{id}/disclosure-sets")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR','DPO'," + Patterns.OH + ")")
    public List<DisclosureSetService.SetView> list(@PathVariable UUID id) {
        return sets.list(users.require(), id);
    }

    public record RedactionBody(@NotNull UUID documentId, @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 200) String> phrases) {
    }

    @PostMapping("/claims/disclosure-sets/{setId}/redactions")
    @PreAuthorize("hasAnyRole(" + Patterns.OH + ")")
    public DisclosureSetService.SetView redact(@PathVariable UUID setId, @Valid @RequestBody RedactionBody b) {
        return sets.redact(users.require(), setId, b.documentId(), b.phrases());
    }

    public record SendBody(UUID consentId) {
    }

    @PostMapping("/claims/disclosure-sets/{setId}/send")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR'," + Patterns.OH + ")")
    public DisclosureSetService.SetView send(@PathVariable UUID setId, @RequestBody(required = false) SendBody b) {
        return sets.send(users.require(), setId, b == null ? null : b.consentId());
    }

    @PostMapping("/claims/disclosure-sets/{setId}/revoke")
    @PreAuthorize("hasAnyRole('CLAIMS_COORDINATOR','DPO')")
    public DisclosureSetService.SetView revoke(@PathVariable UUID setId) {
        return sets.revoke(users.require(), setId);
    }

    @GetMapping("/claims/disclosure-report")
    @PreAuthorize("hasRole('DPO')")
    public List<DisclosureSetService.DisclosureRow> report(@RequestParam(required = false) LocalDate from,
                                                          @RequestParam(required = false) LocalDate to) {
        return sets.report(from, to);
    }
}
