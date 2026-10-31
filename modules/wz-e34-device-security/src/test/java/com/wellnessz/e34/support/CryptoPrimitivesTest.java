package com.wellnessz.e34.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.wellnessz.e34.identity.CertificateVerifier;
import com.wellnessz.e34.signing.Canonicaliser;

/** Token hashing with a pepper, canonical manifests and Ed25519 / ECDSA signatures with the JDK, proof of possession. */
class CryptoPrimitivesTest {

    final TokenHasher hasher = new TokenHasher("unit-test-pepper-0123456789".getBytes(StandardCharsets.UTF_8));

    @Test
    void tokensAre256BitAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String t = hasher.newToken();
            assertThat(t).hasSize(43).matches("[A-Za-z0-9_\\-]+");
            assertThat(seen.add(t)).isTrue();
        }
    }

    @Test
    void hashIsPepperedDeterministicAndNeverTheToken() {
        String h = hasher.hash("secret-token");
        assertThat(h).isEqualTo(hasher.hash("secret-token")).isNotEqualTo("secret-token").doesNotContain("secret");
        assertThat(new TokenHasher("another-pepper-abcdefghijk".getBytes(StandardCharsets.UTF_8)).hash("secret-token")).isNotEqualTo(h);
        assertThat(hasher.matches("secret-token", h)).isTrue();
        assertThat(hasher.matches("secret-tokeN", h)).isFalse();
        assertThat(hasher.matches(null, h)).isFalse();
        assertThat(TokenHasher.constantTimeEquals("a", null)).isFalse();
        assertThatThrownBy(() -> new TokenHasher("short".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void canonicalFormSortsKeysDropsWhitespaceKeepsNumbersAndRemovesTopLevelSignature() {
        String a = "{ \"version\" : \"1.0.0\", \"packageId\":\"vitals.wa.connex\", \"mappings\":[{\"key\":\"SPO2\",\"decimals\":0,\"factor\":1.50}],"
                + " \"signature\": {\"keyId\":\"k\",\"value\":\"x\"}, \"nested\": {\"signature\": \"kept\", \"b\": true, \"a\": null}, \"text\":\"Größe \\\"x\\\"\" }";
        String c = new String(Canonicaliser.canonical(a), StandardCharsets.UTF_8);
        assertThat(c).isEqualTo("{\"mappings\":[{\"decimals\":0,\"factor\":1.50,\"key\":\"SPO2\"}],\"nested\":{\"a\":null,\"b\":true,\"signature\":\"kept\"},"
                + "\"packageId\":\"vitals.wa.connex\",\"text\":\"Größe \\\"x\\\"\",\"version\":\"1.0.0\"}");
        // key order and whitespace do not change the canonical bytes
        String b = "{\"text\":\"Größe \\\"x\\\"\",\"version\":\"1.0.0\",\"nested\":{\"b\":true,\"a\":null,\"signature\":\"kept\"},"
                + "\"packageId\":\"vitals.wa.connex\",\"mappings\":[{\"factor\":1.50,\"key\":\"SPO2\",\"decimals\":0}]}";
        assertThat(Canonicaliser.canonical(b)).isEqualTo(Canonicaliser.canonical(a));
    }

    @Test
    void ed25519AndEcdsaSignaturesDetectTampering() throws Exception {
        byte[] manifest = Canonicaliser.canonical("{\"packageId\":\"spiro.ndd.easyone\",\"version\":\"1.2.0\"}");
        byte[] tampered = Canonicaliser.canonical("{\"packageId\":\"spiro.ndd.easyone\",\"version\":\"1.2.1\"}");
        KeyPair ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPairGenerator ecg = KeyPairGenerator.getInstance("EC");
        ecg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair ec = ecg.generateKeyPair();
        for (Object[] c : new Object[][] {{"Ed25519", ed}, {"SHA256withECDSA", ec}}) {
            String alg = (String) c[0];
            KeyPair kp = (KeyPair) c[1];
            Signature s = Signature.getInstance(alg);
            s.initSign(kp.getPrivate());
            s.update(manifest);
            byte[] sig = s.sign();
            Signature v = Signature.getInstance(alg);
            v.initVerify(kp.getPublic());
            v.update(manifest);
            assertThat(v.verify(sig)).as(alg).isTrue();
            v.initVerify(kp.getPublic());
            v.update(tampered);
            assertThat(v.verify(sig)).as(alg + " tampered").isFalse();
        }
    }

    @Test
    void proofOfPossession() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(kp.getPrivate());
        s.update("enrol:tok:abc".getBytes(StandardCharsets.UTF_8));
        String proof = Base64.getEncoder().encodeToString(s.sign());
        CertificateVerifier.verifyProof(kp.getPublic(), "enrol:tok:abc", proof);
        assertThatThrownBy(() -> CertificateVerifier.verifyProof(kp.getPublic(), "enrol:other:abc", proof))
                .isInstanceOf(CertificateVerifier.PkiException.class).hasMessage("proof_invalid");
        assertThatThrownBy(() -> CertificateVerifier.verifyProof(kp.getPublic(), "enrol:tok:abc", "not-base64!"))
                .hasMessage("proof_invalid");
        KeyPair other = g.generateKeyPair();
        assertThatThrownBy(() -> CertificateVerifier.verifyProof(other.getPublic(), "enrol:tok:abc", proof)).hasMessage("proof_invalid");
    }

    @Test
    void untrustedWithoutAnchorsAndInvalidPem() {
        CertificateVerifier v = new CertificateVerifier("");
        assertThatThrownBy(() -> v.parseChain("not a pem")).hasMessage("enrolment_invalid");
        assertThatThrownBy(() -> v.parseChain("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----")).hasMessage("enrolment_invalid");
        assertThatThrownBy(() -> v.validateChain(java.util.List.of(), java.time.Instant.now())).hasMessage("certificate_untrusted");
    }

    @Test
    void settingsClamp() {
        assertThat(E34Settings.clamp(500, 1, 72)).isEqualTo(72);
        assertThat(E34Settings.clamp(-3, 1, 72)).isEqualTo(1);
        assertThat(E34Settings.clamp(15, 1, 60)).isEqualTo(15);
    }
}
