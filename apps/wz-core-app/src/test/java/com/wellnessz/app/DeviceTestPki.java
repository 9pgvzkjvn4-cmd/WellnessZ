package com.wellnessz.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Throw-away device PKI for the E34 integration test, built with the JDK {@code keytool} only (no Bouncy Castle): a test CA and
 * client certificates (EC P-256, EKU clientAuth) signed by it, plus an untrusted self-signed certificate and one without the
 * clientAuth usage. Keys exist only in a temporary directory for the duration of the test JVM.
 */
final class DeviceTestPki {

    /** A client certificate with its private key. */
    record Client(String pem, String sha256, PrivateKey key) {

        String sign(String message) {
            try {
                Signature s = Signature.getInstance("SHA256withECDSA");
                s.initSign(key);
                s.update(message.getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(s.sign());
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final String PASS = "test-only-store";

    final Path dir;
    final Path caPem;
    private int counter;

    private DeviceTestPki(Path dir) {
        this.dir = dir;
        this.caPem = dir.resolve("ca.pem");
    }

    static DeviceTestPki create() {
        try {
            Path dir = Files.createTempDirectory("wz-device-pki");
            dir.toFile().deleteOnExit();
            DeviceTestPki pki = new DeviceTestPki(dir);
            pki.keytool("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=WellnessZ Test Device CA,O=Test",
                    "-ext", "bc:c", "-validity", "30", "-keystore", dir.resolve("ca.p12").toString(), "-storetype", "PKCS12", "-storepass", PASS);
            pki.keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", dir.resolve("ca.p12").toString(), "-storepass", PASS,
                    "-file", pki.caPem.toString());
            return pki;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String caPemPath() {
        return caPem.toString();
    }

    /** Client certificate signed by the test CA with EKU clientAuth (or {@code extendedKeyUsage} as given). */
    Client client(String cn, String extendedKeyUsage) {
        String alias = "c" + (++counter);
        Path ks = dir.resolve(alias + ".p12");
        keytool("-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + cn + ",OU=Devices,O=Test",
                "-validity", "30", "-keystore", ks.toString(), "-storetype", "PKCS12", "-storepass", PASS);
        Path csr = dir.resolve(alias + ".csr");
        Path pem = dir.resolve(alias + ".pem");
        keytool("-certreq", "-alias", alias, "-keystore", ks.toString(), "-storepass", PASS, "-file", csr.toString());
        keytool("-gencert", "-alias", "ca", "-keystore", dir.resolve("ca.p12").toString(), "-storepass", PASS, "-infile", csr.toString(),
                "-outfile", pem.toString(), "-rfc", "-validity", "30", "-ext", "EKU=" + extendedKeyUsage, "-ext", "KU=digitalSignature");
        return load(ks, alias, pem);
    }

    Client client(String cn) {
        return client(cn, "clientAuth");
    }

    /** Self-signed certificate (not issued by the test CA). */
    Client selfSigned(String cn) {
        String alias = "s" + (++counter);
        Path ks = dir.resolve(alias + ".p12");
        Path pem = dir.resolve(alias + ".pem");
        keytool("-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + cn, "-validity", "30",
                "-ext", "EKU=clientAuth", "-keystore", ks.toString(), "-storetype", "PKCS12", "-storepass", PASS);
        keytool("-exportcert", "-rfc", "-alias", alias, "-keystore", ks.toString(), "-storepass", PASS, "-file", pem.toString());
        return load(ks, alias, pem);
    }

    private Client load(Path ks, String alias, Path pem) {
        try (InputStream in = Files.newInputStream(ks)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, PASS.toCharArray());
            PrivateKey key = (PrivateKey) store.getKey(alias, PASS.toCharArray());
            String pemText = Files.readString(pem, StandardCharsets.US_ASCII);
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(pemText.getBytes(StandardCharsets.US_ASCII)));
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
            return new Client(pemText, sha, key);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void keytool(String... args) {
        List<String> cmd = new ArrayList<>();
        String javaHome = System.getProperty("java.home");
        cmd.add(Path.of(javaHome, "bin", "keytool").toString());
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                throw new IllegalStateException("keytool failed: " + out);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
