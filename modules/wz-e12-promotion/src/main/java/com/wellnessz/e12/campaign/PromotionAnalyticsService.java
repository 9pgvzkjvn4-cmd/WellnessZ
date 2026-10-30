package com.wellnessz.e12.campaign;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.e12.ergonomics.ErgonomicsService;
import com.wellnessz.e12.model.Campaign;
import com.wellnessz.e12.model.CampaignInvitation;
import com.wellnessz.e12.model.E12CampaignInvitationRepository;
import com.wellnessz.e12.model.E12CampaignRepository;
import com.wellnessz.e12.model.E12ErgonomicActionRepository;
import com.wellnessz.e12.model.E12ParticipationRepository;
import com.wellnessz.e12.model.E12ScreeningEventRepository;
import com.wellnessz.e12.model.E12ScreeningResultRepository;
import com.wellnessz.e12.model.E12WearableDailyRepository;
import com.wellnessz.e12.model.Participation;
import com.wellnessz.e12.model.ScreeningEvent;
import com.wellnessz.e12.model.ScreeningResult;
import com.wellnessz.e12.model.WearableDaily;
import com.wellnessz.e12.rules.InsightRules;
import com.wellnessz.e12.rules.ScreeningThresholds;
import com.wellnessz.e12.rules.Suppression;
import com.wellnessz.e12.support.PromotionSupport;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * Anonymised prevention analytics (E12-F01 / F05): hotspot insights from kernel cases, intake incidents, E03 results and E12
 * screenings; campaign participation and outcome reports; the manager workspace's unit participation. Everything leaving
 * this service is aggregated and suppressed below {@code wellnessz.analytics.min-group-size} – never person-level.
 */
@Service
public class PromotionAnalyticsService {

    static final Set<String> METABOLIC_FLAGS = Set.of("SYSTOLIC_HIGH", "DIASTOLIC_HIGH", "BMI_HIGH", "GLUCOSE_HIGH", "CHOLESTEROL_HIGH", "WAIST_HIGH");
    static final List<String> WEARABLE_METRICS = List.of("STEPS", "SLEEP_MINUTES", "ACTIVE_MINUTES");

    private final JdbcTemplate jdbc;
    private final E12CampaignRepository campaigns;
    private final E12CampaignInvitationRepository invitations;
    private final E12ParticipationRepository participation;
    private final E12ScreeningEventRepository events;
    private final E12ScreeningResultRepository results;
    private final E12WearableDailyRepository wearableDaily;
    private final E12ErgonomicActionRepository actions;
    private final PromotionCampaignService campaignService;
    private final PromotionSupport support;
    private final AuditService audit;

    public PromotionAnalyticsService(JdbcTemplate jdbc, E12CampaignRepository campaigns, E12CampaignInvitationRepository invitations,
                                     E12ParticipationRepository participation, E12ScreeningEventRepository events, E12ScreeningResultRepository results,
                                     E12WearableDailyRepository wearableDaily, E12ErgonomicActionRepository actions, PromotionCampaignService campaignService,
                                     PromotionSupport support, AuditService audit) {
        this.jdbc = jdbc;
        this.campaigns = campaigns;
        this.invitations = invitations;
        this.participation = participation;
        this.events = events;
        this.results = results;
        this.wearableDaily = wearableDaily;
        this.actions = actions;
        this.campaignService = campaignService;
        this.support = support;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ insights

    @Transactional(readOnly = true)
    public List<InsightRules.Insight> insights(CurrentUser user, String risk, int months) {
        LocalDate to = support.today();
        LocalDate from = to.minusMonths(months);
        Timestamp since = Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC));
        List<InsightRules.Count> counts = new ArrayList<>();
        // kernel cases (restricted class excluded – never broken down by unit)
        jdbc.query("""
                select org_unit_code, category, body_part, shift, count(*) as n from casemgmt.oh_case
                 where classification <> 'RESTRICTED_CLINICAL' and created_at >= ? and org_unit_code is not null
                 group by org_unit_code, category, body_part, shift""", rs -> {
            String unit = rs.getString(1);
            long n = rs.getLong(5);
            if ("INJURY".equals(rs.getString(2)) && InsightRules.isMsk(rs.getString(3))) {
                counts.add(new InsightRules.Count(unit, InsightRules.MSK, "MSK_INJURY_CASES", n));
            }
            if (InsightRules.isNightShift(rs.getString(4))) {
                counts.add(new InsightRules.Count(unit, InsightRules.FATIGUE, "NIGHT_SHIFT_CASES", n));
            }
        }, since);
        // EHS incidents
        jdbc.query("""
                select org_unit_code, injury, body_part, shift, exposure_type, count(*) as n from intake.ehs_incident
                 where coalesce(occurred_at, received_at) >= ? and org_unit_code is not null
                 group by org_unit_code, injury, body_part, shift, exposure_type""", rs -> {
            String unit = rs.getString(1);
            long n = rs.getLong(6);
            if (rs.getBoolean(2) && InsightRules.isMsk(rs.getString(3))) {
                counts.add(new InsightRules.Count(unit, InsightRules.MSK, "MSK_INCIDENTS", n));
            }
            if (InsightRules.isNightShift(rs.getString(4))) {
                counts.add(new InsightRules.Count(unit, InsightRules.FATIGUE, "NIGHT_SHIFT_INCIDENTS", n));
            }
            String exposure = rs.getString(5);
            if (exposure != null && exposure.toUpperCase(Locale.ROOT).contains("NOISE")) {
                counts.add(new InsightRules.Count(unit, InsightRules.HEARING, "NOISE_EXPOSURE_INCIDENTS", n));
            }
        }, since);
        // E03 audiometry results (distinct employees with an abnormal audiogram / standard threshold shift)
        Map<String, Set<String>> abnormal = new HashMap<>();
        Map<String, Set<String>> sts = new HashMap<>();
        jdbc.query("""
                select e.department_code, r.employee_id, r.detail from ohrecord.result r
                  join masterdata.employee e on e.employee_id = r.employee_id
                 where r.category = 'AUDIOMETRY' and r.flag = 'ABNORMAL' and r.effective_at >= ? and e.department_code is not null""", rs -> {
            String unit = rs.getString(1);
            abnormal.computeIfAbsent(unit, k -> new HashSet<>()).add(rs.getString(2));
            if (stsFlag(rs.getString(3))) {
                sts.computeIfAbsent(unit, k -> new HashSet<>()).add(rs.getString(2));
            }
        }, since);
        abnormal.forEach((u, s) -> counts.add(new InsightRules.Count(u, InsightRules.HEARING, "AUDIOMETRY_ABNORMAL", s.size())));
        sts.forEach((u, s) -> counts.add(new InsightRules.Count(u, InsightRules.HEARING, "AUDIOMETRY_STS", s.size())));
        // E12 screening results (flags decrypted in memory, only counted)
        for (ScreeningResult r : results.findByRecordedAtGreaterThanEqual(since.toInstant())) {
            if (!"ABNORMAL".equals(r.getOutcome()) || r.getUnitCode() == null) {
                continue;
            }
            List<String> flags = PromotionSupport.csv(r.getFlags());
            if (flags.stream().anyMatch(METABOLIC_FLAGS::contains)) {
                counts.add(new InsightRules.Count(r.getUnitCode(), InsightRules.METABOLIC, "ABNORMAL_BIOMETRIC_SCREENS", 1));
            }
            if (flags.contains("MSK_SCORE_HIGH")) {
                counts.add(new InsightRules.Count(r.getUnitCode(), InsightRules.MSK, "HIGH_MSK_SCORE_SCREENS", 1));
            }
            if (flags.contains("WELLBEING_SCORE_LOW")) {
                counts.add(new InsightRules.Count(r.getUnitCode(), InsightRules.MENTAL_WELLBEING, "LOW_WELLBEING_SCREENS", 1));
            }
        }
        List<InsightRules.Count> filtered = risk == null ? counts : counts.stream().filter(c -> risk.equals(c.risk())).toList();
        List<InsightRules.Insight> out = InsightRules.publish(filtered, from + "/" + to, support.minGroupSize());
        audit.record("PROMOTION_INSIGHTS", "INSIGHT", null, AuditService.Outcome.SUCCESS, "rows=" + out.size() + " months=" + months);
        return out;
    }

    boolean stsFlag(String detail) {
        if (detail == null || detail.isBlank()) {
            return false;
        }
        try {
            Object s = support.readMap(detail).get("sts");
            if (s instanceof Map<?, ?> m) {
                return m.values().stream().anyMatch(Boolean.TRUE::equals);
            }
            return Boolean.TRUE.equals(s);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ campaign report

    public record ParticipationRow(String key, Long invited, Long participated, BigDecimal pct, boolean suppressed) {
    }

    public record OutcomeRow(String metric, BigDecimal baseline, BigDecimal current, BigDecimal delta, Long n, boolean suppressed) {
    }

    public record KpiRow(String metric, BigDecimal target, BigDecimal actual, Boolean met) {
    }

    public record Report(UUID campaignId, String ref, String title, String risk, String status, String groupBy, int minGroupSize,
                         List<ParticipationRow> participation, ParticipationRow total, List<OutcomeRow> outcomes, List<KpiRow> kpis) {
    }

    static ParticipationRow row(String key, long invited, long participated, int k) {
        if (Suppression.rate(invited, participated, k)) {
            return new ParticipationRow(key, null, null, null, true);
        }
        return new ParticipationRow(key, invited, participated, Suppression.pct(participated, invited), false);
    }

    /** Participation per group: invited population vs distinct participants (participants are always part of the population). */
    static List<ParticipationRow> participation(List<CampaignInvitation> invited, Set<String> participants, Function<CampaignInvitation, String> key,
                                                int k) {
        Map<String, long[]> groups = new TreeMap<>();
        for (CampaignInvitation i : invited) {
            long[] c = groups.computeIfAbsent(String.valueOf(key.apply(i)), x -> new long[2]);
            c[0]++;
            if (participants.contains(i.getEmployeeId())) {
                c[1]++;
            }
        }
        List<ParticipationRow> out = new ArrayList<>();
        groups.forEach((g, c) -> out.add(row(g, c[0], c[1], k)));
        // complementary suppression: one hidden group could be recovered from the total
        long hidden = out.stream().filter(ParticipationRow::suppressed).count();
        if (hidden == 1 && out.size() > 1) {
            out.stream().filter(r -> !r.suppressed()).min(Comparator.comparingLong(ParticipationRow::invited)).ifPresent(r -> {
                int idx = out.indexOf(r);
                out.set(idx, new ParticipationRow(r.key(), null, null, null, true));
            });
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Report report(CurrentUser user, UUID campaignId, String groupBy) {
        Campaign c = campaignService.require(campaignId);
        String g = normaliseGroupBy(groupBy);
        int k = support.minGroupSize();
        List<CampaignInvitation> invited = invitations.findByCampaignId(campaignId);
        List<Participation> parts = participation.findByCampaignId(campaignId);
        Set<String> participants = new HashSet<>();
        parts.forEach(p -> participants.add(p.getEmployeeId()));
        Map<String, Employee> emps = support.employeesById(invited.stream().map(CampaignInvitation::getEmployeeId).toList());
        Function<CampaignInvitation, String> key = switch (g) {
            case "subsidiary" -> i -> attr(emps.get(i.getEmployeeId()), Employee::getSubsidiaryCode);
            case "jobCode" -> i -> attr(emps.get(i.getEmployeeId()), Employee::getJobCode);
            case "location" -> i -> attr(emps.get(i.getEmployeeId()), Employee::getLocationCode);
            default -> i -> i.getUnitCode() == null ? "UNKNOWN" : i.getUnitCode();
        };
        List<ParticipationRow> rows = participation(invited, participants, key, k);
        long totalParticipated = invited.stream().filter(i -> participants.contains(i.getEmployeeId())).count();
        ParticipationRow total = row("TOTAL", invited.size(), totalParticipated, k);
        List<OutcomeRow> outcomes = new ArrayList<>();
        outcomes.addAll(screeningOutcomes(campaignId, k));
        outcomes.addAll(wearableOutcomes(campaignId, k));
        OutcomeRow msk = "MSK".equals(c.getRisk()) ? mskCaseOutcome(c, k) : null;
        if (msk != null) {
            outcomes.add(msk);
        }
        List<KpiRow> kpis = new ArrayList<>();
        for (PromotionCampaignService.Kpi kpi : campaignService.kpis(c)) {
            BigDecimal actual = switch (kpi.metric()) {
                case "PARTICIPATION_PCT" -> total.pct();
                case "ERGONOMIC_ACTIONS_CLOSED_60D_PCT" -> ErgonomicsService.kpi(actions.findByUnitCodeInOrderByDueDateAsc(PromotionSupport.csv(c.getTargetUnits())),
                        support.today(), support.configInt("promotion.ergonomics.closure-target-days", 60), 80).pct();
                case "ABNORMAL_FOLLOW_UP_PCT" -> followUpPct(campaignId, k);
                case "MSK_CASE_RATE_CHANGE_PCT" -> msk == null || msk.suppressed() || msk.baseline() == null || msk.baseline().signum() == 0 ? null
                        : msk.delta().multiply(BigDecimal.valueOf(100)).divide(msk.baseline(), 1, RoundingMode.HALF_UP);
                default -> null;
            };
            Boolean met = actual == null ? null : kpi.target().signum() < 0 ? actual.compareTo(kpi.target()) <= 0 : actual.compareTo(kpi.target()) >= 0;
            kpis.add(new KpiRow(kpi.metric(), kpi.target(), actual, met));
        }
        audit.record("CAMPAIGN_REPORT", "CAMPAIGN", campaignId.toString(), AuditService.Outcome.SUCCESS, "groupBy=" + g);
        return new Report(c.getId(), c.getCampaignRef(), c.getTitle(), c.getRisk(), c.getStatus(), g, k, rows, total, outcomes, kpis);
    }

    static String normaliseGroupBy(String groupBy) {
        if (groupBy == null) {
            return "unit";
        }
        return switch (groupBy.toLowerCase(Locale.ROOT).replace("_", "")) {
            case "subsidiary" -> "subsidiary";
            case "jobcode", "job" -> "jobCode";
            case "location", "site" -> "location";
            default -> "unit";
        };
    }

    private static String attr(Employee e, Function<Employee, String> f) {
        String v = e == null ? null : f.apply(e);
        return v == null ? "UNKNOWN" : v;
    }

    List<OutcomeRow> screeningOutcomes(UUID campaignId, int k) {
        List<ScreeningEvent> evs = events.findByCampaignIdOrderByEventDateAsc(campaignId);
        if (evs.isEmpty()) {
            return List.of();
        }
        Map<UUID, Integer> order = new HashMap<>();
        for (int i = 0; i < evs.size(); i++) {
            order.put(evs.get(i).getId(), i);
        }
        // metric -> event index -> values
        Map<String, TreeMap<Integer, List<BigDecimal>>> values = new LinkedHashMap<>();
        for (ScreeningResult r : results.findByEventIdIn(order.keySet())) {
            Map<String, BigDecimal> m = r.getMeasures() == null ? Map.of()
                    : support.read(r.getMeasures(), new TypeReference<LinkedHashMap<String, BigDecimal>>() {
                    });
            for (Map.Entry<String, BigDecimal> e : m.entrySet()) {
                values.computeIfAbsent(e.getKey(), x -> new TreeMap<>()).computeIfAbsent(order.get(r.getEventId()), x -> new ArrayList<>())
                        .add(e.getValue());
            }
        }
        List<OutcomeRow> out = new ArrayList<>();
        for (String metric : ScreeningThresholds.FLAGS.keySet()) {
            TreeMap<Integer, List<BigDecimal>> byEvent = values.get(metric);
            if (byEvent == null || byEvent.isEmpty()) {
                continue;
            }
            out.add(outcome(metric, byEvent.firstEntry().getValue(), byEvent.lastEntry().getValue(), k));
        }
        return out;
    }

    List<OutcomeRow> wearableOutcomes(UUID campaignId, int k) {
        List<OutcomeRow> out = new ArrayList<>();
        for (String metric : WEARABLE_METRICS) {
            Map<UUID, TreeMap<LocalDate, BigDecimal>> byEnrolment = new HashMap<>();
            for (WearableDaily d : wearableDaily.findByCampaignIdAndMetric(campaignId, metric)) {
                byEnrolment.computeIfAbsent(d.getEnrolmentId(), x -> new TreeMap<>()).put(d.getMetricDate(), new BigDecimal(d.getValue()));
            }
            if (byEnrolment.isEmpty()) {
                continue;
            }
            List<BigDecimal> baseline = new ArrayList<>();
            List<BigDecimal> current = new ArrayList<>();
            for (TreeMap<LocalDate, BigDecimal> series : byEnrolment.values()) {
                List<BigDecimal> all = new ArrayList<>(series.values());
                baseline.add(Suppression.mean(all.subList(0, Math.min(7, all.size()))));
                current.add(Suppression.mean(all.subList(Math.max(0, all.size() - 7), all.size())));
            }
            out.add(outcome(metric, baseline, current, k));
        }
        return out;
    }

    static OutcomeRow outcome(String metric, List<BigDecimal> baselineValues, List<BigDecimal> currentValues, int k) {
        long n = Math.min(baselineValues.size(), currentValues.size());
        if (n < k) {
            return new OutcomeRow(metric, null, null, null, null, true);
        }
        BigDecimal b = Suppression.mean(baselineValues);
        BigDecimal c = Suppression.mean(currentValues);
        return new OutcomeRow(metric, b, c, Suppression.delta(b, c), n, false);
    }

    /** MSK injury cases in the targeted units since the start vs. an equally long window before it. */
    OutcomeRow mskCaseOutcome(Campaign c, int k) {
        LocalDate today = support.today();
        if (c.getStartDate().isAfter(today)) {
            return null;
        }
        LocalDate end = c.getEndDate().isBefore(today) ? c.getEndDate() : today;
        long days = Math.max(1, ChronoUnit.DAYS.between(c.getStartDate(), end) + 1);
        Instant start = c.getStartDate().atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant before = start.minus(days, ChronoUnit.DAYS);
        Instant after = end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Set<String> units = new HashSet<>(PromotionSupport.csv(c.getTargetUnits()));
        long[] counts = new long[2];
        jdbc.query("""
                select org_unit_code, body_part, created_at from casemgmt.oh_case
                 where category = 'INJURY' and classification <> 'RESTRICTED_CLINICAL' and created_at >= ? and created_at < ?""", rs -> {
            if (units.contains(rs.getString(1)) && InsightRules.isMsk(rs.getString(2))) {
                counts[rs.getTimestamp(3).toInstant().isBefore(start) ? 0 : 1]++;
            }
        }, Timestamp.from(before), Timestamp.from(after));
        boolean suppressed = (counts[0] > 0 && counts[0] < k) || (counts[1] > 0 && counts[1] < k);
        if (suppressed) {
            return new OutcomeRow("MSK_INJURY_CASES", null, null, null, null, true);
        }
        BigDecimal b = BigDecimal.valueOf(counts[0]);
        BigDecimal cur = BigDecimal.valueOf(counts[1]);
        return new OutcomeRow("MSK_INJURY_CASES", b, cur, cur.subtract(b), counts[0] + counts[1], false);
    }

    BigDecimal followUpPct(UUID campaignId, int k) {
        List<UUID> ids = events.findByCampaignIdOrderByEventDateAsc(campaignId).stream().map(ScreeningEvent::getId).toList();
        if (ids.isEmpty()) {
            return null;
        }
        long abnormal = 0;
        long followed = 0;
        for (ScreeningResult r : results.findByEventIdIn(ids)) {
            if ("ABNORMAL".equals(r.getOutcome())) {
                abnormal++;
                if (r.getCaseId() != null) {
                    followed++;
                }
            }
        }
        return abnormal < k ? null : Suppression.pct(followed, abnormal);
    }

    public String reportCsv(CurrentUser user, UUID campaignId, String groupBy) {
        Report r = report(user, campaignId, groupBy);
        StringBuilder sb = new StringBuilder("section,key,invited,participated,pct,suppressed\n");
        for (ParticipationRow p : r.participation()) {
            sb.append("participation,").append(cell(p.key())).append(',').append(num(p.invited())).append(',').append(num(p.participated()))
                    .append(',').append(num(p.pct())).append(',').append(p.suppressed()).append('\n');
        }
        ParticipationRow t = r.total();
        sb.append("participation,TOTAL,").append(num(t.invited())).append(',').append(num(t.participated())).append(',').append(num(t.pct()))
                .append(',').append(t.suppressed()).append('\n');
        sb.append("section,metric,baseline,current,delta,suppressed\n");
        for (OutcomeRow o : r.outcomes()) {
            sb.append("outcome,").append(cell(o.metric())).append(',').append(num(o.baseline())).append(',').append(num(o.current())).append(',')
                    .append(num(o.delta())).append(',').append(o.suppressed()).append('\n');
        }
        return sb.toString();
    }

    private static String num(Object v) {
        return v == null ? "" : v instanceof BigDecimal b ? b.toPlainString() : v.toString();
    }

    /** CSV cell: quoted, and neutralised against spreadsheet formula injection. */
    static String cell(String v) {
        if (v == null) {
            return "";
        }
        String s = v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    // ------------------------------------------------------------------ manager workspace & employee view

    public record UnitParticipation(UUID campaignId, String title, String risk, String status, LocalDate startDate, LocalDate endDate,
                                    String unitCode, Long invited, Long participated, BigDecimal pct, boolean suppressed) {
    }

    @Transactional(readOnly = true)
    public List<UnitParticipation> workspace(CurrentUser user) {
        Set<String> units = support.unitsInScope(user);
        int k = support.minGroupSize();
        List<UnitParticipation> out = new ArrayList<>();
        for (Campaign c : campaigns.findAllByOrderByStartDateDesc()) {
            if (!"ACTIVE".equals(c.getStatus()) && !"COMPLETED".equals(c.getStatus())) {
                continue;
            }
            List<String> targeted = PromotionSupport.csv(c.getTargetUnits()).stream().filter(units::contains).toList();
            if (targeted.isEmpty()) {
                continue;
            }
            List<CampaignInvitation> inv = invitations.findByCampaignId(c.getId());
            Set<String> participants = new HashSet<>();
            participation.findByCampaignId(c.getId()).forEach(p -> participants.add(p.getEmployeeId()));
            for (String unit : targeted) {
                long invited = inv.stream().filter(i -> unit.equals(i.getUnitCode())).count();
                long participated = inv.stream().filter(i -> unit.equals(i.getUnitCode()) && participants.contains(i.getEmployeeId())).count();
                ParticipationRow r = row(unit, invited, participated, k);
                out.add(new UnitParticipation(c.getId(), c.getTitle(), c.getRisk(), c.getStatus(), c.getStartDate(), c.getEndDate(), unit, r.invited(),
                        r.participated(), r.pct(), r.suppressed()));
            }
        }
        audit.record("CAMPAIGN_WORKSPACE", "CAMPAIGN", null, AuditService.Outcome.SUCCESS, "units=" + units + " rows=" + out.size());
        return out;
    }

    public record MyInvitation(UUID campaignId, String title, String risk, String objective, LocalDate startDate, LocalDate endDate, String status,
                               Instant invitedAt, boolean participated) {
    }

    @Transactional(readOnly = true)
    public List<MyInvitation> myInvitations(String employeeId) {
        Set<UUID> joined = new HashSet<>();
        participation.findByEmployeeId(employeeId).forEach(p -> joined.add(p.getCampaignId()));
        List<MyInvitation> out = new ArrayList<>();
        for (CampaignInvitation i : invitations.findByEmployeeIdOrderByInvitedAtDesc(employeeId)) {
            campaigns.findById(i.getCampaignId()).filter(c -> !"CANCELLED".equals(c.getStatus()) && !"PLANNED".equals(c.getStatus()))
                    .ifPresent(c -> out.add(new MyInvitation(c.getId(), c.getTitle(), c.getRisk(), c.getObjective(), c.getStartDate(), c.getEndDate(),
                            c.getStatus(), i.getInvitedAt(), joined.contains(c.getId()))));
        }
        return out;
    }
}
