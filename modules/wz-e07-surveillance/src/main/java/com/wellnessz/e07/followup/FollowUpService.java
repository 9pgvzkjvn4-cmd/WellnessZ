package com.wellnessz.e07.followup;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.CaseTask;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OpenCaseCommand;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.CaseWorkService;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.casekernel.support.ResultFollowUpListener;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.e03.model.Result;
import com.wellnessz.e03.model.ResultRepository;
import com.wellnessz.e07.E07Events;
import com.wellnessz.e07.model.Enrolment;
import com.wellnessz.e07.model.EnrolmentRepository;
import com.wellnessz.e07.model.EvaluationRule;
import com.wellnessz.e07.model.EvaluationRuleRepository;
import com.wellnessz.e07.model.FollowUp;
import com.wellnessz.e07.model.FollowUpRepository;
import com.wellnessz.e07.model.Programme;
import com.wellnessz.e07.programme.ProgrammeCatalog;
import com.wellnessz.e07.support.SurveillanceSupport;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Result evaluation & auto-follow-up (E07-F07): called synchronously by E03 for every recorded LIS / device result. A
 * result of an employee enrolled in an active programme whose panel contains the test is evaluated against the rules;
 * abnormal → follow-up record, kernel case {@code SURVEILLANCE_FOLLOW_UP} (idempotent per result), recheck task,
 * regulatory-notification task where the rule requires it and a content-free notification to the responsible role.
 */
@Service
public class FollowUpService implements ResultFollowUpListener {

    private final EnrolmentRepository enrolments;
    private final ProgrammeCatalog catalog;
    private final EvaluationRuleRepository rules;
    private final FollowUpRepository followUps;
    private final ResultRepository results;
    private final CaseService cases;
    private final CaseWorkService work;
    private final CaseTimeline timeline;
    private final EmployeeRecordAccess records;
    private final SurveillanceSupport support;
    private final EventBus bus;
    private final AuditService audit;

    public FollowUpService(EnrolmentRepository enrolments, ProgrammeCatalog catalog, EvaluationRuleRepository rules, FollowUpRepository followUps,
                           ResultRepository results, CaseService cases, CaseWorkService work, CaseTimeline timeline,
                           EmployeeRecordAccess records, SurveillanceSupport support, EventBus bus, AuditService audit) {
        this.enrolments = enrolments;
        this.catalog = catalog;
        this.rules = rules;
        this.followUps = followUps;
        this.results = results;
        this.cases = cases;
        this.work = work;
        this.timeline = timeline;
        this.records = records;
        this.support = support;
        this.bus = bus;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ SPI (E03 → E07)

    @Override
    @Transactional
    public void resultRecorded(RecordedResult rr) {
        if (rr == null || rr.employeeId() == null || rr.resultId() == null) {
            return;
        }
        List<String> tests = EvaluationRules.testsFor(rr.category(), rr.code());
        if (tests.isEmpty()) {
            return;
        }
        List<Programme> programmes = new ArrayList<>();
        for (Enrolment e : enrolments.findByEmployeeIdAndActiveTrue(rr.employeeId())) {
            catalog.active(e.getProgrammeCode()).filter(p -> catalog.panelContains(p, tests)).ifPresent(programmes::add);
        }
        if (programmes.isEmpty()) {
            return;
        }
        List<EvaluationRule> active = rules.findByActiveTrue();
        List<EvaluationRule> specific = active.stream().filter(r -> tests.contains(r.getTest())).toList();
        List<EvaluationRule> applicable = specific.isEmpty() ? active.stream().filter(r -> "*".equals(r.getTest())).toList() : specific;
        if (applicable.isEmpty()) {
            return;
        }
        Result result = results.findById(rr.resultId()).orElse(null);
        for (EvaluationRule rule : applicable) {
            if (followUps.existsByResultIdAndRuleCode(rr.resultId(), rule.getCode()) || !abnormal(rule, rr, result)) {
                continue;
            }
            Programme p = programmes.stream().filter(x -> "*".equals(rule.getTest()) || catalog.panelContains(x, List.of(rule.getTest())))
                    .findFirst().orElse(programmes.get(0));
            raise(rr, rule, p);
        }
    }

    boolean abnormal(EvaluationRule rule, RecordedResult rr, Result result) {
        Map<String, Object> params = support.readMap(rule.getParams());
        return switch (rule.getRuleType()) {
            case "STS" -> EvaluationRules.sts(rr.flag(), result == null ? null : support.readMap(result.getDetail()));
            case "SPIRO_DECLINE" -> {
                if (result == null) {
                    yield false;
                }
                Map<String, Object> m = support.readMap(result.getMeasurements());
                yield EvaluationRules.spirometryDecline(EvaluationRules.number(m.get("fev1L")), EvaluationRules.number(m.get("fvcL")),
                        previousFev1(result), EvaluationRules.param(params, "fev1DropPct", 15), EvaluationRules.param(params, "ratioMin", 0.70));
            }
            case "BM_LIMIT" -> result != null && EvaluationRules.biomonitoringLimit(result.getValue(),
                    EvaluationRules.param(params, "limit", Double.MAX_VALUE));
            case "VISION" -> EvaluationRules.vision(rr.flag());
            default -> EvaluationRules.flagAbnormal(rr.flag());
        };
    }

    private Double previousFev1(Result current) {
        for (Result r : results.findByEmployeeIdOrderByEffectiveAtDesc(current.getEmployeeId())) {
            if (!r.getId().equals(current.getId()) && "SPIROMETRY".equals(r.getCategory()) && r.getEffectiveAt() != null
                    && current.getEffectiveAt() != null && !r.getEffectiveAt().isAfter(current.getEffectiveAt())) {
                Double v = EvaluationRules.number(support.readMap(r.getMeasurements()).get("fev1L"));
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    private void raise(RecordedResult rr, EvaluationRule rule, Programme p) {
        OhCase c = cases.openIdempotent(OpenCaseCommand.builder(rr.employeeId(), CaseCategory.EXPOSURE, CaseSource.SURVEILLANCE_TRIGGER)
                .caseType(CaseType.SURVEILLANCE_FOLLOW_UP).sourceRef("surveillance:" + rr.resultId()).build());
        LocalDate today = support.today();
        LocalDate recheck = today.plusDays(Math.max(1, rule.getFollowUpDays()));
        Instant now = support.now();
        FollowUp f = new FollowUp();
        f.setId(UUID.randomUUID());
        f.setEmployeeId(rr.employeeId());
        f.setProgrammeCode(p.getCode());
        f.setRuleCode(rule.getCode());
        f.setResultId(rr.resultId());
        f.setCaseId(c.getId());
        f.setCaseRef(c.getCaseRef());
        f.setRecheckDueOn(recheck);
        f.setRegulatoryNotificationRequired(rule.isRegulatoryNotification());
        f.setStatus("OPEN");
        f.setCreatedAt(now);
        followUps.save(f);
        Instant due = recheck.atStartOfDay().toInstant(ZoneOffset.UTC);
        work.systemTask(c.getId(), CaseTask.Type.FOLLOW_UP, "Surveillance recheck (" + p.getCode() + ")", due, null, "system");
        if (rule.isRegulatoryNotification()) {
            work.systemTask(c.getId(), CaseTask.Type.REMINDER, "Assess regulatory notification (" + rule.getCode() + ")",
                    today.plusDays(7).atStartOfDay().toInstant(ZoneOffset.UTC), null, "system");
        }
        timeline.record(c.getId(), "SURVEILLANCE_FOLLOW_UP", "Surveillance follow-up raised by rule " + rule.getCode());
        support.notifyRole(p.getResponsibleRole(), "surveillance.follow-up", Map.of("caseRef", c.getCaseRef(), "dueDate", recheck.toString()),
                "/#/surveillance/follow-ups");
        bus.publish(new E07Events.FollowUpCreated(f.getId().toString(), f.getEmployeeId(), c.getId().toString()));
        audit.recordAs("system", "MODULE", "SURVEILLANCE_FOLLOW_UP_CREATED", "FOLLOW_UP", f.getId().toString(), AuditService.Outcome.SUCCESS,
                "rule=" + rule.getCode() + " programme=" + p.getCode());
    }

    // ------------------------------------------------------------------ follow-up worklist

    public record FollowUpView(UUID id, String employeeId, String displayName, String programmeCode, String ruleCode, UUID resultId,
                               UUID caseId, String caseRef, LocalDate recheckDueOn, boolean regulatoryNotificationRequired, String status,
                               Instant createdAt, String reviewedBy, String reviewDecision) {
    }

    FollowUpView view(FollowUp f) {
        return new FollowUpView(f.getId(), f.getEmployeeId(), support.displayName(f.getEmployeeId()), f.getProgrammeCode(), f.getRuleCode(),
                f.getResultId(), f.getCaseId(), f.getCaseRef(), f.getRecheckDueOn(), f.isRegulatoryNotificationRequired(), f.getStatus(),
                f.getCreatedAt(), f.getReviewedBy(), f.getReviewDecision());
    }

    @Transactional(readOnly = true)
    public List<FollowUpView> list(CurrentUser user, String status) {
        List<FollowUp> list = status == null ? followUps.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 1000))
                : followUps.findByStatusOrderByCreatedAtDesc(status, PageRequest.of(0, 1000));
        List<FollowUpView> out = list.stream().map(this::view).toList();
        audit.record("SURVEILLANCE_FOLLOW_UP_LIST", "FOLLOW_UP", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public FollowUpView review(CurrentUser user, UUID id, String decision, String note, Boolean regulatoryNotificationRequired) {
        FollowUp f = followUps.findById(id).orElseThrow(() -> ApiException.notFound("Follow-up"));
        records.requireClinical(user, f.getEmployeeId(), DataClassification.CLINICAL, AccessPolicy.Action.UPDATE, "SURVEILLANCE_FOLLOW_UP_REVIEW");
        if ("CLOSED".equals(f.getStatus())) {
            throw ApiException.conflict("follow_up_closed", "The follow-up is closed");
        }
        f.setReviewDecision(decision);
        f.setReviewNote(SurveillanceSupport.clinical("note", note, 4000));
        f.setReviewedBy(user.username());
        f.setReviewedAt(support.now());
        if (regulatoryNotificationRequired != null) {
            f.setRegulatoryNotificationRequired(regulatoryNotificationRequired);
        }
        f.setStatus("NOT_CONFIRMED".equals(decision) ? "CLOSED" : "REVIEWED");
        timeline.record(f.getCaseId(), "SURVEILLANCE_FOLLOW_UP_REVIEWED", "Surveillance follow-up reviewed: " + decision);
        audit.record("SURVEILLANCE_FOLLOW_UP_REVIEW", "FOLLOW_UP", id.toString(), AuditService.Outcome.SUCCESS, decision);
        return view(f);
    }

    // ------------------------------------------------------------------ evaluation rules

    public record RuleView(String code, String test, String ruleType, Map<String, Object> params, int followUpDays,
                           boolean regulatoryNotification, boolean active) {
    }

    public record RuleCommand(@NotBlank @Pattern(regexp = "\\*|AUDIOMETRY|SPIROMETRY|VISION|ECG|LAB:[A-Za-z0-9.\\-]{1,20}|BIOMONITORING:[A-Za-z0-9.\\-_]{1,20}") String test,
                              @NotBlank @Pattern(regexp = "STS|SPIRO_DECLINE|BM_LIMIT|VISION|FLAG_ABNORMAL") String ruleType,
                              @Size(max = 10) Map<String, Object> params, @NotNull @Min(1) @Max(365) Integer followUpDays,
                              @NotNull Boolean regulatoryNotification, Boolean active) {
    }

    RuleView view(EvaluationRule r) {
        return new RuleView(r.getCode(), r.getTest(), r.getRuleType(), support.readMap(r.getParams()), r.getFollowUpDays(),
                r.isRegulatoryNotification(), r.isActive());
    }

    @Transactional(readOnly = true)
    public List<RuleView> rules() {
        return rules.findAllByOrderByCodeAsc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public RuleView rule(String code) {
        return rules.findById(code).map(this::view).orElseThrow(() -> ApiException.notFound("Evaluation rule"));
    }

    @Transactional
    public RuleView putRule(CurrentUser user, String code, RuleCommand cmd) {
        if (!code.matches("[A-Z][A-Z0-9_]{1,39}")) {
            throw ApiException.badRequest("invalid_code", "Rule codes are upper-case identifiers");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (cmd.params() != null) {
            for (Map.Entry<String, Object> e : cmd.params().entrySet()) {
                if (e.getKey() == null || !e.getKey().matches("[A-Za-z0-9]{1,30}")
                        || !(e.getValue() instanceof Number || e.getValue() instanceof String s && s.length() <= 40)) {
                    throw ApiException.badRequest("invalid_params", "Parameters are short names with numeric or short text values");
                }
                params.put(e.getKey(), e.getValue());
            }
        }
        EvaluationRule r = rules.findById(code).orElseGet(() -> {
            EvaluationRule n = new EvaluationRule();
            n.setCode(code);
            return n;
        });
        r.setTest(cmd.test());
        r.setRuleType(cmd.ruleType());
        r.setParams(support.write(params));
        r.setFollowUpDays(cmd.followUpDays());
        r.setRegulatoryNotification(cmd.regulatoryNotification());
        r.setActive(cmd.active() == null || cmd.active());
        r.setUpdatedBy(user.username());
        r.setUpdatedAt(support.now());
        rules.save(r);
        audit.record("SURVEILLANCE_RULE_PUT", "EVALUATION_RULE", code, AuditService.Outcome.SUCCESS, cmd.ruleType());
        return view(r);
    }

    public Optional<FollowUp> find(UUID id) {
        return followUps.findById(id);
    }
}
