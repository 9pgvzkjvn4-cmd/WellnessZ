package com.wellnessz.e31.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.secrets.CryptoException;
import com.wellnessz.secrets.Hkdf;
import com.wellnessz.secrets.MasterKey;

/**
 * Raw archive (E31-HLR-04, contract section 4.5): every inbound body and attachment is stored before parsing, AES-256-GCM
 * encrypted with a per-object key derived (HKDF-SHA256) from the master key, with its SHA-256 over the plaintext. Large
 * attachments are streamed (digest + encryption on the fly into a ciphertext-only temporary file), never held in memory as
 * a whole. DB-backed ({@code deviceint.raw_object}); an S3-compatible implementation is a later option.
 */
@Component("e31RawPayloadStore")
public class RawPayloadStore {

    public static final String UNFILED = "DEVICE_RAW_UNFILED";
    public static final String PAYLOAD = "DEVICE_RAW_PAYLOAD";
    private static final int IV = 12;
    private static final int TAG_BITS = 128;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final byte[] masterKey;
    private final SecureRandom random = new SecureRandom();

    public record ArchiveRef(UUID archiveId, String sha256, long sizeBytes) {
    }

    public RawPayloadStore(JdbcTemplate jdbc, Clock clock, @Value("${WZ_MASTER_KEY:}") String inlineKey,
                           @Value("${WZ_MASTER_KEY_FILE:}") String keyFile) {
        this.jdbc = jdbc;
        this.clock = clock;
        byte[] key = MasterKey.load(name -> MasterKey.ENV_KEY.equals(name) ? inlineKey : keyFile);
        if (key == null) {
            throw new CryptoException("Master key is required for the device raw archive", null);
        }
        this.masterKey = key;
    }

    SecretKeySpec key(UUID id) {
        return new SecretKeySpec(Hkdf.derive(masterKey, "wz.deviceint.raw.v1:" + id, 32), "AES");
    }

    /** Stores a small body (messages are parsed from memory anyway). */
    public ArchiveRef put(byte[] plain, String kind, String mediaType, String tenant, String endpointId) {
        UUID id = UUID.randomUUID();
        String sha = HexFormat.of().formatHex(sha256(plain));
        byte[] cipher = encrypt(id, plain);
        insert(id, kind, mediaType, sha, plain.length, cipher, null, tenant, endpointId);
        return new ArchiveRef(id, sha, plain.length);
    }

    /**
     * Streams an attachment of at most {@code maxBytes}: SHA-256 and AES-GCM are computed on the fly; only ciphertext reaches
     * the temporary file. 413 {@code payload_too_large} beyond the limit.
     */
    public ArchiveRef putStream(InputStream in, long maxBytes, String kind, String mediaType, String tenant, String endpointId) throws IOException {
        UUID id = UUID.randomUUID();
        Path tmp = Files.createTempFile("wz-raw-", ".bin");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            byte[] iv = new byte[IV];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(id), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try (OutputStream file = Files.newOutputStream(tmp)) {
                file.write(iv);
                try (CipherOutputStream out = new CipherOutputStream(file, c)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        size += n;
                        if (size > maxBytes) {
                            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large", "The payload exceeds the maximum size");
                        }
                        digest.update(buf, 0, n);
                        out.write(buf, 0, n);
                    }
                }
            }
            String sha = HexFormat.of().formatHex(digest.digest());
            long cipherLength = Files.size(tmp);
            long plainSize = size;
            try (InputStream cipherIn = Files.newInputStream(tmp)) {
                jdbc.update("""
                        insert into deviceint.raw_object (id, tenant_code, kind, media_type, sha256, size_bytes, content, endpoint_id,
                            retention_class, purge_after, referenced, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?)""", ps -> {
                    ps.setObject(1, id);
                    ps.setString(2, tenant);
                    ps.setString(3, kind);
                    ps.setString(4, mediaType);
                    ps.setString(5, sha);
                    ps.setLong(6, plainSize);
                    ps.setBinaryStream(7, cipherIn, cipherLength);
                    ps.setString(8, endpointId);
                    ps.setString(9, UNFILED);
                    ps.setDate(10, Date.valueOf(purgeAfterUnfiled()));
                    ps.setTimestamp(11, Timestamp.from(clock.instant()));
                });
            }
            return new ArchiveRef(id, sha, plainSize);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Archive encryption unavailable", e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void insert(UUID id, String kind, String mediaType, String sha, long size, byte[] cipher, Boolean referenced, String tenant,
                        String endpointId) {
        jdbc.update("""
                insert into deviceint.raw_object (id, tenant_code, kind, media_type, sha256, size_bytes, content, endpoint_id, retention_class,
                    purge_after, referenced, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, tenant, kind, mediaType, sha, size, cipher, endpointId, UNFILED, Date.valueOf(purgeAfterUnfiled()),
                referenced == null || referenced, Timestamp.from(clock.instant()));
    }

    LocalDate purgeAfterUnfiled() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusYears(1);
    }

    public byte[] get(UUID id) {
        byte[] cipher = jdbc.query("select content from deviceint.raw_object where id = ?", rs -> rs.next() ? rs.getBytes(1) : null, id);
        if (cipher == null) {
            throw ApiException.notFound("Archive object");
        }
        if (cipher.length == 0) {
            throw new ApiException(HttpStatus.GONE, "archive_purged", "The raw payload was purged under its retention class");
        }
        return decrypt(id, cipher);
    }

    public record ObjectInfo(UUID id, String kind, String mediaType, String sha256, long sizeBytes, String tenantCode, boolean referenced,
                             Instant createdAt) {
    }

    public java.util.Optional<ObjectInfo> info(UUID id) {
        return jdbc.query("select id, kind, media_type, sha256, size_bytes, tenant_code, referenced, created_at from deviceint.raw_object where id = ?",
                rs -> rs.next() ? java.util.Optional.of(new ObjectInfo(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getString(6), rs.getBoolean(7), rs.getTimestamp(8).toInstant()))
                        : java.util.Optional.<ObjectInfo>empty(), id);
    }

    /** Recomputes the SHA-256 of the decrypted object (E31-F04 acceptance, {@code GET /device-engine/archive/{id}/verify}). */
    public Verification verify(UUID id) {
        ObjectInfo info = info(id).orElseThrow(() -> ApiException.notFound("Archive object"));
        String computed = HexFormat.of().formatHex(sha256(get(id)));
        return new Verification(info.sha256(), computed, info.sha256().equals(computed));
    }

    public record Verification(String sha256Stored, String sha256Computed, boolean intact) {
    }

    /** Removes an unreferenced upload (hash mismatch, 24-hour purge). */
    public int deleteUnreferenced(UUID id) {
        return jdbc.update("delete from deviceint.raw_object where id = ? and referenced = false and kind <> 'MESSAGE'", id);
    }

    /** Unreferenced attachment uploads older than the given time (purged after 24 h, contract section 7). */
    public int purgeUnreferenced(Instant before) {
        return jdbc.update("""
                delete from deviceint.raw_object r where r.referenced = false and r.kind <> 'MESSAGE' and r.created_at < ?
                   and not exists (select 1 from deviceint.message m where m.raw_object_id = r.id)""", Timestamp.from(before));
    }

    /** Retention: empties raw payloads past their purge date (the SHA-256 stays as evidence). */
    public int purgeExpired(LocalDate today) {
        return jdbc.update("update deviceint.raw_object set content = '\\x'::bytea where purge_after is not null and purge_after < ? and length(content) > 0",
                Date.valueOf(today));
    }

    /** Marks an attachment as referenced by an observation (otherwise purged after 24 h). */
    public void reference(UUID id) {
        jdbc.update("update deviceint.raw_object set referenced = true where id = ?", id);
    }

    /** Filed observation: the raw payload follows the OH record (40 years from the last encounter – purge date set by retention). */
    public void retainAsPayload(UUID id) {
        jdbc.update("update deviceint.raw_object set retention_class = ?, purge_after = null, referenced = true where id = ?", PAYLOAD, id);
    }

    public void retainAsUnfiled(UUID id) {
        jdbc.update("update deviceint.raw_object set retention_class = ?, purge_after = ? where id = ? and retention_class <> ?", UNFILED,
                Date.valueOf(purgeAfterUnfiled()), id, PAYLOAD);
    }

    byte[] encrypt(UUID id, byte[] plain) {
        try {
            byte[] iv = new byte[IV];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(id), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[IV + ct.length];
            System.arraycopy(iv, 0, out, 0, IV);
            System.arraycopy(ct, 0, out, IV, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Archive encryption failed", e);
        }
    }

    byte[] decrypt(UUID id, byte[] stored) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(id), new GCMParameterSpec(TAG_BITS, stored, 0, IV));
            c.updateAAD(id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return c.doFinal(stored, IV, stored.length - IV);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Archive object failed authentication", e);
        }
    }

    static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
