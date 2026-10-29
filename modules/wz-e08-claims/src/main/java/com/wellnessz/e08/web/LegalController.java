package com.wellnessz.e08.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.e08.legal.LegalService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E08-F06 medico-legal & litigation support: litigation hold, privilege, legal requests and the legal access set. */
@RestController("e08LegalController")
@RequestMapping("/api/v1")
public class LegalController {

    private final LegalService legal;
    private final CurrentUserProvider users;

    public LegalController(LegalService legal, CurrentUserProvider users) {
        this.legal = legal;
        this.users = users;
    }

    public record HoldBody(@Size(max = 1000) String reason) {
    }

    @PostMapping("/claims/{id}/legal-hold")
    @PreAuthorize("hasRole('LEGAL_COUNSEL')")
    public LegalService.HoldView hold(@PathVariable UUID id, @Valid @RequestBody HoldBody b) {
        return legal.legalHold(users.require(), id, true, b.reason());
    }

    @DeleteMapping("/claims/{id}/legal-hold")
    @PreAuthorize("hasRole('LEGAL_COUNSEL')")
    public LegalService.HoldView release(@PathVariable UUID id, @Valid @RequestBody(required = false) HoldBody b) {
        return legal.legalHold(users.require(), id, false, b == null ? null : b.reason());
    }

    public record PrivilegeBody(@NotNull Boolean privileged, @Size(max = 1000) String basis) {
    }

    @PostMapping("/claims/{id}/privilege")
    @PreAuthorize("hasRole('LEGAL_COUNSEL')")
    public LegalService.HoldView privilege(@PathVariable UUID id, @Valid @RequestBody PrivilegeBody b) {
        return legal.privilege(users.require(), id, b.privileged(), b.basis());
    }

    public record RequestBodyIn(@NotBlank @Pattern(regexp = Patterns.LEGAL_TYPE) String type, @NotBlank @Size(max = 4000) String questions,
                                @NotNull LocalDate dueDate, @Pattern(regexp = "|" + Patterns.CODE) String providerCode) {
    }

    @PostMapping("/claims/{id}/legal-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('LEGAL_COUNSEL','CLAIMS_COORDINATOR')")
    public LegalService.LegalRequestView create(@PathVariable UUID id, @Valid @RequestBody RequestBodyIn b) {
        return legal.create(users.require(), id, b.type(), b.questions(), b.dueDate(), b.providerCode());
    }

    @GetMapping("/legal-requests")
    @PreAuthorize("hasAnyRole('LEGAL_COUNSEL','CMO','OH_PHYSICIAN','AME')")
    public List<LegalService.LegalRequestView> list(@RequestParam(required = false) @Pattern(regexp = Patterns.LEGAL_STATUS) String status) {
        return legal.list(users.require(), status);
    }

    @GetMapping("/legal-requests/{id}")
    @PreAuthorize("hasAnyRole('LEGAL_COUNSEL','CMO','OH_PHYSICIAN','AME')")
    public LegalService.LegalRequestView get(@PathVariable UUID id) {
        return legal.get(users.require(), id);
    }

    public record OpinionBody(@NotBlank @Size(max = 4000) String opinion) {
    }

    @PostMapping("/legal-requests/{id}/report")
    @PreAuthorize(Roles.PHYSICIANS_SPEL)
    public LegalService.LegalRequestView report(@PathVariable UUID id, @Valid @RequestBody OpinionBody b) {
        return legal.report(users.require(), id, b.opinion());
    }

    @GetMapping("/legal-requests/{id}/reports/{version}/content")
    @PreAuthorize("hasAnyRole('LEGAL_COUNSEL','CMO','OH_PHYSICIAN','AME')")
    public ResponseEntity<byte[]> content(@PathVariable UUID id, @PathVariable @Min(1) @Max(1000) int version) {
        DocumentService.Content c = legal.content(users.require(), id, version);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(c.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + c.fileName() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(c.bytes());
    }

    @PostMapping("/legal-requests/{id}/close")
    @PreAuthorize("hasRole('LEGAL_COUNSEL')")
    public LegalService.LegalRequestView close(@PathVariable UUID id) {
        return legal.close(users.require(), id);
    }
}
