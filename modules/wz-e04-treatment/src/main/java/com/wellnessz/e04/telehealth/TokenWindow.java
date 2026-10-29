package com.wellnessz.e04.telehealth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Telehealth participant tokens: single-use, random 256-bit, stored only as SHA-256, valid from 15 minutes before the
 * scheduled start until the scheduled end.
 */
public final class TokenWindow {

    public static final Duration EARLY_JOIN = Duration.ofMinutes(15);
    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenWindow() {
    }

    public static Instant opensAt(Instant scheduledAt) {
        return scheduledAt.minus(EARLY_JOIN);
    }

    public static Instant closesAt(Instant scheduledAt, int durationMinutes) {
        return scheduledAt.plus(Duration.ofMinutes(durationMinutes));
    }

    public static boolean isOpen(Instant now, Instant scheduledAt, int durationMinutes) {
        return !now.isBefore(opensAt(scheduledAt)) && now.isBefore(closesAt(scheduledAt, durationMinutes));
    }

    public static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
