package com.wellnessz.e01.encounter;

import java.time.Instant;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.security.identity.CurrentUserProvider;

@RestController
@RequestMapping("/api/v1/intake/encounters")
public class EncounterController {

    static final String REGISTRARS = "hasAnyRole('CLINIC_RECEPTION','FIRST_AIDER','OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER')";

    private final EncounterService service;
    private final CurrentUserProvider users;

    public EncounterController(EncounterService service, CurrentUserProvider users) {
        this.service = service;
        this.users = users;
    }

    public record CreateRequest(@NotNull UUID clientRef, @NotNull Encounter.Mode mode,
                                @NotBlank @Pattern(regexp = "[A-Za-z0-9\\-]{1,64}") String employeeId, Instant occurredAt,
                                @Size(max = 40) String siteCode, @Size(max = 200) String location,
                                @Size(max = 1000) String chiefComplaint, @Size(max = 1000) String actionTaken) {
    }

    public record EnrichRequest(@Size(max = 1000) String chiefComplaint, @Size(max = 1000) String actionTaken,
                                @Size(max = 1000) String vitals, @Size(max = 4000) String notes) {
    }

    @PostMapping
    @PreAuthorize(REGISTRARS)
    @ResponseStatus(HttpStatus.CREATED)
    public EncounterService.EncounterView create(@Valid @RequestBody CreateRequest r) {
        return service.create(users.require(), new EncounterService.CreateRequest(r.clientRef(), r.mode(), r.employeeId(),
                r.occurredAt(), r.siteCode(), r.location(), r.chiefComplaint(), r.actionTaken()));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(REGISTRARS)
    public EncounterService.EncounterView enrich(@PathVariable UUID id, @Valid @RequestBody EnrichRequest r) {
        String notes = r.vitals() == null ? r.notes() : "Vitals: " + r.vitals() + (r.notes() == null ? "" : "\n" + r.notes());
        return service.enrich(users.require(), id, r.chiefComplaint(), r.actionTaken(), notes);
    }

    @GetMapping
    @PreAuthorize(REGISTRARS)
    public List<EncounterService.EncounterView> list(@RequestParam(required = false) Encounter.Status status,
                                                     @RequestParam(required = false) @Size(max = 40) String site) {
        return service.list(users.require(), status, site);
    }

    @PostMapping("/{id}/convert")
    @PreAuthorize("hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER')")
    public EncounterService.Converted convert(@PathVariable UUID id) {
        return service.convert(users.require(), id);
    }
}
