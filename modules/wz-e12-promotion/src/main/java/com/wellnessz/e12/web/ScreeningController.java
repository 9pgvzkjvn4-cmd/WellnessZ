package com.wellnessz.e12.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

import com.wellnessz.e12.screening.EducationService;
import com.wellnessz.e12.screening.ScreeningService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E12-F02 screening events, participants, results & follow-up; E12-BL-007 education attendance. */
@RestController
@RequestMapping("/api/v1")
public class ScreeningController {

    private final ScreeningService screening;
    private final EducationService education;
    private final CurrentUserProvider users;

    public ScreeningController(ScreeningService screening, EducationService education, CurrentUserProvider users) {
        this.screening = screening;
        this.education = education;
        this.users = users;
    }

    @GetMapping("/promotion/screening-events")
    @PreAuthorize(Spel.SCREENING_EVENTS)
    public List<ScreeningService.EventView> events(@RequestParam(required = false) @Pattern(regexp = ScreeningService.TYPES) String type) {
        return screening.list(users.require(), type);
    }

    @GetMapping("/promotion/screening-events/{id}")
    @PreAuthorize(Spel.SCREENING_EVENTS)
    public ScreeningService.EventView event(@PathVariable UUID id) {
        return screening.get(users.require(), id);
    }

    @PostMapping("/promotion/screening-events")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.SCREENING_EVENTS)
    public ScreeningService.EventView create(@Valid @RequestBody ScreeningService.EventCommand cmd) {
        return screening.create(users.require(), cmd);
    }

    @PostMapping("/promotion/screening-events/{id}/participants")
    @PreAuthorize(Spel.SCREENING_EVENTS)
    public ScreeningService.AddResult participants(@PathVariable UUID id, @Valid @RequestBody ScreeningService.ParticipantsCommand cmd) {
        return screening.addParticipants(users.require(), id, cmd);
    }

    @PostMapping("/promotion/screening-events/{id}/results")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.CLINICIANS)
    public ScreeningService.ResultView result(@PathVariable UUID id, @Valid @RequestBody ScreeningService.ResultCommand cmd) {
        return screening.recordResult(users.require(), id, cmd);
    }

    @PostMapping("/promotion/education-attendance")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.EDUCATION)
    public EducationService.AttendanceView attend(@Valid @RequestBody EducationService.AttendanceCommand cmd) {
        return education.record(users.require(), cmd);
    }

    @GetMapping("/promotion/education-attendance")
    @PreAuthorize(Spel.EDUCATION)
    public List<EducationService.AttendanceView> attendance(@RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId,
                                                            @RequestParam(required = false) @Pattern(regexp = EducationService.PROGRAMMES) String programme) {
        return education.list(users.require(), employeeId, programme);
    }

    @GetMapping("/promotion/education-attendance/report")
    @PreAuthorize(Spel.EDUCATION)
    public EducationService.Report report(@RequestParam(required = false) @Pattern(regexp = EducationService.PROGRAMMES) String programme,
                                          @RequestParam(defaultValue = "12") @Min(1) @Max(36) int months) {
        return education.report(users.require(), programme, months);
    }
}
