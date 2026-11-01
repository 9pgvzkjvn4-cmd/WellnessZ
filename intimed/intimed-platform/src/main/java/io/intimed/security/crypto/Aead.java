package io.intimed.security.crypto;

import io.intimed.common.Hashing;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** AES-256-GCM with random 96-bit IV and associated data. Format: base64(iv || ciphertext+tag). */
public final class Aead {
    private Aead() { }

    public static String encrypt(SecretKey key, byte[] plain, String aad) {
        try {
            byte[] iv = Hashing.randomBytes(12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plain);
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + ct.length).put(iv).put(ct).array());
        } catch (Exception e) { throw new IllegalStateException("encryption failed", e); }
    }

    public static byte[] decrypt(SecretKey key, String data, String aad) {
        try {
            byte[] all = Base64.getDecoder().decode(data);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return c.doFinal(all, 12, all.length - 12);
        } catch (Exception e) { throw new SecurityException("decryption failed (wrong key, tenant or tampered data)"); }
    }

    public static String encryptString(SecretKey key, String plain, String aad) { return encrypt(key, plain.getBytes(StandardCharsets.UTF_8), aad); }
    public static String decryptString(SecretKey key, String data, String aad) { return new String(decrypt(key, data, aad), StandardCharsets.UTF_8); }
}
