package io.intimed.security.crypto;

import io.intimed.common.Hashing;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Envelope encryption with one data-encryption key per tenant (SC-10, SC-18, architecture §10.3). The DEK is stored
 * wrapped by the master KEK; the AAD binds every ciphertext to its tenant, so a row copied into another tenant cannot
 * be decrypted there. Crypto-shredding a tenant = deleting its wrapped DEK.
 */
@Service
public class TenantKeyService {
    private final JdbcClient jdbc;
    private final Map<String, SecretKey> cache = new ConcurrentHashMap<>();

    public TenantKeyService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public SecretKey dek(String tenantId) {
        return cache.computeIfAbsent(tenantId, t -> {
            SecretKey kek = MasterKey.aes("kek/tenant-dek");
            String wrapped = jdbc.sql("select wrapped_key from tenant_keys where tenant_id = :t").param("t", t)
                    .query(String.class).optional().orElse(null);
            if (wrapped == null) {
                byte[] raw = Hashing.randomBytes(32);
                wrapped = Aead.encrypt(kek, raw, "dek:" + t);
                jdbc.sql("insert into tenant_keys(tenant_id, wrapped_key, created_at) values (:t, :w, current_timestamp)")
                        .param("t", t).param("w", wrapped).update();
                return new SecretKeySpec(raw, "AES");
            }
            return new SecretKeySpec(Aead.decrypt(kek, wrapped, "dek:" + t), "AES");
        });
    }

    public String encrypt(String tenantId, String context, String plain) {
        return "enc:v1:" + Aead.encryptString(dek(tenantId), plain, tenantId + "|" + context);
    }

    public String decrypt(String tenantId, String context, String data) {
        if (data == null || !data.startsWith("enc:v1:")) return data;
        return Aead.decryptString(dek(tenantId), data.substring(7), tenantId + "|" + context);
    }

    public String fingerprint(String tenantId) {
        return Hashing.sha256(Base64.getEncoder().encodeToString(dek(tenantId).getEncoded())).substring(0, 16);
    }

    /** Crypto-shredding on tenant offboarding (IM-E09-BL-001 evidence). */
    public void shred(String tenantId) {
        jdbc.sql("delete from tenant_keys where tenant_id = :t").param("t", tenantId).update();
        cache.remove(tenantId);
    }
}
