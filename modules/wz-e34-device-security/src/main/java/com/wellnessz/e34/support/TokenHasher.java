package com.wellnessz.e34.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.wellnessz.secrets.Hkdf;
import com.wellnessz.secrets.MasterKey;

/**
 * Per-endpoint tokens and one-time enrolment tokens (contract §15.1 / §15.2): 256-bit random values, shown once, stored
 * only as {@code HMAC-SHA256(pepper, token)} (base64url) and compared in constant time. The pepper is
 * {@code wellnessz.device.security.token-pepper} (an {@code ENC(...)} or {@code file:} secret resolved by wz-secrets) or, when
 * not configured, derived from the platform master key with HKDF – it never lives in the database, so a database dump alone
 * does not allow offline guessing.
 */
@Component
public class TokenHasher {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] pepper;

    @Autowired
    public TokenHasher(@Value("${wellnessz.device.security.token-pepper:${WZ_DEVICE_TOKEN_PEPPER:}}") String configuredPepper,
                       @Value("${WZ_MASTER_KEY:}") String inlineKey,
                       @Value("${WZ_MASTER_KEY_FILE:}") String keyFile) {
        this(resolvePepper(configuredPepper, inlineKey, keyFile));
    }

    public TokenHasher(byte[] pepper) {
        if (pepper == null || pepper.length < 16) {
            throw new IllegalStateException("Device token pepper is missing or too short");
        }
        this.pepper = pepper.clone();
    }

    private static byte[] resolvePepper(String configured, String inlineKey, String keyFile) {
        if (configured != null && !configured.isBlank()) {
            return configured.strip().getBytes(StandardCharsets.UTF_8);
        }
        byte[] master = MasterKey.load(name -> MasterKey.ENV_KEY.equals(name) ? inlineKey : keyFile);
        if (master == null) {
            throw new IllegalStateException("Device token pepper requires wellnessz.device.security.token-pepper or the master key");
        }
        return Hkdf.derive(master, "wellnessz/devicesec/token-pepper/v1", 32);
    }

    /** A new 256-bit token, base64url without padding (43 characters). */
    public String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public String hash(String token) {
        if (token == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Token hashing unavailable", e);
        }
    }

    /** Constant-time comparison of a presented token against a stored hash. */
    public boolean matches(String token, String storedHash) {
        if (token == null || storedHash == null) {
            return false;
        }
        return constantTimeEquals(hash(token), storedHash);
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Lowercase hex SHA-256. */
    public static String sha256Hex(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256Hex(String data) {
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }
}
