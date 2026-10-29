package com.wellnessz.e01.rules;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.Urgency;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** Rule table administration with dry-run testing (E01-BL-003). Every change is versioned and audited. */
@RestController
@RequestMapping("/api/v1/intake/rules")
public class IntakeRuleController {

    private static final String CODE = "[A-Z0-9_]{1,60}";

    private final IntakeRuleRepository rules;
    private final CurrentUserProvider users;
    private final AuditService audit;
    private final Clock clock;

    public IntakeRuleController(IntakeRuleRepository rules, CurrentUserProvider users, AuditService audit, Clock clock) {
        this.rules = rules;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    public record RuleRequest(@NotBlank @Size(max = 200) String name, @Min(0) @Max(10000) int priority, Boolean active,
                              @Pattern(regexp = CODE) String incidentClass, @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL") String minSeverity,
                              Boolean lostTime, Boolean injury, @Pattern(regexp = CODE) String exposureType,
                              @Pattern(regexp = "[A-Z0-9]{1,20}") String unitCode, @Pattern(regexp = CODE) String bodyPart,
                              @NotNull RuleAction action, CaseType caseType, Urgency urgency) {
    }

    public record RuleView(Long id, String name, int priority, boolean active, String incidentClass, String minSeverity,
                           Boolean lostTime, Boolean injury, String exposureType, String unitCode, String bodyPart,
                           RuleAction action, CaseType caseType, Urgency urgency, long version, String updatedBy, Instant updatedAt) {
        static RuleView of(IntakeRule r) {
            return new RuleView(r.getId(), r.getName(), r.getPriority(), r.isActive(), r.getIncidentClass(), r.getMinSeverity(),
                    r.getLostTime(), r.getInjury(), r.getExposureType(), r.getUnitCode(), r.getBodyPart(), r.getAction(),
                    r.getCaseType(), r.getUrgency(), r.getVersion(), r.getUpdatedBy(), r.getUpdatedAt());
        }
    }

    public record TestRequest(@NotNull IncidentFacts incident) {
    }

    public record TestResult(Long ruleId, String ruleName, String action, CaseType caseType, Urgency urgency) {
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','OH_ADMIN','HSE_OFFICER')")
    public List<RuleView> list() {
        return rules.findAllByOrderByPriorityAscIdAsc().stream().map(RuleView::of).toList();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OH_ADMIN','NURSING_HEAD')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public RuleView create(@Valid @RequestBody RuleRequest r) {
        IntakeRule rule = apply(new IntakeRule(users.actorOrSystem(), clock.instant()), r);
        rule = rules.save(rule);
        audit.record("INTAKE_RULE_CREATE", "INTAKE_RULE", String.valueOf(rule.getId()), AuditService.Outcome.SUCCESS, r.name());
        return RuleView.of(rule);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OH_ADMIN','NURSING_HEAD')")
    @Transactional
    public RuleView update(@PathVariable long id, @Valid @RequestBody RuleRequest r) {
        IntakeRule rule = rules.findById(id).orElseThrow(() -> ApiException.notFound("Rule"));
        apply(rule, r);
        audit.record("INTAKE_RULE_UPDATE", "INTAKE_RULE", String.valueOf(id), AuditService.Outcome.SUCCESS, r.name());
        return RuleView.of(rule);
    }

    @PostMapping("/test")
    @PreAuthorize("hasAnyRole('OH_ADMIN','NURSING_HEAD')")
    public TestResult test(@Valid @RequestBody TestRequest r) {
        Optional<IntakeRule> m = RuleEngine.firstMatch(rules.findByActiveTrueOrderByPriorityAscIdAsc(), r.incident());
        return m.map(x -> new TestResult(x.getId(), x.getName(), x.getAction().name(), x.getCaseType(), x.getUrgency()))
                .orElse(new TestResult(null, null, "NO_MATCH", null, null));
    }

    private IntakeRule apply(IntakeRule rule, RuleRequest r) {
        if (r.action() == RuleAction.AUTO_CREATE && r.caseType() == null) {
            throw ApiException.badRequest("case_type_required", "AUTO_CREATE rules must define the case type");
        }
        rule.update(r.name(), r.priority(), r.active() == null || r.active(), r.incidentClass(), r.minSeverity(), r.lostTime(),
                r.injury(), r.exposureType(), r.unitCode(), r.bodyPart(), r.action(), r.caseType(), r.urgency(),
                users.actorOrSystem(), clock.instant());
        return rule;
    }
}
