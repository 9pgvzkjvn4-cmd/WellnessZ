package com.wellnessz.secrets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves secret references in configuration values:
 * <ul>
 *   <li>{@code ENC(...)} – AES-256-GCM ciphertext produced by {@link EncryptCli}, decrypted with the master key</li>
 *   <li>{@code file:/mnt/secrets/db-password} – read from a mounted secret file; honoured only for
 *       sensitive property names (password, secret, key, token, credential) so ordinary {@code file:} URLs are untouched</li>
 * </ul>
 */
public final class SecretValueResolver {

    public static final String CONFIG_AAD = "wellnessz-config";
    private static final Pattern ENC = Pattern.compile("^ENC\\((.+)\\)$");
    private static final Pattern SENSITIVE = Pattern.compile(".*(password|secret|key|token|credential).*");

    private final AesGcmCipher cipher;

    public SecretValueResolver(byte[] masterKey) {
        this.cipher = masterKey != null ? new AesGcmCipher(Hkdf.derive(masterKey, "wellnessz/config/v1", 32)) : null;
    }

    public boolean needsResolution(String propertyName, String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return ENC.matcher(v).matches() || (v.startsWith("file:") && isSensitive(propertyName));
    }

    public String resolve(String propertyName, String value) {
        String v = value.trim();
        Matcher m = ENC.matcher(v);
        if (m.matches()) {
            if (cipher == null) {
                throw new CryptoException("Property '" + propertyName + "' is encrypted but no master key ("
                        + MasterKey.ENV_KEY + " / " + MasterKey.ENV_KEY_FILE + ") is configured", null);
            }
            return cipher.decrypt(m.group(1), CONFIG_AAD);
        }
        if (v.startsWith("file:") && isSensitive(propertyName)) {
            try {
                return Files.readString(Path.of(v.substring("file:".length())), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new CryptoException("Unable to read secret file for property '" + propertyName + "'", e);
            }
        }
        return value;
    }

    public String encrypt(String plaintext) {
        if (cipher == null) {
            throw new CryptoException("No master key configured", null);
        }
        return "ENC(" + cipher.encrypt(plaintext, CONFIG_AAD) + ")";
    }

    static boolean isSensitive(String propertyName) {
        return propertyName != null && SENSITIVE.matcher(propertyName.toLowerCase(Locale.ROOT)).matches();
    }
}
