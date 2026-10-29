package com.wellnessz.e01.trigger;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.security.identity.CurrentUserProvider;

@RestController
@RequestMapping("/api/v1/intake")
public class TriggerController {

    private static final String ID = "[A-Za-z0-9\\-]{1,64}";
    private static final String REF = "[A-Za-z0-9._:/\\-]{1,100}";

    private final TriggerService service;
    private final CurrentUserProvider users;

    public TriggerController(TriggerService service, CurrentUserProvider users) {
        this.service = service;
        this.users = users;
    }

    public record AbsenceRequest(@NotBlank @Pattern(regexp = REF) String sourceRef, @NotBlank @Pattern(regexp = ID) String employeeId,
                                 @NotNull LocalDate startDate, LocalDate endDate, @Pattern(regexp = "[A-Z_]{1,40}") String reasonCategory) {
    }

    public record SurveillanceRequest(@NotBlank @Pattern(regexp = REF) String sourceRef, @NotBlank @Pattern(regexp = ID) String employeeId,
                                      @NotBlank @Pattern(regexp = "[A-Z_]{1,40}") String testCode, boolean abnormal, Double value,
                                      @Size(max = 20) String unit) {
    }

    public record DeclarationRequest(@NotBlank @Pattern(regexp = "[A-Z]{1,20}") String reason, LocalDate unfitFrom,
                                     LocalDate expectedUntil, @Size(max = 2000) String details) {
    }

    public record DaRequest(@NotBlank @Pattern(regexp = REF) String sourceRef, @NotBlank @Pattern(regexp = ID) String employeeId,
                            @NotBlank @Pattern(regexp = "POSITIVE|REFUSED|NEGATIVE") String result) {
    }

    public record TriggerView(String type, String sourceRef, String employeeId, String detailCode, String outcome, String caseId,
                              Instant receivedAt) {
    }

    @PostMapping("/absence-episodes")
    @PreAuthorize("hasRole('INTEGRATION')")
    public TriggerService.TriggerResult absence(@Valid @RequestBody AbsenceRequest r) {
        return service.absence(r.sourceRef(), r.employeeId(), r.startDate(), r.endDate(), r.reasonCategory());
    }

    @PostMapping("/triggers/surveillance-result")
    @PreAuthorize("hasRole('INTEGRATION')")
    public TriggerService.TriggerResult surveillance(@Valid @RequestBody SurveillanceRequest r) {
        return service.surveillance(r.sourceRef(), r.employeeId(), r.testCode(), r.abnormal());
    }

    @PostMapping("/triggers/fitness-declaration")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public TriggerService.TriggerResult declaration(@Valid @RequestBody DeclarationRequest r) {
        return service.fitnessDeclaration(users.require(), r.reason(), r.unfitFrom(), r.expectedUntil(), r.details());
    }

    @PostMapping("/triggers/da-test")
    @PreAuthorize("hasRole('INTEGRATION')")
    public TriggerService.TriggerResult daTest(@Valid @RequestBody DaRequest r) {
        return service.daTest(r.sourceRef(), r.employeeId(), r.result());
    }

    @GetMapping("/triggers")
    @PreAuthorize("hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER')")
    public List<TriggerView> log(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) TriggerEvent.Type type) {
        Instant end = to == null ? Instant.now().plus(1, ChronoUnit.DAYS) : to.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        Instant start = from == null ? end.minus(31, ChronoUnit.DAYS) : from.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        return service.log(start, end).stream().filter(t -> type == null || t.getType() == type)
                .map(t -> new TriggerView(t.getType().name(), t.getSourceRef(), t.getEmployeeId(), t.getDetailCode(),
                        t.getOutcome().name(), t.getCaseId() == null ? null : t.getCaseId().toString(), t.getReceivedAt()))
                .toList();
    }
}
