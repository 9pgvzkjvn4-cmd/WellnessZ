package io.intimed.security.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.common.Paths2;
import io.intimed.security.audit.AuditService;
import io.intimed.security.crypto.Aead;
import io.intimed.security.crypto.MasterKey;
import io.intimed.store.DocumentStore;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OAuth2 client-credentials token service for machine clients (SC-11, IM-E07-HLR-01): ES256 JWTs, 15-minute lifetime,
 * tenant claim, SMART-style scopes ({@code system/Observation.read}, {@code system/Device.read}, {@code harness.run},
 * {@code console.read}, {@code ingest.write}). Client secrets are shown once and stored as SHA-256 digests.
 */
@Service
public class TokenService {
    public static final String ISSUER = "https://localhost:9443/intimed";
    public static final Set<String> SCOPES = Set.of("system/Observation.read", "system/Device.read", "system/DiagnosticReport.read",
            "system/Subscription.write", "system/Observation.write", "system/Patient.write", "harness.run", "console.read", "catalogue.read", "templates.read", "events.read");
    private final DocumentStore store;
    private final AuditService audit;
    private final ECPrivateKey priv;
    private final ECPublicKey pub;

    public TokenService(DocumentStore store, AuditService audit) {
        this.store = store;
        this.audit = audit;
        KeyPair kp = loadOrCreate();
        this.priv = (ECPrivateKey) kp.getPrivate();
        this.pub = (ECPublicKey) kp.getPublic();
    }

    private static KeyPair loadOrCreate() {
        Path dir = Paths2.dir("keys");
        Path pk = dir.resolve("jwt-signing.key.enc");
        Path pb = dir.resolve("jwt-signing.pub");
        try {
            KeyFactory kf = KeyFactory.getInstance("EC");
            if (Files.exists(pk)) {
                byte[] der = Aead.decrypt(MasterKey.aes("jwt"), Files.readString(pk).trim(), "jwt");
                return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(java.util.Base64.getDecoder().decode(Files.readString(pb).trim()))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(der)));
            }
            KeyPair kp = io.intimed.security.pki.LocalPki.ecKeyPair();
            Paths2.writeSecret(pk, Aead.encrypt(MasterKey.aes("jwt"), kp.getPrivate().getEncoded(), "jwt").getBytes(StandardCharsets.UTF_8));
            Files.writeString(pb, java.util.Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
            return kp;
        } catch (Exception e) { throw new IllegalStateException("JWT key unavailable", e); }
    }

    public JwtDecoder decoder() {
        var jwk = new com.nimbusds.jose.jwk.ECKey.Builder(com.nimbusds.jose.jwk.Curve.P_256, pub).keyID("intimed-local-1").build();
        var proc = new com.nimbusds.jwt.proc.DefaultJWTProcessor<com.nimbusds.jose.proc.SecurityContext>();
        proc.setJWSKeySelector(new com.nimbusds.jose.proc.JWSVerificationKeySelector<>(JWSAlgorithm.ES256,
                new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(jwk))));
        NimbusJwtDecoder d = new NimbusJwtDecoder(proc);
        d.setJwtValidator(org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer(ISSUER));
        return d;
    }

    public ECPublicKey publicKey() { return pub; }

    public record ClientCreated(String clientId, String clientSecret, List<String> scopes) { }

    public ClientCreated createClient(String tenant, String name, List<String> scopes, String actor) {
        for (String s : scopes) if (!SCOPES.contains(s)) throw ApiException.bad("invalid-scope", "unknown scope " + s);
        String id = "cli-" + Hashing.randomToken(8).toLowerCase().replaceAll("[^a-z0-9]", "x");
        String secret = Hashing.randomToken(32);
        ObjectNode doc = Json.obj().put("clientId", id).put("name", name).put("tenant", tenant).put("secretSha256", Hashing.sha256(secret))
                .put("createdAt", Instant.now().toString()).put("createdBy", actor).put("status", "ACTIVE");
        doc.set("scopes", Json.tree(scopes));
        store.put(DocumentStore.GLOBAL, "api-client", id, doc, "ACTIVE", tenant);
        audit.record(tenant, actor, "api-client.create", "api-client/" + id, Map.of("scopes", scopes));
        return new ClientCreated(id, secret, scopes);
    }

    public List<ObjectNode> clients(String tenant) {
        return store.listByRef(DocumentStore.GLOBAL, "api-client", tenant).stream().map(d -> { d.remove("secretSha256"); return d; }).toList();
    }

    public void revokeClient(String tenant, String clientId, String actor) {
        ObjectNode d = store.require(DocumentStore.GLOBAL, "api-client", clientId);
        if (!tenant.equals(d.get("tenant").asString())) throw ApiException.notFound("api-client");
        d.put("status", "REVOKED");
        store.put(DocumentStore.GLOBAL, "api-client", clientId, d, "REVOKED", tenant);
        audit.record(tenant, actor, "api-client.revoke", "api-client/" + clientId, null);
    }

    /** client_credentials grant. Requested scopes are intersected with the client's grant (least privilege). */
    public Map<String, Object> token(String clientId, String secret, String requestedScope) {
        ObjectNode d = clientId == null ? null : store.get(DocumentStore.GLOBAL, "api-client", clientId).orElse(null);
        if (d == null || !"ACTIVE".equals(d.get("status").asString())
                || !Hashing.equalsConstantTime(Hashing.sha256(secret == null ? "" : secret), d.get("secretSha256").asString())) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "invalid_client", "client authentication failed");
        }
        Set<String> granted = new LinkedHashSet<>();
        d.get("scopes").forEach(s -> granted.add(s.asString()));
        if (requestedScope != null && !requestedScope.isBlank()) granted.retainAll(Arrays.asList(requestedScope.split(" ")));
        String tenant = d.get("tenant").asString();
        Instant now = Instant.now();
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(clientId).audience("intimed-api")
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(900))).jwtID(Hashing.randomToken(12))
                    .claim("tenant", tenant).claim("scope", String.join(" ", granted)).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("intimed-local-1").build(), claims);
            jwt.sign(new ECDSASigner(priv));
            audit.record(tenant, "client:" + clientId, "oauth.token", "api-client/" + clientId, Map.of("scope", String.join(" ", granted)));
            return Map.of("access_token", jwt.serialize(), "token_type", "Bearer", "expires_in", 900, "scope", String.join(" ", granted));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
