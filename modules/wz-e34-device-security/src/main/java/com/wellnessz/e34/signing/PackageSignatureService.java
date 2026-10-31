package com.wellnessz.e34.signing;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DevicePackageSignatures;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e34.events.E34Events;
import com.wellnessz.e34.events.SecurityEventService;
import com.wellnessz.e34.identity.EndpointRepository;
import com.wellnessz.e34.support.E34Settings;
import com.wellnessz.e34.support.TokenHasher;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.secrets.FieldEncryptor;
import com.wellnessz.security.audit.AuditService;

/**
 * Driver-package signing (E31-HLR-03, E34-HLR-06; contract §15.5) with JDK signatures only: Ed25519 (default) or
 * SHA256withECDSA on P-256. Key registry with one ACTIVE key, RETIRED keys still verify, REVOKED keys never verify. Private keys
 * are stored encrypted with wz-secrets field encryption (context {@code devsec:signing-key:<keyId>}); production replaces this
 * with an HSM / KMS reference.
 */
@Service
public class PackageSignatureService implements DevicePackageSignatures {

    public static final Set<String> ALGORITHMS = Set.of("Ed25519", "SHA256withECDSA");
    private static final Set<String> SIGNER_ROLES = Set.of("ROLE_INTEGRATION_TESTER", "ROLE_DEVICE_APPROVER", "ROLE_SECURITY_OPS");
    private static final SecureRandom RANDOM = new SecureRandom();

    public record KeyView(String keyId, String algorithm, String publicKey, String status, String createdBy, Instant createdAt,
                          Instant retiredAt, Instant revokedAt) {
    }

    private record StoredKey(String keyId, String algorithm, String publicKey, String privateKey, String status) {
    }

    private final JdbcTemplate jdbc;
    private final FieldEncryptor encryptor;
    private final SecurityEventService events;
    private final AuditService audit;
    private final EventBus bus;
    private final E34Settings settings;

    public PackageSignatureService(JdbcTemplate jdbc, FieldEncryptor encryptor, SecurityEventService events, AuditService audit, EventBus bus,
                                   E34Settings settings) {
        this.jdbc = jdbc;
        this.encryptor = encryptor;
        this.events = events;
        this.audit = audit;
        this.bus = bus;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ SPI

    @Override
    public Verification verify(byte[] canonicalManifest, String signatureBase64, String keyId) {
        if (canonicalManifest == null || keyId == null || !keyId.matches("[A-Za-z0-9._\\-]{1,40}")) {
            return invalid(keyId, null, "unknown_key");
        }
        Optional<StoredKey> key = find(keyId);
        if (key.isEmpty()) {
            return invalid(keyId, null, "unknown_key");
        }
        StoredKey k = key.get();
        if ("REVOKED".equals(k.status())) {
            return invalid(keyId, k.algorithm(), "key_revoked");
        }
        if (!ALGORITHMS.contains(k.algorithm())) {
            return invalid(keyId, k.algorithm(), "algorithm_not_allowed");
        }
        try {
            java.security.Signature s = java.security.Signature.getInstance(k.algorithm());
            s.initVerify(publicKey(k));
            s.update(canonicalManifest);
            if (signatureBase64 != null && s.verify(Base64.getDecoder().decode(signatureBase64.strip()))) {
                return new Verification(true, keyId, k.algorithm(), null);
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // falls through to signature_invalid
        }
        return invalid(keyId, k.algorithm(), "signature_invalid");
    }

    @Override
    @Transactional
    public Signature sign(String actor, byte[] canonicalManifest) {
        settings.requireEnabled();
        requireSigner(actor);
        if (canonicalManifest == null || canonicalManifest.length == 0 || canonicalManifest.length > 2_000_000) {
            throw ApiException.badRequest("validation_failed", "Manifest required");
        }
        StoredKey k = activeKey(actor);
        try {
            java.security.Signature s = java.security.Signature.getInstance(k.algorithm());
            s.initSign(privateKey(k));
            s.update(canonicalManifest);
            String sig = Base64.getEncoder().encodeToString(s.sign());
            String sha = TokenHasher.sha256Hex(canonicalManifest);
            audit.record("DEVICE_PACKAGE_SIGN", "DEVICE_DRIVER_PACKAGE", sha, AuditService.Outcome.SUCCESS, k.keyId());
            events.record("PACKAGE_SIGNED", DeviceCodes.Severity.INFO, null, null, actor, null, Map.of("keyId", k.keyId(), "algorithm", k.algorithm()));
            return new Signature(k.keyId(), k.algorithm(), sig);
        } catch (GeneralSecurityException e) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "signing_unavailable", "Signing failed");
        }
    }

    // ------------------------------------------------------------------ key registry (SECURITY_OPS)

    @Transactional(readOnly = true)
    public List<KeyView> keys() {
        settings.requireEnabled();
        return jdbc.query("select key_id, algorithm, public_key, status, created_by, created_at, retired_at, revoked_at from devicesec.signing_key "
                + "order by created_at desc", (rs, i) -> new KeyView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), EndpointRepository.inst(rs, "created_at"), EndpointRepository.inst(rs, "retired_at"),
                EndpointRepository.inst(rs, "revoked_at")));
    }

    /** Rotation: a new ACTIVE key; the previous one becomes RETIRED and keeps verifying existing packages. */
    @Transactional
    public KeyView rotate(String actor, String algorithm) {
        settings.requireEnabled();
        String alg = algorithm == null || algorithm.isBlank() ? "Ed25519" : algorithm;
        if (!ALGORITHMS.contains(alg)) {
            throw ApiException.badRequest("validation_failed", "Algorithm must be Ed25519 or SHA256withECDSA");
        }
        Instant now = settings.now();
        jdbc.queryForList("select key_id from devicesec.signing_key where status = 'ACTIVE' for update", String.class);
        jdbc.update("update devicesec.signing_key set status = 'RETIRED', retired_at = ? where status = 'ACTIVE'", EndpointRepository.ts(now));
        String keyId = createKey(actor, alg, now);
        audit.record("DEVICE_SIGNING_KEY_ROTATE", "DEVICE_SIGNING_KEY", keyId, AuditService.Outcome.SUCCESS, alg);
        events.record("SIGNING_KEY_ROTATED", DeviceCodes.Severity.MEDIUM, null, null, actor, null, Map.of("keyId", keyId, "algorithm", alg));
        return keys().stream().filter(k -> k.keyId().equals(keyId)).findFirst().orElseThrow();
    }

    /** Revocation: the key never verifies again; packages signed with it are revoked by E32 (in-process event). */
    @Transactional
    public KeyView revoke(String actor, String keyId) {
        settings.requireEnabled();
        StoredKey k = find(keyId).orElseThrow(() -> ApiException.notFound("Signing key"));
        if ("REVOKED".equals(k.status())) {
            throw ApiException.conflict("key_revoked", "The key is already revoked");
        }
        Instant now = settings.now();
        jdbc.update("update devicesec.signing_key set status = 'REVOKED', revoked_at = ?, revoked_by = ? where key_id = ?",
                EndpointRepository.ts(now), actor, keyId);
        if ("ACTIVE".equals(k.status())) {
            createKey(actor, "Ed25519", now);
        }
        audit.record("DEVICE_SIGNING_KEY_REVOKE", "DEVICE_SIGNING_KEY", keyId, AuditService.Outcome.SUCCESS, "revoked");
        events.record("SIGNING_KEY_REVOKED", DeviceCodes.Severity.HIGH, null, null, actor, "key_revoked", Map.of("keyId", keyId));
        bus.publish(new E34Events.SigningKeyRevoked(keyId, actor, now));
        return keys().stream().filter(v -> v.keyId().equals(keyId)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ helpers

    private Verification invalid(String keyId, String algorithm, String code) {
        try {
            events.record("PACKAGE_SIGNATURE_INVALID", DeviceCodes.Severity.HIGH, null, null, actorOrSystem(), code,
                    keyId == null ? Map.of() : Map.of("keyId", keyId.length() > 40 ? keyId.substring(0, 40) : keyId));
        } catch (RuntimeException e) {
            // verification result must not depend on the event store
        }
        return new Verification(false, keyId, algorithm, code);
    }

    private StoredKey activeKey(String actor) {
        Optional<StoredKey> active = jdbc.query("select key_id, algorithm, public_key, private_key, status from devicesec.signing_key "
                + "where status = 'ACTIVE'", (rs, i) -> new StoredKey(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5))).stream().findFirst();
        if (active.isPresent()) {
            return active.get();
        }
        try {
            createKey(actor, "Ed25519", settings.now());
        } catch (DuplicateKeyException e) {
            // created concurrently
        }
        return jdbc.query("select key_id, algorithm, public_key, private_key, status from devicesec.signing_key where status = 'ACTIVE'",
                (rs, i) -> new StoredKey(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))).get(0);
    }

    private String createKey(String actor, String algorithm, Instant now) {
        try {
            KeyPair pair;
            if ("Ed25519".equals(algorithm)) {
                pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            } else {
                KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
                g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
                pair = g.generateKeyPair();
            }
            byte[] rnd = new byte[3];
            RANDOM.nextBytes(rnd);
            String keyId = "PKS-" + DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(now) + "-" + HexFormat.of().formatHex(rnd);
            String pub = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
            String priv = encryptor.encrypt(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()), "devsec:signing-key:" + keyId);
            jdbc.update("insert into devicesec.signing_key (key_id, algorithm, public_key, private_key, status, created_by, created_at) "
                    + "values (?,?,?,?,'ACTIVE',?,?)", keyId, algorithm, pub, priv, actor, EndpointRepository.ts(now));
            return keyId;
        } catch (GeneralSecurityException e) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "signing_unavailable", "Key generation failed");
        }
    }

    private Optional<StoredKey> find(String keyId) {
        return jdbc.query("select key_id, algorithm, public_key, private_key, status from devicesec.signing_key where key_id = ?",
                (rs, i) -> new StoredKey(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), keyId)
                .stream().findFirst();
    }

    private static PublicKey publicKey(StoredKey k) throws GeneralSecurityException {
        return KeyFactory.getInstance(family(k.algorithm())).generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(k.publicKey())));
    }

    private PrivateKey privateKey(StoredKey k) throws GeneralSecurityException {
        String b64 = encryptor.decrypt(k.privateKey(), "devsec:signing-key:" + k.keyId());
        return KeyFactory.getInstance(family(k.algorithm())).generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }

    private static String family(String algorithm) {
        return "Ed25519".equals(algorithm) ? "Ed25519" : "EC";
    }

    /** People need INTEGRATION_TESTER, DEVICE_APPROVER or SECURITY_OPS; unauthenticated callers only as a {@code system} actor. */
    private static void requireSigner(String actor) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
        if (!authenticated) {
            if (actor != null && actor.startsWith("system")) {
                return;
            }
            throw ApiException.forbidden("Package signing requires an authorised signer");
        }
        boolean allowed = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(SIGNER_ROLES::contains);
        if (!allowed) {
            throw ApiException.forbidden("Package signing requires INTEGRATION_TESTER or DEVICE_APPROVER");
        }
    }

    private static String actorOrSystem() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}
