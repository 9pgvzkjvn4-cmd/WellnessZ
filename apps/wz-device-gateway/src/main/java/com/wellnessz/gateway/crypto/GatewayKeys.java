package com.wellnessz.gateway.crypto;

import com.wellnessz.gateway.support.PathNames;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Function;

import com.wellnessz.secrets.AesGcmCipher;
import com.wellnessz.secrets.CryptoException;
import com.wellnessz.secrets.Hkdf;
import com.wellnessz.secrets.MasterKey;

/**
 * Key material of the edge node. The root key comes from {@code WZ_MASTER_KEY} / {@code WZ_MASTER_KEY_FILE}
 * (OS key store / TPM-sealed file provisioned by the hardening image) or, when neither is set, from a key file in the
 * data directory created on first start and readable only by the service account (POSIX 0600 / Windows owner-only ACL).
 * Purpose-specific AES-256-GCM keys are derived with HKDF-SHA256 (wz-secrets) so the identity file and the queue
 * never share a key.
 */
public final class GatewayKeys {

    public static final String IDENTITY_PURPOSE = "wz-gateway/identity/v1";
    public static final String JOURNAL_PURPOSE = "wz-gateway/journal/v1";
    public static final String STATE_PURPOSE = "wz-gateway/state/v1";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] rootKey;
    private final boolean externallyProvided;

    private GatewayKeys(byte[] rootKey, boolean externallyProvided) {
        this.rootKey = rootKey;
        this.externallyProvided = externallyProvided;
    }

    /** Loads the root key from the environment lookup or the local key file (created when absent). */
    public static GatewayKeys load(Function<String, String> lookup, Path dataDir) {
        byte[] external = MasterKey.load(lookup);
        if (external != null) {
            return new GatewayKeys(external, true);
        }
        Path keyFile = dataDir.resolve("keys").resolve("gateway.key");
        try {
            Files.createDirectories(PathNames.parent(keyFile));
            if (!Files.exists(keyFile)) {
                byte[] fresh = new byte[32];
                RANDOM.nextBytes(fresh);
                writeOwnerOnly(keyFile, Base64.getEncoder().encodeToString(fresh));
            }
            byte[] key = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.US_ASCII).trim());
            if (key.length != 32) {
                throw new CryptoException("Local key file must contain a base64 256-bit key", null);
            }
            return new GatewayKeys(key, false);
        } catch (IOException | IllegalArgumentException e) {
            throw new CryptoException("Unable to load or create the local gateway key", e);
        }
    }

    /** Test / tooling factory with an explicit root key. */
    public static GatewayKeys of(byte[] rootKey) {
        if (rootKey == null || rootKey.length != 32) {
            throw new IllegalArgumentException("root key must be 32 bytes");
        }
        return new GatewayKeys(rootKey.clone(), true);
    }

    public AesGcmCipher cipher(String purpose) {
        return new AesGcmCipher(Hkdf.derive(rootKey, purpose, 32));
    }

    /** Keyed hash used for pseudonymous values that leave the edge (e.g. BLE address hashes). */
    public byte[] derive(String purpose) {
        return Hkdf.derive(rootKey, purpose, 32);
    }

    public boolean externallyProvided() {
        return externallyProvided;
    }

    private static void writeOwnerOnly(Path file, String content) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(PathNames.parent(file), PosixFileAttributeView.class);
        try {
            if (posix != null) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(file);
                restrictToOwner(file);
            }
        } catch (FileAlreadyExistsException e) {
            return; // created concurrently by another start; keep it
        }
        Files.writeString(file, content, StandardCharsets.US_ASCII);
    }

    /** Windows: replace the inherited ACL by a single entry for the file owner (the service account). */
    static void restrictToOwner(Path file) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl == null) {
            return;
        }
        UserPrincipal owner = Files.getOwner(file);
        AclEntry entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.of(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA,
                        AclEntryPermission.APPEND_DATA, AclEntryPermission.READ_ATTRIBUTES,
                        AclEntryPermission.WRITE_ATTRIBUTES, AclEntryPermission.READ_ACL, AclEntryPermission.DELETE,
                        AclEntryPermission.SYNCHRONIZE))
                .build();
        acl.setAcl(List.of(entry));
    }
}
