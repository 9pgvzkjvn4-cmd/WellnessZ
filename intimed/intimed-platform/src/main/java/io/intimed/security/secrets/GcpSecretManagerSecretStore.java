package io.intimed.security.secrets;

import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.security.audit.AuditService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Google Cloud Secret Manager adapter ({@code intimed.secrets.provider=gcp-secret-manager}) – IM-E06-BL-004 (GCP Secret
 * Manager behind the one secrets interface). Authentication by GKE Workload Identity ({@link GcpIdentity}). Data
 * residency: every secret is created with <b>user-managed replication to the configured location only</b>
 * (me-central1, Doha) – never automatic (multi-region) replication. Secret ids are {@code im-<sha256(tenant,name)[0..40]>};
 * tenant and logical name travel as label / annotations; values never appear in logs or audit records.
 */
public final class GcpSecretManagerSecretStore implements SecretStore {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String api, project, location;
    private final Supplier<String> token;
    private final AuditService audit;

    public GcpSecretManagerSecretStore(String apiBase, String project, String location, Supplier<String> token, AuditService audit, boolean allowHttpForTests) {
        if (project == null || !project.matches("[a-z][a-z0-9-]{4,28}[a-z0-9]")) throw new IllegalStateException("intimed.secrets.gcp.project must be a valid project id");
        if (location == null || !location.matches("[a-z]+-[a-z]+[0-9]+")) throw new IllegalStateException("intimed.secrets.gcp.location must be a region such as me-central1");
        URI u = URI.create(apiBase);
        if (!"https".equals(u.getScheme()) && !allowHttpForTests) throw new IllegalStateException("Secret Manager endpoint must be https");
        if ("https".equals(u.getScheme()) && !u.getHost().endsWith(".googleapis.com")) throw new IllegalStateException("Secret Manager host must end with .googleapis.com");
        this.api = apiBase.replaceAll("/$", "");
        this.project = project;
        this.location = location;
        this.token = token;
        this.audit = audit;
    }

    @Override public String provider() { return "gcp-secret-manager"; }

    static String secretId(String tenant, String name) { return "im-" + Hashing.sha256(tenant + "\n" + name).substring(0, 40); }

    static String label(String tenant) { return tenant.toLowerCase().replaceAll("[^a-z0-9_-]", "-"); }

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(api + "/projects/" + project + path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token.get());
    }

    @Override
    public void put(String tenant, String name, String value, String actor) {
        LocalSecretStore.validateName(name);
        String id = secretId(tenant, name);
        ObjectNode create = Json.obj();
        create.withObject("replication").withObject("userManaged").putArray("replicas").addObject().put("location", location);
        create.putObject("labels").put("intimed-tenant", label(tenant)).put("app", "intimed");
        create.putObject("annotations").put("intimed-tenant", tenant).put("intimed-name", name);
        HttpResponse<String> c = send(req("/secrets?secretId=" + id).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(create))).build());
        if (c.statusCode() / 100 != 2 && c.statusCode() != 409) throw new IllegalStateException("Secret Manager create failed: HTTP " + c.statusCode());
        ObjectNode ver = Json.obj();
        ver.putObject("payload").put("data", Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        HttpResponse<String> v = send(req("/secrets/" + id + ":addVersion").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(ver))).build());
        if (v.statusCode() / 100 != 2) throw new IllegalStateException("Secret Manager addVersion failed: HTTP " + v.statusCode());
        audit.record(tenant, actor, "secret.put", "secret/" + name, "{\"provider\":\"gcp-secret-manager\",\"location\":\"" + location + "\"}");
    }

    @Override
    public Optional<String> get(String tenant, String name) {
        String id = secretId(tenant, name);
        HttpResponse<String> meta = send(req("/secrets/" + id).GET().build());
        if (meta.statusCode() == 404) return Optional.empty();
        if (meta.statusCode() / 100 != 2) throw new IllegalStateException("Secret Manager get failed: HTTP " + meta.statusCode());
        if (!tenant.equals(Json.parse(meta.body()).at("/annotations/intimed-tenant").asString(""))) return Optional.empty(); // tenant binding
        HttpResponse<String> r = send(req("/secrets/" + id + "/versions/latest:access").GET().build());
        if (r.statusCode() == 404) return Optional.empty();
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("Secret Manager access failed: HTTP " + r.statusCode());
        String data = Json.parse(r.body()).at("/payload/data").asString(null);
        return data == null ? Optional.empty() : Optional.of(new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8));
    }

    @Override
    public List<String> names(String tenant) {
        List<String> out = new ArrayList<>();
        String filter = URLEncoder.encode("labels.intimed-tenant=" + label(tenant), StandardCharsets.UTF_8);
        String pageToken = null;
        int pages = 0;
        do {
            HttpResponse<String> r = send(req("/secrets?pageSize=100&filter=" + filter + (pageToken == null ? "" : "&pageToken=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8))).GET().build());
            if (r.statusCode() / 100 != 2) throw new IllegalStateException("Secret Manager list failed: HTTP " + r.statusCode());
            JsonNode j = Json.parse(r.body());
            for (JsonNode s : j.path("secrets")) if (tenant.equals(s.at("/annotations/intimed-tenant").asString(""))) out.add(s.at("/annotations/intimed-name").asString());
            pageToken = j.path("nextPageToken").asString(null);
        } while (pageToken != null && !pageToken.isEmpty() && ++pages < 200);
        return out;
    }

    @Override
    public void delete(String tenant, String name, String actor) {
        HttpResponse<String> r = send(req("/secrets/" + secretId(tenant, name)).DELETE().build());
        if (r.statusCode() / 100 != 2 && r.statusCode() != 404) throw new IllegalStateException("Secret Manager delete failed: HTTP " + r.statusCode());
        audit.record(tenant, actor, "secret.delete", "secret/" + name, "{\"provider\":\"gcp-secret-manager\"}");
    }

    private HttpResponse<String> send(HttpRequest r) {
        try { return http.send(r, HttpResponse.BodyHandlers.ofString()); }
        catch (Exception e) { throw new IllegalStateException("Secret Manager unavailable: " + e.getClass().getSimpleName()); }
    }
}
