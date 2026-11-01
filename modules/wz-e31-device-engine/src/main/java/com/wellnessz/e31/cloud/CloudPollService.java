package com.wellnessz.e31.cloud;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceSecurityEventPublisher;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.casekernel.device.DeviceTenantDirectory;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.driver.DriverRuntime;
import com.wellnessz.e31.pipeline.IngestService;
import com.wellnessz.e31.support.EngineException;
import com.wellnessz.e31.support.EngineSettings;
import com.wellnessz.e31.support.Ids;
import com.wellnessz.security.consent.ConsentPurpose;
import com.wellnessz.security.consent.ConsentService;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * P6 vendor-cloud polling with OAuth2 client credentials (E31-BL-008, E31-HLR-02; contract §13.3 P6 and §17 egress hosts).
 * <ul>
 * <li><b>Schedule</b> per connector ({@code poll.intervalSeconds} ≥ 60), claimed with a compare-and-set on
 * {@code deviceint.cloud_poll_state.next_poll_at} so that several application instances never poll the same connector at once;
 * failures back off exponentially (interval × 2^n, at most one hour, or the vendor's {@code Retry-After}).</li>
 * <li><b>Rate limit</b> per connector ({@code poll.rateLimitPerMinute}, token and record calls together).</li>
 * <li><b>Credentials</b> through {@code DeviceSecretStore} (purpose {@code CLOUD_POLL}); the client secret is held only for the
 * token request (HTTP Basic, RFC 6749 §2.3.1) and wiped; the access token is cached in memory until shortly before expiry.</li>
 * <li><b>Egress</b>: the API and token hosts must be on {@code device.cloud.allowed-hosts} (empty = no outbound call) and use
 * HTTPS; the connector's {@code residencyRegion} must equal the tenant's residency ({@code residency_check}).</li>
 * <li><b>High-water mark</b>: the vendor cursor (or the last record time / id) is persisted only after the page was durably
 * ingested; each record enters the E31 pipeline with the idempotency key {@code CP:<connector>:<record id>} – a repeated
 * record is a {@code DUPLICATE}, never a second observation.</li>
 * <li><b>Consent-scoped wearables</b>: when the connector maps a consent purpose, records of employees without an active
 * consent are dropped before ingest (not stored, counted) – the CONSENT gate stays as the second line.</li>
 * </ul>
 * Nothing of a response body is logged or stored outside the raw archive of the ingested record.
 */
@Service("e31CloudPollService")
public class CloudPollService {

    private static final Logger log = LoggerFactory.getLogger(CloudPollService.class);
    static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    static final long MAX_BACKOFF_SECONDS = 3600;

    /** Outcome of one connector run. */
    public record Run(String deviceInstanceId, String status, String error, int records, int accepted, int duplicates, int skippedNoConsent,
                      String highWaterMark, Instant nextPollAt) {
    }

    private record Token(String value, Instant expiresAt) {
    }

    /** Thrown inside a run to end it with a status (no response body ever travels in it). */
    static final class PollFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final String status;
        final String code;
        final long retryAfterSeconds;

        PollFailure(String status, String code, long retryAfterSeconds) {
            super(code, null, false, false);
            this.status = status;
            this.code = code;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private final DeviceSpis spis;
    private final DriverRuntime drivers;
    private final IngestService ingest;
    private final ConsentService consents;
    private final EngineSettings settings;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final boolean allowLoopbackHttp;
    private final HttpClient http;
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> calls = new ConcurrentHashMap<>();

    public CloudPollService(DeviceSpis spis, DriverRuntime drivers, IngestService ingest, ConsentService consents, EngineSettings settings,
                            JdbcTemplate jdbc, JsonMapper json, Clock clock,
                            @Value("${wellnessz.device.cloud.allow-loopback-http:false}") boolean allowLoopbackHttp) {
        this.spis = spis;
        this.drivers = drivers;
        this.ingest = ingest;
        this.consents = consents;
        this.settings = settings;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.allowLoopbackHttp = allowLoopbackHttp;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    // ------------------------------------------------------------------ scheduling

    /** Polls every published P6 connector whose schedule is due; returns the runs performed. */
    public List<Run> pollDue() {
        List<Run> runs = new ArrayList<>();
        for (DeviceCatalogueLookup.DeviceInstance i : spis.catalogue().publishedInstances(DeviceCodes.Option.P6_CLOUD_API)) {
            if (!i.published()) {
                continue;
            }
            Instant now = clock.instant();
            ensureState(i, now);
            if (!claim(i.deviceInstanceId(), now)) {
                continue;
            }
            runs.add(poll(i));
        }
        return runs;
    }

    /** Polls one connector now (operations "poll now" and tests); the schedule is advanced as after a scheduled run. */
    public Run pollNow(String deviceInstanceId) {
        DeviceCatalogueLookup.DeviceInstance i = spis.catalogue().instance(deviceInstanceId)
                .filter(x -> x.option() == DeviceCodes.Option.P6_CLOUD_API && x.published())
                .orElseThrow(() -> ApiException.notFound("Connector"));
        ensureState(i, clock.instant());
        return poll(i);
    }

    void ensureState(DeviceCatalogueLookup.DeviceInstance i, Instant now) {
        jdbc.update("""
                insert into deviceint.cloud_poll_state (device_instance_id, tenant_code, next_poll_at, updated_at) values (?, ?, ?, ?)
                on conflict (device_instance_id) do nothing""", i.deviceInstanceId(), tenant(i), Timestamp.from(now), Timestamp.from(now));
    }

    /** Compare-and-set lease of 10 minutes: only one instance runs a connector at a time. */
    boolean claim(String deviceInstanceId, Instant now) {
        return jdbc.update("""
                update deviceint.cloud_poll_state set next_poll_at = ? where device_instance_id = ? and (next_poll_at is null or next_poll_at <= ?)""",
                Timestamp.from(now.plusSeconds(600)), deviceInstanceId, Timestamp.from(now)) == 1;
    }

    // ------------------------------------------------------------------ one run

    Run poll(DeviceCatalogueLookup.DeviceInstance i) {
        Instant started = clock.instant();
        CloudConnector c;
        try {
            DriverPackage pkg = drivers.forInstance(i, null);
            c = CloudConnector.of(i, pkg);
        } catch (EngineException | ApiException e) {
            return finish(i.deviceInstanceId(), null, "ERROR", e instanceof EngineException ee ? ee.code() : ((ApiException) e).code(), 0, 0, 0, 0,
                    null, started, 300);
        }
        if (c == null || !c.enabled()) {
            return finish(i.deviceInstanceId(), c, "DISABLED", null, 0, 0, 0, 0, null, started, c == null ? 3600 : c.intervalSeconds());
        }
        String hwm = jdbc.query("select high_water_mark from deviceint.cloud_poll_state where device_instance_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, i.deviceInstanceId());
        int records = 0;
        int accepted = 0;
        int duplicates = 0;
        int skipped = 0;
        try {
            preflight(i, c);
            String cursor = hwm;
            for (int page = 0; page < c.maxPagesPerRun(); page++) {
                JsonNode body = fetchPage(c, cursor);
                JsonNode items = body.at(c.recordsPointer());
                String lastTime = null;
                String lastId = null;
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        records++;
                        String recordId = text(item, c.recordIdPointer());
                        String time = c.recordTimePointer() == null ? null : text(item, c.recordTimePointer());
                        lastId = recordId != null ? recordId : lastId;
                        lastTime = later(lastTime, time);
                        if (!consented(c, item)) {
                            skipped++;
                            continue;
                        }
                        byte[] raw = json.writeValueAsBytes(item);
                        String key = "CP:" + i.deviceInstanceId() + ":" + (recordId != null && recordId.length() <= 120
                                ? recordId : Ids.sha256Hex(raw));
                        IngestService.Receipt r = ingest.acquire(new IngestService.Acquire(
                                new IngestService.Source("CLOUD:" + i.deviceInstanceId(), tenant(i), i.siteCode(), Set.of(i.deviceInstanceId()), false),
                                "WEBHOOK", "application/json", key.length() > 200 ? key.substring(0, 200) : key, null, parseTime(time), null,
                                i.deviceInstanceId(), null, false, raw, Map.of("source", "CLOUD_POLL"), false, false, null, null, false));
                        if ("DUPLICATE".equals(r.status())) {
                            duplicates++;
                        } else {
                            accepted++;
                        }
                    }
                }
                String next = text(body, c.nextCursorPointer());
                String advanced = next != null ? next : lastTime != null ? lastTime : lastId;
                if (advanced != null && !advanced.equals(cursor)) {
                    cursor = advanced;
                    hwm = advanced;
                    jdbc.update("update deviceint.cloud_poll_state set high_water_mark = ?, updated_at = ? where device_instance_id = ?",
                            hwm.length() > 200 ? hwm.substring(0, 200) : hwm, Timestamp.from(clock.instant()), i.deviceInstanceId());
                }
                if (next == null || !items.isArray() || items.isEmpty()) {
                    break;
                }
            }
            return finish(i.deviceInstanceId(), c, "OK", null, records, accepted, duplicates, skipped, hwm, started, c.intervalSeconds());
        } catch (PollFailure f) {
            if ("HOST_NOT_ALLOWED".equals(f.status) || "RESIDENCY_MISMATCH".equals(f.status) || "AUTH_FAILED".equals(f.status)) {
                securityEvent(i, f.code);
            }
            return finish(i.deviceInstanceId(), c, f.status, f.code, records, accepted, duplicates, skipped, hwm, started,
                    f.retryAfterSeconds > 0 ? f.retryAfterSeconds : -1);
        } catch (ApiException e) {
            // e.g. the tenant quota (429) or a quarantined connector (409): the high-water mark stays at the last durable page
            return finish(i.deviceInstanceId(), c, "ERROR", e.code(), records, accepted, duplicates, skipped, hwm, started, -1);
        }
    }

    /** Allow-list, scheme and residency – before any outbound call. */
    void preflight(DeviceCatalogueLookup.DeviceInstance i, CloudConnector c) throws PollFailure {
        if (c.apiBaseUrl() == null || c.tokenUrl() == null || c.clientId() == null || c.clientSecretRef() == null) {
            throw new PollFailure("CONFIGURATION_INCOMPLETE", "configuration_incomplete", 0);
        }
        List<String> allowed = settings.cloudAllowedHosts();
        for (String url : List.of(c.apiBaseUrl(), c.tokenUrl())) {
            if (!hostAllowed(url, allowed)) {
                throw new PollFailure("HOST_NOT_ALLOWED", "host_not_allowed", 0);
            }
            if (!schemeAllowed(url)) {
                throw new PollFailure("HOST_NOT_ALLOWED", "https_required", 0);
            }
        }
        Optional<DeviceTenantDirectory.Tenant> t = spis.tenants().tenant(tenant(i));
        String residency = t.map(DeviceTenantDirectory.Tenant::residencyRegion).orElse(null);
        if (c.residencyRegion() == null || residency == null || !residency.equalsIgnoreCase(c.residencyRegion())) {
            throw new PollFailure("RESIDENCY_MISMATCH", "residency_check", 0);
        }
    }

    /** Exact host or a leading-dot domain suffix ({@code .vendor.example}) of {@code device.cloud.allowed-hosts}. */
    public static boolean hostAllowed(String url, List<String> allowed) {
        String host = CloudConnector.host(url);
        if (host == null) {
            return false;
        }
        return allowed.stream().map(a -> a.toLowerCase(Locale.ROOT))
                .anyMatch(a -> a.equals(host) || a.startsWith(".") && host.endsWith(a) && host.length() > a.length());
    }

    boolean schemeAllowed(String url) {
        URI u = URI.create(url);
        if ("https".equalsIgnoreCase(u.getScheme())) {
            return true;
        }
        if (allowLoopbackHttp && "http".equalsIgnoreCase(u.getScheme())) {
            try {
                return InetAddress.getByName(u.getHost()).isLoopbackAddress();
            } catch (UnknownHostException e) {
                return false;
            }
        }
        return false;
    }

    boolean consented(CloudConnector c, JsonNode item) {
        if (c.consentPurpose() == null) {
            return true;
        }
        String employee = c.subjectPointer() == null ? null : text(item, c.subjectPointer());
        if (employee == null) {
            return false;
        }
        try {
            return consents.hasActiveConsent(employee, ConsentPurpose.valueOf(c.consentPurpose()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ HTTP

    JsonNode fetchPage(CloudConnector c, String cursor) throws PollFailure {
        URI uri = c.pageUri(cursor);
        HttpResponse<byte[]> r = send(c, HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
                .header("Authorization", "Bearer " + token(c).value()).GET().build());
        if (r.statusCode() == 401) {
            tokens.remove(c.deviceInstanceId()); // expired or revoked early: one fresh token, then give up
            r = send(c, HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token(c).value()).GET().build());
            if (r.statusCode() == 401) {
                throw new PollFailure("AUTH_FAILED", "token_rejected", 0);
            }
        }
        check(r);
        try {
            JsonNode n = json.readTree(r.body());
            if (n == null || !n.isObject()) {
                throw new PollFailure("ERROR", "response_not_json_object", 0);
            }
            return n;
        } catch (JacksonException e) {
            throw new PollFailure("ERROR", "response_not_json", 0);
        }
    }

    Token token(CloudConnector c) throws PollFailure {
        Token cached = tokens.get(c.deviceInstanceId());
        Instant now = clock.instant();
        if (cached != null && cached.expiresAt().isAfter(now.plusSeconds(30))) {
            return cached;
        }
        char[] secret = spis.secrets().resolve(c.clientSecretRef(), "CLOUD_POLL").orElseThrow(() -> new PollFailure("AUTH_FAILED", "secret_unresolved", 0));
        String basic;
        try {
            byte[] pair = (enc(c.clientId()) + ":" + enc(new String(secret))).getBytes(StandardCharsets.UTF_8);
            basic = Base64.getEncoder().encodeToString(pair);
            Arrays.fill(pair, (byte) 0);
        } finally {
            Arrays.fill(secret, '\0');
        }
        StringBuilder form = new StringBuilder("grant_type=client_credentials");
        if (!c.scopes().isEmpty()) {
            form.append("&scope=").append(enc(String.join(" ", c.scopes())));
        }
        HttpResponse<byte[]> r = send(c, HttpRequest.newBuilder(URI.create(c.tokenUrl())).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .header("Authorization", "Basic " + basic).POST(HttpRequest.BodyPublishers.ofString(form.toString())).build());
        if (r.statusCode() == 400 || r.statusCode() == 401 || r.statusCode() == 403) {
            throw new PollFailure("AUTH_FAILED", "token_request_refused", 0);
        }
        check(r);
        try {
            JsonNode t = json.readTree(r.body());
            String access = t == null ? null : t.path("access_token").asString(null);
            if (access == null || access.isBlank() || !"bearer".equalsIgnoreCase(t.path("token_type").asString("bearer"))) {
                throw new PollFailure("AUTH_FAILED", "token_response_invalid", 0);
            }
            long expiresIn = Math.max(60, Math.min(86_400, t.path("expires_in").asLong(3600)));
            Token token = new Token(access, now.plusSeconds(expiresIn));
            tokens.put(c.deviceInstanceId(), token);
            return token;
        } catch (JacksonException e) {
            throw new PollFailure("AUTH_FAILED", "token_response_invalid", 0);
        }
    }

    HttpResponse<byte[]> send(CloudConnector c, HttpRequest request) throws PollFailure {
        rateLimit(c);
        try {
            HttpResponse<byte[]> r = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (r.body() != null && r.body().length > MAX_RESPONSE_BYTES) {
                throw new PollFailure("ERROR", "response_too_large", 0);
            }
            return r;
        } catch (IOException e) {
            throw new PollFailure("ERROR", "vendor_unreachable", 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PollFailure("ERROR", "interrupted", 0);
        }
    }

    /** Sliding one-minute window per connector over all its outbound calls. */
    void rateLimit(CloudConnector c) throws PollFailure {
        Deque<Instant> window = calls.computeIfAbsent(c.deviceInstanceId(), k -> new ArrayDeque<>());
        Instant now = clock.instant();
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(now.minusSeconds(60))) {
                window.pollFirst();
            }
            if (window.size() >= c.rateLimitPerMinute()) {
                long wait = Math.max(1, Duration.between(now, window.peekFirst().plusSeconds(60)).getSeconds());
                throw new PollFailure("RATE_LIMITED", "connector_rate_limit", wait);
            }
            window.addLast(now);
        }
    }

    static void check(HttpResponse<byte[]> r) throws PollFailure {
        int s = r.statusCode();
        if (s == 429 || s == 503) {
            long retry = r.headers().firstValue("Retry-After").map(v -> {
                try {
                    return Long.parseLong(v.strip());
                } catch (NumberFormatException e) {
                    return 60L;
                }
            }).orElse(60L);
            throw new PollFailure("RATE_LIMITED", "vendor_rate_limited", Math.max(1, Math.min(MAX_BACKOFF_SECONDS, retry)));
        }
        if (s < 200 || s >= 300) {
            throw new PollFailure("ERROR", "http_" + s, 0);
        }
    }

    // ------------------------------------------------------------------ state

    Run finish(String instanceId, CloudConnector c, String status, String error, int records, int accepted, int duplicates, int skipped,
               String hwm, Instant started, long delaySeconds) {
        Instant now = clock.instant();
        boolean ok = "OK".equals(status) || "DISABLED".equals(status);
        int failures = ok ? 0 : 1 + jdbc.query("select consecutive_failures from deviceint.cloud_poll_state where device_instance_id = ?",
                rs -> rs.next() ? rs.getInt(1) : 0, instanceId);
        long interval = c == null ? 300 : c.intervalSeconds();
        long delay = delaySeconds > 0 ? delaySeconds : backoff(interval, failures);
        Instant next = now.plusSeconds(ok ? interval : Math.max(delay, 1));
        jdbc.update("""
                update deviceint.cloud_poll_state set last_poll_at = ?, next_poll_at = ?, last_status = ?, last_error = ?, consecutive_failures = ?,
                    records_total = records_total + ?, records_last = ?, duplicates_total = duplicates_total + ?, skipped_no_consent = skipped_no_consent + ?,
                    updated_at = ? where device_instance_id = ?""", Timestamp.from(started), Timestamp.from(next), status, error, failures, accepted,
                records, duplicates, skipped, Timestamp.from(now), instanceId);
        if (!ok) {
            log.warn("cloud connector {} poll {} ({})", instanceId, status, error);
        }
        return new Run(instanceId, status, error, records, accepted, duplicates, skipped, hwm, next);
    }

    /** interval × 2^failures, capped at one hour (and never below the interval). */
    static long backoff(long interval, int failures) {
        if (failures <= 0) {
            return interval;
        }
        long d = interval;
        for (int k = 0; k < failures && d < MAX_BACKOFF_SECONDS; k++) {
            d *= 2;
        }
        return Math.max(interval, Math.min(MAX_BACKOFF_SECONDS, d));
    }

    /** P6 connector status for the operations screen (PHI-free: ids, states, counts, times). */
    public List<Map<String, Object>> status() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                select device_instance_id, tenant_code, last_poll_at, next_poll_at, last_status, last_error, consecutive_failures, records_total,
                       records_last, duplicates_total, skipped_no_consent, high_water_mark is not null as has_hwm from deviceint.cloud_poll_state""")) {
            rows.put((String) r.get("device_instance_id"), r);
        }
        for (DeviceCatalogueLookup.DeviceInstance i : spis.catalogue().publishedInstances(DeviceCodes.Option.P6_CLOUD_API)) {
            Map<String, Object> r = rows.getOrDefault(i.deviceInstanceId(), Map.of());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceInstanceId", i.deviceInstanceId());
            m.put("siteCode", i.siteCode());
            m.put("tenantCode", tenant(i));
            m.put("driverPackageId", i.driverPackageId());
            CloudConnector c = null;
            try {
                c = CloudConnector.of(i, drivers.forInstance(i, null));
            } catch (EngineException | ApiException e) {
                m.put("driverStatus", e instanceof EngineException ee ? ee.code() : ((ApiException) e).code());
            }
            m.put("pollEnabled", c != null && c.enabled());
            m.put("intervalSeconds", c == null ? null : c.intervalSeconds());
            m.put("rateLimitPerMinute", c == null ? null : c.rateLimitPerMinute());
            m.put("apiHost", c == null ? null : CloudConnector.host(c.apiBaseUrl()));
            m.put("hostAllowed", c != null && c.apiBaseUrl() != null && hostAllowed(c.apiBaseUrl(), settings.cloudAllowedHosts()));
            m.put("consentPurpose", c == null ? null : c.consentPurpose());
            m.put("residencyRegion", c == null ? null : c.residencyRegion());
            m.put("state", r.getOrDefault("last_status", "NEVER_POLLED"));
            m.put("lastError", r.get("last_error"));
            m.put("lastPollAt", ts(r.get("last_poll_at")));
            m.put("nextPollAt", ts(r.get("next_poll_at")));
            m.put("consecutiveFailures", r.getOrDefault("consecutive_failures", 0));
            m.put("recordsTotal", r.getOrDefault("records_total", 0L));
            m.put("recordsLastRun", r.getOrDefault("records_last", 0));
            m.put("duplicatesTotal", r.getOrDefault("duplicates_total", 0L));
            m.put("skippedNoConsent", r.getOrDefault("skipped_no_consent", 0L));
            m.put("highWaterMarkSet", Boolean.TRUE.equals(r.get("has_hwm")));
            out.add(m);
        }
        return out;
    }

    void securityEvent(DeviceCatalogueLookup.DeviceInstance i, String reason) {
        try {
            spis.securityEvents().publish(new DeviceSecurityEventPublisher.DeviceSecurityEvent("CLOUD_POLL_REFUSED", DeviceCodes.Severity.MEDIUM,
                    "CLOUD:" + i.deviceInstanceId(), i.deviceInstanceId(), i.siteCode(), "device-engine", "E31", reason, Map.of("protocol", "HTTPS"),
                    clock.instant()));
        } catch (RuntimeException ignored) {
            // the refusal is recorded in the poll state regardless of event delivery
        }
    }

    // ------------------------------------------------------------------ helpers

    static String tenant(DeviceCatalogueLookup.DeviceInstance i) {
        return i.tenantCode() == null ? EngineSettings.DEFAULT_TENANT : i.tenantCode();
    }

    static String text(JsonNode n, String pointer) {
        if (pointer == null || n == null) {
            return null;
        }
        JsonNode v = n.at(pointer);
        return v == null || v.isMissingNode() || v.isNull() || !v.isValueNode() ? null : v.asString();
    }

    static String later(String a, String b) {
        if (b == null) {
            return a;
        }
        if (a == null) {
            return b;
        }
        Instant ia = parseTime(a);
        Instant ib = parseTime(b);
        if (ia == null || ib == null) {
            return b;
        }
        return ib.isAfter(ia) ? b : a;
    }

    static Instant parseTime(String v) {
        if (v == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String enc(String v) {
        return java.net.URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    static Instant ts(Object o) {
        return o instanceof Timestamp t ? t.toInstant() : null;
    }
}
