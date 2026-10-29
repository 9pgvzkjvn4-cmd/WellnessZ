package com.wellnessz.e01.trigger;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseLink;
import com.wellnessz.casekernel.model.CaseLinkRepository;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.FitnessStatus;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.model.OpenCaseCommand;
import com.wellnessz.casekernel.model.Urgency;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.masterdata.ConfigService;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.databind.json.JsonMapper;

/**
 * System triggers (E01-F04): absence thresholds per subsidiary, abnormal surveillance results, crew decrease-in-fitness
 * declarations and positive / refused D&amp;A tests open (or flag) proactive cases. Each trigger is idempotent on its
 * source reference and logged in the trigger log.
 */
@Service
public class TriggerService {

    static final Set<String> FITNESS_REASONS = Set.of("ILLNESS", "INJURY", "MEDICATION", "FATIGUE", "PREGNANCY", "OTHER");

    private final AbsenceEpisodeRepository absences;
    private final TriggerEventRepository triggers;
    private final CaseService cases;
    private final OhCaseRepository caseRepo;
    private final CaseLinkRepository links;
    private final EmployeeRepository employees;
    private final ConfigService config;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;

    public TriggerService(AbsenceEpisodeRepository absences, TriggerEventRepository triggers, CaseService cases,
                          OhCaseRepository caseRepo, CaseLinkRepository links, EmployeeRepository employees, ConfigService config,
                          AuditService audit, JsonMapper json, Clock clock) {
        this.absences = absences;
        this.triggers = triggers;
        this.cases = cases;
        this.caseRepo = caseRepo;
        this.links = links;
        this.employees = employees;
        this.config = config;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public record TriggerResult(String outcome, String caseId, String caseRef) {
    }

    // ------------------------------------------------------------- absence (E01-BL-014)

    @Transactional
    public TriggerResult absence(String sourceRef, String employeeId, LocalDate start, LocalDate end, String reasonCategory) {
        if (end != null && end.isBefore(start)) {
            throw ApiException.badRequest("invalid_dates", "endDate before startDate");
        }
        AbsenceEpisode ep = absences.findBySourceRef(sourceRef)
                .map(a -> {
                    a.update(end);
                    return a;
                })
                .orElseGet(() -> absences.save(new AbsenceEpisode(sourceRef, employeeId, start, end, reasonCategory, clock.instant())));
        LocalDate today = LocalDate.now(clock);
        String sub = employees.findById(employeeId).map(e -> e.getSubsidiaryCode()).orElse("DEFAULT");
        int consecutive = threshold("intake.absence." + sub + ".consecutive-days", "intake.absence.default.consecutive-days", 3);
        int episodes = threshold("intake.absence." + sub + ".episodes-in-90-days", "intake.absence.default.episodes-in-90-days", 3);
        List<AbsenceEpisode> recent = absences.findByEmployeeIdAndStartDateGreaterThanEqualOrderByStartDate(employeeId, today.minusDays(90));
        boolean crossed = ep.days(today) > consecutive || recent.size() >= episodes;
        if (!crossed) {
            triggers.save(new TriggerEvent(TriggerEvent.Type.ABSENCE, sourceRef, employeeId, reasonCategory,
                    TriggerEvent.Outcome.BELOW_THRESHOLD, null, clock.instant()));
            return new TriggerResult("BELOW_THRESHOLD", null, null);
        }
        // one open early-intervention watch case per employee
        var open = caseRepo.findByEmployeeIdOrderByCreatedAtDesc(employeeId).stream()
                .filter(c -> c.getCaseType() == CaseType.ABSENCE && c.isOpen()).findFirst();
        OhCase c = open.orElseGet(() -> cases.open(OpenCaseCommand.builder(employeeId, CaseCategory.OTHER, CaseSource.ABSENCE_TRIGGER)
                .caseType(CaseType.ABSENCE).sourceRef("absence:" + employeeId + ":" + today).earlyIntervention(true)
                .urgency(Urgency.ROUTINE)
                .intakeDetailsJson(json.writeValueAsString(Map.of("absenceHistory", recent.stream()
                        .map(a -> Map.of("start", a.getStartDate().toString(), "end", String.valueOf(a.getEndDate()),
                                "reasonCategory", String.valueOf(a.getReasonCategory()))).toList(),
                        "thresholds", Map.of("consecutiveDays", consecutive, "episodesIn90Days", episodes))))
                .build()));
        links.save(new CaseLink(c.getId(), CaseLink.Type.ABSENCE, sourceRef, "absence-feed", clock.instant()));
        triggers.save(new TriggerEvent(TriggerEvent.Type.ABSENCE, sourceRef, employeeId, reasonCategory,
                open.isPresent() ? TriggerEvent.Outcome.CASE_EXISTS : TriggerEvent.Outcome.CASE_CREATED, c.getId(), clock.instant()));
        return new TriggerResult(open.isPresent() ? "CASE_EXISTS" : "CASE_CREATED", c.getId().toString(), c.getCaseRef());
    }

    // ------------------------------------------------------------- surveillance (E01-BL-015)

    @Transactional
    public TriggerResult surveillance(String sourceRef, String employeeId, String testCode, boolean abnormal) {
        if (!abnormal) {
            triggers.save(new TriggerEvent(TriggerEvent.Type.SURVEILLANCE_RESULT, sourceRef, employeeId, testCode,
                    TriggerEvent.Outcome.NOT_ABNORMAL, null, clock.instant()));
            return new TriggerResult("NOT_ABNORMAL", null, null);
        }
        OhCase c = cases.openIdempotent(OpenCaseCommand.builder(employeeId, CaseCategory.EXPOSURE, CaseSource.SURVEILLANCE_TRIGGER)
                .caseType(CaseType.SURVEILLANCE_FOLLOW_UP).sourceRef(sourceRef).urgency(Urgency.ROUTINE)
                .intakeDetailsJson(json.writeValueAsString(Map.of("testCode", testCode, "abnormal", true))).build());
        links.save(new CaseLink(c.getId(), CaseLink.Type.SURVEILLANCE, sourceRef, "surveillance-feed", clock.instant()));
        triggers.save(new TriggerEvent(TriggerEvent.Type.SURVEILLANCE_RESULT, sourceRef, employeeId, testCode,
                TriggerEvent.Outcome.CASE_CREATED, c.getId(), clock.instant()));
        return new TriggerResult("CASE_CREATED", c.getId().toString(), c.getCaseRef());
    }

    // ------------------------------------------------------------- crew decrease in fitness (E01-BL-016)

    @Transactional
    public TriggerResult fitnessDeclaration(CurrentUser user, String reason, LocalDate unfitFrom, LocalDate expectedUntil, String details) {
        if (user.employeeId() == null || !user.hasRole(Roles.EMPLOYEE)) {
            throw ApiException.forbidden("Declarations are made by the employee");
        }
        if (!FITNESS_REASONS.contains(reason)) {
            throw ApiException.badRequest("invalid_reason", "Unknown declaration reason");
        }
        String sourceRef = "declaration:" + user.employeeId() + ":" + (unfitFrom == null ? LocalDate.now(clock) : unfitFrom);
        OhCase c = cases.openIdempotent(OpenCaseCommand.builder(user.employeeId(), CaseCategory.DECREASE_IN_FITNESS,
                        CaseSource.FITNESS_DECLARATION)
                .employeeName(user.displayName()).caseType(CaseType.FITNESS).sourceRef(sourceRef).urgency(Urgency.URGENT)
                .occurredOn(unfitFrom).initialFitness(FitnessStatus.UNFIT_PENDING)
                .clinicalSummary(details)
                .intakeDetailsJson(json.writeValueAsString(Map.of("reason", reason,
                        "expectedUntil", expectedUntil == null ? "" : expectedUntil.toString())))
                .build());
        cases.publishFitness(c); // crew scheduling receives "unfit – pending" only
        triggers.save(new TriggerEvent(TriggerEvent.Type.FITNESS_DECLARATION, sourceRef, user.employeeId(), reason,
                TriggerEvent.Outcome.CASE_CREATED, c.getId(), clock.instant()));
        audit.record("FITNESS_DECLARATION", "CASE", c.getId().toString(), AuditService.Outcome.SUCCESS, null);
        return new TriggerResult("CASE_CREATED", c.getId().toString(), c.getCaseRef());
    }

    // ------------------------------------------------------------- D&A (E01-HLR-04)

    @Transactional
    public TriggerResult daTest(String sourceRef, String employeeId, String result) {
        if (!Set.of("POSITIVE", "REFUSED", "NEGATIVE").contains(result)) {
            throw ApiException.badRequest("invalid_result", "Unknown D&A result");
        }
        if ("NEGATIVE".equals(result)) {
            triggers.save(new TriggerEvent(TriggerEvent.Type.DA_TEST, sourceRef, employeeId, null, TriggerEvent.Outcome.NOT_ABNORMAL,
                    null, clock.instant()));
            return new TriggerResult("NOT_ABNORMAL", null, null);
        }
        OhCase c = cases.openIdempotent(OpenCaseCommand.builder(employeeId, CaseCategory.SUBSTANCE, CaseSource.DA_TEST)
                .caseType(CaseType.SUBSTANCE).sourceRef(sourceRef).urgency(Urgency.URGENT)
                .initialFitness(FitnessStatus.UNDER_REVIEW).build());
        // detail code deliberately generic: the result itself is restricted-class data
        triggers.save(new TriggerEvent(TriggerEvent.Type.DA_TEST, sourceRef, employeeId, "FOLLOW_UP", TriggerEvent.Outcome.CASE_CREATED,
                c.getId(), clock.instant()));
        return new TriggerResult("CASE_CREATED", c.getId().toString(), c.getCaseRef());
    }

    public List<TriggerEvent> log(java.time.Instant from, java.time.Instant to) {
        return triggers.findByReceivedAtBetweenOrderByReceivedAtDesc(from, to);
    }

    private int threshold(String key, String fallbackKey, int defaultValue) {
        return config.get(key).or(() -> config.get(fallbackKey)).map(v -> {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }
}
