package com.wellnessz.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretsTest {

    static byte[] key() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    void aesGcmRoundTripAndTamperDetection() {
        AesGcmCipher c = new AesGcmCipher(key());
        String ct = c.encrypt("db-password", "ctx");
        assertThat(ct).doesNotContain("db-password");
        assertThat(c.encrypt("db-password", "ctx")).isNotEqualTo(ct); // random IV
        assertThat(c.decrypt(ct, "ctx")).isEqualTo("db-password");
        assertThatThrownBy(() -> c.decrypt(ct, "other-context")).isInstanceOf(CryptoException.class);
        char[] chars = ct.toCharArray();
        chars[20] = chars[20] == 'A' ? 'B' : 'A';
        assertThatThrownBy(() -> c.decrypt(new String(chars), "ctx")).isInstanceOf(CryptoException.class);
    }

    @Test
    void resolvesEncryptedAndFileSecrets(@TempDir Path dir) throws Exception {
        SecretValueResolver r = new SecretValueResolver(key());
        String enc = r.encrypt("s3cret!");
        assertThat(r.needsResolution("WZ_DB_PASSWORD", enc)).isTrue();
        assertThat(r.resolve("WZ_DB_PASSWORD", enc)).isEqualTo("s3cret!");

        Path f = dir.resolve("pw");
        Files.writeString(f, "from-file\n");
        assertThat(r.resolve("spring.datasource.password", "file:" + f)).isEqualTo("from-file");
        // file: on non-sensitive properties is left untouched
        assertThat(r.needsResolution("spring.config.import", "file:" + f)).isFalse();
    }

    @Test
    void encryptedValueWithoutKeyFailsClosed() {
        String enc = new SecretValueResolver(key()).encrypt("x");
        assertThatThrownBy(() -> new SecretValueResolver(null).resolve("password", enc)).isInstanceOf(CryptoException.class);
    }

    @Test
    void hkdfDerivesDistinctKeysPerPurpose() {
        byte[] master = key();
        assertThat(Hkdf.derive(master, "a", 32)).isNotEqualTo(Hkdf.derive(master, "b", 32)).hasSize(32);
    }
}
