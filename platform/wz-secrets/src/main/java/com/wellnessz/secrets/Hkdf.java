package com.wellnessz.secrets;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HKDF-SHA256 (RFC 5869) used to derive purpose-specific sub-keys from the master key. */
public final class Hkdf {

    private Hkdf() {
    }

    public static byte[] derive(byte[] inputKey, String info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            byte[] prk = mac.doFinal(inputKey);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] t = new byte[0];
            byte counter = 1;
            while (out.size() < length) {
                mac.update(t);
                mac.update(info.getBytes(StandardCharsets.UTF_8));
                mac.update(counter++);
                t = mac.doFinal();
                out.write(t, 0, t.length);
            }
            byte[] okm = new byte[length];
            System.arraycopy(out.toByteArray(), 0, okm, 0, length);
            return okm;
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Key derivation failed", e);
        }
    }
}
