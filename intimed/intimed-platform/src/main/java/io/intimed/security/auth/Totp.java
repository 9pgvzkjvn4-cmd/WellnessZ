package io.intimed.security.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.time.Instant;

/** RFC 6238 TOTP (SHA-1, 6 digits, 30 s) with ±1 step tolerance; Base32 secrets for authenticator apps. */
public final class Totp {
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() { }

    public static String code(String base32Secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32Decode(base32Secret), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0xf;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String now(String secret) { return code(secret, Instant.now().getEpochSecond() / 30); }

    public static boolean verify(String secret, String code) {
        if (code == null || !code.matches("\\d{6}")) return false;
        long s = Instant.now().getEpochSecond() / 30;
        for (long d = -1; d <= 1; d++) if (io.intimed.common.Hashing.equalsConstantTime(code(secret, s + d), code)) return true;
        return false;
    }

    public static String newSecret() {
        byte[] b = io.intimed.common.Hashing.randomBytes(20);
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte x : b) {
            buffer = (buffer << 8) | (x & 0xff); bits += 8;
            while (bits >= 5) { sb.append(B32.charAt((buffer >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) sb.append(B32.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] base32Decode(String s) {
        s = s.replace("=", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : s.toCharArray()) {
            int v = B32.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("bad base32");
            buffer = (buffer << 5) | v; bits += 5;
            if (bits >= 8) { out.put((byte) (buffer >> (bits - 8))); bits -= 8; }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }
}
