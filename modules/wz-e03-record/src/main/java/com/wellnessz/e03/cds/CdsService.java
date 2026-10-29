package com.wellnessz.e03.cds;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clinical decision support (E03-F05): drug-class allergy checks (incl. cross-reactivity), a curated interaction table
 * and duplicate-test detection (same code within 30 days). Blocking alerts are returned as {@code 409 cds_alert} unless
 * every alert carries an override reason; every fired alert is logged for the alert-fatigue statistics.
 */
@Service
public class CdsService {

    static final Duration DUPLICATE_WINDOW = Duration.ofDays(30);

    private final JdbcTemplate jdbc;
    private final CdsAlertLog log;

    public CdsService(JdbcTemplate jdbc, CdsAlertLog log) {
        this.jdbc = jdbc;
        this.log = log;
    }

    record Rule(String id, String type, String classA, String classB, String severity, String message, String reference) {
    }

    /** A recorded allergy or medication reduced to the text used for class matching. */
    public record Substance(String label, String code) {
    }

    // ------------------------------------------------------------------ medication checks

    /**
     * Checks a medication about to be recorded against the employee's allergies and active medications.
     *
     * @param medication  name / display of the new medication
     * @param allergies   recorded allergies (substance display + code)
     * @param activeMeds  names of the employee's active medications
     */
    @Transactional(readOnly = true)
    public List<CdsAlert> medicationAlerts(String medication, Collection<Substance> allergies, Collection<String> activeMeds) {
        Map<String, String> drugClasses = drugClasses();
        Set<String> newClasses = classesOf(medication, drugClasses);
        List<CdsAlert> alerts = new ArrayList<>();
        if (newClasses.isEmpty()) {
            return alerts;
        }
        List<Rule> rules = rules();
        for (Substance allergy : allergies) {
            Set<String> allergyClasses = classesOf(allergy.label(), drugClasses);
            for (String c : newClasses) {
                if (allergyClasses.contains(c)) {
                    alerts.add(new CdsAlert(CdsAlert.ALLERGY_CLASS, "CRITICAL",
                            "Recorded allergy (" + allergy.label() + ") to the same drug class as " + medication + " (" + c.toLowerCase(Locale.ROOT) + ")",
                            "Allergy record / drug-class map"));
                }
            }
            for (Rule r : rules) {
                if ("ALLERGY_CROSS".equals(r.type()) && allergyClasses.contains(r.classA()) && newClasses.contains(r.classB())) {
                    alerts.add(new CdsAlert(CdsAlert.ALLERGY_CLASS, r.severity(), r.message(), r.reference()));
                }
            }
        }
        for (String other : activeMeds) {
            Set<String> otherClasses = classesOf(other, drugClasses);
            for (Rule r : rules) {
                if (!"INTERACTION".equals(r.type())) {
                    continue;
                }
                boolean hit = newClasses.contains(r.classA()) && otherClasses.contains(r.classB())
                        || newClasses.contains(r.classB()) && otherClasses.contains(r.classA());
                if (hit) {
                    alerts.add(new CdsAlert(CdsAlert.INTERACTION, r.severity(), r.message() + " (with " + other + ")", r.reference()));
                }
            }
        }
        return dedupe(alerts);
    }

    /** Drug classes of a free-text medication / substance name: whole-word match against the drug-class map. */
    static Set<String> classesOf(String text, Map<String, String> drugClasses) {
        Set<String> out = new HashSet<>();
        if (text == null) {
            return out;
        }
        for (String token : tokens(text)) {
            String c = drugClasses.get(token);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder w = new StringBuilder();
        String lower = text.toLowerCase(Locale.ROOT);
        int max = Math.min(lower.length(), 500);
        for (int i = 0; i < max; i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-') {
                w.append(c);
            } else if (!w.isEmpty()) {
                out.add(w.toString());
                w.setLength(0);
            }
        }
        if (!w.isEmpty()) {
            out.add(w.toString());
        }
        return out;
    }

    // ------------------------------------------------------------------ order checks

    /** Same test (code) ordered or resulted for the employee within the last 30 days. */
    @Transactional(readOnly = true)
    public List<CdsAlert> duplicateTestAlerts(String employeeId, Collection<String> codes, Instant now) {
        List<CdsAlert> alerts = new ArrayList<>();
        Timestamp since = Timestamp.from(now.minus(DUPLICATE_WINDOW));
        for (String code : codes) {
            Integer orders = jdbc.queryForObject("""
                    select count(*) from ohrecord.lab_order
                     where employee_id = ? and status <> 'CANCELLED' and ordered_at >= ? and items like ?
                    """, Integer.class, employeeId, since, "%\"code\":\"" + escapeLike(code) + "\"%");
            Integer results = jdbc.queryForObject(
                    "select count(*) from ohrecord.result where employee_id = ? and code = ? and effective_at >= ?",
                    Integer.class, employeeId, code, since);
            if ((orders != null && orders > 0) || (results != null && results > 0)) {
                alerts.add(new CdsAlert(CdsAlert.DUPLICATE_TEST, "WARNING",
                        "Test " + code + " was already ordered or resulted within the last 30 days", "Duplicate-test rule (30 days)"));
            }
        }
        return alerts;
    }

    private static String escapeLike(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '%' || c == '_' || c == '\\') {
                b.append('\\');
            }
            b.append(c);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ enforcement & statistics

    /**
     * Logs the fired alerts and throws {@link CdsAlertException} unless every alert code has an override with a reason.
     *
     * @return the overrides that were applied (alertCode → reason)
     */
    public Map<String, String> enforce(String employeeId, String context, String username, List<CdsAlert> alerts,
                                       List<CdsOverride> overrides) {
        if (alerts.isEmpty()) {
            return Map.of();
        }
        Map<String, String> reasons = new LinkedHashMap<>();
        if (overrides != null) {
            for (CdsOverride o : overrides) {
                if (o != null && o.reason() != null && !o.reason().isBlank()) {
                    reasons.put(o.alertCode(), o.reason().trim());
                }
            }
        }
        boolean allOverridden = alerts.stream().allMatch(a -> reasons.containsKey(a.code()));
        Map<String, String> applied = new HashMap<>();
        if (allOverridden) {
            alerts.forEach(a -> applied.put(a.code(), reasons.get(a.code())));
        }
        log.log(employeeId, context, username, alerts, applied);
        if (!allOverridden) {
            throw new CdsAlertException(alerts);
        }
        return applied;
    }

    public record CodeStat(String code, long fired, long overridden) {
    }

    public record Statistics(Instant from, Instant to, long fired, long overridden, long overriddenWithoutReason, double overrideRate,
                             List<CodeStat> byCode) {
    }

    @Transactional(readOnly = true)
    public Statistics statistics(Instant from, Instant to) {
        List<CodeStat> byCode = jdbc.query("""
                select alert_code, count(*), count(*) filter (where overridden) from ohrecord.cds_alert_log
                 where fired_at >= ? and fired_at < ? group by alert_code order by alert_code
                """, (rs, i) -> new CodeStat(rs.getString(1), rs.getLong(2), rs.getLong(3)), Timestamp.from(from), Timestamp.from(to));
        long fired = byCode.stream().mapToLong(CodeStat::fired).sum();
        long overridden = byCode.stream().mapToLong(CodeStat::overridden).sum();
        Long withoutReason = jdbc.queryForObject("""
                select count(*) from ohrecord.cds_alert_log
                 where fired_at >= ? and fired_at < ? and overridden and override_reason is null
                """, Long.class, Timestamp.from(from), Timestamp.from(to));
        double rate = fired == 0 ? 0.0 : Math.round(overridden * 1000.0 / fired) / 1000.0;
        return new Statistics(from, to, fired, overridden, withoutReason == null ? 0 : withoutReason, rate, byCode);
    }

    // ------------------------------------------------------------------ reference data

    private Map<String, String> drugClasses() {
        Map<String, String> m = new HashMap<>();
        jdbc.query("select drug_name, drug_class from ohrecord.drug_class", rs -> {
            m.put(rs.getString(1), rs.getString(2));
        });
        return m;
    }

    private List<Rule> rules() {
        return jdbc.query("select id, rule_type, class_a, class_b, severity, message, reference from ohrecord.cds_rule order by id",
                (rs, i) -> new Rule(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7)));
    }

    private static List<CdsAlert> dedupe(List<CdsAlert> alerts) {
        Map<String, CdsAlert> m = new LinkedHashMap<>();
        alerts.forEach(a -> m.putIfAbsent(a.code() + "|" + a.message(), a));
        return new ArrayList<>(m.values());
    }
}
