package com.wellnessz.e34.anomaly;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.wellnessz.e34.identity.EndpointRepository;

/** Anomaly rules ({@code devicesec.anomaly_rule}) with a short read cache, invalidated on every update. */
@Repository
public class AnomalyRuleRepository {

    private static final long CACHE_MILLIS = 10_000;

    /**
     * @param action           ALERT | REJECT_ALERT | THROTTLE | ISOLATE | NOTIFY
     * @param threshold        events in the window that raise the alert (MESSAGE_RATE_SPIKE: ceiling per minute;
     *                         CERTIFICATE_EXPIRING: warning days)
     * @param isolateThreshold events in the window that isolate the endpoint (action ISOLATE); {@code null} = at the threshold
     */
    public record AnomalyRule(String code, String description, String severity, boolean enabled, int threshold, int windowMinutes,
                              String action, Integer isolateThreshold, String updatedBy, Instant updatedAt) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile Map<String, AnomalyRule> cache = Map.of();
    private volatile long loadedAt;

    public AnomalyRuleRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<AnomalyRule> all() {
        return jdbc.query("select code, description, severity, enabled, threshold, window_minutes, action, isolate_threshold, updated_by, updated_at "
                + "from devicesec.anomaly_rule order by code", (rs, i) -> new AnomalyRule(rs.getString("code"), rs.getString("description"),
                rs.getString("severity"), rs.getBoolean("enabled"), rs.getInt("threshold"), rs.getInt("window_minutes"), rs.getString("action"),
                (Integer) rs.getObject("isolate_threshold"), rs.getString("updated_by"), EndpointRepository.inst(rs, "updated_at")));
    }

    public Optional<AnomalyRule> get(String code) {
        long now = clock.millis();
        Map<String, AnomalyRule> c = cache;
        if (c.isEmpty() || now - loadedAt > CACHE_MILLIS) {
            c = all().stream().collect(Collectors.toUnmodifiableMap(AnomalyRule::code, Function.identity()));
            cache = c;
            loadedAt = now;
        }
        return Optional.ofNullable(c.get(code));
    }

    public int update(String code, String severity, boolean enabled, int threshold, int windowMinutes, String action, Integer isolateThreshold,
                      String actor) {
        int n = jdbc.update("update devicesec.anomaly_rule set severity = ?, enabled = ?, threshold = ?, window_minutes = ?, action = ?, "
                + "isolate_threshold = ?, updated_by = ?, updated_at = ? where code = ?", severity, enabled, threshold, windowMinutes, action,
                isolateThreshold, actor, EndpointRepository.ts(clock.instant()), code);
        invalidate();
        return n;
    }

    public void invalidate() {
        cache = Map.of();
    }
}
