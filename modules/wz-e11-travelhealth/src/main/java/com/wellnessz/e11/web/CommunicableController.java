package com.wellnessz.e11.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

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

import com.wellnessz.e11.communicable.CommunicableService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E11-F02 communicable-disease cases, exclusion, contact tracing, monitoring and the employee's check-ins. */
@RestController("e11CommunicableController")
@RequestMapping("/api/v1")
public class CommunicableController {

    private final CommunicableService communicable;
    private final CurrentUserProvider users;

    public CommunicableController(CommunicableService communicable, CurrentUserProvider users) {
        this.communicable = communicable;
        this.users = users;
    }

    @GetMapping("/communicable/conditions")
    @PreAuthorize(Spel.OH)
    public List<CommunicableService.ConditionView> conditions() {
        return communicable.conditions();
    }

    @PostMapping("/communicable/cases")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLINICIANS)
    public CommunicableService.CdCaseView create(@Valid @RequestBody CommunicableService.CreateCommand cmd) {
        return communicable.create(users.require(), cmd);
    }

    @GetMapping("/communicable/cases")
    @PreAuthorize(Spel.CLINICIANS)
    public List<CommunicableService.CdCaseView> list(
            @RequestParam(required = false) @Pattern(regexp = "SUSPECTED|CONFIRMED|EXCLUDED|ISOLATING|CLEARED") String status) {
        return communicable.list(users.require(), status);
    }

    @GetMapping("/communicable/cases/{id}")
    @PreAuthorize(Spel.CLINICIANS)
    public CommunicableService.CdCaseView get(@PathVariable UUID id) {
        return communicable.get(users.require(), id);
    }

    @PostMapping("/communicable/cases/{id}/confirm")
    @PreAuthorize(Spel.CLINICIANS)
    public CommunicableService.CdCaseView confirm(@PathVariable UUID id, @RequestBody(required = false) CommunicableService.ConfirmCommand cmd) {
        return communicable.confirm(users.require(), id, cmd);
    }

    @PostMapping("/communicable/cases/{id}/exclusion")
    @PreAuthorize(Spel.CLINICIANS)
    public CommunicableService.CdCaseView exclusion(@PathVariable UUID id, @Valid @RequestBody CommunicableService.ExclusionCommand cmd) {
        return communicable.exclusion(users.require(), id, cmd);
    }

    @PostMapping("/communicable/cases/{id}/trace")
    @PreAuthorize(Spel.CLINICIANS)
    public CommunicableService.TraceResult trace(@PathVariable UUID id, @Valid @RequestBody CommunicableService.TraceCommand cmd) {
        return communicable.trace(users.require(), id, cmd);
    }

    @GetMapping("/communicable/cases/{id}/contacts")
    @PreAuthorize(Spel.CLINICIANS)
    public List<CommunicableService.ContactView> contacts(@PathVariable UUID id) {
        return communicable.contacts(users.require(), id);
    }

    @PostMapping("/communicable/cases/{id}/contacts/notify")
    @PreAuthorize(Spel.CLINICIANS)
    public CommunicableService.NotifyResult notifyContacts(@PathVariable UUID id, @Valid @RequestBody CommunicableService.NotifyCommand cmd) {
        return communicable.notifyContacts(users.require(), id, cmd);
    }

    @PostMapping("/communicable/cases/{id}/clear")
    @PreAuthorize(Spel.CLINICIANS)
    public CommunicableService.CdCaseView clear(@PathVariable UUID id, @Valid @RequestBody CommunicableService.ClearCommand cmd) {
        return communicable.clear(users.require(), id, cmd);
    }

    @GetMapping("/me/check-ins")
    @PreAuthorize(Spel.EMPLOYEE)
    public List<CommunicableService.MyCheckIn> myCheckIns() {
        return communicable.myCheckIns(TravelController.employee(users.require()));
    }

    @PostMapping("/me/check-ins/{id}")
    @PreAuthorize(Spel.EMPLOYEE)
    public CommunicableService.MyCheckIn submit(@PathVariable UUID id, @Valid @RequestBody CommunicableService.CheckInCommand cmd) {
        return communicable.submitCheckIn(TravelController.employee(users.require()), id, cmd);
    }
}
