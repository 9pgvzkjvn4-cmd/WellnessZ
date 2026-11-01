package io.intimed.harness;

import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.common.Paths2;
import io.intimed.engine.driver.Signatures;
import io.intimed.security.crypto.Aead;
import io.intimed.security.crypto.MasterKey;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Evidence pack sealing (architecture §7.1, XT-16, SC-20): SHA-256 over every element, a canonical manifest of those
 * digests, an Ed25519 signature by the harness service key; the zip is written read-only ("retention lock" of the local
 * object store – Azure immutable blob / GCS retention lock / MinIO object lock in the cloud settings).
 */
@Component
public class EvidenceSealer {
    private final PrivateKey priv;
    private final PublicKey pub;

    public EvidenceSealer() {
        Path dir = Paths2.dir("keys");
        Path k = dir.resolve("harness-signing.key.enc"), p = dir.resolve("harness-signing.pub");
        try {
            if (!Files.exists(k)) {
                KeyPair kp = Signatures.generate();
                Paths2.writeSecret(k, Aead.encrypt(MasterKey.aes("harness-sign"), kp.getPrivate().getEncoded(), "harness").getBytes(StandardCharsets.UTF_8));
                Files.writeString(p, Signatures.b64(kp.getPublic().getEncoded()));
            }
            this.priv = java.security.KeyFactory.getInstance("Ed25519").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(
                    Aead.decrypt(MasterKey.aes("harness-sign"), Files.readString(k), "harness")));
            this.pub = Signatures.publicKey(Files.readString(p));
        } catch (Exception e) { throw new IllegalStateException("harness key unavailable", e); }
    }

    public String publicKey() { return Signatures.b64(pub.getEncoded()); }

    /** Writes the pack and returns the seal (digest of the canonical file manifest + signature). */
    public ObjectNode seal(String runId, Map<String, byte[]> files) {
        Map<String, String> digests = new TreeMap<>();
        files.forEach((n, b) -> digests.put(n, Hashing.sha256(b)));
        ObjectNode manifest = Json.obj().put("runId", runId).put("algorithm", "SHA-256").put("signature", "Ed25519");
        manifest.set("files", Json.tree(digests));
        String canonical = Json.canonical(manifest);
        String seal = Hashing.sha256(canonical);
        String sig = Signatures.sign(priv, canonical.getBytes(StandardCharsets.UTF_8));
        ObjectNode sealDoc = Json.obj().put("seal", seal).put("signature", sig).put("publicKey", publicKey());
        sealDoc.set("manifest", manifest);
        Path zip = Paths2.dir("evidence").resolve(runId + ".zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (var e : files.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue()); z.closeEntry(); }
            z.putNextEntry(new ZipEntry("seal.json"));
            z.write(Json.pretty(sealDoc).getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
        zip.toFile().setWritable(false, false);
        return sealDoc;
    }

    public byte[] read(String runId) {
        try { return Files.readAllBytes(Paths2.dir("evidence").resolve(runId.replaceAll("[^A-Za-z0-9-]", "") + ".zip")); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** Re-computes every digest and verifies the signature. */
    public Map<String, Object> verify(byte[] zip) {
        Map<String, byte[]> files = new TreeMap<>();
        JsonNode sealDoc = null;
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                z.transferTo(b);
                if ("seal.json".equals(e.getName())) sealDoc = Json.parse(b.toString(StandardCharsets.UTF_8));
                else files.put(e.getName(), b.toByteArray());
            }
        } catch (IOException ex) { return Map.of("valid", false, "reason", "unreadable pack"); }
        if (sealDoc == null) return Map.of("valid", false, "reason", "seal.json missing");
        JsonNode m = sealDoc.get("manifest");
        for (var e : files.entrySet()) {
            if (!Hashing.sha256(e.getValue()).equals(m.path("files").path(e.getKey()).asString(""))) return Map.of("valid", false, "reason", "digest mismatch: " + e.getKey());
        }
        if (m.path("files").size() != files.size()) return Map.of("valid", false, "reason", "file set changed");
        String canonical = Json.canonical(m);
        boolean sigOk = Signatures.verify(Signatures.publicKey(sealDoc.get("publicKey").asString()), canonical.getBytes(StandardCharsets.UTF_8), sealDoc.get("signature").asString());
        boolean trusted = publicKey().equals(sealDoc.get("publicKey").asString());
        return Map.of("valid", sigOk && trusted && Hashing.sha256(canonical).equals(sealDoc.get("seal").asString()), "seal", sealDoc.get("seal").asString(),
                "signatureValid", sigOk, "trustedKey", trusted, "files", files.size());
    }
}
