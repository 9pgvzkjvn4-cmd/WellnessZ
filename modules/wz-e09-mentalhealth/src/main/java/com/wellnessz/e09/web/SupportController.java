package com.wellnessz.e09.web;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.common.model.Roles;
import com.wellnessz.e09.casework.EapService;
import com.wellnessz.e09.casework.PathwayService;
import com.wellnessz.e09.casework.SupportCaseService;
import com.wellnessz.e09.casework.SupportViews;
import com.wellnessz.e09.fitness.MhFitnessService;
import com.wellnessz.e09.fitness.ReliefService;
import com.wellnessz.e09.model.MentalHealthEnums.Concern;
import com.wellnessz.e09.model.MentalHealthEnums.FitnessDecision;
import com.wellnessz.e09.model.MentalHealthEnums.Instrument;
import com.wellnessz.e09.model.MentalHealthEnums.Risk;
import com.wellnessz.e09.model.MentalHealthEnums.Source;
import com.wellnessz.e09.model.MentalHealthEnums.UrgentOutcome;
import com.wellnessz.e09.peer.PeerSupportService;
import com.wellnessz.e09.support.SupportAccess;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.identity.CurrentUser;
import com.wellnessz.security.identity.CurrentUserProvider;

/**
 * The restricted support pathway for named mental-health clinicians (contract §3.1, §3.2, §3.3 relief, §3.6). Role checks for
 * the restricted class are made in the services so that every refused attempt is audited DENIED and answered 404 (existence is
 * never revealed); the general-OH placeholder answers counts only.
 */
@RestController("e09SupportController")
@RequestMapping("/api/v1/support")
public class SupportController {

    private final SupportCaseService cases;
    private final PathwayService pathway;
    private final EapService eap;
    private final ReliefService relief;
    private final MhFitnessService fitness;
    private final PeerSupportService peers;
    private final SupportAccess access;
    private final CurrentUserProvider users;

    public SupportController(SupportCaseService cases, PathwayService pathway, EapService eap, ReliefService relief, MhFitnessService fitness,
                             PeerSupportService peers, SupportAccess access, CurrentUserProvider users) {
        this.cases = cases;
        this.pathway = pathway;
        this.eap = eap;
        this.relief = relief;
        this.fitness = fitness;
        this.peers = peers;
        this.access = access;
        this.users = users;
    }

    // ------------------------------------------------------------------ F01 cases

    @GetMapping("/cases")
    @PreAuthorize("isAuthenticated()")
    public List<SupportViews.SupportCaseView> list(@RequestParam(required = false) @Pattern(regexp = Patterns.STATUS) String status,
                                                   @RequestParam(required = false) @Pattern(regexp = Patterns.RISK) String risk) {
        return cases.list(users.require(), status, risk);
    }

    @GetMapping("/cases/{id}")
    @PreAuthorize("isAuthenticated()")
    public SupportViews.SupportCaseDetail get(@PathVariable UUID id) {
        return cases.get(users.require(), id);
    }

    public record CreateBody(@NotBlank @Pattern(regexp = Patterns.EMPLOYEE_ID) String employeeId, @NotBlank @Pattern(regexp = Patterns.CONCERN) String concern,
                             @NotBlank @Pattern(regexp = Patterns.RISK) String risk, @Size(max = 8000) String note,
                             @Pattern(regexp = Patterns.CLINICIAN_SOURCE) String source) {
    }

    @PostMapping("/cases")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public SupportViews.SupportCaseDetail create(@Valid @RequestBody CreateBody b) {
        return cases.create(users.require(), new SupportCaseService.CreateCommand(b.employeeId(), Concern.valueOf(b.concern()), Risk.valueOf(b.risk()),
                b.note(), b.source() == null ? null : Source.valueOf(b.source())));
    }

    public record NoteBody(@NotBlank @Size(max = 8000) String text) {
    }

    @PostMapping("/cases/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public SupportViews.NoteView note(@PathVariable UUID id, @Valid @RequestBody NoteBody b) {
        return cases.addNote(users.require(), id, b.text());
    }

    public record CloseBody(@NotBlank @Size(max = 2000) String reason) {
    }

    @PostMapping("/cases/{id}/close")
    @PreAuthorize("isAuthenticated()")
    public SupportViews.SupportCaseDetail close(@PathVariable UUID id, @Valid @RequestBody CloseBody b) {
        return cases.close(users.require(), id, b.reason());
    }

    /** General-OH placeholder: count of restricted cases only. */
    @GetMapping("/placeholder")
    @PreAuthorize("isAuthenticated()")
    public SupportViews.Placeholder placeholder(@RequestParam @Pattern(regexp = Patterns.EMPLOYEE_ID) String employeeId) {
        CurrentUser user = users.require();
        access.requireRoles(user, "SUPPORT_PLACEHOLDER_READ", employeeId, Roles.OH_TEAM);
        return cases.placeholder(user, employeeId);
    }

    // ------------------------------------------------------------------ F02 screening, urgent risk, EAP, governance

    public record ScreenBody(@NotBlank @Pattern(regexp = Patterns.INSTRUMENT) String instrument, @NotEmpty @Size(max = 9) List<@NotNull @Min(0) @Max(3) Integer> answers) {
    }

    @PostMapping("/cases/{id}/screens")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public SupportViews.ScreenOutcome screen(@PathVariable UUID id, @Valid @RequestBody ScreenBody b) {
        return pathway.clinicianScreen(users.require(), id, Instrument.valueOf(b.instrument()), b.answers());
    }

    @GetMapping("/urgent")
    @PreAuthorize("isAuthenticated()")
    public List<SupportViews.UrgentView> urgent() {
        return pathway.urgentItems(users.require());
    }

    @GetMapping("/protocols/urgent-risk")
    @PreAuthorize("isAuthenticated()")
    public SupportViews.Protocol protocol() {
        return pathway.protocol(users.require());
    }

    public record UrgentContactBody(@NotBlank @Pattern(regexp = Patterns.URGENT_OUTCOME) String outcome, @Size(max = 4000) String note) {
    }

    @PostMapping("/cases/{id}/urgent-contact")
    @PreAuthorize("isAuthenticated()")
    public SupportViews.SupportCaseDetail urgentContact(@PathVariable UUID id, @Valid @RequestBody UrgentContactBody b) {
        return pathway.urgentContact(users.require(), id, UrgentOutcome.valueOf(b.outcome()), b.note());
    }

    public record EapBody(@NotBlank @Pattern(regexp = Patterns.CODE) String partnerCode, @Min(1) @Max(12) int sessionsAuthorised,
                          @NotBlank @Pattern(regexp = Patterns.URGENCY) String urgency, @NotNull UUID consentId) {
    }

    @PostMapping("/cases/{id}/eap-referrals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public EapService.EapReferralView eapReferral(@PathVariable UUID id, @Valid @RequestBody EapBody b) {
        return eap.create(users.require(), id, new EapService.CreateCommand(b.partnerCode(), b.sessionsAuthorised(), b.urgency(), b.consentId()));
    }

    @GetMapping("/cases/{id}/consents")
    @PreAuthorize("isAuthenticated()")
    public List<EapService.ConsentView> consents(@PathVariable UUID id, @RequestParam(required = false) ConsentPurpose purpose) {
        return eap.consents(users.require(), id, purpose);
    }

    @GetMapping("/eap-referrals")
    @PreAuthorize("isAuthenticated()")
    public List<EapService.ClinicianReferralView> eapReferrals(@RequestParam(required = false) @Pattern(regexp = Patterns.EAP_STATUS) String status) {
        return eap.list(users.require(), status);
    }

    @GetMapping("/governance")
    @PreAuthorize("isAuthenticated()")
    public SupportViews.Governance governance() {
        return pathway.governance(users.require());
    }

    // ------------------------------------------------------------------ F03 trusted professional queue & relief from duties

    @GetMapping("/peer-referrals")
    @PreAuthorize("isAuthenticated()")
    public List<PeerSupportService.TrustedReferralView> peerReferrals() {
        return peers.trustedReferrals(users.require());
    }

    @PostMapping("/peer-referrals/{id}/pick-up")
    @PreAuthorize("isAuthenticated()")
    public PeerSupportService.TrustedReferralView pickUp(@PathVariable UUID id) {
        return peers.pickUp(users.require(), id);
    }

    public record ReliefBody(@NotBlank @Pattern(regexp = Patterns.EMPLOYEE_ID) String employeeId, @NotNull LocalDate from, LocalDate expectedUntil) {
    }

    @PostMapping("/relief")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public ReliefService.ReliefView relief(@Valid @RequestBody ReliefBody b) {
        return relief.start(users.require(), b.employeeId(), b.from(), b.expectedUntil());
    }

    @GetMapping("/relief")
    @PreAuthorize("isAuthenticated()")
    public List<ReliefService.ReliefView> reliefs(@RequestParam(required = false) @Pattern(regexp = "ACTIVE|RETURNED") String status) {
        return relief.list(users.require(), status);
    }

    public record ReturnBody(@NotBlank @Pattern(regexp = Patterns.RETURN_DECISION) String decision, LocalDate until) {
    }

    @PostMapping("/relief/{id}/return")
    @PreAuthorize("isAuthenticated()")
    public ReliefService.ReliefView reliefReturn(@PathVariable UUID id, @Valid @RequestBody ReturnBody b) {
        return relief.decide(users.require(), id, b.decision(), b.until());
    }

    // ------------------------------------------------------------------ F06 safety-critical fitness

    public record MedicationBody(@NotBlank @Size(max = 200) String name, @Size(max = 100) String drugClass, @Size(max = 100) String dose,
                                 LocalDate startDate) {
    }

    public record FitnessBody(@NotBlank @Pattern(regexp = Patterns.EMPLOYEE_ID) String employeeId, UUID supportCaseId,
                              @NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String standardCode,
                              @Size(max = 20) List<@Valid MedicationBody> medications, @Size(max = 8000) String findings,
                              @NotBlank @Pattern(regexp = Patterns.DECISION) String decision, @Size(max = 1000) String limitations, @NotNull LocalDate reviewDate,
                              boolean authorityReportRequired) {
    }

    @PostMapping("/fitness-assessments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public MhFitnessService.AssessmentView assess(@Valid @RequestBody FitnessBody b) {
        List<MhFitnessService.Medication> meds = b.medications() == null ? List.of()
                : b.medications().stream().map(m -> new MhFitnessService.Medication(m.name(), m.drugClass(), m.dose(), m.startDate())).toList();
        return fitness.create(users.require(), new MhFitnessService.CreateCommand(b.employeeId(), b.supportCaseId(), b.standardCode(), meds,
                b.findings(), FitnessDecision.valueOf(b.decision()), b.limitations(), b.reviewDate(), b.authorityReportRequired()));
    }

    @GetMapping("/fitness-assessments")
    @PreAuthorize("isAuthenticated()")
    public List<MhFitnessService.AssessmentView> history(@RequestParam @Pattern(regexp = Patterns.EMPLOYEE_ID) String employeeId) {
        return fitness.history(users.require(), employeeId);
    }
}
