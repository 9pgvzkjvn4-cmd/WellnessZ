package io.intimed.security.secrets;

import io.intimed.common.Json;
import io.intimed.security.audit.AuditService;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * HashiCorp Vault KV v2 adapter ({@code intimed.secrets.provider=vault}). Tenants map to {@code <mount>/data/<tenant>/<name>}
 * so Vault policies can isolate tenants. Token comes from {@code VAULT_TOKEN} (never from configuration files).
 */
public class HashiCorpVaultSecretStore implements SecretStore {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String addr;
    private final String mount;
    private final String token;
    private final AuditService audit;

    public HashiCorpVaultSecretStore(String addr, String mount, String token, AuditService audit) {
        this.addr = addr.replaceAll("/$", "");
        this.mount = mount;
        this.token = token;
        this.audit = audit;
        if (token == null || token.isBlank()) throw new IllegalStateException("VAULT_TOKEN is required for the vault secrets provider");
    }

    @Override public String provider() { return "hashicorp-vault"; }

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(addr + "/v1/" + mount + path)).timeout(Duration.ofSeconds(10)).header("X-Vault-Token", token);
    }

    @Override
    public void put(String tenant, String name, String value, String actor) {
        LocalSecretStore.validateName(name);
        String body = Json.write(Json.obj().set("data", Json.obj().put("value", value)));
        send(req("/data/" + tenant + "/" + name).POST(HttpRequest.BodyPublishers.ofString(body)).build());
        audit.record(tenant, actor, "secret.put", "secret/" + name, "{\"provider\":\"hashicorp-vault\"}");
    }

    @Override
    public Optional<String> get(String tenant, String name) {
        HttpResponse<String> r = send(req("/data/" + tenant + "/" + name).GET().build());
        if (r.statusCode() == 404) return Optional.empty();
        return Optional.ofNullable(Json.parse(r.body()).at("/data/data/value").asString(null));
    }

    @Override
    public List<String> names(String tenant) {
        HttpResponse<String> r = send(req("/metadata/" + tenant + "?list=true").GET().build());
        List<String> out = new ArrayList<>();
        if (r.statusCode() == 200) for (JsonNode k : Json.parse(r.body()).at("/data/keys")) out.add(k.asString());
        return out;
    }

    @Override
    public void delete(String tenant, String name, String actor) {
        send(req("/metadata/" + tenant + "/" + name).DELETE().build());
        audit.record(tenant, actor, "secret.delete", "secret/" + name, null);
    }

    private HttpResponse<String> send(HttpRequest r) {
        try { return http.send(r, HttpResponse.BodyHandlers.ofString()); }
        catch (Exception e) { throw new IllegalStateException("Vault unavailable: " + e.getClass().getSimpleName()); }
    }
}
