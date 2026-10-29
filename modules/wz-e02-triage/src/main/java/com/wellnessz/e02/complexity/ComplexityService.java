package com.wellnessz.e02.complexity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.CaseEnums.Intensity;
import com.wellnessz.casekernel.model.CaseEnums.Recordability;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.model.Urgency;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e02.model.ComplexityAssessment;
import com.wellnessz.e02.model.ComplexityAssessmentRepository;
import com.wellnessz.e02.model.DurationGuideline;
import com.wellnessz.e02.model.DurationGuidelineRepository;
import com.wellnessz.e02.model.RiskFlags;
import com.wellnessz.e02.model.RiskFlagsRepository;
import com.wellnessz.masterdata.ConfigService;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Case complexity / risk score 0–100 with visible drivers (E02-F06). Weights and the intensive threshold are E18
 * configuration ({@code triage.complexity.weights}, {@code triage.complexity.intensive-threshold}); crossing the
 * threshold switches the case to the INTENSIVE case-management pathway.
 */
@Service
public class ComplexityService {

    static final Map<String, Integer> DEFAULT_WEIGHTS = Map.of("SAFETY_CRITICAL", 10, "REPEAT_BODY_PART", 15, "COMORBIDITY", 15,
            "PSYCHOSOCIAL", 20, "CLAIM_OR_LITIGATION", 20, "DURATION_EXCEEDED", 20, "DURATION_DOUBLED", 10, "LOST_TIME", 10,
            "ESCALATED", 5, "HIGH_URGENCY", 10);
    static final Map<String, String> LABELS = Map.of("SAFETY_CRITICAL", "Safety-critical role", "REPEAT_BODY_PART",
            "Repeat injury to the same body part (12 months)", "COMORBIDITY", "Comorbidity flag", "PSYCHOSOCIAL",
            "Positive psychosocial screen", "CLAIM_OR_LITIGATION", "Claim or litigation", "DURATION_EXCEEDED",
            "Absence exceeds duration guideline", "DURATION_DOUBLED", "Absence more than twice the guideline", "LOST_TIME",
            "Lost-time recordable", "ESCALATED", "Escalated / SLA breached", "HIGH_URGENCY", "Emergency or urgent triage");

    private final OhCaseRepository cases;
    private final RiskFlagsRepository flags;
    private final ComplexityAssessmentRepository history;
    private final DurationGuidelineRepository guidelines;
    private final CaseAccess access;
    private final CaseTimeline timeline;
    private final ConfigService config;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;

    public ComplexityService(OhCaseRepository cases, RiskFlagsRepository flags, ComplexityAssessmentRepository history,
                             DurationGuidelineRepository guidelines, CaseAccess access, CaseTimeline timeline, ConfigService config,
                             JdbcTemplate jdbc, AuditService audit, JsonMapper json, Clock clock) {
        this.cases = cases;
        this.flags = flags;
        this.history = history;
        this.guidelines = guidelines;
        this.access = access;
        this.timeline = timeline;
        this.config = config;
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public record Driver(String code, String label, int points) {
    }

    public record Score(int score, Intensity intensity, List<Driver> drivers, Instant calculatedAt, Integer expectedDurationDays,
                        Integer actualAbsenceDays) {
    }

    @Transactional
    public Score recalculate(OhCase c) {
        Map<String, Integer> w = weights();
        List<Driver> drivers = new ArrayList<>();
        RiskFlags f = flags.findById(c.getId()).orElse(null);
        add(drivers, w, "SAFETY_CRITICAL", c.isSafetyCritical());
        add(drivers, w, "REPEAT_BODY_PART", c.getBodyPart() != null
                && cases.countByEmployeeAndBodyPartSince(c.getEmployeeId(), c.getBodyPart(), clock.instant().minus(Duration.ofDays(365))) >= 3);
        add(drivers, w, "COMORBIDITY", f != null && f.isComorbidity());
        add(drivers, w, "PSYCHOSOCIAL", f != null && f.isPsychosocialScreenPositive());
        add(drivers, w, "CLAIM_OR_LITIGATION", f != null && f.isClaimOrLitigation());
        Integer expected = f == null || f.getPrimaryIcd10() == null ? null
                : guidelines.findById(f.getPrimaryIcd10()).map(DurationGuideline::getExpectedDays).orElse(null);
        Integer actual = absenceDays(c);
        add(drivers, w, "DURATION_EXCEEDED", expected != null && actual != null && actual > expected);
        add(drivers, w, "DURATION_DOUBLED", expected != null && actual != null && actual > 2 * expected);
        add(drivers, w, "LOST_TIME", c.getRecordability() == Recordability.LOST_TIME);
        add(drivers, w, "ESCALATED", c.isEscalated());
        add(drivers, w, "HIGH_URGENCY", c.getUrgency() == Urgency.EMERGENCY || c.getUrgency() == Urgency.URGENT);
        int score = Math.min(100, drivers.stream().mapToInt(Driver::points).sum());
        int threshold = config.get("triage.complexity.intensive-threshold").map(v -> Integer.parseInt(v.trim())).orElse(60);
        Intensity intensity = score >= threshold ? Intensity.INTENSIVE : Intensity.STANDARD;
        if (intensity != c.getIntensity()) {
            timeline.record(c.getId(), "INTENSITY", "Case-management intensity " + intensity + " (score " + score + ")");
        }
        c.setComplexity(score, intensity, clock.instant());
        ComplexityAssessment a = new ComplexityAssessment();
        a.setId(UUID.randomUUID());
        a.setCaseId(c.getId());
        a.setScore(score);
        a.setDrivers(json.writeValueAsString(drivers));
        a.setIntensity(intensity.name());
        a.setExpectedDurationDays(expected);
        a.setActualAbsenceDays(actual);
        a.setCalculatedAt(clock.instant());
        history.save(a);
        return new Score(score, intensity, drivers, a.getCalculatedAt(), expected, actual);
    }

    @Transactional(readOnly = true)
    public Score current(CurrentUser user, UUID caseId) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.READ, "CASE_COMPLEXITY_READ");
        return history.findFirstByCaseIdOrderByCalculatedAtDesc(c.getId()).map(a -> new Score(a.getScore(),
                        Intensity.valueOf(a.getIntensity()), List.of(json.readValue(a.getDrivers(), Driver[].class)), a.getCalculatedAt(),
                        a.getExpectedDurationDays(), a.getActualAbsenceDays()))
                .orElse(new Score(0, Intensity.STANDARD, List.of(), null, null, null));
    }

    @Transactional
    public Score recalculate(CurrentUser user, UUID caseId) {
        return recalculate(access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "CASE_COMPLEXITY"));
    }

    public record FlagsView(boolean comorbidity, boolean psychosocialScreenPositive, boolean claimOrLitigation, String primaryIcd10) {
    }

    @Transactional
    public Score updateFlags(CurrentUser user, UUID caseId, FlagsView v) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "CASE_RISK_FLAGS");
        if (v.primaryIcd10() != null && !v.primaryIcd10().matches("[A-Z][0-9]{2}(\\.[0-9A-Z]{1,4})?")) {
            throw ApiException.badRequest("invalid_icd10", "Invalid ICD-10 code");
        }
        RiskFlags f = flags.findById(caseId).orElseGet(() -> {
            RiskFlags n = new RiskFlags();
            n.setCaseId(caseId);
            return n;
        });
        f.setComorbidity(v.comorbidity());
        f.setPsychosocialScreenPositive(v.psychosocialScreenPositive());
        f.setClaimOrLitigation(v.claimOrLitigation());
        f.setPrimaryIcd10(v.primaryIcd10());
        f.setUpdatedBy(user.username());
        f.setUpdatedAt(clock.instant());
        flags.save(f);
        audit.record("CASE_RISK_FLAGS", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, null);
        return recalculate(c);
    }

    @Transactional(readOnly = true)
    public FlagsView flags(CurrentUser user, UUID caseId) {
        access.clinical(user, caseId, AccessPolicy.Action.READ, "CASE_RISK_FLAGS_READ");
        return flags.findById(caseId).map(f -> new FlagsView(f.isComorbidity(), f.isPsychosocialScreenPositive(),
                f.isClaimOrLitigation(), f.getPrimaryIcd10())).orElse(new FlagsView(false, false, false, null));
    }

    /** Monthly calibration: score bands vs. outcomes (closed, escalated, mean days open). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> calibration(YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return jdbc.queryForList("""
                select case when coalesce(c.complexity_score,0) < 30 then '0-29' when c.complexity_score < 60 then '30-59' else '60-100' end band,
                       count(*) cases,
                       count(*) filter (where c.status in ('CLOSED','MERGED')) closed,
                       count(*) filter (where c.escalated) escalated,
                       round(avg(extract(epoch from (coalesce(case when c.status in ('CLOSED','MERGED') then c.updated_at end, now()) - c.created_at)) / 86400)::numeric, 1) mean_days_open
                  from casemgmt.oh_case c where c.created_at >= ? and c.created_at < ?
                 group by 1 order by 1""", java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
    }

    public List<DurationGuideline> guidelines(String icd) {
        return icd == null ? guidelines.findAll() : guidelines.findById(icd).map(List::of).orElse(List.of());
    }

    private Integer absenceDays(OhCase c) {
        LocalDate since = c.getOccurredOn() != null ? c.getOccurredOn() : c.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        Integer days = jdbc.queryForObject("""
                select coalesce(sum((coalesce(end_date, current_date) - greatest(start_date, ?)) + 1), 0)::int
                  from intake.absence_episode where employee_id = ? and coalesce(end_date, current_date) >= ?""",
                Integer.class, since, c.getEmployeeId(), since);
        return days == null || days == 0 ? null : days;
    }

    private Map<String, Integer> weights() {
        Map<String, Integer> w = new LinkedHashMap<>(DEFAULT_WEIGHTS);
        config.get("triage.complexity.weights").ifPresent(v -> {
            JsonNode n = json.readTree(v);
            n.propertyNames().forEach(k -> {
                if (w.containsKey(k)) {
                    w.put(k, Math.max(0, Math.min(50, n.get(k).asInt())));
                }
            });
        });
        return w;
    }

    private static void add(List<Driver> drivers, Map<String, Integer> w, String code, boolean present) {
        if (present && w.getOrDefault(code, 0) > 0) {
            drivers.add(new Driver(code, LABELS.get(code), w.get(code)));
        }
    }
}
