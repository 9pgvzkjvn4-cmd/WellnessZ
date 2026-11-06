package io.intimed.security.secrets;

import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.security.audit.AuditService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Azure Key Vault adapter ({@code intimed.secrets.provider=azure-keyvault}) – IM-E06-BL-004 (Azure Key Vault behind the
 * one secrets interface). Authentication by Entra workload identity / managed identity ({@link AzureIdentity}); no key,
 * password or connection string is configured. Key Vault object names only allow letters, digits and dashes, so each
 * secret is stored under {@code im-<sha256(tenant, name)[0..40]>} with the tenant and logical name as tags; values never
 * appear in logs or audit records. Use a Premium vault (HSM-backed keys) with RBAC, purge protection and private endpoint
 * – see deploy/azure/terraform/keyvault.tf.
 */
public final class AzureKeyVaultSecretStore implements SecretStore {
    private static final String API = "api-version=7.4";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String vault;
    private final Supplier<String> token;
    private final AuditService audit;

    public AzureKeyVaultSecretStore(String vaultUri, Supplier<String> token, AuditService audit, boolean allowHttpForTests) {
        if (vaultUri == null || vaultUri.isBlank()) throw new IllegalStateException("intimed.secrets.azure.vault-uri is required for the azure-keyvault provider");
        URI u = URI.create(vaultUri);
        if (!"https".equals(u.getScheme()) && !allowHttpForTests) throw new IllegalStateException("Key Vault URI must be https");
        if ("https".equals(u.getScheme()) && !u.getHost().endsWith(".vault.azure.net")) throw new IllegalStateException("Key Vault host must end with .vault.azure.net");
        this.vault = vaultUri.replaceAll("/$", "");
        this.token = token;
        this.audit = audit;
    }

    @Override public String provider() { return "azure-keyvault"; }

    static String objectName(String tenant, String name) { return "im-" + Hashing.sha256(tenant + "\n" + name).substring(0, 40); }

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(vault + path + (path.contains("?") ? "&" : "?") + API)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token.get());
    }

    @Override
    public void put(String tenant, String name, String value, String actor) {
        LocalSecretStore.validateName(name);
        ObjectNode body = Json.obj().put("value", value).put("contentType", "text/plain");
        body.putObject("tags").put("intimedTenant", tenant).put("intimedName", name);
        body.putObject("attributes").put("enabled", true);
        HttpResponse<String> r = send(req("/secrets/" + objectName(tenant, name)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(Json.write(body))).build());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("Key Vault put failed: HTTP " + r.statusCode());
        audit.record(tenant, actor, "secret.put", "secret/" + name, "{\"provider\":\"azure-keyvault\"}");
    }

    @Override
    public Optional<String> get(String tenant, String name) {
        HttpResponse<String> r = send(req("/secrets/" + objectName(tenant, name)).GET().build());
        if (r.statusCode() == 404) return Optional.empty();
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("Key Vault get failed: HTTP " + r.statusCode());
        JsonNode j = Json.parse(r.body());
        if (!tenant.equals(j.at("/tags/intimedTenant").asString(""))) return Optional.empty(); // defence in depth: tenant binding
        return Optional.ofNullable(j.path("value").asString(null));
    }

    @Override
    public List<String> names(String tenant) {
        List<String> out = new ArrayList<>();
        String next = vault + "/secrets?maxresults=25&" + API;
        int pages = 0;
        while (next != null && pages++ < 200) {
            if (!next.startsWith(vault + "/")) break; // never follow a nextLink to another host
            HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(next)).timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token.get()).GET().build());
            if (r.statusCode() / 100 != 2) throw new IllegalStateException("Key Vault list failed: HTTP " + r.statusCode());
            JsonNode j = Json.parse(r.body());
            for (JsonNode s : j.path("value")) if (tenant.equals(s.at("/tags/intimedTenant").asString(""))) out.add(s.at("/tags/intimedName").asString());
            next = j.path("nextLink").asString(null);
        }
        return out;
    }

    @Override
    public void delete(String tenant, String name, String actor) {
        HttpResponse<String> r = send(req("/secrets/" + objectName(tenant, name)).DELETE().build());
        if (r.statusCode() / 100 != 2 && r.statusCode() != 404) throw new IllegalStateException("Key Vault delete failed: HTTP " + r.statusCode());
        audit.record(tenant, actor, "secret.delete", "secret/" + name, "{\"provider\":\"azure-keyvault\",\"softDeleted\":true}");
    }

    private HttpResponse<String> send(HttpRequest r) {
        try { return http.send(r, HttpResponse.BodyHandlers.ofString()); }
        catch (Exception e) { throw new IllegalStateException("Key Vault unavailable: " + e.getClass().getSimpleName()); }
    }
}
