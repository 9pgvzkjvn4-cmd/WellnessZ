package io.intimed.security.endpoint;

import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.events.EventBus;
import io.intimed.security.audit.AuditService;
import io.intimed.security.pki.LocalPki;
import io.intimed.store.DocumentStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-endpoint identity (SC-01, SC-14, IM-E06-F01 / IM-E06-BL-001): gateways, device-host agents, mobile apps, kiosks
 * and harness probes register in the console, receive a single-use enrolment token (15 min, stored as SHA-256), and
 * enrol with a CSR – the private key never leaves the endpoint. Every connection is re-checked against the registry
 * (fingerprint → ACTIVE), so revocation and isolation take effect immediately (SC-09: isolation &lt; 15 min).
 */
@Service
public class EndpointService {
    public static final Duration TOKEN_TTL = Duration.ofMinutes(15);
    public static final Duration CERT_VALIDITY = Duration.ofDays(90);
    public static final List<String> TYPES = List.of("GATEWAY", "DEVICE_HOST", "MOBILE", "KIOSK", "VENDOR_CLOUD", "HARNESS");
    private final DocumentStore store;
    private final AuditService audit;
    private final EventBus events;

    public EndpointService(DocumentStore store, AuditService audit, EventBus events) {
        this.store = store;
        this.audit = audit;
        this.events = events;
    }

    public record Registered(ObjectNode endpoint, String enrolmentToken) { }

    public Registered register(String tenant, String name, String type, String site, String zone, String actor) {
        if (!TYPES.contains(type)) throw ApiException.bad("invalid-type", "endpoint type must be one of " + TYPES);
        if (name == null || !name.matches("[A-Za-z0-9 ()._/-]{3,60}")) throw ApiException.bad("invalid-name", "invalid endpoint name");
        String id = "ep-" + Hashing.randomToken(6).toLowerCase().replaceAll("[^a-z0-9]", "0");
        ObjectNode e = Json.obj().put("id", id).put("name", name).put("type", type).put("site", site).put("zone", zone == null ? "MDZ-1" : zone)
                .put("status", "PENDING").put("createdAt", Instant.now().toString()).put("createdBy", actor);
        e.putArray("configs");
        String token = issueToken(tenant, e);
        store.put(tenant, "endpoint", id, e, "PENDING", null);
        audit.record(tenant, actor, "endpoint.register", "endpoint/" + id, Map.of("type", type, "zone", e.get("zone").asString()));
        return new Registered(e, token);
    }

    private String issueToken(String tenant, ObjectNode e) {
        String token = "enr_" + Hashing.randomToken(24);
        e.put("enrolTokenSha256", Hashing.sha256(token)).put("enrolTokenExpires", Instant.now().plus(TOKEN_TTL).toString());
        store.put(DocumentStore.GLOBAL, "enrol-token", Hashing.sha256(token), Json.obj().put("tenant", tenant).put("endpoint", e.get("id").asString())
                .put("expires", e.get("enrolTokenExpires").asString()), "OPEN", null);
        return token;
    }

    /** Rotation: a new single-use token; the old certificate stays valid until the new one is enrolled. */
    public String rotationToken(String tenant, String id, String actor) {
        ObjectNode e = require(tenant, id);
        if (List.of("REVOKED", "ISOLATED").contains(e.get("status").asString())) throw ApiException.conflict("endpoint-blocked", "endpoint is " + e.get("status").asString());
        String t = issueToken(tenant, e);
        store.put(tenant, "endpoint", id, e, e.get("status").asString(), e.path("certFingerprint").asString(null));
        audit.record(tenant, actor, "endpoint.rotation-token", "endpoint/" + id, null);
        return t;
    }

    /** Enrolment with a one-time token and a PKCS#10 CSR (proof of possession). Returns the PEM chain. */
    public Map<String, String> enrol(String token, String csrPem) {
        if (token == null || !token.startsWith("enr_")) throw ApiException.forbidden("bad-token", "invalid enrolment token");
        String h = Hashing.sha256(token);
        ObjectNode t = store.get(DocumentStore.GLOBAL, "enrol-token", h).orElseThrow(() -> ApiException.forbidden("bad-token", "invalid or used enrolment token"));
        store.delete(DocumentStore.GLOBAL, "enrol-token", h);
        String tenant = t.get("tenant").asString();
        if (Instant.parse(t.get("expires").asString()).isBefore(Instant.now())) {
            audit.record(tenant, "anonymous", "endpoint.enrol.expired", "endpoint/" + t.get("endpoint").asString(), null);
            throw ApiException.forbidden("token-expired", "enrolment token expired");
        }
        ObjectNode e = require(tenant, t.get("endpoint").asString());
        if (!Hashing.equalsConstantTime(h, e.path("enrolTokenSha256").asString(""))) throw ApiException.forbidden("bad-token", "token superseded");
        X509Certificate cert = LocalPki.get().issueFromCsr(tenant, e.get("id").asString(), csrPem, CERT_VALIDITY);
        String fp = LocalPki.fingerprint(cert);
        if (e.hasNonNull("certFingerprint")) {
            e.withArray("previousFingerprints").add(e.get("certFingerprint").asString());
            store.delete(DocumentStore.GLOBAL, "endpoint-fp", e.get("certFingerprint").asString());
        }
        e.put("status", "ACTIVE").put("certFingerprint", fp).put("certSerial", cert.getSerialNumber().toString(16))
                .put("notAfter", cert.getNotAfter().toInstant().toString()).put("enrolledAt", Instant.now().toString());
        e.remove("enrolTokenSha256");
        e.remove("enrolTokenExpires");
        store.put(tenant, "endpoint", e.get("id").asString(), e, "ACTIVE", fp);
        store.put(DocumentStore.GLOBAL, "endpoint-fp", fp, Json.obj().put("tenant", tenant).put("endpoint", e.get("id").asString()), "ACTIVE", tenant);
        audit.record(tenant, "endpoint:" + e.get("id").asString(), "endpoint.enrol", "endpoint/" + e.get("id").asString(), Map.of("serial", e.get("certSerial").asString()));
        LocalPki pki = LocalPki.get();
        return Map.of("endpointId", e.get("id").asString(), "tenant", tenant, "certificate", LocalPki.toPem(cert),
                "chain", LocalPki.toPem(pki.tenantCa(tenant).cert()) + LocalPki.toPem(pki.root()), "rootCa", LocalPki.toPem(pki.root()));
    }

    /** Internal enrolment for platform-owned probes (harness, demo gateway): key pair generated in-process, CSR-equivalent. */
    public java.security.cert.X509Certificate enrolInternal(String tenant, String id, String name, String type, java.security.PublicKey pub) {
        ObjectNode e = store.get(tenant, "endpoint", id).orElseGet(() -> {
            ObjectNode n = Json.obj().put("id", id).put("name", name).put("type", type).put("site", "platform").put("zone", "CORE")
                    .put("createdAt", Instant.now().toString()).put("createdBy", "system");
            n.putArray("configs");
            return n;
        });
        X509Certificate cert = LocalPki.get().issueEndpoint(tenant, id, pub, CERT_VALIDITY);
        String fp = LocalPki.fingerprint(cert);
        if (e.hasNonNull("certFingerprint")) store.delete(DocumentStore.GLOBAL, "endpoint-fp", e.get("certFingerprint").asString());
        e.put("status", "ACTIVE").put("certFingerprint", fp).put("certSerial", cert.getSerialNumber().toString(16))
                .put("notAfter", cert.getNotAfter().toInstant().toString()).put("enrolledAt", Instant.now().toString());
        store.put(tenant, "endpoint", id, e, "ACTIVE", fp);
        store.put(DocumentStore.GLOBAL, "endpoint-fp", fp, Json.obj().put("tenant", tenant).put("endpoint", id), "ACTIVE", tenant);
        audit.record(tenant, "system", "endpoint.enrol.internal", "endpoint/" + id, Map.of("type", type));
        return cert;
    }

    /** Identity binding at connection time (XT-02): certificate fingerprint → ACTIVE endpoint, tenant from registry. */
    public Optional<Identity> authenticate(X509Certificate cert) {
        String fp = LocalPki.fingerprint(cert);
        Optional<ObjectNode> idx = store.get(DocumentStore.GLOBAL, "endpoint-fp", fp);
        if (idx.isEmpty()) return Optional.empty();
        String tenant = idx.get().get("tenant").asString();
        ObjectNode e = store.get(tenant, "endpoint", idx.get().get("endpoint").asString()).orElse(null);
        if (e == null) return Optional.empty();
        String cn = cert.getSubjectX500Principal().getName();
        if (!cn.contains("CN=" + e.get("id").asString())) return Optional.empty();
        return Optional.of(new Identity(tenant, e.get("id").asString(), e.get("status").asString(), e));
    }

    public record Identity(String tenant, String endpointId, String status, ObjectNode endpoint) {
        public boolean active() { return "ACTIVE".equals(status); }
    }

    public ObjectNode require(String tenant, String id) { return store.require(tenant, "endpoint", id); }

    public List<ObjectNode> list(String tenant) {
        return store.list(tenant, "endpoint").stream().map(e -> { e.remove("enrolTokenSha256"); return e; }).toList();
    }

    public void bindConfig(String tenant, String endpointId, String configId) {
        ObjectNode e = require(tenant, endpointId);
        boolean present = false;
        for (var c : e.withArray("configs")) present |= c.asString().equals(configId);
        if (!present) e.withArray("configs").add(configId);
        store.put(tenant, "endpoint", endpointId, e, e.get("status").asString(), e.path("certFingerprint").asString(null));
    }

    public void unbindConfig(String tenant, String configId) {
        for (ObjectNode e : store.list(tenant, "endpoint")) {
            var arr = e.withArray("configs");
            for (int i = arr.size() - 1; i >= 0; i--) if (arr.get(i).asString().equals(configId)) arr.remove(i);
            store.put(tenant, "endpoint", e.get("id").asString(), e, e.get("status").asString(), e.path("certFingerprint").asString(null));
        }
    }

    public void touch(String tenant, String id, String remote) {
        store.get(tenant, "endpoint", id).ifPresent(e -> {
            e.put("lastSeen", Instant.now().toString()).put("lastAddress", remote).put("messages", e.path("messages").asLong(0) + 1);
            store.put(tenant, "endpoint", id, e, e.get("status").asString(), e.path("certFingerprint").asString(null));
        });
    }

    public ObjectNode setStatus(String tenant, String id, String status, String reason, String actor) {
        ObjectNode e = require(tenant, id);
        String old = e.get("status").asString();
        e.put("status", status).put("statusReason", reason).put("statusChangedAt", Instant.now().toString()).put("statusChangedBy", actor);
        // the fingerprint index is kept for revoked endpoints so later connection attempts are attributed to the tenant's SOC
        store.put(tenant, "endpoint", id, e, status, e.path("certFingerprint").asString(null));
        audit.record(tenant, actor, "endpoint.status", "endpoint/" + id, Map.of("from", old, "to", status, "reason", reason == null ? "" : reason));
        if ("REVOKED".equals(status)) events.publish(tenant, "identity.revoked", Json.obj().put("endpoint", id));
        if ("ISOLATED".equals(status)) events.publish(tenant, "identity.isolated", Json.obj().put("endpoint", id));
        return e;
    }

    /** SC-14: certificates expiring within {@code days}. */
    public List<ObjectNode> expiring(String tenant, int days) {
        Instant limit = Instant.now().plus(Duration.ofDays(days));
        return list(tenant).stream().filter(e -> e.hasNonNull("notAfter") && "ACTIVE".equals(e.get("status").asString())
                && Instant.parse(e.get("notAfter").asString()).isBefore(limit)).toList();
    }
}
