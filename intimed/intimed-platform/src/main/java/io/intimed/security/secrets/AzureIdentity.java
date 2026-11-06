package io.intimed.security.secrets;

import io.intimed.common.Json;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Microsoft Entra token acquisition without SDK dependencies (IM-E06-BL-004 Azure track): AKS <b>workload identity</b>
 * (federated service-account token in {@code AZURE_FEDERATED_TOKEN_FILE}, client-assertion grant) or, on VMs / node
 * managed identity, the Instance Metadata Service. Tokens are cached per scope until five minutes before expiry; no
 * secret is ever stored.
 */
public final class AzureIdentity {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, String> env;
    private final Map<String, Token> cache = new ConcurrentHashMap<>();
    private final String imds;

    record Token(String value, Instant expires) { }

    public AzureIdentity() { this(System.getenv(), "http://169.254.169.254"); }

    /** For tests: injected environment and IMDS base. */
    public AzureIdentity(Map<String, String> env, String imdsBase) {
        this.env = env;
        this.imds = imdsBase;
    }

    public String mode() { return env.get("AZURE_FEDERATED_TOKEN_FILE") != null ? "workload-identity" : "managed-identity-imds"; }

    /** @param scope e.g. {@code https://vault.azure.net/.default} */
    public String token(String scope) {
        Token t = cache.get(scope);
        if (t != null && t.expires().isAfter(Instant.now().plusSeconds(300))) return t.value();
        Token n = env.get("AZURE_FEDERATED_TOKEN_FILE") != null ? workload(scope) : imds(scope);
        cache.put(scope, n);
        return n.value();
    }

    private Token workload(String scope) {
        try {
            String assertion = Files.readString(Path.of(env.get("AZURE_FEDERATED_TOKEN_FILE"))).trim();
            String authority = env.getOrDefault("AZURE_AUTHORITY_HOST", "https://login.microsoftonline.com/");
            if (!authority.endsWith("/")) authority += "/";
            String body = "grant_type=client_credentials&client_id=" + enc(env.get("AZURE_CLIENT_ID")) + "&scope=" + enc(scope)
                    + "&client_assertion_type=" + enc("urn:ietf:params:oauth:client-assertion-type:jwt-bearer") + "&client_assertion=" + enc(assertion);
            HttpRequest r = HttpRequest.newBuilder(URI.create(authority + env.get("AZURE_TENANT_ID") + "/oauth2/v2.0/token")).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return parse(http.send(r, HttpResponse.BodyHandlers.ofString()), "expires_in");
        } catch (IllegalStateException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Entra token request failed: " + e.getClass().getSimpleName()); }
    }

    private Token imds(String scope) {
        try {
            String resource = scope.endsWith("/.default") ? scope.substring(0, scope.length() - "/.default".length()) : scope;
            String q = "api-version=2018-02-01&resource=" + enc(resource) + (env.get("AZURE_CLIENT_ID") == null ? "" : "&client_id=" + enc(env.get("AZURE_CLIENT_ID")));
            HttpRequest r = HttpRequest.newBuilder(URI.create(imds + "/metadata/identity/oauth2/token?" + q)).timeout(Duration.ofSeconds(5)).header("Metadata", "true").GET().build();
            return parse(http.send(r, HttpResponse.BodyHandlers.ofString()), "expires_in");
        } catch (IllegalStateException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("managed identity unavailable: " + e.getClass().getSimpleName()); }
    }

    private static Token parse(HttpResponse<String> r, String expiresField) {
        if (r.statusCode() != 200) throw new IllegalStateException("token endpoint returned HTTP " + r.statusCode());
        JsonNode j = Json.parse(r.body());
        long secs = j.path(expiresField).asLong(j.path("expires_in").asLong(3600));
        return new Token(j.get("access_token").asString(), Instant.now().plusSeconds(secs));
    }

    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
}
