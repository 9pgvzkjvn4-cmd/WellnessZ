package com.wellnessz.e02.web;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.casekernel.model.CaseEnums.WaitingState;
import com.wellnessz.casekernel.model.CaseTask;
import com.wellnessz.casekernel.model.CaseTaskRepository;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseWorkService;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e02.model.AssignmentRule;
import com.wellnessz.e02.model.AssignmentRuleRepository;
import com.wellnessz.e02.model.Handover;
import com.wellnessz.e02.model.SavedView;
import com.wellnessz.e02.model.SavedViewRepository;
import com.wellnessz.e02.model.SlaPolicy;
import com.wellnessz.e02.model.SlaPolicyRepository;
import com.wellnessz.e02.queue.QueueService;
import com.wellnessz.e02.routing.AssignmentEngine;
import com.wellnessz.e02.routing.AssignmentService;
import com.wellnessz.e02.sla.SlaService;
import com.wellnessz.masterdata.StaffMember;
import com.wellnessz.masterdata.StaffMemberRepository;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;
import com.wellnessz.security.identity.CurrentUserProvider;

/** Work queues, SLA board, assignment, caseload, staff availability and handover endpoints (E02-F03/F04). */
@RestController
@RequestMapping("/api/v1")
public class WorkController {

    static final String OH = TriageController.OH;
    static final String RULE_ADMIN = "hasAnyRole('NURSING_HEAD','OH_ADMIN')";

    private final QueueService queues;
    private final AssignmentService assignments;
    private final AssignmentEngine engine;
    private final AssignmentRuleRepository rules;
    private final SlaPolicyRepository policies;
    private final SavedViewRepository views;
    private final StaffMemberRepository staff;
    private final OhCaseRepository cases;
    private final CaseTaskRepository tasks;
    private final CaseAccess access;
    private final SlaService sla;
    private final JdbcTemplate jdbc;
    private final CurrentUserProvider users;
    private final AuditService audit;
    private final Clock clock;

    public WorkController(QueueService queues, AssignmentService assignments, AssignmentEngine engine, AssignmentRuleRepository rules,
                          SlaPolicyRepository policies, SavedViewRepository views, StaffMemberRepository staff, OhCaseRepository cases,
                          CaseTaskRepository tasks, CaseAccess access, SlaService sla, JdbcTemplate jdbc, CurrentUserProvider users,
                          AuditService audit, Clock clock) {
        this.queues = queues;
        this.assignments = assignments;
        this.engine = engine;
        this.rules = rules;
        this.policies = policies;
        this.views = views;
        this.staff = staff;
        this.cases = cases;
        this.tasks = tasks;
        this.access = access;
        this.sla = sla;
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------- queues

    @GetMapping("/queues/counts")
    public Map<String, Long> counts() {
        return queues.counts(users.require());
    }

    @GetMapping("/queues/{queue}")
    public QueueService.Page queue(@PathVariable @Pattern(regexp = "MY|TEAM|UNASSIGNED|OVERDUE|AWAITING_EXTERNAL|AWAITING_EMPLOYEE|ESCALATED") String queue,
                                   @RequestParam(defaultValue = "0") @Min(0) int page,
                                   @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
                                   @RequestParam(required = false) @Pattern(regexp = "EMERGENCY|URGENT|SEMI_URGENT|ROUTINE|ADVICE") String urgency,
                                   @RequestParam(required = false) @Pattern(regexp = "[A-Z0-9]{1,20}") String subsidiary,
                                   @RequestParam(required = false) @Pattern(regexp = "[A-Z_]{1,40}") String caseType,
                                   @RequestParam(required = false) Boolean safetyCritical,
                                   @RequestParam(required = false) @Size(max = 100) String q) {
        return queues.queue(users.require(), queue, new QueueService.Filters(urgency, subsidiary, caseType, safetyCritical, q), page, size);
    }

    public record ViewRequest(@NotBlank @Size(max = 100) String name, @NotBlank @Pattern(regexp = "[A-Z_]{1,30}") String queue,
                              Map<String, String> filters) {
    }

    public record SavedViewDto(UUID id, String name, String queue, Map<String, String> filters) {
        static SavedViewDto of(SavedView v) {
            Map<String, String> f = new java.util.LinkedHashMap<>();
            for (String kv : v.getFilters().split("&")) {
                int i = kv.indexOf('=');
                if (i > 0) {
                    f.put(kv.substring(0, i), kv.substring(i + 1));
                }
            }
            return new SavedViewDto(v.getId(), v.getName(), v.getQueue(), f);
        }
    }

    public record RuleDto(Long id, String name, int priority, boolean active, String subsidiaryCode, String siteCode, String language,
                          String caseType, String safetyCategory, String specialty, String targetQueue, String strategy,
                          String targetUsername, String updatedBy, Instant updatedAt) {
        static RuleDto of(AssignmentRule r) {
            return new RuleDto(r.getId(), r.getName(), r.getPriority(), r.isActive(), r.getSubsidiaryCode(), r.getSiteCode(),
                    r.getLanguage(), r.getCaseType(), r.getSafetyCategory(), r.getSpecialty(), r.getTargetQueue(), r.getStrategy(),
                    r.getTargetUsername(), r.getUpdatedBy(), r.getUpdatedAt());
        }
    }

    public record PolicyDto(Long id, String caseType, String urgency, String milestone, int targetMinutes, int amberPercent,
                            String escalateToRole) {
        static PolicyDto of(SlaPolicy p) {
            return new PolicyDto(p.getId(), p.getCaseType(), p.getUrgency(), p.getMilestone(), p.getTargetMinutes(),
                    p.getAmberPercent(), p.getEscalateToRole());
        }
    }

    @GetMapping("/queues/views")
    @PreAuthorize(OH)
    public List<SavedViewDto> savedViews() {
        return views.findByUsernameOrderByName(users.require().username()).stream().map(SavedViewDto::of).toList();
    }

    @PostMapping("/queues/views")
    @PreAuthorize(OH)
    @ResponseStatus(HttpStatus.CREATED)
    public SavedViewDto saveView(@Valid @RequestBody ViewRequest r) {
        Map<String, String> f = r.filters() == null ? Map.of() : r.filters();
        if (f.size() > 10 || !f.keySet().stream().allMatch(k -> List.of("urgency", "subsidiary", "caseType", "safetyCritical", "q").contains(k))) {
            throw ApiException.badRequest("invalid_filters", "Unknown filter keys");
        }
        SavedView v = new SavedView();
        v.setId(UUID.randomUUID());
        v.setUsername(users.require().username());
        v.setName(r.name());
        v.setQueue(r.queue());
        v.setFilters(f.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&")));
        return SavedViewDto.of(views.save(v));
    }

    @DeleteMapping("/queues/views/{id}")
    @PreAuthorize(OH)
    @Transactional
    public Map<String, String> deleteView(@PathVariable UUID id) {
        SavedView v = views.findById(id).filter(x -> x.getUsername().equals(users.require().username()))
                .orElseThrow(() -> ApiException.notFound("View"));
        views.delete(v);
        return Map.of("status", "DELETED");
    }

    public record BulkRequest(@NotBlank @Pattern(regexp = "REASSIGN|REMIND|CLOSE_DUPLICATES") String action,
                              @NotNull @Size(min = 1, max = 200) List<UUID> caseIds, @Size(max = 200) String toUsername) {
    }

    @PostMapping("/queues/bulk")
    public QueueService.BulkResult bulk(@Valid @RequestBody BulkRequest r) {
        return queues.bulk(users.require(), r.action(), r.caseIds(), r.toUsername());
    }

    public record WaitingRequest(@NotNull WaitingState waitingState, @Size(max = 500) String note) {
    }

    @PostMapping("/cases/{id}/waiting-state")
    public Map<String, String> waiting(@PathVariable UUID id, @Valid @RequestBody WaitingRequest r) {
        queues.waitingState(users.require(), id, r.waitingState(), r.note());
        return Map.of("waitingState", r.waitingState().name());
    }

    // ------------------------------------------------------------- case header (E02-BL-018)

    public record Header(String caseRef, String status, String urgency, SlaService.SlaView sla, boolean safetyCritical,
                         String safetyCategory, List<String> badges, List<CaseWorkService.TaskView> openTasks, String nextAction,
                         String owner, String ownerUsername, String queueCode, String intensity, Integer complexityScore) {
    }

    @GetMapping("/cases/{id}/header")
    @Transactional(readOnly = true)
    public Header header(@PathVariable UUID id) {
        CurrentUser user = users.require();
        OhCase c = access.clinical(user, id, AccessPolicy.Action.READ, "CASE_HEADER_READ");
        List<CaseWorkService.TaskView> open = tasks.findByCaseIdAndStatus(id, CaseTask.Status.OPEN).stream()
                .map(CaseWorkService.TaskView::of).toList();
        List<String> badges = new ArrayList<>();
        if (c.isSafetyCritical()) {
            badges.add("SAFETY_CRITICAL:" + c.getSafetyCategory());
        }
        if (c.isDuplicateSuspect()) {
            badges.add("DUPLICATE_SUSPECT");
        }
        if (c.isEarlyIntervention()) {
            badges.add("EARLY_INTERVENTION");
        }
        if (c.isEscalated()) {
            badges.add("ESCALATED");
        }
        if (c.isHrisOverride()) {
            badges.add("HRIS_REVIEW");
        }
        if (c.getBodyPart() != null && cases.countByEmployeeAndBodyPartSince(c.getEmployeeId(), c.getBodyPart(),
                clock.instant().minusSeconds(365L * 86400)) >= 3) {
            badges.add("REPEAT_BODY_PART");
        }
        if (c.getIntensity() == com.wellnessz.casekernel.model.CaseEnums.Intensity.INTENSIVE) {
            badges.add("INTENSIVE");
        }
        String owner = c.getOwnerUsername() == null ? null
                : staff.findById(c.getOwnerUsername()).map(StaffMember::getDisplayName).orElse(c.getOwnerUsername());
        return new Header(c.getCaseRef(), c.getStatus().name(), c.getUrgency().name(), sla.view(id), c.isSafetyCritical(),
                c.getSafetyCategory() == null ? null : c.getSafetyCategory().name(), badges, open, nextAction(c, open), owner,
                c.getOwnerUsername(), c.getQueueCode(), c.getIntensity().name(), c.getComplexityScore());
    }

    static String nextAction(OhCase c, List<CaseWorkService.TaskView> open) {
        if (c.getOwnerUsername() == null) {
            return "Assign a case owner";
        }
        if (c.getStatus() == com.wellnessz.casekernel.model.CaseStatus.INTAKE) {
            return c.getRecordability() == com.wellnessz.casekernel.model.CaseEnums.Recordability.NOT_CLASSIFIED
                    && List.of("INJURY", "ILLNESS", "EXPOSURE").contains(c.getCaseType().name())
                    ? "Triage and classify recordability" : "Complete triage";
        }
        if (!open.isEmpty()) {
            return open.getFirst().title();
        }
        if (c.isSafetyCritical() && List.of("NOT_ASSESSED", "UNFIT_PENDING", "UNDER_REVIEW").contains(c.getFitnessStatus().name())) {
            return "Record a fitness-for-duty decision";
        }
        return "Continue case management";
    }

    // ------------------------------------------------------------- assignment & caseload

    public record AssignRequest(@Size(max = 200) String toUsername, @Pattern(regexp = "[A-Z_]{1,40}") String queueCode,
                                @Size(max = 500) String reason) {
    }

    public record AssignmentView(Instant at, String fromUsername, String toUsername, String queueCode, String reason, String actor) {
    }

    @PostMapping("/cases/{id}/assign")
    public AssignmentView assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest r) {
        var a = assignments.assign(users.require(), id, r.toUsername(), r.queueCode(), r.reason());
        return new AssignmentView(a.getAssignedAt(), a.getFromUsername(), a.getToUsername(), a.getQueueCode(), a.getReason(), a.getActor());
    }

    @GetMapping("/cases/{id}/assignments")
    public List<AssignmentView> assignmentHistory(@PathVariable UUID id) {
        return assignments.history(users.require(), id).stream().map(a -> new AssignmentView(a.getAssignedAt(), a.getFromUsername(),
                a.getToUsername(), a.getQueueCode(), a.getReason(), a.getActor())).toList();
    }

    @GetMapping("/assignment/caseload")
    @PreAuthorize(OH)
    public List<AssignmentService.CaseloadRow> caseload() {
        Map<String, Long> urgent = jdbc.queryForList("""
                select owner_username u, count(*) n from casemgmt.oh_case where owner_username is not null
                   and status not in ('CLOSED','MERGED') and urgency in ('EMERGENCY','URGENT') group by owner_username""")
                .stream().collect(Collectors.toMap(m -> (String) m.get("u"), m -> ((Number) m.get("n")).longValue()));
        return assignments.caseload(urgent);
    }

    public record StaffView(String username, String displayName, List<String> roles, List<String> sites, List<String> languages,
                            List<String> specialties, int caseloadCap, LocalDate outOfOfficeUntil, String coverUsername, boolean active) {
        static StaffView of(StaffMember s) {
            return new StaffView(s.getUsername(), s.getDisplayName(), StaffMember.list(s.getRoles()), StaffMember.list(s.getSiteCodes()),
                    StaffMember.list(s.getLanguages()), StaffMember.list(s.getSpecialties()), s.getCaseloadCap(), s.getOutOfOfficeUntil(),
                    s.getCoverUsername(), s.isActive());
        }
    }

    @GetMapping("/staff")
    @PreAuthorize(OH)
    public List<StaffView> staff() {
        return staff.findByActiveTrueOrderByDisplayName().stream().map(StaffView::of).toList();
    }

    public record AvailabilityRequest(LocalDate outOfOfficeUntil, @Size(max = 200) String coverUsername) {
    }

    @PutMapping("/staff/{username}/availability")
    @PreAuthorize(OH)
    public StaffView availability(@PathVariable @Size(max = 200) String username, @RequestBody AvailabilityRequest r) {
        return StaffView.of(assignments.availability(users.require(), username, r.outOfOfficeUntil(), r.coverUsername()));
    }

    public record RuleRequest(@NotBlank @Size(max = 200) String name, @Min(0) @Max(10000) int priority, Boolean active,
                              @Pattern(regexp = "[A-Z0-9]{1,20}") String subsidiaryCode, @Size(max = 40) String siteCode,
                              @Pattern(regexp = "en|ar|fr") String language, @Pattern(regexp = "[A-Z_]{1,40}") String caseType,
                              @Pattern(regexp = "[A-Z_]{1,40}") String safetyCategory, @Size(max = 60) String specialty,
                              @NotBlank @Pattern(regexp = "[A-Z_]{1,40}") String targetQueue,
                              @NotBlank @Pattern(regexp = "NONE|ROUND_ROBIN|LEAST_LOADED|SPECIFIC_USER") String strategy,
                              @Size(max = 200) String targetUsername) {
    }

    @GetMapping("/assignment/rules")
    @PreAuthorize(OH + " or hasRole('OH_ADMIN')")
    public List<RuleDto> rules() {
        return rules.findAllByOrderByPriorityAscIdAsc().stream().map(RuleDto::of).toList();
    }

    @PostMapping("/assignment/rules")
    @PreAuthorize(RULE_ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public RuleDto createRule(@Valid @RequestBody RuleRequest r) {
        return RuleDto.of(saveRule(new AssignmentRule(), r));
    }

    @PutMapping("/assignment/rules/{id}")
    @PreAuthorize(RULE_ADMIN)
    @Transactional
    public RuleDto updateRule(@PathVariable long id, @Valid @RequestBody RuleRequest r) {
        return RuleDto.of(saveRule(rules.findById(id).orElseThrow(() -> ApiException.notFound("Rule")), r));
    }

    private AssignmentRule saveRule(AssignmentRule rule, RuleRequest r) {
        if (engine.queue(r.targetQueue()).isEmpty()) {
            throw ApiException.badRequest("unknown_queue", "Unknown target queue");
        }
        if ("SPECIFIC_USER".equals(r.strategy()) && (r.targetUsername() == null || staff.findById(r.targetUsername()).isEmpty())) {
            throw ApiException.badRequest("unknown_user", "SPECIFIC_USER requires a valid target user");
        }
        rule.setName(r.name());
        rule.setPriority(r.priority());
        rule.setActive(r.active() == null || r.active());
        rule.setSubsidiaryCode(r.subsidiaryCode());
        rule.setSiteCode(r.siteCode());
        rule.setLanguage(r.language());
        rule.setCaseType(r.caseType());
        rule.setSafetyCategory(r.safetyCategory());
        rule.setSpecialty(r.specialty());
        rule.setTargetQueue(r.targetQueue());
        rule.setStrategy(r.strategy());
        rule.setTargetUsername(r.targetUsername());
        rule.setUpdatedBy(users.actorOrSystem());
        rule.setUpdatedAt(clock.instant());
        AssignmentRule saved = rules.save(rule);
        audit.record("ASSIGNMENT_RULE_SAVE", "ASSIGNMENT_RULE", String.valueOf(saved.getId()), AuditService.Outcome.SUCCESS, r.name());
        return saved;
    }

    public record RuleTestRequest(@NotNull UUID caseId) {
    }

    @PostMapping("/assignment/rules/test")
    @PreAuthorize(RULE_ADMIN)
    @Transactional(readOnly = true)
    public AssignmentEngine.Decision testRule(@Valid @RequestBody RuleTestRequest r) {
        OhCase c = access.clinical(users.require(), r.caseId(), AccessPolicy.Action.READ, "ASSIGNMENT_RULE_TEST");
        return engine.decide(c);
    }

    public record PolicyRequest(@Min(0) @Max(100000) int targetMinutes, @Min(1) @Max(100) int amberPercent,
                                @Pattern(regexp = "[A-Z_]{1,40}") String escalateToRole) {
    }

    @GetMapping("/sla/policies")
    @PreAuthorize(OH + " or hasRole('OH_ADMIN')")
    public List<PolicyDto> policies() {
        return policies.findAll().stream().map(PolicyDto::of).toList();
    }

    @PutMapping("/sla/policies/{id}")
    @PreAuthorize(RULE_ADMIN)
    @Transactional
    public PolicyDto updatePolicy(@PathVariable long id, @Valid @RequestBody PolicyRequest r) {
        SlaPolicy p = policies.findById(id).orElseThrow(() -> ApiException.notFound("SLA policy"));
        p.setTargetMinutes(r.targetMinutes());
        p.setAmberPercent(r.amberPercent());
        p.setEscalateToRole(r.escalateToRole());
        audit.record("SLA_POLICY_UPDATE", "SLA_POLICY", String.valueOf(id), AuditService.Outcome.SUCCESS, null);
        return PolicyDto.of(p);
    }

    // ------------------------------------------------------------- handover (E02-BL-019)

    public record HandoverRequest(@NotBlank @Size(max = 200) String toUsername, @NotBlank @Size(max = 4000) String summary,
                                  @Size(max = 4000) String outstandingActions, Instant nextContactAt) {
    }

    public record HandoverView(UUID id, UUID caseId, String caseRef, String fromUsername, String toUsername, String summary,
                               String outstandingActions, Instant nextContactAt, Instant createdAt, Instant acknowledgedAt) {
    }

    private HandoverView view(Handover h) {
        return new HandoverView(h.getId(), h.getCaseId(), cases.findById(h.getCaseId()).map(OhCase::getCaseRef).orElse(null),
                h.getFromUsername(), h.getToUsername(), h.getSummary(), h.getOutstandingActions(), h.getNextContactAt(), h.getCreatedAt(),
                h.getAcknowledgedAt());
    }

    @PostMapping("/cases/{id}/handover")
    @ResponseStatus(HttpStatus.CREATED)
    public HandoverView handover(@PathVariable UUID id, @Valid @RequestBody HandoverRequest r) {
        return view(assignments.handover(users.require(), id, r.toUsername(), r.summary(), r.outstandingActions(), r.nextContactAt()));
    }

    @PostMapping("/handovers/{id}/acknowledge")
    public HandoverView acknowledge(@PathVariable UUID id) {
        return view(assignments.acknowledge(users.require(), id));
    }

    @GetMapping("/handovers/pending")
    @PreAuthorize(OH)
    public List<HandoverView> pending() {
        return assignments.pending(users.require()).stream().map(this::view).toList();
    }

}
