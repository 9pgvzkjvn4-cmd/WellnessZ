package io.intimed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.intimed.common.Json;
import io.intimed.security.secrets.GcpIdentity;
import io.intimed.security.secrets.GcpSecretManagerSecretStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** IntiMed_GCP adapters against local stubs: Secret Manager (Doha-only replication), metadata tokens, create-only GCS archive. */
class GcpAdaptersTest {
    static {
        try { System.setProperty("intimed.home", Files.createTempDirectory("intimed-gcp").toString()); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    HttpServer server;
    final Map<String, ObjectNode> secrets = new ConcurrentHashMap<>();
    final Map<String, List<String>> versions = new ConcurrentHashMap<>();
    final List<JsonNode> createBodies = new ArrayList<>();
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final AtomicInteger tokenCalls = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/projects/intimed-uat-qa", this::secretManager);
        server.createContext("/computeMetadata/v1/instance/service-accounts/default/token", ex -> {
            tokenCalls.incrementAndGet();
            boolean ok = "Google".equals(ex.getRequestHeaders().getFirst("Metadata-Flavor"));
            reply(ex, ok ? 200 : 403, ok ? "{\"access_token\":\"ya29.test\",\"expires_in\":3599,\"token_type\":\"Bearer\"}" : "{}");
        });
        server.createContext("/upload/storage/v1/b/", ex -> {
            String q = ex.getRequestURI().getRawQuery();
            String bucket = ex.getRequestURI().getPath().split("/")[5];
            String name = java.net.URLDecoder.decode(q.replaceAll(".*name=", ""), StandardCharsets.UTF_8);
            boolean ok = "POST".equals(ex.getRequestMethod()) && q.contains("ifGenerationMatch=0") && "Bearer ya29.test".equals(ex.getRequestHeaders().getFirst("Authorization"));
            int code = !ok ? 400 : objects.putIfAbsent(bucket + "/" + name, ex.getRequestBody().readAllBytes()) == null ? 200 : 412;
            reply(ex, code, "{}");
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
        ex.close();
    }

    void secretManager(HttpExchange ex) throws IOException {
        if (!"Bearer ya29.test".equals(ex.getRequestHeaders().getFirst("Authorization"))) { reply(ex, 401, "{}"); return; }
        String path = ex.getRequestURI().getPath().substring("/v1/projects/intimed-uat-qa".length());
        String query = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
        String m = ex.getRequestMethod();
        if (path.equals("/secrets") && m.equals("POST")) {
            String id = query.replaceAll(".*secretId=([^&]+).*", "$1");
            ObjectNode b = (ObjectNode) Json.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            createBodies.add(b);
            if (secrets.containsKey(id)) { reply(ex, 409, "{}"); return; }
            b.put("name", "projects/intimed-uat-qa/secrets/" + id);
            secrets.put(id, b);
            reply(ex, 200, Json.write(b));
        } else if (path.equals("/secrets") && m.equals("GET")) {
            String label = java.net.URLDecoder.decode(query, StandardCharsets.UTF_8).replaceAll(".*labels\\.intimed-tenant=([^&]+).*", "$1");
            ArrayNode a = Json.arr();
            secrets.values().stream().filter(s -> label.equals(s.at("/labels/intimed-tenant").asString())).forEach(a::add);
            reply(ex, 200, Json.write(Json.obj().set("secrets", a)));
        } else if (path.endsWith(":addVersion")) {
            String id = path.substring("/secrets/".length(), path.indexOf(':'));
            JsonNode b = Json.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            versions.computeIfAbsent(id, k -> new ArrayList<>()).add(b.at("/payload/data").asString());
            reply(ex, 200, "{}");
        } else if (path.endsWith("/versions/latest:access")) {
            String id = path.substring("/secrets/".length(), path.indexOf("/versions"));
            List<String> v = versions.get(id);
            if (v == null) { reply(ex, 404, "{}"); return; }
            reply(ex, 200, Json.write(Json.obj().set("payload", Json.obj().put("data", v.get(v.size() - 1)))));
        } else if (path.startsWith("/secrets/")) {
            String id = path.substring("/secrets/".length());
            if (m.equals("DELETE")) { secrets.remove(id); versions.remove(id); reply(ex, 200, "{}"); return; }
            ObjectNode s = secrets.get(id);
            reply(ex, s == null ? 404 : 200, s == null ? "{}" : Json.write(s));
        } else reply(ex, 404, "{}");
    }

    @Test
    void secretManagerStoreReplicatesOnlyToDohaAndIsTenantBound() {
        GcpSecretManagerSecretStore sm = new GcpSecretManagerSecretStore(base() + "/v1", "intimed-uat-qa", "me-central1", () -> "ya29.test", new CloudAdaptersTest.NoopAudit(), true);
        sm.put("t-a", "hl7/pump-gateway", "v1-secret", "user:x");
        sm.put("t-a", "hl7/pump-gateway", "v2-secret", "user:x");   // second put → 409 on create, new version
        sm.put("t-b", "hl7/pump-gateway", "other-tenant", "user:y");
        assertThat(createBodies).allSatisfy(b -> {
            assertThat(b.at("/replication/userManaged/replicas").size()).isEqualTo(1);
            assertThat(b.at("/replication/userManaged/replicas/0/location").asString()).isEqualTo("me-central1");
            assertThat(b.has("automatic")).isFalse();
        });
        assertThat(secrets.keySet()).allMatch(k -> k.matches("im-[0-9a-f]{40}"));
        assertThat(sm.get("t-a", "hl7/pump-gateway")).contains("v2-secret");
        assertThat(sm.get("t-b", "hl7/pump-gateway")).contains("other-tenant");
        assertThat(sm.names("t-a")).containsExactly("hl7/pump-gateway");
        assertThat(sm.get("t-a", "missing")).isEmpty();
        // annotation names another tenant → never returned
        ObjectNode s = secrets.values().iterator().next();
        String owner = s.at("/annotations/intimed-tenant").asString();
        s.withObject("annotations").put("intimed-tenant", "someone-else");
        assertThat(sm.get(owner, "hl7/pump-gateway")).isEmpty();
        sm.delete("t-b", "hl7/pump-gateway", "user:y");
        assertThat(sm.names("t-b")).isEmpty();
    }

    @Test
    void secretManagerRejectsBadEndpointsProjectsAndRegions() {
        assertThatThrownBy(() -> new GcpSecretManagerSecretStore("http://secretmanager.googleapis.com/v1", "intimed-uat-qa", "me-central1", () -> "t", null, false)).hasMessageContaining("https");
        assertThatThrownBy(() -> new GcpSecretManagerSecretStore("https://evil.example.com/v1", "intimed-uat-qa", "me-central1", () -> "t", null, false)).hasMessageContaining("googleapis.com");
        assertThatThrownBy(() -> new GcpSecretManagerSecretStore("https://secretmanager.googleapis.com/v1", "Bad_Project", "me-central1", () -> "t", null, false)).hasMessageContaining("project");
        assertThatThrownBy(() -> new GcpSecretManagerSecretStore("https://secretmanager.googleapis.com/v1", "intimed-uat-qa", "global", () -> "t", null, false)).hasMessageContaining("region");
    }

    @Test
    void metadataServerTokenIsCached() {
        GcpIdentity id = new GcpIdentity(base());
        assertThat(id.token()).isEqualTo("ya29.test");
        assertThat(id.token()).isEqualTo("ya29.test");
        assertThat(tokenCalls.get()).isEqualTo(1);
        assertThatThrownBy(() -> new GcpIdentity("http://127.0.0.1:1").token()).hasMessageContaining("metadata server unavailable");
    }

    @Test
    void gcsArchiveIsCreateOnly() throws Exception {
        Files.writeString(io.intimed.common.Paths2.dir("evidence").resolve("run-gcp.zip"), "PK-gcp");
        Path siem = io.intimed.common.Paths2.dir("siem").resolve("audit.jsonl");
        Files.writeString(siem, "{\"seq\":1}\n{\"seq\":2}\n");
        io.intimed.cloud.BlobArchiver a = io.intimed.cloud.BlobArchiver.forTestGcs(base(), () -> "ya29.test", "intimed-uat-evidence", "intimed-uat-audit");
        ObjectNode r = a.archive();
        assertThat(r.get("evidenceUploaded").asInt()).isEqualTo(1);
        assertThat(r.get("auditSegmentsUploaded").asInt()).isEqualTo(1);
        assertThat(objects.keySet()).anyMatch(k -> k.equals("intimed-uat-evidence/test/run-gcp.zip")).anyMatch(k -> k.startsWith("intimed-uat-audit/test/"));
        assertThat(a.archive().get("evidenceUploaded").asInt()).isZero();
        assertThatThrownBy(() -> new io.intimed.cloud.BlobArchiver("", "e", "a", "p", "Bad Bucket!", "x")).hasMessageContaining("bucket");
    }
}
