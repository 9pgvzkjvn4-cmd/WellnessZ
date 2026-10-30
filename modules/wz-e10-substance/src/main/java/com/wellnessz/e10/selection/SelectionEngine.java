package com.wellnessz.e10.selection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Deterministic, auditable random selection (E10-BL-001). A 256-bit seed from {@link SecureRandom} drives a partial
 * Fisher–Yates shuffle of the sorted pool; random integers come from HMAC-SHA256(seed, counter) with rejection sampling, so
 * the draw is unbiased and can be reproduced exactly from the stored seed and pool snapshot. The SHA-256 commitment of the seed
 * and the hash of the pool snapshot are recorded at creation time.
 */
public final class SelectionEngine {

    private static final HexFormat HEX = HexFormat.of();
    private static final BigDecimal TWELVE_HUNDRED = BigDecimal.valueOf(1200);

    private SelectionEngine() {
    }

    /** Monthly sample size {@code n = ceil(poolSize × rate% / 12)}, never more than the pool. */
    public static int sampleSize(int poolSize, BigDecimal ratePct) {
        if (poolSize <= 0 || ratePct == null || ratePct.signum() <= 0) {
            return 0;
        }
        int n = BigDecimal.valueOf(poolSize).multiply(ratePct).divide(TWELVE_HUNDRED, 0, RoundingMode.CEILING).intValueExact();
        return Math.min(n, poolSize);
    }

    /** Canonical pool snapshot: distinct, sorted employee ids. */
    public static List<String> canonical(Collection<String> pool) {
        return List.copyOf(new TreeSet<>(pool));
    }

    public static String poolHash(List<String> canonicalPool) {
        return sha256Hex(String.join("\n", canonicalPool).getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] newSeed(SecureRandom random) {
        byte[] seed = new byte[32];
        random.nextBytes(seed);
        return seed;
    }

    public static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    public static byte[] unhex(String hex) {
        return HEX.parseHex(hex);
    }

    public static String commitment(byte[] seed) {
        return sha256Hex(seed);
    }

    /** Draws {@code n} employees from the canonical pool; the result is in draw order. */
    public static List<String> select(List<String> canonicalPool, byte[] seed, int n) {
        List<String> a = new ArrayList<>(canonicalPool);
        int count = Math.min(Math.max(n, 0), a.size());
        DeterministicStream rng = new DeterministicStream(seed);
        for (int i = 0; i < count; i++) {
            int j = i + rng.nextInt(a.size() - i);
            String t = a.get(i);
            a.set(i, a.get(j));
            a.set(j, t);
        }
        return List.copyOf(a.subList(0, count));
    }

    static String sha256Hex(byte[] data) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** HMAC-SHA256 counter-mode stream of unsigned 63-bit values. */
    static final class DeterministicStream {
        private final Mac mac;
        private long counter;
        private ByteBuffer block = ByteBuffer.allocate(0);

        DeterministicStream(byte[] seed) {
            try {
                mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(seed, "HmacSHA256"));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("HmacSHA256 not available", e);
            }
        }

        long nextLong() {
            if (block.remaining() < Long.BYTES) {
                block = ByteBuffer.wrap(mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter++).array()));
            }
            return block.getLong() >>> 1;
        }

        /** Uniform integer in [0, bound) by rejection sampling. */
        int nextInt(int bound) {
            if (bound <= 0) {
                throw new IllegalArgumentException("bound must be positive");
            }
            long limit = Long.MAX_VALUE - (Long.MAX_VALUE % bound);
            long v;
            do {
                v = nextLong();
            } while (v >= limit);
            return (int) (v % bound);
        }
    }
}
