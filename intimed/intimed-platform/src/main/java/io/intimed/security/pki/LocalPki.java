package io.intimed.security.pki;

import io.intimed.common.Hashing;
import io.intimed.common.Paths2;
import io.intimed.security.crypto.Aead;
import io.intimed.security.crypto.MasterKey;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Local PKI of the MacBook kit (SC-01, SC-14, ADR-09; replaces step-ca in the single-process LOCAL setting): an
 * installation root CA, per-tenant issuing CAs and a localhost server certificate. Private keys are stored wrapped with
 * a master-derived key (never in clear on disk); the server keystore password is derived from the master key at runtime.
 * In DEV/UAT/PROD the {@link PkiService} interface is backed by the client CA, Vault PKI or step-ca (see docs/security).
 */
public final class LocalPki {
    public static final String SIG = "SHA256withECDSA";
    private static volatile LocalPki instance;
    private final Path dir;
    private final X509Certificate root;
    private final PrivateKey rootKey;

    private LocalPki(Path dir) {
        this.dir = dir;
        Path crt = dir.resolve("root-ca.crt");
        if (!Files.exists(crt)) {
            KeyPair kp = ecKeyPair();
            X509Certificate c = buildCa(new X500Name("CN=IntiMed Local Root CA,O=IntiMed,OU=LOCAL"), kp.getPublic(),
                    new X500Name("CN=IntiMed Local Root CA,O=IntiMed,OU=LOCAL"), kp.getPrivate(), Duration.ofDays(3650), 1, null);
            writePem(crt, c);
            storeKey(dir.resolve("root-ca.key.enc"), kp.getPrivate(), "root");
        }
        this.root = readCert(crt);
        this.rootKey = loadKey(dir.resolve("root-ca.key.enc"), "root");
    }

    public static LocalPki get() {
        if (instance == null) {
            synchronized (LocalPki.class) {
                if (instance == null) instance = new LocalPki(Paths2.dir("pki"));
            }
        }
        return instance;
    }

    public static synchronized void reset() { instance = null; }

    public X509Certificate root() { return root; }
    public Path dir() { return dir; }

    // ---------------------------------------------------------------- tenant issuing CA
    public synchronized TenantCa tenantCa(String tenant) {
        Path crt = dir.resolve("tenant-" + tenant + "-ca.crt");
        Path key = dir.resolve("tenant-" + tenant + "-ca.key.enc");
        if (!Files.exists(crt)) {
            KeyPair kp = ecKeyPair();
            X509Certificate c = buildCa(new X500Name("CN=IntiMed Tenant CA " + tenant + ",O=IntiMed,OU=" + tenant), kp.getPublic(),
                    X500Name.getInstance(root.getSubjectX500Principal().getEncoded()), rootKey, Duration.ofDays(1825), 0, root);
            writePem(crt, c);
            storeKey(key, kp.getPrivate(), "tenant-ca/" + tenant);
        }
        return new TenantCa(tenant, readCert(crt), loadKey(key, "tenant-ca/" + tenant));
    }

    public record TenantCa(String tenant, X509Certificate cert, PrivateKey key) { }

    /** Issues an endpoint client certificate from a PKCS#10 CSR (private key stays on the device). */
    public X509Certificate issueFromCsr(String tenant, String endpointId, String csrPem, Duration validity) {
        try {
            PKCS10CertificationRequest csr = new PKCS10CertificationRequest(Pem.decode(csrPem, "CERTIFICATE REQUEST"));
            if (!csr.isSignatureValid(new JcaContentVerifierProviderBuilder().build(csr.getSubjectPublicKeyInfo()))) {
                throw new SecurityException("CSR signature invalid (proof of possession failed)");
            }
            PublicKey pub = new JcaPKCS10CertificationRequest(csr).getPublicKey();
            return issueEndpoint(tenant, endpointId, pub, validity);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid CSR: " + e.getMessage());
        }
    }

    public X509Certificate issueEndpoint(String tenant, String endpointId, PublicKey pub, Duration validity) {
        TenantCa ca = tenantCa(tenant);
        try {
            Instant now = Instant.now();
            JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                    X500Name.getInstance(ca.cert().getSubjectX500Principal().getEncoded()), serial(),
                    Date.from(now.minusSeconds(60)), Date.from(now.plus(validity)),
                    new X500Name("CN=" + endpointId + ",OU=" + tenant + ",O=IntiMed endpoint"), pub);
            JcaX509ExtensionUtils u = new JcaX509ExtensionUtils();
            b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyAgreement));
            b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
            b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(
                    GeneralName.uniformResourceIdentifier, "urn:intimed:tenant:" + tenant + ":endpoint:" + endpointId)));
            b.addExtension(Extension.authorityKeyIdentifier, false, u.createAuthorityKeyIdentifier(ca.cert()));
            b.addExtension(Extension.subjectKeyIdentifier, false, u.createSubjectKeyIdentifier(pub));
            return sign(b, ca.key());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** localhost server certificate used by the console (9443), device ingress (8443) and MLLP-TLS (2575). */
    public synchronized ServerCredentials server() {
        Path crt = dir.resolve("server.crt");
        Path key = dir.resolve("server.key.enc");
        if (!Files.exists(crt) || readCert(crt).getNotAfter().toInstant().isBefore(Instant.now().plus(Duration.ofDays(30)))) {
            KeyPair kp = ecKeyPair();
            try {
                Instant now = Instant.now();
                JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                        X500Name.getInstance(root.getSubjectX500Principal().getEncoded()), serial(),
                        Date.from(now.minusSeconds(60)), Date.from(now.plus(Duration.ofDays(397))),
                        new X500Name("CN=localhost,O=IntiMed,OU=LOCAL"), kp.getPublic());
                b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
                b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyAgreement));
                b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
                b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[]{
                        new GeneralName(GeneralName.dNSName, "localhost"), new GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                        new GeneralName(GeneralName.dNSName, "intimed.local")}));
                JcaX509ExtensionUtils u = new JcaX509ExtensionUtils();
                b.addExtension(Extension.authorityKeyIdentifier, false, u.createAuthorityKeyIdentifier(root));
                b.addExtension(Extension.subjectKeyIdentifier, false, u.createSubjectKeyIdentifier(kp.getPublic()));
                writePem(crt, sign(b, rootKey));
                storeKey(key, kp.getPrivate(), "server");
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        return new ServerCredentials(readCert(crt), loadKey(key, "server"));
    }

    public record ServerCredentials(X509Certificate cert, PrivateKey key) { }

    /** Server keystore (PKCS#12) for Spring Boot's HTTPS connector; password derived from the master key. */
    public Path serverKeystore() {
        ServerCredentials s = server();
        Path p = dir.resolve("server.p12");
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("intimed", s.key(), keystorePassword(), new java.security.cert.Certificate[]{s.cert(), root});
            try (var out = Files.newOutputStream(p)) { ks.store(out, keystorePassword()); }
            Paths2.restrict(p, false);
            return p;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public char[] keystorePassword() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MasterKey.derive("keystore")).toCharArray();
    }

    /** SSLContext for the device listeners: TLS 1.2/1.3, client certificates must chain to the installation root. */
    public SSLContext serverSslContext() {
        try {
            ServerCredentials s = server();
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("server", s.key(), keystorePassword(), new java.security.cert.Certificate[]{s.cert(), root});
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, keystorePassword());
            KeyStore ts = KeyStore.getInstance("PKCS12");
            ts.load(null, null);
            ts.setCertificateEntry("root", root);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), Hashing.RANDOM);
            return ctx;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Client SSLContext for SDK / gateway (trusts the root, presents the endpoint certificate). */
    public static SSLContext clientSslContext(X509Certificate rootCa, X509Certificate cert, PrivateKey key) {
        return clientSslContext(rootCa, cert == null ? null : new X509Certificate[]{cert}, key);
    }

    /** Client SSLContext presenting the full chain (endpoint certificate + tenant issuing CA). */
    public static SSLContext clientSslContext(X509Certificate rootCa, X509Certificate[] chain, PrivateKey key) {
        try {
            char[] pw = "client".toCharArray();
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            if (chain != null && chain.length > 0) ks.setKeyEntry("client", key, pw, chain);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pw);
            KeyStore ts = KeyStore.getInstance("PKCS12");
            ts.load(null, null);
            ts.setCertificateEntry("root", rootCa);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), Hashing.RANDOM);
            return ctx;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ---------------------------------------------------------------- helpers
    public static KeyPair ecKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), Hashing.RANDOM);
            return g.generateKeyPair();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static BigInteger serial() { return new BigInteger(1, Hashing.randomBytes(16)); }

    private static X509Certificate buildCa(X500Name subject, PublicKey pub, X500Name issuer, PrivateKey issuerKey, Duration validity, int pathLen, X509Certificate issuerCert) {
        try {
            Instant now = Instant.now();
            JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(issuer, serial(), Date.from(now.minusSeconds(60)),
                    Date.from(now.plus(validity)), subject, pub);
            b.addExtension(Extension.basicConstraints, true, new BasicConstraints(pathLen));
            b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            b.addExtension(Extension.subjectKeyIdentifier, false, new JcaX509ExtensionUtils().createSubjectKeyIdentifier(pub));
            if (issuerCert != null) b.addExtension(Extension.authorityKeyIdentifier, false, new JcaX509ExtensionUtils().createAuthorityKeyIdentifier(issuerCert));
            return sign(b, issuerKey);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static X509Certificate sign(JcaX509v3CertificateBuilder b, PrivateKey key) throws Exception {
        ContentSigner signer = new JcaContentSignerBuilder(SIG).build(key);
        X509CertificateHolder h = b.build(signer);
        return new JcaX509CertificateConverter().getCertificate(h);
    }

    private static void storeKey(Path p, PrivateKey k, String purpose) {
        Paths2.writeSecret(p, Aead.encrypt(MasterKey.aes("pki/" + purpose), k.getEncoded(), purpose).getBytes(StandardCharsets.UTF_8));
    }

    private static PrivateKey loadKey(Path p, String purpose) {
        try {
            byte[] der = Aead.decrypt(MasterKey.aes("pki/" + purpose), Files.readString(p).trim(), purpose);
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IOException e) { throw new UncheckedIOException(e); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static X509Certificate readCert(Path p) {
        try { return parseCert(Files.readString(p)); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public static X509Certificate parseCert(String pem) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                    new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalArgumentException("Invalid certificate"); }
    }

    public static void writePem(Path p, Object o) {
        try { Files.writeString(p, toPem(o)); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public static String toPem(Object o) {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(sw)) { w.writeObject(o); } catch (IOException e) { throw new UncheckedIOException(e); }
        return sw.toString();
    }

    public static String fingerprint(X509Certificate c) {
        try { return Hashing.sha256(c.getEncoded()); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public List<X509Certificate> chain(String tenant) { return List.of(tenantCa(tenant).cert(), root); }

    /** Minimal PEM decoder (no external parser on the CSR path). */
    public static final class Pem {
        private Pem() { }
        public static byte[] decode(String pem, String type) {
            String b = pem.replace("-----BEGIN " + type + "-----", "").replace("-----END " + type + "-----", "")
                    .replace("-----BEGIN NEW " + type + "-----", "").replace("-----END NEW " + type + "-----", "").replaceAll("\\s", "");
            return Base64.getDecoder().decode(b);
        }
    }
}
