package com.wellnessz.e12.screening;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e12.campaign.PromotionCampaignService;
import com.wellnessz.e12.model.Campaign;
import com.wellnessz.e12.model.E12EducationAttendanceRepository;
import com.wellnessz.e12.model.EducationAttendance;
import com.wellnessz.e12.rules.Suppression;
import com.wellnessz.e12.support.PromotionSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.JobCode;
import com.wellnessz.masterdata.JobCodeRepository;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Hearing-conservation (and other) education attendance recorded alongside audiometry (E12-BL-007) and the programme
 * compliance report: noise-exposed population per unit, attended education and audiometry in the last 12 months –
 * aggregated and suppressed below the minimum group size.
 */
@Service
public class EducationService {

    public static final String PROGRAMMES = "HEARING_CONSERVATION|MANUAL_HANDLING|FATIGUE_SLEEP|METABOLIC_HEALTH|MENTAL_WELLBEING";

    private final E12EducationAttendanceRepository attendance;
    private final PromotionCampaignService campaigns;
    private final JobCodeRepository jobCodes;
    private final JdbcTemplate jdbc;
    private final PromotionSupport support;
    private final AuditService audit;

    public EducationService(E12EducationAttendanceRepository attendance, PromotionCampaignService campaigns, JobCodeRepository jobCodes, JdbcTemplate jdbc,
                            PromotionSupport support, AuditService audit) {
        this.attendance = attendance;
        this.campaigns = campaigns;
        this.jobCodes = jobCodes;
        this.jdbc = jdbc;
        this.support = support;
        this.audit = audit;
    }

    public record AttendanceCommand(@NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{1,64}") String employeeId,
                                    @NotBlank @Pattern(regexp = PROGRAMMES) String programme, @NotNull LocalDate sessionDate, UUID campaignId) {
    }

    public record AttendanceView(UUID id, String employeeId, String displayName, String unitCode, String programme, LocalDate sessionDate,
                                 UUID campaignId, String recordedBy, Instant recordedAt) {
    }

    public record ReportRow(String unitCode, String programme, Long population, Long attended, BigDecimal attendedPct, Long audiometry,
                            BigDecimal audiometryPct, boolean suppressed) {
    }

    public record Report(String programme, LocalDate from, LocalDate to, List<ReportRow> units, ReportRow total) {
    }

    AttendanceView view(EducationAttendance a) {
        return new AttendanceView(a.getId(), a.getEmployeeId(), support.displayName(a.getEmployeeId()), a.getUnitCode(), a.getProgramme(),
                a.getSessionDate(), a.getCampaignId(), a.getRecordedBy(), a.getRecordedAt());
    }

    @Transactional
    public AttendanceView record(CurrentUser user, AttendanceCommand cmd) {
        Employee e = support.requireEmployee(cmd.employeeId());
        if (cmd.sessionDate().isAfter(support.today())) {
            throw PromotionSupport.unprocessable("future_session", "Attendance can only be recorded for past or current sessions");
        }
        if (attendance.existsByEmployeeIdAndProgrammeAndSessionDate(e.getEmployeeId(), cmd.programme(), cmd.sessionDate())) {
            throw ApiException.conflict("attendance_exists", "Attendance for this session is already recorded");
        }
        Campaign c = cmd.campaignId() == null ? null : campaigns.require(cmd.campaignId());
        EducationAttendance a = new EducationAttendance();
        a.setId(UUID.randomUUID());
        a.setEmployeeId(e.getEmployeeId());
        a.setUnitCode(e.getDepartmentCode());
        a.setProgramme(cmd.programme());
        a.setSessionDate(cmd.sessionDate());
        a.setCampaignId(c == null ? null : c.getId());
        a.setRecordedBy(user.username());
        a.setRecordedAt(support.now());
        attendance.save(a);
        if (c != null && ("ACTIVE".equals(c.getStatus()) || "COMPLETED".equals(c.getStatus()))) {
            campaigns.record(c, e, "EDUCATION_" + cmd.programme(), cmd.sessionDate().atStartOfDay().toInstant(ZoneOffset.UTC), null, "EDUCATION",
                    a.getId().toString());
        }
        audit.record("EDUCATION_ATTENDANCE_RECORD", "EDUCATION_ATTENDANCE", a.getId().toString(), AuditService.Outcome.SUCCESS, cmd.programme());
        return view(a);
    }

    @Transactional(readOnly = true)
    public List<AttendanceView> list(CurrentUser user, String employeeId, String programme) {
        List<EducationAttendance> all = employeeId != null ? attendance.findByEmployeeIdOrderBySessionDateDesc(employeeId)
                : attendance.findAllByOrderBySessionDateDesc();
        List<AttendanceView> out = all.stream().filter(a -> programme == null || programme.equals(a.getProgramme())).limit(2000).map(this::view)
                .toList();
        audit.record("EDUCATION_ATTENDANCE_LIST", "EDUCATION_ATTENDANCE", employeeId, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    /** Programme completeness by unit (aggregated, suppressed): population, education attended and audiometry in the window. */
    @Transactional(readOnly = true)
    public Report report(CurrentUser user, String programme, int months) {
        String prog = programme == null ? "HEARING_CONSERVATION" : programme;
        LocalDate to = support.today();
        LocalDate from = to.minusMonths(months);
        Set<String> attended = new HashSet<>();
        attendance.findByProgrammeAndSessionDateGreaterThanEqual(prog, from).forEach(a -> attended.add(a.getEmployeeId()));
        Set<String> audiometry = new HashSet<>(jdbc.queryForList(
                "select distinct employee_id from ohrecord.result where category = 'AUDIOMETRY' and effective_at >= ?", String.class,
                Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC))));
        Set<String> noiseJobs = new HashSet<>();
        for (JobCode j : jobCodes.findAll()) {
            if (PromotionSupport.csv(j.getHazardCodes()).contains("NOISE")) {
                noiseJobs.add(j.getCode());
            }
        }
        Map<String, long[]> byUnit = new TreeMap<>();
        for (Employee e : support.activeEmployees()) {
            boolean inPopulation = "HEARING_CONSERVATION".equals(prog) ? noiseJobs.contains(e.getJobCode()) : attended.contains(e.getEmployeeId());
            if (!inPopulation || e.getDepartmentCode() == null) {
                continue;
            }
            long[] c = byUnit.computeIfAbsent(e.getDepartmentCode(), k -> new long[3]);
            c[0]++;
            if (attended.contains(e.getEmployeeId())) {
                c[1]++;
            }
            if (audiometry.contains(e.getEmployeeId())) {
                c[2]++;
            }
        }
        int k = support.minGroupSize();
        List<ReportRow> rows = new ArrayList<>();
        long[] total = new long[3];
        for (Map.Entry<String, long[]> u : byUnit.entrySet()) {
            long[] c = u.getValue();
            for (int i = 0; i < 3; i++) {
                total[i] += c[i];
            }
            rows.add(row(u.getKey(), prog, c, k));
        }
        audit.record("EDUCATION_REPORT", "EDUCATION_ATTENDANCE", null, AuditService.Outcome.SUCCESS, prog + " units=" + rows.size());
        return new Report(prog, from, to, rows, row("ALL", prog, total, k));
    }

    static ReportRow row(String unit, String programme, long[] c, int k) {
        boolean suppressed = Suppression.rate(c[0], c[1], k) || Suppression.rate(c[0], c[2], k);
        if (suppressed) {
            return new ReportRow(unit, programme, null, null, null, null, null, true);
        }
        return new ReportRow(unit, programme, c[0], c[1], Suppression.pct(c[1], c[0]), c[2], Suppression.pct(c[2], c[0]), false);
    }
}
