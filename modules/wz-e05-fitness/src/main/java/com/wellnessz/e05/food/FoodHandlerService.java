package com.wellnessz.e05.food;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.FitnessFeed;
import com.wellnessz.casekernel.model.FitnessStatus;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.SafetyCategory;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.documents.model.DocumentShare;
import com.wellnessz.e02.safety.SafetyService;
import com.wellnessz.e05.E05Events;
import com.wellnessz.e05.model.FoodHandlerEpisode;
import com.wellnessz.e05.model.FoodHandlerEpisodeRepository;
import com.wellnessz.e05.support.FitnessSupport;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.fitness.JobDemandProfileRepository;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Food-handler fitness control (E05-BL-014/015, HACCP): exclusion with an OH-only reason category and checklist,
 * return request, clearance by a nurse or physician (48 h symptom-free) with a FOOD_HANDLER_CLEARANCE document; the
 * catering hygiene manager receives status and dates only (feed + audited view).
 */
@Service
public class FoodHandlerService {

    public static final String EXCLUDED = "EXCLUDED";
    public static final String RETURN_PENDING = "RETURN_PENDING_CLEARANCE";
    public static final String FIT = "FIT_TO_HANDLE_FOOD";
    static final Set<String> REASONS = Set.of("GI_ILLNESS", "SKIN_INFECTION", "TYPHOID_PARATYPHOID", "HEPATITIS_A", "STEC", "OTHER");
    static final int MAX_ANSWERS = 30;

    private final FoodHandlerEpisodeRepository episodes;
    private final JobDemandProfileRepository jobDemands;
    private final CaseService caseService;
    private final CaseAccess access;
    private final CaseTimeline timeline;
    private final SafetyService safety;
    private final DocumentService documents;
    private final FitnessSupport support;
    private final EventBus bus;
    private final AuditService audit;

    public FoodHandlerService(FoodHandlerEpisodeRepository episodes, JobDemandProfileRepository jobDemands, CaseService caseService,
                              CaseAccess access, CaseTimeline timeline, SafetyService safety, DocumentService documents, FitnessSupport support,
                              EventBus bus, AuditService audit) {
        this.episodes = episodes;
        this.jobDemands = jobDemands;
        this.caseService = caseService;
        this.access = access;
        this.timeline = timeline;
        this.safety = safety;
        this.documents = documents;
        this.support = support;
        this.bus = bus;
        this.audit = audit;
    }

    public record EpisodeView(UUID id, UUID caseId, String employeeId, String department, String status, String reasonCategory,
                              Map<String, Object> checklistAnswers, LocalDate excludedFrom, LocalDate expectedReturn, Instant returnRequestedAt,
                              Instant clearedAt, String clearedBy, UUID clearanceDocumentId) {
    }

    /** Hygiene-manager row: status and dates only. */
    public record HygieneRow(String employeeId, String displayName, String department, String status, LocalDate excludedFrom,
                             LocalDate expectedReturn, Instant clearedAt) {
    }

    @Transactional
    public EpisodeView exclude(CurrentUser user, UUID caseId, String reasonCategory, LocalDate excludedFrom, LocalDate expectedReturn,
                               Map<String, Object> answers) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "FOOD_HANDLER_EXCLUDE");
        requireFoodHandler(c);
        if (!REASONS.contains(reasonCategory)) {
            throw ApiException.badRequest("invalid_reason_category", "Unknown exclusion reason category");
        }
        LocalDate from = excludedFrom == null ? support.today() : excludedFrom;
        if (expectedReturn != null && expectedReturn.isBefore(from)) {
            throw ApiException.badRequest("invalid_dates", "expectedReturn must not be before excludedFrom");
        }
        if (episodes.findByCaseIdOrderByCreatedAtDesc(caseId).stream().anyMatch(e -> !FIT.equals(e.getStatus()))) {
            throw ApiException.conflict("already_excluded", "This case already has an open food-handler exclusion");
        }
        Map<String, Object> clean = cleanAnswers(answers);
        Instant now = support.clock().instant();
        Employee emp = support.employee(c.getEmployeeId()).orElse(null);
        FoodHandlerEpisode e = FoodHandlerEpisode.create();
        e.setCaseId(c.getId());
        e.setEmployeeId(c.getEmployeeId());
        e.setDepartmentCode(emp != null ? emp.getDepartmentCode() : c.getOrgUnitCode());
        e.setStatus(EXCLUDED);
        e.setReasonCategory(reasonCategory);
        e.setChecklist(support.writeJson(clean));
        e.setExcludedFrom(from);
        e.setExpectedReturn(expectedReturn);
        e.setCreatedBy(user.username());
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        episodes.save(e);
        // the E02 role checklist (closure guard) is completed too when all its items were answered
        if (SafetyService.CHECKLISTS.get("FOOD_HANDLER").stream().allMatch(clean::containsKey)) {
            safety.complete(user, caseId, "FOOD_HANDLER", clean, "EXCLUDED");
        }
        caseService.applyFitnessDecision(user, caseId, FitnessStatus.TEMPORARILY_UNFIT, expectedReturn,
                new FitnessFeed(from, expectedReturn, expectedReturn, List.of()));
        publish(e);
        timeline.record(caseId, "FOOD_HANDLER", "Excluded from food handling from " + from);
        audit.record("FOOD_HANDLER_EXCLUDE", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, reasonCategory);
        return view(e);
    }

    @Transactional
    public EpisodeView requestReturn(CurrentUser user, UUID caseId) {
        access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "FOOD_HANDLER_RETURN_REQUEST");
        FoodHandlerEpisode e = open(caseId);
        if (!EXCLUDED.equals(e.getStatus())) {
            throw ApiException.conflict("invalid_state", "A return can only be requested for an excluded food handler");
        }
        Instant now = support.clock().instant();
        e.setStatus(RETURN_PENDING);
        e.setReturnRequestedAt(now);
        e.setReturnRequestedBy(user.username());
        e.setUpdatedAt(now);
        publish(e);
        timeline.record(caseId, "FOOD_HANDLER", "Return to food handling requested – awaiting clearance");
        audit.record("FOOD_HANDLER_RETURN_REQUEST", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, null);
        return view(e);
    }

    @Transactional
    public EpisodeView clear(CurrentUser user, UUID caseId, Map<String, Object> criteria, String note) {
        if (!user.hasAnyRole(Roles.OH_CLINICIAN, Roles.OH_PHYSICIAN, Roles.AME, Roles.CMO)) {
            throw ApiException.forbidden("Return clearance is given by an OH nurse or physician");
        }
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "FOOD_HANDLER_CLEAR");
        FoodHandlerEpisode e = open(caseId);
        if (criteria == null || !Boolean.TRUE.equals(criteria.get("symptomFree48h"))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "clearance_criteria_not_met",
                    "Clearance requires the food handler to be symptom-free for 48 hours");
        }
        Instant now = support.clock().instant();
        LocalDate today = support.today();
        e.setStatus(FIT);
        e.setClearedAt(now);
        e.setClearedBy(user.username());
        e.setCriteria(support.writeJson(cleanAnswers(criteria)));
        e.setClearanceNote(FitnessSupport.clinical("note", note, 2000));
        e.setUpdatedAt(now);
        Employee emp = support.employee(c.getEmployeeId()).orElse(null);
        Map<String, String> f = new LinkedHashMap<>();
        f.put("employeeName", emp == null ? c.getEmployeeId() : emp.getDisplayName());
        f.put("employeeId", c.getEmployeeId());
        if (e.getDepartmentCode() != null) {
            f.put("department", e.getDepartmentCode());
        }
        f.put("status", "Fit to handle food");
        f.put("effectiveFrom", today.toString());
        f.put("issuedBy", support.staffName(user.username()));
        DocumentService.DocumentView doc = documents.generate(user, "FOOD_HANDLER_CLEARANCE", c.getEmployeeId(), caseId, f);
        documents.sign(user, doc.id());
        documents.share(user, doc.id(), DocumentShare.Target.EMPLOYEE, null, null);
        if (emp != null && emp.getManagerEmployeeId() != null) {
            documents.share(user, doc.id(), DocumentShare.Target.MANAGER, null, null);
        }
        e.setDocumentId(doc.id());
        caseService.applyFitnessDecision(user, caseId, FitnessStatus.FIT, null, new FitnessFeed(today, null, null, List.of()));
        publish(e);
        timeline.record(caseId, "FOOD_HANDLER", "Cleared to return to food handling");
        audit.record("FOOD_HANDLER_CLEAR", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, null);
        return view(e);
    }

    @Transactional(readOnly = true)
    public List<EpisodeView> forCase(CurrentUser user, UUID caseId) {
        access.clinical(user, caseId, AccessPolicy.Action.READ, "FOOD_HANDLER_READ");
        return episodes.findByCaseIdOrderByCreatedAtDesc(caseId).stream().map(this::view).toList();
    }

    /** Hygiene manager / OH: latest episode per food handler in a department – status and dates only, audited. */
    @Transactional(readOnly = true)
    public List<HygieneRow> department(CurrentUser user, String department) {
        if (!user.hasRole(Roles.HYGIENE_MANAGER) && !user.hasAnyRole(Roles.OH_TEAM)) {
            throw ApiException.forbidden("Catering hygiene and OH only");
        }
        Map<String, HygieneRow> latest = new LinkedHashMap<>();
        for (FoodHandlerEpisode e : episodes.findByDepartmentCodeOrderByUpdatedAtDesc(department)) {
            latest.computeIfAbsent(e.getEmployeeId(), id -> new HygieneRow(id, support.employeeName(id), e.getDepartmentCode(), e.getStatus(),
                    e.getExcludedFrom(), e.getExpectedReturn(), e.getClearedAt()));
        }
        audit.record("HYGIENE_FOOD_HANDLERS_READ", "DEPARTMENT", department, AuditService.Outcome.SUCCESS, "rows=" + latest.size());
        return new ArrayList<>(latest.values());
    }

    private void requireFoodHandler(OhCase c) {
        if (c.getSafetyCategory() == SafetyCategory.FOOD_HANDLER) {
            return;
        }
        String job = support.employee(c.getEmployeeId()).map(Employee::getJobCode).orElse(c.getJobCode());
        boolean handlesFood = job != null && jobDemands.findById(job).map(p -> p.getDemands() != null
                && p.getDemands().replace(" ", "").contains("\"FOOD_HANDLING\":true")).orElse(false);
        if (!handlesFood) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "not_food_handler", "The employee is not a food handler");
        }
    }

    private FoodHandlerEpisode open(UUID caseId) {
        return episodes.findByCaseIdOrderByCreatedAtDesc(caseId).stream().filter(e -> !FIT.equals(e.getStatus())).findFirst()
                .orElseThrow(() -> ApiException.conflict("no_open_exclusion", "No open food-handler exclusion on this case"));
    }

    private static Map<String, Object> cleanAnswers(Map<String, Object> answers) {
        Map<String, Object> clean = new LinkedHashMap<>();
        if (answers == null) {
            return clean;
        }
        if (answers.size() > MAX_ANSWERS) {
            throw ApiException.badRequest("too_many_answers", "At most " + MAX_ANSWERS + " checklist answers");
        }
        answers.forEach((k, v) -> {
            if (k == null || k.length() > 40 || !k.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_')) {
                throw ApiException.badRequest("invalid_answer", "Checklist keys are short identifiers");
            }
            if (v instanceof Boolean || v instanceof Number) {
                clean.put(k, v);
            } else {
                String s = String.valueOf(v);
                clean.put(k, s.length() > 200 ? s.substring(0, 200) : s);
            }
        });
        return clean;
    }

    private void publish(FoodHandlerEpisode e) {
        bus.publish(new E05Events.FoodHandlerStatusChanged(e.getEmployeeId(), e.getStatus(), e.getExcludedFrom(), e.getExpectedReturn()),
                E05Events.HYGIENE_DESTINATION);
    }

    private EpisodeView view(FoodHandlerEpisode e) {
        Map<String, Object> answers = e.getChecklist() == null ? Map.of() : support.readJson(e.getChecklist(),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
        return new EpisodeView(e.getId(), e.getCaseId(), e.getEmployeeId(), e.getDepartmentCode(), e.getStatus(), e.getReasonCategory(), answers,
                e.getExcludedFrom(), e.getExpectedReturn(), e.getReturnRequestedAt(), e.getClearedAt(), e.getClearedBy(), e.getDocumentId());
    }
}
