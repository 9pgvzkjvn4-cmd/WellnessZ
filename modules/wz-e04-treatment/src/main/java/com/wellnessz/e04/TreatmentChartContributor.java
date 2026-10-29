package com.wellnessz.e04;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.support.ChartContributor;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e04.model.PlanTemplateRepository;
import com.wellnessz.e04.model.Referral;
import com.wellnessz.e04.model.ServiceDefinitionRepository;
import com.wellnessz.e04.model.TreatmentOutcome;
import com.wellnessz.e04.model.TreatmentOutcomeRepository;
import com.wellnessz.e04.model.TreatmentPlan;
import com.wellnessz.e04.model.TreatmentPlanRepository;
import com.wellnessz.e04.model.TreatmentReferralRepository;
import com.wellnessz.e04.model.VarianceAlertRepository;
import com.wellnessz.e04.referral.ChaseRules;
import com.wellnessz.e04.support.TreatmentSupport;

/**
 * Contributes the "treatment" section (active plan, open referrals, latest outcomes) and alerts (overdue / escalated
 * provider report, recovery variance) to the E03 chart summary (tab {@code #/employees/{id}/chart/treatment}). Nothing
 * for non-OH callers.
 */
@Component
public class TreatmentChartContributor implements ChartContributor {

    static final Set<String> OPEN_REFERRAL = Set.of("DRAFT", "SENT", "ACCEPTED", "SCHEDULED", "SEEN", "REPORT_RECEIVED");

    private final TreatmentPlanRepository plans;
    private final TreatmentReferralRepository referrals;
    private final TreatmentOutcomeRepository outcomes;
    private final VarianceAlertRepository alerts;
    private final ServiceDefinitionRepository services;
    private final TreatmentSupport support;
    private final PlanTemplateRepository templates;

    public TreatmentChartContributor(TreatmentPlanRepository plans, TreatmentReferralRepository referrals, TreatmentOutcomeRepository outcomes,
                                     VarianceAlertRepository alerts, ServiceDefinitionRepository services, TreatmentSupport support,
                                     PlanTemplateRepository templates) {
        this.templates = templates;
        this.plans = plans;
        this.referrals = referrals;
        this.outcomes = outcomes;
        this.alerts = alerts;
        this.services = services;
        this.support = support;
    }

    @Override
    @Transactional(readOnly = true)
    public Contribution contribute(com.wellnessz.security.identity.CurrentUser user, String employeeId) {
        if (!user.hasAnyRole(Roles.OH_TEAM)) {
            return Contribution.EMPTY;
        }
        Map<String, Object> section = new LinkedHashMap<>();
        List<Alert> out = new ArrayList<>();
        TreatmentPlan active = plans.findByEmployeeIdOrderByCreatedAtDesc(employeeId).stream().filter(p -> "ACTIVE".equals(p.getStatus()))
                .findFirst().orElse(null);
        if (active != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", active.getId());
            p.put("caseId", active.getCaseId());
            p.put("goals", active.getGoals() != null ? support.readStrings(active.getGoals()) : active.getTemplateCode() == null ? List.of()
                    : templates.findById(active.getTemplateCode()).map(t -> support.readStrings(t.getGoals())).orElse(List.of()));
            p.put("startDate", active.getStartDate());
            p.put("responsibleClinician", active.getResponsibleClinician());
            section.put("activePlan", p);
            alerts.findByPlanIdOrderByRaisedAtDesc(active.getId()).stream().filter(a -> "OPEN".equals(a.getStatus()) || "ESCALATED".equals(a.getStatus()))
                    .findFirst().ifPresent(a -> out.add(new Alert("TREATMENT_VARIANCE", "MEDIUM",
                            "Recovery " + a.getPctOver() + "% beyond the expected " + a.getExpectedDays() + " days")));
        }
        Map<String, String> names = new LinkedHashMap<>();
        services.findAll().forEach(s -> names.put(s.getCode(), s.getName()));
        List<Map<String, Object>> open = new ArrayList<>();
        for (Referral r : referrals.findByEmployeeIdOrderByCreatedAtDesc(employeeId)) {
            if (!OPEN_REFERRAL.contains(r.getStatus())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("ref", r.getRef());
            m.put("serviceName", names.getOrDefault(r.getServiceCode(), r.getServiceCode()));
            m.put("providerName", r.getProviderCode() == null ? null : support.partnerName(r.getProviderCode()));
            m.put("status", r.getStatus());
            m.put("appointmentAt", r.getAppointmentAt());
            open.add(m);
            if (ChaseRules.overdue(r.getStatus(), r.getReportDueAt(), r.getReportReceivedAt(), support.now())) {
                out.add(new Alert("REFERRAL_REPORT_OVERDUE", r.isEscalated() ? "HIGH" : "MEDIUM", "Provider report overdue for referral " + r.getRef()));
            }
        }
        section.put("openReferrals", open);
        List<Map<String, Object>> latest = new ArrayList<>();
        for (TreatmentPlan p : plans.findByEmployeeIdOrderByCreatedAtDesc(employeeId)) {
            if (latest.size() >= 5) {
                break;
            }
            for (TreatmentOutcome o : outcomes.findByCaseIdOrderByRecordedAtAsc(p.getCaseId())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("caseId", o.getCaseId());
                m.put("milestone", o.getMilestone());
                m.put("painScore", o.getPainScore());
                m.put("functionScale", o.getFunctionScale());
                m.put("functionScore", o.getFunctionScore());
                m.put("recordedOn", LocalDate.ofInstant(o.getRecordedAt(), ZoneOffset.UTC));
                if (latest.stream().noneMatch(x -> x.get("caseId").equals(o.getCaseId()) && x.get("milestone").equals(o.getMilestone()))) {
                    latest.add(m);
                }
            }
        }
        section.put("outcomes", latest);
        if (active == null && open.isEmpty() && latest.isEmpty()) {
            return Contribution.EMPTY;
        }
        return new Contribution(Map.of("treatment", section), out);
    }
}
