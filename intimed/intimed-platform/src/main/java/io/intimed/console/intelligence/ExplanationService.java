package io.intimed.console.intelligence;

import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.security.RateLimiter;
import io.intimed.security.audit.AuditService;
import io.intimed.security.egress.EgressClient;
import io.intimed.security.privacy.PhiMasker;
import io.intimed.security.secrets.SecretScanner;
import io.intimed.security.secrets.SecretStore;
import io.intimed.tenancy.TenantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM explanation layer behind a policy gateway (IM-E03-BL-006). It explains interface-specification passages that the
 * {@link DocAssistant} retrieved (BM25, tenant-scoped) – it never decides configuration values: the recommendation
 * engine stays rule-based and nothing in the configuration path calls this service (ADR-03).
 *
 * <p>Policy gateway, in order:
 * <ol>
 *   <li>Enabled only when {@code intimed.llm.enabled=true} (default false) <b>and</b> the tenant setting
 *       {@code aiExplanations.enabled} is true (Tenant Administrator).</li>
 *   <li>Rate limit per tenant ({@code intimed.llm.requests-per-minute}), question ≤ 1000 characters.</li>
 *   <li>Citations required: without retrieved passages no model call is made.</li>
 *   <li>PHI redaction of question and passages ({@link PhiMasker} + identifier / phone / SSN patterns); prompts that
 *       still contain secrets are refused.</li>
 *   <li>Data residency: the endpoint host must be on {@code intimed.llm.allowed-hosts}; HTTPS only, no redirects
 *       ({@link EgressClient}).</li>
 *   <li>Credentials: API key from the secret store reference {@code intimed.llm.api-key-ref} (default
 *       {@code secret://azure-openai-key}) or, with {@code intimed.llm.auth=managed-identity}, an Entra ID token from the
 *       Azure Instance Metadata Service (resource {@code https://cognitiveservices.azure.com}). Keys are never logged.</li>
 *   <li>{@code max_tokens} capped ({@code intimed.llm.max-tokens}), temperature 0.</li>
 *   <li>Audit: SHA-256 of the prompt and of the response, token usage and citation count – never the content.</li>
 *   <li>Output label: "AI-generated explanation – UNVERIFIED, citations required"; an answer that cites none of the
 *       passages is flagged {@code citationsMissing}.</li>
 * </ol>
 * Endpoint: Azure OpenAI-compatible chat completions
 * {@code POST {endpoint}/openai/deployments/{deployment}/chat/completions?api-version={apiVersion}}.
 */
@Service
public class ExplanationService {
    public static final String LABEL = "AI-generated explanation – UNVERIFIED, citations required";
    public static final String IMDS = "http://169.254.169.254/metadata/identity/oauth2/token?api-version=2018-02-01&resource=https://cognitiveservices.azure.com";
    public static final int MAX_QUESTION = 1000;
    static final String SYSTEM_PROMPT = "You explain medical-device interface specifications to integration engineers. Use ONLY the numbered passages provided. "
            + "Cite every statement with its passage number in square brackets, e.g. [1]. If the passages do not answer the question, say so. "
            + "Never recommend or decide configuration values, thresholds or clinical interpretations; the platform's rule-based engine and the exchange test decide those.";
    private static final Pattern IDENT = Pattern.compile("(?i)\\b(MRN|PID|patient[ _-]?id|subject[ _-]?id|NHS(?: number)?|SSN|employee[ _-]?id)\\s*[:#=]?\\s*[A-Za-z0-9-]{3,}");
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w.])\\+?\\(?\\d{2,4}\\)?[ .-]\\d{3,4}[ .-]\\d{3,4}\\b");

    /** Gateway settings (from properties or constructed in tests). */
    public record Settings(boolean enabled, String endpoint, String deployment, String apiVersion, String auth, String apiKeyRef, String allowedHosts,
                           int maxTokens, int requestsPerMinute, boolean allowLoopbackHttp, String imdsUrl) { }

    private final DocAssistant docs;
    private final SecretStore secrets;
    private final RateLimiter limiter;
    private final AuditService audit;
    private final TenantService tenants;
    private final Settings s;
    private final EgressClient http;
    private final HttpClient imds = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile String miToken;
    private volatile long miExpiresAt;

    @Autowired
    public ExplanationService(DocAssistant docs, SecretStore secrets, RateLimiter limiter, AuditService audit, TenantService tenants,
                              @Value("${intimed.llm.enabled:false}") boolean enabled, @Value("${intimed.llm.endpoint:}") String endpoint,
                              @Value("${intimed.llm.deployment:}") String deployment, @Value("${intimed.llm.api-version:2024-06-01}") String apiVersion,
                              @Value("${intimed.llm.auth:api-key}") String auth, @Value("${intimed.llm.api-key-ref:secret://azure-openai-key}") String apiKeyRef,
                              @Value("${intimed.llm.allowed-hosts:}") String allowedHosts, @Value("${intimed.llm.max-tokens:600}") int maxTokens,
                              @Value("${intimed.llm.requests-per-minute:10}") int rpm, @Value("${intimed.llm.allow-loopback-http:false}") boolean loopback) {
        this(docs, secrets, limiter, audit, tenants, new Settings(enabled, endpoint, deployment, apiVersion, auth, apiKeyRef, allowedHosts, maxTokens, rpm, loopback, IMDS));
    }

    public ExplanationService(DocAssistant docs, SecretStore secrets, RateLimiter limiter, AuditService audit, TenantService tenants, Settings settings) {
        this.docs = docs;
        this.secrets = secrets;
        this.limiter = limiter;
        this.audit = audit;
        this.tenants = tenants;
        this.s = settings;
        this.http = new EgressClient(EgressClient.hosts(settings.allowedHosts()), settings.allowLoopbackHttp(), 512 * 1024);
    }

    public boolean tenantEnabled(String tenant) { return tenants.require(tenant).settings().at("/aiExplanations/enabled").asBoolean(false); }

    /** Policy status (no secrets): what the gateway would do for this tenant. */
    public ObjectNode policy(String tenant) {
        String host = endpointHost();
        ObjectNode p = Json.obj().put("globalEnabled", s.enabled()).put("tenantEnabled", tenantEnabled(tenant)).put("active", s.enabled() && tenantEnabled(tenant))
                .put("endpointHost", host).put("hostAllowed", !host.isEmpty() && http.allowedHosts().contains(host.toLowerCase(Locale.ROOT)))
                .put("deployment", s.deployment()).put("apiVersion", s.apiVersion()).put("auth", "managed-identity".equals(s.auth()) ? "managed-identity" : "api-key")
                .put("apiKeyReference", "managed-identity".equals(s.auth()) ? null : s.apiKeyRef()).put("maxTokens", s.maxTokens()).put("requestsPerMinute", s.requestsPerMinute())
                .put("label", LABEL).put("phiRedaction", true).put("auditContent", "hashes only").put("decidesConfiguration", false);
        p.set("allowedHosts", Json.tree(http.allowedHosts()));
        return p;
    }

    private String endpointHost() {
        try { String h = URI.create(s.endpoint() == null ? "" : s.endpoint()).getHost(); return h == null ? "" : h; } catch (RuntimeException e) { return ""; }
    }

    public void setTenantEnabled(String tenant, boolean on, String actor) {
        ObjectNode set = Json.obj();
        set.putObject("aiExplanations").put("enabled", on);
        tenants.updateSettings(tenant, set, actor);
    }

    /** Redacts PHI from free text before it may leave the platform; returns the redacted text. */
    public static String redact(String text) {
        if (text == null) return "";
        String t = text.contains("PID|") ? PhiMasker.maskHl7(text) : text;
        t = PhiMasker.mask(t);
        t = IDENT.matcher(t).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + " [REDACTED]"));
        t = SSN.matcher(t).replaceAll("[REDACTED]");
        t = PHONE.matcher(t).replaceAll("[REDACTED]");
        return t;
    }

    public ObjectNode explain(String tenant, String question, String actor) {
        if (!s.enabled()) throw ApiException.conflict("llm-disabled", "LLM explanations are disabled (intimed.llm.enabled=false)");
        if (!tenantEnabled(tenant)) throw ApiException.conflict("llm-disabled-tenant", "LLM explanations are not enabled for this tenant (Tenant Administrator)");
        if (question == null || question.isBlank()) throw ApiException.bad("question-required", "question is required");
        if (question.length() > MAX_QUESTION) throw ApiException.bad("question-too-long", "question exceeds " + MAX_QUESTION + " characters");
        int rpm = Math.max(1, s.requestsPerMinute());
        if (!limiter.allow("llm:" + tenant, rpm / 60.0, rpm)) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "LLM explanation rate limit reached");
        List<ObjectNode> citations = docs.ask(tenant, question, 5);
        ObjectNode out = Json.obj().put("label", LABEL).put("question", question);
        ArrayNode cites = out.putArray("citations");
        for (int i = 0; i < citations.size(); i++) {
            ObjectNode c = citations.get(i);
            cites.addObject().put("n", i + 1).put("citation", c.get("citation").asString()).put("documentId", c.get("documentId").asString())
                    .put("chunk", c.get("chunk").asInt()).put("passage", c.get("passage").asString()).put("score", c.get("score").asDouble());
        }
        if (citations.isEmpty()) {
            audit.record(tenant, actor, "llm.explain.no-citations", "llm/" + s.deployment(), Map.of("questionHash", Hashing.sha256(question)));
            return out.put("status", "NO_CITATIONS").put("explanation", (String) null)
                    .put("note", "No passage of the uploaded interface specifications matches – the explanation layer only answers with citations.");
        }
        String rq = redact(question);
        StringBuilder user = new StringBuilder("Question: ").append(rq).append("\n\nPassages:\n");
        int redactions = rq.equals(question) ? 0 : 1;
        for (int i = 0; i < citations.size(); i++) {
            String p = citations.get(i).get("passage").asString();
            String rp = redact(p);
            if (!rp.equals(p)) redactions++;
            user.append('[').append(i + 1).append("] (").append(citations.get(i).get("citation").asString()).append(")\n").append(rp).append("\n\n");
        }
        String prompt = user.toString();
        if (!SecretScanner.scan(prompt).isEmpty()) throw ApiException.unprocessable("prompt-secret", "prompt would contain a credential – refused by the policy gateway");
        ObjectNode body = Json.obj().put("max_tokens", Math.max(1, Math.min(s.maxTokens(), 4000))).put("temperature", 0);
        ArrayNode msgs = body.putArray("messages");
        msgs.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        msgs.addObject().put("role", "user").put("content", prompt);
        String url = trimSlash(s.endpoint()) + "/openai/deployments/" + URLEncoder.encode(s.deployment(), StandardCharsets.UTF_8) + "/chat/completions?api-version="
                + URLEncoder.encode(s.apiVersion(), StandardCharsets.UTF_8);
        http.check(url); // residency allow-list before any credential is resolved
        String promptHash = Hashing.sha256(SYSTEM_PROMPT + "\n" + prompt);
        EgressClient.Response r = http.postJson(url, Json.write(body), authHeaders(tenant));
        if (r.status() == 429) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "llm-rate-limited", "model endpoint rate limit");
        if (r.status() != 200) {
            audit.record(tenant, actor, "llm.explain.error", "llm/" + s.deployment(), Map.of("promptHash", promptHash, "status", r.status()));
            throw ApiException.conflict("llm-error", "model endpoint answered HTTP " + r.status());
        }
        JsonNode resp;
        try { resp = Json.parse(r.body()); } catch (RuntimeException e) { throw ApiException.conflict("llm-error", "model endpoint answered non-JSON"); }
        String text = resp.at("/choices/0/message/content").asString("");
        if (text.length() > 8000) text = text.substring(0, 8000);
        String finish = resp.at("/choices/0/finish_reason").asString("");
        if ("content_filter".equals(finish)) text = "";
        boolean cited = false;
        for (int i = 1; i <= citations.size(); i++) cited |= text.contains("[" + i + "]");
        String responseHash = Hashing.sha256(text);
        audit.record(tenant, actor, "llm.explain", "llm/" + s.deployment(), Map.of("promptHash", promptHash, "responseHash", responseHash, "citations", citations.size(),
                "redactions", redactions, "promptTokens", resp.at("/usage/prompt_tokens").asInt(-1), "completionTokens", resp.at("/usage/completion_tokens").asInt(-1),
                "host", endpointHost(), "cited", cited));
        out.put("status", cited ? "OK" : "CITATIONS_MISSING").put("explanation", text).put("citationsMissing", !cited).put("finishReason", finish)
                .put("model", resp.path("model").asString(s.deployment())).put("promptHash", promptHash).put("responseHash", responseHash)
                .put("redactions", redactions).put("advisoryOnly", true).put("generatedAt", Instant.now().toString());
        return out;
    }

    private Map<String, String> authHeaders(String tenant) {
        if ("managed-identity".equals(s.auth())) return Map.of("Authorization", "Bearer " + managedIdentityToken());
        if (!SecretStore.isRef(s.apiKeyRef())) throw ApiException.conflict("llm-key-ref", "intimed.llm.api-key-ref must be a secret:// reference");
        String key = secrets.get(tenant, SecretStore.name(s.apiKeyRef()))
                .orElseThrow(() -> ApiException.conflict("llm-key-missing", "secret " + s.apiKeyRef() + " is not set for this tenant"));
        return Map.of("api-key", key);
    }

    /** Entra ID token from IMDS (managed identity); cached until 5 minutes before expiry. Only the fixed IMDS URL is called. */
    String managedIdentityToken() {
        long now = Instant.now().getEpochSecond();
        if (miToken != null && now < miExpiresAt - 300) return miToken;
        URI u = URI.create(s.imdsUrl());
        boolean imdsHost = "169.254.169.254".equals(u.getHost()) || (s.allowLoopbackHttp() && "127.0.0.1".equals(u.getHost()));
        if (!imdsHost || !u.getPath().equals("/metadata/identity/oauth2/token")) throw ApiException.conflict("llm-imds", "managed identity is only obtained from the Azure IMDS endpoint");
        try {
            HttpResponse<String> r = imds.send(HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(5)).header("Metadata", "true").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw ApiException.conflict("llm-imds", "managed identity token unavailable (IMDS HTTP " + r.statusCode() + ")");
            JsonNode t = Json.parse(r.body());
            String token = t.path("access_token").asString("");
            if (token.isEmpty()) throw ApiException.conflict("llm-imds", "IMDS answered without access_token");
            miToken = token;
            miExpiresAt = t.path("expires_on").asLong(now + 600);
            return token;
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.conflict("llm-imds", "IMDS call interrupted");
        } catch (Exception e) {
            throw ApiException.conflict("llm-imds", "managed identity token unavailable: " + e.getClass().getSimpleName());
        }
    }

    private static String trimSlash(String s) { return s == null ? "" : s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
}
