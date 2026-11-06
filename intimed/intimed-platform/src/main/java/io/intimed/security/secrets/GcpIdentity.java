package io.intimed.security.secrets;

import io.intimed.common.Json;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Google Cloud access tokens without SDK dependencies (IM-E06-BL-004 GCP track): on GKE with Workload Identity the
 * metadata server returns a token for the Google service account bound to the pod's Kubernetes service account; on
 * Compute Engine it returns the VM's service account token. Tokens are cached until five minutes before expiry.
 */
public final class GcpIdentity {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String metadata;
    private volatile String token;
    private volatile Instant expires = Instant.EPOCH;

    public GcpIdentity() { this("http://metadata.google.internal"); }

    /** For tests: injected metadata base URL. */
    public GcpIdentity(String metadataBase) { this.metadata = metadataBase.replaceAll("/$", ""); }

    public synchronized String token() {
        if (token != null && expires.isAfter(Instant.now().plusSeconds(300))) return token;
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(metadata + "/computeMetadata/v1/instance/service-accounts/default/token"))
                    .timeout(Duration.ofSeconds(5)).header("Metadata-Flavor", "Google").GET().build();
            HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("metadata server returned HTTP " + res.statusCode());
            JsonNode j = Json.parse(res.body());
            token = j.get("access_token").asString();
            expires = Instant.now().plusSeconds(j.path("expires_in").asLong(3600));
            return token;
        } catch (IllegalStateException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("GCP metadata server unavailable: " + e.getClass().getSimpleName()); }
    }

    /** Project id of the workload (metadata server). */
    public String projectId() {
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(metadata + "/computeMetadata/v1/project/project-id")).timeout(Duration.ofSeconds(5))
                    .header("Metadata-Flavor", "Google").GET().build();
            HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IllegalStateException("metadata server returned HTTP " + res.statusCode());
            return res.body().trim();
        } catch (IllegalStateException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("GCP metadata server unavailable: " + e.getClass().getSimpleName()); }
    }
}
