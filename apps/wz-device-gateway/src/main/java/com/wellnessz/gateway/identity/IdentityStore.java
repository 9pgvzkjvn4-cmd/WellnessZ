package com.wellnessz.gateway.identity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import com.wellnessz.gateway.support.AtomicFiles;
import com.wellnessz.secrets.AesGcmCipher;
import com.wellnessz.secrets.CryptoException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Persists the endpoint id and device token encrypted at rest (AES-256-GCM via wz-secrets, key derived for the
 * identity purpose only, associated data binds the ciphertext to this file). The token is never logged.
 */
public class IdentityStore {

    private static final String AAD = "wz-gateway/identity-file";

    private final Path file;
    private final AesGcmCipher cipher;
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile EndpointIdentity current;

    public IdentityStore(Path dataDir, AesGcmCipher cipher) {
        this.file = dataDir.resolve("identity.enc");
        this.cipher = cipher;
        this.current = readFile();
    }

    public EndpointIdentity current() {
        return current;
    }

    public synchronized void save(EndpointIdentity identity) {
        try {
            AtomicFiles.write(file, cipher.encrypt(json.writeValueAsString(identity), AAD));
            current = identity;
        } catch (IOException e) {
            throw new UncheckedIOException("unable to persist the endpoint identity", e);
        }
    }

    private EndpointIdentity readFile() {
        try {
            String stored = AtomicFiles.readOrNull(file);
            if (stored == null) {
                return null;
            }
            return json.readValue(cipher.decrypt(stored.trim(), AAD), EndpointIdentity.class);
        } catch (IOException e) {
            throw new UncheckedIOException("unable to read the endpoint identity", e);
        } catch (CryptoException e) {
            throw new IllegalStateException("endpoint identity file cannot be decrypted (wrong key or tampered)", e);
        }
    }
}
