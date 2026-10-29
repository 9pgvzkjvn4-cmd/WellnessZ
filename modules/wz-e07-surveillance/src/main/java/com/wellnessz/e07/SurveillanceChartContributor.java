package com.wellnessz.e07;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.support.ChartContributor;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e07.due.DueService;
import com.wellnessz.e07.followup.RaiService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Contributes the "surveillance" section (programmes with status and next due date) and alerts (overdue programme,
 * return-after-illness medical required) to the E03 chart summary. Nothing for non-OH callers.
 */
@Component
public class SurveillanceChartContributor implements ChartContributor {

    private final DueService due;
    private final RaiService rai;

    public SurveillanceChartContributor(DueService due, RaiService rai) {
        this.due = due;
        this.rai = rai;
    }

    @Override
    @Transactional(readOnly = true)
    public Contribution contribute(CurrentUser user, String employeeId) {
        if (!user.hasAnyRole(Roles.OH_TEAM)) {
            return Contribution.EMPTY;
        }
        List<Map<String, Object>> programmes = new ArrayList<>();
        List<Alert> alerts = new ArrayList<>();
        for (DueService.MyProgramme p : due.mine(employeeId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("programmeTitle", p.programmeTitle());
            row.put("status", p.status());
            row.put("nextDueOn", p.nextDueOn());
            programmes.add(row);
            if ("OVERDUE".equals(p.status())) {
                alerts.add(new Alert("SURVEILLANCE_OVERDUE", "MEDIUM", p.programmeTitle() + " overdue since " + p.nextDueOn()));
            }
        }
        if (rai.required(employeeId)) {
            alerts.add(new Alert("RETURN_AFTER_ILLNESS_REQUIRED", "HIGH", "Return-after-illness medical required before a FIT decision"));
        }
        if (programmes.isEmpty() && alerts.isEmpty()) {
            return Contribution.EMPTY;
        }
        return new Contribution(Map.of("surveillance", programmes), alerts);
    }
}
