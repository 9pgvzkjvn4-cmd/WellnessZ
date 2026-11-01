package io.intimed.engine.driver;

import io.intimed.common.Json;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Ed25519 signatures over canonical JSON (driver packages, evidence packs). */
public final class Signatures {
    private Signatures() { }

    public static KeyPair generate() {
        try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String sign(PrivateKey k, JsonNode doc) { return sign(k, Json.canonical(doc).getBytes(StandardCharsets.UTF_8)); }

    public static String sign(PrivateKey k, byte[] data) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(k);
            s.update(data);
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static boolean verify(PublicKey k, JsonNode doc, String sig) { return verify(k, Json.canonical(doc).getBytes(StandardCharsets.UTF_8), sig); }

    public static boolean verify(PublicKey k, byte[] data, String sig) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(k);
            s.update(data);
            return s.verify(Base64.getDecoder().decode(sig.trim()));
        } catch (Exception e) { return false; }
    }

    public static PublicKey publicKey(String b64) {
        try { return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64.trim()))); }
        catch (Exception e) { throw new IllegalArgumentException("invalid Ed25519 public key"); }
    }

    public static PrivateKey privateKey(String b64) {
        try { return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64.trim()))); }
        catch (Exception e) { throw new IllegalArgumentException("invalid Ed25519 private key"); }
    }

    public static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
}
