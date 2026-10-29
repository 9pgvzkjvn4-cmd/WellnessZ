package com.wellnessz.e02.safety;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.FitnessFeed;
import com.wellnessz.casekernel.model.CaseStatus;
import com.wellnessz.casekernel.model.FitnessStatus;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.model.SafetyCategory;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.CaseTransitionGuard;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e02.model.CaseChecklist;
import com.wellnessz.e02.model.CaseChecklistRepository;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.databind.json.JsonMapper;

/**
 * Safety-critical role controls (E02-F02): role checklists, closure guard (fitness decision + checklist), and the
 * status-only APIs for crew scheduling (E02-BL-017) and the catering hygiene manager (E02-BL-005). Every status read
 * is audited.
 */
@Service
public class SafetyService implements CaseTransitionGuard {

    public static final Map<String, List<String>> CHECKLISTS = Map.of(
            "FOOD_HANDLER", List.of("GI_SYMPTOMS", "SKIN_LESIONS", "INFECTIONS", "EXCLUSION_DECISION"),
            "DRIVING", List.of("VISION", "SEIZURE_HISTORY", "SEDATING_MEDICATION", "FITNESS_TO_DRIVE"),
            "HEIGHT", List.of("VERTIGO", "BALANCE", "FITNESS_FOR_HEIGHT"));
    static final Set<String> OUTCOMES = Set.of("CLEAR", "EXCLUDED", "RESTRICTED");
    static final Set<FitnessStatus> UNDECIDED = Set.of(FitnessStatus.NOT_ASSESSED, FitnessStatus.UNDER_REVIEW, FitnessStatus.UNFIT_PENDING);

    private final CaseChecklistRepository checklists;
    private final OhCaseRepository cases;
    private final EmployeeRepository employees;
    private final CaseAccess access;
    private final CaseTimeline timeline;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;
    private final List<CrewStatusEnricher> enrichers;

    public SafetyService(CaseChecklistRepository checklists, OhCaseRepository cases, EmployeeRepository employees, CaseAccess access,
                         CaseTimeline timeline, AuditService audit, JsonMapper json, Clock clock, List<CrewStatusEnricher> enrichers) {
        this.checklists = checklists;
        this.cases = cases;
        this.employees = employees;
        this.access = access;
        this.timeline = timeline;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.enrichers = enrichers;
    }

    public static String checklistFor(SafetyCategory sc) {
        if (sc == null) {
            return null;
        }
        return switch (sc) {
            case FOOD_HANDLER -> "FOOD_HANDLER";
            case AIRSIDE_DRIVER, FORKLIFT -> "DRIVING";
            case WORKING_AT_HEIGHT -> "HEIGHT";
            default -> null;
        };
    }

    public record ChecklistView(String code, boolean required, List<String> items, Map<String, Object> answers, String outcome,
                                Instant completedAt, String completedBy) {
    }

    @Transactional(readOnly = true)
    public List<ChecklistView> list(CurrentUser user, UUID caseId) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.READ, "CASE_CHECKLIST_READ");
        String required = checklistFor(c.getSafetyCategory());
        List<ChecklistView> out = new ArrayList<>();
        for (String code : List.of("FOOD_HANDLER", "DRIVING", "HEIGHT")) {
            var latest = checklists.findByCaseIdOrderByCompletedAtDesc(caseId).stream().filter(x -> x.getCode().equals(code)).findFirst();
            if (latest.isEmpty() && !code.equals(required)) {
                continue;
            }
            out.add(latest.map(x -> new ChecklistView(code, code.equals(required), CHECKLISTS.get(code), readMap(x.getAnswers()),
                    x.getOutcome(), x.getCompletedAt(), x.getCompletedBy()))
                    .orElse(new ChecklistView(code, true, CHECKLISTS.get(code), Map.of(), null, null, null)));
        }
        return out;
    }

    @Transactional
    public ChecklistView complete(CurrentUser user, UUID caseId, String code, Map<String, Object> answers, String outcome) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "CASE_CHECKLIST");
        List<String> items = CHECKLISTS.get(code);
        if (items == null || !OUTCOMES.contains(outcome)) {
            throw ApiException.badRequest("invalid_checklist", "Unknown checklist or outcome");
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        for (String item : items) {
            if (!answers.containsKey(item)) {
                throw ApiException.badRequest("checklist_incomplete", "Checklist item missing: " + item);
            }
            Object v = answers.get(item);
            clean.put(item, v instanceof Boolean ? v : String.valueOf(v).substring(0, Math.min(String.valueOf(v).length(), 40)));
        }
        CaseChecklist x = new CaseChecklist();
        x.setId(UUID.randomUUID());
        x.setCaseId(caseId);
        x.setCode(code);
        x.setAnswers(json.writeValueAsString(clean));
        x.setOutcome(outcome);
        x.setCompletedBy(user.username());
        x.setCompletedAt(clock.instant());
        checklists.save(x);
        timeline.record(c.getId(), "CHECKLIST", code + " checklist completed: " + outcome);
        audit.record("CASE_CHECKLIST", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, code + "/" + outcome);
        return new ChecklistView(code, code.equals(checklistFor(c.getSafetyCategory())), items, clean, outcome, x.getCompletedAt(),
                user.username());
    }

    /** Safety-critical cases cannot close without a fitness decision and their role checklist. */
    @Override
    public void check(OhCase c, CaseStatus next) {
        if (next != CaseStatus.CLOSED || !c.isSafetyCritical()) {
            return;
        }
        if (UNDECIDED.contains(c.getFitnessStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "fitness_required",
                    "Record a fitness-for-duty decision before closing a safety-critical case");
        }
        String required = checklistFor(c.getSafetyCategory());
        if (required != null && checklists.findByCaseIdOrderByCompletedAtDesc(c.getId()).stream().noneMatch(x -> x.getCode().equals(required))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "checklist_required",
                    "Complete the " + required + " checklist before closing this case");
        }
    }

    /**
     * Status-only row. {@code rosterStatus} (FIT / LIMITED / UNFIT / PENDING), validity dates and operational limitation
     * codes come from the roster-status feed when a module provides it ({@link CrewStatusEnricher}, contract §5.4).
     */
    public record StatusRow(String employeeId, String displayName, String fitnessStatus, LocalDate restrictionUntil, Instant updatedAt,
                            String rosterStatus, LocalDate validFrom, LocalDate validUntil, List<String> operationalLimitations) {
    }

    /** fit / unfit / fit-with-limitations / pending only – latest case per employee (E02-BL-017). */
    @Transactional(readOnly = true)
    public List<StatusRow> crewStatus(Collection<String> employeeIds) {
        if (employeeIds.size() > 500) {
            throw ApiException.badRequest("too_many", "At most 500 employees per request");
        }
        List<StatusRow> rows = enrich(latest(cases.findByEmployeeIds(employeeIds)));
        audit.record("CREW_STATUS_READ", "EMPLOYEE", null, AuditService.Outcome.SUCCESS, "employees=" + employeeIds.size());
        return rows;
    }

    @Transactional(readOnly = true)
    public List<StatusRow> foodHandlerStatus(String department) {
        List<StatusRow> rows = latest(cases.findBySafetyCategoryAndDepartment(SafetyCategory.FOOD_HANDLER, department));
        audit.record("HYGIENE_STATUS_READ", "DEPARTMENT", department, AuditService.Outcome.SUCCESS, "rows=" + rows.size());
        return rows;
    }

    private List<StatusRow> latest(List<OhCase> ordered) {
        Map<String, StatusRow> byEmployee = new LinkedHashMap<>();
        for (OhCase c : ordered) {
            byEmployee.computeIfAbsent(c.getEmployeeId(), id -> new StatusRow(id,
                    employees.findById(id).map(Employee::getDisplayName).orElse(c.getEmployeeDisplayName()), simple(c.getFitnessStatus()),
                    c.getRestrictionUntil(), c.getUpdatedAt(), FitnessFeed.rosterStatus(c.getFitnessStatus()), null, null, List.of()));
        }
        return List.copyOf(byEmployee.values());
    }

    private List<StatusRow> enrich(List<StatusRow> rows) {
        if (enrichers.isEmpty() || rows.isEmpty()) {
            return rows;
        }
        Map<String, CrewStatusEnricher.RosterInfo> info = new LinkedHashMap<>();
        List<String> ids = rows.stream().map(StatusRow::employeeId).toList();
        enrichers.forEach(e -> info.putAll(e.rosterInfo(ids)));
        return rows.stream().map(r -> {
            CrewStatusEnricher.RosterInfo i = info.get(r.employeeId());
            return i == null ? r : new StatusRow(r.employeeId(), r.displayName(), r.fitnessStatus(), r.restrictionUntil(), r.updatedAt(),
                    i.rosterStatus(), i.validFrom(), i.validUntil(), i.operationalLimitations());
        }).toList();
    }

    static String simple(FitnessStatus f) {
        return switch (f) {
            case FIT -> "FIT";
            case FIT_WITH_RESTRICTIONS -> "FIT_WITH_LIMITATIONS";
            case TEMPORARILY_UNFIT, PERMANENTLY_UNFIT -> "UNFIT";
            default -> "PENDING";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String s) {
        return s == null ? Map.of() : json.readValue(s, Map.class);
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }
}
