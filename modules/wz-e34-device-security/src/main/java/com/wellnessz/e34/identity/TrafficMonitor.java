package com.wellnessz.e34.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.e34.anomaly.AnomalyRuleRepository;
import com.wellnessz.e34.anomaly.SiteHours;
import com.wellnessz.e34.events.SecurityEventService;

/**
 * Per-endpoint traffic counters kept by the authenticator (successful authentications are not audited per request – contract
 * §15.3): requests per minute against the 7-day p95 baseline and the ceiling of {@code MESSAGE_RATE_SPIKE}, throttling after a
 * sustained spike, first authentication of an endpoint ({@code NEW_DEVICE_IDENTITY}) and data traffic outside clinic hours.
 * Minute buckets are flushed to {@code devicesec.traffic_minute} by the E34 jobs (baseline across replicas).
 */
@Component
public class TrafficMonitor {

    private static final Logger log = LoggerFactory.getLogger(TrafficMonitor.class);
    static final int SPIKE_FACTOR = 5;
    static final int MIN_DYNAMIC_LIMIT = 30;

    /** Mutable per-endpoint state, guarded by its own monitor. */
    static final class Counter {
        long minute = -1;
        int count;
        boolean spikeRaised;
        int consecutiveSpikeMinutes;
        long throttledThroughMinute = -1;
        Instant lastSeen;
    }

    record MinuteCount(String endpointId, long minute, int count) {
    }

    /** Outcome of recording one authenticated request. */
    public record Verdict(boolean throttled, boolean spike, int countThisMinute, int limit) {
    }

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<MinuteCount> pending = new ConcurrentLinkedQueue<>();
    private final Map<String, double[]> baselines = new ConcurrentHashMap<>(); // endpoint -> {p95, loadedAtMillis}
    private final Set<String> firstSeenChecked = ConcurrentHashMap.newKeySet();
    private final Set<String> offHoursRaised = ConcurrentHashMap.newKeySet();

    private final JdbcTemplate jdbc;
    private final EndpointRepository repo;
    private final AnomalyRuleRepository rules;
    private final SiteHours hours;
    private final SecurityEventService events;
    private final Clock clock;

    public TrafficMonitor(JdbcTemplate jdbc, EndpointRepository repo, AnomalyRuleRepository rules, SiteHours hours, SecurityEventService events,
                          Clock clock) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.rules = rules;
        this.hours = hours;
        this.events = events;
        this.clock = clock;
    }

    /** Records an authenticated request; publishes rate / identity / off-hours events at most once per window. */
    public Verdict record(String endpointId, String siteCode, String path) {
        Instant now = clock.instant();
        long minute = now.getEpochSecond() / 60;
        AnomalyRuleRepository.AnomalyRule rule = rules.get("MESSAGE_RATE_SPIKE").orElse(null);
        boolean rateRuleOn = rule != null && rule.enabled();
        int ceiling = rule == null ? 600 : rule.threshold();
        int sustained = rule == null ? 10 : rule.windowMinutes();
        int limit = limit(endpointId, ceiling);
        boolean publishSpike = false;
        boolean throttled;
        int count;
        Counter c = counters.computeIfAbsent(endpointId, k -> new Counter());
        synchronized (c) {
            if (c.minute != minute) {
                if (c.minute >= 0) {
                    pending.add(new MinuteCount(endpointId, c.minute, c.count));
                    boolean previousOver = c.count > limit;
                    c.consecutiveSpikeMinutes = previousOver && c.minute == minute - 1 ? c.consecutiveSpikeMinutes + 1 : previousOver ? 1 : 0;
                    if (rateRuleOn && "THROTTLE".equals(rule.action()) && c.consecutiveSpikeMinutes >= sustained) {
                        c.throttledThroughMinute = minute;
                    }
                }
                c.minute = minute;
                c.count = 0;
                c.spikeRaised = false;
            }
            c.count++;
            c.lastSeen = now;
            count = c.count;
            if (rateRuleOn && count > limit && !c.spikeRaised) {
                c.spikeRaised = true;
                publishSpike = true;
            }
            throttled = c.throttledThroughMinute >= minute;
        }
        if (publishSpike) {
            safePublish("MESSAGE_RATE_SPIKE", DeviceCodes.Severity.MEDIUM, endpointId, "rate_above_limit",
                    Map.of("rate", String.valueOf(count), "limit", String.valueOf(limit), "path", path == null ? "" : path));
        }
        if (firstSeenChecked.add(endpointId) && repo.markFirstSeen(endpointId, now) == 1) {
            safePublish("ENDPOINT_FIRST_AUTHENTICATED", DeviceCodes.Severity.MEDIUM, endpointId, "first_authentication", Map.of());
        }
        if (EndpointPolicy.isDataPath(path) && hours.outsideHours(siteCode, now)) {
            String key = endpointId + "@" + LocalDate.ofInstant(now, ZoneOffset.UTC);
            if (offHoursRaised.add(key)) {
                safePublish("OFF_HOURS_TRAFFIC", DeviceCodes.Severity.LOW, endpointId, "outside_clinic_hours",
                        Map.of("protocol", EndpointPolicy.resourceOf(path)));
            }
        }
        return new Verdict(throttled, publishSpike, count, limit);
    }

    /** Limit per minute: the ceiling, or 5x the 7-day p95 when that is lower (but never below 30 / minute). */
    int limit(String endpointId, int ceiling) {
        double[] b = baselines.get(endpointId);
        long nowMillis = clock.millis();
        if (b == null || nowMillis - (long) b[1] > 600_000) {
            Double p95 = null;
            try {
                p95 = jdbc.queryForObject("select percentile_cont(0.95) within group (order by requests) from devicesec.traffic_minute "
                        + "where endpoint_id = ? and minute_start >= ?", Double.class, endpointId,
                        EndpointRepository.ts(clock.instant().minusSeconds(7L * 24 * 3600)));
            } catch (RuntimeException e) {
                log.debug("Traffic baseline unavailable: {}", e.getClass().getSimpleName());
            }
            b = new double[] {p95 == null ? -1 : p95, nowMillis};
            baselines.put(endpointId, b);
        }
        return dynamicLimit(b[0], ceiling);
    }

    static int dynamicLimit(double p95, int ceiling) {
        if (p95 <= 0) {
            return ceiling;
        }
        long dynamic = Math.max(MIN_DYNAMIC_LIMIT, Math.round(Math.ceil(p95 * SPIKE_FACTOR)));
        return (int) Math.min(ceiling, dynamic);
    }

    /** Writes finished minute buckets and last-seen times (called every minute by the E34 jobs). */
    public int flush() {
        List<MinuteCount> batch = new ArrayList<>();
        MinuteCount m;
        while ((m = pending.poll()) != null) {
            batch.add(m);
        }
        for (MinuteCount mc : batch) {
            jdbc.update("insert into devicesec.traffic_minute (endpoint_id, minute_start, requests) values (?,?,?) "
                    + "on conflict (endpoint_id, minute_start) do update set requests = devicesec.traffic_minute.requests + excluded.requests",
                    mc.endpointId(), EndpointRepository.ts(Instant.ofEpochSecond(mc.minute() * 60)), mc.count());
        }
        counters.forEach((id, c) -> {
            Instant seen;
            synchronized (c) {
                seen = c.lastSeen;
            }
            if (seen != null) {
                repo.touchLastSeen(id, seen);
            }
        });
        if (offHoursRaised.size() > 10_000) {
            offHoursRaised.clear();
        }
        return batch.size();
    }

    /** Clears in-memory state of an endpoint (after release or rotation the throttle and first-seen checks start fresh). */
    public void reset(String endpointId) {
        counters.remove(endpointId);
        baselines.remove(endpointId);
    }

    private void safePublish(String type, DeviceCodes.Severity severity, String endpointId, String reason, Map<String, String> attrs) {
        try {
            events.record(type, severity, endpointId, null, "device:" + endpointId, reason, attrs);
        } catch (RuntimeException e) {
            log.warn("Traffic event {} not recorded: {}", type, e.getClass().getSimpleName());
        }
    }
}
