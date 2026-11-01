package com.wellnessz.e31.support;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.TreeSet;
import java.util.UUID;

/** Deterministic identifiers and hashes of the engine (contract section 8: replay never duplicates). */
public final class Ids {

    /** Namespace of the engine's name-based (version 5) UUIDs. */
    public static final UUID NAMESPACE = UUID.fromString("6f3b2c1e-9d1a-5e2f-8b31-31e31e31e310");

    private Ids() {
    }

    /** RFC 9562 version-5 UUID (SHA-1, name based) in the engine namespace. */
    public static UUID uuidV5(String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            ByteBuffer ns = ByteBuffer.allocate(16);
            ns.putLong(NAMESPACE.getMostSignificantBits());
            ns.putLong(NAMESPACE.getLeastSignificantBits());
            sha1.update(ns.array());
            byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer bb = ByteBuffer.wrap(hash, 0, 16);
            return new UUID(bb.getLong(), bb.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    /**
     * Canonical observation id = UUIDv5(messageId + record index + binding key set). The binding key set is sorted, so the
     * same record mapped by the same binding always yields the same id – every sink is idempotent on it.
     */
    public static UUID observationId(UUID messageId, int recordIndex, Collection<String> bindingKeys) {
        return uuidV5(messageId + "|" + recordIndex + "|" + String.join(",", new TreeSet<>(bindingKeys)));
    }

    /** DiagnosticReport id of a composite observation. */
    public static UUID reportId(UUID observationId) {
        return uuidV5("report|" + observationId);
    }

    public static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256(bytes));
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Constant-time comparison of two hex strings (case-insensitive). */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                b.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }
}
