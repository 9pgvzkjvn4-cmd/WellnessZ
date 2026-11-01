package io.intimed.engine.driver;

import io.intimed.common.ApiException;
import io.intimed.common.Json;
import io.intimed.security.audit.AuditService;
import io.intimed.security.secrets.SecretScanner;
import io.intimed.store.DocumentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Driver registry (IM-E01-F03 / IM-E01-BL-011): bundled certified packages and tenant-published candidates. Every
 * package must carry a valid Ed25519 signature from a trusted publisher key and pass the secret scanner, otherwise it is
 * refused (SC-05, SC-06, threat "malicious driver package"). Channels: CERTIFIED (released with the product or
 * certified by the harness) and CANDIDATE (published by a tenant's driver author, usable in DEV/LOCAL only until a
 * passing harness run promotes it).
 */
@Service
public class DriverRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(DriverRegistry.class);
    private final Map<String, DriverPackage> bundled = new LinkedHashMap<>();
    private final Map<String, PublicKey> trusted = new LinkedHashMap<>();
    private final List<Map<String, Object>> rejected = new ArrayList<>();
    private final DocumentStore store;
    private final AuditService audit;

    public DriverRegistry(DocumentStore store, AuditService audit) throws IOException {
        this.store = store;
        this.audit = audit;
        var resolver = new PathMatchingResourcePatternResolver();
        for (Resource r : resolver.getResources("classpath*:drivers/trust/*.pub")) {
            trusted.put(r.getFilename().replace(".pub", ""), Signatures.publicKey(r.getContentAsString(StandardCharsets.UTF_8)));
        }
        for (Resource r : resolver.getResources("classpath*:drivers/*.json")) {
            JsonNode pkg = Json.parse(r.getContentAsString(StandardCharsets.UTF_8));
            Resource sig = r.createRelative(r.getFilename() + ".sig");
            String publisher = sig.exists() ? verifyAny(pkg, sig.getContentAsString(StandardCharsets.UTF_8)) : null;
            if (publisher == null || !SecretScanner.scan(Json.write(pkg)).isEmpty()) {
                rejected.add(Map.of("file", r.getFilename(), "reason", publisher == null ? "signature missing or invalid" : "secret found"));
                LOG.warn("Driver package {} REFUSED: signature missing/invalid or secret found", r.getFilename());
                continue;
            }
            DriverPackage d = new DriverPackage(pkg, "CERTIFIED", "VALID", publisher);
            bundled.put(d.ref(), d);
        }
        LOG.info("Driver registry: {} certified packages, {} trusted publishers, {} refused", bundled.size(), trusted.size(), rejected.size());
    }

    private String verifyAny(JsonNode pkg, String sig) {
        for (var e : trusted.entrySet()) if (Signatures.verify(e.getValue(), pkg, sig)) return e.getKey();
        return null;
    }

    private String verifyAny(String tenant, JsonNode pkg, String sig) {
        String p = verifyAny(pkg, sig);
        if (p != null) return p;
        for (ObjectNode k : store.list(tenant, "publisher-key")) {
            if (Signatures.verify(Signatures.publicKey(k.get("publicKey").asString()), pkg, sig)) return k.get("name").asString();
        }
        return null;
    }

    public List<DriverPackage> all(String tenant) {
        List<DriverPackage> out = new ArrayList<>(bundled.values());
        for (ObjectNode d : store.list(tenant, "driver")) out.add(new DriverPackage(d.get("package"), d.get("channel").asString(), "VALID", d.get("publisher").asString()));
        return out;
    }

    public Optional<DriverPackage> find(String tenant, String ref) {
        if (bundled.containsKey(ref)) return Optional.of(bundled.get(ref));
        return store.get(tenant, "driver", ref).map(d -> new DriverPackage(d.get("package"), d.get("channel").asString(), "VALID", d.get("publisher").asString()));
    }

    public DriverPackage require(String tenant, String ref) { return find(tenant, ref).orElseThrow(() -> ApiException.notFound("driver " + ref)); }

    public List<DriverPackage> forModel(String tenant, String modelId) {
        return all(tenant).stream().filter(d -> d.models().contains(modelId)).toList();
    }

    public List<Map<String, Object>> rejected() { return rejected; }

    public Map<String, PublicKey> trustedPublishers() { return trusted; }

    public void trustPublisher(String tenant, String name, String publicKeyB64, String actor) {
        Signatures.publicKey(publicKeyB64);
        store.put(tenant, "publisher-key", name, Json.obj().put("name", name).put("publicKey", publicKeyB64).put("addedBy", actor), "ACTIVE", null);
        audit.record(tenant, actor, "driver.publisher.trust", "publisher/" + name, null);
    }

    /** Tenant driver publication (SDK {@code publish}): signature + secret scan + structural validation → CANDIDATE. */
    public DriverPackage publish(String tenant, JsonNode pkg, String sig, String actor) {
        List<String> problems = DriverValidator.validate(pkg);
        if (!problems.isEmpty()) throw ApiException.unprocessable("driver-invalid", String.join("; ", problems));
        if (!SecretScanner.scan(Json.write(pkg)).isEmpty()) throw ApiException.unprocessable("driver-secret", "driver package contains a secret – use secret:// references");
        String publisher = verifyAny(tenant, pkg, sig);
        if (publisher == null) {
            audit.record(tenant, actor, "driver.publish.refused", "driver/" + pkg.at("/manifest/packageId").asString(), "{\"reason\":\"signature\"}");
            throw ApiException.forbidden("driver-signature", "driver signature invalid or publisher not trusted");
        }
        DriverPackage d = new DriverPackage(pkg, "CANDIDATE", "VALID", publisher);
        if (bundled.containsKey(d.ref())) throw ApiException.conflict("driver-exists", "a certified package with this version exists");
        store.put(tenant, "driver", d.ref(), Json.obj().put("channel", "CANDIDATE").put("publisher", publisher).put("signature", sig)
                .put("publishedAt", Instant.now().toString()).put("publishedBy", actor).set("package", pkg), "CANDIDATE", d.packageId());
        audit.record(tenant, actor, "driver.publish", "driver/" + d.ref(), Map.of("publisher", publisher));
        return d;
    }

    public void certify(String tenant, String ref, String runId, String actor) {
        store.get(tenant, "driver", ref).ifPresent(d -> {
            d.put("channel", "CERTIFIED").put("certifiedByRun", runId);
            store.put(tenant, "driver", ref, d, "CERTIFIED", d.get("package").at("/manifest/packageId").asString());
            audit.record(tenant, actor, "driver.certify", "driver/" + ref, Map.of("run", runId));
        });
    }
}
