package io.intimed.intelligence;

import io.intimed.common.Json;
import io.intimed.security.pki.LocalPki;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * FHIR R4 terminology server client (IM-E12-BL-010 adapter): {@code CodeSystem/$lookup}, {@code CodeSystem/$validate-code}
 * and {@code ConceptMap/$translate} against a configurable base URL. Disabled by default
 * ({@code intimed.terminology.enabled=false}); when enabled the base URL must be HTTPS and its host on
 * {@code intimed.terminology.allowed-hosts} (SSRF guard), redirects are not followed, every call is time-bounded
 * ({@code timeout-ms}) and answers – including negative ones – are cached ({@code cache-minutes}). Only codes and code
 * systems leave the platform, never patient data. Licensed content (LOINC/SNOMED CT distributions) stays on the
 * terminology server operated under the customer's licence.
 */
@Service
public class FhirTerminologyClient implements TerminologyService {
    private static final Logger LOG = LoggerFactory.getLogger(FhirTerminologyClient.class);
    private volatile boolean configured;
    private volatile String baseUrl;
    private volatile Set<String> allowedHosts;
    private volatile Duration timeout;
    private volatile Duration ttl;
    private volatile HttpClient http;
    private volatile String disabledReason;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private volatile long calls, cacheHits, failures;

    private record Cached(Instant at, Object value) { }

    public FhirTerminologyClient(@Value("${intimed.terminology.enabled:false}") boolean enabled,
                                 @Value("${intimed.terminology.base-url:}") String baseUrl,
                                 @Value("${intimed.terminology.allowed-hosts:}") String allowed,
                                 @Value("${intimed.terminology.timeout-ms:3000}") long timeoutMs,
                                 @Value("${intimed.terminology.cache-minutes:60}") long cacheMinutes,
                                 @Value("${intimed.terminology.trust-local-ca:false}") boolean trustLocalCa) {
        configure(enabled, baseUrl, allowed, timeoutMs, cacheMinutes, trustLocalCa);
    }

    /**
     * (Re-)applies the settings – same validation as at start-up (HTTPS, allow-listed host); clears the cache. Not exposed
     * over HTTP: configuration changes go through the deployment's properties (used by the integration tests).
     */
    public synchronized void configure(boolean enabled, String baseUrl, String allowed, long timeoutMs, long cacheMinutes, boolean trustLocalCa) {
        cache.clear();
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.allowedHosts = Arrays.stream(allowed == null ? new String[0] : allowed.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        this.timeout = Duration.ofMillis(Math.max(200, Math.min(timeoutMs, 30_000)));
        this.ttl = Duration.ofMinutes(Math.max(1, cacheMinutes));
        String reason = null;
        if (!enabled) reason = "disabled (intimed.terminology.enabled=false)";
        else {
            URI u;
            try { u = URI.create(this.baseUrl); } catch (IllegalArgumentException e) { u = null; }
            if (u == null || u.getHost() == null) reason = "invalid base URL";
            else if (!"https".equals(u.getScheme())) reason = "base URL must use HTTPS";
            else if (!allowedHosts.contains(u.getHost())) reason = "host " + u.getHost() + " is not on intimed.terminology.allowed-hosts";
        }
        this.disabledReason = reason;
        this.configured = reason == null;
        HttpClient c = null;
        if (configured) {
            try {
                SSLContext ssl = trustLocalCa ? LocalPki.clientSslContext(LocalPki.get().root(), (java.security.cert.X509Certificate[]) null, null) : SSLContext.getDefault();
                c = HttpClient.newBuilder().sslContext(ssl).connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
            } catch (Exception e) {
                LOG.warn("terminology client TLS setup failed: {}", e.getClass().getSimpleName());
            }
        }
        this.http = c;
    }

    @Override public boolean enabled() { return configured && http != null; }

    @Override
    public ObjectNode status() {
        ObjectNode s = Json.obj().put("enabled", enabled()).put("cacheEntries", cache.size()).put("calls", calls).put("cacheHits", cacheHits).put("failures", failures)
                .put("timeoutMs", timeout.toMillis()).put("cacheMinutes", ttl.toMinutes());
        if (!baseUrl.isEmpty()) { try { s.put("host", URI.create(baseUrl).getHost()); } catch (RuntimeException ignored) { s.put("host", "invalid"); } }
        if (disabledReason != null) s.put("reason", disabledReason);
        s.putArray("operations").add("CodeSystem/$lookup").add("CodeSystem/$validate-code").add("ConceptMap/$translate");
        return s;
    }

    @Override
    public Optional<Concept> lookup(String system, String code) {
        JsonNode p = call("CodeSystem/$lookup", Map.of("system", system, "code", code));
        if (p == null) return Optional.empty();
        String display = param(p, "display").path("valueString").asString(null);
        return display == null ? Optional.empty() : Optional.of(new Concept(system, code, display));
    }

    @Override
    public Optional<Boolean> validateCode(String system, String code) {
        JsonNode p = call("CodeSystem/$validate-code", Map.of("url", system, "code", code));
        if (p == null) return Optional.empty();
        JsonNode r = param(p, "result");
        return r.has("valueBoolean") ? Optional.of(r.get("valueBoolean").asBoolean()) : Optional.empty();
    }

    @Override
    public List<Mapping> translate(String system, String code, String targetSystem) {
        JsonNode p = call("ConceptMap/$translate", Map.of("system", system, "code", code, "targetsystem", targetSystem));
        List<Mapping> out = new ArrayList<>();
        if (p == null || !param(p, "result").path("valueBoolean").asBoolean(false)) return out;
        for (JsonNode prm : p.path("parameter")) {
            if (!"match".equals(prm.path("name").asString())) continue;
            String eq = "equivalent";
            JsonNode concept = null;
            for (JsonNode part : prm.path("part")) {
                if ("equivalence".equals(part.path("name").asString()) || "relationship".equals(part.path("name").asString())) eq = part.path("valueCode").asString(eq);
                if ("concept".equals(part.path("name").asString())) concept = part.path("valueCoding");
            }
            if (concept != null && concept.hasNonNull("code")) out.add(new Mapping(concept.path("system").asString(targetSystem), concept.get("code").asString(), concept.path("display").asString(""), eq));
        }
        return out;
    }

    private static JsonNode param(JsonNode parameters, String name) {
        for (JsonNode p : parameters.path("parameter")) if (name.equals(p.path("name").asString())) return p;
        return Json.obj();
    }

    /** GET {base}/{op}?… → Parameters resource, or null (disabled, timeout, non-2xx, invalid JSON). Cached incl. misses. */
    private JsonNode call(String op, Map<String, String> q) {
        if (!enabled() || q.values().stream().anyMatch(v -> v == null || v.isBlank() || v.length() > 256)) return null;
        String query = q.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        String key = op + "?" + query;
        Cached c = cache.get(key);
        if (c != null && c.at().plus(ttl).isAfter(Instant.now())) { cacheHits++; return (JsonNode) c.value(); }
        calls++;
        JsonNode result = null;
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/" + op + "?" + query)).timeout(timeout)
                    .header("Accept", "application/fhir+json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 200 && r.statusCode() < 300 && r.body().length() < 1_000_000) {
                JsonNode j = Json.parse(r.body());
                if ("Parameters".equals(j.path("resourceType").asString())) result = j;
            } else if (r.statusCode() >= 500) failures++;
        } catch (Exception e) {
            failures++;
            LOG.debug("terminology call {} failed: {}", op, e.getClass().getSimpleName());
            return null; // transient failures are not cached
        }
        if (cache.size() > 10_000) cache.clear();
        cache.put(key, new Cached(Instant.now(), result));
        return result;
    }
}
