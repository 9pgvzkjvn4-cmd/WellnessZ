package io.intimed.security.crypto;

import io.intimed.common.Hashing;
import io.intimed.common.Paths2;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Root key-encryption key (SC-05/SC-10). Resolution order: {@code INTIMED_MASTER_KEY} (base64, 32 bytes – the macOS
 * launcher injects it from the login Keychain) → {@code <home>/keys/master.key} (generated, owner-only permissions; LOCAL
 * only). In DEV/UAT/PROD the same interface is backed by Azure Key Vault / Cloud KMS / Vault Transit (see docs).
 * Purpose keys are derived with HKDF-like HMAC expansion so that one root never encrypts data directly.
 */
public final class MasterKey {
    private static volatile byte[] root;

    private MasterKey() { }

    public static synchronized byte[] root() {
        if (root != null) return root;
        String env = io.intimed.common.Secrets.get("INTIMED_MASTER_KEY");
        if (env == null) env = System.getProperty("intimed.master-key");
        if (env != null && !env.isBlank()) {
            byte[] k = Base64.getDecoder().decode(env.trim());
            if (k.length != 32) throw new IllegalStateException("INTIMED_MASTER_KEY must be 32 bytes (base64)");
            root = k;
            return root;
        }
        Path f = Paths2.dir("keys").resolve("master.key");
        try {
            if (Files.exists(f)) {
                root = Base64.getDecoder().decode(Files.readString(f).trim());
            } else {
                root = Hashing.randomBytes(32);
                Paths2.writeSecret(f, Base64.getEncoder().encode(root));
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return root;
    }

    /** Derived purpose key: HMAC-SHA256(root, "intimed/" + purpose). */
    public static byte[] derive(String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(root(), "HmacSHA256"));
            return mac.doFinal(("intimed/" + purpose).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static SecretKey aes(String purpose) { return new SecretKeySpec(derive(purpose), "AES"); }

    /** Test hook. */
    public static synchronized void reset() { root = null; }
}
