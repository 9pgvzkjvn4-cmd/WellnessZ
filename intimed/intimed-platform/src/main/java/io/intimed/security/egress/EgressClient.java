package io.intimed.security.egress;

import io.intimed.common.ApiException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Outbound HTTP with an SSRF guard (SC-13, used by the register feeds IM-E02-BL-003 and the LLM policy gateway
 * IM-E03-BL-006):
 * <ul>
 *   <li>HTTPS only; no user-info in URLs; the host must be on an explicit allow-list (exact, case-insensitive match).</li>
 *   <li>The resolved addresses of an allow-listed host must not be loopback / link-local / site-local / any-local,
 *       unless the host itself is a loopback literal and {@code allowLoopbackHttp} is set (LOCAL tests with a stub
 *       server only – never in DEV/UAT/PROD).</li>
 *   <li>Redirects are never followed (a 3xx answer is refused), response bodies are size-capped, timeouts are short.</li>
 *   <li>Header values (API keys, bearer tokens) are never logged or echoed in errors.</li>
 * </ul>
 */
public final class EgressClient {
    public record Response(int status, String body) { }

    private final Set<String> allowedHosts;
    private final boolean allowLoopbackHttp;
    private final int maxBytes;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();

    public EgressClient(Set<String> allowedHosts, boolean allowLoopbackHttp, int maxBytes) {
        this.allowedHosts = allowedHosts.stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).collect(Collectors.toUnmodifiableSet());
        this.allowLoopbackHttp = allowLoopbackHttp;
        this.maxBytes = maxBytes;
    }

    public static Set<String> hosts(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    public Set<String> allowedHosts() { return allowedHosts; }

    /** Validates a URL against the policy and returns it; throws 400/403 ApiException otherwise. */
    public URI check(String url) {
        URI u;
        try { u = URI.create(url); } catch (IllegalArgumentException e) { throw ApiException.bad("invalid-url", "invalid outbound URL"); }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || u.getRawUserInfo() != null) throw ApiException.bad("invalid-url", "outbound URL must have a host and no user-info");
        boolean loopbackLiteral = host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]") || host.equals("::1");
        if (!allowedHosts.contains(host)) throw ApiException.forbidden("egress-host-not-allowed", "host " + host + " is not on the egress allow-list");
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !("http".equals(scheme) && loopbackLiteral && allowLoopbackHttp)) {
            throw ApiException.forbidden("egress-https-required", "outbound calls must use HTTPS");
        }
        if (!(loopbackLiteral && allowLoopbackHttp)) {
            try {
                for (InetAddress a : InetAddress.getAllByName(host)) {
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()) {
                        throw ApiException.forbidden("egress-private-address", "host " + host + " resolves to a private or local address");
                    }
                }
            } catch (java.net.UnknownHostException e) {
                throw ApiException.conflict("egress-unresolvable", "host " + host + " cannot be resolved");
            }
        }
        return u;
    }

    public Response get(String url, Map<String, String> headers) { return send(url, "GET", null, headers); }

    public Response postJson(String url, String json, Map<String, String> headers) { return send(url, "POST", json, headers); }

    private Response send(String url, String method, String body, Map<String, String> headers) {
        URI u = check(url);
        HttpRequest.Builder b = HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
        headers.forEach(b::header);
        if (body != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        else b.method(method, HttpRequest.BodyPublishers.noBody());
        try {
            HttpResponse<InputStream> r = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            String text = read(r.body());
            if (r.statusCode() >= 300 && r.statusCode() < 400) throw ApiException.forbidden("egress-redirect", "redirect refused (" + r.statusCode() + ") from " + u.getHost());
            return new Response(r.statusCode(), text);
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.conflict("egress-interrupted", "outbound call interrupted");
        } catch (IOException e) {
            throw ApiException.conflict("egress-unreachable", u.getHost() + " unreachable: " + e.getClass().getSimpleName());
        }
    }

    private String read(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > maxBytes) throw ApiException.conflict("egress-response-too-large", "response exceeds " + maxBytes + " bytes");
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
