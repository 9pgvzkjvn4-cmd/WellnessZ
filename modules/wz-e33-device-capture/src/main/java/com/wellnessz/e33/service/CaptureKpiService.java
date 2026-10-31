package com.wellnessz.e33.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e33.domain.CaptureKpis;
import com.wellnessz.e33.model.CaptureSession;
import com.wellnessz.e33.model.E33CaptureSessionRepository;
import com.wellnessz.e33.model.E33FormRuleRepository;
import com.wellnessz.e33.model.E33ObservationNoticeRepository;
import com.wellnessz.e33.model.FormRule;
import com.wellnessz.security.audit.AuditService;

/** Capture KPIs per site and period (counts only, no person data): usage, minutes saved, re-test and unmatched rates. */
@Service
public class CaptureKpiService {

    static final int MAX_DAYS = 366;

    public record KpiView(String siteCode, LocalDate from, LocalDate to, CaptureKpis.Result kpis, Map<String, BigDecimal> targets) {
    }

    private final E33CaptureSessionRepository sessions;
    private final E33ObservationNoticeRepository notices;
    private final E33FormRuleRepository rules;
    private final AuditService audit;
    private final CaptureSupport support;

    public CaptureKpiService(E33CaptureSessionRepository sessions, E33ObservationNoticeRepository notices, E33FormRuleRepository rules,
                             AuditService audit, CaptureSupport support) {
        this.sessions = sessions;
        this.notices = notices;
        this.rules = rules;
        this.audit = audit;
        this.support = support;
    }

    /**
     * @param to inclusive end date; window at most one year
     */
    @Transactional(readOnly = true)
    public KpiView kpis(String siteCode, LocalDate from, LocalDate to) {
        support.requireEnabled();
        LocalDate end = to == null ? support.today() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end) || start.plusDays(MAX_DAYS).isBefore(end)) {
            throw ApiException.badRequest("validation_failed", "The window must be between one day and one year");
        }
        Instant a = start.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant b = end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Map<String, BigDecimal> minutes = rules.findAll().stream().collect(Collectors.toMap(FormRule::getFormCode, FormRule::getMinutesSaved));
        List<CaptureSession> list = siteCode == null ? sessions.findOpened(a, b) : sessions.findOpenedAtSite(siteCode, a, b);
        List<CaptureKpis.SessionFact> facts = list.stream()
                .map(s -> new CaptureKpis.SessionFact(s.getState(), s.getPath(), minutes.get(s.getFormCode()), s.getRetestCount())).toList();
        // notices carry no site: the unmatched rate is group-wide unless the site's device instances are known
        List<CaptureKpis.NoticeFact> noticeFacts = notices.findByFirstAtGreaterThanEqualAndFirstAtLessThan(a, b).stream()
                .map(n -> new CaptureKpis.NoticeFact(n.isEverUnmatched())).toList();
        CaptureKpis.Result r = CaptureKpis.compute(facts, noticeFacts);
        audit.record("DEVICE_CAPTURE_KPI_READ", "DEVICE_CAPTURE_KPI", siteCode == null ? "ALL" : siteCode, AuditService.Outcome.SUCCESS,
                start + ".." + end);
        return new KpiView(siteCode, start, end, r, Map.of("usagePct", new BigDecimal("90"), "minutesSavedPerDeviceTest", new BigDecimal("3"),
                "retestRatePct", new BigDecimal("1"), "unmatchedRatePct", new BigDecimal("0.5")));
    }
}
