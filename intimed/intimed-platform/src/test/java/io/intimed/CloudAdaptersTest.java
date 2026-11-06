package io.intimed;

import com.sun.net.httpserver.HttpServer;
import io.intimed.common.Json;
import io.intimed.security.secrets.AzureIdentity;
import io.intimed.security.secrets.AzureKeyVaultSecretStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Azure adapters (IM-E06-BL-004 Azure track): Key Vault secret store and Entra workload-identity tokens against local stubs. */
class CloudAdaptersTest {
    static {
        try { System.setProperty("intimed.home", Files.createTempDirectory("intimed-cloud").toString()); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    HttpServer server;
    final Map<String, ObjectNode> vault = new ConcurrentHashMap<>();
    final AtomicInteger tokenCalls = new AtomicInteger();
    String lastAuth;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/secrets", ex -> {
            lastAuth = ex.getRequestHeaders().getFirst("Authorization");
            String path = ex.getRequestURI().getPath();
            String name = path.length() > "/secrets/".length() ? path.substring("/secrets/".length()) : null;
            int code = 200;
            String body;
            switch (ex.getRequestMethod()) {
                case "PUT" -> {
                    ObjectNode b = (ObjectNode) Json.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    b.put("id", "https://kv/secrets/" + name);
                    vault.put(name, b);
                    body = Json.write(b);
                }
                case "DELETE" -> { body = vault.remove(name) == null ? "{}" : "{}"; }
                default -> {
                    if (name == null) {
                        ArrayNode v = Json.arr();
                        vault.values().forEach(s -> v.addObject().put("id", s.get("id").asString()).set("tags", s.get("tags")));
                        body = Json.write(Json.obj().set("value", v));
                    } else if (vault.containsKey(name)) body = Json.write(vault.get(name));
                    else { code = 404; body = "{}"; }
                }
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/tenant-x/oauth2/v2.0/token", ex -> {
            tokenCalls.incrementAndGet();
            String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean ok = form.contains("client_assertion=federated-jwt") && form.contains("client_id=app-1") && form.contains("vault.azure.net");
            byte[] out = (ok ? "{\"access_token\":\"tok-1\",\"expires_in\":3600}" : "{}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(ok ? 200 : 400, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @Test
    void keyVaultStoreIsTenantBoundAndNeverUsesPlainNames() {
        AzureKeyVaultSecretStore kv = new AzureKeyVaultSecretStore(base(), () -> "tok-1", null, true);
        assertThatThrownBy(() -> kv.put("t-a", "INVALID NAME", "x", "u")).isInstanceOf(IllegalArgumentException.class);
        AzureKeyVaultSecretStore audited = new AzureKeyVaultSecretStore(base(), () -> "tok-1", new NoopAudit(), true);
        audited.put("t-a", "hl7/pump-gateway", "s3cr3t-value", "user:x");
        audited.put("t-b", "hl7/pump-gateway", "other-tenant", "user:y");
        assertThat(lastAuth).isEqualTo("Bearer tok-1");
        assertThat(vault.keySet()).allMatch(k -> k.matches("im-[0-9a-f]{40}")).noneMatch(k -> k.contains("pump"));
        assertThat(audited.get("t-a", "hl7/pump-gateway")).contains("s3cr3t-value");
        assertThat(audited.get("t-b", "hl7/pump-gateway")).contains("other-tenant");
        assertThat(audited.names("t-a")).containsExactly("hl7/pump-gateway");
        assertThat(audited.get("t-a", "missing")).isEmpty();
        // a secret whose tags name another tenant is never returned (defence in depth)
        String id = vault.keySet().iterator().next();
        ObjectNode tampered = vault.get(id);
        String owner = tampered.at("/tags/intimedTenant").asString();
        tampered.withObject("tags").put("intimedTenant", "someone-else");
        assertThat(audited.get(owner, "hl7/pump-gateway")).isEmpty();
        audited.delete("t-b", "hl7/pump-gateway", "user:y");
        assertThat(audited.names("t-b")).isEmpty();
    }

    @Test
    void keyVaultUriMustBeHttpsAzureHost() {
        assertThatThrownBy(() -> new AzureKeyVaultSecretStore("http://kv.example.com", () -> "t", null, false)).hasMessageContaining("https");
        assertThatThrownBy(() -> new AzureKeyVaultSecretStore("https://evil.example.com", () -> "t", null, false)).hasMessageContaining("vault.azure.net");
        assertThat(new AzureKeyVaultSecretStore("https://kv-intimed-uat-qc.vault.azure.net/", () -> "t", null, false).provider()).isEqualTo("azure-keyvault");
    }

    @Test
    void workloadIdentityExchangesTheFederatedTokenAndCaches() throws Exception {
        Path f = Files.createTempFile("sa-token", ".jwt");
        Files.writeString(f, "federated-jwt\n");
        AzureIdentity id = new AzureIdentity(Map.of("AZURE_FEDERATED_TOKEN_FILE", f.toString(), "AZURE_CLIENT_ID", "app-1", "AZURE_TENANT_ID", "tenant-x",
                "AZURE_AUTHORITY_HOST", base() + "/"), "http://127.0.0.1:1");
        assertThat(id.mode()).isEqualTo("workload-identity");
        assertThat(id.token("https://vault.azure.net/.default")).isEqualTo("tok-1");
        assertThat(id.token("https://vault.azure.net/.default")).isEqualTo("tok-1");
        assertThat(tokenCalls.get()).isEqualTo(1);
        AzureIdentity noImds = new AzureIdentity(Map.of(), "http://127.0.0.1:1");
        assertThatThrownBy(() -> noImds.token("https://vault.azure.net/.default")).hasMessageContaining("managed identity unavailable");
    }

    @Test
    void blobArchiverUploadsEvidenceAndAuditSegmentsWriteOnce() throws Exception {
        Map<String, byte[]> blobs = new ConcurrentHashMap<>();
        server.createContext("/evidence", ex -> store(ex, blobs));
        server.createContext("/audit", ex -> store(ex, blobs));
        Path home = io.intimed.common.Paths2.home();
        Files.writeString(io.intimed.common.Paths2.dir("evidence").resolve("run-abc.zip"), "PK-fake-zip");
        Path siem = io.intimed.common.Paths2.dir("siem").resolve("audit.jsonl");
        Files.writeString(siem, "{\"seq\":1}\n{\"seq\":2}\n{\"seq\":3,\"partial");
        io.intimed.cloud.BlobArchiver a = io.intimed.cloud.BlobArchiver.forTest(base(), () -> "tok-1");
        ObjectNode r1 = a.archive();
        assertThat(r1.get("evidenceUploaded").asInt()).isEqualTo(1);
        assertThat(r1.get("auditSegmentsUploaded").asInt()).isEqualTo(1);
        assertThat(blobs.keySet()).anyMatch(k -> k.equals("/evidence/test/run-abc.zip")).anyMatch(k -> k.startsWith("/audit/test/"));
        String seg = blobs.entrySet().stream().filter(e -> e.getKey().startsWith("/audit/")).map(e -> new String(e.getValue(), StandardCharsets.UTF_8)).findFirst().orElseThrow();
        assertThat(seg).contains("\"seq\":2").doesNotContain("partial"); // only complete lines
        ObjectNode r2 = a.archive();
        assertThat(r2.get("evidenceUploaded").asInt()).isZero();
        assertThat(r2.get("auditSegmentsUploaded").asInt()).isZero();
        Files.writeString(siem, "\"}\n", java.nio.file.StandardOpenOption.APPEND);
        assertThat(a.archive().get("auditSegmentsUploaded").asInt()).isEqualTo(1);
        assertThat(home).exists();
        assertThatThrownBy(() -> new io.intimed.cloud.BlobArchiver("https://evil.example.com", "e", "a", "p")).hasMessageContaining("blob.core.windows.net");
    }

    private static void store(com.sun.net.httpserver.HttpExchange ex, Map<String, byte[]> blobs) throws java.io.IOException {
        String key = ex.getRequestURI().getPath();
        boolean ok = "PUT".equals(ex.getRequestMethod()) && "*".equals(ex.getRequestHeaders().getFirst("If-None-Match"))
                && "Bearer tok-1".equals(ex.getRequestHeaders().getFirst("Authorization")) && "BlockBlob".equals(ex.getRequestHeaders().getFirst("x-ms-blob-type"));
        int code = !ok ? 400 : blobs.putIfAbsent(key, ex.getRequestBody().readAllBytes()) == null ? 201 : 409;
        ex.sendResponseHeaders(code, -1);
        ex.close();
    }

    /** Audit stand-in: the real AuditService needs the database. */
    static final class NoopAudit extends io.intimed.security.audit.AuditService {
        NoopAudit() { super(null); }
        @Override public synchronized long record(String tenant, String actor, String action, String target, Object detail) { return 0; }
    }
}
