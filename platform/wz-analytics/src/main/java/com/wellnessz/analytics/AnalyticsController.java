package com.wellnessz.analytics;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.security.audit.AuditService;

@RestController
@RequestMapping("/api/v1/analytics")
@PreAuthorize("hasAnyRole('MANAGER','HR','OH_CLINICIAN','OH_ADMIN','CASE_MANAGER','PLATFORM_ADMIN')")
public class AnalyticsController {

    private final KpiService kpis;
    private final AuditService audit;

    public AnalyticsController(KpiService kpis, AuditService audit) {
        this.kpis = kpis;
        this.audit = audit;
    }

    @GetMapping("/kpis/cases-by-status")
    public KpiService.KpiResult casesByStatus(@RequestParam(defaultValue = "orgUnit") String groupBy) {
        KpiService.KpiResult result = kpis.casesByStatus(groupBy);
        audit.record("ANALYTICS_QUERY", "KPI", "cases-by-status", AuditService.Outcome.SUCCESS, groupBy);
        return result;
    }
}
