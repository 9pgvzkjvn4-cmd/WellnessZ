package com.wellnessz.e05;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.support.ChartContributor;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e05.decision.DecisionRules;
import com.wellnessz.e05.decision.FitnessDecisionService;
import com.wellnessz.e05.model.RestrictionInstance;
import com.wellnessz.e05.restriction.RestrictionService;
import com.wellnessz.e05.support.FitnessSupport;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Contributes the "fitness" (current decision) and "restrictions" (in force, with status) sections and fitness alerts
 * (expiring / lapsed restrictions, decision awaiting sign-off, validity ending) to the E03 chart summary. The chart
 * module performs the record-level access check; this contributor adds nothing for non-OH callers.
 */
@Component
public class FitnessChartContributor implements ChartContributor {

    private final FitnessDecisionService decisions;
    private final RestrictionService restrictions;
    private final FitnessSupport support;

    public FitnessChartContributor(FitnessDecisionService decisions, RestrictionService restrictions, FitnessSupport support) {
        this.decisions = decisions;
        this.restrictions = restrictions;
        this.support = support;
    }

    @Override
    @Transactional(readOnly = true)
    public Contribution contribute(CurrentUser user, String employeeId) {
        if (!user.hasAnyRole(Roles.OH_TEAM)) {
            return Contribution.EMPTY;
        }
        LocalDate today = support.today();
        Map<String, Object> sections = new LinkedHashMap<>();
        List<Alert> alerts = new ArrayList<>();
        decisions.currentDecision(employeeId).ifPresent(d -> {
            Map<String, Object> fitness = new LinkedHashMap<>();
            fitness.put("status", d.getOutcome());
            fitness.put("standardCode", d.getStandardCode());
            fitness.put("validUntil", d.getValidUntil());
            fitness.put("reviewDate", d.getReviewDate());
            fitness.put("decidedBy", d.getDecidedBy());
            sections.put("fitness", fitness);
            if (d.getValidUntil() != null && !d.getValidUntil().isAfter(today.plusDays(30))) {
                alerts.add(new Alert("FITNESS_VALIDITY_ENDING", d.getValidUntil().isBefore(today) ? "HIGH" : "MEDIUM",
                        "Fitness decision against " + d.getStandardCode() + " valid until " + d.getValidUntil()));
            }
        });
        if (!decisions.pendingSignoff(employeeId).isEmpty()) {
            alerts.add(new Alert("FITNESS_SIGNOFF_PENDING", "MEDIUM", "A fitness decision is awaiting CMO sign-off"));
        }
        List<Map<String, Object>> rs = new ArrayList<>();
        for (RestrictionInstance r : restrictions.forEmployee(employeeId)) {
            String status = DecisionRules.restrictionStatus(r.getStatus(), r.getValidUntil(), today);
            if ("CLOSED".equals(status)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", r.getCode());
            row.put("label", r.getLabel());
            row.put("validUntil", r.getValidUntil());
            row.put("status", status);
            rs.add(row);
            if ("EXPIRING".equals(status) || "LAPSED".equals(status)) {
                alerts.add(new Alert("RESTRICTION_" + status, "LAPSED".equals(status) ? "HIGH" : "MEDIUM",
                        r.getLabel() + ("LAPSED".equals(status) ? " lapsed on " : " ends on ") + r.getValidUntil()));
            }
        }
        sections.put("restrictions", rs);
        return new Contribution(sections, alerts);
    }
}
