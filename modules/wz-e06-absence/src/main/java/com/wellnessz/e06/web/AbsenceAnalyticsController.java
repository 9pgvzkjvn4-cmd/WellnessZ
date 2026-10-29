package com.wellnessz.e06.web;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import jakarta.validation.constraints.Pattern;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.e06.analytics.AbsenceAnalyticsService;
import com.wellnessz.e06.analytics.UnitAggregator;
import com.wellnessz.security.identity.CurrentUserProvider;

/** E06-F06 absence analytics: unit dashboards (suppressed), KPIs and the monthly CSV pack. Never person level. */
@RestController
@RequestMapping("/api/v1")
public class AbsenceAnalyticsController {

    private final AbsenceAnalyticsService analytics;
    private final CurrentUserProvider users;

    public AbsenceAnalyticsController(AbsenceAnalyticsService analytics, CurrentUserProvider users) {
        this.analytics = analytics;
        this.users = users;
    }

    @GetMapping("/absence/analytics/units")
    @PreAuthorize("hasAnyRole('CMO','NURSING_HEAD','HR','OH_ADMIN')")
    public List<UnitAggregator.UnitRow> units(@RequestParam(defaultValue = "DEPARTMENT") @Pattern(regexp = "SUBSIDIARY|DEPARTMENT|JOB_FAMILY") String groupBy,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analytics.units(users.require(), groupBy, from, to);
    }

    @GetMapping("/absence/analytics/kpis")
    @PreAuthorize("hasAnyRole('CMO','NURSING_HEAD','OH_ADMIN')")
    public AbsenceAnalyticsService.Kpis kpis(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analytics.kpis(users.require(), from, to);
    }

    @GetMapping("/absence/analytics/export")
    @PreAuthorize("hasAnyRole('CMO','NURSING_HEAD','HR')")
    public ResponseEntity<byte[]> export(@RequestParam @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String month) {
        YearMonth m = YearMonth.parse(month);
        byte[] csv = analytics.export(users.require(), m).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("absence-" + m + ".csv").build().toString())
                .body(csv);
    }
}
