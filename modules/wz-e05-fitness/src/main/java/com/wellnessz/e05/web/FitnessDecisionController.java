package com.wellnessz.e05.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

import com.wellnessz.common.model.Roles;
import com.wellnessz.e05.crew.CrewDashboard;
import com.wellnessz.e05.crew.CrewService;
import com.wellnessz.e05.crew.RosterFeedLog;
import com.wellnessz.e05.decision.FitnessDecisionService;
import com.wellnessz.e05.support.RestrictionRef;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E05-F01 fitness decisions / sign-off / duration comparison and E05-F04 crew assessment, roster feed and dashboard. */
@RestController
@RequestMapping("/api/v1")
public class FitnessDecisionController {

    static final String CODE = "[A-Z0-9_]{1,40}";
    static final String OUTCOME = "FIT|FIT_WITH_LIMITATIONS|TEMPORARILY_UNFIT|PERMANENTLY_UNFIT|PENDING";

    private final FitnessDecisionService decisions;
    private final CrewService crew;
    private final RosterFeedLog feed;
    private final CurrentUserProvider users;

    public FitnessDecisionController(FitnessDecisionService decisions, CrewService crew, RosterFeedLog feed, CurrentUserProvider users) {
        this.decisions = decisions;
        this.crew = crew;
        this.feed = feed;
        this.users = users;
    }

    // ------------------------------------------------------------------ E05-F01

    public record FitnessDecisionRequest(@NotBlank @Pattern(regexp = CODE) String standardCode,
                                         @NotBlank @Pattern(regexp = OUTCOME) String outcome,
                                         @Size(max = 15) List<@Pattern(regexp = "[A-Z]{3}") String> limitationCodes,
                                         @Size(max = 20) @Valid List<RestrictionRef.Input> restrictions,
                                         LocalDate validFrom, LocalDate validUntil, LocalDate reviewDate,
                                         @Size(max = 4000) String rationale, @Size(max = 1000) String durationOverrideReason,
                                         UUID encounterId, Boolean generateCertificate) {
    }

    @PostMapping("/cases/{id}/fitness-decisions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OH_PHYSICIAN','AME','CMO','OH_CLINICIAN')")
    public FitnessDecisionService.DecisionView decide(@PathVariable UUID id, @Valid @RequestBody FitnessDecisionRequest r) {
        return decisions.decide(users.require(), id, new FitnessDecisionService.DecisionCommand(r.standardCode(), r.outcome(),
                r.limitationCodes(), r.restrictions(), r.validFrom(), r.validUntil(), r.reviewDate(), r.rationale(),
                r.durationOverrideReason(), r.encounterId(), r.generateCertificate(), null, false));
    }

    @GetMapping("/cases/{id}/fitness-decisions")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<FitnessDecisionService.DecisionView> history(@PathVariable UUID id) {
        return decisions.history(users.require(), id);
    }

    public record SignoffRequest(@NotNull Boolean approve, @Size(max = 2000) String note) {
    }

    @PostMapping("/fitness-decisions/{id}/signoff")
    @PreAuthorize("hasAnyRole('AME','CMO')")
    public FitnessDecisionService.DecisionView signoff(@PathVariable UUID id, @Valid @RequestBody SignoffRequest r) {
        return decisions.signoff(users.require(), id, r.approve(), r.note());
    }

    @GetMapping("/fitness-decisions/awaiting-signoff")
    @PreAuthorize("hasAnyRole('AME','CMO')")
    public List<FitnessDecisionService.DecisionView> awaiting() {
        return decisions.awaitingSignoff(users.require());
    }

    @GetMapping("/cases/{id}/duration-comparison")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public FitnessDecisionService.DurationComparison duration(@PathVariable UUID id) {
        return decisions.durationComparison(users.require(), id);
    }

    @GetMapping("/employees/{employeeId}/fitness")
    @PreAuthorize(Roles.OH_TEAM_SPEL)
    public List<FitnessDecisionService.DecisionView> employee(@PathVariable @Pattern(regexp = "[A-Za-z0-9\\-]{1,64}") String employeeId) {
        return decisions.employeeFitness(users.require(), employeeId);
    }

    // ------------------------------------------------------------------ E05-F04

    public record CrewAssessmentRequest(@NotBlank @Pattern(regexp = "CAA_CLASS1|CAA_CLASS2|CABIN_CREW") String standardCode,
                                        @NotBlank @Pattern(regexp = OUTCOME) String outcome,
                                        @Size(max = 15) List<@Pattern(regexp = "[A-Z]{3}") String> limitationCodes,
                                        LocalDate validFrom, LocalDate validUntil, LocalDate reviewDate, LocalDate examinationDate,
                                        @Size(max = 4000) String rationale) {
    }

    @PostMapping("/cases/{id}/crew-assessment")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('AME','CMO')")
    public FitnessDecisionService.DecisionView crewAssessment(@PathVariable UUID id, @Valid @RequestBody CrewAssessmentRequest r) {
        return crew.assess(users.require(), id, new CrewService.CrewAssessment(r.standardCode(), r.outcome(), r.limitationCodes(),
                r.validFrom(), r.validUntil(), r.reviewDate(), r.examinationDate(), r.rationale()));
    }

    @GetMapping("/crew/limitation-codes")
    @PreAuthorize("hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN','CREW_SCHEDULING')")
    public List<CrewService.LimitationCode> limitationCodes() {
        return crew.limitationCodes();
    }

    /**
     * Roster feed. {@code /integration/**} is restricted to INTEGRATION / platform admins by the platform security filter, so
     * crew scheduling reads the same feed through the {@code /crew/roster-feed} alias.
     */
    @GetMapping({"/integration/roster-feed", "/crew/roster-feed"})
    @PreAuthorize("hasAnyRole('INTEGRATION','CREW_SCHEDULING','CMO','OH_ADMIN')")
    public List<RosterFeedLog.FeedMessage> rosterFeed(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                      Instant since) {
        return feed.messages(since);
    }

    @PostMapping("/integration/roster-feed/{messageId}/ack")
    @PreAuthorize("hasRole('INTEGRATION')")
    public RosterFeedLog.FeedMessage ack(@PathVariable @Pattern(regexp = "[A-Za-z0-9\\-]{1,64}") String messageId) {
        return feed.acknowledge(users.require(), messageId);
    }

    @GetMapping({"/integration/roster-feed/reconciliation", "/crew/roster-feed/reconciliation"})
    @PreAuthorize("hasAnyRole('CREW_SCHEDULING','CMO','OH_ADMIN','INTEGRATION')")
    public RosterFeedLog.Reconciliation reconciliation(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return feed.reconciliation(from, to);
    }

    @GetMapping("/crew/dashboard")
    @PreAuthorize("hasRole('CMO')")
    public CrewDashboard.Result dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return feed.dashboard(from, to);
    }
}
