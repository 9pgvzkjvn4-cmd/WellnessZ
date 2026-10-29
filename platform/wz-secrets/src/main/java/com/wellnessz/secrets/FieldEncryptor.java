package com.wellnessz.secrets;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Application-level (column) encryption for clinical data, on top of storage encryption (TDE / CMEK).
 * Keys are derived per key-version from the master key so that rotation is a configuration change:
 * new writes use {@code wellnessz.crypto.active-key-version}; old ciphertexts remain readable because
 * each value is prefixed with its key version ({@code v1:...}). Re-encryption jobs can migrate data.
 */
@Component
public class FieldEncryptor {

    private final byte[] masterKey;
    private final String activeVersion;
    private final Map<String, AesGcmCipher> ciphers = new ConcurrentHashMap<>();

    public FieldEncryptor(@Value("${wellnessz.crypto.active-key-version:v1}") String activeVersion,
                          @Value("${WZ_MASTER_KEY:}") String inlineKey,
                          @Value("${WZ_MASTER_KEY_FILE:}") String keyFile) {
        byte[] key = MasterKey.load(name -> MasterKey.ENV_KEY.equals(name) ? inlineKey : keyFile);
        if (key == null) {
            throw new CryptoException("Master key is required for field-level encryption ("
                    + MasterKey.ENV_KEY + " or " + MasterKey.ENV_KEY_FILE + ")", null);
        }
        if (!activeVersion.matches("v[0-9]{1,4}")) {
            throw new IllegalArgumentException("Invalid key version");
        }
        this.masterKey = key;
        this.activeVersion = activeVersion;
    }

    public String encrypt(String plaintext, String context) {
        if (plaintext == null) {
            return null;
        }
        return activeVersion + ":" + cipher(activeVersion).encrypt(plaintext, context);
    }

    public String decrypt(String stored, String context) {
        if (stored == null) {
            return null;
        }
        int idx = stored.indexOf(':');
        if (idx < 2) {
            throw new CryptoException("Value is not in versioned ciphertext format", null);
        }
        return cipher(stored.substring(0, idx)).decrypt(stored.substring(idx + 1), context);
    }

    /** Keyed, deterministic blind index so encrypted columns can still be searched for equality. */
    public String blindIndex(String value) {
        if (value == null) {
            return null;
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(Hkdf.derive(masterKey, "wellnessz/blind-index/v1", 32), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new CryptoException("Blind index failed", e);
        }
    }

    private AesGcmCipher cipher(String version) {
        return ciphers.computeIfAbsent(version,
                v -> new AesGcmCipher(Hkdf.derive(masterKey, "wellnessz/field/" + v, 32)));
    }
}
