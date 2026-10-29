package com.wellnessz.e07.web;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import com.wellnessz.e07.preemployment.PreEmploymentService;
import com.wellnessz.e07.preemployment.Questionnaire;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E07-F03 pre-employment / pre-placement: HRIS offer intake, OH workflow, HR status view and the candidate portal. */
@RestController
@RequestMapping("/api/v1")
public class PreEmploymentController {

    private final PreEmploymentService service;
    private final CurrentUserProvider users;

    public PreEmploymentController(PreEmploymentService service, CurrentUserProvider users) {
        this.service = service;
        this.users = users;
    }

    @PostMapping("/integration/pre-employment")
    @PreAuthorize("hasRole('INTEGRATION')")
    public PreEmploymentService.PreEmploymentView offer(@Valid @RequestBody PreEmploymentService.OfferCommand cmd) {
        return service.create(users.require().username(), cmd);
    }

    @PostMapping("/surveillance/pre-employment")
    @PreAuthorize("hasAnyRole('HR'," + Spel.OH + ")")
    public PreEmploymentService.PreEmploymentView create(@Valid @RequestBody PreEmploymentService.OfferCommand cmd) {
        return service.create(users.require().username(), cmd);
    }

    @GetMapping("/surveillance/pre-employment")
    @PreAuthorize(Spel.OH_TEAM)
    public List<PreEmploymentService.PreEmploymentView> list(
            @RequestParam(required = false) @Pattern(regexp = "INVITED|QUESTIONNAIRE_DONE|BOOKED|EXAMINED|OUTCOME_RECORDED|WITHDRAWN") String status) {
        return service.list(users.require(), status);
    }

    @GetMapping("/surveillance/pre-employment/{id}")
    @PreAuthorize(Spel.OH_TEAM)
    public PreEmploymentService.PreEmploymentView get(@PathVariable UUID id) {
        return service.get(users.require(), id);
    }

    public record OutcomeRequest(@NotBlank @Pattern(regexp = "FIT|FIT_WITH_ADJUSTMENTS|UNFIT") String outcome, @Size(max = 1000) String conditions) {
    }

    @PostMapping("/surveillance/pre-employment/{id}/outcome")
    @PreAuthorize(Spel.CLINICIANS)
    public PreEmploymentService.PreEmploymentView outcome(@PathVariable UUID id, @Valid @RequestBody OutcomeRequest r) {
        return service.outcome(users.require(), id, r.outcome(), r.conditions());
    }

    public record GovRequest(@NotBlank @Pattern(regexp = "VISA|GOVERNMENT|RESIDENCY") String type,
                             @NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-/. ]{1,100}") String reference,
                             @NotBlank @Pattern(regexp = "PENDING|PASSED|FAILED") String status, LocalDate expiresOn) {
    }

    @PostMapping("/surveillance/pre-employment/{id}/government-medical")
    @PreAuthorize(Spel.CLINICIANS)
    public PreEmploymentService.PreEmploymentView government(@PathVariable UUID id, @Valid @RequestBody GovRequest r) {
        return service.governmentMedical(users.require(), id, new PreEmploymentService.GovCommand(r.type(), r.reference(), r.status(), r.expiresOn()));
    }

    @PostMapping("/surveillance/pre-employment/{id}/withdraw")
    @PreAuthorize("hasAnyRole('HR'," + Spel.OH + ")")
    public PreEmploymentService.PreEmploymentView withdraw(@PathVariable UUID id) {
        return service.withdraw(users.require(), id);
    }

    @GetMapping("/workspace/pre-employment")
    @PreAuthorize("hasRole('HR')")
    public List<PreEmploymentService.HrRow> hr() {
        return service.hr(users.require());
    }

    // ------------------------------------------------------------------ candidate portal

    @GetMapping("/me/pre-employment")
    @PreAuthorize("hasRole('CANDIDATE')")
    public PreEmploymentService.CandidateView mine() {
        return service.candidate(users.require());
    }

    @GetMapping("/me/pre-employment/questionnaire")
    @PreAuthorize("hasRole('CANDIDATE')")
    public List<Questionnaire.Question> questions() {
        return Questionnaire.QUESTIONS;
    }

    public record AnswersRequest(@NotNull @Size(max = 30) Map<String, String> answers) {
    }

    @PutMapping("/me/pre-employment/questionnaire")
    @PreAuthorize("hasRole('CANDIDATE')")
    public PreEmploymentService.CandidateView answer(@Valid @RequestBody AnswersRequest r) {
        return service.answer(users.require(), r.answers());
    }

    public record ConsentRequest(@NotNull Boolean consentGiven, @NotEmpty @Size(max = 5) List<@NotBlank String> purposes) {
    }

    @PostMapping("/me/pre-employment/consent")
    @PreAuthorize("hasRole('CANDIDATE')")
    public PreEmploymentService.CandidateView consent(@Valid @RequestBody ConsentRequest r) {
        return service.consent(users.require(), r.consentGiven(), r.purposes());
    }

    @PostMapping(value = "/me/pre-employment/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CANDIDATE')")
    public PreEmploymentService.DocRef upload(@RequestPart("file") MultipartFile file,
                                              @RequestParam(value = "title", required = false) @Size(max = 200) String title) {
        try {
            return service.upload(users.require(), file.getBytes(), title);
        } catch (IOException e) {
            throw ApiException.badRequest("unreadable_file", "The file could not be read");
        }
    }

    public record BookRequest(@Pattern(regexp = "[A-Za-z0-9_\\-]{1,40}") String siteCode, LocalDate earliest) {
    }

    @PostMapping("/me/pre-employment/book")
    @PreAuthorize("hasRole('CANDIDATE')")
    public PreEmploymentService.CandidateView book(@Valid @RequestBody(required = false) BookRequest r) {
        return service.book(users.require(), r == null ? null : r.siteCode(), r == null ? null : r.earliest());
    }
}
