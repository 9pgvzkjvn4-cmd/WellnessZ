package io.intimed.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

public final class Hashing {
    public static final SecureRandom RANDOM = new SecureRandom();
    private Hashing() { }

    public static String sha256(String s) { return sha256(s.getBytes(StandardCharsets.UTF_8)); }

    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Constant-time comparison for MACs and tokens. */
    public static boolean equalsConstantTime(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] randomBytes(int n) { byte[] b = new byte[n]; RANDOM.nextBytes(b); return b; }

    public static String randomToken(int bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(bytes)); }

    public static String id(String prefix) { return prefix + "-" + UUID.randomUUID().toString().substring(0, 13).replace("-", ""); }

    /** Deterministic name-based id (UUID v5-like, SHA-256 truncated) – used for idempotent observation ids. */
    public static String stableId(String prefix, String material) { return prefix + "-" + sha256(material).substring(0, 20); }
}
