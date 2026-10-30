package com.wellnessz.e11.communicable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.FitnessFeed;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.CaseTask;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.FitnessStatus;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OpenCaseCommand;
import com.wellnessz.casekernel.model.Urgency;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.CaseWorkService;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e05.food.FoodHandlerService;
import com.wellnessz.e05.model.FoodHandlerEpisodeRepository;
import com.wellnessz.e11.model.CdCase;
import com.wellnessz.e11.model.CheckIn;
import com.wellnessz.e11.model.Condition;
import com.wellnessz.e11.model.Contact;
import com.wellnessz.e11.model.E11CdCaseRepository;
import com.wellnessz.e11.model.E11CheckInRepository;
import com.wellnessz.e11.model.E11ConditionRepository;
import com.wellnessz.e11.model.E11ContactRepository;
import com.wellnessz.e11.support.TravelHealthSupport;
import com.wellnessz.masterdata.DutyAssignment;
import com.wellnessz.masterdata.DutyAssignmentRepository;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.notification.Priority;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.disclosure.DisclosureLogService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Communicable-disease case management and contact tracing (E11-F02): a CD case on a kernel ILLNESS case, the exclusion /
 * isolation decision published as status only (fitness feed; food handlers through the E05 food-handler exclusion so the
 * catering hygiene manager sees status and dates only), roster / shift / facility based contact tracing with the 4-hour KPI,
 * content-free contact notification ("OH needs to speak with you" – never the index case or the condition), daily
 * check-ins with symptomatic alerts, and clearance against the return criteria.
 */
@Service
public class CommunicableService {

    public static final String SUSPECTED = "SUSPECTED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String EXCLUDED = "EXCLUDED";
    public static final String ISOLATING = "ISOLATING";
    public static final String CLEARED = "CLEARED";
    static final String CRITERION = "[A-Z0-9_]{2,40}";
    static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final E11ConditionRepository conditions;
    private final E11CdCaseRepository cdCases;
    private final E11ContactRepository contacts;
    private final E11CheckInRepository checkIns;
    private final DutyAssignmentRepository duties;
    private final CaseService caseService;
    private final CaseWorkService caseWork;
    private final CaseTimeline timeline;
    private final FoodHandlerService foodHandlers;
    private final FoodHandlerEpisodeRepository foodEpisodes;
    private final EmployeeRecordAccess records;
    private final DisclosureLogService disclosures;
    private final TravelHealthSupport support;
    private final AuditService audit;

    public CommunicableService(E11ConditionRepository conditions, E11CdCaseRepository cdCases, E11ContactRepository contacts,
                               E11CheckInRepository checkIns, DutyAssignmentRepository duties, CaseService caseService, CaseWorkService caseWork,
                               CaseTimeline timeline, FoodHandlerService foodHandlers, FoodHandlerEpisodeRepository foodEpisodes,
                               EmployeeRecordAccess records, DisclosureLogService disclosures, TravelHealthSupport support, AuditService audit) {
        this.conditions = conditions;
        this.cdCases = cdCases;
        this.contacts = contacts;
        this.checkIns = checkIns;
        this.duties = duties;
        this.caseService = caseService;
        this.caseWork = caseWork;
        this.timeline = timeline;
        this.foodHandlers = foodHandlers;
        this.foodEpisodes = foodEpisodes;
        this.records = records;
        this.disclosures = disclosures;
        this.support = support;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ conditions

    public record ConditionView(String code, String name, boolean notifiable, Integer reportWithinHours, Integer exclusionDays,
                                boolean foodHandlerExclusion, int monitoringDays, List<String> returnCriteria) {
    }

    static ConditionView view(Condition c) {
        return new ConditionView(c.getCode(), c.getName(), c.isNotifiable(), c.getReportWithinHours(), c.getExclusionDays(),
                c.isFoodHandlerExclusion(), c.getMonitoringDays(), TravelHealthSupport.csv(c.getReturnCriteria()));
    }

    @Transactional(readOnly = true)
    public List<ConditionView> conditions() {
        return conditions.findAllByOrderByNameAsc().stream().map(CommunicableService::view).toList();
    }

    public Condition condition(String code) {
        return conditions.findById(code).orElseThrow(() -> ApiException.badRequest("unknown_condition", "Unknown communicable condition"));
    }

    // ------------------------------------------------------------------ cases

    public record CreateCommand(@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId,
                                @NotBlank @Pattern(regexp = "[A-Z0-9_]{2,40}") String conditionCode, @NotNull LocalDate onsetDate, LocalDate confirmedOn) {
    }

    public record ConfirmCommand(LocalDate confirmedOn) {
    }

    public record ExclusionView(String decision, LocalDate from, LocalDate until, List<String> returnCriteria) {
    }

    public record CdCaseView(UUID id, String ref, UUID caseId, String caseRef, String employeeId, String displayName, String conditionCode,
                             String conditionName, LocalDate onsetDate, LocalDate confirmedOn, Instant confirmedAt, String status, boolean notifiable,
                             Instant reportDueAt, boolean foodHandler, ExclusionView exclusion, Instant traceGeneratedAt, Double hoursSinceConfirmation, BigDecimal hoursToTrace,
                             Boolean traceWithinKpi, long contacts, long contactsNotified, Instant clearedAt, List<String> criteriaMet,
                             Instant createdAt, String createdBy) {
    }

    CdCaseView view(CdCase c) {
        Condition cond = conditions.findById(c.getConditionCode()).orElse(null);
        int kpiHours = support.configInt("travelhealth.tracing.kpi-hours", 4);
        ExclusionView ex = c.getExclusionDecision() == null ? null
                : new ExclusionView(c.getExclusionDecision(), c.getExclusionFrom(), c.getExclusionUntil(), TravelHealthSupport.csv(c.getReturnCriteria()));
        return new CdCaseView(c.getId(), c.getCdRef(), c.getCaseId(), c.getCaseRef(), c.getEmployeeId(), support.displayName(c.getEmployeeId()),
                c.getConditionCode(), cond == null ? c.getConditionCode() : cond.getName(), c.getOnsetDate(), c.getConfirmedOn(), c.getConfirmedAt(),
                c.getStatus(), c.isNotifiable(), c.getReportDueAt(), c.isFoodHandler(), ex, c.getTraceGeneratedAt(),
                c.getTraceHours() == null ? null : c.getTraceHours().doubleValue(), c.getTraceHours(),
                c.getTraceHours() == null ? null : c.getTraceHours().compareTo(BigDecimal.valueOf(kpiHours)) <= 0,
                contacts.countByCdCaseId(c.getId()), contacts.countByCdCaseIdAndNotifiedAtIsNotNull(c.getId()), c.getClearedAt(),
                TravelHealthSupport.csv(c.getCriteriaMet()), c.getCreatedAt(), c.getCreatedBy());
    }

    Instant confirmationInstant(LocalDate confirmedOn) {
        if (confirmedOn == null) {
            return null;
        }
        return confirmedOn.equals(support.today()) ? support.now() : confirmedOn.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    @Transactional
    public CdCaseView create(CurrentUser user, CreateCommand cmd) {
        Employee e = records.requireClinical(user, cmd.employeeId(), DataClassification.CLINICAL, AccessPolicy.Action.CREATE, "CD_CASE_CREATE");
        Condition cond = condition(cmd.conditionCode());
        LocalDate today = support.today();
        if (cmd.onsetDate().isAfter(today) || cmd.onsetDate().isBefore(today.minusDays(365))) {
            throw ApiException.badRequest("invalid_onset_date", "The onset date must be within the last year and not in the future");
        }
        if (cmd.confirmedOn() != null && (cmd.confirmedOn().isAfter(today) || cmd.confirmedOn().isBefore(cmd.onsetDate().minusDays(30)))) {
            throw ApiException.badRequest("invalid_confirmed_on", "The confirmation date must not be in the future");
        }
        Instant now = support.now();
        CdCase c = CdCase.create();
        OhCase kernel = caseService.openIdempotent(OpenCaseCommand.builder(e.getEmployeeId(), CaseCategory.ILLNESS, CaseSource.SYSTEM_TRIGGER)
                .caseType(CaseType.ILLNESS).sourceRef("e11-cd:" + c.getId()).occurredOn(cmd.onsetDate())
                .urgency(cond.isNotifiable() ? Urgency.URGENT : Urgency.SEMI_URGENT)
                .clinicalSummary("Communicable disease: " + cond.getName()).build());
        c.setCdRef(support.nextRef("CD"));
        c.setCaseId(kernel.getId());
        c.setCaseRef(kernel.getCaseRef());
        c.setEmployeeId(e.getEmployeeId());
        c.setConditionCode(cond.getCode());
        c.setOnsetDate(cmd.onsetDate());
        c.setConfirmedOn(cmd.confirmedOn());
        c.setConfirmedAt(confirmationInstant(cmd.confirmedOn()));
        c.setStatus(cmd.confirmedOn() == null ? SUSPECTED : CONFIRMED);
        c.setNotifiable(cond.isNotifiable());
        c.setReportDueAt(reportDue(cond, c.getConfirmedAt() == null ? now : c.getConfirmedAt()));
        c.setFoodHandler(support.isFoodHandler(e));
        c.setCreatedBy(user.username());
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        cdCases.save(c);
        timeline.record(kernel.getId(), "COMMUNICABLE_CASE", "Communicable-disease case " + c.getCdRef() + " opened (" + c.getStatus() + ")");
        audit.record("CD_CASE_CREATE", "CD_CASE", c.getId().toString(), AuditService.Outcome.SUCCESS, cond.getCode() + " " + c.getStatus());
        return view(c);
    }

    static Instant reportDue(Condition cond, Instant from) {
        if (!cond.isNotifiable()) {
            return null;
        }
        int hours = cond.getReportWithinHours() == null ? 72 : cond.getReportWithinHours();
        return from.plusSeconds(hours * 3600L);
    }

    /** Clinical access to a CD case: audited 404 for unknown ids and for employees outside the caller's ABAC scope. */
    public CdCase requireCase(CurrentUser user, UUID id, AccessPolicy.Action action, String auditAction) {
        CdCase c = cdCases.findById(id).orElse(null);
        if (c == null) {
            audit.record(auditAction, "CD_CASE", id.toString(), AuditService.Outcome.DENIED, "Unknown case");
            throw ApiException.notFound("Communicable-disease case");
        }
        records.requireClinical(user, c.getEmployeeId(), DataClassification.CLINICAL, action, auditAction);
        return c;
    }

    @Transactional(readOnly = true)
    public CdCaseView get(CurrentUser user, UUID id) {
        CdCase c = requireCase(user, id, AccessPolicy.Action.READ, "CD_CASE_READ");
        audit.record("CD_CASE_READ", "CD_CASE", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(c);
    }

    @Transactional(readOnly = true)
    public List<CdCaseView> list(CurrentUser user, String status) {
        List<CdCase> all = status == null ? cdCases.findTop500ByOrderByCreatedAtDesc() : cdCases.findByStatusOrderByCreatedAtDesc(status);
        Map<String, Employee> people = support.employeesById(all.stream().map(CdCase::getEmployeeId).distinct().toList());
        List<CdCaseView> out = all.stream()
                .filter(c -> people.containsKey(c.getEmployeeId()) && records.canSeeClinical(user, people.get(c.getEmployeeId()), DataClassification.CLINICAL))
                .map(this::view).toList();
        audit.record("CD_CASE_LIST", "CD_CASE", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public CdCaseView confirm(CurrentUser user, UUID id, ConfirmCommand cmd) {
        CdCase c = requireCase(user, id, AccessPolicy.Action.UPDATE, "CD_CASE_CONFIRM");
        if (c.getConfirmedAt() != null) {
            throw ApiException.conflict("already_confirmed", "The case is already confirmed");
        }
        LocalDate on = cmd == null || cmd.confirmedOn() == null ? support.today() : cmd.confirmedOn();
        if (on.isAfter(support.today())) {
            throw ApiException.badRequest("invalid_confirmed_on", "The confirmation date must not be in the future");
        }
        c.setConfirmedOn(on);
        c.setConfirmedAt(confirmationInstant(on));
        if (SUSPECTED.equals(c.getStatus())) {
            c.setStatus(CONFIRMED);
        }
        Instant due = reportDue(condition(c.getConditionCode()), c.getConfirmedAt());
        if (due != null && (c.getReportDueAt() == null || due.isBefore(c.getReportDueAt()))) {
            c.setReportDueAt(due);
        }
        c.setUpdatedAt(support.now());
        timeline.record(c.getCaseId(), "COMMUNICABLE_CASE", "Communicable-disease case " + c.getCdRef() + " confirmed");
        audit.record("CD_CASE_CONFIRM", "CD_CASE", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(c);
    }

    // ------------------------------------------------------------------ exclusion & clearance

    public record ExclusionCommand(@NotBlank @Pattern(regexp = "EXCLUDED|ISOLATING|CLEARED") String decision, @NotNull LocalDate from, LocalDate until,
                                   @Size(max = 10) List<@NotNull @Pattern(regexp = CRITERION) String> returnCriteria) {
    }

    public record ClearCommand(@NotEmpty @Size(max = 10) List<@NotNull @Pattern(regexp = CRITERION) String> criteriaMet) {
    }

    /** E05 food-handler reason category for the condition (OH-only on the episode; hygiene sees status and dates only). */
    static String foodHandlerReason(String conditionCode) {
        return switch (conditionCode) {
            case "NOROVIRUS", "SALMONELLA" -> "GI_ILLNESS";
            case "HEP_A" -> "HEPATITIS_A";
            case "CHICKENPOX" -> "SKIN_INFECTION";
            default -> "OTHER";
        };
    }

    boolean openFoodEpisode(UUID caseId) {
        return foodEpisodes.findByCaseIdOrderByCreatedAtDesc(caseId).stream().anyMatch(ep -> !FoodHandlerService.FIT.equals(ep.getStatus()));
    }

    @Transactional
    public CdCaseView exclusion(CurrentUser user, UUID id, ExclusionCommand cmd) {
        CdCase c = requireCase(user, id, AccessPolicy.Action.UPDATE, "CD_CASE_EXCLUSION");
        Condition cond = condition(c.getConditionCode());
        if (cmd.until() != null && cmd.until().isBefore(cmd.from())) {
            throw ApiException.badRequest("invalid_dates", "until must not be before from");
        }
        if (cmd.from().isBefore(support.today().minusDays(30)) || cmd.from().isAfter(support.today().plusDays(30))) {
            throw ApiException.badRequest("invalid_dates", "from must be within 30 days of today");
        }
        Instant now = support.now();
        if (CLEARED.equals(cmd.decision())) {
            boolean active = (EXCLUDED.equals(c.getStatus()) || ISOLATING.equals(c.getStatus()));
            if (active) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "use_clear",
                        "The employee is excluded – clear the case against the return criteria instead");
            }
            caseService.applyFitnessDecision(user, c.getCaseId(), FitnessStatus.FIT, null, new FitnessFeed(cmd.from(), null, null, List.of()));
            c.setExclusionDecision(CLEARED);
            c.setExclusionFrom(cmd.from());
            c.setExclusionUntil(null);
            c.setStatus(CLEARED);
            c.setClearedAt(now);
            logStatusDisclosure(user, c, "No exclusion required");
        } else {
            List<String> criteria = cmd.returnCriteria() == null || cmd.returnCriteria().isEmpty() ? TravelHealthSupport.csv(cond.getReturnCriteria())
                    : cmd.returnCriteria().stream().distinct().toList();
            LocalDate until = cmd.until() != null ? cmd.until()
                    : cond.getExclusionDays() == null ? null : cmd.from().plusDays(cond.getExclusionDays());
            if (c.isFoodHandler() && cond.isFoodHandlerExclusion()) {
                if (!openFoodEpisode(c.getCaseId())) {
                    // E05 exclusion: fitness TEMPORARILY_UNFIT + status-only feed to catering hygiene
                    foodHandlers.exclude(user, c.getCaseId(), foodHandlerReason(cond.getCode()), cmd.from(), until, Map.of());
                }
            } else {
                caseService.applyFitnessDecision(user, c.getCaseId(), FitnessStatus.TEMPORARILY_UNFIT, until,
                        new FitnessFeed(cmd.from(), until, until, List.of()));
            }
            c.setExclusionDecision(cmd.decision());
            c.setExclusionFrom(cmd.from());
            c.setExclusionUntil(until);
            c.setReturnCriteria(TravelHealthSupport.joinCsv(criteria));
            c.setStatus(cmd.decision());
            c.setClearedAt(null);
            logStatusDisclosure(user, c, "Not available for duty (" + cmd.decision().toLowerCase(Locale.ROOT) + ")");
        }
        c.setUpdatedAt(now);
        timeline.record(c.getCaseId(), "COMMUNICABLE_EXCLUSION", "Exclusion decision: " + cmd.decision() + " from " + cmd.from());
        audit.record("CD_CASE_EXCLUSION", "CD_CASE", id.toString(), AuditService.Outcome.SUCCESS, cmd.decision());
        return view(c);
    }

    /** The fitness feeds disclose duty status only – logged once per decision for HR, rostering / hygiene. */
    void logStatusDisclosure(CurrentUser user, CdCase c, String title) {
        String actor = user.username();
        disclosures.record(c.getEmployeeId(), "HR", "HR systems (status feed)", "FITNESS_STATUS", c.getCaseId().toString(), title,
                "FITNESS_FOR_WORK", null, actor);
        if (c.isFoodHandler()) {
            disclosures.record(c.getEmployeeId(), "HYGIENE_MANAGER", "Catering hygiene (status feed)", "FITNESS_STATUS", c.getCaseId().toString(), title,
                    "FITNESS_FOR_WORK", null, actor);
        }
    }

    @Transactional
    public CdCaseView clear(CurrentUser user, UUID id, ClearCommand cmd) {
        CdCase c = requireCase(user, id, AccessPolicy.Action.UPDATE, "CD_CASE_CLEAR");
        if (CLEARED.equals(c.getStatus())) {
            throw ApiException.conflict("already_cleared", "The case is already cleared");
        }
        Condition cond = condition(c.getConditionCode());
        List<String> required = c.getReturnCriteria() != null ? TravelHealthSupport.csv(c.getReturnCriteria())
                : TravelHealthSupport.csv(cond.getReturnCriteria());
        Set<String> met = new LinkedHashSet<>(cmd.criteriaMet());
        List<String> missing = required.stream().filter(r -> !met.contains(r)).toList();
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "return_criteria_not_met",
                    "Return criteria not yet met: " + String.join(", ", missing));
        }
        LocalDate today = support.today();
        if (openFoodEpisode(c.getCaseId())) {
            // E05 clearance: FIT + FOOD_HANDLER_CLEARANCE document, status-only feed to catering hygiene
            foodHandlers.clear(user, c.getCaseId(), Map.of("symptomFree48h", Boolean.TRUE), null);
        } else {
            caseService.applyFitnessDecision(user, c.getCaseId(), FitnessStatus.FIT, null, new FitnessFeed(today, null, null, List.of()));
        }
        Instant now = support.now();
        c.setStatus(CLEARED);
        c.setClearedAt(now);
        c.setCriteriaMet(TravelHealthSupport.joinCsv(met));
        c.setUpdatedAt(now);
        logStatusDisclosure(user, c, "Available for duty");
        timeline.record(c.getCaseId(), "COMMUNICABLE_CLEARED", "Cleared against the return criteria");
        audit.record("CD_CASE_CLEAR", "CD_CASE", id.toString(), AuditService.Outcome.SUCCESS, String.join(",", met));
        return view(c);
    }

    // ------------------------------------------------------------------ contact tracing

    public record TraceCommand(@NotNull Instant windowFrom, @NotNull Instant windowTo,
                               @NotEmpty @Size(max = 3) List<@NotNull @Pattern(regexp = "SAME_DUTY|PROXIMITY_GROUP|FACILITY") String> include) {
    }

    public record ContactView(UUID id, String employeeId, String displayName, String contactType, String dutyRef, int overlapMinutes,
                              String riskLevel, Instant notifiedAt, LocalDate monitoringUntil, Instant lastCheckIn, boolean symptomatic) {
    }

    public record TraceResult(Instant generatedAt, Double hoursSinceConfirmation, List<ContactView> contacts) {
    }

    static TracingRules.Duty duty(DutyAssignment d) {
        return new TracingRules.Duty(d.getEmployeeId(), d.getSource(), d.getDutyRef(), d.getProximityGroup(), d.getStartsAt(), d.getEndsAt());
    }

    @Transactional
    public TraceResult trace(CurrentUser user, UUID id, TraceCommand cmd) {
        CdCase c = requireCase(user, id, AccessPolicy.Action.UPDATE, "CD_CASE_TRACE");
        Instant now = support.now();
        int maxDays = support.configInt("travelhealth.tracing.max-window-days", 21);
        if (!cmd.windowTo().isAfter(cmd.windowFrom()) || Duration.between(cmd.windowFrom(), cmd.windowTo()).toDays() > maxDays
                || cmd.windowFrom().isAfter(now)) {
            throw ApiException.badRequest("invalid_window", "The tracing window must be positive, start in the past and span at most " + maxDays + " days");
        }
        Set<TracingRules.ContactType> include = EnumSet.noneOf(TracingRules.ContactType.class);
        cmd.include().forEach(t -> include.add(TracingRules.ContactType.valueOf(t)));
        List<DutyAssignment> indexDuties = duties.findForEmployee(c.getEmployeeId(), cmd.windowFrom(), cmd.windowTo());
        Map<UUID, DutyAssignment> candidates = new LinkedHashMap<>();
        List<String> refs = indexDuties.stream().map(DutyAssignment::getDutyRef).distinct().toList();
        List<String> groups = indexDuties.stream().map(DutyAssignment::getProximityGroup).filter(g -> g != null).distinct().toList();
        if (!refs.isEmpty()) {
            duties.findOnDuties(refs, cmd.windowFrom(), cmd.windowTo()).forEach(d -> candidates.putIfAbsent(d.getId(), d));
        }
        if (!groups.isEmpty()) {
            duties.findInProximityGroups(groups, cmd.windowFrom(), cmd.windowTo()).forEach(d -> candidates.putIfAbsent(d.getId(), d));
        }
        int high = support.configInt("travelhealth.tracing.high-risk-minutes", 15);
        List<TracingRules.Found> found = TracingRules.trace(c.getEmployeeId(), indexDuties.stream().map(CommunicableService::duty).toList(),
                candidates.values().stream().map(CommunicableService::duty).toList(), include, cmd.windowFrom(), cmd.windowTo(), high);
        for (TracingRules.Found f : found) {
            Contact contact = contacts.findByCdCaseIdAndEmployeeId(c.getId(), f.employeeId()).orElseGet(() -> {
                Contact n = Contact.create();
                n.setCdCaseId(c.getId());
                n.setEmployeeId(f.employeeId());
                n.setCreatedAt(now);
                return n;
            });
            contact.setContactType(f.contactType().name());
            contact.setDutyRef(f.dutyRef());
            contact.setOverlapMinutes(f.overlapMinutes());
            contact.setRiskLevel(f.risk().name());
            contact.setUpdatedAt(now);
            contacts.save(contact);
        }
        if (c.getTraceGeneratedAt() == null) {
            c.setTraceGeneratedAt(now);
            if (c.getConfirmedAt() != null) {
                c.setTraceHours(hours(c.getConfirmedAt(), now));
            }
        }
        c.setLastTracedAt(now);
        c.setUpdatedAt(now);
        long highCount = found.stream().filter(f -> f.risk() == TracingRules.Risk.HIGH).count();
        timeline.record(c.getCaseId(), "CONTACT_TRACE", "Contact list generated: " + found.size() + " contacts (" + highCount + " high risk)");
        audit.record("CD_CASE_TRACE", "CD_CASE", id.toString(), AuditService.Outcome.SUCCESS, "contacts=" + found.size() + " include=" + include);
        BigDecimal h = c.getConfirmedAt() == null ? null : hours(c.getConfirmedAt(), now);
        return new TraceResult(now, h == null ? null : h.doubleValue(), contactViews(c.getId()));
    }

    static BigDecimal hours(Instant from, Instant to) {
        return BigDecimal.valueOf(Duration.between(from, to).toSeconds()).divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP);
    }

    List<ContactView> contactViews(UUID cdCaseId) {
        List<Contact> list = new ArrayList<>(contacts.findByCdCaseIdOrderByRiskLevelAscOverlapMinutesDesc(cdCaseId));
        list.sort(Comparator.comparing((Contact x) -> TracingRules.Risk.valueOf(x.getRiskLevel())).thenComparing(Contact::getOverlapMinutes,
                Comparator.reverseOrder()).thenComparing(Contact::getEmployeeId));
        Map<String, Employee> people = support.employeesById(list.stream().map(Contact::getEmployeeId).toList());
        return list.stream().map(x -> new ContactView(x.getId(), x.getEmployeeId(),
                people.containsKey(x.getEmployeeId()) ? people.get(x.getEmployeeId()).getDisplayName() : x.getEmployeeId(), x.getContactType(),
                x.getDutyRef(), x.getOverlapMinutes(), x.getRiskLevel(), x.getNotifiedAt(), x.getMonitoringUntil(), x.getLastCheckIn(),
                x.isSymptomatic())).toList();
    }

    @Transactional(readOnly = true)
    public List<ContactView> contacts(CurrentUser user, UUID id) {
        CdCase c = requireCase(user, id, AccessPolicy.Action.READ, "CD_CONTACTS_READ");
        List<ContactView> out = contactViews(c.getId());
        audit.record("CD_CONTACTS_READ", "CD_CASE", id.toString(), AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    public record NotifyCommand(@NotEmpty @Size(max = 500) List<@NotNull UUID> contactIds) {
    }

    public record NotifyResult(int notified, int alreadyNotified, int checkInsScheduled) {
    }

    /** Content-free {@code tracing.contact-notice} and the daily check-ins for the condition's monitoring days. */
    @Transactional
    public NotifyResult notifyContacts(CurrentUser user, UUID id, NotifyCommand cmd) {
        CdCase c = requireCase(user, id, AccessPolicy.Action.UPDATE, "CD_CONTACTS_NOTIFY");
        Condition cond = condition(c.getConditionCode());
        Instant now = support.now();
        LocalDate today = support.today();
        int notified = 0;
        int already = 0;
        int scheduled = 0;
        for (UUID contactId : new LinkedHashSet<>(cmd.contactIds())) {
            Contact x = contacts.findById(contactId).filter(k -> k.getCdCaseId().equals(c.getId()))
                    .orElseThrow(() -> ApiException.badRequest("unknown_contact", "Contact " + contactId + " does not belong to this case"));
            if (x.getNotifiedAt() != null) {
                already++;
                continue;
            }
            x.setNotifiedAt(now);
            x.setMonitoringUntil(MonitoringSchedule.monitoringUntil(today, cond.getMonitoringDays()));
            x.setUpdatedAt(now);
            for (LocalDate day : MonitoringSchedule.checkInDays(today, cond.getMonitoringDays())) {
                if (!checkIns.existsByContactIdAndDueOn(x.getId(), day)) {
                    CheckIn ci = CheckIn.create();
                    ci.setContactId(x.getId());
                    ci.setEmployeeId(x.getEmployeeId());
                    ci.setDueOn(day);
                    ci.setStatus("PENDING");
                    checkIns.save(ci);
                    scheduled++;
                }
            }
            // never the index case, never the condition – only that OH needs to speak with them
            support.notifyEmployee(x.getEmployeeId(), "tracing.contact-notice", Map.of(), "/check-ins");
            notified++;
        }
        timeline.record(c.getCaseId(), "CONTACTS_NOTIFIED", notified + " contacts notified");
        audit.record("CD_CONTACTS_NOTIFY", "CD_CASE", id.toString(), AuditService.Outcome.SUCCESS, "notified=" + notified + " already=" + already);
        return new NotifyResult(notified, already, scheduled);
    }

    // ------------------------------------------------------------------ employee check-ins

    public record MyCheckIn(UUID id, LocalDate dueOn, String status, Instant submittedAt, List<String> symptoms, BigDecimal temperatureC) {
    }

    public record CheckInCommand(@NotEmpty @Size(max = 7) List<@NotNull @Pattern(regexp = "FEVER|COUGH|VOMITING|DIARRHOEA|RASH|SORE_THROAT|NONE") String> symptoms,
                                 @DecimalMin("30.0") @DecimalMax("45.0") BigDecimal temperatureC) {
    }

    static MyCheckIn mine(CheckIn c) {
        return new MyCheckIn(c.getId(), c.getDueOn(), c.getStatus(), c.getSubmittedAt(), TravelHealthSupport.csv(c.getSymptoms()), c.getTemperatureC());
    }

    @Transactional(readOnly = true)
    public List<MyCheckIn> myCheckIns(CurrentUser user) {
        LocalDate today = support.today();
        return checkIns.findByEmployeeIdAndDueOnBetweenOrderByDueOnDesc(user.employeeId(), today.minusDays(30), today).stream()
                .map(CommunicableService::mine).toList();
    }

    @Transactional
    public MyCheckIn submitCheckIn(CurrentUser user, UUID id, CheckInCommand cmd) {
        CheckIn ci = checkIns.findById(id).filter(c -> c.getEmployeeId().equals(user.employeeId()))
                .orElseThrow(() -> ApiException.notFound("Check-in"));
        if (ci.getDueOn().isAfter(support.today())) {
            throw ApiException.conflict("not_due", "This check-in is not due yet");
        }
        if ("DONE".equals(ci.getStatus())) {
            throw ApiException.conflict("already_submitted", "This check-in has already been completed");
        }
        if (!MonitoringSchedule.validSymptoms(cmd.symptoms())) {
            throw ApiException.badRequest("invalid_symptoms", "Choose symptoms, or NONE on its own");
        }
        BigDecimal threshold = support.configDecimal("travelhealth.check-in.fever-threshold-c", new BigDecimal("38.0"));
        boolean symptomatic = MonitoringSchedule.symptomatic(cmd.symptoms(), cmd.temperatureC(), threshold);
        Instant now = support.now();
        ci.setStatus("DONE");
        ci.setSymptoms(String.join(",", new LinkedHashSet<>(cmd.symptoms())));
        ci.setTemperatureC(cmd.temperatureC());
        ci.setSymptomatic(symptomatic);
        ci.setSubmittedAt(now);
        Contact contact = contacts.findById(ci.getContactId()).orElseThrow();
        contact.setLastCheckIn(now);
        contact.setUpdatedAt(now);
        if (symptomatic) {
            contact.setSymptomatic(true);
            CdCase c = cdCases.findById(contact.getCdCaseId()).orElseThrow();
            caseWork.systemTask(c.getCaseId(), CaseTask.Type.CALL_BACK, "Symptomatic contact – call back today", now.plusSeconds(2 * 3600L),
                    c.getCreatedBy(), "system");
            support.notifyOwnerOrRole(c.getCreatedBy(), Roles.OH_CLINICIAN, "exposure.timer-alert",
                    Map.of("caseRef", c.getCaseRef(), "appointmentTime", HHMM.format(now.plusSeconds(2 * 3600L)), "level", "symptomatic contact"),
                    "/communicable/" + c.getId(), Priority.URGENT);
        }
        audit.record("CHECK_IN_SUBMIT", "CHECK_IN", id.toString(), AuditService.Outcome.SUCCESS, symptomatic ? "symptomatic" : "well");
        return mine(ci);
    }

    /** Daily: reminder for today's pending check-ins; earlier pending check-ins become MISSED. */
    @Transactional
    public int checkInReminders(LocalDate today) {
        for (CheckIn missed : checkIns.findByDueOnBeforeAndStatus(today, "PENDING")) {
            missed.setStatus("MISSED");
        }
        int sent = 0;
        Map<String, Boolean> remindedToday = new HashMap<>();
        for (CheckIn ci : checkIns.findByDueOnAndStatus(today, "PENDING")) {
            if (ci.getRemindedAt() != null || remindedToday.putIfAbsent(ci.getEmployeeId(), Boolean.TRUE) != null) {
                continue;
            }
            support.notifyEmployee(ci.getEmployeeId(), "tracing.check-in", Map.of("dueDate", today.toString()), "/check-ins");
            ci.setRemindedAt(support.now());
            sent++;
        }
        return sent;
    }
}
