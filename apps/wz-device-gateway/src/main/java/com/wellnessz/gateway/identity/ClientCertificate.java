package com.wellnessz.gateway.identity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collections;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.wellnessz.gateway.config.GatewayProperties.ClientTls;
import com.wellnessz.gateway.support.Hashes;

/**
 * The node's client certificate from the group PKI (EST / SCEP / manual CSR outside the platform, contract §15.2) held
 * in a PKCS#12 / JKS key store. Provides the SHA-256 thumbprint bound by E34 at enrolment, the PEM sent with the
 * enrolment request, the proof-of-possession signature and the JDK {@link SSLContext} for mutual TLS to the ingress.
 */
public final class ClientCertificate {

    private final X509Certificate certificate;
    private final PrivateKey privateKey;
    private final SSLContext sslContext;

    private ClientCertificate(X509Certificate certificate, PrivateKey privateKey, SSLContext sslContext) {
        this.certificate = certificate;
        this.privateKey = privateKey;
        this.sslContext = sslContext;
    }

    /** No certificate configured (development without the mTLS ingress). */
    public static ClientCertificate none(ClientTls tls) {
        return new ClientCertificate(null, null, trustOnlyContext(tls));
    }

    public static ClientCertificate load(ClientTls tls) {
        if (tls.keyStore() == null || tls.keyStore().isBlank()) {
            return none(tls);
        }
        try {
            char[] password = chars(tls.keyStorePassword());
            KeyStore ks = loadStore(Path.of(tls.keyStore()), tls.keyStoreType(), password);
            String alias = tls.keyAlias();
            if (alias == null || alias.isBlank()) {
                alias = Collections.list(ks.aliases()).stream().filter(a -> isKeyEntry(ks, a)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("client key store holds no private key entry"));
            }
            X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
            PrivateKey key = (PrivateKey) ks.getKey(alias, password);
            if (cert == null || key == null) {
                throw new IllegalStateException("client key store entry has no certificate / key");
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), trustManagers(tls), null);
            return new ClientCertificate(cert, key, ctx);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("unable to load the client certificate key store", e);
        }
    }

    public boolean present() {
        return certificate != null;
    }

    /** Lower-case hex SHA-256 of the DER certificate (the value the ingress puts into {@code X-Client-Cert-Sha256}). */
    public String thumbprint() {
        try {
            return certificate == null ? null : Hashes.sha256Hex(certificate.getEncoded());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("certificate encoding failed", e);
        }
    }

    public String pem() {
        if (certificate == null) {
            return null;
        }
        try {
            String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(certificate.getEncoded());
            return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("certificate encoding failed", e);
        }
    }

    public java.time.Instant notAfter() {
        return certificate == null ? null : certificate.getNotAfter().toInstant();
    }

    /** Proof of possession: base64 signature with the certificate's private key (RSA, EC or Ed25519). */
    public String sign(String data) {
        if (privateKey == null) {
            return null;
        }
        try {
            Signature sig = Signature.getInstance(switch (privateKey.getAlgorithm()) {
                case "RSA" -> "SHA256withRSA";
                case "EC" -> "SHA256withECDSA";
                case "Ed25519", "EdDSA" -> "Ed25519";
                default -> throw new IllegalStateException("unsupported key algorithm " + privateKey.getAlgorithm());
            });
            sig.initSign(privateKey);
            sig.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("proof-of-possession signature failed", e);
        }
    }

    public SSLContext sslContext() {
        return sslContext;
    }

    private static SSLContext trustOnlyContext(ClientTls tls) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustManagers(tls), null);
            return ctx;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("unable to initialise TLS", e);
        }
    }

    private static javax.net.ssl.TrustManager[] trustManagers(ClientTls tls) throws GeneralSecurityException, IOException {
        if (tls.trustStore() == null || tls.trustStore().isBlank()) {
            return null; // JDK default trust (group PKI root installed in the OS / JDK trust store)
        }
        KeyStore ts = loadStore(Path.of(tls.trustStore()), tls.trustStoreType(), chars(tls.trustStorePassword()));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        return tmf.getTrustManagers();
    }

    static KeyStore loadStore(Path path, String type, char[] password) throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(type == null ? "PKCS12" : type);
        try (InputStream in = Files.newInputStream(path)) {
            ks.load(in, password);
        }
        return ks;
    }

    static char[] chars(String s) {
        return s == null ? new char[0] : s.toCharArray();
    }

    private static boolean isKeyEntry(KeyStore ks, String alias) {
        try {
            return ks.isKeyEntry(alias);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
