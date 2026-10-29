package com.wellnessz.secrets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.function.Function;

/**
 * Loads the 256-bit master key from {@code WZ_MASTER_KEY} (base64) or {@code WZ_MASTER_KEY_FILE}
 * (a file mounted by the Secrets Store CSI driver from Azure Key Vault / GCP Secret Manager / Vault).
 * The key never lives in source control or container images.
 */
public final class MasterKey {

    public static final String ENV_KEY = "WZ_MASTER_KEY";
    public static final String ENV_KEY_FILE = "WZ_MASTER_KEY_FILE";

    private MasterKey() {
    }

    /** @return the raw key, or {@code null} when not configured */
    public static byte[] load(Function<String, String> lookup) {
        String inline = lookup.apply(ENV_KEY);
        String file = lookup.apply(ENV_KEY_FILE);
        String encoded = null;
        if (file != null && !file.isBlank()) {
            try {
                encoded = Files.readString(Path.of(file), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new CryptoException("Unable to read master key file", e);
            }
        } else if (inline != null && !inline.isBlank()) {
            encoded = inline.trim();
        }
        if (encoded == null) {
            return null;
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new CryptoException("Master key is not valid base64", e);
        }
        if (key.length != 32) {
            throw new CryptoException("Master key must decode to 32 bytes (generate with: openssl rand -base64 32)", null);
        }
        return key;
    }
}
