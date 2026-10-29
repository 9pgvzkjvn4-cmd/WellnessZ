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

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e07.crew.CrewCertificateService;
import com.wellnessz.e07.due.DueService;
import com.wellnessz.e07.immunisation.ImmunisationService;
import com.wellnessz.e07.programme.EnrolmentEngine;
import com.wellnessz.e07.programme.ProgrammeService;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E07-F01 programme & protocol builder and E07-F02 due list, invitations, completion, own and team views. */
@RestController
@RequestMapping("/api/v1")
public class ProgrammeDueController {

    private final ProgrammeService programmes;
    private final DueService due;
    private final CrewCertificateService crew;
    private final ImmunisationService immunisation;
    private final CurrentUserProvider users;
    private final AuditService audit;

    public ProgrammeDueController(ProgrammeService programmes, DueService due, CrewCertificateService crew, ImmunisationService immunisation,
                                  CurrentUserProvider users, AuditService audit) {
        this.programmes = programmes;
        this.due = due;
        this.crew = crew;
        this.immunisation = immunisation;
        this.users = users;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ E07-F01

    @GetMapping("/surveillance/programmes")
    @PreAuthorize(Spel.PROGRAMME_READ)
    public List<ProgrammeService.ProgrammeView> programmes() {
        return programmes.list(users.require());
    }

    @GetMapping("/surveillance/programmes/{code}")
    @PreAuthorize(Spel.PROGRAMME_READ)
    public ProgrammeService.ProgrammeView programme(@PathVariable @Pattern(regexp = "[A-Z0-9_]{2,40}") String code) {
        return programmes.get(users.require(), code);
    }

    @PostMapping("/surveillance/programmes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Spel.PROGRAMME_WRITE)
    public ProgrammeService.ProgrammeView create(@Valid @RequestBody ProgrammeService.ProgrammeCommand cmd) {
        return programmes.create(users.require(), cmd);
    }

    @PutMapping("/surveillance/programmes/{code}")
    @PreAuthorize(Spel.PROGRAMME_WRITE)
    public ProgrammeService.ProgrammeView update(@PathVariable @Pattern(regexp = "[A-Z0-9_]{2,40}") String code,
                                                 @Valid @RequestBody ProgrammeService.ProgrammeCommand cmd) {
        return programmes.update(users.require(), code, cmd);
    }

    public record SimulateRequest(@Min(1) @Max(1000) Integer version) {
    }

    @PostMapping("/surveillance/programmes/{code}/simulate")
    @PreAuthorize(Spel.PROGRAMME_WRITE)
    public ProgrammeService.Simulation simulate(@PathVariable @Pattern(regexp = "[A-Z0-9_]{2,40}") String code,
                                                @Valid @RequestBody(required = false) SimulateRequest r) {
        return programmes.simulate(users.require(), code, r == null ? null : r.version());
    }

    public record ActivateRequest(LocalDate effectiveFrom) {
    }

    @PostMapping("/surveillance/programmes/{code}/activate")
    @PreAuthorize("hasAnyRole('CMO','OH_PHYSICIAN')")
    public ProgrammeService.ProgrammeView activate(@PathVariable @Pattern(regexp = "[A-Z0-9_]{2,40}") String code,
                                                   @RequestBody(required = false) ActivateRequest r) {
        return programmes.activate(users.require(), code, r == null ? null : r.effectiveFrom());
    }

    @PostMapping("/surveillance/programmes/{code}/retire")
    @PreAuthorize("hasRole('CMO')")
    public ProgrammeService.ProgrammeView retire(@PathVariable @Pattern(regexp = "[A-Z0-9_]{2,40}") String code) {
        return programmes.retire(users.require(), code);
    }

    // ------------------------------------------------------------------ E07-F02 due list

    @GetMapping("/surveillance/due")
    @PreAuthorize(Spel.DUE_READ)
    public List<DueService.EnrolmentView> due(@RequestParam(required = false) @Pattern(regexp = "[A-Z0-9_]{2,40}") String programme,
                                              @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9_\\-]{1,20}") String subsidiary,
                                              @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String department,
                                              @RequestParam(required = false) @Pattern(regexp = "CURRENT|DUE|OVERDUE|SCHEDULED|EXEMPT") String status,
                                              @RequestParam(required = false) LocalDate dueBefore,
                                              @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId) {
        return due.due(users.require(), programme, subsidiary, department, status, dueBefore, employeeId);
    }

    @PostMapping("/surveillance/due/recalculate")
    @PreAuthorize("hasRole('OH_ADMIN')")
    public EnrolmentEngine.RecalcResult recalculate() {
        return due.recalculate(users.require());
    }

    public record InviteRequest(@NotEmpty @Size(max = 5000) List<@NotNull UUID> enrolmentIds, Boolean book,
                                @Pattern(regexp = "[A-Za-z0-9_\\-]{1,40}") String siteCode, UUID campaignId) {
    }

    @PostMapping("/surveillance/invitations")
    @PreAuthorize(Spel.DUE_READ)
    public DueService.InviteResult invite(@Valid @RequestBody InviteRequest r) {
        return due.invite(users.require(), r.enrolmentIds(), Boolean.TRUE.equals(r.book()), r.siteCode(), r.campaignId());
    }

    @GetMapping("/surveillance/invitations")
    @PreAuthorize(Spel.DUE_READ)
    public List<DueService.InvitationView> invitations(@RequestParam UUID enrolmentId) {
        return due.invitations(users.require(), enrolmentId);
    }

    public record CompleteRequest(@NotNull LocalDate performedOn, @NotBlank @Pattern(regexp = "FIT|FIT_WITH_LIMITATIONS|UNFIT|REFER") String outcome,
                                  UUID encounterId, @Size(max = 50) List<@NotNull UUID> resultIds) {
    }

    @PostMapping("/surveillance/enrolments/{id}/complete")
    @PreAuthorize(Spel.CLINICIANS)
    public DueService.EnrolmentView complete(@PathVariable UUID id, @Valid @RequestBody CompleteRequest r) {
        return due.complete(users.require(), id, new DueService.CompleteCommand(r.performedOn(), r.outcome(), r.encounterId(), r.resultIds()));
    }

    public record ExemptRequest(@NotBlank @Size(max = 1000) String reason, LocalDate until) {
    }

    @PostMapping("/surveillance/enrolments/{id}/exempt")
    @PreAuthorize(Spel.PHYSICIANS)
    public DueService.EnrolmentView exempt(@PathVariable UUID id, @Valid @RequestBody ExemptRequest r) {
        return due.exempt(users.require(), id, r.reason(), r.until());
    }

    // ------------------------------------------------------------------ own & team views

    public record MySurveillance(List<DueService.MyProgramme> programmes, CrewCertificateService.MyCertificate crewCertificate,
                                 List<ImmunisationService.VaccineStatus> immunisations) {
    }

    @GetMapping("/me/surveillance")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public MySurveillance mine() {
        CurrentUser user = users.require();
        if (user.employeeId() == null) {
            throw ApiException.forbidden("No employee number on the session");
        }
        MySurveillance out = new MySurveillance(due.mine(user.employeeId()), crew.mine(user.employeeId()).orElse(null),
                immunisation.mine(user.employeeId()));
        audit.record("SURVEILLANCE_SELF_READ", "EMPLOYEE", user.employeeId(), AuditService.Outcome.SUCCESS, null);
        return out;
    }

    @GetMapping("/workspace/surveillance/compliance")
    @PreAuthorize("hasAnyRole('MANAGER','HR')")
    public List<DueService.TeamRow> team() {
        return due.team(users.require());
    }
}
