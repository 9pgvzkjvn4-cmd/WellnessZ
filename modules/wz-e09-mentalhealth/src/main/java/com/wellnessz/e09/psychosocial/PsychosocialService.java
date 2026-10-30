package com.wellnessz.e09.psychosocial;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e09.casework.SupportCaseService;
import com.wellnessz.e09.model.E09PsychosocialActionRepository;
import com.wellnessz.e09.model.E09PsychosocialAssessmentRepository;
import com.wellnessz.e09.model.MentalHealthEnums.Factor;
import com.wellnessz.e09.model.PsychosocialAction;
import com.wellnessz.e09.model.PsychosocialAssessment;
import com.wellnessz.e09.rules.PsychosocialScoring;
import com.wellnessz.e09.rules.Suppression;
import com.wellnessz.e09.support.MhSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.OrgUnit;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * Psychosocial risk register (E09-F05 / E09-BL-012, ISO 45003): department-level assessments of the psychosocial factors with
 * likelihood × severity scoring and RAG, actions with owners and due dates, review dates and a dashboard showing the anonymised
 * support-case rate per 100 staff (small cells suppressed). Managers see their own unit's assessment and actions.
 */
@Service
public class PsychosocialService {

    private static final TypeReference<List<StoredFactor>> FACTORS = new TypeReference<>() {
    };

    private final E09PsychosocialAssessmentRepository assessments;
    private final E09PsychosocialActionRepository actions;
    private final SupportCaseService cases;
    private final MhSupport support;
    private final AuditService audit;

    public PsychosocialService(E09PsychosocialAssessmentRepository assessments, E09PsychosocialActionRepository actions, SupportCaseService cases,
                               MhSupport support, AuditService audit) {
        this.assessments = assessments;
        this.actions = actions;
        this.cases = cases;
        this.support = support;
        this.audit = audit;
    }

    public record FactorInput(Factor factor, int likelihood, int severity, String controls) {
    }

    public record StoredFactor(String factor, int likelihood, int severity, String controls) {
    }

    public record FactorView(String factor, int likelihood, int severity, int score, String rag, String controls) {
    }

    public record ActionView(UUID id, String title, String owner, LocalDate dueDate, String status, boolean overdue, Instant completedAt) {
    }

    public record AssessmentView(UUID id, String unitCode, String unitName, LocalDate assessedOn, LocalDate reviewDate, boolean reviewOverdue,
                                 List<FactorView> factors, int overallScore, String rag, List<ActionView> actions, String assessedBy, Instant createdAt) {
    }

    public record CreateCommand(String unitCode, LocalDate assessedOn, List<FactorInput> factors, LocalDate reviewDate) {
    }

    @Transactional(readOnly = true)
    public List<AssessmentView> list(String unitCode) {
        List<PsychosocialAssessment> list = unitCode == null ? assessments.findAllByOrderByAssessedOnDescCreatedAtDesc()
                : assessments.findByUnitCodeOrderByAssessedOnDescCreatedAtDesc(unitCode);
        Map<UUID, List<PsychosocialAction>> byAssessment = actions.findByAssessmentIdIn(list.stream().map(PsychosocialAssessment::getId).toList())
                .stream().collect(Collectors.groupingBy(PsychosocialAction::getAssessmentId));
        return list.stream().map(a -> view(a, byAssessment.getOrDefault(a.getId(), List.of()))).toList();
    }

    @Transactional
    public AssessmentView create(CurrentUser user, CreateCommand cmd) {
        support.unit(cmd.unitCode()).orElseThrow(() -> MhSupport.unprocessable("unknown_unit", "Unknown organisational unit"));
        if (cmd.assessedOn().isAfter(support.today())) {
            throw MhSupport.unprocessable("invalid_date", "The assessment date lies in the future");
        }
        if (!cmd.reviewDate().isAfter(cmd.assessedOn())) {
            throw MhSupport.unprocessable("invalid_review_date", "The review date must follow the assessment date");
        }
        Set<Factor> seen = EnumSet.noneOf(Factor.class);
        List<StoredFactor> stored = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (FactorInput f : cmd.factors()) {
            if (!seen.add(f.factor())) {
                throw MhSupport.unprocessable("duplicate_factor", "Each factor is assessed once");
            }
            int score;
            try {
                score = PsychosocialScoring.score(f.likelihood(), f.severity());
            } catch (IllegalArgumentException e) {
                throw MhSupport.unprocessable("invalid_score", "Likelihood and severity are 1 to 5");
            }
            scores.add(score);
            stored.add(new StoredFactor(f.factor().name(), f.likelihood(), f.severity(), MhSupport.screened("controls", f.controls(), 1000)));
        }
        int overall = PsychosocialScoring.overall(scores);
        PsychosocialAssessment a = PsychosocialAssessment.create();
        a.setUnitCode(cmd.unitCode());
        a.setAssessedOn(cmd.assessedOn());
        a.setReviewDate(cmd.reviewDate());
        a.setFactors(support.json(stored));
        a.setOverallScore(overall);
        a.setRag(PsychosocialScoring.rag(overall).name());
        a.setAssessedBy(user.username());
        a.setCreatedAt(support.now());
        assessments.save(a);
        audit.record("PSYCHOSOCIAL_ASSESSMENT_CREATE", "PSYCHOSOCIAL_ASSESSMENT", a.getId().toString(), AuditService.Outcome.SUCCESS, a.getRag());
        return view(a, List.of());
    }

    public record ActionCommand(String title, String owner, LocalDate dueDate, String status) {
    }

    @Transactional
    public ActionView addAction(CurrentUser user, UUID assessmentId, ActionCommand cmd) {
        assessments.findById(assessmentId).orElseThrow(() -> ApiException.notFound("Assessment"));
        PsychosocialAction x = PsychosocialAction.create();
        x.setAssessmentId(assessmentId);
        x.setCreatedBy(user.username());
        x.setCreatedAt(support.now());
        apply(x, cmd);
        actions.save(x);
        audit.record("PSYCHOSOCIAL_ACTION_ADD", "PSYCHOSOCIAL_ASSESSMENT", assessmentId.toString(), AuditService.Outcome.SUCCESS, null);
        return actionView(x);
    }

    @Transactional
    public ActionView updateAction(CurrentUser user, UUID assessmentId, UUID actionId, ActionCommand cmd) {
        PsychosocialAction x = actions.findById(actionId).filter(a -> a.getAssessmentId().equals(assessmentId))
                .orElseThrow(() -> ApiException.notFound("Action"));
        apply(x, cmd);
        audit.record("PSYCHOSOCIAL_ACTION_UPDATE", "PSYCHOSOCIAL_ASSESSMENT", assessmentId.toString(), AuditService.Outcome.SUCCESS, cmd.status());
        return actionView(x);
    }

    private void apply(PsychosocialAction x, ActionCommand cmd) {
        Instant now = support.now();
        x.setTitle(MhSupport.screened("title", cmd.title(), 300));
        x.setOwner(MhSupport.screened("owner", cmd.owner(), 200));
        x.setDueDate(cmd.dueDate());
        String status = cmd.status() == null ? "OPEN" : cmd.status();
        if ("DONE".equals(status) && !"DONE".equals(x.getStatus())) {
            x.setCompletedAt(now);
        } else if (!"DONE".equals(status)) {
            x.setCompletedAt(null);
        }
        x.setStatus(status);
        x.setUpdatedAt(now);
    }

    // ------------------------------------------------------------------ dashboard

    public record UnitRow(String unitCode, String unitName, AssessmentSummary current, boolean reviewOverdue, int openActions, int overdueActions,
                          Long headcount, BigDecimal supportCaseRatePer100, boolean suppressed) {
    }

    public record AssessmentSummary(UUID id, LocalDate assessedOn, LocalDate reviewDate, int overallScore, String rag) {
    }

    @Transactional(readOnly = true)
    public List<UnitRow> dashboard() {
        int k = support.minGroupSize();
        Map<String, Long> headcount = support.headcountByDepartment();
        Map<String, PsychosocialAssessment> current = new LinkedHashMap<>();
        for (PsychosocialAssessment a : assessments.findAllByOrderByAssessedOnDescCreatedAtDesc()) {
            current.putIfAbsent(a.getUnitCode(), a);
        }
        Map<UUID, List<PsychosocialAction>> byAssessment = actions.findByAssessmentIdIn(current.values().stream().map(PsychosocialAssessment::getId)
                .toList()).stream().collect(Collectors.groupingBy(PsychosocialAction::getAssessmentId));
        // anonymised support-case counts per department over the last 12 months (never names, never shown below k)
        Map<String, Long> casesByDept = new LinkedHashMap<>();
        cases.openedSinceByEmployee(support.now().minusSeconds(365L * 86_400)).forEach((emp, n) ->
                support.employee(emp).map(Employee::getDepartmentCode).ifPresent(d -> casesByDept.merge(d, n, Long::sum)));
        Set<String> units = new TreeSet<>(current.keySet());
        units.addAll(headcount.keySet());
        LocalDate today = support.today();
        List<UnitRow> rows = new ArrayList<>();
        for (String unit : units) {
            if (!support.unit(unit).map(OrgUnit::isActive).orElse(false)) {
                continue;
            }
            PsychosocialAssessment a = current.get(unit);
            List<PsychosocialAction> acts = a == null ? List.of() : byAssessment.getOrDefault(a.getId(), List.of());
            int open = (int) acts.stream().filter(x -> "OPEN".equals(x.getStatus())).count();
            int overdue = (int) acts.stream().filter(x -> "OPEN".equals(x.getStatus()) && x.getDueDate().isBefore(today)).count();
            long hc = headcount.getOrDefault(unit, 0L);
            long n = casesByDept.getOrDefault(unit, 0L);
            BigDecimal rate = Suppression.ratePer100(n, hc, k);
            boolean suppressed = rate == null && hc > 0;
            rows.add(new UnitRow(unit, support.unitName(unit), a == null ? null : new AssessmentSummary(a.getId(), a.getAssessedOn(), a.getReviewDate(),
                    a.getOverallScore(), a.getRag()), a == null || a.getReviewDate().isBefore(today), open, overdue,
                    Suppression.publish(hc, k), rate, suppressed));
        }
        return rows;
    }

    // ------------------------------------------------------------------ manager workspace

    public record ManagerView(String unitCode, String unitName, AssessmentView assessment, List<ActionView> actions) {
    }

    @Transactional(readOnly = true)
    public ManagerView managerView(CurrentUser user) {
        String unit = managerUnit(user);
        if (unit == null) {
            throw ApiException.notFound("Unit");
        }
        AssessmentView a = list(unit).stream().findFirst().orElse(null);
        audit.record("PSYCHOSOCIAL_MANAGER_READ", "ORG_UNIT", unit, AuditService.Outcome.SUCCESS, null);
        return new ManagerView(unit, support.unitName(unit), a, a == null ? List.of() : a.actions());
    }

    private String managerUnit(CurrentUser user) {
        String scope = user.orgScope();
        if (scope != null) {
            for (OrgUnit u : support.units()) {
                if (scope.equals(u.getPath())) {
                    return u.getCode();
                }
            }
        }
        return support.employee(user.employeeId()).map(Employee::getDepartmentCode).orElse(null);
    }

    // ------------------------------------------------------------------ projections

    private AssessmentView view(PsychosocialAssessment a, List<PsychosocialAction> acts) {
        List<FactorView> factors = support.read(a.getFactors(), FACTORS).stream().map(f -> {
            int score = f.likelihood() * f.severity();
            return new FactorView(f.factor(), f.likelihood(), f.severity(), score, PsychosocialScoring.rag(score).name(), f.controls());
        }).toList();
        return new AssessmentView(a.getId(), a.getUnitCode(), support.unitName(a.getUnitCode()), a.getAssessedOn(), a.getReviewDate(),
                a.getReviewDate().isBefore(support.today()), factors, a.getOverallScore(), a.getRag(),
                acts.stream().sorted(java.util.Comparator.comparing(PsychosocialAction::getDueDate)).map(this::actionView).toList(),
                a.getAssessedBy(), a.getCreatedAt());
    }

    private ActionView actionView(PsychosocialAction x) {
        return new ActionView(x.getId(), x.getTitle(), x.getOwner(), x.getDueDate(), x.getStatus(),
                "OPEN".equals(x.getStatus()) && x.getDueDate().isBefore(support.today()), x.getCompletedAt());
    }
}
