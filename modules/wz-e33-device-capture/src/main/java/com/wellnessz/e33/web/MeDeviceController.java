package com.wellnessz.e33.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.e33.service.EmployeeDeviceService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** Employee app: released device summaries and wearable consent scopes (contract 14.8). */
@RestController
@RequestMapping("/api/v1/me")
public class MeDeviceController {

    private final EmployeeDeviceService service;
    private final CurrentUserProvider users;

    public MeDeviceController(EmployeeDeviceService service, CurrentUserProvider users) {
        this.service = service;
        this.users = users;
    }

    public record GrantRequest(@NotBlank @Pattern(regexp = "WEARABLE_DATA") String purpose,
                               @NotNull @Size(min = 1, max = 4) List<@Pattern(regexp = "STEPS|SLEEP|HEART_RATE|ACTIVITY") String> scopes,
                               UUID campaignId) {
    }

    @GetMapping("/device-summaries")
    @PreAuthorize(Spel.EMPLOYEE)
    public List<EmployeeDeviceService.SummaryView> summaries() {
        return service.summaries(users.require());
    }

    @GetMapping("/device-consents")
    @PreAuthorize(Spel.EMPLOYEE)
    public List<EmployeeDeviceService.ConsentView> consents() {
        return service.consents(users.require());
    }

    @PostMapping("/device-consents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.EMPLOYEE)
    public EmployeeDeviceService.ConsentView grant(@Valid @RequestBody GrantRequest r) {
        return service.grant(users.require(), new EmployeeDeviceService.GrantCommand(r.purpose(), r.scopes(), r.campaignId()));
    }

    @DeleteMapping("/device-consents/{id}")
    @PreAuthorize(Spel.EMPLOYEE)
    public EmployeeDeviceService.ConsentView revoke(@PathVariable UUID id) {
        return service.revoke(users.require(), id);
    }
}
