package com.wellnessz.e07.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.e07.exposure.ExposureService;
import com.wellnessz.e07.followup.FollowUpService;
import com.wellnessz.e07.followup.RaiService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E07-F06 exposure registers & IH linkage, E07-F07 follow-ups / evaluation rules and E07-BL-020 return after illness. */
@RestController
@RequestMapping("/api/v1")
public class ExposureFollowUpController {

    private final ExposureService exposure;
    private final FollowUpService followUps;
    private final RaiService rai;
    private final CurrentUserProvider users;

    public ExposureFollowUpController(ExposureService exposure, FollowUpService followUps, RaiService rai, CurrentUserProvider users) {
        this.exposure = exposure;
        this.followUps = followUps;
        this.rai = rai;
        this.users = users;
    }

    // ------------------------------------------------------------------ E07-F06

    @GetMapping("/surveillance/exposure-groups")
    @PreAuthorize("hasAnyRole(" + Spel.OH + ",'HSE_OFFICER','OH_ADMIN')")
    public List<ExposureService.SegView> segs() {
        return exposure.segs(users.require());
    }

    @PostMapping("/surveillance/exposure-groups")
    @PreAuthorize("hasAnyRole('HSE_OFFICER','OH_ADMIN')")
    public ExposureService.SegView upsertSeg(@Valid @RequestBody ExposureService.SegCommand cmd) {
        return exposure.upsertSeg(users.require(), cmd);
    }

    @PostMapping("/surveillance/ih-measurements/import")
    @PreAuthorize("hasRole('HSE_OFFICER')")
    public ExposureService.ImportResult importMeasurements(@RequestBody @Valid @NotEmpty @Size(max = 5000) List<ExposureService.MeasurementRow> rows) {
        return exposure.importMeasurements(users.require().username(), rows);
    }

    @PostMapping("/integration/ih-measurements")
    @PreAuthorize("hasRole('INTEGRATION')")
    public ExposureService.ImportResult importMeasurementsIntegration(
            @RequestBody @Valid @NotEmpty @Size(max = 5000) List<ExposureService.MeasurementRow> rows) {
        return exposure.importMeasurements(users.require().username(), rows);
    }

    @GetMapping("/surveillance/ih-measurements")
    @PreAuthorize("hasAnyRole(" + Spel.OH + ",'HSE_OFFICER')")
    public List<ExposureService.MeasurementView> measurements(
            @RequestParam(required = false) @Pattern(regexp = "[A-Z0-9][A-Z0-9_\\-]{1,39}") String segCode) {
        return exposure.measurements(users.require(), segCode);
    }

    @PostMapping("/surveillance/cosmic-dose/import")
    @PreAuthorize("hasAnyRole('AME','INTEGRATION')")
    public ExposureService.DoseImportResult importDoses(@RequestBody @Valid @NotEmpty @Size(max = 5000) List<ExposureService.DoseRow> rows) {
        return exposure.importDoses(users.require().username(), rows);
    }

    @PostMapping("/integration/cosmic-dose")
    @PreAuthorize("hasRole('INTEGRATION')")
    public ExposureService.DoseImportResult importDosesIntegration(@RequestBody @Valid @NotEmpty @Size(max = 5000) List<ExposureService.DoseRow> rows) {
        return exposure.importDoses(users.require().username(), rows);
    }

    @GetMapping("/surveillance/cosmic-dose")
    @PreAuthorize("hasAnyRole('AME','CMO')")
    public List<ExposureService.DoseView> doses(@RequestParam(required = false) @Min(2000) @Max(2100) Integer year,
                                                @RequestParam(required = false) Boolean aboveThreshold) {
        return exposure.doses(users.require(), year, aboveThreshold);
    }

    public record DeclarationRequest(LocalDate declaredOn) {
    }

    @PostMapping("/me/pregnancy-declaration")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EMPLOYEE')")
    public ExposureService.DeclarationView declare(@RequestBody(required = false) DeclarationRequest r) {
        return exposure.declarePregnancy(users.require(), r == null ? null : r.declaredOn());
    }

    @GetMapping("/surveillance/employees/{employeeId}/exposure-history")
    @PreAuthorize(Spel.OH_TEAM)
    public ExposureService.ExposureHistory history(@PathVariable @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId) {
        return exposure.history(users.require(), employeeId);
    }

    // ------------------------------------------------------------------ E07-F07

    @GetMapping("/surveillance/follow-ups")
    @PreAuthorize(Spel.OH_TEAM)
    public List<FollowUpService.FollowUpView> followUps(@RequestParam(required = false) @Pattern(regexp = "OPEN|REVIEWED|CLOSED") String status) {
        return followUps.list(users.require(), status);
    }

    public record ReviewRequest(@NotBlank @Pattern(regexp = "CONFIRMED|NOT_CONFIRMED|WORK_RELATED") String decision, @Size(max = 4000) String note,
                                Boolean regulatoryNotificationRequired) {
    }

    @PostMapping("/surveillance/follow-ups/{id}/review")
    @PreAuthorize(Spel.PHYSICIANS)
    public FollowUpService.FollowUpView review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest r) {
        return followUps.review(users.require(), id, r.decision(), r.note(), r.regulatoryNotificationRequired());
    }

    @GetMapping("/surveillance/evaluation-rules")
    @PreAuthorize(Spel.OH_TEAM)
    public List<FollowUpService.RuleView> rules() {
        return followUps.rules();
    }

    @GetMapping("/surveillance/evaluation-rules/{code}")
    @PreAuthorize(Spel.OH_TEAM)
    public FollowUpService.RuleView rule(@PathVariable @Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String code) {
        return followUps.rule(code);
    }

    @PutMapping("/surveillance/evaluation-rules/{code}")
    @PreAuthorize("hasAnyRole('OH_ADMIN','CMO')")
    public FollowUpService.RuleView putRule(@PathVariable @Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String code,
                                            @Valid @RequestBody FollowUpService.RuleCommand cmd) {
        return followUps.putRule(users.require(), code, cmd);
    }

    // ------------------------------------------------------------------ E07-BL-020

    @GetMapping("/surveillance/return-after-illness")
    @PreAuthorize(Spel.OH_TEAM)
    public List<RaiService.RaiView> rai(@RequestParam(required = false) @Pattern(regexp = "REQUIRED|COMPLETED|WAIVED") String status) {
        return rai.list(users.require(), status);
    }

    public record RaiRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId,
                             @NotNull @Pattern(regexp = "LONG_TERM_ABSENCE|SURGERY|CARDIAC_EVENT|OTHER_SERIOUS_ILLNESS") String reason, UUID caseId) {
    }

    @PostMapping("/surveillance/return-after-illness")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLINICIANS)
    public RaiService.RaiView createRai(@Valid @RequestBody RaiRequest r) {
        return rai.create(users.require(), r.employeeId(), r.reason(), r.caseId());
    }

    public record CompleteRaiRequest(UUID encounterId, @Size(max = 4000) String note) {
    }

    @PostMapping("/surveillance/return-after-illness/{id}/complete")
    @PreAuthorize(Spel.CLINICIANS)
    public RaiService.RaiView completeRai(@PathVariable UUID id, @Valid @RequestBody(required = false) CompleteRaiRequest r) {
        return rai.complete(users.require(), id, r == null ? null : r.encounterId(), r == null ? null : r.note());
    }

    public record WaiveRequest(@NotBlank @Size(max = 4000) String reason) {
    }

    @PostMapping("/surveillance/return-after-illness/{id}/waive")
    @PreAuthorize(Spel.PHYSICIANS)
    public RaiService.RaiView waiveRai(@PathVariable UUID id, @Valid @RequestBody WaiveRequest r) {
        return rai.waive(users.require(), id, r.reason());
    }
}
