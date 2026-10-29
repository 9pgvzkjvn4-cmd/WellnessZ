package com.wellnessz.e07.compliance;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e07.model.Enrolment;
import com.wellnessz.e07.model.EnrolmentRepository;
import com.wellnessz.e07.model.Programme;
import com.wellnessz.e07.programme.Eligibility;
import com.wellnessz.e07.programme.ProgrammeCatalog;
import com.wellnessz.e07.support.SurveillanceSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/** Compliance dashboards (E07-F08): live and nightly-snapshot aggregates with small-group suppression, trend, overdue drill-down. */
@Service
public class ComplianceService {

    static final List<String> GROUP_BY = List.of("PROGRAMME", "SUBSIDIARY", "DEPARTMENT");
    static final String ALL = "*";

    private final EnrolmentRepository enrolments;
    private final ProgrammeCatalog catalog;
    private final SurveillanceSupport support;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ComplianceService(EnrolmentRepository enrolments, ProgrammeCatalog catalog, SurveillanceSupport support, JdbcTemplate jdbc,
                             AuditService audit) {
        this.enrolments = enrolments;
        this.catalog = catalog;
        this.support = support;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record Compliance(Instant refreshedAt, String groupBy, String programme, Double overallCompliancePct, List<ComplianceMaths.Row> rows) {
    }

    public record TrendPoint(LocalDate date, Double compliancePct) {
    }

    public record OverdueRow(UUID enrolmentId, String employeeId, String displayName, String subsidiaryCode, String departmentCode,
                             String programmeCode, String programmeTitle, LocalDate nextDueOn, long daysOverdue, int invitations, Instant escalatedAt) {
    }

    /** Live counts per group key. */
    Map<String, ComplianceMaths.Counts> live(String groupBy, String programme, Map<String, String> labels) {
        LocalDate today = support.today();
        List<Enrolment> active = enrolments.findByActiveTrue().stream().filter(e -> programme == null || programme.equals(e.getProgrammeCode()))
                .toList();
        Map<String, Employee> emps = support.employeesById(active.stream().map(Enrolment::getEmployeeId).distinct().toList());
        Map<String, Programme> programmes = catalog.activeByCode();
        Map<String, ComplianceMaths.Counts> counts = new TreeMap<>();
        for (Enrolment e : active) {
            Employee emp = emps.get(e.getEmployeeId());
            if (emp == null || !programmes.containsKey(e.getProgrammeCode())) {
                continue;
            }
            String key = switch (groupBy) {
                case "SUBSIDIARY" -> emp.getSubsidiaryCode();
                case "DEPARTMENT" -> emp.getDepartmentCode();
                default -> e.getProgrammeCode();
            };
            labels.putIfAbsent(key, "PROGRAMME".equals(groupBy) ? programmes.get(key).getTitle() : key);
            boolean exempt = e.isExempt() && (e.getExemptUntil() == null || !e.getExemptUntil().isBefore(today));
            counts.merge(key, ComplianceMaths.ZERO.add(exempt ? "CURRENT" : Eligibility.dateStatus(e.getNextDueOn(), today)), ComplianceService::sum);
        }
        return counts;
    }

    static ComplianceMaths.Counts sum(ComplianceMaths.Counts a, ComplianceMaths.Counts b) {
        return new ComplianceMaths.Counts(a.enrolled() + b.enrolled(), a.current() + b.current(), a.due() + b.due(), a.overdue() + b.overdue());
    }

    @Transactional(readOnly = true)
    public Compliance compliance(CurrentUser user, String groupBy, String programme) {
        String g = groupBy == null ? "PROGRAMME" : groupBy;
        int k = support.minGroupSize();
        String code = programme == null ? ALL : programme;
        List<Map<String, Object>> snap = jdbc.queryForList("""
                select group_key, label, enrolled, current_count, due, overdue, created_at from surveillance.compliance_snapshot
                 where snapshot_date = ? and group_by = ? and programme_code = ? order by group_key""",
                java.sql.Date.valueOf(support.today()), g, code);
        List<ComplianceMaths.Row> rows = new ArrayList<>();
        Instant refreshed;
        if (!snap.isEmpty()) {
            refreshed = ((Timestamp) snap.get(0).get("created_at")).toInstant();
            for (Map<String, Object> r : snap) {
                rows.add(ComplianceMaths.row((String) r.get("group_key"), (String) r.get("label"), new ComplianceMaths.Counts(
                        ((Number) r.get("enrolled")).intValue(), ((Number) r.get("current_count")).intValue(), ((Number) r.get("due")).intValue(),
                        ((Number) r.get("overdue")).intValue()), k));
            }
        } else {
            refreshed = support.now();
            Map<String, String> labels = new HashMap<>();
            live(g, programme, labels).forEach((key, c) -> rows.add(ComplianceMaths.row(key, labels.get(key), c, k)));
        }
        audit.record("SURVEILLANCE_COMPLIANCE_READ", "COMPLIANCE", g, AuditService.Outcome.SUCCESS, "rows=" + rows.size());
        return new Compliance(refreshed, g, programme, ComplianceMaths.overall(rows), rows);
    }

    @Transactional(readOnly = true)
    public List<TrendPoint> trend(CurrentUser user, String programme) {
        int k = support.minGroupSize();
        Map<LocalDate, int[]> byDate = new TreeMap<>();
        List<Map<String, Object>> rows = programme == null
                ? jdbc.queryForList("""
                        select snapshot_date, sum(enrolled) as enrolled, sum(overdue) as overdue from surveillance.compliance_snapshot
                         where group_by = 'PROGRAMME' and programme_code = '*' and snapshot_date >= ? group by snapshot_date""",
                java.sql.Date.valueOf(support.today().minusDays(365)))
                : jdbc.queryForList("""
                        select snapshot_date, enrolled, overdue from surveillance.compliance_snapshot
                         where group_by = 'PROGRAMME' and programme_code = '*' and group_key = ? and snapshot_date >= ?""",
                programme, java.sql.Date.valueOf(support.today().minusDays(365)));
        for (Map<String, Object> r : rows) {
            byDate.put(((java.sql.Date) r.get("snapshot_date")).toLocalDate(),
                    new int[] {((Number) r.get("enrolled")).intValue(), ((Number) r.get("overdue")).intValue()});
        }
        if (!byDate.containsKey(support.today())) {
            int enrolled = 0;
            int overdue = 0;
            for (ComplianceMaths.Counts c : live("PROGRAMME", programme, new HashMap<>()).values()) {
                enrolled += c.enrolled();
                overdue += c.overdue();
            }
            byDate.put(support.today(), new int[] {enrolled, overdue});
        }
        List<TrendPoint> out = new ArrayList<>();
        byDate.forEach((d, v) -> {
            if (v[0] >= k) {
                out.add(new TrendPoint(d, ComplianceMaths.pct(v[0], v[1])));
            }
        });
        audit.record("SURVEILLANCE_COMPLIANCE_TREND", "COMPLIANCE", programme, AuditService.Outcome.SUCCESS, "points=" + out.size());
        return out;
    }

    @Transactional(readOnly = true)
    public List<OverdueRow> overdue(CurrentUser user, String programme, String department) {
        LocalDate today = support.today();
        List<Enrolment> list = enrolments.findByActiveTrue().stream()
                .filter(e -> programme == null || programme.equals(e.getProgrammeCode()))
                .filter(e -> e.getNextDueOn().isBefore(today))
                .filter(e -> !(e.isExempt() && (e.getExemptUntil() == null || !e.getExemptUntil().isBefore(today))))
                .toList();
        Map<String, Employee> emps = support.employeesById(list.stream().map(Enrolment::getEmployeeId).distinct().toList());
        Map<String, Programme> programmes = catalog.activeByCode();
        List<OverdueRow> out = new ArrayList<>();
        for (Enrolment e : list) {
            Employee emp = emps.get(e.getEmployeeId());
            if (emp == null || department != null && !department.equals(emp.getDepartmentCode())) {
                continue;
            }
            Programme p = programmes.get(e.getProgrammeCode());
            out.add(new OverdueRow(e.getId(), emp.getEmployeeId(), emp.getDisplayName(), emp.getSubsidiaryCode(), emp.getDepartmentCode(),
                    e.getProgrammeCode(), p == null ? e.getProgrammeCode() : p.getTitle(), e.getNextDueOn(),
                    ChronoUnit.DAYS.between(e.getNextDueOn(), today), e.getInvitations(), e.getEscalatedAt()));
            if (out.size() >= 5000) {
                break;
            }
        }
        out.sort(Comparator.comparing(OverdueRow::nextDueOn));
        audit.record("SURVEILLANCE_OVERDUE_READ", "ENROLMENT", programme, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    /** Nightly snapshot of all groupings (all programmes and per programme). */
    @Transactional
    public int snapshot(LocalDate day) {
        jdbc.update("delete from surveillance.compliance_snapshot where snapshot_date = ?", java.sql.Date.valueOf(day));
        List<Object[]> batch = new ArrayList<>();
        Instant now = support.now();
        List<String> codes = new ArrayList<>();
        codes.add(null);
        codes.addAll(catalog.activeByCode().keySet());
        for (String g : GROUP_BY) {
            for (String code : codes) {
                if ("PROGRAMME".equals(g) && code != null) {
                    continue;
                }
                Map<String, String> labels = new HashMap<>();
                live(g, code, labels).forEach((key, c) -> batch.add(new Object[] {UUID.randomUUID(), java.sql.Date.valueOf(day), g,
                        code == null ? ALL : code, key, labels.getOrDefault(key, key), c.enrolled(), c.current(), c.due(), c.overdue(),
                        Timestamp.from(now)}));
            }
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate("""
                    insert into surveillance.compliance_snapshot (id, snapshot_date, group_by, programme_code, group_key, label, enrolled,
                                                                  current_count, due, overdue, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", batch);
        }
        return batch.size();
    }

    static void requireGroupBy(String groupBy) {
        if (groupBy != null && !GROUP_BY.contains(groupBy)) {
            throw ApiException.badRequest("invalid_group_by", "groupBy is PROGRAMME, SUBSIDIARY or DEPARTMENT");
        }
    }
}
