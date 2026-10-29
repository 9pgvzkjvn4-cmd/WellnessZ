package com.wellnessz.e04.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
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

import com.wellnessz.e04.outcome.OutcomeService;
import com.wellnessz.e04.telehealth.TelehealthService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E04-F06 outcomes and E04-F07 telehealth (OH and employee sides; the partner side is in the partner portal API). */
@RestController
@RequestMapping("/api/v1")
public class OutcomeTelehealthController {

    private final OutcomeService outcomes;
    private final TelehealthService telehealth;
    private final CurrentUserProvider users;

    public OutcomeTelehealthController(OutcomeService outcomes, TelehealthService telehealth, CurrentUserProvider users) {
        this.outcomes = outcomes;
        this.telehealth = telehealth;
        this.users = users;
    }

    // ------------------------------------------------------------------ outcomes

    @PostMapping("/cases/{caseId}/outcomes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLINICIANS)
    public OutcomeService.OutcomeView record(@PathVariable UUID caseId, @Valid @RequestBody OutcomeService.OutcomeCommand cmd) {
        return outcomes.record(users.require(), caseId, cmd);
    }

    @GetMapping("/cases/{caseId}/outcomes")
    @PreAuthorize(Spel.OH_TEAM)
    public OutcomeService.OutcomeList outcomes(@PathVariable UUID caseId) {
        return outcomes.list(users.require(), caseId);
    }

    // ------------------------------------------------------------------ telehealth (OH)

    @PostMapping("/telehealth/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLINICIANS)
    public TelehealthService.SessionView create(@Valid @RequestBody TelehealthService.SessionCommand cmd) {
        return telehealth.create(users.require(), cmd);
    }

    @GetMapping("/telehealth/sessions")
    @PreAuthorize(Spel.OH_TEAM)
    public List<TelehealthService.SessionView> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                    @RequestParam(required = false) @Pattern(regexp = "SCHEDULED|IN_PROGRESS|COMPLETED|CANCELLED|NO_SHOW") String status) {
        return telehealth.list(users.require(), from, to, status);
    }

    @PostMapping("/telehealth/sessions/{id}/join")
    @PreAuthorize(Spel.OH_TEAM)
    public TelehealthService.Join join(@PathVariable UUID id) {
        return telehealth.join(users.require(), id, TelehealthService.Participant.CLINICIAN);
    }

    public record MessageRequest(@NotBlank @Size(max = 2000) String text) {
    }

    @GetMapping("/telehealth/sessions/{id}/messages")
    @PreAuthorize(Spel.OH_TEAM)
    public List<TelehealthService.MessageView> messages(@PathVariable UUID id) {
        return telehealth.messages(users.require(), id, TelehealthService.Participant.CLINICIAN);
    }

    @PostMapping("/telehealth/sessions/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.OH_TEAM)
    public TelehealthService.MessageView post(@PathVariable UUID id, @Valid @RequestBody MessageRequest r) {
        return telehealth.post(users.require(), id, TelehealthService.Participant.CLINICIAN, r.text());
    }

    public record CompleteRequest(@NotBlank @Size(max = 20000) String note, LocalDate followUpDate) {
    }

    @PostMapping("/telehealth/sessions/{id}/complete")
    @PreAuthorize(Spel.CLINICIANS)
    public TelehealthService.SessionView complete(@PathVariable UUID id, @Valid @RequestBody CompleteRequest r) {
        return telehealth.complete(users.require(), id, r.note(), r.followUpDate());
    }

    public record CancelRequest(@NotBlank @Size(max = 2000) String reason) {
    }

    @PostMapping("/telehealth/sessions/{id}/cancel")
    @PreAuthorize(Spel.CLINICIANS)
    public TelehealthService.SessionView cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest r) {
        return telehealth.cancel(users.require(), id, r.reason());
    }

    // ------------------------------------------------------------------ telehealth (employee)

    @GetMapping("/me/telehealth")
    @PreAuthorize(Spel.EMPLOYEE)
    public List<TelehealthService.SessionView> mine() {
        return telehealth.mine(users.require());
    }

    @PostMapping("/me/telehealth/{id}/consent")
    @PreAuthorize(Spel.EMPLOYEE)
    public TelehealthService.SessionView consent(@PathVariable UUID id) {
        return telehealth.consent(users.require(), id);
    }

    @PostMapping("/me/telehealth/{id}/join")
    @PreAuthorize(Spel.EMPLOYEE)
    public TelehealthService.Join joinMine(@PathVariable UUID id) {
        return telehealth.join(users.require(), id, TelehealthService.Participant.EMPLOYEE);
    }

    @GetMapping("/me/telehealth/{id}/messages")
    @PreAuthorize(Spel.EMPLOYEE)
    public List<TelehealthService.MessageView> myMessages(@PathVariable UUID id) {
        return telehealth.messages(users.require(), id, TelehealthService.Participant.EMPLOYEE);
    }

    @PostMapping("/me/telehealth/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.EMPLOYEE)
    public TelehealthService.MessageView postMine(@PathVariable UUID id, @Valid @RequestBody MessageRequest r) {
        return telehealth.post(users.require(), id, TelehealthService.Participant.EMPLOYEE, r.text());
    }
}
