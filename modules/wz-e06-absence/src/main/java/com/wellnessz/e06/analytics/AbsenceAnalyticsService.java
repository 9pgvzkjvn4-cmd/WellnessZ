package com.wellnessz.e06.analytics;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e06.model.AbsenceEnums.MilestoneStatus;
import com.wellnessz.e06.model.Certificate;
import com.wellnessz.e06.model.AbsenceCertificateRepository;
import com.wellnessz.e06.model.Episode;
import com.wellnessz.e06.model.E06EpisodeRepository;
import com.wellnessz.e06.model.LtaMilestoneRepository;
import com.wellnessz.e06.model.LtaPathway;
import com.wellnessz.e06.model.LtaPathwayRepository;
import com.wellnessz.e06.model.QueueItem;
import com.wellnessz.e06.model.AbsenceQueueItemRepository;
import com.wellnessz.e06.rules.AbsenceFacts;
import com.wellnessz.e06.support.AbsenceSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.JobCodeRepository;
import com.wellnessz.masterdata.OrgUnit;
import com.wellnessz.masterdata.OrgUnitRepository;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Absence analytics (E06-F06): unit dashboards with small-group suppression (categories for OH roles only), service KPIs
 * (LTA with case, trigger-to-contact, certificate verification) with a monthly trend, and the monthly CSV pack.
 */
@Service
public class AbsenceAnalyticsService {

    static final int LTA_DAYS = 28;
    static final int MAX_RANGE_DAYS = 3 * 366;

    private final E06EpisodeRepository episodes;
    private final AbsenceQueueItemRepository queue;
    private final AbsenceCertificateRepository certificates;
    private final LtaPathwayRepository pathways;
    private final LtaMilestoneRepository milestones;
    private final OrgUnitRepository orgUnits;
    private final JobCodeRepository jobCodes;
    private final AbsenceSupport support;
    private final AuditService audit;
    private final int minGroupSize;

    public AbsenceAnalyticsService(E06EpisodeRepository episodes, AbsenceQueueItemRepository queue, AbsenceCertificateRepository certificates,
                                   LtaPathwayRepository pathways, LtaMilestoneRepository milestones, OrgUnitRepository orgUnits,
                                   JobCodeRepository jobCodes, AbsenceSupport support, AuditService audit,
                                   @Value("${wellnessz.analytics.min-group-size:5}") int minGroupSize) {
        this.episodes = episodes;
        this.queue = queue;
        this.certificates = certificates;
        this.pathways = pathways;
        this.milestones = milestones;
        this.orgUnits = orgUnits;
        this.jobCodes = jobCodes;
        this.support = support;
        this.audit = audit;
        this.minGroupSize = Math.max(3, minGroupSize);
    }

    public record TrendPoint(String month, BigDecimal absenceRatePct, BigDecimal medianDaysTriggerToContact, BigDecimal ltaWithCasePct) {
    }

    public record Kpis(LocalDate from, LocalDate to, BigDecimal absenceRatePct, BigDecimal ltaWithCasePct, int ltaWithCaseTarget,
                       BigDecimal medianDaysTriggerToContact, int medianDaysTriggerToContactTarget, BigDecimal certificatesVerifiedPct, long openLta,
                       long overdueMilestones, int minGroupSize, List<TrendPoint> trend) {
    }

    // ------------------------------------------------------------------ units

    @Transactional(readOnly = true)
    public List<UnitAggregator.UnitRow> units(CurrentUser user, String groupBy, LocalDate from, LocalDate to) {
        LocalDate[] w = window(from, to);
        List<UnitAggregator.UnitRow> rows = UnitAggregator.aggregate(members(user, groupBy), w[0], w[1], minGroupSize, AbsenceSupport.isOh(user));
        audit.record("ABSENCE_ANALYTICS_READ", "ABSENCE_ANALYTICS", groupBy, AuditService.Outcome.SUCCESS, "rows=" + rows.size());
        return rows;
    }

    @Transactional(readOnly = true)
    public String export(CurrentUser user, YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        List<UnitAggregator.UnitRow> rows = UnitAggregator.aggregate(members(user, "DEPARTMENT"), from, to, minGroupSize, false);
        StringBuilder csv = new StringBuilder("month,groupBy,key,label,headcount,episodes,daysLost,absenceRatePct,frequencyRate,avgDurationDays,bradfordMean,suppressed\n");
        for (UnitAggregator.UnitRow r : rows) {
            csv.append(month).append(",DEPARTMENT,").append(csv(r.key())).append(',').append(csv(r.label())).append(',')
                    .append(v(r.headcount())).append(',').append(v(r.episodes())).append(',').append(v(r.daysLost())).append(',')
                    .append(v(r.absenceRatePct())).append(',').append(v(r.frequencyRate())).append(',').append(v(r.avgDurationDays())).append(',')
                    .append(v(r.bradfordMean())).append(',').append(r.suppressed()).append('\n');
        }
        audit.record("ABSENCE_ANALYTICS_EXPORT", "ABSENCE_ANALYTICS", month.toString(), AuditService.Outcome.SUCCESS, "rows=" + rows.size());
        return csv.toString();
    }

    private static String v(Object o) {
        return o == null ? "" : o.toString();
    }

    /** CSV cell: quoted, inner quotes doubled, leading formula characters neutralised. */
    static String csv(String s) {
        if (s == null) {
            return "";
        }
        String x = s.replace("\"", "\"\"");
        if (!x.isEmpty() && "=+-@".indexOf(x.charAt(0)) >= 0) {
            x = "'" + x;
        }
        return "\"" + x + "\"";
    }

    private List<UnitAggregator.Member> members(CurrentUser user, String groupBy) {
        List<Employee> population = user.hasRole(Roles.HR) && !user.hasAnyRole(Roles.CMO, Roles.NURSING_HEAD, Roles.OH_ADMIN) && user.orgScope() != null
                ? support.employeesInScope(user) : support.allActiveEmployees();
        Map<String, String> deptNames = orgUnits.findAll().stream().collect(Collectors.toMap(OrgUnit::getCode, OrgUnit::getName, (a, b) -> a));
        Map<String, String> jobTitles = new HashMap<>();
        jobCodes.findAll().forEach(j -> jobTitles.put(j.getCode(), j.getTitle()));
        Map<String, List<Episode>> byEmployee = episodes.findByEmployeeIdInOrderByStartDateDesc(population.stream().map(Employee::getEmployeeId).toList())
                .stream().collect(Collectors.groupingBy(Episode::getEmployeeId));
        List<UnitAggregator.Member> out = new ArrayList<>();
        for (Employee e : population) {
            String key;
            String label;
            switch (groupBy) {
                case "SUBSIDIARY" -> {
                    key = e.getSubsidiaryCode();
                    label = e.getSubsidiaryCode();
                }
                case "JOB_FAMILY" -> {
                    key = e.getJobCode();
                    label = jobTitles.getOrDefault(e.getJobCode(), e.getJobCode());
                }
                case "DEPARTMENT" -> {
                    key = e.getDepartmentCode();
                    label = deptNames.getOrDefault(e.getDepartmentCode(), e.getDepartmentCode());
                }
                default -> throw ApiException.badRequest("invalid_group_by", "groupBy must be SUBSIDIARY, DEPARTMENT or JOB_FAMILY");
            }
            List<UnitAggregator.Absence> abs = byEmployee.getOrDefault(e.getEmployeeId(), List.of()).stream()
                    .map(x -> new UnitAggregator.Absence(AbsenceFacts.fact(x), x.getReasonCategory())).toList();
            out.add(new UnitAggregator.Member(e.getEmployeeId(), key, label == null ? key : label, abs));
        }
        return out;
    }

    // ------------------------------------------------------------------ KPIs

    @Transactional(readOnly = true)
    public Kpis kpis(CurrentUser user, LocalDate from, LocalDate to) {
        LocalDate[] w = window(from, to);
        List<UnitAggregator.Member> all = members(user, "DEPARTMENT");
        List<Episode> allEpisodes = episodes.findOverlapping(w[0], w[1]);
        List<QueueItem> items = queue.findByRaisedAtBetween(start(w[0]), start(w[1].plusDays(1)));
        Map<java.util.UUID, LtaPathway> pathwayByEpisode = new HashMap<>();
        Map<String, List<LtaPathway>> pathwaysByEmployee = new HashMap<>();
        for (LtaPathway p : pathways.findAllByOrderByAbsenceStartDesc()) {
            if (p.getEpisodeId() != null) {
                pathwayByEpisode.put(p.getEpisodeId(), p);
            }
            pathwaysByEmployee.computeIfAbsent(p.getEmployeeId(), k -> new ArrayList<>()).add(p);
        }
        List<Certificate> certs = certificates.findByReceivedAtBetween(start(w[0]), start(w[1].plusDays(1)));
        long processed = certs.stream().filter(c -> !"RECEIVED".equals(c.getStatus())).count();
        List<LtaPathway> active = pathways.findByStatusOrderByAbsenceStartAsc("ACTIVE");
        Set<java.util.UUID> activeIds = active.stream().map(LtaPathway::getCaseId).collect(Collectors.toSet());
        long overdue = milestones.findByStatusAndDueDateLessThanOrderByDueDateAsc(MilestoneStatus.OVERDUE.name(), support.today().plusDays(1))
                .stream().filter(m -> activeIds.contains(m.getCaseId())).count();
        List<TrendPoint> trend = new ArrayList<>();
        for (YearMonth m = YearMonth.from(w[0]); !m.isAfter(YearMonth.from(w[1])); m = m.plusMonths(1)) {
            LocalDate ms = m.atDay(1).isBefore(w[0]) ? w[0] : m.atDay(1);
            LocalDate me = m.atEndOfMonth().isAfter(w[1]) ? w[1] : m.atEndOfMonth();
            YearMonth month = m;
            List<QueueItem> mItems = items.stream().filter(q -> YearMonth.from(q.getRaisedAt().atZone(ZoneOffset.UTC)).equals(month)).toList();
            trend.add(new TrendPoint(m.toString(), UnitAggregator.stats(all, ms, me).absenceRatePct(), medianToContact(mItems),
                    ltaWithCase(allEpisodes, ms, me, pathwayByEpisode, pathwaysByEmployee)));
        }
        audit.record("ABSENCE_KPIS_READ", "ABSENCE_ANALYTICS", null, AuditService.Outcome.SUCCESS, null);
        return new Kpis(w[0], w[1], UnitAggregator.stats(all, w[0], w[1]).absenceRatePct(),
                ltaWithCase(allEpisodes, w[0], w[1], pathwayByEpisode, pathwaysByEmployee), 95, medianToContact(items), 5,
                UnitAggregator.pct(processed, certs.size()), active.size(), overdue, minGroupSize, trend);
    }

    /** Share of long-term episodes (&ge; 28 days) overlapping the window that are managed on an OH case. */
    private BigDecimal ltaWithCase(Collection<Episode> eps, LocalDate from, LocalDate to, Map<java.util.UUID, LtaPathway> byEpisode,
                                   Map<String, List<LtaPathway>> byEmployee) {
        LocalDate asOf = to.isAfter(support.today()) ? support.today() : to;
        List<Episode> lta = eps.stream().filter(e -> !e.getStartDate().isAfter(to) && (e.getEndDate() == null || !e.getEndDate().isBefore(from)))
                .filter(e -> AbsenceFacts.fact(e).triggerable() && AbsenceFacts.fact(e).days(asOf) >= LTA_DAYS).toList();
        long withCase = lta.stream().filter(e -> e.getCaseId() != null || byEpisode.containsKey(e.getId())
                || byEmployee.getOrDefault(e.getEmployeeId(), List.of()).stream().anyMatch(p -> !p.getAbsenceStart().isBefore(e.getStartDate().minusDays(7))
                    && (e.getEndDate() == null || !p.getAbsenceStart().isAfter(e.getEndDate())))).count();
        return UnitAggregator.pct(withCase, lta.size());
    }

    private static BigDecimal medianToContact(List<QueueItem> items) {
        return UnitAggregator.median(items.stream().filter(q -> q.getFirstContactAt() != null)
                .map(q -> Duration.between(q.getRaisedAt(), q.getFirstContactAt()).toMinutes() / 1440.0).toList());
    }

    private static Instant start(LocalDate d) {
        return d.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private LocalDate[] window(LocalDate from, LocalDate to) {
        LocalDate t = to == null ? support.today() : to;
        LocalDate f = from == null ? t.minusDays(364) : from;
        if (f.isAfter(t)) {
            throw ApiException.badRequest("invalid_range", "from must not be after to");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(f, t) > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("invalid_range", "The range is limited to three years");
        }
        return new LocalDate[]{f, t};
    }
}
