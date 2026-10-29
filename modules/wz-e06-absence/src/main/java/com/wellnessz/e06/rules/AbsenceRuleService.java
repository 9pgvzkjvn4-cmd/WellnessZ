package com.wellnessz.e06.rules;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e06.model.AbsenceEnums;
import com.wellnessz.e06.model.E06EpisodeRepository;
import com.wellnessz.e06.model.TriggerRule;
import com.wellnessz.e06.model.AbsenceTriggerRuleRepository;
import com.wellnessz.e06.model.TriggerRuleVersion;
import com.wellnessz.e06.model.AbsenceTriggerRuleVersionRepository;
import com.wellnessz.e06.support.AbsenceSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/** Trigger-rule configuration (E06-F02): versioned CRUD and test mode against the live population (no cases created). */
@Service
public class AbsenceRuleService {

    private final AbsenceTriggerRuleRepository rules;
    private final AbsenceTriggerRuleVersionRepository versions;
    private final E06EpisodeRepository episodes;
    private final AbsenceFacts facts;
    private final AbsenceSupport support;
    private final AuditService audit;

    public AbsenceRuleService(AbsenceTriggerRuleRepository rules, AbsenceTriggerRuleVersionRepository versions, E06EpisodeRepository episodes, AbsenceFacts facts,
                              AbsenceSupport support, AuditService audit) {
        this.rules = rules;
        this.versions = versions;
        this.episodes = episodes;
        this.facts = facts;
        this.support = support;
        this.audit = audit;
    }

    public record RuleView(UUID id, String code, String name, String subsidiaryCode, String ruleType, Map<String, Object> params, String action,
                           boolean active, int version, String updatedBy, Instant updatedAt) {
    }

    public record RuleCommand(String code, String name, String subsidiaryCode, String ruleType, Map<String, Object> params, String action,
                              Boolean active) {
    }

    public record TestMatch(String employeeId, String displayName, String subsidiaryCode, String detail) {
    }

    public record TestResult(List<TestMatch> matches, int count) {
    }

    @Transactional(readOnly = true)
    public List<RuleView> list() {
        return rules.findAllByOrderByCodeAsc().stream().map(this::view).toList();
    }

    @Transactional
    public RuleView create(CurrentUser user, RuleCommand cmd) {
        if (rules.findByCode(cmd.code()).isPresent()) {
            throw ApiException.conflict("duplicate_code", "A rule with this code already exists");
        }
        TriggerRule r = TriggerRule.create();
        r.setCode(cmd.code());
        r.setVersion(1);
        apply(r, cmd, user);
        rules.save(r);
        snapshot(r, "CREATE", user);
        audit.record("ABSENCE_RULE_CREATE", "ABSENCE_RULE", r.getId().toString(), AuditService.Outcome.SUCCESS, r.getCode());
        return view(r);
    }

    @Transactional
    public RuleView update(CurrentUser user, UUID id, RuleCommand cmd) {
        TriggerRule r = rules.findById(id).orElseThrow(() -> ApiException.notFound("Rule"));
        if (cmd.code() != null && !cmd.code().equals(r.getCode())) {
            throw ApiException.badRequest("code_immutable", "The rule code cannot be changed");
        }
        r.setVersion(r.getVersion() + 1);
        apply(r, cmd, user);
        snapshot(r, "UPDATE", user);
        audit.record("ABSENCE_RULE_UPDATE", "ABSENCE_RULE", id.toString(), AuditService.Outcome.SUCCESS, "version=" + r.getVersion());
        return view(r);
    }

    @Transactional
    public RuleView deactivate(CurrentUser user, UUID id) {
        TriggerRule r = rules.findById(id).orElseThrow(() -> ApiException.notFound("Rule"));
        r.setActive(false);
        r.setVersion(r.getVersion() + 1);
        r.setUpdatedBy(user.username());
        r.setUpdatedAt(support.now());
        snapshot(r, "DEACTIVATE", user);
        audit.record("ABSENCE_RULE_DEACTIVATE", "ABSENCE_RULE", id.toString(), AuditService.Outcome.SUCCESS, "version=" + r.getVersion());
        return view(r);
    }

    private void apply(TriggerRule r, RuleCommand cmd, CurrentUser user) {
        if (!AbsenceEnums.names(AbsenceEnums.RuleType.class).contains(cmd.ruleType())) {
            throw ApiException.badRequest("invalid_rule_type", "Unknown rule type");
        }
        if (!AbsenceEnums.names(AbsenceEnums.RuleAction.class).contains(cmd.action())) {
            throw ApiException.badRequest("invalid_action", "Unknown rule action");
        }
        Map<String, Object> params = RuleEngine.validateParams(cmd.ruleType(), cmd.params());
        r.setName(cmd.name().strip());
        r.setSubsidiaryCode(cmd.subsidiaryCode() == null || cmd.subsidiaryCode().isBlank() ? null : cmd.subsidiaryCode());
        r.setRuleType(cmd.ruleType());
        r.setParams(support.json(params));
        r.setAction(cmd.action());
        r.setActive(cmd.active() == null || cmd.active());
        r.setUpdatedBy(user.username());
        r.setUpdatedAt(support.now());
    }

    private void snapshot(TriggerRule r, String change, CurrentUser user) {
        TriggerRuleVersion v = TriggerRuleVersion.create();
        v.setRuleId(r.getId());
        v.setVersion(r.getVersion());
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("code", r.getCode());
        snap.put("name", r.getName());
        snap.put("subsidiaryCode", r.getSubsidiaryCode());
        snap.put("ruleType", r.getRuleType());
        snap.put("params", support.readMap(r.getParams()));
        snap.put("action", r.getAction());
        snap.put("active", r.isActive());
        v.setSnapshot(support.json(snap));
        v.setChangeType(change);
        v.setChangedBy(user.username());
        v.setChangedAt(support.now());
        versions.save(v);
    }

    // ------------------------------------------------------------------ engine

    /** Active rules in engine form. */
    @Transactional(readOnly = true)
    public List<RuleEngine.Rule> activeRules() {
        return rules.findByActiveTrueOrderByCodeAsc().stream().map(this::engineRule).toList();
    }

    RuleEngine.Rule engineRule(TriggerRule r) {
        return new RuleEngine.Rule(r.getId(), r.getCode(), r.getName(), r.getSubsidiaryCode(), r.getRuleType(), support.readMap(r.getParams()),
                r.getAction());
    }

    /** Test mode: evaluates one rule (saved or unsaved) against everyone with recent absence – read only. */
    @Transactional(readOnly = true)
    public TestResult test(CurrentUser user, UUID ruleId, RuleCommand unsaved, String subsidiaryCode) {
        RuleEngine.Rule rule;
        if (ruleId != null) {
            rule = engineRule(rules.findById(ruleId).orElseThrow(() -> ApiException.notFound("Rule")));
        } else if (unsaved != null) {
            if (!AbsenceEnums.names(AbsenceEnums.RuleType.class).contains(unsaved.ruleType())
                    || !AbsenceEnums.names(AbsenceEnums.RuleAction.class).contains(unsaved.action())) {
                throw ApiException.badRequest("invalid_rule", "Unknown rule type or action");
            }
            rule = new RuleEngine.Rule(null, unsaved.code() == null ? "TEST" : unsaved.code(), unsaved.name() == null ? "Test rule" : unsaved.name(),
                    unsaved.subsidiaryCode(), unsaved.ruleType(), RuleEngine.validateParams(unsaved.ruleType(), unsaved.params()), unsaved.action());
        } else {
            throw ApiException.badRequest("rule_required", "Provide a rule or a ruleId");
        }
        String sub = subsidiaryCode != null && !subsidiaryCode.isBlank() ? subsidiaryCode : rule.subsidiaryCode();
        LocalDate today = support.today();
        List<String> population = episodes.findEmployeesWithStartSince(today.minusDays(400));
        List<TestMatch> out = new ArrayList<>();
        for (Employee e : support.employeesById(population)) {
            if (sub != null && !sub.equals(e.getSubsidiaryCode())) {
                continue;
            }
            RuleEngine.evaluate(rule, facts.facts(e), today).ifPresent(m ->
                    out.add(new TestMatch(e.getEmployeeId(), e.getDisplayName(), e.getSubsidiaryCode(), m.detail())));
        }
        out.sort(java.util.Comparator.comparing(TestMatch::employeeId));
        audit.record("ABSENCE_RULE_TEST", "ABSENCE_RULE", ruleId == null ? null : ruleId.toString(), AuditService.Outcome.SUCCESS,
                "matches=" + out.size());
        return new TestResult(out, out.size());
    }

    private RuleView view(TriggerRule r) {
        return new RuleView(r.getId(), r.getCode(), r.getName(), r.getSubsidiaryCode(), r.getRuleType(), support.readMap(r.getParams()),
                r.getAction(), r.isActive(), r.getVersion(), r.getUpdatedBy(), r.getUpdatedAt());
    }
}
