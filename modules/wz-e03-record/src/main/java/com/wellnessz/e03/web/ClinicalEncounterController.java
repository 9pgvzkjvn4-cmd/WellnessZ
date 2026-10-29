package com.wellnessz.e03.web;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e03.ai.AiAssistantService;
import com.wellnessz.e03.encounter.ClinicalEncounterService;
import com.wellnessz.e03.encounter.EncounterTemplates;
import com.wellnessz.security.identity.CurrentUserProvider;

/** ClinicalEncounter templates, documentation lifecycle, body map, photos and the AI documentation assistant (E03-F02, E03-F07). */
@RestController
@RequestMapping("/api/v1")
public class ClinicalEncounterController {

    static final String TYPE = "INJURY|ILLNESS|FITNESS_ASSESSMENT|SURVEILLANCE_EXAM|MENTAL_HEALTH|TRAVEL|CREW_AEROMEDICAL|FOOD_HANDLER";

    private final ClinicalEncounterService encounters;
    private final EncounterTemplates templates;
    private final AiAssistantService ai;
    private final CurrentUserProvider users;

    public ClinicalEncounterController(ClinicalEncounterService encounters, EncounterTemplates templates, AiAssistantService ai, CurrentUserProvider users) {
        this.encounters = encounters;
        this.templates = templates;
        this.ai = ai;
        this.users = users;
    }

    // ------------------------------------------------------------------ templates & pick-lists

    @GetMapping("/encounter-templates")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<EncounterTemplates.EncounterTemplate> templates() {
        return templates.list();
    }

    public record ApproveRequest(@Min(1) Integer version) {
    }

    @PostMapping("/encounter-templates/{type}/approve")
    @PreAuthorize("hasRole('CMO')")
    public EncounterTemplates.EncounterTemplate approve(@PathVariable @Pattern(regexp = TYPE) String type,
                                                       @Valid @RequestBody(required = false) ApproveRequest r) {
        return templates.approve(users.require(), type, r == null ? null : r.version());
    }

    @GetMapping("/body-map/mechanisms")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<ClinicalEncounterService.Mechanism> mechanisms() {
        return ClinicalEncounterService.MECHANISMS;
    }

    // ------------------------------------------------------------------ encounters

    @PostMapping("/employees/{employeeId}/encounters")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    @ResponseStatus(HttpStatus.CREATED)
    public ClinicalEncounterService.EncounterView create(@PathVariable @Pattern(regexp = ChartController.EMPLOYEE_ID) String employeeId,
                                                 @Valid @RequestBody ClinicalEncounterService.CreateRequest r) {
        return encounters.create(users.require(), employeeId, r);
    }

    /** Restricted encounters the caller may not open are left out; their number is returned in {@code X-Restricted-Items-Hidden}. */
    @GetMapping("/employees/{employeeId}/encounters")
    public ResponseEntity<List<ClinicalEncounterService.EncounterSummary>> list(
            @PathVariable @Pattern(regexp = ChartController.EMPLOYEE_ID) String employeeId) {
        ClinicalEncounterService.Listing l = encounters.list(users.require(), employeeId);
        return ResponseEntity.ok().header("X-Restricted-Items-Hidden", String.valueOf(l.restrictedItemsHidden())).body(l.items());
    }

    @GetMapping("/encounters/unsigned")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    public List<ClinicalEncounterService.EncounterSummary> unsigned(@RequestParam(defaultValue = "true") boolean mine) {
        return encounters.unsigned(users.require(), mine);
    }

    @GetMapping("/encounters/{id}")
    public ClinicalEncounterService.EncounterView get(@PathVariable UUID id) {
        return encounters.get(users.require(), id);
    }

    @PutMapping("/encounters/{id}")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    public ClinicalEncounterService.EncounterView update(@PathVariable UUID id, @Valid @RequestBody ClinicalEncounterService.UpdateRequest r) {
        return encounters.update(users.require(), id, r);
    }

    @PostMapping("/encounters/{id}/sign")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    public ClinicalEncounterService.EncounterView sign(@PathVariable UUID id) {
        return encounters.sign(users.require(), id);
    }

    @PostMapping("/encounters/{id}/addenda")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    @ResponseStatus(HttpStatus.CREATED)
    public ClinicalEncounterService.EncounterView addendum(@PathVariable UUID id, @Valid @RequestBody ClinicalEncounterService.AddendumRequest r) {
        return encounters.addAddendum(users.require(), id, r);
    }

    @PostMapping("/encounters/{id}/entered-in-error")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    public ClinicalEncounterService.EncounterView enteredInError(@PathVariable UUID id, @Valid @RequestBody ClinicalEncounterService.ErrorRequest r) {
        return encounters.enteredInError(users.require(), id, r);
    }

    @PostMapping(value = "/encounters/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    @ResponseStatus(HttpStatus.CREATED)
    public ClinicalEncounterService.PhotoResult photo(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                                              @RequestParam(defaultValue = "false") boolean consent,
                                              @RequestParam(required = false) @Pattern(regexp = "[A-Z_]{1,40}") String bodyRegion)
            throws IOException {
        if (file.isEmpty()) {
            throw ApiException.badRequest("empty_file", "A JPEG or PNG photo is required");
        }
        return encounters.addPhoto(users.require(), id, file.getBytes(), consent, bodyRegion);
    }

    // ------------------------------------------------------------------ AI documentation assistant (consent-gated)

    @PostMapping("/encounters/{id}/ai-consent")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    @ResponseStatus(HttpStatus.CREATED)
    public AiAssistantService.ConsentView aiConsent(@PathVariable UUID id, @Valid @RequestBody AiAssistantService.ConsentRequest r) {
        return ai.consent(users.require(), id, r);
    }

    @PostMapping("/encounters/{id}/ai-draft")
    @PreAuthorize(Roles.CLINICIANS_SPEL)
    public AiAssistantService.DraftView aiDraft(@PathVariable UUID id, @Valid @RequestBody AiAssistantService.DraftRequest r) {
        return ai.draft(users.require(), id, r);
    }
}
