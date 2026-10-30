package com.wellnessz.e09.web;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.model.Roles;
import com.wellnessz.e09.cism.CismService;
import com.wellnessz.e09.model.MentalHealthEnums.CismCategory;
import com.wellnessz.security.identity.CurrentUser;
import com.wellnessz.security.identity.CurrentUserProvider;

/**
 * Critical incident stress management (contract §3.4). Clinicians (PSYCH_CLINICIAN, OH_CLINICIAN) see names; the safety manager
 * creates events and sees counts only.
 */
@RestController("e09CismController")
@RequestMapping("/api/v1/cism/events")
public class CismController {

    private final CismService cism;
    private final CurrentUserProvider users;

    public CismController(CismService cism, CurrentUserProvider users) {
        this.cism = cism;
        this.users = users;
    }

    public record CreateBody(@NotBlank @Size(max = 200) String title, @NotBlank @Pattern(regexp = Patterns.CISM_CATEGORY) String category, @NotNull Instant occurredAt,
                             @Size(max = 200) String location, @Size(max = 20) List<@Pattern(regexp = Patterns.DUTY_REF) String> dutyRefs) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('PSYCH_CLINICIAN','OH_CLINICIAN','SAFETY_MANAGER')")
    public Object create(@Valid @RequestBody CreateBody b) {
        CurrentUser user = users.require();
        CismService.EventDetail d = cism.create(user, new CismService.ManualCommand(b.title(), CismCategory.valueOf(b.category()), b.occurredAt(), b.location(),
                b.dutyRefs()));
        if (clinical(user)) {
            return d;
        }
        return cism.list(user).stream().filter(s -> s.id().equals(d.id())).findFirst().orElseThrow();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PSYCH_CLINICIAN','OH_CLINICIAN','SAFETY_MANAGER')")
    public List<CismService.EventSummary> list() {
        return cism.list(users.require());
    }

    @GetMapping("/{id}")
    @PreAuthorize(Patterns.CISM_CLINICAL)
    public CismService.EventDetail get(@PathVariable UUID id) {
        return cism.get(users.require(), id);
    }

    @PostMapping("/{id}/offers")
    @PreAuthorize(Patterns.CISM_CLINICAL)
    public CismService.OfferResult offers(@PathVariable UUID id) {
        return cism.sendOffers(users.require(), id);
    }

    public record AffectedBody(@Pattern(regexp = "|" + Patterns.CISM_RESPONSE) String response, @Pattern(regexp = "|" + Patterns.SESSION) String sessionType,
                               Instant attendedAt, Boolean followUp1wDone, Boolean followUp4wDone, Boolean referToSupport) {
    }

    @PutMapping("/{id}/affected/{employeeId}")
    @PreAuthorize(Patterns.CISM_CLINICAL)
    public CismService.AffectedView update(@PathVariable UUID id, @PathVariable @Pattern(regexp = Patterns.EMPLOYEE_ID) String employeeId,
                                           @Valid @RequestBody AffectedBody b) {
        return cism.update(users.require(), id, employeeId, new CismService.AffectedUpdate(blank(b.response()), blank(b.sessionType()), b.attendedAt(),
                b.followUp1wDone(), b.followUp4wDone(), b.referToSupport()));
    }

    private static boolean clinical(CurrentUser user) {
        return user.hasAnyRole(Roles.PSYCH_CLINICIAN, Roles.OH_CLINICIAN);
    }

    private static String blank(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
