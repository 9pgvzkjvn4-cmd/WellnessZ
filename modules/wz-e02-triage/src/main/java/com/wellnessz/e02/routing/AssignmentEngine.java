package com.wellnessz.e02.routing;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.SafetyCategory;
import com.wellnessz.casekernel.model.Urgency;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.e02.model.AssignmentRule;
import com.wellnessz.e02.model.AssignmentRuleRepository;
import com.wellnessz.e02.model.WorkQueue;
import com.wellnessz.e02.model.WorkQueueRepository;
import com.wellnessz.masterdata.StaffMember;
import com.wellnessz.masterdata.StaffMemberRepository;

/**
 * Decides queue and owner for a case (E02-F02/F03):
 * <ol>
 *   <li>Restricted class (psychological / substance) → MENTAL_HEALTH queue.</li>
 *   <li>Safety-critical roles → AME_AEROMEDICAL (crew, ATC), FOOD_SAFETY (food handlers) or PHYSICIAN queue, with
 *       urgency raised to at least SEMI_URGENT.</li>
 *   <li>Otherwise the first matching assignment rule (subsidiary, site, language, case type, safety category).</li>
 * </ol>
 * Owner selection honours the queue's required role, rule filters (site, language, specialty), caseload caps and
 * out-of-office cover.
 */
@Component
public class AssignmentEngine {

    public static final String DEFAULT_QUEUE = "OH_GENERAL";

    private final AssignmentRuleRepository rules;
    private final WorkQueueRepository queues;
    private final StaffMemberRepository staff;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AssignmentEngine(AssignmentRuleRepository rules, WorkQueueRepository queues, StaffMemberRepository staff,
                            JdbcTemplate jdbc, Clock clock) {
        this.rules = rules;
        this.queues = queues;
        this.staff = staff;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Decision(Long ruleId, String queue, String username, Urgency minimumUrgency, String explanation) {
    }

    public Decision decide(OhCase c) {
        if (c.getClassification() == DataClassification.RESTRICTED_CLINICAL) {
            return withOwner(null, "MENTAL_HEALTH", "LEAST_LOADED", null, null, null, null, null, "Restricted class → MENTAL_HEALTH");
        }
        SafetyCategory sc = c.getSafetyCategory();
        if (sc != null) {
            String queue = sc.isAeromedical() ? "AME_AEROMEDICAL" : sc == SafetyCategory.FOOD_HANDLER ? "FOOD_SAFETY" : "PHYSICIAN";
            Decision d = withOwner(null, queue, "LEAST_LOADED", null, c.getLocationCode(), c.getLanguage(), null, Urgency.SEMI_URGENT,
                    "Safety-critical role " + sc + " → " + queue);
            return d;
        }
        for (AssignmentRule r : rules.findByActiveTrueOrderByPriorityAscIdAsc()) {
            if (matches(r, c)) {
                return withOwner(r.getId(), r.getTargetQueue(), r.getStrategy(), r.getTargetUsername(),
                        r.getSiteCode(), r.getLanguage(), r.getSpecialty(), null, "Rule '" + r.getName() + "'");
            }
        }
        return withOwner(null, DEFAULT_QUEUE, "NONE", null, null, null, null, null, "No rule matched → " + DEFAULT_QUEUE);
    }

    static boolean matches(AssignmentRule r, OhCase c) {
        return eq(r.getSubsidiaryCode(), c.getSubsidiaryCode()) && eq(r.getSiteCode(), c.getLocationCode())
                && eq(r.getLanguage(), c.getLanguage()) && eq(r.getCaseType(), c.getCaseType().name())
                && eq(r.getSafetyCategory(), c.getSafetyCategory() == null ? null : c.getSafetyCategory().name());
    }

    private static boolean eq(String condition, String value) {
        return condition == null || condition.equalsIgnoreCase(value);
    }

    private Decision withOwner(Long ruleId, String queueCode, String strategy, String targetUser, String site, String language,
                               String specialty, Urgency minimum, String why) {
        String owner = switch (strategy == null ? "NONE" : strategy) {
            case "SPECIFIC_USER" -> availableOrCover(targetUser);
            case "LEAST_LOADED", "ROUND_ROBIN" -> pick(queueCode, strategy, site, language, specialty);
            default -> null;
        };
        return new Decision(ruleId, queueCode, owner, minimum, why + (owner == null ? " (unassigned)" : " → " + owner));
    }

    private String availableOrCover(String username) {
        if (username == null) {
            return null;
        }
        LocalDate today = LocalDate.now(clock);
        return staff.findById(username).map(s -> s.isAvailableOn(today) ? s.getUsername()
                : s.getCoverUsername() != null ? availableOrCover(s.getCoverUsername()) : null).orElse(null);
    }

    private String pick(String queueCode, String strategy, String site, String language, String specialty) {
        String role = queues.findById(queueCode).map(WorkQueue::getRequiredRole).orElse("OH_CLINICIAN");
        LocalDate today = LocalDate.now(clock);
        Map<String, Long> load = openCaseload();
        List<StaffMember> pool = staff.findByActiveTrueOrderByDisplayName().stream()
                .filter(s -> s.hasRole(role) && s.isAvailableOn(today))
                .filter(s -> site == null || StaffMember.list(s.getSiteCodes()).contains(site))
                .filter(s -> language == null || StaffMember.list(s.getLanguages()).contains(language))
                .filter(s -> specialty == null || StaffMember.list(s.getSpecialties()).contains(specialty))
                .filter(s -> load.getOrDefault(s.getUsername(), 0L) < s.getCaseloadCap())
                .toList();
        if (pool.isEmpty() && (language != null || site != null)) {
            return pick(queueCode, strategy, null, null, specialty); // relax soft preferences rather than leave unassigned
        }
        Comparator<StaffMember> order = "ROUND_ROBIN".equals(strategy)
                ? Comparator.comparing((StaffMember s) -> lastAssigned().getOrDefault(s.getUsername(), java.time.Instant.EPOCH))
                : Comparator.comparingLong((StaffMember s) -> load.getOrDefault(s.getUsername(), 0L));
        return pool.stream().min(order.thenComparing(StaffMember::getUsername)).map(StaffMember::getUsername).orElse(null);
    }

    public Map<String, Long> openCaseload() {
        return jdbc.queryForList("""
                select owner_username u, count(*) n from casemgmt.oh_case
                 where owner_username is not null and status not in ('CLOSED','MERGED') group by owner_username""")
                .stream().collect(Collectors.toMap(r -> (String) r.get("u"), r -> ((Number) r.get("n")).longValue()));
    }

    private Map<String, java.time.Instant> lastAssigned() {
        return jdbc.queryForList("select to_username u, max(assigned_at) t from triage.case_assignment where to_username is not null group by to_username")
                .stream().collect(Collectors.toMap(r -> (String) r.get("u"), r -> ((java.sql.Timestamp) r.get("t")).toInstant(),
                        (a, b) -> a, java.util.HashMap::new));
    }

    public Optional<WorkQueue> queue(String code) {
        return queues.findById(code);
    }

    public static Function<String, String> noop() {
        return Function.identity();
    }
}
